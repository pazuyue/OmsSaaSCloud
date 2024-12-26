package com.oms.channel.controller.api;

import com.oms.channel.model.entity.TChannel;
import com.oms.channel.service.ITChannelService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/channel")
public class RemoteTChannelController extends BaseController {

    @Resource
    private ITChannelService tChannelService;
    /**
     * 查询店铺信息列表
     */
    @GetMapping("/list")
    public TableDataInfo list(TChannel tChannel)
    {
        List<TChannel> list = tChannelService.selectTChannelList(tChannel);
        return getDataTable(list);
    }
}
