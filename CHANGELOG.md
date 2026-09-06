# Changelog

All notable changes to this project will be documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The version
number is **not** SemVer: the minor number counts releases (every merge to
`master` is one), and whether a release breaks a consumer is stated in the
entry text, not in the number.

Entries are written by hand per release; nothing is generated from commit
messages. Earlier minor versions are summarised from `git log` and may not be
exhaustive.

## [Unreleased]

### Added
- **`pt:tableSettings` has its storage and is ready for consumers** (Karte 1077, 6 September
  2026). The building block from 1.657.0 shipped `TableStateStore` as an interface only — every
  app would have had to write its own storage, and none did. root now ships
  `UserPreferenceTableStateStore` (`plaintext-root-common`): the table state is stored per
  **user and tenant** (key `mandat + "/" + page`) as JSON in `UserPreference.tabellenStaende`,
  through the session bean that already owns the record — no entity, no repository, no Flyway.
  A page needs one injected `TableStateStore`, one `TableSettings` field and a `@PostConstruct`.
  The previous storage `UserPreference.tabellenSpalten` (column visibility only, no tenant) is
  read as a fallback and taken over on first save, so the `useradmin` selection of 25.08.2026
  survives; `MyUserBackingBean` now runs on the shared building block. New drift guard
  `PlaintextTableSettingsDriftTest` (runs in every consumer): every copy of
  `tableSettings.xhtml`, `table-settings.js` and `table-settings.css` — root's own or the one in
  `plaintext-oblique-theme` — must hash to the released root version. Below 768px the tag no
  longer applies stored column widths (visibility and profiles still apply).
  **Consumers:** the classes moved from `ch.plaintext.boot.web.table` (`plaintext-root-web`)
  to `ch.plaintext.boot.table` (`plaintext-root-common`) — no consumer imported them yet, and
  `plaintext-root-common` is on every module's class path already, so `pt:tableSettings` needs
  no new POM entry. `UserPreference`, `TableState` and `TableColumnProfile` carry
  `@JsonIgnoreProperties(ignoreUnknown = true)`: from this version on, a rollback no longer
  discards a user's whole preference record because of a field the older version does not know.

### Security
- **Reset and verification links no longer take their host from the request** (Karte 1068,
  5 September 2026). `SelfServiceController` used `request.getServerName()` as the fallback
  for `plaintext.selfservice.public-base-url`; behind the `ForwardedHeaderFilter` that is the
  value of `X-Forwarded-Host`, which the reverse proxy did not overwrite until Karte 1054 —
  a forged header made a genuine password-reset mail carry a genuine token that pointed to a
  foreign host. The base URL now comes from `EigeneAdresse` (setting `app.ownhost`, then
  `plaintext.app.ownhost`, then `plaintext.baseurl`) and is empty rather than request-derived
  when nothing is configured. `PlaintextAuthenticationSuccessHandler` no longer reads
  `X-Forwarded-Proto/-Host/-Port` for the login event either. New ArchUnit rule
  `PlaintextHostAbleitungTest` (runs in every consumer) forbids `getServerName()` and
  `ServletUriComponentsBuilder` outside a frozen list of legacy classes (Karte 1069 A-01).
  Consumers: set `plaintext.baseurl` (already the case in PROD) or `app.ownhost`.

