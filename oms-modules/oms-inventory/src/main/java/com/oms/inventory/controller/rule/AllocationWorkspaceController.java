package com.oms.inventory.controller.rule;

import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.model.dto.ReservationCommand;
import com.oms.inventory.service.impl.rule.AllocationReservationService;
import com.oms.inventory.model.vo.RuleStockExcel;
import com.oms.inventory.service.InventoryCompany;
import com.oms.inventory.service.impl.rule.AllocationCatalog;
import com.oms.inventory.service.impl.rule.AllocationWorkspaceService;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/allocation")
public class AllocationWorkspaceController {
    @Resource private AllocationWorkspaceService service;
    @Resource private AllocationCatalog catalog;
    @Resource private AllocationReservationService reservations;

    /** Integration entry for one order-line callback; company always comes from the authenticated user. */
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/reservation/{action}")
    @Log(title="订单使用锁库库存",businessType=BusinessType.UPDATE)
    public AjaxResult reservation(@PathVariable long id,@PathVariable String action,@RequestBody ReservationCommand command) {
        reservations.order(InventoryCompany.current(),id,action,command,SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    @RequiresPermissions("ruleStock:info:list")
    @GetMapping
    public TableDataInfo list(@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Integer status,
        @RequestParam(required=false) Integer allocationType,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return service.list(InventoryCompany.current(),keyword,status,allocationType,pageNum,pageSize);
    }
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable long id) {return AjaxResult.success(service.detail(InventoryCompany.current(),id));}
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/options/{type}")
    public AjaxResult options(@PathVariable String type,@RequestParam(defaultValue="") String keyword) {
        InventoryCompany.current();
        if(!Arrays.asList("channels","stores").contains(type) || keyword.length()>100)throw new IllegalArgumentException("查询条件无效");
        return AjaxResult.success(catalog.lookup(type.equals("channels"),keyword));
    }
    @RequiresPermissions("ruleStock:info:add")
    @PostMapping
    @Log(title="新建分货草稿",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody AllocationDraft draft) {
        if(draft.getId()!=null)throw new IllegalArgumentException("新建请求不能包含单据 ID");
        return saveDraft(draft);
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PutMapping("/{id}")
    @Log(title="保存分货草稿",businessType=BusinessType.UPDATE)
    public AjaxResult update(@PathVariable long id,@RequestBody AllocationDraft draft) {
        service.rule(InventoryCompany.current(),id,false);draft.setId(id);return saveDraft(draft);
    }
    private AjaxResult saveDraft(AllocationDraft draft) {
        String company=InventoryCompany.current();service.validateDraft(draft);catalog.validate(draft);
        long id=service.save(company,draft,SecurityUtils.getUsername());return AjaxResult.success(service.detail(company,id));
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/submit")
    @Log(title="提交分货审核",businessType=BusinessType.UPDATE)
    public AjaxResult submit(@PathVariable long id,@RequestParam int revision) {
        service.submit(InventoryCompany.current(),id,revision,false);return detail(id);
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/withdraw")
    @Log(title="撤回分货审核",businessType=BusinessType.UPDATE)
    public AjaxResult withdraw(@PathVariable long id,@RequestParam int revision) {
        service.submit(InventoryCompany.current(),id,revision,true);return detail(id);
    }
    @RequiresPermissions("ruleStock:info:remove")
    @DeleteMapping("/{id}")
    @Log(title="删除分货草稿",businessType=BusinessType.DELETE)
    public AjaxResult delete(@PathVariable long id,@RequestParam int revision) {
        service.delete(InventoryCompany.current(),id,revision);return AjaxResult.success();
    }
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/preview")
    public TableDataInfo preview(@PathVariable long id,@RequestParam int revision,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return service.preview(InventoryCompany.current(),id,revision,pageNum,pageSize);
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/start")
    @Log(title="执行或释放分货",businessType=BusinessType.UPDATE)
    @SuppressWarnings("unchecked")
    public AjaxResult start(@PathVariable long id,@RequestParam int revision,@RequestParam String action) {
        String company=InventoryCompany.current();
        if("EXECUTE".equals(action)) {
            Map<String,Object> detail=service.detail(company,id);
            AllocationDraft draft=new AllocationDraft();draft.setStores((List<String>)detail.get("stores"));
            for(Map<String,Object> row:(List<Map<String,Object>>)detail.get("channels")) {
                AllocationDraft.Channel c=new AllocationDraft.Channel();c.setChannelId(((Number)row.get("channelId")).intValue());draft.getChannels().add(c);
            }
            catalog.validate(draft);
        }
        service.start(company,id,revision,action,SecurityUtils.getUsername());return detail(id);
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/step")
    public AjaxResult step(@PathVariable long id) {return AjaxResult.success(service.step(InventoryCompany.current(),id,SecurityUtils.getUsername()));}
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/results")
    public TableDataInfo results(@PathVariable long id,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return service.results(InventoryCompany.current(),id,status,pageNum,pageSize);
    }
    @RequiresPermissions("ruleStock:info:list")
    @GetMapping("/{id}/goods")
    public TableDataInfo goods(@PathVariable long id,@RequestParam(defaultValue="") String skuSn,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return service.goods(InventoryCompany.current(),id,skuSn,pageNum,pageSize);
    }
    @RequiresPermissions("ruleStock:info:edit")
    @PostMapping("/{id}/goods")
    @Log(title="导入分货商品",businessType=BusinessType.IMPORT)
    public AjaxResult importGoods(@PathVariable long id,@RequestParam int revision,@RequestParam MultipartFile file) throws Exception {
        String company=InventoryCompany.current();service.rule(company,id,false);
        if(file.isEmpty() || file.getSize()>10*1024*1024)throw new IllegalArgumentException("请选择不超过 10 MB 的 Excel 文件");
        List<RuleStockExcel> rows=new ExcelUtil<>(RuleStockExcel.class).importExcel(file.getInputStream());
        int count=service.importGoods(company,id,revision,rows.stream().map(RuleStockExcel::getSkuSn).collect(Collectors.toList()));
        Map<String,Object> result=service.detail(company,id);result.put("imported",count);result.put("duplicates",rows.size()-count);return AjaxResult.success(result);
    }
    @RequiresPermissions("ruleStock:info:list")
    @PostMapping("/template")
    public void template(HttpServletResponse response) {new ExcelUtil<>(RuleStockExcel.class).importTemplateExcel(response,"分货商品模板");}
}
