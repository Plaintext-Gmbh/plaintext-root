# TOTP / Zwei-Faktor-Authentifizierung (2FA)

Optionaler zweiter Faktor per **Authenticator-App** (TOTP, RFC 6238) für die
eigene Benutzerverwaltung von Plaintext Root. Betrifft **ausschließlich lokale
Passwort-User** (`MY_USER_ENTITY`). OIDC-/Keycloak-only-User (`passwordless`)
sind grundsätzlich nicht betroffen – deren zweiter Faktor liegt beim IdP.

> **Sicherheits-Default: AUS.** Das Feature ist über eine Property
> gate-kontrolliert und standardmäßig deaktiviert. Ohne Aktivierung ändert sich
> für niemanden etwas: kein zweiter Login-Schritt, keine Profil-Option, kein
> aktiver Verifikations-Gate.

## Aktivieren

```properties
# application.yml / Umgebungsvariable
plaintext.security.totp.enabled=true          # PLAINTEXT_SECURITY_TOTP_ENABLED
```

Optionale Feineinstellungen (mit Defaults):

| Property | Default | Bedeutung |
| --- | --- | --- |
| `plaintext.security.totp.enabled` | `false` | Master-Schalter für das gesamte Feature |
| `plaintext.security.totp.issuer` | `Plaintext` | Aussteller-Name in der Authenticator-App (Teil der `otpauth://`-URI) |
| `plaintext.security.totp.allowed-time-period-discrepancy` | `1` | Toleranz in 30-Sekunden-Fenstern (±1 gegen Uhr-Drift, RFC-6238-Empfehlung) |
| `plaintext.security.totp.recovery-code-count` | `10` | Anzahl der bei der Einrichtung erzeugten Einmal-Recovery-Codes |
| `plaintext.security.totp.enforce-for-roles` | `[]` | **Platzhalter** (noch nicht durchgesetzt), siehe *Erzwingen* unten |

## Einrichten (Self-Service)

Im eigenen Profil (`myuser.xhtml`) erscheint bei aktivem Feature der Bereich
**„Zwei-Faktor-Authentifizierung"** (nur für nicht-`passwordless`-User):

1. **Einrichten** klicken → es wird ein frisches Base32-Secret + QR-Code erzeugt.
   2FA ist zu diesem Zeitpunkt **noch nicht** aktiv.
2. QR-Code mit der App scannen (Google Authenticator, Aegis, 1Password, …) oder
   den Schlüssel manuell eingeben.
3. Den von der App angezeigten **6-stelligen Code** zur Bestätigung eingeben und
   **Aktivieren** klicken. Erst jetzt wird 2FA scharf – niemand sperrt sich durch
   versehentliches Aktivieren aus.
4. Die **Recovery-Codes** werden **genau einmal** angezeigt. Sicher aufbewahren!

**Deaktivieren:** im selben Bereich, **mit Passwort-Bestätigung** (verhindert,
dass eine fremde offene Session den zweiten Faktor still abschaltet). Secret und
Recovery-Codes werden dabei gelöscht.

## Anmelde-Flow (Zwei-Schritt)

```
POST /login  (username + password + _csrf)
   │
   ├─ Passwort falsch ──────────────► /login.html?error=true
   │
   └─ Passwort ok
        │
        ├─ Feature AUS  ODER  User ohne totpEnabled ─► Startseite (unverändert)
        │
        └─ Feature AN  UND  User totpEnabled=true
             │  (volle Authentication wird NICHT gesetzt,
             │   sondern als "pending" in die Session gelegt;
             │   SecurityContext bleibt leer/anonym)
             ▼
           302 /login/totp   (Code-Eingabe: 6-stellig ODER Recovery-Code)
             │
             ├─ Code falsch ─────────► /login/totp?error=totp_invalid  (Rate-Limit greift)
             ├─ Lockout ─────────────► /login.html?error=totp_locked
             └─ Code gültig ─────────► volle Authentication in SecurityContext ► Startseite
```

