package com.smbox.service;

import com.smbox.model.DailyBar;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.TradeJournalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeJournalService {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final double COMMISSION = 0.00025;  // 佣金万2.5
    private static final double MIN_COMMISSION = 5.0;  // 单边最低5元
    private static final double STAMP = 0.0005;        // 印花税万5（仅卖出）

    private final TradeJournalRepository repo;
    private final DailyBarRepository dailyBarRepository;

    public long addBuy(String code, String name, LocalDate buyDate,
                       double buyPrice, int qty, String reason) {
        return repo.insertBuy(code, name, buyDate, buyPrice, qty, reason,
                LocalDateTime.now(SH));
    }

    public void close(long id, LocalDate sellDate, double sellPrice, String sellReason) {
        Map<String, Object> t = repo.findById(id);
        if (t == null) throw new IllegalArgumentException("记录不存在");
        int qty = ((Number) t.get("qty")).intValue();
        double buyPrice = ((Number) t.get("buy_price")).doubleValue();
        LocalDate buyDate = ((java.sql.Date) t.get("buy_date")).toLocalDate();
        double buyAmt = buyPrice * qty, sellAmt = sellPrice * qty;
        double fee = Math.max(buyAmt * COMMISSION, MIN_COMMISSION)
                + Math.max(sellAmt * COMMISSION, MIN_COMMISSION)
                + sellAmt * STAMP;
        double pnlAmt = sellAmt - buyAmt - fee;
        double pnlPct = buyAmt > 0 ? pnlAmt / buyAmt * 100 : 0;
        int holdDays = (int) ChronoUnit.DAYS.between(buyDate, sellDate);
        repo.closeTrade(id, sellDate, sellPrice, sellReason,
                round2(pnlAmt), round3(pnlPct), holdDays, round2(fee), LocalDateTime.now(SH));
    }

    public void setTags(long id, String tags, String note) {
        repo.updateTagsNote(id, tags, note, LocalDateTime.now(SH));
    }

    public void delete(long id) {
        repo.delete(id);
    }

    public Map<String, Object> overview() {
        List<Map<String, Object>> rows = repo.listAll();
        List<Map<String, Object>> out = new ArrayList<>();
        int closedN = 0, wins = 0, holdSum = 0;
        double realized = 0;
        TreeMap<String, double[]> byTag = new TreeMap<>();

        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            long id = ((Number) r.get("id")).longValue();
            String code = (String) r.get("code");
            int qty = ((Number) r.get("qty")).intValue();
            double buyPrice = ((Number) r.get("buy_price")).doubleValue();
            String status = (String) r.get("status");
            m.put("id", id);
            m.put("code", code);
            m.put("name", r.get("name"));
            m.put("buyDate", str(r.get("buy_date")));
            m.put("buyPrice", buyPrice);
            m.put("qty", qty);
            m.put("buyReason", r.get("buy_reason"));
            m.put("status", status);
            m.put("tags", r.get("tags"));
            m.put("note", r.get("note"));

            if ("CLOSED".equals(status)) {
                double pnlAmt = num(r.get("pnl_amt")), pnlPct = num(r.get("pnl_pct"));
                int hold = r.get("hold_days") == null ? 0 : ((Number) r.get("hold_days")).intValue();
                m.put("sellDate", str(r.get("sell_date")));
                m.put("sellPrice", num(r.get("sell_price")));
                m.put("sellReason", r.get("sell_reason"));
                m.put("pnlAmt", pnlAmt);
                m.put("pnlPct", pnlPct);
                m.put("holdDays", hold);
                m.put("fee", num(r.get("fee")));
                closedN++; realized += pnlAmt; holdSum += hold;
                if (pnlAmt > 0) wins++;
                for (String tag : splitTags(r.get("tags"))) {
                    double[] a = byTag.computeIfAbsent(tag, k -> new double[3]);
                    a[0]++; if (pnlAmt > 0) a[1]++; a[2] += pnlAmt;
                }
            } else {
                Double last = latestClose(code);
                if (last != null) {
                    double floatPnl = (last - buyPrice) * qty;
                    m.put("lastClose", last);
                    m.put("floatPnlAmt", round2(floatPnl));
                    m.put("floatPnlPct", round3(buyPrice > 0 ? floatPnl / (buyPrice * qty) * 100 : 0));
                }
            }
            out.add(m);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("closedCount", closedN);
        stats.put("winRate", closedN > 0 ? round1((double) wins / closedN * 100) : null);
        stats.put("realizedPnl", round2(realized));
        stats.put("avgHoldDays", closedN > 0 ? round1((double) holdSum / closedN) : null);

        List<Map<String, Object>> tagStats = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byTag.entrySet()) {
            double[] a = e.getValue();
            Map<String, Object> ts = new LinkedHashMap<>();
            ts.put("tag", e.getKey());
            ts.put("count", (int) a[0]);
            ts.put("winRate", a[0] > 0 ? round1(a[1] / a[0] * 100) : null);
            ts.put("pnl", round2(a[2]));
            tagStats.add(ts);
        }
        tagStats.sort((x, y) -> Double.compare(num(x.get("pnl")), num(y.get("pnl"))));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("trades", out);
        resp.put("stats", stats);
        resp.put("tagStats", tagStats);
        return resp;
    }

    private Double latestClose(String code) {
        try {
            List<DailyBar> r = dailyBarRepository.findRecent(code, 1);
            return r.isEmpty() ? null : r.get(0).getClose();
        } catch (Exception e) { return null; }
    }

    private static List<String> splitTags(Object tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        for (String t : tags.toString().split(",")) {
            String s = t.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
