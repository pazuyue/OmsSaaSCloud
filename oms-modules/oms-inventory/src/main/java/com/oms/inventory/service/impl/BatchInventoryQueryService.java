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

/** Batch identity is always tenant + database ID, never a possibly reused batch code. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class BatchInventoryQueryService {
    @Resource private JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    private static int size(int size){return Math.max(1,Math.min(100,size));}
    private static int offset(int page,int size){return (Math.max(1,Math.min(100000,page))-1)*size;}
    private Map<String,Object> batch(String company,long id) {
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=?",ROW,company,id);
        if(rows.isEmpty())throw new IllegalArgumentException("批次不存在或无权访问");return rows.get(0);
    }
    private static long remaining(Map<String,Object> s){return number(s,"originalQuantity")-number(s,"consumedQuantity")-number(s,"releasedQuantity");}

    public Map<String,Object> detail(String company,long id) {
        Map<String,Object> row=batch(company,id);
        String trusted="r.status='SUCCESS' AND h.allocation_type=2";
        Map<String,Object> summary=jdbc.queryForObject("SELECT COUNT(*) AS source_count,COALESCE(SUM(CASE WHEN "+trusted+" THEN s.original_quantity-s.consumed_quantity-s.released_quantity ELSE 0 END),0) AS tracked_locked,COALESCE(SUM(CASE WHEN "+trusted+" THEN s.occupied_quantity ELSE 0 END),0) AS occupied_quantity,COALESCE(SUM(CASE WHEN "+trusted+" THEN s.original_quantity-s.consumed_quantity-s.released_quantity-s.occupied_quantity ELSE 0 END),0) AS releasable_quantity,COALESCE(SUM(CASE WHEN "+trusted+" THEN 0 ELSE 1 END),0) AS unknown_sources,COALESCE(SUM(s.occupied_quantity<0 OR s.consumed_quantity<0 OR s.released_quantity<0 OR s.original_quantity<s.occupied_quantity+s.consumed_quantity+s.released_quantity),0) AS invalid_sources FROM rule_stock_reservation s LEFT JOIN rule_stock_result r ON r.rule_id=s.rule_id AND r.sku_sn=s.sku_sn AND r.company_code=s.company_code LEFT JOIN rule_stock_info h ON h.id=s.rule_id AND h.company_code=s.company_code WHERE s.company_code=? AND s.sku_sn=? AND s.batch_id=?",ROW,company,row.get("skuSn"),id);
        summary.put("otherLocked",Math.max(0,number(row,"zpLockNumber")-number(summary,"trackedLocked")));
        summary.put("inconsistent",number(summary,"trackedLocked")>number(row,"zpLockNumber") || number(summary,"invalidSources")>0);
        row.put("reservationSummary",summary);return row;
    }

    public TableDataInfo history(String company,long id,String operation,int page,int pageSize) {
        Map<String,Object> row=batch(company,id);List<Object> args=new ArrayList<>(Arrays.asList(company,id,row.get("skuSn"),row.get("storeCode")));
        String where=" WHERE company_code=? AND batch_id=? AND sku_sn=? AND store_code=?";
        if(operation!=null && !operation.isEmpty()){if(!Arrays.asList("RECEIVE","ADJUST","LOCK","UNLOCK","CONSUME").contains(operation))throw new IllegalArgumentException("库存流水类型无效");where+=" AND operation_type=?";args.add(operation);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory_change_history"+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM wms_inventory_change_history"+where+" ORDER BY log_id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        List<Object> requests=rows.stream().map(r->r.get("requestId")).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String,String> orders=new HashMap<>();
        if(!requests.isEmpty()) {
            List<Object> eventArgs=new ArrayList<>(Arrays.asList(company,row.get("skuSn")));eventArgs.addAll(requests);
            for(Map<String,Object> e:jdbc.query("SELECT journal_request,order_line FROM rule_stock_reservation_event WHERE company_code=? AND sku_sn=? AND journal_request IN ("+String.join(",",Collections.nCopies(requests.size(),"?"))+")",ROW,eventArgs.toArray()))orders.put((String)e.get("journalRequest"),(String)e.get("orderLine"));
        }
        for(Map<String,Object> item:rows)item.put("orderLine",orders.get(item.get("requestId")));
        return page(rows,total);
    }

    public TableDataInfo sources(String company,long id,int page,int pageSize) {
        Map<String,Object> row=batch(company,id);Object[] keys={company,row.get("skuSn"),id};
        String where=" WHERE s.company_code=? AND s.sku_sn=? AND s.batch_id=?";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_reservation s"+where,Long.class,keys);int count=size(pageSize);
        List<Object> args=new ArrayList<>(Arrays.asList(keys));args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT s.*,h.rule_code,h.rule_name,h.allocation_type,r.status,r.release_status,r.detail_json FROM rule_stock_reservation s LEFT JOIN rule_stock_info h ON h.id=s.rule_id AND h.company_code=s.company_code LEFT JOIN rule_stock_result r ON r.rule_id=s.rule_id AND r.company_code=s.company_code AND r.sku_sn=s.sku_sn"+where+" ORDER BY s.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        Map<Long,Map<Long,String>> names=new HashMap<>();
        for(Map<String,Object> source:rows) {
            long rule=number(source,"ruleId");Object raw=source.remove("detailJson");
            if(!names.containsKey(rule))names.put(rule,channelNames(raw));
            source.put("channelName",names.get(rule).getOrDefault(number(source,"channelId"),"渠道 "+source.get("channelId")));
            boolean tracked=number(source,"allocationType")==2 && "SUCCESS".equals(source.get("status"));
            source.put("tracked",tracked);source.put("remainingQuantity",tracked?remaining(source):null);source.put("releasableQuantity",tracked?remaining(source)-number(source,"occupiedQuantity"):null);
        }
        return page(rows,total);
    }

    private Map<Long,String> channelNames(Object raw) {
        Map<Long,String> names=new HashMap<>();
        try {Map<String,Object> detail=json.readValue(String.valueOf(raw),new TypeReference<Map<String,Object>>(){});for(Map<String,Object> c:(List<Map<String,Object>>)detail.get("channels"))names.put(number(c,"channelId"),String.valueOf(c.get("channelName")));}catch(Exception error){throw new IllegalStateException("分货执行快照格式不正确",error);}
        return names;
    }

    public TableDataInfo orders(String company,long id,long sourceId,boolean onlyOccupied,int page,int pageSize) {
        Map<String,Object> batch=batch(company,id);
        List<Map<String,Object>> sources=jdbc.query("SELECT * FROM rule_stock_reservation WHERE company_code=? AND sku_sn=? AND batch_id=? AND id=?",ROW,company,batch.get("skuSn"),id,sourceId);
        if(sources.isEmpty())throw new IllegalArgumentException("锁库来源不属于当前批次或无权访问");
        Map<String,Object> source=sources.get(0);List<Object> args=new ArrayList<>(Arrays.asList(company,sourceId,source.get("ruleId"),batch.get("skuSn")));
        String where=" WHERE company_code=? AND source_id=? AND rule_id=? AND sku_sn=?"+(onlyOccupied?" AND occupied_quantity>0":"");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_order_reservation"+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));
        return page(jdbc.query("SELECT id,order_line,original_quantity,occupied_quantity,consumed_quantity,cancelled_quantity FROM rule_stock_order_reservation"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }
}
