CREATE TABLE processed_notifications (
    notification_id VARCHAR(36)  PRIMARY KEY,
    result          VARCHAR(20)  NOT NULL,
    provider        VARCHAR(100),
    reason          TEXT,
    processed_at    TIMESTAMP    NOT NULL DEFAULT now()
);
