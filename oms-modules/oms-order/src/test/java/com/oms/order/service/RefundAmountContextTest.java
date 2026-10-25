package com.oms.order.service;

import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;

class RefundAmountContextTest {
    RefundServiceTest f=new RefundServiceTest();
    @BeforeAll static void start(){RefundServiceTest.start();}
    @BeforeEach void setup(){f.reset();}
    @AfterEach void clear(){f.clear();}
    long refund(String rid,String fee,String state){Map<String,Object> src=f.source(1,fee,state),p=parse(text(src,"jdp_response"));src.put("refund_id",rid);p.put("refund_id",rid);src.put("jdp_response",encode(m("refund",p)));return f.convert(f.ingest(src));}
    Map<String,Object> context(long id,Map<String,Object> sale){Map<String,Object> b=f.refunds.business("VERIFY",id,false);return RefundAmountContext.read(f.repo,"VERIFY",b,f.refunds.platform("VERIFY",number(b,"platform_refund_id"),false),sale);}
    Map<String,Object> sale(){return m("goods_payment","25.00","payment_source","平台分摊实付（已核对不含运费）","quantity",5);}
    @Test void completedCurrentRefundCountsOnceAndLeavesNoRemainingMoney(){long prior=refund("90001","5.00","SUCCESS");f.workspace.review("VERIFY",prior,f.review(prior,"DIFFERENCE","KEEP"),"test");long current=refund("90002","20.00","SUCCESS");Map<String,Object> c=context(current,sale());assertEquals("25.00",text(c,"confirmed_total"));assertEquals("5.00",text(c,"other_confirmed"));assertEquals("20.00",text(c,"current_confirmed"));assertEquals("20.00",text(c,"current_limit"));assertEquals("0.00",text(c,"remaining_unpaid"));}
    @Test void onlyOtherAdoptedOpenRefundsOccupyTheCurrentLimit(){long other=refund("90001","3.00","WAIT_SELLER_AGREE");f.workspace.review("VERIFY",other,f.review(other,"COMPENSATION","KEEP"),"test");long current=refund("90002","10.00","WAIT_SELLER_AGREE");f.workspace.review("VERIFY",current,f.review(current,"OTHER","KEEP"),"test");Map<String,Object> c=context(current,sale());assertEquals("3.00",text(c,"other_occupied"));assertEquals("22.00",text(c,"current_limit"));assertEquals("25.00",text(c,"remaining_unpaid"));}
    @Test void partialRequestedQuantityNeverGetsAnAveragedPayment(){long id=refund("90001","2.00","WAIT_SELLER_AGREE");Map<String,Object> c=context(id,sale());assertNull(c.get("requested_goods_payment"));assertTrue(text(c,"requested_payment_note").contains("不按平均"));Map<String,Object> all=sale();all.put("quantity",1);assertEquals("25.00",text(context(id,all),"requested_goods_payment"));}
    @Test void calculatedOrMissingPaidAmountIsNeverAnAuthoritativeLimit(){long id=refund("90001","2.00","WAIT_SELLER_AGREE");Map<String,Object> calculated=sale();calculated.put("payment_source","销售金额减优惠");assertFalse((Boolean)context(id,calculated).get("reliable"));assertNull(context(id,calculated).get("current_limit"));assertNull(context(id,m()).get("original_goods_payment"));}
    @Test void closedAndUnreviewedRefundsDoNotOccupyMoney(){refund("90001","3.00","CLOSED");refund("90002","5.00","WAIT_SELLER_AGREE");long id=refund("90003","2.00","WAIT_SELLER_AGREE");assertEquals("25.00",text(context(id,sale()),"current_limit"));assertEquals("0.00",text(context(id,sale()),"other_occupied"));}
    @Test void conflictingSourceMakesRemainingMoneyUnknown(){long id=refund("90001","2.00","WAIT_SELLER_AGREE");f.db.update("UPDATE oms_platform_refund SET source_state='CONFLICT'");Map<String,Object> c=context(id,sale());assertFalse((Boolean)c.get("reliable"));assertNull(c.get("remaining_unpaid"));}
    @Test void postageMoneyIsNotSilentlyDeductedAsGoodsPayment(){long prior=refund("90001","2.00","SUCCESS");f.workspace.review("VERIFY",prior,f.review(prior,"POSTAGE","KEEP"),"test");long id=refund("90002","3.00","WAIT_SELLER_AGREE");Map<String,Object> c=context(id,sale());assertFalse((Boolean)c.get("reliable"));assertTrue(text(c,"issue").contains("运费"));}
    @Test void otherTenantCannotReadTheBudget(){long id=refund("90001","2.00","WAIT_SELLER_AGREE");assertThrows(IllegalArgumentException.class,()->f.workspace.detail("OTHER",id));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> records(long id,int page,int size){return (List<Map<String,Object>>)f.workspace.amountRecords("VERIFY",id,page,size).get("rows");}
    @Test void relatedRefundsUseTheSameCompletedAndOccupiedAmountsAndExcludeCurrent(){
        long prior=refund("90001","5.00","SUCCESS");f.workspace.review("VERIFY",prior,f.review(prior,"DIFFERENCE","KEEP"),"test");
        long occupied=refund("90002","3.00","WAIT_SELLER_AGREE");f.workspace.review("VERIFY",occupied,f.review(occupied,"COMPENSATION","KEEP"),"test");
        refund("90003","1.00","WAIT_SELLER_AGREE");refund("90004","1.00","CLOSED");long current=refund("90005","17.00","SUCCESS");
        List<Map<String,Object>> rows=records(current,1,20);assertEquals(2,rows.size());assertEquals(occupied,number(rows.get(0),"business_refund_id"));assertEquals("0.00",text(rows.get(0),"confirmed_amount"));assertEquals("3.00",text(rows.get(0),"occupied_amount"));assertEquals(prior,number(rows.get(1),"business_refund_id"));assertEquals("5.00",text(rows.get(1),"confirmed_amount"));assertEquals("DIFFERENCE",rows.get(1).get("purpose"));
        Map<String,Object> summary=context(current,sale());assertEquals(text(summary,"other_confirmed"),text(rows.get(1),"confirmed_amount"));assertEquals(text(summary,"other_occupied"),text(rows.get(0),"occupied_amount"));
    }
    @Test void relatedRefundsStayInsideTenantShopOrderAndSuborder(){
        long current=refund("90001","1.00","WAIT_SELLER_AGREE"),same=refund("90002","1.00","SUCCESS");
        for(int i=0;i<3;i++){long id=refund("9000"+(i+3),"1.00","SUCCESS");String column=Arrays.asList("channel_id","platform_tid","platform_oid").get(i);f.db.update("UPDATE oms_platform_refund SET "+column+"=? WHERE business_refund_id=?",i==0?999999:"999999",id);}
        assertEquals(1,records(current,1,20).size());assertEquals(same,number(records(current,1,20).get(0),"business_refund_id"));assertThrows(IllegalArgumentException.class,()->f.workspace.amountRecords("OTHER",current,1,20));
    }
    @Test void platformSuccessWithoutBusinessConversionRemainsVisibleAndPreservesLongRefundNumber(){
        long current=refund("90001","1.00","WAIT_SELLER_AGREE");Map<String,Object> src=f.source(1,"4.00","SUCCESS"),p=parse(text(src,"jdp_response"));String rid="9007199254740993123";src.put("refund_id",rid);p.put("refund_id",rid);src.put("jdp_response",encode(m("refund",p)));f.ingest(src);
        Map<String,Object> row=records(current,1,20).get(0);assertEquals(rid,row.get("refund_id"));assertNull(row.get("business_refund_id"));assertEquals("4.00",text(row,"confirmed_amount"));
    }
    @Test void relatedRefundsHaveBoundedStablePaginationAndValidateLimits(){
        refund("90001","1.00","SUCCESS");refund("90002","1.00","SUCCESS");refund("90003","1.00","SUCCESS");long current=refund("90004","1.00","WAIT_SELLER_AGREE");
        Map<String,Object> first=f.workspace.amountRecords("VERIFY",current,1,2);assertEquals(true,first.get("has_more"));assertEquals(Arrays.asList("90003","90002"),Arrays.asList(records(current,1,2).get(0).get("refund_id"),records(current,1,2).get(1).get("refund_id")));assertEquals("90001",records(current,2,2).get(0).get("refund_id"));assertEquals(false,f.workspace.amountRecords("VERIFY",current,2,2).get("has_more"));assertTrue(records(current,3,2).isEmpty());
        assertThrows(IllegalArgumentException.class,()->records(current,0,20));assertThrows(IllegalArgumentException.class,()->records(current,1,51));
    }
}
