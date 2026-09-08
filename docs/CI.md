# CI pipeline

What runs when you open a pull request, what runs on `master`, and the handful
of things that are different in this repository than in the applications built
on it.

> **Engine: Woodpecker** (`https://ci.plaintext.ch`), switched over on
> 30 August 2026. The file `.ci-engine` in the repository root holds one word
> and decides who is in charge; the GitHub Actions workflows it replaced have
> been removed. To wire up another repository, see
> [Setting up Woodpecker](ci/WOODPECKER_SETUP.md).

## The switch

```
.ci-engine        →  woodpecker
```

Every Woodpecker step sources `.woodpecker/waechter.sh` as its first command.
The guard reads that file: on `github` it exits 0 immediately, so the pipeline
reports success without having done anything. Two consequences worth knowing:

- Switching engines is a **commit**, not a setting in a database on the NAS —
  you can see it in the log and revert it.
- A green pipeline is therefore not proof that anything ran. When you care, look
  for the guard's line in the step log: `CI-Motor: woodpecker — dieser Step ist
  zustaendig und laeuft.`

A typo in `.ci-engine` fails the step on purpose. Neither engine deploying is
the safe outcome; both deploying the same commit is not.

## Pull requests

`.woodpecker/build.yml` — services: a throwaway `postgres:18-alpine`; steps:

1. **build-und-test** — `mvn clean install -DskipITs`, with the quality-gate
   group excluded on pull requests and manual runs, included on the nightly
   cron. 24 modules; `plaintext-root-webapp` runs Surefire **twice** (the second
   execution, `kontext-ohne-abwaehlbare-module`, boots a second Spring context
   against the same database to prove the admin modules really are optional).
2. **coverage-uebersicht** — a JaCoCo summary, also on failure.
3. **testbericht** — an Allure report, also on failure. See "Test reports" below.

`.woodpecker/playwright.yml` runs the browser smoke tests on pull requests.
`.woodpecker/sonar.yml` runs the weekly full analysis (SonarQube, OWASP CVE,
SpotBugs, quality gate) on the `wochenanalyse` cron, and manually with `analyse = voll`.

Two gates are **armed here that the consuming applications switch off**:

- **JaCoCo**: `jacoco.halt-on-failure=true` at `jacoco.coverage.minimum=0.40`.
  A module below 40 % turns the build red — that is the point, not a pipeline
  fault. The fix is a documented, dated per-module minimum, never `0` and never
  flipping the switch.
- **The shared ArchUnit rules** from `plaintext-root-archtests`. This repository
  ships them and is its own first consumer.

## Master: release-only

plaintext-root is a framework, not a deployable application. Its container on
the NAS has been shut down since 12 August 2026; the Maven artifact lives on,
because `plaintext-root-parent` is the parent POM of plaintext-app, -guild, -iot
and -schuetu — the Spring Boot version reaches them through it.

`.woodpecker/deploy.yml` therefore runs with `deploy-target: release-only`:
version bump, tag, push, `mvn clean deploy` — no blue-green, no container
deployment.

**`release-only` does not mean "does not touch the NAS".** The repository
carries `.m3-jar-volume`, so the release stages the built jar to
`/volume1/docker/plaintext-root/jars/staging` over SSH. That is why this
pipeline still needs `ssh_private_key`: without it the run breaks *after*
publishing.

### One release, three destinations

| # | Destination | Driven by |
|---|-------------|-----------|
| 1 | GitHub Packages (`maven.pkg.github.com/Plaintext-Gmbh/plaintext-mvn`) | `<distributionManagement>`, server id `plaintext` |
| 2 | Reposilite on the NAS (`maven.plaintext.ch/releases`) | profile `dual-publish-nas`, server id `plaintext-nas` |
| 3 | `Plaintext-Gmbh/plaintext-mvn` (git-backed, readable without a login) | `mirror_to_plaintext_mvn()` in the local build script |

Destination 2 is enabled by `.mvn/maven.config` (`-Droot.dual.publish=true`),
not by the pipeline — that way it holds for every engine and every local build.
Adding a `-P` switch to a pipeline file would create a second truth.

Destination 3 needs `GITHUB_TOKEN`. Without it the mirror step logs "skipped"
and returns 0: the run stays green while the public path stays empty. The
pipeline sets it from the same secret as `MVN_DEPLOY_TOKEN`.

> **`deployAtEnd` does not work here.** The deploy plugin has two executions
> (GitHub Packages, then Reposilite). With `deployAtEnd` the second one hit a
> 409 and left half a release behind (1.636.0, 29 August 2026). The order stays
> as it is.

### No verify-dev, no verify-prod

The applications have four pipeline files; this repository has three. The two
verify pipelines are missing **on purpose**: there is no DEV and no PROD to
verify since the container was shut down. Keeping them would mean either
pipelines that never start, or ones that ping a dead environment for seven
minutes before warning — both worse than their absence.

If root is ever deployed again, copy `verify-dev.yml` and `verify-prod.yml` from
plaintext-iot, point `DEV_URL` at the restored port, and extend the target
allowlist in `deploy.yml` to `release-all`.

## Tests need a database — and no Docker

There are **no Testcontainers tests** in this repository. Card 451 (2 August
2026) replaced them with `io.zonky.test:embedded-postgres`, specifically to get
rid of the root-owned `/var/run/docker.sock` mount. A `grep -ril testcontainers`
today finds comments in two `pom.xml` files and one class comment, and no Java
class importing the library.

The consequence for CI: `EmbeddedPg` starts its own server **only when
`SPRING_DATASOURCE_URL` is absent**. In the pipeline the variable is set and
points at the service container, so every test class gets its own database on
that server. Locally, `docker compose up -d` gives you PostgreSQL on port
**5434** — or you set nothing and let the embedded server start.

## Test reports

Woodpecker has no artifact store and no report tab. Until 31 August 2026 the only
view on the tests was the Maven output, plus a step in `playwright.yml` that dumped
the `.txt` files from `surefire-reports`/`failsafe-reports` into the log **on
failure**. No history, nothing to click — and the `.txt` files are wrong for
`@Nested` classes: they report `Tests run: 0` while the XML next to them says
`tests="31"`.

Since card 1018 the `testbericht` step builds an **Allure** report from that XML —
no adapter in the test code, no change to any POM, because Allure reads the common
junit.xml dialects directly. The report is written into the volume
`woodpecker-reports` and served by the nginx of
`plaintext-dockercompose/tri/plaintext-reports`:

| | |
|---|---|
| This run | `http://192.168.1.224:1155/plaintext-root/<pipeline number>/` |
| Latest run | `http://192.168.1.224:1155/plaintext-root/latest/` |
| All repos | `http://192.168.1.224:1155/` |

