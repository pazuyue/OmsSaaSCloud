package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.*;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;
import static com.oms.supplychain.service.wms.WmsStore.*;

@RestController @RequestMapping("/shipmentWms/internal")
public class OrderShipmentWmsController {
    @Resource private WmsOutboundService outbound;
    @Resource private WmsOutboundReceiptService receipts;
    @Resource private SalesOutboundDocumentService salesDocuments;
    @InnerAuth @GetMapping("/reviews") public R<List<Map<String,Object>>> reviews(@RequestParam("company_code") String company,@RequestParam("shipment_id") long shipment){return scoped(company,()->receipts.reviews(company.toUpperCase(Locale.ROOT),shipment));}
    @InnerAuth @PostMapping("/reviews/{review}") public R<Map<String,Object>> review(@RequestParam("company_code") String company,@RequestParam("shipment_id") long shipment,@PathVariable long review,@RequestBody Map<String,Object> body){return scoped(company,()->receipts.review(company.toUpperCase(Locale.ROOT),shipment,review,body));}
    @InnerAuth @PostMapping("/sales-document") public R<Boolean> salesDocument(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){return scoped(company,()->salesDocuments.sync(company,body));}
    @InnerAuth @GetMapping("/receipts") public R<List<Map<String,Object>>> receipts(@RequestParam("company_code") String company,@RequestParam(value="shipment_sn",defaultValue="") String sn,@RequestParam(value="include_failed",defaultValue="false") boolean failed){require(sn.isEmpty()||sn.matches("[A-Za-z0-9_-]{1,64}"),"发货单号无效");return scoped(company,()->receipts.pending(company.toUpperCase(Locale.ROOT),sn,failed));}
    @InnerAuth @PostMapping("/receipts/{event}/result") public R<Boolean> result(@RequestParam("company_code") String company,@PathVariable long event,@RequestBody Map<String,Object> body){require(body.get("success") instanceof Boolean,"回传结果参数无效");return scoped(company,()->{receipts.posted(company.toUpperCase(Locale.ROOT),event,Boolean.TRUE.equals(body.get("success")),text(body.get("message")));return true;});}
    @Resource private WmsTenantRoutes routes;
    @Resource private DataSource dataSource;
    private <T> R<T> scoped(String company,Supplier<T> work){require(company.matches("[A-Za-z0-9_-]{1,50}"),"公司编码无效");require(dataSource instanceof DynamicRoutingDataSource,"租户数据源未配置");DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;String datasource=routes.datasource(company);require(routing.getDataSources().containsKey(datasource)&&routing.getDataSource(datasource)==routing.getDataSources().get(datasource),"仓库租户数据源不存在或不唯一");DynamicDataSourceContextHolder.push(datasource);try{return R.ok(work.get());}finally{DynamicDataSourceContextHolder.poll();}}
    @InnerAuth @PostMapping("/resolve") public R<Map<String,Object>> resolve(@RequestParam("company_code") String company,@RequestBody Map<String,Object> payload){return scoped(company,()->outbound.resolve(company.toUpperCase(Locale.ROOT),payload));}
    @SuppressWarnings("unchecked") @InnerAuth @PostMapping("/submit") public R<Map<String,Object>> submit(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){require(body.get("payload") instanceof Map,"下发快照无效");return scoped(company,()->outbound.submit(company.toUpperCase(Locale.ROOT),(Map<String,Object>)body.get("payload"),Boolean.TRUE.equals(body.get("retry"))));}
    @InnerAuth @PostMapping("/query") public R<Map<String,Object>> query(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){String sn=required(body,"shipment_sn",64);require(sn.matches("[A-Za-z0-9_-]{1,64}"),"发货单号无效");return scoped(company,()->outbound.query(company.toUpperCase(Locale.ROOT),sn));}
    @ExceptionHandler(IllegalArgumentException.class) public R<Object> invalid(IllegalArgumentException e){return R.fail(e.getMessage());}
}
