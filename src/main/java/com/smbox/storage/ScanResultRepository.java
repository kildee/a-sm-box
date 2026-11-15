package com.smbox.storage;

import com.smbox.service.BottomTurnEngine;
import com.smbox.service.FlagEngine;
import com.smbox.service.OversoldEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class ScanResultRepository {
    private final JdbcTemplate jdbc;
    private static final String TABLE = "scan_result";

    public ScanResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static final class Row {
        public final String code;
        public final String name;
        public final double score;
        public final int tier;
        public final Boolean ox;       // 是否透支
        public final String bLabel;    // 最近背驰买点 B1/B2/B3
        public final Integer bDays;    // 距今交易日数
        public final String xLabel;    // 最近一次均线交叉 金叉/死叉
        public final Integer xDays;    // 距今交易日数
        public final Integer bCount;   // 近15根背驰买点个数
        public final String regime;    // 趋势状态
        public final String payload;
        public BottomTurnEngine.Result bt;
        public OversoldEngine.Result os;
        public FlagEngine.Result flag;

        public Row(String code, String name, double score, int tier, Boolean ox,
                   String bLabel, Integer bDays, String xLabel, Integer xDays,
                   Integer bCount, String regime, String payload) {
            this.code = code; this.name = name; this.score = score; this.tier = tier; this.ox = ox;
            this.bLabel = bLabel; this.bDays = bDays; this.xLabel = xLabel; this.xDays = xDays;
            this.bCount = bCount; this.regime = regime; this.payload = payload;
        }
    }

    public static List<Row> parseRows(Map<String, Object> body, ObjectMapper mapper) {
        List<Row> rows = new ArrayList<>();
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List)) return rows;
        for (Object o : (List<?>) itemsObj) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object codeObj = m.get("code");
            if (codeObj == null) continue;
            String code = codeObj.toString();
            double score = (m.get("score") instanceof Number) ? ((Number) m.get("score")).doubleValue() : -1;
            String stockName = m.get("name") == null ? null : m.get("name").toString();
            int tier = m.get("tier") instanceof Number ? ((Number) m.get("tier")).intValue() : 0;
            Boolean ox = m.get("ox") instanceof Boolean ? (Boolean) m.get("ox") : null;
            Object bl = m.get("bLabel");
            String bLabel = "B1".equals(bl) || "B2".equals(bl) || "B3".equals(bl) ? bl.toString() : null;
            Integer bDays = bLabel != null && m.get("bDaysAgo") instanceof Number ? ((Number) m.get("bDaysAgo")).intValue() : null;
            Object xl = m.get("xLabel");
            String xLabel = "金叉".equals(xl) || "死叉".equals(xl) ? xl.toString() : null;
            Integer xDays = xLabel != null && m.get("xDays") instanceof Number ? ((Number) m.get("xDays")).intValue() : null;
            Integer bCount = m.get("bCount") instanceof Number ? ((Number) m.get("bCount")).intValue() : null;
            String regime = m.get("regime") == null ? null : m.get("regime").toString();
            String payload;
            try { payload = mapper.writeValueAsString(m); }
            catch (Exception e) { payload = "{}"; }
            rows.add(new Row(code, stockName, score, tier, ox, bLabel, bDays, xLabel, xDays, bCount, regime, payload));
        }
        return rows;
    }

    public void deleteGroup(String group) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE group_name = ?", group);
    }

    public void upsertBatch(String group, List<Row> rows, LocalDateTime scannedAt) {
        if (rows.isEmpty()) return;
        Timestamp ts = Timestamp.valueOf(scannedAt);
        jdbc.batchUpdate(
                "MERGE INTO " + TABLE + "(group_name, code, name, score, tier, b_label, b_days, x_label, x_days, b_count, regime, payload, scanned_at, "
                        + "bottom_turn, bt_rebound, bt_days_since, bt_vol_ratio, bt_pos, bt_drawdown, "
                        + "oversold, os_ret20, os_dma20, flag_state, flag_pole, flag_days) "
                        + "KEY(group_name, code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Row r = rows.get(i);
                        ps.setString(1, group);
                        ps.setString(2, r.code);
                        ps.setString(3, r.name);
                        ps.setDouble(4, r.score);
                        ps.setInt(5, r.tier);
                        ps.setString(6, r.bLabel);
                        if (r.bDays == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, r.bDays);
                        ps.setString(8, r.xLabel);
                        if (r.xDays == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, r.xDays);
                        if (r.bCount == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, r.bCount);
                        ps.setString(11, r.regime);
                        ps.setString(12, r.payload);
                        ps.setTimestamp(13, ts);
                        BottomTurnEngine.Result bt = r.bt != null && r.bt.evaluable ? r.bt : null;
                        if (bt == null) {
                            ps.setNull(14, Types.BOOLEAN);
                            for (int k = 15; k <= 19; k++) ps.setNull(k, k == 16 ? Types.INTEGER : Types.DECIMAL);
                        } else {
                            ps.setBoolean(14, bt.hit);
                            ps.setDouble(15, BottomTurnEngine.pctOf(bt.rebound));
                            ps.setInt(16, bt.daysSince);
                            ps.setDouble(17, BottomTurnEngine.round2(bt.volRatio));
                            ps.setDouble(18, BottomTurnEngine.round2(bt.pos));
                            ps.setDouble(19, BottomTurnEngine.pctOf(bt.drawdown));
                        }
                        OversoldEngine.Result os = r.os != null && r.os.evaluable ? r.os : null;
                        if (os == null) {
                            ps.setNull(20, Types.BOOLEAN); ps.setNull(21, Types.DECIMAL); ps.setNull(22, Types.DECIMAL);
                        } else {
                            ps.setBoolean(20, os.hit);
                            ps.setDouble(21, BottomTurnEngine.pctOf(os.ret20));
                            ps.setDouble(22, BottomTurnEngine.pctOf(os.distMa20));
                        }
                        FlagEngine.Result fl = r.flag;
                        ps.setString(23, fl == null ? null : fl.state);
                        if (fl == null || !fl.hit()) { ps.setNull(24, Types.DECIMAL); ps.setNull(25, Types.INTEGER); }
                        else { ps.setDouble(24, BottomTurnEngine.pctOf(fl.pole)); ps.setInt(25, fl.flagDays); }
                    }
                    @Override
                    public int getBatchSize() { return rows.size(); }
                });
    }

    public static final class Filter {
        public String bLabel;       // B点级别 B1/B2/B3
        public Integer bDays;       // B点距今 ≤ N 个交易日
        public String xLabel;       // 最近一次均线交叉 金叉/死叉
        public Integer xDays;       // 交叉距今 ≤ N 个交易日
        public Integer bCount;      // 近15根买点数 ≥ N
        public String regime;       // 趋势，UP_ANY=任一上升
        public String kw;           // 名称或代码模糊
    }

    private static String buildWhere(String group, Filter f, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE group_name = ?");
        args.add(group);
        if (f == null) return sql.toString();
        boolean bl = "B1".equals(f.bLabel) || "B2".equals(f.bLabel) || "B3".equals(f.bLabel);
        if (bl || f.bDays != null) {
            sql.append(" AND b_label IS NOT NULL AND b_days IS NOT NULL");
            if (bl) { sql.append(" AND b_label = ?"); args.add(f.bLabel); }
            if (f.bDays != null) { sql.append(" AND b_days <= ?"); args.add(f.bDays); }
        }
        boolean xl = "金叉".equals(f.xLabel) || "死叉".equals(f.xLabel);
        if (xl || f.xDays != null) {
            sql.append(" AND x_label IS NOT NULL AND x_days IS NOT NULL");
            if (xl) { sql.append(" AND x_label = ?"); args.add(f.xLabel); }
            if (f.xDays != null) { sql.append(" AND x_days <= ?"); args.add(f.xDays); }
        }
        if (f.bCount != null) {
            sql.append(" AND b_count IS NOT NULL AND b_count >= ?");
            args.add(f.bCount);
        }
        if (f.regime != null && !f.regime.isEmpty()) {
            if ("UP_ANY".equals(f.regime)) sql.append(" AND regime IN ('UPTREND','RANGE_UP')");
            else { sql.append(" AND regime = ?"); args.add(f.regime); }
        }
        if (f.kw != null && !f.kw.isEmpty()) {
            sql.append(" AND (code LIKE ? OR name LIKE ?)");
            args.add("%" + f.kw + "%");
            args.add("%" + f.kw + "%");
        }
        return sql.toString();
    }

    public List<Map<String, Object>> exportCodeName(String group, Filter f) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(group, f, args);
        return jdbc.queryForList("SELECT code, name FROM " + TABLE + where + " ORDER BY score DESC, code",
                args.toArray());
    }

    public List<String> findPage(String group, Filter f, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(group, f, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT payload FROM " + TABLE + where + " ORDER BY score DESC, code LIMIT ? OFFSET ?",
                (rs, i) -> rs.getString("payload"), args.toArray());
    }

    public Map<String, Map<String, Object>> bottomTurnOf(String group, List<String> codes) {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        List<Object> args = new ArrayList<>();
        args.add(group);
        args.addAll(codes);
        String in = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code, bt_rebound, bt_days_since, bt_vol_ratio, bt_pos, bt_drawdown FROM " + TABLE
                        + " WHERE group_name = ? AND bottom_turn = TRUE AND code IN (" + in + ")", args.toArray())) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("rebound", r.get("bt_rebound")); m.put("daysSince", r.get("bt_days_since"));
            m.put("volRatio", r.get("bt_vol_ratio")); m.put("pos", r.get("bt_pos")); m.put("drawdown", r.get("bt_drawdown"));
            out.put(String.valueOf(r.get("code")), m);
        }
        return out;
    }

    public Map<String, Map<String, Object>> flagOf(String group, List<String> codes) {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        List<Object> args = new ArrayList<>();
        args.add(group);
        args.addAll(codes);
        String in = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code, flag_state, flag_pole, flag_days FROM " + TABLE
                        + " WHERE group_name = ? AND flag_state IN ('FORMING','BREAKOUT') AND code IN (" + in + ")", args.toArray())) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("state", r.get("flag_state")); m.put("pole", r.get("flag_pole")); m.put("days", r.get("flag_days"));
            out.put(String.valueOf(r.get("code")), m);
        }
        return out;
    }

    public Map<String, Map<String, Object>> oversoldOf(String group, List<String> codes) {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        List<Object> args = new ArrayList<>();
        args.add(group);
        args.addAll(codes);
        String in = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code, os_ret20, os_dma20 FROM " + TABLE + " WHERE group_name = ? AND oversold = TRUE AND code IN (" + in + ")", args.toArray())) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ret20", r.get("os_ret20")); m.put("distMa20", r.get("os_dma20"));
            out.put(String.valueOf(r.get("code")), m);
        }
        return out;
    }

    public Map<String, Object> stats(String group, Filter f) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(group, f, args);
        return jdbc.queryForMap(
                "SELECT COUNT(*) AS total, " +
                        "COALESCE(SUM(CASE WHEN tier = 5 THEN 1 ELSE 0 END), 0) AS strong, " +
                        "COALESCE(SUM(CASE WHEN tier = 4 THEN 1 ELSE 0 END), 0) AS build " +
                        "FROM " + TABLE + where, args.toArray());
    }

    public LocalDateTime maxScannedAt(String group) {
        List<LocalDateTime> r = jdbc.query(
                "SELECT MAX(scanned_at) AS m FROM " + TABLE + " WHERE group_name = ?",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("m");
                    return t == null ? null : t.toLocalDateTime();
                }, group);
        return r.isEmpty() ? null : r.get(0);
    }
}
