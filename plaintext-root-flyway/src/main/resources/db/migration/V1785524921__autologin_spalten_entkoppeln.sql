-- Karte 30 — Rueckbau von /autologin?key=, Schritt 1 von 2 (Postgres).
--
-- Diese Migration entfernt NICHTS. Sie stellt nur sicher, dass neuer Code (kennt die Spalten
-- nicht mehr) und eine noch laufende alte Instanz (liest sie noch) im Deploy-Fenster
-- nebeneinander arbeiten koennen: die Spalten muessen NULL-bar sein bzw. einen Default haben,
-- sonst scheitert ein INSERT der neuen Version.
--
-- Nach heutigem Stand der Basis-DDL ist beides bereits erfuellt
-- (MY_USER_ENTITY.AUTOLOGIN_KEY ist nullable, SETUP_CONFIG.AUTOLOGIN_ENABLED hat DEFAULT FALSE) —
-- die Migration ist dort ein bewusster No-Op. Sie steht hier, damit die Bedingung auch auf
-- abweichend gewachsenen Instanzen nachweislich gilt, bevor Schritt 2 die Spalten droppt.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'my_user_entity' AND column_name = 'autologin_key') THEN
        EXECUTE 'ALTER TABLE my_user_entity ALTER COLUMN autologin_key DROP NOT NULL';
        EXECUTE 'ALTER TABLE my_user_entity ALTER COLUMN autologin_key SET DEFAULT NULL';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'setup_config' AND column_name = 'autologin_enabled') THEN
        EXECUTE 'ALTER TABLE setup_config ALTER COLUMN autologin_enabled SET DEFAULT false';
    END IF;
END
$$;
