package com.oms.order.service;
import com.oms.common.api.OrderStockClient;
import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderProcessorTest {
    private static class SkuCase {
        final OrderRepository repository=mock(OrderRepository.class);
        final OrderCatalog catalog=mock(OrderCatalog.class);
        final OrderStockClient inventory=mock(OrderStockClient.class);
        final Map<String,Object> config=new HashMap<>();
        SkuCase(String source,String manual) throws Exception {
            com.fasterxml.jackson.databind.JsonNode root=OrderValues.JSON.readTree(TmallOrderParserTest.payload("25"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("trade_fullinfo_get_response").path("trade").path("orders").path("order").get(0)).put("outer_sku_id",source);
            Map<String,Object> order=new HashMap<>();order.put("processing_payload",root.toString());order.put("platform_tid","987654321012345678");
            when(repository.snapshot("VERIFY",1,"lease")).thenReturn(order);
            when(repository.manualMatches("VERIFY",1)).thenReturn(manual==null?Collections.emptyMap():Collections.singletonMap("987654321012345679",manual));
            config.put("seller_nick","seller");config.put("allowed_trade_types","[\"fixed\"]");config.put("channel_id",1);
            when(catalog.goods(anyString(),anyList())).thenReturn(Collections.singletonMap(manual==null?"A":"M",Collections.singletonList(Collections.emptyMap())));
            when(inventory.execute(anyString(),anyString(),any(),anyString())).thenReturn(R.ok(Collections.emptyList()));
            OrderProcessor processor=new OrderProcessor();ReflectionTestUtils.setField(processor,"repository",repository);ReflectionTestUtils.setField(processor,"catalog",catalog);ReflectionTestUtils.setField(processor,"inventory",inventory);
            processor.process("VERIFY",1,"lease",config,true);
        }
        void matched(String source,String expected) {
            verify(catalog).goods("VERIFY",Collections.singletonList(expected));
            verify(repository).parsed(eq("VERIFY"),eq(1L),eq("lease"),argThat(t->source.equals(t.lines.get(0).outerSku)),eq(Collections.singletonMap("987654321012345679",expected)),eq(Collections.emptyMap()));
            for(String operation:Arrays.asList("register","reserve"))verify(inventory).execute(eq(operation),eq("VERIFY"),argThat(c->c.getLines().size()==1&&expected.equals(c.getLines().get(0).getSkuSn())&&c.getLines().get(0).getQuantity()==5),anyString());
            verify(repository).complete(eq("VERIFY"),eq(1L),eq("lease"),eq(config),anyList());
            verify(repository,never()).failure(anyString(),anyLong(),anyString(),anyString(),anyString(),anyList(),anyBoolean());
        }
    }
    @Test void trimsPlatformSkuBoundaryWhitespaceButPreservesSourceSnapshot() throws Exception {
        for(String source:Arrays.asList(" A ","\tA\r\n","\u00a0\u3000A\u2003"))new SkuCase(source,null).matched(source,"A");
    }
    @Test void normalizedManualMatchStillTakesPriorityOverPlatformSku() throws Exception {
        new SkuCase(" A ","\tM\u3000").matched(" A ","M");
    }
    @Test void whitespaceOnlySkuIsReportedMissingWithoutRegisteringDemand() throws Exception {
        String source=" \t\u3000\u00a0";SkuCase test=new SkuCase(source,null);
        verify(test.catalog).goods("VERIFY",Collections.emptyList());verifyNoInteractions(test.inventory);
        verify(test.repository).failure(eq("VERIFY"),eq(1L),eq("lease"),eq("PRODUCT_INVALID"),eq("缺少outer_sku_id"),argThat(v->v.size()==1&&"OUTER_SKU_MISSING".equals(v.get(0).get("code"))&&source.equals(v.get(0).get("outer_sku_id"))),eq(false));
    }
    @Test void skuCaseInternalSpacesAndNonWhitespaceCharactersRemainExact() throws Exception {
        for(String source:Arrays.asList("a","A B","A\u200b")){
            SkuCase test=new SkuCase(source,null);verify(test.catalog).goods("VERIFY",Collections.singletonList(source));verifyNoInteractions(test.inventory);
            verify(test.repository).failure(eq("VERIFY"),eq(1L),eq("lease"),eq("PRODUCT_INVALID"),eq("内部SKU不存在："+source),argThat(v->v.size()==1&&"SKU_NOT_FOUND".equals(v.get(0).get("code"))),eq(false));
        }
    }
    @Test void shopValidationFailureIsRecordedOnThePendingTaskWithoutSourceOrStockCalls(){
        OrderRepository repository=mock(OrderRepository.class);OrderCatalog catalog=mock(OrderCatalog.class);OrderVoidService voids=mock(OrderVoidService.class);OrderStockClient inventory=mock(OrderStockClient.class);ConfiguredPushReader push=mock(ConfiguredPushReader.class);org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(repository.claim(eq("VERIFY"),eq(1L),anyString())).thenReturn(true);when(repository.db()).thenReturn(jdbc);when(repository.config("VERIFY",1)).thenReturn(Collections.emptyMap());when(voids.due("VERIFY",1)).thenReturn(Collections.emptyList());when(repository.nextTask(eq("VERIFY"),eq(1L),anyString())).thenReturn(Collections.singletonMap("id",42L));when(catalog.validateShop("VERIFY",1)).thenThrow(new IllegalArgumentException("店铺已停用"));
        OrderProcessor processor=new OrderProcessor();ReflectionTestUtils.setField(processor,"repository",repository);ReflectionTestUtils.setField(processor,"catalog",catalog);ReflectionTestUtils.setField(processor,"voids",voids);ReflectionTestUtils.setField(processor,"inventory",inventory);ReflectionTestUtils.setField(processor,"push",push);processor.shop("VERIFY",1);
        verify(repository).taskFailed(eq("VERIFY"),eq(1L),anyString(),eq(42L),eq("店铺已停用"));verifyNoInteractions(push,inventory);verify(repository).release(eq("VERIFY"),eq(1L),anyString());
    }
    @Test void businessRetryUsesConvertedLinesAndLatestPolicyWithoutMatchingAgain(){
        OrderRepository repository=mock(OrderRepository.class);OrderCatalog catalog=mock(OrderCatalog.class);OrderStockClient inventory=mock(OrderStockClient.class);
        OrderProcessor processor=new OrderProcessor();ReflectionTestUtils.setField(processor,"repository",repository);ReflectionTestUtils.setField(processor,"catalog",catalog);ReflectionTestUtils.setField(processor,"inventory",inventory);
        Map<String,Object> order=new HashMap<>();order.put("business_order_id",9L);order.put("platform_tid","12345");order.put("channel_id",1L);order.put("processing_payload","invalid payload must not be reparsed");when(repository.snapshot("VERIFY",1,"lease")).thenReturn(order);
        org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);when(repository.db()).thenReturn(jdbc);
        Map<String,Object> line=new HashMap<>();line.put("platform_oid","123451");line.put("sku_sn","MATCHED");line.put("quantity",20);
        when(jdbc.query(contains("FROM oms_order_line"),eq(OrderValues.ROW),eq("VERIFY"),eq(9L))).thenReturn(Arrays.asList(line));when(inventory.execute(anyString(),anyString(),any(),anyString())).thenReturn(R.ok(Collections.emptyList()));
        Map<String,Object> config=new HashMap<>();config.put("allow_public_stock",1);processor.process("VERIFY",1,"lease",config,false);
        verify(inventory).execute(eq("reserve"),eq("VERIFY"),argThat(c->c.isAllowPublicStock()&&c.getLines().size()==1&&"MATCHED".equals(c.getLines().get(0).getSkuSn())),anyString());
        verifyNoInteractions(catalog);verify(inventory,never()).execute(eq("register"),anyString(),any(),anyString());verify(repository).complete(eq("VERIFY"),eq(1L),eq("lease"),eq(config),anyList());
    }
    @Test void bindingBeatsMerchantCodeAndFrozenDemandBeatsNewBinding() {
        for(boolean frozen:Arrays.asList(false,true)){
            OrderRepository r=mock(OrderRepository.class);OrderCatalog c=mock(OrderCatalog.class);OrderStockClient i=mock(OrderStockClient.class);OrderProcessor p=new OrderProcessor();ReflectionTestUtils.setField(p,"repository",r);ReflectionTestUtils.setField(p,"catalog",c);ReflectionTestUtils.setField(p,"inventory",i);
            Map<String,Object> raw=new HashMap<>();raw.put("processing_payload",TmallOrderParserTest.payload("25"));raw.put("platform_tid","987654321012345678");when(r.snapshot("QM",1,"lease")).thenReturn(raw);
            TmallOrderParser.Line line=new TmallOrderParser().parse(TmallOrderParserTest.payload("25")).lines.get(0);Map<String,Object> b=new HashMap<>();b.put("binding_id",22L);b.put("binding_revision",3);b.put("binding_state","BOUND");b.put("oms_sku_sn","BOUND");when(c.productBindings(eq("QM"),eq(1L),anyList())).thenReturn(Collections.singletonMap(OrderCatalog.bindingKey(line),b));if(frozen)when(r.frozenMatches("QM",1)).thenReturn(Collections.singletonMap(line.oid,"FROZEN"));String expected=frozen?"FROZEN":"BOUND";
            when(c.goods(anyString(),anyList())).thenReturn(Collections.singletonMap(expected,Collections.singletonList(Collections.emptyMap())));when(i.execute(anyString(),anyString(),any(),anyString())).thenReturn(R.ok(Collections.emptyList()));Map<String,Object> config=new HashMap<>();config.put("channel_id",1);config.put("seller_nick","seller");config.put("allowed_trade_types","[\"fixed\"]");p.process("QM",1,"lease",config,true);verify(i).execute(eq("register"),eq("QM"),argThat(cmd->expected.equals(cmd.getLines().get(0).getSkuSn())),anyString());verify(r,never()).failure(anyString(),anyLong(),anyString(),anyString(),anyString(),anyList(),anyBoolean());
        }
    }
    @Test void reviewBindingBlocksMerchantCodeFallback(){
        OrderRepository r=mock(OrderRepository.class);OrderCatalog c=mock(OrderCatalog.class);OrderStockClient i=mock(OrderStockClient.class);OrderProcessor p=new OrderProcessor();ReflectionTestUtils.setField(p,"repository",r);ReflectionTestUtils.setField(p,"catalog",c);ReflectionTestUtils.setField(p,"inventory",i);Map<String,Object> raw=new HashMap<>();raw.put("processing_payload",TmallOrderParserTest.payload("25"));raw.put("platform_tid","987654321012345678");when(r.snapshot("QM",1,"lease")).thenReturn(raw);TmallOrderParser.Line line=new TmallOrderParser().parse(TmallOrderParserTest.payload("25")).lines.get(0);when(c.productBindings(eq("QM"),eq(1L),anyList())).thenReturn(Collections.singletonMap(OrderCatalog.bindingKey(line),Collections.singletonMap("binding_state","REVIEW")));when(c.goods(anyString(),anyList())).thenReturn(Collections.singletonMap("A",Collections.singletonList(Collections.emptyMap())));Map<String,Object> config=new HashMap<>();config.put("channel_id",1);config.put("seller_nick","seller");config.put("allowed_trade_types","[\"fixed\"]");p.process("QM",1,"lease",config,true);verifyNoInteractions(i);verify(r).failure(eq("QM"),eq(1L),eq("lease"),eq("PRODUCT_INVALID"),contains("平台商品绑定REVIEW"),anyList(),eq(false));
    }    @Test void pendingCatalogReadRetriesAutomaticallyWithoutDemand(){
        OrderRepository r=mock(OrderRepository.class);OrderCatalog c=mock(OrderCatalog.class);OrderStockClient i=mock(OrderStockClient.class);OrderProcessor p=new OrderProcessor();ReflectionTestUtils.setField(p,"repository",r);ReflectionTestUtils.setField(p,"catalog",c);ReflectionTestUtils.setField(p,"inventory",i);Map<String,Object> raw=new HashMap<>();raw.put("processing_payload",TmallOrderParserTest.payload("25"));raw.put("platform_tid","987654321012345678");when(r.snapshot("QM",1,"lease")).thenReturn(raw);TmallOrderParser.Line line=new TmallOrderParser().parse(TmallOrderParserTest.payload("25")).lines.get(0);when(c.productBindings(eq("QM"),eq(1L),anyList())).thenReturn(Collections.singletonMap(OrderCatalog.bindingKey(line),Collections.singletonMap("binding_state","SYNC_PENDING")));when(c.goods(anyString(),anyList())).thenReturn(Collections.singletonMap("A",Collections.singletonList(Collections.emptyMap())));Map<String,Object> config=new HashMap<>();config.put("channel_id",1);config.put("seller_nick","seller");config.put("allowed_trade_types","[\"fixed\"]");p.process("QM",1,"lease",config,true);verifyNoInteractions(i);verify(r).failure(eq("QM"),eq(1L),eq("lease"),eq("PRODUCT_SYNC_PENDING"),contains("自动重试"),anyList(),eq(true));
    }
    void run(String payment,boolean matched,boolean auto,boolean manual) {
        OrderRepository repository=mock(OrderRepository.class);OrderCatalog catalog=mock(OrderCatalog.class);OrderStockClient inventory=mock(OrderStockClient.class);
        OrderProcessor processor=new OrderProcessor();ReflectionTestUtils.setField(processor,"repository",repository);ReflectionTestUtils.setField(processor,"catalog",catalog);ReflectionTestUtils.setField(processor,"inventory",inventory);
        Map<String,Object> raw=new HashMap<>();raw.put("processing_payload",TmallOrderParserTest.payload(payment));raw.put("platform_tid","987654321012345678");raw.put("force_convert",manual?1:0);
        when(repository.snapshot("VERIFY",1,"lease")).thenReturn(raw);
        Map<String,Object> config=new HashMap<>();config.put("seller_nick","seller");config.put("allowed_trade_types","[\"fixed\"]");config.put("channel_id",1);config.put("owner_code","OWNER");config.put("store_codes","[\"W1\"]");
        when(catalog.goods(anyString(),anyList())).thenReturn(matched?Collections.singletonMap("A",Arrays.asList(new HashMap<>())):Collections.emptyMap());
        when(inventory.execute(anyString(),anyString(),any(),anyString())).thenReturn(R.ok(Collections.emptyList()));
        processor.process("VERIFY",1,"lease",config,auto);
        if(!matched){verifyNoInteractions(inventory);verify(repository).failure(eq("VERIFY"),eq(1L),eq("lease"),eq("PRODUCT_INVALID"),contains("SKU"),argThat(v->v.size()==1&&"SKU_NOT_FOUND".equals(v.get(0).get("code"))),eq(false));return;}
        InOrder sequence=inOrder(repository,inventory);sequence.verify(repository).prepareDemand("VERIFY",1,"lease");sequence.verify(inventory).execute(eq("register"),eq("VERIFY"),any(),anyString());sequence.verify(repository).registered("VERIFY",1,"lease");
        if("invalid".equals(payment)){verify(inventory,never()).execute(eq("reserve"),anyString(),any(),anyString());verify(repository).failure(eq("VERIFY"),eq(1L),eq("lease"),eq("AMOUNT_INVALID"),anyString(),anyList(),eq(false));}
        else{verify(inventory).execute(eq("reserve"),eq("VERIFY"),any(),anyString());verify(repository).complete(eq("VERIFY"),eq(1L),eq("lease"),eq(config),anyList());}
    }
    @Test void invalidAmountStillRegistersKnownSkuDemand(){run("invalid",true,true,false);}
    @Test void unmatchedSkuNeverInventsInventoryDemand(){run("25",false,true,false);}
    @Test void manualRetryConvertsWithAutoConversionDisabled(){run("25",true,false,true);}
}
