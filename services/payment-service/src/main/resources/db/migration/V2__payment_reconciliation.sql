-- Client idempotency key on the payment itself: a retry after a crash finds the payment instead of
-- creating a second one. Nullable only for rows created before this migration.
alter table payments add column idempotency_key varchar(255);
create unique index payments_merchant_idempotency_key_idx on payments (merchant_id, idempotency_key);

-- Whether to capture once the bank approves, so the reconciler can finish what the request asked.
alter table payments add column auto_capture boolean not null default false;

-- Reconciliation lease and attempt count (managed with SQL by PaymentReconciler, not mapped in JPA).
alter table payments add column next_reconcile_at timestamptz;
alter table payments add column reconcile_attempts int not null default 0;
create index payments_awaiting_bank_idx on payments (updated_at) where status in ('CREATED', 'PENDING');
