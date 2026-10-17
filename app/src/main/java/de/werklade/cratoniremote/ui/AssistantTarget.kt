package de.werklade.cratoniremote.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import de.werklade.cratoniremote.ble.CratoniBleManager

/**
 * Which app takes over when the remote's walkie-talkie button is pressed (docs/architektur.md §12).
 *
 * [de.werklade.cratoniremote.action.MediaKeyAssistantExecutor] only sends a long press of the headset key;
 * `MediaSessionService` then starts the hands-free voice search, and which app that is, is a choice of the
 * system — not of this app. The status card shows it because a button that "does nothing" almost always means
 * the choice went somewhere unexpected, and because the first press opens a chooser whose "Immer" is easy to
 * miss.
 */
sealed interface AssistantTarget {

    /** One app answers the hands-free action as the default; [label] is its name as shown by the system. */
    data class App(val label: String) : AssistantTarget

    /**
     * Apps answer the action, but none of them is the default, so the system asks on every press. Recognised
     * from the resolution naming a component that is not among the candidates — that is the resolver activity.
     */
    data object AskedOnPress : AssistantTarget

    /** Nothing answers the hands-free action; the button cannot start an assistant. */
    data object None : AssistantTarget
}

/**
 * An activity that can answer the hands-free action, as plain data: the decision in [assistantTargetFor] stays
 * free of Android types and therefore unit testable (`android.content.ComponentName` would need a device).
 */
internal data class AssistantComponent(val packageName: String, val className: String)

/**
 * What [resolveAssistantTarget] found: the line for the status card plus the package behind it, which
 * [openAssistantSettings] needs for its first step. The package is deliberately not part of
 * [AssistantTarget.App] — the screen shows a name, it has no business knowing a package.
 */
internal data class AssistantResolution(
    val target: AssistantTarget,
    /** The resolved package, `null` unless [target] is an [AssistantTarget.App]. */
    val packageName: String?,
)

/**
 * The decision, pure: which [AssistantTarget] a resolution means.
 *
 * - nothing resolved → [AssistantTarget.None]
 * - [resolved] is one of the [candidates] → [AssistantTarget.App]
 * - [resolved] is not among them → [AssistantTarget.AskedOnPress]. This is the documented chooser case:
 *   `PackageManager.resolveActivity` answers with the resolver activity when no default is set, and that one
 *   never appears in `queryIntentActivities`. Checked that way round on purpose — a check for class names like
 *   `ResolverActivity` would miss One UI, which brings its own.
 * - no candidates at all, yet something resolved → [AssistantTarget.None] and a log line: that combination can
 *   only happen with the `<queries>` entry in the manifest missing or filtered away.
 *
 * @param label the name of the resolved app as the system shows it, see [assistantLabel]; falls back to the
 *   package name when it is blank
 * @param log where the "no candidates" case goes; a no-op in tests
 */
internal fun assistantTargetFor(
    resolved: AssistantComponent?,
    candidates: List<AssistantComponent>,
    label: String?,
    log: (String) -> Unit = {},
): AssistantTarget = when {
    resolved == null -> AssistantTarget.None
    candidates.isEmpty() -> {
        log(
            "no app answers the hands-free action although ${resolved.packageName} resolved for it — " +
                "the <queries> entry in the manifest is missing or filtered",
        )
        AssistantTarget.None
    }
    resolved in candidates -> AssistantTarget.App(label?.trim()?.takeIf { it.isNotEmpty() } ?: resolved.packageName)
    else -> AssistantTarget.AskedOnPress
}

/**
 * The first usable name out of [labels], tidied up; [packageName] if every one of them is `null`, empty or only
 * whitespace. Pure, so the order of the fallbacks is covered by a unit test.
 *
 * A package name is a poor label, but it is the only thing that is always there — and it still tells the user
 * which app answered.
 *
 * The name comes out of another app and is therefore not trusted to be one short line: line breaks, tabs and
 * runs of spaces are collapsed into single spaces (invisible control and format characters included, which could
 * otherwise reorder the text around them), and anything longer than [MAX_LABEL_LENGTH] characters is cut with an
 * ellipsis. The status row limits the height as well, see `RemoteScreen`; this is the half that also shortens
 * what a screen reader reads out.
 */
internal fun assistantLabel(packageName: String, vararg labels: CharSequence?): String =
    labels.asSequence()
        .mapNotNull { it?.toString()?.let(::tidyLabel) }
        .firstOrNull { it.isNotEmpty() }
        ?: packageName

