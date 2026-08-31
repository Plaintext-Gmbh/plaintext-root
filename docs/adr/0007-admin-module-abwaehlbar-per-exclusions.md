# Opting out of admin modules: Maven `<exclusions>` instead of `optional`, a BOM or `@ConditionalOnProperty`

* **Status:** accepted
* **Date:** 2026-08-30 (status report of 29.08.2026, §3 "Aggregator ohne Opt-out")
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root-webapp` pulls in 22 internal modules, none of them `optional`. An app depends on
precisely this one artifact and thereby gets all of them: `plaintext-schuetu`, with 5.9k lines of
business code, drags along webhooks, notifications, secrets, OIDC and mail templates — code,
classpath and CVE surface. `plaintext-admin-modules` only switches off **menu visibility**
(`plaintext.menu.module-roles.<key>`), not the classpath.

The internal dependency graph is flat:

```
plaintext-root-interfaces          (contracts, no dependencies)
  └── plaintext-root-menu, plaintext-root-common
        └── all remaining root-* and admin-* modules
```

No admin module depends on another admin module; all twelve are leaves. The contracts
(`IMailTemplateProvider`, `ISetupConfigService`, `ModuleEnablementProvider`, `SecretResolver`,
`NotificationService`, …) already live in `plaintext-root-interfaces` — so the separation had
been thought through, it was just never exploited.

## Decision

**The modules stay non-`optional` in `plaintext-root-webapp`; whoever wants to be rid of one uses
Maven `<exclusions>`.** Plus a test run that proves the context starts without them.

The three alternatives that were considered are ruled out for concrete reasons:

1. **`<optional>true</optional>` on the dependencies.** Flips the default from "everything on" to
   "nothing on". All four apps depend on exactly one artifact and on the next root bump would
   **silently** lose modules they use — precisely the behavioural change this rework must not
   have. `optional` only becomes viable once the apps declare their modules themselves; that is a
   cut through four foreign repos and does not belong in this step.
2. **A separate BOM, `plaintext-root-bom`.** Would be a second copy of the same list, maintained
   in parallel: `plaintext-root-parent` **is** the BOM already — its `dependencyManagement`
   manages every internal artifact through `${plaintext-root.version}`, and all four apps have
   exactly that parent. A BOM would not add a single case here that does not work without it.
3. **`@ConditionalOnProperty` on the modules' auto-configurations.** Ineffective. In root **and
   in all four apps** the modules' beans hang off the boot class's `@ComponentScan("ch.plaintext")`,
   not off their auto-configuration — verified in all four repos. A switch that was turned off
   would have removed the auto-configuration and left the beans in place: a switch that looks as
   if it switched something.

Four modules can be opted out of today: `plaintext-admin-webhooks`, `-notifications`, `-secrets`,
`-modules`. Which ones cannot, and why, is in `docs/OPTIONAL_MODULES.md`.

## Consequences

* **Positive:** an app can drop modules without the other three noticing. For the existing apps
  nothing changes — the default stays "everything on".
* **Positive:** being able to opt out is no longer an accident that the next `@Autowired` takes
  away again. The Surefire run `kontext-ohne-abwaehlbare-module` in `plaintext-root-webapp` takes
  the four jars off the test classpath via `classpathDependencyExcludes` and starts the context
  without them; `AbwaehlbareModuleXhtmlTest` covers the path that starting the context does not
  see — a core XHTML that addresses a bean of the module.
* **Negative:** the list exists in four places (pom.xml, `SchlankerKontextTest`, `AbwaehlbareModuleXhtmlTest`, and the table in OPTIONAL_MODULES.md) (pom.xml, `SchlankerKontextTest`,
  `docs/OPTIONAL_MODULES.md`). Maven cannot read plugin configuration from Java; the test checks
  the coverage against the pom, the documentation stays manual work.
* **Negative:** whoever throws a module out of an app whose database has already applied that
  module's Flyway migrations needs `spring.flyway.validate-on-migrate: false` (root itself has
  set that anyway ever since `plaintext-root-email` was dropped). It is in the guide.
* **Open:** `plaintext-admin-cron` demonstrably starts without it as well — but switching it off
  would be **silent**: `PlaintextCron` beans would stay ordinary beans and never run again. That
  is why it is not on the list. Whoever wants to add it needs a loud notice at startup first
  (a `PlaintextCron` bean without a `CronBeanPostProcessor` → warning).
