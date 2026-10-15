package com.ruoyi.system.company;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import javax.annotation.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.system.company.CompanyServices.*;
/** Explicit checks only: bounded concurrency, bounded queue and timeouts; no polling timer. */
@Component
public class NacosCompanyRuntimeProbe implements CompanyRuntimeProbe {
    @Resource private NacosServiceManager nacos;
    @Resource private NacosDiscoveryProperties discovery;
    private final Semaphore checks=new Semaphore(2);
    private final ExecutorService workers=new ThreadPoolExecutor(3,3,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"company-probe");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final RestTemplate http;
    public NacosCompanyRuntimeProbe(){SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(1500);factory.setReadTimeout(4000);http=new RestTemplate(factory);}
    @SuppressWarnings("unchecked") private Map<String,Object> probe(String app,Instance instance,String company,String alias){
        String ref=instance.getIp()+":"+instance.getPort();if(!instance.isHealthy()||!instance.isEnabled())return map("instance",ref,"ready",false,"message","实例不健康或未启用");
        try{
            HttpHeaders headers=new HttpHeaders();headers.set(SecurityConstants.FROM_SOURCE,SecurityConstants.INNER);headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String,String> body=new HashMap<>();body.put("company_code",company);if(alias!=null)body.put("datasource_alias",alias);
            URI uri=new URI("http",null,instance.getIp(),instance.getPort(),"/tenantRouting/internal/probe",null,null);
            R<?> reply=http.postForObject(uri,new HttpEntity<>(body,headers),R.class);
            require(reply!=null&&R.isSuccess(reply)&&reply.getData() instanceof Map,"实例接入检查未确认");
            Map<String,Object> result=new LinkedHashMap<>((Map<String,Object>)reply.getData());require(app.equals(result.get("service")),"实例服务身份不一致");result.put("instance",ref);return result;
        }catch(Exception e){return map("instance",ref,"ready",false,"message","实例不可达或检查接口未就绪");}
    }
    @SuppressWarnings("unchecked") public Map<String,Object> inspect(String company,Map<String,String> routes,String version,boolean requireLoaded){
        require(checks.tryAcquire(),"检查正在进行，请稍后重试");long started=System.currentTimeMillis();List<Future<?>> submitted=new ArrayList<>();
        try{
            Map<String,List<Future<Map<String,Object>>>> futures=new LinkedHashMap<>();int total=0;
            Map<String,Future<List<Instance>>> listings=new LinkedHashMap<>();
            for(Map.Entry<String,String> service:APPS.entrySet()){Future<List<Instance>> listing=workers.submit(()->nacos.getNamingService(discovery.getNacosProperties()).getAllInstances(service.getValue(),discovery.getGroup()));listings.put(service.getKey(),listing);submitted.add(listing);}
            for(Map.Entry<String,String> service:APPS.entrySet()){
                List<Future<Map<String,Object>>> requests=new ArrayList<>();futures.put(service.getKey(),requests);
                List<Instance> instances;
                try{instances=listings.get(service.getKey()).get(Math.max(1,12000-(System.currentTimeMillis()-started)),TimeUnit.MILLISECONDS);}catch(Exception e){instances=Collections.emptyList();}
                total+=instances.size();require(total<=50,"运行实例超过单次检查上限，请联系平台调整");
                for(Instance instance:instances)try{Future<Map<String,Object>> request=workers.submit(()->probe(service.getValue(),instance,company,routes.get(service.getKey())));requests.add(request);submitted.add(request);}catch(RejectedExecutionException e){throw new IllegalArgumentException("检查繁忙，请稍后重试");}
            }
            boolean all=true;List<Map<String,Object>> services=new ArrayList<>();
            for(String key:APPS.keySet()){
                List<Map<String,Object>> instances=new ArrayList<>();boolean ready=!futures.get(key).isEmpty();Set<String> available=null;
                for(Future<Map<String,Object>> future:futures.get(key)){
                    Map<String,Object> item;try{item=future.get(Math.max(1,12000-(System.currentTimeMillis()-started)),TimeUnit.MILLISECONDS);}catch(Exception e){item=map("ready",false,"message","检查超时，请重试");}
                    Set<String> aliases=new TreeSet<>();Object a=item.get("available_aliases");if(a instanceof List)for(Object alias:(List<?>)a)aliases.add(String.valueOf(alias));if(available==null)available=aliases;else available.retainAll(aliases);
                    boolean good=routes.get(key)==null?item.containsKey("available_aliases"):Boolean.TRUE.equals(item.get("schema_ready"));
                    if(requireLoaded)good=good&&Objects.equals(routes.get(key),item.get("loaded_alias"))&&Objects.equals(version,item.getOrDefault("loaded_version",""))&&(version.isEmpty()||Boolean.TRUE.equals(item.get("managed")));
                    item.put("ready",good);if(!item.containsKey("message")||text(item,"message").isEmpty())item.put("message",good?"检查通过":"连接、必要结构或发布版本未就绪");ready&=good;instances.add(item);
                }
                all&=ready;services.add(map("key",key,"name",NAMES.get(key),"datasource_alias",routes.get(key),"ready",ready,"available_aliases",available==null?Collections.emptySet():available,"instances",instances,"message",instances.isEmpty()?"没有已注册运行实例":ready?"全部实例检查通过":"存在未就绪实例，请查看实例检测"));
            }
            return map("ready",all,"services",services,"version",version,"checked_at",System.currentTimeMillis(),"duration_ms",System.currentTimeMillis()-started);
        }finally{for(Future<?> future:submitted)if(!future.isDone())future.cancel(true);checks.release();}
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}
