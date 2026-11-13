package com.smbox.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SimRepository {
    private final JdbcTemplate jdbc;

    // ===== 账户 =====

    public Map<String, Object> findAccount(String s) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sim_account WHERE strategy = ?", s);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void initAccount(String s, double initialCash, LocalDateTime now) {
        jdbc.update("INSERT INTO sim_account(strategy, initial_cash, cash, started_at) VALUES (?,?,?,?)",
                s, initialCash, initialCash, Timestamp.valueOf(now));
    }

    public void updateCash(String s, double cash) {
        jdbc.update("UPDATE sim_account SET cash = ? WHERE strategy = ?", cash, s);
    }

    public void resetAll(String s) {
        jdbc.update("DELETE FROM sim_account WHERE strategy = ?", s);
        jdbc.update("DELETE FROM sim_position WHERE strategy = ?", s);
        jdbc.update("DELETE FROM sim_order WHERE strategy = ?", s);
        jdbc.update("DELETE FROM sim_snapshot WHERE strategy = ?", s);
    }

    // ===== 持仓 =====

    public List<Map<String, Object>> listPositions(String s) {
        return jdbc.queryForList("SELECT * FROM sim_position WHERE strategy = ? ORDER BY opened_at", s);
    }

    public void upsertPosition(String s, String code, String name, int qty, double avgCost, LocalDateTime openedAt) {
        jdbc.update("MERGE INTO sim_position(strategy, code, name, qty, avg_cost, opened_at) " +
                        "KEY(strategy, code) VALUES (?,?,?,?,?,?)",
                s, code, name, qty, avgCost, Timestamp.valueOf(openedAt));
    }

    public void deletePosition(String s, String code) {
        jdbc.update("DELETE FROM sim_position WHERE strategy = ? AND code = ?", s, code);
    }

    // ===== 指令 =====

    public List<Map<String, Object>> listPendingOrders(String s) {
        return jdbc.queryForList("SELECT * FROM sim_order WHERE strategy = ? AND status = 'PENDING' ORDER BY id", s);
    }

    public List<Map<String, Object>> listOrdersPage(String s, int offset, int limit) {
        return jdbc.queryForList("SELECT * FROM sim_order WHERE strategy = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                s, limit, offset);
    }

    public int countOrders(String s) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_order WHERE strategy = ?", Integer.class, s);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> listFilledAsc(String s) {
        return jdbc.queryForList("SELECT * FROM sim_order WHERE strategy = ? AND status = 'FILLED' ORDER BY id ASC", s);
    }

    public void insertOrder(String s, LocalDate tradeDate, String code, String name, String side,
                            Integer qty, Double budget, String reason, String entryPattern, LocalDateTime now) {
        jdbc.update("INSERT INTO sim_order(strategy, trade_date, code, name, side, qty, budget, reason, entry_pattern, status, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?)",
                s, java.sql.Date.valueOf(tradeDate), code, name, side, qty, budget, reason, entryPattern, Timestamp.valueOf(now));
    }

    public void insertFilledOrder(String s, LocalDate tradeDate, String code, String name, String side,
                                  int qty, double price, double amount, double fee,
                                  String reason, LocalDateTime now) {
        jdbc.update("INSERT INTO sim_order(strategy, trade_date, code, name, side, qty, price, amount, fee, " +
                        "reason, status, created_at, filled_at) VALUES (?,?,?,?,?,?,?,?,?,?,'FILLED',?,?)",
                s, java.sql.Date.valueOf(tradeDate), code, name, side, qty, price, amount, fee,
                reason, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public void fillOrder(long id, int qty, double price, double amount, double fee, LocalDateTime at,
                          Double altPrice, String altNote) {
        jdbc.update("UPDATE sim_order SET status='FILLED', qty=?, price=?, amount=?, fee=?, filled_at=?, " +
                        "alt_price=?, alt_note=? WHERE id=?",
                qty, price, amount, fee, Timestamp.valueOf(at), altPrice, altNote, id);
    }

    public void cancelOrder(long id, String note) {
        jdbc.update("UPDATE sim_order SET status='CANCELLED', reason = CONCAT(reason, ' | ', ?) WHERE id=?", note, id);
    }

    public LocalDateTime lastSellFilledAt(String s, String code) {
        List<Timestamp> r = jdbc.query(
                "SELECT filled_at FROM sim_order WHERE strategy=? AND code=? AND side='SELL' AND status='FILLED' ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("filled_at"), s, code);
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toLocalDateTime();
    }

    public boolean hasSellWithReasonSince(String s, String code, String reasonKw, LocalDateTime since) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sim_order WHERE strategy=? AND code=? AND side='SELL' " +
                        "AND status <> 'CANCELLED' AND reason LIKE ? AND created_at >= ?",
                Integer.class, s, code, "%" + reasonKw + "%", Timestamp.valueOf(since));
        return c != null && c > 0;
    }

    // ===== 快照 =====

    public void upsertSnapshot(String s, LocalDate d, double total, double cash, double posValue,
                               double pnlPct, Double hs300, String payload) {
        jdbc.update("MERGE INTO sim_snapshot(strategy, trade_date, total_value, cash, position_value, total_pnl_pct, hs300_close, payload) " +
                        "KEY(strategy, trade_date) VALUES (?,?,?,?,?,?,?,?)",
                s, java.sql.Date.valueOf(d), total, cash, posValue, pnlPct, hs300, payload);
    }

    public List<Map<String, Object>> listSnapshots(String s) {
        return jdbc.queryForList("SELECT * FROM sim_snapshot WHERE strategy = ? ORDER BY trade_date", s);
    }
}
