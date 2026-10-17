# Architektur — CRATONI SMARTRIDE 1.2 Remote → Android-Mediensteuerung (V1)

Stand: 17.10.2026
Status: Architekturentscheidung für V1, vom Projektinhaber am 11.09.2026 bestätigt; fortgeschrieben bis zur Helm-Verbindung beim Einschalten des Helms nach der Fernbedienung (17.10.2026); davor Anzeige des zuständigen Sprachassistenten in der Statuskarte (A1, 17.10.2026); davor Remote-Auswahl aus einer Liste mit Tastenbestätigung (17.10.2026).

Fachliche Anforderungen: Das zugrunde liegende Anforderungsdokument (`docs/anforderungen.md`) ist privat und nicht Teil dieser Veröffentlichung; Verweise darauf im weiteren Text und in einzelnen Quelldateien laufen daher ins Leere. Dieses Dokument wiederholt die Anforderungen nicht, sondern hält die daraus abgeleiteten Architekturentscheidungen fest.

## 1. Eckdaten

- applicationId und namespace: `de.werklade.cratoniremote`
- Zielplattform: Android 16 (API 36), Zielgerät Samsung Galaxy S25 Ultra, One UI 8.5
- `minSdk = 36`, `targetSdk = 36`, `compileSdk = 37` — angehoben, weil `androidx.compose.ui:ui-android` 1.12.1 (Compose BOM 2026.09.00) laut AAR-Metadaten `minCompileSdk = 37` verlangt; `docs/anforderungen.md` erlaubt compileSdk > 36 ausdrücklich. `targetSdk` bleibt explizit gesetzt, da AGP 9 es sonst von compileSdk übernimmt.
- Kotlin, Jetpack Compose, ein Gradle-Modul `:app`

## 2. Getroffene Entscheidungen (vom Projektinhaber am 11.09.2026 bestätigt)

