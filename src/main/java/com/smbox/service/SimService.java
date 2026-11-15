package com.smbox.service;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.datasource.TencentClient;
import com.smbox.model.DailyBar;
import com.smbox.model.MarketPulse;
import com.smbox.model.WatchItem;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.SimRepository;
import com.smbox.storage.WatchItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimService {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final double INITIAL_CASH = 100_000d;
    private static final int MAX_POSITIONS = 5;
    private static final double POSITION_PCT = 0.20;
    private static final int MIN_SCORE = 70;             // A：指标分门槛
    private static final double MIN_RPS120 = 85;         // A：RPS 门槛
    private static final double RISK_PCT = 0.01;
    private static final double RISK_RELAX = 1.5;
    private static final double HARD_STOP_PCT = 0.12;
    private static final double TAKE_PROFIT_HALF = 0.25;
    private static final double TAKE_PROFIT_FULL = 0.30;
    private static final double MIN_MARKET_TEMP = 40;
    private static final int COOLDOWN_DAYS = 7;

    public static final String STRAT_A = "A";
    public static final String STRAT_B = "B";
    public static final String STRAT_C = "C";
    public static final String[] ALL_STRATS = {STRAT_A, STRAT_B, STRAT_C};

    private final SimRepository repo;
    private final DailyBarRepository dailyBarRepository;
    private final WatchItemRepository watchItemRepository;
    private final WatchlistService watchlistService;
    private final com.smbox.storage.RpsRepository rpsRepository;
    private final com.smbox.storage.MetaRepository metaRepository;
    private final RpsService rpsService;
    private final MarketService marketService;
    private final EastmoneyClient eastmoney;
    private final TencentClient tencent;
    private final ObjectMapper mapper;

    private static String stratName(String s) {
        if (STRAT_B.equals(s)) return "B·形态突破";
        if (STRAT_C.equals(s)) return "C·B2波段";
        return "A·保守综合";
    }

    private static boolean mainBoard(String c) {
        if (c == null || c.length() < 3) return false;
        return c.startsWith("600") || c.startsWith("601") || c.startsWith("603") || c.startsWith("605")
                || c.startsWith("000") || c.startsWith("001") || c.startsWith("002") || c.startsWith("003");
    }

    // ===== 定时 / 结算入口 =====

    public Map<String, Object> runDailyAuto() {
        ensureFreshData();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : ALL_STRATS) out.put(s, runDaily(s, false));
        return out;
    }

    private void ensureFreshData() {
        LocalDate today = LocalDate.now(SH);
        if (!isDataFresh(today)) {
            log.info("模拟盘：今日日K未就绪，先预热 130 日再结算");
            watchlistService.warmKlineCache(130);
        } else if (!today.equals(rpsRepository.maxTradeDate())) {
            try { rpsService.computeAndStore(); } catch (Exception e) { log.error("RPS 计算失败", e); }
        }
    }

    public Map<String, Object> runManual(String... strats) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : strats) {
            try { out.put(s, runDaily(s, true)); }
            catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", String.valueOf(e.getMessage()));
                out.put(s, err);
                log.error("模拟盘[{}]手动结算失败", stratName(s), e);
            }
        }
        out.put("finishedAt", LocalDateTime.now(SH).toString());
        lastManualRun = out;
        return out;
    }

    private volatile Map<String, Object> lastManualRun = null;
    public Map<String, Object> lastManualRun() {
        Map<String, Object> r = lastManualRun;
        return r == null ? Collections.singletonMap("none", true) : r;
    }

    public synchronized Map<String, Object> runDaily(String strat, boolean force) {
        LocalDateTime now = LocalDateTime.now(SH);
        LocalDate today = now.toLocalDate();
        ensureAccount(strat, now);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today.toString());
        if (!force && settledOn(strat, today)) { out.put("skipped", "今日已结算过"); return out; }
        LocalDate dataDate = latestDataDate();
        if (dataDate == null) { out.put("skipped", "无任何日K数据，先「刷新自选K线」"); return out; }
        out.put("dataDate", dataDate.toString());
        LocalDate settleDate = resolveSettleDate(today, dataDate, now.toLocalTime());
        if (settleDate == null) {
            out.put("skipped", "当日日K未就绪（最新数据 " + dataDate + "），未做任何操作 —— "
                    + "若今天是交易日，等 16:05 定时预热完再点，或手动点「刷新自选K线」；节假日则忽略本条");
            return out;
        }
        if (!settleDate.equals(today)) {
            if (settledOn(strat, settleDate)) {
                out.put("skipped", "已按 " + settleDate + " 收盘结算过（昨晚 19:30 定时或手动已跑），盘前无需重复");
                return out;
            }
            out.put("note", "盘前结算：按最新交易日 " + settleDate + " 收盘数据补跑（等同昨日 19:30 定时），新指令今日开盘成交");
        }
        if (!dataDate.equals(rpsRepository.maxTradeDate())) {
            try { rpsService.computeAndStore(); } catch (Exception e) { log.error("RPS 计算失败", e); }
        }

        List<String> filledNotes = fillPendingOrders(strat, dataDate, now);
        List<String> orderNotes = new ArrayList<>();
        if (STRAT_C.equals(strat)) {
            orderNotes.addAll(sellCAtClose(strat, settleDate, dataDate, now));
            orderNotes.addAll(generateBuyOrdersC(strat, settleDate, dataDate, now));
        } else {
            orderNotes.addAll(generateSellOrders(strat, settleDate, dataDate, now));
            orderNotes.addAll(generateBuyOrders(strat, settleDate, dataDate, now));
        }
        Map<String, Object> snap = snapshot(strat, settleDate);
        markSettled(strat, settleDate);

        out.put("filled", filledNotes);
        out.put("newOrders", orderNotes);
        out.putAll(snap);
        log.info("模拟盘[{}]结算完成 {}：成交 {} 笔，新指令 {} 条，总资产 {}",
                stratName(strat), settleDate, filledNotes.size(), orderNotes.size(), snap.get("totalValue"));
        return out;
    }

    private boolean settledOn(String strat, LocalDate d) {
        try { return d.toString().equals(metaRepository.get("sim.settled." + strat)); }
        catch (Exception e) { return false; }
    }

    private void markSettled(String strat, LocalDate d) {
        try { metaRepository.put("sim.settled." + strat, d.toString()); }
        catch (Exception e) { log.warn("模拟盘[{}]写结算标记失败: {}", stratName(strat), e.getMessage()); }
    }

    static LocalDate resolveSettleDate(LocalDate today, LocalDate dataDate, java.time.LocalTime time) {
        if (today.equals(dataDate)) return today;
        return time.isBefore(java.time.LocalTime.of(9, 25)) ? dataDate : null;
    }

    public synchronized Map<String, Object> manualTrade(String strat, String side, String code, int qty, double price) {
        LocalDateTime now = LocalDateTime.now(SH);
        ensureAccount(strat, now);
        Map<String, Object> out = new LinkedHashMap<>();
        if (code == null || !code.matches("\\d{6}") || qty <= 0 || price <= 0) {
            out.put("ok", false); out.put("error", "参数不合法（6位代码/正股数/正价格）"); return out;
        }
        String name = code;
        for (WatchItem it : watchItemRepository.findAll()) {
            if (code.equals(it.getCode())) { name = it.getName() == null ? code : it.getName(); break; }
        }
        double cash = num(repo.findAccount(strat).get("cash"));
        double amt = qty * price;
        if ("BUY".equalsIgnoreCase(side)) {
            if (qty % 100 != 0) { out.put("ok", false); out.put("error", "买入股数须为100的整数倍"); return out; }
            double fee = Math.max(5, amt * 0.00025);
            if (amt + fee > cash) { out.put("ok", false); out.put("error", "现金不足（需 " + round2(amt + fee) + "）"); return out; }
            repo.updateCash(strat, cash - amt - fee);
            mergePosition(strat, code, name, qty, amt + fee, now);
            repo.insertFilledOrder(strat, now.toLocalDate(), code, name, "BUY", qty, price, amt, fee, "手动补录买入", now);
            out.put("ok", true); out.put("message", String.format("已补录买入 %s %d股 @%.2f", name, qty, price));
        } else if ("SELL".equalsIgnoreCase(side)) {
            Map<String, Object> pos = findPosition(strat, code);
            if (pos == null) { out.put("ok", false); out.put("error", "无该持仓"); return out; }
            int posQty = (int) num(pos.get("qty"));
            if (qty > posQty) { out.put("ok", false); out.put("error", "超过持仓股数(" + posQty + ")"); return out; }
            double fee = Math.max(5, amt * 0.00025) + amt * 0.0005;
            repo.updateCash(strat, cash + amt - fee);
            int left = posQty - qty;
            if (left <= 0) repo.deletePosition(strat, code);
            else repo.upsertPosition(strat, code, str(pos.get("name")), left, num(pos.get("avg_cost")),
                    ((java.sql.Timestamp) pos.get("opened_at")).toLocalDateTime());
            repo.insertFilledOrder(strat, now.toLocalDate(), code, name, "SELL", qty, price, amt, fee, "手动矫正卖出", now);
            out.put("ok", true); out.put("message", String.format("已手动卖出 %s %d股 @%.2f", name, qty, price));
        } else {
            out.put("ok", false); out.put("error", "side 须为 BUY 或 SELL");
        }
        log.info("模拟盘[{}]手动矫正: {} {} {}股 @{} -> {}", stratName(strat), side, code, qty, price, out);
        if (Boolean.TRUE.equals(out.get("ok"))) {
            try { snapshot(strat, now.toLocalDate()); } catch (Exception e) { log.warn("矫正后快照刷新失败: {}", e.getMessage()); }
        }
        return out;
    }

    public synchronized void reset(String strat) {
        repo.resetAll(strat);
        repo.initAccount(strat, INITIAL_CASH, LocalDateTime.now(SH));
        log.info("模拟盘[{}]已重置，初始资金 {}", stratName(strat), INITIAL_CASH);
    }

    public Map<String, Object> closedTrades(String strat, int offset, int limit) {
        List<Map<String, Object>> filled = repo.listFilledAsc(strat);
        Map<String, double[]> pos = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        for (Map<String, Object> o : filled) {
            String code = str(o.get("code")), side = str(o.get("side"));
            int qty = (int) num(o.get("qty"));
            double price = num(o.get("price")), amount = num(o.get("amount")), fee = num(o.get("fee"));
            if (code != null) nameOf.put(code, str(o.get("name")));
            double[] p = pos.computeIfAbsent(code, k -> new double[3]);
            if ("BUY".equals(side)) {
                p[0] += qty; p[1] += amount + fee; p[2] += price * qty;
            } else if ("SELL".equals(side)) {
                if (p[0] <= 0) continue;
                int sellQty = (int) Math.min(qty, p[0]);
                double avgCostWithFee = p[1] / p[0], avgBuyRaw = p[2] / p[0];
                double costPortion = avgCostWithFee * sellQty;
                double pnl = (amount - fee) - costPortion;   // 卖出净收入 − 含费成本
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("code", code); t.put("name", nameOf.get(code));
                t.put("sellDate", str(o.get("trade_date")));
                t.put("qty", sellQty);
                t.put("buyPrice", round2(avgBuyRaw));
                t.put("sellPrice", round2(price));
                t.put("pnlAmt", round2(pnl));
                t.put("pnlPct", costPortion > 0 ? round2(pnl / costPortion * 100) : 0);
                t.put("reason", str(o.get("reason")));
                closed.add(t);
                p[1] -= costPortion; p[2] -= avgBuyRaw * sellQty; p[0] -= sellQty;
            }
        }
        Collections.reverse(closed);   // 最新卖出在前
        int total = closed.size();
        int from = Math.max(0, Math.min(offset, total));
        int to = Math.min(from + limit, total);
        List<Map<String, Object>> page = closed.subList(from, to);

        int good = 0, bad = 0;
        for (Map<String, Object> t : page) {
            List<DailyBar> r = dailyBarRepository.findRecent(str(t.get("code")), 1);
            if (r.isEmpty()) continue;
            double last = r.get(0).getClose(), sell = num(t.get("sellPrice"));
            if (sell <= 0) continue;
            double pct = (last - sell) / sell * 100;
            t.put("sincePct", round2(pct));
            t.put("sinceClose", round2(last));
            t.put("sinceDate", r.get(0).getTradeDate().toString());
            if (pct < 0) good++; else if (pct > 0) bad++;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", page);
        resp.put("sinceGood", good);   // 卖出后继续跌的笔数（本页）
        resp.put("sinceBad", bad);     // 卖出后反而涨的笔数（本页）
        resp.put("total", total);
        resp.put("offset", from);
        resp.put("limit", limit);
        return resp;
    }

    public Map<String, Object> overview(String strat) {
        ensureAccount(strat, LocalDateTime.now(SH));
        Map<String, Object> acc = repo.findAccount(strat);
        double cash = num(acc.get("cash"));
        double initial = num(acc.get("initial_cash"));

        List<Map<String, Object>> positions = repo.listPositions(strat);
        double posValue = 0;
        List<Map<String, Object>> posOut = new ArrayList<>();
        for (Map<String, Object> p : positions) {
            String code = str(p.get("code"));
            int qty = (int) num(p.get("qty"));
            double cost = num(p.get("avg_cost"));
            List<DailyBar> r2 = dailyBarRepository.findRecent(code, 2);
            Double close = r2.isEmpty() ? null : r2.get(0).getClose();
            double cachedClose = close != null ? close : cost;
            double cur = cachedClose;
            LocalDate openedDate = ((java.sql.Timestamp) p.get("opened_at")).toLocalDateTime().toLocalDate();
            LocalDate barDate = r2.isEmpty() ? null : r2.get(0).getTradeDate();
            double dayBase = (barDate != null && openedDate.equals(barDate)) || r2.size() < 2
                    ? cost : r2.get(1).getClose();
            posValue += qty * cur;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", str(p.get("name")));
            m.put("qty", qty);
            m.put("avgCost", round2(cost));
            m.put("close", round2(cur));
            m.put("pnlPct", cost > 0 ? round2((cur / cost - 1) * 100) : 0);
            m.put("pnlAmt", round2(qty * (cur - cost)));
            m.put("dayPnl", round2(qty * (cur - dayBase)));
            m.put("value", round2(qty * cur));
            m.put("cachedClose", round2(cachedClose));
            posOut.add(m);
        }
        double total = cash + posValue;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strat);
        out.put("initialCash", initial);
        out.put("cash", round2(cash));
        out.put("positionValue", round2(posValue));
        out.put("totalValue", round2(total));
        out.put("totalPnlPct", initial > 0 ? round2((total / initial - 1) * 100) : 0);
        out.put("startedAt", String.valueOf(acc.get("started_at")));
        out.put("positions", posOut);
        out.put("pendingOrders", repo.listPendingOrders(strat));
        out.put("snapshots", repo.listSnapshots(strat));
        return out;
    }

    public Map<String, Object> orders(String strat, int offset, int limit) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", repo.listOrdersPage(strat, offset, limit));
        resp.put("total", repo.countOrders(strat));
        resp.put("offset", offset);
        resp.put("limit", limit);
        return resp;
    }

    // ===== 成交 =====

    private List<String> fillPendingOrders(String strat, LocalDate dataDate, LocalDateTime now) {
        List<String> notes = new ArrayList<>();
        double cash = num(repo.findAccount(strat).get("cash"));
        for (Map<String, Object> o : repo.listPendingOrders(strat)) {
            long id = (long) num(o.get("id"));
            String code = str(o.get("code")), name = str(o.get("name")), side = str(o.get("side"));
            LocalDate orderDate = ((java.sql.Date) o.get("trade_date")).toLocalDate();
            if (!dataDate.isAfter(orderDate)) continue;   // T+1：生成日之后才成交
            List<DailyBar> recent = dailyBarRepository.findRecent(code, 2);
            if (recent.size() < 2 || !recent.get(0).getTradeDate().equals(dataDate)) continue;   // 停牌顺延
            double open = recent.get(0).getOpen(), prevClose = recent.get(1).getClose();
            if (open <= 0 || prevClose <= 0) continue;
            double dayHigh = recent.get(0).getHigh(), dayLow = recent.get(0).getLow(), dayClose = recent.get(0).getClose();

            if ("BUY".equals(side)) {
                if (open >= prevClose * 1.095) { repo.cancelOrder(id, "开盘涨停，放弃追买"); notes.add("放弃买入 " + name + "（开盘涨停）"); continue; }
                double budget = num(o.get("budget"));
                int qty = (int) Math.floor(budget / (open * 100)) * 100;
                if (qty < 100) {
                    repo.cancelOrder(id, "预算不足一手（开盘价 " + round2(open) + "，一手需 " + round2(open * 100) + "）");
                    notes.add("放弃买入 " + name + "（预算不足一手）");
                    continue;
                }
                while (qty >= 100 && qty * open + Math.max(5, qty * open * 0.00025) > cash) qty -= 100;
                if (qty < 100) { repo.cancelOrder(id, "账户现金不足一手"); notes.add("放弃买入 " + name + "（现金不足）"); continue; }
                double amt = qty * open;
                double fee = Math.max(5, amt * 0.00025);
                cash -= amt + fee;
                mergePosition(strat, code, name, qty, amt + fee, now);
                Double altP = dayLow <= prevClose ? prevClose : null;
                String altN = altP != null ? "挂昨收成交" : "未回踩昨收，挂单不成交";
                repo.fillOrder(id, qty, open, amt, fee, now, altP, altN);
                notes.add(String.format("买入 %s %d股 @%.2f", name, qty, open));
            } else {
                Map<String, Object> pos = findPosition(strat, code);
                if (pos == null) { repo.cancelOrder(id, "无持仓"); continue; }
                int posQty = (int) num(pos.get("qty"));
                int qty = Math.min(o.get("qty") == null ? posQty : (int) num(o.get("qty")), posQty);
                if (open <= prevClose * 0.905) { notes.add("卖出顺延 " + name + "（开盘跌停）"); continue; }
                double amt = qty * open;
                double fee = Math.max(5, amt * 0.00025) + amt * 0.0005;
                cash += amt - fee;
                int left = posQty - qty;
                if (left <= 0) repo.deletePosition(strat, code);
                else repo.upsertPosition(strat, code, str(pos.get("name")), left, num(pos.get("avg_cost")),
                        ((java.sql.Timestamp) pos.get("opened_at")).toLocalDateTime());
                double altP = dayHigh >= prevClose ? prevClose : dayClose;
                String altN = dayHigh >= prevClose ? "挂昨收成交" : "未触及昨收，收盘兜底";
                repo.fillOrder(id, qty, open, amt, fee, now, altP, altN);
                notes.add(String.format("卖出 %s %d股 @%.2f", name, qty, open));
            }
        }
        repo.updateCash(strat, cash);
        return notes;
    }

    private void mergePosition(String strat, String code, String name, int qty, double costWithFee, LocalDateTime now) {
        Map<String, Object> pos = findPosition(strat, code);
        if (pos == null) {
            repo.upsertPosition(strat, code, name, qty, costWithFee / qty, now);
        } else {
            int oldQty = (int) num(pos.get("qty"));
            double oldCost = num(pos.get("avg_cost"));
            int newQty = oldQty + qty;
            double avg = (oldQty * oldCost + costWithFee) / newQty;
            repo.upsertPosition(strat, code, name, newQty, avg,
                    ((java.sql.Timestamp) pos.get("opened_at")).toLocalDateTime());
        }
    }

    // ===== 卖出纪律 =====

    private List<String> generateSellOrders(String strat, LocalDate today, LocalDate dataDate, LocalDateTime now) {
        List<String> notes = new ArrayList<>();
        Set<String> pendingCodes = pendingCodes(strat);
        for (Map<String, Object> p : repo.listPositions(strat)) {
            String code = str(p.get("code")), name = str(p.get("name"));
            if (pendingCodes.contains(code)) continue;
            List<DailyBar> bars = ascBars(code, 130);
            if (bars.isEmpty() || !bars.get(bars.size() - 1).getTradeDate().equals(dataDate)) continue;
            AnalysisEngine.ExpertResult e = AnalysisEngine.expert(bars);
            if (!e.ok) continue;
            double close = e.close;
            int qty = (int) num(p.get("qty"));
            double cost = num(p.get("avg_cost"));
            double profit = cost > 0 ? close / cost - 1 : 0;
            if (close < e.hardStop && e.volRatio > 1.2) {
                repo.insertOrder(strat, today, code, name, "SELL", qty, null, "放量跌破前低 " + round2(e.hardStop) + "，硬止损清仓", null, now);
                notes.add("卖出 " + name + " 全部（放量破前低，硬止损）");
            } else if (e.chandelier != null && close < e.chandelier) {
                repo.insertOrder(strat, today, code, name, "SELL", qty, null,
                        "收盘跌破 ATR 吊灯止损线(" + round2(e.chandelier) + ")，趋势结束", null, now);
                notes.add("卖出 " + name + " 全部（跌破ATR吊灯线）");
            } else if (close <= cost * (1 - HARD_STOP_PCT)) {
                repo.insertOrder(strat, today, code, name, "SELL", qty, null,
                        "含费浮亏达硬止损 −" + (int) (HARD_STOP_PCT * 100) + "%（成本 " + round2(cost) + " → 收盘 " + round2(close) + "），阴跌离场", null, now);
                notes.add("卖出 " + name + " 全部（硬止损 −" + (int) (HARD_STOP_PCT * 100) + "%，阴跌离场）");
            } else if (profit >= TAKE_PROFIT_FULL) {
                repo.insertOrder(strat, today, code, name, "SELL", qty, null,
                        "止盈+" + (int) (TAKE_PROFIT_FULL * 100) + "%（成本 " + round2(cost) + " → 收盘 " + round2(close) + "），清仓落袋", null, now);
                notes.add("卖出 " + name + " 全部（止盈+" + (int) (TAKE_PROFIT_FULL * 100) + "%，落袋为安）");
            } else if (profit >= TAKE_PROFIT_HALF && qty >= 200
                    && !repo.hasSellWithReasonSince(strat, code, "止盈+" + (int) (TAKE_PROFIT_HALF * 100) + "%",
                        ((java.sql.Timestamp) p.get("opened_at")).toLocalDateTime())) {
                int half = qty / 200 * 100;
                if (half >= 100) {
                    repo.insertOrder(strat, today, code, name, "SELL", half, null,
                            "止盈+" + (int) (TAKE_PROFIT_HALF * 100) + "%（成本 " + round2(cost) + " → 收盘 " + round2(close) + "），减半先落袋", null, now);
                    notes.add("减半 " + name + " " + half + "股（止盈+" + (int) (TAKE_PROFIT_HALF * 100) + "%，先落袋一半）");
                }
            } else if (e.overext && e.r1 != null && close >= e.r1 * 0.97 && qty >= 200) {
                int half = qty / 200 * 100;
                if (half >= 100) {
                    repo.insertOrder(strat, today, code, name, "SELL", half, null, "短线透支且撞压力 " + round2(e.r1) + "，减半兑现", null, now);
                    notes.add("减半 " + name + " " + half + "股（透支撞压力）");
                }
            }
        }
        return notes;
    }

    // ===== 选股买入 =====

    static final class Cand {   // 包内可见：测试要断言选票产出
        String code, name, pattern, reason;
        double atr, price;
        int sortA;      // 一级排序（降序）
        double sortB;   // 二级排序（降序）
    }

    private List<String> generateBuyOrders(String strat, LocalDate today, LocalDate dataDate, LocalDateTime now) {
        List<String> notes = new ArrayList<>();
        Set<String> pendingCodes = pendingCodes(strat);
        List<Map<String, Object>> positions = repo.listPositions(strat);
        List<Map<String, Object>> pending = repo.listPendingOrders(strat);
        long pendingBuys = pending.stream().filter(o -> "BUY".equals(str(o.get("side")))).count();

        int pendingFullSells = 0;
        double estProceeds = 0;
        for (Map<String, Object> o : pending) {
            if (!"SELL".equals(str(o.get("side")))) continue;
            Map<String, Object> pos = findPosition(strat, str(o.get("code")));
            if (pos == null) continue;
            int sellQty = o.get("qty") == null ? (int) num(pos.get("qty")) : (int) num(o.get("qty"));
            if (sellQty >= (int) num(pos.get("qty"))) {
                pendingFullSells++;
                Double close = latestClose(str(o.get("code")));
                estProceeds += sellQty * (close != null ? close : num(pos.get("avg_cost"))) * 0.995;
            }
        }
        int rawFree = Math.max(0, MAX_POSITIONS - positions.size() - (int) pendingBuys);
        int slots = MAX_POSITIONS - (positions.size() - pendingFullSells) - (int) pendingBuys;
        if (slots <= 0) {
            notes.add("（说明）未开新仓：仓位已满（持仓 " + positions.size() + " + 待成交买单 " + pendingBuys + " ≥ " + MAX_POSITIONS + "）");
            return notes;
        }

        MarketPulse mp = marketService.last();
        if (mp != null && (mp.isForcedDefensive() || mp.getTemperature() < MIN_MARKET_TEMP)) {
            log.info("模拟盘[{}]：大盘温度 {}（防守={}）低于 {}，不拦、只提示", stratName(strat),
                    mp.getTemperature(), mp.isForcedDefensive(), MIN_MARKET_TEMP);
            notes.add(String.format("（提示）大盘温度 %.0f%s 低于 %.0f，温度门槛已取消不拦新仓——可手动平仓",
                    mp.getTemperature(), mp.isForcedDefensive() ? "（风控防守信号）" : "", MIN_MARKET_TEMP));
        }

        Set<String> held = new HashSet<>();
        for (Map<String, Object> p : positions) held.add(str(p.get("code")));

        List<Cand> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        LocalDateTime cooledBefore = LocalDateTime.now(SH).minusDays(COOLDOWN_DAYS);
        for (WatchItem it : watchItemRepository.findAll()) {
            String code = it.getCode(), name = it.getName() == null ? "" : it.getName();
            if (code == null || !seen.add(code)) continue;
            if (code.startsWith("920") || code.startsWith("300") || code.startsWith("301")
                    || code.startsWith("688") || code.startsWith("689")) continue;
            if (name.contains("ST") || name.contains("退")) continue;
            if (held.contains(code) || pendingCodes.contains(code)) continue;
            LocalDateTime lastSell = repo.lastSellFilledAt(strat, code);
            if (lastSell != null && lastSell.isAfter(cooledBefore)) continue;
            List<DailyBar> bars = ascBars(code, 130);
            if (bars.size() < 70 || !bars.get(bars.size() - 1).getTradeDate().equals(dataDate)) continue;
            double atr = AnalysisEngine.atr22At(bars, bars.size() - 1);
            if (Double.isNaN(atr) || atr <= 0) continue;
            Double rps120 = rps120Of(code);

            Cand c;
            if (STRAT_B.equals(strat)) c = judgeStrategyB(code, name, bars, atr, rps120);
            else c = judgeStrategyA(code, name, bars, atr, rps120);
            if (c != null) candidates.add(c);
        }
        candidates.sort((x, y) -> {
            int c = Integer.compare(y.sortA, x.sortA);
            return c != 0 ? c : Double.compare(y.sortB, x.sortB);
        });
        if (candidates.isEmpty()) {
            notes.add("（说明）未开新仓：" + dataDate + " 无票通过 " + stratName(strat) + " 判据，空仓等待");
            return notes;
        }

        double cash = num(repo.findAccount(strat).get("cash"));
        double equity = cash + positionValue(positions);
        double cashAvail = cash + estProceeds;
        int filled = 0;
        for (Cand c : candidates) {
            if (filled >= slots || cashAvail < 3000) break;
            double riskBudget = equity * RISK_PCT / (2 * c.atr) * c.price;
            double oneLot = c.price * 100;
            if (riskBudget < oneLot * 1.02) {
                double oneLotRisk = 100 * 2 * c.atr;
                if (oneLotRisk <= equity * RISK_PCT * RISK_RELAX) riskBudget = oneLot * 1.02;
                else continue;
            }
            double budget = Math.min(Math.min(equity * POSITION_PCT, riskBudget), cashAvail / (slots - filled));
            if (budget < 3000 || budget < oneLot * 1.02) continue;
            cashAvail -= budget;
            String backfill = filled >= rawFree ? "（补位）" : "";
            repo.insertOrder(strat, today, c.code, c.name, "BUY", null, round2(budget), c.reason + backfill, c.pattern, now);
            notes.add(String.format("买入 %s 预算 %.0f（%s）", c.name, budget, c.reason + backfill));
            filled++;
        }
        return notes;
    }

    // ===== 策略C·B2波段 =====
    static final double C_TAKE_PROFIT = 0.15;
    static final double C_STOP_LOSS = -0.10;
    static final int C_MAX_HOLD_DAYS = 10;

    static String cExitReason(double close, double avgCost, int heldDays) {
        if (heldDays <= 1 || avgCost <= 0) return null;
        double pnl = close / avgCost - 1;
        if (pnl >= C_TAKE_PROFIT - 1e-9) return String.format("B2波段：收盘涨%.1f%%≥15%%止盈（第%d日）", pnl * 100, heldDays);
        if (pnl <= C_STOP_LOSS + 1e-9) return String.format("B2波段：收盘跌%.1f%%≤−10%%止损（第%d日）", pnl * 100, heldDays);
        if (heldDays >= C_MAX_HOLD_DAYS) return String.format("B2波段：第%d个交易日到期（收盘%+.1f%%）", heldDays, pnl * 100);
        return null;
    }

    static int heldTradingDays(List<DailyBar> barsAsc, LocalDate opened, LocalDate dataDate) {
        int n = 0;
        for (DailyBar b : barsAsc) {
            LocalDate d = b.getTradeDate();
            if (!d.isBefore(opened) && !d.isAfter(dataDate)) n++;
        }
        return n;
    }

    private List<String> sellCAtClose(String strat, LocalDate today, LocalDate dataDate, LocalDateTime now) {
        List<String> notes = new ArrayList<>();
        double cash = num(repo.findAccount(strat).get("cash"));
        for (Map<String, Object> p : repo.listPositions(strat)) {
            String code = str(p.get("code")), name = str(p.get("name"));
            LocalDate opened = ((java.sql.Timestamp) p.get("opened_at")).toLocalDateTime().toLocalDate();
            List<DailyBar> bars = ascBars(code, C_MAX_HOLD_DAYS + 20);
            if (bars.isEmpty() || !bars.get(bars.size() - 1).getTradeDate().equals(dataDate)) continue;   // 停牌顺延
            double close = bars.get(bars.size() - 1).getClose();
            int held = heldTradingDays(bars, opened, dataDate);
            String reason = cExitReason(close, num(p.get("avg_cost")), held);
            if (reason == null) continue;
            int qty = (int) num(p.get("qty"));
            double amt = qty * close;
            double fee = Math.max(5, amt * 0.00025) + amt * 0.0005;
            cash += amt - fee;
            repo.deletePosition(strat, code);
            repo.insertFilledOrder(strat, today, code, name, "SELL", qty, close, amt, fee, reason, now);
            notes.add(String.format("收盘卖 %s %d股 @%.2f（%s）", name, qty, close, reason));
        }
        repo.updateCash(strat, cash);
        return notes;
    }

    private List<String> generateBuyOrdersC(String strat, LocalDate today, LocalDate dataDate, LocalDateTime now) {
        List<String> notes = new ArrayList<>();
        Set<String> pendingCodes = pendingCodes(strat);
        List<Map<String, Object>> positions = repo.listPositions(strat);
        long pendingBuys = repo.listPendingOrders(strat).stream().filter(o -> "BUY".equals(str(o.get("side")))).count();
        int slots = MAX_POSITIONS - positions.size() - (int) pendingBuys;
        if (slots <= 0) return notes;
        Set<String> held = new HashSet<>();
        for (Map<String, Object> p : positions) held.add(str(p.get("code")));
        List<Cand> cands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WatchItem it : watchItemRepository.findAll()) {
            String code = it.getCode(), name = it.getName() == null ? "" : it.getName();
            if (code == null || !seen.add(code)) continue;
            if (!mainBoard(code) || name.contains("ST") || name.contains("退")) continue;
            if (held.contains(code) || pendingCodes.contains(code)) continue;
            List<DailyBar> bars = ascBars(code, 130);
            if (bars.size() < 70 || !bars.get(bars.size() - 1).getTradeDate().equals(dataDate)) continue;
            if (!AnalysisEngine.b2BuyIndices(bars).contains(bars.size() - 2)) continue;   // 前一根出非潜在B2
            AnalysisEngine.ScoreResult sr = AnalysisEngine.scoreStock(bars, rps120Of(code));
            Cand c = new Cand();
            c.code = code; c.name = name; c.price = bars.get(bars.size() - 1).getClose(); c.pattern = "B2波段";
            c.sortB = sr.score;
            c.reason = "B2波段：指标分 " + sr.score + "，B2后第3日开盘买，最多持有10个交易日";
            cands.add(c);
        }
        cands.sort((x, y) -> Double.compare(y.sortB, x.sortB));   // 按指标分
        if (cands.isEmpty()) {
            notes.add("（说明）未开新仓：" + dataDate + " 无票通过 " + stratName(strat) + " 判据，空仓等待");
            return notes;
        }

        double cash = num(repo.findAccount(strat).get("cash"));
        double equity = cash + positionValue(positions);
        double cashAvail = cash;
        int filled = 0;
        for (Cand c : cands) {
            if (filled >= slots || cashAvail < 3000) break;
            double oneLot = c.price * 100;
            double budget = Math.min(equity * POSITION_PCT, cashAvail / (slots - filled));
            if (budget < oneLot * 1.02) continue;   // 预算不足一手
            cashAvail -= budget;
            repo.insertOrder(strat, today, c.code, c.name, "BUY", null, round2(budget), c.reason, c.pattern, now);
            notes.add(String.format("买入 %s 预算 %.0f（%s）", c.name, budget, c.reason));
            filled++;
        }
        return notes;
    }

    private Cand judgeStrategyA(String code, String name, List<DailyBar> bars, double atr, Double rps120) {
        if (rps120 == null || rps120 < MIN_RPS120) return null;
        AnalysisEngine.ScoreResult s = AnalysisEngine.scoreStock(bars, rps120);
        if (s.score < MIN_SCORE) return null;
        AnalysisEngine.ExpertResult e = AnalysisEngine.expert(bars);
        if (!e.ok || !"REVERSAL_LIKELY".equals(e.verdict) || e.overext) return null;
        if ("DOWNTREND".equals(e.regime) || e.close <= e.ma60) return null;
        Cand c = new Cand();
        c.code = code; c.name = name; c.atr = atr; c.price = e.close; c.pattern = "综合";
        c.sortA = s.score; c.sortB = e.score;
        c.reason = String.format("指标分 %d + 形态反转(%d分)·不透支 + RPS %.0f", s.score, e.score, rps120);
        return c;
    }

    private Cand judgeStrategyB(String code, String name, List<DailyBar> bars, double atr, Double rps120) {
        String pattern = AnalysisEngine.detectBreakout(bars);
        if (pattern == null) return null;
        double price = bars.get(bars.size() - 1).getClose();
        Cand c = new Cand();
        c.code = code; c.name = name; c.atr = atr; c.price = price; c.pattern = pattern;
        c.sortA = "平台突破".equals(pattern) ? 1 : 0;
        c.sortB = rps120 == null ? 0 : rps120;         // 同形态按相对强度排
        c.reason = "形态突破：" + pattern + (rps120 != null ? String.format("（RPS %.0f）", rps120) : "");
        return c;
    }

    public synchronized Map<String, Object> closePosition(String strat, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            LocalDateTime now = LocalDateTime.now(SH);
            Map<String, Object> pos = findPosition(strat, code);
            if (pos == null) { out.put("ok", false); out.put("error", "无该持仓"); return out; }
            List<DailyBar> recent = dailyBarRepository.findRecent(code, 1);
            if (recent.isEmpty()) { out.put("ok", false); out.put("error", "该股无日K缓存，拿不到收盘价"); return out; }
            DailyBar last = recent.get(0);
            LocalDate opened = ((java.sql.Timestamp) pos.get("opened_at")).toLocalDateTime().toLocalDate();
            if (!opened.isBefore(last.getTradeDate())) {
                out.put("ok", false);
                out.put("error", "该仓位为 " + opened + " 买入，T+1 规则当日不能卖（最新K线日 " + last.getTradeDate() + "）");
                return out;
            }
            String name = str(pos.get("name"));
            int qty = (int) num(pos.get("qty"));
            double price = last.getClose();
            double amt = qty * price;
            double fee = Math.max(5, amt * 0.00025) + amt * 0.0005;
            double cash = num(repo.findAccount(strat).get("cash"));
            repo.updateCash(strat, cash + amt - fee);
            repo.deletePosition(strat, code);
            repo.insertFilledOrder(strat, now.toLocalDate(), code, name, "SELL", qty, price, amt, fee,
                    "手动平仓：按最新收盘价（" + last.getTradeDate() + "）", now);
            try { snapshot(strat, now.toLocalDate()); } catch (Exception e) { log.warn("平仓后快照刷新失败: {}", e.getMessage()); }
            out.put("ok", true);
            out.put("message", String.format("已平仓 %s %d股 @%.2f（%s 收盘价，含费净入 %.0f）",
                    name, qty, price, last.getTradeDate(), amt - fee));
            log.info("模拟盘[{}]手动平仓: {} {}股 @{}", stratName(strat), code, qty, price);
            return out;
        } catch (Throwable e) {
            log.error("手动平仓异常", e);
            out.put("ok", false);
            out.put("error", "平仓异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return out;
        }
    }

    private Double rps120Of(String code) {
        Map<String, Object> row = rpsRepository.findByCode(code);
        return row == null || row.get("rps120") == null ? null : ((Number) row.get("rps120")).doubleValue();
    }

    // ===== 快照 / 工具 =====

    private Map<String, Object> snapshot(String strat, LocalDate today) {
        Map<String, Object> acc = repo.findAccount(strat);
        double cash = num(acc.get("cash"));
        double initial = num(acc.get("initial_cash"));
        List<Map<String, Object>> positions = repo.listPositions(strat);
        double posValue = positionValue(positions);
        double total = cash + posValue;
        double pnlPct = initial > 0 ? (total / initial - 1) * 100 : 0;

        Double hs300 = null;
        try {
            List<DailyBar> idx = eastmoney.fetchKline("000300", "1.000300", 140);
            if (idx.isEmpty()) idx = tencent.fetchKline("000300", "1.000300", 140);
            if (!idx.isEmpty()) {
                hs300 = idx.get(idx.size() - 1).getClose();
                dailyBarRepository.upsertBatch(idx);
            }
        } catch (Exception e) { /* 基准拿不到不影响结算 */ }

        String payload = "[]";
        try { payload = mapper.writeValueAsString(positions); } catch (Exception ignore) {}
        repo.upsertSnapshot(strat, today, round2(total), round2(cash), round2(posValue), round2(pnlPct), hs300, payload);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalValue", round2(total));
        m.put("cash", round2(cash));
        m.put("positionValue", round2(posValue));
        m.put("totalPnlPct", round2(pnlPct));
        return m;
    }

    private void ensureAccount(String strat, LocalDateTime now) {
        if (repo.findAccount(strat) == null) repo.initAccount(strat, INITIAL_CASH, now);
    }

    private boolean isDataFresh(LocalDate today) { return today.equals(latestDataDate()); }

    private LocalDate latestDataDate() {
        LocalDate max = null;
        int checked = 0;
        Set<String> seen = new HashSet<>();
        for (WatchItem it : watchItemRepository.findAll()) {
            if (it.getCode() == null || it.getCode().startsWith("920") || !seen.add(it.getCode())) continue;
            LocalDate d = dailyBarRepository.findLatestDate(it.getCode());
            if (d != null && (max == null || d.isAfter(max))) max = d;
            if (++checked >= 30) break;
        }
        return max;
    }

    private double positionValue(List<Map<String, Object>> positions) {
        double v = 0;
        for (Map<String, Object> p : positions) {
            Double close = latestClose(str(p.get("code")));
            v += num(p.get("qty")) * (close != null ? close : num(p.get("avg_cost")));
        }
        return v;
    }

    private Double latestClose(String code) {
        List<DailyBar> r = dailyBarRepository.findRecent(code, 1);
        return r.isEmpty() ? null : r.get(0).getClose();
    }

    private List<DailyBar> ascBars(String code, int n) {
        List<DailyBar> bars = dailyBarRepository.findRecent(code, n);
        Collections.reverse(bars);
        return bars;
    }

    private Map<String, Object> findPosition(String strat, String code) {
        for (Map<String, Object> p : repo.listPositions(strat)) if (code.equals(str(p.get("code")))) return p;
        return null;
    }

    private Set<String> pendingCodes(String strat) {
        Set<String> s = new HashSet<>();
        for (Map<String, Object> o : repo.listPendingOrders(strat)) s.add(str(o.get("code")));
        return s;
    }

    private static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double round2(double v) { return Math.round(v * 100d) / 100d; }
}
