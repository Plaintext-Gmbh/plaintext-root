# Module Reference

Plaintext Root is a multi-module Maven project with **24 modules** (the `<modules>` section of the root `pom.xml` is authoritative). This page describes each module, its purpose, and key classes.

## Infrastructure Modules

### plaintext-root-interfaces

Shared interfaces that define the framework's public API and extension points. No implementation code — only contracts.

| Interface | Purpose |
|-----------|---------|
| `PlaintextSecurity` | Security context: current user, mandate, impersonation |
| `ISettingsService` | Hierarchical key-value settings per mandate |
| `MenuRegistry` | Access registered menu items and their metadata |
| `MenuVisibilityProvider` | Control menu visibility per mandate |
| `PlaintextCron` | Implement scheduled cron jobs |
| `IApiTokenService` | JWT-based API token management |
| `SearchProvider` | Contribute hits to the global search (Cmd+K) |
| `ModuleRoleProperties` | `plaintext.menu.module-roles.*` — role required per module |
| `IKontaktService` | Contact management |
| `IRechnungService` | Invoice management with PDF generation |
| `IMermaidService` | Mermaid diagram generation |
| `IUploadTarget` | File upload handling |

### plaintext-root-common

Shared utilities used across modules.

| Class | Purpose |
|-------|---------|
| `XStreamSerializer` | XML serialization with XStream |
| `SimpleStorableEntity` | Generic key-value object storage (JPA) |
| `ObjectStoreService` | CRUD operations for storable entities |
| `PlaintextRoleProvider` | Module contribution interface for declaring roles (see [Role Registry](ROLE_REGISTRY.md)) |
| `PlaintextRole` | Declared role: technical name plus human-readable description |
| `PlaintextRoleRegistry` | Collects all declared roles (union, deduplicated) for selection UIs |

### plaintext-root-jpa

Base JPA entities with audit fields and soft-delete support.

| Class | Purpose |
|-------|---------|
| `SuperModel` | Base entity with `mandat`, `createdBy`, `createdDate`, `deleted`, `tags` |
| `GenericRepository` | Generic JPA repository with mandate-aware queries |

### plaintext-root-flyway

Database migration management. Migrations are located in each module's `src/main/resources/db/migration/` directory.

| Class | Purpose |
|-------|---------|
| `FlywayRepairCallback` | Auto-repairs failed migrations on startup |

## Business Modules

### plaintext-root-menu

Annotation-driven menu system with hierarchical support.

| Class | Purpose |
|-------|---------|
| `MenuItemImpl` | Base class for defining menu items as Spring beans |
| `MenuModelBuilder` | Builds PrimeFaces MenuModel from registered items |
| `MenuAnnotation` | Annotation to mark classes as menu items |

### plaintext-root-menu-visibility

Mandate-based menu visibility control. Allows hiding menus for specific tenants.

### plaintext-root-role-assignment

User role assignment and management UI (ROLE_USER, ROLE_ADMIN, ROLE_ROOT).

### plaintext-root-pageguard

Page Access Guard — per-view authorization derived from menu visibility (roles and
mandate), enforced in a servlet filter before the `FacesServlet`. Own module so an
application consuming single modules gets page-level authorization without the whole web
stack. Registered via `PageGuardAutoConfiguration` (`AutoConfiguration.imports`), publishes
a `test-jar` with `PageAccessGuardTestFactory`.

| Class | Purpose |
|-------|---------|
| `PageAccessGuardService` | Decision "may this view be opened?" — menu lookup, allowlist, aliases, mode |
| `PageAccessGuardFilter` | Enforcement in the Spring Security chain |
| `PageGuardMode` / `PageGuardProperties` | `REPORT` (framework default) vs `STRICT` (fail-closed, root app), prefix `plaintext.security.page-guard` |
| `PageAccessGuardStartupReport` | Lists views without an access rule at boot |

See [security/PAGE_ACCESS_GUARD.md](security/PAGE_ACCESS_GUARD.md) and ADR 0004.

### plaintext-root-web