| Thema | Entscheidung | Verworfene Alternative + Grund |
|---|---|---|
| Mediensteuerung V1 | `AudioManager.dispatchMediaKeyEvent` mit `KEYCODE_MEDIA_PLAY_PAUSE` / `KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS` (jeweils `ACTION_DOWN`, dann `ACTION_UP`). Kein `NotificationListenerService`. | `MediaSessionManager.getActiveSessions` + `NotificationListenerService` — erfordert manuelle Freischaltung, Lesezugriff auf alle Benachrichtigungen, evtl. durch „Eingeschränkte Einstellungen" (Android 13+) bei direkt installierten APKs blockiert; widerspricht der Vorgabe „nur notwendige Berechtigungen". Bleibt mögliche Erweiterung. |
| Lautstärke V1 | `AudioManager.adjustStreamVolume(…, ADJUST_RAISE` bzw. `ADJUST_LOWER, FLAG_SHOW_UI)`; der Stream richtet sich seit 12.09.2026 nach `AudioManager.mode`: `MODE_IN_CALL`/`MODE_IN_COMMUNICATION` → `STREAM_VOICE_CALL`, sonst `STREAM_MUSIC` | `adjustSuggestedStreamVolume(…, USE_DEFAULT_STREAM_TYPE, …)` — überlässt dem System die Wahl und könnte im Leerlauf unbemerkt die Klingeltonlautstärke verstellen. |
| Anruf-Taste (ergänzt am 12.09.2026) | `TelecomManager.acceptRingingCall()` bzw. `endCall()`, Unterscheidung über `AudioManager.mode`; genau eine neue Laufzeitberechtigung `ANSWER_PHONE_CALLS`. Beide Methoden sind seit API 29 deprecated, funktionieren aber; Ausnahme eng begrenzt und im Code begründet. | `InCallService` + `MANAGE_ONGOING_CALLS`: laut AOSP nur über ein CDM-Geräteprofil (WATCH/GLASSES) zu bekommen, das gebündelt Telefon-, SMS-, Kontakt- und Nahbereichsberechtigungen gewährt, die Fernbedienung als Smartwatch ausgeben würde und eine neue Kopplung verlangt. Am Gerät belegt: `hasManageOngoingCallsPermission()` = false mit unserer profillosen Association. |
| Hintergrund | `CompanionDeviceManager` (nicht self-managed) + Präsenzerkennung durch das System; Foreground Service vom Typ `connectedDevice` läuft NUR, solange die Remote anwesend ist. | (a) Scan per `PendingIntent` — laut Google-Doku nur für kurze Kommunikation geeignet. (b) Dauerhafter Foreground Service mit `autoConnect=true` — permanente Benachrichtigung, läuft ständig; bleibt Plan B, falls die CDM-Präsenzerkennung auf dem Samsung unzuverlässig ist. |
| Abhängigkeiten | Keine zusätzlichen Bibliotheken für DI (kein Hilt), kein Media3, keine Fremd-BLE-Bibliothek; ausschließlich Framework-APIs. | — |
| Akkustand (entschieden am 26.09.2026) | Quelle ist der 3-Byte-Statusrahmen aus einem LESEZUGRIFF auf `FFF4`: Byte 0 = Ladestand in Prozent (Zehnerstufen), Byte 1–2 = Zellenspannung in Millivolt (big-endian). Gelesen wird beim Verbinden und danach alle 5 Minuten. Anzeige: Akkusymbol mit zehn Segmenten plus „60 % · 3,74 V". | Battery Level 0x2A19: bleibt abonniert, hat aber in zwei Wochen kein einziges Mal gesendet und kann nicht gelesen werden — als Quelle unbrauchbar. |
| Akkuwarnung (26.09.2026) | Warnung bei 20 %, erneut bei 10 % und 0 %, jede Schwelle nur einmal; wieder scharf, sobald der Wert 10 Punkte darüber liegt. Eigener Benachrichtigungskanal „Akkuwarnung" mit Ton, getrennt von der stillen Statusanzeige. Zuletzt gewarnte Stufe in `SharedPreferences`, damit ein Dienstneustart nicht erneut warnt. | Warnung im Arbeitsspeicher — der Dienst startet im Alltag häufig neu und hätte bei jeder Verbindung erneut gewarnt. |
| Walkie-Talkie-Taste (umgesetzt und am Gerät geprüft am 16.10.2026) | Startet den Sprachassistenten. `AssistantExecutor` / `MediaKeyAssistantExecutor` senden über `AudioManager.dispatchMediaKeyEvent` eine Langdruck-Folge der Headset-Taste (`KEYCODE_HEADSETHOOK`); `MediaSessionService` startet daraufhin `ACTION_VOICE_SEARCH_HANDS_FREE`. Keine neue Berechtigung, kein WakeLock. Gesendet wird nur im Audio-Modus `MODE_NORMAL`; bei Klingeln (`MODE_RINGTONE`), im Telefonat (`MODE_IN_CALL`) und bei `MODE_IN_COMMUNICATION` (auch Intercom-/VoIP-Apps wie Discord, Wave Intercom) tut die Taste nichts (Ergebnis `Ignored`). Doppeldruck-Schutz nur für die Dauer der Folge (rund 0,5 s). Details und Messungen: §12, §11. | (a) `startActivity(ACTION_VOICE_COMMAND / ACTION_ASSIST)` aus dem Dienst — scheitert an der Startsperre aus dem Hintergrund. (b) `BluetoothHeadset.startVoiceRecognition` — öffnet nur den SCO-Kanal, startet keinen Assistenten. (c) Anruf-Taste mitbelegen — vom Nutzer ausdrücklich nicht gewünscht; sie bleibt bei annehmen/auflegen. (d) Nachlaufsperre von 2 s nach dem Start — erwogen, auf Wunsch des Nutzers nicht eingebaut. Sperre in Telefonat und Klingeln: ANGENOMMEN nach AOSP-Quellcode — im Telefonat lässt das System Medientasten fremder Apps ohnehin nicht durch (Global-Priority-Session), ein langer Headset-Druck gilt beim Klingeln als „ablehnen“, und der Assistent würde einer Intercom-App das Mikrofon wegnehmen. |
| Remote-Auswahl und Bestätigung (umgesetzt und am Gerät geprüft am 17.10.2026) | Der Systemdialog zeigt eine **Liste** (`AssociationRequest` ohne `setSingleDevice`), Filter nur das strenge Namensmuster `^Remote control-[0-9A-Fa-f]{4}$`. Der Nutzer wählt eine Remote und bestätigt sie innerhalb von 30 s mit einem Tastendruck an genau dieser Remote; ohne Druck wird die Kopplung per `disassociate` zurückgenommen. Als „die Remote“ gilt nur, was bestätigt wurde: die Assoziations-ID steht in `SharedPreferences` (`pairing`, §4). Der Dienst, nicht die Oberfläche, empfängt den Druck, besitzt die Frist und schreibt die Bestätigung. Das erste gültige Tastenpaket bestätigt (auch `UNKNOWN`), wird angezeigt, aber nicht ausgeführt. Keine neue Berechtigung, keine neue Abhängigkeit, kein Room. Ablauf: §3, Messungen: §11. | (a) `setSingleDevice(true)` (bisheriger Stand) — das System wählt den ersten Treffer selbst und koppelt ohne Liste; bei mehreren gleichen `Remote control-XXXX` (Gruppenfahrt) wäre es zufällig die falsche. (b) `ScanFilter` auf FFF0 — die Remote wirbt ohne Service-UUIDs (§11), der Filter würde sie aus dem Dialog ausschließen. (c) `BluetoothLeDeviceFilter.setRenameFromName` / `setRenameFromBytes` — beide indexieren im Systemprozess in Alias bzw. MAC und können dort eine Exception werfen, die die App nicht fangen kann (Begründung aus dem Code-Kommentar von `CompanionSetup.associate`; die Exception wurde nicht am Gerät ausgelöst). Der Dialog zeigt ohnehin den lesbaren Namen (§11, 17.10.2026 b). (d) Eigener BLE-Scan mit Assoziation per MAC — bräuchte `BLUETOOTH_SCAN`, und der Nutzer müsste trotzdem raten, welche MAC seine ist. (e) Stille Übernahme der einzigen vorhandenen Assoziation als Dauerregel (bisheriger Stand) — nur noch als einmalige Migration pro Installation (§3). (f) Bestätigung in der Oberfläche persistieren — ginge verloren, wenn der Nutzer die App zwischen Druck und Schreiben verlässt; der Dienst besitzt die Verbindung, auf der der Druck ankommt. |
| Anzeige des zuständigen Assistenten, „A1“ (umgesetzt und am Gerät geprüft am 17.10.2026) | Die Statuskarte zeigt in der Zeile „Zuständiger Assistent“, welche App die Walkie-Talkie-Taste übernimmt, plus Knopf „Einstellungen öffnen“. Ermittelt wird per `PackageManager.queryIntentActivities` und `resolveActivity` auf `android.speech.action.VOICE_SEARCH_HANDS_FREE`, beide mit `MATCH_DEFAULT_ONLY`. Chooser („Wird beim Drücken abgefragt“) ausschließlich daran erkannt, dass die aufgelöste Komponente nicht in der Kandidatenliste steht. Anzeigename: `getApplicationLabel` → `loadLabel` → Paketname, danach bereinigt (Zeilenumbrüche und Steuerzeichen zu Leerzeichen, auf 60 Zeichen gekürzt). Dafür ein `<queries>`-Block im Manifest mit genau einer Action (`VOICE_SEARCH_HANDS_FREE`); das ist keine Berechtigung, §7 bleibt unverändert. Der Assistent ist ein eigener UI-Zustand in `MainActivity` (`rememberAssistantTarget`, auf `Dispatchers.IO`, ausgelöst vom vorhandenen `ON_RESUME`-Auffrischen), nicht Teil von `RemoteStatus` und nicht in der Benachrichtigung. Knopf: Kette aus drei Zielen, jede Stufe einzeln abgesichert: App-Infoseite der aufgelösten App, dann `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, dann `ACTION_VOICE_INPUT_SETTINGS`; scheitert alles, erscheint in der Zeile der Hinweis „Diese Systemseite gibt es auf diesem Gerät nicht.“ Gefangen werden `ActivityNotFoundException` („gibt es hier nicht“) und `SecurityException` („existiert, lässt sich aber von uns nicht starten“, bei Hersteller-Einstellungen möglich). Ausgewertet wird nur die Freisprech-Aktion, die Zeile zeigt genau einen Wert (Begründung: §11, Gerätetest A1). Ausführlich: §12. | (a) `Settings.Secure` („assistant“, „voice_interaction_service“) — `@hide`/`@TestApi`, und beschreibt den `ACTION_ASSIST`-Assistenten (Home-Taste), nicht den Empfänger der Medientaste. (b) `RoleManager.getRoleHolders` — `@SystemApi`, verlangt `MANAGE_ROLE_HOLDERS`. (c) `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` — würde anbieten, UNSERE App zum Assistenten zu machen, statt die Wahl für die Medientaste zu ändern. (d) `QUERY_ALL_PACKAGES` statt `<queries>` — auf Google Play begründungspflichtig und unverhältnismäßig für eine einzige Action. Ohne jeden Eintrag liefert `resolveActivity` ab Android 11 `null`. (e) `resolveActivity`-Vortest vor jedem Knopfziel — die Paketsichtbarkeit würde ihn verfälschen, während `startActivity` davon unabhängig funktioniert; deshalb versucht jede Stufe direkt zu starten. (f) Zweite Zeile für `ACTION_WEB_SEARCH` (AOSP wählt sie bei entsperrtem Gerät mit Bildschirm an) — One UI nimmt auch dort die Freisprech-Aktion und hat für `WEB_SEARCH` keinen Standard; die zweite Zeile wäre auf dem Zielgerät falsch. |
| Helm-Verbindung beim Einschalten des Helms (umgesetzt und am Gerät geprüft am 17.10.2026) | Die SPP-Steuerverbindung zum Helm (§13) wird auch dann aufgebaut, wenn der Helm erst NACH der Fernbedienung eingeschaltet wird. Ein zweiter, zur Laufzeit registrierter Rundruf-Empfänger im Dienst (`helmetAclReceiver`; `onCreate` an, `onDestroy` ab) hört auf `BluetoothDevice.ACTION_ACL_CONNECTED` und `ACTION_ACL_DISCONNECTED`. Die Entscheidung ist eine reine, getestete Regel in `service/HelmetAclGate.kt` (`isHelmetDevice`, `helmetShouldConnect`). Verbunden wird nur, wenn **alle** Bedingungen gelten: (1) das Ereignis gehört zum Helm — Vorrang hat der Vergleich mit der bereits aufgelösten Adresse über den Lesezugriff `HelmetSppClient.knownAddress()`, hilfsweise das Namenspräfix `SMARTRIDE` (`HELMET_NAME_PREFIX`, ohne Beachtung der Groß-/Kleinschreibung); (2) keine Bestätigung läuft (`dispatchAllowed`, §3); (3) der Helm ist weder `Connected` noch `Connecting`; (4) die Fernbedienung ist verbunden. Keine MAC ist im Code hart kodiert. `ACTION_ACL_DISCONNECTED` des Helms verbindet nie, sondern schließt den toten Socket (`helmet.disconnect()`), und nur im Zustand `Connected` oder `Connecting`. Ereignisse über LE werden vorweg verworfen (`EXTRA_TRANSPORT`). `BLUETOOTH_CONNECT` wird vor dem Lesen von Adresse und Name geprüft, eine `SecurityException` gefangen; fehlt die Berechtigung, geschieht still nichts. Keine neue Berechtigung. **Der Empfänger ist mit `Context.RECEIVER_EXPORTED` registriert** — im Gegensatz zum Empfänger für `BluetoothAdapter.ACTION_STATE_CHANGED` im selben Dienst, der bei `RECEIVER_NOT_EXPORTED` bleibt. Messung: §11, Gerätetest „Helm nach der Fernbedienung einschalten“. Weitere Einzelheiten: §13. | (a) Helm nur beim Verbinden der Fernbedienung öffnen (bisheriger Stand) — ein zu diesem Zeitpunkt ausgeschalteter Helm blieb unerreichbar, bis ein Lichttastendruck über `HelmetSppClient.send()` einen Verbindungsversuch anstieß; der Rahmen dieses Drucks ging verloren. Dieser Weg bleibt als Rückfall bestehen (§13). (b) `Context.RECEIVER_NOT_EXPORTED` für die ACL-Rundrufe — am Gerät gemessen wirkungslos: Die Rundrufe kamen nie an, obwohl der Dienst lief, `BLUETOOTH_CONNECT` erteilt war und andere Apps dieselben Ereignisse im selben Moment empfingen (im Systemlog belegt). Ursache nicht ermittelt. `RECEIVER_EXPORTED` ist vertretbar, weil beide Aktionen geschützte Rundrufe sind, die nur das System senden darf; selbst ein gefälschtes Ereignis könnte höchstens eine Verbindung zu dem Helm anstoßen, den die App ohnehin kennt (Aussage aus dem Code-Kommentar und der Rundruf-Schutzstufe; ein Fälschungsversuch wurde nicht unternommen). (c) Wiederholungsversuche nach dem Funkkontakt — nicht eingebaut: Die Sorge, der Helm nehme unmittelbar nach dem Funkkontakt noch kein RFCOMM an, bestätigte sich nicht; ein Versuch genügte (§11). (d) Auch auf LE-Ereignisse reagieren — verworfen: Der SPP-Socket lebt nur auf klassischem Bluetooth, und die eigenen GATT-Verbindungen zur Fernbedienung erzeugen ebenfalls LE-Ereignisse; ein LE-Abbruch derselben Adresse würde eine gesunde Verbindung schließen. |
| Medien-Testumgebung | Gerätetests mit Amazon Music (`com.amazon.mp3`) auf dem S25 Ultra; Spotify und YouTube Music sind auf dem Testgerät (Nutzer 0) nicht installiert. Aktionen werden vor der BLE-Schicht über einen Debug-Testauslöser per adb geprüft (nur Debug-Build). | — |

## 3. Ablauf

### Ersteinrichtung: Auswahl aus einer Liste, Bestätigung per Tastendruck (seit 17.10.2026)

Grund: Bei einer Gruppenfahrt sind mehrere gleiche `Remote control-XXXX` in Reichweite; kein Filter unterscheidet sie (Name generisch, MAC vorab unbekannt). Der Nutzer wählt deshalb aus einer Liste und beweist mit einem Tastendruck, dass es seine Remote ist. Voraussetzungen wie bisher: Standort-Schalter aktiv (§7), `BLUETOOTH_CONNECT` erteilt, Bluetooth an.

1. **Anfrage.** `CompanionSetup.associate()` stellt einen `AssociationRequest` mit `BluetoothLeDeviceFilter` und dem Namensmuster `^Remote control-[0-9A-Fa-f]{4}$` (exakte Groß-/Kleinschreibung), nicht selfManaged, ohne Device-Profile und **ohne** `setSingleDevice`. Kein `ScanFilter` auf FFF0, weil die Remote ohne Service-UUIDs wirbt (§11). `setRenameFromName/-Bytes` werden bewusst nicht benutzt (§2).
2. **Auswahl.** Der Systemdialog zeigt die Liste mit dem lesbaren Namen (z. B. „Remote control-3D2C“, nicht die MAC; §11, 17.10.2026 b). `onAssociationCreated` und das Activity-Ergebnis melden dieselbe Assoziation in nicht festgelegter Reihenfolge; der Start der Bestätigung ist daher idempotent (`MainActivity.beginConfirmation`).
3. **Bestätigungszustand synchron.** Die Oberfläche setzt `RemoteStatus.pendingConfirmation` (`PendingConfirmation`: Assoziations-ID, Anzeigename, Frist) sofort und auf dem aufrufenden Thread. Die Frist ist `SystemClock.elapsedRealtime() + 30 s` (`CONFIRMATION_TIMEOUT_MS`); diese Uhr läuft im Tiefschlaf weiter und lässt sich weder vom Nutzer noch vom Netz verstellen. Der Zustand muss vor allem anderen stehen, weil der nächste Reconcile ihn liest, um die neue Assoziation nicht anzufassen. Ist Bluetooth aus, entfällt das Warten: Ausgang `BLUETOOTH_OFF`, Assoziation sofort zurück.
4. **Dienst starten.** `RemoteConnectionService.confirm()` startet den Dienst mit `ACTION_CONFIRM_ASSOCIATION`. Er verbindet über **denselben** `CratoniBleManager` wie im Normalbetrieb (die Remote lässt nur eine Verbindung zu; es gibt nie einen zweiten GATT-Client). Die Verbindungsfreigabe (`connectAllowed`) lässt währenddessen nur die zu bestätigende Assoziation durch.
5. **Pending-Marker.** Erst danach schreibt `CompanionSetup.markPendingConfirmation()` auf einem IO-Thread `pending_association_id` (`commit()`). Der Marker ist das Netz für einen Prozesstod: Er macht eine nie bestätigte Assoziation beim nächsten Start als solche erkennbar. Eine noch markierte ältere Assoziation wird vorher zurückgenommen (der Marker hält nur eine ID). Gelingt das Schreiben nicht, endet die Bestätigung als `FAILED` und die Assoziation wird zurückgenommen.
6. **Frist im Dienst.** Der Dienst besitzt die Frist: eine eigene Timeout-Coroutine misst in Schritten von höchstens 1 s gegen `elapsedRealtime()` (`delay` allein stünde im Tiefschlaf still). Die Oberfläche zählt dieselbe Frist nur für die Anzeige, denn sie endet mit der Activity (Zurück-Taste), die Verbindung zur gewählten Remote aber nicht.
7. **Tastendruck.** `onPacket` prüft zuerst die Frist („zu spät ist keine Bestätigung“, Ausgang `TIMED_OUT`). Sonst bestätigt das **erste gültige Tastenpaket**, auch `UNKNOWN`: Es wird als letzter Befehl angezeigt, aber **nicht ausgeführt** (`DispatchGate.dispatchAllowed`, §6). `RemoteStatus.onConfirmed` setzt den Zustand auf dem BLE-Thread (das schließt das Tor für das nächste Paket und beendet den Countdown), der Dienst öffnet die Helm-Verbindung und publiziert den bereits gelesenen Akkuwert nach (der Fluss würde ihn nicht erneut senden).
8. **Schreiben.** `persistConfirmation` schreibt in einem `NonCancellable`-Scope auf IO `confirmed_association_id` und löscht den Marker in demselben `commit()`. Die Oberfläche schreibt dieselbe Bestätigung idempotent mit (`markConfirmed`) und startet erst dann `startObservingDevicePresence(ObservingDevicePresenceRequest)` mit der Assoziations-ID. Fällt der Prozess zwischen Zustand und Platte aus, heilt `reconcile()` den Fall „Zustand bestätigt, Platte nicht“ (`PairingCleanup.confirmationToHeal`, nur wenn Ergebnis `CONFIRMED`, Marker und gemerkte ID übereinstimmen und die Assoziation existiert).

**Ausgänge** (`ConfirmationOutcome`): `CONFIRMED` (Assoziation bleibt), `TIMED_OUT` (keine Taste in 30 s), `CANCELLED` (Abbrechen-Knopf), `BLUETOOTH_OFF` (Adapter aus vor oder während des Wartens), `FAILED` (interner Fehler, Marker nicht schreibbar, Dienst vorzeitig beendet — `onDestroy` beendet eine laufende Bestätigung). Alle außer `CONFIRMED` nehmen die Kopplung per `disassociate` zurück; der Dienst tut das in einem `NonCancellable`-Scope, damit sein eigenes Ende es nicht abschneidet.

**Währenddessen gesperrt:** Tastendispatch, Helm-Verbindung, Akkuanzeige und Akkuwarnung (die Messung gehört womöglich einer fremden Remote und würde sonst hörbar warnen und eine Schwelle speichern), „Einrichten“ und „Remote vergessen“ (werden nicht komponiert). Ein Präsenzereignis für eine andere Assoziation beendet die laufende Bestätigung nicht; `BLE_DISAPPEARED` stoppt den Dienst währenddessen nicht (`keepAliveReason`).

**Aufräumen (`reconcile()`).** `PairingReconciler.decide(storedId, pendingId, associationIds, legacyMigrationDone, confirmingId)` ist die einzige Regel; `CompanionSetup.reconcile()` führt sie bei jedem Start und jedem Fortsetzen der Oberfläche aus:

- Eine gerade zu bestätigende Assoziation (`confirmingId`) wird nie entfernt und nie übernommen.
- Die gespeicherte ID gewinnt, sofern die Assoziation noch existiert; alle anderen werden entfernt.
- Ein Marker verhindert die Übernahme der Assoziation, auf die er zeigt (der Druck blieb aus, sie kann jedem gehören).
- Mehrere Assoziationen ohne gespeicherte ID werden alle entfernt; der Nutzer richtet neu ein.
- Eine gespeicherte ID ohne Assoziation (z. B. in den Systemeinstellungen entfernt) wird gelöscht, damit eine spätere Assoziation mit derselben Nummer nicht als bestätigt gilt.
- **Einmalige Migration:** Gibt es keine gespeicherte ID, genau eine Assoziation ohne Marker und ist `migrated_legacy_association` noch nicht gesetzt, wird sie still übernommen (Entscheidung des Projektinhabers: eine mit einem früheren Stand eingerichtete Installation soll nicht neu fragen). Danach ist das Fenster für diese Installation geschlossen; es überlebt „Remote vergessen“. Jeder Fehlerfall (fehlender Marker, abgebrochene Coroutine, gescheitertes `disassociate`) endet so in „neu einrichten“ statt in einer unbestätigten Remote.
- **Nachbereitung** (`PairingCleanup.decide`, `afterReconcile`, `confirmationToHeal`): Gespeicherte ID und Marker werden erst gelöscht, wenn die Assoziation nachweislich weg ist (die Liste wird nach dem Entfernen neu gelesen). Bleibt eine Assoziation trotz `disassociate` bestehen, bleiben die Vermerke, und der nächste Start versucht es erneut. Der Dienst wird dabei nur gestoppt, wenn keine bestätigte Assoziation mehr übrig ist und keine andere Bestätigung läuft.
- **Nebenläufigkeit:** Die Schreiber von `CompanionSetup` (`reconcile`, `markPendingConfirmation`, `markConfirmed`, `cancelAssociation`, `forget`) laufen unter einem prozessweiten `synchronized`-Lock (`PAIRING_LOCK`) auf IO-Threads. Ausnahme: Der Dienst schreibt die Bestätigung über `persistConfirmation` direkt in `ConfirmedRemoteStore.confirm` (ein einzelner idempotenter `commit()`, ohne Lock).

**Zwei Startwege, die aus dem Gerätetest vom 17.10.2026 entstanden sind** (eine eingeschaltete Remote löst kein neues Präsenzereignis aus, siehe §11):

- *Startweg Dienst.* Trifft ein Präsenzereignis ein, bevor die App nach einem Update je geöffnet wurde, ist noch nichts bestätigt. `connectDecision` (`service/ConnectionGate.kt`) liefert `CONNECT`, `RESOLVE_MIGRATION` oder `REFUSE`. `RESOLVE_MIGRATION` gilt nur, wenn nichts bestätigt ist, keine Bestätigung läuft und das Migrationsfenster offen ist. Dann läuft `reconcile()` auf IO (dieselbe Regel wie in der Oberfläche) und danach **genau ein** weiterer Verbindungsversuch; ein Einmal-Flag (`migrationResolveAttempted`) verhindert Kreisen, auch wenn das Schreiben scheitert. Bei `REFUSE` (Fenster geschlossen, nie bestätigt) beendet sich der Dienst.
- *Startweg Oberfläche.* Nach einem `reconcile()` mit geltender Kopplung startet die Oberfläche den Dienst, falls keiner läuft (`shouldStartConnectionService`, `service/ServiceStart.kt`): nicht bei ausgeschaltetem Bluetooth (der Dienst würde unbegrenzt warten und eine Dauerbenachrichtigung hinterlassen; der Versuch wird bei `STATE_ON` wiederholt), nicht während einer Bestätigung, und nur ein Versuch je geltender Assoziation. Die App kann nicht fragen, ob die Remote in Reichweite ist (das bräuchte `BLUETOOTH_SCAN`); ist sie es nicht, scheitert die Verbindung und der Dienst beendet sich selbst (`FailureStopPolicy`).

**Anzeige.** Statuszeile und Benachrichtigung: „Tastendruck an der Remote wird erwartet“ (`RemoteStatusText.remoteLine`; Priorität nach „Nicht eingerichtet“, vor „Bluetooth ist aus …“ und allen Verbindungszuständen). Die Bestätigungskarte steht über der Statuskarte: Titel, Aufforderung mit fett gesetztem Remote-Namen, Restzeit als Zahl („Noch 23 s“) plus Balken ohne Animation, Hinweis „Falls das nicht deine Remote ist: einfach nichts drücken.“, Knopf „Abbrechen“. In den letzten 10 s wechselt der Container über Material-Rollen von `secondaryContainer` auf `errorContainer`; die Farbe trägt nie allein, Zahl und Balken sagen dasselbe. Für Screenreader ist die Restzeit nur gestuft (5, 10, 20, 30 s) als Live-Region (`Polite`) lesbar, der Balken ist ausgeblendet, die Handlungsanweisung ist ebenfalls eine Live-Region. Nach einem Fehlschlag erklärt eine Zeile den Grund („Es kam kein Tastendruck – die Kopplung wurde zurückgenommen.“); vor der Einrichtung weist ein Hinweis darauf hin, dass der Dialog alle Fernbedienungen in der Nähe listet.

### Betrieb

1. Remote eingeschaltet → `CompanionDeviceService.onDevicePresenceEvent` (`BLE_APPEARED`) → Start `RemoteConnectionService` (Foreground Service, Typ `connectedDevice`). Verbunden wird nur mit der bestätigten Assoziation (`connectDecision`, siehe oben).
2. `connectGatt(context, autoConnect=false, callback, TRANSPORT_LE)` → `discoverServices` → prüfen, dass Service FFF0 und Characteristic FFF4 existieren, sonst trennen und Fehlerstatus.
3. `setCharacteristicNotification(FFF4, true)` → `writeDescriptor(CCCD 0x2902, ENABLE_NOTIFICATION_VALUE)` mit der nicht-deprecated Signatur `writeDescriptor(descriptor, value)` → erst nach erfolgreichem `onDescriptorWrite` gilt die Remote als verbunden. Danach, strikt nacheinander, dasselbe für Battery Level 0x2A19 (Service FFF0); schlägt nur dieser Schritt fehl, bleibt die Remote verbunden und der Akkustand „unbekannt".
4. `onCharacteristicChanged(gatt, characteristic, value)` → `CratoniProtocolParser` → `RemoteCommand` → `ActionDispatcher`.
5. Verbindungsabbruch: `BluetoothGatt` schließen (`close`), begrenzte Reconnect-Versuche mit wachsendem Abstand, solange die Remote als anwesend gilt.
6. `BLE_DISAPPEARED`: Foreground Service beenden — außer eine Bestätigung läuft, die Verbindung steht (eine verbundene Remote wirbt nicht mehr) oder der Dienst wartet auf Bluetooth (`keepAliveReason`, Begründungen in §11, Gerätetest 12.09.2026).
7. Bluetooth aus: `BroadcastReceiver` für `BluetoothAdapter.ACTION_STATE_CHANGED`, zur Laufzeit im Service registriert (im Manifest nicht zulässig) → GATT und Helm-Verbindung schließen; der Dienst bleibt im Zustand „Bluetooth ist aus, wartet auf Aktivierung“ und verbindet bei `STATE_ON` selbst neu (das System sendet danach kein neues `BLE_APPEARED`, §11). Läuft gerade eine Bestätigung, endet sie stattdessen mit `BLUETOOTH_OFF`.
8. „Remote vergessen“: `stopObservingDevicePresence` + `disassociate` für jede Assoziation, danach `confirmed_association_id` und `pending_association_id` löschen (`migrated_legacy_association` bleibt), Dienst beenden, `RemoteStatus` zurücksetzen. Gelöscht wird nur, wenn keine Assoziation übrig blieb.

**Anmerkung:** `autoConnect=false` ist eine eigene Einschätzung (Präsenzereignis gibt den Zeitpunkt vor, Direktverbindung ist schneller) — im Gerätetest mit `autoConnect=true` vergleichen. nRF Connect nutzte ebenfalls `autoConnect=false`, `TRANSPORT_LE`; Service Discovery war nach ca. 0,9 s abgeschlossen (Richtwert für Timeouts).

## 4. Geräteidentifikation

- **Identität ist die bestätigte CDM-Assoziation** (seit 17.10.2026). Das System kennt alle Assoziationen der App, aber nicht, welche der Nutzer meinte; deshalb zählt nur die Assoziation, deren ID in `SharedPreferences` als bestätigt steht und die im System noch existiert (`CompanionSetup.confirmedAssociationId`). Gespeichert werden ausschließlich Assoziations-IDs, keine MAC und kein Name.
- Datei `pairing` (`ConfirmedRemoteStore`), drei Schlüssel, geschrieben mit `commit()` statt `apply()`, weil die Werte einen Prozesstod im nächsten Moment überstehen müssen:

| Schlüssel | Typ | Bedeutung |
|---|---|---|
| `confirmed_association_id` | Int (`-1` = keine) | die per Tastendruck bestätigte Assoziation |
| `pending_association_id` | Int (`-1` = keine) | Assoziation, die auf ihren bestätigenden Druck wartet; nur das Netz gegen Prozesstod |
| `migrated_legacy_association` | Boolean | die einmalige stille Übernahme einer Altkopplung ist erledigt; wird von „Remote vergessen“ nicht zurückgesetzt |

- Die CDM-Assoziation selbst ist die dauerhafte Merkung; sie übersteht Neustart der App, `adb install -r` und `am force-stop` (§11, 17.10.2026 a, g). Nach einer Deinstallation ist sie weg (§11, 12.09.2026). Die Datei `remote_battery` der Akkuwarnung ist davon getrennt.
- Primär zum Verbinden: MAC-Adresse aus der bestätigten Assoziation. Nach dem Verbinden zusätzlich: FFF0 + FFF4 vorhanden.
- Name nicht als alleiniges Merkmal (auch nicht zur Unterscheidung mehrerer Remotes: bestätigt wird per Tastendruck, nicht per Name): Er lautet „Remote control-3D2C", das Suffix entspricht den letzten zwei MAC-Bytes in umgekehrter Reihenfolge (MAC A4:C1:38:1B:2C:3D), der Name ist also generisch.
- Präfix A4:C1:38 ist ein Herstellerpräfix (auch zwei Petkit-Geräte in der Umgebung tragen es) → nicht eindeutig; nur die volle MAC zählt. Erstes Byte 0xA4 passt zu keinem Bitmuster zufälliger BLE-Adressen → öffentliche, feste Adresse (VERIFIZIERT am 11.09.2026: Verbindung über `getRemoteLeDevice(mac, ADDRESS_TYPE_PUBLIC)` ohne vorherigen Scan und ohne Bonding, mehrfach reproduziert, auch nach Aus-/Einschalten der Remote).
- Device Information 0x180A enthält nur PnP ID 0x2A50 (kein Hersteller-/Modellname); Wert noch nicht ausgelesen, ggf. zusätzliches Merkmal.

## 5. Beobachtete GATT-Struktur (nRF Connect, verbunden, nicht gebondet)

- 0x1800 Generic Access: Device Name 0x2A00, Appearance 0x2A01, PPCP 0x2A04
- 0x1801 Generic Attribute: Service Changed 0x2A05 (Indicate)
- 0x180A Device Information: PnP ID 0x2A50
- 0xFFF0: FFF4 (READ, NOTIFY; CCCD 0x2902, User Description 0x2901) · `00010203-0405-0607-0809-0a0b0c0d2b11` (READ, WRITE NO RESPONSE; 0x2901) · Battery Level 0x2A19 (nur NOTIFY, kein READ; CCCD 0x2902)
- `00010203-0405-0607-0809-0a0b0c0d1912`: `00010203-0405-0607-0809-0a0b0c0d2b12` (READ, WRITE NO RESPONSE; 0x2901)
- Die App schreibt NIE auf die WRITE-NO-RESPONSE-Characteristics.
- Klartextnamen aus den `0x2901`-Deskriptoren (nRF Connect, 12.09.2026, VERIFIZIERT): `FFF4` = „Uart2Phone", `…2b11` = „Phone2Uart", `…2b12` = „OTA". Der Service FFF0 ist damit eine serielle Brücke (UART über BLE), der Service `…1912` der Firmware-Update-Kanal.
- `FFF4` ist auch lesbar und liefert einen 3 Byte langen Statusrahmen — nicht das Tastenformat (`0F …`, 4 oder 5 Bytes). ENTSCHLÜSSELT am 26.09.2026: `[Prozent] [Spannung high] [Spannung low]`, Spannung als 16-Bit big-endian in Millivolt. Beispiele: `5A 0F FE` = 90 %, 4094 mV · `50 0F 42` = 80 %, 3906 mV · `46 0E ED` = 70 %, 3821 mV · `3C 0E 98` = 60 %, 3736 mV. Belege in §11.
- Weitere gelesene Werte: Device Name `0x2A00` = „RemoteControl" (der beworbene Name lautet dagegen „Remote control-3D2C"), Appearance `0x2A01` = 0x0000, PPCP `0x2A04` = Intervall 25–50 ms, Latenz 0, Supervision-Timeout 10 s; `…2b11` liefert 240 Nullbytes (leerer Sendepuffer), `…2b12` liefert `00`.
- Battery Level liegt ungewöhnlicherweise in FFF0 und ist nur per Notification lesbar; Wertformat (vermutlich uint8 Prozent) OFFEN.
- Gerätetest 11.09.2026: Der CCCD-Write für 2A19 gelingt ohne Bonding (Status 0), in rund 6 Minuten über zwei Verbindungen kam aber kein einziger Wert. Die Remote sendet den Akkustand offenbar nicht von sich aus. Geplante Klärung: gezielte Langzeitbeobachtung mit nRF Connect.
- PnP ID 0x2A50 = `02 8A 24 66 82 01 00`, nach Standardformat: Vendor ID Source USB-IF, Vendor ID 0x248A, Product ID 0x8266, Version 0x0001 (Zuordnung des Herstellers nicht verifiziert).

## 6. Protokoll-Parser

- Reines Kotlin, keine Android-Abhängigkeit, lokale Unit-Tests.
- Akzeptiert nur Länge 4 oder 5, Header `0x0F`, `checksum = (Summe aller vorherigen Bytes + 1) & 0xFF`.
- Zuordnung über exakte Bytefolge: die 9 Pakete aus `docs/anforderungen.md` plus `0F 59 01 6A` = `REAR_LIGHT` (Rücklicht-Taste, gemessen am 26.09.2026; Prüfsumme nach der Remote-Regel Summe + 1 bestätigt). Gültiges, unbekanntes Paket → `RemoteCommand.UNKNOWN`. Ungültiges Paket (leer, zu kurz, falsche Länge, falscher Header, falsche Checksumme) → Ergebnis „ungültig" mit Grund, wird nie ausgeführt.
- Byte 0x08 der Blinker-Pakete wird nicht interpretiert.
- Zuordnung Befehl → Ausführer im `ActionDispatcher` (Stand 16.10.2026):

| Befehl | Ausführer | Wirkung |
|---|---|---|
| `PLAY_PAUSE`, `NEXT`, `PREVIOUS` | `MediaKeyExecutor` | Medientaste |
| `VOLUME_UP`, `VOLUME_DOWN` | `VolumeExecutor` | Lautstärke, Stream nach Audio-Modus |
| `CALL` (seit 12.09.2026) | `CallExecutor` | Anruf annehmen bzw. auflegen |
| `TURN_LEFT`, `TURN_RIGHT`, `REAR_LIGHT` (seit 26.09.2026) | `LightExecutor` | schalten Blinker bzw. Rücklicht am Helm (§13) |
| `WALKIE_TALKIE` (seit 16.10.2026) | `AssistantExecutor` | startet den Sprachassistenten (§12) |
| `UNKNOWN` | — | `Ignored`, nur angezeigt und geloggt |

- Ein Ausführer kann „nichts zu tun" mit `false` melden (`CallExecutor`, `LightExecutor`, `AssistantExecutor`); der Dispatcher macht daraus `Ignored`, keinen Fehler. Ausnahmen der Ausführer werden zu `Failed`.
- Nur `UNKNOWN` wird ausschließlich angezeigt und geloggt. Die maßgebliche Menge der auslösenden Befehle steht als `ACTION_COMMANDS` in `action/` und wird von der UI mitgenutzt; ein Test bindet sie an das Verhalten des Dispatchers.
- Solange eine Bestätigung der Remote-Auswahl läuft (§3), führt der Dienst gar nichts aus: `dispatchAllowed(state)` in `service/DispatchGate.kt` ist genau dann `true`, wenn `pendingConfirmation == null`. Das bestätigende Paket wird nur angezeigt.
- Anzeigetext der Walkie-Talkie-Taste: „Sprachassistent" (`RemoteStatusText`, `strings.xml`); der Hinweis „wird nur angezeigt" entfällt für sie.

## 7. Berechtigungen und Manifest

| Permission | Art | Zweck |
|---|---|---|
| `BLUETOOTH_CONNECT` | Laufzeit | GATT; erfüllt zugleich die Voraussetzung für FGS-Typ `connectedDevice` (VERIFIZIERT) |
| `POST_NOTIFICATIONS` | Laufzeit | FGS-Benachrichtigung |
| `ANSWER_PHONE_CALLS` | Laufzeit | Anruf-Taste: annehmen und auflegen (seit 12.09.2026). Wird sie verweigert, bleibt nur diese Taste wirkungslos; die UI sagt das. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | normal | Foreground Service |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | — | Präsenzerkennung |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | — | FGS-Start aus dem Hintergrund (VERIFIZIERT als vorgesehene Ausnahme) |

Schutzstufen VERIFIZIERT am 12.09.2026 (`dumpsys package permissions` auf dem Testgerät): `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` und `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` sind `normal`, `FOREGROUND_SERVICE` und `FOREGROUND_SERVICE_CONNECTED_DEVICE` `normal|instant`, `BLUETOOTH_CONNECT` und `POST_NOTIFICATIONS` `dangerous` (Laufzeitabfrage), `BIND_COMPANION_DEVICE_SERVICE` `signature` — deshalb ist `exported=true` beim `CompanionDeviceService` unbedenklich. Weggelassen: `REQUEST_COMPANION_RUN_IN_BACKGROUND` (kein Beleg für Notwendigkeit, der FGS-Start ist bereits abgedeckt) und `BLUETOOTH_SCAN` (die App scannt nie selbst).

Bewusst nicht: Standort, `BLUETOOTH_SCAN` (das System scannt; nur ergänzen, falls `associate()` es im Test verlangt), NotificationListener, `WAKE_LOCK`, Legacy-Bluetooth-Permissions. `RECEIVE_BOOT_COMPLETED` nur, falls der Test zeigt, dass die Präsenzbeobachtung einen Neustart nicht übersteht.

Manifest-Features: `android.hardware.bluetooth_le` (required), `android.software.companion_device_setup`.

Manifest-Eintrag `<queries>` (seit 17.10.2026, keine Berechtigung): genau ein `<intent>` mit der Action `android.speech.action.VOICE_SEARCH_HANDS_FREE`. Ab Android 11 filtert die Paketsichtbarkeit; ohne den Eintrag liefert `resolveActivity` für diese Action `null`, und die Statuszeile „Zuständiger Assistent“ könnte die App nie nennen. Bewusst kein `QUERY_ALL_PACKAGES` (Entscheidung und Begründung: §2). Fehlt der Eintrag, steht in der Zeile „Nicht festgelegt“, also dasselbe wie bei einem Gerät ohne Assistent; nur wenn trotz leerer Kandidatenliste etwas aufgelöst wurde, schreibt `assistantTargetFor` eine Warnung ins Log.

Hinweise: Während der Ersteinrichtung muss laut Google-Guide der Standort-Schalter (Systemeinstellung) aktiv sein. Samsung: App zusätzlich auf „Nie im Standby" setzen — keine offizielle Quelle, dass CDM vor der One-UI-Akkuoptimierung schützt.

## 8. Package-Struktur (`de.werklade.cratoniremote.*`)

| Package | Inhalt |
|---|---|
| `protocol` | `RemoteCommand`, `CratoniProtocolParser` (reines Kotlin) |
| `ble` | `CratoniBleManager` (GATT-Ablauf), `GattUuids` |
| `companion` | `CompanionSetup` (associate/disassociate, `reconcile`, prozessweiter `PAIRING_LOCK`), `CratoniCompanionService`; seit 17.10.2026 `ConfirmedRemoteStore` (Datei `pairing`, drei Schlüssel, §4), `PairingReconciler` (reine Regel, §3), `PairingCleanup` (`decide`, `afterReconcile`, `confirmationToHeal`) |
| `service` | `RemoteConnectionService` (Foreground Service, Benachrichtigung); seit 17.10.2026 reine Regeln `DispatchGate` (`dispatchAllowed`), `ConnectionGate` (`connectAllowed`, `connectDecision`), `ServiceStart` (`shouldStartConnectionService`) und `ConfirmationPersistence` (`persistConfirmation`); seit 17.10.2026 `HelmetAclGate.kt` (reine Regel `isHelmetDevice`, `helmetShouldConnect`, dazu der Datentyp `AclDevice`; Test: `src/test/.../service/HelmetAclGateTest.kt`, 13 `@Test`) und im Dienst der Empfänger `helmetAclReceiver` samt `aclDevice()` (§2, §13) |
| `action` | `ActionDispatcher`; je Aktion ein Interface mit Implementierung, damit ein späterer NotificationListener-Weg lokal bleibt: `MediaKeyExecutor` (`AudioManagerMediaKeyExecutor`), `VolumeExecutor` (`AudioManagerVolumeExecutor`), `CallExecutor` (`TelecomCallExecutor`), `LightExecutor` (`HelmetLightExecutor`), `AssistantExecutor` (`MediaKeyAssistantExecutor`, seit 16.10.2026) |
| `helmet` | Rahmenbau, Lichtzustände und SPP-Client zum Helm (§13); seit 17.10.2026 hat `HelmetSppClient` den Lesezugriff `knownAddress()` (Adresse des zuletzt aufgelösten gekoppelten Helms, `null` davor) |
| `data` | `RemoteStatus` (StateFlow, geteilt von Service und UI; seit 17.10.2026 mit `pendingConfirmation`, `lastConfirmation`, `lastConfirmedAssociationId`), `RemoteStatusText` (Texte für Benachrichtigung und UI), seit 17.10.2026 `PairingConfirmation` (`PendingConfirmation`, `CONFIRMATION_TIMEOUT_MS`) |
| `ui` | `MainActivity` (hält die Logik der Oberfläche; `PAIRING_SCOPE` für Kopplungsarbeit, die die Komposition überleben muss; seit 17.10.2026 `rememberAssistantTarget`), `RemoteScreen` (Compose, Bestätigungskarte; seit 17.10.2026 `AssistantRow` in der Statuskarte, direkt über „Letzter Befehl“), seit 17.10.2026 `AssistantTarget.kt` (`AssistantTarget` mit `App`, `AskedOnPress`, `None`; reine Entscheidung `assistantTargetFor`, Namensbereinigung `assistantLabel`, `resolveAssistantTarget` gegen den `PackageManager`; Knopfziele `AssistantSettingsPage`, `assistantSettingsPages`, `openAssistantPage`, `openAssistantSettings`). Test: `src/test/.../ui/AssistantTargetTest.kt` (33 `@Test`). Ein `RemoteViewModel` existiert nicht; `MainActivity` begründet das im Kommentar von `RemoteRoute`. |

Regel: Die BLE-Schicht liefert nur Bytes und Verbindungszustand und führt keine Medienaktion aus.

## 9. Toolchain

VERIFIZIERT (lokal auf dem Entwicklungsrechner geprüft): SDK-Plattformen android-36, android-36.1, android-37.0; Build-Tools 36.0.0; Android Studio Build 261.26222.65; `JAVA_HOME` = JDK 17.

Aus Webrecherche, teils widersprüchlich, vor Verwendung zu verifizieren: AGP 9.4.0 (verlangt Gradle ≥ 9.6.0, JDK ≥ 17), Gradle 9.7.1, Kotlin 2.4.20. AGP 9 bringt Kotlin eingebaut mit — Plugin `org.jetbrains.kotlin.android` entfällt; Compose-Compiler-Plugin `org.jetbrains.kotlin.plugin.compose` bleibt separat.

Vorgehen: Exakte Versionen werden vor dem Anlegen des Version Catalogs direkt aus `maven-metadata.xml` (Google Maven / Maven Central) bzw. von Gradle ermittelt.

Geplante Abhängigkeiten: Compose BOM (material3), activity-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose, core-ktx, datastore-preferences; Tests: JUnit 4.

## 10. Umsetzungsschritte

| # | Schritt | Zuständig |
|---|---|---|
| 1 | Gradle-Gerüst, Version Catalog, leere Compose-Activity, Build läuft | android-engineer |
| 2 | Protokoll-Parser + Unit-Tests | android-engineer |
| 3 | Aktionsschicht (Medientasten, Lautstärke) inkl. Debug-Testauslöser per adb | android-engineer |
| 4 | `CratoniBleManager` inkl. Battery-Level-Notifications | android-engineer |
| 5 | CDM-Kopplung, `CompanionDeviceService`, Foreground Service, Manifest | android-engineer |
| 6 | UI inkl. Akkustand-Anzeige | ui-designer |
| 7 | README mit Testcheckliste | docs-writer |
| 8 | Nach V1: Anruf-Taste (12.09.2026), Akkustand und Akkuwarnung (26.09.2026), Helm-Lichter (26.09.2026, §13) | — |
| 9 | Nach V1: Walkie-Talkie-Taste startet den Sprachassistenten (16.10.2026, §12) inkl. Debug-Auslöser per adb und Unit-Tests | — |
| 10 | Nach V1: Remote aus einer Liste wählen und per Tastendruck bestätigen (17.10.2026, §3, §11): Namensmuster, Bestätigungszustand und Dienst-Frist, `ConfirmedRemoteStore`, `PairingReconciler`/`PairingCleanup`, Sperren in Dispatch, Helm und Akku, zwei Startwege, Bestätigungskarte; 355 Unit-Tests, Gerätetest am S25 Ultra | — |
| 11 | Nach V1: A1, Anzeige des zuständigen Sprachassistenten in der Statuskarte mit Knopf in die Systemeinstellungen (17.10.2026, §2, §12, §11): `ui/AssistantTarget.kt`, `<queries>`-Eintrag, Zeile `AssistantRow`, `rememberAssistantTarget` in `MainActivity`; 388 Unit-Tests (33 davon für Auflösung, Namensbereinigung und Reihenfolge der Knopfziele), Gerätetest am S25 Ultra | — |
| 12 | Nach V1: SPP-Verbindung zum Helm auch dann, wenn der Helm erst nach der Fernbedienung eingeschaltet wird (17.10.2026, §2, §11, §13): `HelmetSppClient.knownAddress()`, reine Regel `service/HelmetAclGate.kt`, Empfänger `helmetAclReceiver` für `ACTION_ACL_CONNECTED`/`ACTION_ACL_DISCONNECTED` in `RemoteConnectionService` (registriert mit `RECEIVER_EXPORTED`, Begründung §2); 401 Unit-Tests (13 davon für die Regel), Gerätetest am S25 Ultra | — |

Jeder Schritt wird von code-reviewer geprüft; Commits nur auf Ansage des Projektinhabers.

## 11. Offene Punkte (nur am Gerät klärbar)

- Advertising-Daten — VERIFIZIERT am 12.09.2026: Die Remote wirbt OHNE Service-UUIDs (`mServiceUuids=null` im vom System gespeicherten ScanRecord der Assoziation). Ein ScanFilter auf 0xFFF0 hätte sie im Kopplungsdialog ausgeschlossen; der Filter läuft deshalb über das Namensmuster (bis 16.10.2026 `^Remote control-.*$`, seit 17.10.2026 streng `^Remote control-[0-9A-Fa-f]{4}$`, §3).
- MAC-Adresse — VERIFIZIERT am 11.09.2026: nach Aus-/Einschalten der Remote unverändert, Verbindung mit derselben Adresse erfolgreich.
- Battery Level 0x2A19 — ERLEDIGT durch Verzicht: Notifications sind aktiviert (Status 0), die Remote hat in zwei Wochen Dauerbetrieb kein einziges Mal gesendet, auch nicht unter nRF Connect, und lesen lässt sich die Characteristic nicht. Sie bleibt abonniert, ist aber keine Quelle. Der Akkustand kommt stattdessen aus dem Statusrahmen von `FFF4` (siehe unten).
- Akkustand — Analyse des HCI-Mitschnitts der Hersteller-App `com.lscsmartconnection.smart` (12.09.2026, btsnoop aus einem Bugreport, Parser im Scratchpad, Verbindungszuordnung über LE-Connection-Complete-Events, 60 ATT-PDUs vollständig ausgewertet):
  - BELEGT: Die Hersteller-App aktiviert die Notifications auf `FFF4` und führt **genau einen Lesezugriff auf `FFF4`** aus; Antwort `5A 10 03` bei angezeigtem Symbol „voll". Danach kein weiterer Verkehr.
  - BELEGT: Sie schreibt **nie** auf „Phone2Uart" (`…2b11`) — es gibt keinen Abfragebefehl, den die App nachbauen müsste. Der OTA-Kanal wird nicht berührt.
  - BELEGT: Sie abonniert und liest die Standard-Characteristic `0x2A19` **nicht**. Für die Hersteller-App ist sie unbenutzt; unsere Annahme, der Wert käme von dort, war falsch.
  - VERMUTET: Das dritte Byte von `5A 10 XX` trägt den Ladezustand, den die App in ihr Symbol übersetzt.
  - NICHT ERMITTELBAR: die Zuordnung Byte → Ladezustand. Es gibt nur diesen einen Datenpunkt; die manuellen nRF-Lesungen am selben Tag ergaben bei ebenfalls vollem Akku 05, 07, 09 und 0B. Alle bisher gesehenen Werte sind ungerade (03, 05, 07, 09, 0B), was zu einer gestuften Skala passen könnte — ohne weitere Messpunkte bleibt das Spekulation.
  - Die bekannte Prüfsummenregel der `0F`-Tastenpakete gilt für die `5A`-Rahmen NICHT (`(0x5A+0x10+1) & 0xFF = 0x6B ≠ 0x03`).
  - Nächster Schritt war: Werte über mehrere Ladezustände erheben. Das ist geschehen, siehe unmittelbar folgender Abschnitt.

### Akkustand entschlüsselt (26.09.2026)

Datengrundlage: 50 Messpunkte vom 12.09. bis 24.09.2026 aus `files/fff4-probe.log` des Debug-Builds (je ein Lesezugriff auf `FFF4` pro Verbindung), dazu Screenshots der Hersteller-App aus demselben Zeitraum.

**Format des Statusrahmens:** `[Byte0] [Byte1] [Byte2]` = Ladestand in Prozent, dann Zellenspannung in Millivolt als 16-Bit big-endian.

| Byte 0 | Ladestand | gemessener Spannungsbereich | Zeitraum |
|---|---|---|---|
| `5A` | 90 % | 4101 – 4061 mV | 12.09. |
| `50` | 80 % | 3967 – 3904 mV | 13.09. vormittags |
| `46` | 70 % | 3895 – 3810 mV | 13.09. nachmittags bis 14.09. |
| `3C` | 60 % | 3797 – 3727 mV | ab 14.09. abends |

**Belege**

- Byte 0 nimmt ausschließlich Vielfache von 10 an und fällt über zwei Wochen monoton — die Remote meldet also in Zehnerstufen.
- Die Spannung fällt von 4,10 V auf 3,73 V, die Entladekurve einer einzelnen Lithium-Zelle. Die Stufenwechsel liegen an sauberen Schwellen: 80 → 70 bei rund 3,90 V, 70 → 60 bei rund 3,80 V.
- Die Screenshots der Hersteller-App zeigen im selben Zeitraum einen schrumpfenden Balken, von voll am 13.09. auf etwa halb ab dem 15.09. Die App zeigt keine Zahl, die Gegenprobe bestätigt also die Richtung, nicht den exakten Wert.
- Aufgelöst: Die früher beobachteten „ungeraden, springenden" Werte (03, 05, 07, 09, 0B) waren nie ein eigener Wert, sondern das niederwertige Byte der Spannung — 4099, 4101, 4103 mV und so fort.
- Ausgeschlossen: Eine im Screenshot sichtbare Zahl „65" stammte vom Lautstärkeregler des Systems, nicht von der Hersteller-App.

**Nebenbefund:** Zwischen dem 15.09. und 24.09. protokollierte unsere App keine einzige Verbindung, während die Hersteller-App „Verbunden" zeigte. Die Remote nimmt nur eine Verbindung gleichzeitig an — wer die Hersteller-App benutzt, sperrt unsere App aus und umgekehrt. Nach dem 24.09. war die Remote ausgeschaltet.

**Plausibilitätsprüfung:** Am 24.09. meldete die Remote 3736 mV, nach zwölf Tagen im ausgeschalteten Zustand am 26.09.2026 wieder 3738 mV bei unverändert 60 %. Die Selbstentladung ist also nicht messbar, der Wert ist stabil und nicht verrauscht.

**Abfragerhythmus:** Gelesen wird beim Verbinden und danach alle 5 Minuten. Liefert der Lesezugriff gerade ein Tastenpaket statt des Statusrahmens, folgt genau ein kurzer Wiederholungsversuch nach 2 Sekunden, danach wieder der 5-Minuten-Takt. Eine verspätet eintreffende Antwort wird übernommen statt verworfen. Am Gerät bestätigt am 26.09.2026: 21:02:40 `3C 0E 93` = 60 %, 3731 mV, dann 21:07:40 `3C 0E 91` = 60 %, 3729 mV.

**Anzeige:** Akkusymbol mit zehn Segmenten, Füllung aufgerundet, unter 20 % in der Fehlerfarbe, daneben „60 % · 3,73 V" und der Messzeitpunkt. Ab 12 Stunden Alter zeigt der Zeitpunkt zusätzlich das Datum — eine Verbindung kann Mitternacht überdauern, und die Zeitgeber stehen im Tiefschlaf still.

**Warnung am Gerät geprüft (26.09.2026):** Mit simulierten 20 % erschien die Warnung „Akku bei 20 % (3,68 V)" auf dem eigenen Kanal; der nächste echte Messwert von 60 % stellte die Schwelle wieder scharf. Nebenbefund: One UI hatte die App app-weit auf lautlos gesetzt (`isNotificationAlertsEnabled=false`), weshalb die Warnung trotz Kanal-Wichtigkeit 3 ohne Ton zugestellt wurde — eine Systemeinstellung, kein Fehler der App.

**Bleibt unsicher:** ob Byte 0 exakt „Prozent" bedeutet oder eine firmware-eigene Stufenanzeige ist. Die App zeigt den gemeldeten Wert unverändert an und rechnet nichts um.
- Medientasten — VERIFIZIERT am 11.09.2026 (S25 Ultra, Android 16 / API 36.1, One UI 8.5) mit Amazon Music: Play/Pause, Next, Previous per `dispatchMediaKeyEvent`, ausgelöst über den Debug-BroadcastReceiver bei App im Hintergrund und pausierter Media-App; ungültiges Paket löst nichts aus. OFFEN: Auslösung aus dem Foreground Service (Schritt 5), Media-App vollständig beendet, andere Apps (Spotify/YouTube Music auf dem Testgerät nicht installiert).
- Lautstärke — VERIFIZIERT am 11.09.2026: `adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/ADJUST_LOWER, FLAG_SHOW_UI)` ändert die Musiklautstärke (4 → 5 → 4) ohne Permission, das Lautstärke-Panel erscheint, ausgelöst aus dem Hintergrund.
- Lautstärke im Gespräch — BEFUND des Nutzers am 12.09.2026: Die Tasten regelten auch während eines Telefonats nur die Medienlautstärke, weil der Stream fest auf `STREAM_MUSIC` stand. Behoben durch die Wahl des Streams nach `AudioManager.mode` (siehe §2).
- Lautstärke bei Spotify Connect / Cast: `adjustStreamVolume` wirkt nur auf lokale Wiedergabe (VERIFIZIERT) — reicht das in der Praxis?
- Zuverlässigkeit und Latenz von `onDevicePresenceEvent` beim Einschalten, nach Bluetooth aus/an, nach Neustart.
- Verlangt `associate()` in der Praxis `BLUETOOTH_SCAN`?
- Einfluss der One-UI-Akkuoptimierung trotz CDM.
- GATT-Fehler 133 — VERIFIZIERT am 11.09.2026: trat genau einmal auf, beim Reconnect-Versuch gegen die noch ausgeschaltete Remote; der nächste Versuch verband normal.

### Ergebnisse des BLE-Gerätetests vom 11.09.2026 (Schritt 4, App im Vordergrund)

- Verbindungsaufbau 0,36 s, Service Discovery 0,79 s (zweite Verbindung 0,79 s), CCCD-Writes für FFF4 und 2A19 jeweils Status 0.
- Alle 9 bekannten Tastenpakete kamen unverändert an und wurden korrekt erkannt; Zeit vom Paket bis zur ausgeführten Aktion 4–25 ms.
- Jede Taste sendet genau ein Paket pro Druck; ein Ereignis beim Loslassen gibt es bei keiner Taste.
- Verbindungsverlust beim Ausschalten der Remote: Status 8 nach 5 min 16 s stabiler Verbindung; Reconnect gelang im zweiten von drei Versuchen, Gesamtausfall rund 8,6 s. Die Abstände 2/5/10 s haben sich bewährt.
- Kein Absturz, keine ungültigen Pakete.
- Nachtest nach den Review-Korrekturen (12.09.2026, 00:00 Uhr): gleiche Werte (Connect 757 ms, Discovery 798/799 ms, alle CCCD-Writes Status 0); der Reconnect nach dem Ausschalten gelang diesmal bereits im ersten Versuch nach 2 s, Ausfall rund 3,2 s; Tastenpakete davor und danach korrekt ausgeführt.
- OFFEN, nur Diagnose: Nach diesem zweiten Reconnect fehlte die Logzeile des PnP-ID-Lesens, während sie bei allen anderen Verbindungen erschien. Keine Funktion hängt daran; im Review von Schritt 5 prüfen, ob der Lesevorgang bei zeitgleich eintreffenden Notifications ausgelassen oder nur nicht protokolliert wird.

### Vorgabe für Schritt 5: Zeitgeber im Tiefschlaf

`Handler.postDelayed` nutzt `uptimeMillis`; diese Zeitbasis steht im Suspend/Doze still. Reconnect-Verzögerungen und Timeouts der BLE-Schicht laufen deshalb bei ausgeschaltetem Bildschirm womöglich erst beim nächsten Aufwachen weiter — genau der Betriebsfall (Remote am Lenker, Handy in der Tasche). In Schritt 5 daher: Wiederverbinden primär durch CDM-Präsenzereignisse auslösen (die das Gerät wecken); falls doch eigene Verzögerungen nötig sind, `AlarmManager.setAndAllowWhileIdle` statt Handler verwenden, kein WakeLock. Testfall aufnehmen: Bildschirm aus, Remote ausschalten, 2–3 Minuten warten, Verhalten prüfen.

**ERLEDIGT am 12.09.2026:** Der Testfall wurde gefahren, siehe nächster Abschnitt — das Präsenzereignis weckt das Gerät zuverlässig, ein `AlarmManager`-Weckruf ist nicht nötig.

### Ergebnisse des Gerätetests vom 12.09.2026 (Schritt 5, Kopplung + Foreground Service)

- Kopplung über den Systemdialog mit Namensmuster erfolgreich (Assoziation 8, `mNotifyOnDeviceNearby=true`); erste Verbindung 4 s später ohne weiteres Zutun. Foreground Service läuft mit Typ `connectedDevice` (0x10) und stillem Kanal (`IMPORTANCE_LOW`).
- VERIFIZIERT: Tastendrücke wirken bei geschlossener App aus dem Foreground Service heraus (Amazon Music) — der offene Punkt aus §11 zur Auslösung aus dem Dienst ist damit geschlossen.
- VERIFIZIERT: `BLE_DISAPPEARED` trifft regelmäßig 9–10 s nach dem Verbinden ein, obwohl die Verbindung steht (ein verbundenes Peripheriegerät wirbt nicht mehr). Ohne die Sonderbehandlung würde der Dienst jede funktionierende Verbindung selbst beenden.
- VERIFIZIERT: Remote ausschalten → Status 8; die drei Reconnect-Versuche scheitern nach je rund 10 s mit Status 147 (Timeout des Stacks, unsere 15-s-Grenze greift nie). Danach beendet sich der Dienst, und das nächste `BLE_APPEARED` verbindet neu (Verbindung 0,24 s, Discovery 0,63 s).
- VERIFIZIERT (Kernfall): Bildschirm aus, Remote 2 Minuten aus, dann einschalten — das Präsenzereignis erreicht die App trotz ausgeschaltetem Bildschirm, die Verbindung steht in unter 1 s, Tastendrücke wirken sofort.
- FEHLER GEFUNDEN UND BEHOBEN: Nach Bluetooth aus/an kam kein neues Präsenzereignis (das System führte die Remote durchgehend als anwesend), der Dienst hing dauerhaft in „Bluetooth ist aus". Er verbindet jetzt beim Einschalten selbst; gemessen rund 2 s bis zur Verbindung. Ein verspätetes `BLE_APPEARED` bei ausgeschaltetem Bluetooth führt nur noch in den Wartezustand, nicht in einen Fehler.
- VERIFIZIERT (Neustart des Telefons, 12.09.2026): Rund 50 s nach dem Booten meldet das System von sich aus `BLE_APPEARED` für die Assoziation, bindet `CratoniCompanionService` und erlaubt den Start des Foreground Service aus dem Hintergrund ausdrücklich mit der Begründung `code:COMPANION_DEVICE_MANAGER` (Systemlog `ActivityManager: Background started FGS: Allowed`). Die Remote blieb dabei durchgehend eingeschaltet. `RECEIVE_BOOT_COMPLETED` wird nicht gebraucht. Nebenbefund: Eigene App-Logzeilen aus der Bootphase fehlen im Logcat, während Systemzeilen vorhanden sind — vermutlich verwirft One UI App-Logs während des Hochfahrens (ANGENOMMEN, nur ein Protokoll-Effekt).
- Nach einem App-Update startet kein Dienst, solange die Remote durchgehend anwesend bleibt, weil das auslösende Ereignis fehlt (Stand 12.09.2026: offen, Abhilfe Remote aus- und einschalten). Seit 17.10.2026 über zwei Startwege behandelt (§3); am Gerät belegt ist der Weg über den Dienst (Gerätetest 17.10.2026 a), der Weg über die Oberfläche ist unbewiesen (dort unter „Offen“).
- VERIFIZIERT (12.09.2026): Beim **Deinstallieren** entfernt Android die Companion-Verknüpfung und die Laufzeitberechtigungen mit; danach ist eine neue Kopplung nötig (Systemdump vermerkt die Assoziation mit `pkg-data-cleared`). Ein Update über `adb install -r` behält dagegen beides.

### Ergebnisse des Gerätetests vom 12.09.2026 (Anruf-Taste)

Getestet mit zwei Telefonen, erst über den Debug-Befehl, dann mit der echten Taste.

- VERIFIZIERT: Klingelnder Anruf → `ringing call accepted`, der Anruf wird angenommen (nicht abgelehnt).
- VERIFIZIERT: Laufendes Gespräch → `endCall() returned true`, es wird aufgelegt.
- VERIFIZIERT: Kein Anruf → `no call in progress (audio mode 0)`, Ergebnis `Ignored`, kein Nebeneffekt; die Musik läuft weiter.
- VERIFIZIERT: `hasManageOngoingCallsPermission()` = false — Beleg, dass die profillose Association den `InCallService`-Weg nicht öffnet.
- Beobachtung: Bei einem eingehenden Anruf pausiert bzw. verstummt die Musik ohnehin durch das System, nicht durch unsere App.
- VERIFIZIERT: Annehmen und Auflegen funktionieren auch mit der echten Taste an der Fernbedienung, nicht nur über den Debug-Befehl.
- VERIFIZIERT (Lautstärke im Gespräch): Während eines Telefonats blieb die Medienlautstärke unverändert (3), die Gesprächslautstärke wanderte von 15 auf 13, und das Panel am Gerät zeigte „Gesprächslautstärke". Beobachtung am Rand: Zwei Mal leiser und ein Mal lauter müssten 14 ergeben, gemessen wurden 13 — vermutlich führt Android die Gesprächslautstärke je Ausgabeweg getrennt (Hörmuschel, Lautsprecher, Bluetooth) und die Abfrage meldet einen anderen Weg als den aktiven. Nicht weiter verfolgt, da hörbar korrekt.
- VERIFIZIERT (Doppeldruck-Schutz): Annehmen mit der Taste und sofortiger zweiter Druck beenden das Gespräch nicht; nach Ablauf der Sperre legt ein Druck normal auf.

**Bekannte Grenzen der Anruf-Taste**

- **Anklopfen:** Läuft ein Gespräch und ein zweiter Anruf klingelt, steht der Audio-Modus auf „im Gespräch". Der Tastendruck beendet dann das laufende Gespräch, statt den zweiten anzunehmen. Nur mit `InCallService` lösbar.
- **Doppeldruck:** Nach dem Annehmen wechselt der Modus binnen 0,2–0,5 s; ein zweiter Druck würde sonst sofort auflegen. Abgesichert durch eine Sperre von 3 s, die nur das Auflegen betrifft.
- **VoIP (WhatsApp) — GEMESSEN am 12.09.2026:** Die Anruf-Taste bleibt wirkungslos. WhatsApp setzt den Audio-Modus schon beim Klingeln auf `MODE_IN_COMMUNICATION`, die App versucht deshalb zu beenden statt anzunehmen, und `endCall()` liefert bei selbstverwalteten Anrufen `false` → Ergebnis `Ignored`, vier Tastendrücke ohne Wirkung und ohne Schaden. Merke: Für eine VoIP-App, die ihre Anrufe über Telecom verwalten ließe, würde diese Heuristik beim Klingeln auflegen statt annehmen — dann wäre der `InCallService`-Weg nötig. Die Lautstärketasten wirken im WhatsApp-Gespräch dagegen korrekt auf die Gesprächslautstärke.
- **Notrufe:** `endCall()` kann sie laut Doku nicht beenden, Rückgabe `false` → `Ignored`.
- `acceptRingingCall()` liefert `void`: Ein `Executed` bedeutet „aufgerufen, keine Ausnahme", nicht „nachweislich verbunden".
- Hinweis für String-Ressourcen: Ein gerades `"` wird von AAPT verschluckt (Sonderbedeutung). Deutsche Anführungszeichen `„ “` verwenden — einmal im Samsung-Hinweis aufgetreten und korrigiert.

