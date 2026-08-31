# Login paths — and the two that were removed

The framework knows three login paths: **form login**, **OAuth2/OIDC** and **one-time token**.
All three run through the same gates (account lockout, session fixation, second factor).

There used to be two more. Both have been removed, and both are still documented here, because
their rationale explains why there should not be a third special path.

## Removed: `GET /token-login?token=` (card 560, 05.08.2026)

The endpoint exchanged an ApiToken JWT for a fully fledged browser session, intended for
script-driven or kiosk callers (UI tests, PageTester, ZAP, tournament kiosk).

**Why it is gone:** it was a second door next to the marked one. A token that had been issued for
machine access produced a browser session there, carrying the **full database roles** of its
owner. Card 309 secured the path (gates, mandatory scope, emergency stop), card 544 narrowed the
default scope from `ADMIN` to `SESSION` — but as long as the door exists, it can be opened again.

**The precondition was a measurement, not an assumption:** not a single successful
`/token-login` in PROD over 30 days. The measurement was taken against the success message of the
`SessionLoginFinalizer` (`"… Session aufgebaut fuer …"`), not against the absence of error
messages — and the search itself was verified against a positive case beforehand, otherwise the
zero would have been worthless.

**What was removed along with it:** `TokenLoginController` and its test,
`plaintext.security.token-login.*` (`TokenLoginProperties`), the `permitAll` entry and the CSRF
entry in `PlaintextSecurityConfig`, the rate-limit branch in the `RateLimitFilter`, and the
`SESSION` option in token issuance.

**What was NOT removed:** the scope **value** `SESSION` itself. Existing tokens carry it in their
claim; they remain valid and behave like `READ` tokens (the `McpBearerTokenFilter` does not know
the value and grants `SCOPE_READ`). It is merely no longer *offered* — the same treatment as
`EINTRAGEN` in card 545. The removal therefore needs no migration.

**Scripts now use the form login.** Callers that used to append `PLAINTEXT_KIOSK_TOKEN` to
`/token-login` need the regular login path; `scripts/lib/plaintext-login.sh` (plaintext-boot) can
do both and already falls back to the form login when no token is present.

## Removed: `GET /autologin?key=`

A static plaintext key in `my_user_entity.autologin_key`, neither expiring nor revocable.
Endpoint, column and configuration have been removed.

## Why there should be no fourth path

Both removed paths were built the same way: a credential in the URL that opens a session without
a password being entered. Both were well meant (kiosk, automation), and in both of them a secret
issued for a narrow purpose ended up yielding a full session. Anyone with a similar need should
satisfy it inside the existing paths — not next to them.
