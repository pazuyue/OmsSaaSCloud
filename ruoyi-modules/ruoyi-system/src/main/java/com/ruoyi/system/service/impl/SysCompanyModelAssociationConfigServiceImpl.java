package com.ruoyi.system.service.impl;

import java.util.List;
import com.ruoyi.common.core.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.SysCompanyModelAssociationConfigMapper;
import com.ruoyi.system.domain.SysCompanyModelAssociationConfig;
import com.ruoyi.system.service.ISysCompanyModelAssociationConfigService;

/**
 * 公司-OMS系统插件关联Service业务层处理
 *
 * @author ruoyi
 * @date 2024-07-26
 */
@Service
public class SysCompanyModelAssociationConfigServiceImpl implements ISysCompanyModelAssociationConfigService
{
    @Autowired
    private SysCompanyModelAssociationConfigMapper sysCompanyModelAssociationConfigMapper;

    /**
     * 查询公司-OMS系统插件关联
     *
     * @param id 公司-OMS系统插件关联主键
     * @return 公司-OMS系统插件关联
     */
    @Override
    public SysCompanyModelAssociationConfig selectSysCompanyModelAssociationConfigById(Integer id)
    {
        return sysCompanyModelAssociationConfigMapper.selectSysCompanyModelAssociationConfigById(id);
    }

    /**
     * 查询公司-OMS系统插件关联列表
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 公司-OMS系统插件关联
     */
    @Override
    public List<SysCompanyModelAssociationConfig> selectSysCompanyModelAssociationConfigList(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        return sysCompanyModelAssociationConfigMapper.selectSysCompanyModelAssociationConfigList(sysCompanyModelAssociationConfig);
    }

    /**
     * 新增公司-OMS系统插件关联
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 结果
     */
    @Override
    public int insertSysCompanyModelAssociationConfig(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        sysCompanyModelAssociationConfig.setCreateTime(DateUtils.getNowDate());
        return sysCompanyModelAssociationConfigMapper.insertSysCompanyModelAssociationConfig(sysCompanyModelAssociationConfig);
    }

    /**
     * 修改公司-OMS系统插件关联
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 结果
     */
    @Override
    public int updateSysCompanyModelAssociationConfig(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig)
    {
        return sysCompanyModelAssociationConfigMapper.updateSysCompanyModelAssociationConfig(sysCompanyModelAssociationConfig);
    }

    /**
     * 批量删除公司-OMS系统插件关联
     *
     * @param ids 需要删除的公司-OMS系统插件关联主键
     * @return 结果
     */
    @Override
    public int deleteSysCompanyModelAssociationConfigByIds(Integer[] ids)
    {
        return sysCompanyModelAssociationConfigMapper.deleteSysCompanyModelAssociationConfigByIds(ids);
    }

    /**
     * 删除公司-OMS系统插件关联信息
     *
     * @param id 公司-OMS系统插件关联主键
     * @return 结果
     */
    @Override
    public int deleteSysCompanyModelAssociationConfigById(Integer id)
    {
        return sysCompanyModelAssociationConfigMapper.deleteSysCompanyModelAssociationConfigById(id);
    }
}

