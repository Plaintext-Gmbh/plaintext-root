# Vertragsbesitz in `plaintext-root-interfaces` und der Umgang mit Split-Packages

* **Status:** accepted
* **Date:** 2026-08-30
* **Deciders:** Daniel Marthaler
* **Kontext:** Zustandsbericht 29.08.2026, §3 (Welle 3)

## Context

Der Zustandsbericht hielt zwei Vermutungen fest: `plaintext-root-interfaces` enthalte
Verträge, die App-Fachlichkeit sind und nach `plaintext-app-interfaces` gehören; und
mehrere Java-Pakete würden von mehr als einem Maven-Modul befüllt (Split-Packages —
unter JPMS verboten, für Werkzeuge verwirrend, und sie verstecken, wer für ein Paket
zuständig ist).

Beides wurde gemessen, bevor etwas bewegt wurde: alle 42 Typen in
`plaintext-root-interfaces` wurden einzeln nach Implementierern und Nutzern über **alle
fünf Repos** (root, app, guild, iot, schuetu) aufgelöst, und die Paket-zu-Modul-Zuordnung
wurde für alle 326 Pakete der Familie gebildet.

### Befund 1 — die Verträge sind zu 41 von 42 Framework

Die Messung widerlegt die Vermutung weitgehend. Was wie App-Fachlichkeit aussieht, ist
fast durchweg Javadoc: `SecretResolver` nennt `zeiterfassung.jira-password` als
Beispielschlüssel, `MenuRegistry` „Zeiterfassung" als Beispieltitel, `SearchProvider`
`"kontakte"` als Beispiel-Id. Die **Signaturen** kennen keine Fachbegriffe — sie sprechen
über Menüs, Rollen, Mandate, Cron-Läufe, Suchtreffer und Kacheln. Das sind
Framework-Begriffe, und die Nutzungszahlen bestätigen es: die tragenden Verträge
(`PlaintextSecurity`, `MenuAnnotation`, `PlaintextCron`, `ModuleDescriptor`,
`MenuVisibilityProvider`) werden von drei bis fünf Repos konsumiert.

Genau **ein** Typ fällt heraus: `ch.plaintext.upload.IUploadTarget`.

| | |
| --- | --- |
| Nutzer in root | **keiner** (ausser dem eigenen Test) |
| Nutzer in app | `PostkontoUploadTarget`, `RunningUploadTarget` (Implementierungen) und `RootUploadController` (Konsument) |
| Nutzer in guild / iot / schuetu | keine |

Der Endpunktname `/nosec/root/upload` und das Javadoc legten eine Root-Zuständigkeit nahe,
die es nie gab: der treibende Controller liegt in `plaintext-app-webapp`. Der Vertrag ist
App-Fachlichkeit im Framework-Vertragsmodul.

Zwei weitere Typen haben auffällige Zahlen, sind aber **korrekt** hier:

* `PlaintextRoles` — 0 Nutzer in root, 15 in app. Reines Framework-Vokabular
  (`ROLE_USER`/`ROLE_ADMIN`/`ROLE_ROOT`) und Prädikate über `PlaintextSecurity`; dass
  root es selbst nicht braucht, macht es nicht zu App-Code.
* `PlaintextEmailAddress` — scheinbar 0 Nutzer, tatsächlich von der Default-Methode
  `PlaintextSecurity#getDeliverableEmail` im selben Modul verwendet.

### Befund 2 — Split-Packages sind fast alle Vertrag + Implementierung

29 Pakete werden von mehr als einem Modul befüllt. Der grosse Teil davon ist **gewollt**
und die direkte Folge des Musters „Vertrag im Interface-Modul, Implementierung im
Fachmodul unter demselben Paket":

| Paket | Module |
| --- | --- |
| `ch.plaintext.settings` | root-interfaces + admin-settings |
| `ch.plaintext.secrets` | root-interfaces + admin-secrets |
| `ch.plaintext.modules` | root-interfaces + admin-modules |
| `ch.plaintext.notifications` | root-interfaces + admin-notifications |
| `ch.plaintext.webhooks` | root-interfaces + admin-webhooks |
| `ch.plaintext.mailtemplate` | root-interfaces + admin-mailtemplate |
| `ch.plaintext.bus` | root-interfaces + root-common |
| `ch.plaintext.store` | root-interfaces + root-common |
| `ch.plaintext.boot.dashboard` | root-interfaces + root-menu |
| `ch.plaintext.boot.search` | root-interfaces + root-webapp |
| `ch.plaintext.boot.deeplink` | root-interfaces + root-webapp |
| `ch.plaintext.boot.menu` | root-interfaces + root-menu + root-webapp |
| `ch.plaintext.rechnungen`, `.kontakte`, `.auszahlungen`, `.ocr`, `.postkonto`, `.strom`, `.messenger` | app-interfaces + das jeweilige `plaintext-z-*` |

Auflösen hiesse, jeden Vertrag in ein `…​.api`-Unterpaket zu schieben — eine
Massenumbenennung über fünf Repos, die jede Import-Zeile jedes Konsumenten anfasst und
für jede der vier Apps eine Release-Kette auslöst. Der Nutzen wäre auf absehbare Zeit
null, weil die Familie ein klassischer Classpath-Build ist und JPMS nicht einsetzt.
**Wir lösen sie nicht auf.**

Interessant sind stattdessen die Pakete, die eine **Repo-Grenze** überschreiten — dort
befüllt eine App ein Paket, das root gehört:

