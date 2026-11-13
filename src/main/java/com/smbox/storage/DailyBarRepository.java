package com.smbox.storage;

import com.smbox.model.DailyBar;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DailyBarRepository {
    private final JdbcTemplate jdbc;

    public void upsertBatch(List<DailyBar> bars) {
        if (bars == null || bars.isEmpty()) return;
        jdbc.batchUpdate(
                "MERGE INTO daily_bar(code, trade_date, open, close, high, low, volume, amount) " +
                        "KEY(code, trade_date) VALUES (?,?,?,?,?,?,?,?)",
                bars,
                500,
                (ps, bar) -> {
                    ps.setString(1, bar.getCode());
                    ps.setDate(2, Date.valueOf(bar.getTradeDate()));
                    ps.setDouble(3, bar.getOpen());
                    ps.setDouble(4, bar.getClose());
                    ps.setDouble(5, bar.getHigh());
                    ps.setDouble(6, bar.getLow());
                    ps.setLong(7, bar.getVolume());
                    ps.setDouble(8, bar.getAmount());
                });
    }

    public List<DailyBar> findRecent(String code, int n) {
        return jdbc.query(
                "SELECT code, trade_date, open, close, high, low, volume, amount " +
                        "FROM daily_bar WHERE code = ? ORDER BY trade_date DESC LIMIT ?",
                (rs, i) -> DailyBar.builder()
                        .code(rs.getString("code"))
                        .tradeDate(rs.getDate("trade_date").toLocalDate())
                        .open(rs.getDouble("open"))
                        .close(rs.getDouble("close"))
                        .high(rs.getDouble("high"))
                        .low(rs.getDouble("low"))
                        .volume(rs.getLong("volume"))
                        .amount(rs.getDouble("amount"))
                        .build(),
                code, n);
    }

    public LocalDate findLatestDate(String code) {
        Date d = jdbc.queryForObject(
                "SELECT MAX(trade_date) FROM daily_bar WHERE code = ?",
                Date.class, code);
        return d == null ? null : d.toLocalDate();
    }
}
