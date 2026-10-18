package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** A replacement round has its own immutable inventory demand under the same HH and SO. */
final class ExchangeRounds {
    private ExchangeRounds() {}
    static long numberOf(Map<String,Object> e){return Math.max(1,number(e,"fulfillment_round"));}
    static long quantityOf(Map<String,Object> e){return e.get("round_quantity")==null?number(e,"requested_quantity"):number(e,"round_quantity");}
    static String reference(Map<String,Object> e){long round=numberOf(e);return "EXCHANGE:"+number(e,"id")+(round==1?"":":"+round);}
    static Map<String,Object> recipient(Map<String,Object> e){return e.get("round_recipient")==null?object(object(e.get("approved_snapshot")).get("replacement_recipient")):object(e.get("round_recipient"));}
    static void guardShipment(Map<String,Object> e,Map<String,Object> shipment){
        require(number(e,"fulfillment_hold")==0&&!ExchangeControlService.terminal(e),"换货已暂停或结束");
        require(numberOf(e)==Math.max(1,number(shipment,"fulfillment_round"))&&reference(e).equals(stockReference(shipment)),"该发货单属于已结束的换出批次，不能重新执行");
    }
    static List<Map<String,Object>> records(OrderRepository repository,String company,long id){return repository.db().query("SELECT id,round_no,stock_reference,quantity,target_sku,state,recipient_json,reason,evidence,actor,reviewed_by,review_reason,reviewed_at,fulfillment_line_id,create_time FROM oms_exchange_round WHERE company_code=? AND exchange_id=? ORDER BY round_no LIMIT 100",ROW,company,id);}
}
