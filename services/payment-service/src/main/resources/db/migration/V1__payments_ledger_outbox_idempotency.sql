create table payments (
    id              uuid primary key,
    merchant_id     varchar(64)  not null,
    amount_minor    bigint       not null check (amount_minor > 0),
    currency        varchar(3)   not null,
    method          varchar(16)  not null,
    source_masked   varchar(128) not null,
    status          varchar(32)  not null,
    refunded_minor  bigint       not null default 0 check (refunded_minor >= 0 and refunded_minor <= amount_minor),
    risk_score      double precision,
    bank_reference  varchar(128),
    failure_reason  varchar(128),
    description     varchar(255),
    version         bigint       not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);
create index payments_merchant_created_idx on payments (merchant_id, created_at desc);

-- Append-only double-entry ledger.
create table ledger_entries (
    id            uuid primary key,
    journal_id    uuid        not null,
    payment_id    uuid        not null references payments (id),
    merchant_id   varchar(64) not null,
    account       varchar(32) not null,
    direction     varchar(6)  not null check (direction in ('DEBIT', 'CREDIT')),
    amount_minor  bigint      not null check (amount_minor > 0),
    currency      varchar(3)  not null,
    created_at    timestamptz not null
);
create index ledger_entries_merchant_account_idx on ledger_entries (merchant_id, account);
create index ledger_entries_payment_idx on ledger_entries (payment_id);
create index ledger_entries_journal_idx on ledger_entries (journal_id);

-- Transactional outbox, drained by OutboxRelay.
create table outbox_events (
    id            uuid primary key,
    aggregate_id  varchar(64)  not null,
    event_type    varchar(64)  not null,
    payload       text         not null,
    created_at    timestamptz  not null,
    published_at  timestamptz,
    attempts      int          not null default 0,
    last_error    text
);
create index outbox_events_unpublished_idx on outbox_events (created_at) where published_at is null;
create index outbox_events_aggregate_idx on outbox_events (aggregate_id);

create table idempotency_keys (
    merchant_id    varchar(64)  not null,
    idem_key       varchar(255) not null,
    request_hash   char(64)     not null,
    status         varchar(16)  not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_body  text,
    created_at     timestamptz  not null,
    completed_at   timestamptz,
    primary key (merchant_id, idem_key)
);
create index idempotency_keys_created_idx on idempotency_keys (created_at);
