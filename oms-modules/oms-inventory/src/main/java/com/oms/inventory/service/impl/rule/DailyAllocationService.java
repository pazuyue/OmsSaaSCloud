package com.oms.inventory.service.impl.rule;

import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.sql.Statement;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** Durable rounds. Lock order is header -> run/item -> shared SKU -> warehouses -> channels.
 * No leases, remote calls or long-lived locks: competing workers serialize one SKU at a time.
 */
@Service
public class DailyAllocationService {
    @Resource private JdbcTemplate db;
    @Resource private AllocationWorkspaceService workspace;
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
    private Map<String,Object> header(String company,long id) {
        Map<String,Object> row=workspace.rule(company,id,true);
        require(number(row,"ruleType")==1 && number(row,"allocationType")==1,"仅日常普通分货可使用定时执行");return row;
    }
    private boolean inWindow(long id){return db.queryForObject("SELECT COUNT(*) FROM rule_stock_info WHERE id=? AND start_time<=NOW() AND end_time>NOW()",Long.class,id)==1;}
    private boolean expired(long id){return db.queryForObject("SELECT COUNT(*) FROM rule_stock_info WHERE id=? AND (end_time IS NULL OR end_time<=NOW())",Long.class,id)==1;}

    public void command(String company,long id,int revision,String action,String actor) {
        require(Arrays.asList("ENABLE","PAUSE","RUN").contains(action),"日常分货动作无效");
        workspace.transaction().execute(tx->{
            Map<String,Object> h=header(company,id);require(number(h,"revision")==revision,"规则已变化，请刷新重试");
            if("ENABLE".equals(action)) {
                require(Arrays.asList(2L,3L,12L).contains(number(h,"status")),"请先提交审核或选择已停用规则");workspace.configured(h);
                require(number(h,"dailyEnabled")==0,"规则已经启用");
                db.update("UPDATE rule_stock_info SET daily_enabled=1,enable=1,status=3,next_run_at=GREATEST(start_time,NOW()),revision=revision+1,first_reviewer_time=COALESCE(first_reviewer_time,NOW()),reviewer_time=NOW(),reviewer_user_name=? WHERE id=?",actor,id);
            } else if("PAUSE".equals(action)) {
                require(number(h,"dailyEnabled")==1,"规则尚未启用");stop(h,"STOPPED",12);
            } else {
                require(number(h,"dailyEnabled")==1 && number(h,"status")==3 && inWindow(id),"规则未启用或不在有效期内");
                if(number(h,"activeRunId")==0)begin(h,actor);
            }
            return null;
        });
    }
    private void begin(Map<String,Object> h,String actor) {
        long id=number(h,"id");Map<String,Object> config=new LinkedHashMap<>(h);config.put("stores",workspace.stores(id));config.put("channels",workspace.channels(id));
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        db.update(c->{java.sql.PreparedStatement ps=c.prepareStatement("INSERT INTO rule_stock_daily_run(company_code,rule_id,config_json,operator_name) VALUES (?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setObject(1,h.get("companyCode"));ps.setLong(2,id);ps.setString(3,workspace.encode(config));ps.setString(4,actor);return ps;},key);
        db.update("UPDATE rule_stock_info SET active_run_id=?,last_update_time=NOW(),next_run_at=NULL WHERE id=?",key.getKey().longValue(),id);
    }
    private void stop(Map<String,Object> h,String reason,int status) {
        long run=number(h,"activeRunId");
        if(run>0)db.update("UPDATE rule_stock_daily_run SET status=?,finish_time=NOW() WHERE id=? AND status IN ('PREPARING','RUNNING')",reason,run);
        db.update("UPDATE rule_stock_info SET daily_enabled=0,enable=2,status=?,active_run_id=NULL,next_run_at=NULL,revision=revision+1 WHERE id=?",status,h.get("id"));
    }
    private void finish(Map<String,Object> h,Map<String,Object> run) {
        String status=number(run,"failed")==0?"SUCCESS":number(run,"success")==0?"FAILED":"PARTIAL";
        db.update("UPDATE rule_stock_daily_run SET status=?,finish_time=NOW() WHERE id=?",status,run.get("id"));
        db.update("UPDATE rule_stock_info SET active_run_id=NULL,next_run_at=LEAST(end_time,TIMESTAMPADD(MINUTE,interval_minutes,NOW())) WHERE id=?",h.get("id"));
    }
    /** Bounded work; safely callable from multiple JVMs or resumed after a process restart. */
    public boolean tick(String company,long id) {
        long deadline=System.nanoTime()+2000000000L;
        // Approved round configuration is immutable; decode once per batch, not query twice per SKU.
        Map<Long,Map<String,Object>> configs=new HashMap<>();
        for(int i=0;i<50 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();i++) {
            final long[] attempted={0,0};
            try {
                Boolean more=workspace.transaction().execute(tx->{
                    Map<String,Object> h=header(company,id);
                    if(number(h,"dailyEnabled")!=1 || number(h,"status")!=3)return false;
                    if(expired(id)){stop(h,"EXPIRED",13);return false;}
                    if(!inWindow(id))return false;
                    long runId=number(h,"activeRunId");
                    if(runId==0) {
                        if(db.queryForObject("SELECT COUNT(*) FROM rule_stock_info WHERE id=? AND next_run_at<=NOW()",Long.class,id)==0)return false;
                        begin(h,"定时分货");runId=db.queryForObject("SELECT active_run_id FROM rule_stock_info WHERE id=?",Long.class,id);
                    }
                    Map<String,Object> run=db.queryForObject("SELECT * FROM rule_stock_daily_run WHERE id=? FOR UPDATE",ROW,runId);
                    if("PREPARING".equals(run.get("status"))) {
                        List<Object> args=new ArrayList<>();String source=workspace.skuSource(h,args);args.add(run.get("cursorSku"));
                        List<String> skus=db.queryForList(source+" AND sku_sn>? ORDER BY sku_sn LIMIT 2000",String.class,args.toArray());
                        if(!skus.isEmpty()) {
                            List<Object> values=new ArrayList<>(skus.size()*2);for(String sku:skus){values.add(runId);values.add(sku);}
                            // One bounded multi-row INSERT; no dependency on JDBC rewriteBatchedStatements.
                            // Keep the source read separate so INSERT SELECT cannot lock warehouse ranges.
                            db.update("INSERT INTO rule_stock_daily_item(run_id,sku_sn) VALUES "+String.join(",",Collections.nCopies(skus.size(),"(?,?)")),values.toArray());
                        }
                        db.update("UPDATE rule_stock_daily_run SET total=total+?,cursor_sku=?,status=? WHERE id=?",skus.size(),skus.isEmpty()?run.get("cursorSku"):skus.get(skus.size()-1),skus.size()<2000?"RUNNING":"PREPARING",runId);
                        return true;
                    }
                    require("RUNNING".equals(run.get("status")),"执行轮次状态异常");
                    List<Map<String,Object>> todo=db.query("SELECT * FROM rule_stock_daily_item WHERE run_id=? AND status='PENDING' ORDER BY sku_sn LIMIT 1 FOR UPDATE",ROW,runId);
                    if(todo.isEmpty()){finish(h,run);return false;}
                    Map<String,Object> item=todo.get(0);attempted[0]=runId;attempted[1]=number(item,"id");
                    Map<String,Object> config=configs.computeIfAbsent(runId,key->workspace.decode((String)run.get("configJson")));
                    Map<String,Object> detail=workspace.applyTargets(company,h,(String)item.get("skuSn"),(List<String>)config.get("stores"),(List<Map<String,Object>>)config.get("channels"));
                    db.update("UPDATE rule_stock_daily_item SET status='SUCCESS',allocated_quantity=?,detail_json=?,attempts=attempts+1 WHERE id=?",number(detail,"total"),workspace.encode(detail),item.get("id"));
                    db.update("UPDATE rule_stock_daily_run SET success=success+1 WHERE id=?",runId);return true;
                });
                if(!Boolean.TRUE.equals(more))return false;
            } catch(RuntimeException error) {
                if(attempted[1]==0)throw error;
                String message=workspace.failure(error);
                workspace.transaction().execute(tx->{
                    Map<String,Object> h=header(company,id);
                    if(number(h,"activeRunId")!=attempted[0])return null;
                    int changed=db.update("UPDATE rule_stock_daily_item SET status='FAILED',error_message=?,attempts=attempts+1 WHERE id=? AND status='PENDING'",message,attempted[1]);
                    if(changed>0)db.update("UPDATE rule_stock_daily_run SET failed=failed+1 WHERE id=?",attempted[0]);return null;
                });
            }
        }
        return true;
    }
    public TableDataInfo runs(String company,long id,int pageNum,int pageSize) {
        workspace.rule(company,id,false);int size=Math.max(1,Math.min(100,pageSize)),offset=(Math.max(1,Math.min(100000,pageNum))-1)*size;
        long total=db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_run WHERE company_code=? AND rule_id=?",Long.class,company,id);
        return page(db.query("SELECT id,status,total,success,failed,total-success-failed AS pending,operator_name,create_time,finish_time FROM rule_stock_daily_run WHERE company_code=? AND rule_id=? ORDER BY id DESC LIMIT ? OFFSET ?",ROW,company,id,size,offset),total);
    }
    public TableDataInfo items(String company,long id,long runId,String status,int pageNum,int pageSize) {
        workspace.rule(company,id,false);require(db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_run WHERE company_code=? AND rule_id=? AND id=?",Long.class,company,id,runId)==1,"执行轮次不存在或无权访问");
        int size=Math.max(1,Math.min(100,pageSize)),offset=(Math.max(1,Math.min(100000,pageNum))-1)*size;String where=" WHERE run_id=?"+("FAILED".equals(status)?" AND status='FAILED'":"");
        long total=db.queryForObject("SELECT COUNT(*) FROM rule_stock_daily_item"+where,Long.class,runId);
        List<Map<String,Object>> rows=db.query("SELECT * FROM rule_stock_daily_item"+where+" ORDER BY sku_sn LIMIT ? OFFSET ?",ROW,runId,size,offset);
        for(Map<String,Object> row:rows){Object json=row.remove("detailJson");if(json!=null)row.put("detail",workspace.decode(json.toString()));}
        return page(rows,total);
    }
    public Map<String,Object> run(String company,long id,long runId) {
        workspace.rule(company,id,false);
        List<Map<String,Object>> rows=db.query("SELECT id,status,total,success,failed,total-success-failed AS pending,operator_name,create_time,finish_time FROM rule_stock_daily_run WHERE company_code=? AND rule_id=? AND id=?",ROW,company,id,runId);
        require(!rows.isEmpty(),"执行轮次不存在或无权访问");return rows.get(0);
    }
}
