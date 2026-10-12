package com.oms.order.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderShipmentPackageService {
    @Resource private OrderRepository repository;
    @Resource private OrderShipmentDispatchService locks;
    public Map<String,Object> register(String company,long id,Map<String,Object> input,String actor){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource())).execute(s->{
        Map<String,Object> shipment=locks.lock(company,id,null,false);require(input.containsKey("control_version"),"缺少发货单版本");String request=text(input,"request_id");require(request.matches("[A-Za-z0-9_-]{16,64}"),"运单登记请求号无效");require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_virtual_outbound WHERE company_code=? AND shipment_id=? AND state='COMPLETED'",Long.class,company,id)>0,"只有已确认虚拟出库的发货单可人工登记运单；WMS运单以仓库回传为准");require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_dispatch WHERE company_code=? AND shipment_id=?",Long.class,company,id)==0,"WMS发货单不能人工补录运单");
        Map<String,Object> pack=new LinkedHashMap<>();pack.put("package_code",text(input,"package_code").isEmpty()?"PK"+hash(request).substring(0,24):text(input,"package_code"));pack.put("express_code",text(input,"express_code"));pack.put("outbound_time",text(input,"outbound_time"));pack.put("lines",input.get("lines"));for(String field:Arrays.asList("carrier_code","carrier_name","warehouse_carrier_code"))pack.put(field,shipment.get(field));long existing=repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_shipment_package WHERE company_code=? AND shipment_id=? AND (request_id=? OR (carrier_code=? AND express_code=?))",Long.class,company,id,request,shipment.get("carrier_code"),pack.get("express_code"));if(existing==0){require(number(shipment,"control_version")==number(input,"control_version"),"发货单已变化，请刷新后登记运单");require(Arrays.asList("PART_SHIPPED","SHIPPED").contains(text(shipment,"status")),"请先确认实际出库结果后登记运单");}
        long packageId=new OrderShipmentOutboundRecords(repository).packageRecord(company,shipment,null,pack,"VIRTUAL",request,actor);if(existing==0){repository.db().update("UPDATE oms_order_shipment SET control_version=control_version+1 WHERE company_code=? AND id=?",company,id);repository.db().update("UPDATE oms_order SET control_version=control_version+1 WHERE company_code=? AND id=?",company,shipment.get("order_id"));String message="登记运单 "+pack.get("express_code")+"，仅关联已出库商品，不重复扣库存";repository.db().update("INSERT INTO oms_order_shipment_log(company_code,shipment_id,action,result,message,operator_name) VALUES (?,?,'PACKAGE_REGISTER','SUCCESS',?,?)",company,id,message,actor);repository.log(company,number(shipment,"platform_order_id"),"PACKAGE_REGISTER","SUCCESS",text(shipment,"shipment_sn")+"："+message,actor);}Map<String,Object> result=new LinkedHashMap<>();result.put("id",packageId);result.put("success",true);result.put("message",existing>0?"该运单已登记，无需重复提交":"运单已登记，库存保持不变");return result;
    });}
}
