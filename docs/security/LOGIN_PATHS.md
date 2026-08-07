# Anmeldewege — und die beiden entfernten

Das Framework kennt drei Anmeldewege: **Form-Login**, **OAuth2/OIDC** und
**One-Time-Token**. Alle drei laufen über dieselben Gates (Account-Lockout,
Session-Fixation, zweiter Faktor).

Zwei weitere Wege gab es einmal. Beide sind entfernt, und beide stehen hier
weiterhin, weil ihre Begründung erklärt, warum es keinen dritten Sonderweg
geben sollte.

## Entfernt: `GET /token-login?token=` (Karte 560, 05.08.2026)

Der Endpunkt tauschte ein ApiToken-JWT gegen eine vollwertige Browser-Session,
gedacht für scriptgesteuerte bzw. Kiosk-Aufrufer (UI-Tests, PageTester, ZAP,
Turnier-Kiosk).

**Warum er weg ist:** Er war eine zweite Tür neben der markierten. Ein Token, das
für maschinellen Zugriff ausgestellt wurde, ergab dort eine Browser-Session mit
den **vollen DB-Rollen** seines Besitzers. Karte 309 hat den Weg abgesichert
(Gates, Scope-Zwang, Not-Aus), Karte 544 den Default-Scope von `ADMIN` auf
`SESSION` verengt — aber solange die Tür existiert, kann sie wieder aufgehen.

**Vorbedingung war eine Messung, keine Annahme:** In 30 Tagen kein einziger
erfolgreicher `/token-login` in PROD. Gemessen wurde gegen die Erfolgsmeldung des
`SessionLoginFinalizer` (`"… Session aufgebaut fuer …"`), nicht gegen die
Abwesenheit von Fehlermeldungen — und die Suche vorher an einem Positivfall
geprüft, sonst wäre die Null wertlos gewesen.

**Was mit entfernt wurde:** `TokenLoginController` samt Test,
`plaintext.security.token-login.*` (`TokenLoginProperties`), der `permitAll`- und
der CSRF-Eintrag in `PlaintextSecurityConfig`, der Rate-Limit-Zweig im
`RateLimitFilter` und der Auswahlwert `SESSION` in der Token-Ausstellung.

**Was NICHT entfernt wurde:** Der Scope-**Wert** `SESSION` selbst. Bestehende
Tokens tragen ihn im Claim; sie bleiben gültig und verhalten sich wie
`READ`-Tokens (der `McpBearerTokenFilter` kennt den Wert nicht und vergibt
`SCOPE_READ`). Er wird nur nicht mehr *angeboten* — dieselbe Behandlung wie
`EINTRAGEN` in Karte 545. Damit braucht der Ausbau keine Migration.

**Für Skripte gilt jetzt der Form-Login.** Aufrufer, die bisher
`PLAINTEXT_KIOSK_TOKEN` an `/token-login` gehängt haben, brauchen den regulären
Anmeldeweg; `scripts/lib/plaintext-login.sh` (plaintext-boot) kann beides und
fällt ohne Token bereits auf den Form-Login zurück.

## Entfernt: `GET /autologin?key=`

Statischer Klartext-Key in `my_user_entity.autologin_key`, weder ablaufend noch
widerrufbar. Endpunkt, Spalte und Konfiguration sind entfernt.

## Warum es keinen vierten Weg geben sollte

Beide entfernten Wege hatten dieselbe Bauart: ein Credential in der URL, das
ohne Passworteingabe eine Session eröffnet. Beide waren gut gemeint (Kiosk,
Automation), und bei beiden ergab am Ende ein für einen engen Zweck
ausgestelltes Geheimnis eine Vollsession. Wer einen ähnlichen Bedarf hat, baut
ihn in den bestehenden Wegen ab — nicht daneben.
