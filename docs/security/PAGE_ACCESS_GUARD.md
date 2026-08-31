# Page Access Guard

Status: implemented since root 1.429.0.

The page access guard derives a JSF view's access protection from **menu visibility**: whoever
may see the menu item that points at a page may open that page. This covers roles
(`@MenuAnnotation(roles = ...)`) **and** tenant-specific visibility (`MenuVisibilityProvider`,
module `plaintext-root-menu-visibility`).

## Building blocks

| Class | Responsibility |
|---|---|
| `PageAccessGuardService` | the decision "is this view allowed?" — menu lookup, allowlist, aliases, mode |
| `PageAccessGuardFilter` | enforcement **before** the `FacesServlet`, hooked into the Spring Security chain |
| `PageAccessGuardBackingBean` | second layer, as an `f:event preRenderView` in `includes/template.xhtml` |
| `PageAccessGuardStartupReport` | lists the views without an access rule at boot time |
| `PlaintextSecurityConfig` | hard `requestMatchers` for the admin/ROOT pages (independent of the menu) |
| `MenuLinkInvariantTest` | build guardrail: every link ends in `.html`, points at an existing view, and every view has a rule |

## Modes

`plaintext.security.page-guard.mode`

| | `REPORT` (framework default) | `STRICT` |
|---|---|---|
| canonical link comparison (`.htm`/`.html`/`.xhtml`/`.jsf`) | yes | yes |
| exception during the check | deny | deny |
| allowlist / aliases | yes | yes |
| view **without** menu entry, alias or allowlist | allow **+ WARN** | **deny** |
| inheritance of parent roles | no | yes |

`plaintext-root-webapp` itself runs in `STRICT` (`application.yml`). The framework default is
`REPORT`, because every consuming app brings views of its own: without a transitional mode,
`plaintext-app`, `plaintext-guild` and `plaintext-schuetu` would lock themselves out of their
detail and edit pages on a framework update. Every app brings its own `application.yml`; the
framework's is shadowed on the classpath and therefore only takes effect for the root app.

## Adding a new page

1. Standard case: `@MenuAnnotation(link = "meineseite.html", parent = "...", roles = {...})`.
   **The link must end in `.html`** — `MenuLinkInvariantTest` enforces that.
2. Detail or edit page without a menu item of its own: set an **alias**, and the rules of the
   list page then apply.
   ```yaml
   plaintext:
     security:
       page-guard:
         aliases:
           rechnungdetail.xhtml: rechnungen.html
   ```
   Framework aliases (in `PageAccessGuardService.FRAMEWORK_ALIASES`):
   `mandatemenudetail → mandatemenu`, `anforderungdetail → anforderungen`,
   `claudesummary → anforderungen`, `howtodetail → howtos`.
3. A page that really every logged-in user may see: **allowlist**.
   ```yaml
   plaintext:
     security:
       page-guard:
         allowlist:
           - wander-druck.xhtml
           - public/**
   ```
   Framework allowlist: `login-totp`, `myuser`, `useradmin`, prefix `nosec/**`.
   System pages: `home`, `index`, `access-denied`, `error`, `login`.

## Roles and parent menus

`MenuItemImpl.isOn()` only checks the item's **own** `roles`. In the rendered menu an invisible
parent menu nevertheless hides all of its children (`PrimefacesSubmenu.isRendered()`) — the guard
used not to reproduce that hierarchy. A menu item without `roles` of its own underneath "Root"
was therefore open to every logged-in user via a direct URL.

In `STRICT` mode a menu item **without `roles` of its own** inherits the visibility of its parent
menu. If it declares its own `roles`, those are final. That is precisely what keeps a
deliberately more widely reachable page possible underneath a restricted parent menu:

```java
// notifications.html hangs under "Root", but the topbar bell links to it for every user
@MenuAnnotation(title = "Benachrichtigungen", link = "notifications.html", parent = "Root",
                roles = {"USER", "ADMIN", "ROOT"})
```

## Why a filter and not `preRenderView`

`preRenderView` fires in RENDER_RESPONSE (phase 6), whereas action methods run in
INVOKE_APPLICATION (phase 5). An AJAX or POST postback to a locked page had therefore already
**executed** the backing bean action; the redirect afterwards only discarded the response. The
filter takes effect before any JSF phase.

It is hooked into the **Spring Security chain** with
`http.addFilterAfter(..., AuthorizationFilter.class)`, not with a `FilterRegistrationBean`. The
reason: `UrlRewriteConfig` registers its rewrite filter with `Ordered.HIGHEST_PRECEDENCE + 1`,
that is, ahead of Spring Security (`order = -100`), and forwards `/x.html` to `/x.xhtml` without
`chain.doFilter()`. A separately registered filter with `DispatcherType.REQUEST` would therefore
never run for `.html` URLs. The security chain, by contrast, runs for all dispatch types
(`SecurityFilterProperties.dispatcherTypes = EnumSet.allOf(...)`).

Response behaviour when access is denied: an ordinary GET → `302` to `/access-denied.html`,
POST/AJAX → `403` (a `302` in response to a postback would fool the client into thinking the
action had been carried out).

## Emergency stop

`plaintext.security.page-guard.enabled=false` switches off both the filter **and** the
`preRenderView` guard. Intended only for the case where the guard locks legitimate pages in PROD
and no rollback is possible. The hard `requestMatchers` in `PlaintextSecurityConfig` are
unaffected by it.

## Open items (follow-up cards)

- Enable `@EnableMethodSecurity` and move authorization down into the service / backing bean;
  menu visibility should then be pure UX.
- Switch the consumer apps to `mode: STRICT` once all of their views are fully mapped.
  `MenuLinkInvariantTest` belongs in `plaintext-root-archtests` after that — that module runs as
  part of the consumer builds and would enforce the invariant there.
- Delete `demo.xhtml` (an orphaned example page, currently blocked on purpose).
