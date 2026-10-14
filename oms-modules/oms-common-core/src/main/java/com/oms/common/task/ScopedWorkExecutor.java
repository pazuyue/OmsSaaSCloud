package com.oms.common.task;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Bounded event execution, never a timer. Tenant context is explicit and always cleared. */
public class ScopedWorkExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    public ScopedWorkExecutor(String name){AtomicInteger ids=new AtomicInteger();executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread thread=new Thread(r,name+"-"+ids.incrementAndGet());thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());}
    public boolean submit(String route,Runnable work){try{executor.execute(()->{DynamicDataSourceContextHolder.clear();try{if(route!=null&&!route.isEmpty())DynamicDataSourceContextHolder.push(route);work.run();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Immediate work not complete; durable task retained for compensation");}finally{DynamicDataSourceContextHolder.clear();}});return true;}catch(RejectedExecutionException e){return false;}}
    @Override public void close(){executor.shutdown();}
}
