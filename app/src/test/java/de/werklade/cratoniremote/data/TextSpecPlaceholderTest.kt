package de.werklade.cratoniremote.data

import de.werklade.cratoniremote.R
import de.werklade.cratoniremote.ble.BleConnectionState
import de.werklade.cratoniremote.ble.FailureReason
import de.werklade.cratoniremote.ble.GattStep
import de.werklade.cratoniremote.ble.RemoteBattery
import de.werklade.cratoniremote.protocol.RemoteCommand
import de.werklade.cratoniremote.service.NotificationText
import java.io.File
import java.lang.reflect.Modifier
import java.time.ZoneId
import java.util.IllegalFormatException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The seam between the [TextSpec]s and the real format strings: every spec is checked against the wording in
 * `res/values/strings.xml` *and* `res/values-de/strings.xml`.
 *
 * Why this exists: a spec carries its arguments, the string resource carries the `%1$s` positions they are
 * formatted into, and nothing in the compiler connects the two. A translation that gains one placeholder makes
 * `Resources.getString` throw an [IllegalFormatException] at runtime — and the one place that formats the
 * notification line sits in a flow collector, where that exception kills the collector and the permanently
 * visible notification stops being updated, silently. One wrong `%` in a translation is enough, and no test
 * so far would have noticed.
 *
 * The chosen way is the direct one: parse both string files (plain JDK XML, no new dependency), walk every
 * branch that produces a spec, and for each of them
 * - check that the highest position used in the format string is exactly the number of arguments the spec
 *   carries, and that no position in between is skipped,
 * - check that a `%n$d` position really gets a number and a `%n$s` a text, and
 * - format the whole thing for real, nested specs and all, exactly the way `Resources.getString` would.
 *
 * The last point is what would have caught the failure on its own; the first two also catch the harmless-looking
 * opposite, a translation that *drops* a placeholder, which would quietly swallow the measurement or the time.
 * Both languages are checked separately, because a format string lives in each of them.
 *
 * Deliberately not a device test: nothing here needs Android. The ids come from the generated `R` class, the
 * texts from the files in the source tree.
 */
class TextSpecPlaceholderTest {

    // The values behind the arguments do not matter here, only how many there are and of what kind.

    private val zone = ZoneId.of("Europe/Berlin")

    private val at = 1_789_209_296_000L

    private val shortlyAfter = at + 60_000L

    /** Past [RemoteStatusText.DATE_SHOWN_AFTER_MS], so the timestamp branch with the date is covered too. */
    private val nextDay = at + 20 * 60 * 60 * 1000L

    private val battery = RemoteBattery(percent = 80, millivolts = 3_906, atEpochMs = at)

    private val pending = PendingConfirmation(
        associationId = 7,
        label = "Remote control-3D2C",
        deadlineElapsedMs = 12_345_678L + CONFIRMATION_TIMEOUT_MS,
    )

    private val failureReasons: List<FailureReason> = listOf(
        FailureReason.BluetoothAdapterMissing,
        FailureReason.BluetoothDisabled,
        FailureReason.MissingPermission,
        FailureReason.ConnectionLost(8),
        FailureReason.DeviceUnsuitable("service FFF0"),
    ) + GattStep.entries.flatMap { step ->
        listOf(
            FailureReason.GattError(step, 133),
            FailureReason.OperationNotStarted(step),
            FailureReason.Timeout(step),
        )
    }

    private val connectionStates: List<BleConnectionState> = listOf(
        BleConnectionState.Disconnected,
        BleConnectionState.Connecting,
        BleConnectionState.DiscoveringServices,
        BleConnectionState.EnablingNotifications,
        BleConnectionState.Connected,
        BleConnectionState.Reconnecting(2, 3, 5_000L, FailureReason.ConnectionLost(8)),
    ) + failureReasons.map { BleConnectionState.Failed(it) }

