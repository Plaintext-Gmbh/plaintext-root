---
layout: default
title: Role Registry
---

# Role Registry (module role registration)

Modules — in root itself and in apps that build on root — declare their roles as a Spring bean.
Root collects all declarations (a deduplicated union) and offers them as a choice in the
**user administration** (`useradmin.xhtml`) and in the role assignment, instead of forcing role
names to be typed in freehand.

The mechanism follows the same pattern as the [menu system](ARCHITECTURE.md#menu-system):
modules contribute their part as a bean, root collects them centrally.

## Declaring roles

One `PlaintextRoleProvider` bean per module (the interface lives in `plaintext-root-common`,
`ch.plaintext.framework`):

```java
@Component
public class MeinModulRoleProvider implements PlaintextRoleProvider {

    @Override
    public Set<PlaintextRole> getDeclaredRoles() {
        Set<PlaintextRole> ret = new LinkedHashSet<>();
        ret.add(new PlaintextRole("postkonto", "Zugriff auf das Postkonto-Modul"));
        ret.add(new PlaintextRole("privatausgaben", "Erfassen und Auswerten privater Ausgaben"));
        return ret;
    }

    @Override
    public Set<String> getRoles() {
        return getDeclaredRoles().stream().map(PlaintextRole::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

Existing providers that only implement `getRoles()` keep working unchanged: `getDeclaredRoles()`
has a default implementation that derives roles without a description.

Reference implementation: `RootRoleProvider` (`plaintext-root-webapp`,
`ch.plaintext.boot.plugins.security`) declares root's own roles `root`, `admin`, `user`.
`ROLE_SYSTEM` is deliberately not declared (a purely technical authority for cron and the bus).

## Naming conventions

| Form | Example | Used for |
|------|---------|----------|
| `PlaintextRole.name()` | `admin` or `ROLE_ADMIN` | as declared |
| `PlaintextRole.normalizedName()` | `admin` | identity/dedup; storage format on `MyUserEntity` |
| `PlaintextRole.authorityName()` | `ROLE_ADMIN` | Spring Security authority / role assignment |

Declarations are deduplicated case- and prefix-insensitively: `admin`, `ADMIN` and `ROLE_admin`
are the same role. If several modules declare the same role, the first non-empty description
wins.

## Collecting them (consumer side)

`PlaintextRoleRegistry` (`@Component`, `plaintext-root-common`) collects all provider beans:

| Method | Returns |
|--------|---------|
| `getDeclaredRoles()` | sorted `PlaintextRole` list (name + description) |
| `getDeclaredRoleNames()` | normalized names (`admin`, ...) |
| `getDeclaredAuthorityNames()` | authority names (`ROLE_ADMIN`, ...) |
| `getDescription(name)` | description of a role (any spelling) |

## Existing roles are not lost

The selection UIs always merge the **existing stock** from the database into the declared roles
(roles stored on the user, or granted through the role assignment). A role that no module
declares (any more) therefore stays visible and selectable — it only loses its description.

---

# Configurable module roles (`plaintext.menu.module-roles`)

*since root 1.604.0*

An app can assign a role to an **entire module** by configuration. Without that role the module
does not exist for the user:

* the **menu item** (and all submenus) is invisible,
* the **dashboard tile** is invisible,
* **direct access** to the pages is denied by the `PageAccessGuard`.

`admin` and `root` **always** retain access. Modules without an entry behave exactly as before.

## Why configuration and not module code

The same module artifact is bundled by several apps — `plaintext-z-mailbox` runs in
`app.plaintext.ch` **and** in `app.guild42.ch`. A role in the module code would be the same role
in both apps. The assignment therefore belongs in the configuration of the individual app;
**nothing is changed in the module**.

## Configuration

```yaml
plaintext:
  menu:
    module-roles:
      wiki: wiki            # only someone with ROLE_WIKI sees the wiki
      mailbox: mail
      postkonto: finanzen   # several modules may share one role
      rechnungen: finanzen
      buchhaltung: finanzen
```

The role name is written without the `ROLE_` prefix; case does not matter (`wiki`, `WIKI` and
`ROLE_Wiki` are the same role).

## The module key

Primarily the **`moduleId`** — the same value as in `@MenuAnnotation(moduleId = "…")` or
`ModuleDescriptor#moduleId()`, that is, exactly what the admin panel **"Root | Module"**
displays.

For modules without a `moduleId` the **menu root id** applies as a fallback: the `menuId` of the
module's topmost menu item, or failing that the identifier derived from its title (lower-cased,
umlauts transliterated, special characters turned into `_`). Both forms work — for the module
*Rechnungen*, for instance, both `rechnungen` (moduleId) and `rechnungsverwaltung` (menu root
title).

**Finding a module's key** — three ways:

