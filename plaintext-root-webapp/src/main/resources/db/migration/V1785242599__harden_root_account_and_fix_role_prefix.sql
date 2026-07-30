-- Karte 306: Default-Root-Account haerten + Rollen-Praefix-Bug korrigieren.
--
-- HSQLDB-parseable Syntax (wie die Sibling-Skripte, z.B. V1784100000): plain ADD COLUMN sowie
-- REPLACE/LIKE existieren in Postgres UND HSQLDB identisch. Flyway garantiert ueber die
-- schema_history den Einmal-Lauf (kein IF NOT EXISTS noetig; HSQLDB kennt es bei ADD COLUMN nicht).
--
-- (1) MUST_CHANGE_PASSWORD: erzwingt den Passwortwechsel beim ersten Login. Der Root-Bootstrap-User
--     bekommt statt des frueheren statischen "root" ein zufaelliges Einmal-Initialpasswort (im
--     Startup-Log) und dieses Flag=true. Bestandsrows erhalten per Spalten-Default FALSE -> keine
--     Bestands-User sind betroffen.
ALTER TABLE MY_USER_ENTITY ADD COLUMN MUST_CHANGE_PASSWORD BOOLEAN DEFAULT FALSE;

-- (2) Rollen-Praefix-Migration: selbst-registrierte User hatten faelschlich die Rolle "ROLE_USER"
--     gespeichert. MyUserDetailsService praefixt den nackten Rollennamen beim Login zu "ROLE_",
--     wodurch die Authority zu "ROLE_ROLE_USER" wurde (wirkungslos). Konvention ist der nackte
--     Name "user" (-> Authority "ROLE_USER"). Wir ziehen den Bestand nach; der Code speichert
--     kuenftig ebenfalls "user" (RegistrationService).
--     Rollen sind XStream-serialisiert (<string>...</string>); die vollstaendige Tag-Grenze macht
--     das REPLACE eindeutig (kein Teil-Match, "ROLE_USER" innerhalb anderer Werte bleibt unberuehrt).
--     "ROLE_USER" (9) -> "user" (4) verkuerzt den Wert -> keine VARCHAR(255)-Ueberlauf-Gefahr.
UPDATE MY_USER_ENTITY
SET ROLES = REPLACE(ROLES, '<string>ROLE_USER</string>', '<string>user</string>')
WHERE ROLES LIKE '%<string>ROLE_USER</string>%';