### Ergebnisse des Gerätetests vom 16.10.2026 (Walkie-Talkie-Taste → Sprachassistent)

Aufbau: S25 Ultra (Android 16, One UI 8.5), echte Remote und Helm verbunden, Handy gesperrt; Produktionspfad (Taste → Parser → `ActionDispatcher` → `MediaKeyAssistantExecutor`). Ohne Remote lässt sich derselbe Pfad im Debug-Build auslösen (PowerShell oder Bash, Gerät per adb verbunden):

```
adb shell am broadcast --user 0 -n de.werklade.cratoniremote/.debug.DebugCommandReceiver --es command WALKIE_TALKIE
```

`--user 0` ist nötig, weil das Testgerät einen Secure-Folder-Nutzer (150) hat.

- (a) VERIFIZIERT, laufende Musik: 5 Drücke, jeder startet den Assistenten (Google `HandsFreeActivity`); der lange Druck folgt nach 401–402 ms, die Musik läuft danach weiter.
- (b) VERIFIZIERT, Doppeldruck mit 250 ms Abstand: Der zweite Druck wird ignoriert, der Assistent startet genau einmal.
- (c) VERIFIZIERT, ohne Musik, knapp 6 Minuten ohne Druck, Bildschirm aus: Der lange Druck folgt ebenfalls nach 401 ms. Der Tiefschlaf verzögert die Folge also nicht; ein WakeLock ist nicht nötig.
- (d) BEOBACHTET, Ursache ANGENOMMEN: Bei 3 von 5 Drücken kam das UP erst nach rund 1,9 s statt nach rund 0,5 s. Vermutlich blockiert das System den Aufruf, während der Assistent öffnet. Folgenlos, weil der Assistent dann schon gestartet ist; die Doppeldruck-Sperre dauert in diesen Fällen entsprechend länger. Der Main-Thread der App ist in dieser Zeit blockiert (ANGENOMMEN: `dispatchMediaKeyEvent` läuft synchron über Binder); ohne geöffnete App folgenlos, weit unter den ANR-Schwellen für Dienste und Broadcasts.
- Der Nutzer bestätigt: „läuft wie gewollt, kurz drücken reicht".
- NICHT am Gerät geprüft: der Abbruchzweig (UP mit `FLAG_CANCELED` bei Fehler oder Dienst-Ende mitten in der Folge) und die Sperren bei Klingeln, Telefonat und `MODE_IN_COMMUNICATION` (die Logik ist durch Unit-Tests in `MediaKeyAssistantExecutorTest` abgedeckt, nicht aber das Systemverhalten; die Gründe stehen in §2 und sind aus dem AOSP-Quellcode ANGENOMMEN).

