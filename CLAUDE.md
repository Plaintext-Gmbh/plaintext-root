# plaintext-root — Claude-Orientierung

## Zweck

`plaintext-root` ist das Framework der Plaintext-Familie: Security (Spring Security, OIDC,
TOTP), Menü-System mit Seiten-Zugriffsschutz, JPA-Basisklassen, Flyway, Admin-Panels und das
UI-Template. Es wird als **Maven-Bibliothek** konsumiert: `plaintext-app`, `plaintext-guild`,
`plaintext-iot` und `plaintext-schuetu` haben `plaintext-root-parent` als Parent-POM und ziehen
die Module aus `https://maven.plaintext.ch/releases` (LAN-only) bzw. GitHub Packages. Der
Container von plaintext-root selbst ist seit 12.08.2026 stillgelegt (Karte 776) — das Repo
released weiter, deployt aber nichts (`release-only` in `.github/workflows/ci-cd.yaml`).

Versionen nie hier eintragen — sie stehen in `pom.xml` (Parent = `spring-boot-starter-parent`;
Java-Version in `<java.version>`).

## Module (24, Quelle: `<modules>` in `pom.xml`)

| Gruppe | Module |
|---|---|
| Kern | `plaintext-root-interfaces` (Verträge, keine Implementierung), `plaintext-root-common`, `plaintext-root-flyway`, `plaintext-root-jpa` (`SuperModel`, Mandant, Audit, Soft-Delete) |
| Menü/Zugriff | `plaintext-root-menu` (`@MenuAnnotation`, `MenuRegistry`), `plaintext-root-menu-visibility` (Mandanten-Sichtbarkeit, Menü-Diagnose), `plaintext-root-pageguard` (Page Access Guard), `plaintext-root-role-assignment` |
| Web | `plaintext-root-web` (`UrlRewriteConfig` `.html`→`.xhtml`, `SpringSecurityProvider`, `MenuBean`), `plaintext-root-template` (Layout, CSS, JS), `plaintext-root-webapp` (die lauffähige root-App: `PlaintextSecurityConfig`, Login, `application.yml`) |
| Qualität | `plaintext-root-archtests` (ArchUnit-Regeln als **main**-Jar, damit die Apps sie ausführen) |
| Admin | `plaintext-admin-requirements`, `-cron`, `-sessions`, `-settings`, `-i18n`, `-oidc`, `-apitoken`, `-secrets`, `-modules`, `-mailtemplate`, `-webhooks`, `-notifications` |

Details je Modul: `docs/MODULE_REFERENCE.md`. Es gibt **kein** `plaintext-admin-value-lists` und
**kein** `plaintext-root-email` mehr — wer die in alten Docs findet, findet einen Zombie.

## Konventionen

- **Flyway in PostgreSQL-Syntax.** Die App läuft ausschliesslich auf `jdbc:postgresql`; es gibt
  kein H2/HSQLDB. `BIGSERIAL`, `TEXT`, `IF NOT EXISTS` sind in Ordnung. Ältere Docs, die
  „HSQLDB-Syntax“ sagen, sind falsch. Nummern: `V{Sekunden seit 2000-01-01}__beschreibung.sql`
  (`echo $(( $(date +%s) - 946684800 ))`), siehe `docs/FLYWAY_MIGRATIONS.md`. Released
  Migrationen nie ändern (Prüfsumme).
- JSF-Forms: `<h:form id="fm">` + `<input type="hidden" name="_csrf" value="#{_csrf.token}"/>`
  — `CsrfFormInvariantTest` erzwingt das repo-weit.
- Kein `@Lob` auf Strings → `@Column(columnDefinition = "text")`.
- Domänenbegriffe sind deutsch (`Mandat`, `Anforderung`, `Rollenzuteilung`), Glossar in
  `docs/GERMAN_TERMS.md`. Kommentare in neuen Dateien und Docs englisch oder deutsch — Hauptsache
  sie erklären das **Warum**.
- Commit-Betreff deutsch, Body mit dem Warum; keine Conventional-Commits-Tags, keine
  generierten Release-Notes. `CHANGELOG.md` wird von Hand pro Release nachgeführt.
- „Karte NNN“ in Kommentaren = Karte auf dem internen Kanban (keine URL, kein GitHub-Issue).
- Architekturentscheide als ADR in `docs/adr/` (Muster `0003-…`).

## Build und lokaler Start

- PostgreSQL: `docker compose up -d` → Port **5434**, DB `plaintext_root`, User/Passwort
  `plaintext`/`plaintext` (Volume `~/plaintext-root-db`). Das sind die Defaults in
  `plaintext-root-webapp/src/main/resources/application.yml` (`DB_HOST`/`DB_PORT`/`DB_NAME`/
  `DB_USER`/`DB_PASSWORD` überschreiben sie).
- `./build 0` = Compose hochfahren + `mvn spring-boot:run -pl plaintext-root-webapp`
  (Wrapper sourct `~/codeplain/plaintext-scripts`). Ohne Wrapper: dieselben zwei Befehle.
