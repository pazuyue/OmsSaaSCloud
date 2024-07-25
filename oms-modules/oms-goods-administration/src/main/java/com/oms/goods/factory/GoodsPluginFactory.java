package com.oms.goods.factory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.oms.common.model.entity.CompanyModelAssociationInfo;
import com.oms.goods.mapper.CompanyModelAssociationInfoMapper;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.model.enums.GoodsPluginEnum;
import com.oms.goods.service.plugin.GoodsPluginService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GoodsPluginFactory {
    @Resource
    private Map<String, GoodsPluginService> serviceMap = new ConcurrentHashMap<>();
    @Resource
    private CompanyModelAssociationInfoMapper companyModelAssociationInfoMapper;

    public GoodsPluginService getBean(String companyCode) {
        // 初始化为Common，如果未来有更多类型，考虑将其抽象为一个枚举或常量集合
        String type = GoodsPluginEnum.Type.Common;
        // 使用日志记录公司信息，即使为null，也清晰表明了这一事实
        log.info("Attempting to fetch companyModelAssociationInfo for companyCode: {}", companyCode);

        // 查询公司关联信息
        QueryWrapper<CompanyModelAssociationInfo> queryWrapper = Wrappers.query();
        queryWrapper.eq("company_code", companyCode);
        CompanyModelAssociationInfo companyModelAssociationInfo = companyModelAssociationInfoMapper.selectOne(queryWrapper);

        if (companyModelAssociationInfo != null) {
            // 订单转移模型处理，考虑未来可能的拓展性
            int orderTransferModel = companyModelAssociationInfo.getOrderTransferModel();
            switch (orderTransferModel) {
                case 2:
                    type = GoodsPluginEnum.Type.HM;
                    break;
                // 如果未来有更多情况，可以在这里添加case语句，或者考虑使用策略模式等设计模式进行优化
                default:
                    // 明确指出默认行为是保持为Common
                    log.info("OrderTransferModel is not recognized, falling back to Common type.");
                    break;
            }
        } else {
            // 如果查询结果为null，明确记录此信息，有助于问题排查
            log.info("companyModelAssociationInfo is null for companyCode: {}", companyCode);
        }

        // 记录最终type的值，有助于调试和问题排查
        log.info("Final type selected: {}", type);

        // 获取服务实例，考虑到可能的null值，这里添加了null检查
        GoodsPluginService service = serviceMap.get(type);
        if (service == null) {
            // 如果service为null，记录错误日志并处理异常（或返回null，具体取决于业务逻辑）
            log.error("No service found for type: {}", type);
            // 根据业务需求，这里可以选择抛出异常，或者返回一个默认的GoodsPluginService实例
            // 抛出异常示例：
             throw new RuntimeException("No service found for type: " + type);
            // 返回null示例：
            // return null;
        }
        return service;
    }

}
