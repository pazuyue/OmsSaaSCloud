package com.oms.inventory.service.impl.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.service.impl.InventoryMutationService;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;
import static com.oms.inventory.service.impl.InventoryQueryService.*;

/** One header lock then one SKU per transaction. No network calls, nested SKU transactions or background locks. */
@Service
public class AllocationWorkspaceService {
    @Resource private JdbcTemplate jdbc;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource private InventoryMutationService inventory;
    @Resource private AllocationReservationService reservations;
    private final ObjectMapper json=new ObjectMapper();

    TransactionTemplate transaction() {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
        return tx;
    }
    private static void require(boolean ok,String message) { if(!ok)throw new IllegalArgumentException(message); }
    private static String marks(int count) { return String.join(",",Collections.nCopies(count,"?")); }
    private static int limit(int size) { return Math.max(1,Math.min(100,size)); }
    private static int offset(int page,int size) { return (Math.max(1,Math.min(100000,page))-1)*size; }

    public Map<String,Object> rule(String company,long id,boolean lock) {
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM rule_stock_info WHERE company_code=? AND id=?"+(lock?" FOR UPDATE":""),ROW,company,id);
        require(!rows.isEmpty(),"分货单不存在或无权访问");return rows.get(0);
    }
    private void revision(Map<String,Object> rule,int revision) {
        require(number(rule,"revision")==revision,"分货单已被修改，请刷新后重试");
    }
    private void editable(Map<String,Object> rule) {
        require(number(rule,"status")==1,"仅草稿可以修改，请先撤回待审核单据");
        require(number(rule,"ruleType")>=1 && number(rule,"ruleType")<=3,"不支持的分货规则");
    }
    public TableDataInfo list(String company,String keyword,Integer status,Integer allocationType,int page,int size) {
        List<Object> args=new ArrayList<>();args.add(company);
        String where=" WHERE company_code=?";
        if(keyword!=null && !keyword.trim().isEmpty()) { require(keyword.length()<=100,"搜索内容过长");where+=" AND (rule_name LIKE ? OR rule_code=?)";args.add(keyword.trim()+"%");args.add(keyword.trim()); }
        if(status!=null){where+=" AND status=?";args.add(status);}
        if(allocationType!=null){where+=" AND allocation_type=?";args.add(allocationType);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_info"+where,Long.class,args.toArray());
        int count=limit(size);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM rule_stock_info"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        if(!rows.isEmpty()) {
            List<Object> ids=new ArrayList<>();ids.add(company);rows.forEach(row->ids.add(row.get("id")));
            Map<Long,Map<String,Object>> balances=new HashMap<>();
            for(Map<String,Object> row:jdbc.query("SELECT rule_id,SUM(allocated_quantity) AS original_quantity,SUM(occupied_quantity) AS occupied_quantity,SUM(consumed_quantity) AS consumed_quantity,SUM(released_quantity) AS released_quantity,SUM(allocated_quantity-occupied_quantity-consumed_quantity-released_quantity) AS releasable_quantity FROM rule_stock_result WHERE company_code=? AND rule_id IN ("+marks(rows.size())+") AND status='SUCCESS' GROUP BY rule_id",ROW,ids.toArray()))balances.put(number(row,"ruleId"),row);
            for(Map<String,Object> row:rows)row.put("reservationBalance",balances.get(number(row,"id")));
        }
        return page(rows,total);
    }
    List<String> stores(long id) {
        return jdbc.queryForList("SELECT store_code FROM rule_stock_store_code_info WHERE rule_id=? ORDER BY store_code",String.class,id);
    }
    List<Map<String,Object>> channels(long id) {
        return jdbc.query("SELECT * FROM rule_stock_channel_info WHERE rule_id=? ORDER BY priority,id",ROW,id);
    }
    public Map<String,Object> detail(String company,long id) {
        Map<String,Object> row=rule(company,id,false);
        row.put("stores",stores(id));row.put("channels",channels(id));
        row.put("goodsCount",jdbc.queryForObject("SELECT COUNT(DISTINCT sku_sn) FROM rule_stock_goods_info WHERE rule_id=?",Long.class,id));
        row.put("counts",counts(id));return row;
    }
    private Map<String,Object> counts(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) AS total,COALESCE(SUM(status='SUCCESS'),0) AS success,COALESCE(SUM(status='FAILED'),0) AS failed,COALESCE(SUM(status='PENDING'),0) AS pending,COALESCE(SUM(release_status='RELEASED'),0) AS released,COALESCE(SUM(release_status='FAILED'),0) AS release_failed,COALESCE(SUM(release_status='PENDING'),0) AS release_pending FROM rule_stock_result WHERE rule_id=?",ROW,id);
    }

    public void validateDraft(AllocationDraft d) {
        require(d!=null && d.getRuleName()!=null && !d.getRuleName().trim().isEmpty() && d.getRuleName().length()<=100,"请填写 1 至 100 字的分货单名称");
        require(d.getRemark()==null || d.getRemark().length()<=1000,"备注最多 1000 字");
        require(d.getRuleType()!=null && d.getRuleType()>=1 && d.getRuleType()<=3,"分货单规则无效");
        if(d.getRuleType()==1) {
            require(Integer.valueOf(1).equals(d.getAllocationType()),"日常分货只能重算普通配额，不能分配锁库库存");
            require(d.getStartTime()!=null && d.getEndTime()!=null && d.getEndTime().isAfter(d.getStartTime()),"请设置有效的生效时间和结束时间");
            require(d.getIntervalMinutes()!=null && d.getIntervalMinutes()>=1 && d.getIntervalMinutes()<=1440,"执行间隔须为 1 至 1440 分钟");
            require(d.getDailyPriority()!=null && d.getDailyPriority()>=1 && d.getDailyPriority()<=9999,"规则优先级须为 1 至 9999，数字越小越优先");
        }
        if(d.getRuleType()==3)require(Integer.valueOf(2).equals(d.getAllocationType()),"锁库时分货必须使用锁库库存方式");
        require(Integer.valueOf(1).equals(d.getAllocationType()) || Integer.valueOf(2).equals(d.getAllocationType()),"分货类型无效");
        require(Integer.valueOf(1).equals(d.getRuleRange()) || Integer.valueOf(2).equals(d.getRuleRange()),"商品范围无效");
        require(Integer.valueOf(1).equals(d.getRuleMode()) || Integer.valueOf(2).equals(d.getRuleMode()),"分配策略无效");
        require(d.getStores()!=null && d.getStores().size()<=100 && new HashSet<>(d.getStores()).size()==d.getStores().size(),"仓库不能重复且最多 100 个");
        for(String s:d.getStores()) require(s!=null && !s.trim().isEmpty() && s.length()<=50 && s.equals(s.trim()),"仓库编码无效");
        require(d.getChannels()!=null && d.getChannels().size()<=100,"渠道最多 100 个");
        Set<Integer> seen=new HashSet<>();
        for(AllocationDraft.Channel c:d.getChannels()) {
            require(c!=null && c.getChannelId()!=null && c.getChannelId()>0 && seen.add(c.getChannelId()),"渠道无效或重复");
            require(Integer.valueOf(1).equals(c.getRuleType()) || Integer.valueOf(2).equals(c.getRuleType()),"渠道计算方式无效");
            BigDecimal value=c.getPercentage();
            require(value!=null && value.signum()>=0 && value.scale()<=2 && value.compareTo(BigDecimal.valueOf(c.getRuleType()==1?100:100000000))<=0,"比例须为 0 至 100，固定数量须为非负整数");
            if(c.getRuleType()==2) require(value.stripTrailingZeros().scale()<=0,"固定数量必须为整数");
            require(c.getDecimalHandleType()!=null && c.getDecimalHandleType()>=1 && c.getDecimalHandleType()<=3,"取整方式无效");
        }
    }
    public long save(String company,AllocationDraft d,String actor) {
        validateDraft(d);
        return transaction().execute(tx->{
            long id;
            if(d.getId()==null) {
                GeneratedKeyHolder key=new GeneratedKeyHolder();
                jdbc.update(connection->{
                    java.sql.PreparedStatement ps=connection.prepareStatement("INSERT INTO rule_stock_info(company_code,rule_code,rule_name,enable,rule_type,allocation_type,rule_range,rule_mode,status,create_user_name,remark) VALUES (?,?,?,2,2,?,?,?,1,?,?)",Statement.RETURN_GENERATED_KEYS);
                    Object[] args={company,"FH-"+UUID.randomUUID().toString(),d.getRuleName().trim(),d.getAllocationType(),d.getRuleRange(),d.getRuleMode(),actor,d.getRemark()};
                    for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;
                },key);id=key.getKey().longValue();
            } else {
                id=d.getId();Map<String,Object> old=rule(company,id,true);editable(old);
                require(d.getRevision()!=null,"缺少版本号");revision(old,d.getRevision());
                jdbc.update("UPDATE rule_stock_info SET rule_name=?,remark=?,allocation_type=?,rule_range=?,rule_mode=?,revision=revision+1 WHERE id=?",d.getRuleName().trim(),d.getRemark(),d.getAllocationType(),d.getRuleRange(),d.getRuleMode(),id);
            }
            jdbc.update("UPDATE rule_stock_info SET rule_type=?,start_time=?,end_time=?,interval_minutes=?,daily_priority=? WHERE id=?",d.getRuleType(),d.getStartTime(),d.getEndTime(),d.getIntervalMinutes(),d.getDailyPriority(),id);
            jdbc.update("DELETE FROM rule_stock_store_code_info WHERE rule_id=?",id);
            jdbc.update("DELETE FROM rule_stock_channel_info WHERE rule_id=?",id);
            List<Object[]> storeArgs=new ArrayList<>();for(String store:d.getStores())storeArgs.add(new Object[]{id,store,company});
            if(!storeArgs.isEmpty())jdbc.batchUpdate("INSERT INTO rule_stock_store_code_info(rule_id,store_code,company_code) VALUES (?,?,?)",storeArgs);
            List<Object[]> channelArgs=new ArrayList<>();int priority=0;
            for(AllocationDraft.Channel c:d.getChannels())channelArgs.add(new Object[]{id,c.getChannelId(),c.getChannelName(),c.getPercentage(),c.getRuleType(),c.getDecimalHandleType(),company,++priority});
            if(!channelArgs.isEmpty())jdbc.batchUpdate("INSERT INTO rule_stock_channel_info(rule_id,channel_id,channel_name,percentage,rule_type,decimal_handle_type,company_code,priority) VALUES (?,?,?,?,?,?,?,?)",channelArgs);
            return id;
        });
    }
    void configured(Map<String,Object> row) {
        require(number(row,"ruleType")>=1 && number(row,"ruleType")<=3,"不支持的分货规则");
        if(number(row,"ruleType")==1) {
            require(number(row,"allocationType")==1,"日常分货只能处理普通配额");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_info WHERE id=? AND start_time IS NOT NULL AND end_time>start_time AND end_time>NOW() AND interval_minutes BETWEEN 1 AND 1440 AND daily_priority BETWEEN 1 AND 9999",Long.class,row.get("id"))==1,"日常分货有效期或执行间隔无效，请先调整草稿");
        }
        long id=number(row,"id");
        require(!stores(id).isEmpty() && !channels(id).isEmpty(),"请配置仓库和渠道");
        require(number(row,"ruleMode")==1 || number(row,"ruleMode")==2,"不支持该分配策略");
        if(number(row,"ruleRange")==2) require(jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_goods_info WHERE rule_id=?",Long.class,id)>0,"请先导入指定商品");
    }
    public void submit(String company,long id,int version,boolean withdraw) {
        transaction().execute(tx->{
            Map<String,Object> row=rule(company,id,true);revision(row,version);
            if(withdraw) require(number(row,"status")==2,"仅待审核单据可以撤回");
            else {editable(row);configured(row);}
            jdbc.update("UPDATE rule_stock_info SET status=?,revision=revision+1 WHERE id=?",withdraw?1:2,id);return null;
        });
    }
    public void delete(String company,long id,int version) {
        transaction().execute(tx->{
            Map<String,Object> row=rule(company,id,true);editable(row);revision(row,version);
            for(String table:Arrays.asList("rule_stock_goods_info","rule_stock_channel_info","rule_stock_store_code_info"))jdbc.update("DELETE FROM "+table+" WHERE rule_id=?",id);
            jdbc.update("DELETE FROM rule_stock_info WHERE id=?",id);return null;
        });
    }
    public int importGoods(String company,long id,int version,List<String> values) {
        require(values!=null && !values.isEmpty() && values.size()<=50000,"导入商品须为 1 至 50000 行");
        LinkedHashSet<String> skus=new LinkedHashSet<>();int row=1;
        for(String sku:values) {
            row++;require(sku!=null && !sku.trim().isEmpty() && sku.trim().length()<=50 && !sku.matches(".*[\\r\\n\\t].*"),"第 "+row+" 行 SKU 为空、过长或包含控制字符");skus.add(sku.trim());
        }
        return transaction().execute(tx->{
            Map<String,Object> rule=rule(company,id,true);editable(rule);revision(rule,version);
            require(number(rule,"ruleRange")==2,"仅指定商品模式可以导入");
            jdbc.update("DELETE FROM rule_stock_goods_info WHERE rule_id=?",id);
            jdbc.batchUpdate("INSERT INTO rule_stock_goods_info(rule_id,sku_sn,company_code,create_time) VALUES (?,?,?,NOW())",skus,500,(ps,sku)->{ps.setLong(1,id);ps.setString(2,sku);ps.setString(3,company);});
            jdbc.update("UPDATE rule_stock_info SET revision=revision+1 WHERE id=?",id);return skus.size();
        });
    }
    public TableDataInfo goods(String company,long id,String sku,int page,int size) {
        rule(company,id,false);String where=" WHERE rule_id=?";List<Object> args=new ArrayList<>();args.add(id);
        if(sku!=null && !sku.trim().isEmpty()){where+=" AND sku_sn=?";args.add(sku.trim());}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_goods_info"+where,Long.class,args.toArray());
        int count=limit(size);args.add(count);args.add(offset(page,count));
        return page(jdbc.query("SELECT sku_sn FROM rule_stock_goods_info"+where+" ORDER BY sku_sn LIMIT ? OFFSET ?",ROW,args.toArray()),total);
    }
    String skuSource(Map<String,Object> rule,List<Object> args) {
        long id=number(rule,"id");
        if(number(rule,"ruleRange")==2) {args.add(id);return "SELECT DISTINCT sku_sn FROM rule_stock_goods_info WHERE rule_id=?";}
        List<String> warehouses=stores(id);require(!warehouses.isEmpty(),"请先配置仓库");
        args.add(rule.get("companyCode"));args.addAll(warehouses);
        return "SELECT DISTINCT sku_sn FROM wms_inventory WHERE company_code=? AND store_code IN ("+marks(warehouses.size())+")";
    }
    private Map<Long,Map<String,Object>> current(String company,String sku,List<Map<String,Object>> channels,boolean lock) {
        List<Object> args=new ArrayList<>(Arrays.asList(company,sku));channels.forEach(c->args.add(c.get("channelId")));
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM oms_channel_inventory WHERE company_code=? AND sku_sn=? AND channel_id IN ("+marks(channels.size())+") ORDER BY channel_id"+(lock?" FOR UPDATE":""),ROW,args.toArray());
        return rows.stream().collect(Collectors.toMap(r->number(r,"channelId"),r->r));
    }
    private Map<String,Object> calculation(Map<String,Object> rule,String sku,long available,List<Map<String,Object>> channels,Map<Long,Map<String,Object>> current) {
        List<Map<String,Object>> targets=AllocationCalculator.calculate(available,number(rule,"ruleMode")==2,number(rule,"allocationType")==2,channels,current);
        Map<String,Object> result=new LinkedHashMap<>();result.put("skuSn",sku);result.put("available",available);result.put("channels",targets);
        long total=targets.stream().mapToLong(r->number(r,"target")).sum();result.put("total",total);
        result.put("lockQuantity",number(rule,"allocationType")==2?total:0);result.put("shared",total>available);
        if(number(rule,"ruleType")==1) {
            // Read other headers without locking: only this rule's header and the shared SKU mutex are locked.
            List<Object> args=new ArrayList<>(Arrays.asList(rule.get("companyCode"),rule.get("dailyPriority"),rule.get("dailyPriority"),rule.get("id"),sku,sku));
            channels.forEach(c->args.add(c.get("channelId")));
            List<Long> claimed=jdbc.queryForList("SELECT DISTINCT c.channel_id FROM rule_stock_info h JOIN rule_stock_channel_info c ON c.rule_id=h.id WHERE h.company_code=? AND h.rule_type=1 AND h.daily_enabled=1 AND h.status=3 AND h.start_time<=NOW() AND h.end_time>NOW() AND (h.daily_priority<? OR (h.daily_priority=? AND h.id<?)) AND ((h.rule_range=2 AND EXISTS (SELECT 1 FROM rule_stock_goods_info g WHERE g.rule_id=h.id AND g.sku_sn=?)) OR (h.rule_range=1 AND EXISTS (SELECT 1 FROM rule_stock_store_code_info st JOIN wms_inventory w ON w.store_code=st.store_code WHERE st.rule_id=h.id AND w.company_code=h.company_code AND w.sku_sn=?))) AND c.channel_id IN ("+marks(channels.size())+")",Long.class,args.toArray());
            Set<Long> excluded=new HashSet<>(claimed);long applied=0;
            for(Map<String,Object> target:targets) {boolean skipped=excluded.contains(number(target,"channelId"));target.put("skipped",skipped);if(skipped){target.put("after",target.get("before"));target.put("change",0);target.put("warning","更高优先级日常规则负责该商品和渠道，本轮跳过");}else applied+=number(target,"target");}
            result.put("total",applied);
        }
        return result;
    }
    public TableDataInfo preview(String company,long id,int version,int page,int size) {
        Map<String,Object> rule=rule(company,id,false);revision(rule,version);configured(rule);
        List<String> warehouses=stores(id);List<Map<String,Object>> channels=channels(id);List<Object> args=new ArrayList<>();String source=skuSource(rule,args);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM ("+source+") skus",Long.class,args.toArray());
        int count=Math.min(20,limit(size));args.add(count);args.add(offset(page,count));
        List<String> skus=jdbc.queryForList(source+" ORDER BY sku_sn LIMIT ? OFFSET ?",String.class,args.toArray());
        List<Map<String,Object>> rows=new ArrayList<>();
        if(skus.isEmpty())return page(rows,total);
        // Fixed query count per page, independent of SKU/channel counts. Preview never locks rows.
        List<Object> stockArgs=new ArrayList<>();stockArgs.add(company);stockArgs.addAll(skus);stockArgs.addAll(warehouses);
        String stockWhere=" WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+") AND store_code IN ("+marks(warehouses.size())+")";
        Map<String,Long> availableBySku=new HashMap<>();
        for(Map<String,Object> row:jdbc.query("SELECT sku_sn,SUM(zp_available_number) AS available FROM wms_inventory"+stockWhere+" GROUP BY sku_sn",ROW,stockArgs.toArray()))availableBySku.put((String)row.get("skuSn"),number(row,"available"));
        List<Object> skuArgs=new ArrayList<>();skuArgs.add(company);skuArgs.addAll(skus);
        Set<String> omsSkus=new HashSet<>(jdbc.queryForList("SELECT sku_sn FROM oms_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+")",String.class,skuArgs.toArray()));
        List<Object> channelArgs=new ArrayList<>(skuArgs);channels.forEach(c->channelArgs.add(c.get("channelId")));
        Map<String,Map<Long,Map<String,Object>>> currentBySku=new HashMap<>();
        for(Map<String,Object> row:jdbc.query("SELECT * FROM oms_channel_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+") AND channel_id IN ("+marks(channels.size())+")",ROW,channelArgs.toArray()))currentBySku.computeIfAbsent((String)row.get("skuSn"),key->new HashMap<>()).put(number(row,"channelId"),row);
        Set<String> inconsistent=new HashSet<>();
        if(number(rule,"allocationType")==2) {
            String balance=Arrays.stream(QUANTITIES).map(q->"COALESCE(SUM(b."+q+"),0)=w."+q).collect(Collectors.joining(" AND "));
            String invalid=Arrays.stream(QUANTITIES).map(q->"w."+q+"<0").collect(Collectors.joining(" OR "));
            inconsistent.addAll(jdbc.queryForList("SELECT DISTINCT w.sku_sn FROM wms_inventory w WHERE w.company_code=? AND w.sku_sn IN ("+marks(skus.size())+") AND w.store_code IN ("+marks(warehouses.size())+") AND ("+invalid+" OR w.zp_actual_number<>w.zp_available_number+w.zp_lock_number OR w.cp_actual_number<>w.cp_available_number+w.cp_lock_number OR NOT EXISTS (SELECT 1 FROM wms_inventory_batch b WHERE b.company_code=w.company_code AND b.sku_sn=w.sku_sn AND b.store_code=w.store_code HAVING "+balance+"))",String.class,stockArgs.toArray()));
        }
        for(String sku:skus) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("skuSn",sku);
            try {
                row=calculation(rule,sku,availableBySku.getOrDefault(sku,0L),channels,currentBySku.getOrDefault(sku,Collections.emptyMap()));
                if(!omsSkus.contains(sku))row.put("error","缺少商品汇总库存，请先核对入库记录");
                else if(inconsistent.contains(sku))row.put("error","汇总与批次库存不一致，请先核对数据");
            } catch(IllegalArgumentException e) {row.put("error",e.getMessage());}
            rows.add(row);
        }
        return page(rows,total);
    }
    public void start(String company,long id,int version,String action,String actor) {
        require(Arrays.asList("EXECUTE","RETRY","RELEASE").contains(action),"执行动作无效");
        transaction().execute(tx->{
            Map<String,Object> rule=rule(company,id,true);int status=(int)number(rule,"status");
            require(number(rule,"ruleType")!=1,"日常分货请使用启用、停用和立即执行入口");
            // A repeated start request never resets already committed results.
            if((status==4 && !action.equals("RELEASE")) || (status==9 && action.equals("RELEASE")))return null;
            revision(rule,version);
            if(action.equals("EXECUTE")) {
                require(status==2,"仅待审核单据可执行");configured(rule);
                List<Object> args=new ArrayList<>(Arrays.asList(id,company));String source=skuSource(rule,args);
                int count=jdbc.update("INSERT INTO rule_stock_result(rule_id,company_code,sku_sn) SELECT ?,?,selected.sku_sn FROM ("+source+") selected",args.toArray());
                require(count>0,"所选范围没有商品，请调整商品范围");
                jdbc.update("UPDATE rule_stock_info SET status=4,run_action='ALLOCATE',revision=revision+1,first_reviewer_time=COALESCE(first_reviewer_time,NOW()),reviewer_time=NOW(),reviewer_user_name=?,last_update_time=NOW() WHERE id=?",actor,id);
            } else if(action.equals("RETRY")) {
                require((status==7 || status==8) && "ALLOCATE".equals(rule.get("runAction")),"当前没有可重试的分货项");
                jdbc.update("UPDATE rule_stock_result SET status='PENDING',error_message='' WHERE rule_id=? AND status='FAILED'",id);
                jdbc.update("UPDATE rule_stock_info SET status=4,revision=revision+1 WHERE id=?",id);
            } else {
                require(number(rule,"allocationType")==2 && (status==5 || status==7 || status==8 || status==11),"当前单据不能释放锁库");
                int count=jdbc.update("UPDATE rule_stock_result SET release_status='PENDING',release_error='' WHERE rule_id=? AND status='SUCCESS' AND release_status IN ('NONE','FAILED','PARTIAL')",id);
                require(count>0,"没有可释放的锁库记录");
                jdbc.update("UPDATE rule_stock_info SET status=9,run_action='RELEASE',revision=revision+1 WHERE id=?",id);
            }
            return null;
        });
    }
    public Map<String,Object> step(String company,long id,String actor) {
        Map<String,Object> head=rule(company,id,false);
        require(number(head,"ruleType")!=1,"日常分货由后台定时处理");
        require(number(head,"status")==4 || number(head,"status")==9,"当前没有待执行任务");
        boolean release=number(head,"status")==9;
        List<String> stores=stores(id);List<Map<String,Object>> channels=channels(id);
        long deadline=System.nanoTime()+2000000000L;
        for(int i=0;i<10 && System.nanoTime()<deadline;i++) {
            final String[] sku={null};
            try {
                Boolean processed=transaction().execute(tx->{
                    Map<String,Object> latest=rule(company,id,true);
                    if(number(latest,"status")!=(release?9:4))return false;
                    String field=release?"release_status":"status";
                    List<Map<String,Object>> todo=jdbc.query("SELECT * FROM rule_stock_result WHERE rule_id=? AND "+field+"='PENDING' ORDER BY sku_sn LIMIT 1 FOR UPDATE",ROW,id);
                    if(todo.isEmpty())return false;
                    Map<String,Object> item=todo.get(0);sku[0]=(String)item.get("skuSn");
                    if(release) release(company,id,item,stores,actor);
                    else allocate(company,latest,item,stores,channels,actor);
                    return true;
                });
                if(!Boolean.TRUE.equals(processed))break;
            } catch(RuntimeException e) {
                if(sku[0]==null)throw e;
                String message=failure(e);
                transaction().execute(tx->{
                    rule(company,id,true);
                    if(release)jdbc.update("UPDATE rule_stock_result SET release_status='FAILED',release_error=?,release_operator=? WHERE rule_id=? AND sku_sn=? AND release_status='PENDING'",message,actor,id,sku[0]);
                    else jdbc.update("UPDATE rule_stock_result SET status='FAILED',error_message=?,attempts=attempts+1,operator_name=? WHERE rule_id=? AND sku_sn=? AND status='PENDING'",message,actor,id,sku[0]);
                    return null;
                });
            }
        }
        transaction().execute(tx->{
            Map<String,Object> latest=rule(company,id,true);
            if(number(latest,"status")!=(release?9:4))return null;
            Map<String,Object> counts=counts(id);
            if(number(counts,release?"releasePending":"pending")==0) {
                long failed=number(counts,release?"releaseFailed":"failed");
                int status=failed==0?(release?10:5):(number(counts,release?"released":"success")>0?7:8);
                if(release && failed==0 && jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_result WHERE rule_id=? AND release_status='PARTIAL'",Long.class,id)>0)status=11;
                jdbc.update("UPDATE rule_stock_info SET status=?,revision=revision+1,over_time=NOW(),last_update_time=NOW() WHERE id=?",status,id);
            }
            return null;
        });
        return detail(company,id);
    }
    @SuppressWarnings("unchecked")
    private void allocate(String company,Map<String,Object> rule,Map<String,Object> item,List<String> stores,List<Map<String,Object>> channels,String actor) {
        String sku=(String)item.get("skuSn");long id=number(rule,"id");
        Map<String,Object> result=applyTargets(company,rule,sku,stores,channels);
        jdbc.update("UPDATE rule_stock_result SET status='SUCCESS',allocated_quantity=?,detail_json=?,error_message='',attempts=attempts+1,operator_name=? WHERE id=?",number(result,"total"),encode(result),actor,item.get("id"));
    }
    /** Caller must hold the rule header in a short transaction. Both execution modes share this writer. */
    @SuppressWarnings("unchecked")
    Map<String,Object> applyTargets(String company,Map<String,Object> rule,String sku,List<String> stores,List<Map<String,Object>> channels) {
        long id=number(rule,"id");
        require(number(rule,"ruleType")!=1 || number(rule,"allocationType")==1,"日常分货不能锁库");
        long available=inventory.allocationAvailable(company,sku,stores);
        Map<String,Object> result=calculation(rule,sku,available,channels,current(company,sku,channels,true));
        boolean locking=number(rule,"allocationType")==2;
        long quantity=number(result,"total");
        if(locking && quantity>0) {
            // A SKU need not exist in every selected warehouse. Reserve only rows which exist.
            List<String> existing=existingStores(company,sku,stores);
            inventory.reserve(company,existing,sku,BigDecimal.valueOf(quantity),"RULE-"+id,false);
        }
        List<Map<String,Object>> targets=(List<Map<String,Object>>)result.get("channels");
        List<Map<String,Object>> ordered=new ArrayList<>(targets);ordered.sort(Comparator.comparingLong(r->number(r,"channelId")));
        for(Map<String,Object> target:ordered) {
            if(Boolean.TRUE.equals(target.get("skipped")))continue;
            if(number(rule,"ruleType")==1 && Boolean.TRUE.equals(target.get("unchanged")))continue;
            long channel=number(target,"channelId");
            jdbc.update("INSERT INTO oms_channel_inventory(channel_id,company_code,sku_sn) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",channel,company,sku);
            int changed=jdbc.update("UPDATE oms_channel_inventory SET "+(locking?"allocated_stock":"available_stock")+"=?,version=version+1 WHERE company_code=? AND channel_id=? AND sku_sn=?",number(target,"after"),company,channel,sku);
            require(changed==1,"渠道库存所属公司不一致，请先核对渠道资料");
        }
        if(locking)reservations.record(company,id,sku,targets,quantity);
        return result;
    }
    private List<String> existingStores(String company,String sku,List<String> stores) {
        List<Object> args=new ArrayList<>(Arrays.asList(company,sku));args.addAll(stores);
        return jdbc.queryForList("SELECT store_code FROM wms_inventory WHERE company_code=? AND sku_sn=? AND store_code IN ("+marks(stores.size())+") ORDER BY store_code",String.class,args.toArray());
    }
    @SuppressWarnings("unchecked")
    private void release(String company,long id,Map<String,Object> item,List<String> stores,String actor) {
        reservations.release(company,id,item,actor);
    }
    String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("保存执行明细失败",e);}}
    Map<String,Object> decode(String value) {try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException("执行明细无法读取",e);}}
    String failure(Throwable e) {
        for(Throwable t=e;t!=null;t=t.getCause()) {
            if(t instanceof PessimisticLockingFailureException)return "库存并发冲突，本 SKU 已回滚，可稍后重试";
            if(t instanceof IllegalArgumentException)return t.getMessage()==null?"参数或库存校验失败":t.getMessage().substring(0,Math.min(500,t.getMessage().length()));
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Allocation SKU rolled back",e);
        return "处理失败，本 SKU 已回滚，请核对数据后重试";
    }
    public TableDataInfo results(String company,long id,String status,int page,int size) {
        rule(company,id,false);List<Object> args=new ArrayList<>();args.add(id);String where=" WHERE rule_id=?";
        if("FAILED".equals(status))where+=" AND (status='FAILED' OR release_status='FAILED')";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rule_stock_result"+where,Long.class,args.toArray());
        int count=limit(size);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=jdbc.query("SELECT * FROM rule_stock_result"+where+" ORDER BY sku_sn LIMIT ? OFFSET ?",ROW,args.toArray());
        for(Map<String,Object> row:rows){
            Object detail=row.remove("detailJson");if(detail!=null)row.put("detail",decode(detail.toString()));
            row.put("releasableQuantity",number(row,"allocatedQuantity")-number(row,"occupiedQuantity")-number(row,"consumedQuantity")-number(row,"releasedQuantity"));
        }
        return page(rows,total);
    }
}
