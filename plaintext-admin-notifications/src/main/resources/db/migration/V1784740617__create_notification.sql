-- Zentrales In-App-Benachrichtigungssystem (#001 notification-system-in-app, Teil B von #018).
-- PostgreSQL-Syntax (root laeuft auf Postgres).
CREATE TABLE IF NOT EXISTS notification (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    empfaenger_username VARCHAR(255) NOT NULL,
    typ                 VARCHAR(200) NOT NULL,
    titel               VARCHAR(500) NOT NULL,
    text                VARCHAR(2000) NOT NULL,
    link                VARCHAR(500),
    gelesen_am          TIMESTAMP,
    quelle_entity_type  VARCHAR(200),
    quelle_entity_id    VARCHAR(100),
    deleted             BOOLEAN      DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(255),
    last_modified_date  TIMESTAMP,
    mandat              VARCHAR(255),
    tags                VARCHAR(5000)
);

-- Haeufigste Zugriffe: Inbox eines Users (neueste zuerst) und Ungelesen-Zaehler fuer die Topbar-Glocke.
CREATE INDEX IF NOT EXISTS ix_notification_empfaenger_created
    ON notification (empfaenger_username, created_date DESC);
CREATE INDEX IF NOT EXISTS ix_notification_empfaenger_ungelesen
    ON notification (empfaenger_username, gelesen_am)
    WHERE gelesen_am IS NULL;
-- Fuer den Aufraeum-Cron (gelesene aelter als 90 Tage).
CREATE INDEX IF NOT EXISTS ix_notification_gelesen_am
    ON notification (gelesen_am)
    WHERE gelesen_am IS NOT NULL;