### Ergebnisse des Gerätetests vom 17.10.2026 (Remote-Auswahl aus einer Liste, Bestätigung per Tastendruck)

Aufbau: Samsung S25 Ultra (SM-S938B), Android 16, One UI 8.5, echte Remote und Helm; Bedienung über die Oberfläche, Auswertung über Logcat (Uhrzeiten aus dem Logcat). Das Testgerät hat einen Secure-Folder-Nutzer (150), `adb`-Befehle brauchen `--user 0`.

**Unit-Tests:** 355 `@Test` im Quellbaum (nachgezählt), 0 Fehler laut Testlauf des Projektinhabers vom 17.10.2026. Die neuen Regeln sind einzeln getestet: `PairingReconciler` 18, `PairingCleanup` 14, `ConnectionGate` 13, `ServiceStart` 7, `PairingConfirmation` 6, `DispatchGate` 3, `ConfirmationPersistence` 3.

- (a) VERIFIZIERT, **Fehler gefunden und behoben**: Bestandsinstallation, `adb install -r`, Remote eingeschaltet, bevor die App geöffnet wurde. Der erste Stand lehnte ab („association 10 is not the confirmed remote, stopping“). Weil eine eingeschaltete Remote kein neues Präsenzereignis auslöst, blieb die Fernbedienung tot, bis der Nutzer sie aus- und einschaltete. Behoben über `RESOLVE_MIGRATION` (§3, Startweg Dienst). Nachtest 17:18:21: „nothing confirmed yet, settling the pairing before connecting (10)“ → Reconcile übernimmt 10 → verbunden 17:18:23, Akku 70 % (3,81 V), Helm verbunden, alles ohne App-Start.
- (b) VERIFIZIERT, Einrichten von Null nach `pm clear`: Der Dialog zeigt den lesbaren Namen „Remote control-3D2C“, nicht die MAC — die frühere Annahme, es erscheine nur die MAC, ist damit geklärt. 17:21:59 Assoziation 12 erzeugt und als wartend vermerkt; drei Reconciles während der Bestätigung ließen sie unangetastet. 17:22:05 Tastendruck: „PLAY_PAUSE confirms the association, shown but not dispatched“, gespeichert, Helm verbunden; die folgenden Drücke wirken normal.
- (c) VERIFIZIERT, Fristablauf: 17:25:00.580 gewählt, 17:25:30.580 „ends as TIMED_OUT, taking it back“ — exakt 30 s. Assoziation entfernt, Datei sauber. Anzeige: „Es kam kein Tastendruck – die Kopplung wurde zurückgenommen.“
- (d) VERIFIZIERT, Zurück-Taste während der Bestätigung, App geschlossen: Assoziation 14; die Frist lief im Dienst ab (17:26:44.740 → 17:27:14.740), die Kopplung wurde entfernt.
- (e) VERIFIZIERT, Bluetooth während des Countdowns aus: Assoziation 15, 2,2 s nach dem Start „ends as BLUETOOTH_OFF, taking it back“.
- (f) VERIFIZIERT, „Remote vergessen“: Dienst beendet, Helm getrennt, Vermerke gelöscht, Assoziation entfernt.
- (g) VERIFIZIERT, `am force-stop` bei bestätigter Kopplung: `pairing.xml` und Assoziation 16 unverändert. Beobachtung: 23 s später meldete das System die Remote von selbst wieder (eine getrennte Remote wirbt erneut), und ein neuer Prozess verband sofort. Der Startweg über die Oberfläche wurde dadurch nicht durchlaufen.
- (h) VERIFIZIERT, alle Tasten nach dem Einrichten: Play/Pause, Blinker links/rechts, Rücklicht, Lautstärke, Sprachassistent; Reconnect nach Bluetooth aus/an („Bluetooth is on, connecting again“).
- (i) BEOBACHTET, nicht neu: 17:19:41 ein Funkabbruch (status=8) nach 78 s mit selbständiger Wiederherstellung in 12 s. Gehört zum offenen Thema Funkstörung (Befund „Telefonat“ in §13).

