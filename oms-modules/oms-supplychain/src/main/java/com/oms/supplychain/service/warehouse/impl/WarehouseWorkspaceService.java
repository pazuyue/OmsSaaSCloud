package com.oms.supplychain.service.warehouse.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.supplychain.model.dto.warehouse.*;
import com.oms.supplychain.model.entity.warehouse.WmsRealStoreInfo;
import com.oms.supplychain.service.warehouse.WarehouseReferenceClient;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DuplicateKeyException;
import javax.annotation.Resource;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Company-scoped warehouse master data. Virtual warehouse ownership is immutable. */
@Service
public class WarehouseWorkspaceService {
    @Resource private JdbcTemplate jdbc;
    @Resource private WarehouseReferenceClient references;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);

    private String company(String value) {
        if(value==null || value.trim().isEmpty()) throw bad("请先选择登录公司");
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
    private String table(String kind) {
        switch(kind) {case "owner":return "owner_info";case "realStore":return "wms_real_store_info";case "simulationStore":return "wms_simulation_store_info";case "ownerWarehouse":return "owner_warehouse";default:throw bad("未知仓库资料类型");}
    }
    private String base(String kind) {
        if("owner".equals(kind))return "SELECT o.*, (SELECT COUNT(*) FROM owner_warehouse r WHERE r.owner_id=o.id AND UPPER(r.company_code)=UPPER(o.company_code)) warehouse_count, (SELECT COUNT(*) FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id WHERE r.owner_id=o.id AND UPPER(v.company_code)=UPPER(o.company_code)) virtual_count FROM owner_info o";
        if("realStore".equals(kind))return "SELECT w.*, (SELECT COUNT(*) FROM owner_warehouse r WHERE r.real_store_id=w.id AND UPPER(r.company_code)=UPPER(w.company_code)) owner_count, (SELECT COUNT(*) FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.id=v.owner_warehouse_id WHERE r.real_store_id=w.id AND UPPER(v.company_code)=UPPER(w.company_code)) virtual_count FROM wms_real_store_info w";
        String joins=" LEFT JOIN owner_info o ON o.id=r.owner_id AND UPPER(o.company_code)=UPPER(r.company_code) LEFT JOIN wms_real_store_info w ON w.id=r.real_store_id AND UPPER(w.company_code)=UPPER(r.company_code)";
        if("ownerWarehouse".equals(kind))return "SELECT r.*,o.owner_code,o.owner_name,o.is_enable owner_status,w.real_store_code,w.wms_name,w.status warehouse_status,CASE WHEN o.id IS NOT NULL AND w.id IS NOT NULL THEN 1 ELSE 0 END relation_valid, CASE WHEN r.status=2 AND o.is_enable=2 AND w.status=2 THEN 1 ELSE 0 END effective_enabled, (SELECT COUNT(*) FROM wms_simulation_store_info v WHERE v.owner_warehouse_id=r.id AND UPPER(v.company_code)=UPPER(r.company_code)) virtual_count FROM owner_warehouse r"+joins;
        if("simulationStore".equals(kind))return "SELECT v.id,v.status,v.wms_simulation_code,v.wms_simulation_name,v.owner_warehouse_id,v.inbound_mode,v.outbound_mode,v.connection_id,v.external_warehouse,v.external_owner,v.company_code,v.create_time,v.modify_time,o.id owner_id,o.owner_code,o.owner_name,w.id real_store_id,w.real_store_code,w.wms_name,r.status relation_status,o.is_enable owner_status,w.status warehouse_status, CASE WHEN r.id IS NOT NULL AND o.id IS NOT NULL AND w.id IS NOT NULL THEN 1 ELSE 0 END relation_valid, CASE WHEN v.status=2 AND r.status=2 AND o.is_enable=2 AND w.status=2 AND v.inbound_mode IN (1,2) AND v.outbound_mode IN (1,2) AND ((v.inbound_mode=2 AND v.outbound_mode=2) OR (v.external_warehouse<>'' AND v.external_owner<>'' AND EXISTS(SELECT 1 FROM wms_connection c WHERE c.id=v.connection_id AND UPPER(c.company_code)=UPPER(v.company_code) AND c.enabled=1))) THEN 1 ELSE 0 END effective_enabled FROM wms_simulation_store_info v LEFT JOIN owner_warehouse r ON r.id=v.owner_warehouse_id AND UPPER(r.company_code)=UPPER(v.company_code)"+joins;
        throw bad("未知仓库资料类型");
    }
    private Map<String,Object> camel(Map<String,Object> source) {
        Map<String,Object> target=new LinkedHashMap<>();
        source.forEach((key,value)->{StringBuilder name=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_'){upper=true;continue;}name.append(upper?Character.toUpperCase(c):c);upper=false;} target.put(name.toString(),value);});
        return target;
    }
    private List<Map<String,Object>> rows(String sql,Object... args){return jdbc.queryForList(sql,args).stream().map(this::camel).collect(Collectors.toList());}
    private long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    /** Lock existing rows only. Dependency readers share locks; editors/deleters take exclusive locks. */
    private void lockRow(String company,String kind,long id,boolean exclusive){
        if(jdbc.queryForList("SELECT id FROM "+table(kind)+" WHERE id=? AND UPPER(company_code)=?"+(exclusive?" FOR UPDATE":" LOCK IN SHARE MODE"),Long.class,id,company).size()!=1)
            throw bad("资料不存在或不属于当前公司");
    }
    /** Always acquire parent locks in owner -> real warehouse -> association -> virtual order. */
    private void lockRelation(String company,long relationId){
        Map<String,Object> relation=detail(company,"ownerWarehouse",relationId);
        lockRow(company,"owner",number(relation.get("ownerId"),0),false);
        lockRow(company,"realStore",number(relation.get("realStoreId"),0),false);
        lockRow(company,"ownerWarehouse",relationId,false);
    }

    public TableDataInfo page(String supplied,String kind,Map<String,Object> filters,int page,int size) {
        String company=company(supplied); List<Object> args=new ArrayList<>();args.add(company);
        String where=where(kind,filters,args);String sql=" FROM ("+base(kind)+") x WHERE UPPER(x.company_code)=?"+where;
        long total=count("SELECT COUNT(*)"+sql,args.toArray());
        size=Math.max(1,Math.min(size,100)); page=Math.max(1,page);
        args.add(size); args.add((long)(page-1)*size);
        TableDataInfo result=new TableDataInfo();result.setCode(200);result.setMsg("查询成功");result.setRows(rows("SELECT x.*"+sql+" ORDER BY x.modify_time DESC,x.id DESC LIMIT ? OFFSET ?",args.toArray()));result.setTotal(total);return result;
    }
    public List<Map<String,Object>> list(String supplied,String kind,Map<String,Object> filters) {
        List<Object> args=new ArrayList<>();args.add(company(supplied));
        return rows("SELECT x.* FROM ("+base(kind)+") x WHERE UPPER(x.company_code)=?"+where(kind,filters,args)+" ORDER BY x.modify_time DESC,x.id DESC",args.toArray());
    }
    private String where(String kind,Map<String,Object> q,List<Object> args) {
        StringBuilder sql=new StringBuilder();
        Map<String,String> allowed=new LinkedHashMap<>();allowed.put("id","id");
        if(kind.equals("owner")){allowed.put("ownerCode","owner_code");allowed.put("ownerName","owner_name");allowed.put("isEnable","is_enable");allowed.put("isSync","is_sync");}
        if(kind.equals("realStore")){for(String field:Arrays.asList("status","wmsType","realStoreCode","wmsName","director","mobilePhone","province","city","district","address"))allowed.put(field,snake(field));}
        if(kind.equals("simulationStore") || kind.equals("ownerWarehouse")){for(String field:Arrays.asList("status","ownerId","realStoreId","ownerCode","ownerName","realStoreCode","wmsName","effectiveEnabled","relationValid"))allowed.put(field,snake(field));}
        if(kind.equals("simulationStore")){allowed.put("wmsSimulationCode","wms_simulation_code");allowed.put("wmsSimulationName","wms_simulation_name");allowed.put("ownerWarehouseId","owner_warehouse_id");allowed.put("inboundMode","inbound_mode");}
        allowed.forEach((key,column)->{if(present(q.get(key))){boolean like=key.endsWith("Name") || Arrays.asList("director","address").contains(key);sql.append(" AND x.").append(column).append(like?" LIKE ?":"=?");args.add(like?"%"+q.get(key).toString().trim()+"%":q.get(key));}});
        if(kind.equals("owner") && present(q.get("realStoreId"))){sql.append(" AND EXISTS(SELECT 1 FROM owner_warehouse r WHERE r.owner_id=x.id AND r.real_store_id=? AND UPPER(r.company_code)=UPPER(x.company_code))");args.add(q.get("realStoreId"));}
        if(kind.equals("realStore") && present(q.get("ownerId"))){sql.append(" AND EXISTS(SELECT 1 FROM owner_warehouse r WHERE r.real_store_id=x.id AND r.owner_id=? AND UPPER(r.company_code)=UPPER(x.company_code))");args.add(q.get("ownerId"));}
        try {
            Object start=q.get("beginTime"),end=q.get("endTime");
            if(present(q.get("modifyTime")))start=end=q.get("modifyTime");
            if(present(start)){sql.append(" AND x.modify_time>=?");args.add(LocalDate.parse(start.toString()).atStartOfDay());}
            if(present(end)){sql.append(" AND x.modify_time<?");args.add(LocalDate.parse(end.toString()).plusDays(1).atStartOfDay());}
            if(present(start) && present(end) && LocalDate.parse(start.toString()).isAfter(LocalDate.parse(end.toString())))throw bad("开始日期不能晚于结束日期");
        } catch(java.time.format.DateTimeParseException e){throw bad("日期格式应为 yyyy-MM-dd");}
        return sql.toString();
    }
    public Map<String,Object> detail(String supplied,String kind,long id) {
        List<Map<String,Object>> found=rows("SELECT x.* FROM ("+base(kind)+") x WHERE UPPER(x.company_code)=? AND x.id=?",company(supplied),id);
        if(found.isEmpty())throw bad("资料不存在或不属于当前公司");
        Map<String,Object> row=found.get(0);
        if(kind.equals("owner"))row.put("warehouses",list(supplied,"ownerWarehouse",map("ownerId",id)));
        if(kind.equals("realStore"))row.put("owners",list(supplied,"ownerWarehouse",map("realStoreId",id)));
        return row;
    }
    public Map<String,Object> options(String company) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String kind:Arrays.asList("owner","realStore","ownerWarehouse"))result.put(kind,list(company,kind,Collections.emptyMap()));
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public long save(String supplied,String kind,Map<String,Object> input) {
        String company=company(supplied);table(kind);
        long id=number(input.get("id"),0);
        if(kind.equals("ownerWarehouse")){
            lockRow(company,"owner",number(input.get("ownerId"),0),false);
            lockRow(company,"realStore",number(input.get("realStoreId"),0),false);
        }else if(kind.equals("simulationStore"))lockRelation(company,number(input.get("ownerWarehouseId"),0));
        if(id!=0)lockRow(company,kind,id,true);
        Map<String,Object> old=id==0?null:detail(company,kind,id);
        LinkedHashMap<String,Object> fields=new LinkedHashMap<>();
        if(kind.equals("owner")) {
            fields.put("owner_code",code(company,kind,input,old,"ownerCode"));
            fields.put("owner_name",required(input,"ownerName","货主名称",100));
            fields.put("is_enable",state(input,"isEnable",old));
            fields.put("is_sync",1); // Synchronization is configured per owner/warehouse association.
        } else if(kind.equals("realStore")) {
            fields.put("real_store_code",code(company,kind,input,old,"realStoreCode"));
            fields.put("wms_name",required(input,"wmsName","实体仓库名称",100));fields.put("status",state(input,"status",old));
            fields.put("wms_type",choice(input,"wmsType",1,2,3));
            for(String key:Arrays.asList("director","mobilePhone","province","city","district","address"))fields.put(snake(key),optional(input,key,255));
            String phone=String.valueOf(fields.get("mobile_phone"));if(!phone.isEmpty() && !phone.matches("[0-9+()\\- #]{3,40}"))throw bad("联系电话格式不正确");
        } else if(kind.equals("ownerWarehouse")) {
            long ownerId=number(input.get("ownerId"),0),realId=number(input.get("realStoreId"),0);
            Map<String,Object> owner=detail(company,"owner",ownerId);detail(company,"realStore",realId);
            if(old!=null && (number(old.get("ownerId"),0)!=ownerId || number(old.get("realStoreId"),0)!=realId))throw bad("关联的货主与实体仓库创建后不能更换");
            if(count("SELECT COUNT(*) FROM owner_warehouse WHERE UPPER(company_code)=? AND owner_id=? AND real_store_id=? AND id<>?",company,ownerId,realId,id)>0)throw bad("该货主已关联此实体仓库");
            fields.put("owner_id",ownerId);fields.put("real_store_id",realId);fields.put("status",state(input,"status",old));
        } else {
            fields.put("wms_simulation_code",code(company,kind,input,old,"wmsSimulationCode"));
            fields.put("wms_simulation_name",required(input,"wmsSimulationName","虚拟仓库名称",100));
            long relationId=number(input.get("ownerWarehouseId"),0);
            if(relationId==0)throw bad("请选择明确的货主与实体仓库关联");
            Map<String,Object> relation=detail(company,"ownerWarehouse",relationId);
            if(old!=null && number(old.get("ownerWarehouseId"),0)!=relationId)throw bad("虚仓的货主与实体仓库归属创建后不能更换，请通过调拨处理");
            int state=state(input,"status",old);
            if(state==2 && number(relation.get("effectiveEnabled"),0)!=1)throw bad("关联、货主和实体仓库均启用后才能启用虚仓");
            fields.put("status",state);fields.put("owner_warehouse_id",relationId);
            int inbound=choice(input,"inboundMode",1,2),outbound=choice(input,"outboundMode",1,2);
            fields.put("inbound_mode",inbound);fields.put("outbound_mode",outbound);
            Long connection=null;String externalWarehouse="",externalOwner="";
            if(inbound==1 || outbound==1){
                connection=number(input.get("connectionId"),0);
                if(connection==0 || count("SELECT COUNT(*) FROM wms_connection WHERE id=? AND company_code=?",connection,company)!=1)throw bad("请选择当前公司的仓库对接配置");
                externalWarehouse=required(input,"externalWarehouse","外部仓库编码",100);
                externalOwner=required(input,"externalOwner","WMS货主编码",100);
            }
            fields.put("connection_id",connection);fields.put("external_warehouse",externalWarehouse);fields.put("external_owner",externalOwner);

            fields.put("owner_code",relation.get("ownerCode"));fields.put("owner_name",relation.get("ownerName"));
        }
        fields.put("company_code",company);fields.put("modify_time",new Timestamp(System.currentTimeMillis()));
        try {
        if(id==0){fields.put("create_time",new Timestamp(System.currentTimeMillis()));return insert(table(kind),fields);}
        List<Object> args=new ArrayList<>(fields.values());args.add(id);args.add(company);
        jdbc.update("UPDATE "+table(kind)+" SET "+fields.keySet().stream().map(k->k+"=?").collect(Collectors.joining(","))+" WHERE id=? AND UPPER(company_code)=?",args.toArray());
        return id;
        }catch(DuplicateKeyException e){throw bad("编码或货主实仓关联已存在，请刷新后重试");}
    }
    private String code(String company,String kind,Map<String,Object> input,Map<String,Object> old,String key) {
        String value=required(input,key,"编码",64);if(value.matches(".*\\s.*"))throw bad("编码不能包含空白字符");
        if(old!=null && !value.equals(old.get(key)))throw bad("编码创建后不能修改");
        if(count("SELECT COUNT(*) FROM "+table(kind)+" WHERE UPPER(company_code)=? AND "+snake(key)+"=? AND id<>?",company,value,old==null?0:old.get("id"))>0)throw bad("当前公司内编码已存在");
        return value;
    }
    private long insert(String table,LinkedHashMap<String,Object> fields) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();List<Object> args=new ArrayList<>(fields.values());
        jdbc.update(connection->{PreparedStatement ps=connection.prepareStatement("INSERT INTO "+table+" ("+String.join(",",fields.keySet())+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")",Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.size();i++)ps.setObject(i+1,args.get(i));return ps;},key);
        return key.getKey().longValue();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void delete(String supplied,String kind,List<Long> ids) {
        String company=company(supplied);if(ids==null || ids.isEmpty() || ids.size()>100)throw bad("每次请选择 1 至 100 条资料");
        List<Long> ordered=ids.stream().distinct().sorted().collect(Collectors.toList());
        for(long id:ordered)lockRow(company,kind,id,true);
        List<Map<String,Object>> selected=ordered.stream().map(id->detail(company,kind,id)).collect(Collectors.toList());
        for(Map<String,Object> row:selected) {
            long id=number(row.get("id"),0);
            if(kind.equals("owner")){
                if(number(row.get("warehouseCount"),0)>0)throw bad("货主已关联实体仓库，不能删除，请停用；停用的关联也需保留");
            }
            if(kind.equals("realStore")) {
                if(number(row.get("ownerCount"),0)>0)throw bad("实体仓库已关联货主，不能删除，请停用；停用的关联也需保留");
                if(localRef(company,"wms_tickets","real_store_code",row.get("realStoreCode").toString()))throw bad("实体仓库存在出入库记录（含已完成、已作废），不能删除，请停用");
            }
            if(kind.equals("ownerWarehouse") && number(row.get("virtualCount"),0)>0)throw bad("该关联下存在虚拟仓库，不能删除，可停用关联");
            if(kind.equals("simulationStore"))for(String t:Arrays.asList("po_info","no_tickets","wms_tickets"))if(localRef(company,t,"wms_simulation_code",row.get("wmsSimulationCode").toString()))throw bad("虚仓存在"+(t.equals("po_info")?"采购单":t.equals("no_tickets")?"到货单":"出入库单")+"引用（含草稿、已完成、已作废），不能删除，请停用");
        }
        if(kind.equals("simulationStore")) {
            List<String> codes=selected.stream().map(r->r.get("wmsSimulationCode").toString()).collect(Collectors.toList());
            R<List<String>> result;
            try{result=references.references(company,codes,"inner");}catch(Exception e){throw bad("库存引用检查暂不可用，请稍后重试");}
            if(result==null || result.getCode()!=200 || result.getData()==null)throw bad("库存引用检查暂不可用，请稍后重试");
            if(!result.getData().isEmpty())throw bad("虚仓存在库存、历史流水或分货规则引用，不能删除："+String.join("、",result.getData()));
        }
        for(Map<String,Object> row:selected)jdbc.update("DELETE FROM "+table(kind)+" WHERE id=? AND UPPER(company_code)=?",row.get("id"),company);
    }
    private boolean localRef(String company,String table,String column,String code) {
        if(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table)==0)return false;
        return count("SELECT COUNT(*) FROM "+table+" WHERE UPPER(company_code)=? AND "+column+"=?",company,code)>0;
    }
    /** Document reads retain disabled masters; new documents must pass the whole chain. */
    public SimulationStoreInfoDto resolve(String company,String code,boolean requireEnabled) {
        if(!present(code))throw bad("虚仓编码不能为空");
        List<Map<String,Object>> found=list(company,"simulationStore",map("wmsSimulationCode",code));
        if(found.size()!=1)throw bad("虚仓不存在或编码不唯一："+code);
        Map<String,Object> row=found.get(0);
        if(requireEnabled && TransactionSynchronizationManager.isActualTransactionActive()){
            lockRelation(company(company),number(row.get("ownerWarehouseId"),0));
            lockRow(company(company),"simulationStore",number(row.get("id"),0),false);
            row=detail(company,"simulationStore",number(row.get("id"),0));
        }
        if(number(row.get("relationValid"),0)!=1)throw bad("虚仓的货主与实体仓库关联不完整");
        if(requireEnabled && !Arrays.asList(1L,2L).contains(number(row.get("inboundMode"),0)))throw bad("请先维护虚仓入库执行方式");
        if(requireEnabled && number(row.get("effectiveEnabled"),0)!=1)throw bad("虚仓关联未启用，或执行方式、仓库对接配置不可用，不能创建新业务单据");
        OwnerInfoDto owner=convert(detail(company,"owner",number(row.get("ownerId"),0)),OwnerInfoDto.class);
        WmsRealStoreInfo warehouse=convert(detail(company,"realStore",number(row.get("realStoreId"),0)),WmsRealStoreInfo.class);
        owner.setRealStoreCode(warehouse.getRealStoreCode());owner.setRealStoreInfo(warehouse);
        SimulationStoreInfoDto result=convert(row,SimulationStoreInfoDto.class);result.setOwnerInfo(owner);return result;
    }
    public <T>T convert(Map<String,Object> row,Class<T> type){Map<String,Object> values=new HashMap<>(row);values.replaceAll((k,v)->v instanceof LocalDateTime?Timestamp.valueOf((LocalDateTime)v):v);return mapper.convertValue(values,type);}
    public Map<String,Object> asMap(Object value){return mapper.convertValue(value,Map.class);}
    private static boolean present(Object value){return value!=null && !value.toString().trim().isEmpty();}
    private static String snake(String value){return value.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    public static Map<String,Object> map(String key,Object value){Map<String,Object> result=new HashMap<>();result.put(key,value);return result;}
    private static long number(Object value,long fallback){if(!present(value))return fallback;try{return Long.parseLong(value.toString());}catch(NumberFormatException e){throw bad("编号或状态格式错误");}}
    private static String optional(Map<String,Object> map,String key,int max){String s=map.get(key)==null?"":map.get(key).toString().trim();if(s.length()>max)throw bad(key+"长度不能超过 "+max);return s;}
    private static String required(Map<String,Object> map,String key,String label,int max){String s=optional(map,key,max);if(s.isEmpty())throw bad(label+"不能为空");return s;}
    private static int choice(Map<String,Object> input,String key,int... values){long v=number(input.get(key),-1);for(int allowed:values)if(v==allowed)return allowed;throw bad(key+"选项无效");}
    private static int state(Map<String,Object> input,String key,Map<String,Object> old){if(!present(input.get(key)))return old==null?2:(int)number(old.get(key),1);return choice(input,key,1,2);}
}
