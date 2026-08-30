# German Terms in Code

This document lists all German terms remaining in the codebase (package names, class names, database tables, etc.) and their English equivalents. These terms are kept for backward compatibility but may be migrated in future versions.

## Module Names (Maven artifacts)

| Old German Name | New English Name | Status |
|----------------|-----------------|--------|
| `plaintext-root-menuesteuerung` | `plaintext-root-menu-visibility` | Renamed |
| `plaintext-root-rollenzuteilung` | `plaintext-root-role-assignment` | Renamed |
| `plaintext-admin-anforderungen` | `plaintext-admin-requirements` | Renamed |

## Java Package Names (kept for backward compatibility)

| German Package | English Meaning | Used In |
|---------------|----------------|---------|
| `ch.plaintext.menuesteuerung` | menu-control / menu-visibility | menu-visibility module |
| `ch.plaintext.rollenzuteilung` | role-assignment | role-assignment module |
| `ch.plaintext.anforderungen` | requirements | requirements module |

## Class Names

| German Class | English Meaning | Location |
|-------------|----------------|----------|
| `Rollenzuteilung` | RoleAssignment | role-assignment module |
| `RollenzuteilungService` | RoleAssignmentService | role-assignment module |
| `RollenzuteilungBackingBean` | RoleAssignmentBackingBean | role-assignment module |
| `RollenzuteilungRepository` | RoleAssignmentRepository | role-assignment module |
| `Anforderung` | Requirement | requirements module |
| `AnforderungService` | RequirementService | requirements module |
| `MandateMenuBackingBean` | MandateMenuBackingBean | menu-visibility module |
| `MandateMenuVisibilityService` | MandateMenuVisibilityService | menu-visibility module |
| `MandateMenuConfig` | MandateMenuConfig | menu-visibility module |

## Database Table Names

| German Table | English Meaning |
|-------------|----------------|
| `ROLLENZUTEILUNG` | ROLE_ASSIGNMENT |
| `WERTELISTE` | VALUE_LIST |
| `WERTELISTE_ENTRY` | VALUE_LIST_ENTRY |
| `ANFORDERUNG` | REQUIREMENT |

## UI Labels and XHTML Files

| German File/Label | English Meaning |
|------------------|----------------|
| `rollenzuteilung.xhtml` | Role Assignment |
| `anforderungen.xhtml` | Requirements |
| `anforderungdetail.xhtml` | Requirement Detail |
| `anforderungssettings.xhtml` | Requirement Settings |
| `howtos.xhtml` | How-Tos |
| `howtodetail.xhtml` | How-To Detail |

## The "Mandat" Term

### Current Usage

The term **"Mandat"** (German for "mandate" or "tenant") is used throughout the codebase as the multi-tenancy identifier. It appears in:

- **Database columns**: `mandat` column in virtually every table (via `SuperModel`)
- **Java fields**: `private String mandat` in `SuperModel` and many entities
- **Security**: `PlaintextSecurity.getMandat()`, `getAllMandate()`, `setMandat()`
- **Configuration**: `MandateMenuConfig`, `MANDATE_MENU_CONFIG` table
- **UI**: Mandate selector in topbar, mandate filter in admin pages
- **Spring Security**: Roles contain mandate info (e.g., `PROPERTY_MANDAT_default`)


## Why these names stay

Renaming a class here renames it in four other repositories that consume this
one. The modules were renamed once (2026); the packages, classes and tables were
not, because the cost falls on the consumers and the benefit is cosmetic. If a
rename ever happens it belongs in a release of its own, with a deprecation
window — not folded into a feature.

> Checked against the code on 30 August 2026. The `wertelisten` module and its
> table were removed before that date; entries for them have been deleted from
> this glossary rather than kept as history.
