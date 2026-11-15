package com.smbox.storage;

import com.smbox.model.WatchItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class WatchItemRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<WatchItem> ROW_MAPPER = (rs, i) -> WatchItem.builder()
            .groupName(rs.getString("group_name"))
            .code(rs.getString("code"))
            .name(rs.getString("name"))
            .secid(rs.getString("secid"))
            .sortOrder(rs.getInt("sort_order"))
            .build();

    public List<String> listGroups() {
        return jdbc.queryForList(
                "SELECT name FROM (SELECT name, 0 AS ord FROM watch_group "
                        + "UNION SELECT DISTINCT group_name, 1 FROM watch_item "
                        + "WHERE group_name NOT IN (SELECT name FROM watch_group)) g ORDER BY ord, name",
                String.class);
    }

    public List<WatchItem> findAll() {
        return jdbc.query(
                "SELECT * FROM watch_item ORDER BY code",
                ROW_MAPPER);
    }

    public List<WatchItem> findByGroup(String groupName) {
        return jdbc.query(
                "SELECT * FROM watch_item WHERE group_name = ? ORDER BY sort_order, code",
                ROW_MAPPER, groupName);
    }

    public List<WatchItem> findByGroupPage(String groupName, String kw, int offset, int limit) {
        if (kw != null && !kw.isEmpty()) {
            return jdbc.query(
                    "SELECT * FROM watch_item WHERE group_name = ? AND (code LIKE ? OR name LIKE ?) " +
                            "ORDER BY sort_order, code LIMIT ? OFFSET ?",
                    ROW_MAPPER, groupName, "%" + kw + "%", "%" + kw + "%", limit, offset);
        }
        return jdbc.query(
                "SELECT * FROM watch_item WHERE group_name = ? ORDER BY sort_order, code LIMIT ? OFFSET ?",
                ROW_MAPPER, groupName, limit, offset);
    }

    public int countByGroup(String groupName, String kw) {
        Integer c;
        if (kw != null && !kw.isEmpty()) {
            c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM watch_item WHERE group_name = ? AND (code LIKE ? OR name LIKE ?)",
                    Integer.class, groupName, "%" + kw + "%", "%" + kw + "%");
        } else {
            c = jdbc.queryForObject("SELECT COUNT(*) FROM watch_item WHERE group_name = ?",
                    Integer.class, groupName);
        }
        return c == null ? 0 : c;
    }

    public int maxSortOrder(String groupName) {
        Integer m = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM watch_item WHERE group_name = ?",
                Integer.class, groupName);
        return m == null ? 0 : m;
    }

    public void upsert(WatchItem item) {
        jdbc.update(
                "MERGE INTO watch_item(group_name, code, name, secid, sort_order) " +
                        "KEY(group_name, code) VALUES (?,?,?,?,?)",
                item.getGroupName(), item.getCode(), item.getName(),
                item.getSecid(), item.getSortOrder());
    }

    /** 从分组删除一只 */
    public void delete(String groupName, String code) {
        jdbc.update("DELETE FROM watch_item WHERE group_name = ? AND code = ?",
                groupName, code);
    }

    /** 删除整个分组 */
    public void deleteGroup(String groupName) {
        jdbc.update("DELETE FROM watch_item WHERE group_name = ?", groupName);
    }
}
