# Contract ownership in `plaintext-root-interfaces` and how split packages are handled

* **Status:** accepted
* **Date:** 2026-08-30
* **Deciders:** Daniel Marthaler
* **Context:** status report of 29.08.2026, §3 (wave 3)

## Context

The status report recorded two suspicions: that `plaintext-root-interfaces` holds contracts which
are app business logic and belong in `plaintext-app-interfaces`; and that several Java packages
are filled by more than one Maven module (split packages — forbidden under JPMS, confusing for
tooling, and they hide who is responsible for a package).

Both were measured before anything was moved: all 42 types in `plaintext-root-interfaces` were
individually resolved for implementers and users across **all five repos** (root, app, guild,
iot, schuetu), and the package-to-module mapping was built for all 326 packages of the family.

### Finding 1 — 41 of the 42 contracts are framework

The measurement largely refutes the suspicion. What looks like app business logic is almost
always Javadoc: `SecretResolver` names `zeiterfassung.jira-password` as an example key,
`MenuRegistry` names "Zeiterfassung" as an example title, `SearchProvider` names `"kontakte"` as
an example id. The **signatures** know no business terms — they talk about menus, roles, tenants,
cron runs, search hits and tiles. Those are framework terms, and the usage figures confirm it:
the load-bearing contracts (`PlaintextSecurity`, `MenuAnnotation`, `PlaintextCron`,
`ModuleDescriptor`, `MenuVisibilityProvider`) are consumed by three to five repos.

Exactly **one** type falls out of that: `ch.plaintext.upload.IUploadTarget`.

| | |
| --- | --- |
| users in root | **none** (apart from its own test) |
| users in app | `PostkontoUploadTarget`, `RunningUploadTarget` (implementations) and `RootUploadController` (consumer) |
| users in guild / iot / schuetu | none |

The endpoint name `/nosec/root/upload` and the Javadoc suggested a root responsibility that never
existed: the driving controller lives in `plaintext-app-webapp`. The contract is app business
logic inside the framework's contract module.

Two further types have conspicuous figures but are **correctly** placed here:

* `PlaintextRoles` — 0 users in root, 15 in app. Pure framework vocabulary
  (`ROLE_USER`/`ROLE_ADMIN`/`ROLE_ROOT`) and predicates over `PlaintextSecurity`; that root does
  not need it itself does not make it app code.
* `PlaintextEmailAddress` — apparently 0 users, but in fact used by the default method
  `PlaintextSecurity#getDeliverableEmail` in the same module.

### Finding 2 — almost all split packages are contract + implementation

29 packages are filled by more than one module. The bulk of them is **intended** and the direct
consequence of the pattern "contract in the interface module, implementation in the business
module under the same package":

| Package | Modules |
| --- | --- |
| `ch.plaintext.settings` | root-interfaces + admin-settings |
| `ch.plaintext.secrets` | root-interfaces + admin-secrets |
| `ch.plaintext.modules` | root-interfaces + admin-modules |
| `ch.plaintext.notifications` | root-interfaces + admin-notifications |
| `ch.plaintext.webhooks` | root-interfaces + admin-webhooks |
| `ch.plaintext.mailtemplate` | root-interfaces + admin-mailtemplate |
| `ch.plaintext.bus` | root-interfaces + root-common |
| `ch.plaintext.store` | root-interfaces + root-common |
| `ch.plaintext.boot.dashboard` | root-interfaces + root-menu |
| `ch.plaintext.boot.search` | root-interfaces + root-webapp |
| `ch.plaintext.boot.deeplink` | root-interfaces + root-webapp |
| `ch.plaintext.boot.menu` | root-interfaces + root-menu + root-webapp |
| `ch.plaintext.rechnungen`, `.kontakte`, `.auszahlungen`, `.ocr`, `.postkonto`, `.strom`, `.messenger` | app-interfaces + the respective `plaintext-z-*` |

Resolving them would mean pushing every contract into a `…​.api` sub-package — a mass rename
across five repos that touches every import line of every consumer and triggers a release chain
for each of the four apps. The benefit would be nil for the foreseeable future, because the
family is a classic classpath build and does not use JPMS. **We are not resolving them.**

What is interesting instead are the packages that cross a **repo boundary** — there an app fills
a package that belongs to root:

