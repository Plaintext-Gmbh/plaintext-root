# plaintext-root-flyway

## Purpose
Manages database schema migrations using Flyway for version-controlled database evolution across all Plaintext modules.

## Key Features
- Flyway migration configuration
- Database versioning
- Migration script validation
- Cross-module migration coordination
- PostgreSQL syntax only (there is no H2/HSQLDB; the apps run on `jdbc:postgresql`)

## Main Components
- Flyway configuration
- Migration script repository
- Version control integration

## Dependencies
### External Dependencies
- Flyway Core
- Spring Boot
- PostgreSQL (embedded-postgres in the webapp ITs)

### Internal Module Dependencies
- plaintext-root-jpa

## Configuration
- Migration scripts in `db/migration`
- Flyway properties in application.properties
- Use `./getflywaynr` script to generate patch numbers (calculates seconds since 2000, checks for conflicts)
- Migration scripts use PostgreSQL syntax

## Tests
- `FlywayServiceTest` — JDBC access to `flyway_schema_history` (mocked DataSource)
- `FlywayMigrationenTest` — every `V*.sql` in the reactor: file name, reactor-wide unique version, no H2/HSQLDB/MySQL idioms
