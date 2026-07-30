-- Add OIDC subject ID column to user table for Keycloak/OIDC linking
ALTER TABLE MY_USER_ENTITY ADD COLUMN OIDC_SUBJECT VARCHAR(255);
