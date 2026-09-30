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
   query across tenants for admin reporting.

## Decision

Every persisted entity gets a `mandat` column (German term retained for
historical reasons; rename to `tenant_id` is tracked separately, see
`docs/GERMAN_TERMS.md`). The `SuperModel` base entity stamps it
automatically from the authenticated user's mandate at write time
(`@PrePersist`, only if the field is still empty).

Reads are separated **explicitly and only** in the repositories and services:
finder methods take the tenant as a parameter (`findByMandat…`,
`findByIdAndMandat`, `PlaintextRepository`), or a loaded row is checked
against the caller's tenant before it is used.

> **Correction (card 1360, 30.09.2026).** Until then this ADR stated that a
> Hibernate `MandantFilter` (`@Filter`) is active by default and that only
> `ROLE_ROOT` can opt out. That filter does not exist: a search for
> `@FilterDef|enableFilter|@TenantId|@Where|@SQLRestriction` over plaintext-root
> returns 0 hits (positive control: the same search finds `@Query`). There is no
> safety net below the repositories — a plain `findById(id)` in a tenant-aware
> path crosses tenants. The security audit (card 1353, part B) found exactly
> that in the requirements module (fixed in card 1360, HB1).

## Consequences

Positive:

* Single schema = simplest Flyway story. Migrations apply to all tenants
  atomically.
* Cross-tenant reporting (root-only) works without federated queries.
* Backups, restores, replication are per-database — no per-tenant choreography.
* Adding a tenant is data-only (insert into `mandate`); no DDL.

Negative:

* Application-level isolation without a safety net. A repository or service
  that loads by id without checking the tenant exposes other tenants' rows
  (see the correction above). Mitigation: tenant-aware finders
  (`findByIdAndMandat`) instead of `findById` on every path that takes an id
  from a request, and tests that load a foreign tenant's row and expect
  "not found".
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

* Hibernate `@Filter` (considered, NOT used): <https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-filter>
* Implementation lives in `plaintext-root-jpa`.
* Naming follow-up: `docs/GERMAN_TERMS.md` (mandat → tenant rename plan).
