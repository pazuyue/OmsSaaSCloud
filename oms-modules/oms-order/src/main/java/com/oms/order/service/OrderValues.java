package com.oms.order.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.RowMapper;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class OrderValues {
    private OrderValues(){}
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final RowMapper<Map<String,Object>> ROW=(rs,n)->{
        Map<String,Object> row=new LinkedHashMap<>();ResultSetMetaData meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){Object value=rs.getObject(i);if(value instanceof Timestamp)value=((Timestamp)value).toLocalDateTime().format(TIME);else if(value instanceof LocalDateTime)value=((LocalDateTime)value).format(TIME);row.put(meta.getColumnLabel(i),value);}return row;
    };
    public static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
    public static String text(Map<String,?> row,String key){Object value=row.get(key);return value==null?"":String.valueOf(value);}
    public static long number(Map<String,?> row,String key){String value=text(row,key);return value.isEmpty()?0:Long.parseLong(value);}
    public static String stockReference(Map<String,?> row){String reference=text(row,"stock_reference");return reference.isEmpty()?text(row,"platform_tid"):reference;}
    public static String company(String value){require(value!=null&&value.matches("[A-Za-z0-9_-]{1,50}"),"公司编码无效");return value.toUpperCase(Locale.ROOT);}
    public static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("订单数据无法编码",e);}}
    public static List<String> strings(Object value){try{return JSON.readValue(String.valueOf(value),new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalArgumentException("库存范围配置无效",e);}}
    public static List<String> tradeTypes(Object value){
        List<?> items;
        try{items=value instanceof List?(List<?>)value:JSON.readValue(String.valueOf(value),List.class);}catch(Exception e){throw new IllegalArgumentException("允许转单的交易类型配置无效");}
        require(items!=null&&!items.isEmpty()&&items.size()<=20,"请选择1至20种允许转单的交易类型");
        Set<String> unique=new LinkedHashSet<>();
        for(Object item:items){require(item instanceof String&&((String)item).matches("[A-Za-z0-9_]{1,64}"),"交易类型须为1至64位字母、数字或下划线");unique.add((String)item);}
        return new ArrayList<>(unique);
    }
    public static String hash(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public static String token(){return UUID.randomUUID().toString().replace("-","");}
    public static LocalDateTime time(String value){try{return LocalDateTime.parse(value,TIME);}catch(Exception e){throw new IllegalArgumentException("时间格式应为yyyy-MM-dd HH:mm:ss");}}
    public static <T> T data(R<T> result){if(result==null||!R.isSuccess(result))throw new IllegalStateException(result==null?"关联服务未返回结果":result.getMsg());return result.getData();}
    public static TableDataInfo page(List<?> rows,long total){TableDataInfo result=new TableDataInfo();result.setRows(rows);result.setTotal(total);result.setCode(200);result.setMsg("查询成功");return result;}
    public static int size(int size){return Math.max(1,Math.min(100,size));}
    public static int offset(int page,int size){return (Math.max(1,Math.min(100000,page))-1)*size;}
}
