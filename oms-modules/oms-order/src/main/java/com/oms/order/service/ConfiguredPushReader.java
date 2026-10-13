package com.oms.order.service;
import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.oms.order.service.OrderValues.*;

/** Database-backed sources. Retired pools close after their in-flight reads finish. */
@Component
public class ConfiguredPushReader {
    @Resource private PushSourceService sources;
    private static class Pool {
        final long revision;final DruidDataSource db;int readers;boolean retired;
        Pool(TmallPushReader.Source value){revision=value.getRevision();db=TmallPushReader.createPool(value);}
        synchronized void take(){readers++;}
        synchronized void release(){readers--;if(retired&&readers==0)db.close();}
        synchronized void retire(){retired=true;if(readers==0)db.close();}
    }
    private final Map<String,Pool> pools=new ConcurrentHashMap<>();
    public List<Map<String,Object>> choices(String company){return sources.choices(company);}
    public boolean configured(String company,String key){return sources.configured(company,key);}
    protected TmallPushReader.Source source(String company,String key){return sources.resolve(company,key);}
    public List<Map<String,Object>> read(String company,String key,String seller,Map<String,Object> task,int count){
        return query(company,key,seller,task,count,false);
    }
    public List<Map<String,Object>> preview(String company,String key,String seller,Map<String,Object> task){
        return query(company,key,seller,task,5,true);
    }
    private List<Map<String,Object>> query(String company,String key,String seller,Map<String,Object> task,int count,boolean preview){
        TmallPushReader.Source value=source(company,key);String identity=DynamicDataSourceContextHolder.peek()+"/"+company+"/"+key;
        Pool pool=pools.compute(identity,(k,old)->{Pool next=old;if(old==null||old.revision<value.getRevision()){next=new Pool(value);if(old!=null)old.retire();}next.take();return next;});
        try{JdbcTemplate db=new JdbcTemplate(pool.db);db.setQueryTimeout(12);String fields=preview?"CAST(tid AS CHAR) tid,seller_nick,jdp_modified":"CAST(tid AS CHAR) tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response";if(!preview&&"TID".equals(text(task,"pull_mode"))){
                Object raw=task.get("selected_tids");require(raw instanceof List,"单号任务范围缺失");List<String> tids=OrderBackfillService.tids(raw);require(tids.size()<=Math.max(1,Math.min(100,count)),"单次单号查询范围过大");List<Object> args=new ArrayList<>();args.add(seller);args.addAll(tids);
                return db.query("SELECT "+fields+" FROM jdp_tb_trade WHERE seller_nick=? AND tid IN ("+String.join(",",Collections.nCopies(tids.size(),"CAST(? AS SIGNED)"))+") ORDER BY tid",ROW,args.toArray());
            }
            String lower=text(task,"start_time").compareTo(text(task,"page_time"))>0?text(task,"start_time"):text(task,"page_time");
            return db.query("SELECT "+fields+" FROM jdp_tb_trade WHERE seller_nick=? AND jdp_modified>=? AND jdp_modified<? AND (jdp_modified>? OR (jdp_modified=? AND tid>?)) ORDER BY jdp_modified,tid LIMIT ?",ROW,seller,lower,preview?task.get("end_time"):OrderBackfillService.windowEnd(task),task.get("page_time"),task.get("page_time"),task.get("page_tid"),Math.max(1,Math.min(100,count)));}finally{pool.release();}
    }
    @PreDestroy public void close(){pools.values().forEach(Pool::retire);pools.clear();}
}
