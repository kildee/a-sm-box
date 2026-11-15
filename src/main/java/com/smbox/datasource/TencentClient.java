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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TencentClient {
    private static final String KLINE_URL = "http://web.ifzq.gtimg.cn/appstock/app/fqkline/get";

    private static final String SUGGEST_URL = "http://smartbox.gtimg.cn/s3/";

    private static final Charset GBK = Charset.forName("GBK");

    private final OkHttpClient http;
    private final ObjectMapper mapper;





    public List<DailyBar> fetchKline(String code, String secid, int days) {
        String market = (secid != null && secid.startsWith("1.")) ? "sh" : "sz";
        String symbol = market + code;
        String param = symbol + ",day,,," + days + ",qfq";

        HttpUrl url = HttpUrl.parse(KLINE_URL).newBuilder()
                .addQueryParameter("param", param)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://gu.qq.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();

        log.info("请求腾讯K线(备用) symbol={} code={} days={} URL={}", symbol, code, days, url);

        IOException lastIo = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("腾讯K线响应异常 symbol={} attempt={} HTTP={}", symbol, attempt, resp.code());
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    return Collections.emptyList();
                }
                String bodyStr = resp.body().string();
                JsonNode root = mapper.readTree(bodyStr);
                JsonNode stockNode = root.path("data").path(symbol);
                JsonNode arr = stockNode.path("qfqday");
                if (!arr.isArray() || arr.size() == 0) arr = stockNode.path("day");
                if (!arr.isArray() || arr.size() == 0) {
                    log.warn("腾讯K线无数据 symbol={} body前300字={}", symbol,
                            bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr);
                    return Collections.emptyList();
                }
                List<DailyBar> bars = new ArrayList<>(arr.size());
                for (JsonNode row : arr) {
                    if (!row.isArray() || row.size() < 6) continue;
                    bars.add(DailyBar.builder()
                            .code(code)
                            .tradeDate(LocalDate.parse(row.get(0).asText()))
                            .open(parseD(row.get(1).asText()))
                            .close(parseD(row.get(2).asText()))
                            .high(parseD(row.get(3).asText()))
                            .low(parseD(row.get(4).asText()))
                            .volume((long) parseD(row.get(5).asText()))
                            .amount(row.size() >= 7 ? parseD(row.get(6).asText()) * 10000d : 0d)
                            .build());
                }
                log.info("腾讯K线(备用)成功 symbol={} 返回 {} 条", symbol, bars.size());
                return bars;
            } catch (IOException e) {
                lastIo = e;
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        log.warn("腾讯K线三次重试均失败 symbol={}: {}", symbol,
                lastIo == null ? "?" : lastIo.getMessage());
        return Collections.emptyList();
    }

    public Quote searchStock(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return null;
        String kw = keyword.trim();

        String q;
        try {
            q = URLEncoder.encode(kw, "GBK");
        } catch (UnsupportedEncodingException e) {
            q = kw;
        }
        HttpUrl url = HttpUrl.parse(SUGGEST_URL).newBuilder()
                .addQueryParameter("v", "2")
                .addEncodedQueryParameter("q", q)
                .addQueryParameter("t", "all")
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("Referer", "https://stockapp.finance.qq.com/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Connection", "close")
                .build();

        log.info("请求腾讯搜索(备用) input={} URL={}", kw, url);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("腾讯搜索响应异常 input={} attempt={} HTTP={}", kw, attempt, resp.code());
                    if (attempt < 3) { sleepBackoff(attempt); continue; }
                    return null;
                }
                String body = new String(resp.body().bytes(), GBK);
                int s = body.indexOf('"');
                int e = body.lastIndexOf('"');
                if (s < 0 || e <= s) {
                    log.warn("腾讯搜索无结果 input={} 返回: {}", kw,
                            body.length() > 200 ? body.substring(0, 200) : body);
                    return null;
                }
                String hint = body.substring(s + 1, e);
                if (hint.isEmpty()) return null;

                Quote first = null;
                for (String entry : hint.split("\\^")) {
                    String[] f = entry.split("~");
                    if (f.length < 2 || f[0].length() < 3) continue;
                    String market = f[0].substring(0, 2);     // sh / sz / bj
                    String code = f[0].substring(2);
                    String name = f[1];
                    if (!code.matches("\\d{6}")) continue;
                    if (!"sh".equals(market) && !"sz".equals(market) && !"bj".equals(market)) continue;
                    Quote quote = Quote.builder().code(code).name(name).exchange(market).build();
                    String type = f.length >= 4 ? f[3] : "";
                    if (type.startsWith("GP-A")) return quote;   // A 股优先直接返回
                    if (first == null) first = quote;
                }
                return first;
            } catch (IOException ex) {
                log.warn("腾讯搜索异常 input={} attempt={}: {}", kw, attempt, ex.getMessage());
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        return null;
    }

    private static double parseD(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return 0d;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0d; }
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(attempt * 300L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
