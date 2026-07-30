-- Task #016: Modul-Verwaltung — Ein-/Aus-Zustand der Feature-Module.
-- App-weit pro module_id genau eine Zeile. Läuft in jeder App-DB (Modul ist root-Framework-weit).

CREATE TABLE IF NOT EXISTS module_config (
    id                 BIGSERIAL PRIMARY KEY,
    module_id          VARCHAR(100) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    mandat             VARCHAR(255),
    deleted            BOOLEAN DEFAULT FALSE,
    created_by         VARCHAR(255),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(255),
    last_modified_date TIMESTAMP,
    tags               VARCHAR(5000)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_module_config_module_id ON module_config (module_id);
