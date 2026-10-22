CREATE TABLE tenders
(
    id              uuid PRIMARY KEY,
    owner_id        uuid           NOT NULL REFERENCES users (id),
    title           varchar(200)   NOT NULL,
    description     varchar(5000)  NOT NULL,
    category        varchar(32)    NOT NULL,
    max_price       numeric(19, 2) NOT NULL CHECK (max_price > 0),
    deadline        timestamptz    NOT NULL,
    weight_price    smallint       NOT NULL CHECK (weight_price >= 0),
    weight_delivery smallint       NOT NULL CHECK (weight_delivery >= 0),
    weight_warranty smallint       NOT NULL CHECK (weight_warranty >= 0),
    status          varchar(16)    NOT NULL
        CHECK (status IN ('ON_REVIEW', 'REJECTED', 'OPEN', 'CANCELLED', 'COMPLETED', 'FAILED')),
    status_reason   varchar(1100),
    moderated_by    uuid REFERENCES users (id),
    published_at    timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL,
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT chk_tenders_weights_sum CHECK (weight_price + weight_delivery + weight_warranty = 100),
    CONSTRAINT chk_tenders_open_is_published CHECK (status <> 'OPEN' OR published_at IS NOT NULL),
    CONSTRAINT chk_tenders_closed_at CHECK ((status IN ('COMPLETED', 'FAILED')) = (closed_at IS NOT NULL))
);

CREATE INDEX ix_tenders_status_deadline ON tenders (status, deadline);
CREATE INDEX ix_tenders_category_status ON tenders (category, status);
CREATE INDEX ix_tenders_owner ON tenders (owner_id, created_at DESC);
CREATE INDEX ix_tenders_open_deadline ON tenders (deadline) WHERE status = 'OPEN';