### Changed
- **Documentation overhauled and switched to English** (30 August 2026). All code
  comments and Javadoc across the 24 modules, the Woodpecker pipeline comments and
  every document are now English; identifiers, UI strings, `@DisplayName` values and
  log messages stay as they are, because four consuming repositories depend on them.
  The documentation list at the bottom of the [README](README.md#documentation) is
  the entry point, grouped by what you are trying to do.
- `docs/ARCHITECTURE.md` rewritten: six diagrams read off the `pom.xml` files
  instead of eight that named three modules which do not exist and left out eight
  that do.
- `docs/CI-UMSCHALTEN.md` (594 lines about a one-time migration) replaced by
  [docs/CI.md](docs/CI.md) — what actually runs, on Woodpecker.
- Corrected against the code: the Flyway timestamp is **Unix epoch seconds**, not
  seconds since 2000 (a number computed the old way would sort below every
  migration of the last year); the module role is the **second** menu filter, not
  the fourth; the TOTP migration is PostgreSQL, and `dev.samstevens.totp` is not a
  Spring library; `plaintext-root-webapp` bundles **22** internal modules, not 20.

### Added
- [docs/ci/WOODPECKER_SETUP.md](docs/ci/WOODPECKER_SETUP.md) — runbook for wiring a
  repository to `ci.plaintext.ch`, written to be executed by an AI agent: what to
  configure, how to verify it really ran, and the pitfalls that cost us a day
  (`${...}` in a YAML comment, a `.woodpecker/` subdirectory, the shared Maven
  build cache, a pipeline `error` producing no GitHub status).
- `docs/MENU_SYSTEM.md` — moved out of `plaintext-root-menu/README.md`, with the
  `.html` link invariant corrected in the examples.

### Removed
- GitHub Actions workflows (`ci-cd.yaml`, `playwright.yaml`, `housekeeping.yml`).
  The repository runs on Woodpecker; `.ci-engine` decides, and it says `woodpecker`.
- Nine documents and ten `INFO.md` files, about 2 700 lines that described classes
  which do not exist (`ConfigEncryptionService`, `XStreamSerializer`, `Chat*`),
  plans never carried out, and a test report written on a different machine.

### Added
- `docs/OPTIONAL_MODULES.md` and [ADR 0007](docs/adr/0007-admin-module-abwaehlbar-per-exclusions.md):
  an application can throw admin modules out of `plaintext-root-webapp` via Maven `<exclusions>`
  (status report of 29 August 2026, §3 „Aggregator ohne Opt-out"). Opting out is possible for
  `plaintext-admin-webhooks`, `-notifications`, `-secrets` and `-modules`; for every module the table
  states whether it can be dropped and, where it cannot, why. **Nothing changes for the existing applications** —
  the dependencies stay non-`optional`, the default stays "everything on". `<optional>true</optional>`
  would have silently taken modules away from all four applications at the next bump; a BOM of its
  own would be a second copy of the list that `plaintext-root-parent` already keeps; and
  `@ConditionalOnProperty` on the module auto-configurations would have no effect, because in root
  as in all four applications the beans hang off the
  `@ComponentScan("ch.plaintext")` and not off their auto-configuration.
- `SchlankerKontextTest` (plaintext-root-webapp) with the second Surefire execution
  `kontext-ohne-abwaehlbare-module`: it takes the place on the test classpath away from the four
  module jars via `classpathDependencyExcludes` — the absence is real, not simulated — and starts the
  Spring context against an embedded PostgreSQL. That proves the opting out and locks it down at the
  same time: whoever imports a class from one of these packages in the core, or hard-injects one of
  their beans, brings this run down instead of only the PROD start of a slim application.
- `AbwaehlbareModuleXhtmlTest` (plaintext-root-webapp): the context start renders no page, so a
  core XHTML with `#{webhookBean.x}` does not catch its eye. The test collects the bean names of
  the optional modules and requires, for every mention in an XHTML from
  `plaintext-root-webapp` or `plaintext-root-template`, a null guard in the same document (the
  notification bell in `includes/topbar.xhtml` has one; that is precisely why
  `plaintext-admin-notifications` can be opted out of).
- **Wave 4 (CSP without `'unsafe-inline'`) — groundwork.** `plaintext.security.csp.script-unsafe-inline`
  (default `true` = previous behaviour) controls whether `script-src` carries the token
  `'unsafe-inline'`. As long as it is in there, the browser executes EVERY `<script>` that stands in
  the document — an injected one as well; at this point the CSP is not XSS protection. The policy is
  now built in `PlaintextSecurityConfig.cspPolicy(boolean)`, `PlaintextCspPolicyTest` checks both
  positions and that no other directive changes in the process. **The switch is thrown per
  application and not in this PR** — precondition per application: `joinfaces.primefaces.csp=true`
  AND no more inline JavaScript of its own in the markup.
  **Finding for the switching step (measured on 30 August 2026 against the running root application,
  not derived):** with `joinfaces.primefaces.csp=true` PrimeFaces writes its own
  `Content-Security-Policy` header on EVERY Faces page and replaces the one built by Spring
  Security — what remains is `script-src 'self' 'nonce-…';`, so neither `default-src` nor
  `frame-ancestors`, `form-action`, `img-src` or `connect-src`. On non-Faces paths (REST, Actuator,
  static files) the Spring header stays in force. That affects app, guild, iot and schuetu **today
  already**, because `csp: true` has been set there for a long time. Whoever switches has to hand
  PrimeFaces the complete policy (`joinfaces.primefaces.csp-policy`), and there `script-src` must be
  the LAST directive — PrimeFaces appends the nonce to the end of the string and it otherwise ends up
  in the wrong directive (measured: `… form-action 'self' 'nonce-…'`).
- Shared test `PlaintextInlineJsVertragTest` (plaintext-root-archtests) with two rules:
  **(1)** `<script>`/`<h:outputScript>` WITH a body instead of an extracted `.js` file;
  **(2)** an `on…` attribute on a non-`p:` tag. `on…` on `p:` components stays unchallenged:
  `oncomplete`/`onstart`/`onerror` are PrimeFaces events of the Ajax lifecycle and not HTML
  attributes at all, and where PrimeFaces really does generate an HTML handler (`onclick` on
  `p:commandButton`), the CSP mode pulls it out itself. Exceptions: `<!-- inline-js-ok -->` on the
  same line or allowlist rule `inline-js`.
  **Two positions:** enforcing only with `-Dplaintext.arch.inline-js=enforce` — set that way in the
  Surefire block of `plaintext-root-webapp`. Without the property the test only reports on
  `System.err` and passes. That way a root release does not knock over a consumer build while app,
  guild, iot and schuetu are still clearing up their existing stock; whoever is through sets the same
  two lines in their webapp `pom.xml`.
- `InlineJsLinter` (plaintext-root-common): the scanner for it. Before the evaluation it masks out
  XML comments as well as the bodies of `<script>`/`<style>` — otherwise an
  `<img src=x onerror=…>` from an explanatory comment and a JS property such as
  `xhr.onreadystatechange = …` count as a violation.
- i18n seed `plaintext-root.csv`: **French and Italian** for all 287 keys, plus
  25 **menu titles** from the `@MenuAnnotation` classes (en/fr/it) — 312 keys × 3 target languages
  = 936 lines. So far only `en` was in the seed; whoever switched to FR or IT in the topbar read
  `X_Speichern`, because `I18nService.translate()` silently creates an `X_` placeholder for a
  missing language. `getAvailableLanguages()` already offered `de, en, fr, it` before, the content
  was missing. The menu titles already run through the i18n layer
  (`PrimefacesMenuItem.getValue()` / `PrimefacesSubmenu.getLabel()` call
  `I18nProvider.translate(title, lang)`) but had no pre-population — **no change to the
  menu classes needed**. Translated for a Swiss club and business application:
  „Mandant/Mandat" is the application's tenant term (fr `mandant`, it `mandante`, not
  `mandat` in the sense of an assignment). Proper names stay: `Flyway`, `Swagger`, `Cron`,
  `Howtos`, `Secrets`, `Magic-Link`, `Deep-Links`, `Root`, `Admin`, `OIDC`, `TOTP`.
- Test `I18nSeedSprachabdeckungTest` (plaintext-admin-i18n): for every seed key there is a
  line for every target language from `getAvailableLanguages()` without `de`; no `de` or foreign
  language codes; no `(key, language)` twice; every `@MenuAnnotation` title of the reactor
  is in the seed. Justified gaps go into `BEGRUENDETE_LUECKEN` (empty today). Complements
  `PlaintextI18nSeedTest`, which only checks the other axis (every `i18n.t('…')` has an
  `en` line), but not whether the same key is also present in fr/it.
- ADR-0008 `docs/adr/0008-vertragsbesitz-und-split-packages.md`: measurement of contract ownership in
  `plaintext-root-interfaces` and of the split packages of the whole family (status report of
  29 August 2026, §3 — wave 3). Result: 41 of the 42 contracts are framework and stay; the
  domain terms in the module appear exclusively in Javadoc examples, not in signatures. 29
  packages are filled by more than one module, most of them the intended pattern
  "contract in the interface module, implementation in the domain module"; they stay. Family-wide
  there is exactly one name collision (`ForwardedHeaderConfig`, three times identical in app-, guild-
  and iot-webapp), and it is inconsequential, because the three copies never share a classpath.
- i18n seed `plaintext-admin-i18n/src/main/resources/i18n/plaintext-root.csv`: 287 English
  pre-populations for every `i18n.t('…')` of the root facelets (status report of 29 August 2026, §4 —
  the seed importer had been running empty at every start, family-wide there was no seed CSV; all
  texts are derived from the German default text, PROD had only `X_` placeholders). The importer
  only creates missing entries or `X_` placeholders; texts maintained in the database survive
  every deploy (`I18nServiceSeedImportTest` proves it).
- Shared test `PlaintextI18nSeedTest` (plaintext-root-archtests): every `i18n.t('…')` in a
  facelet of the reactor needs a seed line for the lead language `en`; seed lines without use
  are reported as a warning. Runs in root and, via `dependenciesToScan`, in every consumer —
  seeds from jars (root) count as delivered there. Exceptions: `<!-- i18n-seed-ok -->` or
  allowlist rule `i18n-seed`.
- `I18nSeedLinter` (plaintext-root-common): shared CSV parser and facelet scanner for
  importer and test; resolves the protective apostrophe of the export, so that an export can be
  checked in unchanged as a seed.
- Shared test `PlaintextLayeringTest` (plaintext-root-archtests, status report of 29 August 2026,
  measure 12) with two rules: **L1** — a class in a `service`/`services` package does not hang off
  `jakarta.faces..` or `org.primefaces..` (allowlist rule `layering-jsf-in-service`);
  **L2** — a `*BackingBean` does not lie in a package with the segment `service`, `services`,
  `repository`, `repositories` or `jpa`, unless the same package also names the web layer
  (`web`, `view(s)`, `ui`, `gui`, `jsf`, `bean(s)`; allowlist rule `layering-backingbean-paket`).
  `jakarta.servlet..` is deliberately **not** in L1: the finding of the report is the JSF coupling,
  and a session registry or a servlet filter rightly needs the Servlet API. The web exception in L2
  prevents the module namespace (`ch.plaintext.jpa.web`) from counting against the class. Unlike the
  other ArchUnit rules this test imports only the `target/classes` of its own reactor — a consumer
  should judge its own code and not root classes from jars that it cannot change. root is green on
  both rules **without exception** and still keeps no `plaintext-arch-allowlist.txt`.
- Shared test `PlaintextGroessenLeitplankeTest` (plaintext-root-archtests): no `.java` file
  in `src/main/java` or `src/test/java` of the reactor over 1500 lines (allowlist rule
  `groesse-max-loc`). The threshold is deliberately not a property — a justified exception is
  traceable, a dialled-up limit is not. root keeps to it without exception (largest
  file: `ClaudeAutomationServiceTest`, 1128 lines).
- **Not** implemented: the planned third rule "no cycles between the `ch.plaintext.*` packages".
  The measurement produced 31 cycle groups over practically all top-level packages (hub
  `ch.plaintext.boot`, e.g. `boot -> settings -> modules -> jpa -> boot`). A rule with thirty
  exceptions checks nothing; the finding is recorded as a Javadoc section in `PlaintextLayeringTest`
  and the disentangling is a stage of its own.
- **Woodpecker pipeline and CI switch** (`.ci-engine`, `.woodpecker/`,
  [docs/CI.md](docs/CI.md) (then `docs/CI-UMSCHALTEN.md`)). root is the last repository of the family to get the
  switchover; app, guild, iot and schuetu already run over `.ci-engine=woodpecker`.
  **`.ci-engine` is set to `github` — nothing is armed**, the Woodpecker files bail out again
  immediately on every run. Three workflows instead of the four of the apps: `verify-dev.yml` and
  `verify-prod.yml` are deliberately missing, because the shared GitHub pipeline skips both jobs for
  `deploy-target: release-only` anyway (`!= 'release-only'` respectively `== 'release-all'`)
  and the root container has been shut down since 12 August 2026. The release call is taken character
  for character from the GitHub deploy job (`./build release`), including `MVN_TEST_FLAG` and the
  `[fast]` special handling; nothing has been changed about the order of the three publication targets
  (GitHub Packages, Reposilite, git mirror `plaintext-mvn`) — `deployAtEnd` stays out of the game
  (409 from Reposilite, 29 August 2026). Deliberately added compared with the iot template: `gh` is
  installed afterwards, otherwise the GitHub release with notes would be dropped silently, and a
  manual run accepts only `ci-only`/`release-only` — the replacement for the
  `release-all` option deleted in the GitHub UI. **Weaker than today:** Woodpecker knows no
  concurrency groups; two simultaneous release runs can compute the same version and only fall apart
  at the rejected `git push` respectively at the Reposilite collision check (details in section 7.1 of
  the documentation).

### Changed
- `RepoMaster.repos` is `@Autowired(required = false)`: a context without a single
  `PlaintextRepository` bean now starts. Previously the standard injection of an empty list held the
  start up — and the only implementations in the framework lie in
  `plaintext-admin-modules` and `plaintext-admin-secrets`; whoever opted out of both got a
  startup error at a place that has nothing to do with either module. Nothing changes about the
  behaviour of the applications: as soon as a bean is there, Spring injects the list as before,
  without one the already initialised `new ArrayList<>()` remains.
- **root is free of inline JavaScript** (wave 4): 15 inline blocks (13 `<script>` with a body,
  2 `<h:outputScript>` with a body) and 16 real HTML event attributes have been extracted. New under
  `plaintext-layout/js/`: `menu.js`, `topbar.js`, `global-search.js`, `primefaces-fixes.js`,
  `pushstate.js`; new under the library `plaintext-root`: `login.js`, `login-totp.js`,
  `myuser.js`, `dashboard.js` (root-webapp), `cron.js`, `claudesummary.js`, `secrets.js`.
  Server-side values arrive throughout as a `data-` attribute on an element instead of as EL in the
  script body (`data-pt-pushstate`, `data-pt-redirect`, `data-pt-copy-target`,
  `data-pt-delete-color`, `data-pt-config-aktion`, `data-pt-dropdown`, `data-pt-logout-form`).
  The 69 `on…` attributes on `p:` components stay unchanged — they are not in the way of the
  enforcing header.
- `I18nService.importSeedTranslations()` and `I18nExportController` read CSV lines via
  `I18nSeedLinter` instead of via a private copy of the parser each.
- `I18nServiceSeedImportTest` and `I18nServiceTest` now distinguish stored entries by
  `(label, language)` instead of only by label: with fr/it in the seed the same label carries three
  texts, and the rule that a maintained entry is never overwritten
  (`I18nServiceSeedImportTest.gepflegterTextWirdNichtUeberschrieben`) applies only to the
  maintained pair —
  the missing languages of the same label are indeed pre-populated.
- `plaintext-root-jpa` no longer hangs off `plaintext-admin-sessions` (status report of 29 August 2026,
  measure 12): that was a layering inversion — a base module hung off an admin module
  that conversely builds on the substructure. No source of the module names `ch.plaintext.sessions`;
  the dependency was dead and has been removed without replacement. Nothing changes for consumers,
  `plaintext-root-webapp` declares `plaintext-admin-sessions` itself.

### Fixed
- Topbar: the notification dropdown menu stopped reacting after 60 seconds. Its
  `<p:poll interval="60" update="@form"/>` replaces bell and list with new elements, but the
  inline block had attached its listeners once to the original nodes. The
  extracted `topbar.js` binds by delegation on the `document` and survives every Ajax update.
- Session beans in `plaintext-admin-requirements` are serialisable again:
  `AnforderungSettingsBackingBean.apiSettingsRepository`/`.claudeAutomationService` and
  `HowtoBackingBean.howtoRepository` are `transient` (rule `PlaintextSessionBeanSerialisierbarTest`,
  card 915). root had not seen the violations, because the shared ArchUnit rules run only in
  `plaintext-root-webapp` and that does not hang off `plaintext-admin-requirements` — the base
  package was already `ch.plaintext`, the classpath was not. Now the module runs the
  class-based rules (`PlaintextSessionBeanSerialisierbarTest`, `PlaintextMcpScopeVertragTest`)
  itself via Surefire `dependenciesToScan`, and the new `SessionBeanRegelDeckungTest` in the webapp
  reports every session-scoped bean in the reactor that neither the webapp sees nor its own module checks.
- Duplicate Flyway migration `V1774038471__create_branding_logo_table.sql` (identical in
  `plaintext-root-flyway` and `plaintext-admin-settings`): the copy in the Flyway module has been
  removed, the table belongs to `BrandingLogo` in settings. Flyway had not reported this only
  because the classpath scanner collapses resources of the same name; a diverging line in one
  copy would have produced a checksum conflict at startup. Nothing changes for consumers
  (same resource name, same checksum, no entry in `flyway_schema_history` affected).

### Changed
- **JaCoCo gate enforcing (status report of 29 August 2026, measure 13).** The `coverage-check` execution
  in the reactor parent breaks the build off if a module falls below 40 % line coverage
  (`jacoco.halt-on-failure=true`, `jacoco.coverage.minimum=0.40`, both properties). All root modules
  keep to the threshold without staggering; `plaintext-root-archtests` does not measure (its main sources are
  the test rules), `plaintext-root-template` has no Java. **Consumer note:** app, guild, iot and
  schuetu inherit the execution and, when bumping to this version, have to put
  `<jacoco.halt-on-failure>false</jacoco.halt-on-failure>` into their pom until they are clean
  themselves — otherwise `mvn verify` breaks off there.
- Coverage raised (lines, before → after): `plaintext-root-web` 3.5 % → 59.3 % (the tests for
  `UrlRewriteConfig`, `PathParameterConfig`, `SessionTrackingConfig`, `MenuBean`, `MenuDebugController`,
  `XhtmlDebugController`, `SpringSecurityProvider`, `DashboardController` lay in the webapp and did
  not count there — now in the module), `plaintext-admin-oidc` 15.4 % → 91.1 %
  (`OidcConfigServiceTest`, `OidcConfigBackingBeanFlowTest`), `plaintext-admin-secrets` 19.3 % → 48.5 %
  (`PasswordGeneratorTest`, `SecretServiceVerwaltungTest`), `plaintext-admin-i18n` 28.1 % → 57.7 %
  (`I18nServiceTest` incl. CSV seed import), `plaintext-admin-notifications` 38.0 % → 82.3 %
  (`NotificationCleanupCronTest`, `NotificationBeansTest`), `plaintext-admin-mailtemplate`
  39.6 % → 91.2 % (`MailTemplateBackingBeanTest`), `plaintext-root-flyway` without a test → 98.3 %
  (`FlywayServiceTest`, `FlywayMigrationenTest`: file name, version unique across the reactor,
  PostgreSQL syntax of all migrations).
- `plaintext-root-webapp` measures honestly: excluded are only `RootBootApplication`, the empty
  `@MenuAnnotation` declarations and five pure `@Bean` wirings without branching (each group
  with a reason in the pom); `PlaintextSecurityConfig` & co. stay in.

### Deprecated
- `ch.plaintext.upload.IUploadTarget` (plaintext-root-interfaces) is `@Deprecated(forRemoval =
  true)`: the only contract of the module that is application domain logic. No module in root uses it,
  both implementations (`PostkontoUploadTarget`, `RunningUploadTarget`) and the driving
  `RootUploadController` lie in plaintext-app, and guild/iot/schuetu do not know it — the endpoint
  name `/nosec/root/upload` had feigned a root responsibility that never existed.
  The successor lies under **the same** fully qualified name in `plaintext-app-interfaces`
  (plaintext-app PR „IUploadTarget nach app-interfaces"), so consumers change no imports.
  **Not yet removed:** app is pinned to a published root version; it will be deleted
  only once app has released its own contract and is pinned to a root version without this copy.
  The order is mandatory: app first, then the deletion here.

## [1.635.0] — 2026-08-29

### Fixed
- Language switching (topbar) can be turned off in the setup again: `I18nService.isI18nEnabled()`
  only read the old global key `i18n.enabled`, but the setup writes
  `branding.i18n.enabled` per tenant — now the tenant switch applies first, then the old
  key, otherwise on. Affects the topbar icon, translated menu titles and `i18n.t()`.
- The setup page is cleanly aligned on the phone: the six rigid two-column tables
  are flex grids (side by side from `md`, on mobile the label above the field), fixed widths are fluid.
- Menü-Diagnose (`menudiagnose.html`) renders again: `MenuDiagnoseZeile` is a record, and
  the RecordELResolver resolves `#{z.modulKeysText}` only via a method `modulKeysText()`
  — the previous bean getters `getModulKeysText()`/`getErsterGrund()` left the page
  empty with a `PropertyNotFoundException`. The new `MenuDiagnoseZeileElTest` checks every
  `#{z.…}` reference of the page against the record methods.
- Datenverwaltung (`rootentities.html`, `adminentities.html`): the type selection did not trigger
  a request via `onchange="submit()"`, no table appeared. Now a `p:ajax` event
  with listener `entityTypeChanged()`, which reloads list and messages and discards the selection
  of the old type.
- The menu item „Swagger" only appears when `springdoc.swagger-ui.enabled=true`.
  Previously in PROD (springdoc off) it led to a 404 and from there — via
  `PlaintextErrorViewResolver` — to the dashboard. For that the `MenuAnnotationScanner` evaluates
  `@Conditional` annotations on `@MenuAnnotation` classes against the application environment
  (`mitUmgebung(Environment)`); `SwaggerMenu` carries `@ConditionalOnProperty`.

### Changed
- Menuesteuerung guide: no menu item of its own any more (`MenuesteuerungAnleitungMenu`
  removed). It opens via an info button on `mandatemenu`, `mandatemenudetail`
  and `menudiagnose`; in the `PageAccessGuardService` it stands on the framework allowlist,
  ROOT-only is still enforced by `PlaintextSecurityConfig`.
- Mailtexte (`mailtemplates.html`) now hang in the **Admin** submenu and can be operated by ADMIN and
  ROOT (previously root-only). The overrides are tenant-specific; `ADMIN_PAGES` instead of
  `ROOT_ONLY_PAGES` in `PlaintextSecurityConfig`.

### Added
- Configurable module roles: `plaintext.menu.module-roles.<modulKey>=<rolle>` switches a
  whole module on or off per application — menu item, dashboard tile and the direct call of the
  pages (`PageAccessGuard`) additionally require the configured role, `admin`/`root`
  bypass it. The module key is the `moduleId` (admin panel „Root | Module"), alternatively the
  menu root id; modules without an entry behave unchanged. The configured roles
  appear automatically in the `PlaintextRoleRegistry` without the application writing a
  `PlaintextRoleProvider` of its own. New: `ModuleRoleProperties` (`plaintext-root-interfaces`),
  `ModuleRoleService` (`plaintext-root-menu`), `ModuleRoleDeclarationProvider`
  (`plaintext-root-common`). Documentation: `docs/ROLE_REGISTRY.md`.
- `plaintext-root-web`: reusable JSF/web infrastructure carved out of
  `plaintext-root-webapp` — `UrlRewriteConfig` (the `.html`/`.htm` → `.xhtml` rewrite
  filter), `SpringSecurityProvider`, `MenuBean` and the two debug controllers.
  Applications on `plaintext-root-webapp` are unaffected; the module is pulled in
  transitively. The point of the split is that `plaintext-root-webapp` is an
  *application* module — it carries its own `application.yml`, its own
  `SecurityFilterChain`, its own JPA entities and pulls eighteen `ch.plaintext`
  modules plus Flyway and PostgreSQL. An application that only wants the URL
  rewriting cannot take all of that.
- `WebAutoConfiguration` registers `SpringSecurityProvider` and `UrlRewriteConfig`
  through `AutoConfiguration.imports`, guarded by `@ConditionalOnWebApplication` and
  `@ConditionalOnMissingBean`. As with the page guard, these were previously reachable
  only by component-scanning `ch.plaintext`; an application that did not scan it
  started cleanly and silently had no URL rewriting, so every `.html` link 404'd.
  `MenuBean` deliberately keeps its `@Component`: it runs in the JSF `view` scope,
  which only an application with JoinFaces knows.
- `plaintext-root-pageguard`: the page access guard is now its own module instead of
  living inside `plaintext-root-webapp`. It carries `PageAccessGuardFilter`, the
  service, the startup report, the `preRenderView` backing bean and `PageGuardMode`.
  Applications on `plaintext-root-webapp` are unaffected — the module is pulled in
  transitively and the filter is still registered by `PlaintextSecurityConfig`, where
  its position in the Spring Security chain is documented. The point of the split is
  that an application consuming single modules can now have page-level authorization
  without taking the whole web stack.
- `PageGuardAutoConfiguration` registers the guard through `AutoConfiguration.imports`
  rather than through `@Service`/`@Component`, and `MenuAutoConfiguration` now
  registers `MenuRegistryImpl` the same way. Both were previously reachable only by
  component-scanning `ch.plaintext`; an application that did not scan it started
  cleanly and silently had no page guard at all.
- `plaintext.security.page-guard.startup-report` (default `true`) switches off the
  boot-time scan that lists views without an access rule.
- `plaintext-root-pageguard` publishes a `test-jar`. `PageAccessGuardTestFactory`
  builds a guard over a mocked menu registry, so a consuming application can assert
  its own views against its own menus — see `MenuLinkInvariantTest` in
  `plaintext-root-webapp` for the pattern.
- Menu access policy `STRICT` (`plaintext.menu.access-policy`): a menu item stays
  hidden unless a rule admits the user — `ROLE_ROOT`, `ROLE_ADMIN` outside the Root
  menu, the derived `ROLE_MENU_<menuId>`, one of the declared `roles`, or a prefix
  from the new `roleStartsWith`. The default stays `PERMISSIVE`, so existing
  applications are unaffected, and an unknown value falls back to it rather than
  failing startup. New `menuId` and `roleStartsWith` attributes on `MenuAnnotation`,
  `getAutoRole()`/`getEffectiveMenuId()`/`isUnderRootMenu()` on `MenuItemImpl`,
  `hasAnyRoleStartingWith()` as a default method on `SecurityProvider`.
- `MenuRoleService`: lists every assignable menu role and resolves a request path
  back to its menu item, ignoring the current user's permissions — a screen that
  grants roles must show the ones its operator does not hold. Registered through
  `MenuAutoConfiguration` so consumers need not component-scan `ch.plaintext`.
- `CronConfigStore` as the seam for cron configuration, with `JpaCronConfigStore`
  as the unchanged default (`plaintext.cron.store`, default `jpa`). Applications
  that keep their cron configuration outside the database — a wiki page, a config
  service — contribute their own bean.
- `plaintext.cron.default-enabled` / `default-startup` (both `true`, unchanged
  behaviour) plus `isEnabledByDefault()` / `isStartupByDefault()` on
  `PlaintextCron`, so a deployment need not fire every newly registered job at once.
- `CronModuleConfiguration` registers the cron beans explicitly (guarded by
  `@ConditionalOnMissingBean`) and is announced through `AutoConfiguration.imports`
  — the module now works in applications that do not component-scan `ch.plaintext`.

### Fixed
- `WebAutoConfiguration` is now ordered `@AutoConfigureBefore(MenuAutoConfiguration.class)`.
  Both offer a `SecurityProvider` bean guarded by `@ConditionalOnMissingBean`: the menu module
  contributes a *permissive* default so that an application without security still gets a menu,
  the web module contributes the real, Spring-Security-backed one. Without the ordering the
  permissive default won — every menu item visible to everyone, and since the page guard derives
  its rules from menu visibility, every page reachable. Fail-open, with no error message.
  `SecurityProviderReihenfolgeTest` pins it down.

### Changed
- `PlaintextSecurityProperties.PageGuardProperties` is now the top-level class
  `ch.plaintext.boot.security.PageGuardProperties`, and `getPageGuard()` is gone from
  `PlaintextSecurityProperties`. **The configuration prefix is unchanged** —
  `plaintext.security.page-guard.*` binds exactly as before, so no `application.yml`
  and no environment variable needs touching; `PageGuardAutoConfigurationTest` pins
  this down. Only code that referenced the nested Java class has to adjust its import.
- `PageAccessGuardService` takes `PageGuardProperties` rather than
  `PlaintextSecurityProperties` in its constructor.

### Fixed
- Cron key derivation resolves CGLIB proxies via `ClassUtils.getUserClass`. A job
  annotated `@Transactional` or `@Async` was filed as `MyCron$$SpringCGLIB$$0`,
  never found its stored configuration again and silently created a fresh one.
- `SessionAttribute` formats sizes with `Locale.ROOT`; `SessionAttributeTest`
  failed on every machine whose locale uses a decimal comma.

### Security
- Validate CSRF tokens on JSF pages: `/**/*.xhtml` and `/**/*.html` removed
  from `DEFAULT_CSRF_IGNORE` — the JSF ViewState is state management, not a
  CSRF protection. Every `h:form` must embed the
  `<input type="hidden" name="_csrf" value="#{_csrf.token}"/>` convention;
  the new repo-wide `CsrfFormInvariantTest` enforces this and the missing
  inputs in `menu.xhtml`, `debug.xhtml`, `adminentities.xhtml` and
  `rootentities.xhtml` were added. (Spec 22)

### Added
- App-wide mobile-responsive forms: new central
  `plaintext-layout/css/mobile-responsive.css` (loaded last from the shared
  `includes/template.xhtml`, so it wins the cascade) caps every `p:dialog`
  at `96vw`/`92vh` with a scrolling content area, and on `max-width: 768px`
  makes inputs/textareas/selects `max-width: 100%`. Fixes `p:dialog width="NNN"`
  (fixed px) dialogs running off the right edge on phones. Adds a
  `.mobile-safe` helper class; all root dialogs with fixed px widths were
  converted from `width="NNN"` to `styleClass="mobile-safe"`.
- `MobileFormLinter` (in `plaintext-root-common`) + `MobileFormLinterTest`
  fail the build on mobile anti-patterns (`p:dialog` with fixed px width
  without a `mobile-exempt`/`<!-- mobile-ok -->` opt-out). The utility is
  reusable by dependent apps (app, iot, fwtool, schuetu) after the next root
  release. `scripts/mobile-form-lint.sh` reports fixed-width dialogs / inputs
  and `panelGrid`s per file:line and offers an idempotent `--fix`.
- `ConfigEncryptionService` now uses authenticated AES/GCM with a versioned
  `ENCv2[…]` envelope; `ConfigEncryptionMigrator` re-encrypts legacy
  `ENC[…]` (CBC) values. (#117 / closes #116)
- `docs/CRYPTO.md` documents the wire format and migration procedure.
- `plaintext.security.remember-me-key` makes the persistent-token signing
  key configurable; an ephemeral random key is generated when not set, with
  a startup WARN. (#120)
- `docs/IMPROVEMENT_PROPOSALS.md` and `docs/DOCS_OVERHAUL_PLAN.md` capture
  prioritised follow-ups and the docs-site rewrite plan. (#119)

### Changed
- README, `docs/index.md`, and `SECURITY.md` corrected to use the
  Plaintext-Gmbh organisation URLs and current Spring Boot 4 / framework
  v1.167 versions. (#118)
- `plaintext-root-menu` README no longer pins a stale SNAPSHOT version in
  the dependency snippet. (#118)

### Removed
- The `email_config` legacy table and `EmailConfigMigration` (start-up data
  backfill marked `@Deprecated(forRemoval = true, since = "1.94.0")`). (#121)

## [1.166.0] - 2026-04-29

### Added
- SonarQube integration: build option `s` plus a weekly CI analysis
  workflow. (#89)

### Security
- Escape error messages instead of concatenating them into `innerHTML`
  in admin pages. (#115)
- Stop leaking exception messages in REST error responses. (#114)
- Mark theme cookies `Secure` + `SameSite=Lax`. (#113)

### Changed
- Bump base image from `eclipse-temurin:25.0.2_10-jre-alpine` to
  `26_35-jre-alpine`. (#109)

### Fixed
- Align redirect-path expectations with handler logic in tests. (#111)

## [1.164.0] - 2026-04 (approximate)

### Added
- Multi-OIDC provider configuration in `plaintext-admin-oidc`.

### Changed
- Spring Boot 4.0.6 baseline; PrimeFaces 15.x; JoinFaces 5.x.

## [1.162.0] - 2026-04 (approximate)

### Added
- API token module (`plaintext-admin-apitoken`).
- Custom branding (per-mandate logo and colour palette).

### Fixed
- Logout flow on dynamic theme; integration-test stability.

## [1.158.x] - 2026-03

### Added
- Release workflow scaffolding.
- Dynamic theming (light/dark + per-tenant accent colour).

### Changed
- Build configuration extracted into `plaintext-build.cfg`.

---

For older history, run `git log` against the relevant tags. Release tags
follow the `<major>.<minor>.<patch>` pattern (no `v` prefix); the
`maven-release-plugin` is the source of truth.
