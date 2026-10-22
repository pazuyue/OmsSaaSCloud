package com.oms.inventory.controller.wms.api;
import com.oms.common.model.dto.OrderStockCommand;
import com.oms.inventory.service.impl.OrderStockService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderStock/internal")
public class OrderStockController {
    @Resource private com.oms.inventory.service.impl.LuckyBagInventory luckyPools;
    @Resource private com.oms.inventory.service.impl.LuckyBagStock luckyStock;
    @InnerAuth @PostMapping("/lucky-claim") public R<Map<String,Object>> luckyClaim(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){String c=signalCompany(company);luckyPools.prepare(c,Collections.singletonList(com.oms.common.lucky.LuckyBagRules.text(input,"sku_sn")));return R.ok(luckyStock.claim(c,input));}
    @Resource private com.oms.inventory.service.impl.ExchangeStockLookup exchangeStock;
    @InnerAuth @PostMapping("/exchange-stock") public R<List<Map<String,Object>>> exchangeStock(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(exchangeStock.lookup(signalCompany(company),command));}
    @Resource private com.oms.inventory.service.impl.OrderStockDiagnosticsService diagnostics;
    @InnerAuth @PostMapping("/diagnostics")
    public R<List<Map<String,Object>>> diagnostics(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> keys){return R.ok(diagnostics.facts(company,keys));}
    private String signalCompany(String company){if(company==null||!company.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("公司无效");return company.toUpperCase(Locale.ROOT);}
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @InnerAuth @PostMapping("/return-basis") public R<Map<String,Object>> returnBasis(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){
        String scope=signalCompany(company);Object raw=command.get("allocation_ids");if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>100)throw new IllegalArgumentException("原出库来源无效");Set<Long> ids=new TreeSet<>();for(Object id:(List<?>)raw)if(!ids.add(Long.parseLong(String.valueOf(id))))throw new IllegalArgumentException("原出库来源重复");List<Object> args=new ArrayList<>(Arrays.asList(scope,command.get("channel_id"),command.get("platform_tid"),command.get("platform_oid"),command.get("sku_sn")));args.addAll(ids);List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.id,a.outbound_quantity,b.transaction_price FROM oms_order_warehouse_allocation a JOIN oms_order_stock_demand d ON d.company_code=a.company_code AND d.id=a.demand_id JOIN wms_inventory_batch b ON b.company_code=a.company_code AND b.id=a.batch_id WHERE a.company_code=? AND a.channel_id=? AND a.platform_tid=? AND d.platform_oid=? AND a.sku_sn=? AND a.outbound_quantity>0 AND a.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());if(rows.size()!=ids.size())throw new IllegalArgumentException("原出库来源缺失或不属于此订单商品");java.math.BigDecimal total=java.math.BigDecimal.ZERO;long quantity=0;for(Map<String,Object> r:rows){long q=((Number)r.get("outbound_quantity")).longValue();quantity+=q;if(r.get("transaction_price")==null)throw new IllegalArgumentException("原出库成本缺失，请核对来源批次");total=total.add(new java.math.BigDecimal(r.get("transaction_price").toString()).multiply(java.math.BigDecimal.valueOf(q)));}Map<String,Object> result=new LinkedHashMap<>();result.put("quantity",quantity);result.put("unit_cost",total.divide(java.math.BigDecimal.valueOf(quantity),6,java.math.RoundingMode.HALF_UP));result.put("cost_policy","ORIGINAL_SHIPMENT_WEIGHTED");return R.ok(result);
    }
    @InnerAuth @GetMapping("/allocation-stock-signals") public R<List<Map<String,Object>>> stockSignals(@RequestParam("company_code") String company){return R.ok(jdbc.queryForList("SELECT sku_sn,store_code,revision FROM oms_allocation_stock_signal WHERE company_code=? AND revision>acknowledged_revision ORDER BY update_time,sku_sn,store_code LIMIT 50",signalCompany(company)));}
    @InnerAuth @PostMapping("/allocation-stock-signals/ack") public R<Map<String,Object>> ackStockSignal(@RequestParam("company_code") String company,@RequestBody Map<String,Object> signal){jdbc.update("UPDATE oms_allocation_stock_signal SET acknowledged_revision=GREATEST(acknowledged_revision,?) WHERE company_code=? AND sku_sn=? AND store_code=? AND revision>=?",signal.get("revision"),signalCompany(company),signal.get("sku_sn"),signal.get("store_code"),signal.get("revision"));return R.ok(Collections.emptyMap());}
    @Resource private com.oms.inventory.service.impl.OrderFulfillmentAdjustmentService adjustments;
    @InnerAuth @PostMapping("/fulfillment-adjustment") public R<Map<String,Object>> adjust(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(adjustments.apply(company,command));}
    @Resource private OrderStockService service;
    @Resource private com.oms.inventory.service.impl.OrderVirtualOutboundService virtualOutbound;
    @InnerAuth @GetMapping("/warehouse-outbound/{request}")
    public R<Map<String,Object>> warehouseReceipt(@RequestParam("company_code") String company,@PathVariable String request){return R.ok(virtualOutbound.warehouseReceipt(company,request));}
    @InnerAuth @PostMapping("/warehouse-outbound")
    public R<Map<String,Object>> warehouseOutbound(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(virtualOutbound.warehouseOutbound(company,command));}
    @InnerAuth @GetMapping("/virtual-outbound/{request}")
    public R<Map<String,Object>> virtualReceipt(@RequestParam("company_code") String company,@PathVariable String request){return R.ok(virtualOutbound.receipt(company,request));}
    @InnerAuth @PostMapping("/virtual-outbound")
    public R<Map<String,Object>> virtualOutbound(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(virtualOutbound.outbound(company,command));}
    @Resource private com.oms.inventory.service.impl.OrderWarehouseAllocationService warehouses;
    @InnerAuth @GetMapping("/warehouse-receipts")
    public R<List<Map<String,Object>>> receipts(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,@RequestParam("platform_tid") String tid){return R.ok(warehouses.receipts(company,channel,tid));}
    @InnerAuth @PostMapping("/warehouse-receipts/batch")
    public R<List<Map<String,Object>>> receiptsBatch(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(warehouses.receiptsBatch(company,command));}
    @InnerAuth @PostMapping("/warehouse-allocation-preview")
    public R<Map<String,Object>> preview(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(warehouses.preview(company,command));}
    @InnerAuth @PostMapping("/warehouse-allocate")
    public R<List<Map<String,Object>>> allocate(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(warehouses.allocate(company,command));}
    @InnerAuth @GetMapping("/demands")
    public R<List<Map<String,Object>>> demands(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,@RequestParam("platform_tid") String tid){return R.ok(service.demands(company,channel,tid));}
    @InnerAuth @PostMapping("/{action}")
    public R<List<Map<String,Object>>> execute(@PathVariable String action,@RequestParam("company_code") String company,@RequestBody OrderStockCommand command){return R.ok(service.execute(company,action,command));}
}
