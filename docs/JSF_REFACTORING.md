# JSF Quick-Win Refactoring — Application Guide

Machine-readable playbook for applying the Phase-1 JSF quick-wins (introduced in
plaintext-root ≥ 1.631.0) to any consumer application (plaintext-app, plaintext-guild,
plaintext-schuetu, plaintext-iot, plaintext-fwtool, or future projects deriving from
plaintext-root-parent). Written for AI agents and humans alike: every step states the
precondition, the mechanical change, and the verification.

## Background

The portfolio duplicated three UI concerns on almost every page:

1. a `<p:growl>` per page (~100 copies),
2. a global `<p:confirmDialog>` per page,
3. a private `addMessage()` helper per backing bean (~39 copies) or direct
   `FacesContext.getCurrentInstance().addMessage(...)` calls (~124 sites).

Since root 1.631.0 the shared template (`includes/template.xhtml` in
plaintext-root-template) renders ONE global growl (`widgetVar="globalGrowl"`, inside
`globalMessagesForm`) and ONE global confirm dialog for every page, and
`ch.plaintext.boot.plugins.jsf.FacesMessages` (plaintext-root-common) provides
`info/warn/error(String[, String detail])` as the single messaging entry point.

`FacesMessages` is also the migration seam: code that only calls this helper has no
other compile-time dependency on JSF messaging, which keeps a later move away from
JSF cheap.

## Step 1 — bump root

Precondition: the app builds against plaintext-root-parent.

- Set BOTH root version locations in the app `pom.xml`: the `<parent><version>` AND the
  `<plaintext-root.version>` property. They drift silently if only one is set.
- Verify: `mvn dependency:tree | grep plaintext-root-common` resolves the new version.

Nothing breaks at this point: page-local growls simply render next to the global one
(duplicate toasts, harmless), page-local global confirm dialogs are ignored by
PrimeFaces beyond the first.

## Step 2 — replace message plumbing (per module, mechanical)

For every backing bean:

- Delete the private `addMessage(FacesMessage.Severity, String)` helper.
- Replace calls:
  - `addMessage(FacesMessage.SEVERITY_INFO, x)` → `FacesMessages.info(x)`
  - `addMessage(FacesMessage.SEVERITY_WARN, x)` → `FacesMessages.warn(x)`
  - `addMessage(FacesMessage.SEVERITY_ERROR, x)` → `FacesMessages.error(x)`
  - `FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(SEVERITY_X, a, b))`
    → `FacesMessages.x(a, b)`
- Remove now-unused imports (`FacesMessage`, `FacesContext`).
- Do NOT change messages that target a specific client id (first argument non-null) —
  those are field-level validations and stay as they are.

Verify: module tests green; `grep -rn "private void addMessage" <module>` returns nothing.

## Step 3 — remove page-local duplicates (per page, independent releases)

For every `.xhtml` page:

- Remove `<p:growl .../>` unless the page depends on a page-specific growl feature
  (e.g. a custom `widgetVar` used by page JavaScript — then migrate that JS to
  `PF('globalGrowl')` first).
- Remove `<p:confirmDialog global="true">...</p:confirmDialog>` blocks — the template
  provides the dialog. Buttons using `<p:confirm .../>` keep working unchanged.
- Ajax updates that referenced the page growl (`update="messages"` etc.) can drop that
  id; global growl renders messages added during ANY request without being targeted.
  (Non-ajax postbacks always rendered messages anyway.)

Verify per page: trigger one success and one error path, both toasts appear exactly once.

## Step 4 — special cases

- **schuetu template fork**: schuetu ships its own `includes/template.xhtml` classpath
  shadow. Until that fork is dissolved, NONE of the template-level changes reach
  schuetu. Dissolve the fork first (diff the fork against the root template, move the
  deltas into `ui:insert` hooks or app CSS), then apply steps 1–3.
- **Pages with JS that calls `PF('growl')`**: the page-local widgetVar was usually
  `growl`. Either keep the local growl on that page for now, or switch the JS to
  `PF('globalGrowl')` when removing it.

## Roll-out order used in this portfolio

1. plaintext-root (template + helper + this doc) — release.
2. Consumer apps bump root (step 1) — no functional change, verify rendering.
3. Steps 2–3 module by module, page by page, in normal feature releases.

## Related follow-ups (Phase 1, tracked separately)

- PrimeFaces CSP mode (`primefaces.CSP` via the JoinFaces property) — render event
  handlers with a nonce; own inline `<script>` blocks need the nonce or must move to
  files before `'unsafe-inline'` can leave the CSP.
- Playwright smoke tests for guild and iot; measure and lower
  `number-of-views-in-session` (100 → 20–30) after measuring per-view session cost.
