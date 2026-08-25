-- Auftrag Daniel, 25.08.2026: In der Benutzerverwaltung sollen Vor- und Nachname angezeigt
-- werden koennen. Beide gab es am Benutzer bisher nicht.
--
-- ch.plaintext.framework.PlaintextUser deklariert getVorname()/getNachname() seit jeher, hatte
-- aber keine Implementierung - die Erwartung lief ins Leere. Diese Migration schliesst die
-- Luecke an der Stelle, an der die Benutzer wirklich stehen.
--
-- Beide Spalten sind NULL-faehig: bestehende Konten haben keine Namen, und ein NOT NULL wuerde
-- jedes Speichern eines Altkontos blockieren.
ALTER TABLE my_user_entity ADD COLUMN IF NOT EXISTS vorname  VARCHAR(255);
ALTER TABLE my_user_entity ADD COLUMN IF NOT EXISTS nachname VARCHAR(255);
