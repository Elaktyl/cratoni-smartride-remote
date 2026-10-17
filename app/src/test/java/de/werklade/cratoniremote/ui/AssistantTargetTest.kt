package de.werklade.cratoniremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of `AssistantTarget.kt`: the decision [assistantTargetFor], the label fallback chain
 * [assistantLabel] and the order of the settings pages [openAssistantPage]. `resolveAssistantTarget` and the
 * `startActivity` behind `openAssistantSettings` need a real `PackageManager` and a real Activity context and
 * stay for the device test — an `Intent` cannot even be read in a JVM test ("getAction not mocked").
 */
class AssistantTargetTest {

    private val google = AssistantComponent(
        packageName = "com.google.android.googlequicksearchbox",
        className = "com.google.android.voicesearch.handsfree.HandsFreeActivity",
    )

    private val bixby = AssistantComponent(
        packageName = "com.samsung.android.bixby.agent",
        className = "com.samsung.android.bixby.agent.handsfree.HandsFreeActivity",
    )

    /** The system's resolver activity: never among the candidates, whatever its class is called. */
    private val resolver = AssistantComponent("android", "com.android.internal.app.ResolverActivity")

    @Test
    fun `nothing resolved means no assistant`() {
        assertEquals(
            AssistantTarget.None,
            assistantTargetFor(resolved = null, candidates = listOf(google), label = "Google"),
        )
    }

    @Test
    fun `nothing resolved and no candidates means no assistant`() {
        assertEquals(
            AssistantTarget.None,
            assistantTargetFor(resolved = null, candidates = emptyList(), label = null),
        )
    }

    @Test
    fun `a resolved candidate is the app in charge`() {
        assertEquals(
            AssistantTarget.App("Google"),
            assistantTargetFor(resolved = google, candidates = listOf(google, bixby), label = "Google"),
        )
    }

    /** The measured case on the test device: one candidate, and it is the default. */
    @Test
    fun `a single candidate that resolves is the app in charge`() {
        assertEquals(
            AssistantTarget.App("Google"),
            assistantTargetFor(resolved = google, candidates = listOf(google), label = "Google"),
        )
    }

    /**
     * No default set: `resolveActivity` answers with the resolver activity, which `queryIntentActivities` never
     * lists. Recognised from that absence, not from the class name.
     */
    @Test
    fun `a resolution outside the candidates means the system asks on every press`() {
        assertEquals(
            AssistantTarget.AskedOnPress,
            assistantTargetFor(resolved = resolver, candidates = listOf(google, bixby), label = "Google"),
        )
    }

    /** A resolver activity of the manufacturer, with a name this code deliberately does not know. */
    @Test
    fun `an unknown resolver class is treated the same way`() {
        val oneUiResolver = AssistantComponent("android", "com.samsung.android.internal.app.SemResolverActivity")
        assertEquals(
            AssistantTarget.AskedOnPress,
            assistantTargetFor(resolved = oneUiResolver, candidates = listOf(google), label = null),
        )
    }

    /** Same package, other activity: not the candidate, so it is the chooser case as well. */
    @Test
    fun `another activity of a candidate package is not that candidate`() {
        val otherActivity = google.copy(className = "com.google.android.voicesearch.VoiceSearchActivity")
        assertEquals(
            AssistantTarget.AskedOnPress,
            assistantTargetFor(resolved = otherActivity, candidates = listOf(google), label = "Google"),
        )
    }

    @Test
    fun `no candidates at all means no assistant and is logged`() {
        val logged = mutableListOf<String>()
        val target = assistantTargetFor(
            resolved = google,
            candidates = emptyList(),
            label = "Google",
            log = { logged.add(it) },
        )
        assertEquals(AssistantTarget.None, target)
        assertEquals(1, logged.size)
        assertTrue("the log line should name the package: ${logged.first()}", logged.first().contains(google.packageName))
    }

