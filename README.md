# CRATONI SMARTRIDE 1.2 Remote – Android-App

Eine eigene Android-App für die Bluetooth-LE-Fernbedienung des Fahrradhelms CRATONI SMARTRIDE 1.2. Sie ersetzt die Hersteller-App für den laufenden Betrieb: Die App verbindet sich selbst mit der Fernbedienung und setzt deren Tasten systemweit um, auch bei geschlossener Oberfläche und gesperrtem Bildschirm. Die Tasten steuern Medienwiedergabe, Anrufe, Lautstärke und den Sprachassistenten; zusätzlich schalten sie Blinker und Rücklicht am Helm.

## Haftung und Unabhängigkeit

Dies ist ein privates, unabhängiges Projekt. Es besteht keine Verbindung zum Hersteller des Helms, und der Hersteller hat das Projekt weder beauftragt noch geprüft oder freigegeben. Produkt- und Markennamen dienen ausschließlich dazu, das Gerät zu bezeichnen, mit dem die App zusammenarbeitet; sie gehören ihren jeweiligen Inhabern.

- Die Software wird ohne jede Gewähr bereitgestellt. Die Nutzung erfolgt auf eigenes Risiko.
- **Straßenverkehr:** Die App kann Blinker und Rücklicht am Helm schalten. Die Lichtfunktion ist kein geprüftes Sicherheitssystem. Der Helm bestätigt keinen Schaltvorgang; die App führt den Lichtzustand selbst und kann nicht erkennen, ob ein Befehl angekommen ist oder ob das Licht tatsächlich leuchtet. Wer sich im Straßenverkehr darauf verlässt, tut das auf eigene Verantwortung. Im Zweifel Handzeichen geben und die Beleuchtung am Helm prüfen.
- Die Kommunikation mit Fernbedienung und Helm wurde durch Beobachtung des eigenen Geräts ermittelt (Interoperabilität), nicht aus Herstellerunterlagen. Es wurde keine Firmware verändert. Die App schreibt weder auf den Firmware-Update-Kanal (OTA) der Fernbedienung noch auf deren übrige Schreib-Characteristics. Zum Helm sendet sie ausschließlich die beobachteten Lichtbefehle.
- Während die App mit der Fernbedienung verbunden ist, kann die Hersteller-App sie nicht gleichzeitig nutzen: Die Fernbedienung nimmt nur eine Verbindung auf einmal an.

## Funktionsumfang

- Tastenbelegung der Fernbedienung:
  - Play/Pause, nächster Titel, vorheriger Titel (als Medientasten an die aktive Medien-App)
  - Lauter/Leiser: Die Lautstärke richtet sich nach dem Audio-Modus. Im Telefonat bzw. bei Sprachverbindungen wirkt sie auf die Gesprächslautstärke, sonst auf die Medienlautstärke.
  - Anruf-Taste: nimmt einen klingelnden Anruf an bzw. beendet ein laufendes Gespräch
  - Walkie-Talkie-Taste: startet den Sprachassistenten
  - Blinker links und rechts am Helm (ein/aus)
  - Rücklicht am Helm mit drei Zuständen (aus, Dauerlicht, blinkend)
- Anzeige des Akkustands der Fernbedienung (Prozent und Zellenspannung) mit Warnung bei 20 %, 10 % und 0 %.
- Vordergrunddienst, der nur läuft, solange die Fernbedienung in Reichweite ist, mit automatischem Wiederverbinden.
- Einrichtung über eine Geräteliste, bei der die gewählte Fernbedienung per Tastendruck bestätigt wird.

## Voraussetzungen

Alle Angaben aus `app/build.gradle.kts` und `gradle/libs.versions.toml`, soweit nicht anders vermerkt.

