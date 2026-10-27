package com.oms.order.controller;
import com.oms.order.service.*;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/returns") public class ReturnManagementController {
    @Resource ReturnQueryService query;
    @RequiresPermissions("order:return:query") @GetMapping("/document") public AjaxResult document(@RequestParam String sn,@RequestParam(defaultValue="SUMMARY") String section,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(query.document(company(),sn,section,pageNum,pageSize));}
    private String company(){return OrderValues.company(SecurityUtils.getLoginUser().getCompanyCode());}
    @RequiresPermissions("order:return:list") @GetMapping public AjaxResult list(@RequestParam Map<String,String> filters){return AjaxResult.success(query.list(company(),filters,false));}
    @RequiresPermissions("order:return:query") @GetMapping("/detail") public AjaxResult detail(@RequestParam String view,@RequestParam String key,@RequestParam Map<String,String> filters){return AjaxResult.success(query.detail(company(),view,key,filters));}
    @RequiresPermissions("order:return:list") @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(query.catalog(company()));}
    @RequiresPermissions("order:return:export") @GetMapping("/export") public AjaxResult export(@RequestParam Map<String,String> filters){return AjaxResult.success(query.list(company(),filters,true));}
}
