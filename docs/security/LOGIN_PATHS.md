# Alternativer Anmeldeweg: `/token-login`

Neben Form-Login, OAuth2/OIDC und One-Time-Token kennt das Framework einen Weg, der
eine Browser-Session **ohne Passworteingabe** aufbaut. Er ist für scriptgesteuerte
bzw. Kiosk-Aufrufer gedacht (UI-Tests, PageTester, ZAP, Turnier-Kiosk) und
sicherheitskritisch.

| Weg | Credential | Widerrufbar | Ablauf | Abschaltbar |
|---|---|---|---|---|
| `GET /token-login?token=` | ApiToken-JWT (RS256, gehasht in `api_token`) | ja (Invalidierung) | ja | `plaintext.security.token-login.enabled` |

Der frühere `GET /autologin?key=` (statischer Klartext-Key in
`my_user_entity.autologin_key`, weder ablaufend noch widerrufbar) ist
**entfernt** — Endpunkt, Spalte und Konfiguration.

## Absicherung

`TokenLoginController` finalisiert die Anmeldung über `SessionLoginFinalizer` und
durchläuft damit **dieselben Gates wie der Form-Login**:

1. **Account-Status** (`AccountStatusUserDetailsChecker`) – ein wegen Brute-Force
   gesperrter Account (`AccountLockoutService`) kommt auch hier nicht durch.
2. **Session-Fixation** – `ChangeSessionIdAuthenticationStrategy` vergibt vor dem
   Speichern des `SecurityContext` eine neue Session-Id. Ohne das wäre eine dem
   Opfer untergeschobene Session nach dem Klick auf den Login-Link voll
   authentifiziert.
3. **Zweiter Faktor** – `PlaintextAuthenticationSuccessHandler` schickt TOTP-User
   in den Pending-Flow (`/login/totp`) statt in eine Vollsession; ebenso greift
   der erzwungene Passwortwechsel.

Zusätzlich ist der Pfad im `RateLimitFilter` gedrosselt, seine Antwort ist
`no-store`, und das Token erscheint im Log nur maskiert.

## Scope-Zwang

Ein ApiToken trägt einen `scope`-Claim (`READ`/`EINTRAGEN`/`ADMIN`/`SESSION`).
Für den Session-Aufbau sind nur die Scopes aus
`plaintext.security.token-login.required-scopes` (Default `SESSION`)
zugelassen; ein Token **ohne** Claim wird abgelehnt (fail-closed).

Bis zum 05.08.2026 stand `ADMIN` ebenfalls im Default, damit bestehende
Vollzugriffs-Tokens weiterfunktionieren. Damit war jedes ADMIN-MCP-Token zugleich
ein Generalschlüssel für eine Browser-Session — eine zweite Tür neben der
markierten. Karte 544 hat den Bestand erhoben (in 30 Tagen kein einziger
erfolgreicher `/token-login`; die drei ADMIN-Tokens mit `use_count 0`) und `ADMIN`
daraufhin aus dem Default entfernt.

Grund: Die Session bekommt die vollen DB-Rollen des Token-Besitzers. Ohne diese
Prüfung ließe sich ein für Automation ausgestelltes `READ`-Token an
`/token-login` hängen und ergäbe eine Browser-Vollsession — die Scope-Grenze, auf
die sich der MCP-Zugriff verlässt, wäre über diesen Weg wertlos.

`SESSION` ist der dafür gedachte minimale Scope. Der `McpBearerTokenFilter` kennt
den Wert nicht und vergibt dafür nur `SCOPE_READ` — ein Session-Token wird also
nicht nebenbei zum API-Vollzugriff.

## Betriebshinweise

* Deployments ohne Token-Login-Nutzer sollten `plaintext.security.token-login.enabled: false`
  setzen.
* Das Token reist heute als Query-Parameter (Bookmark-/Script-Tauglichkeit)
  und landet damit in Proxy-Logs und Browser-History. Die Umstellung auf
  POST/Header ist eine offene Folgeaufgabe.
* Ein vorab ausgestelltes Token validiert nur gegen Instanzen mit **stabilem**
  JWT-Signaturschlüssel (`plaintext.jwt.private-key-vault-item` bzw.
  `-file`). Eine lokal ohne Schlüssel gestartete Instanz erzeugt bei jedem Start
  ein flüchtiges RSA-Paar — dort ist der Form-Login der Weg für Skripte.
