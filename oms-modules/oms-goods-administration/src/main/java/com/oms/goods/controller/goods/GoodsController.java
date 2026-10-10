package com.oms.goods.controller.goods;
import com.oms.goods.service.goods.GoodsCompany;
import com.oms.goods.service.goods.impl.GoodsWorkspaceService;
import com.oms.goods.model.vo.export.GoodsExportRow;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.model.vo.export.GoodsImportError;
import com.oms.goods.model.dto.export.ExportGoodsRequestDTO;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/goodsAdministration")
public class GoodsController extends BaseController {
    @Resource private GoodsWorkspaceService workspace;
    @RequiresPermissions("goods:info:import") @PostMapping("/import")
    @Log(title="商品导入预览",businessType=BusinessType.IMPORT)
    public AjaxResult preview(@RequestParam MultipartFile file,@RequestParam(required=false) String company_code) throws Exception {
        String company=GoodsCompany.check(company_code);
        if(file.isEmpty() || file.getSize()>10*1024*1024)throw new IllegalArgumentException("请选择不超过 10 MB 的 Excel 文件");
        String name=file.getOriginalFilename();
        if(name==null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(xls|xlsx)$"))throw new IllegalArgumentException("仅支持 xls、xlsx 文件");
        List<GoodsVO> rows=com.oms.goods.service.goods.GoodsImportReader.read(file.getInputStream());
        return success(workspace.preview(company,rows,SecurityUtils.getUsername()));
    }
    @RequiresPermissions("goods:info:import") @PostMapping("/list")
    public TableDataInfo list(@RequestBody ExportGoodsRequestDTO filter,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="false") boolean errorsOnly) {
        return workspace.previewRows(GoodsCompany.current(),filter.getImportBatch(),pageNum,pageSize,errorsOnly);
    }
    @RequiresPermissions("goods:info:import") @GetMapping("/batch")
    public AjaxResult batch(@RequestParam("import_batch") String batch){return success(workspace.batch(GoodsCompany.current(),batch));}
    @RequiresPermissions("goods:info:import") @PostMapping("/errors")
    @Log(title="商品导入错误明细",businessType=BusinessType.EXPORT)
    public void errors(HttpServletResponse response,@RequestParam("import_batch") String batch) {
        List<GoodsImportError> rows=workspace.importErrors(GoodsCompany.current(),batch).stream().map(r->workspace.convert(r,GoodsImportError.class)).collect(Collectors.toList());
        new ExcelUtil<>(GoodsImportError.class).exportExcel(response,rows,"商品导入错误明细");
    }
    @RequiresPermissions("goods:info:import") @PostMapping("/toExamine")
    @Log(title="商品导入确认",businessType=BusinessType.IMPORT)
    public AjaxResult confirm(@RequestParam("import_batch") String batch,@RequestParam(required=false) String company_code){return success(workspace.confirm(GoodsCompany.check(company_code),batch,SecurityUtils.getUsername()));}
    @RequiresPermissions("goods:info:import") @PostMapping("/importTemplate")
    public void template(HttpServletResponse response){new ExcelUtil<>(GoodsVO.class).importTemplateExcel(response,"商品导入模板");}
}
