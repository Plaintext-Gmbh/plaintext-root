-- Karte 307, K2.3: Selbstservice-Einmal-Tokens (Passwort-Reset, Registrierung) nur noch GEHASHT
-- speichern (SHA-256, Muster MAGIC_LINK_TOKEN.TOKEN_HASH). Bisher stand der Klartext-Token in Spalte
-- TOKEN -> bei DB-/Backup-/Log-Leak oder (vor K2.1/K2.2) ueber die Datenverwaltung direkt als
-- Konto-Uebernahme verwendbar.
--
-- Bestehende (kurzlebige) Klartext-Tokens sind serverseitig NICHT in ihren Hash umrechenbar und werden
-- daher invalidiert (geloescht) — offene Links werden ungueltig, Nutzer fordern bei Bedarf einen neuen an.
--
-- HSQLDB- und Postgres-parseable: DELETE + DROP/ADD COLUMN + CREATE UNIQUE INDEX (kein dialekt-
-- spezifisches RENAME COLUMN). Reihenfolge: erst leeren, dann Spalte tauschen, dann Unique-Index.
-- ddl-auto=none -> das Schema gehoert vollstaendig Flyway.

DELETE FROM PASSWORD_RESET_TOKEN;
ALTER TABLE PASSWORD_RESET_TOKEN DROP COLUMN TOKEN;
ALTER TABLE PASSWORD_RESET_TOKEN ADD COLUMN TOKEN_HASH VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX UX_PWRESET_TOKEN_HASH ON PASSWORD_RESET_TOKEN (TOKEN_HASH);

DELETE FROM REGISTRATION_TOKEN;
ALTER TABLE REGISTRATION_TOKEN DROP COLUMN TOKEN;
ALTER TABLE REGISTRATION_TOKEN ADD COLUMN TOKEN_HASH VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX UX_REGISTRATION_TOKEN_HASH ON REGISTRATION_TOKEN (TOKEN_HASH);
