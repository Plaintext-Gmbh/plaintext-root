---
layout: default
title: Plaintext Root Framework
---

# Plaintext Root Framework

An open-source Jakarta Faces application framework for rapidly building multi-tenant web applications.

## What is Plaintext Root?

Plaintext Root provides a complete foundation for building enterprise web applications with:

- **Multi-Tenancy** — Built-in mandate system for tenant data isolation
- **Security** — Spring Security with role-based access control
- **Admin Panels** — Pre-built modules for settings, sessions, cron jobs, emails
- **Menu System** — Annotation-driven, role-aware navigation
- **Template System** — Swappable UI templates with dark/light mode

## Quick Start

```bash
git clone https://github.com/Plaintext-Gmbh/plaintext-root.git
cd plaintext-root
mvn clean install -DskipTests
mvn spring-boot:run -pl plaintext-root-webapp
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

## Documentation

### Guides

| Document | Description |
|----------|-------------|
| [Getting Started](GETTING_STARTED.md) | Setup, build, and run the application |
| [Architecture Overview](ARCHITECTURE.md) | System design, module dependencies, and data flow |
| [Module Reference](MODULE_REFERENCE.md) | Detailed reference for all 17 modules |
| [Flyway Migrations](FLYWAY_MIGRATIONS.md) | Database migration conventions and tooling |

### Reference

| Document | Description |
|----------|-------------|
| [German Terms](GERMAN_TERMS.md) | Mapping of German class/module names to English equivalents |
| [Configuration Encryption](CRYPTO.md) | Cipher format and migration guide for stored secrets |
| [REST API (Swagger UI)](/swagger-ui/index.html) | Interactive API documentation (when app is running) |

### Project

| Document | Description |
|----------|-------------|
| [Contributing Guide](../CONTRIBUTING.md) | How to contribute to the project |
| [Security Policy](../SECURITY.md) | Vulnerability reporting process |
| [Improvement Proposals](IMPROVEMENT_PROPOSALS.md) | Tracked improvements grouped by theme and priority |
| [Docs Overhaul Plan](DOCS_OVERHAUL_PLAN.md) | Plan for the upcoming user-facing documentation site |

## Tech Stack

| Technology | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.x |
| Jakarta Faces | 4.1 |
| PrimeFaces | 15.x |
| PostgreSQL | 18+ |

## License

Licensed under the [Mozilla Public License 2.0](https://opensource.org/licenses/MPL-2.0).
