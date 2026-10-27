package com.oms.supplychain.service.wms;

import com.ruoyi.common.core.domain.R;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;
import static com.oms.supplychain.service.wms.PurchaseReturnFulfillment.n;

/** Read-only explanation of the same source limit enforced under the purchase/root locks. */
public class PurchaseReturnRestoreCheck {
 private final WmsStore db;
 private final PurchaseReturnStockClient stock;
 public PurchaseReturnRestoreCheck(WmsStore db,PurchaseReturnStockClient stock){this.db=db;this.stock=stock;}

 public static class Blocked extends IllegalArgumentException {
  private final Map<String,Object> check;
  public Blocked(Map<String,Object> check){super(text(check.get("message")));this.check=check;}
  public Map<String,Object> getCheck(){return check;}
 }
 private String key(Map<String,Object> l){return text(l.get("source_key"))+"/"+text(l.get("inventory_type"));}
 private String batchKey(Map<String,Object> l){return text(l.get("sku_sn"))+"/"+text(l.get("store_code"))+"/"+text(l.get("batch_code"));}

 public Map<String,Object> sources(String c,Map<String,Object>d){
  long parent=n(d,"id");
  List<Map<String,Object>> ls=db.jdbc.queryForList("SELECT id,source_key,ticket_sn,sku_sn,goods_name,stock_unit,batch_code,inventory_type,quantity,source_quantity,posted_quantity FROM purchase_return_line WHERE company_code=? AND return_id=? AND quantity>posted_quantity ORDER BY id",c,parent);
  require(ls.size()<=200,"恢复商品明细超过200行，请核对原计划");
  List<Object>args=new ArrayList<>(Arrays.asList(c,d.get("purchase_id"),parent));String where=sourceWhere(ls,args);
  Map<String,Map<String,Object>>used=new HashMap<>();
  if(!ls.isEmpty())for(Map<String,Object>x:db.jdbc.queryForList("SELECT l.source_key,l.inventory_type,SUM(IF(r.state='CANCELED',l.posted_quantity,l.quantity)) claimed_quantity,SUM(l.posted_quantity) posted_quantity FROM purchase_return_line l JOIN purchase_return r ON r.company_code=l.company_code AND r.id=l.return_id WHERE r.company_code=? AND r.purchase_id=? AND r.id<>? AND r.state<>'DRAFT' AND ("+where+") GROUP BY l.source_key,l.inventory_type",args.toArray()))used.put(key(x),x);
  List<Map<String,Object>>rows=new ArrayList<>();boolean passed=!ls.isEmpty();
  for(Map<String,Object>l:ls){Map<String,Object>u=used.getOrDefault(key(l),Collections.emptyMap());long needed=n(l,"quantity")-n(l,"posted_quantity"),other=n(u,"claimed_quantity"),available=Math.max(0,n(l,"source_quantity")-n(l,"posted_quantity")-other),gap=Math.max(0,needed-available);Map<String,Object>row=new LinkedHashMap<>(l);row.putAll(map("store_code",d.get("store_code"),"required_quantity",needed,"own_posted_quantity",n(l,"posted_quantity"),"other_claimed_quantity",other,"other_posted_quantity",n(u,"posted_quantity"),"other_unexecuted_quantity",Math.max(0,other-n(u,"posted_quantity")),"source_available_quantity",available,"source_shortage_quantity",gap,"inventory_known",false,"claimants",new ArrayList<Map<String,Object>>()));rows.add(row);passed&=gap==0;}
  Map<String,Object>check=map("return_id",parent,"return_sn",d.get("return_sn"),"po_sn",d.get("po_sn"),"control_version",d.get("control_version"),"source_ok",passed,"inventory_checked",false,"inventory_ok",false,"can_approve",false,"claimants_truncated",false,"lines",rows,"inventory_groups",Collections.emptyList(),"checked_at",java.time.LocalDateTime.now().toString());
  check.put("message",sourceMessage(rows,passed));return check;
 }
 private String sourceWhere(List<Map<String,Object>>ls,List<Object>args){List<String>clauses=new ArrayList<>();for(Map<String,Object>l:ls){clauses.add("(l.source_key=? AND l.inventory_type=?)");args.add(l.get("source_key"));args.add(l.get("inventory_type"));}return clauses.isEmpty()?"1=0":String.join(" OR ",clauses);}
 private String sourceMessage(List<Map<String,Object>>ls,boolean passed){
  if(passed)return "原入库来源额度满足本次恢复数量";
  for(Map<String,Object>l:ls)if(n(l,"source_shortage_quantity")>0)return "原入库来源已被其他退仓占用："+text(l.get("sku_sn"))+" 本次恢复 "+n(l,"required_quantity")+text(l.get("stock_unit"))+"，原来源可恢复 "+n(l,"source_available_quantity")+text(l.get("stock_unit"))+"，缺口 "+n(l,"source_shortage_quantity")+text(l.get("stock_unit"))+"。请核对占用单据。";
  return "原计划没有尚未出库的可恢复数量";
 }
 @SuppressWarnings("unchecked") public void claimants(String c,Map<String,Object>d,Map<String,Object>check){
  List<Map<String,Object>>ls=(List<Map<String,Object>>)check.get("lines");if(ls.isEmpty())return;
  Map<String,Map<String,Object>>index=new HashMap<>();for(Map<String,Object>l:ls)index.put(key(l),l);
  List<Object>args=new ArrayList<>(Arrays.asList(c,d.get("purchase_id"),d.get("id")));String where=sourceWhere(ls,args);
  List<Map<String,Object>>rows=db.jdbc.queryForList("SELECT r.id return_id,r.return_sn,r.state,r.dispatch_state,l.source_key,l.inventory_type,l.stock_unit,l.quantity,l.posted_quantity,IF(r.state='CANCELED',l.posted_quantity,l.quantity) claimed_quantity FROM purchase_return_line l JOIN purchase_return r ON r.company_code=l.company_code AND r.id=l.return_id WHERE r.company_code=? AND r.purchase_id=? AND r.id<>? AND r.state<>'DRAFT' AND ("+where+") AND IF(r.state='CANCELED',l.posted_quantity,l.quantity)>0 ORDER BY r.id DESC,l.id LIMIT 201",args.toArray());
  check.put("claimants_truncated",rows.size()>200);for(Map<String,Object>x:rows.subList(0,Math.min(200,rows.size()))){x.put("unexecuted_claim_quantity",Math.max(0,n(x,"claimed_quantity")-n(x,"posted_quantity")));((List<Map<String,Object>>)index.get(key(x)).get("claimants")).add(x);}
 }
 @SuppressWarnings("unchecked") public Map<String,Object> preview(String c,long parent,long request){
  Map<String,Object>d=db.one("SELECT * FROM purchase_return WHERE company_code=? AND id=? AND fulfillment_model=2",c,parent),r=db.one("SELECT * FROM purchase_return_execution_request WHERE company_code=? AND return_id=? AND id=? AND kind='RESTORE'",c,parent,request);
  Map<String,Object>check=sources(c,d);check.put("request_id",request);check.put("request_state",r.get("state"));claimants(c,d,check);
  boolean actionable="REVIEW".equals(r.get("state"))&&"CANCELED".equals(d.get("state"));check.put("actionable",actionable);
  List<Map<String,Object>>ls=(List<Map<String,Object>>)check.get("lines");Map<String,Map<String,Object>>keys=new LinkedHashMap<>();for(Map<String,Object>l:ls)keys.putIfAbsent(batchKey(l),map("sku_sn",l.get("sku_sn"),"store_code",l.get("store_code"),"batch_code",l.get("batch_code")));
  if(!ls.isEmpty())try{R<List<Map<String,Object>>>reply=stock.balances(c,new ArrayList<>(keys.values()));require(reply!=null&&R.isSuccess(reply)&&reply.getData()!=null,"当前批次库存查询未确认，请刷新核对");Map<String,Map<String,Object>>balances=new HashMap<>();for(Map<String,Object>b:reply.getData())balances.put(batchKey(b),b);Map<String,Map<String,Object>>groups=new LinkedHashMap<>();boolean inventoryOk=true;
   for(Map<String,Object>l:ls){Map<String,Object>b=balances.get(batchKey(l));String field="ZP".equals(l.get("inventory_type"))?"zp_available_number":"cp_available_number";boolean known=b!=null&&b.get(field)!=null;long free=known?Math.max(0,n(b,field)):0;l.put("inventory_known",known);l.put("batch_available_quantity",known?free:null);String groupKey=batchKey(l)+"/"+l.get("inventory_type");Map<String,Object>g=groups.computeIfAbsent(groupKey,k->map("sku_sn",l.get("sku_sn"),"store_code",l.get("store_code"),"batch_code",l.get("batch_code"),"inventory_type",l.get("inventory_type"),"stock_unit",l.get("stock_unit"),"required_quantity",0L,"available_quantity",known?free:null,"inventory_known",known));g.put("required_quantity",Math.addExact(n(g,"required_quantity"),n(l,"required_quantity")));}
   for(Map<String,Object>g:groups.values()){boolean known=Boolean.TRUE.equals(g.get("inventory_known"));long gap=known?Math.max(0,n(g,"required_quantity")-n(g,"available_quantity")):n(g,"required_quantity");g.put("shortage_quantity",known?gap:null);inventoryOk&=known&&gap==0;}
   check.put("inventory_checked",true);check.put("inventory_ok",inventoryOk);check.put("inventory_groups",new ArrayList<>(groups.values()));
  }catch(Exception ex){check.put("inventory_error","当前批次库存未查到，请刷新核对；本次没有占用或释放库存");}
  boolean can=actionable&&Boolean.TRUE.equals(check.get("source_ok"))&&Boolean.TRUE.equals(check.get("inventory_ok"));check.put("can_approve",can);
  if(!actionable)check.put("message","本恢复申请的进度已变化，请刷新原单据");else if(Boolean.TRUE.equals(check.get("source_ok")))check.put("message",can?"当前来源额度及批次库存满足本次恢复数量，审核后重新校验并锁定":"原来源额度满足，当前批次可用库存尚未满足本次恢复，请核对库存明细");return check;
 }
}
