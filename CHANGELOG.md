# Changelog

All notable changes to this project, newest first. Dates are the day the work was finished and
verified on the test device (Samsung Galaxy S25 Ultra, Android 16, One UI 8.5).

Versions below 1.0.0 were never released. They are labels added afterwards, so that the steps the
project went through can be told apart; the public history starts at 1.0.0.

## 1.1.0 — 2026-10-19

### Added
- Quick reference card at the bottom of the screen: every button with what it does, including that
  a short press on the volume keys changes the volume while a long press skips tracks — that split
  is decided by the remote, not by the app. It also names the cases where a button does nothing:
  during a call, for WhatsApp calls, with a second incoming call, and without a connected helmet.
- Per-app language picker (`localeConfig`), so the app can be read in English on a German phone and
  the other way round.

### Changed
- The app follows the system language. English is the base and German the translation, so a phone
  set to a third language gets English rather than German. Numbers and times follow the language as
  well (`3.91 V` against `3,91 V`).
- The foreground notification is built from the same string resources as the status card. Before, its
  wording lived as German literals next to the state machine and would have stayed German on an
  English phone — the one piece of text that is visible all day. The service rebuilds it when the
  language changes, and both notification channels are renamed with it.
- Four duplicated decisions about which text belongs to which state are gone; status card and
  notification now derive from one place, which only differ in the wording for "waiting for
  Bluetooth".

### Removed
- The notification no longer carries the raw GATT status code (`133`, `8`). The shared texts do not
  hold the number; it is still written to the log.

## 1.0.0 — 2026-10-17

First public release.

### Added
- The remote is picked from a list when setting up, for group rides where several identical remotes
  are in range. Since their names only differ in the last four characters, the chosen one has to
  prove itself: the app connects and waits 30 seconds for any button press, which it shows but does
  not act on, so a stranger's remote is never controlled and is dropped again if nothing arrives.
- The status card names the app that answers the walkie-talkie button, with a button leading to the
  system page where that choice can be taken back. Android offers no direct way to ask this — the
  settings key and the role manager both describe the assistant behind the home gesture, not the one
  behind the media key.
- The helmet's control connection opens when the helmet is switched on, not only when the remote
  connects, and the dead socket is closed when it is switched off.
- Readme, MIT licence, and a warning that this app has no crash detection and sends no emergency SMS.
- Release builds are signed with a key kept outside the repository; without it the build stays
  unsigned instead of falling back to the debug key.

## 0.6.0 — 2026-10-16

### Added
- The walkie-talkie button starts the voice assistant. The app sends the headset-hook long press that
  `MediaSessionService` turns into a hands-free voice search; the button was unused before. It is
  held back while a call is ringing or running and while a communication app is active, and the check
  is repeated right before the long press, because a call can start ringing in between.

## 0.5.0 — 2026-09-26

### Added
- Turn signals and rear light on the helmet, switched from the remote over classic Bluetooth (SPP).
  The rear light cycles through steady, blinking and off. The helmet confirms nothing, so the app
  shows what it believes, not a measurement.
- The helmet protocol as observed on the author's own devices, documented in `docs/architektur.md`.

## 0.4.0 — 2026-09-26

### Added
- Battery level of the remote as a ten-bar gauge with percentage and cell voltage, read once per
  connection and every five minutes, with an audible warning at 20 %, 10 % and 0 %.

## 0.3.0 — 2026-09-12

### Added
- The call button answers a ringing call and ends a running one.

### Changed
- Volume follows the audio mode: during a call it changes the call volume, otherwise the media
  volume.

## 0.2.0 — 2026-09-12

### Added
- The app connects to the remote on its own: pairing through the CompanionDeviceManager, presence
  detection, and a foreground service that runs only while the remote is in range and reconnects
  after a drop or after Bluetooth is switched off and on.
- Status screen with light and dark theme.

## 0.1.0 — 2026-09-11

### Added
- Project skeleton, the packet parser for the remote's button frames with unit tests, and the action
  layer that turns a recognised button into a system-wide media key. At this point the packets came
  from a debug trigger, not yet from the remote itself.
