# plaintext-root-menuesteuerung

## Purpose
Provides menu visibility control and configuration management, allowing per-mandate customization of which menu items are visible to users.

## Key Features
- Per-mandate menu configuration
- Menu visibility rules
- Dynamic menu enable/disable
- Menu configuration persistence
- Integration with role-based security

## Main Components
- **MandateMenuConfigRepository**: Persistence for menu configurations
- Menu visibility controllers
- Configuration management services

## Dependencies
### External Dependencies
- Spring Boot Data JPA
- PrimeFaces

### Internal Module Dependencies
- plaintext-root-common
- plaintext-root-interfaces
- plaintext-root-menu
- plaintext-root-jpa

## Configuration
- Menu configurations stored in database
- Per-mandate visibility rules
- Default menu states
