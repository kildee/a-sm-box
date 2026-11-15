package com.smbox.desktop;

import java.awt.Desktop;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * 桌面模式：安装包启动时带 -Dsmbox.desktop=true。
 * 数据放在用户目录下，端口固定，已有实例在跑时只打开页面不重复启动。
 */
public final class DesktopLauncher {

    public static final int PORT = 8099;
    public static final String URL = "http://localhost:" + PORT + "/";

    private DesktopLauncher() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("smbox.desktop", "false"));
    }

    /** 启动前的准备。返回 false 表示已有实例在运行，本进程应直接退出。 */
    public static boolean prepare() {
        if (alreadyRunning()) {
            openBrowser();
            return false;
        }
        File home = new File(System.getProperty("user.home"), ".smbox");
        File data = new File(home, "data");
        data.mkdirs();
        String db = data.getAbsolutePath().replace('\\', '/') + "/smbox";
        setIfAbsent("server.port", String.valueOf(PORT));
        setIfAbsent("server.address", "127.0.0.1");
        setIfAbsent("spring.datasource.url",
                "jdbc:h2:file:" + db + ";AUTO_SERVER=TRUE;MODE=MySQL;DATABASE_TO_UPPER=FALSE");
        setIfAbsent("logging.file.name", new File(home, "smbox.log").getAbsolutePath());
        return true;
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    private static boolean alreadyRunning() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void openBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(URL));
            }
        } catch (Throwable ignore) {
        }
    }
}
