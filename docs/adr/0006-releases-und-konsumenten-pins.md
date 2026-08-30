# Releases of root and how the consumers follow (auto-bump, pins)

* **Status:** accepted
* **Date:** 2026-08-29 (recorded retroactively; cards 322, 776, 942)
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root` is a library with four consumers (`plaintext-app`, `-guild`, `-schuetu`,
`-iot`) that use `plaintext-root-parent` as their parent POM and therefore draw their Spring Boot
version from here as well. Root's own container has been shut down since 12.08.2026 (card 776) —
there is nothing left to deploy, but without releases from here no app would have a route to a
new root version.

Three things had to be settled: how a root release comes about, how it reaches the apps, and how
to keep the cleanup of the Maven repository from deleting a version an app is still pinned to.

## Decision

1. **Every merge to `master` is a release** (`ci-cd.yaml` → `release-only`): bump, tag,
   `mvn clean deploy` to `maven.plaintext.ch/releases` **and** to GitHub Packages (dual publish),
   followed by `Prepare next development iteration … [skip-ci]`. No container deployment. The
   minor number counts releases and is not a SemVer promise; compatibility is stated in the
   `CHANGELOG.md` text.
2. **The consumers pull — root does not push** (`root-autobump.yaml` in every consumer repo,
   card 322): the workflow reads the `maven-metadata.xml` of the NAS repository (not the Git tag
   — that is created before the `deploy`), sets the parent version and
   `<plaintext-root.version>`, **builds as a check** and only then opens a PR. Red build → no PR,
   just a Pushover message. **No auto-merge**: the merge in the consumer is that consumer's
   deployment and stays a deliberate, serial act.
3. **Every consumer reports its pin** (`publish-root-pin.yaml`, card 942) to
   `Plaintext-Gmbh/plaintext-mvn`, branch `pins`, on every master push that touches `pom.xml`,
   and weekly as a heartbeat. The cleanup job there deletes no pinned version and aborts if a pin
   is older than 30 days.

## Consequences

* **Positive:** a broken root release cannot spread through the apps — the verify build in the
  consumer stops it before a PR even exists.
* **Positive:** no cross-repo PAT, no push rights from root into the apps; the "pull" direction
  makes do with the token that exists anyway.
* **Negative:** latency. The apps lag up to a day (two cron windows) behind root; an urgent fix
  needs `workflow_dispatch` or a manual bump.
* **Negative:** one bump PR per app and per root release — with daily root releases that is
  visible review effort, and nobody automates it away (deliberately).
* **Negative:** a green build is no proof of a working runtime (fwtool fell over with a 502 after
  a bump because Vault variables were missing — the build was green). That is why a human stays
  in charge of the merge.
* **Neutral:** root's version number climbs fast (a three-digit minor). That is the consequence
  of "every merge a release" and not a defect.

## Alternatives considered

| Option | Why not? |
| --- | --- |
| root triggers bumps in the apps via `repository_dispatch` (push) | Needs a cross-repo PAT; on the free plan, org secrets silently resolve to empty in private repos — one more silent failure path. |
| Auto-merging the bump PRs | Parallel consumer deployments recycle the NAS runners in the middle of a blue-green switch (PROD 502); and green ≠ running. |
| `versions:update-property` for the bump | Produces wrong suggestions, because through `${plaintext.version}` the property `${plaintext-root.version}` also attaches to artifacts from other version lines (measured on 30.07.2026 in iot). |
| The Git tag as the source of the version | The tag is created before the `mvn deploy`; a tag without an artifact would give an unresolvable bump. |
| Letting the cleanup job read the pins out of the app POMs | The app repos are private; the token of `plaintext-mvn` cannot get in. That is why the apps push. |

## References

* `.github/workflows/ci-cd.yaml` (header comment on card 776 / `release-only`)
* Consumer repos: `.github/workflows/root-autobump.yaml`, `.github/scripts/root-autobump.sh`,
  `.github/workflows/publish-root-pin.yaml`
* `plaintext-scripts/.github/workflows/ci-cd-pipeline.yaml`
* `CLAUDE.md` (release procedure, port table)
