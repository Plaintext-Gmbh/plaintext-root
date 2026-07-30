-- PR C: GLOBAL-Systemmailkonto für Auth-Mails (Passwort-Reset/Login-Link/Registrierung) auswählbar machen.
-- Long-Referenz auf ein Mailbox-Konto (mail_account.id, Scope GLOBAL) – analog zu OIDC_AUTO_REDIRECT_CONFIG_ID.
-- HSQLDB-/PostgreSQL-kompatibel.
ALTER TABLE SETUP_CONFIG ADD COLUMN IF NOT EXISTS SYSTEM_MAIL_ACCOUNT_ID BIGINT;
