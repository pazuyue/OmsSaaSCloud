package com.oms.common.api;

import com.oms.common.factory.RemoteChannelFallbackFactory;
import com.oms.common.factory.RemoteGoodsFallbackFactory;
import com.oms.common.model.entity.channel.TChannel;
import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteChannelService", value = ServiceNameConstants.OMS_CHANNEL_SERVICE, fallbackFactory = RemoteChannelFallbackFactory.class)
public interface RemoteChannelService {
    @PostMapping("/api/channel/list")
    public R<TChannel> selectChannelList(TChannel tChannel,@RequestParam("company_code") String company_code);
}
