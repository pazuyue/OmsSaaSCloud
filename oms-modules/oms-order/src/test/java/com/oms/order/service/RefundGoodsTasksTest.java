package com.oms.order.service;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.RefundValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RefundGoodsTasksTest {
 RefundServiceTest f=new RefundServiceTest();RefundGoodsTasks tasks;long refund,line,source;
 @BeforeAll static void start(){RefundServiceTest.start();}
 @BeforeEach void setup(){f.reset();f.shipped();f.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=2");f.db.update("UPDATE oms_order_shipment SET status='PART_SHIPPED'");line=f.db.queryForObject("SELECT id FROM oms_order_line",Long.class);source=f.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);refund=f.convert(f.ingest(f.source(1,"25.00","WAIT_SELLER_AGREE")));tasks=new RefundGoodsTasks();tasks.refunds=f.refunds;tasks.inventory=mock(OrderWarehouseClient.class);f.workspace.goodsTasks=tasks;}
 @AfterEach void clear(){f.clear();}
 Map<String,Object> review(List<Map<String,Object>> scopes){Map<String,Object> input=f.review(refund,"CANCEL","MIXED");input.put("goods_tasks",scopes);return input;}
 Map<String,Object> cancel(long quantity){return m("action","CANCEL","order_line_id",line,"quantity",quantity);}
 Map<String,Object> returned(long quantity){return m("action","RETURN","order_line_id",line,"shipment_line_id",source,"quantity",quantity,"store_code","V1","return_address","测试退货地址","return_contact","测试收件人 10086");}
 @Test void oneRefundAdoptsBothUnshippedCancellationAndShippedReturn(){f.workspace.review("VERIFY",refund,review(Arrays.asList(cancel(3),returned(2))),"test");assertEquals(2,tasks.report("VERIFY",refund).size());assertEquals("MIXED",f.refunds.business("VERIFY",refund,false).get("goods_action"));assertEquals(0,f.db.queryForObject("SELECT cancelled_quantity FROM oms_order_line",Integer.class));verifyNoInteractions(tasks.inventory);}
 @Test void cannotUseAmountOrPlatformQuantityToCancelShippedGoods(){assertThrows(IllegalArgumentException.class,()->f.workspace.review("VERIFY",refund,review(Collections.singletonList(cancel(4))),"test"));assertEquals(0,tasks.report("VERIFY",refund).size());}
 @Test void sameShippedSourceCannotBeClaimedTwiceWithDifferentActions(){Map<String,Object> intercept=returned(2);intercept.put("action","INTERCEPT");assertThrows(IllegalArgumentException.class,()->f.workspace.review("VERIFY",refund,review(Arrays.asList(returned(2),intercept)),"test"));assertTrue(tasks.report("VERIFY",refund).isEmpty());}
 @Test void unknownWarehouseCannotBeTreatedAsUnshipped(){f.db.update("UPDATE oms_order_shipment SET status='DISPATCHING'");assertThrows(IllegalArgumentException.class,()->f.workspace.review("VERIFY",refund,review(Collections.singletonList(cancel(3))),"test"));verifyNoInteractions(tasks.inventory);}
 @Test void firstSnapshotDoesNotChangeWhenAnotherWarehouseShips(){String initial=f.db.queryForObject("SELECT initial_fulfillment_snapshot FROM oms_refund WHERE id=?",String.class,refund);f.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=5");Map<String,Object> b=f.refunds.business("VERIFY",refund,false);assertEquals("SHIPPED",RefundFulfillment.context(f.repo,"VERIFY",b).get("stage"));assertEquals(initial,f.db.queryForObject("SELECT initial_fulfillment_snapshot FROM oms_refund WHERE id=?",String.class,refund));}
 @Test void otherTenantCannotReadOrRetryTasks(){assertThrows(IllegalArgumentException.class,()->tasks.report("OTHER",refund));assertThrows(IllegalArgumentException.class,()->tasks.retry("OTHER",refund,1));}
 @Test void refundOnlyWithMixedReturnIsQueryableBeforeTheReturnPlanExists(){f.workspace.review("VERIFY",refund,review(Arrays.asList(cancel(3),returned(2))),"test");assertEquals(1,number(f.refunds.businessList("VERIFY",Collections.singletonMap("scenario","REFUND_RETURN")),"total"));assertEquals(1,number(f.refunds.businessList("VERIFY",Collections.singletonMap("scenario","MISSING_DOCUMENT")),"total"));f.db.update("UPDATE oms_refund_goods_task SET state='MANUAL' WHERE refund_id=?",refund);assertEquals(1,number(f.refunds.businessList("VERIFY",Collections.singletonMap("task","ISSUE")),"total"));}
 @Test void cancellationOnlyMixedScopeIsNotMisclassifiedAsReturn(){f.workspace.review("VERIFY",refund,review(Collections.singletonList(cancel(3))),"test");assertEquals(0,number(f.refunds.businessList("VERIFY",Collections.singletonMap("scenario","REFUND_RETURN")),"total"));}
 @Test void confirmedFullCancellationFinishesOriginalOrderAndReplayCannotReopenIt(){
  f.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=0");f.db.update("UPDATE oms_order_shipment SET status='VOIDED'");
  f.workspace.review("VERIFY",refund,review(Collections.singletonList(cancel(5))),"test");
  Map<String,Object> task=tasks.report("VERIFY",refund).get(0);String request=f.db.queryForObject("SELECT request_id FROM oms_refund_goods_task WHERE id=?",String.class,task.get("id"));Map<String,Object> command=m("request_id",request,"mode","REDUCE","demands",Collections.singletonList(m("platform_oid","987654321012345679","sku_sn","A","quantity",5)));
  f.db.update("UPDATE oms_refund_goods_task SET command_json=? WHERE id=?",encode(command),task.get("id"));
  when(tasks.inventory.adjustmentReceipt(eq("VERIFY"),eq(request),anyString())).thenReturn(com.ruoyi.common.core.domain.R.ok(m("request_id",request,"state","COMPLETED","lines",Collections.singletonList(m("platform_oid","987654321012345679","sku_sn","A","cancelled_quantity",5,"reserved_quantity",0)))));
  tasks.process("VERIFY",number(task,"id"));assertEquals("VOIDED",f.db.queryForObject("SELECT status FROM oms_order",String.class));assertEquals("RELEASED",f.db.queryForObject("SELECT inventory_status FROM oms_order",String.class));assertEquals("DONE",tasks.report("VERIFY",refund).get(0).get("state"));
  long version=f.db.queryForObject("SELECT control_version FROM oms_order",Long.class);tasks.process("VERIFY",number(task,"id"));assertEquals(version,f.db.queryForObject("SELECT control_version FROM oms_order",Long.class));verify(tasks.inventory,never()).adjust(anyString(),anyMap(),anyString());
 }
}
