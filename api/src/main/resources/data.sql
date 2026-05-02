-- Module 05 seed data. Inserts 1,000 rows into the numbers table on
-- first run, no-ops on subsequent runs. The WHERE NOT EXISTS guard
-- makes this idempotent: a redeploy that re-runs init without changing
-- the table contents will not duplicate rows.
INSERT INTO numbers (value)
SELECT generate_series FROM generate_series(1, 1000)
WHERE NOT EXISTS (SELECT 1 FROM numbers);
