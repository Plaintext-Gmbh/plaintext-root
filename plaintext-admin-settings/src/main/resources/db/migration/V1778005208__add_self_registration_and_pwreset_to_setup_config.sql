-- Add per-mandant toggles for self-registration and password-reset-via-link.
-- Both default to FALSE so existing deployments do not silently expose these
-- flows; operators must opt in via the Setup screen.
ALTER TABLE SETUP_CONFIG ADD COLUMN IF NOT EXISTS SELF_REGISTRATION_ENABLED BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE SETUP_CONFIG ADD COLUMN IF NOT EXISTS PASSWORD_RESET_LINK_ENABLED BOOLEAN DEFAULT FALSE NOT NULL;
