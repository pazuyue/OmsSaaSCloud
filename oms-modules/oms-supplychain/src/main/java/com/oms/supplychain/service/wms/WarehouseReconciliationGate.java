package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** A short database lease limits warehouse queries without holding any inventory lock. */
@Component public class WarehouseReconciliationGate {
 @Resource WmsStore db; @Resource WarehouseControlScope scope;
 public String key(Map<String,Object> d){return scope.hash(Arrays.asList(d.get("connection_id"),d.get("owner_code"),d.get("real_store")));}
 public boolean acquire(String c,Map<String,Object>d,String token){String k=key(d);db.jdbc.update("INSERT IGNORE INTO warehouse_reconciliation_gate(company_code,scope_key) VALUES(?,?)",c,k);return db.jdbc.update("UPDATE warehouse_reconciliation_gate SET task_id=?,lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND scope_key=? AND (lease_until IS NULL OR lease_until<NOW())",d.get("id"),token,c,k)==1;}
 public void renew(String c,Map<String,Object>d,String token){require(db.jdbc.update("UPDATE warehouse_reconciliation_gate SET lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) WHERE company_code=? AND scope_key=? AND task_id=? AND lease_token=? AND lease_until>NOW()",c,key(d),d.get("id"),token)==1,"同仓查询租约已变化，请等待后台恢复");}
 public void release(String c,Map<String,Object>d,String token){db.jdbc.update("UPDATE warehouse_reconciliation_gate SET lease_until=NULL,lease_token=NULL WHERE company_code=? AND scope_key=? AND task_id=? AND lease_token=?",c,key(d),d.get("id"),token);}
}
