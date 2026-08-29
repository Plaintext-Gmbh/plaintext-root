# Page Access Guard: `STRICT` für die root-App, `REPORT` als Framework-Default

* **Status:** accepted
* **Date:** 2026-08-29 (nachträglich festgehalten; Verhalten seit root 1.429.0, Karte 308)
* **Deciders:** Daniel Marthaler
* **Informed:** alle Bearbeiter der Konsumenten-Apps

## Context

Der Zugriffsschutz einer JSF-View wurde lange nur über die Menü-Sichtbarkeit *gerendert*: wer
den Menüpunkt nicht sah, fand die Seite nicht — aufrufen konnte er sie trotzdem
(`mandatemenudetail` hatte nicht einmal einen Menüpunkt und war damit völlig offen). Der
Page Access Guard (`plaintext-root-pageguard`) leitet die Entscheidung seit 1.429.0 aus
derselben Quelle ab, an der die Sichtbarkeit hängt — `@MenuAnnotation(roles = …)` plus
Mandanten-Sichtbarkeit — und setzt sie in einem Filter **vor** dem `FacesServlet` durch.

Die offene Frage war der Umgang mit Views, die **keinem** Menüpunkt zugeordnet sind
(Detail-/Edit-Seiten). Fail-closed sperrt sie aus; fail-open lässt die Lücke von vorher.

## Decision

Zwei Modi unter `plaintext.security.page-guard.mode`:

* **`REPORT` ist der Framework-Default.** Kanonischer Link-Vergleich, Verweigern bei
  Exception, Allowlist und Aliase gelten; eine View ohne Zuordnung wird **erlaubt und mit
  WARN protokolliert**, der `PageAccessGuardStartupReport` listet sie beim Boot.
* **Die root-App selbst läuft in `STRICT`** (gesetzt in ihrer `application.yml`): View ohne
  Zuordnung → verweigern, Eltern-Rollen werden vererbt. Ein Menüpunkt mit eigenen `roles`
  bleibt abschliessend, damit eine bewusst breite Seite unter einem engen Elternmenü möglich
  ist (`notifications.html`).
* Unabhängig vom Guard bleiben die harten `requestMatchers` in `PlaintextSecurityConfig`
  (`ROOT_ONLY_PAGES`, `ADMIN_PAGES`) — zweite Schicht, menü-unabhängig.

## Consequences

* **Positiv:** Die root-App ist fail-closed; `MenuLinkInvariantTest` erzwingt beim Build, dass
  jede View eine Regel hat (Menüpunkt, Alias oder Allowlist).
* **Positiv:** Die Konsumenten (app, guild, schuetu, iot) bekommen beim Framework-Update
  zuerst ihre Lückenliste ins Log statt ausgesperrte Detail-Seiten — und stellen dann
  gezielt auf `STRICT` um.
* **Negativ:** Solange eine App in `REPORT` bleibt, ist die Lücke dort nur *sichtbar*, nicht
  geschlossen. Der Default ist bewusst der schwächere Modus; die Umstellung ist Arbeit je
  App.
* **Negativ:** Jede App bringt eine eigene `application.yml` mit, die die von root verdeckt.
  Der `STRICT`-Eintrag von root wirkt deshalb **nur** in root — das muss man wissen, sonst
  glaubt man, das Framework sei überall fail-closed.
* **Neutral:** `PageGuardProperties` war bis 1.491.0 eine innere Klasse von
  `PlaintextSecurityProperties`; der Präfix `plaintext.security.page-guard` ist beim
  Herauslösen gleich geblieben (`PageGuardAutoConfigurationTest` pinnt das).

## Alternatives considered

| Option | Why not? |
| --- | --- |
| `STRICT` als Framework-Default | Hätte in jeder Konsumenten-App beim Bump alle Detail-/Edit-Views ohne Menüpunkt ausgesperrt — Ausfall statt Warnung. |
| Nur die harten `requestMatchers` pflegen | Eine Liste je Seite von Hand; genau so ist `mandatemenudetail` durchgerutscht. Der Guard leitet aus der Quelle ab, die ohnehin gepflegt wird. |
| Prüfung nur im `preRenderView` (JSF-Ebene) | Läuft erst in RENDER_RESPONSE, nach Actions; der Filter greift vor dem `FacesServlet`. Der `preRenderView`-Bean bleibt als zweite Schicht. |

## References

* `docs/security/PAGE_ACCESS_GUARD.md`
* `plaintext-root-pageguard`: `PageGuardMode`, `PageGuardProperties`, `PageAccessGuardService`
* `plaintext-root-webapp/src/main/resources/application.yml` (`page-guard.mode: STRICT`, Karte 308)
* CHANGELOG 1.635.0 (Modul-Herauslösung, Auto-Configuration)
