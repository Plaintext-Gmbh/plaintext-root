# Setting up Woodpecker CI for a repository

A runbook for wiring a repository of this family to the Woodpecker instance at
`ci.plaintext.ch`. Written to be executed by an AI agent working on the
repository, so it states what to *verify* after each step, not just what to
type — a CI pipeline that reports green without having run is worse than none.

> **Status, 30 August 2026.** Seven repositories run on Woodpecker:
> plaintext-root, -app, -guild, -iot, -schuetu, -config, -dockercompose.
> plaintext-scripts is still on GitHub Actions.

## 1. What you are connecting to

| Piece | Where | Notes |
|-------|-------|-------|
| Woodpecker server | `https://ci.plaintext.ch` | container `woodpecker-server` on the NAS |
| Agent | same host | Docker backend: every step is its own container |
| Parallelism | `WOODPECKER_MAX_WORKFLOWS=4` | four workflows at once; the NAS has 6 CPUs |
| Maven cache | Docker volume `woodpecker-m2` → `/root/.m2` | mounted into **every** step by the agent |
| Stack definition | `plaintext-dockercompose`, path `tri/woodpecker` | change it there, not on the host |

The stack is the one thing in the gitops repository that Woodpecker must **not**
deploy itself — it would replace the agent that is running the pipeline. It is
listed in `PROJEKTE_GESPERRT` in `ci-lib/deploy.sh`; roll it out by hand with
`./build deploy tri/woodpecker` and write the new hash into `hashes.yaml`.

## 2. Files a repository needs

```
.ci-engine                 one word: github | woodpecker
.woodpecker/waechter.sh    guard, sourced first by every step
.woodpecker/build.yml      pull requests: compile + tests
.woodpecker/deploy.yml     master: release, publish, deploy
.woodpecker/<extra>.yml    optional: playwright.yml, sonar.yml, secret-scan.yml
```

Two constraints that are easy to get wrong:

- **`.woodpecker/` may contain files only, no subdirectories.** A folder there
  makes the server refuse the whole repository with
  `configFetcher: fallback did not find config: .woodpecker/lib is a folder not
  a file use Dir(..)` — and, because the pipeline never starts, GitHub shows no
  status at all. Shared shell code goes into a sibling directory such as
  `ci-lib/` (this is why plaintext-config and plaintext-dockercompose have one).
- **Every step sources the guard first**, as its first command:
  `- . .woodpecker/waechter.sh`. The guard reads `.ci-engine`; if it says
  `github`, the step exits 0 without doing anything. That is what makes the
  switch a commit you can revert, instead of a setting in a database on the NAS.

## 3. Wiring a new repository

1. **Enable the repository** in the Woodpecker UI (`ci.plaintext.ch` → Add
   repository). Nothing in the repo has any effect before this.
2. **Grant trust if the pipeline mounts volumes.** A step that needs
   `/var/run/docker.sock` (Testcontainers, docker builds) requires
   *Settings → Project → Trusted: Volumes*. Without it the pipeline fails at
   parse time with `Insufficient trust level to use volumes`. Grant volumes
   only — not network, not security.
3. **Add the secrets** the pipeline references. Currently in use across this
   family:

   | Secret | Used for |
   |--------|----------|
   | `maven_nas_token` | reading/publishing artifacts on `maven.plaintext.ch` |
   | `mvn_deploy_token` | publishing to the GitHub package registry |
   | `ssh_private_key` | deploying to the NAS |
   | `sonar_token` | SonarQube analysis |
   | `pushover_app_token`, `pushover_user_key` | failure notifications |

4. **Create the crons** the pipelines filter on. `when: event: cron` matches by
   **name**, not by expression, so a pipeline waiting for `cron: nightly` never
   runs until a cron with exactly that name exists. In use: `nightly` and
   `wochenanalyse`. Crons can only be created in the UI or through the API —
   there is no file for them in the repository.
5. **Copy the pipeline files** from the closest sibling repository and adapt
   ports, module names and deploy targets. plaintext-app is the fullest example
   (build, deploy, verify-dev, verify-prod, analyse, e2e-smoke), plaintext-root
   the simplest that still releases (build, deploy, playwright, sonar).
