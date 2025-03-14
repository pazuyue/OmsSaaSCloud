package com.oms.inventory.service.rule;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.inventory.model.dto.rule.RuleDetailsInfoDto;
import com.oms.inventory.model.entity.rule.RuleStockInfo;

import java.util.List;

public interface IRuleStockInfoService extends IService<RuleStockInfo> {
    /**
     * 查询分货单基础信息
     *
     * @param id 分货单基础信息主键
     * @return 分货单基础信息
     */
    public RuleStockInfo selectRuleStockInfoById(Long id);

    /**
     * 查询分货单基础信息列表
     *
     * @param ruleStockInfo 分货单基础信息
     * @return 分货单基础信息集合
     */
    public List<RuleStockInfo> selectRuleStockInfoList(RuleStockInfo ruleStockInfo);

    /**
     * 新增分货单基础信息
     *
     * @param ruleStockInfo 分货单基础信息
     * @return 结果
     */
    public int insertRuleStockInfo(RuleStockInfo ruleStockInfo);

    /**
     * 修改分货单基础信息
     *
     * @param ruleStockInfo 分货单基础信息
     * @return 结果
     */
    public int updateRuleStockInfo(RuleStockInfo ruleStockInfo);

    /**
     * 批量删除分货单基础信息
     *
     * @param ids 需要删除的分货单基础信息主键集合
     * @return 结果
     */
    public int deleteRuleStockInfoByIds(Long[] ids);

    /**
     * 删除分货单基础信息信息
     *
     * @param id 分货单基础信息主键
     * @return 结果
     */
    public int deleteRuleStockInfoById(Long id);
}
