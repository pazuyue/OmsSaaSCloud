package com.oms.inventory.service.impl;

import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InventoryQueryService {
    @Resource private JdbcTemplate jdbc;
    public static final String[] QUANTITIES = {"zp_actual_number", "cp_actual_number", "zp_available_number", "cp_available_number", "zp_lock_number", "cp_lock_number"};
    public static final RowMapper<Map<String, Object>> ROW = (rs, n) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String label = rs.getMetaData().getColumnLabel(i);
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : label.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                name.append(upper ? Character.toUpperCase(c) : c); upper = false;
            }
            Object value = rs.getObject(i);
            if (value instanceof java.time.LocalDateTime) value=((java.time.LocalDateTime)value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            row.put(name.toString(), value instanceof java.sql.Timestamp ? value.toString().substring(0, 19) : value);
        }
        return row;
    };

    public static TableDataInfo page(List<?> rows, long total) {
        TableDataInfo result = new TableDataInfo();
        result.setCode(200); result.setMsg("查询成功"); result.setRows(rows); result.setTotal(total);
        return result;
    }

    private static int size(int size) { return Math.max(1, Math.min(100, size)); }
    private static int offset(int page, int size) { return (Math.max(1, Math.min(100000, page)) - 1) * size; }

    // The count/page/batch totals share one snapshot. Only this page's inventory keys are aggregated.
    @Transactional(readOnly = true)
    public TableDataInfo list(String company, String store, String sku, boolean onlyStock, boolean abnormal, int page, int pageSize) {
        List<Object> args = new ArrayList<>(); args.add(company);
        String where = " WHERE w.company_code=?";
        if (store != null && !store.trim().isEmpty()) { where += " AND w.store_code=?"; args.add(store.trim()); }
        if (sku != null && !sku.trim().isEmpty()) { where += " AND w.sku_sn=?"; args.add(sku.trim()); }
        if (onlyStock) where += " AND (w.zp_actual_number<>0 OR w.cp_actual_number<>0)";
        if (abnormal) where += " AND (w.zp_actual_number<>w.zp_available_number+w.zp_lock_number OR w.cp_actual_number<>w.cp_available_number+w.cp_lock_number OR " +
                Arrays.stream(QUANTITIES).map(q->"w."+q+"<0").collect(Collectors.joining(" OR ")) +
                " OR NOT EXISTS (SELECT 1 FROM wms_inventory_batch b WHERE b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code HAVING " +
                Arrays.stream(QUANTITIES).map(q -> "COALESCE(SUM(b." + q + "),0)=w." + q).collect(Collectors.joining(" AND ")) + "))";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory w" + where, Long.class, args.toArray());
        int limit = size(pageSize);
        args.add(limit); args.add(offset(page, limit));
        // MySQL 5.7 can choose the SKU index and filesort a whole tenant even for a 20-row page.
        String index = sku == null || sku.trim().isEmpty() ? " FORCE INDEX (idx_inventory_company_page)" : "";
        List<Map<String, Object>> rows = jdbc.query("SELECT w.* FROM wms_inventory w" + index + where + " ORDER BY w.id DESC LIMIT ? OFFSET ?", ROW, args.toArray());
        enrich(rows, company);
        return page(rows, total);
    }

    public void enrich(List<Map<String, Object>> rows, String company) {
        if (rows.isEmpty()) return;
        String ids = rows.stream().map(r -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(); args.add(company); rows.forEach(r -> args.add(r.get("id")));
        String sums = Arrays.stream(QUANTITIES).map(q -> "COALESCE(SUM(b." + q + "),0) AS " + q).collect(Collectors.joining(","));
        List<Map<String,Object>> totals = jdbc.query("SELECT w.id,COUNT(b.id) AS batch_count," + sums + " FROM wms_inventory w LEFT JOIN wms_inventory_batch b ON b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code WHERE w.company_code=? AND w.id IN (" + ids + ") GROUP BY w.id", ROW, args.toArray());
        Map<Object, Map<String,Object>> byId = totals.stream().collect(Collectors.toMap(r -> r.get("id"), r -> r));
        for (Map<String,Object> row : rows) {
            Map<String,Object> sum = byId.get(row.get("id"));
            boolean consistent = number(row, "zpActualNumber") == number(row,"zpAvailableNumber") + number(row,"zpLockNumber") && number(row,"cpActualNumber") == number(row,"cpAvailableNumber") + number(row,"cpLockNumber");
            for (String key : Arrays.asList("zpActualNumber","cpActualNumber","zpAvailableNumber","cpAvailableNumber","zpLockNumber","cpLockNumber")) {
                consistent &= number(row,key) == number(sum,key) && number(row,key) >= 0;
            }
            row.put("batchCount", sum.get("batchCount")); row.put("batchTotals", sum); row.put("consistent", consistent);
        }
    }

    @Transactional(readOnly = true)
    public Map<String,Object> detail(String company, long id) {
        List<Map<String,Object>> rows = jdbc.query("SELECT * FROM wms_inventory WHERE company_code=? AND id=?", ROW, company, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("库存不存在或无权访问");
        enrich(rows, company); return rows.get(0);
    }

    public TableDataInfo history(String company, long id, String operation, int page, int pageSize) {
        List<Map<String,Object>> found = jdbc.query("SELECT sku_sn,store_code FROM wms_inventory WHERE company_code=? AND id=?", ROW, company,id);
        if (found.isEmpty()) throw new IllegalArgumentException("库存不存在或无权访问");
        Map<String,Object> inventory = found.get(0);
        List<Object> args = new ArrayList<>(Arrays.asList(company,inventory.get("skuSn"),inventory.get("storeCode")));
        String where = " WHERE company_code=? AND sku_sn=? AND store_code=?";
        if ("LOCK".equals(operation)) where += " AND operation_type IN ('LOCK','UNLOCK')";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory_change_history"+where, Long.class,args.toArray());
        int limit = size(pageSize); args.add(limit); args.add(offset(page,limit));
        return page(jdbc.query("SELECT * FROM wms_inventory_change_history"+where+" ORDER BY log_id DESC LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }

    public static long number(Map<String,Object> row, String key) { return row == null || row.get(key) == null ? 0 : ((Number)row.get(key)).longValue(); }
}
