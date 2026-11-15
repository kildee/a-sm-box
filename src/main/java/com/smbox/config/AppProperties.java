package com.smbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "smbox")
public class AppProperties {
    private boolean scheduleEnabled = true;
    private Http http = new Http();

    @Data
    public static class Http {
        private int connectTimeout = 10;
        private int readTimeout = 20;
        private String proxyHost = "";
        private int proxyPort = 0;
    }
}