1. Admin panel **"Root | Module"**: the *Modul-Id* column.
2. The app's startup log:
   `ModuleRoleService : Modul-Rollen aktiv: {wiki=WIKI} — erkannte Modul-Keys: [anschreiben, buchhaltung, …]`
3. In the source: the `moduleId` of the `@MenuAnnotation` on the module's root menu.

A configured key that no menu item claims does **not** break startup — it is reported as a WARN
at startup (including the list of known keys).

## How menu, tile, PageGuard and registry interact

| Building block | Effect |
|----------------|--------|
| `ModuleRoleProperties` (`plaintext-root-interfaces`) | binds `plaintext.menu.module-roles`, canonicalizes keys and roles |
| `ModuleRoleService` (`plaintext-root-menu`) | derives every menu item's module membership from `moduleId` + parent chain and writes the required roles onto the menu item |
| `MenuItemImpl.isOn()` | checks the module role as its second step — the menu **and** the `PageAccessGuard` both call this method |
| `TileItemImpl.isOn()` | maps the tile to the module menu via its `link` (or `menuTitle` as a fallback) |
| `ModuleRoleDeclarationProvider` (`plaintext-root-common`) | registers the configured roles with the `PlaintextRoleRegistry` automatically |

Because the check sits **inside** `MenuItemImpl.isOn()`, it also applies where the guard's parent
inheritance explicitly does not: submenus that declare `roles = {USER, ADMIN, ROOT}` themselves
(the normal case) would otherwise be open via a direct URL.

## Granting the role

The configured roles appear in the user administration **automatically** — the app needs no
`PlaintextRoleProvider` of its own for that. The description is generated:

* one role for one module → *"Zugriff auf das Modul wiki"* ("access to the module wiki")
* one role for several modules → *"Zugriff auf die Module buchhaltung, postkonto, rechnungen"*
  ("access to the modules buchhaltung, postkonto, rechnungen")

If the app already declares its own provider for the same role (guild for `finanzen`, for
example), that provider's more detailed description wins.

> **After the deployment:** nobody holds a newly configured module role yet. Until it is granted
> in the user administration, only `admin` and `root` see the module.

---

# Rule of responsibility: root says WHICH, admin says WHO

*since root 1.608.0*

A menu item's visibility has **two responsible parties**, and they answer different questions.
Confusing the two means looking for the fault in the wrong place.

| Question | Who | Tool |
|----------|-----|------|
| **Which modules belong to this tenant?** | `root` | `Root → Menuesteuerung` (menu visibility; tenant whitelist/blacklist) |
| **Who is allowed to use them?** | `admin` | granting module roles to users (user administration, role assignment) |

The four filters in `MenuItemImpl.isOn()` stay **AND-combined**: a role does **not** override the
tenant filter. That is deliberate — the tenant filter describes a tenant's scope, not a
permission.

## The four filters at a glance

| # | Filter | Source | Bypass |
|---|--------|--------|--------|
| 1 | Role | `@MenuAnnotation(roles = …)` | — |
| 2 | Module role | `plaintext.menu.module-roles` | `admin`, `root` |
| 3 | Module enabled | `Root → Module` | — |
| 4 | Tenant | tenant whitelist/blacklist | **only** the root branch, for `root` |

## The one exception: the root branch

The tenant filter has exactly **one** exception, and it is drawn as narrowly as possible:

```java
// MenuItemImpl
public boolean isRootBranchExemptFromMandate() {
    return isUnderRootMenu() && securityProvider != null && securityProvider.hasRole(ROLE_ROOT);
}
```

Both conditions must hold: the menu item lies in the **root branch** (title `Root` or parent menu
`Root`) **and** the user holds `ROLE_ROOT`.

**Why it exists:** the menu visibility page itself (`Root | Menuesteuerung`) hangs in the root
branch. If a tenant is in whitelist mode without that title, root locks itself out of the only UI
that could correct the list — through the menu *and* through a direct URL, because the
`PageAccessGuard` evaluates the same `isOn()`. Measured on app.plaintext.ch: in **8 out of 10**
tenants the menu visibility page was unreachable this way.

**What it is not:** a general bypass. Outside the root branch the tenant filter stays in force for
root as well, and the other three filters remain untouched everywhere — a disabled module stays
disabled in the root branch too.

## Tenant lists: two forms of entry

| Form | Example | Effect |
|------|---------|--------|
| **Module** | `modul:wiki` | the whole module including all submenus |
| **Menu title** | `Wiki \| Projekte` | exactly this one menu item |

The module key is **the same** one the module roles use (`moduleId`, or the menu root id as a
fallback). Both responsible parties therefore speak the same vocabulary:
`ModuleRoleService.resolve()` writes the keys onto every menu item as `MenuItemImpl.moduleKeys`,
and `MenuItemImpl` passes them on to the tenant list via
`MenuVisibilityProvider.isMenuVisible(titel, moduleKeys)`.