    /**
     * Every branch that produces a spec, labelled for the failure message.
     *
     * @param locale the language the notification fields format their numbers and times for; it decides
     *   nothing about the placeholders, but it is what the real code passes
     */
    private fun specs(locale: Locale): List<Pair<String, TextSpec>> = buildList {
        add("remoteLine(not set up)" to StatusText.remoteLine(remoteConfigured = false, state = RemoteState()))
        add(
            "remoteLine(confirming)" to
                StatusText.remoteLine(remoteConfigured = true, state = RemoteState(pendingConfirmation = pending)),
        )
        add(
            "remoteLine(waiting for Bluetooth)" to StatusText.remoteLine(
                remoteConfigured = true,
                state = RemoteState(serviceRunning = true, waitingForBluetooth = true),
            ),
        )
        add(
            "remoteLine(waiting for Bluetooth, notification wording)" to StatusText.remoteLine(
                remoteConfigured = true,
                state = RemoteState(serviceRunning = true, waitingForBluetooth = true),
                waitingForBluetooth = R.string.notification_waiting_for_bluetooth,
            ),
        )
        add(
            "remoteLine(out of range)" to
                StatusText.remoteLine(remoteConfigured = true, state = RemoteState()),
        )
        connectionStates.forEach { connection ->
            add(
                "remoteLine($connection)" to StatusText.remoteLine(
                    remoteConfigured = true,
                    state = RemoteState(serviceRunning = true, connection = connection),
                ),
            )
            add("connection($connection)" to StatusText.connection(connection))
        }
        failureReasons.forEach { add("failure($it)" to StatusText.failure(it)) }
        GattStep.entries.forEach { add("gattStep($it)" to StatusText.gattStep(it)) }
        RemoteCommand.entries.forEach { add("command($it)" to StatusText.command(it)) }
        // The notification line: the state alone, and with each of the two fields that only exist sometimes.
        // The timestamp is taken once from the same day and once from the day before, because that is a
        // branch of its own in RemoteStatusText.
        listOf(
            "state only" to RemoteState(serviceRunning = true, connection = BleConnectionState.Connected),
            "battery" to RemoteState(
                serviceRunning = true,
                connection = BleConnectionState.Connected,
                battery = battery,
            ),
            "battery without voltage" to RemoteState(
                serviceRunning = true,
                connection = BleConnectionState.Connected,
                battery = RemoteBattery(percent = 80, millivolts = null, atEpochMs = at),
            ),
            "last command" to RemoteState(
                serviceRunning = true,
                connection = BleConnectionState.Connected,
                lastCommand = LastCommand(RemoteCommand.NEXT, at),
            ),
        ).forEach { (label, state) ->
            listOf("same day" to shortlyAfter, "with date" to nextDay).forEach { (age, now) ->
                NotificationText.fields(state, now, zone, locale).forEachIndexed { index, spec ->
                    add("notification field $index ($label, $age)" to spec)
                }
            }
        }
    }

    // Checks

    @Test
    fun `every text gets exactly as many arguments as its format string asks for`() {
        forEachLanguage { language, strings, locale ->
            eachSpecAndNested(locale) { label, spec ->
                val name = resourceName(spec.id)
                val text = requireText(language, label, name, strings)
                val positions = placeholders(language, label, name, text).map { it.position }
                assertEquals(
                    "$language/$name ($label) is given ${spec.args.size} argument(s), but its text uses " +
                        "${positions.maxOrNull() ?: 0} position(s): '$text'. A translation must carry the same " +
                        "placeholders as the base language, no more and no fewer.",
                    spec.args.size,
                    positions.maxOrNull() ?: 0,
                )
                assertEquals(
                    "$language/$name ($label) skips a position: '$text'",
                    (1..spec.args.size).toSet(),
                    positions.toSet(),
                )
            }
        }
    }

    @Test
    fun `a position for a number gets a number and one for a text gets a text`() {
        forEachLanguage { language, strings, locale ->
            eachSpecAndNested(locale) { label, spec ->
                val name = resourceName(spec.id)
                val text = requireText(language, label, name, strings)
                placeholders(language, label, name, text).forEach { placeholder ->
                    val argument = spec.args[placeholder.position - 1]
                    val where = "$language/$name ($label), %${placeholder.position}\$${placeholder.conversion}"
                    when (placeholder.conversion) {
                        'd' -> assertTrue(
                            "$where asks for a number but is given ${argument.javaClass.simpleName} " +
                                "'$argument'",
                            argument is Int || argument is Long,
                        )
                        's' -> assertTrue(
                            "$where asks for a text but is given ${argument.javaClass.simpleName} '$argument'",
                            argument is String || argument is TextSpec,
                        )
                        else -> fail("$where uses a conversion this app does not use anywhere; is it intended?")
                    }
                }
            }
        }
    }

    /** What would actually have thrown: the whole spec, nested specs and all, formatted as the platform does. */
    @Test
    fun `every text can really be formatted with the arguments it carries`() {
        forEachLanguage { language, strings, locale ->
            specs(locale).forEach { (label, spec) ->
                val resolved = try {
                    spec.resolve { id, args ->
                        val name = resourceName(id)
                        val text = requireText(language, label, name, strings)
                        // Exactly what Resources.getString does — and what the two callers do: without
                        // arguments the text is taken as it is, so a per cent sign in a text that has no
                        // placeholder cannot be mistaken for one.
                        if (args.isEmpty()) text else String.format(locale, text, *args)
                    }
                } catch (e: IllegalFormatException) {
                    throw AssertionError(
                        "$language: '$label' cannot be formatted, so Resources.getString would throw this " +
                            "at runtime — in the notification collector that stops the notification from " +
                            "ever being updated again",
                        e,
                    )
                }
                assertTrue("$language: '$label' resolves to nothing", resolved.isNotBlank())
            }
        }
    }

