-- Extra item state for Phase 1 sync.
-- products: Plaid products on the Item (e.g. {transactions} or {investments}); sync skips
--   /transactions/sync for Items without transactions (retirement-only logins).
-- transactions_update_status: Plaid's NOT_READY / INITIAL_UPDATE_COMPLETE / HISTORICAL_UPDATE_COMPLETE,
--   so the UI can say "still pulling history".
-- last_error_code: Plaid error_code from the last failed sync (never secrets).
ALTER TABLE item
    ADD COLUMN products                   TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN transactions_update_status TEXT,
    ADD COLUMN last_error_code            TEXT;
