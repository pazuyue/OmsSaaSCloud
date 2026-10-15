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
    @Resource private com.oms.inventory.service.impl.OrderStockDiagnosticsService diagnostics;
    @InnerAuth @PostMapping("/diagnostics")
    public R<List<Map<String,Object>>> diagnostics(@RequestParam("company_code") String company,@RequestBody List<Map<String,Object>> keys){return R.ok(diagnostics.facts(company,keys));}
    private String signalCompany(String company){if(company==null||!company.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("公司无效");return company.toUpperCase(Locale.ROOT);}
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @InnerAuth @GetMapping("/allocation-stock-signals") public R<List<Map<String,Object>>> stockSignals(@RequestParam("company_code") String company){return R.ok(jdbc.queryForList("SELECT sku_sn,store_code,revision FROM oms_allocation_stock_signal WHERE company_code=? AND revision>acknowledged_revision ORDER BY update_time,sku_sn,store_code LIMIT 50",signalCompany(company)));}
    @InnerAuth @PostMapping("/allocation-stock-signals/ack") public R<Map<String,Object>> ackStockSignal(@RequestParam("company_code") String company,@RequestBody Map<String,Object> signal){jdbc.update("UPDATE oms_allocation_stock_signal SET acknowledged_revision=GREATEST(acknowledged_revision,?) WHERE company_code=? AND sku_sn=? AND store_code=? AND revision>=?",signal.get("revision"),signalCompany(company),signal.get("sku_sn"),signal.get("store_code"),signal.get("revision"));return R.ok(Collections.emptyMap());}
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
    @InnerAuth @PostMapping("/warehouse-allocation-preview")
    public R<Map<String,Object>> preview(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(warehouses.preview(company,command));}
    @InnerAuth @PostMapping("/warehouse-allocate")
    public R<List<Map<String,Object>>> allocate(@RequestParam("company_code") String company,@RequestBody Map<String,Object> command){return R.ok(warehouses.allocate(company,command));}
    @InnerAuth @GetMapping("/demands")
    public R<List<Map<String,Object>>> demands(@RequestParam("company_code") String company,@RequestParam("channel_id") long channel,@RequestParam("platform_tid") String tid){return R.ok(service.demands(company,channel,tid));}
    @InnerAuth @PostMapping("/{action}")
    public R<List<Map<String,Object>>> execute(@PathVariable String action,@RequestParam("company_code") String company,@RequestBody OrderStockCommand command){return R.ok(service.execute(company,action,command));}
}
