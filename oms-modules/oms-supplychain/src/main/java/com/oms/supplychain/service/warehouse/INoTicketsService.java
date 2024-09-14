package com.oms.supplychain.service.warehouse;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.oms.supplychain.model.entity.warehouse.NoTickets;

/**
 * 采购入库通知单Service接口
 *
 * @author ruoyi
 * @date 2024-08-25
 */
public interface INoTicketsService
{
    /**
     * 查询采购入库通知单
     *
     * @param id 采购入库通知单主键
     * @return 采购入库通知单
     */
    public NoTickets selectNoTicketsById(Integer id);


    public NoTickets getOne(QueryWrapper queryWrapper);
    /**
     * 查询采购入库通知单列表
     *
     * @param noTickets 采购入库通知单
     * @return 采购入库通知单集合
     */
    public List<NoTickets> selectNoTicketsList(NoTickets noTickets);

    /**
     * 新增采购入库通知单
     *
     * @param noTickets 采购入库通知单
     * @return 结果
     */
    public int insertNoTickets(NoTickets noTickets);

    /**
     * 修改采购入库通知单
     *
     * @param noTickets 采购入库通知单
     * @return 结果
     */
    public int updateNoTickets(NoTickets noTickets);

    public boolean updateNoTicketsByWrapper(NoTickets noTickets, UpdateWrapper updateWrapper);

    /**
     * 批量删除采购入库通知单
     *
     * @param ids 需要删除的采购入库通知单主键集合
     * @return 结果
     */
    public int deleteNoTicketsByIds(Integer[] ids);

    /**
     * 删除采购入库通知单信息
     *
     * @param id 采购入库通知单主键
     * @return 结果
     */
    public int deleteNoTicketsById(Integer id);
}