**Offen:**
- Startweg über die Oberfläche (§3) am Gerät unbewiesen: Der Fall „Kopplung gilt, kein Dienst läuft, Remote schon an“ trat nicht ein (siehe g, das System meldete die Remote selbst).
- Liste mit mehreren Remotes nie geprüft, weil nur eine Remote vorhanden ist. Geplant mit einem zweiten Handy und einem nRF-Connect-Advertiser (Name nach dem Muster); das würde eine Liste mit zwei Einträgen, die falsche Auswahl und die ausbleibende Bestätigung belegen.
- TalkBack-Verhalten der Live-Regions (gestufte Restzeit, Handlungsanweisung, Fehlertext) nicht geprüft.
- Prozesstod in den Millisekunden zwischen Tastendruck und Schreiben: dreifach abgesichert (Dienst schreibt in `NonCancellable`, Oberfläche schreibt idempotent mit, `reconcile()` heilt „Zustand bestätigt, Platte nicht“); im schlimmsten Rest bleibt „neu einrichten“. Nicht am Gerät provoziert.
- Eine Remote mit abweichendem Namensformat erscheint durch das strenge Muster nicht im Dialog.
- Neustart des Telefons mit dem neuen Stand nicht erneut geprüft (der Neustart-Test vom 12.09.2026 lief mit der Einzelkopplung).

