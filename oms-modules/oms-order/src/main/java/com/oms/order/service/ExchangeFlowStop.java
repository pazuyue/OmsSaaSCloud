package com.oms.order.service;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Platform withdrawal stops new fulfillment; only existing cutoff receipts can release inventory. */
@Service public class ExchangeFlowStop {
    @Resource OrderRepository repository;
    @Resource ExchangeControlService controls;
    @Resource ExchangeService exchanges;
    void advance(String c,Map<String,Object> e,Map<String,Object> p){
        repository.db().update("UPDATE oms_exchange SET fulfillment_hold=1,problem_codes='PLATFORM_CLOSED',problem_message='平台申请关闭或转退款，停止新增换出并核对原仓库截单',control_version=control_version+IF(fulfillment_hold=0,1,0) WHERE company_code=? AND id=?",c,e.get("id"));
        long open=repository.db().queryForObject("SELECT COUNT(*) FROM oms_exchange_control WHERE company_code=? AND exchange_id=? AND state<>'COMPLETED'",Long.class,c,e.get("id"));
        if(open>0){controls.process(c,number(e,"id"));return;}
        if(!ExchangeFlowPolicy.on(p,"enabled")||!ExchangeFlowPolicy.on(p,"auto_stop")||e.get("approved_version")==null||ExchangeControlService.terminal(e)||ExchangeFulfillment.unshipped(e))return;
        if(repository.db().queryForObject("SELECT COUNT(*) FROM oms_exchange_control WHERE company_code=? AND exchange_id=? AND kind='CANCEL'",Long.class,c,e.get("id"))>0)return;
        Map<String,Object> current=exchanges.row(c,number(e,"id"),false);
        controls.request(c,number(e,"id"),map("control_version",current.get("control_version"),"kind","CANCEL","mode","TERMINATE","reason","商家自动规则：平台申请关闭或转退款，截停尚未完成的退回与换出；已发生实收实发保留"),ExchangeFlowPolicy.ACTOR);
        controls.process(c,number(e,"id"));
    }
}
