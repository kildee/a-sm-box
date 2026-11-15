package com.smbox.web;

import com.smbox.service.TradeJournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class TradeJournalController {
    private final TradeJournalService service;

    @GetMapping("/list")
    public Map<String, Object> list() {
        return service.overview();
    }

    @PostMapping("/buy")
    public Map<String, Object> buy(@RequestBody Map<String, Object> b) {
        String code = str(b.get("code"));
        if (code == null || code.isEmpty()) return err("代码不能为空");
        long id = service.addBuy(code, str(b.get("name")),
                LocalDate.parse(str(b.get("buyDate"))), dbl(b.get("buyPrice")), intv(b.get("qty")),
                str(b.get("reason")));
        return ok("id", id);
    }

    @PostMapping("/close")
    public Map<String, Object> close(@RequestBody Map<String, Object> b) {
        try {
            service.close(lng(b.get("id")),
                    LocalDate.parse(str(b.get("sellDate"))), dbl(b.get("sellPrice")), str(b.get("sellReason")));
            return ok(null, null);
        } catch (Exception e) { return err(e.getMessage()); }
    }

    @PostMapping("/tags")
    public Map<String, Object> tags(@RequestBody Map<String, Object> b) {
        service.setTags(lng(b.get("id")), str(b.get("tags")), str(b.get("note")));
        return ok(null, null);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> b) {
        service.delete(lng(b.get("id")));
        return ok(null, null);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double dbl(Object o) { return o == null ? 0 : Double.parseDouble(o.toString()); }
    private static int intv(Object o) { return o == null ? 0 : (int) Double.parseDouble(o.toString()); }
    private static long lng(Object o) { return o == null ? 0 : (long) Double.parseDouble(o.toString()); }
    private static Map<String, Object> ok(String k, Object v) {
        Map<String, Object> m = new HashMap<>(); m.put("ok", true); if (k != null) m.put(k, v); return m;
    }
    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new HashMap<>(); m.put("ok", false); m.put("error", msg); return m;
    }
}