    @Test
    fun `the other cases log nothing`() {
        val logged = mutableListOf<String>()
        val log: (String) -> Unit = { logged.add(it) }
        assistantTargetFor(resolved = null, candidates = emptyList(), label = null, log = log)
        assistantTargetFor(resolved = google, candidates = listOf(google), label = "Google", log = log)
        assistantTargetFor(resolved = resolver, candidates = listOf(google), label = "Google", log = log)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `a missing label falls back to the package name`() {
        assertEquals(
            AssistantTarget.App(google.packageName),
            assistantTargetFor(resolved = google, candidates = listOf(google), label = null),
        )
    }

    @Test
    fun `a blank label falls back to the package name`() {
        assertEquals(
            AssistantTarget.App(google.packageName),
            assistantTargetFor(resolved = google, candidates = listOf(google), label = "   "),
        )
    }

    @Test
    fun `a padded label is shown without its padding`() {
        assertEquals(
            AssistantTarget.App("Google"),
            assistantTargetFor(resolved = google, candidates = listOf(google), label = "  Google  "),
        )
    }

    // ---- assistantLabel: the fallback chain application label -> resolve info label -> package name ----

    @Test
    fun `the application label wins`() {
        assertEquals("Google", assistantLabel("com.google.android.googlequicksearchbox", "Google", "Hands-free"))
    }

    @Test
    fun `without an application label the resolve info label is used`() {
        assertEquals("Hands-free", assistantLabel("com.google.android.googlequicksearchbox", null, "Hands-free"))
    }

    @Test
    fun `an empty or blank label is skipped`() {
        assertEquals("Hands-free", assistantLabel("some.package", "", "Hands-free"))
        assertEquals("Hands-free", assistantLabel("some.package", "  ", "Hands-free"))
        assertEquals("Hands-free", assistantLabel("some.package", "\t\n", "Hands-free"))
    }

    @Test
    fun `without any usable label the package name is the label`() {
        assertEquals("some.package", assistantLabel("some.package", null, null))
        assertEquals("some.package", assistantLabel("some.package", "", " "))
        assertEquals("some.package", assistantLabel("some.package"))
    }

    @Test
    fun `a label is trimmed`() {
        assertEquals("Google", assistantLabel("some.package", " Google\n"))
    }

    /** `CharSequence`, not `String`: this is what `getApplicationLabel` and `loadLabel` return. */
    @Test
    fun `a label that is no String is accepted`() {
        assertEquals("Google", assistantLabel("some.package", StringBuilder(" Google ")))
    }

    // ---- assistantLabel: a name out of another app is not trusted to be one short line ----

    @Test
    fun `line breaks and tabs become single spaces`() {
        assertEquals("Voice Assistant", assistantLabel("some.package", "Voice\nAssistant"))
        assertEquals("Voice Assistant", assistantLabel("some.package", "Voice\tAssistant"))
        assertEquals("a b c", assistantLabel("some.package", "a\r\nb c"))
    }

    @Test
    fun `runs of whitespace are collapsed`() {
        assertEquals("Voice Assistant", assistantLabel("some.package", "  Voice   \n\t  Assistant  "))
    }

    /** Invisible format characters can reorder the text around them, so they go the same way as whitespace. */
    @Test
    fun `invisible control and format characters become spaces`() {
        assertEquals("Google Assistant", assistantLabel("some.package", "Google‮Assistant"))
        assertEquals("Google", assistantLabel("some.package", "\u0000Google\u0007"))
    }

    @Test
    fun `a label of nothing but whitespace and control characters is skipped`() {
        assertEquals("some.package", assistantLabel("some.package", "‮ \n\t"))
    }

    @Test
    fun `a label of exactly the maximum length is kept whole`() {
        val label = "A".repeat(60)
        assertEquals(label, assistantLabel("some.package", label))
    }

    @Test
    fun `a longer label is cut with an ellipsis`() {
        val shortened = assistantLabel("some.package", "B".repeat(200))
        assertEquals(60, shortened.length)
        assertEquals("B".repeat(59) + "…", shortened)
    }

    /** The cut must not leave half a surrogate pair behind, which would render as a box. */
    @Test
    fun `a cut does not split a surrogate pair`() {
        val emoji = "😀" // U+1F600
        val shortened = assistantLabel("some.package", "C".repeat(58) + emoji + emoji)
        assertEquals("C".repeat(58) + "…", shortened)
        assertTrue("no lone surrogate may be left: $shortened", shortened.none { it.isSurrogate() })
    }

    @Test
    fun `a cut label carries no trailing space in front of the ellipsis`() {
        val shortened = assistantLabel("some.package", "D".repeat(58) + " EEEE")
        assertEquals("D".repeat(58) + "…", shortened)
    }

    /** Shortening happens per candidate, so a long first label is still preferred over the next one. */
    @Test
    fun `a long application label still wins over the resolve info label`() {
        assertEquals("F".repeat(59) + "…", assistantLabel("some.package", "F".repeat(80), "Hands-free"))
    }

    // ---- openAssistantPage: app info (only with a package) -> default apps -> voice input ----

    private val appInfo = AssistantSettingsPage.AppInfo(google.packageName)
    private val defaultApps = AssistantSettingsPage.DefaultApps
    private val voiceInput = AssistantSettingsPage.VoiceInput

    @Test
    fun `without a known package the app info page is skipped`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        val opened = openAssistantPage(assistantPackage = null) { tried.add(it); false }
        assertFalse(opened)
        assertEquals(listOf(defaultApps, voiceInput), tried)
    }

    @Test
    fun `with a known package the app info page comes first`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        val opened = openAssistantPage(assistantPackage = google.packageName) { tried.add(it); false }
        assertFalse(opened)
        assertEquals(listOf(appInfo, defaultApps, voiceInput), tried)
    }

    @Test
    fun `false comes only after all three pages failed`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        assertFalse(openAssistantPage(google.packageName) { tried.add(it); false })
        assertEquals(3, tried.size)
    }

    @Test
    fun `the first page that opens ends the chain`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        val opened = openAssistantPage(google.packageName) { tried.add(it); true }
        assertTrue(opened)
        assertEquals(listOf(appInfo), tried)
    }

    @Test
    fun `a failing app info page falls through to the default apps page`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        val opened = openAssistantPage(google.packageName) { page ->
            tried.add(page)
            page != appInfo
        }
        assertTrue(opened)
        assertEquals(listOf(appInfo, defaultApps), tried)
    }

    @Test
    fun `the voice input page is the last resort`() {
        val tried = mutableListOf<AssistantSettingsPage>()
        val opened = openAssistantPage(google.packageName) { page ->
            tried.add(page)
            page == voiceInput
        }
        assertTrue(opened)
        assertEquals(listOf(appInfo, defaultApps, voiceInput), tried)
    }
}