    /**
     * Guards the other direction: a text that carries no argument may not be marked `formatted="false"` and a
     * text that does may not be either — the attribute turns `%` into plain text for AAPT, and the string is
     * then only safe to read without arguments.
     */
    @Test
    fun `no text that carries arguments is marked as unformatted`() {
        forEachLanguage { language, strings, locale ->
            eachSpecAndNested(locale) { label, spec ->
                if (spec.args.isEmpty()) return@eachSpecAndNested
                val name = resourceName(spec.id)
                assertTrue(
                    "$language/$name ($label) carries ${spec.args.size} argument(s) but is declared " +
                        "formatted=\"false\"",
                    strings[name]?.unformatted != true,
                )
            }
        }
    }

    /**
     * Across the two languages, independent of the specs: the same key has to use the same placeholders
     * everywhere. Covers the texts that are formatted directly in the UI as well, which no spec reaches.
     */
    @Test
    fun `both languages use the same placeholders for the same key`() {
        val base = readStrings("values")
        val german = readStrings("values-de")
        base.forEach { (name, resource) ->
            val translation = german[name] ?: return@forEach
            assertEquals(
                "values-de/$name uses other placeholders than the base language: " +
                    "'${resource.text}' / '${translation.text}'",
                placeholders("values", name, name, resource.text).toSet(),
                placeholders("values-de", name, name, translation.text).toSet(),
            )
        }
    }

    /** Would the two files be missing or empty, every test above would pass without checking anything. */
    @Test
    fun `both string files were found and hold the keys the specs use`() {
        listOf("values", "values-de").forEach { language ->
            val strings = readStrings(language)
            assertTrue(
                "$language/strings.xml holds only ${strings.size} strings; was the right file read?",
                strings.size > 50,
            )
            eachSpecAndNested(Locale.US) { label, spec ->
                val name = resourceName(spec.id)
                assertTrue("$language/strings.xml has no '$name' ($label)", strings.containsKey(name))
            }
        }
    }

    // Helpers

    private fun forEachLanguage(block: (String, Map<String, Resource>, Locale) -> Unit) {
        block("values", readStrings("values"), Locale.US)
        block("values-de", readStrings("values-de"), Locale.GERMANY)
    }

    /** Every spec, and every spec among its arguments, because those are format strings in their own right. */
    private fun eachSpecAndNested(locale: Locale, block: (String, TextSpec) -> Unit) {
        fun walk(label: String, spec: TextSpec) {
            block(label, spec)
            spec.args.filterIsInstance<TextSpec>().forEach { nested -> walk("$label → nested", nested) }
        }
        specs(locale).forEach { (label, spec) -> walk(label, spec) }
    }

    private fun requireText(
        language: String,
        label: String,
        name: String,
        strings: Map<String, Resource>,
    ): String = strings[name]?.text ?: throw AssertionError("$language/strings.xml has no '$name' ($label)")

    private data class Placeholder(val position: Int, val conversion: Char)

    /**
     * The positions a format string uses. `%%` is a per cent sign and no position; a placeholder without a
     * position (`%s`) is refused, because a translation has to be free to reorder the arguments.
     */
    private fun placeholders(
        language: String,
        label: String,
        name: String,
        text: String,
    ): List<Placeholder> = PLACEHOLDER.findAll(text).mapNotNull { match ->
        if (match.value == "%%") {
            null
        } else {
            val position = match.groupValues[1]
            assertTrue(
                "$language/$name ($label) uses '${match.value}' without a position; every argument has to " +
                    "be addressed as %1\$s, %2\$s, … so a translation can put them in another order",
                position.isNotEmpty(),
            )
            Placeholder(position.toInt(), match.groupValues[2].single())
        }
    }.toList()

    /** One `<string>` of a `strings.xml`. */
    private data class Resource(val text: String, val unformatted: Boolean)

    private fun readStrings(language: String): Map<String, Resource> {
        val file = File(RESOURCE_DIRECTORY, "$language/strings.xml")
        assertTrue("${file.absolutePath} does not exist", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name.isEmpty()) {
                null
            } else {
                name to Resource(element.textContent, element.getAttribute("formatted") == "false")
            }
        }.toMap()
    }

    private fun resourceName(id: Int): String =
        RESOURCE_NAMES[id] ?: throw AssertionError("No R.string field carries the id $id")

    private companion object {

        /** `%1$s`, `%2$d`, …, a per cent sign (`%%`), and a placeholder without a position, which is refused. */
        val PLACEHOLDER = Regex("""%%|%(\d+)\$(\w)|%(\w)""")

        /** Id to name, the other way round than the generated `R.string`; unit tests see the real ids. */
        val RESOURCE_NAMES: Map<Int, String> = R.string::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

        /**
         * `src/main/res` of the app module. Searched for instead of hard-coded, because the working directory
         * of a unit test run is the module directory with Gradle and the repository root in some IDE runs.
         */
        val RESOURCE_DIRECTORY: File = run {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                val current: File = directory
                listOf(File(current, "src/main/res"), File(current, "app/src/main/res")).forEach { candidate ->
                    if (File(candidate, "values/strings.xml").isFile) return@run candidate
                }
                directory = current.parentFile
            }
            throw AssertionError("No src/main/res with values/strings.xml found from ${File("").absolutePath}")
        }
    }
}
