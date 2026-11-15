package com.smbox.service;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.datasource.TencentClient;
import com.smbox.model.DailyBar;
import com.smbox.model.MarketPulse;
import com.smbox.model.Quote;
import com.smbox.storage.MarketSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketService {
    private final EastmoneyClient eastmoney;
    private final TencentClient tencent;
    private final MarketSnapshotRepository repo;
    private final ObjectMapper mapper;

    // 四大指数：{名称, 代码, secid}
    private static final String[][] INDICES = {
            {"上证指数", "000001", "1.000001"},
            {"深证成指", "399001", "0.399001"},
            {"创业板指", "399006", "0.399006"},
            {"沪深300", "000300", "1.000300"},
    };

    // 五维权重
    private static final double W_TREND = 0.30, W_BREADTH = 0.25, W_CAPITAL = 0.20,
            W_VOLUME = 0.15, W_MOMENTUM = 0.10;

    public MarketPulse compute() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        log.info("大盘研判：开始计算 trade_date={}", today);

        List<MarketPulse.Reason> reasons = new ArrayList<>();

        // ===== 1. 市场宽度 =====
        List<Quote> quotes = eastmoney.fetchAllQuotes(false);
        int up = 0, down = 0, flat = 0, limitUp = 0, limitDown = 0;
        double sumChg = 0, totalAmount = 0;
        for (Quote q : quotes) {
            double c = q.getChangePct();
            if (c > 0.01) up++; else if (c < -0.01) down++; else flat++;
            if (c >= 9.8) limitUp++;
            if (c <= -9.8) limitDown++;
            sumChg += c;
            totalAmount += q.getAmount();
        }
        int n = Math.max(1, up + down);
        double upRatio = (double) up / n;
        double avgChg = quotes.isEmpty() ? 0 : sumChg / quotes.size();
        double breadthScore = clamp(upRatio * 100 + (limitUp - limitDown) * 0.3);
        if (!quotes.isEmpty()) {
            reasons.add(reason(upRatio >= 0.5 ? "good" : "bad",
                    String.format("涨跌家数 %d:%d（上涨占比 %.0f%%）", up, down, upRatio * 100)));
            if (limitUp + limitDown > 0) {
                reasons.add(reason(limitUp >= limitDown ? "good" : "bad",
                        String.format("涨停 %d / 跌停 %d", limitUp, limitDown)));
            }
        } else {
            reasons.add(reason("warn", "全市场快照拉取为空，宽度维度按中性计"));
            breadthScore = 50;
        }

        // ===== 2. 指数趋势 + 上证量能/动量 =====
        List<MarketPulse.IndexCard> cards = new ArrayList<>();
        double trendSum = 0; int trendCnt = 0;
        List<DailyBar> shBars = null;
        for (String[] idx : INDICES) {
            List<DailyBar> bars = fetchIndex(idx[1], idx[2], 65);
            if (bars.isEmpty()) {
                reasons.add(reason("warn", idx[0] + " K 线拉取失败，跳过"));
                continue;
            }
            if (idx[1].equals("000001")) shBars = bars;
            double close = bars.get(bars.size() - 1).getClose();
            double ma20 = ma(bars, 20), ma60 = ma(bars, 60);
            double chg = changePct(bars);
            boolean aboveMa20 = close > ma20, aboveMa60 = close > ma60, bullish = ma20 > ma60;
            boolean slopeUp = slopeUp(bars, 20);
            double t = 50;
            t += aboveMa20 ? 15 : -15;
            t += aboveMa60 ? 15 : -15;
            t += bullish ? 12 : -12;
            t += slopeUp ? 8 : -8;
            trendSum += clamp(t); trendCnt++;
            cards.add(MarketPulse.IndexCard.builder()
                    .name(idx[0]).code(idx[1]).close(round2(close)).changePct(round2(chg))
                    .aboveMa20(aboveMa20).aboveMa60(aboveMa60).bullish(bullish).build());
        }
        double trendScore = trendCnt > 0 ? trendSum / trendCnt : 50;
        int above20 = (int) cards.stream().filter(MarketPulse.IndexCard::isAboveMa20).count();
        if (!cards.isEmpty()) {
            reasons.add(reason(above20 >= cards.size() - above20 ? "good" : "bad",
                    String.format("%d/%d 指数站上 20 日线", above20, cards.size())));
        }

        double volumeScore = 50, momentumScore = 50;
        boolean shUp = true;
        double shChg = 0;
        if (shBars != null && shBars.size() >= 21) {
            double todayAmt = shBars.get(shBars.size() - 1).getAmount();
            double ma20Amt = maAmount(shBars, 20);
            double ratio = ma20Amt > 0 ? todayAmt / ma20Amt : 1;
            shChg = changePct(shBars);
            shUp = shChg >= 0;
            double chg5 = changePctN(shBars, 5);
            volumeScore = shUp ? clamp(50 + (ratio - 1) * 60) : clamp(50 - (ratio - 1) * 60);
            momentumScore = clamp(50 + shChg * 8 + chg5 * 2.5);
            reasons.add(reason(ratio >= 1 ? (shUp ? "good" : "bad") : "info",
                    String.format("上证成交额为 20 日均量的 %.0f%%（%s）", ratio * 100,
                            ratio >= 1.1 ? (shUp ? "放量上攻" : "放量下跌") : ratio <= 0.9 ? "缩量" : "平量")));
        } else {
            reasons.add(reason("warn", "上证指数量能数据不足，量能/动量按中性计"));
        }

        // ===== 3. 大盘资金 =====
        double capitalScore = 50, netToday = 0, net5d = 0;
        List<EastmoneyClient.FlowPoint> flow = eastmoney.fetchMarketMoneyFlow(25);
        if (flow.size() >= 6) {
            netToday = flow.get(flow.size() - 1).getMainNetInflow();
            int m = flow.size();
            for (int i = Math.max(0, m - 5); i < m; i++) net5d += flow.get(i).getMainNetInflow();
            int from = Math.max(0, m - 21);
            double mean = 0; int cnt = 0;
            for (int i = from; i < m - 1; i++) { mean += flow.get(i).getMainNetInflow(); cnt++; }
            mean = cnt > 0 ? mean / cnt : 0;
            double var = 0;
            for (int i = from; i < m - 1; i++) { double d = flow.get(i).getMainNetInflow() - mean; var += d * d; }
            double std = cnt > 1 ? Math.sqrt(var / cnt) : 0;
            double z = std > 0 ? (netToday - mean) / std : 0;
            capitalScore = clamp(50 + z * 20);
            reasons.add(reason(netToday >= mean ? "good" : "bad",
                    String.format("大盘主力今日净%s %.1f 亿（近 20 日均值 %.1f 亿，相对%s）",
                            netToday >= 0 ? "流入" : "流出", Math.abs(netToday) / 1e8, mean / 1e8,
                            netToday >= mean ? "偏强" : "偏弱")));
        } else {
            reasons.add(reason("warn", "大盘资金流数据不足，资金维度按中性计"));
        }

        // ===== 4. 温度 =====
        double temp = W_TREND * trendScore + W_BREADTH * breadthScore + W_CAPITAL * capitalScore
                + W_VOLUME * volumeScore + W_MOMENTUM * momentumScore;
        temp = clamp(temp);

        boolean forced = false;
        if (shBars != null && shBars.size() >= 61) {
            boolean belowMa60 = shBars.get(shBars.size() - 1).getClose() < ma(shBars, 60);
            boolean heavyDrop = !shUp && volumeScore < 45;
            boolean panicBreadth = limitDown > limitUp * 2 && down > up * 2;
            boolean weakCapital = capitalScore < 40;
            forced = belowMa60 && heavyDrop && panicBreadth && weakCapital;
        }

        String advice;
        if (temp >= 78) advice = "强势";
        else if (temp >= 62) advice = "偏强";
        else if (temp >= 48) advice = "中性";
        else if (temp >= 32) advice = "偏弱";
        else advice = "弱势";

        if (forced) {
            reasons.add(0, reason("warn",
                    "触发风控条件：指数破 60 日线 + 放量下跌 + 恐慌跌停 + 资金流出"));
        }

        String summary = String.format("大盘温度 %.0f 分，%s", temp, advice);

        MarketPulse pulse = MarketPulse.builder()
                .tradeDate(today).temperature(round2(temp)).advice(advice)
                .summary(summary).forcedDefensive(forced)
                .trendScore(round2(trendScore)).breadthScore(round2(breadthScore))
                .capitalScore(round2(capitalScore)).volumeScore(round2(volumeScore))
                .momentumScore(round2(momentumScore))
                .upCount(up).downCount(down).flatCount(flat).limitUp(limitUp).limitDown(limitDown)
                .avgChangePct(round2(avgChg)).totalAmount(totalAmount)
                .mainNetInflowToday(netToday).mainNetInflow5d(net5d)
                .indices(cards).reasons(reasons)
                .build();

        // 归档
        try {
            LocalDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            repo.upsert(today, mapper.writeValueAsString(pulse), now);
        } catch (Exception e) {
            log.warn("大盘研判归档失败: {}", e.getMessage());
        }
        log.info("大盘研判：{} 温度={} 档位={}", today, pulse.getTemperature(), advice);
        return pulse;
    }

    public MarketPulse last() {
        MarketSnapshotRepository.Entry e = repo.findLatest();
        if (e == null || e.payload == null) return null;
        try {
            return mapper.readValue(e.payload, MarketPulse.class);
        } catch (Exception ex) {
            log.warn("大盘研判归档反序列化失败: {}", ex.getMessage());
            return null;
        }
    }

    // ===== 指数 K 线 =====
    private List<DailyBar> fetchIndex(String code, String secid, int days) {
        List<DailyBar> bars = eastmoney.fetchKline(code, secid, days);
        if (bars.isEmpty()) bars = tencent.fetchKline(code, secid, days);
        return bars;
    }

    // ===== 指标工具 =====
    private static double ma(List<DailyBar> bars, int n) {
        int m = bars.size(); if (m == 0) return 0;
        int k = Math.min(n, m); double s = 0;
        for (int i = m - k; i < m; i++) s += bars.get(i).getClose();
        return s / k;
    }
    private static double maAmount(List<DailyBar> bars, int n) {
        int m = bars.size(); if (m == 0) return 0;
        int k = Math.min(n, m); double s = 0;
        for (int i = m - k; i < m; i++) s += bars.get(i).getAmount();
        return s / k;
    }
    /** 今日相对昨收的涨跌幅% */
    private static double changePct(List<DailyBar> bars) {
        int m = bars.size(); if (m < 2) return 0;
        double prev = bars.get(m - 2).getClose(), last = bars.get(m - 1).getClose();
        return prev > 0 ? (last - prev) / prev * 100 : 0;
    }
    /** 最近 n 个交易日涨跌幅% */
    private static double changePctN(List<DailyBar> bars, int n) {
        int m = bars.size(); if (m < n + 1) return 0;
        double base = bars.get(m - 1 - n).getClose(), last = bars.get(m - 1).getClose();
        return base > 0 ? (last - base) / base * 100 : 0;
    }
    private static boolean slopeUp(List<DailyBar> bars, int n) {
        int m = bars.size(); if (m < n + 1) return bars.get(m - 1).getClose() >= bars.get(0).getClose();
        return bars.get(m - 1).getClose() >= bars.get(m - 1 - n).getClose();
    }
    private static double clamp(double v) { return Math.max(0, Math.min(100, v)); }
    private static double round2(double v) { return Math.round(v * 100d) / 100d; }
    private static MarketPulse.Reason reason(String kind, String text) {
        return MarketPulse.Reason.builder().kind(kind).text(text).build();
    }
}
