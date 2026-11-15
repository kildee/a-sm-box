package com.smbox;

import com.smbox.desktop.DesktopLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmBoxApplication {
    public static void main(String[] args) {
        boolean desktop = DesktopLauncher.enabled();
        if (desktop && !DesktopLauncher.prepare()) {
            return;
        }
        new SpringApplicationBuilder(SmBoxApplication.class)
                .headless(!desktop)
                .run(args);
    }
}
