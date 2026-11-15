package com.smbox.desktop;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 桌面模式下给页面用的两个接口：是否桌面模式、退出程序。 */
@RestController
public class DesktopController {

    private final DesktopTray tray;

    public DesktopController(DesktopTray tray) {
        this.tray = tray;
    }

    @GetMapping("/api/desktop/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("desktop", DesktopLauncher.enabled());
        return m;
    }

    @PostMapping("/api/desktop/quit")
    public Map<String, Object> quit() {
        if (!DesktopLauncher.enabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅安装包模式可用");
        tray.quit();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
