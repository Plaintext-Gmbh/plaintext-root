# plaintext-admin-cron

## Purpose
Cron job management and scheduling module providing centralized control over scheduled tasks across the application.

## Key Features
- Cron job configuration and management
- Schedule editing with cron4j syntax
- Job execution monitoring
- Job enable/disable control
- Automatic job discovery via @PlaintextCron annotation
- Job execution history

## Main Components
- **CronConfigRepository**: Persistence for cron configurations
- **CronBeanPostProcessor**: Automatic discovery of @PlaintextCron annotated beans
- **CronMenu**: Admin menu for cron management

## Dependencies
### External Dependencies
- Cron4j
- Spring Boot Data JPA
- PrimeFaces

### Internal Module Dependencies
- plaintext-root-common
- plaintext-root-interfaces (PlaintextCron interface)
- plaintext-root-menu

## Configuration
- Cron expressions stored in database
- Job configurations per environment
- Enable/disable flags
- Execution logging settings

## Usage
Mark any Spring bean method with `@PlaintextCron` annotation to register it as a scheduled task.