| Bestandteil | Wert | Quelle |
|---|---|---|
| Android auf dem Gerät | Android 16 (API 36) oder neuer | `minSdk = 36` |
| `targetSdk` / `compileSdk` | 36 / 37 | `app/build.gradle.kts` |
| JDK | 17 (Quell- und Zielkompatibilität `VERSION_17`) | `app/build.gradle.kts` |
| Android Gradle Plugin | 9.4.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Gradle | 9.7.1 (über den Wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| Compose BOM | 2026.09.00 | `gradle/libs.versions.toml` |

- Android SDK mit der Plattform 37 (für `compileSdk = 37`). Der Pfad zum SDK steht in der lokalen Datei `local.properties`, die nicht im Repository liegt.
- Das Gerät braucht Bluetooth Low Energy und die Companion-Device-Unterstützung (beides im Manifest als erforderlich deklariert).
- Eine Fernbedienung und ein Helm CRATONI SMARTRIDE 1.2.

**Getestet wurde ausschließlich auf einem Samsung Galaxy S25 Ultra mit Android 16 (One UI 8.5).** Andere Geräte, Hersteller-Oberflächen und Android-Versionen sind ungeprüft.

## Bauen und installieren

Zielplattform: Entwicklungsrechner mit JDK 17 und Android SDK; Android 16 auf dem Gerät. Unter Windows (PowerShell) statt `./gradlew` den Aufruf `.\gradlew.bat` verwenden.

Debug-APK bauen:

```bash
./gradlew assembleDebug
```

Unit-Tests ausführen:

```bash
./gradlew testDebugUnitTest
```

Lint ausführen:

```bash
./gradlew lintDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/`. Installation auf ein per USB-Debugging verbundenes Gerät:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Auf Geräten mit mehreren Nutzerprofilen (zum Beispiel Secure Folder) gegebenenfalls zusätzlich `--user 0` angeben.

Hinweise:

- Der Debug-Build ist mit dem automatisch erzeugten Debug-Schlüssel signiert. Im Repository liegt kein Release-Keystore, und `app/build.gradle.kts` definiert keine Signaturkonfiguration; ein Release-Build wäre unsigniert und müsste mit eigenem Schlüssel signiert werden. Schlüsseldateien (`*.jks`, `*.keystore`, `keystore.properties`) sind in `.gitignore` ausgeschlossen.
- Der Debug-Build enthält zusätzlich einen Debug-Auslöser für Befehle per `adb`; er ist nur für die Entwicklung gedacht.
- **Deinstallieren entfernt die Kopplung und die erteilten Berechtigungen.** Danach ist eine neue Einrichtung nötig. Ein Update mit `adb install -r` behält beides.

## Einrichtung in der App

Zweck: Die App mit genau einer Fernbedienung verbinden und diese Wahl bestätigen.

Voraussetzungen:

- Der Helm ist in den Android-Bluetooth-Einstellungen gekoppelt. Die App sucht ihn unter den gekoppelten Geräten anhand des Namenspräfixes `SMARTRIDE` und verbindet nur, wenn genau ein solches Gerät gekoppelt ist.
- Bluetooth ist eingeschaltet, die Fernbedienung ist eingeschaltet und in der Nähe.
- Der Standort-Schalter in den Systemeinstellungen ist während der Einrichtung aktiv (Anforderung des Companion-Device-Dialogs). Die App selbst verlangt keine Standortberechtigung.

Schritte:

1. App öffnen und über „Berechtigungen erteilen“ den Bluetooth-Zugriff, Benachrichtigungen und „Anrufe annehmen“ freigeben (siehe Abschnitt Berechtigungen).
2. „Einrichten“ antippen. Das System zeigt eine Liste aller Fernbedienungen in Reichweite (Namen der Form `Remote control-XXXX`).
3. Die eigene Fernbedienung wählen. Mehrere Fernbedienungen tragen dasselbe Namensmuster; die Liste allein erlaubt deshalb keine sichere Zuordnung.
4. Die App zeigt „Remote bestätigen“ mit einem Countdown von 30 Sekunden. In dieser Zeit eine beliebige Taste an der gewählten Fernbedienung drücken.
5. Kommt der Tastendruck über die Verbindung dieser Fernbedienung an, gilt die Wahl als bestätigt, und die Kopplung bleibt bestehen.

**Wird nichts gedrückt** (oder „Abbrechen“ gewählt, oder Bluetooth ausgeschaltet), nimmt die App die Kopplung zurück und meldet es. Einen Tastendruck an einer fremden Fernbedienung bestätigt die Wahl nicht, solange er nicht über die Verbindung der gewählten Fernbedienung ankommt. Bis zur Bestätigung führt die App keine Tastenbefehle aus.

Verifikation: Im Hauptbildschirm zeigt „Fernbedienung“ den Zustand „Verbunden“, „Letzter Befehl“ zeigt den zuletzt gedrückten Befehl, und im Akkufeld erscheint ein Messwert. Nach dem Ausschalten der Fernbedienung beendet sich der Dienst; beim erneuten Einschalten verbindet das System die App von selbst.

Rückgängig machen: „Remote vergessen“ entfernt die Kopplung, beendet den Dienst und löscht die gespeicherte Bestätigung. Danach ist eine neue Einrichtung nötig.

Samsung-Geräte: Die App zeigt den Hinweis, die Akkuoptimierung der App auf „Nie im Standby“ zu stellen. Ob die Companion-Device-Funktion auf One UI vor der Akkuoptimierung schützt, ist nicht belegt.

## Berechtigungen

Nach `app/src/main/AndroidManifest.xml`:

| Berechtigung | Art | Zweck |
|---|---|---|
| `BLUETOOTH_CONNECT` | Laufzeit | GATT-Verbindung zur Fernbedienung, Verbindung zum Helm; Voraussetzung für den Vordergrunddienst-Typ `connectedDevice` |
| `POST_NOTIFICATIONS` | Laufzeit | Benachrichtigung des Vordergrunddienstes und Akkuwarnung |
| `ANSWER_PHONE_CALLS` | Laufzeit | Anruf-Taste: Anruf annehmen und beenden. Ohne sie bleibt nur diese Taste wirkungslos. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal | Vordergrunddienst für die Verbindung |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | normal | Das System meldet, wenn die Fernbedienung in Reichweite kommt |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | normal | Start des Vordergrunddienstes aus dem Hintergrund nach einer solchen Meldung |

Bewusst nicht angefordert: Standort, `BLUETOOTH_SCAN` (das System sucht für den Kopplungsdialog, die App scannt nie selbst), `READ_PHONE_STATE`, Zugriff auf Benachrichtigungen (kein NotificationListener), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

## Technik

Kurzüberblick; Protokolle, Messungen und Entscheidungen stehen in [`docs/architektur.md`](docs/architektur.md).

- **Fernbedienung:** Bluetooth Low Energy, GATT. Service `0000FFF0-0000-1000-8000-00805F9B34FB`, Tasten als Notifications der Characteristic `0000FFF4-0000-1000-8000-00805F9B34FB` (Pakete mit Header `0x0F` und Prüfsumme). Derselbe Characteristic liefert per Lesezugriff den Akkustatus. Der Parser ist reines Kotlin und per Unit-Test geprüft.
- **Helm:** klassisches Bluetooth (BR/EDR) mit Serial Port Profile über RFCOMM. Der Helm muss in Android gekoppelt sein; die App öffnet zusätzlich eine eigene SPP-Verbindung für die Lichtbefehle, Audio und Freisprechen bleiben unberührt.
- **Kopplung und Hintergrund:** `CompanionDeviceManager` (nicht self-managed, ohne Geräteprofil) übernimmt Kopplung und Präsenzerkennung. `CompanionDeviceService` startet bei Anwesenheit der Fernbedienung den Vordergrunddienst (Typ `connectedDevice`) und beendet ihn bei Abwesenheit.
- **Aktionen:** Medientasten und Assistent über `AudioManager.dispatchMediaKeyEvent`, Lautstärke über `AudioManager.adjustStreamVolume`, Anrufe über `TelecomManager`.
- Ein Gradle-Modul `:app`, Kotlin, Jetpack Compose; keine Bibliotheken für Dependency Injection, Media oder BLE.

## Bekannte Grenzen und offene Punkte

- **WhatsApp-Anrufe:** Die Anruf-Taste reagiert nicht. WhatsApp setzt den Audio-Modus schon beim Klingeln auf Kommunikation; die App erkennt darin einen laufenden Anruf, kann ihn aber nicht beenden und tut deshalb nichts. Die Lautstärketasten wirken im Gespräch dagegen.
- **Anklopfen:** Klingelt während eines Gesprächs ein zweiter Anruf, beendet die Anruf-Taste das laufende Gespräch, statt den zweiten anzunehmen.
- **Verlorene Tastenpakete bei Telefonaten:** Während eines Telefonats kommen einzelne Tastendrücke nicht an. Die betreffenden Pakete wurden nie empfangen. Vermutet wird eine Funk-Koexistenz zwischen Sprachverbindung und BLE-Verbindung; die Ursache ist nicht belegt und die Verlustrate nicht gemessen.
- **Mehrere Fernbedienungen:** Die Auswahl aus einer Liste mit Bestätigung per Tastendruck wurde nie mit einer echten zweiten Fernbedienung geprüft.
- **Strenges Namensmuster:** Die Fernbedienung wird nur gefunden, wenn ihr beworbener Name exakt `Remote control-` plus vier Hexadezimalziffern lautet (Muster `^Remote control-[0-9A-Fa-f]{4}$`). Weicht der Name einer anderen Geräte- oder Firmware-Variante ab, erscheint sie nicht in der Liste.
- **Keine Rückmeldung vom Helm:** Der Helm bestätigt keine Lichtbefehle. Die angezeigten Lichtzustände sind das, was die App annimmt, keine Messung. Bei einer neu aufgebauten Verbindung geht die App von „alles aus“ aus, ohne Befehle zu senden; blinkt der Helm nach einem Abbruch weiter, kann die Anzeige abweichen.
- **Sprachassistent:** Der Start läuft über eine nicht offiziell dokumentierte Tastenfolge (lange Headset-Taste). Ein Update von Android oder One UI kann ihn wirkungslos machen. Beim ersten Mal fragt Android, welche App geöffnet werden soll. Die Taste ist gesperrt, solange es klingelt, ein Telefonat läuft oder eine Kommunikations-App (Audio-Modus Kommunikation) aktiv ist.
- **Lautstärke:** Wirkt nur auf die lokale Wiedergabe, nicht auf entfernte Ziele wie Spotify Connect oder Cast.
- **Akkuanzeige:** Es ist nicht belegt, ob der gemeldete Wert exakt Prozent bedeutet oder eine geräteeigene Stufenanzeige ist; die App zeigt ihn unverändert an. Gemeldet wird in Zehnerstufen.
- **Akkustand des Helms** wird nicht angezeigt.
- **Nur ein Testgerät:** Alle Aussagen zum Verhalten beruhen auf einem Samsung Galaxy S25 Ultra mit Android 16 und einer Fernbedienung samt Helm.
- Die vollständige Liste offener Punkte steht in `docs/architektur.md`.

## Lizenz

MIT-Lizenz, siehe Datei [`LICENSE`](LICENSE).

## Entstehung

Die Entwicklung erfolgte mit Unterstützung von Claude Code.
