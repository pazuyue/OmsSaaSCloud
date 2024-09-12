package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.NoTicketExcel;
import com.oms.supplychain.model.entity.warehouse.NoTicketsGoodsTmp;

import java.util.List;

/**
 * 入库通知单明细-未送审Service接口
 *
 * @author ruoyi
 * @date 2024-09-03
 */
public interface INoTicketsGoodsTmpService extends IService<NoTicketsGoodsTmp>
{
    /**
     * 查询入库通知单明细-未送审
     *
     * @param id 入库通知单明细-未送审主键
     * @return 入库通知单明细-未送审
     */
    public NoTicketsGoodsTmp selectNoTicketsGoodsTmpById(Long id);

    /**
     * 查询入库通知单明细-未送审列表
     *
     * @param noTicketsGoodsTmp 入库通知单明细-未送审
     * @return 入库通知单明细-未送审集合
     */
    public List<NoTicketsGoodsTmp> selectNoTicketsGoodsTmpList(NoTicketsGoodsTmp noTicketsGoodsTmp);

    /**
     * 新增入库通知单明细-未送审
     *
     * @param noTicketsGoodsTmp 入库通知单明细-未送审
     * @return 结果
     */
    public int insertNoTicketsGoodsTmp(NoTicketsGoodsTmp noTicketsGoodsTmp);

    public boolean batchInsertNoTicketsGoodsTmp(List<NoTicketExcel> noTicketGoodsList,String noSn,String companyCode);

    /**
     * 修改入库通知单明细-未送审
     *
     * @param noTicketsGoodsTmp 入库通知单明细-未送审
     * @return 结果
     */
    public int updateNoTicketsGoodsTmp(NoTicketsGoodsTmp noTicketsGoodsTmp,String companyCode);

    /**
     * 批量删除入库通知单明细-未送审
     *
     * @param ids 需要删除的入库通知单明细-未送审主键集合
     * @return 结果
     */
    public int deleteNoTicketsGoodsTmpByIds(Long[] ids);

    /**
     * 删除入库通知单明细-未送审信息
     *
     * @param id 入库通知单明细-未送审主键
     * @return 结果
     */
    public int deleteNoTicketsGoodsTmpById(Long id);
}