The URL is printed at the end of the step log. Reachable **from the LAN and through
Twingate only** — there is no `VIRTUAL_HOST` and no entry in the Cloudflare tunnel,
because a test report shows stack traces, class names and test data (card 1018,
open question 1).

Three properties worth knowing:

- **Trend.** Each run copies the `history/` of the previous report into its results
  before generating, so the report shows how the numbers moved. The points of the
  trend chart link back to their Woodpecker run.
- **Housekeeping is part of the step**, not follow-up work: the 30 newest runs per
  repo are kept (`REPORT_KEEP` in `build.yml`), older ones are deleted while writing.
- **An empty report is an error, not an empty page** — with one exception. If the
  step finds no XML at all it fails, the usual cause being a compile error before
  the first test. The exception is a full build-cache hit, see below.

### The build cache used to hide the test output

A pull request that touches no Java file rebuilds no module: the Maven build cache
restores all 24 of them, `mvn install` goes green in three minutes, and
`*/target/surefire-reports/` does not exist at all. Measured on 31 August 2026 in
pipeline 50. The same hole had been swallowing the `coverage-uebersicht` table for
as long as it existed — an empty table reads like a formatting problem, so nobody
looked.

`.mvn/maven-build-cache-config.xml` therefore lists the report directories under
`attachedOutputs`: they are now saved and restored together with the jar, so a
restored module brings its JUnit XML with it and the report covers all modules
rather than the two that happened to be rebuilt. Without that, the trend chart
would compare 24 modules against 2.

