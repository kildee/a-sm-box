package com.smbox.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** 桌面模式下：启动完成后打开浏览器，并在系统托盘放一个图标（打开页面 / 退出）。 */
@Slf4j
@Component
public class DesktopTray {

    private final ConfigurableApplicationContext context;

    public DesktopTray(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!DesktopLauncher.enabled()) return;
        DesktopLauncher.openBrowser();
        installTray();
    }

    private void installTray() {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                log.info("当前环境不支持系统托盘，跳过");
                return;
            }
            PopupMenu menu = new PopupMenu();
            Font font = new Font(Font.DIALOG, Font.PLAIN, 12);
            MenuItem open = new MenuItem("打开页面");
            open.setFont(font);
            open.addActionListener(e -> DesktopLauncher.openBrowser());
            MenuItem quit = new MenuItem("退出");
            quit.setFont(font);
            quit.addActionListener(e -> quit());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);

            TrayIcon icon = new TrayIcon(trayImage(), "指标复盘盒子", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> DesktopLauncher.openBrowser());
            SystemTray.getSystemTray().add(icon);
        } catch (Throwable t) {
            log.warn("系统托盘初始化失败: {}", t.toString());
        }
    }

    public void quit() {
        new Thread(() -> {
            int code = 0;
            try {
                code = SpringApplication.exit(context, () -> 0);
            } catch (Throwable ignore) {
            }
            System.exit(code);
        }, "smbox-quit").start();
    }

    private static BufferedImage trayImage() {
        int s = 64;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1f, 0x4e, 0x79));
        g.fillRoundRect(0, 0, s, s, 16, 16);
        int[][] bars = {{12, 30, 20}, {26, 18, 28}, {40, 24, 18}};
        Color[] colors = {new Color(0xe5, 0x48, 0x4d), new Color(0x30, 0xa4, 0x6c), new Color(0xe5, 0x48, 0x4d)};
        for (int i = 0; i < bars.length; i++) {
            int x = bars[i][0], y = bars[i][1], h = bars[i][2];
            g.setColor(colors[i]);
            g.fillRect(x + 5, y - 7, 2, h + 14);
            g.fillRect(x, y, 12, h);
        }
        g.dispose();
        return img;
    }
}
