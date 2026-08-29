# Creating Flyway Database Migrations

## Overview

Plaintext Root uses [Flyway](https://flywaydb.org/) for database schema management. Migrations are SQL files that are automatically executed on application startup.

**Database:** PostgreSQL (development via Docker Compose, tests via Testcontainers).

## Migration File Location

Each module can have its own migrations in:

```
{module}/src/main/resources/db/migration/
```

All migrations from all modules on the classpath are discovered and executed in order.

## Naming Convention

```
V{timestamp}__{description}.sql
```

- **V**: Prefix (required by Flyway)
- **{timestamp}**: Seconds since January 1, 2000 (ensures unique, ordered names)
- **__**: Double underscore separator (required by Flyway)
- **{description}**: Snake_case description of the change

### Examples

```
V820503544__create_webapp_tables.sql
V1770481749__add_expires_at_and_enlarge_token.sql
```

## Generating a Timestamp

### Recommended: Use the included script

```bash
./getflywaynr
```

The script generates a Unix timestamp, checks all existing migrations for conflicts, and confirms the version is safe to use.

## Writing Migrations

### SQL Syntax

Write migrations in **PostgreSQL syntax** — PostgreSQL is the only database the framework runs on (there is no H2/HSQLDB fallback; `BIGSERIAL` and friends are fine):

```sql
-- Use IF NOT EXISTS for idempotent table creation
CREATE TABLE IF NOT EXISTS my_table (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_date TIMESTAMP,
    last_modified_date TIMESTAMP,
    mandat VARCHAR(255)
);

-- Add columns safely
ALTER TABLE my_table ADD COLUMN IF NOT EXISTS new_column VARCHAR(100);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_my_table_name ON my_table(name);
```

### Common Data Types

| Type | Description |
|------|-------------|
| `BIGSERIAL` | Auto-increment BIGINT |
| `SERIAL` | Auto-increment INT |
| `VARCHAR(n)` | Variable-length string |
| `TEXT` | Unlimited text |
| `BOOLEAN` | True/false |
| `TIMESTAMP` | Date and time |
| `BYTEA` | Binary data |
| `INTEGER` | 32-bit integer |
| `BIGINT` | 64-bit integer |

### Base Entity Columns

If your entity extends `SuperModel`, include these standard columns:

```sql
CREATE TABLE IF NOT EXISTS my_entity (
    id BIGSERIAL PRIMARY KEY,

    -- Your fields
    name VARCHAR(255),

    -- SuperModel fields (multi-tenancy + audit)
    mandat VARCHAR(255),
    created_by VARCHAR(255),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(255),
    last_modified_date TIMESTAMP,
    deleted BOOLEAN DEFAULT FALSE,
    tags VARCHAR(5000)
);
```

## Best Practices

1. **Never modify existing migrations** — Create new ones instead
2. **Use `IF NOT EXISTS`** — Makes migrations idempotent
3. **One change per migration** — Easier to debug
4. **Test locally** — `docker compose up` starts PostgreSQL, app runs migrations on startup
5. **Include rollback comments** — Document how to undo if needed
6. **Add indexes for foreign keys** — PostgreSQL doesn't auto-index FKs

## Development Setup

```bash
# Start PostgreSQL (Docker Compose starts automatically with the app)
docker compose up -d

# Or let Spring Boot start it automatically:
mvn spring-boot:run -pl plaintext-root-webapp
```

## Configuration

```yaml
# application.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true     # Creates baseline for existing DBs
    out-of-order: true            # Allows migrations from different modules
```

## Troubleshooting

### Migration fails on startup

The app automatically repairs failed migrations via `FlywayRepairCallback`.

### Reset local database

```bash
docker compose down -v   # Removes volumes (data)
docker compose up -d     # Fresh database
```
