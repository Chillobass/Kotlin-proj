CREATE TABLE notifications
(
    id           uuid PRIMARY KEY,
    recipient_id uuid          NOT NULL REFERENCES users (id),
    type         varchar(40)   NOT NULL,
    tender_id    uuid REFERENCES tenders (id),
    message      varchar(2000) NOT NULL,
    created_at   timestamptz   NOT NULL,
    read_at      timestamptz
);

CREATE INDEX ix_notifications_recipient ON notifications (recipient_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;
