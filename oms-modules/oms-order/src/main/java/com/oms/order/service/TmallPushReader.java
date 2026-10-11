package com.oms.order.service;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.Data;

/** Connection value and pool factory. Runtime sources are exclusively database-backed. */
public class TmallPushReader {
    @Data public static class Source {private String label,jdbcUrl,username,password;private long revision;}
    public static DruidDataSource createPool(Source s){DruidDataSource ds=new DruidDataSource();ds.setUrl(s.jdbcUrl);ds.setUsername(s.username);ds.setPassword(s.password);ds.setDefaultReadOnly(true);ds.setMaxActive(2);ds.setInitialSize(0);ds.setMinIdle(0);ds.setMaxWait(5000);ds.setConnectTimeout(5000);ds.setSocketTimeout(15000);return ds;}
}
