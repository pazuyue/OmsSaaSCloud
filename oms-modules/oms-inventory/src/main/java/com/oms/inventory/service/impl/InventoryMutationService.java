package com.oms.inventory.service.impl;

import com.oms.inventory.model.entity.WmsInventoryBatch;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** One local transaction. Lock order: company/SKU OMS row, sorted warehouses, batch IDs, history.
 * No remote calls or automatic retries inside a transaction. Callers must propagate failures.
 */
@Service
public class InventoryMutationService {
    @Resource private JdbcTemplate jdbc;

    private void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private String canonical(String company) {
        require(company != null && !company.trim().isEmpty(), "公司编码不能为空");
        return company.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String,Object> lockOms(String company, String sku, boolean create) {
        if (create) jdbc.update("INSERT INTO oms_inventory(company_code,sku_sn,total_stock,available_stock,version) VALUES (?,?,0,0,1) ON DUPLICATE KEY UPDATE id=id",company,sku);
        List<Map<String,Object>> rows = jdbc.query("SELECT * FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE", ROW, company,sku);
        require(!rows.isEmpty(),"缺少商品汇总库存，请先核对入库记录");
        return rows.get(0);
    }

    private Map<String,Object> lockWarehouse(String company,String sku,String store) {
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM wms_inventory WHERE company_code=? AND sku_sn=? AND store_code=? FOR UPDATE",ROW,company,sku,store);
        require(!rows.isEmpty(),"仓库库存不存在："+store);
        return rows.get(0);
    }

    private void checkConsistent(Map<String,Object> inventory, String company) {
        // A locking aggregate is a current read, including when an outer rule transaction has an older RR snapshot.
        String sums=Arrays.stream(QUANTITIES).map(q->"COALESCE(SUM("+q+"),0) AS "+q).collect(java.util.stream.Collectors.joining(","));
        Map<String,Object> totals=jdbc.queryForObject("SELECT "+sums+" FROM wms_inventory_batch WHERE company_code=? AND sku_sn=? AND store_code=? FOR UPDATE",ROW,company,inventory.get("skuSn"),inventory.get("storeCode"));
        boolean valid=number(inventory,"zpActualNumber")==number(inventory,"zpAvailableNumber")+number(inventory,"zpLockNumber") && number(inventory,"cpActualNumber")==number(inventory,"cpAvailableNumber")+number(inventory,"cpLockNumber");
        for(String q:QUANTITIES) valid &= number(inventory,camel(q))>=0 && number(inventory,camel(q))==number(totals,camel(q));
        require(valid,"汇总与批次库存不一致，请先核对数据："+inventory.get("storeCode"));
    }

    private List<Map<String,Object>> previous(String company,String sku,String request,String operation) {
        return jdbc.query("SELECT * FROM wms_inventory_change_history WHERE company_code=? AND sku_sn=? AND request_id=? AND operation_type=? ORDER BY log_id FOR UPDATE",ROW,company,sku,request,operation);
    }

    /** Called before channel writes inside the existing allocation transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean beginAllocation(String company,String sku,String relation,boolean locking) {
        company=canonical(company);
        lockOms(company,sku,false);
        return !locking || previous(company,sku,relation,"LOCK").isEmpty();
    }

    private void journal(String company, Map<String,Object> before, String operation,String request,String relation,String type,int quantity,String reason) {
        Map<String,Object> after=jdbc.queryForObject("SELECT * FROM wms_inventory_batch WHERE id=?",ROW,before.get("id"));
        List<Object> args=new ArrayList<>(Arrays.asList(company,before.get("storeCode"),before.get("skuSn"),before.get("id"),before.get("batchCode"),type,operation,request,relation,quantity,reason,SecurityUtils.getUserId(),SecurityUtils.getUsername()));
        StringBuilder cols=new StringBuilder("company_code,store_code,sku_sn,batch_id,batch_code,inventory_type,operation_type,request_id,relation_sn,change_quantity,change_reason,operator_id,operator_name");
        for(String q:QUANTITIES) {
            String key=camel(q); cols.append(",old_").append(q).append(",new_").append(q);
            args.add(before.get(key));args.add(after.get(key));
        }
        jdbc.update("INSERT INTO wms_inventory_change_history ("+cols+") VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
    }

    private String camel(String value) {
        StringBuilder result=new StringBuilder();boolean next=false;
        for(char c:value.toCharArray()){if(c=='_'){next=true;continue;}result.append(next?Character.toUpperCase(c):c);next=false;}
        return result.toString();
    }

    @Transactional(rollbackFor=Exception.class, timeout=15)
    public void adjust(String company,long batchId,int version,int quantity,String type,String reason,String request) {
        company=canonical(company);
        require(quantity!=0 && Math.abs((long)quantity)<=100000000,"调整数量必须是非零整数，且绝对值不超过一亿");
        require("ZP".equals(type)||"CP".equals(type),"请选择正品或次品");
        require(reason!=null && reason.trim().length()>=2 && reason.length()<=255,"请填写 2 至 255 字的调整原因");
        require(request!=null && request.matches("[a-zA-Z0-9_-]{16,64}"),"调整请求编号无效");
        List<Map<String,Object>> found=jdbc.query("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=?",ROW,company,batchId);
        require(!found.isEmpty(),"批次不存在或无权访问");
        Map<String,Object> hint=found.get(0);String sku=(String)hint.get("skuSn");
        Map<String,Object> oms=lockOms(company,sku,false);
        List<Map<String,Object>> prior=previous(company,sku,request,"ADJUST");
        if(!prior.isEmpty()) {
            Map<String,Object> p=prior.get(0);
            require(number(p,"batchId")==batchId && number(p,"changeQuantity")==quantity && type.equals(p.get("inventoryType")) && reason.trim().equals(p.get("changeReason")),"重复请求的调整内容不一致");return;
        }
        Map<String,Object> inventory=lockWarehouse(company,sku,(String)hint.get("storeCode"));
        checkConsistent(inventory,company);
        Map<String,Object> batch=jdbc.queryForObject("SELECT * FROM wms_inventory_batch WHERE company_code=? AND id=? FOR UPDATE",ROW,company,batchId);
        require(number(batch,"version")==version,"库存已变化，请刷新批次后重新调整");
        String prefix="ZP".equals(type)?"zp":"cp";
        require(number(batch,prefix+"AvailableNumber")+quantity>=0,"调整后可用库存不能为负数，预占库存不能直接扣减");
        require(number(oms,"availableStock")+quantity>=0 && number(oms,"totalStock")+quantity>=0,"调整后商品汇总库存不足");
        jdbc.update("UPDATE wms_inventory_batch SET "+prefix+"_actual_number="+prefix+"_actual_number+?,"+prefix+"_available_number="+prefix+"_available_number+?,version=version+1 WHERE id=?",quantity,quantity,batchId);
        jdbc.update("UPDATE wms_inventory SET "+prefix+"_actual_number="+prefix+"_actual_number+?,"+prefix+"_available_number="+prefix+"_available_number+?,version=version+1 WHERE id=?",quantity,quantity,inventory.get("id"));
        jdbc.update("UPDATE oms_inventory SET total_stock=total_stock+?,available_stock=available_stock+?,version=version+1 WHERE id=?",quantity,quantity,oms.get("id"));
        journal(company,batch,"ADJUST",request,"ADJ-"+request,type,quantity,reason.trim());
    }

    @Transactional(rollbackFor=Exception.class, timeout=15)
    public boolean reserve(String company,List<String> stores,String sku,BigDecimal amount,String relation,boolean release) {
        company=canonical(company);
        require(stores!=null && !stores.isEmpty() && stores.size()<=100,"请选择 1 至 100 个仓库");
        require(sku!=null && !sku.isEmpty() && relation!=null && relation.length()<=64,"库存操作必须关联来源单据");
        int quantity=amount.intValueExact();require(quantity>0,"预占或释放数量必须为正整数");
        Map<String,Object> oms=lockOms(company,sku,false);
        String operation=release?"UNLOCK":"LOCK";
        List<Map<String,Object>> prior=previous(company,sku,relation,operation);
        if(!prior.isEmpty()) {
            require(prior.stream().mapToLong(p->Math.abs(number(p,"changeQuantity"))).sum()==quantity && prior.stream().allMatch(p->stores.contains(p.get("storeCode"))),"该单据已经处理，数量或仓库发生变化");
            return true;
        }
        require(number(oms,release?"allocatedStock":"availableStock")>=quantity,"商品汇总库存不足");
        List<Map<String,Object>> warehouses=new ArrayList<>();
        for(String store:new TreeSet<>(stores)) warehouses.add(lockWarehouse(company,sku,store));
        for(Map<String,Object> inventory:warehouses) checkConsistent(inventory,company);
        Map<Long,Long> reserved=new HashMap<>();
        if(release) {
            for(Map<String,Object> p:previous(company,sku,relation,"LOCK")) reserved.put(number(p,"batchId"),number(p,"changeQuantity"));
            require(reserved.values().stream().mapToLong(Long::longValue).sum()==quantity,"释放数量必须与原单据预占数量一致");
        }
        int remaining=quantity;
        for(Map<String,Object> inventory:warehouses) {
            long cursor=0;int warehouseQuantity=0;
            while(remaining>0) {
                List<Map<String,Object>> batches=jdbc.query("SELECT * FROM wms_inventory_batch WHERE company_code=? AND sku_sn=? AND store_code=? AND id>? AND "+(release?"zp_lock_number":"zp_available_number")+">0 ORDER BY id LIMIT 100 FOR UPDATE",ROW,company,sku,inventory.get("storeCode"),cursor);
                if(batches.isEmpty())break;
                for(Map<String,Object> batch:batches) {
                    cursor=number(batch,"id");
                    int take=(int)Math.min(remaining,release?reserved.getOrDefault(cursor,0L):number(batch,"zpAvailableNumber"));
                    if(take==0)continue;
                    require(!release || number(batch,"zpLockNumber")>=take,"原批次预占库存不足");
                    int delta=release?-take:take;
                    jdbc.update("UPDATE wms_inventory_batch SET zp_available_number=zp_available_number-?,zp_lock_number=zp_lock_number+?,version=version+1 WHERE id=?",delta,delta,batch.get("id"));
                    journal(company,batch,operation,relation,relation,"ZP",delta,release?"按原单据释放预占":"分货预占");
                    warehouseQuantity+=delta;remaining-=take;if(remaining==0)break;
                }
            }
            if(warehouseQuantity!=0)jdbc.update("UPDATE wms_inventory SET zp_available_number=zp_available_number-?,zp_lock_number=zp_lock_number+?,version=version+1 WHERE id=?",warehouseQuantity,warehouseQuantity,inventory.get("id"));
            if(remaining==0)break;
        }
        require(remaining==0,"批次可用库存不足，整笔操作已取消");
        int delta=release?-quantity:quantity;
        jdbc.update("UPDATE oms_inventory SET available_stock=available_stock-?,allocated_stock=allocated_stock+?,version=version+1 WHERE id=?",delta,delta,oms.get("id"));
        return true;
    }

    @Transactional(rollbackFor=Exception.class, timeout=15)
    public boolean receive(WmsInventoryBatch input,String relation) {
        String company=canonical(input.getCompanyCode()),sku=input.getSkuSn(),store=input.getStoreCode();
        require(sku!=null && store!=null && input.getBatchCode()!=null && !input.getBatchCode().trim().isEmpty(),"入库必须包含仓库、SKU 和批次");
        require(relation!=null && !relation.isEmpty() && relation.length()<=64,"入库必须关联来源单据");
        int zp=input.getZpActualNumber(),cp=input.getCpActualNumber();
        require(zp>=0 && cp>=0 && (long)zp+cp>0,"入库数量必须为非负整数且合计大于零");
        Map<String,Object> oms=lockOms(company,sku,true);
        List<Map<String,Object>> prior=previous(company,sku,relation,"RECEIVE");
        for(Map<String,Object> p:prior) if(input.getBatchCode().equals(p.get("batchCode")) && store.equals(p.get("storeCode"))) {
            require(number(p,"newZpActualNumber")-number(p,"oldZpActualNumber")==zp && number(p,"newCpActualNumber")-number(p,"oldCpActualNumber")==cp,"重复入库回传的数量不一致");return true;
        }
        jdbc.update("INSERT INTO wms_inventory(company_code,sku_sn,store_code,version) VALUES (?,?,?,1) ON DUPLICATE KEY UPDATE id=id",company,sku,store);
        Map<String,Object> inventory=lockWarehouse(company,sku,store);checkConsistent(inventory,company);
        jdbc.update("INSERT INTO wms_inventory_batch(company_code,sku_sn,store_code,batch_code,brand_code,transaction_price,version) VALUES (?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE id=id",company,sku,store,input.getBatchCode(),input.getBrandCode(),input.getTransactionPrice());
        Map<String,Object> batch=jdbc.queryForObject("SELECT * FROM wms_inventory_batch WHERE company_code=? AND sku_sn=? AND store_code=? AND batch_code=? FOR UPDATE",ROW,company,sku,store,input.getBatchCode());
        jdbc.update("UPDATE wms_inventory_batch SET zp_actual_number=zp_actual_number+?,zp_available_number=zp_available_number+?,cp_actual_number=cp_actual_number+?,cp_available_number=cp_available_number+?,version=version+1 WHERE id=?",zp,zp,cp,cp,batch.get("id"));
        jdbc.update("UPDATE wms_inventory SET zp_actual_number=zp_actual_number+?,zp_available_number=zp_available_number+?,cp_actual_number=cp_actual_number+?,cp_available_number=cp_available_number+?,version=version+1 WHERE id=?",zp,zp,cp,cp,inventory.get("id"));
        jdbc.update("UPDATE oms_inventory SET total_stock=total_stock+?,available_stock=available_stock+?,version=version+1 WHERE id=?",Math.addExact(zp,cp),Math.addExact(zp,cp),oms.get("id"));
        journal(company,batch,"RECEIVE",relation,relation,"",Math.addExact(zp,cp),"入库回传");
        return true;
    }
}
