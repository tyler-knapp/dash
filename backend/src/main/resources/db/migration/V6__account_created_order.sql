-- now() is the transaction's start time, so accounts created together (one Plaid link, or the
-- sample set) all got the same created_at and their display order wasn't stable. clock_timestamp()
-- advances within a transaction, so created_at reflects the order accounts were added.
ALTER TABLE account ALTER COLUMN created_at SET DEFAULT clock_timestamp();
