package com.oms.inventory.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** Tenant-scoped read models. Quantities and their sources share one nonblocking MVCC snapshot. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class ChannelInventoryQueryService {
    @Resource private JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    private static int size(int value){return Math.max(1,Math.min(100,value));}
    private static int offset(int page,int count){return (Math.max(1,Math.min(100000,page))-1)*count;}
    private static BigDecimal decimal(Map<String,Object> row,String key){Object v=row.get(key);return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
    private static final String SOURCE_JOINS=" FROM rule_stock_reservation s LEFT JOIN rule_stock_info h ON h.id=s.rule_id AND h.company_code=s.company_code LEFT JOIN rule_stock_result r ON r.rule_id=s.rule_id AND r.company_code=s.company_code AND r.sku_sn=s.sku_sn";
    private static final String VALID="s.original_quantity>=0 AND s.occupied_quantity>=0 AND s.consumed_quantity>=0 AND s.released_quantity>=0 AND s.original_quantity>=s.occupied_quantity+s.consumed_quantity+s.released_quantity";
    private static final String TRUSTED="r.status='SUCCESS' AND h.allocation_type=2 AND ("+VALID+")";

    public TableDataInfo list(String company,Long channel,String sku,boolean onlyStock,int page,int pageSize) {
        List<Object> args=new ArrayList<>();args.add(company);String where=" WHERE company_code=?";
        if(channel!=null){if(channel<=0)throw new IllegalArgumentException("渠道无效");where+=" AND channel_id=?";args.add(channel);}
        if(sku!=null && !sku.trim().isEmpty()){if(sku.length()>128)throw new IllegalArgumentException("SKU 过长");where+=" AND sku_sn=?";args.add(sku.trim());}
        if(onlyStock)where+=" AND (available_stock<>0 OR allocated_stock<>0 OR reserved_stock<>0 OR frozen_stock<>0)";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM oms_channel_inventory"+where,Long.class,args.toArray());
        int count=size(pageSize);args.add(count);args.add(offset(page,count));
        String index=sku!=null && !sku.trim().isEmpty()?"idx_channel_company_sku_page":channel!=null?"idx_channel_company_channel_page":"idx_channel_company_page";
        return page(jdbc.query("SELECT id,company_code,channel_id,sku_sn,available_stock,allocated_stock,reserved_stock,frozen_stock,modify_time,version FROM oms_channel_inventory FORCE INDEX ("+index+")"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }

    private Map<String,Object> head(String company,long id) {
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM oms_channel_inventory WHERE company_code=? AND id=?",ROW,company,id);
        if(rows.isEmpty())throw new IllegalArgumentException("渠道库存不存在或无权访问");return rows.get(0);
    }
    private static Object[] keys(String company,Map<String,Object> row){return new Object[]{company,row.get("skuSn"),row.get("channelId")};}
    private static String sourceWhere(){return " WHERE s.company_code=? AND s.sku_sn=? AND s.channel_id=?";}

    public Map<String,Object> detail(String company,long id) {
        Map<String,Object> row=head(company,id);
        Map<String,Object> summary=jdbc.queryForObject("SELECT COUNT(*) AS source_count,COALESCE(SUM(CASE WHEN "+TRUSTED+" THEN s.original_quantity-s.consumed_quantity-s.released_quantity ELSE 0 END),0) AS tracked_locked,COALESCE(SUM(CASE WHEN "+TRUSTED+" THEN s.occupied_quantity ELSE 0 END),0) AS occupied_quantity,COALESCE(SUM(CASE WHEN "+TRUSTED+" THEN s.original_quantity-s.consumed_quantity-s.released_quantity-s.occupied_quantity ELSE 0 END),0) AS releasable_quantity,COALESCE(SUM(CASE WHEN "+TRUSTED+" THEN 0 ELSE 1 END),0) AS unknown_sources,COALESCE(SUM(CASE WHEN "+VALID+" THEN 0 ELSE 1 END),0) AS invalid_sources"+SOURCE_JOINS+sourceWhere(),ROW,keys(company,row));
        BigDecimal difference=decimal(row,"allocatedStock").subtract(decimal(summary,"trackedLocked"));
        summary.put("difference",difference);
        boolean negative=Arrays.asList("availableStock","allocatedStock","reservedStock","frozenStock").stream().anyMatch(k->decimal(row,k).signum()<0);
        String status=negative || difference.signum()<0 || number(summary,"invalidSources")>0?"DIFFERENT":difference.signum()>0 || number(summary,"unknownSources")>0?"INCOMPLETE":"CONSISTENT";
        row.put("reservationSummary",summary);row.put("checkStatus",status);
        row.put("checkMessage",status.equals("CONSISTENT")?"渠道锁库量与可追溯来源余额一致；普通配额不参与此核对":status.equals("INCOMPLETE")?"渠道锁库量与来源余额不匹配，请检查关联单据":"渠道数量或锁库来源余额存在异常，请按业务单据核对");
        return row;
    }

    public TableDataInfo sources(String company,long id,int page,int pageSize) {
        Map<String,Object> head=head(company,id);Object[] keys=keys(company,head);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_reservation s"+sourceWhere(),Long.class,keys);
        int count=size(pageSize);List<Object> args=new ArrayList<>(Arrays.asList(keys));args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT s.*,h.rule_code,h.rule_name,r.release_status,("+TRUSTED+") AS tracked,b.store_code,b.batch_code,b.id AS matched_batch_id,w.id AS inventory_id"+SOURCE_JOINS+" LEFT JOIN wms_inventory_batch b ON b.id=s.batch_id AND b.company_code=s.company_code AND b.sku_sn=s.sku_sn LEFT JOIN wms_inventory w ON w.company_code=b.company_code AND w.sku_sn=b.sku_sn AND w.store_code=b.store_code"+sourceWhere()+" ORDER BY s.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        for(Map<String,Object> row:rows){Object value=row.get("tracked");boolean tracked=Boolean.TRUE.equals(value) || (value instanceof Number && ((Number)value).intValue()==1);row.put("tracked",tracked);long remaining=number(row,"originalQuantity")-number(row,"consumedQuantity")-number(row,"releasedQuantity");row.put("remainingQuantity",tracked?remaining:null);row.put("releasableQuantity",tracked?remaining-number(row,"occupiedQuantity"):null);}
        return page(rows,total);
    }

    public TableDataInfo orders(String company,long id,boolean onlyOccupied,int page,int pageSize) {
        Map<String,Object> head=head(company,id);Object[] keys=keys(company,head);
        String from=" FROM rule_stock_order_reservation o JOIN rule_stock_reservation s ON s.id=o.source_id AND s.company_code=o.company_code AND s.rule_id=o.rule_id AND s.sku_sn=o.sku_sn LEFT JOIN rule_stock_info h ON h.id=s.rule_id AND h.company_code=s.company_code LEFT JOIN wms_inventory_batch b ON b.id=s.batch_id AND b.company_code=s.company_code AND b.sku_sn=s.sku_sn"+sourceWhere()+(onlyOccupied?" AND o.occupied_quantity>0":"");
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,keys);int count=size(pageSize);List<Object> args=new ArrayList<>(Arrays.asList(keys));args.add(count);args.add(offset(page,count));
        return page(jdbc.query("SELECT o.id,o.rule_id,o.order_line,o.original_quantity,o.occupied_quantity,o.consumed_quantity,o.cancelled_quantity,s.batch_id,b.batch_code,b.store_code,h.rule_name,h.rule_code"+from+" ORDER BY o.id DESC LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }

    public TableDataInfo events(String company,long id,int page,int pageSize) {
        Map<String,Object> head=head(company,id);Object[] keys=keys(company,head);
        String from=" FROM rule_stock_reservation_event e LEFT JOIN rule_stock_info h ON h.id=e.rule_id AND h.company_code=e.company_code WHERE e.company_code=? AND e.sku_sn=? AND e.channel_id=?";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,keys);int count=size(pageSize);List<Object> args=new ArrayList<>(Arrays.asList(keys));args.add(count);args.add(offset(page,count));
        return page(jdbc.query("SELECT e.*,h.rule_code,h.rule_name"+from+" ORDER BY e.id DESC LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }

    /** Stored execution snapshots, not an invented universal stock journal. JSON membership uses exact IDs. */
    public TableDataInfo executions(String company,long id,int page,int pageSize) {
        Map<String,Object> head=head(company,id);Object[] keys=keys(company,head);
        String contains=" AND JSON_CONTAINS(IF(JSON_VALID(i.detail_json),i.detail_json,'{}'),JSON_OBJECT('channelId',?),'$.channels')=1";
        String ordinary="SELECT i.id,'ONCE' AS execution_type,i.rule_id,NULL AS run_id,h.rule_name,h.rule_code,h.allocation_type,i.status,i.release_status,i.detail_json,i.modify_time AS record_time,i.operator_name FROM rule_stock_result i JOIN rule_stock_info h ON h.id=i.rule_id AND h.company_code=i.company_code WHERE i.company_code=? AND i.sku_sn=?"+contains;
        String daily="SELECT i.id,'DAILY' AS execution_type,n.rule_id,n.id AS run_id,h.rule_name,h.rule_code,1 AS allocation_type,i.status,'NONE' AS release_status,i.detail_json,i.modify_time AS record_time,n.operator_name FROM rule_stock_daily_item i JOIN rule_stock_daily_run n ON n.id=i.run_id JOIN rule_stock_info h ON h.id=n.rule_id AND h.company_code=n.company_code WHERE n.company_code=? AND i.sku_sn=?"+contains;
        String from=" FROM ("+ordinary+" UNION ALL "+daily+") executions";
        List<Object> args=new ArrayList<>(Arrays.asList(keys));args.addAll(Arrays.asList(keys));
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT *"+from+" ORDER BY record_time DESC,execution_type,id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        for(Map<String,Object> row:rows){Object raw=row.remove("detailJson");row.put("channel",snapshot(raw,number(head,"channelId")));}
        return page(rows,total);
    }
    private Map<String,Object> snapshot(Object raw,long channel) {
        try {Map<String,Object> detail=json.readValue(String.valueOf(raw),new TypeReference<Map<String,Object>>(){});for(Map<String,Object> c:(List<Map<String,Object>>)detail.get("channels"))if(number(c,"channelId")==channel)return c;}catch(Exception error){throw new IllegalStateException("分货执行快照格式不正确",error);}
        return Collections.emptyMap();
    }
}
