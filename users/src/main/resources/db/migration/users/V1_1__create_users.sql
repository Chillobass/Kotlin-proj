CREATE TABLE users
(
    id            uuid PRIMARY KEY,
    email         varchar(254) NOT NULL,
    display_name  varchar(100) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    blocked       boolean      NOT NULL DEFAULT false,
    block_reason  varchar(1000),
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_block_reason CHECK (blocked = (block_reason IS NOT NULL))
);

CREATE UNIQUE INDEX ux_users_email ON users (email);
CREATE INDEX ix_users_role ON users (role);
