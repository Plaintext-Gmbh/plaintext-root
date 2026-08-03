# Changelog

All notable changes to this project will be documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Going forward, entries are written from the same Conventional Commits stream
that drives the GitHub release tags. Earlier minor versions are summarised
from `git log` and may not be exhaustive.

## [Unreleased]

### Added
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
