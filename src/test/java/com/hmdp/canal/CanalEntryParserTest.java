package com.hmdp.canal;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.hmdp.canal.CanalEntryParser.CacheKey;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canal binlog 条目解析器单元测试：表过滤、主键提取（isKey/退化 id 列）、事件类型差异。
 */
class CanalEntryParserTest {

    private static Entry rowDataEntry(String table, EventType eventType,
                                      List<CanalEntry.Column> before, List<CanalEntry.Column> after) {
        CanalEntry.RowData.Builder rowData = CanalEntry.RowData.newBuilder()
                .addAllBeforeColumns(before)
                .addAllAfterColumns(after);
        CanalEntry.RowChange.Builder rowChange = CanalEntry.RowChange.newBuilder()
                .setEventType(eventType)
                .addRowDatas(rowData);
        CanalEntry.Header.Builder header = CanalEntry.Header.newBuilder()
                .setSchemaName("hmdp")
                .setTableName(table);
        return CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(header)
                .setStoreValue(rowChange.build().toByteString())
                .build();
    }

    private static CanalEntry.Column column(String name, String value, boolean isKey) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).setIsKey(isKey).build();
    }

    @Test
    void tb_shop_update_shouldEvictShopCache() {
        Entry entry = rowDataEntry("tb_shop", EventType.UPDATE,
                Collections.singletonList(column("id", "1", true)),
                Collections.singletonList(column("id", "1", true)));

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getKeyPrefix()).isEqualTo("cache:shop:");
        assertThat(keys.get(0).getId()).isEqualTo("1");
    }

    @Test
    void tb_shop_delete_shouldReadIdFromBeforeColumns() {
        // DELETE 时 after 列为空，必须从 before 取主键
        Entry entry = rowDataEntry("tb_shop", EventType.DELETE,
                Collections.singletonList(column("id", "7", true)),
                Collections.emptyList());

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getKeyPrefix()).isEqualTo("cache:shop:");
        assertThat(keys.get(0).getId()).isEqualTo("7");
    }

    @Test
    void tb_voucher_insert_shouldEvictVoucherCache() {
        Entry entry = rowDataEntry("tb_voucher", EventType.INSERT,
                Collections.emptyList(),
                Collections.singletonList(column("id", "10", true)));

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getKeyPrefix()).isEqualTo("cache:voucher:");
        assertThat(keys.get(0).getId()).isEqualTo("10");
    }

    @Test
    void idColumn_withoutIsKeyFlag_shouldFallbackToIdName() {
        // 极端情况下 binlog 未标记 isKey，按列名 id 退化查找
        Entry entry = rowDataEntry("tb_shop", EventType.UPDATE,
                Collections.emptyList(),
                Collections.singletonList(column("id", "99", false)));

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getId()).isEqualTo("99");
    }

    @Test
    void unknownTable_shouldBeIgnored() {
        Entry entry = rowDataEntry("tb_blog", EventType.UPDATE,
                Collections.emptyList(),
                Collections.singletonList(column("id", "1", true)));

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).isEmpty();
    }

    @Test
    void transactionEvent_shouldBeIgnored() {
        // 非 ROWDATA 条目（事务开始/结束）直接忽略
        Entry entry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.TRANSACTIONBEGIN)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("hmdp").setTableName(""))
                .build();

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).isEmpty();
    }

    @Test
    void noIdColumn_shouldBeIgnored() {
        Entry entry = rowDataEntry("tb_shop", EventType.UPDATE,
                Collections.emptyList(),
                Collections.singletonList(column("name", "xx", false)));

        List<CacheKey> keys = CanalEntryParser.parse(Collections.singletonList(entry));

        assertThat(keys).isEmpty();
    }
}