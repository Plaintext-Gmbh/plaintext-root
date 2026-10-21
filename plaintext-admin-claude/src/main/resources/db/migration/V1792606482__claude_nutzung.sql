-- Karte 1560: eigener Verbrauch der Claude-API je Tag, aufrufendem Modul und Modell.
-- Ohne Mandat: der API-Schluessel gilt pro Installation, nicht pro Mandat. Eine Zeile je
-- (tag, quelle, modell), hochgezaehlt per Upsert — die Tabelle bleibt klein (Tage x Module x Modelle).
CREATE TABLE IF NOT EXISTS claude_nutzung (
    tag            DATE         NOT NULL,
    quelle         VARCHAR(200) NOT NULL,
    modell         VARCHAR(100) NOT NULL,
    aufrufe        BIGINT       NOT NULL DEFAULT 0,
    fehler         BIGINT       NOT NULL DEFAULT 0,
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    cache_tokens   BIGINT       NOT NULL DEFAULT 0,
    latenz_ms      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (tag, quelle, modell)
);
