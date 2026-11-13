package com.smbox.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class MetaRepository {
    private final JdbcTemplate jdbc;

    public String get(String key) {
        List<String> r = jdbc.query("SELECT v FROM meta_kv WHERE k = ?",
                (rs, i) -> rs.getString("v"), key);
        return r.isEmpty() ? null : r.get(0);
    }

    public void put(String key, String value) {
        jdbc.update("MERGE INTO meta_kv(k, v) KEY(k) VALUES (?,?)", key, value);
    }
}