6. **Flip `.ci-engine` to `woodpecker`** and, in the same pull request, delete
   or disable the GitHub workflow it replaces. Leaving both live means two
   engines deploying the same commit.
7. **Verify** — see section 5. Do not treat "the pull request is green" as
   proof: a pipeline that never started also shows no red mark.

## 4. Pitfalls, each one paid for

**`${...}` inside a YAML comment breaks the pipeline.** Woodpecker expands
variables before parsing and does not skip comments. A comment mentioning
`${plaintext-app.version}` produced `missing closing brace` in plaintext-guild
and the pipeline errored **without a single step and without a GitHub status**,
which reads exactly like "no CI configured". Escape as `$${...}` or, better,
write the property name in prose.

**A pipeline `error` produces no commit status.** `failure` (a step failed) is
reported to GitHub; `error` (the config could not be parsed) is not. If a
repository seems to have stopped building, look at the pipeline list in the UI
or query the database (section 6) — the pull request will look clean.

**The Maven build cache outlives the run.** `woodpecker-m2` is shared across
repositories and holds `~/.m2/build-cache`. On a cache hit the module is
"restored" as a jar only: there is no `target/test-classes` (Failsafe then
reports `No tests matching pattern ... were executed`) and files unpacked during
`prepare-package` are missing from `target/classes`. Any step that runs against
`target/` after `mvn install` — Playwright integration tests, for instance —
needs `-Dmaven.build.cache.enabled=false` on both Maven invocations.

**The build cache also swallows the test *reports*.** Same mechanism, different
victim: a restored module has no `target/surefire-reports` either, so anything
reading them sees nothing. On a pull request that touches no Java file, that is
*every* module — `coverage-uebersicht` printed an empty table for months without
anyone noticing, and pipeline 50 (card 1018) showed it plainly. The fix is
`attachedOutputs` in `.mvn/maven-build-cache-config.xml`, which saves and
restores those directories with the jar; existing cache entries pick it up only
at their next real build (`-Dmaven.build.cache.skipCache=true` fills them all in
one run). See docs/CI.md, "Test reports".

**A manual run starts EVERY workflow of the repository, not the one you meant.**
`when: - event: manual` is present in all four files of plaintext-root, so
`POST /api/repos/6/pipelines` (or the "Run pipeline" button) also fires
`deploy.yml` and `sonar.yml`. Paid for on 31 August 2026: a manual run intended
for `build.yml` also pushed a full SonarQube analysis of a feature branch onto
the `ch.plaintext:plaintext-root` project — the step passes no `sonar.branch.name`,
so it overwrites the main analysis until the next weekly run. Nothing was
released: `deploy.yml` forces `DEPLOY_TARGET=ci-only` for manual events and exits
before publishing. That guard is the only reason a manual run is safe — do not
remove it, and expect the Sonar side effect.

**`[skip-ci]` with a hyphen means nothing to Woodpecker.** It honours the
GitHub spellings `[skip ci]` and `[ci skip]`. A release commit tagged
`[skip-ci]` still triggers a run — harmless here, but do not rely on it to
suppress one.

**Testcontainers needs a Docker socket the step does not have by default.**
`build.yml` mounts `/var/run/docker.sock` (hence the trusted-volumes flag);
`deploy.yml` in plaintext-app did not, and the release step failed *after*
pushing the tag and half the artifacts. Either mount the socket in every step
that runs such a test, or exclude the test there.

**Service containers start at the same time as the steps.** Woodpecker has no
`--health-cmd` gate, so a step that needs PostgreSQL must wait for it. The
established shape is a `for i in $(seq 1 60)` loop against `/dev/tcp/postgres/5432`
with a clear error message after 120 seconds — otherwise the run fails two
minutes later inside the tests and looks like a test problem.

## 5. Verifying the setup

Run these after wiring a repository. Each one fails loudly if the pipeline is
not actually doing its job.

