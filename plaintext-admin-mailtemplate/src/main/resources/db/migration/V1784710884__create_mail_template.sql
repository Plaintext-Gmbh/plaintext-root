-- Admin-editierbare Mailtext-Overrides (Betreff+Body je Mandant+templateKey). Ohne Zeile fuer einen
-- Key gilt der Code-Default (siehe MailTemplateService.render). PostgreSQL-Syntax (root laeuft auf
-- Postgres).
CREATE TABLE IF NOT EXISTS mail_template (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    template_key        VARCHAR(500) NOT NULL,
    betreff             VARCHAR(500) NOT NULL,
    body                VARCHAR(8000) NOT NULL,
    html                BOOLEAN      DEFAULT FALSE,
    deleted             BOOLEAN      DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(255),
    last_modified_date  TIMESTAMP,
    mandat              VARCHAR(255),
    tags                VARCHAR(5000)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_mail_template_mandat_key ON mail_template (mandat, template_key);
