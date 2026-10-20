create table merchants (
    id          varchar(64)  primary key,
    name        varchar(128) not null,
    created_at  timestamptz  not null
);

-- Only the SHA-256 of each key is stored; the key itself is shown to the merchant once.
create table api_keys (
    id          uuid         primary key,
    merchant_id varchar(64)  not null references merchants (id),
    key_hash    char(64)     not null unique,
    key_prefix  varchar(16)  not null,
    created_at  timestamptz  not null,
    revoked_at  timestamptz
);
create index api_keys_merchant_idx on api_keys (merchant_id);
