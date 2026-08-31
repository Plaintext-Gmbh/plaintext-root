# `plaintext-root-web` and `plaintext-root-pageguard` extracted from `plaintext-root-webapp`

* **Status:** accepted
* **Date:** 2026-08-29 (recorded retroactively; carried out in the 1.635.0 release line)
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root-webapp` is an **application** module: its own `application.yml`, its own
`SecurityFilterChain`, its own JPA entities, and it pulls in eighteen `ch.plaintext` modules plus
Flyway and PostgreSQL. Even so, it contained things that every consumer app needs, even one that
does not want the whole root app: the `.html`/`.htm` → `.xhtml` rewrite filter, the
Spring-Security-backed `SecurityProvider`, `MenuBean`, and the complete page access guard. An app
that only wanted the URL rewriting, or only the page protection, had to take all of it.

On top of that came a second problem: those classes were `@Component`/`@Service` and reachable
only through a component scan of `ch.plaintext`. An app that did not scan that package started up
cleanly — and **silently** had no guard and no rewriting (every `.html` link a 404). Fail-open
with no error message.

## Decision

Two new modules, both registered through `AutoConfiguration.imports` instead of through the
component scan:

* **`plaintext-root-web`**: `UrlRewriteConfig`, `SpringSecurityProvider`, `MenuBean`,
  `SessionTrackingConfig`, the debug controllers. `WebAutoConfiguration` is
  `@AutoConfigureBefore(MenuAutoConfiguration)` so that the real `SecurityProvider` beats the
  permissive default of the menu module (`SecurityProviderReihenfolgeTest`). `MenuBean` keeps its
  `@Component`, because it lives in the JSF `view` scope, which only an app with JoinFaces knows.
* **`plaintext-root-pageguard`**: `PageAccessGuardFilter`, the service, the startup report, the
  `preRenderView` bean, `PageGuardMode`; `PageGuardAutoConfiguration`; it publishes a `test-jar`
  with `PageAccessGuardTestFactory` so that an app can check its own views against its own menus.

Apps built on `plaintext-root-webapp` are unaffected — both modules arrive transitively, and the
filter is still hooked into the security chain by `PlaintextSecurityConfig`.

## Consequences

* **Positive:** a consumer can take page protection and URL rewriting individually; the modules
  are safeguarded with `@ConditionalOnWebApplication` / `@ConditionalOnMissingBean`.
* **Positive:** no more silent fail-open through a missing component scan — the
  auto-configuration takes effect without the app doing anything.
* **Negative:** two more reactor modules (24 instead of 22); every module boundary is one more
  jar build step and one more entry in the consumer POMs (see ADR 0003 on the price of the
  multi-module layout).
* **Negative:** bean ordering is now a dependency between modules; whoever touches it risks the
  permissive `SecurityProvider` winning — every menu item visible, every page reachable. The test
  pins that, and nothing more.
* **Neutral:** `PlaintextSecurityConfig` stays in the webapp and knows both modules; the filter's
  position in the chain is documented there.

## Alternatives considered

| Option | Why not? |
| --- | --- |
| Leave everything in `plaintext-root-webapp` | Whoever wants page protection gets the whole app including entities and Flyway — that was the starting point. |
| One single module `plaintext-root-web` for both | The guard makes sense without a JSF template, the rewriting without the guard; kept separate, each can be consumed on its own. |
| Mandate a component scan of `ch.plaintext` in every app | Fixes the fail-open by convention only; the auto-configuration fixes it in the framework. |

## References

* CHANGELOG 1.635.0, sections «Added» (`plaintext-root-web`, `plaintext-root-pageguard`,
  `PageGuardAutoConfiguration`) and «Fixed» (`WebAutoConfiguration` ordering)
* ADR 0003 (Maven multi-module), ADR 0004 (guard modes)
* `docs/MODULE_REFERENCE.md`
