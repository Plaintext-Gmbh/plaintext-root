# plaintext-admin-settings

## Purpose
Centralized application settings and configuration management providing a key-value store for system-wide and per-mandate configuration.

## Key Features
- Key-value settings storage
- Per-mandate configuration
- System-wide settings
- Settings categories
- Settings validation
- Default value management

## Main Components
- Settings repositories
- Settings service implementation
- Settings management UI
- Settings menus

## Dependencies
### External Dependencies
- Spring Boot Data JPA
- PrimeFaces

### Internal Module Dependencies
- plaintext-root-common
- plaintext-root-interfaces (ISettingsService)
- plaintext-root-menu

## Configuration
- Settings stored in database
- Category definitions
- Validation rules
- Default values
