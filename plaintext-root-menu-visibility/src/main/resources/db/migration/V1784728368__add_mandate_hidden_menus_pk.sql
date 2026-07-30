-- Reparatur #002: mandate_hidden_menus ist Hibernates @ElementCollection-Tabelle ohne eigene
-- Surrogate-ID (nur config_id + menu_title). Wie bei postkonto_mandate_share nutzt die
-- urspruengliche Migration (V820503558) "CREATE TABLE IF NOT EXISTS" -- existierte die Tabelle
-- in einer Umgebung schon vorher, fehlt dort jeder Schutz gegen Duplikat-Zeilen (INT-Befund
-- vom 22.07.2026-Incident: 97 Tabellen ohne PK). Natuerlicher Schluessel ist das Composite
-- (config_id, menu_title) -- entspricht genau der Java-Seite (Set<String> hiddenMenus je
-- Config), verhindert also zugleich doppelte Ausblendungen desselben Menus.

-- Duplikate zuerst konsolidieren (ctid als Tie-Breaker, da es keine eigene ID gibt).
DELETE FROM mandate_hidden_menus a
    USING mandate_hidden_menus b
WHERE a.ctid < b.ctid
  AND a.config_id = b.config_id
  AND a.menu_title = b.menu_title;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'mandate_hidden_menus'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE mandate_hidden_menus ADD PRIMARY KEY (config_id, menu_title);
    END IF;
END $$;
