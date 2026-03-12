CREATE TABLE notification_requests (
    notification_id VARCHAR(36)  PRIMARY KEY,
    trace_id        VARCHAR(36)  NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    body            TEXT         NOT NULL,
    template_code   VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACCEPTED',
    provider        VARCHAR(100),
    reason          TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    notification_id VARCHAR(36)  NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    published       BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    published_at    TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, id) WHERE published = false;
