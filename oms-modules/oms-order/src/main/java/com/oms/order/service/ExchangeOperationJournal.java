package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;

/** Immutable automatic intent; unknown replies always reconcile the original exchange request. */
@Service public class ExchangeOperationJournal {
    @Resource OrderRepository repository;
    @Resource ExchangeFlowPolicy policies;
    Map<String,Object> begin(String c,Map<String,Object> e,String action,Map<String,Object> policy,Map<String,Object> input){
        String plan=e.get("approved_snapshot")==null?text(e,"payload_hash"):hash(canonical(object(e.get("approved_snapshot"))));
        String key=hash(c+"/"+number(e,"id")+"/"+ExchangeRounds.numberOf(e)+"/"+action+"/"+plan+"/"+number(policy,"revision"));
        repository.db().update("INSERT IGNORE INTO oms_exchange_operation(company_code,exchange_id,round_no,action,command_key,request_id,source_version,policy_revision,request_json,actor) VALUES (?,?,?,?,?,?,?,?,?,?)",c,e.get("id"),ExchangeRounds.numberOf(e),action,key,"EXOP"+token(),e.get("control_version"),policy.get("revision"),encode(input),ExchangeFlowPolicy.ACTOR);
        return repository.db().query("SELECT * FROM oms_exchange_operation WHERE company_code=? AND exchange_id=? AND command_key=?",ROW,c,e.get("id"),key).get(0);
    }
    void update(String c,long id,String state,String message,Object result){repository.db().update("UPDATE oms_exchange_operation SET state=?,message=?,result_json=? WHERE company_code=? AND id=? AND state NOT IN ('SUCCESS','CLOSED')",state,message.substring(0,Math.min(1000,message.length())),result==null?null:encode(result),c,id);}
    static String flag(String action){return "AGREE".equals(action)?"auto_agree":"RECEIVED".equals(action)?"auto_receive":"CONSIGN".equals(action)?"auto_consign":"";}
    void authorize(String c,Map<String,Object> e,Map<String,Object> command){
        if(number(command,"flow_operation_id")==0)return;
        List<Map<String,Object>> rows=repository.db().query("SELECT policy_revision,state FROM oms_exchange_operation WHERE company_code=? AND id=? AND exchange_id=? AND round_no=? AND action=?",ROW,c,command.get("flow_operation_id"),e.get("id"),ExchangeRounds.numberOf(e),command.get("operation"));
        require(rows.size()==1&&!Arrays.asList("SUCCESS","CLOSED","FAILED").contains(text(rows.get(0),"state")),"原换货自动命令已结束，请核对原结果");
        policies.requireCurrent(c,number(e,"channel_id"),number(rows.get(0),"policy_revision"),flag(text(command,"operation")));
    }
    void recorded(String c,Map<String,Object> command,String state,String message,Object result){if(number(command,"flow_operation_id")>0)update(c,number(command,"flow_operation_id"),state,message,result);}
    List<Map<String,Object>> records(String c,long id){return repository.db().query("SELECT id,round_no,action,request_id,source_version,policy_revision,state,message,actor,create_time,update_time FROM oms_exchange_operation WHERE company_code=? AND exchange_id=? ORDER BY id DESC LIMIT 100",ROW,c,id);}
}
