# Improvement Proposals

Living document — concrete, actionable improvements for the Plaintext Root
framework. Each item is sized so it can become its own PR or design issue
without dragging the rest of the list with it. Items are grouped by theme and
prefixed with a rough priority.

Legend:
- 🔴 high — security or correctness, ship soon
- 🟠 medium — meaningful UX or developer-experience win
- 🟢 low — nice to have, low risk
- ✅ done — shipped; the evidence (PR, CHANGELOG entry, file) is named in the item

## Security

### ✅ Replace the hard-coded remember-me signing key

**Done** (#120, CHANGELOG 1.635.0): `plaintext.security.remember-me-key` makes the key
configurable; when unset an ephemeral random key is generated with a startup WARN.
Original proposal kept for the record:

`PlaintextSecurityConfig#rememberMeServices` passes the literal string
`"mySecretKey"` to `PersistentTokenBasedRememberMeServices`. The same string
appears in two places. Anyone with the source can forge a remember-me cookie.

- Move the secret to `plaintext.security.remember-me.key`.
- Generate a 32-byte default at first start, persist it next to the app
  (e.g. `~/.plaintext-root/remember-me.key`), refuse to start in `prod`
  profile if the property is missing.

### 🔴 Account lockout after failed login attempts

No code path counts failed logins. A single password is the only thing
between an attacker and any tenant. Implement counter persisted per
username + IP, with exponential back-off (5 fails → 1 min, 10 fails →
15 min, manual reset by admin). Spring Security's `LockedException` and
`AccountStatusUserDetailsChecker` are the natural integration points.

### 🟠 CAPTCHA after lockout threshold (not on every login)

Render a CAPTCHA only once the failure counter for that username/IP exceeds
N (e.g. 3). Keeps the login flow snappy for normal users, blocks botnets.
Use [Cloudflare Turnstile](https://developers.cloudflare.com/turnstile/) or
hCaptcha — both privacy-friendlier than reCAPTCHA.

### 🟠 Magic Link / passwordless login (issue #26)

Groundwork is there (`MY_USER_ENTITY.PASSWORDLESS` flag, backing-bean
support). What's missing: magic-link token entity, issuer service, Spring
Security `OneTimeTokenAuthenticationFilter` glue, login UI affordance.
See the comment on issue #26 for a concrete plan.

### ✅ 2FA/MFA (TOTP) for ROOT/ADMIN

**Done**: opt-in authenticator-app 2FA with recovery codes for local password users,
`plaintext.security.totp.enabled` (default off) — see
[security/TOTP_2FA.md](security/TOTP_2FA.md). Original proposal:

[Spring Security 6.4+ TOTP support](https://docs.spring.io/spring-security/reference/servlet/authentication/otp.html)
ships out of the box. Add an opt-in toggle on the user profile, mandatory
flag per role for admins, recovery codes stored encrypted via the new
`ConfigEncryptionService`.

### 🟠 Per-tenant rate limiting on REST endpoints

`plaintext-admin-apitoken` issues bearer tokens but does not limit request
volume. Add a Spring `Filter` backed by Bucket4j (or a Redis token bucket
when the deployment topology has Redis available).

### 🟢 Tighten Content-Security-Policy headers

Audit current `Content-Security-Policy`. Move inline JSF event handlers
behind nonces, drop `unsafe-inline` where possible. CSP report endpoint
into the audit log.

### 🟢 Encrypt OIDC client secrets at rest

`OidcConfig` stores client secrets in plain text. With the new
`ConfigEncryptionService` this becomes a one-line getter/setter change
plus a one-time migration via `ConfigEncryptionMigrator`.

## Observability

### 🟠 Audit-Log viewer (closes most of issue #66 item 4)

Spring Data auditing already records `created_by`, `created_date`,
`last_modified_by`, `last_modified_date` on most entities. Add:

1. A small `AuditEvent` record (entity type, id, action, actor, mandate,
   timestamp) written by an `EntityListener`.
2. An admin page that queries it with filters by mandate/actor/range and
   exports to CSV.

### ✅ `/actuator/prometheus` and a starter Grafana dashboard

**Done**: `micrometer-registry-prometheus` is a dependency of `plaintext-root-webapp`,
`management.endpoints.web.exposure.include` lists `prometheus`, the Grafana dashboard is
committed as [dashboards/plaintext-root.json](dashboards/plaintext-root.json) and the
operator guide is [operator/PROMETHEUS.md](operator/PROMETHEUS.md). Original proposal:

Spring Boot Actuator already provides metrics; the missing piece is the
`micrometer-registry-prometheus` dependency, a curated set of metrics
exposed for HTTP, JPA, Hikari, and JSF view counts, plus a Grafana JSON
dashboard committed under `docs/dashboards/`.

### 🟢 Structured request logging with correlation IDs

`MDC` filter that sets `requestId`, `mandate`, `username` and attaches them
to every log line. Stops the day-long debugging sessions where two requests
interleave in the log file.

## Developer experience

### ✅ CHANGELOG.md

**Done**: [CHANGELOG.md](../CHANGELOG.md) exists and is maintained by hand per release
(Keep-a-Changelog layout). The "generate from Conventional Commits" half was dropped —
the repository does not use Conventional Commits and nothing is generated from commit
messages.

### ✅ Architecture Decision Records (`docs/adr/`)

**Done**: [adr/](adr/) holds 0001 (JoinFaces over Spring MVC), 0002 (mandate per row),
0003 (Maven multi-module over Spring Modulith), 0004 (page guard `STRICT` for the root
app), 0005 (`root-web` and `root-pageguard` carved out of the webapp) and 0006 (releases
and consumer pins). Note the original wording said "Flyway with HSQLDB syntax" — the
migrations are PostgreSQL syntax; there is no HSQLDB.

### ✅ Run the JaCoCo coverage gate

**Done (29.08.2026, Zustandsbericht Massnahme 13)**: the `coverage-check` execution in the
reactor `pom.xml` fails the build (`jacoco.halt-on-failure=true`) when a module's line
coverage drops below `jacoco.coverage.minimum` (0.40). Modules below the line carry a dated,
justified `<jacoco.coverage.minimum>` in their own pom until they catch up (never 0);
`plaintext-root-archtests` skips JaCoCo because its main sources *are* test code. Consumers
inherit the execution and set `<jacoco.halt-on-failure>false</jacoco.halt-on-failure>` until
they are clean. Raising the floor (60 % overall, 80 % for new code) stays open.

### 🟢 Pre-commit hook running the formatter

[Spotless](https://github.com/diffplug/spotless) with Google Java Format
or Eclipse formatter. Wire it into `mvn verify` so style drift cannot
land.

### 🟢 Improve `./build` introspection

The build script accepts numeric flags (0…7+ combos). Adding a `./build
--help` that prints the table of meanings would save a lot of grepping.

## User experience

### 🟠 Maintenance mode

A property + a `Filter` that swaps every non-ADMIN response for a
configurable maintenance page. Add a banner that shows up an hour before
the planned start. Smallest piece of issue #66 to ship and one of the most
visible ones in operations.

### ✅ Global search (Cmd+K)

**Done**: `SearchProvider` in `plaintext-root-interfaces`, `SearchService`,
`GET /api/search`, topbar UI — documented in the [README](../README.md#global-search-cmdk).
Original proposal:

Define a `SearchProvider` SPI (`Stream<SearchHit> search(String query, int
limit)`); each module that wants to participate registers one. Topbar
search uses the user's current mandate to scope results.

### 🟠 Real i18n resource bundles

The `plaintext-admin-i18n` module has the data plumbing but the framework
itself is not localised — page titles, validation messages, and admin UI
strings are still German-only. Extract them into `messages_de.properties`
and `messages_en.properties` and switch lookups to `f:loadBundle`.

### 🟢 First-login onboarding wizard

Three steps: confirm email, pick theme, take a guided tour. Driven by a
config-driven step list so each consumer of the framework can extend it.

## Architecture

### ✅ Drop the legacy `email_config` table (the V2 migration is done)

**Done** (#121, CHANGELOG 1.635.0): table and `EmailConfigMigration` removed. Original
proposal:

`EmailConfigMigration#migrateToEmailConfigV2_December2024` is marked
`@Deprecated(forRemoval = true, since = "1.94.0")` with a TODO to remove
after 2025-03-01. We're past that. Add a Flyway migration that drops the
old `email_config` table and remove the migration class.

### 🟠 Rename `mandat` → `tenant` (long-term)

`docs/GERMAN_TERMS.md` already plans this, but the rename hasn't started.
Strategy: add tenant-named convenience methods alongside `mandat`, deprecate
`mandat`, and remove in two minors. Touches every module — likely a
multi-PR rollout.

### 🟢 File/Document manager module

Separate `plaintext-root-files` module with S3-compatible backend, used
first by branding logos and email attachments. Replaces the ad-hoc per-use
storage today.

## Process

### 🟠 Renovate auto-merge for safe updates

The Renovate dashboard (issue #4) is open but auto-merge is off. Configure
`automergeType: "pr"` for patch-level updates of test-only dependencies
and Spring Boot patch releases. Reviewer time should be spent on minor/major
updates only.

### 🟢 Define a security-disclosure email address

`SECURITY.md` says "send an email to the maintainers" without listing one.
Add a dedicated address (e.g. `security@plaintext.ch`) and route it to a
shared inbox.

---

## How to adopt this list

- Pick one item, open a tracking issue, and link it back here from the
  description.
- When an item ships, leave it in the list with a "✅ done in #PR" line —
  this doc doubles as a retrospective of what changed.
- Items move freely between priorities as context changes; don't be precious
  about them.
