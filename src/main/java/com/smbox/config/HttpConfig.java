package com.smbox.config;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class HttpConfig {
    private static final Logger log = LoggerFactory.getLogger(HttpConfig.class);

    @Bean
    public OkHttpClient okHttpClient(AppProperties properties) {
        AppProperties.Http http = properties.getHttp();
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(http.getConnectTimeout()))
                .readTimeout(Duration.ofSeconds(http.getReadTimeout()))
                .retryOnConnectionFailure(true)
                .dns(IPV4_FIRST_DNS);

        String proxyHost = http.getProxyHost();
        if (proxyHost != null && !proxyHost.trim().isEmpty() && http.getProxyPort() > 0) {
            builder.proxy(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost.trim(), http.getProxyPort())));
            log.warn("OkHttp 走代理: {}:{}", proxyHost.trim(), http.getProxyPort());
        }
        return builder.build();
    }

    private static final Dns IPV4_FIRST_DNS = hostname -> {
        List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
        List<InetAddress> v4 = all.stream()
                .filter(a -> a instanceof Inet4Address)
                .collect(Collectors.toList());
        return v4.isEmpty() ? all : v4;
    };
}
