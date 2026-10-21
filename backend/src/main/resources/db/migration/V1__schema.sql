-- Baseline schema. See SPEC §4.
-- Money is NUMERIC(14,2). App sign: negative = money out, positive = money in.

CREATE TABLE item (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plaid_item_id    TEXT NOT NULL UNIQUE,
    institution_id   TEXT,
    institution_name TEXT,
    access_token_enc BYTEA NOT NULL,
    sync_cursor      TEXT,
    status           TEXT NOT NULL DEFAULT 'ok'
                     CHECK (status IN ('ok', 'login_required', 'error')),
    last_synced_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE account (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id          UUID REFERENCES item (id) ON DELETE CASCADE,
    plaid_account_id TEXT UNIQUE,
    name             TEXT NOT NULL,
    official_name    TEXT,
    mask             TEXT,
    type             TEXT,
    subtype          TEXT,
    role             TEXT NOT NULL CHECK (role IN ('ledger', 'balance_only')),
    display_color    TEXT,
    is_manual        BOOLEAN NOT NULL DEFAULT false,
    hidden           BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Manual accounts have no Plaid item; linked accounts always do.
    CONSTRAINT account_manual_vs_linked CHECK (
        (is_manual AND item_id IS NULL AND plaid_account_id IS NULL)
        OR (NOT is_manual AND item_id IS NOT NULL AND plaid_account_id IS NOT NULL)
    )
);
CREATE INDEX account_item_idx ON account (item_id);

CREATE TABLE category (
    name       TEXT PRIMARY KEY,
    kind       TEXT NOT NULL CHECK (kind IN ('spend', 'income', 'transfer')),
    sort_order INT NOT NULL
);

CREATE TABLE subcategory (
    category_name TEXT NOT NULL REFERENCES category (name) ON UPDATE CASCADE,
    name          TEXT NOT NULL,
    sort_order    INT NOT NULL,
    PRIMARY KEY (category_name, name)
);

CREATE TABLE transaction (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                   UUID NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    plaid_transaction_id         TEXT UNIQUE,
    pending_plaid_transaction_id TEXT,
    date                         DATE NOT NULL,
    authorized_date              DATE,
    merchant_name                TEXT,
    raw_name                     TEXT,
    amount                       NUMERIC(14, 2) NOT NULL,
    pending                      BOOLEAN NOT NULL DEFAULT false,
    category                     TEXT,
    subcategory                  TEXT,
    category_source              TEXT CHECK (category_source IN ('rule', 'plaid', 'ai', 'user')),
    confidence                   NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    reviewed                     BOOLEAN NOT NULL DEFAULT false,
    plaid_pfc_primary            TEXT,
    plaid_pfc_detailed           TEXT,
    plaid_pfc_confidence         TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transaction_category_pair CHECK ((category IS NULL) = (subcategory IS NULL)),
    CONSTRAINT transaction_subcategory_fk FOREIGN KEY (category, subcategory)
        REFERENCES subcategory (category_name, name) ON UPDATE CASCADE
);
CREATE INDEX transaction_date_idx ON transaction (date DESC, id);
CREATE INDEX transaction_account_date_idx ON transaction (account_id, date DESC);
CREATE INDEX transaction_category_idx ON transaction (category, subcategory);
CREATE INDEX transaction_pending_plaid_idx ON transaction (pending_plaid_transaction_id);

-- A version applies from effective_from until the next version (SPEC §6).
CREATE TABLE budget_version (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    effective_from CHAR(7) NOT NULL UNIQUE
                   CHECK (effective_from ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    amounts        JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE balance_snapshot (
    account_id UUID NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    as_of      DATE NOT NULL,
    current    NUMERIC(14, 2) NOT NULL,
    available  NUMERIC(14, 2),
    source     TEXT NOT NULL CHECK (source IN ('plaid', 'manual')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, as_of)
);

-- Used from Phase 7; exists from day one.
CREATE TABLE category_rule (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    priority            INT NOT NULL,
    match_field         TEXT NOT NULL CHECK (match_field IN ('merchant', 'raw_name', 'account')),
    match_type          TEXT NOT NULL CHECK (match_type IN ('exact', 'contains', 'regex')),
    pattern             TEXT NOT NULL,
    account_id          UUID REFERENCES account (id) ON DELETE CASCADE,
    category            TEXT NOT NULL,
    subcategory         TEXT NOT NULL,
    created_from_txn_id UUID REFERENCES transaction (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT category_rule_subcategory_fk FOREIGN KEY (category, subcategory)
        REFERENCES subcategory (category_name, name) ON UPDATE CASCADE
);
CREATE INDEX category_rule_priority_idx ON category_rule (priority);
