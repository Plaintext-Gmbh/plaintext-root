-- Karte 1245: Zustand der Watch-Ansicht je Benutzer.
--
-- WARUM IN DER DATENBANK UND NICHT IN DER SESSION: Die Watch-Ansicht wird ueber einen Link
-- geoeffnet, oft auf einem anderen Geraet als zuvor. Eine Session verloere die Position genau
-- dann, wenn sie gebraucht wird.
--
-- PostgreSQL. Spalten von SuperModel: id, mandat, deleted, created_by, created_date,
-- last_modified_by, last_modified_date, tags.
CREATE TABLE watch_user_state (
    id                 BIGSERIAL PRIMARY KEY,
    benutzer           VARCHAR(255) NOT NULL,
    aktuelle_seite     VARCHAR(64),
    testseite_aktiv    BOOLEAN NOT NULL DEFAULT FALSE,
    mandat             VARCHAR(255),
    deleted            BOOLEAN DEFAULT FALSE,
    created_by         VARCHAR(255),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(255),
    last_modified_date TIMESTAMP,
    tags               VARCHAR(5000)
);

-- Ein Zustand je Benutzer; der Teilindex laesst weich geloeschte Zeilen zu.
CREATE UNIQUE INDEX uq_watch_user_state_benutzer
    ON watch_user_state (benutzer) WHERE deleted = FALSE;
