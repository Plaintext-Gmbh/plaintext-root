# Use JoinFaces (Jakarta Faces + Spring Boot) instead of plain Spring MVC

* **Status:** accepted
* **Date:** 2026-05-04 (recorded retroactively)
* **Deciders:** Daniel Marthaler

## Context

The framework needs an admin-style UI with forms, tables, modal dialogs,
permissions, and a navigation tree. The starting point is a Spring Boot
application with a strong opinion about boilerplate-light Java.

The available options are:

1. **Plain Spring MVC + a JS framework** (React/Vue) — modern, flexible,
   but doubles the toolchain (Maven + npm), and loses the
   "everything-in-one-Java-process" deployment story.
2. **Spring MVC + Thymeleaf** — single language, but every dynamic
   component (data table, dropdown chooser, file upload) becomes hand-rolled.
3. **Jakarta Faces (JSF) + PrimeFaces** — server-side stateful UI with a
   large component library. Historical baggage is real but PrimeFaces is
   actively maintained.
4. **JoinFaces** — a Spring Boot starter that hides the
   `web.xml`/`faces-config.xml` glue and lets us use Spring DI, Spring
   Security, Spring Data, and JSF in one app.

## Decision

We will build the framework on **JoinFaces** with **PrimeFaces** components.
Backing beans are Spring beans (`@Component`/`@Named`) and view models are
JSF-scoped (`@ViewScoped`). REST endpoints stay on Spring MVC for use by
external tooling.

## Consequences

Positive:

* Single language, single build, single deployable.
* PrimeFaces ships data-table, dialog, upload, calendar, charts — exactly
  the bricks an admin UI needs.
* Spring Security integrates cleanly, including OIDC (added in
  `plaintext-admin-oidc`).
* Test access via JsfTesting + Selenium is documented and works.

Negative:

* JSF's stateful view tree is unfamiliar to engineers from React/Vue
  backgrounds; mentoring overhead.
* Custom CSP headers must allow inline JSF event handlers (mitigated by
  nonces, see `IMPROVEMENT_PROPOSALS.md`).
* Hot-reload story is weaker than with the Vite-style frontend stacks.

Neutral:

* JoinFaces version cadence is independent from Spring Boot's. We pin in
  the parent `pom.xml`.

## Alternatives considered

| Option                              | Why not?                                              |
| ----------------------------------- | ----------------------------------------------------- |
| Spring MVC + React/Vue              | Doubles the build pipeline and host-deploy contract.  |
| Spring MVC + Thymeleaf              | No comparable component library; everything bespoke.  |
| Plain JSF without JoinFaces         | Drops Spring DI for backing beans; fights the runtime. |

## References

* JoinFaces: <https://github.com/joinfaces/joinfaces>
* PrimeFaces: <https://www.primefaces.org/>
* Used in `plaintext-root-webapp/pom.xml`.
