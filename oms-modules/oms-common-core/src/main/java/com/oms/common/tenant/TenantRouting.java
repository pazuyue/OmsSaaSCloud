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
    private Map<String,String> versions=Collections.emptyMap();
    private Map<String,Boolean> managed=Collections.emptyMap();
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<TenantCompanyAccess> companyAccess;
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
    public Map<String,String> getVersions(){return versions;}
    public void setVersions(Map<String,String> values){Map<String,String> copy=new TreeMap<>();if(values!=null)values.forEach((key,value)->{require(value!=null&&value.matches("[A-Za-z0-9_-]{1,64}"),"公司路由版本无效");copy.put(company(key),value);});versions=Collections.unmodifiableMap(copy);}
    public Map<String,Boolean> getManaged(){return managed;}
    public void setManaged(Map<String,Boolean> values){Map<String,Boolean> copy=new TreeMap<>();if(values!=null)values.forEach((key,value)->copy.put(company(key),Boolean.TRUE.equals(value)));managed=Collections.unmodifiableMap(copy);}
    public boolean isManaged(String company){return Boolean.TRUE.equals(managed.get(company(company)));}
    private Set<String> permitted(boolean continuation){Set<String> result=new TreeSet<>(routes.keySet()),controlled=new TreeSet<>();for(String company:result)if(isManaged(company))controlled.add(company);if(!controlled.isEmpty()){Map<String,String> states;try{states=companyAccess.getObject().states(controlled);}catch(RuntimeException e){states=Collections.emptyMap();org.slf4j.LoggerFactory.getLogger(getClass()).warn("Company switches unavailable; managed scan scopes skipped");}final Map<String,String> snapshot=states;result.removeIf(company->controlled.contains(company)&&!("ENABLED".equals(snapshot.get(company))||(continuation&&"DISABLED".equals(snapshot.get(company)))));}return Collections.unmodifiableSet(result);}
    public Set<String> companies(){return permitted(false);}
    public Set<String> continuingCompanies(){return permitted(true);}
    public static String company(String value){require(value!=null&&value.trim().matches("[A-Za-z0-9_-]{1,64}"),"公司编码无效");return value.trim().toUpperCase(Locale.ROOT);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private DynamicRoutingDataSource routing(){require(dataSource instanceof DynamicRoutingDataSource,"动态数据源未配置");return (DynamicRoutingDataSource)dataSource;}
    private DataSource pool(String name){
        DynamicRoutingDataSource routing=routing();DataSource pool=routing.getDataSources().get(name);
        require(pool!=null&&!routing.getGroupDataSources().containsKey(name)&&routing.getDataSource(name)==pool,"公司数据源不存在或存在组名歧义");return pool;
    }
    @PostConstruct public void validate(){require(!routes.isEmpty(),"必须配置 oms.tenant.routes 公司数据源映射");routes.values().forEach(this::pool);}
    public Route capture(String company){return capture(company,false);}
    public Route captureForContinuation(String company){return capture(company,true);}
    private Route capture(String company,boolean continuation){String key=company(company),name=routes.get(key);require(name!=null,"公司未配置明确数据源映射");return new Route(this,key,name,pool(name),continuation);}
    public <T> T call(String company,Supplier<T> work){return capture(company).call(work);}
    public void run(String company,Runnable work){call(company,()->{work.run();return null;});}
    public Binding open(String company){return capture(company).open();}
    public Binding openForContinuation(String company){return captureForContinuation(company).open();}
    public <T> T callForContinuation(String company,Supplier<T> work){return captureForContinuation(company).call(work);}
    /** Read-only onboarding probe of preloaded pools; accepts no JDBC URLs or credentials. */
    public Map<String,Object> inspect(String company,String alias,List<String> requiredTables){
        String key=company(company);Map<String,Object> result=new LinkedHashMap<>();
        result.put("company_code",key);result.put("loaded_alias",routes.get(key));result.put("loaded_version",versions.getOrDefault(key,""));result.put("managed",isManaged(key));
        List<String> aliases=new ArrayList<>();for(String name:new TreeSet<>(routing().getDataSources().keySet()))if(!routing().getGroupDataSources().containsKey(name))aliases.add(name);result.put("available_aliases",aliases);
        if(alias==null||alias.isEmpty())return result;
        DataSource selected=pool(alias);result.put("selected_alias",alias);
        try(java.sql.Connection connection=selected.getConnection()){
            String catalog=connection.getCatalog();result.put("database_name",catalog);Set<String> found=new HashSet<>();
            String marks=String.join(",",Collections.nCopies(requiredTables.size(),"?"));
            try(java.sql.PreparedStatement statement=connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ("+marks+")")){statement.setQueryTimeout(3);for(int i=0;i<requiredTables.size();i++)statement.setString(i+1,requiredTables.get(i));try(java.sql.ResultSet tables=statement.executeQuery()){while(tables.next())found.add(tables.getString(1).toLowerCase(Locale.ROOT));}}
            List<String> missing=new ArrayList<>();for(String table:requiredTables)if(!found.contains(table.toLowerCase(Locale.ROOT)))missing.add(table);
            result.put("schema_ready",missing.isEmpty());result.put("missing_tables",missing);
        }catch(java.sql.SQLException e){result.put("schema_ready",false);result.put("message","连接不可用或缺少结构读取权限");}
        return result;
    }
    /** Only call at request/worker boundaries; nested scopes use Binding.close(). */
    public static void clearContext(){CONTEXT.remove();DynamicDataSourceContextHolder.clear();}

    public static final class Route {
        private final TenantRouting owner;
        private final String company,poolName;
        private final DataSource pool;
        private final boolean continuation;
        private Route(TenantRouting owner,String company,String poolName,DataSource pool,boolean continuation){this.owner=owner;this.company=company;this.poolName=poolName;this.pool=pool;this.continuation=continuation;}
        public String company(){return company;}
        public String datasource(){return poolName;}
        public Binding open(){
            require(poolName.equals(owner.routes.get(company))&&owner.pool(poolName)==pool,"公司数据源已变更，请重新执行");
            Deque<Route> stack=CONTEXT.get();
            Route outer=stack.peek();
            if(owner.isManaged(company)&&(outer==null||!outer.company.equals(company)||(outer.continuation&&!continuation))){TenantCompanyAccess access=owner.companyAccess.getObject();if(continuation)access.requireExisting(company);else access.requireEnabled(company);}
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
