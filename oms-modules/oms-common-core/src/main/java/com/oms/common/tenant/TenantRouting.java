package com.oms.common.tenant;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/** One company registry per service. No implicit primary-pool or group fallback. */
@Component
@ConfigurationProperties(prefix="oms.tenant")
public class TenantRouting {
    @Resource private DataSource dataSource;
    private Map<String,String> routes=Collections.emptyMap();
    private static final ThreadLocal<Deque<Route>> CONTEXT=ThreadLocal.withInitial(ArrayDeque::new);

    public void setRoutes(Map<String,String> configured){
        Map<String,String> values=new TreeMap<>();
        if(configured!=null)configured.forEach((company,pool)->{
            String key=company(company);
            require(pool!=null&&pool.matches("[A-Za-z0-9_-]{1,64}"),"租户数据源名称无效");
            require(values.put(key,pool)==null,"公司数据源映射重复");
        });
        routes=Collections.unmodifiableMap(values);
    }
    public Map<String,String> getRoutes(){return routes;}
    public Set<String> companies(){return routes.keySet();}
    public static String company(String value){require(value!=null&&value.trim().matches("[A-Za-z0-9_-]{1,64}"),"公司编码无效");return value.trim().toUpperCase(Locale.ROOT);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private DynamicRoutingDataSource routing(){require(dataSource instanceof DynamicRoutingDataSource,"动态数据源未配置");return (DynamicRoutingDataSource)dataSource;}
    private DataSource pool(String name){
        DynamicRoutingDataSource routing=routing();DataSource pool=routing.getDataSources().get(name);
        require(pool!=null&&!routing.getGroupDataSources().containsKey(name)&&routing.getDataSource(name)==pool,"公司数据源不存在或存在组名歧义");return pool;
    }
    @PostConstruct public void validate(){require(!routes.isEmpty(),"必须配置 oms.tenant.routes 公司数据源映射");routes.values().forEach(this::pool);}
    public Route capture(String company){String key=company(company),name=routes.get(key);require(name!=null,"公司未配置明确数据源映射");return new Route(this,key,name,pool(name));}
    public <T> T call(String company,Supplier<T> work){return capture(company).call(work);}
    public void run(String company,Runnable work){call(company,()->{work.run();return null;});}
    public Binding open(String company){return capture(company).open();}
    /** Only call at request/worker boundaries; nested scopes use Binding.close(). */
    public static void clearContext(){CONTEXT.remove();DynamicDataSourceContextHolder.clear();}

    public static final class Route {
        private final TenantRouting owner;
        private final String company,poolName;
        private final DataSource pool;
        private Route(TenantRouting owner,String company,String poolName,DataSource pool){this.owner=owner;this.company=company;this.poolName=poolName;this.pool=pool;}
        public String company(){return company;}
        public String datasource(){return poolName;}
        public Binding open(){
            require(poolName.equals(owner.routes.get(company))&&owner.pool(poolName)==pool,"公司数据源已变更，请重新执行");
            Deque<Route> stack=CONTEXT.get();
            if(TransactionSynchronizationManager.isActualTransactionActive()){
                Route current=stack.peek();
                require(current!=null&&current.company.equals(company)&&current.pool==pool&&poolName.equals(DynamicDataSourceContextHolder.peek()),"事务中禁止切换公司或数据源");
            }
            DynamicDataSourceContextHolder.push(poolName);stack.push(this);return new Binding(this,Thread.currentThread());
        }
        public <T> T call(Supplier<T> work){try(Binding ignored=open()){return work.get();}}
        public void run(Runnable work){call(()->{work.run();return null;});}
    }
    public static final class Binding implements AutoCloseable {
        private final Route route;private final Thread thread;private boolean closed;
        private Binding(Route route,Thread thread){this.route=route;this.thread=thread;}
        @Override public void close(){if(closed)return;require(thread==Thread.currentThread()&&CONTEXT.get().peek()==route,"租户上下文必须在原线程按顺序关闭");CONTEXT.get().pop();DynamicDataSourceContextHolder.poll();if(CONTEXT.get().isEmpty())CONTEXT.remove();closed=true;}
    }
}
