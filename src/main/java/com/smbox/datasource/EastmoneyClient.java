package com.smbox.datasource;

import com.smbox.model.DailyBar;
import com.smbox.model.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EastmoneyClient {
    private static final String CLIST_URL = "http://push2.eastmoney.com/api/qt/clist/get";

    /** 个股 K 线 */
    private static final String KLINE_URL = "http://push2his.eastmoney.com/api/qt/stock/kline/get";

    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get";

    private static final String FS_MAINBOARD = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23";
    private static final String FS_WITH_BJ   = FS_MAINBOARD + ",m:0+t:81+s:2048";

    /** 东方财富 clist 单页上限 */
    private static final int PAGE_SIZE = 100;

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public List<Quote> fetchAllQuotes(boolean includeBj) {
        return fetchAllPages(includeBj ? FS_WITH_BJ : FS_MAINBOARD);
    }

    private static final String[] CLIST_HOSTS = {
            "push2delay.eastmoney.com", "push2.eastmoney.com", "82.push2.eastmoney.com",
    };

    private static volatile int clistHostIdx = 0;

    public List<Quote> fetchGlobalQuotes(List<String> secids) {
        List<Quote> out = new ArrayList<>();
        if (secids == null || secids.isEmpty()) return out;
        String joined = String.join(",", secids);
        for (int attempt = 0; attempt < CLIST_HOSTS.length; attempt++) {
            int hostIdx = (clistHostIdx + attempt) % CLIST_HOSTS.length;
            String host = CLIST_HOSTS[hostIdx];
            HttpUrl url = HttpUrl.parse("http://" + host + "/api/qt/ulist.np/get").newBuilder()
                    .addQueryParameter("fltt", "2")
                    .addQueryParameter("invt", "2")
                    .addQueryParameter("secids", joined)
                    .addQueryParameter("fields", "f2,f3,f6,f12,f13,f14")
                    .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                    .build();
            Request req = new Request.Builder().url(url)
                    .header("Referer", "https://quote.eastmoney.com/")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Connection", "close")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) { sleepBackoff(attempt + 1); continue; }
                JsonNode diff = mapper.readTree(resp.body().byteStream()).path("data").path("diff");
                if (!diff.isArray()) { sleepBackoff(attempt + 1); continue; }
                for (JsonNode n : diff) {
                    String code = n.path("f12").asText("");
                    if (code.isEmpty()) continue;
                    out.add(Quote.builder()
                            .code(n.path("f13").asText("") + "." + code)
                            .name(n.path("f14").asText(""))
                            .price(asDouble(n.path("f2")))
                            .changePct(asDouble(n.path("f3")))
                            .amount(asDouble(n.path("f6")))
                            .build());
                }
                clistHostIdx = hostIdx;
                return out;
            } catch (IOException e) {
                log.debug("全球行情 {} 失败: {}", host, e.getMessage());
                sleepBackoff(attempt + 1);
            }
        }
        return out;
    }




    private List<Quote> fetchAllPages(String fs) {
        List<Quote> all = new ArrayList<>(6000);
        int consecutiveFails = 0;
        for (int page = 1; page <= 100; page++) {
            boolean[] failed = {false};
            List<Quote> pageData = fetchQuotePage(fs, page, failed);
            if (failed[0]) {
                consecutiveFails++;
                if (consecutiveFails >= 5) {
                    log.warn("clist 连续 {} 页失败，停止分页（已拉 {} 条）", consecutiveFails, all.size());
                    break;
                }
                continue;
            }
            consecutiveFails = 0;
            if (pageData.isEmpty()) break;
            all.addAll(pageData);
            if (pageData.size() < PAGE_SIZE) break;
        }
        return all;
    }

    private List<Quote> fetchQuotePage(String fs, int page, boolean[] failedOut) {
        HttpUrl url = HttpUrl.parse(CLIST_URL).newBuilder()
                .addQueryParameter("pn", String.valueOf(page))
                .addQueryParameter("pz", String.valueOf(PAGE_SIZE))
                .addQueryParameter("po", "1")
                .addQueryParameter("np", "1")
                .addQueryParameter("fltt", "2")
                .addQueryParameter("invt", "2")
                .addQueryParameter("fs", fs)
                .addQueryParameter("fields", "f2,f3,f5,f6,f12,f13,f14")
                .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();

        IOException lastIo = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    log.warn("Eastmoney clist 请求失败: page={} status={}", page, resp.code());
                    failedOut[0] = true;
                    return Collections.emptyList();
                }
                JsonNode root = mapper.readTree(resp.body().byteStream());
                JsonNode diff = root.path("data").path("diff");
                if (!diff.isArray() || diff.size() == 0) {
                    return Collections.emptyList();    // 真没数据
                }
                List<Quote> quotes = new ArrayList<>(diff.size());
                for (JsonNode n : diff) {
                    String code = n.path("f12").asText();
                    String name = n.path("f14").asText();
                    int market = n.path("f13").asInt();
                    String exchange = market == 1 ? "sh" : (code.startsWith("8") || code.startsWith("4") ? "bj" : "sz");

                    quotes.add(Quote.builder()
                            .code(code)
                            .name(name)
                            .exchange(exchange)
                            .price(asDouble(n.path("f2")))
                            .changePct(asDouble(n.path("f3")))
                            .volume(asLong(n.path("f5")))
                            .amount(asDouble(n.path("f6")))
                            .build());
                }
                return quotes;
            } catch (IOException e) {
                lastIo = e;
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        log.warn("Eastmoney clist 三次重试均失败 page={}: {}", page,
                lastIo == null ? "?" : lastIo.getMessage());
        failedOut[0] = true;
        return Collections.emptyList();
    }

    public List<DailyBar> fetchKline(String code, String secid, int days) {
        HttpUrl url = HttpUrl.parse(KLINE_URL).newBuilder()
                .addQueryParameter("secid", secid)
                .addQueryParameter("klt", "101")   // 日 K
                .addQueryParameter("fqt", "1")     // 前复权
                .addQueryParameter("end", "20500101")
                .addQueryParameter("lmt", String.valueOf(days))
                .addQueryParameter("fields1", "f1,f2,f3,f4,f5,f6")
                .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://quote.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();

        log.info("请求东方财富K线 secid={} code={} 入参days={} URL={}", secid, code, days, url);

        IOException lastIo = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("东方财富K线响应异常 secid={} attempt={} HTTP={}", secid, attempt, resp.code());
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    return Collections.emptyList();
                }
                String bodyStr = resp.body().string();
                log.info("东方财富K线响应 secid={} HTTP={} body前500字={}", secid, resp.code(),
                        bodyStr.length() > 500 ? bodyStr.substring(0, 500) : bodyStr);
                JsonNode root = mapper.readTree(bodyStr);
                JsonNode klines = root.path("data").path("klines");
                if (!klines.isArray()) return Collections.emptyList();
                List<DailyBar> bars = new ArrayList<>(klines.size());
                for (JsonNode k : klines) {
                    String[] p = k.asText().split(",");
                    if (p.length < 7) continue;
                    bars.add(DailyBar.builder()
                            .code(code)
                            .tradeDate(LocalDate.parse(p[0]))
                            .open(parseD(p[1]))
                            .close(parseD(p[2]))
                            .high(parseD(p[3]))
                            .low(parseD(p[4]))
                            .volume(parseL(p[5]))
                            .amount(parseD(p[6]))
                            .build());
                }
                return bars;
            } catch (IOException e) {
                lastIo = e;
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        log.warn("Eastmoney kline 三次重试均失败 secid={}: {}", secid,
                lastIo == null ? "?" : lastIo.getMessage());
        return Collections.emptyList();
    }

    private static double parseD(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return 0d;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0d; }
    }
    private static long parseL(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    public static final class FlowPoint {
        public final LocalDate date;
        public final double mainNetInflow;
        public FlowPoint(LocalDate date, double mainNetInflow) {
            this.date = date; this.mainNetInflow = mainNetInflow;
        }
        public LocalDate getDate() { return date; }
        public double getMainNetInflow() { return mainNetInflow; }
    }

    public List<FlowPoint> fetchMarketMoneyFlow(int days) {
        HttpUrl url = HttpUrl.parse("http://push2.eastmoney.com/api/qt/stock/fflow/kline/get").newBuilder()
                .addQueryParameter("lmt", String.valueOf(days))
                .addQueryParameter("klt", "101")
                .addQueryParameter("secid", "1.000001")
                .addQueryParameter("secid2", "0.399001")
                .addQueryParameter("fields1", "f1,f2,f3,f7")
                .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://data.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    log.warn("大盘资金流请求失败 HTTP={}", resp.code());
                    return Collections.emptyList();
                }
                JsonNode klines = mapper.readTree(resp.body().byteStream())
                        .path("data").path("klines");
                if (!klines.isArray()) return Collections.emptyList();
                List<FlowPoint> out = new ArrayList<>(klines.size());
                for (JsonNode k : klines) {
                    String[] p = k.asText().split(",");
                    if (p.length < 2) continue;
                    out.add(new FlowPoint(LocalDate.parse(p[0]), parseD(p[1])));
                }
                return out;
            } catch (IOException e) {
                if (attempt < 3) sleepBackoff(attempt);
                else log.warn("大盘资金流异常: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    public Quote searchStock(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return null;
        String kw = keyword.trim();

        if (kw.matches("\\d{6}")) {
            Quote q = quoteByCode(kw);
            if (q != null) return q;
            log.warn("代码 {} 直查行情未命中，转 suggest 搜索", kw);
        }

        HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                .addQueryParameter("input", kw)
                .addQueryParameter("type", "14")
                .addQueryParameter("token", "D43BF722C8E33BDC906FB84D85E326E8")
                .addQueryParameter("count", "8")
                .addQueryParameter("_", String.valueOf(System.currentTimeMillis()))
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://www.eastmoney.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("suggest 请求失败 input={} HTTP={}", kw, resp.code());
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    return null;
                }
                String body = resp.body().string();
                JsonNode data = mapper.readTree(body)
                        .path("QuotationCodeTable").path("Data");
                if (!data.isArray() || data.size() == 0) {
                    log.warn("suggest 无结果 input={} 返回前300字: {}", kw,
                            body.length() > 300 ? body.substring(0, 300) : body);
                    return null;
                }
                JsonNode chosen = null;
                for (JsonNode n : data) {
                    String classify = n.path("Classify").asText();
                    if ("AStock".equalsIgnoreCase(classify)) { chosen = n; break; }
                }
                if (chosen == null) chosen = data.get(0);
                String code = chosen.path("Code").asText();
                String name = chosen.path("Name").asText();
                int mkt = chosen.path("MktNum").asInt(-1);
                String exchange = mkt == 1 ? "sh"
                        : (code.startsWith("8") || code.startsWith("4") || code.startsWith("9") ? "bj" : "sz");
                if (code.isEmpty()) return null;
                return Quote.builder().code(code).name(name).exchange(exchange).build();
            } catch (IOException e) {
                log.warn("suggest 异常 input={} attempt={}: {}", kw, attempt, e.getMessage());
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        return null;
    }

    private Quote quoteByCode(String code) {
        String[] secids = (code.startsWith("6") || code.startsWith("9"))
                ? new String[]{"1." + code, "0." + code}
                : new String[]{"0." + code, "1." + code};
        for (String secid : secids) {
            HttpUrl url = HttpUrl.parse("http://push2.eastmoney.com/api/qt/stock/get").newBuilder()
                    .addQueryParameter("secid", secid)
                    .addQueryParameter("fields", "f57,f58")
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .header("Referer", "https://quote.eastmoney.com/")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Connection", "close")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) continue;
                JsonNode d = mapper.readTree(resp.body().byteStream()).path("data");
                String rcode = d.path("f57").asText("");
                String name = d.path("f58").asText("");
                if (!rcode.isEmpty() && !name.isEmpty() && !"-".equals(name)) {
                    String exchange = secid.startsWith("1.") ? "sh"
                            : (code.startsWith("8") || code.startsWith("4")) ? "bj" : "sz";
                    return Quote.builder().code(rcode).name(name).exchange(exchange).build();
                }
            } catch (IOException e) {
                log.warn("按代码查行情失败 secid={}: {}", secid, e.getMessage());
            }
        }
        return null;
    }

    // ===== 涨停/炸板/跌停池 =====

    public LimitUpPools fetchLimitUpPools(java.time.LocalDate date) {
        String d = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        LimitUpPools out = new LimitUpPools();
        out.zt = fetchPool("getTopicZTPool", "fbt:asc", d);
        out.zb = fetchPool("getTopicZBPool", "fbt:asc", d);
        out.dt = fetchPool("getTopicDTPool", "fund:asc", d);
        out.yzt = fetchPool("getYesterdayZTPool", "zs:desc", d);
        return out;
    }

    public static final class PoolItem {
        public String code, name, industry;
        public int lbc;          // 连板数（仅涨停池/昨日涨停池有意义）
        public double zdp;
        public double price = Double.NaN;
        public double amount = Double.NaN;   // 成交额（元）
        public double ltsz = Double.NaN;     // 流通市值（元）
        public double hs = Double.NaN;       // 换手率 %
        public double fund = Double.NaN;
        public double zf = Double.NaN;       // 振幅 %（仅炸板池）
        public int zbc;                      // 炸板次数
        public int fbt, lbt;                 // 首次/最后封板时间 HHMMSS
    }

    public static final class LimitUpPools {
        public java.util.List<PoolItem> zt = java.util.Collections.emptyList();
        public java.util.List<PoolItem> zb = java.util.Collections.emptyList();
        public java.util.List<PoolItem> dt = java.util.Collections.emptyList();
        public java.util.List<PoolItem> yzt = java.util.Collections.emptyList();
    }

    private List<PoolItem> fetchPool(String api, String sort, String yyyymmdd) {
        HttpUrl url = HttpUrl.parse("http://push2ex.eastmoney.com/" + api).newBuilder()
                .addQueryParameter("ut", "7eea3edcaed734bea9cbfc24409ed989")
                .addQueryParameter("dpt", "wz.ztzt")
                .addQueryParameter("Pageindex", "0")
                .addQueryParameter("pagesize", "500")
                .addQueryParameter("sort", sort)
                .addQueryParameter("date", yyyymmdd)
                .build();
        Request req = new Request.Builder().url(url)
                .header("Referer", "https://quote.eastmoney.com/ztb/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    log.warn("涨停池接口 {} HTTP={}", api, resp.code());
                    return java.util.Collections.emptyList();
                }
                JsonNode root = mapper.readTree(resp.body().string());
                JsonNode pool = root.path("data").path("pool");
                if (!pool.isArray()) return java.util.Collections.emptyList();
                List<PoolItem> items = new ArrayList<>(pool.size());
                for (JsonNode p : pool) {
                    PoolItem it = new PoolItem();
                    it.code = p.path("c").asText("");
                    it.name = p.path("n").asText("");
                    it.industry = p.path("hybk").asText("");
                    it.lbc = p.path("lbc").asInt(0);
                    it.zdp = p.path("zdp").asDouble(Double.NaN);
                    if (p.path("p").isNumber()) it.price = p.path("p").asDouble() / 1000d;
                    it.amount = p.path("amount").asDouble(Double.NaN);
                    it.ltsz = p.path("ltsz").asDouble(Double.NaN);
                    it.hs = p.path("hs").asDouble(Double.NaN);
                    it.fund = p.path("fund").asDouble(Double.NaN);
                    it.zf = p.path("zf").asDouble(Double.NaN);
                    it.zbc = p.path("zbc").asInt(0);
                    it.fbt = p.path("fbt").asInt(0);
                    it.lbt = p.path("lbt").asInt(0);
                    items.add(it);
                }
                return items;
            } catch (IOException e) {
                if (attempt < 3) sleepBackoff(attempt);
                else log.warn("涨停池接口 {} 三次重试均失败: {}", api, e.getMessage());
            }
        }
        return java.util.Collections.emptyList();
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(200L * attempt * attempt); } // 200ms, 800ms
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static double asDouble(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0d;
        if (n.isNumber()) return n.asDouble();
        String s = n.asText();
        if (s == null || s.isEmpty() || "-".equals(s)) return 0d;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0d; }
    }

    private static long asLong(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0L;
        if (n.isNumber()) return n.asLong();
        String s = n.asText();
        if (s == null || s.isEmpty() || "-".equals(s)) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }
}
