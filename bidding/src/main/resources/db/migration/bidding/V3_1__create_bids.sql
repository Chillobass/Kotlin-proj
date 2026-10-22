CREATE TABLE bids
(
    id              uuid PRIMARY KEY,
    tender_id       uuid           NOT NULL REFERENCES tenders (id),
    participant_id  uuid           NOT NULL REFERENCES users (id),
    price           numeric(19, 2) NOT NULL CHECK (price > 0),
    delivery_days   integer        NOT NULL CHECK (delivery_days >= 1),
    warranty_months integer        NOT NULL CHECK (warranty_months >= 0),
    comment         varchar(2000),
    status          varchar(16)    NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    submitted_at    timestamptz    NOT NULL,
    created_at      timestamptz    NOT NULL,
    final_place     integer CHECK (final_place >= 1),
    final_score     numeric(7, 2),
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT chk_bids_result CHECK ((final_place IS NULL) = (final_score IS NULL))
);

-- Business rule "one active bid per participant per tender", enforced even under concurrent requests.
CREATE UNIQUE INDEX ux_bids_one_active_per_participant
    ON bids (tender_id, participant_id) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_bids_final_place ON bids (tender_id, final_place) WHERE final_place IS NOT NULL;

CREATE INDEX ix_bids_tender_status ON bids (tender_id, status);
CREATE INDEX ix_bids_participant ON bids (participant_id, created_at DESC);
