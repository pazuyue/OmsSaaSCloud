package com.oms.goods.service.goods.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.service.goods.GoodsReferenceClient;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** One set of association rules for all four master pages and both creation paths. */
@Service
public class GoodsWorkspaceService {
    @Resource private JdbcTemplate jdbc;
    @Resource private GoodsReferenceClient references;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private static final String GOODS = "goods_sku_sn_info";
    private static final String[] FIELDS = {"skuSn","goodsSn","barcodeSn","goodsName","categoryCode","colorCode","sizeCode","marketPrice","validity","goodsDesc","isFd","isGift","isPackage","description"};

    public Map<String,Object> map(Object value) { return json.convertValue(value, new TypeReference<Map<String,Object>>(){}); }
    public <T> T convert(Map<String,Object> value, Class<T> type) {
        // MySQL Connector/J can return LocalDateTime for DATETIME. Normalize it
        // before converting entities with java.util.Date fields.
        Map<String,Object> normalized=new LinkedHashMap<>(value);
        normalized.replaceAll((key,item)->item instanceof java.time.LocalDateTime?java.sql.Timestamp.valueOf((java.time.LocalDateTime)item):item);
        return json.copy().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false).convertValue(normalized,type);
    }
    private String table(String kind) {
        if (!Arrays.asList("category","color","size").contains(kind)) throw new IllegalArgumentException("未知基础资料类型");
        return "goods_" + kind;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { return value == null || text(value).isEmpty() ? 0 : Long.parseLong(text(value)); }
    private String snake(String key) { return key.replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT); }
    private Map<String,Object> camel(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        row.forEach((key,value)->{StringBuilder name=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_')upper=true;else{name.append(upper?Character.toUpperCase(c):c);upper=false;}}result.put(name.toString(),value);});
        return result;
    }
    private List<Map<String,Object>> rows(String sql,Object... args) {
        return jdbc.queryForList(sql,args).stream().map(this::camel).collect(Collectors.toList());
    }
    private void lock(String company) {
        // Duplicate-key UPDATE takes an exclusive lock directly. INSERT IGNORE would
        // take a shared lock and two simultaneous upgrades could deadlock.
        jdbc.update("INSERT INTO goods_master_lock(company_code) VALUES (?) ON DUPLICATE KEY UPDATE company_code=VALUES(company_code)",company);
        jdbc.queryForList("SELECT company_code FROM goods_master_lock WHERE company_code=? FOR UPDATE",company);
    }
    private Map<String,Object> owned(String table,String company,long id) {
        List<Map<String,Object>> rows=rows("SELECT * FROM "+table+" WHERE UPPER(company_code)=? AND id=?",company,id);
        if(rows.isEmpty())throw new IllegalArgumentException("记录不存在或不属于当前公司");
        return rows.get(0);
    }
    public TableDataInfo page(List<Map<String,Object>> rows,long total) {
        TableDataInfo result=new TableDataInfo();result.setCode(200);result.setMsg("查询成功");result.setRows(rows);result.setTotal(total);return result;
    }
    private int limit(int pageSize) { return Math.max(1,Math.min(100,pageSize)); }
    private int offset(int pageNum,int size) {return Math.multiplyExact(Math.max(0,Math.min(1000000,pageNum-1)),size);}

    @Transactional(readOnly=true)
    public Map<String,Object> options(String company) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String kind:Arrays.asList("category","color","size"))result.put(kind,masterList(company,kind,Collections.emptyMap()));
        return result;
    }
    private void paths(List<Map<String,Object>> categories) {
        Map<Long,Map<String,Object>> byId=new HashMap<>();categories.forEach(r->byId.put(number(r.get("id")),r));
        for(Map<String,Object> row:categories){List<String> names=new ArrayList<>();Set<Long> seen=new HashSet<>();Map<String,Object> node=row;
            while(node!=null && seen.add(number(node.get("id")))){names.add(0,text(node.get("name")));node=byId.get(number(node.get("pid")));}
            row.put("path",String.join(" / ",names));
        }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> masterList(String company,String kind,Map<String,Object> filter) {
        String name=kind.equals("category")?"name":kind+"Name";
        String fk=kind.equals("category")?"category_code":kind+"_code";
        List<Map<String,Object>> all=rows("SELECT m.*,COALESCE(g.goods_count,0) goods_count FROM "+table(kind)+" m LEFT JOIN (SELECT "+fk+" fk,COUNT(*) goods_count FROM "+GOODS+" WHERE UPPER(company_code)=? GROUP BY "+fk+") g ON g.fk=m.id WHERE UPPER(m.company_code)=? ORDER BY "+(kind.equals("size")?"m.sort_order,":"")+"m.id",company,company);
        if(kind.equals("category")){
            paths(all);
            Map<Long,Map<String,Object>> byId=new HashMap<>();all.forEach(r->{byId.put(number(r.get("id")),r);r.put("directGoodsCount",r.get("goodsCount"));});
            for(Map<String,Object> row:all){long count=number(row.get("directGoodsCount")),pid=number(row.get("pid"));Set<Long> seen=new HashSet<>();
                while(pid>0 && seen.add(pid) && byId.containsKey(pid)){Map<String,Object> parent=byId.get(pid);parent.put("goodsCount",number(parent.get("goodsCount"))+count);pid=number(parent.get("pid"));}
            }
        }
        String term=text(filter.get(name)),code=text(filter.get("out"+Character.toUpperCase(kind.charAt(0))+kind.substring(1)+"Code"));
        Set<Long> keep=new HashSet<>();Map<Long,Map<String,Object>> byId=new HashMap<>();all.forEach(r->byId.put(number(r.get("id")),r));
        for(Map<String,Object> row:all){
            if(!text(row.get(name)).contains(term) || (!code.isEmpty() && !text(row.get("out"+Character.toUpperCase(kind.charAt(0))+kind.substring(1)+"Code")).contains(code)))continue;
            if(kind.equals("category") && !text(filter.get("level")).isEmpty() && number(row.get("level"))!=number(filter.get("level")))continue;
            if(kind.equals("category") && !text(filter.get("pid")).isEmpty() && number(row.get("pid"))!=number(filter.get("pid")))continue;
            Map<String,Object> node=row;while(node!=null && keep.add(number(node.get("id"))))node=kind.equals("category")?byId.get(number(node.get("pid"))):null;
        }
        return all.stream().filter(r->keep.contains(number(r.get("id")))).collect(Collectors.toList());
    }
    @Transactional(readOnly=true)
    public TableDataInfo masterPage(String company,String kind,Map<String,Object> filter,int pageNum,int pageSize) {
        List<Map<String,Object>> all=masterList(company,kind,filter);
        if(kind.equals("category"))return page(all,all.size());
        int from=Math.min(all.size(),offset(pageNum,limit(pageSize))),to=Math.min(all.size(),from+limit(pageSize));
        return page(all.subList(from,to),all.size());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> masterDetail(String company,String kind,long id) {return owned(table(kind),company,id);}
    private void required(Map<String,Object> data,String key,int max,String label) {
        String value=text(data.get(key));if(value.isEmpty())throw new IllegalArgumentException(label+"不能为空");
        if(value.length()>max)throw new IllegalArgumentException(label+"不能超过 "+max+" 个字符");data.put(key,value);
    }
    private void unique(String table,String company,String column,Object value,long exclude,String label,String extra,Object... extraArgs) {
        List<Object> args=new ArrayList<>(Arrays.asList(company,value,exclude));args.addAll(Arrays.asList(extraArgs));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE UPPER(company_code)=? AND "+column+"=? AND id<>?"+extra,Long.class,args.toArray())>0)
            throw new IllegalArgumentException(label+"已存在");
    }
    @Transactional(rollbackFor=Exception.class)
    public long saveMaster(String company,String kind,Map<String,Object> input) {
        lock(company);Map<String,Object> data=new HashMap<>(input);long id=number(data.get("id"));
        if(id<0)throw new IllegalArgumentException("资料 ID 无效");
        if(id>0)owned(table(kind),company,id);
        String name=kind.equals("category")?"name":kind+"Name";required(data,name,kind.equals("category")?100:30,kind.equals("category")?"分类名称":"名称");
        List<String> fields=new ArrayList<>();fields.add(name);
        if(kind.equals("category")){
            long pid=number(data.get("pid"));int level=1;
            if(pid>0){Map<String,Object> parent=owned(table(kind),company,pid);Set<Long> seen=new HashSet<>();Map<String,Object> node=parent;
                while(node!=null){long n=number(node.get("id"));if(n==id || !seen.add(n))throw new IllegalArgumentException("不能选择自身或子分类作为上级分类");long p=number(node.get("pid"));node=p>0?owned(table(kind),company,p):null;}
                level=(int)number(parent.get("level"))+1;
            }
            if(level>3)throw new IllegalArgumentException("分类最多支持三级");
            if(id>0 && number(owned(table(kind),company,id).get("pid"))!=pid){
                if(jdbc.queryForObject("SELECT COUNT(*) FROM goods_category WHERE UPPER(company_code)=? AND pid=?",Long.class,company,id)>0)
                    throw new IllegalArgumentException("含子分类时不能调整上级分类，请先调整子分类");
                if(level!=3 && jdbc.queryForObject("SELECT COUNT(*) FROM "+GOODS+" WHERE UPPER(company_code)=? AND category_code=?",Long.class,company,id)>0)
                    throw new IllegalArgumentException("已关联商品的分类必须保持三级");
            }
            unique(table(kind),company,"name",data.get(name),id,"同级分类"," AND COALESCE(pid,0)=?",pid);
            data.put("pid",pid);data.put("level",level);fields.add("pid");fields.add("level");
        }else{
            String code="out"+Character.toUpperCase(kind.charAt(0))+kind.substring(1)+"Code";required(data,code,50,"外部编码");
            unique(table(kind),company,snake(name),data.get(name),id,"名称","");unique(table(kind),company,snake(code),data.get(code),id,"外部编码","");fields.add(code);
            if(kind.equals("size")){long order=number(data.get("sortOrder"));if(order<0 || order>999999)throw new IllegalArgumentException("排序必须为 0 至 999999 的整数");data.put("sortOrder",order);fields.add("sortOrder");}
        }
        return write(table(kind),company,id,data,fields);
    }
    private long write(String table,String company,long id,Map<String,Object> data,List<String> fields) {
        List<Object> args=fields.stream().map(data::get).collect(Collectors.toList());
        if(id>0){args.add(company);args.add(id);jdbc.update("UPDATE "+table+" SET "+fields.stream().map(f->snake(f)+"=?").collect(Collectors.joining(","))+",modify_time=NOW() WHERE UPPER(company_code)=? AND id=?",args.toArray());return id;}
        args.add(company);jdbc.update("INSERT INTO "+table+" ("+fields.stream().map(this::snake).collect(Collectors.joining(","))+",company_code) VALUES ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    @Transactional(rollbackFor=Exception.class)
    public int deleteMaster(String company,String kind,List<Long> ids) {
        lock(company);if(ids.isEmpty() || ids.size()>100)throw new IllegalArgumentException("每次请选择 1 至 100 条记录");
        for(long id:ids){Map<String,Object> row=owned(table(kind),company,id);String fk=kind.equals("category")?"category_code":kind+"_code";
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+GOODS+" WHERE UPPER(company_code)=? AND "+fk+"=?",Long.class,company,id);
            if(count>0)throw new IllegalArgumentException(text(row.get(kind.equals("category")?"name":kind+"Name"))+"已关联 "+count+" 个商品，不能删除");
            if(kind.equals("category") && jdbc.queryForObject("SELECT COUNT(*) FROM goods_category WHERE UPPER(company_code)=? AND pid=?",Long.class,company,id)>0)
                throw new IllegalArgumentException("分类含有子分类，不能删除");
        }
        int count=0;for(long id:ids)count+=jdbc.update("DELETE FROM "+table(kind)+" WHERE UPPER(company_code)=? AND id=?",company,id);return count;
    }

    private static final class ImportCatalog {
        final Map<String,Map<Long,Map<String,Object>>> ids=new HashMap<>();
        final Map<String,Map<String,List<Long>>> names=new HashMap<>();
        final Set<String> skus=new HashSet<>(),barcodes=new HashSet<>();
    }
    private String categoryKey(String value) {return value.contains("/")?"path:"+value.replaceAll("\\s*/\\s*","/"):"name:"+value;}
    private ImportCatalog importCatalog(String company) {
        ImportCatalog catalog=new ImportCatalog();Map<String,Object> options=options(company);
        for(String kind:Arrays.asList("category","color","size")){
            Map<Long,Map<String,Object>> ids=new HashMap<>();Map<String,List<Long>> names=new HashMap<>();
            for(Map<String,Object> row:cast(options.get(kind))){long id=number(row.get("id"));ids.put(id,row);
                if(kind.equals("category")){if(number(row.get("level"))!=3)continue;for(String key:Arrays.asList("name:"+text(row.get("name")),categoryKey(text(row.get("path")))))names.computeIfAbsent(key,k->new ArrayList<>()).add(id);}
                else names.computeIfAbsent(text(row.get(kind+"Name")),k->new ArrayList<>()).add(id);
            }
            catalog.ids.put(kind,ids);catalog.names.put(kind,names);
        }
        for(Map<String,Object> row:rows("SELECT sku_sn,barcode_sn FROM "+GOODS+" WHERE UPPER(company_code)=?",company)){catalog.skus.add(text(row.get("skuSn")));if(!text(row.get("barcodeSn")).isEmpty())catalog.barcodes.add(text(row.get("barcodeSn")));}
        return catalog;
    }
    private Map<String,Object> association(String company,String kind,Object id,ImportCatalog catalog) {
        if(catalog==null)return owned(table(kind),company,number(id));
        Map<String,Object> row=catalog.ids.get(kind).get(number(id));if(row==null)throw new IllegalArgumentException("基础资料不存在或不属于当前公司");return row;
    }
    private void validateGoods(String company,Map<String,Object> data,long exclude) {validateGoods(company,data,exclude,null);}
    private void validateGoods(String company,Map<String,Object> data,long exclude,ImportCatalog catalog) {
        if(!text(data.get("sourceErrors")).isEmpty())throw new IllegalArgumentException(text(data.get("sourceErrors")));
        required(data,"skuSn",30,"SKU");required(data,"goodsSn",30,"货号");required(data,"goodsName",100,"商品名称");
        if(text(data.get("skuSn")).matches(".*\\s.*") || text(data.get("goodsSn")).matches(".*\\s.*"))throw new IllegalArgumentException("SKU 和货号不能包含空白字符");
        String barcode=text(data.get("barcodeSn"));if(!barcode.isEmpty() && !barcode.matches("[A-Za-z0-9_-]{1,30}"))throw new IllegalArgumentException("条码限 30 位英文、数字、横线或下划线");data.put("barcodeSn",barcode.isEmpty()?null:barcode);
        if(catalog==null){unique(GOODS,company,"sku_sn",data.get("skuSn"),exclude,"SKU","");if(!barcode.isEmpty())unique(GOODS,company,"barcode_sn",barcode,exclude,"条码","");}
        else {if(catalog.skus.contains(text(data.get("skuSn"))))throw new IllegalArgumentException("SKU 已存在");if(!barcode.isEmpty() && catalog.barcodes.contains(barcode))throw new IllegalArgumentException("条码已存在");}
        Map<String,Object> category=association(company,"category",data.get("categoryCode"),catalog);
        if(number(category.get("level"))!=3)throw new IllegalArgumentException("商品必须选择三级分类");
        association(company,"color",data.get("colorCode"),catalog);association(company,"size",data.get("sizeCode"),catalog);
        BigDecimal price;try{price=new BigDecimal(text(data.get("marketPrice")));}catch(Exception error){throw new IllegalArgumentException("市场价必须填写数字");}
        if(price.signum()<0 || price.compareTo(new BigDecimal("999999999999999.9999"))>0 || price.stripTrailingZeros().scale()>4)throw new IllegalArgumentException("市场价必须非负，最多四位小数");data.put("marketPrice",price);
        String days=text(data.get("validity"));if(!days.isEmpty() && (!days.matches("\\d{1,9}") || Long.parseLong(days)>999999999))throw new IllegalArgumentException("保质期必须为非负整数，单位天");data.put("validity",days.isEmpty()?null:days);
        for(String flag:Arrays.asList("isFd","isGift","isPackage")){String value=text(data.get(flag));if(value.isEmpty())value="0";if(!value.equals("0") && !value.equals("1"))throw new IllegalArgumentException("商品标记只能为是或否");data.put(flag,Integer.valueOf(value));}
        for(String field:Arrays.asList("goodsDesc","description")){String value=text(data.get(field));if(value.length()>10000)throw new IllegalArgumentException("描述或备注不能超过 10000 字符");data.put(field,value.isEmpty()?null:value);}
    }
    @Transactional(rollbackFor=Exception.class)
    public long saveGoods(String company,Map<String,Object> input,String actor) {
        lock(company);Map<String,Object> data=new HashMap<>(input);long id=number(data.get("id"));
        if(id<0)throw new IllegalArgumentException("商品 ID 无效");
        if(id>0){Map<String,Object> old=owned(GOODS,company,id);if(!text(old.get("skuSn")).equals(text(data.get("skuSn"))))throw new IllegalArgumentException("商品创建后不能修改 SKU");}
        validateGoods(company,data,id);data.put("createUser",actor);List<String> fields=new ArrayList<>(Arrays.asList(FIELDS));if(id==0)fields.add("createUser");
        return write(GOODS,company,id,data,fields);
    }
    private String goodsWhere(String company,Map<String,Object> filter,List<Object> args) {
        StringBuilder where=new StringBuilder(" WHERE UPPER(g.company_code)=?");args.add(company);
        for(String field:Arrays.asList("skuSn","goodsSn","barcodeSn","colorCode","sizeCode","marketPrice","validity","isFd","isGift","isPackage"))
            if(!text(filter.get(field)).isEmpty()){where.append(" AND g.").append(snake(field)).append("=?");args.add(filter.get(field));}
        for(String field:Arrays.asList("goodsName","goodsDesc","description"))if(!text(filter.get(field)).isEmpty()){where.append(" AND g.").append(snake(field)).append(" LIKE ?");args.add("%"+text(filter.get(field)).replace("!","!!").replace("%","!%").replace("_","!_")+"%");where.append(" ESCAPE '!'");}
        if(!text(filter.get("modifyTime")).isEmpty()){
            String date=text(filter.get("modifyTime"));try{java.time.LocalDate.parse(date);}catch(Exception e){throw new IllegalArgumentException("修改日期格式应为 yyyy-MM-dd");}
            where.append(" AND DATE(g.modify_time)=?");args.add(date);
        }
        if(!text(filter.get("categoryCode")).isEmpty()){
            long selected=number(filter.get("categoryCode"));List<Map<String,Object>> categories=masterList(company,"category",Collections.emptyMap());Set<Long> ids=new HashSet<>();ids.add(selected);boolean added=true;
            while(added){added=false;for(Map<String,Object> c:categories)if(ids.contains(number(c.get("pid"))) && ids.add(number(c.get("id"))))added=true;}
            where.append(" AND g.category_code IN (").append(String.join(",",Collections.nCopies(ids.size(),"?"))).append(")");args.addAll(ids);
        }
        return where.toString();
    }
    private void enrich(String company,List<Map<String,Object>> goods) {
        Map<String,Object> choices=options(company);Map<Long,String> cats=new HashMap<>(),colors=new HashMap<>(),sizes=new HashMap<>();
        for(Map<String,Object> row:cast(choices.get("category")))cats.put(number(row.get("id")),text(row.get("path")));
        for(Map<String,Object> row:cast(choices.get("color")))colors.put(number(row.get("id")),text(row.get("colorName")));
        for(Map<String,Object> row:cast(choices.get("size")))sizes.put(number(row.get("id")),text(row.get("sizeName")));
        for(Map<String,Object> row:goods){row.put("categoryPath",cats.get(number(row.get("categoryCode"))));row.put("colorName",colors.get(number(row.get("colorCode"))));row.put("sizeName",sizes.get(number(row.get("sizeCode"))));}
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> cast(Object value){return (List<Map<String,Object>>)value;}
    @Transactional(readOnly=true)
    public TableDataInfo goodsPage(String company,Map<String,Object> filter,int pageNum,int pageSize) {
        List<Object> args=new ArrayList<>();String where=goodsWhere(company,filter,args);long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+GOODS+" g"+where,Long.class,args.toArray());
        args.add(limit(pageSize));args.add(offset(pageNum,limit(pageSize)));List<Map<String,Object>> found=rows("SELECT g.* FROM "+GOODS+" g"+where+" ORDER BY g.id DESC LIMIT ? OFFSET ?",args.toArray());enrich(company,found);return page(found,total);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> goodsExport(String company,Map<String,Object> filter) {
        List<Object> args=new ArrayList<>();String where=goodsWhere(company,filter,args);List<Map<String,Object>> found=rows("SELECT g.* FROM "+GOODS+" g"+where+" ORDER BY g.id DESC",args.toArray());enrich(company,found);return found;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> goodsDetail(String company,long id){Map<String,Object> row=owned(GOODS,company,id);enrich(company,Collections.singletonList(row));return row;}
    @Transactional(rollbackFor=Exception.class)
    public int deleteGoods(String company,List<Long> ids) {
        lock(company);if(ids.isEmpty() || ids.size()>100)throw new IllegalArgumentException("每次请选择 1 至 100 个商品");
        List<String> skus=new ArrayList<>();for(long id:ids)skus.add(text(owned(GOODS,company,id).get("skuSn")));
        R<List<String>> result;try{result=references.references(company,skus,SecurityConstants.INNER);}catch(Exception error){throw new IllegalArgumentException("库存引用检查暂时不可用，请稍后重试");}
        if(result==null || result.getCode()!=200 || result.getData()==null)throw new IllegalArgumentException("库存引用检查失败，请稍后重试");
        if(!result.getData().isEmpty())throw new IllegalArgumentException("商品已被库存或分货记录引用，不能删除："+String.join("、",result.getData()));
        for(String table:Arrays.asList("no_tickets_goods","no_tickets_goods_tmp","wms_tickets_goods")){
            long exists=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Long.class,table);
            if(exists>0)for(String sku:skus)if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE UPPER(company_code)=? AND sku_sn=?",Long.class,company,sku)>0)throw new IllegalArgumentException("商品已被出入库单引用，不能删除："+sku);
        }
        int count=0;for(long id:ids)count+=jdbc.update("DELETE FROM "+GOODS+" WHERE UPPER(company_code)=? AND id=?",company,id);return count;
    }

    private Map<String,Object> imported(GoodsVO vo) {
        Map<String,Object> data=map(vo);data.put("description",null);return data;
    }
    private long match(ImportCatalog catalog,String kind,String value) {
        List<Long> found=catalog.names.get(kind).getOrDefault(kind.equals("category")?categoryKey(value):value,Collections.emptyList());
        if(found.isEmpty())throw new IllegalArgumentException((kind.equals("category")?"分类":kind.equals("color")?"颜色":"尺码")+"不存在，请先维护基础资料："+value);
        if(found.size()>1)throw new IllegalArgumentException("名称匹配多条资料，请使用完整分类路径或先处理重复名称："+value);return found.get(0);
    }
    private void resolve(ImportCatalog catalog,Map<String,Object> data) {
        data.put("categoryCode",match(catalog,"category",text(data.get("categoryName"))));data.put("colorCode",match(catalog,"color",text(data.get("colorName"))));data.put("sizeCode",match(catalog,"size",text(data.get("sizeName"))));
    }
    private String encode(Map<String,Object> row){try{return json.writeValueAsString(row);}catch(Exception e){throw new IllegalArgumentException("导入数据无法解析");}}
    private Map<String,Object> decode(String row){try{return json.readValue(row,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException("导入数据无法解析");}}
    @Transactional(rollbackFor=Exception.class)
    public String preview(String company,List<GoodsVO> input,String actor) {
        lock(company);if(input.isEmpty() || input.size()>5000)throw new IllegalArgumentException("每次导入 1 至 5000 行商品");
        ImportCatalog catalog=importCatalog(company);
        String batch=UUID.randomUUID().toString();int errors=0;Set<String> skus=new HashSet<>(),barcodes=new HashSet<>();List<Object[]> pending=new ArrayList<>();
        for(int i=0;i<input.size();i++){
            Map<String,Object> data=imported(input.get(i));List<String> reasons=new ArrayList<>();
            if(!skus.add(text(data.get("skuSn"))))reasons.add("文件内 SKU 重复");String barcode=text(data.get("barcodeSn"));if(!barcode.isEmpty() && !barcodes.add(barcode))reasons.add("文件内条码重复");
            try{resolve(catalog,data);validateGoods(company,data,0,catalog);}catch(IllegalArgumentException error){reasons.add(error.getMessage());}
            String notes=reasons.isEmpty()?"正常":String.join("；",reasons);if(!reasons.isEmpty())errors++;
            pending.add(new Object[]{batch,company,input.get(i).getSourceRowNum()==null?i+2:input.get(i).getSourceRowNum(),encode(data),notes});
        }
        jdbc.batchUpdate("INSERT INTO goods_import_row(import_batch,company_code,row_num,payload,notes) VALUES (?,?,?,?,?)",pending,100,(statement,row)->{for(int i=0;i<row.length;i++)statement.setObject(i+1,row[i]);});
        jdbc.update("INSERT INTO goods_import_batch(import_batch,company_code,status,total_rows,error_rows,create_user) VALUES (?,?,'PREVIEW',?,?,?)",batch,company,input.size(),errors,actor);return batch;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> batch(String company,String batch) {
        List<Map<String,Object>> rows=rows("SELECT * FROM goods_import_batch WHERE UPPER(company_code)=? AND import_batch=?",company,batch);
        if(rows.isEmpty())throw new IllegalArgumentException("导入批次不存在或不属于当前公司");return rows.get(0);
    }
    @Transactional(readOnly=true)
    public TableDataInfo previewRows(String company,String batch,int pageNum,int pageSize,boolean errorsOnly) {
        batch(company,batch);String where=" WHERE UPPER(company_code)=? AND import_batch=?"+(errorsOnly?" AND notes<>'正常'":"");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM goods_import_row"+where,Long.class,company,batch);
        List<Map<String,Object>> records=rows("SELECT row_num,payload,notes FROM goods_import_row"+where+" ORDER BY row_num LIMIT ? OFFSET ?",company,batch,limit(pageSize),offset(pageNum,limit(pageSize)));
        List<Map<String,Object>> data=new ArrayList<>();for(Map<String,Object> record:records){Map<String,Object> item=decode(text(record.get("payload")));item.put("rowNum",record.get("rowNum"));item.put("notes",record.get("notes"));data.add(item);}return page(data,count);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> importErrors(String company,String batch) {
        batch(company,batch);List<Map<String,Object>> data=new ArrayList<>();for(Map<String,Object> record:rows("SELECT row_num,payload,notes FROM goods_import_row WHERE UPPER(company_code)=? AND import_batch=? AND notes<>'正常' ORDER BY row_num",company,batch)){Map<String,Object> item=decode(text(record.get("payload")));item.put("rowNum",record.get("rowNum"));item.put("notes",record.get("notes"));data.add(item);}return data;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> confirm(String company,String batch,String actor) {
        lock(company);Map<String,Object> state=batch(company,batch);if("COMPLETED".equals(state.get("status")))return state;
        if(number(state.get("errorRows"))>0)throw new IllegalArgumentException("请修正全部错误后重新上传，当前批次不能确认");
        List<Map<String,Object>> records=rows("SELECT row_num,payload FROM goods_import_row WHERE UPPER(company_code)=? AND import_batch=? ORDER BY row_num",company,batch);
        if(records.isEmpty())throw new IllegalArgumentException("导入批次没有商品数据");
        ImportCatalog catalog=importCatalog(company);
        List<Map<String,Object>> valid=new ArrayList<>();for(Map<String,Object> record:records){Map<String,Object> data=decode(text(record.get("payload")));
            try{resolve(catalog,data);validateGoods(company,data,0,catalog);}catch(IllegalArgumentException e){throw new IllegalArgumentException("第 "+record.get("rowNum")+" 行："+e.getMessage());}valid.add(data);
        }
        List<String> fields=new ArrayList<>(Arrays.asList(FIELDS));fields.add("createUser");fields.add("companyCode");
        for(Map<String,Object> data:valid){data.put("createUser",actor);data.put("companyCode",company);}
        jdbc.batchUpdate("INSERT INTO "+GOODS+" ("+fields.stream().map(this::snake).collect(Collectors.joining(","))+") VALUES ("+String.join(",",Collections.nCopies(fields.size(),"?"))+")",valid,100,(statement,row)->{for(int i=0;i<fields.size();i++)statement.setObject(i+1,row.get(fields.get(i)));});
        jdbc.update("UPDATE goods_import_batch SET status='COMPLETED',confirm_user=?,confirm_time=NOW() WHERE UPPER(company_code)=? AND import_batch=?",actor,company,batch);return batch(company,batch);
    }
}
