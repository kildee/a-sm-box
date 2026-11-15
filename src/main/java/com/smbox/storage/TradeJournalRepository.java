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
public class TradeJournalRepository {
    private final JdbcTemplate jdbc;

    public long insertBuy(String code, String name, LocalDate buyDate,
                          double buyPrice, int qty, String buyReason, LocalDateTime now) {
        jdbc.update("INSERT INTO trade_journal(code, name, buy_date, buy_price, qty, buy_reason, " +
                        "status, created_at, updated_at) VALUES (?,?,?,?,?,?, 'OPEN', ?, ?)",
                code, name, java.sql.Date.valueOf(buyDate), buyPrice, qty, buyReason,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM trade_journal", Long.class);
        return id == null ? 0 : id;
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT * FROM trade_journal WHERE id = ?", id);
        return r.isEmpty() ? null : r.get(0);
    }

    public void closeTrade(long id, LocalDate sellDate, double sellPrice, String sellReason,
                           double pnlAmt, double pnlPct, int holdDays, double fee, LocalDateTime now) {
        jdbc.update("UPDATE trade_journal SET sell_date=?, sell_price=?, sell_reason=?, status='CLOSED', " +
                        "pnl_amt=?, pnl_pct=?, hold_days=?, fee=?, updated_at=? WHERE id=?",
                java.sql.Date.valueOf(sellDate), sellPrice, sellReason, pnlAmt, pnlPct, holdDays, fee,
                Timestamp.valueOf(now), id);
    }

    public void updateTagsNote(long id, String tags, String note, LocalDateTime now) {
        jdbc.update("UPDATE trade_journal SET tags=?, note=?, updated_at=? WHERE id=?",
                tags, note, Timestamp.valueOf(now), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM trade_journal WHERE id=?", id);
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList(
                "SELECT * FROM trade_journal ORDER BY " +
                        "CASE status WHEN 'OPEN' THEN 0 ELSE 1 END, " +
                        "COALESCE(sell_date, buy_date) DESC, id DESC");
    }
}
