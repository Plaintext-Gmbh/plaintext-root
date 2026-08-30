# Opting out of admin modules

`plaintext-root-webapp` is an aggregator: an app depends on this single artifact and gets all 22
internal modules — eleven of the twelve admin modules among them
(`plaintext-admin-requirements` is a module of this repository, but the aggregator
does not include it). Anyone who does not need a module throws it out with Maven `<exclusions>`.
Why exactly this way, and not through `optional`, a BOM or a property, is explained in
[ADR 0007](adr/0007-admin-module-abwaehlbar-per-exclusions.md).

**The default stays "everything on".** Without any action, nothing changes for an app.

## Which modules can be opted out of

| Module | Can be opted out | Rationale |
|---|---|---|
| `plaintext-admin-webhooks` | **yes** | No core code and no core XHTML touches the module. |
| `plaintext-admin-notifications` | **yes** | The only core reference is the bell in `includes/topbar.xhtml`, and it sits under `rendered="#{notificationTopbarBean != null}"`. It needs `plaintext-admin-mailtemplate` (mail delivery) — which stays anyway. |
| `plaintext-admin-secrets` | **yes** | `SecretResolver` lives in `-interfaces` and has no consumer inside the framework. |
| `plaintext-admin-modules` | **yes** | `MenuItemImpl` obtains the `ModuleEnablementProvider` through `beanFactory.getBean(…)` inside a try/catch; if it is missing, the menu hides nothing on a per-module basis. Dropping the module removes the "Module" page and the database-backed switch-off — the role variant `plaintext.menu.module-roles.<key>` remains (it lives in the `ModuleRoleService` of `plaintext-root-menu`). |
| `plaintext-admin-cron` | **no, deliberately** | The context does start without it — but `CronBeanPostProcessor` and `SuperCron` live in this module. Without them every `PlaintextCron` bean stays an ordinary bean and **never runs again**, with no error and no log line. A silent switch-off is worse than none. |
| `plaintext-admin-i18n` | **no** | The context does start without it, but `#{i18n.t('…')}` appears in practically every core XHTML (`login`, `myuser`, `useradmin`, `topbar`, …). Without the module no page renders any more. A case that starting the context alone would not have revealed. |
| `plaintext-admin-settings` | **no** | `LoginSetupBean` binds `SetupConfig`/`SetupConfigService` directly, and `#{brandingBean}` appears in `footer.xhtml` and `topbar.xhtml`. |
| `plaintext-admin-oidc` | **no** | `PlaintextOidcUserService`, `JdbcClientRegistrationRepository` and `LoginSetupBean` bind `OidcConfig`/`OidcConfigService` directly; `login.xhtml` queries `#{oidcConfigService.oidcEnabled}` unguarded. |
| `plaintext-admin-apitoken` | **no** | `JwksController` (core) imports `ch.plaintext.apitoken.JwtTokenService`. |
| `plaintext-admin-sessions` | **no** | `PasswordResetService` names `ch.plaintext.sessions.service.HttpSessionRegistry` in the type of an `ObjectProvider` — that loads the class even though the bean itself is optional. |
| `plaintext-admin-mailtemplate` | **no** | `RegistrationService`, `PasswordResetService` and `MagicLinkService` receive `IMailTemplateProvider` through the constructor, with no fallback path. It would only become optional together with a default rendering in the core — that is a behavioural change on three security-adjacent mail paths and belongs in a card of its own. |
| `plaintext-admin-requirements` | **not applicable** | It is not part of `plaintext-root-webapp` at all; whoever wants it declares it themselves (as `plaintext-app` does). |

## How an app opts out of a module

```xml
<dependency>
    <groupId>ch.plaintext</groupId>
    <artifactId>plaintext-root-webapp</artifactId>
    <exclusions>
        <exclusion>
            <groupId>ch.plaintext</groupId>
            <artifactId>plaintext-admin-webhooks</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Then check two more things:

1. **Flyway.** The module's migrations live inside its jar under `classpath:db/migration` and are
   gone with it. On a fresh database that has no consequences. If the database has already
   applied them, Flyway reports them as "applied migration not resolved locally" — the remedy is
   `spring.flyway.validate-on-migrate: false` in the app's `application.yml` (root and
   plaintext-app run with that setting anyway). The module's tables stay; dropping them is a
   separate, deliberate migration.
2. **Your own code.** If the app itself accesses the module (its own backing bean, its own
   service), it falls over with a `NoClassDefFoundError` — the exclusion applies to the whole
   classpath, not only to root.

The module's menu items disappear by themselves: they sit in its own `@MenuAnnotation` classes.

## How this is safeguarded

Two tests in `plaintext-root-webapp`, both part of a normal `mvn test`:

* **`SchlankerKontextTest`** runs in the second Surefire execution
  `kontext-ohne-abwaehlbare-module`. That execution takes the four jars off the test classpath
  via `classpathDependencyExcludes` — the absence is real, not simulated — and starts the Spring
  context against an embedded PostgreSQL. What is checked: context up, menu registry and user
  repository present, not a single bean from the four packages, Flyway with no pending migration.
  A `spring.autoconfigure.exclude` would **not** have proven that: in root, as in all four apps,
  the beans hang off `@ComponentScan("ch.plaintext")`, not off the auto-configuration.
* **`AbwaehlbareModuleXhtmlTest`** closes the gap from the other side. Starting the context
  renders no page; a core XHTML containing `#{webhookBean.x}` would go unnoticed. The test
  collects the bean names of the four modules and requires, for every mention in an XHTML from
  `plaintext-root-webapp` or `plaintext-root-template`, a null guard in the same document.

**Whoever changes the list changes it in four places:** `plaintext-root-webapp/pom.xml`
(`classpathDependencyExcludes` and the system property), `SchlankerKontextTest`,
`AbwaehlbareModuleXhtmlTest` — and this table. The test compares the count against the pom and
speaks up when the two drift apart.
