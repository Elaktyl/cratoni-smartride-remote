# CRATONI SMARTRIDE 1.2 Remote – Android app

Deutsche Fassung: [README.de.md](README.de.md)

> [!WARNING]
> **No fall detection, no emergency call.** Together with the manufacturer's app, the helmet offers accident/fall detection that sends an SMS to stored contacts in an emergency. **This app does not support that.** It does not evaluate fall events, does not send SMS messages and does not place emergency calls.
>
> This is not just a theoretical difference: the remote control accepts only **one** Bluetooth connection at a time. While this app is connected, the manufacturer's app cannot use the remote. If you rely on fall detection, do **not** use this app in place of the manufacturer's app.

An independent Android app for the Bluetooth LE remote control of the CRATONI SMARTRIDE 1.2 bicycle helmet. It replaces the manufacturer's app for day-to-day use: the app connects to the remote on its own and carries out its button presses system-wide, including with the UI closed and the screen locked. The buttons control media playback, calls, volume and the voice assistant, and they switch the turn signals and rear light on the helmet.

## Liability and independence

This is a private, independent project. It has no connection to the helmet's manufacturer, and the manufacturer neither commissioned, reviewed nor approved it. Product and brand names are used only to identify the device the app works with; they belong to their respective owners.

- The software is provided without warranty of any kind. Use is at your own risk.
- **Road traffic:** The app can switch the turn signals and rear light on the helmet. The lighting function is not a certified safety system. The helmet does not acknowledge a switching command; the app tracks the light state itself and cannot tell whether a command arrived or whether a light is actually on. Anyone who relies on it in traffic does so at their own risk. When in doubt, use hand signals and check the lights on the helmet.
- The communication with the remote and the helmet was determined by observing the author's own devices (interoperability), not from manufacturer documents. No firmware was modified. The app writes neither to the remote's firmware update channel (OTA) nor to its other writable characteristics. To the helmet it sends only the observed light commands.
- While the app is connected to the remote, the manufacturer's app cannot use it at the same time: the remote accepts only one connection at a time.

## Screens

| Connected | Quick reference | Not set up | Confirmation during setup |
|---|---|---|---|
| ![Status card showing connection, battery level, helmet, assistant and last command](docs/screenshots/verbunden.png) | ![Card "Kurzanleitung" (Quick reference) listing every button with what a short and a long press do](docs/screenshots/kurzanleitung.png) | ![Status card without a configured remote, with a "Einrichten" (Set up) button](docs/screenshots/nicht-eingerichtet.png) | ![Card "Remote bestätigen" (Confirm remote) with device name, remaining time and a cancel button](docs/screenshots/bestaetigung.png) |

The device name in the fourth image is an example; it was replaced for publication. The third image is older than the assistant row and does not show it.

The screenshots show the German interface; on a device set to English the app shows the English wording.

## Features

- Button mapping of the remote:
  - Play/pause (sent as a media key to the active media app)
  - The two volume keys are double keys. A short press changes the volume; a long press skips to the next track (volume up key) or the previous track (volume down key), sent as media keys to the active media app. The remote itself decides what counts as a short or long press and sends a separate packet for each; the app does not detect press length and cannot change this assignment.
  - Volume (short press): the volume follows the audio mode. During a phone call or other voice connection it changes the call volume, otherwise the media volume.
  - Call button: answers a ringing call or ends an ongoing one
  - Walkie-talkie button: starts the voice assistant
  - Left and right turn signals on the helmet (on/off)
  - Rear light on the helmet with three states (off, steady, blinking)
- Battery level display for the remote (percent and cell voltage), with warnings at 20 %, 10 % and 0 %.
- A foreground service that runs only while the remote is in range, with automatic reconnection. Its permanent notification shows the connection state, battery level and last command in the same words as the app, in the app's language.
- Setup through a device list, where the chosen remote is confirmed with a button press.
- Button legend in the app: a permanently visible card at the bottom of the main screen ("What the buttons do", in German "Was die Tasten tun") that explains this mapping on the device itself, including the short and long press of the volume keys and two footnotes (the light buttons only work with the helmet connected; the call button has no effect for WhatsApp calls). It stays visible when no remote is set up and is hidden while a confirmation is running during setup.
- Language: the app follows the system language (see Requirements).

## Requirements

> [!NOTE]
> **Language:** The app follows the system language. English and German are shipped; every other system language gets English. The language can also be set per app in the Android system settings (Settings > Apps > CRATONI Remote > Language; the exact path may differ by vendor). The screenshots in this README still show the German interface. Labels of the UI are quoted in German in this document, with an English gloss where needed; on a device set to English, the app shows the English wording.