| Paket | Fremdbefüller | Bewertung |
| --- | --- | --- |
| `ch.plaintext.boot.plugins.config` | `ForwardedHeaderConfig` **dreimal identisch** in app-, guild- und iot-webapp | Echte Redundanz; gehört einmal nach `plaintext-root-web` |
| `ch.plaintext.boot.web.nosec` | `RootUploadController` (app-webapp) | Der Konsument von `IUploadTarget`; sollte mit umziehen |
| `ch.plaintext.boot.web` | `SwaggerRedirectController` (app-webapp) | App-Belang unter Root-Paketnamen |
| `ch.plaintext.boot` | `DemoCron`, `DemoCronGlobal` (app-webapp) | Demo-Code |
| `ch.plaintext.boot.plugins.security` | `AppRoleProvider` (app-webapp) | Implementiert einen Root-SPI |
| `ch.plaintext.boot.plugins.security.model` | `MyUser` (app-z-zeiterfassung) | JPA-Entity der App unter Root-Paketnamen |
| `ch.plaintext.apitoken` | `KontaktApiController` (app-z-kontakte) | Fach-Controller unter Root-Paketnamen |
| `ch.plaintext.mcp` | `McpLiterale` (app-interfaces) + root-common | Zwei Repos teilen ein Paket |
| `ch` | die fünf `*BootApplication`-Klassen | Absicht: Component-Scan-Wurzel |

Es gibt in der ganzen Familie genau **eine** echte Namenskollision
(`ch.plaintext.boot.plugins.config.ForwardedHeaderConfig`), und sie ist harmlos, weil die
drei Kopien in drei getrennten Deployables liegen, die nie einen Klassenpfad teilen.

## Decision

1. `IUploadTarget` zieht nach `plaintext-app-interfaces` um — **unter demselben voll
   qualifizierten Namen** `ch.plaintext.upload.IUploadTarget`, damit kein Konsument seine
   Importe anfassen muss.
2. Der Umzug läuft als Expand/Contract, weil app auf eine *veröffentlichte* root-Version
   gepinnt ist und ein Typ, der hier verschwindet, für app erst nach einem root-Release
   fehlt:
   * **Expand** (app-PR): app-interfaces bekommt den Vertrag; app-webapp bindet
     app-interfaces explizit ein. Während dieser Phase liegt der Typ zweimal auf app's
     Klassenpfad, und zwar gewinnt die **root**-Kopie: in der aufgelösten Reihenfolge von
     `plaintext-app-webapp` steht `plaintext-root-interfaces` vor
     `plaintext-app-interfaces`. Tragfähig ist die Phase deshalb nur, weil beide Kopien
     bytecode-gleich sind (per `javap` geprüft: gleiche Methoden, gleicher
     `UploadResult`-Record). Wirksam wird die app-Kopie erst mit dem Contract-Schritt.
   * **Contract** (root, dieser PR): die root-Kopie wird `@Deprecated(forRemoval = true)`
     und fällt in einem Folge-PR weg, sobald app auf eine root-Version ohne sie gepinnt
     ist.
   Die Reihenfolge ist zwingend: erst app, dann die Löschung in root.
3. Die übrigen 41 Verträge bleiben, wo sie sind.
4. Split-Packages nach dem Muster „Vertrag + Implementierung" bleiben. Neue
   Fremdbefüllung eines Root-Pakets durch eine App gilt als Fehler und wird beim Review
   zurückgewiesen.

## Consequences

* Positiv: `plaintext-root-interfaces` enthält danach ausschliesslich Framework-Verträge;
  die Behauptung des Zustandsberichts ist damit erledigt statt offen.
* Positiv: Der Umzug kostet keinen Konsumenten eine Import-Änderung.
* Negativ: Zwischen Expand und Contract existiert der Typ doppelt, und es ist die
  root-Kopie, die zieht. Eine Änderung an der app-Kopie bliebe in dieser Phase **still
  wirkungslos** — die unangenehmere Richtung, weil sie wie eine erfolgte Änderung aussieht.
  Deshalb trägt die root-Kopie ein `forRemoval`-Flag und die app-Kopie eine ausdrückliche
  Warnung im Javadoc; die Phase soll kurz bleiben.
* Neutral: `ForwardedHeaderConfig` bleibt vorerst dreifach. Die Zusammenlegung nach
  `plaintext-root-web` wäre ein root-Release plus drei Konsumenten-Bumps für 30 Zeilen
  Konfiguration — separat zu entscheiden, nicht Teil dieses ADR.

## Alternatives considered

| Option | Warum nicht? |
| --- | --- |
| Alles auf einmal: root löscht, app übernimmt, alle bumpen | app wäre zwischen den beiden Merges nicht baubar; der Pin auf eine veröffentlichte root-Version lässt keinen atomaren Schnitt zu. |
| `IUploadTarget` in root lassen und nur das Javadoc korrigieren | Hätte die Fehlbenennung dokumentiert statt behoben; der Vertrag hätte weiter Konsumenten in root vorgetäuscht, die es nicht gibt. |
| Split-Packages global über `…​.api`-Unterpakete auflösen | Massenumbenennung über fünf Repos, vier Release-Ketten, jede Import-Zeile betroffen — ohne JPMS kein Gegenwert. |
| `ForwardedHeaderConfig` in diesem Zug nach root ziehen | Eigene Release-Kette über drei Konsumenten; vermischt zwei unabhängige Entscheidungen in einem PR. |

## References

* Zustandsbericht 29.08.2026, §3 (Welle 3)
* ADR-0006 — Releases und Konsumenten-Pins
