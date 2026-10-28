package com.schwab.assessment.schwab_url_shortener.analytics;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts enriched clicks into {@code analytics.clicks_raw} in one batch. A batch is retried as a whole on failure;
 * rows that made it the first time are merged away by the table's {@code ReplacingMergeTree} on {@code event_id}.
 */
class ClickHouseClickWriter {

	private static final String INSERT = """
			INSERT INTO analytics.clicks_raw
			    (event_id, code, ts, referrer, device, country, is_bot, visitor_hash, region)
			VALUES (?, ?, fromUnixTimestamp64Milli(?, 'UTC'), ?, ?, ?, ?, unhex(?), ?)""";

	private static final HexFormat HEX = HexFormat.of();

	private final JdbcTemplate jdbc;

	ClickHouseClickWriter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	void insert(List<EnrichedClick> clicks) {
		jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {

			@Override
			public void setValues(PreparedStatement statement, int index) throws SQLException {
				var click = clicks.get(index);
				statement.setObject(1, click.eventId());
				statement.setString(2, click.code());
				statement.setLong(3, click.timestamp().toEpochMilli());
				statement.setString(4, click.referrer());
				statement.setString(5, click.device());
				statement.setString(6, click.country());
				statement.setInt(7, click.isBot() ? 1 : 0);
				statement.setString(8, HEX.formatHex(click.visitorHash()));
				statement.setString(9, click.region());
			}

			@Override
			public int getBatchSize() {
				return clicks.size();
			}

		});
	}

}
