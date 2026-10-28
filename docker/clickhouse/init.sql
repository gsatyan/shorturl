-- Click analytics schema (docs/DESIGN.md §7.5). Run by the ClickHouse image on first start, and by the tests.
-- Every column is derived: raw IP addresses and User-Agents are never stored (§6.4, §9).

CREATE DATABASE IF NOT EXISTS analytics;

CREATE TABLE IF NOT EXISTS analytics.clicks_raw
(
    event_id     UUID,
    code         String,
    ts           DateTime64(3, 'UTC'),
    referrer     LowCardinality(String),         -- host only, e.g. linkedin.com; '' if none
    device       LowCardinality(String),         -- MOBILE, TABLET, DESKTOP, BOT, OTHER
    country      LowCardinality(FixedString(2)), -- '' until an IP-to-country source is chosen
    is_bot       UInt8,
    visitor_hash FixedString(16),                -- daily-salted hash of IP + User-Agent
    region       LowCardinality(String)
)
-- At-least-once delivery means an event can arrive twice; rows with the same sorting key, which includes
-- event_id, are merged into one. Queries use FINAL so duplicates not merged yet aren't counted either.
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (code, ts, event_id)
TTL toDateTime(ts) + INTERVAL 13 MONTH;
