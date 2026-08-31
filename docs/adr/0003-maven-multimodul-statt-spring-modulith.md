# Keep the Maven multi-module layout, buy into Spring Modulith selectively

* **Status:** proposed
* **Date:** 2026-08-17
* **Deciders:** Daniel
* **Consulted:** worker01 (analysis)
* **Informed:** all contributors

## Context

`plaintext-root` is today a **Maven reactor with 24 modules** (693 Java files, ~93,000 lines).
The question was whether moving to **Spring Modulith 2.1** would improve the approach.

The decisive circumstance up front, because it inverts most of the arguments: **the modules of
`plaintext-root` are not internal packages of one application, they are published libraries.**
Measured against the consumer POMs:

```
plaintext-app       16 different root/admin artifacts
plaintext-guild     15
plaintext-schuetu    8
```

Spring Modulith addresses the opposite case: **one** deployment monolith whose logical parts you
want to keep clean so that they can later become services. Five applications that share modules
through a Maven repository are not a Modulith scenario — they are already one step beyond it.

## Decision

We stay with the Maven multi-module layout and adopt **individual building blocks from Spring
Modulith wherever they close a demonstrated gap** — not the module definition, but the tooling
around it. Concretely, in this order:

1. **`spring-modulith-docs`** for the architecture documentation (see the gap below).
2. Evaluate **`spring-modulith-events` / the Event Publication Registry** when a module event may
   be lost but should not be (today 8 publisher and 8 listener files).
3. **`ApplicationModules.verify()`** only if individual modules grow internally into packages
   whose boundaries the compiler no longer represents.

No restructuring of the module layout, no merging of Maven modules.

## Consequences

* **Positive:** the compile-time boundary stays. Whatever is not declared as a dependency in a
  Maven module cannot be imported — that is stronger than any test rule, because there is no way
  around it. Modulith checks boundaries only in a test; a skipped test is an open boundary.
* **Positive:** reuse across five applications remains untouched. A Modulith conversion would
  have to rebuild it via `@Modulithic(additionalPackages)` or `ApplicationModuleSourceFactory` —
  more machinery for the same result.
* **Positive:** `plaintext-root-interfaces` (48 contracts) is already what Modulith calls a
  "named interface" — only as an artifact of its own instead of a package convention. The
  difference is enforcement: hard here, by test there.
* **Negative:** the reactor overhead stays. 24 modules mean 24 jar build steps, and a change in
  one module drags a release through the consumers (auto-bump). Modulith would have a single
  build step.
* **Negative:** we do not get the Modulith extras for free: generated documentation, module
  traces in observability, the `Moments` API. Whatever we want of those we have to build in one
  at a time (see Decision).
* **Neutral:** the 42 places using `@Autowired(required = false)` are our pattern for "the module
  may be missing". Modulith has no counterpart for it — it assumes a fixed set of modules. That
  is an argument in our favour, but it does not make the optional wiring any prettier.

## The one gap Modulith would close today

**`docs/ARCHITECTURE.md` is maintained by hand and no longer matches the code.**
Measured on 17.08.2026:

```
Maven modules in the directory                     24
Boxes in the module diagram                        16

in the diagram, but NO module for it               Email Module, Wertelisten, Filelist
module exists, not to be found in the diagram      apitoken, i18n, modules, oidc, secrets,
                                                   webhooks, pageguard, archtests
```

(Two further boxes carry German names for modules named in English — `Menuesteuerung` →
`menu-visibility`, `Rollenzuteilung` → `role-assignment`; that is readability, not an error.)

A diagram that names three modules which do not exist and keeps silent about eight others is no
longer documentation, it is an assertion. This is exactly where `spring-modulith-docs` is strong:
it generates the overview **from the code**, so that it can no longer go stale.

## Alternatives considered

**Full switch to Spring Modulith.** Rejected: the 24 Maven modules would become a single one, the
boundaries would move from compile time into a test, and the reuse in four consumer applications
would have to be rebuilt via `additionalPackages`. We would give up a hard boundary for a tested
one, and get in return tools that can also be had individually.

**Both in parallel: Modulith on top of the existing modules.** Not rejected, but untested — the
documentation names mono-repo structures as the standard case and does not treat modules spread
across several jars as one. Anyone who tries it should start with `spring-modulith-docs` on
**one** consumer (`plaintext-schuetu`, 8 artifacts, for instance) and measure whether the
generated overview recognizes the module boundaries at all. That is the cheapest experiment that
carries weight.

**Just bring the documentation up to date by hand.** Possible, and necessary today, but it only
repairs the current state: the same drift starts over with the next module.

## What this analysis did NOT examine

* **Whether `ApplicationModules.verify()` finds anything at all across Maven module boundaries.**
  The reference describes package conventions inside one application; for our layout that is an
  open question, not an answered one.
* **Build time.** That 24 reactor modules are slower than one module is plausible, but it was not
  measured here — and the comparison would be unfair as long as the consumers need the same
  artifacts.
* **Whether the 8 publishers / 8 listeners really need a registry.** That is decided per event,
  by whether its loss would be noticed. This question cannot be answered with a library.


---

## Addendum, 30 August 2026 — the gap was closed by hand

`docs/ARCHITECTURE.md` was rewritten on 30 August 2026 as part of a documentation
overhaul. The diagram now has one box per module group with the dependencies read
off the `pom.xml` files; the three phantom modules (Email, Wertelisten, Filelist)
are gone and the eight missing ones are represented.

That removes the concrete argument in the section above, but not the underlying
one: the diagram is still **maintained by hand**, so it will drift again. The
measurement that proved the drift took a scripted comparison of `<module>` entries
against the diagram boxes — that comparison is cheap to repeat and is the honest
prerequisite for keeping this decision. Should the drift return, the case for
`spring-modulith-docs` gets stronger, not weaker.

**Status unchanged**: still Maven multi-module, still no Spring Modulith.