/** One line, no padding, at most [MAX_LABEL_LENGTH] characters; empty if [raw] holds nothing but whitespace. */
private fun tidyLabel(raw: String): String {
    val oneLine = raw.replace(BLANKS, " ").trim()
    if (oneLine.length <= MAX_LABEL_LENGTH) return oneLine
    // Never cut a surrogate pair in half: a lone high surrogate is no character and shows up as a box.
    val keep = if (oneLine[MAX_LABEL_LENGTH - 2].isHighSurrogate()) MAX_LABEL_LENGTH - 2 else MAX_LABEL_LENGTH - 1
    return oneLine.take(keep).trimEnd() + "…"
}

/** Whitespace of every kind plus the invisible control and format characters, e.g. a bidi override. */
private val BLANKS = Regex("[\\s\\p{Z}\\p{Cc}\\p{Cf}]+")

/** Long enough for every real app name, short enough not to take over the status card. */
private const val MAX_LABEL_LENGTH = 60

/**
 * Asks the system which app answers [RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE] right now.
 *
 * Needs the `<queries>` entry in the manifest: from Android 11 on both calls below answer `null`/empty for an
 * action the app has not declared visibility for (no `QUERY_ALL_PACKAGES` here, see the manifest).
 *
 * ## What this does not say
 *
 * - It is a second, independent question to the system, not what the button did. The user can change the choice
 *   at any moment, including while this screen is in the background — hence the refresh on `ON_RESUME`.
 * - It says nothing about `ACTION_ASSIST`, which is what the home-button gesture and "digital assistant app"
 *   use. A phone can have Gemini there and Bixby here.
 * - It does not say whether that app then really listens: it may ask to be unlocked, lack the microphone
 *   permission or need its own setup.
 * - It does not say in which user the app runs. `MediaSessionService` starts the voice search as
 *   `UserHandle.CURRENT`, while both calls below resolve in the user this app runs in. With a work profile or a
 *   Secure Folder in play (user 150 on the test device) the app that actually starts can therefore be a
 *   different one than the name shown here.
 * - Should the resolution land on another profile's forwarding activity, that component is not among the
 *   candidates either, so the line reads "Wird beim Drücken abgefragt" although the press would be forwarded
 *   without anybody being asked.
 *
 * ## Why only the hands-free action
 *
 * AOSP's `MediaSessionService` picks `ACTION_WEB_SEARCH` when the device is unlocked with the screen on and
 * `ACTION_VOICE_SEARCH_HANDS_FREE` otherwise. One UI does not: measured on the S25 Ultra (One UI 8.5,
 * 17.10.2026) the log says `about to use ACTION_VOICE_SEARCH_HANDS_FREE` even unlocked with the screen on, and
 * `ACTION_WEB_SEARCH` has no default app there at all. A second line for the unlocked case would be wrong on
 * exactly the device this app is built for, so there is none.
 *
 * Does package manager work and loads labels — call it off the main thread.
 */
internal fun resolveAssistantTarget(packageManager: PackageManager): AssistantResolution {
    val intent = Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE)
    // MATCH_DEFAULT_ONLY for both: the key press ends in a startActivity, which only ever considers activities
    // with CATEGORY_DEFAULT, and only with this flag does resolveActivity answer with the resolver activity
    // instead of an arbitrary match when no default is set.
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
    val candidates = packageManager.queryIntentActivities(intent, flags).mapNotNull { it.activityInfo?.component() }
    val resolved = packageManager.resolveActivity(intent, flags)
    val resolvedComponent = resolved?.activityInfo?.component()
    // Same log path as the "no candidates" case below, and for the same reason: the line will read "Nicht
    // festgelegt" although the system did answer, which nothing on the screen can explain.
    val warn: (String) -> Unit = { Log.w(TAG, "UI: $it") }
    if (resolved != null && resolvedComponent == null) {
        warn("the resolution for the hands-free action carries no activityInfo — treated as 'nothing answers it'")
    }
    val label = resolved?.let { info ->
        assistantLabel(
            info.activityInfo?.packageName.orEmpty(),
            info.activityInfo?.applicationInfo?.let { packageManager.getApplicationLabel(it) },
            info.loadLabel(packageManager),
        )
    }
    val target = assistantTargetFor(
        resolved = resolvedComponent,
        candidates = candidates,
        label = label,
        log = warn,
    )
    Log.i(TAG, "UI: hands-free assistant is $target (resolved $resolvedComponent, ${candidates.size} candidate(s))")
    return AssistantResolution(
        target = target,
        // Only for an App: the resolver activity's package ('android') is no app to open a page for.
        packageName = resolvedComponent?.packageName?.takeIf { target is AssistantTarget.App },
    )
}

