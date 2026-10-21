-- Stable order within a day: the order transactions arrived (Plaid's order, or file order for
-- sample data), matching the prototype, which lists a day's rows in id order.
ALTER TABLE transaction ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
DROP INDEX transaction_date_idx;
CREATE INDEX transaction_date_seq_idx ON transaction (date DESC, seq);
