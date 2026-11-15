package com.smbox.web;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.datasource.TencentClient;
import com.smbox.model.DailyBar;
import com.smbox.service.WatchlistService;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.RpsRepository;
import com.smbox.storage.ScanResultRepository;
import com.smbox.storage.WatchItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WatchController {
    private final WatchlistService watchlistService;
    private final WatchItemRepository watchItemRepository;
    private final DailyBarRepository dailyBarRepository;
    private final RpsRepository rpsRepository;
    private final ScanResultRepository scanResultRepository;
    private final EastmoneyClient eastmoneyClient;
    private final TencentClient tencentClient;
    private final ObjectMapper mapper;

    // ===== 自选分组 =====

    @GetMapping("/watch/groups")
    public Map<String, Object> watchGroups() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("groups", watchlistService.listGroups());
        return resp;
    }

    @GetMapping("/watch/group")
    public Map<String, Object> watchGroup(@RequestParam String name,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "0") int limit,
                                          @RequestParam(required = false) String kw) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("group", name);
        if (limit <= 0) {
            List<com.smbox.model.WatchItem> all = watchlistService.getGroup(name);
            resp.put("items", all);
            resp.put("total", all.size());
            return resp;
        }
        limit = Math.min(limit, 500);
        offset = Math.max(0, offset);
        resp.put("items", watchItemRepository.findByGroupPage(name, kw, offset, limit));
        resp.put("total", watchItemRepository.countByGroup(name, kw));
        resp.put("offset", offset);
        return resp;
    }

    @PostMapping("/watch/group/{name}/add")
    public Map<String, Object> watchAdd(@PathVariable String name,
                                        @RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        List<String> names = Arrays.stream(text.split("[\\n,，、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        WatchlistService.AddResult result = watchlistService.addToGroup(name, names);
        Map<String, Object> resp = new HashMap<>();
        resp.put("added", result.getAdded());
        resp.put("failed", result.getFailed());
        resp.put("items", watchlistService.getGroup(name));
        return resp;
    }

    /** 从分组移除一只 */
    @PostMapping("/watch/group/{name}/remove")
    public Map<String, Object> watchRemove(@PathVariable String name,
                                           @RequestParam String code) {
        watchlistService.removeFromGroup(name, code);
        Map<String, Object> resp = new HashMap<>();
        resp.put("items", watchlistService.getGroup(name));
        return resp;
    }

    @PostMapping("/watch/group/{name}/delete")
    public Map<String, Object> watchDeleteGroup(@PathVariable String name) {
        Map<String, Object> resp = new HashMap<>();
        if (WatchlistService.isProtectedGroup(name)) {
            resp.put("ok", false);
            resp.put("error", "「全部」是默认分组，不能删除");
            resp.put("groups", watchlistService.listGroups());
            return resp;
        }
        watchlistService.deleteGroup(name);
        scanResultRepository.deleteGroup(name);
        resp.put("ok", true);
        resp.put("groups", watchlistService.listGroups());
        return resp;
    }

    @PostMapping("/watch/refresh-all")
    public Map<String, Object> refreshAll() {
        new Thread(() -> {
            log.info("一键刷新自选K线：开始");
            try { watchlistService.warmKlineCacheManual(130); log.info("一键刷新自选K线：完成"); }
            catch (Exception e) { log.error("一键刷新自选K线失败", e); }
        }, "refresh-watch-kline").start();

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("message", "已触发自选分组 K 线刷新，约 1-2 分钟，完成后刷新查看");
        return resp;
    }

    @GetMapping("/watch/warm-status")
    public Map<String, Object> warmStatus() {
        Map<String, Object> m = watchlistService.warmStatus();
        Object last = m.get("last");
        if (last instanceof String) {
            try { m.put("last", mapper.readTree((String) last)); } catch (Exception ignore) { }
        }
        return m;
    }

    // ===== 日K =====

    @PostMapping("/kline/batch")
    public Map<String, Object> klineBatch(@RequestBody Map<String, Object> body) {
        Object codesObj = body.get("codes");
        int days = body.get("days") instanceof Number ? ((Number) body.get("days")).intValue() : 130;
        days = Math.max(1, Math.min(days, 500));
        Map<String, Object> out = new HashMap<>();
        Map<String, Object> map = new HashMap<>();
        if (codesObj instanceof List) {
            List<?> codes = (List<?>) codesObj;
            if (codes.size() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单批最多 200 只");
            }
            for (Object o : codes) {
                if (o == null) continue;
                String code = o.toString();
                if (code.isEmpty() || code.startsWith("920")) continue;
                Map<String, Object> one = new HashMap<>();
                try {
                    List<DailyBar> cached = dailyBarRepository.findRecent(code, days);
                    if (!cached.isEmpty()) Collections.reverse(cached);
                    one.put("bars", cached);
                    try {
                        Map<String, Object> rps = rpsRepository.findByCode(code);
                        if (rps != null) one.put("rps", rps);
                    } catch (Exception ignore) { /* rps 未就绪不影响K线 */ }
                } catch (Exception e) {
                    one.put("bars", Collections.emptyList());
                }
                map.put(code, one);
            }
        }
        out.put("data", map);
        return out;
    }

    @GetMapping("/kline/{code}")
    public Map<String, Object> kline(@PathVariable String code,
                                     @RequestParam(defaultValue = "65") int days,
                                     @RequestParam(defaultValue = "false") boolean force) {
        if (code.startsWith("920")) {
            List<DailyBar> cached = dailyBarRepository.findRecent(code, days);
            if (!cached.isEmpty()) Collections.reverse(cached);
            return klineResponse(code, cached, cached.isEmpty() ? "none" : "cache");
        }

        if (!force) {
            List<DailyBar> cached = dailyBarRepository.findRecent(code, days);
            if (!cached.isEmpty()) {
                Collections.reverse(cached);
                return klineResponse(code, cached, "cache");
            }
            log.info("kline 缓存为空，首次拉取一次 code={}", code);
        }

        String secid = (code.startsWith("6") || code.startsWith("9"))
                ? "1." + code : "0." + code;

        List<DailyBar> bars = eastmoneyClient.fetchKline(code, secid, days);
        String source = "live";
        if (bars.isEmpty()) {
            bars = tencentClient.fetchKline(code, secid, days);
            if (!bars.isEmpty()) {
                source = "live-tencent";
                log.info("东方财富拉空，腾讯备用源命中 code={} 返回 {} 条", code, bars.size());
            }
        }

        if (!bars.isEmpty()) {
            try {
                dailyBarRepository.upsertBatch(bars);
            } catch (Exception e) {
                log.warn("daily_bar 写缓存失败 code={}: {}", code, e.getMessage());
            }
        } else {
            List<DailyBar> cached = dailyBarRepository.findRecent(code, days);
            if (!cached.isEmpty()) {
                Collections.reverse(cached);
                bars = cached;
                source = "cache";
                log.info("东财+腾讯均拉空，回退缓存 code={} 返回 {} 条", code, bars.size());
            } else {
                source = "none";
            }
        }

        return klineResponse(code, bars, source);
    }

    private Map<String, Object> klineResponse(String code, List<DailyBar> bars, String source) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", code);
        resp.put("bars", bars);
        resp.put("source", source);
        try {
            Map<String, Object> rps = rpsRepository.findByCode(code);
            if (rps != null) resp.put("rps", rps);
        } catch (Exception ignore) { /* rps 表未就绪不影响K线 */ }
        return resp;
    }
}
