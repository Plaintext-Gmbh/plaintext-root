-- Task 004: generisches Audit-Log fuer destruktive Operationen (wer/wann/was), nutzbar von ALLEN
-- Apps (root/app/guild/iot/schuetu) ueber die geteilte Entity/Repository/Service-Klasse in
-- plaintext-root-common. Diese Migration liegt bewusst im gemeinsamen Modul: Spring Boots
-- spring.flyway.locations=classpath:db/migration scannt ALLE Jars auf dem Klassenpfad (bestaetigtes
-- Muster: die api_token-Migration aus plaintext-admin-apitoken laeuft genauso automatisch in jeder
-- konsumierenden App-DB) - jede App bekommt so ihre EIGENE lokale Kopie dieser Tabelle, ohne dass
-- root selbst zentral aufgerufen werden muss.
--
-- "wer"/"wann" kommen ueber die SuperModel-Standardspalten (created_by/created_date via
-- AuditingEntityListener) - keine eigenen actor-Spalten noetig.
-- PostgreSQL-Syntax (alle Apps in dieser Familie laufen auf Postgres).

CREATE TABLE IF NOT EXISTS destructive_action_audit (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    channel             VARCHAR(20),
    action_type         VARCHAR(100),
    entity_type         VARCHAR(100),
    entity_ids          VARCHAR(2000),
    detail              VARCHAR(2000),
    deleted             BOOLEAN      DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(255),
    last_modified_date  TIMESTAMP,
    mandat              VARCHAR(255),
    tags                VARCHAR(5000)
);

CREATE INDEX IF NOT EXISTS ix_destructive_action_audit_mandat_created
    ON destructive_action_audit (mandat, created_date);
