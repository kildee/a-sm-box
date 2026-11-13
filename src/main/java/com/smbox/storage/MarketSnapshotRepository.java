package com.smbox.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MarketSnapshotRepository {
    private final JdbcTemplate jdbc;

    public static final class Entry {
        public final LocalDate tradeDate;
        public final String payload;
        public final LocalDateTime computedAt;
        public Entry(LocalDate tradeDate, String payload, LocalDateTime computedAt) {
            this.tradeDate = tradeDate;
            this.payload = payload;
            this.computedAt = computedAt;
        }
    }

    /** 覆盖式写入某交易日 */
    public void upsert(LocalDate tradeDate, String payload, LocalDateTime computedAt) {
        jdbc.update(
                "MERGE INTO market_snapshot(trade_date, payload, computed_at) KEY(trade_date) VALUES (?,?,?)",
                java.sql.Date.valueOf(tradeDate), payload, Timestamp.valueOf(computedAt));
    }

    public Entry findLatest() {
        List<Entry> rows = jdbc.query(
                "SELECT trade_date, payload, computed_at FROM market_snapshot ORDER BY trade_date DESC LIMIT 1",
                (rs, i) -> new Entry(rs.getDate("trade_date").toLocalDate(),
                        rs.getString("payload"),
                        rs.getTimestamp("computed_at").toLocalDateTime()));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
