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
        try{JdbcTemplate db=new JdbcTemplate(pool.db);db.setQueryTimeout(12);String fields=preview?"CAST(tid AS CHAR) tid,seller_nick,jdp_modified":"CAST(tid AS CHAR) tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response";return db.query("SELECT "+fields+" FROM jdp_tb_trade WHERE seller_nick=? AND jdp_modified>=? AND jdp_modified<? AND (jdp_modified>? OR (jdp_modified=? AND tid>?)) ORDER BY jdp_modified,tid LIMIT ?",ROW,seller,task.get("start_time"),task.get("end_time"),task.get("page_time"),task.get("page_time"),task.get("page_tid"),Math.max(1,Math.min(100,count)));}finally{pool.release();}
    }
    @PreDestroy public void close(){pools.values().forEach(Pool::retire);pools.clear();}
}
