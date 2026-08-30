---
layout: default
title: Plaintext Root Framework
---

# Plaintext Root

An open-source Jakarta Faces application framework for building multi-tenant
web applications — security, navigation, admin screens and a swappable UI
template, so an application can start with its own domain instead of with a
login form.

```bash
git clone https://github.com/Plaintext-Gmbh/plaintext-root.git
cd plaintext-root
mvn clean install -DskipTests
docker compose up -d                       # PostgreSQL on port 5434
mvn spring-boot:run -pl plaintext-root-webapp
```

Then open <http://localhost:8080>.

## Documentation

The full, grouped list lives at the bottom of the
[README](https://github.com/Plaintext-Gmbh/plaintext-root#documentation) — it is
maintained in one place so the two cannot drift apart. The entry points:

| I want to … | Start here |
|-------------|-----------|
| understand how it is built | [Architecture](ARCHITECTURE.md) · [Module reference](MODULE_REFERENCE.md) · [ADRs](adr/) |
| build an application on it | [Getting started](GETTING_STARTED.md) · [Menu system](MENU_SYSTEM.md) · [Page access guard](security/PAGE_ACCESS_GUARD.md) |
| contribute to the framework | [Contributing](https://github.com/Plaintext-Gmbh/plaintext-root/blob/master/CONTRIBUTING.md) · [CI pipeline](CI.md) · [Flyway migrations](FLYWAY_MIGRATIONS.md) |
| run it | [Prometheus](operator/PROMETHEUS.md) · [Two-factor auth](security/TOTP_2FA.md) · [Login paths](security/LOGIN_PATHS.md) |

## At a glance

- **Multi-tenancy** — a discriminator column on every entity, one shared base
  class, tenant-aware finders
- **Security** — Spring Security, role-based, plus a page guard that derives
  per-view authorization from the menu the user actually sees
- **Menu** — declared by annotation on the backing bean, role-aware, switchable
  per tenant
- **Admin modules** — twelve of them, each removable with a Maven exclusion
- **Template** — one jar with no dependencies; replace it to reskin everything

Licensed under the [Mozilla Public License 2.0](https://github.com/Plaintext-Gmbh/plaintext-root/blob/master/LICENSE).
