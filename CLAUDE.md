# plaintext-root — Claude orientation

## Purpose

`plaintext-root` is the framework of the Plaintext family: security (Spring Security, OIDC,
TOTP), a menu system with page access protection, JPA base classes, Flyway, admin panels and the
UI template. It is consumed as a **Maven library**: `plaintext-app`, `plaintext-guild`,
`plaintext-iot` and `plaintext-schuetu` have `plaintext-root-parent` as their parent POM and pull
the modules from `https://maven.plaintext.ch/releases` (LAN-only) or from GitHub Packages. The
container of plaintext-root itself has been shut down since 12 August 2026 (Karte 776) — the
repository keeps releasing but deploys nothing (`release-only` in `.woodpecker/deploy.yml`).

Never record versions here — they live in `pom.xml` (parent = `spring-boot-starter-parent`;
Java version in `<java.version>`).

## Modules (24, source: `<modules>` in `pom.xml`)

| Group | Modules |
|---|---|
| Core | `plaintext-root-interfaces` (contracts, no implementation), `plaintext-root-common`, `plaintext-root-flyway`, `plaintext-root-jpa` (`SuperModel`, tenant, audit, soft delete) |
| Menu/access | `plaintext-root-menu` (`@MenuAnnotation`, `MenuRegistry`), `plaintext-root-menu-visibility` (per-tenant visibility, menu diagnostics), `plaintext-root-pageguard` (Page Access Guard), `plaintext-root-role-assignment` |
| Web | `plaintext-root-web` (`UrlRewriteConfig` `.html`→`.xhtml`, `SpringSecurityProvider`, `MenuBean`), `plaintext-root-template` (layout, CSS, JS), `plaintext-root-webapp` (the runnable root app: `PlaintextSecurityConfig`, login, `application.yml`) |
| Quality | `plaintext-root-archtests` (ArchUnit rules as a **main** jar, so that the applications can run them) |
| Admin | `plaintext-admin-requirements`, `-cron`, `-sessions`, `-settings`, `-i18n`, `-oidc`, `-apitoken`, `-secrets`, `-modules`, `-mailtemplate`, `-webhooks`, `-notifications` |

Per-module detail: `docs/MODULE_REFERENCE.md`. There is **no** `plaintext-admin-value-lists` and
**no** `plaintext-root-email` any more — whoever finds them in old docs has found a dead module.

## Conventions

- **Flyway in PostgreSQL syntax.** The application runs exclusively on `jdbc:postgresql`; there
  is no H2/HSQLDB. `BIGSERIAL`, `TEXT`, `IF NOT EXISTS` are fine. Older docs that say
  "HSQLDB syntax" are wrong. Numbering: `V{Unix epoch seconds}__description.sql`
  (`echo $(date +%s)`), see `docs/FLYWAY_MIGRATIONS.md`. The 29 oldest migrations still carry
  the earlier scheme (seconds since 2000-01-01, numbers around 820–838 million) — leave them
  alone, and do not compute new numbers that way: such a number is *lower* than every migration
  added since March 2026 and would arrive out of order. Never change a released migration
  (checksum).
- JSF forms: `<h:form id="fm">` + `<input type="hidden" name="_csrf" value="#{_csrf.token}"/>`
  — `CsrfFormInvariantTest` enforces this repository-wide.
- No `@Lob` on strings → `@Column(columnDefinition = "text")`.
- Domain terms are German (`Mandat`, `Anforderung`, `Rollenzuteilung`), glossary in
  `docs/GERMAN_TERMS.md`. Comments in new files and docs may be English or German — what matters
  is that they explain the **why**.
- Commit subject in German, body with the why; no Conventional-Commits tags, no generated
  release notes. `CHANGELOG.md` is maintained by hand, once per release.
- "Karte NNN" in comments means card NNN on the internal kanban board; there is no URL, and it
  is not a GitHub issue. Keep writing it as `Karte NNN`.
- Architecture decisions as ADRs in `docs/adr/` (pattern `0003-…`).

## Build and local start

- PostgreSQL: `docker compose up -d` → port **5434**, database `plaintext_root`, user/password
  `plaintext`/`plaintext` (volume `~/plaintext-root-db`). Those are the defaults in
  `plaintext-root-webapp/src/main/resources/application.yml` (`DB_HOST`/`DB_PORT`/`DB_NAME`/
  `DB_USER`/`DB_PASSWORD` override them).
- `./build 0` = bring compose up + `mvn spring-boot:run -pl plaintext-root-webapp`
  (the wrapper sources `~/codeplain/plaintext-scripts`). Without the wrapper: the same two
  commands.
- `./build 1` = Maven snapshot build. Releases run **only** through CI (see below); do not use
  the local release commands (`./build 3/5/56`) here.