private fun ActivityInfo.component() = AssistantComponent(packageName, name)

/**
 * One of the system pages [openAssistantSettings] tries, as plain data: the order in [assistantSettingsPages]
 * and the chain in [openAssistantPage] stay free of Android types and therefore unit testable (an `Intent`
 * cannot even be read in a JVM test — `getAction` is not mocked).
 */
internal sealed interface AssistantSettingsPage {

    /** The app info page of [packageName]; only a step when an app is known. */
    data class AppInfo(val packageName: String) : AssistantSettingsPage

    /** The system's "default apps" page. */
    data object DefaultApps : AssistantSettingsPage

    /** The voice input settings. */
    data object VoiceInput : AssistantSettingsPage
}

/**
 * The pages to try, closest one first: the app info page only if [assistantPackage] is known, then the two
 * general pages. Pure, see [AssistantSettingsPage].
 */
internal fun assistantSettingsPages(assistantPackage: String?): List<AssistantSettingsPage> = listOfNotNull(
    assistantPackage?.let { AssistantSettingsPage.AppInfo(it) },
    AssistantSettingsPage.DefaultApps,
    AssistantSettingsPage.VoiceInput,
)

/**
 * Tries the pages for [assistantPackage] in order until [open] reports one as opened.
 *
 * @param open opens one page, `false` if it does not exist on this device or refuses to be started
 * @return `false` only if no page at all could be opened; untried pages after the first success stay untried
 */
internal fun openAssistantPage(assistantPackage: String?, open: (AssistantSettingsPage) -> Boolean): Boolean =
    // any() stops at the first true, which is exactly the contract above.
    assistantSettingsPages(assistantPackage).any(open)

/**
 * Opens the system page on which the choice can be changed, trying the closest one first.
 *
 * 1. with an app known: its app info page ([Settings.ACTION_APPLICATION_DETAILS_SETTINGS]) — the only place that
 *    clears an "Immer" set in the chooser ("Standardmäßig öffnen")
 * 2. the default apps page ([Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS])
 * 3. the voice input settings ([Settings.ACTION_VOICE_INPUT_SETTINGS])
 *
 * No `resolveActivity` test in front of any of them: package visibility would falsify it, while `startActivity`
 * on another app's activity works regardless of it. So each step simply tries, and both
 * [ActivityNotFoundException] ("this page does not exist here") and [SecurityException] ("it exists but refuses
 * to be started by us", possible for a manufacturer's settings activity) count as "take the next step" — the
 * reason every step is in its own `try`.
 *
 * A `true` only says that a page opened, not that the choice can be changed there: step 1 is the page that holds
 * it, steps 2 and 3 are the closest system pages left.
 *
 * [context] is an Activity context (see `MainActivity`); should it ever not be, the missing
 * `FLAG_ACTIVITY_NEW_TASK` is added rather than letting the platform throw.
 *
 * @param start starts one page's intent, `false` if it could not be started; the default is the real
 *   `startActivity` and the only thing in here that needs a device
 * @return `false` only if none of the three could be opened, so the caller can say so instead of leaving a dead
 *   button behind
 */
internal fun openAssistantSettings(
    context: Context,
    assistantPackage: String?,
    start: (Intent) -> Boolean = { intent -> start(context, intent, intent.pageName()) },
): Boolean {
    val opened = openAssistantPage(assistantPackage) { page -> start(page.intent()) }
    if (!opened) Log.w(TAG, "UI: no system page for the assistant choice could be opened")
    return opened
}

private fun AssistantSettingsPage.intent(): Intent = when (this) {
    is AssistantSettingsPage.AppInfo -> Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
    AssistantSettingsPage.DefaultApps -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    AssistantSettingsPage.VoiceInput -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}

/** What the log calls this page; the default [start] has nothing but the intent to go by. */
private fun Intent.pageName(): String = when (action) {
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> "app info of ${data?.schemeSpecificPart}"
    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS -> "default apps settings"
    Settings.ACTION_VOICE_INPUT_SETTINGS -> "voice input settings"
    else -> action.orEmpty()
}

private fun start(context: Context, intent: Intent, what: String): Boolean = try {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    Log.i(TAG, "UI: $what not available on this device: $e")
    false
} catch (e: SecurityException) {
    // A manufacturer's settings activity may exist but refuse to be started by another app (not exported, or
    // permission-guarded). That is the same answer as "not available" for us: try the next step instead of
    // letting the button crash the app.
    Log.i(TAG, "UI: $what refused to start: $e")
    false
}

private const val TAG = CratoniBleManager.TAG
