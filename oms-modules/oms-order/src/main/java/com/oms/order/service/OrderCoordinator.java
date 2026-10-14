package com.oms.order.service;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderCoordinator {
    @Resource private com.oms.common.tenant.TenantRouting tenantRouting;
    @Resource private JdbcTemplate jdbc;
    @Resource private OrderProcessor processor;
    private final AtomicBoolean scanning=new AtomicBoolean();
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final ExecutorService discovery=Executors.newSingleThreadExecutor(r->new Thread(r,"order-discovery"));
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->new Thread(r,"order-shop"),new ThreadPoolExecutor.AbortPolicy());
    public Map<String,Object> poll(){boolean accepted=scanning.compareAndSet(false,true);if(accepted)try{discovery.submit(this::discover);}catch(RuntimeException e){scanning.set(false);throw e;}Map<String,Object> result=new LinkedHashMap<>();result.put("accepted",accepted);result.put("activeShops",active.size());return result;}
    private void discover(){try{
        for(String company:tenantRouting.companies()){
          try(com.oms.common.tenant.TenantRouting.Binding ignored=tenantRouting.open(company)){
            com.oms.common.tenant.TenantRouting.Route captured=tenantRouting.capture(company);JdbcTemplate db=jdbc;
            List<Map<String,Object>> configs=db.queryForList("SELECT c.company_code,c.channel_id FROM oms_order_pull_config c WHERE c.company_code=? AND (c.lease_until IS NULL OR c.lease_until<NOW()) AND (EXISTS(SELECT 1 FROM oms_order v JOIN oms_platform_order vp ON vp.company_code=v.company_code AND vp.id=v.platform_order_id WHERE v.company_code=c.company_code AND v.channel_id=c.channel_id AND v.status='VOIDING' AND vp.next_retry_at<=NOW()) OR (c.enabled=1 AND c.next_run_at<=NOW()) OR EXISTS(SELECT 1 FROM oms_order_pull_task t WHERE t.company_code=c.company_code AND t.channel_id=c.channel_id AND t.status IN ('PENDING','RUNNING') AND t.next_retry_at<=NOW()) OR EXISTS(SELECT 1 FROM oms_platform_order p LEFT JOIN oms_order o ON o.id=p.business_order_id AND o.company_code=p.company_code WHERE p.company_code=c.company_code AND p.channel_id=c.channel_id AND p.change_pending=0 AND p.next_retry_at<=NOW() AND (p.conversion_status IN ('PENDING','PROCESSING') OR (c.auto_convert=1 AND p.conversion_status='WAITING' AND p.platform_status='WAIT_SELLER_SEND_GOODS' AND JSON_CONTAINS(c.allowed_trade_types,JSON_QUOTE(p.trade_type))) OR (p.conversion_status='SUCCESS' AND o.status='WAIT_ALLOCATION' AND o.inventory_status<>'RESERVED' AND o.manual_hold=0 AND p.fulfillment_block_reason='' AND p.platform_status='WAIT_SELLER_SEND_GOODS')))) ORDER BY c.company_code,c.channel_id",company);
            for(Map<String,Object> config:configs){String key=captured.datasource()+"/"+company+"/"+config.get("channel_id");long channel=number(config,"channel_id");if(!active.add(key))continue;
                try{workers.execute(()->{try{captured.run(()->processor.shop(company,channel));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Order worker failed scope={}",key,e);}finally{com.oms.common.tenant.TenantRouting.clearContext();active.remove(key);}});}catch(RejectedExecutionException e){active.remove(key);return;}
            }
          }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Order discovery unavailable company={}",company);}
        }
    }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Order discovery failed",e);}finally{scanning.set(false);}}
    @PreDestroy public void stop(){discovery.shutdownNow();workers.shutdownNow();}
}
