package com.oms.supplychain;
import com.oms.supplychain.service.wms.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;
import static com.oms.supplychain.service.wms.WmsStore.*;
class QimenProcessTest {
 QimenProcessProtocol p=new QimenProcessProtocol();
 Map<String,Object> config(){return map("app_key","app","customer_id","customer","endpoint","http://localhost","external_owner","O");}
 String item(String sku,long q){return "<item><itemCode>"+sku+"</itemCode><quantity>"+q+"</quantity><inventoryType>ZP</inventoryType><batchCode>B1</batchCode></item>";}
 String body(){return "<request><processOrderCode>JG123</processOrderCode><processOrderId>W123</processOrderId><ownerCode>O</ownerCode><warehouseCode>W</warehouseCode><outBizCode>E1</outBizCode><orderType>CNJG</orderType><actualQty>1</actualQty><materialitems>"+item("A",2)+item("B",1)+"</materialitems><productitems>"+item("S",1)+"</productitems></request>";}
 Map<String,String> params(String body){Map<String,String> m=new TreeMap<>();m.put("method","taobao.qimen.storeprocess.confirm");m.put("sign_method","md5");m.put("v","2.0");m.put("app_key","app");m.put("customerId","customer");m.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));m.put("sign",QimenProtocol.sign(m,body,"secret"));return m;}
 @Test void signedStandardContainerArraysPreserveActualBatches(){Map<String,Object> r=p.callback(config(),"secret",params(body()),body());assertEquals("JG123",r.get("process_sn"));assertEquals(2,((List<?>)r.get("materials")).size());assertEquals(1L,r.get("quantity"));}
 @Test void tamperingAndXmlEntitiesAreRejected(){Map<String,String> signed=params(body());assertThrows(IllegalArgumentException.class,()->p.callback(config(),"secret",signed,body().replace("<quantity>2","<quantity>3")));String bad="<!DOCTYPE request [<!ENTITY x SYSTEM 'file:///secret'>]>"+body();assertThrows(IllegalArgumentException.class,()->p.callback(config(),"secret",params(bad),bad));}
 @Test void bothDirectionsUseCnjgServiceTypesAndHeldInputBatch(){Map<String,Object> t=map("process_sn","JG1","external_warehouse","W","external_owner","O","quantity",2,"sku_sn","S","operation","ASSEMBLE","wire_time","2026-10-18 10:00:00");Map<String,Object> recipe=map("components",Arrays.asList(map("sku_sn","A","quantity",2),map("sku_sn","B","quantity",1)));String a=p.create(t,recipe,map("holds",Arrays.asList(map("sku_sn","A","quantity",4,"batch_code","LOT_A"),map("sku_sn","B","quantity",2,"batch_code","LOT_B"))));assertTrue(a.contains("<serviceType>1</serviceType>"));assertTrue(a.contains("<batchCode>LOT_A</batchCode>"));assertTrue(a.contains("<materialitems><item>"));t.put("operation","DISASSEMBLE");String d=p.create(t,recipe,map("holds",Arrays.asList(map("sku_sn","S","quantity",2,"batch_code","FIN"))));assertTrue(d.contains("<serviceType>2</serviceType>"));assertTrue(d.contains("<batchCode>FIN</batchCode>"));}
 @Test void incompleteActualReceiptCannotBeUsedForStock(){String b=body().replace("<batchCode>B1</batchCode>","");assertThrows(IllegalArgumentException.class,()->p.callback(config(),"secret",params(b),b));}
 @Test void internalStockClientHasAnExplicitFeignContract(){assertEquals(4,new org.springframework.cloud.openfeign.support.SpringMvcContract().parseAndValidateMetadata(ProcessStockClient.class).size());}
}