### Ergebnisse des Gerätetests vom 17.10.2026 (A1: Anzeige des zuständigen Assistenten)

Aufbau: Samsung S25 Ultra, Android 16, One UI 8.5; `adb` mit `--user 0` (Secure-Folder-Nutzer 150 auf dem Testgerät), Auswertung über Logcat. Die Abfragen laufen in PowerShell oder Bash bei per adb verbundenem Gerät.

```
adb shell cmd package resolve-activity -a android.speech.action.VOICE_SEARCH_HANDS_FREE
adb shell cmd package resolve-activity -a android.speech.action.WEB_SEARCH
```

- (a) VERIFIZIERT, `VOICE_SEARCH_HANDS_FREE`: aufgelöst auf `com.google.android.googlequicksearchbox/…voicesearch.handsfree.HandsFreeActivity`, `isDefault=true`.
- (b) VERIFIZIERT, `WEB_SEARCH`: aufgelöst auf `android/com.android.internal.app.ResolverActivity`, `isDefault=false` — für diese Action ist kein Standard gesetzt.
- (c) VERIFIZIERT, Tastendruck bei ENTSPERRTEM Handy mit eingeschaltetem Bildschirm: Logcat zeigt `MediaSessionService: about to use ACTION_VOICE_SEARCH_HANDS_FREE` und den Start der `HandsFreeActivity`. One UI nimmt die Freisprech-Aktion also auch im entsperrten Zustand. Das weicht vom AOSP-Standard ab: Dort wählt `MediaSessionService.startVoiceInput` bei entsperrtem Gerät mit eingeschaltetem Bildschirm `ACTION_WEB_SEARCH`, sonst `ACTION_VOICE_SEARCH_HANDS_FREE` (aus AOSP gelesen; für andere Geräte ANGENOMMEN). Deshalb wertet die App nur die Freisprech-Aktion aus und zeigt genau einen Wert.
- (d) VERIFIZIERT, in der App: Die Zeile zeigt „Google“, der Knopf öffnet die App-Info von Google. Logcat bestätigt die Auflösung; der `<queries>`-Eintrag greift also aus der UID der App heraus.
- (e) Unit-Tests: 388 `@Test` im Quellbaum (nachgezählt), 0 Fehler laut Testlauf vom 17.10.2026. 33 davon in `AssistantTargetTest` (Auflösung, Namensbereinigung, Reihenfolge der Knopfziele). Das sind 33 mehr als die 355 des Standes „Remote-Auswahl“.

**Offen:**
- Geräte ohne One UI: Welche Aktion dort greift (AOSP-Verhalten laut (c) ANGENOMMEN), ist ungeprüft. Ein Pixel 7 ist als Vergleichsgerät vorgesehen. Ein Moto Edge 20 Lite kann die App nicht installieren (`minSdk = 36`, das Gerät endet bei Android 12); dort ist nur die adb-Abfrage möglich.
- Chooser-Zustand („Wird beim Drücken abgefragt“) am Gerät nicht gesehen; herstellbar nur, indem die Voreinstellung aufgehoben wird. Nur durch Unit-Tests der Entscheidung abgedeckt.
- TalkBack-Ansagen der Zeile (Live-Region, lange Beschreibung des Knopfes) nicht geprüft.
- Ein tatsächlich überlanger Fremdname wurde nicht gesehen; Kürzung auf 60 Zeichen und `valueMaxLines = 2` nur durch Unit-Tests bzw. Code belegt.
- Zeile bei Arbeitsprofil oder Secure Folder: nicht geprüft (Grenze siehe §12).

### Ergebnisse des Gerätetests vom 17.10.2026 (Helm nach der Fernbedienung einschalten)

Aufbau: Samsung S25 Ultra, Android 16, One UI 8.5, echte Remote und Helm; Auswertung über Logcat (Uhrzeiten aus dem Logcat). Entscheidung und Regel: §2, Ablauf: §13.