## Recovery-Codes

- `recovery-code-count` Stück, Format `XXXX-XXXX-XXXX` (ohne leicht verwechselbare
  Zeichen 0/O/1/I/L).
- Werden **gehasht** gespeichert (SHA-256, hex) – der Klartext liegt nie in der DB
  und wird nur einmalig bei der Einrichtung angezeigt.
- **Einmal gültig** (one-time): ein eingelöster Code wird atomar aus dem
  gespeicherten Set entfernt und funktioniert danach nicht mehr.
- Eingabe ist bindestrich-/whitespace-/case-unabhängig.

## Sicherheits-Invarianten

- **Kein Bypass.** Solange der zweite Faktor aussteht, steht die echte
  Authentication *nicht* im `SecurityContext` (nur „pending" in der Session).
  Jeder Zugriff auf geschützte Ressourcen wird abgewiesen. Wer nur das Passwort
  hat, kommt ohne gültigen Code nicht durch (Test:
  `TotpLoginIntegrationTest.totpUser_wirdNachPasswortAufTotpSchrittGeleitet_undErstMitCodeRein`).
- **Kein Aussperren.** Recovery-Codes stellen sicher, dass ein legitimer User
  auch ohne Authenticator wieder hineinkommt. Aktivierung erfordert einen
  bestätigten Code (kein versehentliches Scharfschalten).
- **`/login/totp` ist „cold-call"-sicher.** Ohne pending-Session-Zustand
  (den nur der Erfolgs-Handler nach korrektem Passwort setzt) meldet der Endpunkt
  niemanden an – Redirect zurück zum Login.
- **Gilt für alle Anmeldewege.** Das Gate hängt am Erfolgs-Handler, nicht am einzelnen
  Login-Weg – deshalb greift es auch für neue Wege automatisch. Der Sonderweg `/token-login`
  baute den `SecurityContext` einst selbst und meldete TOTP-User ohne zweiten Faktor an; er ist
  seit Karte 560 entfernt. Siehe `docs/security/LOGIN_PATHS.md`.
- **Rate-Limit.** Fehlversuche am zweiten Faktor laufen über den bestehenden
  `AccountLockoutService` (Brute-Force-Schutz).
- **CSRF bleibt aktiv** auf der TOTP-Seite (Token im Formular).

## Erzwingen (ROOT/ADMIN) – Folge-PR

Die Property `plaintext.security.totp.enforce-for-roles` ist als **Platzhalter**
vorhanden, aber **noch nicht durchgesetzt**. Ein Folge-PR kann darauf aufbauen,
um User bestimmter Rollen (z. B. `ADMIN`) beim Login ohne eingerichtetes TOTP
zwingend in die Einrichtung zu leiten. Diese Erzwingung ist bewusst nicht Teil
dieses PRs (Scope + PROD-Risiko).

## Datenmodell / Migration

`MY_USER_ENTITY` wird additiv erweitert (Migration
`V1784100000__user_add_totp_2fa.sql`, HSQLDB-kompatibel):

| Spalte | Typ | Bedeutung |
| --- | --- | --- |
| `TOTP_SECRET` | `VARCHAR(64)` | Base32-Secret, `NULL` solange nicht eingerichtet |
| `TOTP_ENABLED` | `BOOLEAN DEFAULT FALSE` | erst `TRUE` nach bestätigter Einrichtung |
| `RECOVERY_CODES` | `VARCHAR(2000)` | XStream-serialisiertes Set gehashter Recovery-Codes |

## Bibliothek

[`dev.samstevens.totp:totp:1.7.1`](https://github.com/samdjstevens/java-totp) –
Standard-Spring-TOTP-Lib: Base32-Secret, `otpauth://`-URI, QR-Code (PNG-Data-URI
via ZXing) und zeitfenster-tolerante Code-Verifikation. Der optionale
NTP-`commons-net`-Zeitprovider ist ausgeschlossen (wir nutzen `SystemTimeProvider`).
