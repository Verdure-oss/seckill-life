package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.hmdp.utils.RedisConstants;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canal binlog 事件解析器：把 ROWDATA 条目转换为「缓存失效命令」。
 * <p>
 * 当前维护 {@code tb_shop → cache:shop:{id}}、{@code tb_voucher → cache:voucher:{id}} 两张映射，
 * 其余表忽略。DELETE 事件取 before 列、其余取 after 列中的主键（isKey 优先，退化为 id 列）。
 * </p>
 */
@Slf4j
public final class CanalEntryParser {

    private CanalEntryParser() {
    }

    /** 表名 → 多级缓存 key 前缀 */
    private static final Map<String, String> TABLE_KEY_PREFIX = Map.of(
            "tb_shop", RedisConstants.CACHE_SHOP_KEY,
            "tb_voucher", RedisConstants.CACHE_VOUCHER_KEY
    );

    /** 缓存失效命令：keyPrefix + id 即完整的多级缓存键 */
    @Getter
    @AllArgsConstructor
    public static class CacheKey {
        private final String keyPrefix;
        private final Object id;
    }

    /**
     * 解析一批 binlog 条目，产出需要失效的缓存键。
     * 单条解析失败不影响其余条目（记录日志后跳过）。
     */
    public static List<CacheKey> parse(List<Entry> entries) {
        List<CacheKey> result = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        for (Entry entry : entries) {
            try {
                parseRowData(entry, result);
            } catch (Exception e) {
                log.warn("解析 Canal 条目失败，跳过: table={}, type={}, err={}",
                        entry.getHeader().getTableName(), entry.getEntryType(), e.toString());
            }
        }
        return result;
    }

    private static void parseRowData(Entry entry, List<CacheKey> result) throws Exception {
        if (entry.getEntryType() != EntryType.ROWDATA) {
            return; // 忽略事务开始/结束等事件
        }
        String table = entry.getHeader().getTableName();
        String keyPrefix = TABLE_KEY_PREFIX.get(table);
        if (keyPrefix == null) {
            return; // 未订阅的表，忽略
        }

        RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
        EventType eventType = rowChange.getEventType();
        for (RowData rowData : rowChange.getRowDatasList()) {
            String id = extractId(rowData, eventType);
            if (id != null && !id.isEmpty()) {
                result.add(new CacheKey(keyPrefix, id));
            }
        }
    }

    /**
     * DELETE 时 after 列为空，必须读 before；INSERT/UPDATE 读 after（id 前后一致）。
     * 优先 isKey 标记的列，退化按列名 id 查找。
     */
    private static String extractId(RowData rowData, EventType eventType) {
        List<CanalEntry.Column> columns = eventType == EventType.DELETE
                ? rowData.getBeforeColumnsList()
                : rowData.getAfterColumnsList();
        if (columns == null) {
            return null;
        }
        for (CanalEntry.Column column : columns) {
            if (column.getIsKey()) {
                return column.getValue();
            }
        }
        for (CanalEntry.Column column : columns) {
            if ("id".equalsIgnoreCase(column.getName())) {
                return column.getValue();
            }
        }
        return null;
    }
}