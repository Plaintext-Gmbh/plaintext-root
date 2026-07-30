-- TOTP / Zwei-Faktor-Authentifizierung fuer lokale (Passwort-)User.
-- Additive Spalten auf MY_USER_ENTITY; Feature ist per Property
-- plaintext.security.totp.enabled=false standardmaessig AUS.
-- HSQLDB-kompatible Syntax (Tests laufen gegen Postgres via Testcontainers,
-- die Flyway-Skripte muessen aber auch HSQLDB-parsebar bleiben).
--
-- TOTP_SECRET:    Base32-kodiertes RFC-6238-Secret, NULL solange 2FA nicht eingerichtet.
-- TOTP_ENABLED:   erst TRUE, nachdem der User bei Einrichtung einen gueltigen Code bestaetigt hat.
-- RECOVERY_CODES: XStream-serialisiertes Set gehashter (SHA-256) Einmal-Recovery-Codes.
ALTER TABLE MY_USER_ENTITY ADD COLUMN TOTP_SECRET VARCHAR(64);
ALTER TABLE MY_USER_ENTITY ADD COLUMN TOTP_ENABLED BOOLEAN DEFAULT FALSE;
ALTER TABLE MY_USER_ENTITY ADD COLUMN RECOVERY_CODES VARCHAR(2000);