- `./build 1` = Maven-Snapshot-Build. Releases laufen **nur** über CI (siehe unten), lokale
  Release-Befehle (`./build 3/5/56`) hier nicht benutzen.
- Dev-Profil ist aktiv (`spring.profiles.active: dev`): Cookie `secure=false`, damit
  `http://localhost:8080` funktioniert. Swagger nur mit `SPRINGDOC_ENABLED=true`.

## Tests

- JaCoCo-Gate (seit 29.08.2026 scharf): `mvn verify` bricht ab, wenn ein Modul unter 40 %
  Zeilenabdeckung faellt (`jacoco.coverage.minimum` im Parent). Ein Modul darunter bekommt Tests
  oder — begruendet und datiert — ein eigenes `<jacoco.coverage.minimum>` in seiner pom, nie 0.
  Tests gehoeren ins Modul der getesteten Klasse, sonst zaehlen sie dort nicht (root-web-Lehre).
- `mvn clean test` — Unit-Tests + JaCoCo. `mvn clean verify` — zusätzlich die ITs; die booten
  ein **eingebettetes PostgreSQL** (`io.zonky.test:embedded-postgres`, Karte 451), **kein**
  Docker und keine Testcontainers. `-DskipITs` überspringt sie.
- Playwright-ITs (`SelfServicePlaywrightIT`, `RootPagesPlaywrightIT` in
  `plaintext-root-webapp`): brauchen einmalig ein Chromium — Befehle in
  `CONTRIBUTING.md` („Playwright UI tests“), identisch mit `.github/workflows/playwright.yaml`.
  Start: `mvn -pl plaintext-root-webapp failsafe:integration-test failsafe:verify
  -Dit.test='SelfServicePlaywrightIT,RootPagesPlaywrightIT'`. Die ITs starten die App selbst.
- Invarianten-Tests, die gern rot werden: `CsrfFormInvariantTest`, `MenuLinkInvariantTest`
  (jeder Link endet auf `.html`, jede View hat eine Regel), `MobileFormLinterTest`
  (`p:dialog` ohne feste px-Breite), ArchUnit in `plaintext-root-archtests`.
- Testcontainers-ITs anderer Repos failen lokal ohne Docker — hier nicht relevant, root nutzt
  embedded-postgres.

## Release-Ablauf

1. Feature-Branch → PR → Merge nach `master`.
2. Der Merge löst `ci-cd.yaml` mit `release-only` aus: Version-Bump, Tag, `mvn clean deploy`
   nach `maven.plaintext.ch/releases` **und** GitHub Packages (Dual-Publish), dann
   `Prepare next development iteration … [skip-ci]`. Kein Container-Deploy (stillgelegt).
3. Die Apps ziehen nach: `root-autobump.yaml` in jedem Consumer-Repo (täglich, zwei Fenster)
   liest die `maven-metadata.xml` des NAS-Repos, setzt Parent-Version und
   `<plaintext-root.version>`, baut zur Prüfung und öffnet einen PR. **Kein Auto-Merge** —
   der Merge im Consumer ist der Deploy und bleibt eine bewusste Handlung.
4. `publish-root-pin.yaml` meldet die gepinnte root-Version jedes Consumers nach
   `plaintext-mvn` (Branch `pins`), damit das Aufräumen dort keine benutzte Version löscht.
5. `CHANGELOG.md`: den `[Unreleased]`-Block beim Release auf die Nummer umbenennen. Die
   Minor-Nummer zählt Releases; sie ist **kein** SemVer-Versprechen.

Details: `docs/adr/0006-releases-und-konsumenten-pins.md`.

## Sicherheitsregeln

- Zugriff auf eine Seite leitet sich aus dem Menü ab: `@MenuAnnotation(link = "x.html",
  roles = {…})`. Rollen dort **ohne** `ROLE_`-Präfix (`"ADMIN"`, `"ROOT"`); der
  `SpringSecurityProvider` akzeptiert beide Schreibweisen.
- Harte, menü-unabhängige Gates in `PlaintextSecurityConfig`: `ROOT_ONLY_PAGES` (Setup,
  Settings, Secrets, OIDC, Flyway, Module, Mandate, Menü-Diagnose, …) und `ADMIN_PAGES`
  (Useradmin, Mailtexte, Rollenzuteilung, Sessions, …). Neue Admin-/Root-Seite → dort
  eintragen, nicht nur im Menü.
- Page Access Guard (`plaintext-root-pageguard`): root-App läuft in
  `plaintext.security.page-guard.mode: STRICT` (fail-closed, Eltern-Rollen vererbt).
  Framework-Default bleibt `REPORT`, damit die Apps beim Update nicht ihre Detail-Views
  aussperren. Detail-Seite ohne Menüpunkt → `aliases`, echte Jedermann-Seite → `allowlist`.
  Doku: `docs/security/PAGE_ACCESS_GUARD.md`, ADR 0004.
