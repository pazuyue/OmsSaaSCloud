package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.SysCompanyModelAssociationConfig;

/**
 * 公司-OMS系统插件关联Mapper接口
 *
 * @author ruoyi
 * @date 2024-07-26
 */
public interface SysCompanyModelAssociationConfigMapper
{
    /**
     * 查询公司-OMS系统插件关联
     *
     * @param id 公司-OMS系统插件关联主键
     * @return 公司-OMS系统插件关联
     */
    public SysCompanyModelAssociationConfig selectSysCompanyModelAssociationConfigById(Integer id);

    /**
     * 查询公司-OMS系统插件关联列表
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 公司-OMS系统插件关联集合
     */
    public List<SysCompanyModelAssociationConfig> selectSysCompanyModelAssociationConfigList(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig);

    /**
     * 新增公司-OMS系统插件关联
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 结果
     */
    public int insertSysCompanyModelAssociationConfig(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig);

    /**
     * 修改公司-OMS系统插件关联
     *
     * @param sysCompanyModelAssociationConfig 公司-OMS系统插件关联
     * @return 结果
     */
    public int updateSysCompanyModelAssociationConfig(SysCompanyModelAssociationConfig sysCompanyModelAssociationConfig);

    /**
     * 删除公司-OMS系统插件关联
     *
     * @param id 公司-OMS系统插件关联主键
     * @return 结果
     */
    public int deleteSysCompanyModelAssociationConfigById(Integer id);

    /**
     * 批量删除公司-OMS系统插件关联
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSysCompanyModelAssociationConfigByIds(Integer[] ids);
}
