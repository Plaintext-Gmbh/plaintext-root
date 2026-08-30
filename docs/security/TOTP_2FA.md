# TOTP / two-factor authentication (2FA)

Optional second factor via an **authenticator app** (TOTP, RFC 6238) for Plaintext Root's own
user administration. It concerns **local password users only** (`MY_USER_ENTITY`).
OIDC/Keycloak-only users (`passwordless`) are fundamentally unaffected – their second factor
lives with the IdP.

> **Security default: OFF.** The feature is gated by a property and disabled by default. Without
> activation nothing changes for anyone: no second login step, no profile option, no active
> verification gate.

## Enabling it

```properties
# application.yml / environment variable
plaintext.security.totp.enabled=true          # PLAINTEXT_SECURITY_TOTP_ENABLED
```

Optional fine-tuning (with defaults):

| Property | Default | Meaning |
| --- | --- | --- |
| `plaintext.security.totp.enabled` | `false` | master switch for the whole feature |
| `plaintext.security.totp.issuer` | `Plaintext` | issuer name in the authenticator app (part of the `otpauth://` URI) |
| `plaintext.security.totp.allowed-time-period-discrepancy` | `1` | tolerance in 30-second windows (±1 against clock drift, the RFC 6238 recommendation) |
| `plaintext.security.totp.recovery-code-count` | `10` | number of one-time recovery codes generated during setup |
| `plaintext.security.totp.enforce-for-roles` | `[]` | **placeholder** (not enforced yet), see *Enforcing* below |

## Setting it up (self-service)

When the feature is active, the section **"Zwei-Faktor-Authentifizierung"** appears in your own
profile (`myuser.xhtml`) — for non-`passwordless` users only:

1. Click **Einrichten** ("set up") → a fresh Base32 secret and a QR code are generated.
   At this point 2FA is **not yet** active.
2. Scan the QR code with the app (Google Authenticator, Aegis, 1Password, …) or enter the key
   manually.
3. Enter the **6-digit code** shown by the app as confirmation and click **Aktivieren**
   ("activate"). Only now does 2FA go live – nobody locks themselves out by activating it by
   accident.
4. The **recovery codes** are shown **exactly once**. Store them safely!

**Disabling:** in the same section, **with password confirmation** (this prevents somebody
else's open session from silently switching the second factor off). The secret and the recovery
codes are deleted in the process.

## Login flow (two steps)

```
POST /login  (username + password + _csrf)
   │
   ├─ wrong password ───────────────► /login.html?error=true
   │
   └─ password ok
        │
        ├─ feature OFF  OR  user without totpEnabled ─► home page (unchanged)
        │
        └─ feature ON  AND  user totpEnabled=true
             │  (the full Authentication is NOT set;
             │   it is placed in the session as "pending" instead,
             │   the SecurityContext stays empty/anonymous)
             ▼
           302 /login/totp   (code entry: 6 digits OR recovery code)
             │
             ├─ wrong code ──────────► /login/totp?error=totp_invalid  (rate limit applies)
             ├─ lockout ─────────────► /login.html?error=totp_locked
             └─ valid code ──────────► full Authentication in SecurityContext ► home page
```

## Recovery codes

- `recovery-code-count` of them, format `XXXX-XXXX-XXXX` (without the easily confused
  characters 0/O/1/I/L).
- They are stored **hashed** (SHA-256, hex) – the plaintext never lands in the database and is
  displayed only once, at setup time.
- **Valid once** (one-time): a redeemed code is removed atomically from the stored set and does
  not work afterwards.
- Input is insensitive to hyphens, whitespace and case.

## Security invariants

- **No bypass.** As long as the second factor is outstanding, the real Authentication is *not*
  in the `SecurityContext` (only "pending" in the session). Every access to a protected resource
  is rejected. Whoever has the password alone does not get through without a valid code (test:
  `TotpLoginIntegrationTest.totpUser_wirdNachPasswortAufTotpSchrittGeleitet_undErstMitCodeRein`).
- **No lockout.** Recovery codes make sure a legitimate user gets back in even without their
  authenticator. Activation requires a confirmed code (no accidental arming).
- **`/login/totp` is safe against "cold calls".** Without the pending session state (which only
  the success handler sets after a correct password) the endpoint logs nobody in – it redirects
  back to the login.
- **Applies to every login path.** The gate hangs off the success handler, not off the individual
  login path – which is why it automatically covers new paths as well. The special path
  `/token-login` once built the `SecurityContext` itself and logged TOTP users in without a
  second factor; it has been removed as of card 560. See `docs/security/LOGIN_PATHS.md`.
- **Rate limit.** Failed attempts at the second factor go through the existing
  `AccountLockoutService` (brute-force protection).
- **CSRF stays active** on the TOTP page (token in the form).

## Enforcing (ROOT/ADMIN) – follow-up PR

The property `plaintext.security.totp.enforce-for-roles` exists as a **placeholder** but is
**not enforced yet**. A follow-up PR can build on it in order to force users of certain roles
(`ADMIN`, for example) who log in without TOTP configured into the setup flow. That enforcement
is deliberately not part of this PR (scope + PROD risk).

## Data model / migration

`MY_USER_ENTITY` is extended additively (migration `V1784100000__user_add_totp_2fa.sql`,
PostgreSQL (like every migration in this project — there is no HSQLDB here)):

| Column | Type | Meaning |
| --- | --- | --- |
| `TOTP_SECRET` | `VARCHAR(64)` | Base32 secret, `NULL` as long as it is not set up |
| `TOTP_ENABLED` | `BOOLEAN DEFAULT FALSE` | only `TRUE` after confirmed setup |
| `RECOVERY_CODES` | `VARCHAR(2000)` | XStream-serialized set of hashed recovery codes |

## Library

[`dev.samstevens.totp:totp:1.7.1`](https://github.com/samdjstevens/java-totp) –
`dev.samstevens.totp` 1.7.1, a plain Java library with no Spring ties: Base32 secret, `otpauth://` URI, QR code (PNG data URI via
ZXing) and code verification that tolerates time-window drift. The optional NTP `commons-net`
time provider is excluded (we use `SystemTimeProvider`).