The change invalidates nothing — the checksum stays the same, so existing entries
are neither rebuilt nor discarded, and they gain the reports only at the module's
next real build. To fill them in one go:

```
mvn clean install -DskipITs -Dmaven.build.cache.skipCache=true
```

Until the cache has turned over, a run may still find nothing. The step tells the
two cases apart by the extension's own `target/maven-incremental/cache-report*.xml`:
every module `checksumMatched` means nothing was rebuilt, and the step says so and
goes green. Anything else is an error.

`build.yml` and `playwright.yml` do **not** share a workspace, so the report covers
the unit tests of `build.yml`. A report spanning both needs either two writes into
the same directory or a collecting workflow.

## The weekly full analysis (was a gap until 8 September 2026)

Removing the GitHub Actions workflows (30 August 2026) took the weekly full
analysis with it, and Woodpecker did not replace it for nine days. It is back:
`.woodpecker/sonar.yml` now carries the whole `quality-analysis` chain —

```
SonarQube  ->  OWASP dependency check (CVE)  ->  SpotBugs  ->  Quality gate
```

— and the gate step commits `quality/quality-gate.properties` back to the branch it
ran on, with `[skip ci]` in the subject so the write-back triggers nothing.

**What was in the way, and why it no longer is.** The CVE scan needs a persistent
NVD data set; without one, dependency-check downloads the entire NVD on every run
(~96 minutes without an API key), hits the repository timeout and writes **no**
report — and `quality-gate.py` counts a missing report as "no breach". A green gate
that measured nothing is worse than no gate (cards 365, 420, 896, 925). Three things
had to be true, and all three are (checked, not assumed):

1. The data set lives in the named volume `github-runners_odc-cache` (card 914), and
   this repository stands at `trusted.volumes = true` in Woodpecker, so the
   `owasp-cve` step may mount it. It is the only step in the file that mounts anything.
2. The gate step installs `python3` itself (6 seconds, 28 MB).
3. The secret `nvd_api_key` exists on the repository, so the scan updates
   incrementally (~25 seconds) instead of downloading the NVD.

`plaintext-app`, `plaintext-guild` and `plaintext-iot` have run exactly this workflow
as `.woodpecker/analyse.yml` since 30 August 2026.

**Two crons, and both have to be switched on.** A cron created through the API is
born with `enabled: false` while `next_exec` already shows a plausible future time —
plaintext-root's `nightly` (id 15) and `wochenanalyse` (id 16) sat like that from
8 September until it was noticed the same evening. Read the `enabled` field back after
creating one; `next_exec` proves nothing.

**A manual run has to say it means it.** `.woodpecker/analyse-freigabe.sh` demands the
variable `analyse = voll` in the "Run pipeline" dialog and otherwise leaves every step
with success, having done nothing. A full analysis occupies one of the agent's two
slots for about an hour, and a stray click should not cost that. Cron runs pass without
the variable. To start one workflow and only one, run the cron itself:
`POST /api/repos/6/cron/16` — the "Run pipeline" button fires *every* file with
`event: manual`.

## When something is red

1. Open the pipeline from the commit status (`ci/woodpecker/pr/build`).
2. If there is **no status at all**, the configuration failed to parse — a
   Woodpecker `error` produces no GitHub status. Check the pipeline list in the
   UI, or read `pipelines.errors` from the server database.
3. Logs do not reach GitHub. The read path without an API token is described in
   [Setting up Woodpecker, section 6](ci/WOODPECKER_SETUP.md#6-reading-logs-without-an-api-token).

Common causes, in the order they actually occur: a module below the JaCoCo
minimum; an ArchUnit rule from `plaintext-root-archtests`; the second Surefire
execution failing where the first passed (a bean that only exists when an admin
module is present); `${...}` inside a YAML comment breaking the parse.
