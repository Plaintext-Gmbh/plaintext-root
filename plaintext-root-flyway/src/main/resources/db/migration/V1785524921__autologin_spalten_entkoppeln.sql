-- Karte 30 — Rueckbau von /autologin?key=, Schritt 1 von 2 (Postgres).
--
-- Diese Migration entfernt NICHTS. Sie loest nur die Kopplung, damit der neue Code (ohne die
-- Felder MyUserEntity.autologinKey und SetupConfig.autologinEnabled) und eine noch laufende alte
-- Instanz GLEICHZEITIG arbeiten koennen:
--   * alte Instanz liest die Spalten weiter  -> darf also noch nicht gedroppt werden
--   * neue Instanz schreibt sie nicht mehr   -> braucht Default bzw. NULL-Erlaubnis beim INSERT
--
-- Der eigentliche DROP folgt in einer zweiten Migration, NACHDEM der neue Code ueberall
-- ausgerollt ist.

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
