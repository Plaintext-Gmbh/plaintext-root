-- Zustandsbericht 29.08.2026 (H3-Nebenbefund): Das TOTP-Secret lag im Klartext, die
-- Recovery-Codes daneben gehasht. Ab jetzt schreibt TotpSecretConverter das Secret als
-- "enc1:" + Base64(IV || AES-GCM) — fuer ein 32-Zeichen-Secret ~85 Zeichen, VARCHAR(64) war zu
-- knapp. Bestehende Klartext-Werte bleiben lesbar und werden beim naechsten Schreiben
-- verschluesselt; darum keine Daten-Migration.
ALTER TABLE MY_USER_ENTITY ALTER COLUMN TOTP_SECRET TYPE VARCHAR(255);