**Messung zum Empfänger (RECEIVER_EXPORTED statt RECEIVER_NOT_EXPORTED) — GEMESSEN:** Mit `Context.RECEIVER_NOT_EXPORTED` kamen die ACL-Rundrufe (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`) **nie** an. Der Dienst lief, `BLUETOOTH_CONNECT` war erteilt, und andere Apps empfingen dieselben Ereignisse im selben Moment (im Systemlog belegt). Erst mit `Context.RECEIVER_EXPORTED` funktioniert es. Der Empfänger für `BluetoothAdapter.ACTION_STATE_CHANGED` im selben Dienst arbeitet unverändert mit `RECEIVER_NOT_EXPORTED` (§3, Betrieb, Punkt 7). Die Ursache wurde nicht ermittelt; ob sich andere Geräte oder Android-Stände gleich verhalten, ist ungeprüft. Warum das vertretbar ist: §2.

**Ablauf am Gerät (Fernbedienung durchgehend verbunden) — VERIFIZIERT:**

| Uhrzeit | Ereignis | Logzeile bzw. Zustand |
|---|---|---|
| 23:50:59 | Helm ausgeschaltet | „switched off or out of range, closing the dead SPP socket“; Zustand sofort „Nicht verbunden“ |
| 23:51:12 | Helm eingeschaltet | „switched on …, opening the SPP connection“; 180 ms später verbunden, ohne Tastendruck |

- Die Sorge aus dem Review, der Helm könne unmittelbar nach dem Funkkontakt noch kein RFCOMM annehmen, hat sich **nicht** bestätigt: ein Versuch genügte. Deshalb ist kein Wiederholungsversuch eingebaut. Hier dokumentiert ist ein Zyklus (aus, dann ein); eine Statistik über viele Zyklen liegt nicht vor.
- Ohne den Empfänger bemerkt der Client den Linkverlust erst beim nächsten Schreibversuch (er hat keinen Lesethread), bis dahin stünde fälschlich „Verbunden“, und ein Lichtdruck ginge verloren. Das ist die Begründung für das Schließen bei `ACTION_ACL_DISCONNECTED` (aus dem Code gelesen, §2).

**Unit-Tests:** 401 `@Test` im Quellbaum (nachgezählt), 0 Fehler laut Testlauf vom 17.10.2026. 13 davon in `HelmetAclGateTest` (Regel `isHelmetDevice` und `helmetShouldConnect`); das sind 13 mehr als die 388 des Standes „A1“.

**Offen / ungeprüft:**
- Ein fremdes Gerät mit passendem Namenspräfix `SMARTRIDE` (Wirkung auf die Regel): nicht herstellbar, ungeprüft. Solange `knownAddress()` leer ist, entscheidet nur das Präfix.
- Zwei gekoppelte Helme mit dem Präfix `SMARTRIDE`: Die Adressauflösung bleibt dann leer (`findHelmet` wählt keinen), es wird nichts verbunden. So aus dem Code gelesen, am Gerät nicht geprüft.
- `resolvedAddress` in `HelmetSppClient` wird nach dem Entkoppeln eines Helms nicht zurückgesetzt, `knownAddress()` liefert dann weiter die alte Adresse. Review-Vorschlag, nicht umgesetzt.
- Ein Fenster in `HelmetSppClient.connectOnWorker`, in dem ein `disconnect()` verpufft (vorbestehend, nicht durch diese Änderung entstanden): Review-Vorschlag, nicht umgesetzt.

### Bekannte kleine Mängel (offen, bewusst zurückgestellt)

- `CratoniBleManager`: Fehlt beim rein diagnostischen Lesen von `2A50` (PnP ID) oder `FFF4` die Berechtigung `BLUETOOTH_CONNECT`, läuft der Code in `failMissingPermission` und beendet damit eine bestehende Verbindung — im Widerspruch zur Zusage, dass diagnostische Schritte den Verbindungszustand nie verändern. Praktisch kaum erreichbar, weil ein Berechtigungsentzug den Prozess ohnehin beendet. Fix: an beiden Stellen nur loggen und zurückkehren, wie im Zweig „Characteristic nicht gefunden". Zurückgestellt am 12.09.2026, damit der laufende Alltagstest nicht unterbrochen werden muss.
- `Fff4ProbeLog.append` ist nicht unit-getestet (Dateizugriff); Format und Kürzungsregel dagegen schon. Die Datei wird beim Anhängen neu geschrieben, ein Absturz mitten im Schreiben kann sie verlieren — für ein Diagnosemittel akzeptiert.
- OFFEN: Der Akkustand blieb auch hier ohne Wert.

## 12. Erweiterungen nach V1

Der erste Abschnitt ist umgesetzt (16.10.2026), einschließlich der Anzeige des zuständigen Assistenten (A1, 17.10.2026); die übrigen sind Ideen und nicht umgesetzt. Die Remote-Auswahl aus einer Liste (17.10.2026) steht nicht hier, sondern in §3, §4 und §11.

### Walkie-Talkie-Taste startet den Sprachassistenten (umgesetzt und am Gerät geprüft am 16.10.2026)

**Ziel des Nutzers:** Die bisher ungenutzte Walkie-Talkie-Taste soll den Sprachassistenten starten, damit Befehle wie „rufe X an" oder „navigiere nach Hause" ohne Weckwort möglich sind. Ausdrücklich NICHT die Anruf-Taste. Das Weckwort „Okay Google" funktioniert unterwegs nicht zuverlässig — VERMUTET, weil die Weckwort-Erkennung auf dem Handy mit dessen eingebauten Mikrofonen läuft und die Sprachverbindung zum Helm im Ruhezustand geschlossen ist; das Handy steckt in der Tasche.

**Recherche (AOSP-Quellcode, Branch main):**
- `BluetoothHeadset.startVoiceRecognition` öffnet nur den SCO-Audiokanal zum Headset, startet aber KEINEN Assistenten — der Start läuft im System umgekehrt (Headset meldet AT+BVRA, das Bluetooth-System startet `ACTION_VOICE_COMMAND`). Für uns unbrauchbar.
- `startActivity(ACTION_VOICE_COMMAND / ACTION_ASSIST)` aus dem Dienst scheitert an der Startsperre aus dem Hintergrund; eine Companion-Ausnahme existiert im Code, ist aber undokumentiert und für eine Kopplung ohne Profil unklar. Nicht weiter verfolgt.
- `MediaSessionService` behandelt `KEYCODE_HEADSETHOOK` und `KEYCODE_MEDIA_PLAY_PAUSE` als Sprachtasten: Ein DOWN mit repeatCount 1 und `FLAG_LONG_PRESS` löst `startVoiceInput` aus. Der Aufrufer wird dabei nicht geprüft — außer während einer Global-Priority-Session (Telefonat), dann nur `SYSTEM_UID`.

**Experiment (Herleitung):** Ein Debug-Befehl `VOICE_LONGPRESS` (inzwischen entfernt, ersetzt durch den Produktionspfad, siehe unten) sendete über `AudioManager.dispatchMediaKeyEvent` DOWN (repeat 0), nach `ViewConfiguration.getLongPressTimeout()` (am Gerät 400 ms) DOWN (repeat 1, `FLAG_LONG_PRESS`), 100 ms später UP — alles `KEYCODE_HEADSETHOOK`.

**Ergebnis des Experiments am Gerät (S25 Ultra, Android 16, One UI 8.5, Helm per HFP verbunden) — VERIFIZIERT:**
- Das System nimmt den langen Druck unserer App als Sprachtaste an: `MediaSessionService: voice-based interactions: about to use ACTION_VOICE_SEARCH_HANDS_FREE`.
- Die Freisprech-Sprachsuche wird auch bei eingeschaltetem Bildschirm gewählt (laut Quellcode wäre dort `ACTION_WEB_SEARCH` zu erwarten gewesen).
- Beim ersten Mal fragt Android, welche App öffnen soll (Kandidaten: Google `voicesearch.handsfree.HandsFreeActivity`, Bixby, Alexa). Mit „Google" und „Immer" ist das dauerhaft erledigt.
- Bei gesperrtem Handy im Ruhezustand (`Dozing`, Sperrbildschirm) und laufender Musik: Signalton im Helm, Spracheingabe und Antwort über den Helm, Musik läuft danach von selbst weiter.
- „Rufe [Kontakt] an" startet den Anruf direkt, ohne Entsperren; Musik läuft nach dem Anruf weiter.
- Kein versehentliches Play/Pause durch die Tastenfolge.

**Umsetzung im Produktionscode (16.10.2026):**
- `action/AssistantExecutor` (Interface, `startAssistant(): Boolean`) und `action/MediaKeyAssistantExecutor`. Der `ActionDispatcher` ruft den Ausführer für `WALKIE_TALKIE` auf; `false` ergibt `Ignored`.
- Folge wie im Experiment, alle drei Ereignisse mit derselben `downTime`, auf dem Main-Looper nacheinander gesendet: DOWN (repeat 0) → nach `ViewConfiguration.getLongPressTimeout()` (wird bei jedem Druck gelesen; 400 ms am Testgerät) DOWN (repeat 1, `FLAG_LONG_PRESS`) → 100 ms → UP. `startAssistant` kehrt sofort zurück und blockiert den BLE-Thread nicht.
- Keine neue Berechtigung (`dispatchMediaKeyEvent` verlangt keine), kein WakeLock.
- Die Anruf-Taste bleibt unverändert (annehmen/auflegen) und wird bewusst nicht für den Assistenten genutzt (Wunsch des Nutzers).
- **Sperren:** Gesendet wird nur im Audio-Modus `MODE_NORMAL`. Bei Klingeln (`MODE_RINGTONE`), im Telefonat (`MODE_IN_CALL`) und bei `MODE_IN_COMMUNICATION` wird nichts gesendet (Ergebnis `Ignored`); das ist strenger als die Heuristik der Anruf- und Lautstärketasten (`callActionForAudioMode`), die unbekannte Modi (4–6) als „kein Anruf“ werten. Gründe, aus AOSP ANGENOMMEN: Im Telefonat lässt das System Medientasten fremder Apps ohnehin nicht durch (Global-Priority-Session, nur `SYSTEM_UID`); ein langer Headset-Druck gilt beim Klingeln als „ablehnen"; und der Assistent würde einer Intercom-/VoIP-App (Discord, Wave Intercom) das Mikrofon wegnehmen.
- **Doppeldruck-Schutz:** Solange die Folge läuft (rund 0,5 s), wird ein weiterer Druck ignoriert. Eine längere Nachlaufsperre (2 s) wurde erwogen, auf Wunsch des Nutzers aber nicht eingebaut.
- **Abbruch:** Ein Fehler oder das Dienst-Ende (`close()` aus `onDestroy`) mitten in der Folge sendet ein UP mit `FLAG_CANCELED`, damit nie ein DOWN ohne UP bleibt und das UP nicht als kurzer Druck (Play/Pause) gewertet wird. Dieser Zweig ist am Gerät UNGEPRÜFT (nur Unit-Tests).
- **Debug-Build:** Der Experimentbefehl `VOICE_LONGPRESS` und `DebugVoiceLongPress.kt` sind entfernt. Der Befehl `WALKIE_TALKIE` des `DebugCommandReceiver` läuft über den Produktionspfad; die Ausführer-Instanz hält prozessweit `debug/DebugAssistant` (Aufruf siehe §11, Gerätetest 16.10.2026).
- Messungen am Gerät: siehe §11, Gerätetest vom 16.10.2026.

**Grenzen:**
- Der Weg ist nicht offiziell dokumentiert — ein künftiges Android- oder One-UI-Update kann das Verhalten ändern; die Taste würde dann stillschweigend wirkungslos (das Log zeigt die Ereignisse weiterhin als gesendet).
- Beim ersten Druck fragt Android, welche App öffnen soll; mit „Google" und „Immer" ist das dauerhaft erledigt. Welcher Assistent zuständig ist, entscheidet die Systemeinstellung, nicht die App; die Statuskarte zeigt ihn seit 17.10.2026 an und öffnet auf Wunsch die Einstellungen (A1, unten).
- Die Taste ist damit belegt und steht nicht mehr für Push-to-Talk oder Mikrofon-Stummschaltung zur Verfügung (siehe unten).

**Offen / später geplant:**
- B: Die Remote aus einer Liste wählen (statt der bisherigen Einzelkopplung). **Umgesetzt und am Gerät geprüft am 17.10.2026** (§3, §4, §11). Offen aus diesem Punkt: Liste mit mehreren Remotes (nur eine vorhanden; geplant mit zweitem Handy und nRF-Connect-Advertiser), TalkBack-Verhalten der Live-Regions, Startweg über die Oberfläche am Gerät, Remote mit abweichendem Namensformat — Einzelheiten unter „Offen“ im Gerätetest vom 17.10.2026 in §11.
- A1: In der App anzeigen, welcher Assistent zuständig ist, mit einem Knopf in die Systemeinstellung. **Umgesetzt und am Gerät geprüft am 17.10.2026**, ohne zusätzliche Berechtigung (Beschreibung unten, Entscheidung §2, Messungen §11). Offen aus diesem Punkt: Vergleichsgerät ohne One UI (Pixel 7), Chooser-Zustand, TalkBack-Ansagen, überlanger Fremdname.
- Weiterhin offen, nicht Teil von A1 oder B: Messung des Paketverlusts im Telefonat (§13, Befund „Telefonat“); Review-Befunde am Helm-Code (§13, „Vor dem Weiterbau zu beheben“); die Testcheckliste (Schritt 7 in §10; im README steht sie derzeit nicht); der Schalter „Pausieren“ (unten); Vergleichsgerät ohne One UI für A1.

**Folgen für die beiden nächsten Ideen** (Push-to-Talk, Mikrofon stummschalten): Sie waren für dieselbe Taste gedacht und stehen jetzt im Konflikt mit deren Belegung. Sie bleiben als Hintergrund und als mögliche spätere Erweiterung stehen; eine Umsetzung bräuchte eine andere Taste oder eine umschaltbare Belegung. Hinzu kommt: Die Sperre bei `MODE_IN_COMMUNICATION` betrifft genau die Intercom-Apps, für die PTT gedacht war — die Taste tut dort derzeit nichts.

#### A1: Anzeige des zuständigen Assistenten (umgesetzt und am Gerät geprüft am 17.10.2026)

**Anzeige.** Die Statuskarte hat eine neue Zeile „Zuständiger Assistent“ (`AssistantRow`), direkt über „Letzter Befehl“. Werte:

| Wert in der Zeile | Bedeutung (`AssistantTarget`) |
|---|---|
| App-Name, z. B. „Google“ | `App`: genau eine App beantwortet die Freisprech-Aktion als Standard |
| „Nicht festgelegt“ | `None`: nichts beantwortet die Aktion (oder der `<queries>`-Eintrag fehlt, §7) |
| „Wird beim Drücken abgefragt“ | `AskedOnPress`: Apps beantworten sie, aber keine ist Standard; das System fragt bei jedem Druck |
| „Wird ermittelt …“ | noch keine Antwort (`null`) |

Daneben der Knopf „Einstellungen öffnen“ (Sprachausgabe: „Assistent-Einstellungen öffnen“). Er ist immer sichtbar: ohne Standard dient er zum Festlegen, mit Standard zum Ändern. Der Assistent steht nicht in der Benachrichtigung. Er gehört nicht zum Zustand der Fernbedienung (`RemoteStatus`), sondern ist ein eigener UI-Zustand: `rememberAssistantTarget(refreshKey)` in `MainActivity` fragt auf `Dispatchers.IO` und wird durch das vorhandene `ON_RESUME`-Auffrischen (`refreshKey`) ausgelöst. Es gibt keinen Broadcast, auf den man hören könnte; die Wahl ändert sich nur in den Systemeinstellungen oder im Chooser, beides ein Umweg aus der Oberfläche heraus. Beim Auffrischen bleibt die vorige Antwort sichtbar; nur eine Neuerstellung der Activity (z. B. Drehung) beginnt wieder bei „Wird ermittelt …“. Die Zeile ist eine Live-Region und zeigt den Wert auf höchstens zwei Zeilen.

**Ermittlung** (`resolveAssistantTarget`, `ui/AssistantTarget.kt`): `queryIntentActivities` und `resolveActivity` auf `android.speech.action.VOICE_SEARCH_HANDS_FREE`, beide mit `MATCH_DEFAULT_ONLY` (die Taste endet in einem `startActivity`, das nur Aktivitäten mit `CATEGORY_DEFAULT` berücksichtigt; nur mit dem Flag antwortet `resolveActivity` ohne Standard mit der Resolver-Activity statt mit einem beliebigen Treffer). Die Entscheidung `assistantTargetFor` ist reines Kotlin ohne Android-Typen:

- nichts aufgelöst → `None`
- aufgelöste Komponente steht in der Kandidatenliste → `App`
- aufgelöste Komponente steht **nicht** darin → `AskedOnPress`. Erkannt ausschließlich so, nie über Klassennamen wie `ResolverActivity`, weil Hersteller eigene Resolver mitbringen.
- keine Kandidaten, aber etwas aufgelöst → `None` und eine Logzeile (nur möglich, wenn der `<queries>`-Eintrag fehlt oder weggefiltert wird)

Anzeigename: `getApplicationLabel` → `loadLabel` → Paketname. Weil der Name aus einer fremden App stammt, wird er bereinigt (`assistantLabel`): Whitespace, Steuer- und Formatzeichen (auch bidi-Overrides) werden zu einzelnen Leerzeichen, über 60 Zeichen wird mit „…“ gekürzt, ohne ein Surrogatpaar zu zerschneiden.

**Knopf** (`openAssistantSettings`): Kette aus drei Zielen, die erste erfolgreich gestartete Seite beendet sie:

1. App-Infoseite der aufgelösten App (`ACTION_APPLICATION_DETAILS_SETTINGS`) — nur ein Schritt, wenn eine App bekannt ist; nur dort lässt sich „Standardmäßig öffnen“ zurücknehmen
2. `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`
3. `ACTION_VOICE_INPUT_SETTINGS`

Jede Stufe hat ihr eigenes `try`; `ActivityNotFoundException` („gibt es hier nicht“) und `SecurityException` („existiert, lässt sich aber von uns nicht starten“, bei Hersteller-Einstellungen möglich) führen zur nächsten Stufe. Schlägt alles fehl, erscheint unter der Zeile „Diese Systemseite gibt es auf diesem Gerät nicht.“ Kein `resolveActivity`-Vortest (§2). Ein `true` sagt nur, dass eine Seite aufging, nicht dass sich die Wahl dort ändern lässt. Nach der Rückkehr aktualisiert sich die Zeile über `ON_RESUME`.

**Was die Anzeige NICHT aussagt:**

- Sie ist eine zweite, unabhängige Abfrage an das System und nicht das, was die Taste tat. Die Wahl kann sich bis zum nächsten Druck ändern.
- Sie sagt nichts über `ACTION_ASSIST` (Home-Langdruck, „digitale Assistenten-App“) und nichts über „Okay Google“. Auf einem Gerät kann dort ein anderer Assistent stehen als hier.
- Sie sagt nicht, ob die App dann wirklich zuhört: Sie kann Entsperren verlangen, die Mikrofonberechtigung oder eine eigene Einrichtung brauchen.
- Benutzerprofil: `MediaSessionService` startet die Sprachsuche als `UserHandle.CURRENT`, die App löst im eigenen Nutzer auf. Bei Arbeitsprofil oder Secure Folder (Testgerät: Nutzer 150) kann die startende App eine andere sein als die angezeigte. Landet die Auflösung auf der Weiterleitungs-Activity eines anderen Profils, steht sie ebenfalls nicht in den Kandidaten; die Zeile zeigt dann „Wird beim Drücken abgefragt“, obwohl ohne Rückfrage weitergeleitet würde. Beides am Gerät nicht geprüft.
- Sie wertet nur `VOICE_SEARCH_HANDS_FREE` aus. Auf dem Testgerät wählt One UI diese Aktion auch bei entsperrtem Gerät mit eingeschaltetem Bildschirm (§11, Gerätetest A1, Punkt c); für Geräte mit AOSP-Verhalten (dort `ACTION_WEB_SEARCH` in diesem Zustand) ist die Anzeige ANGENOMMEN unvollständig.

### Walkie-Talkie-Taste als Push-to-Talk (Wunsch vom 11.09.2026)

- Ziel: Die Taste WALKIE_TALKIE (`0F 57 03 6A`) soll als PTT-Taste für andere Apps nutzbar sein, z. B. Discord oder Wave Intercom.
- Stand: Die Taste ist seit 16.10.2026 mit dem Sprachassistenten belegt (siehe oben). Frühere Fassung (V1): Parser erkannte WALKIE_TALKIE, `ActionDispatcher` ignorierte sie.
- Bestätigt durch die Originalanleitung (Abschnitt „Bluetooth Intercom"): Die Mikrofontaste ist herstellerseitig ein Umschalter — ein Druck öffnet den Sprechkanal, ein erneuter Druck schließt ihn. Die Hardware kennt also gar kein Halten; unsere Messung und die Anleitung stimmen überein.
- Remote — GEMESSEN am 11.09.2026: Ein kurzer Druck sendet genau ein Paket `0F 57 03 6A`. Halten (5 s, isoliert getestet) sendet gar nichts, ein Loslassen-Ereignis gibt es nicht. Echtes „halten = sprechen" ist damit nicht umsetzbar; möglich bleibt nur ein Umschaltmodus per kurzem Druck, und das auch nur, wenn die Ziel-App das unterstützt.
- OFFEN – Ziel-Apps: Ob Discord bzw. Wave Intercom eine Schnittstelle für externe PTT-Tasten anbieten (z. B. Intents, Medien- oder Headset-Taste), ist nicht recherchiert.
- ANGENOMMEN, nicht verifiziert: Normale Apps können keine Tastenereignisse in fremde Apps einspeisen (`INJECT_EVENTS` ist System-Apps vorbehalten); Medientasten erreichen nur die aktive Mediensitzung. Ein Umweg über Bedienungshilfen (`AccessibilityService`) wäre ein Grenzfall zur Vorgabe „keine Workarounds gegen Sicherheitsmechanismen" und wird nur nach gesonderter Entscheidung verfolgt.
- Architektur: eigener Ausführer hinter einem Interface (analog `MediaKeyExecutor`), damit die Belegung später austauschbar bzw. konfigurierbar bleibt.

### Mikrofon stummschalten als Belegung der Walkie-Talkie-Taste (Idee vom 12.09.2026)

Da die Taste herstellerseitig ein Umschalter ist, passt „Mikrofon an/aus" besser als jede Halte-Funktion.
- ZU PRÜFEN: Ist `AudioManager.setMicrophoneMute(Boolean)` in API 37 aktuell (nicht deprecated), und welche Berechtigung verlangt sie (vermutlich `MODIFY_AUDIO_SETTINGS`, normal)? In den SDK-Stubs belegen, wie bei den Medientasten.
- ZU PRÜFEN am Gerät: Wirkt die Stummschaltung auch auf fremde Apps (Discord, Aufnahme-App), oder nur auf den eigenen Prozess? Die Doku beschreibt die Methode als für Systemeinstellungen und Telefonie gedacht.
- ABGRENZUNG: Der systemweite Mikrofonschalter in den Schnelleinstellungen gehört dem System (`SensorPrivacyManager`) und ist für normale Apps nicht zugänglich — kein Umweg darüber.
- Test über den vorhandenen Debug-Auslöser, bevor irgendetwas in die Belegung übernommen wird.

### „Pausieren" statt „Trennen" (entschieden am 12.09.2026)

- V1 hat bewusst KEINEN Knopf „Trennen", obwohl `docs/anforderungen.md` ihn nennt: Die Verbindung ist präsenzgesteuert, ein manuelles Trennen würde vom nächsten `BLE_APPEARED` sofort rückgängig gemacht. Wer Ruhe will, schaltet die Remote aus.
- Für später vorgemerkt: ein Schalter „Pausieren", der den Dienst beendet UND `stopObservingDevicePresence` aufruft, bis der Nutzer „Fortsetzen" wählt. Das wäre ein echtes Aus, ohne die Kopplung zu verlieren.

## 13. Helm-Protokoll (recherchiert und am 26.09.2026 umgesetzt)

Der Helm ist ein eigenes Gerät und hat mit der BLE-Fernbedienung technisch nichts gemeinsam.

**Gerät:** `SMARTRIDE 1.2-B2C3`, MAC 4C:BC:98:A1:B2:C3, **klassisches Bluetooth (BR/EDR)**, Profile SPP, HFP, A2DP-Sink, AVRCP. Also Kopfhörer und Freisprecheinrichtung in einem — die Steuerung läuft seriell, nicht über GATT.

**Kanal:** SPP über PSM 0x0003. Darin zwei belegte RFCOMM-Kanäle: **DLCI 2** trägt die proprietären Steuerbefehle, DLCI 20 ist der normale HFP-AT-Kanal (Anrufsteuerung — nicht anfassen).

**Rahmenformat:** `0F [Befehl] [Ziel] [Parameter] [Prüfsumme]`
- Befehl `0x01` = Licht setzen
- Ziel als Bitmaske: `0x01` Blinker links, `0x02` Blinker rechts, `0x04` Rücklicht
- **Prüfsumme = Summe aller vorherigen Bytes & 0xFF, OHNE +1** — abweichend vom Remote-Protokoll derselben Produktfamilie, dort ist es Summe + 1. Belegt an `0F 01 01 08` → 0x19 und `0F 01 04 FF` → 0x13; XOR und Zweierkomplement passen nicht.

**Befehlstabelle (gemessen)**

| Funktion | Bytes |
|---|---|
| Blinker links ein | `0F 01 01 08 19` |
| Blinker links aus | `0F 01 01 00 11` |
| Blinker rechts ein | `0F 01 02 08 1A` |
| Blinker rechts aus | `0F 01 02 00 12` |
| Rücklicht Dauerleuchten | `0F 01 04 FF 13` |
| Rücklicht blinkend | `0F 01 04 FE 12` |
| Rücklicht aus | `0F 01 04 00 14` |
| Lebenszeichen (kein Licht) | `0F 05 00 14`, etwa alle 10 s in beide Richtungen |

**Rücklicht hat drei Zustände** (Angabe des Nutzers, deckt sich mit der gemessenen Reihenfolge): aus → Dauerleuchten → blinkend → aus. Die Remote meldet nur „Taste gedrückt", die App muss den Zustand also selbst führen — bei den Blinkern zweistufig, beim Rücklicht dreistufig.

**Weitere Befunde**
- Der Helm **quittiert Lichtbefehle nicht**; nur das Lebenszeichen läuft unabhängig weiter.
- Unmittelbar nach dem Verbindungsaufbau sendet die Hersteller-App dieselben Rahmen ohne Nutzeraktion — vermutlich ein Selbsttest.
- Akkustand des Helms: Android erhält ihn über das Apple-Schema `+IPHONEACCEV=2,1,9,2,0` (zehn Stufen, 9 → Anzeige 100 %); die Standardwege sind aus (`BATTERY=-1`, `main_battery=null`). Ein feinerer Wert steckt vermutlich im SPP-Kanal, wurde aber nicht gesucht.

**Methodik:** HCI-Snoop-Mitschnitt der Hersteller-App (`com.cratoni2`), eigener Parser für btsnoop → HCI-ACL → L2CAP → RFCOMM im Scratchpad. Zuordnung der Verbindung über `Connection Complete`; andere Geräte des Nutzers blieben ausgeschlossen. Zwischen den notierten Uhrzeiten und den gemessenen Rahmen lag durchgängig ein Versatz von 6,4–6,9 s, was die Zuordnung nicht gefährdet, aber erwähnt sei.

### Umsetzung und Gerätetest vom 26.09.2026

Paket `helmet/` mit Rahmenbau, Lichtzuständen und einem SPP-Client; `action/LightExecutor` verbindet die drei Tasten damit; der Dienst verbindet den Helm, sobald die Fernbedienung verbunden ist (Stand 26.09.2026; seit 17.10.2026 zusätzlich beim Einschalten des Helms, siehe „Verbindungswege zum Helm“ am Ende dieses Abschnitts). Es sind ausschließlich die gemessenen Rahmen erzeugbar (feste Aufzählungstypen statt freier Parameter), der Eingangskanal des Helms wird nie gelesen, das Lebenszeichen nicht gesendet, HFP und Audio bleiben unangetastet. Keine neue Berechtigung: `BLUETOOTH_CONNECT` genügt, `BLUETOOTH_PRIVILEGED` wäre nur bei Datenpfad-Offload nötig (an den Stubs von android-37.0 belegt).

**Am Gerät bestätigt (Sichtkontrolle am Helm):**

| Taste | gesendeter Rahmen | Beobachtung |
|---|---|---|
| Blinker links, 1./2. Druck | `0F 01 01 08 19` / `0F 01 01 00 11` | links blinkt / aus |
| Blinker rechts, 1./2. Druck | `0F 01 02 08 1A` / `0F 01 02 00 12` | rechts blinkt / aus |
| Rücklicht, 1./2./3. Druck | `0F 01 04 FF 13` / `FE 12` / `00 14` | Dauerlicht / Blinken / aus |

- VERIFIZIERT: Die SPP-Verbindung kommt zustande (`Helmet: SPP connected`, 0,8 s) und trifft den richtigen Kanal — die Befehle wirken. Sie übersteht einen kurzen Abbruch der BLE-Verbindung zur Fernbedienung.
- VERIFIZIERT: Musik über den Helm läuft ohne Aussetzer, während Lichter geschaltet werden; ein Telefonat bleibt ebenfalls ungestört. Die eigene SPP-Verbindung stört Audio und Freisprechen also nicht.
- BEFUND, offen: **Während eines Telefonats kommen einzelne Tastendrücke der Fernbedienung nicht an.** Im Log fehlen die betreffenden Pakete vollständig — sie wurden nie empfangen, die App verwirft nichts. VERMUTET: Funk-Koexistenz zwischen der Sprachverbindung (SCO, feste Zeitfenster, Vorrang) und der BLE-Verbindung, verschärft durch die von der Remote ausgehandelte hohe Peripheral Latency (Intervall 15 ms, Latenz 88). Tastenpakete sind unbestätigt, ein verlorenes Paket ist endgültig weg. Nächster Schritt: Verlustrate messen (zehn Tastendrücke im Gespräch gegen zehn ohne), danach entscheiden, ob `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` während eines Gesprächs sinnvoll ist — schnellere Intervalle gegen höheren Stromverbrauch.

**Vor dem Weiterbau zu beheben (aus dem Review):**

1. Im Debug-Build existieren zwei SPP-Clients im selben Prozess (Dienst und Debug-Halter) mit getrennten Lichtzuständen; beim Gerätetest wurde deshalb nur der Weg über die echten Tasten benutzt. `DebugHelmet` soll die Instanz des Dienstes bekommen.
2. Bei einer frischen Verbindung nimmt die App „alle Lichter aus" an. Blinkt der Helm nach einem Abbruch weiter, zeigt die App „Aus" und der nächste Druck sendet „ein". Entweder beim Verbinden die drei gemessenen Aus-Rahmen senden oder den Zustand als „unbekannt" ausweisen.

**Weiterhin offen**
- Verträgt sich eine eigene SPP-Verbindung mit der gleichzeitigen Audio-/Freisprechnutzung des Helms?
- Über welchen SDP-Eintrag bzw. Kanal findet ein eigener Client den proprietären Kanal?
- Verhält sich der Helm gutmütig, wenn niemand das Lebenszeichen sendet?

### Verbindungswege zum Helm (ergänzt am 17.10.2026)

**Korrektur.** Bis zur Änderung vom 17.10.2026 galt im Code (und in den Kommentaren von `HelmetSppClient.send` und `RemoteConnectionService`): Ist der Helm in dem Moment ausgeschaltet, in dem die Fernbedienung verbindet, bleibt er unerreichbar, und der einzige Weg zurück ist ein Lichttastendruck, der über `HelmetSppClient.send()` einen Verbindungsversuch anstößt (der Rahmen dieses Drucks geht verloren, die Statuskarte zeigt bis dahin „Helm nicht erreichbar“). **Das ist überholt.** Der Weg über `send()` besteht weiter, ist aber nur noch der Rückfall.

Der Dienst öffnet die SPP-Verbindung (`HelmetSppClient.connect()`, idempotent und nicht blockierend) an vier Stellen:

| Auslöser | Bedingung | Stelle |
|---|---|---|
| Fernbedienung wird `Connected` | keine Bestätigung läuft (`dispatchAllowed`) | Sammler des Verbindungszustands in `RemoteConnectionService.manager()` |
| Der Tastendruck bestätigt die Remote (§3, Schritt 7) | — | `onPacket` |
| `ACTION_ACL_CONNECTED` des Helms (**neu**) | `helmetShouldConnect`: Ereignis gehört zum Helm, keine Bestätigung, Helm weder `Connected` noch `Connecting`, Fernbedienung verbunden | `helmetAclReceiver` |
| Lichttaste bei nicht verbundenem Helm (Rückfall, unverändert) | Zustand nicht `Connected` und nicht `Connecting`; der Rahmen dieses Drucks geht verloren | `HelmetSppClient.send()` |

Ein Wiederverbinden nach Bluetooth aus/an läuft über den ersten Weg: `STATE_ON` verbindet die Fernbedienung neu, deren Zustand `Connected` löst den Helm aus (§3, Betrieb, Punkt 7).

**Warum das ACL-Ereignis der richtige Auslöser ist.** Beim Einschalten verbindet sich der Helm als klassisches Bluetooth-Gerät (Headset/Freisprechen) mit dem Handy; das meldet das System als `ACTION_ACL_CONNECTED` (Annahme aus dem Code-Kommentar, durch den Gerätetest vom 17.10.2026 gestützt, §11). Der Helm wird erst über die gekoppelten Geräte aufgelöst; `HelmetSppClient.knownAddress()` liefert die dabei gefundene Adresse, auch wenn der folgende `connect()` scheiterte, weil der Helm aus war.

**Gegenstück: `ACTION_ACL_DISCONNECTED`.** Verliert der Helm die ACL-Verbindung (ausgeschaltet oder außer Reichweite), läuft der RFCOMM-Kanal darauf nicht mehr. Der Client hat keinen Lesethread und bemerkte das sonst erst beim nächsten Schreibversuch (ein verlorener Rahmen); bis dahin stünde „Verbunden“. Der Dienst ruft deshalb `helmet.disconnect()` auf, aber nur bei Zustand `Connected` oder `Connecting`. Der Client bleibt dabei benutzbar.

**Was unverändert bleibt:** Stille bei Fehlern (kein Ton, kein Dialog), Stille des Helms während einer Bestätigung, `HelmetLightExecutor.reset` bei jeder frischen Verbindung, HFP und Audio unangetastet.

**Filter vor der Regel:** LE-Ereignisse (`EXTRA_TRANSPORT`) werden vor allem anderen verworfen; fehlt `BLUETOOTH_CONNECT` oder verweigert das System das Lesen von Adresse und Name, wird das Ereignis ohne Wirkung verworfen. Registrierung als `RECEIVER_EXPORTED` und die Begründung: §2. Messung: §11. Offene Punkte: §11 unter „Offen / ungeprüft“ des Gerätetests „Helm nach der Fernbedienung einschalten“.

