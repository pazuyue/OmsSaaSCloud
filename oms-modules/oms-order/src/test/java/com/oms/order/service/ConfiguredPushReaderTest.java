package com.oms.order.service;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConfiguredPushReaderTest {
    @Test void previewIsBoundedAndDoesNotReadOrderPayload(){
        String url=System.getenv("ORDER_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/order_workspace_test?"));
        JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));
        db.execute("CREATE TABLE IF NOT EXISTS jdp_tb_trade(tid BIGINT PRIMARY KEY,status VARCHAR(64),type VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY idx_seller_time(seller_nick,jdp_modified))");
        db.update("DELETE FROM jdp_tb_trade WHERE seller_nick='__PREVIEW_TEST__'");
        TmallPushReader.Source s=new TmallPushReader.Source();s.setJdbcUrl(url);s.setUsername("root");s.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));s.setRevision(1);
        PushSourceService sources=mock(PushSourceService.class);when(sources.resolve("VERIFY","source")).thenReturn(s);
        ConfiguredPushReader reader=new ConfiguredPushReader();ReflectionTestUtils.setField(reader,"sources",sources);
        try{
            for(int i=0;i<7;i++)db.update("INSERT INTO jdp_tb_trade(tid,seller_nick,jdp_modified,jdp_response) VALUES (?,'__PREVIEW_TEST__','2026-10-01 00:00:00','private payload')",888880+i);
            Map<String,Object> task=new HashMap<>();task.put("start_time","2026-10-01 00:00:00");task.put("end_time","2026-10-02 00:00:00");task.put("page_time","2026-10-01 00:00:00");task.put("page_tid",0);
            List<Map<String,Object>> rows=reader.preview("VERIFY","source","__PREVIEW_TEST__",task);
            assertEquals(5,rows.size());assertEquals(new HashSet<>(Arrays.asList("tid","seller_nick","jdp_modified")),rows.get(0).keySet());
            assertTrue(reader.preview("VERIFY","source","__OTHER_SELLER__",task).isEmpty());
            task.put("start_time","2026-10-02 00:00:00");assertTrue(reader.preview("VERIFY","source","__PREVIEW_TEST__",task).isEmpty());
            assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM jdp_tb_trade WHERE seller_nick='__PREVIEW_TEST__'",Integer.class));
        }finally{reader.close();db.update("DELETE FROM jdp_tb_trade WHERE seller_nick='__PREVIEW_TEST__'");}
    }
    @Test void configChangesAndCompanyIsolation(){String url=System.getenv("ORDER_TEST_URL");Assumptions.assumeTrue(url!=null&&url.contains("/order_workspace_test?"));JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD")));db.execute("CREATE TABLE IF NOT EXISTS jdp_tb_trade(tid BIGINT PRIMARY KEY,status VARCHAR(64),type VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY idx_seller_time(seller_nick,jdp_modified))");TmallPushReader.Source s=new TmallPushReader.Source();s.setJdbcUrl(url);s.setUsername("root");s.setPassword(System.getenv("INVENTORY_TEST_PASSWORD"));s.setRevision(1);PushSourceService sources=mock(PushSourceService.class);when(sources.resolve("VERIFY","source")).thenReturn(s);when(sources.resolve("OTHER","source")).thenThrow(new IllegalArgumentException("No source in company"));ConfiguredPushReader reader=new ConfiguredPushReader();ReflectionTestUtils.setField(reader,"sources",sources);Map<String,Object> task=new TmallPushReaderTest().task();try{assertTrue(reader.read("VERIFY","source","__NO_SHOP__",task,1).isEmpty());Object first=((Map<?,?>)ReflectionTestUtils.getField(reader,"pools")).values().iterator().next();s.setRevision(2);assertTrue(reader.read("VERIFY","source","__NO_SHOP__",task,1).isEmpty());assertNotSame(first,((Map<?,?>)ReflectionTestUtils.getField(reader,"pools")).values().iterator().next());assertThrows(IllegalArgumentException.class,()->reader.read("OTHER","source","__NO_SHOP__",task,1));when(sources.resolve("VERIFY","source")).thenThrow(new IllegalArgumentException("Disabled"));assertThrows(IllegalArgumentException.class,()->reader.read("VERIFY","source","__NO_SHOP__",task,1));}finally{reader.close();}}
}
