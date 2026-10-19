package com.oms.supplychain.controller.warehouse;
import com.oms.supplychain.service.wms.*;
import com.oms.supplychain.service.warehouse.WarehouseCompany;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/wmsProcess") public class WmsProcessController {
 @Resource WmsProcessService service;
 private String company(boolean write){AuthUtil.checkPermi(write?"warehouse:process:edit":"warehouse:process:query");return WarehouseCompany.current();}
 @GetMapping public AjaxResult list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="1") int page){return AjaxResult.success(service.list(company(false),keyword,state,page));}
 @GetMapping("/options") public AjaxResult options(@RequestParam(defaultValue="") String keyword){return AjaxResult.success(service.options(company(false),keyword));}
 @GetMapping("/batches") public AjaxResult batches(@RequestParam String sku,@RequestParam String store){return AjaxResult.success(service.batches(company(false),sku,store));}
 @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(company(false),id));}
 @PostMapping public AjaxResult create(@RequestBody Map<String,Object> input){String c=company(true);long id=service.create(c,input,SecurityUtils.getUsername());return AjaxResult.success(service.detail(c,id));}
 @PostMapping("/{id}/{action}") public AjaxResult action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input){String c=company(true);if("event".equals(action))return AjaxResult.success(service.retryEvent(c,id,WmsStore.id(input.get("event_id")),false,"",SecurityUtils.getUsername()));if("review".equals(action))return AjaxResult.success(service.retryEvent(c,id,WmsStore.id(input.get("event_id")),true,WmsStore.text(input.get("reason")),SecurityUtils.getUsername()));long version=WmsStore.id(input.get("control_version"));if("reserve".equals(action))return AjaxResult.success(service.reserve(c,id,version));if("dispatch".equals(action))return AjaxResult.success(service.dispatch(c,id,version));if("stop".equals(action))return AjaxResult.success(service.stop(c,id,version,WmsStore.text(input.get("reason"))));throw new IllegalArgumentException("加工操作无效");}
}
