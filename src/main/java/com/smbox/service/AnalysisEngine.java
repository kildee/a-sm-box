package com.smbox.service;

import com.smbox.model.DailyBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnalysisEngine {
    private AnalysisEngine() {}

    // ===== 输出结构 =====

    public static final class ScoreResult {
        public final int score;
        public final String level;
        public final int tier;
        public final List<Map<String, Object>> facts;
        public ScoreResult(int score, String level, int tier) {
            this(score, level, tier, new ArrayList<>());
        }
        public ScoreResult(int score, String level, int tier, List<Map<String, Object>> facts) {
            this.score = score; this.level = level; this.tier = tier; this.facts = facts;
        }
        public List<String> factCodes() {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> f : facts) out.add(String.valueOf(f.get("c")));
            return out;
        }
    }

    static Map<String, Object> fact(String code, Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("c", code);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    static double jsFixed(double v, int d) {
        return new java.math.BigDecimal(v).setScale(d, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    public static final class ExpertResult {
        public boolean ok;
        public String regime;
        public int score;
        public String verdict;
        public boolean overext;
        public Double r1;
        public double s1;
        public double hardStop;
        public double ma10;
        public double ma60;
        public double close;
        public double volRatio;
        public Double chandelier;
    }

    static final class Sig {
        final int index; final String type; final String label; final boolean potential;
        Sig(int index, String type, String label, boolean potential) {
            this.index = index; this.type = type; this.label = label; this.potential = potential;
        }
    }

    // ===== 指标工具 =====

    static double[] maSeries(List<DailyBar> bars, int n) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            if (i < n - 1) { out[i] = Double.NaN; continue; }
            double s = 0;
            for (int j = 0; j < n; j++) s += bars.get(i - j).getClose();
            out[i] = new java.math.BigDecimal(s / n)
                    .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        }
        return out;
    }

    private static double[] emaSeries(double[] vals, int n) {
        double k = 2d / (n + 1);
        double[] out = new double[vals.length];
        double prev = Double.NaN;
        for (int i = 0; i < vals.length; i++) {
            prev = Double.isNaN(prev) ? vals[i] : vals[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    static double atr22At(List<DailyBar> bars, int i) {
        if (i < 22) return Double.NaN;
        double s = 0;
        for (int j = i - 21; j <= i; j++) {
            double h = bars.get(j).getHigh(), l = bars.get(j).getLow(), pc = bars.get(j - 1).getClose();
            s += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return s / 22;
    }

    public static double chandelierAt(List<DailyBar> bars, int i) {
        double a = atr22At(bars, i);
        if (Double.isNaN(a)) return Double.NaN;
        double hh = Double.NEGATIVE_INFINITY;
        for (int j = i - 21; j <= i; j++) hh = Math.max(hh, bars.get(j).getHigh());
        return hh - 3 * a;
    }

    private static double linSlope(double[] arr) {
        int n = arr.length; if (n < 2) return 0;
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) { sx += i; sy += arr[i]; sxy += (double) i * arr[i]; sxx += (double) i * i; }
        double d = n * sxx - sx * sx;
        return d != 0 ? (n * sxy - sx * sy) / d : 0;
    }

    // ===== 均线金叉死叉 =====

    private static List<Sig> maSignals(List<DailyBar> bars, double[] ma5, double[] ma20) {
        List<Sig> out = new ArrayList<>();
        String lastType = null; int lastIdx = -99;
        for (int i = 1; i < bars.size(); i++) {
            double m5 = ma5[i], m5p = ma5[i - 1], m20 = ma20[i], m20p = ma20[i - 1];
            if (Double.isNaN(m5) || Double.isNaN(m5p) || Double.isNaN(m20) || Double.isNaN(m20p)) continue;
            String type = null;
            if (m5p <= m20p && m5 > m20) type = "buy";
            else if (m5p >= m20p && m5 < m20) type = "sell";
            if (type == null) continue;
            if (type.equals(lastType) && i - lastIdx < 3) continue;
            out.add(new Sig(i, type, type.equals("buy") ? "金叉" : "死叉", false));
            lastType = type; lastIdx = i;
        }
        return out;
    }






    // ===== 背驰法 =====

    private static List<Sig> chanSignals(List<DailyBar> s) {
        List<Sig> signals = new ArrayList<>();
        int n = s.size();
        if (n < 8) return signals;

        // 1. 分型
        List<int[]> fr = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            DailyBar c = s.get(i), p = s.get(i - 1), nx = s.get(i + 1);
            if (c.getHigh() > p.getHigh() && c.getHigh() > nx.getHigh()
                    && c.getLow() > p.getLow() && c.getLow() > nx.getLow()) {
                fr.add(new int[]{i, 0});
            } else if (c.getLow() < p.getLow() && c.getLow() < nx.getLow()
                    && c.getHigh() < p.getHigh() && c.getHigh() < nx.getHigh()) {
                fr.add(new int[]{i, 1});
            }
        }
        if (fr.size() < 2) return signals;
        java.util.function.IntFunction<Double> priceOf = k ->
                fr.get(k)[1] == 0 ? s.get(fr.get(k)[0]).getHigh() : s.get(fr.get(k)[0]).getLow();

        for (int k = 0; k < fr.size(); k++) {
            int idx = fr.get(k)[0]; boolean bottom = fr.get(k)[1] == 1;
            int lb = Math.max(0, idx - 19);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int j = lb; j <= idx; j++) {
                low = Math.min(low, s.get(j).getLow());
                high = Math.max(high, s.get(j).getHigh());
            }
            double price = priceOf.apply(k);
            if (bottom && price - low < low * 0.02) signals.add(new Sig(idx, "buy", "B1", false));
            else if (!bottom && high - price < high * 0.02) signals.add(new Sig(idx, "sell", "S1", false));
        }

        // 3. 二买/二卖
        for (int k = 1; k < fr.size(); k++) {
            int type = fr.get(k)[1];
            Integer prevSame = null; boolean midOpp = false;
            for (int j = k - 1; j >= 0; j--) {
                if (fr.get(j)[1] == type) { prevSame = j; break; }
                midOpp = true;
            }
            if (prevSame == null || !midOpp) continue;
            double cur = priceOf.apply(k), prev = priceOf.apply(prevSame);
            if (type == 1 && cur > prev) signals.add(new Sig(fr.get(k)[0], "buy", "B2", false));
            else if (type == 0 && cur < prev) signals.add(new Sig(fr.get(k)[0], "sell", "S2", false));
        }

        // 4. 三买/三卖
        for (int k = 2; k < fr.size(); k++) {
            int type = fr.get(k)[1];
            double cur = priceOf.apply(k);
            if (type == 1) {
                List<Integer> tops = new ArrayList<>();
                for (int j = 0; j < k; j++) if (fr.get(j)[1] == 0) tops.add(j);
                if (tops.size() < 2) continue;
                double lastTop = priceOf.apply(tops.get(tops.size() - 1));
                double prevTop = priceOf.apply(tops.get(tops.size() - 2));
                if (lastTop > prevTop && cur > prevTop) signals.add(new Sig(fr.get(k)[0], "buy", "B3", false));
            } else {
                List<Integer> bots = new ArrayList<>();
                for (int j = 0; j < k; j++) if (fr.get(j)[1] == 1) bots.add(j);
                if (bots.size() < 2) continue;
                double lastB = priceOf.apply(bots.get(bots.size() - 1));
                double prevB = priceOf.apply(bots.get(bots.size() - 2));
                if (lastB < prevB && cur < prevB) signals.add(new Sig(fr.get(k)[0], "sell", "S3", false));
            }
        }
        DailyBar cur = s.get(n - 1), prev = s.get(n - 2);
        if (cur.getHigh() < prev.getHigh() && cur.getLow() < prev.getLow())
            signals.add(new Sig(n - 1, "buy", "?B", true));
        else if (cur.getHigh() > prev.getHigh() && cur.getLow() > prev.getLow())
            signals.add(new Sig(n - 1, "sell", "?S", true));

        java.util.Map<Integer, Sig> best = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Integer> bestP = new java.util.HashMap<>();
        for (Sig sg : signals) {
            int p = sigPriority(sg.label);
            Integer curP = bestP.get(sg.index);
            if (curP == null || p > curP) { best.put(sg.index, sg); bestP.put(sg.index, p); }
        }
        List<Sig> out = new ArrayList<>(best.values());
        out.sort(java.util.Comparator.comparingInt(x -> x.index));
        return out;
    }

    private static int sigPriority(String label) {
        switch (label) {
            case "B3": case "S3": return 3;
            case "B2": case "S2": return 2;
            case "B1": case "S1": return 1;
            default: return 0;   // ?B / ?S 潜在
        }
    }

    // ===== 指标打分 =====

    public static ScoreResult scoreStock(List<DailyBar> bars) {
        return scoreStock(bars, null);
    }

    public static List<Integer> b2BuyIndices(List<DailyBar> bars) {
        List<Integer> out = new ArrayList<>();
        if (bars == null || bars.size() < 2) return out;
        for (Sig s : chanSignals(bars)) {
            if (!s.potential && "buy".equals(s.type) && "B2".equals(s.label)) out.add(s.index);
        }
        return out;
    }


    public static ScoreResult scoreStock(List<DailyBar> bars, Double rps120) {
        int n = bars == null ? 0 : bars.size();
        if (n < 25) {
            List<Map<String, Object>> f0 = new ArrayList<>();
            f0.add(fact("DATA_SHORT"));
            return new ScoreResult(0, "数据不足", 0, f0);
        }

        double[] ma5 = maSeries(bars, 5), ma10 = maSeries(bars, 10), ma20 = maSeries(bars, 20);
        int i = n - 1;
        DailyBar bar = bars.get(i);
        double close = bar.getClose(), m5 = ma5[i], m10 = ma10[i], m20 = ma20[i];
        boolean bull = m5 > m10 && m10 > m20;
        boolean bear = m5 < m10 && m10 < m20;
        boolean aboveMA20 = !Double.isNaN(m20) && close > m20;

        List<Sig> maSig = maSignals(bars, ma5, ma20);
        Sig lastMa = maSig.isEmpty() ? null : maSig.get(maSig.size() - 1);
        Sig lastMaBuy = null;
        for (int k = maSig.size() - 1; k >= 0; k--) if (maSig.get(k).type.equals("buy")) { lastMaBuy = maSig.get(k); break; }
        Integer goldenDaysAgo = lastMaBuy != null ? i - lastMaBuy.index : null;
        boolean deathMoreRecent = lastMa != null && lastMa.type.equals("sell");

        List<Sig> chanAll = chanSignals(bars);
        List<Sig> chanSig = new ArrayList<>();
        for (Sig sg : chanAll) if (!sg.potential) chanSig.add(sg);
        Sig lastChan = chanSig.isEmpty() ? null : chanSig.get(chanSig.size() - 1);
        Sig lastChanBuy = null;
        for (int k = chanSig.size() - 1; k >= 0; k--) if (chanSig.get(k).type.equals("buy")) { lastChanBuy = chanSig.get(k); break; }
        Integer chanBuyDaysAgo = lastChanBuy != null ? i - lastChanBuy.index : null;
        boolean chanSellMoreRecent = lastChan != null && lastChan.type.equals("sell");

        Double volAvg5 = null;
        if (i >= 5) { double sum = 0; for (int j = 1; j <= 5; j++) sum += bars.get(i - j).getVolume(); volAvg5 = sum / 5; }
        boolean todayUp = close >= bar.getOpen();
        Double volRatio = volAvg5 != null && volAvg5 > 0 ? bar.getVolume() / volAvg5 : null;
        boolean volExpand = volRatio != null && volRatio >= 1.3;

        int win = Math.min(60, n);
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int j = n - win; j < n; j++) { hi = Math.max(hi, bars.get(j).getHigh()); lo = Math.min(lo, bars.get(j).getLow()); }
        double posRatio = hi > lo ? (close - lo) / (hi - lo) : 0.5;
        int posPct = (int) Math.round(posRatio * 100);
        boolean lowPos = posRatio <= 0.35, highPos = posRatio >= 0.7;

        List<Map<String, Object>> facts = new ArrayList<>();

        // 一票否决：空头排列 + 价在 MA20 下方
        if (bear && !aboveMA20) {
            facts.add(fact("VETO_BEAR_BELOW_MA20", "m20", m20));
            if (lastChanBuy != null && chanBuyDaysAgo <= 10) facts.add(fact("VETO_RECENT_BUY", "days", chanBuyDaysAgo, "label", lastChanBuy.label));
            facts.add(fact("POS", "win", win, "pct", posPct));
            return new ScoreResult(8, "E档", 1, facts);
        }

        double score = 0;
        if (bull) { score += 25; facts.add(fact("MA_BULL", "m5", m5, "m10", m10, "m20", m20)); }
        else if (m5 > m20) { score += 12; facts.add(fact("MA5_ABOVE_MA20")); }
        else if (bear) { score -= 15; facts.add(fact("MA_BEAR")); }
        if (aboveMA20) { score += 10; facts.add(fact("ABOVE_MA20", "close", close, "m20", m20)); }
        else facts.add(fact("BELOW_MA20", "close", close, "m20", m20));
        if (goldenDaysAgo != null && goldenDaysAgo <= 10) { score += 20; facts.add(fact("GOLDEN_RECENT", "days", goldenDaysAgo)); }
        else if (goldenDaysAgo != null && goldenDaysAgo <= 30) { score += 6; facts.add(fact("GOLDEN_OLD", "days", goldenDaysAgo)); }
        if (deathMoreRecent) { score -= 20; facts.add(fact("DEATH_RECENT")); }
        if (lastChanBuy != null && chanBuyDaysAgo <= 10) {
            score += "B3".equals(lastChanBuy.label) ? 25 : "B2".equals(lastChanBuy.label) ? 20 : 8;
            facts.add(fact("CHAN_BUY_RECENT", "days", chanBuyDaysAgo, "label", lastChanBuy.label));
        } else if (lastChanBuy != null && chanBuyDaysAgo <= 20) { score += 4; facts.add(fact("CHAN_BUY_OLD", "days", chanBuyDaysAgo, "label", lastChanBuy.label)); }
        if (chanSellMoreRecent) { score -= 18; facts.add(fact("CHAN_SELL_RECENT", "label", lastChan.label)); }
        if (volExpand && todayUp) { score += 5; facts.add(fact("VOL_UP", "ratio", jsFixed(volRatio, 1))); }
        else if (volRatio != null && volRatio < 0.7) { score -= 3; facts.add(fact("VOL_SHRINK", "ratio", jsFixed(volRatio, 1))); }
        String bk = detectBreakout(bars);
        if ("平台突破".equals(bk)) { score += 12; facts.add(fact("BK_PLATFORM")); }
        else if ("新高突破".equals(bk)) { score += 6; facts.add(fact("BK_NEWHIGH")); }
        if (lowPos) { score += 15; facts.add(fact("POS_LOW", "win", win, "pct", posPct)); }
        else if (highPos) { score -= 10; facts.add(fact("POS_HIGH", "win", win, "pct", posPct)); }
        else facts.add(fact("POS_MID", "win", win, "pct", posPct));
        boolean confluence = lastChanBuy != null && chanBuyDaysAgo <= 10
                && ("B2".equals(lastChanBuy.label) || "B3".equals(lastChanBuy.label))
                && goldenDaysAgo != null && goldenDaysAgo <= 10 && bull;
        if (confluence) { score += 8; facts.add(0, fact("CONFLUENCE")); }

        // ATR 吊灯止损维度（与 JS 一致）
        double chand = chandelierAt(bars, i);
        if (!Double.isNaN(chand)) {
            if (close > chand) { score += 5; facts.add(fact("CHAND_ABOVE", "v", jsFixed(chand, 2))); }
            else { score -= 10; facts.add(fact("CHAND_BELOW", "v", jsFixed(chand, 2))); }
        }
        // RPS 相对强度维度（与 JS 一致）
        if (rps120 != null) {
            long rv = Math.round(rps120);
            if (rps120 >= 85) { score += 10; facts.add(fact("RPS_TOP", "v", rv)); }
            else if (rps120 >= 70) { score += 5; facts.add(fact("RPS_STRONG", "v", rv)); }
            else if (rps120 < 50) { score -= 5; facts.add(fact("RPS_WEAK", "v", rv)); }
            else facts.add(fact("RPS_MID", "v", rv));
        }
        try {
            ExpertResult ex = expert(bars);
            if (ex.ok && ex.overext) { score -= 8; facts.add(fact("OVEREXT")); }
        } catch (Exception ignore) { /* 判别失败不影响打分 */ }

        int sc = (int) Math.max(0, Math.min(100, Math.round(score)));
        if (sc >= 70) return new ScoreResult(sc, "A档", 5, facts);
        if (sc >= 50) return new ScoreResult(sc, "B档", 4, facts);
        if (sc >= 32) return new ScoreResult(sc, "C档", 3, facts);
        return new ScoreResult(sc, "D档", 2, facts);
    }

    // ===== 形态判别 =====

    public static ExpertResult expert(List<DailyBar> bars) {
        ExpertResult r = new ExpertResult();
        int n = bars == null ? 0 : bars.size();
        if (n < 70) { r.ok = false; return r; }
        r.ok = true;

        double[] ma5s = maSeries(bars, 5), ma10s = maSeries(bars, 10),
                ma20s = maSeries(bars, 20), ma60s = maSeries(bars, 60);
        int i = n - 1;
        DailyBar bar = bars.get(i);
        double close = bar.getClose();
        double ma5 = ma5s[i], ma10 = ma10s[i], ma20 = ma20s[i], ma60 = ma60s[i];
        r.close = close; r.ma10 = ma10; r.ma60 = ma60;

        double[] closes = new double[n];
        for (int j = 0; j < n; j++) closes[j] = bars.get(j).getClose();
        double[] e12 = emaSeries(closes, 12), e26 = emaSeries(closes, 26);
        double[] dif = new double[n];
        for (int j = 0; j < n; j++) dif[j] = e12[j] - e26[j];
        double[] dea = emaSeries(dif, 9);
        double[] hist = new double[n];
        for (int j = 0; j < n; j++) hist[j] = 2 * (dif[j] - dea[j]);

        double biasMa5 = (close - ma5) / ma5;
        double volSum = 0; for (int j = i - 4; j <= i; j++) volSum += bars.get(j).getVolume();
        double volMa5 = volSum / 5;
        double volRatio = volMa5 > 0 ? bar.getVolume() / volMa5 : 1;
        r.volRatio = volRatio;
        double[] vol5 = new double[5];
        for (int j = 0; j < 5; j++) vol5[j] = bars.get(i - 4 + j).getVolume();
        double volSlope = volMa5 > 0 ? linSlope(vol5) / volMa5 : 0;
        int upStreak = 0; for (int j = i; j > 0 && bars.get(j).getClose() >= bars.get(j - 1).getClose(); j--) upStreak++;
        double gain5 = (close - bars.get(i - 5).getClose()) / bars.get(i - 5).getClose();
        boolean maBull = ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
        boolean maBear = ma5 < ma10 && ma10 < ma20 && ma20 < ma60;
        boolean maConverging = Math.abs(ma5 - ma60) < Math.abs(ma5s[i - 10] - ma60s[i - 10]) * 0.7;
        double[] ma60last10 = new double[10];
        for (int j = 0; j < 10; j++) ma60last10[j] = ma60s[i - 9 + j];
        double ma60Slope = linSlope(ma60last10);
        boolean ma5Up = ma5s[i] > ma5s[i - 3];

        int win = Math.min(60, n - 1);
        int segFrom = n - win;
        List<Integer> pivLows = new ArrayList<>(), pivHighs = new ArrayList<>();
        for (int k = segFrom + 3; k < n - 3; k++) {
            boolean isL = true, isH = true;
            for (int j = k - 3; j <= k + 3; j++) {
                if (bars.get(j).getLow() < bars.get(k).getLow()) isL = false;
                if (bars.get(j).getHigh() > bars.get(k).getHigh()) isH = false;
            }
            if (isL) pivLows.add(k);
            if (isH) pivHighs.add(k);
        }
        double swingLow;
        if (!pivLows.isEmpty()) {
            swingLow = Double.POSITIVE_INFINITY;
            for (int k : pivLows) swingLow = Math.min(swingLow, bars.get(k).getLow());
        } else {
            swingLow = Double.POSITIVE_INFINITY;
            for (int j = segFrom; j < n; j++) swingLow = Math.min(swingLow, bars.get(j).getLow());
        }
        Double swingHigh = null;
        for (int k = pivHighs.size() - 1; k >= 0; k--) {
            if (pivHighs.get(k) - segFrom < win - 5) { swingHigh = bars.get(pivHighs.get(k)).getHigh(); break; }
        }
        if (swingHigh == null) {
            double mx = Double.NEGATIVE_INFINITY;
            for (int j = segFrom; j < Math.max(segFrom + 1, n - 5); j++) mx = Math.max(mx, bars.get(j).getHigh());
            swingHigh = mx;
        }

        // regime
        int downDays = 0;
        for (int j = Math.max(60, i - 40); j < i - 5; j++) {
            if (bars.get(j).getClose() < ma60s[j] || (ma5s[j] < ma20s[j] && ma20s[j] < ma60s[j])) downDays++;
        }
        boolean wasDown = downDays >= 15;
        double recent10Low = Double.POSITIVE_INFINITY;
        for (int j = i - 9; j <= i; j++) recent10Low = Math.min(recent10Low, bars.get(j).getLow());
        boolean higherLows = false;
        if (pivLows.size() >= 2) {
            double l1 = bars.get(pivLows.get(pivLows.size() - 1)).getLow();
            double l0 = bars.get(pivLows.get(pivLows.size() - 2)).getLow();
            higherLows = l1 > l0;
        }
        boolean notLowerHighs = true;
        if (pivHighs.size() >= 2) {
            double h1 = bars.get(pivHighs.get(pivHighs.size() - 1)).getHigh();
            double h0 = bars.get(pivHighs.get(pivHighs.size() - 2)).getHigh();
            notLowerHighs = h1 >= h0 * 0.98;
        }
        boolean rangeUp = ma60Slope > 0 && close > ma60 && higherLows && notLowerHighs;

        if (maBull && ma60Slope > 0 && close > ma60) r.regime = "UPTREND";
        else if (maBear && ma60Slope < 0 && close < ma60) r.regime = "DOWNTREND";
        else if (wasDown && close > ma5 && close > ma10 && close > swingLow * 1.03) r.regime = "REBOUND";
        else if (wasDown && recent10Low > swingLow * 0.995 && Math.abs(ma5s[i] - ma5s[i - 5]) / close < 0.01) r.regime = "BOTTOMING";
        else if (!wasDown && close > ma60 && close < ma5 && volRatio > 1.5) r.regime = "TOPPING";
        else if (rangeUp) r.regime = "RANGE_UP";
        else r.regime = "RANGE";

        // A 均线修复
        double a = 0;
        if (close > ma5) a += 0.15;
        if (close > ma20) a += 0.30;
        if (close > ma60) a += 0.25;
        if (ma5 > ma10 && ma5Up) a += 0.15;
        if (maConverging) a += 0.15;
        double scoreMa = Math.min(1, a);
        // B 量能
        double b = 0;
        if (volSlope > 0) b += 0.4;
        boolean brokeToday = (close > ma20 && bars.get(i - 1).getClose() <= ma20s[i - 1])
                || (close > ma60 && bars.get(i - 1).getClose() <= ma60s[i - 1])
                || (close > swingHigh && bars.get(i - 1).getClose() <= swingHigh);
        if (brokeToday && volRatio > 1.5) b += 0.4;
        if (volRatio >= 1.0 && volRatio <= 2.0) b += 0.2;
        if (close > bars.get(i - 1).getClose() && volRatio < 0.8) b -= 0.4;
        double scoreVol = Math.max(0, Math.min(1, b));
        // C 底背驰
        double scoreBackchi = 0.3;
        if (pivLows.size() >= 2) {
            int i1 = pivLows.get(pivLows.size() - 2), i2 = pivLows.get(pivLows.size() - 1);
            if (i2 - i1 >= 5 && bars.get(i2).getLow() <= bars.get(i1).getLow() * 1.02) {
                double a1 = negArea(hist, i1, i), a2 = negArea(hist, i2, i);
                if (bars.get(i2).getLow() <= bars.get(i1).getLow() && (a2 < a1 * 0.8 || dif[i2] > dif[i1])) scoreBackchi = 1.0;
                else scoreBackchi = 0.0;
            }
        }
        // D 突破确认
        double d = 0;
        int brokeIdx = -1;
        for (int j = Math.max(1, i - 9); j <= i; j++) {
            if (bars.get(j).getClose() > swingHigh && bars.get(j - 1).getClose() <= swingHigh) { brokeIdx = j; break; }
        }
        Double breakoutLevel = null;
        if (brokeIdx >= 0) {
            d += 0.5; breakoutLevel = swingHigh;
            if (brokeIdx < i) {
                double minLow = Double.POSITIVE_INFINITY;
                for (int j = brokeIdx + 1; j <= i; j++) minLow = Math.min(minLow, bars.get(j).getLow());
                if (minLow >= Math.min(swingHigh, ma10) * 0.99 && close > bars.get(i - 1).getClose()) d += 0.5;
            }
        }
        List<Sig> chan = chanSignals(bars);
        for (int k = chan.size() - 1; k >= 0; k--) {
            Sig sg = chan.get(k);
            if (!sg.potential && sg.type.equals("buy") && "B3".equals(sg.label) && i - sg.index <= 10) { d = Math.max(d, 1); break; }
        }
        double scoreBreakout = Math.min(1, d);

        r.score = (int) Math.round(30 * scoreMa + 25 * scoreVol + 25 * scoreBackchi + 20 * scoreBreakout);
        r.verdict = r.score >= 65 ? "REVERSAL_LIKELY" : r.score >= 40 ? "UNCONFIRMED" : "BOUNCE_ONLY";
        r.overext = biasMa5 > 0.07 || (upStreak >= 5 && gain5 > 0.15);

        double recentHigh = Double.NEGATIVE_INFINITY;
        for (int j = segFrom; j < n; j++) recentHigh = Math.max(recentHigh, bars.get(j).getHigh());
        List<Double> above = new ArrayList<>();
        for (double v : new double[]{ma20, ma60, swingHigh, recentHigh}) if (!Double.isNaN(v) && v > close) above.add(v);
        r.r1 = above.isEmpty() ? null : above.stream().min(Double::compare).get();
        List<Double> below = new ArrayList<>();
        for (double v : new double[]{ma10, ma20}) if (!Double.isNaN(v) && v <= close) below.add(v);
        r.s1 = below.isEmpty() ? ma10 : below.stream().max(Double::compare).get();
        r.hardStop = swingLow;
        double chand = chandelierAt(bars, i);
        r.chandelier = Double.isNaN(chand) ? null : chand;
        return r;
    }

    // ===== 策略B =====









    public static String detectBreakout(List<DailyBar> bars) {
        int n = bars == null ? 0 : bars.size();
        if (n < 70) return null;
        int i = n - 1;
        double close = bars.get(i).getClose();
        double[] ma20 = maSeries(bars, 20), ma60 = maSeries(bars, 60);
        if (Double.isNaN(ma20[i]) || close <= ma20[i]) return null;

        double ma60Now = ma60[i], ma60Prev = ma60[Math.max(0, i - 5)];
        boolean downTrend = ma60Now < ma60Prev * 0.995 && close < ma60Now;
        if (downTrend) return null;

        int hiFrom = Math.max(0, i - 3 - 60), hiTo = i - 3;
        if (hiTo > hiFrom) {
            double priorHigh = Double.NEGATIVE_INFINITY;
            for (int j = hiFrom; j <= hiTo; j++) priorHigh = Math.max(priorHigh, bars.get(j).getHigh());
            for (int j = Math.max(1, i - 2); j <= i; j++) {
                if (bars.get(j).getClose() > priorHigh && bars.get(j - 1).getClose() <= priorHigh
                        && volRatioAt(bars, j) > 1.3) {
                    return "新高突破";
                }
            }
        }

        int boxFrom = Math.max(0, i - 28), boxTo = i - 4;
        if (boxTo - boxFrom >= 12) {
            double boxHigh = Double.NEGATIVE_INFINITY, boxLow = Double.POSITIVE_INFINITY;
            for (int j = boxFrom; j <= boxTo; j++) {
                boxHigh = Math.max(boxHigh, bars.get(j).getHigh());
                boxLow = Math.min(boxLow, bars.get(j).getLow());
            }
            boolean narrow = boxLow > 0 && (boxHigh - boxLow) / boxLow < 0.18;
            boolean brokeBox = false;
            for (int j = Math.max(1, i - 2); j <= i; j++) {
                if (bars.get(j).getClose() > boxHigh && bars.get(j - 1).getClose() <= boxHigh
                        && volRatioAt(bars, j) > 1.3) { brokeBox = true; break; }
            }
            if (narrow && brokeBox) {
                List<Sig> chan = chanSignals(bars);
                int cnt = 0; String lastBuyLabel = null;
                for (Sig sg : chan) {
                    if (sg.potential || !"buy".equals(sg.type)) continue;
                    if (i - sg.index > 15) continue;
                    cnt++; lastBuyLabel = sg.label;
                }
                if (cnt >= 2 && ("B2".equals(lastBuyLabel) || "B3".equals(lastBuyLabel))) return "平台突破";
            }
        }
        return null;
    }

    public static Integer platformBreakoutDaysAgo(List<DailyBar> bars, int maxBack) {
        int n = bars == null ? 0 : bars.size();
        if (n < 70) return null;
        return platformBreakoutDaysAgo(bars, maxBack, chanSignals(bars));
    }

    static Integer platformBreakoutDaysAgo(List<DailyBar> bars, int maxBack, List<Sig> chan) {
        int n = bars.size();
        double[] ma20 = maSeries(bars, 20), ma60 = maSeries(bars, 60);
        for (int d = 0; d <= maxBack; d++) {
            int j = n - 1 - d;
            if (j < 69) break;
            if (isPlatformBreakoutDay(bars, j, ma20, ma60, chan)) return d;
        }
        return null;
    }

    private static boolean isPlatformBreakoutDay(List<DailyBar> bars, int j,
                                                 double[] ma20, double[] ma60, List<Sig> chan) {
        if (j < 1) return false;
        double close = bars.get(j).getClose();
        if (Double.isNaN(ma20[j]) || close <= ma20[j]) return false;
        double ma60Now = ma60[j], ma60Prev = ma60[Math.max(0, j - 5)];
        if (!Double.isNaN(ma60Now) && ma60Now < ma60Prev * 0.995 && close < ma60Now) return false;
        int boxFrom = Math.max(0, j - 28), boxTo = j - 4;
        if (boxTo - boxFrom < 12) return false;
        double boxHigh = Double.NEGATIVE_INFINITY, boxLow = Double.POSITIVE_INFINITY;
        for (int k = boxFrom; k <= boxTo; k++) {
            boxHigh = Math.max(boxHigh, bars.get(k).getHigh());
            boxLow = Math.min(boxLow, bars.get(k).getLow());
        }
        if (!(boxLow > 0 && (boxHigh - boxLow) / boxLow < 0.18)) return false;
        if (!(close > boxHigh && bars.get(j - 1).getClose() <= boxHigh && volRatioAt(bars, j) > 1.3)) return false;
        int cnt = 0; String lastBuyLabel = null;
        for (Sig sg : chan) {
            if (sg.potential || !"buy".equals(sg.type)) continue;
            if (sg.index > j || j - sg.index > 15) continue;
            cnt++; lastBuyLabel = sg.label;
        }
        return cnt >= 2 && ("B2".equals(lastBuyLabel) || "B3".equals(lastBuyLabel));
    }

    private static double volRatioAt(List<DailyBar> bars, int j) {
        if (j < 5) return 1;
        double s = 0;
        for (int k = j - 5; k < j; k++) s += bars.get(k).getVolume();
        double avg = s / 5;
        return avg > 0 ? bars.get(j).getVolume() / avg : 1;
    }

    private static double negArea(double[] hist, int c, int maxIdx) {
        double s = 0;
        for (int j = Math.max(0, c - 4); j <= Math.min(maxIdx, c + 4); j++) if (hist[j] < 0) s += -hist[j];
        return s;
    }
}
