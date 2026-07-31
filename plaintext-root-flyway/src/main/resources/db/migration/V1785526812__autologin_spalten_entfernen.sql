-- Karte 30 — Rueckbau von /autologin?key=, Schritt 2 von 2 (Postgres).
--
-- ACHTUNG REIHENFOLGE: Diese Migration darf erst laufen, wenn der Code aus
-- V1785524921 (Endpunkt entfernt, Felder aus MyUserEntity/SetupConfig raus) UEBERALL
-- ausgerollt ist. Laeuft sie frueher, bricht jede noch laufende alte Instanz, die die
-- Spalten liest.
--
-- Mit den Spalten verschwinden auch die letzten neun gesetzten Autologin-Keys
-- (app 7, schuetu 1, guild 1). Der Verlust dieser Login-Moeglichkeit ist bewusst
-- in Kauf genommen; Ersatz ist der Form-Login bzw. /token-login.

ALTER TABLE my_user_entity DROP COLUMN IF EXISTS autologin_key;
ALTER TABLE setup_config DROP COLUMN IF EXISTS autologin_enabled;
