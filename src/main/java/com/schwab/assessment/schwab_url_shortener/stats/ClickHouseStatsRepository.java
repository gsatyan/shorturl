package com.schwab.assessment.schwab_url_shortener.stats;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stats from {@code analytics.clicks_raw} (docs/DESIGN.md §7.5). The table is ordered by {@code (code, ts)}, so every
 * query reads one contiguous range. {@code FINAL} merges rows delivered twice before counting, which costs little
 * on a single link's range. Unique visitors use {@code uniqCombined} (about 1% error) per §6.4.
 */
@Repository
class ClickHouseStatsRepository implements StatsRepository {

	private static final String RANGE = """
			FROM analytics.clicks_raw FINAL
			WHERE code = ?
			  AND ts >= fromUnixTimestamp64Milli(?, 'UTC') AND ts < fromUnixTimestamp64Milli(?, 'UTC')
			""";

	private static final String HUMANS_ONLY = " AND is_bot = 0\n";

	private final JdbcTemplate jdbc;

	ClickHouseStatsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public long[] totals(StatsQuery query) {
		return jdbc.queryForObject("SELECT count(), uniqCombined(visitor_hash) " + range(query),
				(row, index) -> new long[] { row.getLong(1), row.getLong(2) }, args(query));
	}

	@Override
	public Map<Instant, Long> series(StatsQuery query) {
		var bucket = (query.granularity() == StatsQuery.Granularity.HOUR) ? "toStartOfHour(ts)" : "toStartOfDay(ts)";
		var series = new LinkedHashMap<Instant, Long>();
		jdbc.query("SELECT toUnixTimestamp(" + bucket + ") AS bucket, count() " + range(query)
				+ "GROUP BY bucket ORDER BY bucket",
				row -> {
					series.put(Instant.ofEpochSecond(row.getLong(1)), row.getLong(2));
				}, args(query));
		return series;
	}

	@Override
	public List<LinkStats.Referrer> topReferrers(StatsQuery query, int limit) {
		return jdbc.query("SELECT referrer, count() AS clicks " + range(query)
				+ "AND referrer != '' GROUP BY referrer ORDER BY clicks DESC, referrer LIMIT " + limit,
				(row, index) -> new LinkStats.Referrer(row.getString(1), row.getLong(2)), args(query));
	}

	@Override
	public Map<String, Long> byDevice(StatsQuery query) {
		return counts("device", query);
	}

	@Override
	public Map<String, Long> byCountry(StatsQuery query) {
		return counts("country", query);
	}

	/** @param column a fixed column name, never caller input */
	private Map<String, Long> counts(String column, StatsQuery query) {
		var counts = new LinkedHashMap<String, Long>();
		jdbc.query("SELECT toString(" + column + ") AS value, count() AS clicks " + range(query) + "AND " + column
				+ " != '' GROUP BY value ORDER BY clicks DESC, value",
				row -> {
					counts.put(row.getString(1), row.getLong(2));
				}, args(query));
		return counts;
	}

	private static String range(StatsQuery query) {
		return RANGE + (query.includeBots() ? "" : HUMANS_ONLY);
	}

	private static Object[] args(StatsQuery query) {
		return new Object[] { query.code(), query.from().toEpochMilli(), query.to().toEpochMilli() };
	}

}
