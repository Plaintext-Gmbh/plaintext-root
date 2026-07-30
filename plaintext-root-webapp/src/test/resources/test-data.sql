-- Test-Daten für Playwright Integration Tests
-- Diese Datei wird automatisch von Spring Boot beim Starten der Test-Applikation ausgeführt

-- Admin User für Tests
-- Passwort: admin (BCrypt Hash für "admin")
INSERT INTO user_account (id, username, password, enabled, account_non_expired, account_non_locked, credentials_non_expired)
VALUES (1, 'admin', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, true, true, true);

-- Admin Rolle zuweisen
INSERT INTO user_roles (user_id, roles)
VALUES (1, 'ROLE_ADMIN');
