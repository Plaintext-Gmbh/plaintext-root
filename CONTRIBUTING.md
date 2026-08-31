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
5. Commit with a descriptive message — see [Commit messages](#commit-messages) below
6. Push to your fork and open a Pull Request against `master`

### Commit messages

There is no Conventional-Commits tooling in this repository and nothing is
generated from commit messages. What matters is the content:

- **Subject line:** one line, what changed. The maintainers write it in German;
  English is fine for external contributions.
- **Body:** the *why* — the observation, the wrong assumption, the alternative you
  rejected. The code shows the *what*; the message is the only place the *why*
  survives.
- Release notes are written by hand into [CHANGELOG.md](CHANGELOG.md) per release.
  The `Release version X.Y.Z` and `Prepare next development iteration` commits are
  produced by the release tooling — do not write those by hand.

### Branch Protection

- Direct pushes to `master` are restricted to maintainers
- All contributions must go through Pull Requests
- Contributors can create branches and open PRs freely

### What happens to your PR in CI

- The pipeline (`.woodpecker/build.yml`) runs build and tests. For pull
  requests it runs in `ci-only` mode — it never deploys.
- GitHub does not hand repository secrets to workflows triggered from a fork.
  Steps that would need them are skipped; that is expected and not a failure of
  your change.
- The Playwright UI check (`.woodpecker/playwright.yml`) is informational
  and does not block a merge.

## Development Setup

### Prerequisites

- Java 25+ (e.g. via [SDKMAN](https://sdkman.io/): `sdk install java 25-open`)
- Maven 3.9+
- Docker or Podman — only needed to run the application against the local
  PostgreSQL from `compose.yaml`. The build, the unit tests **and** the
  integration tests run without it: the integration tests boot an embedded
  PostgreSQL (`io.zonky.test:embedded-postgres`, see `plaintext-root-webapp/pom.xml`).

### Quick Start

```bash
# Clone the repository
git clone https://github.com/Plaintext-Gmbh/plaintext-root.git
cd plaintext-root

# Build the project (no database needed)
mvn clean install -DskipTests

# Start the local PostgreSQL (port 5434, db/user/password: plaintext_root/plaintext/plaintext)
docker compose up -d

# Run the application
mvn spring-boot:run -pl plaintext-root-webapp
```

The app is then available at <http://localhost:8080>. There is no embedded
fallback database — the compose defaults match `application.yml`, so nothing else
needs configuring. See the [README](README.md) for the environment variables that
point the app at another PostgreSQL.

### Running Tests

```bash
mvn clean test                  # unit tests + JaCoCo coverage
mvn clean verify                # additionally the integration tests (embedded PostgreSQL, no Docker)
mvn clean verify -DskipITs      # skip the integration tests
```

### Playwright UI tests

`SelfServicePlaywrightIT` and `RootPagesPlaywrightIT` in `plaintext-root-webapp`
drive the real application in a headless Chromium. They run in CI
(`.woodpecker/playwright.yml`, informational only). To run them locally you
need the Chromium build that Playwright downloads into `~/.cache/ms-playwright`
— once per machine:

```bash
# 1. Install the internal SNAPSHOT modules so the classpath below resolves
mvn -B -q -DskipTests install

# 2. Let Playwright fetch its Chromium (same commands as the CI workflow)
mvn -B -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt \
    -pl plaintext-root-webapp -am
java -cp "$(cat /tmp/cp.txt):plaintext-root-webapp/target/classes" \
    com.microsoft.playwright.CLI install --with-deps chromium

# 3. Run the UI tests (Failsafe boots the app on a random port itself)
mvn -pl plaintext-root-webapp failsafe:integration-test failsafe:verify \
    -Dit.test='SelfServicePlaywrightIT,RootPagesPlaywrightIT'
```

No separately started application is needed — the IT classes start Spring Boot
against the embedded PostgreSQL themselves.

## Reading the Code

A few conventions that are not obvious from the outside:

- Comments referring to **"Karte NNN"** name a card on the maintainers' internal
  Kanban board. That board has no public URL and is not linked to GitHub issues;
  the number is a breadcrumb for the maintainers, the sentence next to it is what
  matters. You never need access to the board to understand the code. External
  contributions should reference GitHub issues (`#123`) instead.
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
- Add the MPL 2.0 license header to new Java files. Almost every Java file in the
  repository carries it (a handful of recent test files do not); the license
  itself applies through [LICENSE](LICENSE) and the `<licenses>` block of the
  parent `pom.xml` regardless, so a missing header is a nit, not a blocker:

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
