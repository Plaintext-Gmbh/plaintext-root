-- Karte 1476: Freigabe-Links ohne Anmeldung, allgemein fuer alle Module (FreigabeQuelle).
-- Das Token steht nur als SHA-256 da, nie im Klartext. Widerruf = deleted.
-- PostgreSQL. Spalten von SuperModel: id, mandat, deleted, created_by, created_date,
-- last_modified_by, last_modified_date, tags.
CREATE TABLE IF NOT EXISTS freigabe_link (
    id                  BIGSERIAL PRIMARY KEY,
    typ                 VARCHAR(40)  NOT NULL,
    objekt_id           BIGINT       NOT NULL,
    teil                VARCHAR(200),
    recht               VARCHAR(2)   NOT NULL,
    token_hash          VARCHAR(64)  NOT NULL,
    zweck               VARCHAR(500),
    gueltig_bis         DATE,
    aufrufe             INTEGER      NOT NULL DEFAULT 0,
    zuletzt_aufgerufen  TIMESTAMP WITH TIME ZONE,
    mandat              VARCHAR(255) NOT NULL,
    deleted             BOOLEAN DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(255),
    last_modified_date  TIMESTAMP,
    tags                VARCHAR(5000)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_freigabe_link_token_hash ON freigabe_link (token_hash);
CREATE INDEX IF NOT EXISTS idx_freigabe_link_objekt ON freigabe_link (mandat, typ, objekt_id);