- The dev profile is active (`spring.profiles.active: dev`): cookie `secure=false`, so that
  `http://localhost:8080` works. Swagger only with `SPRINGDOC_ENABLED=true`.

## Tests

- JaCoCo gate (armed since 29 August 2026): `mvn verify` fails when a module drops below 40 % line
  coverage (`jacoco.coverage.minimum` in the parent). A module below that gets tests or — with a
  stated reason and a date — its own `<jacoco.coverage.minimum>` in its pom, never 0. Tests
  belong in the module of the class under test, otherwise they do not count there (the lesson
  from root-web).
- `mvn clean test` — unit tests + JaCoCo. `mvn clean verify` — additionally the ITs; they boot
  an **embedded PostgreSQL** (`io.zonky.test:embedded-postgres`, Karte 451), **no** Docker and
  no Testcontainers. `-DskipITs` skips them.
- Playwright ITs (`SelfServicePlaywrightIT`, `RootPagesPlaywrightIT` in `plaintext-root-webapp`):
  locally they need a Chromium once — commands in `CONTRIBUTING.md` ("Playwright UI tests").
  In CI that step is unnecessary: `.woodpecker/playwright.yml` runs on
  `mcr.microsoft.com/playwright/java:v1.60.0-noble`, which already contains Chromium; the image
  tag must match the Playwright version in `plaintext-root-webapp/pom.xml`. Run them with
  `mvn -pl plaintext-root-webapp failsafe:integration-test failsafe:verify
  -Dit.test='SelfServicePlaywrightIT,RootPagesPlaywrightIT'`. The ITs start the application
  themselves.
- Invariant tests that like to go red: `CsrfFormInvariantTest`, `MenuLinkInvariantTest` (every
  link ends in `.html`, every view has a rule), `SeitenrechteInvariantTest` (menu roles against
  `ROOT_ONLY_PAGES`/`ADMIN_PAGES`), `PlaintextMobileFormLinterTest` (`p:dialog` without a fixed
  px width) and the rest of the ArchUnit and linter suite in `plaintext-root-archtests`.
- Testcontainers ITs of other repositories fail locally without Docker — not relevant here, root
  uses embedded-postgres.

## CI and release flow

**Engine: Woodpecker** (`https://ci.plaintext.ch`), switched over on 30 August 2026. The pipelines
live in `.woodpecker/` (`build.yml`, `playwright.yml`, `sonar.yml`, `deploy.yml`); the GitHub
Actions workflows they replaced have been removed from this repository. Which engine is in charge
is decided by the file `.ci-engine` in the repository root — exactly one word, currently
`woodpecker`. Every Woodpecker step sources `.woodpecker/waechter.sh` as its first command; if
the file says `github`, the guard exits 0 immediately, so a green pipeline on its own is not
proof that anything ran.

- What runs on a pull request and on `master`: `docs/CI.md`
- Runbook for wiring a repository to Woodpecker: `docs/ci/WOODPECKER_SETUP.md`

1. Feature branch → PR → merge into `master`.
2. The merge triggers `.woodpecker/deploy.yml` with `release-only`: version bump, tag,
   `mvn clean deploy` to `maven.plaintext.ch/releases` **and** GitHub Packages (dual publish),
   then `Prepare next development iteration … [skip-ci]`. No container deployment (shut down).
   `release-only` still touches the NAS, though: the repository carries `.m3-jar-volume`, so the
   released jar is staged to the NAS over SSH afterwards.
3. The applications follow: `root-autobump.yaml` in every consumer repository (daily, two
   windows) reads the `maven-metadata.xml` of the NAS repository, sets the parent version and
   `<plaintext-root.version>`, builds as a check and opens a PR. **No auto-merge** — merging in
   the consumer is the deployment and stays a deliberate act.
4. `publish-root-pin.yaml` reports each consumer's pinned root version to `plaintext-mvn`
   (branch `pins`), so that housekeeping there never deletes a version still in use.
5. `CHANGELOG.md`: rename the `[Unreleased]` block to the release number on release. The minor
   number counts releases; it is **not** a SemVer promise.

Steps 3 and 4 are the exception to the engine switch: `root-autobump.yaml` and
`publish-root-pin.yaml` are GitHub Actions workflows and stay in the consumer repositories'
`.github/workflows/`. Woodpecker took over build, test and release, not those two.

Detail: `docs/adr/0006-releases-und-konsumenten-pins.md`.

## Security rules

- Access to a page is derived from the menu: `@MenuAnnotation(link = "x.html", roles = {…})`.
  Roles there **without** the `ROLE_` prefix (`"ADMIN"`, `"ROOT"`); the `SpringSecurityProvider`
  accepts both spellings.
