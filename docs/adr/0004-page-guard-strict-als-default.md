# Page Access Guard: `STRICT` for the root app, `REPORT` as the framework default

* **Status:** accepted
* **Date:** 2026-08-29 (recorded retroactively; behaviour in place since root 1.429.0, card 308)
* **Deciders:** Daniel Marthaler
* **Informed:** everyone working on the consumer apps

## Context

For a long time a JSF view's access protection was merely *rendered* through menu visibility:
whoever did not see the menu item did not find the page — but could open it all the same
(`mandatemenudetail` did not even have a menu item and was therefore wide open). Since 1.429.0
the page access guard (`plaintext-root-pageguard`) derives the decision from the same source that
visibility hangs on — `@MenuAnnotation(roles = …)` plus tenant visibility — and enforces it in a
filter **ahead of** the `FacesServlet`.

The open question was how to treat views that map to **no** menu item at all (detail and edit
pages). Fail-closed locks them out; fail-open keeps the gap that existed before.

## Decision

Two modes under `plaintext.security.page-guard.mode`:

* **`REPORT` is the framework default.** Canonical link comparison, deny on exception, allowlist
  and aliases all apply; a view without a mapping is **allowed and logged with WARN**, and the
  `PageAccessGuardStartupReport` lists it at boot.
* **The root app itself runs in `STRICT`** (set in its own `application.yml`): a view without a
  mapping → deny, and parent roles are inherited. A menu item with `roles` of its own stays
  final, so that a deliberately broad page underneath a narrow parent menu remains possible
  (`notifications.html`).
* Independently of the guard, the hard `requestMatchers` in `PlaintextSecurityConfig`
  (`ROOT_ONLY_PAGES`, `ADMIN_PAGES`) remain — a second layer, independent of the menu.

## Consequences

* **Positive:** the root app is fail-closed; `MenuLinkInvariantTest` enforces at build time that
  every view has a rule (menu item, alias or allowlist).
* **Positive:** on a framework update the consumers (app, guild, schuetu, iot) first get their
  list of gaps in the log instead of locked-out detail pages — and can then switch to `STRICT`
  deliberately.
* **Negative:** as long as an app stays on `REPORT`, the gap there is only *visible*, not closed.
  The default is deliberately the weaker mode; the switchover is work for each app.
* **Negative:** every app brings an `application.yml` of its own that shadows root's. Root's
  `STRICT` entry therefore takes effect **only** in root — you have to know that, or you will
  believe the framework is fail-closed everywhere.
* **Neutral:** until 1.491.0 `PageGuardProperties` was an inner class of
  `PlaintextSecurityProperties`; the prefix `plaintext.security.page-guard` stayed the same when
  it was extracted (`PageGuardAutoConfigurationTest` pins that).

## Alternatives considered

| Option | Why not? |
| --- | --- |
| `STRICT` as the framework default | Would have locked out every detail/edit view without a menu item in every consumer app on the next bump — an outage instead of a warning. |
| Maintaining only the hard `requestMatchers` | A hand-written list entry per page; that is exactly how `mandatemenudetail` slipped through. The guard derives from the source that is maintained anyway. |
| Checking only in `preRenderView` (JSF level) | Runs only in RENDER_RESPONSE, after the actions; the filter takes effect ahead of the `FacesServlet`. The `preRenderView` bean stays as a second layer. |

## References

* `docs/security/PAGE_ACCESS_GUARD.md`
* `plaintext-root-pageguard`: `PageGuardMode`, `PageGuardProperties`, `PageAccessGuardService`
* `plaintext-root-webapp/src/main/resources/application.yml` (`page-guard.mode: STRICT`, card 308)
* CHANGELOG 1.635.0 (module extraction, auto-configuration)
