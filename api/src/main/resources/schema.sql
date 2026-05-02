-- Module 05 schema init. Spring Boot runs this on application startup
-- via spring.sql.init.mode=always (see application.properties). With
-- SnapStart enabled, the script executes once per published version
-- during the snapshot capture's init phase; restored Lambda containers
-- reuse the snapshotted post-init state and do not re-run schema DDL.
--
-- Idempotency: IF NOT EXISTS makes this script safe to re-run on
-- redeploys. The same applies to data.sql, which uses a WHERE NOT
-- EXISTS guard to avoid duplicate rows.
CREATE TABLE IF NOT EXISTS numbers (
    id SERIAL PRIMARY KEY,
    value INTEGER
);