- Modul-Rollen: `plaintext.menu.module-roles.<modulKey>=<rolle>` schaltet ein Modul pro App;
  `admin`/`root` umgehen sie (`docs/ROLE_REGISTRY.md`).
- CSRF gilt auch für `.xhtml`/`.html` — ViewState ist kein CSRF-Schutz.
- Session nur per Cookie (`tracking-modes: cookie`, `SessionTrackingConfig` in root-web),
  `same-site: lax` bewusst (OIDC-Rückläufer), `secure` per Default an.
- Swagger/OpenAPI per Default aus; `/actuator/health` offen, Rest hinter ADMIN.

## Typische Fallen

- **Records in EL:** Der `RecordELResolver` löst `#{z.foo}` nur über die Record-Methode
  `foo()` auf — **keine** `getFoo()`-Bean-Getter auf Records schreiben, die Seite bleibt sonst
  mit `PropertyNotFoundException` leer (`MenuDiagnoseZeile`, `MenuDiagnoseZeileElTest`).
- **`f:metadata`** (`f:viewParam`, `preRenderView`) gehört direkt unter `ui:composition`,
  nicht in ein `ui:define` — sonst wird es still ignoriert.
- **Kein `onchange="submit()"` an `p:`-Komponenten** — löst keinen Request aus. `p:ajax` mit
  Listener verwenden (`rootentities`/`adminentities`, CHANGELOG 1.635.0).
- **Test-User:** Rollen in der DB bzw. `addRole(...)` **mit** `ROLE_`-Präfix (`ROLE_ADMIN`,
  `ROLE_ROOT`, plus `PROPERTY_MANDAT_<mandat>`). Spring-Securitys `hasRole` hinter
  `ROOT_ONLY_PAGES`/`ADMIN_PAGES` kennt kein Präfix-Raten — ein Test-User mit `"ADMIN"`
  sieht das Menü, bekommt aber 403.
- **`@MenuAnnotation` an `@Conditional`-Klassen:** der Scanner wertet die Bedingung aus
  (`SwaggerMenu` mit `@ConditionalOnProperty`); wer die Seite deaktiviert, muss auch den
  Menüpunkt bedingt machen, sonst 404 → Dashboard.
- **AutoConfiguration statt Component-Scan:** root-Module registrieren sich über
  `AutoConfiguration.imports`. Eine App, die `ch.plaintext` nicht scannt, bekommt Guard,
  URL-Rewrite und Menü trotzdem — eine `@Component`-Klasse in einem root-Modul dagegen nicht.
- **`@Transactional`/`@Async` an Crons:** der Cron-Key wird über `ClassUtils.getUserClass`
  aufgelöst; Proxies hiessen früher `MyCron$$SpringCGLIB$$0` und verloren ihre Konfiguration.
- **Locale in Tests:** Formatierung mit `Locale.ROOT`, sonst fällt der Test auf Rechnern mit
  Dezimalkomma.
- Änderungen an `application.yml` von root wirken **nur** in der root-App — jede App bringt
  ihre eigene mit und verdeckt diese.

## Port-Tabelle der Familie

| Repo | lokaler DB-Port (compose.yaml) | DB-Name | DEV / PROD (NAS 192.168.1.224) | CI `postgres-port` |
|---|---|---|---|---|
| plaintext-root | **5434** | `plaintext_root` | 1123 / 1124 (stillgelegt seit 12.08.2026) | 5434 (Default) |
| plaintext-app | 5432 (+ pgAdmin 5050) | `plaintext` | 1111 / 1112 | 5436 |
| plaintext-iot | 5433 | `plaintext_iot` | 1121 / 1122 | 5435 |
| plaintext-schuetu | **5434 → 5436** (siehe unten) | `plaintext_schuetu` | 1130 / 1132 | 5437 |
| plaintext-guild | kein compose.yaml (`application.yml`: 5432) | `plaintext_guild` | 1151 / 1152 | 5439 |

- **Kollision:** root und schuetu lagen beide auf **5434**; wer beide Compose-Stacks
  gleichzeitig hochfährt, bekommt den zweiten nicht gestartet. schuetu wird auf **5436**
  verschoben (eigenes Paket) — die Tabelle trägt bereits den Zielzustand ein; bis der
  Umzug gemergt ist, gilt für schuetu noch 5434.
- Quellen: `compose.yaml` je Repo, `dev-url`/`prod-url`/`postgres-port` in
  `.github/workflows/ci-cd.yaml` je Repo (root: Repository-Variablen `DEPLOY_DEV_URL`/
  `DEPLOY_PROD_URL`). Die CI-Spalte ist der Host-Port auf dem NAS-Runner (nur noch vom
  `deploy`-Job gelesen, `DEPLOY_PGPORT = PGPORT + 100`; `ci`/`sonar` würfeln den Port aus der
  run-id) — nichts Lokales.
