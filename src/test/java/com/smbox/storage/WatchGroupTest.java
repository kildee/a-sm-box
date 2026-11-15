package com.smbox.storage;

import com.smbox.model.WatchItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WatchGroupTest {
    private EmbeddedDatabase db;
    private WatchItemRepository repo;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("watchgrouptest;MODE=MySQL;DATABASE_TO_UPPER=FALSE").addScript("schema.sql").build();
        repo = new WatchItemRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() { if (db != null) db.shutdown(); }

    private static WatchItem item(String group, String code) {
        return WatchItem.builder().groupName(group).code(code).name("名" + code).secid("1." + code).sortOrder(1).build();
    }

    @Test
    void 新库默认就有全部分组() {
        assertEquals(Collections.singletonList("全部"), repo.listGroups());
    }

    @Test
    void 全部排最前_其余按名称_不重复() {
        repo.upsert(item("B组", "600001"));
        repo.upsert(item("A组", "600002"));
        repo.upsert(item("全部", "600003"));
        assertEquals(Arrays.asList("全部", "A组", "B组"), repo.listGroups());
    }

    @Test
    void 清空全部分组的股票后分组仍在() {
        repo.upsert(item("全部", "600003"));
        repo.deleteGroup("全部");
        assertEquals(Collections.singletonList("全部"), repo.listGroups());
    }
}
