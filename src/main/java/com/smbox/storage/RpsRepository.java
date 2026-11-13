package com.smbox.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RpsRepository {
    private final JdbcTemplate jdbc;

    public static final class Row {
        public final String code;
        public final Double rps50;
        public final Double rps120;
        public Row(String code, Double rps50, Double rps120) {
            this.code = code; this.rps50 = rps50; this.rps120 = rps120;
        }
    }

    public void replaceAll(LocalDate tradeDate, List<Row> rows) {
        jdbc.update("DELETE FROM rps");
        if (rows.isEmpty()) return;
        java.sql.Date d = java.sql.Date.valueOf(tradeDate);
        jdbc.batchUpdate("INSERT INTO rps(code, trade_date, rps50, rps120) VALUES (?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Row r = rows.get(i);
                        ps.setString(1, r.code);
                        ps.setDate(2, d);
                        if (r.rps50 == null) ps.setNull(3, java.sql.Types.DECIMAL); else ps.setDouble(3, r.rps50);
                        if (r.rps120 == null) ps.setNull(4, java.sql.Types.DECIMAL); else ps.setDouble(4, r.rps120);
                    }
                    @Override
                    public int getBatchSize() { return rows.size(); }
                });
    }

    public java.util.Map<String, Object> findByCode(String code) {
        List<java.util.Map<String, Object>> r = jdbc.queryForList(
                "SELECT rps50, rps120, trade_date FROM rps WHERE code = ?", code);
        return r.isEmpty() ? null : r.get(0);
    }

    public LocalDate maxTradeDate() {
        List<java.sql.Date> r = jdbc.query("SELECT MAX(trade_date) AS m FROM rps",
                (rs, i) -> rs.getDate("m"));
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toLocalDate();
    }
}
