package com.smbox.web;

import com.smbox.service.BottomTurnService;
import com.smbox.storage.ScanResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScanResultController {
    private final ScanResultRepository scanResultRepository;
    private final BottomTurnService bottomTurnService;
    private final ObjectMapper mapper;

    @PostMapping("/watch/scan-result/batch")
    public Map<String, Object> putScanResultBatch(@RequestParam String name,
                                                  @RequestParam(defaultValue = "false") boolean reset,
                                                  @RequestBody Map<String, Object> body) {
        if (reset) scanResultRepository.deleteGroup(name);
        LocalDateTime ts = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        List<ScanResultRepository.Row> rows = ScanResultRepository.parseRows(body, mapper);
        bottomTurnService.annotateScan(rows);
        scanResultRepository.upsertBatch(name, rows, ts);

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("saved", rows.size());
        resp.put("scannedAt", ts.toString());
        return resp;
    }

    @GetMapping("/watch/scan-result")
    public Map<String, Object> getScanResult(@RequestParam String name,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "30") int limit,
                                             @RequestParam Map<String, String> q) {
        limit = Math.max(1, Math.min(limit, 500));
        offset = Math.max(0, offset);
        ScanResultRepository.Filter f = filterOf(q);

        Map<String, Object> resp = new HashMap<>();
        resp.put("tradingNow", TradingClock.isTradingNow());
        List<String> payloads = scanResultRepository.findPage(name, f, offset, limit);
        List<JsonNode> items = new ArrayList<>(payloads.size());
        for (String p : payloads) {
            try { items.add(mapper.readTree(p)); } catch (Exception ignore) { }
        }
        List<String> codes = codesOf(items);
        resp.put("items", items);
        resp.put("bt", safe(() -> scanResultRepository.bottomTurnOf(name, codes)));
        resp.put("os", safe(() -> scanResultRepository.oversoldOf(name, codes)));
        resp.put("flag", safe(() -> scanResultRepository.flagOf(name, codes)));
        Map<String, Object> st = scanResultRepository.stats(name, f);
        resp.put("total", ((Number) st.get("total")).intValue());
        resp.put("strong", ((Number) st.get("strong")).intValue());
        resp.put("build", ((Number) st.get("build")).intValue());
        resp.put("offset", offset);
        resp.put("limit", limit);
        LocalDateTime at = scanResultRepository.maxScannedAt(name);
        if (at != null) resp.put("scannedAt", at.toString());
        return resp;
    }

    @GetMapping("/watch/scan-result/export")
    public Map<String, Object> exportScanResult(@RequestParam String name,
                                                @RequestParam Map<String, String> q) {
        List<Map<String, Object>> items = scanResultRepository.exportCodeName(name, filterOf(q));
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("items", items);
        resp.put("total", items.size());
        return resp;
    }

    // ===== 工具 =====

    static ScanResultRepository.Filter filterOf(Map<String, String> q) {
        ScanResultRepository.Filter f = new ScanResultRepository.Filter();
        f.bLabel = str(q, "bLabel");
        f.bDays = intOf(q, "bDays");
        f.xLabel = str(q, "xLabel");
        f.xDays = intOf(q, "xDays");
        f.bCount = intOf(q, "bCount");
        f.regime = str(q, "regime");
        f.kw = str(q, "kw");
        return f;
    }

    private static String str(Map<String, String> q, String k) {
        String v = q.get(k);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Integer intOf(Map<String, String> q, String k) {
        try { return str(q, k) == null ? null : Integer.valueOf(q.get(k).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static List<String> codesOf(List<JsonNode> items) {
        List<String> codes = new ArrayList<>();
        for (JsonNode n : items) if (n.hasNonNull("code")) codes.add(n.get("code").asText());
        return codes;
    }

    private interface MapCall { Map<String, Map<String, Object>> get() throws Exception; }

    private static Map<String, Map<String, Object>> safe(MapCall c) {
        try { return c.get(); } catch (Exception e) { return new HashMap<>(); }
    }
}