```bash
# 1. Does the repository build at all? Open a pull request with a trivial change
#    and check that a Woodpecker status appears on the commit.
gh pr checks <nr> | grep woodpecker      # expect: ci/woodpecker/pr/<pipeline>

# 2. Did the steps run, or did the guard skip them?
#    The step log must contain the line from waechter.sh:
#    "CI-Motor: woodpecker — dieser Step ist zustaendig und laeuft."

# 3. Negative control: set .ci-engine to "github" on a scratch branch and push.
#    The pipeline must go green WITHOUT running the build — if it still builds,
#    the guard is not being sourced.
```

For a repository that releases, additionally: after the first merge to master,
check that the artifact really landed
(`curl -s -o /dev/null -w '%{http_code}' https://maven.plaintext.ch/releases/ch/plaintext/<artifact>/<version>/<artifact>-<version>.jar`
must be `200`) and that the deployed version endpoint reports the new number.

## 6. Reading logs without an API token

Woodpecker logs never reach GitHub — the commit status is only a link. The API
at `ci.plaintext.ch/api/...` answers `401` without a personal token, and the
vault holds only the agent secret and the OAuth credentials. The logs are in the
server's SQLite database on the NAS:

```bash
ssh mad@192.168.1.224 'sudo -n docker run --rm -i -v /volume1/docker/woodpecker/data:/d:ro \
  alpine:3.20 sh -c "apk add -q sqlite; cp /d/woodpecker.sqlite /tmp/w.db; sqlite3 /tmp/w.db"' <<'SQL'
-- which pipelines exist for a repository (repo_id 4 = plaintext-app)
select p.number, p.event, p.status, substr(p."commit",1,9), datetime(p.created,'unixepoch')
  from pipelines p where p.repo_id=4 order by p.number desc limit 5;
-- why did a pipeline error before any step ran?
select errors from pipelines where repo_id=4 and number=<nr>;
-- the log of one step
select s.id, s.name, s.state from steps s
  join pipelines p on s.pipeline_id=p.id where p.repo_id=4 and p.number=<nr>;
select cast(data as text) from log_entries where step_id=<step-id> order by id;
SQL
```

Notes for whoever automates this: `commit` is a reserved word, so quote it as
`p."commit"`. Pass the SQL on stdin — quoting it through three shell levels is
how you get `unrecognized token`. A single application build produces on the
order of 15 000 log lines, so grep for `<<< FAILURE`, `<<< ERROR`, `Caused by`
and `BUILD` rather than reading it whole.

## 7. Running a pipeline locally

Useful for checking pipeline mechanics before pushing:

```bash
brew install woodpecker-cli
DOCKER_HOST=unix:///var/run/docker.sock woodpecker-cli exec \
  --local --repo-path "$PWD" --repo-trusted-volumes \
  --pipeline-event pull_request --commit-branch master \
  --volumes "$HOME/.m2-woodpecker:/root/.m2" --timeout 3h \
  .woodpecker/build.yml
```

`--repo-trusted-volumes` is required as soon as the pipeline mounts anything;
without it the run stops at `Insufficient trust level to use volumes`. Copy your
`settings.xml` into the mounted cache directory, otherwise the private Maven
repository is unreachable.

What this proves and what it does not: it exercises the clone, the service
containers and the Maven steps. It does **not** work for Testcontainers-based
tests on macOS — the socket the container sees belongs to the Podman VM and the
step gets `AccessDenied` on `/var/run/docker.sock`, with or without the rootless
socket. Those tests can only be verified on the real agent.

## 8. Checklist

- [ ] Repository enabled in the Woodpecker UI
- [ ] Trusted → Volumes granted, if any step mounts one
- [ ] Secrets present under the exact names the pipeline references
- [ ] Crons created with the names the pipelines filter on
- [ ] `.woodpecker/` contains files only, no subdirectory
- [ ] Every step sources `.woodpecker/waechter.sh` as its first command
- [ ] No `${...}` inside YAML comments
- [ ] `.ci-engine` says `woodpecker`
- [ ] The GitHub workflow it replaces is deleted or disabled
- [ ] A pull request shows a Woodpecker status, and the log proves the steps ran
- [ ] Negative control done: `.ci-engine=github` skips the steps
