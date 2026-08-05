# Module Reference

Plaintext Root is a multi-module Maven project. This page describes each module, its purpose, and key classes.

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
| `PlaintextEmailSender` | Send emails via configured SMTP accounts |
| `PlaintextEmailReceiver` | Read emails from IMAP mailboxes |
| `PlaintextIncomingEmailListener` | React to incoming emails |
| `ChatService` | Chat rooms, messages, and invitations |
| `IApiTokenService` | JWT-based API token management |
| `IWertelistenService` | Value lists (lookup tables) per mandate |
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

## Admin Modules

### plaintext-admin-settings

UI for managing application settings (key-value pairs, hierarchical, per mandate).

### plaintext-admin-sessions

Active session monitoring with user agent parsing, login timestamps, and session invalidation.

### plaintext-admin-cron

Cron job monitoring and management. Shows all registered `PlaintextCron` implementations with execution history.

### plaintext-admin-value-lists

Management UI for value lists (Wertelisten) — lookup tables with key-value pairs per mandate.

### plaintext-admin-requirements

Requirements management with AI integration (Claude automation). Includes REST API with full OpenAPI documentation.

## Template & Application

### plaintext-root-template

Open-source UI template providing layout CSS, navigation JavaScript, and XHTML templates. Supports light/dark mode, three menu layouts, and eight color themes.

### plaintext-root-webapp

Main web application module. Bundles all other modules and provides:

| Class | Purpose |
|-------|---------|
| `PlaintextSecurityConfig` | Spring Security configuration |
| `UserPreferencesRestController` | REST API for saving UI preferences |
| `VersionController` | Public version endpoint |

## Module Dependencies

```
plaintext-root-webapp
├── plaintext-root-template
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
├── plaintext-admin-value-lists

└── plaintext-admin-requirements
```
