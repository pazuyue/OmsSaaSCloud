package com.oms.goods.service.goods.impl;

import com.oms.goods.service.goods.GiftStrategyReferenceClient;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;

/** Configuration protection only. Never called by order conversion or gift quota reservation. */
@Service public class GoodsGiftProtection {
    @Resource private JdbcTemplate jdbc;
    @Resource private GiftStrategyReferenceClient orders;
    static String text(Object value){return value==null?"":String.valueOf(value);}
    static long number(Object value){return value==null?0:Long.parseLong(text(value));}
    static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value){require(value instanceof List,"赠品引用检查结果不完整，请稍后重试");return (List<Map<String,Object>>)value;}
    void lock(String company){jdbc.update("INSERT INTO goods_master_lock(company_code) VALUES (?) ON DUPLICATE KEY UPDATE company_code=VALUES(company_code)",company);}
    static List<String> skus(Object value){require(value instanceof List,"请选择赠品SKU");Set<String> result=new LinkedHashSet<>();for(Object v:(List<?>)value){String sku=text(v).trim();require(!sku.isEmpty()&&sku.length()<=128,"赠品SKU无效");result.add(sku);}require(!result.isEmpty()&&result.size()<=200,"请选择1至200种赠品SKU");return new ArrayList<>(result);}

    @Transactional(rollbackFor=Exception.class)
    public boolean protect(String company,Map<String,Object> input){
        long group=number(input.get("group_id"));String token=text(input.get("request_token")),name=text(input.get("group_name"));List<String> skus=skus(input.get("skus"));
        require(group>0&&token.matches("[a-f0-9]{32}")&&!name.isEmpty()&&name.length()<=100,"赠品策略保护请求无效");lock(company);
        List<Object> args=new ArrayList<>();args.add(company);args.addAll(skus);
        Map<String,Map<String,Object>> found=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT g.sku_sn,g.is_gift,g.is_fd,g.is_package,b.inventory_mode,b.enabled bundle_enabled,b.revision bundle_revision,l.revision lucky_revision,JSON_EXTRACT(l.published_json,'$.enabled') lucky_enabled FROM goods_sku_sn_info g LEFT JOIN goods_bundle b ON b.company_code=? AND b.sku_sn=g.sku_sn LEFT JOIN goods_lucky_bag l ON l.company_code=UPPER(g.company_code) AND l.sku_sn=g.sku_sn WHERE UPPER(g.company_code)=? AND g.sku_sn IN ("+marks(skus.size())+")",withCompany(args,company))){String sku=text(row.get("sku_sn"));require(!found.containsKey(sku),"赠品SKU不唯一："+sku);found.put(sku,row);}
        List<Object[]> inserts=new ArrayList<>();for(String sku:skus){Map<String,Object> row=found.get(sku);require(row!=null,"赠品商品不存在："+sku);require(number(row.get("is_gift"))==1,"商品未勾选赠品，请先到商品资料设置："+sku);require(!(number(row.get("is_fd"))==1&&number(row.get("is_package"))==1),"商品形态冲突："+sku);require(number(row.get("is_package"))==0||number(row.get("bundle_revision"))>0&&number(row.get("bundle_enabled"))==1,"赠品套装组成未发布或已停用："+sku);require(number(row.get("is_fd"))==0||number(row.get("lucky_revision"))>0&&number(row.get("lucky_enabled"))==1,"赠品福袋规则未发布或已停用："+sku);inserts.add(new Object[]{company,group,token,sku,name});}
        jdbc.batchUpdate("INSERT INTO goods_gift_strategy_ref(company_code,group_id,request_token,sku_sn,group_name) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE group_name=VALUES(group_name)",inserts);return true;
    }
    private Object[] withCompany(List<Object> args,String company){List<Object> all=new ArrayList<>();all.add(company);all.addAll(args);return all.toArray();}

    public static final class Inspection {
        final List<Map<String,Object>> obsolete,references;
        Inspection(List<Map<String,Object>> obsolete,List<Map<String,Object>> references){this.obsolete=obsolete;this.references=references;}
    }
    /** Suspend a caller's master-data transaction: never wait for order locks while holding the goods mutex. */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Inspection inspect(String company,List<String> skus){return inspectNow(company,skus,null);}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Inspection inspectIds(String company,List<Long> ids){
        require(!ids.isEmpty()&&ids.size()<=100,"每次请选择1至100个商品");List<Object> args=new ArrayList<>();args.add(company);args.addAll(ids);
        List<String> skus=jdbc.queryForList("SELECT sku_sn FROM goods_sku_sn_info WHERE UPPER(company_code)=? AND id IN ("+marks(ids.size())+")",String.class,args.toArray());require(skus.size()==ids.size(),"商品不存在或不属于当前公司");return inspectNow(company,skus,null);
    }
    private Inspection inspectNow(String company,List<String> skus,Long group){
        List<Object> args=new ArrayList<>();args.add(company);String where="company_code=?";
        if(group!=null){where+=" AND group_id=?";args.add(group);}else {require(!skus.isEmpty()&&skus.size()<=200,"赠品引用查询范围无效");where+=" AND sku_sn IN ("+marks(skus.size())+")";args.addAll(skus);}
        List<Map<String,Object>> held=jdbc.queryForList("SELECT group_id,request_token,sku_sn,group_name FROM goods_gift_strategy_ref WHERE "+where,args.toArray());
        Set<Long> ids=new TreeSet<>();for(Map<String,Object> row:held)ids.add(number(row.get("group_id")));if(group!=null&&held.isEmpty())return new Inspection(Collections.emptyList(),Collections.emptyList());
        R<Map<String,Object>> response;try{Map<String,Object> query=new HashMap<>();query.put("group_ids",new ArrayList<>(ids));query.put("skus",skus);response=orders.inspect(company,query,SecurityConstants.INNER);}catch(Exception error){throw new IllegalArgumentException("赠品策略引用检查暂时不可用，请稍后重试");}
        require(response!=null&&response.getCode()==200&&response.getData()!=null,"赠品策略引用检查失败，请稍后重试");
        Map<Long,Map<String,Object>> states=new HashMap<>();for(Map<String,Object> state:rows(response.getData().get("states")))states.put(number(state.get("group_id")),state);require(states.keySet().containsAll(ids),"赠品引用检查结果不完整，请稍后重试");
        List<Map<String,Object>> obsolete=new ArrayList<>();for(Map<String,Object> row:held){Map<String,Object> state=states.get(number(row.get("group_id")));if(number(state.get("enabled"))!=1||!text(row.get("request_token")).equals(text(state.get("request_token"))))obsolete.add(row);}
        return new Inspection(obsolete,rows(response.getData().get("references")));
    }
    void clean(String company,Inspection inspection){
        List<Object[]> deletes=new ArrayList<>();for(Map<String,Object> row:inspection.obsolete)deletes.add(new Object[]{company,row.get("group_id"),row.get("request_token"),row.get("sku_sn")});
        if(!deletes.isEmpty())jdbc.batchUpdate("DELETE FROM goods_gift_strategy_ref WHERE company_code=? AND group_id=? AND request_token=? AND sku_sn=?",deletes);
    }
    public void requireUnused(String company,List<String> skus,Inspection inspection){
        clean(company,inspection);List<Map<String,Object>> references=current(company,skus);references.addAll(inspection.references);
        if(!references.isEmpty()){Set<String> names=new LinkedHashSet<>();for(Map<String,Object> row:references)names.add(text(row.get("sku_sn"))+" → "+text(row.get("group_name")));throw new IllegalArgumentException("商品被启用或正在保存的赠品策略使用，请先替换赠品或停用策略："+String.join("；",names));}
    }
    private List<Map<String,Object>> current(String company,List<String> skus){List<Object> args=new ArrayList<>();args.add(company);args.addAll(skus);return jdbc.queryForList("SELECT group_id,group_name,sku_sn FROM goods_gift_strategy_ref WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+") ORDER BY group_id LIMIT 200 FOR UPDATE",args.toArray());}
    @Transactional(rollbackFor=Exception.class)
    public boolean refresh(String company,long group){require(group>0,"策略组编号无效");Inspection inspection=inspectNow(company,Collections.emptyList(),group);lock(company);clean(company,inspection);return true;}
    @Transactional(rollbackFor=Exception.class)
    public List<Map<String,Object>> references(String company,long id){
        List<String> found=jdbc.queryForList("SELECT sku_sn FROM goods_sku_sn_info WHERE UPPER(company_code)=? AND id=?",String.class,company,id);require(found.size()==1,"商品不存在或不属于当前公司");Inspection inspection=inspectNow(company,found,null);lock(company);clean(company,inspection);
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> row:current(company,found))result.put(number(row.get("group_id")),row);for(Map<String,Object> row:inspection.references)result.put(number(row.get("group_id")),row);return new ArrayList<>(result.values());
    }
}
