# Seiten-Zugriffsschutz (Page Access Guard)

Status: implemented since root 1.429.0.

Der Page Access Guard leitet den Zugriffsschutz einer JSF-View aus der **Menü-Sichtbarkeit** ab:
Wer den Menüpunkt sehen darf, der auf eine Seite zeigt, darf die Seite aufrufen. Das umfasst
Rollen (`@MenuAnnotation(roles = ...)`) **und** die mandantenspezifische Sichtbarkeit
(`MenuVisibilityProvider`, Modul `plaintext-root-menu-visibility`).

## Bausteine

| Klasse | Aufgabe |
|---|---|
| `PageAccessGuardService` | Entscheidung „darf diese View?" — Menü-Lookup, Allowlist, Aliase, Modus |
| `PageAccessGuardFilter` | Durchsetzung **vor** dem `FacesServlet`, eingehängt in die Spring-Security-Kette |
| `PageAccessGuardBackingBean` | zweite Schicht als `f:event preRenderView` in `includes/template.xhtml` |
| `PageAccessGuardStartupReport` | listet beim Boot die Views ohne Zugriffsregel auf |
| `PlaintextSecurityConfig` | harte `requestMatchers` für die Admin-/ROOT-Seiten (menü-unabhängig) |
| `MenuLinkInvariantTest` | Build-Leitplanke: jeder Link endet auf `.html`, zeigt auf eine existierende View, jede View hat eine Regel |

## Modi

`plaintext.security.page-guard.mode`

| | `REPORT` (Framework-Default) | `STRICT` |
|---|---|---|
| kanonischer Link-Vergleich (`.htm`/`.html`/`.xhtml`/`.jsf`) | ja | ja |
| Exception bei der Prüfung | verweigern | verweigern |
| Allowlist / Aliase | ja | ja |
| View **ohne** Menüeintrag, Alias, Allowlist | erlauben **+ WARN** | **verweigern** |
| Eltern-Rollen-Vererbung | nein | ja |

`plaintext-root-webapp` selbst läuft in `STRICT` (`application.yml`). Der Framework-Default ist
`REPORT`, weil jede konsumierende App eigene Views mitbringt: ohne Übergangsmodus würden
`plaintext-app`, `plaintext-guild` und `plaintext-schuetu` beim Framework-Update ihre Detail- und
Edit-Seiten aussperren. Jede App bringt ihre eigene `application.yml` mit; die des Frameworks wird
auf dem Klassenpfad verdeckt und wirkt daher nur für die root-App.

## Eine neue Seite anlegen

1. Regelfall: `@MenuAnnotation(link = "meineseite.html", parent = "...", roles = {...})`.
   **Der Link muss auf `.html` enden** — `MenuLinkInvariantTest` erzwingt das.
2. Detail-/Edit-Seite ohne eigenen Menüpunkt: **Alias** setzen, dann gelten die Regeln der
   Listenseite.
   ```yaml
   plaintext:
     security:
       page-guard:
         aliases:
           rechnungdetail.xhtml: rechnungen.html
   ```
   Framework-Aliase (in `PageAccessGuardService.FRAMEWORK_ALIASES`):
   `mandatemenudetail → mandatemenu`, `anforderungdetail → anforderungen`,
   `claudesummary → anforderungen`, `howtodetail → howtos`.
3. Seite, die wirklich jeder eingeloggte User sehen darf: **Allowlist**.
   ```yaml
   plaintext:
     security:
       page-guard:
         allowlist:
           - wander-druck.xhtml
           - public/**
   ```
   Framework-Allowlist: `login-totp`, `myuser`, `useradmin`, Präfix `nosec/**`.
   Systemseiten: `home`, `index`, `access-denied`, `error`, `login`.

## Rollen und Elternmenüs

`MenuItemImpl.isOn()` prüft nur die **eigenen** `roles`. Im gerenderten Menü verbirgt ein
unsichtbares Elternmenü trotzdem alle Kinder (`PrimefacesSubmenu.isRendered()`) — der Guard
bildete diese Hierarchie früher nicht nach. Ein Menüpunkt ohne eigene `roles` unter „Root" war
deshalb per Direkt-URL für jeden eingeloggten User offen.

Im Modus `STRICT` erbt ein Menüpunkt **ohne eigene `roles`** die Sichtbarkeit seines Elternmenüs.
Deklariert er eigene `roles`, sind diese abschließend. Genau so bleibt eine bewusst breiter
erreichbare Seite unter einem eingeschränkten Elternmenü möglich:

```java
// notifications.html hängt unter "Root", ist aber über die Topbar-Glocke für jeden User verlinkt
@MenuAnnotation(title = "Benachrichtigungen", link = "notifications.html", parent = "Root",
                roles = {"USER", "ADMIN", "ROOT"})
```

## Warum ein Filter und nicht `preRenderView`

`preRenderView` feuert in RENDER_RESPONSE (Phase 6), Action-Methoden laufen in
INVOKE_APPLICATION (Phase 5). Ein AJAX-/POST-Postback auf eine gesperrte Seite hatte die
Backing-Bean-Action also bereits **ausgeführt**; der Redirect verwarf danach nur noch die Antwort.
Der Filter greift vor jeder JSF-Phase.

Er ist per `http.addFilterAfter(..., AuthorizationFilter.class)` in die **Spring-Security-Kette**
eingehängt, nicht per `FilterRegistrationBean`. Grund: `UrlRewriteConfig` registriert seinen
Rewrite-Filter mit `Ordered.HIGHEST_PRECEDENCE + 1`, also vor Spring Security (`order = -100`), und
forwardet `/x.html` auf `/x.xhtml` ohne `chain.doFilter()`. Ein eigenständig registrierter Filter
mit `DispatcherType.REQUEST` würde bei `.html`-URLs deshalb nie laufen. Die Security-Kette läuft
dagegen für alle Dispatch-Typen (`SecurityFilterProperties.dispatcherTypes = EnumSet.allOf(...)`).

Antwortverhalten bei verweigertem Zugriff: normaler GET → `302` auf `/access-denied.html`,
POST/AJAX → `403` (ein `302` auf einen Postback würde dem Client vortäuschen, die Aktion sei
ausgeführt worden).

## Not-Aus

`plaintext.security.page-guard.enabled=false` schaltet Filter **und** `preRenderView`-Guard ab.
Nur für den Fall gedacht, dass der Guard in PROD legitime Seiten sperrt und kein Rollback möglich
ist. Die harten `requestMatchers` in `PlaintextSecurityConfig` bleiben davon unberührt.

## Offene Punkte (Folgekarten)

- `@EnableMethodSecurity` aktivieren und Autorisierung nach Service/Backing-Bean verlagern; die
  Menü-Sichtbarkeit soll dann reine UX sein.
- `mode: STRICT` in den Consumer-Apps aktivieren, nachdem deren Views vollständig zugeordnet sind.
  `MenuLinkInvariantTest` gehört danach nach `plaintext-root-archtests` — das Modul läuft in den
  Consumer-Builds mit und würde die Invariante dort erzwingen.
- `demo.xhtml` löschen (verwaiste Beispielseite, aktuell bewusst gesperrt).