Reusable JSF/web infrastructure carved out of `plaintext-root-webapp` so that an
application can take URL rewriting and the security provider without the application
module. Registered via `WebAutoConfiguration` (ordered before `MenuAutoConfiguration`
so the real `SecurityProvider` beats the menu module's permissive default).

| Class | Purpose |
|-------|---------|
| `UrlRewriteConfig` | `.html`/`.htm` -> `.xhtml` rewrite filter |
| `SpringSecurityProvider` | `SecurityProvider` backed by the Spring `SecurityContext` |
| `MenuBean` | View-scoped menu model for the template |
| `SessionTrackingConfig` | Cookie-only session tracking for every consuming app |
| `MenuDebugController` / `XhtmlDebugController` | Debug endpoints |

## Admin Modules

### plaintext-admin-settings

UI for managing application settings (key-value pairs, hierarchical, per mandate).

### plaintext-admin-sessions

Active session monitoring with user agent parsing, login timestamps, and session invalidation.

### plaintext-admin-cron

Cron job monitoring and management. Shows all registered `PlaintextCron` implementations with execution history.

### plaintext-admin-requirements

Requirements management with AI integration (Claude automation). Includes REST API with full OpenAPI documentation.

### plaintext-admin-i18n

Translatable resource bundles with an admin UI (`I18nService`, `I18nTranslationsMenu`,
`I18nExportController`). The topbar language switch is enabled per mandate via
`branding.i18n.enabled` (falls back to the legacy global `i18n.enabled`).

### plaintext-admin-oidc

OIDC/OAuth2 client registrations stored in the database (`OidcConfig`,
`OidcConfigService`, `JdbcClientRegistrationRepository`) instead of `application.yml`.
ROOT-only page.

### plaintext-admin-apitoken

JWT API tokens for REST/MCP access (`JwtTokenService`, `ApiTokenValidatorServiceImpl`,
`McpBearerTokenFilterConfig`). Separate ROOT and ADMIN pages (`RootApiTokenMenu`,
`AdminApiTokenMenu`).

### plaintext-admin-secrets

Secret store with pluggable backends (`SecretService`, `SecretBackendConfig`), ROOT-only.
Values are encrypted at rest with `ConfigEncryptionService` (see [CRYPTO.md](CRYPTO.md)).

### plaintext-admin-modules

Module registry and activation per application (`ModuleService`, `ModuleConfig`,
`ModuleDataService`, `ModuleDangerZoneService`). The `moduleId` shown here is the key for
`plaintext.menu.module-roles.<moduleId>`.

### plaintext-admin-mailtemplate

Editable mail templates with per-mandate overrides (`MailTemplateService`,
`MailTemplateConfig`). ADMIN and ROOT (menu **Admin**).

### plaintext-admin-webhooks

Outgoing webhook configuration and dispatch (`WebhookConfig`, `WebhookEndpointService`,
`WebhookDispatchService`). ROOT-only.

### plaintext-admin-notifications

In-app notifications behind the topbar bell (`NotificationServiceImpl`,
`NotificationMenu`); `notifications.html` is reachable for every user.

## Template & Application

### plaintext-root-template

Open-source UI template providing layout CSS, navigation JavaScript, and XHTML templates. Supports light/dark mode, three menu layouts, and eight color themes.

### plaintext-root-webapp

Main web application module. Bundles all other modules and provides:

| Class | Purpose |
|-------|---------|
| `PlaintextSecurityConfig` | Spring Security configuration incl. the hard-wired `ROOT_ONLY_PAGES` / `ADMIN_PAGES` |
| `UserPreferencesRestController` | REST API for saving UI preferences |
| `VersionController` | Public version endpoint |

### plaintext-root-archtests

Shared ArchUnit rules published as a **main** jar so the consuming applications run the
same checks: `PlaintextArchitectureTest`, `PlaintextViewScopedBanTest`,
`PlaintextPrivateKeyBanTest`, `PlaintextAjaxAntwortLesbarTest`, `PlaintextMcpScopeVertragTest`,
`PlaintextOwaspSuppressionsTest`, `PlaintextMobileFormLinterTest`.

## Module Dependencies

```
plaintext-root-webapp
├── plaintext-root-template
├── plaintext-root-web
│   └── plaintext-root-menu
├── plaintext-root-pageguard
│   └── plaintext-root-menu
├── plaintext-root-interfaces
├── plaintext-root-jpa
│   └── plaintext-root-common
├── plaintext-root-menu
│   └── plaintext-root-interfaces
├── plaintext-root-menu-visibility
│   └── plaintext-root-interfaces
├── plaintext-root-role-assignment
│   └── plaintext-root-interfaces
├── plaintext-root-flyway
├── plaintext-admin-settings
├── plaintext-admin-sessions
├── plaintext-admin-cron
├── plaintext-admin-requirements
├── plaintext-admin-i18n
├── plaintext-admin-oidc
├── plaintext-admin-apitoken
├── plaintext-admin-secrets
├── plaintext-admin-modules
├── plaintext-admin-mailtemplate
├── plaintext-admin-webhooks
└── plaintext-admin-notifications

plaintext-root-archtests   (test rules, consumed by the applications; not on the webapp's path)
```

The exact dependency edges are in the module `pom.xml` files; the tree above is the
consumer's view.
