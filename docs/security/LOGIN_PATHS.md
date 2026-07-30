# Alternative Anmeldewege: `/autologin` und `/token-login`

Neben Form-Login, OAuth2/OIDC und One-Time-Token kennt das Framework zwei Wege, die
eine Browser-Session **ohne Passworteingabe** aufbauen. Beide sind für
scriptgesteuerte bzw. Kiosk-Aufrufer gedacht (UI-Tests, PageTester, ZAP,
Turnier-Kiosk) und beide sind sicherheitskritisch.

| Weg | Credential | Widerrufbar | Ablauf | Abschaltbar |
|---|---|---|---|---|
| `GET /autologin?key=` | statischer Key in `my_user_entity.autologin_key` (Klartext) | nur durch Löschen des Keys | nein | `mad.autologin` je Mandat (Setup-UI / `MAD_AUTOLOGIN`) |
| `GET /token-login?token=` | ApiToken-JWT (RS256, gehasht in `api_token`) | ja (Invalidierung) | ja | `plaintext.security.token-login.enabled` |

`/token-login` ist der vorgesehene Nachfolger von `/autologin`.

## Gemeinsame Absicherung

Beide Controller finalisieren die Anmeldung über `SessionLoginFinalizer` und
durchlaufen damit **dieselben Gates wie der Form-Login**:

1. **Account-Status** (`AccountStatusUserDetailsChecker`) – ein wegen Brute-Force
   gesperrter Account (`AccountLockoutService`) kommt auch hier nicht durch.
2. **Session-Fixation** – `ChangeSessionIdAuthenticationStrategy` vergibt vor dem
   Speichern des `SecurityContext` eine neue Session-Id. Ohne das wäre eine dem
   Opfer untergeschobene Session nach dem Klick auf den Login-Link voll
   authentifiziert.
3. **Zweiter Faktor** – `PlaintextAuthenticationSuccessHandler` schickt TOTP-User
   in den Pending-Flow (`/login/totp`) statt in eine Vollsession; ebenso greift
   der erzwungene Passwortwechsel.

Zusätzlich sind beide Pfade im `RateLimitFilter` gedrosselt, ihre Antwort ist
`no-store`, und Key bzw. Token erscheinen im Log nur maskiert.

## Scope-Zwang bei `/token-login`

Ein ApiToken trägt einen `scope`-Claim (`READ`/`EINTRAGEN`/`ADMIN`/`SESSION`).
Für den Session-Aufbau sind nur die Scopes aus
`plaintext.security.token-login.required-scopes` (Default `SESSION`, `ADMIN`)
zugelassen; ein Token **ohne** Claim wird abgelehnt (fail-closed).

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
* `/autologin` ist weiterhin in mehreren Umgebungen aktiv (`MAD_AUTOLOGIN=true`)
  und wird von UI-Test-/Kiosk-Skripten benutzt. Der Rückbau (Entfernen von
  Endpunkt, Spalte und Bestands-Keys) setzt voraus, dass diese Aufrufer vorher auf
  `/token-login` umgestellt sind.
* Key und Token reisen heute als Query-Parameter (Bookmark-/Script-Tauglichkeit)
  und landen damit in Proxy-Logs und Browser-History. Die Umstellung auf
  POST/Header ist eine offene Folgeaufgabe.
