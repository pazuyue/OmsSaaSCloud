package com.oms.order.controller;

import com.oms.order.service.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@RestController @RequestMapping("/sharedCarriers")
public class SharedCarrierController {
    @Resource private SharedCarrierService carriers;
    @Resource private SharedCarrierWork work;
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    private String actor(){return SecurityUtils.getUsername();}
    private void edit(String type,Map<String,Object> body){String permission="mapping".equals(type)?"WMS".equals(text(body,"system_type"))?"wmsEdit":"channelEdit":"directory".equals(type)?"WMS".equals(text(body,"system_type"))?"wmsEdit":"channelEdit":type+"Edit";AuthUtil.checkPermi("common:carrier:"+permission);}
    @RequiresPermissions("common:carrier:query") @GetMapping("/{type}/list") public AjaxResult list(@PathVariable String type,@RequestParam Map<String,String> query){if("audit".equals(type))AuthUtil.checkPermi("common:carrier:audit");return AjaxResult.success(carriers.page(company(),type,query));}
    @RequiresPermissions(value={"common:carrier:query","order:order:query","order:refund:query","order:exchange:query","warehouse:purchaseReturn:query","warehouse:return:query"},logical=Logical.OR)
    @GetMapping("/standards/search") public AjaxResult search(@RequestParam(defaultValue="") String keyword){Map<String,String> q=new HashMap<>();q.put("keyword",keyword);q.put("pageSize","30");return AjaxResult.success(carriers.page(company(),"standard",q));}
    @RequiresPermissions(value={"common:carrier:query","order:order:query","order:refund:query","order:exchange:query","warehouse:purchaseReturn:query","warehouse:return:query"},logical=Logical.OR)
    @GetMapping("/standards/{id}") public AjaxResult standard(@PathVariable long id){return AjaxResult.success(carriers.configuration(company(),"standard",id));}
    @RequiresPermissions("common:carrier:query") @GetMapping("/metadata") public AjaxResult metadata(){return AjaxResult.success(carriers.metadata(company()));}
    @PostMapping("/{type}/save") public AjaxResult save(@PathVariable String type,@RequestBody Map<String,Object> body){edit(type,body);if(number(body,"id")>0)edit(type,carriers.configuration(company(),type,number(body,"id")));return AjaxResult.success(carriers.save(company(),type,body,actor()));}
    @RequiresPermissions(value={"common:carrier:query","order:order:query","order:refund:query","order:exchange:query","warehouse:purchaseReturn:query","warehouse:return:query"},logical=Logical.OR)
    @PostMapping("/options") public AjaxResult options(@RequestBody Map<String,Object> body){return AjaxResult.success(carriers.options(company(),body));}
    @RequiresPermissions("common:carrier:query") @PostMapping("/preview") public AjaxResult preview(@RequestBody Map<String,Object> body){return AjaxResult.success(carriers.preview(company(),body));}
    @RequiresPermissions("common:carrier:query") @GetMapping("/{type}/{id}/impact") public AjaxResult impact(@PathVariable String type,@PathVariable long id){return AjaxResult.success(carriers.impact(company(),type,id));}
    @RequiresPermissions("common:carrier:query") @PostMapping("/mapping/output-options") public AjaxResult outputOptions(@RequestBody Map<String,Object> body){return AjaxResult.success(carriers.outputOptions(company(),body));}
    @RequiresPermissions("common:carrier:query") @PostMapping("/mapping/suggestions") public AjaxResult suggestions(@RequestBody List<Long> ids){return AjaxResult.success(carriers.suggestions(company(),ids));}
    @PostMapping("/mapping/batch-preview") public AjaxResult mappingPreview(@RequestBody Map<String,Object> body){edit("mapping",body);return AjaxResult.success(carriers.mappingPreview(company(),body,actor()));}
    @PostMapping("/mapping/batch-confirm") public AjaxResult mappingConfirm(@RequestBody Map<String,Object> body){edit("mapping",carriers.importScope(company(),text(body,"request_key")));return AjaxResult.success(carriers.mappingConfirm(company(),text(body,"request_key"),actor()));}
    @PostMapping("/directory/import-preview") public AjaxResult importPreview(@RequestBody Map<String,Object> body){edit("directory",body);return AjaxResult.success(carriers.importPreview(company(),body,actor()));}
    @PostMapping("/directory/import-confirm") public AjaxResult importConfirm(@RequestBody Map<String,Object> body){edit("directory",carriers.importScope(company(),text(body,"request_key")));return AjaxResult.success(carriers.importConfirm(company(),text(body,"request_key"),actor()));}
    @RequiresPermissions("common:carrier:todoHandle") @PostMapping("/todo/{id}/revalidate") public AjaxResult revalidate(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(carriers.revalidate(company(),id,number(body,"revision"),actor()));}
    @RequiresPermissions("common:carrier:todoHandle") @PostMapping("/todo/{id}/retry") public AjaxResult retry(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(work.retry(company(),id,number(body,"revision"),actor()));}
    @PostMapping("/directory/sync") public AjaxResult sync(@RequestBody Map<String,Object> body){edit("directory",body);return AjaxResult.success(work.sync(company(),body,actor()));}
    @InnerAuth @PostMapping("/internal/options") public R<Map<String,Object>> internalOptions(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){return R.ok(carriers.options(OrderValues.company(company),body));}
    @InnerAuth @GetMapping("/internal/standards") public R<Map<String,Object>> standards(@RequestParam("company_code") String company,@RequestParam(defaultValue="") String keyword){Map<String,String> q=new HashMap<>();q.put("keyword",keyword);q.put("pageSize","30");return R.ok(carriers.page(OrderValues.company(company),"standard",q));}
    @InnerAuth @GetMapping("/internal/standard/{id}") public R<Map<String,Object>> internalStandard(@RequestParam("company_code") String company,@PathVariable long id){return R.ok(carriers.configuration(OrderValues.company(company),"standard",id));}
    @InnerAuth @PostMapping("/internal/convert") public R<Map<String,Object>> internalConvert(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){return R.ok(carriers.convert(OrderValues.company(company),body));}
    @InnerAuth @PostMapping("/internal/freeze/{request}") public R<Map<String,Object>> freeze(@RequestParam("company_code") String company,@PathVariable String request,@RequestBody Map<String,Object> body){return R.ok(carriers.freeze(OrderValues.company(company),request,body,"业务自动流程"));}
    @InnerAuth @PostMapping("/internal/facts") public R<Map<String,Object>> fact(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){return R.ok(work.fact(OrderValues.company(company),body,"业务自动流程"));}
    @ExceptionHandler(IllegalArgumentException.class) public AjaxResult invalid(IllegalArgumentException e){return AjaxResult.error(e.getMessage());}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public AjaxResult duplicate(){return AjaxResult.error("同公司已存在该编码或能力配置，请刷新核对");}
}
