# User Documentation & Presentation Overhaul

Plan for replacing the current README-plus-loose-`docs/`-folder with a
proper, multi-audience documentation site.

## Why

The framework today targets at least four different audiences with one
README and a handful of `*.md` files:

- **Evaluators** – "should I adopt Plaintext Root for my project?"
- **App developers** – building against the framework.
- **Module developers** – writing code *inside* the framework.
- **Operators** – running it in staging/prod.

Each of those needs a different starting point and a different depth of
detail. The current docs answer mostly the second group, leave the others
to figure it out, and break links to one another (e.g. README and
`docs/index.md` say different things about the Spring Boot version).

## Goals

1. Single source of truth, generated from Markdown in-repo.
2. Searchable.
3. Versioned per release (`/v1.167/…`, `/latest/…`).
4. Skinnable so each downstream project (plaintext-app, plaintext-iot,
   plaintext-schuetu) can plug in its own additional pages without
   re-implementing the chrome.
5. Works as a deployable site **and** renders nicely on GitHub for casual
   browsers.
6. English first, with the German Terms doc retained as a side reference.

## Target structure

```
plaintext-root/
├── README.md            ← short marketing-style overview, links into the site
├── docs/
│   ├── index.md          ← landing page, audience picker
│   ├── overview/
│   │   ├── what-is-plaintext-root.md
│   │   ├── feature-tour.md
│   │   ├── architecture.md
│   │   └── tech-stack.md
│   ├── getting-started/
│   │   ├── first-app.md         ← create a new project that depends on root
│   │   ├── run-locally.md
│   │   ├── docker-compose.md
│   │   └── connect-postgres.md
│   ├── developer/
│   │   ├── building.md
│   │   ├── module-template.md
│   │   ├── menu-system.md
│   │   ├── jpa-conventions.md
│   │   ├── flyway-conventions.md
│   │   ├── testing.md
│   │   ├── code-style.md
│   │   └── extending.md
│   ├── operator/
│   │   ├── deployment.md
│   │   ├── configuration-reference.md
│   │   ├── crypto.md           ← already drafted in docs/CRYPTO.md
│   │   ├── observability.md
│   │   ├── backup-restore.md
│   │   └── upgrade-guide.md
│   ├── api/
│   │   ├── rest-overview.md
│   │   └── api-tokens.md
│   ├── reference/
│   │   ├── module-reference.md   ← (existing)
│   │   ├── german-terms.md       ← (existing)
│   │   ├── cli-reference.md      ← `./build` flags
│   │   └── glossary.md
│   ├── adr/                       ← Architecture Decision Records
│   │   └── 0001-template.md
│   └── _config.yml                ← MkDocs Material config
└── mkdocs.yml                     ← top-level config
```

## Tooling choice

| Option         | Pros                                                                | Cons                                                       |
| -------------- | ------------------------------------------------------------------- | ---------------------------------------------------------- |
| **MkDocs Material** | Single Markdown source; great default search; versioning via `mike`; renders in <1s; tiny dependency | Python toolchain in CI                                     |
| Docusaurus     | React-based, lots of plugins, great for landing pages              | Node toolchain; harder to skin without writing JSX         |
| Antora         | Multi-repo first-class, AsciiDoc                                   | AsciiDoc learning curve; we're a Markdown shop today       |
| Just GitHub Pages + Jekyll | Already what `_config.yml` hints at                                  | Search is weak, theme is dated, versioning is manual       |

Recommendation: **MkDocs Material** with the `mike` versioning plugin.
Build runs in a GitHub Action and publishes to `gh-pages`; the existing
GitHub-Pages config (`_config.yml`) gets retired. Each minor release tags
its docs (`mike deploy 1.167 latest`) so old versions stay reachable.

## Phased rollout

### Phase 1 — Skeleton + lift-and-shift (≈ 1 day)

- Add `mkdocs.yml` and a starter `docs/_config.yml` for Material theme.
- Move existing `docs/*.md` into the new tree without rewriting them
  (`overview/architecture.md` ← current `ARCHITECTURE.md`,
  `developer/flyway-conventions.md` ← current `FLYWAY_MIGRATIONS.md`,
  etc.).
- New GitHub Action: `docs.yml` runs `mkdocs build --strict` on every PR
  and `mike deploy` on push to `master`.
- Archive `docs/_config.yml` (Jekyll) once the new pipeline works.

### Phase 2 — Authoring pass (≈ 3 days)

Write the missing pages:

- `overview/what-is-plaintext-root.md` (1 page elevator pitch with
  diagram).
- `overview/feature-tour.md` (screenshots of admin panels, menu, etc.).
- `getting-started/first-app.md` — copy-pasteable Maven archetype steps.
- `operator/configuration-reference.md` — every `plaintext.*` property
  with default and explanation. Generate from
  `@ConfigurationProperties` classes via `spring-boot-configuration-processor`'s
  metadata file.
- `operator/observability.md` and `operator/upgrade-guide.md`.
- ADR template + 3 starter ADRs for already-made decisions.

### Phase 3 — Presentation (≈ 1 day)

- Custom Material theme overrides: Plaintext color palette, hero on the
  landing page with a "Get started" / "See it run" / "Read the
  reference" three-up.
- Embed a recorded demo (asciinema for CLI, ~30s screencast for the
  admin panel) on the overview page.
- Add a "Why Plaintext Root?" comparison table against bare Spring Boot.

### Phase 4 — Cross-project (≈ ½ day per consumer)

For each downstream project (`plaintext-app`, `plaintext-iot`,
`plaintext-schuetu`):

- Adopt the same MkDocs config with a `theme.extends: plaintext-root`.
- Inherit the "Operator" and "Developer" sections, add app-specific pages
  on top.
- One landing page per project under `https://plaintext.ch/<project>/`.

## Translation strategy

English is the base. Translation can come later via MkDocs Material's
i18n plugin (`mkdocs-static-i18n`) once we know which pages get traction.
The existing `docs/GERMAN_TERMS.md` becomes a reference glossary (under
`reference/german-terms.md`) and stays bilingual.

## Success criteria

A new contributor should reach a running app from scratch in under 15
minutes following only `getting-started/`. An operator should be able to
upgrade from a major version to the next using only
`operator/upgrade-guide.md`. A potential adopter should be able to decide
within 3 minutes of landing on the docs site whether the framework fits
their project.

## Out of scope (for now)

- Auto-generated Javadoc — keep deferring until the surface stabilises.
- Translations beyond DE↔EN.
- A separate marketing site under plaintext.ch (covered by
  plaintext-website repo).

## Open questions

- Hosting: deploy to `plaintext-root.plaintext.ch` (own DNS, faster) or
  to `plaintext-gmbh.github.io/plaintext-root/` (zero infra)?
- Demo data: ship a tiny `mvn -Pdemo` profile that loads a deterministic
  set of mandates/users/email-configs so screenshots and screencasts can
  be re-recorded reliably.

## Next action

Open a tracking issue ("Documentation overhaul") referencing this plan,
schedule Phase 1 for the next sprint.
