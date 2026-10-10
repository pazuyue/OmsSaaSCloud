package com.oms.inventory.service.impl.rule;

import com.oms.inventory.model.dto.AllocationDraft;
import com.oms.inventory.service.rule.AllocationChannelClient;
import com.oms.inventory.service.rule.AllocationStoreClient;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/** Remote metadata reads happen before, never inside, inventory transactions. */
@Service
public class AllocationCatalog {
    @Resource private AllocationChannelClient channels;
    @Resource private AllocationStoreClient stores;

    private String company() {
        com.ruoyi.system.api.model.LoginUser user=com.ruoyi.common.security.utils.SecurityUtils.getLoginUser();
        String code=user.getCompanyCode();
        return code==null || code.isEmpty()?user.getSysUser().getLoginCompanyCode():code;
    }
    private String authorization() {return "Bearer "+com.ruoyi.common.security.utils.SecurityUtils.getToken();}

    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> lookup(boolean channel, String keyword) {
        return data(channel ? channels.lookup(Collections.emptyList(),keyword,company(),authorization()) : stores.lookup(Collections.emptyList(),keyword,company(),authorization()));
    }

    public List<Map<String,Object>> lookupChannels(List<Integer> ids,String keyword) {
        return data(channels.lookup(ids,keyword,company(),authorization()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> data(AjaxResult response) {
        if (response == null || !Integer.valueOf(200).equals(response.get("code")))
            throw new IllegalArgumentException("渠道或仓库资料暂时不可用，请稍后重试");
        return (List<Map<String,Object>>) response.get("data");
    }

    public void validate(AllocationDraft draft) {
        if (!draft.getStores().isEmpty()) {
            Set<String> found=data(stores.lookup(draft.getStores(),"",company(),authorization())).stream()
                .map(r->String.valueOf(r.get("wmsSimulationCode"))).collect(Collectors.toSet());
            if (!found.containsAll(draft.getStores())) throw new IllegalArgumentException("包含不存在或不属于当前公司的仓库");
        }
        if (!draft.getChannels().isEmpty()) {
            List<Integer> ids=draft.getChannels().stream().map(AllocationDraft.Channel::getChannelId).collect(Collectors.toList());
            Map<Integer,String> names=new HashMap<>();
            for(Map<String,Object> row:data(channels.lookup(ids,"",company(),authorization()))) names.put(((Number)row.get("channelId")).intValue(),String.valueOf(row.get("channelName")));
            for(AllocationDraft.Channel c:draft.getChannels()) {
                if(!names.containsKey(c.getChannelId())) throw new IllegalArgumentException("包含不存在、停用或不属于当前公司的渠道");
                c.setChannelName(names.get(c.getChannelId()));
            }
        }
    }
}
