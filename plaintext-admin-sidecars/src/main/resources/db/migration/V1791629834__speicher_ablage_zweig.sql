-- Karte 1471: Ablage-Art GIT braucht den Zweig. PostgreSQL-Syntax.
ALTER TABLE speicher_ablage ADD COLUMN IF NOT EXISTS zweig VARCHAR(200);