**Why the prefix `modul:` and not the bare `moduleId`** — the changeover has to be lossless. A
bare `moduleId` would be ambiguous: a module with the id `wiki` usually has its root menu under
the title `Wiki`, and the existing data additionally holds entries that no longer match any title
(renames — 19 out of 123 on app.plaintext.ch). A bare key would therefore be a title sometimes
and a module other times, depending on the state of the menu tree. With the prefix, the intent
can be read off the entry itself: **everything without a prefix is and stays a title**, and
because no menu title starts with `modul:`, no existing entry can change its meaning.

The detail page keeps three separate selections — modules, menu items and **entries with no
counterpart in the menu tree**. The third group is preselected: saving throws nothing away that
the editor has not deliberately deselected.

## Dead list entries

At startup, `MandateMenuStartupReport` reports per tenant which entries point nowhere in the
current menu tree — analogous to the existing message `ModuleRoleService: Modul-Rolle
konfiguriert fuer unbekannten Modul-Key`:

```
WARN  Menuesteuerung: Mandant 'lauftage2026' (Whitelist-Modus) hat 3 Listen-Eintraege ohne
      Entsprechung im Menuebaum — sie wirken nicht (umbenannter Menuepunkt?): [...]
```

Effect of a dead entry: in **blacklist** mode it no longer hides anything; in **whitelist** mode
it silently **hides** the renamed item.

## Diagnostics view

`Root → Menue-Diagnose` (`menudiagnose.html`, ROOT only) shows every menu item with the four
filters as separate columns and gives a reason for every *no* ("Rolle WIKI fehlt", "nicht in
Whitelist von lauftage2026", "Modul 'wiki' ist deaktiviert").

The yes/no values come from **the same** methods the menu and the `PageAccessGuard` use
(`MenuItemImpl.isRoleVisible()`, `isModuleRoleVisible()`, `isModuleVisible()`,
`isMandateVisible()` — public for exactly that reason). `MenuDiagnoseService` only produces the
accompanying text; a second copy of the logic would drift.

In **impersonate** mode the table shows the impersonated user's view, without any extra work: the
menu items query the `SecurityProvider` of the running session, and impersonation swaps out the
`Authentication` in exactly that place.

## Which roles admin may grant

`PrivilegedRoleRules` (`plaintext-root-common`, `ch.plaintext.framework`) is the single place
where that is decided — used by the user administration **and** by the role assignment.

| Role | admin may newly grant | Reason |
|------|-----------------------|--------|
| module roles (`wiki`, `finanzen`, …) | **yes** | pure access roles for a business module — precisely admin's job |
| `user` | yes | no administrative rights |
| `admin` | no | admin could otherwise lift its own restriction |
| `root` | no | administrative rights |
| `PROPERTY_*` | no | takes effect beyond one's own tenant (switching tenants) |

**Existing assignments stay untouched:** the rule only governs granting something NEW. An
assignment that is already persisted remains in place and editable; both callers therefore check
against the persisted state, not against the form.

The role assignment (`Admin → Rollenzuteilung`) needs the same check as the user administration —
otherwise it would be the detour by which an admin grants themselves `ROLE_ROOT`.

## Removing tenants

`Root → Mandate → Aus Verwaltung entfernen` deletes **only what the framework owns**: the
tenant's `mandate_menu_config`, and with it its entry in the selection list. The application's
business data stays — the framework does not know those tables (in plaintext-app they are spread
over eight tables). Previously the action only removed the tenant from an `ArrayList` in the
session and reported success anyway; after a `reload()` it was back.

Two traps when searching for tenant data:

1. **Tenant membership exists twice** — as the role property `PROPERTY_MANDAT_<NAME>` on the user
   (home tenant) and in the `user_mandate` table (additional tenants). Anyone checking only the
   first reports "no users affected" even though some are assigned.
2. **Tenant names are not case-consistent** — in `user_session` it was stored as `BUTSCHER` in
   uppercase, while the same tenant is written `butscher` in lowercase everywhere else. Always
   compare case-insensitively (`findByMandatIgnoreCase`, `findByMandateNameIgnoreCase`).

## Guide pages inside the application

| Page | For | Content |
|------|-----|---------|
| `Root → Menuesteuerung → ⓘ Anleitung` (info button, no menu item of its own since 1.634.0) | ROOT | whitelist/blacklist, when to use which mode, module vs. title entry, reading the diagnostics, impersonate, creating and removing tenants |
| `Admin → Anleitung Modul-Rollen` | ADMIN, ROOT | granting module roles, what they do, interplay with the tenant lists, when you need root |
