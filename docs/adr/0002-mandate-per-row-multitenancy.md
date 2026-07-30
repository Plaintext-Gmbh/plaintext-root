# Tenant isolation as a per-row `mandat` column instead of schema-per-tenant

* **Status:** accepted
* **Date:** 2026-05-04 (recorded retroactively)
* **Deciders:** Daniel Marthaler

## Context

The framework targets workloads with a small-to-medium number of tenants
(low tens), all served from a single Postgres instance, and with operators
who want one image, one container, one upgrade. Hard-isolation
requirements (separate keys, separate backups, separate failure domains)
are not part of the mandate.

Three multi-tenancy strategies are common:

1. **Database-per-tenant** — strongest isolation, highest operational cost.
2. **Schema-per-tenant** — moderate isolation, complicates Flyway, breaks
   cross-tenant reporting.
3. **Discriminator column (`mandat`/`tenant_id`)** — lightest, lets us
   query across tenants for admin reporting, fits Spring Data's
   `@Where`/`@Filter`.

## Decision

Every persisted entity gets a `mandat` column (German term retained for
historical reasons; rename to `tenant_id` is tracked separately, see
`docs/GERMAN_TERMS.md`). The `SuperModel` base entity stamps it
automatically from the authenticated user's mandate at write time.
Repositories filter by `mandat` either via custom JPQL or via a
`MandantFilter` that listens to Hibernate `@Filter`.

## Consequences

Positive:

* Single schema = simplest Flyway story. Migrations apply to all tenants
  atomically.
* Cross-tenant reporting (root-only) works without federated queries.
* Backups, restores, replication are per-database — no per-tenant choreography.
* Adding a tenant is data-only (insert into `mandate`); no DDL.

Negative:

* Application-level isolation. A repository bug that skips the filter
  exposes other tenants' rows. Mitigation: the `MandantFilter` is on by
  default, only `ROLE_ROOT` can opt out, and integration tests assert
  cross-tenant isolation in the major flows.
* No per-tenant resource quotas at the DB level; row counts can drift.

Neutral:

* `mandat` is a string, not a UUID. Allows operators to set human-readable
  identifiers (`"acme"`, `"plaintext"`) at the cost of a slightly larger
  index footprint.

## Alternatives considered

| Option                  | Why not?                                                      |
| ----------------------- | ------------------------------------------------------------- |
| Database-per-tenant     | Every Flyway run × N tenants; backup/upgrade choreography.    |
| Schema-per-tenant       | Flyway-multitenancy adapter is fragile under add-tenant churn. |
| Row-Level Security only | Less portable; harder to test in HSQLDB-mode unit tests.       |

## References

* Spring Data JPA + Hibernate `@Filter`: <https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-filter>
* Implementation lives in `plaintext-root-jpa`.
* Naming follow-up: `docs/GERMAN_TERMS.md` (mandat → tenant rename plan).
