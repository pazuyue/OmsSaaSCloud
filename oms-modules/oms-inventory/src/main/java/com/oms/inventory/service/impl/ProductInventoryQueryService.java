package com.oms.inventory.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** Read models only. Current-page aggregates share one MVCC snapshot; no write locks or remote calls. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class ProductInventoryQueryService {
    @Resource private JdbcTemplate jdbc;
    @Resource private InventoryQueryService warehouses;
    private final ObjectMapper json=new ObjectMapper();
    private static int size(int value){return Math.max(1,Math.min(100,value));}
    private static int offset(int page,int size){return (Math.max(1,Math.min(100000,page))-1)*size;}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String camel(String value){StringBuilder s=new StringBuilder();boolean upper=false;for(char c:value.toCharArray()){if(c=='_'){upper=true;continue;}s.append(upper?Character.toUpperCase(c):c);upper=false;}return s.toString();}
    private static String sums(String alias){return Arrays.stream(QUANTITIES).map(q->"COALESCE(SUM("+alias+"."+q+"),0) AS "+q).collect(Collectors.joining(","));}
    private static String invalid(String alias){return alias+".zp_actual_number<>"+alias+".zp_available_number+"+alias+".zp_lock_number OR "+alias+".cp_actual_number<>"+alias+".cp_available_number+"+alias+".cp_lock_number OR "+Arrays.stream(QUANTITIES).map(q->alias+"."+q+"<0").collect(Collectors.joining(" OR "));}

    public TableDataInfo list(String company,String sku,boolean onlyStock,int page,int pageSize) {
        List<Object> args=new ArrayList<>();args.add(company);String where=" WHERE company_code=?";
        if(sku!=null && !sku.trim().isEmpty()){if(sku.length()>128)throw new IllegalArgumentException("SKU 过长");where+=" AND sku_sn=?";args.add(sku.trim());}
        if(onlyStock)where+=" AND total_stock<>0";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM oms_inventory"+where,Long.class,args.toArray());
        int count=size(pageSize);args.add(count);args.add(offset(page,count));
        // MySQL 5.7 otherwise prefers the SKU key and sorts the entire tenant for a small page.
        String index=sku==null || sku.trim().isEmpty()?" FORCE INDEX (idx_oms_company_page)":"";
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM oms_inventory"+index+where+" ORDER BY id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        enrich(company,rows);return page(rows,total);
    }

    private Map<String,Object> head(String company,long id) {
        List<Map<String,Object>> found=jdbc.query("SELECT * FROM oms_inventory WHERE company_code=? AND id=?",ROW,company,id);
        if(found.isEmpty())throw new IllegalArgumentException("商品库存不存在或无权访问");return found.get(0);
    }
    public Map<String,Object> detail(String company,long id) {
        Map<String,Object> row=head(company,id);enrich(company,Collections.singletonList(row));
        Map<String,Object> source=jdbc.queryForObject("SELECT COALESCE(SUM(original_quantity-consumed_quantity-released_quantity),0) AS tracked_locked,COALESCE(SUM(occupied_quantity),0) AS occupied_quantity FROM rule_stock_reservation WHERE company_code=? AND sku_sn=?",ROW,company,row.get("skuSn"));
        source.put("otherLocked",Math.max(0,number((Map<String,Object>)row.get("warehouseTotals"),"zpLockNumber")-number(source,"trackedLocked")));
        row.put("reservationSummary",source);return row;
    }

    private void enrich(String company,List<Map<String,Object>> rows) {
        if(rows.isEmpty())return;
        List<Object> args=new ArrayList<>();args.add(company);rows.forEach(r->args.add(r.get("skuSn")));
        String filter="company_code=? AND sku_sn IN ("+marks(rows.size())+")";
        // Aggregate batches by warehouse first, so joining them never multiplies warehouse quantities.
        String perStore="SELECT company_code,sku_sn,store_code,COUNT(*) AS batch_count,"+sums("b")+",SUM("+invalid("b")+") AS invalid_batches FROM wms_inventory_batch b WHERE "+filter+" GROUP BY company_code,sku_sn,store_code";
        String unequal=Arrays.stream(QUANTITIES).map(q->"w."+q+"<>COALESCE(b."+q+",0)").collect(Collectors.joining(" OR "));
        String nonzero=Arrays.stream(QUANTITIES).map(q->"w."+q+"<>0").collect(Collectors.joining(" OR "));
        List<Object> combined=new ArrayList<>(args);combined.addAll(args);
        Map<String,Map<String,Object>> bySku=new HashMap<>();
        for(Map<String,Object> w:jdbc.query("SELECT w.sku_sn,COUNT(*) AS warehouse_count,"+sums("w")+",SUM(b.batch_count IS NULL AND ("+nonzero+")) AS missing_batches,SUM("+invalid("w")+" OR "+unequal+" OR COALESCE(b.invalid_batches,0)>0) AS abnormal_warehouses FROM wms_inventory w LEFT JOIN ("+perStore+") b ON b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code WHERE "+filter.replace("company_code","w.company_code").replace("sku_sn","w.sku_sn")+" GROUP BY w.sku_sn",ROW,combined.toArray()))bySku.put((String)w.get("skuSn"),w);
        Map<String,Map<String,Object>> batchBySku=new HashMap<>();
        for(Map<String,Object> b:jdbc.query("SELECT b.sku_sn,COUNT(*) AS batch_count,"+sums("b")+",SUM(w.id IS NULL) AS orphan_batches FROM wms_inventory_batch b LEFT JOIN wms_inventory w ON w.company_code=b.company_code AND w.sku_sn=b.sku_sn AND w.store_code=b.store_code WHERE "+filter.replace("company_code","b.company_code").replace("sku_sn","b.sku_sn")+" GROUP BY b.sku_sn",ROW,args.toArray()))batchBySku.put((String)b.get("skuSn"),b);
        for(Map<String,Object> row:rows) {
            Map<String,Object> w=bySku.getOrDefault(row.get("skuSn"),new LinkedHashMap<>()),b=batchBySku.getOrDefault(row.get("skuSn"),new LinkedHashMap<>());
            for(String field:QUANTITIES){String key=camel(field);w.putIfAbsent(key,0L);b.putIfAbsent(key,0L);}
            long total=number(w,"zpActualNumber")+number(w,"cpActualNumber"),available=number(w,"zpAvailableNumber")+number(w,"cpAvailableNumber"),locked=number(w,"zpLockNumber")+number(w,"cpLockNumber");
            w.put("totalStock",total);w.put("availableStock",available);w.put("allocatedStock",locked);
            b.put("totalStock",number(b,"zpActualNumber")+number(b,"cpActualNumber"));b.put("availableStock",number(b,"zpAvailableNumber")+number(b,"cpAvailableNumber"));b.put("allocatedStock",number(b,"zpLockNumber")+number(b,"cpLockNumber"));
            boolean missing=number(w,"missingBatches")>0 || number(b,"orphanBatches")>0 || (number(w,"warehouseCount")==0 && (number(row,"totalStock")!=0 || number(row,"availableStock")!=0 || number(row,"allocatedStock")!=0));
            boolean balanced=number(row,"totalStock")==total && number(row,"availableStock")==available && number(row,"allocatedStock")==locked && number(row,"totalStock")==number(row,"availableStock")+number(row,"allocatedStock") && number(row,"totalStock")>=0 && number(row,"availableStock")>=0 && number(row,"allocatedStock")>=0 && number(w,"abnormalWarehouses")==0;
            for(String field:QUANTITIES)balanced &= number(w,camel(field))==number(b,camel(field));
            row.put("warehouseTotals",w);row.put("batchTotals",b);row.put("checkStatus",missing?"INCOMPLETE":balanced?"CONSISTENT":"DIFFERENT");
            row.put("checkMessage",missing?"存在非零仓库库存缺少批次、批次缺少仓库或商品缺少仓库明细，请按来源核对":balanced?"商品、仓库与批次数量一致":"商品汇总、仓库或批次余额存在差异，请查看明细");
        }
    }

    public TableDataInfo warehouses(String company,long id,int page,int pageSize) {
        Map<String,Object> row=head(company,id);return warehouses.list(company,null,(String)row.get("skuSn"),false,false,page,pageSize);
    }

    public TableDataInfo reservations(String company,long id,int page,int pageSize) {
        String sku=(String)head(company,id).get("skuSn");
        String from=" FROM rule_stock_result r JOIN rule_stock_info h ON h.id=r.rule_id AND h.company_code=r.company_code WHERE r.company_code=? AND r.sku_sn=? AND r.status='SUCCESS' AND h.allocation_type=2";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,company,sku);int count=size(pageSize);
        List<Map<String,Object>> rows=jdbc.query("SELECT r.*,h.rule_code,h.rule_name"+from+" ORDER BY r.id DESC LIMIT ? OFFSET ?",ROW,company,sku,count,offset(page,count));
        Map<Long,List<Map<String,Object>>> sourceByRule=new HashMap<>();
        if(!rows.isEmpty()) {
            List<Object> args=new ArrayList<>(Arrays.asList(company,sku));rows.forEach(r->args.add(r.get("ruleId")));
            for(Map<String,Object> source:jdbc.query("SELECT rule_id,channel_id,COUNT(*) AS batch_count,SUM(original_quantity) AS original_quantity,SUM(occupied_quantity) AS occupied_quantity,SUM(consumed_quantity) AS consumed_quantity,SUM(released_quantity) AS released_quantity,SUM(original_quantity-occupied_quantity-consumed_quantity-released_quantity) AS releasable_quantity FROM rule_stock_reservation WHERE company_code=? AND sku_sn=? AND rule_id IN ("+marks(rows.size())+") GROUP BY rule_id,channel_id ORDER BY channel_id",ROW,args.toArray()))sourceByRule.computeIfAbsent(number(source,"ruleId"),key->new ArrayList<>()).add(source);
        }
        for(Map<String,Object> row:rows) {
            Object raw=row.remove("detailJson");Map<Long,String> names=new HashMap<>();
            try {Map<String,Object> detail=json.readValue(String.valueOf(raw),new TypeReference<Map<String,Object>>(){});for(Map<String,Object> c:(List<Map<String,Object>>)detail.get("channels"))names.put(number(c,"channelId"),String.valueOf(c.get("channelName")));}catch(Exception ignored){/* Historical metadata can be incomplete; stock balances stay unknown. */}
            List<Map<String,Object>> sources=sourceByRule.getOrDefault(number(row,"ruleId"),Collections.emptyList());
            for(Map<String,Object> source:sources)source.put("channelName",names.getOrDefault(number(source,"channelId"),"渠道 "+source.get("channelId")));
            row.put("channels",sources);row.put("releasableQuantity",number(row,"sourceTracked")==1?number(row,"allocatedQuantity")-number(row,"occupiedQuantity")-number(row,"consumedQuantity")-number(row,"releasedQuantity"):null);
        }
        return page(rows,total);
    }

    public TableDataInfo history(String company,long id,String operation,int page,int pageSize) {
        String sku=(String)head(company,id).get("skuSn");List<Object> args=new ArrayList<>(Arrays.asList(company,sku));String where=" WHERE company_code=? AND sku_sn=?";
        if(operation!=null && !operation.isEmpty()) {if(!Arrays.asList("RECEIVE","ADJUST","LOCK","UNLOCK","CONSUME").contains(operation))throw new IllegalArgumentException("库存流水类型无效");where+=" AND operation_type=?";args.add(operation);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory_change_history"+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM wms_inventory_change_history"+where+" ORDER BY log_id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        List<Object> requests=rows.stream().map(r->r.get("requestId")).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String,String> lines=new HashMap<>();
        if(!requests.isEmpty()) {List<Object> eventArgs=new ArrayList<>(Arrays.asList(company,sku));eventArgs.addAll(requests);for(Map<String,Object> e:jdbc.query("SELECT journal_request,order_line FROM rule_stock_reservation_event WHERE company_code=? AND sku_sn=? AND journal_request IN ("+marks(requests.size())+")",ROW,eventArgs.toArray()))lines.put((String)e.get("journalRequest"),(String)e.get("orderLine"));}
        for(Map<String,Object> row:rows)row.put("orderLine",lines.get(row.get("requestId")));
        return page(rows,total);
    }
}
