package com.oms.inventory.service.impl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.common.lucky.LuckyBagRules.*;

/** Platform writes are fenced before mutation; unknown or paused exposure never silently expires. */
@Service public class LuckyBagExposure {
    @Resource JdbcTemplate db;
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> update(String company,Map<String,Object> input){
        String bag=text(input,"sku_sn"),action=text(input,"action"),request=text(input,"request_key");long channel=number(input,"channel_id"),group=number(input,"group_id"),quantity=number(input,"quantity"),uncertain=number(input,"uncertain");
        require(!bag.isEmpty()&&bag.length()<=50&&channel>0&&group>0&&request.matches("[A-Za-z0-9_-]{8,64}")&&quantity>=0&&quantity<=Integer.MAX_VALUE&&(uncertain==0||uncertain==1),"福袋平台曝光参数无效");require(Arrays.asList("BEGIN","CONFIRM").contains(action),"福袋平台曝光动作无效");
        require(db.queryForList("SELECT id FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE",company,bag).size()==1,"福袋渠道库存尚未建立");
        if(action.equals("BEGIN"))db.update("INSERT INTO oms_lucky_exposure(company_code,channel_id,bag_sku,group_id,request_key,quantity,uncertain) VALUES (?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE request_key=VALUES(request_key),quantity=GREATEST(quantity,VALUES(quantity)),uncertain=1",company,channel,bag,group,request,quantity);
        else require(db.update("UPDATE oms_lucky_exposure SET quantity=?,uncertain=? WHERE company_code=? AND channel_id=? AND bag_sku=? AND group_id=? AND request_key=?",quantity,uncertain,company,channel,bag,group,request)==1,"平台核对请求已过期，保留原曝光保护");
        return map("state",action.equals("BEGIN")||uncertain==1?"PROTECTED":"CONFIRMED","quantity",quantity);
    }
}