- Hard, menu-independent gates in `PlaintextSecurityConfig`: `ROOT_ONLY_PAGES` (setup, settings,
  secrets, OIDC, Flyway, modules, tenants, menu diagnostics, …) and `ADMIN_PAGES` (user admin,
  mail texts, `Rollenzuteilung`, sessions, …). A new admin/root page goes in there, not only into
  the menu.
- Page Access Guard (`plaintext-root-pageguard`): the root application runs in
  `plaintext.security.page-guard.mode: STRICT` (fail-closed, parent roles inherited). The
  framework default stays `REPORT`, so that the applications do not lock themselves out of their
  detail views on an update. A detail page without a menu entry → `aliases`, a genuinely public
  page → `allowlist`. Docs: `docs/security/PAGE_ACCESS_GUARD.md`, ADR 0004.
- Module roles: `plaintext.menu.module-roles.<modulKey>=<role>` switches a module per
  application; `admin`/`root` bypass them (`docs/ROLE_REGISTRY.md`).
- CSRF applies to `.xhtml`/`.html` as well — ViewState is not CSRF protection.
- Session by cookie only (`tracking-modes: cookie`, `SessionTrackingConfig` in root-web),
  `same-site: lax` deliberately (OIDC returns), `secure` on by default.
- Swagger/OpenAPI off by default; `/actuator/health` open, the rest behind ADMIN.

## Typical pitfalls

- **Records in EL:** the `RecordELResolver` resolves `#{z.foo}` only through the record method
  `foo()` — do **not** write `getFoo()` bean getters on records, or the page stays empty with a
  `PropertyNotFoundException` (`MenuDiagnoseZeile`, `MenuDiagnoseZeileElTest`).
- **`f:metadata`** (`f:viewParam`, `preRenderView`) belongs directly under `ui:composition`, not
  inside a `ui:define` — otherwise it is silently ignored.
- **No `onchange="submit()"` on `p:` components** — it triggers no request. Use `p:ajax` with a
  listener (`rootentities`/`adminentities`, CHANGELOG 1.635.0).
- **Test users:** roles in the database or via `addRole(...)` **with** the `ROLE_` prefix
  (`ROLE_ADMIN`, `ROLE_ROOT`, plus `PROPERTY_MANDAT_<mandat>`). Spring Security's `hasRole`
  behind `ROOT_ONLY_PAGES`/`ADMIN_PAGES` does not guess prefixes — a test user with `"ADMIN"`
  sees the menu but gets a 403.
- **`@MenuAnnotation` on `@Conditional` classes:** the scanner evaluates the condition
  (`SwaggerMenu` with `@ConditionalOnProperty`); whoever disables the page must make the menu
  entry conditional too, otherwise 404 → dashboard.
- **AutoConfiguration instead of component scan:** root modules register themselves through
  `AutoConfiguration.imports`. An application that does not scan `ch.plaintext` still gets the
  guard, the URL rewrite and the menu — a `@Component` class inside a root module, however, it
  does not.
- **`@Transactional`/`@Async` on crons:** the cron key is resolved via `ClassUtils.getUserClass`;
  proxies used to be called `MyCron$$SpringCGLIB$$0` and lost their configuration.
- **Locale in tests:** format with `Locale.ROOT`, otherwise the test fails on machines with a
  decimal comma.
- Changes to root's `application.yml` take effect **only** in the root application — every
  application brings its own and shadows this one.

## Port table for the family

| Repo | local DB port (compose.yaml) | DB name | DEV / PROD (NAS 192.168.1.224) |
|---|---|---|---|
| plaintext-root | **5434** | `plaintext_root` | 1123 / 1124 (shut down since 12 August 2026) |
| plaintext-app | 5432 (+ pgAdmin 5050) | `plaintext` | 1111 / 1112 |
| plaintext-iot | 5433 | `plaintext_iot` | 1121 / 1122 |
| plaintext-schuetu | 5436 | `plaintext_schuetu` | 1130 / 1132 |
| plaintext-guild | no compose.yaml (`application.yml`: 5432) | `plaintext_guild` | 1151 / 1152 |

- **The old collision is resolved:** root and schuetu both used to sit on **5434**, so the second
  compose stack refused to start. schuetu moved to **5436** (its own package, merged on schuetu's
  `master`); root keeps 5434.
- Sources: `compose.yaml` per repository, `DEV_PORT`/`PROD_PORT` in
  `plaintext-config/<repo>/build-conf.txt`.
- **CI test database:** Woodpecker attaches it as a service container on the workflow's own
  network — reachable as host `postgres` on port 5432, with no host port and no per-project
  reservation. The host-port table of the old GitHub Actions pipeline (root 5441, iot 5435,
  app 5436, schuetu 5437, guild 5439) only matters if a repository ever switches back; note that
  root's number is **5441 and never 5434**, because on the NAS the UGREEN photo application
  permanently occupies 5434 (Karte 608).
