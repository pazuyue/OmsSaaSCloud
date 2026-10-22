package com.oms.goods.service.goods.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.lucky.LuckyBagRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.common.lucky.LuckyBagRules.*;

/** Master-data configuration only; physical and channel balances belong to inventory. */
@Service public class GoodsLuckyBagService {
    @Resource JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();
    private String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("福袋规则无法保存");}}
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object raw){try{return JSON.readValue(raw.toString(),Map.class);}catch(Exception e){throw new IllegalArgumentException("福袋规则无法读取");}}
    private Map<String,Object> parent(String c,long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,sku_sn,goods_name,is_fd,is_package FROM goods_sku_sn_info WHERE UPPER(company_code)=? AND id=?",c,id);require(rows.size()==1,"商品不存在或不属于本公司");Map<String,Object> g=rows.get(0);require(number(g,"is_fd")==1&&number(g,"is_package")==0,"请选择福袋商品，福袋与固定套装不可同时启用");return g;}
    public Map<String,Object> detail(String c,long id){Map<String,Object> g=parent(c,id);Map<String,Object> r=map("sku_sn",g.get("sku_sn"),"goods_name",g.get("goods_name"),"revision",0,"draft_revision",0,"draft",map("enabled",1,"mode","RANDOM","selection","WEIGHTED","max_cost",0,"preset_quantity",0,"groups",Collections.emptyList()),"published",null);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM goods_lucky_bag WHERE company_code=? AND sku_sn=?",c,g.get("sku_sn"));if(!rows.isEmpty()){Map<String,Object> row=rows.get(0);r.put("revision",row.get("revision"));r.put("draft_revision",row.get("draft_revision"));r.put("draft",object(row.get("draft_json")));if(row.get("published_json")!=null)r.put("published",object(row.get("published_json")));}return r;}
    @Transactional(rollbackFor=Exception.class) public Map<String,Object> save(String c,long id,Map<String,Object> input,String actor,boolean publish){
        Map<String,Object> draft=publish?normalize(input):com.oms.common.lucky.LuckyBagRules.draft(input);jdbc.update("INSERT IGNORE INTO goods_master_lock(company_code) VALUES (?)",c);jdbc.queryForList("SELECT company_code FROM goods_master_lock WHERE company_code=? FOR UPDATE",c);
        Map<String,Object> g=parent(c,id);String sku=text(g,"sku_sn");List<String> candidates=skus(draft);require(!candidates.contains(sku),"福袋不能选择自身作为组件");
        List<Object> args=new ArrayList<>();args.add(c);args.addAll(candidates);Set<String> found=new HashSet<>();Map<String,String> names=new HashMap<>();for(Map<String,Object> child:(candidates.isEmpty()?Collections.<Map<String,Object>>emptyList():jdbc.queryForList("SELECT sku_sn,goods_name,is_fd,is_package FROM goods_sku_sn_info WHERE UPPER(company_code)=? AND sku_sn IN ("+String.join(",",Collections.nCopies(candidates.size(),"?"))+")",args.toArray()))){require(found.add(text(child,"sku_sn"))&&number(child,"is_fd")==0&&number(child,"is_package")==0,"福袋候选须为本公司唯一普通SKU："+text(child,"sku_sn"));names.put(text(child,"sku_sn"),text(child,"goods_name"));}require(found.size()==candidates.size(),"福袋包含不存在的候选SKU");for(Map<String,Object> group:rows(draft.get("groups")))for(Map<String,Object> item:rows(group.get("candidates")))item.put("goods_name",names.get(text(item,"sku_sn")));
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM goods_lucky_bag WHERE company_code=? AND sku_sn=? FOR UPDATE",c,sku);Map<String,Object> old=rows.isEmpty()?map("revision",0,"draft_revision",0):rows.get(0);require(number(old,"revision")==number(input,"revision")&&number(old,"draft_revision")==number(input,"draft_revision"),"福袋规则已变化，请刷新后重试");long revision=number(old,"revision")+(publish?1:0),drev=number(old,"draft_revision")+1;
        if(publish){draft.put("revision",revision);draft.put("sku_sn",sku);draft.put("goods_name",g.get("goods_name"));}
        jdbc.update("INSERT INTO goods_lucky_bag(company_code,sku_sn,revision,draft_revision,draft_json,published_json,updated_by) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),draft_revision=VALUES(draft_revision),draft_json=VALUES(draft_json),published_json=COALESCE(VALUES(published_json),published_json),updated_by=VALUES(updated_by)",c,sku,revision,drev,encode(draft),publish?encode(draft):null,actor);
        if(publish){jdbc.update("INSERT INTO goods_lucky_bag_version(company_code,sku_sn,revision,definition_json,actor) VALUES (?,?,?,?,?)",c,sku,revision,encode(draft),actor);for(String child:candidates)jdbc.update("INSERT IGNORE INTO goods_lucky_bag_component(company_code,bag_sku,component_sku) VALUES (?,?,?)",c,sku,child);}
        return detail(c,id);
    }
    public void attach(String c,List<Map<String,Object>> products){List<String> bags=new ArrayList<>();for(Map<String,Object> g:products)if(number(g,"is_fd")==1)bags.add(text(g,"sku_sn"));if(bags.isEmpty())return;List<Object> args=new ArrayList<>();args.add(c);args.addAll(bags);Map<String,Map<String,Object>> bySku=new HashMap<>();for(Map<String,Object> row:jdbc.queryForList("SELECT sku_sn,published_json FROM goods_lucky_bag WHERE company_code=? AND published_json IS NOT NULL AND sku_sn IN ("+String.join(",",Collections.nCopies(bags.size(),"?"))+")",args.toArray()))bySku.put(text(row,"sku_sn"),object(row.get("published_json")));for(Map<String,Object> g:products)if(bySku.containsKey(text(g,"sku_sn")))g.put("lucky_bag",bySku.get(text(g,"sku_sn")));}
}
