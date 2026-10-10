package com.oms.supplychain.controller.warehouse;

import com.oms.supplychain.service.wms.*;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.auth.AuthUtil;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import javax.annotation.Resource;
import javax.sql.DataSource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

@RestController
public class WmsIntegrationController {
    @Resource private WmsConnections connections;
    @Resource private WmsInboundService inbound;
    @Resource private WmsInteractionLog logs;
    @Resource private DataSource dataSource;
    @Resource private WmsTenantRoutes tenantRoutes;
    private String company(){return WarehouseCompany.current();}
    @GetMapping("/wmsIntegration/connections") public AjaxResult connections(){AuthUtil.checkPermiOr("warehouse:wms:config","warehouse:simulationStoreInfo:edit","warehouse:simulationStoreInfo:add");return AjaxResult.success(connections.list(company()));}
    @PostMapping("/wmsIntegration/connections") public AjaxResult save(@RequestBody Map<String,Object> body){AuthUtil.checkPermi("warehouse:wms:config");return AjaxResult.success(connections.save(company(),body));}
    @GetMapping("/wmsIntegration/tickets/{id}") public AjaxResult task(@PathVariable long id){AuthUtil.checkPermiOr("warehouse:tickets:query","warehouse:poInfo:query");return AjaxResult.success(inbound.task(company(),id));}
    @PostMapping("/wmsIntegration/tickets/{id}/{action}") public AjaxResult action(@PathVariable long id,@PathVariable String action){if(!"cancel".equals(action))AuthUtil.checkPermi("warehouse:tickets:retry");switch(action){case "retry":inbound.retry(company(),id);break;case "query":inbound.query(company(),id);break;case "cancel":AuthUtil.checkPermi("warehouse:tickets:remove");inbound.cancel(company(),id);break;default:throw new IllegalArgumentException("未知仓库操作");}return AjaxResult.success();}
    @GetMapping("/wmsIntegration/logs") public AjaxResult logs(@RequestParam(defaultValue="") String sn,@RequestParam(defaultValue="1") int page){AuthUtil.checkPermi("warehouse:wms:log");return AjaxResult.success(logs.list(company(),sn,page));}
    @GetMapping("/wmsIntegration/logs/{id}") public AjaxResult log(@PathVariable long id){AuthUtil.checkPermi("warehouse:wms:payload");return AjaxResult.success(logs.detail(company(),id));}
    /** Only this exact public route bypasses user login. Provider signatures authenticate the body. */
    @PostMapping("/wmsCallback/{route}/{key}") public ResponseEntity<String> callback(@PathVariable String route,@PathVariable String key,@RequestBody String body,HttpServletRequest request){
        require(route.matches("[A-Za-z0-9_-]{1,64}")&&key.matches("[a-f0-9]{32}"),"无效回调地址");require(body.length()<=1024*1024,"回传报文超过1MB");
        require(dataSource instanceof DynamicRoutingDataSource,"未配置租户数据源");DynamicRoutingDataSource routing=(DynamicRoutingDataSource)dataSource;
        String datasource=tenantRoutes.datasource(route);
        require(routing.getDataSources().containsKey(datasource)&&routing.getDataSource(datasource)==routing.getDataSources().get(datasource),"回调租户数据源不存在或不唯一");
        Map<String,String> params=new TreeMap<>();request.getParameterMap().forEach((k,v)->{require(v.length==1,"回传参数不能重复");params.put(k,v[0]);});
        DynamicDataSourceContextHolder.push(datasource);
        try {String result=inbound.callback(route.toUpperCase(Locale.ROOT),key,params,body);return ResponseEntity.ok().header("Content-Type",result.startsWith("<")?"application/xml;charset=UTF-8":"application/json;charset=UTF-8").body(result);}
        finally{DynamicDataSourceContextHolder.clear();}
    }
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException error){return AjaxResult.error(error.getMessage());}
}
