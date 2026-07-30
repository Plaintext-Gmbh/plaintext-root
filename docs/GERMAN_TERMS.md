# German Terms in Code

This document lists all German terms remaining in the codebase (package names, class names, database tables, etc.) and their English equivalents. These terms are kept for backward compatibility but may be migrated in future versions.

## Module Names (Maven artifacts)

| Old German Name | New English Name | Status |
|----------------|-----------------|--------|
| `plaintext-root-menuesteuerung` | `plaintext-root-menu-visibility` | Renamed |
| `plaintext-root-rollenzuteilung` | `plaintext-root-role-assignment` | Renamed |
| `plaintext-admin-wertelisten` | `plaintext-admin-value-lists` | Renamed |
| `plaintext-admin-anforderungen` | `plaintext-admin-requirements` | Renamed |

## Java Package Names (kept for backward compatibility)

| German Package | English Meaning | Used In |
|---------------|----------------|---------|
| `ch.plaintext.menuesteuerung` | menu-control / menu-visibility | menu-visibility module |
| `ch.plaintext.rollenzuteilung` | role-assignment | role-assignment module |
| `ch.plaintext.wertelisten` | value-lists | value-lists module |
| `ch.plaintext.anforderungen` | requirements | requirements module |

## Class Names

| German Class | English Meaning | Location |
|-------------|----------------|----------|
| `Rollenzuteilung` | RoleAssignment | role-assignment module |
| `RollenzuteilungService` | RoleAssignmentService | role-assignment module |
| `RollenzuteilungBackingBean` | RoleAssignmentBackingBean | role-assignment module |
| `RollenzuteilungRepository` | RoleAssignmentRepository | role-assignment module |
| `Werteliste` | ValueList | value-lists module |
| `WertelisteEntry` | ValueListEntry | value-lists module |
| `WertelisteId` | ValueListId | value-lists module |
| `WertelistenService` | ValueListService | value-lists module |
| `WertelistenBackingBean` | ValueListBackingBean | value-lists module |
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
| `wertelisten.xhtml` | Value Lists |
| `rollenzuteilung.xhtml` | Role Assignment |
| `anforderungen.xhtml` | Requirements |
| `anforderungdetail.xhtml` | Requirement Detail |
| `anforderungssettings.xhtml` | Requirement Settings |
| `howtos.xhtml` | How-Tos |
| `howtodetail.xhtml` | How-To Detail |
| `menuesteuerung.xhtml` | Menu Control |

## The "Mandat" Term

### Current Usage

The term **"Mandat"** (German for "mandate" or "tenant") is used throughout the codebase as the multi-tenancy identifier. It appears in:

- **Database columns**: `mandat` column in virtually every table (via `SuperModel`)
- **Java fields**: `private String mandat` in `SuperModel` and many entities
- **Security**: `PlaintextSecurity.getMandat()`, `getAllMandate()`, `setMandat()`
- **Configuration**: `MandateMenuConfig`, `MANDATE_MENU_CONFIG` table
- **UI**: Mandate selector in topbar, mandate filter in admin pages
- **Spring Security**: Roles contain mandate info (e.g., `PROPERTY_MANDAT_default`)

### Migration Plan: Mandat → Tenant

Renaming "Mandat" to "Tenant" would be a major refactoring effort:

**Phase 1 - New API (non-breaking)**
1. Add `getTenant()` as alias for `getMandat()` in `PlaintextSecurity`
2. Add `tenant` field alias in `SuperModel` (maps to same column)
3. Add English getters/setters alongside German ones
4. Deprecate German methods with `@Deprecated`

**Phase 2 - Database Migration**
1. Create Flyway migration: `ALTER TABLE ... RENAME COLUMN mandat TO tenant`
2. Update all entity `@Column` annotations
3. This affects every table extending `SuperModel`

**Phase 3 - Full Code Migration**
1. Rename all `mandat` variables, parameters, methods
2. Update Spring Security role patterns
3. Update XHTML templates
4. Update REST API parameters

**Estimated Effort**: 3-5 days
**Risk**: High (touches every module)
**Recommendation**: Do Phase 1 first (backward-compatible), then Phase 2+3 in a dedicated release.
