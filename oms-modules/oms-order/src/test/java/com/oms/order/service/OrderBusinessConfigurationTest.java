package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local overview never creates configuration or probes external platforms; visibility follows section permissions. */
class OrderBusinessConfigurationTest {
    RefundServiceTest f=new RefundServiceTest(); OrderBusinessConfiguration settings;
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){
        f.reset();settings=new OrderBusinessConfiguration();settings.repository=f.repo;
        settings.catalog=mock(OrderCatalog.class);settings.sources=mock(ConfiguredPushReader.class);
        for(String table:Arrays.asList("oms_order_review_config","oms_order_allocation_config","oms_order_carrier_config","oms_order_platform_shipping_config","oms_refund_parcel_config","oms_exchange_config"))f.db.update("DELETE FROM "+table+" WHERE company_code='VERIFY'");
        when(settings.catalog.channels("VERIFY")).thenReturn(Arrays.asList(m("channel_id",1,"channel_name","验收店铺","channel_type","TM","enabled",1),m("channel_id",2,"channel_name","停用店铺","channel_type","TM","enabled",0),m("channel_id",3,"channel_name","其他平台","channel_type","JD","enabled",1)));
        when(settings.sources.choices("VERIFY")).thenReturn(Collections.emptyList());
    }
    @AfterEach void clear(){f.clear();}
    Set<String> perms(String...p){return new HashSet<>(Arrays.asList(p));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("rows");}
    @Test void permissionProjectionNeverLeaksOtherSectionsOrCreatesDefaults(){
        Map<String,Object> result=settings.listing("VERIFY",perms("order:refund:config"),Collections.emptyMap());
        assertEquals(2,number(result,"total"));Map<String,Object> row=rows(result).get(0);
        assertTrue(row.containsKey("refund"));assertFalse(row.containsKey("order"));assertFalse(row.containsKey("exchange"));assertFalse(row.containsKey("shipping"));
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_api_cursor",Integer.class));
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM oms_refund_flow_policy",Integer.class));
        verifyNoInteractions(settings.sources);verify(settings.catalog,times(1)).channels("VERIFY");
    }
    @Test void detailEnforcesCompanyAndLoadsSourceChoicesOnlyForOrderEditors(){
        assertThrows(IllegalArgumentException.class,()->settings.detail("OTHER",perms("*:*:*"),1));
        assertThrows(IllegalArgumentException.class,()->settings.detail("VERIFY",perms("*:*:*"),999));
        assertFalse(settings.detail("VERIFY",perms("order:refund:config"),1).containsKey("sources"));verifyNoInteractions(settings.sources);
        assertTrue(settings.detail("VERIFY",perms("order:pull:config"),1).containsKey("sources"));verify(settings.sources,times(1)).choices("VERIFY");
    }
    @Test void shippingEditorsCanSeeConnectionWithoutReadingIntakeCredentials(){
        f.db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,seller_nick,source_key,owner_code,store_codes,initial_time,cursor_time) VALUES ('VERIFY',1,'Shop','seller','missing','OWNER','[]',NOW(),NOW())");
        Map<String,Object> row=settings.detail("VERIFY",perms("order:shipment:platformConfig"),1);
        assertEquals(true,row.get("order_connected"));assertFalse(row.containsKey("order"));assertFalse(row.containsKey("sources"));
    }
    @Test void missingSourcesAndDisabledShopsAreActionableAndFilterable(){
        f.db.update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,seller_nick,source_key,owner_code,store_codes,enabled,initial_time,cursor_time) VALUES ('VERIFY',1,'Shop','seller','missing','OWNER','[]',1,NOW(),NOW())");
        Map<String,String> query=new HashMap<>();query.put("status","INCOMPLETE");
        Map<String,Object> result=settings.listing("VERIFY",perms("order:pull:config"),query);assertEquals(2,number(result,"total"));
        assertEquals("order-intake",((Map<?,?>)((List<?>)rows(result).get(0).get("issues")).get(0)).get("section"));
        query.put("status","AUTOMATIC");assertEquals(1,number(settings.listing("VERIFY",perms("order:pull:config"),query),"total"));
        query.put("keyword","停用");assertEquals(0,number(settings.listing("VERIFY",perms("order:pull:config"),query),"total"));
        query.put("status","");assertEquals(1,number(settings.listing("VERIFY",perms("order:pull:config"),query),"total"));
    }
    @Test void paginationAndInvalidFiltersAreBounded(){
        Map<String,String> query=new HashMap<>();query.put("pageSize","1");query.put("pageNum","2");
        Map<String,Object> result=settings.listing("VERIFY",perms("*:*:*"),query);assertEquals(2,number(result,"total"));assertEquals(2,number(rows(result).get(0),"channel_id"));
        query.put("pageSize","10000");assertThrows(IllegalArgumentException.class,()->settings.listing("VERIFY",perms("*:*:*"),query));
        query.put("pageSize","20");query.put("status","ARBITRARY");assertThrows(IllegalArgumentException.class,()->settings.listing("VERIFY",perms("*:*:*"),query));
    }
}
