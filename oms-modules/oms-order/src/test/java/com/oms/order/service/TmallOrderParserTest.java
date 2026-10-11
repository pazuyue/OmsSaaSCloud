package com.oms.order.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TmallOrderParserTest {
    @Test void displayFieldsAndJsonOrderingDoNotPauseReservation() throws Exception {
        TmallOrderParser p=new TmallOrderParser();String original=payload("25");
        com.fasterxml.jackson.databind.JsonNode root=OrderValues.JSON.readTree(original);
        com.fasterxml.jackson.databind.node.ObjectNode line=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("trade_fullinfo_get_response").path("trade").path("orders").path("order").get(0);
        line.put("title","updated display title");line.put("sku_properties_name","display specification");line.put("logistics_company","display carrier");
        assertEquals(p.parse(original).fingerprint,p.parse(root.toString()).fingerprint);
        line.put("refund_status","WAIT_SELLER_AGREE");assertNotEquals(p.parse(original).fingerprint,p.parse(root.toString()).fingerprint);
    }
    @Test void reorderingPlatformLinesKeepsFingerprint() throws Exception {
        TmallOrderParser p=new TmallOrderParser();com.fasterxml.jackson.databind.JsonNode root=OrderValues.JSON.readTree(payload("25"));
        com.fasterxml.jackson.databind.node.ArrayNode lines=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("trade_fullinfo_get_response").path("trade").path("orders").path("order");
        com.fasterxml.jackson.databind.node.ObjectNode second=lines.get(0).deepCopy();second.put("oid","987654321012345680");lines.add(second);String before=p.parse(root.toString()).fingerprint;
        com.fasterxml.jackson.databind.JsonNode first=lines.remove(0);lines.add(first);assertEquals(before,p.parse(root.toString()).fingerprint);
    }
    @Test void dataErrorsIdentifyFieldsAndKeepAllValidationIssues(){
        TmallOrderParser parser=new TmallOrderParser();
        ConversionException quantity=assertThrows(ConversionException.class,()->parser.parse(payload("25").replace("\"num\":5","\"num\":\"invalid\"")));
        assertEquals("ORDER_DATA_INVALID",quantity.code);assertTrue(quantity.getMessage().contains("987654321012345679"));assertTrue(quantity.getMessage().contains("num"));
        assertThrows(IllegalArgumentException.class,()->parser.parse(payload("25").replace("WAIT_SELLER_SEND_GOODS","")));
        TmallOrderParser.Trade trade=parser.parse(payload("invalid"));trade.oaid="";trade.lines.get(0).payment=null;
        java.util.List<java.util.Map<String,Object>> issues=parser.conversionIssues(trade);assertEquals(3,issues.size());
        assertEquals("payment",issues.get(0).get("field"));assertEquals("RECIPIENT_MISSING",issues.get(1).get("code"));assertEquals(trade.lines.get(0).oid,issues.get(2).get("platform_oid"));
    }
    static final java.util.List<String> TYPES=java.util.Arrays.asList("fixed","guarantee_trade");
    @Test void eligibilityUsesConfiguredTypesIncludingCustomCodes(){
        TmallOrderParser parser=new TmallOrderParser();
        for(String type:java.util.Arrays.asList("tmall_i18n","custom_trade")){
            TmallOrderParser.Trade trade=parser.parse(payload("25").replace("fixed",type));
            assertFalse(trade.eligible(TYPES));assertTrue(trade.eligible(java.util.Collections.singletonList(type)));
            parser.validateConversion(trade,java.util.Collections.singletonList(type));
            trade.status="WAIT_BUYER_PAY";assertFalse(trade.eligible(java.util.Collections.singletonList(type)));
            assertThrows(IllegalArgumentException.class,()->parser.validateConversion(trade,java.util.Collections.singletonList(type)));
        }
    }
    @Test void rejectionExplainsActualStatusOrType(){
        TmallOrderParser parser=new TmallOrderParser();
        TmallOrderParser.Trade international=parser.parse(payload("25").replace("fixed","tmall_i18n"));
        assertFalse(international.eligible(TYPES));assertTrue(international.ineligibleReason(TYPES).contains("交易类型 tmall_i18n"));
        TmallOrderParser.Trade unpaid=parser.parse(payload("25").replace("WAIT_SELLER_SEND_GOODS","WAIT_BUYER_PAY"));
        assertFalse(unpaid.eligible(TYPES));assertTrue(unpaid.ineligibleReason(TYPES).contains("订单状态 WAIT_BUYER_PAY"));
    }
    static String payload(String payment){return "{\"trade_fullinfo_get_response\":{\"trade\":{\"tid\":987654321012345678,\"status\":\"WAIT_SELLER_SEND_GOODS\",\"type\":\"fixed\",\"seller_nick\":\"seller\",\"oaid\":\"test-oaid\",\"payment\":\""+payment+"\",\"orders\":{\"order\":[{\"oid\":987654321012345679,\"num\":5,\"outer_sku_id\":\"A\",\"outer_iid\":\"WRONG\",\"sku_id\":111,\"divide_order_fee\":\"25.00\"}]}}}}";}
    @Test void preservesLongIdsAndSnapshotSku(){TmallOrderParser p=new TmallOrderParser();TmallOrderParser.Trade t=p.parse(payload("25.00"));assertEquals("987654321012345678",t.tid);assertEquals("987654321012345679",t.lines.get(0).oid);assertEquals("A",t.lines.get(0).outerSku);assertEquals(5,t.lines.get(0).quantity);p.validateConversion(t,TYPES);}
    @Test void invalidAmountDoesNotPreventDemandParsing(){TmallOrderParser p=new TmallOrderParser();TmallOrderParser.Trade t=p.parse(payload("invalid"));assertEquals(5,t.lines.get(0).quantity);assertNull(t.payment);assertThrows(IllegalArgumentException.class,()->p.validateConversion(t,TYPES));}
    @Test void rejectsFractionalQuantityAndDuplicateChildIds(){TmallOrderParser p=new TmallOrderParser();assertThrows(IllegalArgumentException.class,()->p.parse(payload("25").replace("\"num\":5","\"num\":1.5")));}
    @Test void modifiedTimeDoesNotChangeBusinessFingerprint(){TmallOrderParser p=new TmallOrderParser();String a=payload("25");assertEquals(p.parse(a).fingerprint,p.parse(a.replace("\"oaid\"","\"modified\":\"2026-10-10 12:00:00\",\"oaid\"")).fingerprint);assertNotEquals(p.parse(a).fingerprint,p.parse(a.replace("\"num\":5","\"num\":6")).fingerprint);}
}
