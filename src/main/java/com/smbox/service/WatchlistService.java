package com.smbox.service;

import com.smbox.datasource.EastmoneyClient;
import com.smbox.datasource.TencentClient;
import com.smbox.model.DailyBar;
import com.smbox.model.Quote;
import com.smbox.model.WatchItem;
import com.smbox.storage.DailyBarRepository;
import com.smbox.storage.WatchItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {
    private final EastmoneyClient eastmoney;
    private final TencentClient tencent;
    private final WatchItemRepository repo;
    private final DailyBarRepository dailyBarRepository;
    private final RpsService rpsService;
    private final com.smbox.storage.MetaRepository metaRepository;

    private final Object warmLock = new Object();
    private volatile boolean warmRunning = false;
    private volatile int warmTotal = 0;
    private volatile int warmDone = 0;
    private volatile String warmStartedAt = null;

    public java.util.Map<String, Object> warmStatus() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("running", warmRunning);
        if (warmRunning) {
            m.put("total", warmTotal);
            m.put("done", warmDone);
            m.put("startedAt", warmStartedAt);
        }
        String last = metaRepository.get("kline_warm_last");
        if (last != null) m.put("last", last);
        return m;
    }

    private volatile java.time.LocalDateTime lastManualWarmAt;

    public static final String GROUP_ALL = "全部";

    public WarmResult warmKlineCacheManual(int days) {
        lastManualWarmAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        return warmKlineCache(days);
    }

    public boolean manuallyWarmedTodayAfter(int hour) {
        java.time.LocalDateTime t = lastManualWarmAt;
        if (t == null) return false;
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        return t.toLocalDate().equals(now.toLocalDate()) && t.getHour() >= hour;
    }

    public List<String> listGroups() {
        return repo.listGroups();
    }

    public List<WatchItem> getGroup(String groupName) {
        return repo.findByGroup(groupName);
    }

    public AddResult addToGroup(String groupName, List<String> names) {
        List<WatchItem> added = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int order = repo.maxSortOrder(groupName);

        for (String raw : names) {
            String kw = raw == null ? "" : raw.trim();
            if (kw.isEmpty()) continue;
            Quote q = eastmoney.searchStock(kw);
            if (q == null || q.getCode() == null || q.getCode().isEmpty()) {
                q = tencent.searchStock(kw);
                if (q != null && q.getCode() != null && !q.getCode().isEmpty()) {
                    log.info("东财搜索拉空，腾讯备用源命中 input={} -> {} {}", kw, q.getCode(), q.getName());
                }
            }
            if (q == null || q.getCode() == null || q.getCode().isEmpty()) {
                failed.add(kw);
                continue;
            }
            order++;
            WatchItem item = WatchItem.builder()
                    .groupName(groupName)
                    .code(q.getCode())
                    .name(q.getName())
                    .secid(q.secid())
                    .sortOrder(order)
                    .build();
            repo.upsert(item);
            added.add(item);
        }
        log.info("自选分组[{}] 添加: 成功 {} 只, 失败 {} 个 ({})",
                groupName, added.size(), failed.size(), failed);
        return new AddResult(added, failed);
    }

    public void removeFromGroup(String groupName, String code) {
        repo.delete(groupName, code);
    }

    public void deleteGroup(String groupName) {
        if (isProtectedGroup(groupName)) {
            throw new IllegalArgumentException("「" + GROUP_ALL + "」分组受保护，不能删除");
        }
        repo.deleteGroup(groupName);
    }

    public static boolean isProtectedGroup(String groupName) {
        return groupName != null && GROUP_ALL.equals(groupName.trim());
    }

    public WarmResult warmKlineCache(int days) {
        synchronized (warmLock) {
            if (warmRunning) {
                log.info("自选 K 线预热已在进行中，本次触发等待其完成后返回（不重复拉取）");
                while (warmRunning) {
                    try { warmLock.wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return new WarmResult(0, 0, 0); }
                }
                return new WarmResult(0, 0, 0);
            }
            warmRunning = true;
        }
        try {
            return doWarmKlineCache(days);
        } finally {
            synchronized (warmLock) {
                warmRunning = false;
                warmLock.notifyAll();   // 唤醒所有等待的触发方
            }
        }
    }

    private WarmResult doWarmKlineCache(int days) {
        List<WatchItem> all = repo.findAll();
        List<WatchItem> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (WatchItem item : all) {
            String code = item.getCode();
            if (code != null && code.startsWith("920")) { skipped++; continue; }  // 920 开头跳过
            if (seen.add(code)) targets.add(item);   // 多分组同一只票只拉一次
        }
        if (skipped > 0) log.info("自选 K 线预热跳过 920 开头 {} 条", skipped);
        int total = targets.size();
        warmTotal = total; warmDone = 0;
        warmStartedAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        log.info("自选 K 线预热开始：共 {} 只，数据源=东方财富（腾讯兜底）", total);

        AtomicInteger fetched = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();

        int poolSize = 3;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(poolSize, total)));
        for (WatchItem item : targets) {
            pool.submit(() -> warmOne(item, days, total, done, fetched, failed));
        }
        pool.shutdown();
        long timeoutMin = Math.max(20L, total / 60L + 10L);
        try {
            if (!pool.awaitTermination(timeoutMin, TimeUnit.MINUTES)) {
                log.warn("自选 K 线预热超时({}分钟)未完成，强制结束", timeoutMin);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        log.info("自选 K 线预热完成: 共 {} 只, 成功 {}, 失败 {}", total, fetched.get(), failed.get());
        try { rpsService.computeAndStore(); } catch (Exception e) { log.error("RPS 计算失败", e); }
        try {
            String fin = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            metaRepository.put("kline_warm_last", String.format(
                    "{\"finishedAt\":\"%s\",\"total\":%d,\"fetched\":%d,\"failed\":%d}",
                    fin, total, fetched.get(), failed.get()));
        } catch (Exception e) { log.warn("预热完成标记写入失败: {}", e.getMessage()); }
        return new WarmResult(total, fetched.get(), failed.get());
    }

    private void warmOne(WatchItem item, int days, int total,
                         AtomicInteger done, AtomicInteger fetched, AtomicInteger failed) {
        int i = done.incrementAndGet();
        warmDone = i;
        String secid = item.getSecid();
        if (secid == null || secid.isEmpty()) {
            secid = (item.getCode().startsWith("6") || item.getCode().startsWith("9"))
                    ? "1." + item.getCode() : "0." + item.getCode();
        }
        String src = "eastmoney";
        List<DailyBar> bars = eastmoney.fetchKline(item.getCode(), secid, days);
        if (bars.isEmpty()) {
            bars = tencent.fetchKline(item.getCode(), secid, days);
            src = "tencent";
        }
        if (bars.isEmpty()) {
            failed.incrementAndGet();
            log.info("自选预热[{}/{}] {} {} 跳过（东财、腾讯都无数据，可能退市/特殊股）",
                    i, total, item.getCode(), item.getName());
        } else {
            dailyBarRepository.upsertBatch(bars);
            fetched.incrementAndGet();
            log.info("自选预热[{}/{}] {} {} 成功 {} 条 源={} 末日={}",
                    i, total, item.getCode(), item.getName(),
                    bars.size(), src, bars.get(bars.size() - 1).getTradeDate());
        }
    }

    public static final class WarmResult {
        public final int total;
        public final int fetched;
        public final int failed;
        public WarmResult(int total, int fetched, int failed) {
            this.total = total; this.fetched = fetched; this.failed = failed;
        }
    }

    public static final class AddResult {
        public final List<WatchItem> added;
        public final List<String> failed;
        public AddResult(List<WatchItem> added, List<String> failed) {
            this.added = added; this.failed = failed;
        }
        public List<WatchItem> getAdded() { return added; }
        public List<String> getFailed() { return failed; }
    }
}
