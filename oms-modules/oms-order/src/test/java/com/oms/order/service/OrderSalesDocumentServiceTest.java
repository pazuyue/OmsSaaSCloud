package com.oms.order.service;

import com.ruoyi.common.core.domain.R;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.oms.order.service.OrderValues.*;

class OrderSalesDocumentServiceTest {
    OrderShipmentServiceTest fixture;OrderSalesDocumentService sync;OrderShipmentWmsClient warehouse;long shipment;
    @BeforeAll static void setup(){OrderShipmentServiceTest.setup();}
    @BeforeEach void seed(){OrderShipmentServiceTest.db.update("DELETE FROM oms_order_sales_document_sync");fixture=new OrderShipmentServiceTest();fixture.seed();Map<String,Object> receipt=fixture.receipt(1,10,"V1","R1");receipt.put("batch_code","B001");fixture.mirror(Arrays.asList(receipt));shipment=number(fixture.generate(0).get(0),"id");fixture.db.update("INSERT IGNORE INTO oms_order_sales_document_sync(company_code,shipment_id) VALUES ('VERIFY',?)",shipment);warehouse=mock(OrderShipmentWmsClient.class);when(warehouse.salesDocument(anyString(),anyMap(),anyString())).thenReturn(R.ok(true));sync=new OrderSalesDocumentService();ReflectionTestUtils.setField(sync,"repository",fixture.repository);ReflectionTestUtils.setField(sync,"warehouse",warehouse);}
    @Test void newShipmentHasXsPrefixAndBridgeCarriesItsImmutablePlanAndBatch(){assertTrue(fixture.db.queryForObject("SELECT shipment_sn FROM oms_order_shipment WHERE id=?",String.class,shipment).startsWith("XS"));sync.sync("VERIFY",shipment);ArgumentCaptor<Map> body=ArgumentCaptor.forClass(Map.class);verify(warehouse).salesDocument(eq("VERIFY"),body.capture(),eq("inner"));Map<?,?> line=(Map<?,?>)((List<?>)body.getValue().get("lines")).get(0);assertEquals("B001",line.get("batch_code"));assertEquals(10L,((Number)line.get("quantity")).longValue());assertEquals(0L,((Number)line.get("shipped_quantity")).longValue());assertEquals(1,fixture.db.queryForObject("SELECT synced_revision FROM oms_order_sales_document_sync",Integer.class));}
    @Test void lostReplyRetriesTheSameSnapshotAndUnchangedAcknowledgedPlanDoesNotResend(){when(warehouse.salesDocument(anyString(),anyMap(),anyString())).thenThrow(new IllegalStateException("lost reply")).thenReturn(R.ok(true));sync.sync("VERIFY",shipment);assertEquals(0,fixture.db.queryForObject("SELECT synced_revision FROM oms_order_sales_document_sync",Integer.class));sync.sync("VERIFY",shipment);sync.sync("VERIFY",shipment);ArgumentCaptor<Map> body=ArgumentCaptor.forClass(Map.class);verify(warehouse,times(2)).salesDocument(eq("VERIFY"),body.capture(),eq("inner"));assertEquals(encode(body.getAllValues().get(0)),encode(body.getAllValues().get(1)));}
    @Test void actualOutboundMirrorsEachCompletedRequestAndAdvancesSourceVersion(){sync.sync("VERIFY",shipment);long line=fixture.db.queryForObject("SELECT id FROM oms_order_shipment_line",Long.class);fixture.db.update("UPDATE oms_order_shipment_line SET shipped_quantity=4");fixture.db.update("UPDATE oms_order_shipment SET status='PART_SHIPPED',control_version=control_version+1");String payload=encode(fixture.row("lines",Arrays.asList(fixture.row("id",line,"batch_id",1,"quantity",4))));fixture.db.update("INSERT INTO oms_order_shipment_outbound(company_code,shipment_id,shipment_sn,request_id,source_mode,quantity,payload_json,payload_hash,state,created_by,outbound_time,completed_at) SELECT company_code,id,shipment_sn,'VO-TEST','VIRTUAL',4,?,'hash','COMPLETED','tester',NOW(),NOW() FROM oms_order_shipment",payload);sync.sync("VERIFY",shipment);ArgumentCaptor<Map> body=ArgumentCaptor.forClass(Map.class);verify(warehouse,times(2)).salesDocument(eq("VERIFY"),body.capture(),eq("inner"));Map<?,?> latest=body.getAllValues().get(1);assertEquals(2L,((Number)latest.get("source_version")).longValue());assertEquals(1,((List<?>)latest.get("movements")).size());assertEquals(4L,((Number)((Map<?,?>)((List<?>)latest.get("movements")).get(0)).get("quantity")).longValue());}
    @Test void newerChangeDuringRemoteSyncRemainsDueAndUnchangedAcknowledgementClearsWork(){
        when(warehouse.salesDocument(anyString(),anyMap(),anyString())).thenAnswer(i->{fixture.repository.shipmentChanged("VERIFY",shipment);return R.ok(true);});
        sync.sync("VERIFY",shipment);Map<String,Object> row=fixture.db.queryForMap("SELECT * FROM oms_order_sales_document_sync");assertTrue(number(row,"dirty_version")>number(row,"synced_dirty_version"));assertNotNull(row.get("next_attempt"));
        doReturn(R.ok(true)).when(warehouse).salesDocument(anyString(),anyMap(),anyString());sync.sync("VERIFY",shipment);row=fixture.db.queryForMap("SELECT * FROM oms_order_sales_document_sync");assertEquals(number(row,"dirty_version"),number(row,"synced_dirty_version"));assertNull(row.get("next_attempt"));verify(warehouse,times(1)).salesDocument(anyString(),anyMap(),anyString());
    }

}
