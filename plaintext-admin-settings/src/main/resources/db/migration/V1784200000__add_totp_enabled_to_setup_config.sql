-- TOTP/2FA-Feature-Toggle pro Mandant (DB-gestützt, ergänzt die statische Property plaintext.security.totp.enabled)
ALTER TABLE SETUP_CONFIG ADD COLUMN IF NOT EXISTS TOTP_ENABLED BOOLEAN DEFAULT FALSE NOT NULL;