| Package | Filled from outside by | Assessment |
| --- | --- | --- |
| `ch.plaintext.boot.plugins.config` | `ForwardedHeaderConfig` **three times identically** in app-, guild- and iot-webapp | Real redundancy; belongs in `plaintext-root-web`, once |
| `ch.plaintext.boot.web.nosec` | `RootUploadController` (app-webapp) | The consumer of `IUploadTarget`; should move along with it |
| `ch.plaintext.boot.web` | `SwaggerRedirectController` (app-webapp) | An app concern under a root package name |
| `ch.plaintext.boot` | `DemoCron`, `DemoCronGlobal` (app-webapp) | Demo code |
| `ch.plaintext.boot.plugins.security` | `AppRoleProvider` (app-webapp) | Implements a root SPI |
| `ch.plaintext.boot.plugins.security.model` | `MyUser` (app-z-zeiterfassung) | The app's JPA entity under a root package name |
| `ch.plaintext.apitoken` | `KontaktApiController` (app-z-kontakte) | A business controller under a root package name |
| `ch.plaintext.mcp` | `McpLiterale` (app-interfaces) + root-common | Two repos share one package |
| `ch` | the five `*BootApplication` classes | Deliberate: the component-scan root |

In the whole family there is exactly **one** genuine name collision
(`ch.plaintext.boot.plugins.config.ForwardedHeaderConfig`), and it is harmless, because the three
copies live in three separate deployables that never share a classpath.

## Decision

1. `IUploadTarget` moves to `plaintext-app-interfaces` — **under the same fully qualified name**
   `ch.plaintext.upload.IUploadTarget`, so that no consumer has to touch its imports.
2. The move runs as expand/contract, because app is pinned to a *published* root version and a
   type that disappears here is only missing for app after a root release:
   * **Expand** (app PR): app-interfaces receives the contract; app-webapp declares
     app-interfaces explicitly. During this phase the type lies on app's classpath twice, and the
     **root** copy is the one that wins: in the resolved order of `plaintext-app-webapp`,
     `plaintext-root-interfaces` comes before `plaintext-app-interfaces`. The phase is viable
     only because both copies are bytecode-identical (verified with `javap`: same methods, same
     `UploadResult` record). The app copy takes effect only with the contract step.
   * **Contract** (root, this PR): the root copy becomes `@Deprecated(forRemoval = true)` and is
     dropped in a follow-up PR as soon as app is pinned to a root version without it.
   The order is mandatory: app first, then the deletion in root.
3. The remaining 41 contracts stay where they are.
4. Split packages following the "contract + implementation" pattern stay. An app newly filling a
   root package from the outside counts as a defect and is rejected at review.

## Consequences

* Positive: `plaintext-root-interfaces` afterwards contains framework contracts exclusively; the
  status report's claim is thereby settled instead of open.
* Positive: the move costs no consumer a single import change.
* Negative: between expand and contract the type exists twice, and it is the root copy that
  takes effect. A change to the app copy would be **silently ineffective** during that phase —
  the more unpleasant direction, because it looks like a change that has taken hold. That is why
  the root copy carries a `forRemoval` flag and the app copy an explicit warning in its Javadoc;
  the phase is meant to stay short.
* Neutral: `ForwardedHeaderConfig` stays threefold for the time being. Consolidating it into
  `plaintext-root-web` would mean a root release plus three consumer bumps for 30 lines of
  configuration — to be decided separately, not part of this ADR.

## Alternatives considered

| Option | Why not? |
| --- | --- |
| All at once: root deletes, app takes over, everybody bumps | app would not be buildable between the two merges; the pin to a published root version does not permit an atomic cut. |
| Leave `IUploadTarget` in root and only correct the Javadoc | Would have documented the misnaming instead of fixing it; the contract would have kept pretending to have consumers in root that do not exist. |
| Resolve split packages globally through `…​.api` sub-packages | A mass rename across five repos, four release chains, every import line affected — without JPMS there is nothing to gain. |
| Move `ForwardedHeaderConfig` to root in the same step | A release chain of its own across three consumers; mixes two independent decisions into one PR. |

## References

* Status report of 29.08.2026, §3 (wave 3)
* ADR-0006 — Releases and consumer pins
