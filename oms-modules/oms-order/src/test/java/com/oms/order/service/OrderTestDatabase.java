package com.oms.order.service;

import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.ConcurrentHashMap;

/** Reuse local fixture connections; per-statement sockets exhaust ephemeral ports on long suites. */
final class OrderTestDatabase {
    private static final ConcurrentHashMap<String,DruidDataSource> POOLS=new ConcurrentHashMap<>();
    static { Runtime.getRuntime().addShutdownHook(new Thread(()->POOLS.values().forEach(DruidDataSource::close))); }
    private OrderTestDatabase() {}
    static JdbcTemplate jdbc(String url) {
        if(url==null||!url.contains("/order_workspace_test?"))throw new IllegalArgumentException("Disposable order test schema required");
        return new JdbcTemplate(POOLS.computeIfAbsent(url,key->{DruidDataSource ds=new DruidDataSource();ds.setUrl(key);ds.setUsername("root");ds.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));ds.setInitialSize(0);ds.setMinIdle(0);ds.setMaxActive(8);ds.setMaxWait(5000);ds.setValidationQuery("SELECT 1");return ds;}));
    }
}
