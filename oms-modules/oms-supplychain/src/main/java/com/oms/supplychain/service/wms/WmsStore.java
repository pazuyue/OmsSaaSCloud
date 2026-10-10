package com.oms.supplychain.service.wms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.sql.*;
import java.util.*;

@Component
public class WmsStore {
    @Resource public JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactionManager;
    public final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    public TransactionTemplate tx(){TransactionTemplate t=new TransactionTemplate(transactionManager);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}
    public Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);require(rows.size()==1,"对接记录不存在或不属于当前公司");return rows.get(0);}
    public long insert(String table,Map<String,Object> fields){GeneratedKeyHolder key=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement ps=c.prepareStatement("INSERT INTO "+table+" ("+String.join(",",fields.keySet())+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")",Statement.RETURN_GENERATED_KEYS);int i=1;for(Object v:fields.values())ps.setObject(i++,v);return ps;},key);return key.getKey().longValue();}
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法编码仓库数据",e);}}
    public static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    public static String text(Object v){return v==null?"":v.toString().trim();}
    public static long id(Object v){try{return Long.parseLong(text(v));}catch(Exception e){throw new IllegalArgumentException("编号或数量格式错误");}}
    public static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    public static String required(Map<String,Object> m,String field,int max){String s=text(m.get(field));require(!s.isEmpty()&&s.length()<=max,"请填写有效的 "+field);return s;}
}