To use the app:

- **Android 16 (API 36) or newer is mandatory** (`minSdk = 36`). The app cannot be installed on older devices.
- **It has been tested only on a Samsung Galaxy S25 Ultra running Android 16 (One UI 8.5).** Other devices, vendor skins and Android versions are untested.
- The app contains no native code of its own (no C/C++ sources, no NDK configuration), so there is no restriction to particular CPU architectures.
- The device needs Bluetooth Low Energy and companion device support (both declared as required in the manifest).
- A remote control and a CRATONI SMARTRIDE 1.2 helmet.
- When installing outside the Play Store, installing from unknown sources must be allowed for the installing app (Android settings).
- An update must be signed with the same key as the installed version. Otherwise Android refuses the update, and the installed app has to be uninstalled first, which discards the pairing and the granted permissions.

To build the app: all values are taken from `app/build.gradle.kts` and `gradle/libs.versions.toml` unless noted otherwise.

| Component | Value | Source |
|---|---|---|
| Android on the device | Android 16 (API 36) or newer | `minSdk = 36` |
| `targetSdk` / `compileSdk` | 36 / 37 | `app/build.gradle.kts` |
| JDK | 17 (source and target compatibility `VERSION_17`) | `app/build.gradle.kts` |
| Android Gradle Plugin | 9.4.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Gradle | 9.7.1 (via the wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| Compose BOM | 2026.09.00 | `gradle/libs.versions.toml` |

- Android SDK with platform 37 (for `compileSdk = 37`). The SDK path is set in the local file `local.properties`, which is not part of the repository.

## Building and installing

Prebuilt version: a signed build (v1.0.0) is available under "Releases" in the GitHub repository. Alternatively, build the app yourself (see below). A build you sign yourself carries a different signature, so a version from "Releases" cannot be updated in place with it, and the other way round.

The release v1.0.0 predates three features described in this document: following the system language (v1.0.0 is German only), the translatable notification, and the button legend. They are only in builds made from the current source.

Target platform: development machine with JDK 17 and the Android SDK; Android 16 on the device. On Windows (PowerShell), use `.\gradlew.bat` instead of `./gradlew`.

Build the debug APK:

```bash
./gradlew assembleDebug
```

Build the release APK (signed only if `keystore.properties` is present, see notes):

```bash
./gradlew assembleRelease
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

Run lint:

```bash
./gradlew lintDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`, the release APK in `app/build/outputs/apk/release/`. To install on a device connected with USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On devices with several user profiles (for example Secure Folder), add `--user 0` if necessary.

Notes:

- The debug build is signed with the automatically generated debug key. The release build reads its signing data from `keystore.properties` in the project root (template: `keystore.properties.example`). If the file is missing or incomplete, the build still runs but the release artifact is left unsigned; it deliberately does not fall back to the debug key. Key files (`*.jks`, `*.keystore`) and `keystore.properties` are excluded in `.gitignore` and are not in the repository. Without the key and its passwords, a published version can no longer be updated.
- The debug build additionally contains a debug trigger for commands via `adb`; it is intended for development only.
- **Uninstalling removes the pairing and the granted permissions.** A new setup is required afterwards. An update with `adb install -r` keeps both.

## Setup in the app

Purpose: connect the app to exactly one remote and confirm that choice.

Prerequisites:

- The helmet is paired in the Android Bluetooth settings. The app looks for it among the paired devices by the name prefix `SMARTRIDE` and connects only if exactly one such device is paired.
- Bluetooth is on, and the remote is switched on and nearby.
- The location toggle in the system settings is on during setup (a requirement of the companion device dialog). The app itself does not request the location permission.

Steps:

1. Open the app and use "Berechtigungen erteilen" (Grant permissions) to allow Bluetooth access, notifications and "Anrufe annehmen" (Answer calls); see the Permissions section.
2. Tap "Einrichten" (Set up). The system shows a list of all remotes in range (names of the form `Remote control-XXXX`).
3. Choose your own remote. Several remotes share the same name pattern, so the list alone does not identify yours reliably.
4. The app shows "Remote bestätigen" (Confirm remote) with a 30-second countdown. Within that time, press any button on the chosen remote.
5. If the button press arrives over that remote's connection, the choice counts as confirmed and the pairing stays in place.

**If nothing is pressed** (or "Abbrechen" (Cancel) is chosen, or Bluetooth is switched off), the app withdraws the pairing and reports it. A button press on a different remote does not confirm the choice unless it arrives over the connection of the chosen remote. Until confirmation, the app executes no button commands.

Verification: on the main screen, "Fernbedienung" (Remote) shows "Verbunden" (Connected), "Letzter Befehl" (Last command) shows the most recently pressed command, and the battery field shows a value. When the remote is switched off, the service ends; when it is switched on again, the system connects the app on its own.

Rollback: "Remote vergessen" (Forget remote) removes the pairing, stops the service and deletes the stored confirmation. A new setup is required afterwards.

Samsung devices: the app shows a hint to set the app's battery optimization to "Nie im Standby" (never in standby). Whether the companion device feature protects against battery optimization on One UI is not established.

## Checking a build on a device

Purpose: go through a built or downloaded version on your own device before relying on it. The items come from the device tests recorded in `docs/architektur.md` (section 11). They do not replace testing in road traffic.

Prerequisites:

- Target platform: Android 16 (API 36) or newer; tested only on a Samsung Galaxy S25 Ultra with One UI 8.5.
- A remote control and a helmet that is paired in the Android Bluetooth settings; the manufacturer's app is closed (the remote accepts only one connection).
- A media app with playback running, and a way to receive a phone call.
- The permissions from the Setup section are granted, as far as an item needs them.

**Warning before the first step:** the items under "Setup" were tested on the device starting from an app that was not set up (fresh install or after "Remote vergessen"). If a remote is already set up, "Remote vergessen" removes the existing pairing and the stored confirmation; a new setup is required afterwards. That is the first test item anyway.

Setup:

- [ ] Choose the remote from the list and confirm it with a button press. The confirming press triggers no action (no playback, no light); afterwards the buttons work.
- [ ] Set up again and press nothing for 30 seconds: the pairing is withdrawn, and the app reports "Es kam kein Tastendruck – die Kopplung wurde zurückgenommen." (No button press arrived – the pairing was withdrawn.)
- [ ] Set up again and return to the phone's home screen during the countdown: the deadline still expires and the pairing is withdrawn.
- [ ] Set up again and switch Bluetooth off during the countdown: the process ends immediately with the Bluetooth message, and the pairing is withdrawn.

Buttons (with remote and helmet connected):

- [ ] Play/pause, next track and previous track control the media app.
- [ ] Volume up and down change the media volume while music is playing.
- [ ] Volume up and down change the call volume during a phone call (the volume panel shows "Gesprächslautstärke", call volume); the media volume stays unchanged.
- [ ] The call button answers a ringing call; a second press right afterwards does not end the call (3-second lock); a later press ends it.
- [ ] The walkie-talkie button starts the voice assistant, including with the screen locked.
- [ ] Left and right turn signals switch on and off again on the helmet.
- [ ] The rear light cycles through off, steady and blinking with each press.

Connection and display:

- [ ] Switch the helmet on after the remote: it connects on its own, without a button press; "Helm" (Helmet) shows "Verbunden" (Connected).
- [ ] Switch the helmet off: "Helm" changes to "Nicht verbunden" (Not connected).
- [ ] The battery level appears and refreshes (read on connect and every 5 minutes afterwards). The 20 % warning has been tested on a device only with a simulated value; a real test needs a correspondingly discharged remote.
- [ ] Switch Bluetooth off and on again: the remote reconnects on its own within a few seconds.
- [ ] Switch the remote off and on again: the service comes back without the app being opened.
- [ ] The "Zuständiger Assistent" (Assistant in charge) row shows the name of the assistant app; the "Einstellungen öffnen" (Open settings) button opens a system page.

Verification: all items are ticked, and "Fernbedienung" on the main screen shows "Verbunden". A failed item is a finding; document it together with the device and the Android version.

Rollback: "Remote vergessen" removes the pairing and stops the service; a new setup is required afterwards.

Known problems while testing: during a phone call, individual button presses are lost (see the Known limitations section), so a missing press during a call is not a reliable sign of a fault. WhatsApp calls cannot be answered or ended with the call button. Selecting from a list with several remotes has never been tested on a device.

## Permissions

According to `app/src/main/AndroidManifest.xml`:

| Permission | Type | Purpose |
|---|---|---|
| `BLUETOOTH_CONNECT` | runtime | GATT connection to the remote, connection to the helmet; prerequisite for the foreground service type `connectedDevice` |
| `POST_NOTIFICATIONS` | runtime | Notification of the foreground service and battery warning |
| `ANSWER_PHONE_CALLS` | runtime | Call button: answer and end calls. Without it, only this button has no effect. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal | Foreground service for the connection |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | normal | The system reports when the remote comes into range |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | normal | Starting the foreground service from the background after such a report |

Deliberately not requested: location, `BLUETOOTH_SCAN` (the system scans for the pairing dialog; the app never scans itself), `READ_PHONE_STATE`, notification access (no NotificationListener), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

## Technical overview

A short overview. Protocols, measurements and design decisions are in [`docs/architektur.md`](docs/architektur.md). That document is written in German.

- **Remote:** Bluetooth Low Energy, GATT. Service `0000FFF0-0000-1000-8000-00805F9B34FB`; buttons arrive as notifications of the characteristic `0000FFF4-0000-1000-8000-00805F9B34FB` (packets with header `0x0F` and a checksum). The same characteristic returns the battery status on a read. The parser is plain Kotlin and covered by unit tests.
- **Helmet:** Classic Bluetooth (BR/EDR) with the Serial Port Profile over RFCOMM. The helmet must be paired in Android; the app opens its own additional SPP connection for the light commands, and audio and hands-free stay untouched.
- **Pairing and background:** `CompanionDeviceManager` (not self-managed, no device profile) handles pairing and presence detection. `CompanionDeviceService` starts the foreground service (type `connectedDevice`) when the remote is present and stops it when it is absent.
- **Actions:** Media keys and assistant through `AudioManager.dispatchMediaKeyEvent`, volume through `AudioManager.adjustStreamVolume`, calls through `TelecomManager`.
- One Gradle module `:app`, Kotlin, Jetpack Compose; no libraries for dependency injection, media or BLE.

## Known limitations and open issues

- **Fall detection and emergency SMS are missing** (see the warning at the top). The helmet's accident/fall detection, which triggers an SMS to stored contacts via the manufacturer's app, is not supported by this app. Whether and how the helmet reports a fall over the channels used here has never been investigated. Support later is not ruled out, but it requires that the event can be detected reliably — an emergency function that fails to trigger in a real emergency would be worse than none.
- **WhatsApp calls:** The call button does not respond. WhatsApp switches the audio mode to communication as soon as the call rings; the app takes that for an ongoing call, cannot end it, and therefore does nothing. The volume buttons do work during the call.
- **Call waiting:** If a second call rings during a call, the call button ends the current call instead of answering the second one.
- **Lost button packets during phone calls:** During a phone call, individual button presses do not arrive. The packets in question were never received. A radio coexistence problem between the voice connection and the BLE connection is suspected; the cause is not established and the loss rate has not been measured.
- **Several remotes:** Selecting from a list and confirming with a button press has never been tested with a real second remote.
- **Strict name pattern:** The remote is found only if its advertised name is exactly `Remote control-` followed by four hexadecimal digits (pattern `^Remote control-[0-9A-Fa-f]{4}$`). If another device or firmware variant uses a different name, it does not appear in the list.
- **No feedback from the helmet:** The helmet does not acknowledge light commands. The light states shown are what the app assumes, not a measurement. After a newly established connection the app assumes "everything off" without sending commands; if the helmet keeps blinking after an interruption, the display can be wrong.
- **Voice assistant:** It is started through an unofficially documented key sequence (long press of the headset button). An Android or One UI update can make it stop working. The first time, Android asks which app to open. The button is blocked while a call rings, a call is in progress, or a communication app (audio mode communication) is active.
- **Volume:** Affects local playback only, not remote targets such as Spotify Connect or Cast.
- **Battery display:** It is not established whether the reported value means exactly percent or is a device-specific level indicator; the app shows it unchanged. The value is reported in steps of ten.
- **The helmet's battery level** is not shown.
- **Time format:** Times and dates follow the app language, not the 12/24-hour switch in the system settings. For example, an English interface shows `12:34:56 PM` (American English) and a German one `12:34:56`, whatever the switch says. Honouring the switch would need an Android `Context` in the formatting code.
- **Language and legend not checked on a device:** Switching the language per app, the notification in English (including its rebuild when the language changes) and the button legend with a large system font have been built and unit-tested, but not yet tried on a device.
- **Error details:** Connection errors appear in plain words on the screen and in the notification. The Bluetooth (GATT) status codes are no longer part of the notification; they are only in the log.
- **Only one test device:** All statements about behavior are based on one Samsung Galaxy S25 Ultra running Android 16 with one remote and one helmet.
- The complete list of open issues is in `docs/architektur.md`.

## License

MIT License, see the file [`LICENSE`](LICENSE).

## Origin

The project was developed with the assistance of Claude Code.
