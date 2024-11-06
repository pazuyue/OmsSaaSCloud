package com.oms.channel.controller;

import java.util.List;
import java.io.IOException;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;

import com.oms.channel.model.entity.TChannel;
import com.oms.channel.service.ITChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 店铺信息Controller
 *
 * @author ruoyi
 * @date 2024-11-06
 */
@RestController
@RequestMapping("/channel")
public class TChannelController extends BaseController
{
    @Resource
    private ITChannelService tChannelService;

    /**
     * 查询店铺信息列表
     */
    @RequiresPermissions("channel:channel:list")
    @GetMapping("/list")
    public TableDataInfo list(TChannel tChannel)
    {
        startPage();
        List<TChannel> list = tChannelService.selectTChannelList(tChannel);
        return getDataTable(list);
    }

    /**
     * 导出店铺信息列表
     */
    @RequiresPermissions("channel:channel:export")
    @Log(title = "店铺信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, TChannel tChannel)
    {
        List<TChannel> list = tChannelService.selectTChannelList(tChannel);
        ExcelUtil<TChannel> util = new ExcelUtil<TChannel>(TChannel.class);
        util.exportExcel(response, list, "店铺信息数据");
    }

    /**
     * 获取店铺信息详细信息
     */
    @RequiresPermissions("channel:channel:query")
    @GetMapping(value = "/{channelId}")
    public AjaxResult getInfo(@PathVariable("channelId") Integer channelId)
    {
        return success(tChannelService.selectTChannelByChannelId(channelId));
    }

    /**
     * 新增店铺信息
     */
    @RequiresPermissions("channel:channel:add")
    @Log(title = "店铺信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody TChannel tChannel)
    {
        return toAjax(tChannelService.insertTChannel(tChannel));
    }

    /**
     * 修改店铺信息
     */
    @RequiresPermissions("channel:channel:edit")
    @Log(title = "店铺信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody TChannel tChannel)
    {
        return toAjax(tChannelService.updateTChannel(tChannel));
    }

    /**
     * 删除店铺信息
     */
    @RequiresPermissions("channel:channel:remove")
    @Log(title = "店铺信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{channelIds}")
    public AjaxResult remove(@PathVariable Integer[] channelIds)
    {
        return toAjax(tChannelService.deleteTChannelByChannelIds(channelIds));
    }
}
