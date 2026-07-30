-- Ausgehende Webhooks: Admin-verwaltete Ziel-Endpoints + Delivery-Log. PostgreSQL-Syntax (root
-- laeuft auf Postgres).
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id                          BIGINT        NOT NULL PRIMARY KEY,
    name                        VARCHAR(200)  NOT NULL,
    url                         VARCHAR(1000) NOT NULL,
    enabled                     BOOLEAN       NOT NULL DEFAULT TRUE,
    event_types                 VARCHAR(2000) NOT NULL,
    signing_secret_encrypted    VARCHAR(2000) NOT NULL,
    deleted                     BOOLEAN       DEFAULT FALSE,
    created_by                  VARCHAR(255),
    created_date                TIMESTAMP,
    last_modified_by            VARCHAR(255),
    last_modified_date          TIMESTAMP,
    mandat                      VARCHAR(255),
    tags                        VARCHAR(5000)
);

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id                  BIGINT        NOT NULL PRIMARY KEY,
    endpoint_id         BIGINT        NOT NULL,
    event_type          VARCHAR(200)  NOT NULL,
    payload             VARCHAR(8000),
    status              VARCHAR(32)   NOT NULL,
    attempts            INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP,
    http_status         INTEGER,
    response_snippet    VARCHAR(2000),
    deleted             BOOLEAN       DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(255),
    last_modified_date  TIMESTAMP,
    mandat              VARCHAR(255),
    tags                VARCHAR(5000)
);

CREATE INDEX IF NOT EXISTS ix_webhook_delivery_endpoint ON webhook_delivery (endpoint_id);
CREATE INDEX IF NOT EXISTS ix_webhook_delivery_retry ON webhook_delivery (mandat, status, next_attempt_at);
