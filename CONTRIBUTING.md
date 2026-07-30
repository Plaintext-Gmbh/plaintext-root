# Contributing to Plaintext Root

Thank you for your interest in contributing to Plaintext Root! This document provides guidelines for contributing to this project.

## How to Contribute

### Reporting Bugs

- Use [GitHub Issues](https://github.com/Plaintext-Gmbh/plaintext-root/issues) to report bugs
- Include steps to reproduce, expected behavior, and actual behavior
- Add screenshots if applicable

### Suggesting Features

- Open a [Discussion](https://github.com/Plaintext-Gmbh/plaintext-root/discussions) to propose new features
- Describe the use case and why it would be valuable

### Security Issues

Please do **not** open a public issue for a security problem. Follow the
reporting process in [SECURITY.md](SECURITY.md).

### Pull Requests

1. Fork the repository
2. Create a feature branch from `master`: `git checkout -b feature/my-feature`
3. Make your changes
4. Ensure the project builds and the tests pass: `mvn clean install`
5. Commit with a descriptive message ([Conventional Commits](https://www.conventionalcommits.org/),
   e.g. `feat(menu): add badge support` — the release notes are generated from them)
6. Push to your fork and open a Pull Request against `master`

### Branch Protection

- Direct pushes to `master` are restricted to maintainers
- All contributions must go through Pull Requests
- Contributors can create branches and open PRs freely

### What happens to your PR in CI

- The pipeline (`.github/workflows/ci-cd.yaml`) runs build and tests. For pull
  requests it runs in `ci-only` mode — it never deploys.
- GitHub does not hand repository secrets to workflows triggered from a fork.
  Steps that would need them are skipped; that is expected and not a failure of
  your change.
- The Playwright UI check (`.github/workflows/playwright.yaml`) is informational
  and does not block a merge.

## Development Setup

### Prerequisites

- Java 25+ (e.g. via [SDKMAN](https://sdkman.io/): `sdk install java 25-open`)
- Maven 3.9+
- Docker or Podman — only needed for PostgreSQL and for the integration tests
  (Testcontainers). A plain build and the unit tests run without it.

### Quick Start

```bash
# Clone the repository
git clone https://github.com/Plaintext-Gmbh/plaintext-root.git
cd plaintext-root

# Build the project (no database needed)
mvn clean install -DskipTests

# Run the application against the in-memory H2 database
mvn spring-boot:run -pl plaintext-root-webapp
```

The app is then available at <http://localhost:8080>. For a persistent
PostgreSQL setup run `docker compose up -d` and start with
`-Dspring-boot.run.profiles=postgres`. See the [README](README.md) for details.

### Running Tests

```bash
mvn clean test                  # unit tests + JaCoCo coverage
mvn clean verify                # additionally the integration tests (needs Docker)
mvn clean verify -DskipITs      # skip the integration tests
```

## Reading the Code

A few conventions that are not obvious from the outside:

- Comments referring to **"Karte NNN"** point at the maintainers' internal
  issue tracker. They are historical breadcrumbs explaining *why* a decision was
  made; you never need access to that tracker to understand the code, and new
  code should not add such references.
- Domain terms in class and column names are German (`Mandat`, `Anforderung`,
  `Rollenzuteilung`, …). [docs/GERMAN_TERMS.md](docs/GERMAN_TERMS.md) is the glossary.
- Architecture decisions are recorded in [docs/adr/](docs/adr/).

## Code Style

- Follow existing code conventions
- Use meaningful variable and method names
- Keep the existing German UI wording where you touch it; code, comments in new
  files and documentation are English (see [docs/GERMAN_TERMS.md](docs/GERMAN_TERMS.md))
- Architecture rules are enforced by ArchUnit tests in `plaintext-root-archtests`
  — if one of those fails, it is telling you about a convention, not a flake
- Add the MPL 2.0 license header to new Java files:

```java
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
```

- New JSF forms must carry the CSRF token, which a repository-wide invariant
  test enforces:

```xhtml
<h:form id="fm">
    <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
    ...
</h:form>
```

## Database Migrations

Flyway migrations live in each module's `src/main/resources/db/migration/` and
are named `V{seconds-since-2000}__description.sql`:

```bash
echo $(( $(date +%s) - 946684800 ))
```

Never modify a migration that has already been released — add a new one.

## License

By contributing, you agree that your contributions will be licensed under the MPL 2.0 license.
