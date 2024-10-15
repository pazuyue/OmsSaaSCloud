package com.oms.supplychain.service.warehouse;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;

import java.util.List;

/**
 * <p>
 * 出入库单 服务类
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
public interface IWmsTicketsService extends IService<WmsTickets> {

    public WmsTickets getOne(String sn);

    /**
     * 查询出入库单
     *
     * @param id 出入库单主键
     * @return 出入库单
     */
    public WmsTickets selectWmsTicketsById(Integer id);

    /**
     * 查询出入库单列表
     *
     * @param wmsTickets 出入库单
     * @return 出入库单集合
     */
    public List<WmsTickets> selectWmsTicketsList(WmsTickets wmsTickets);

    /**
     * 新增出入库单
     *
     * @param wmsTickets 出入库单
     * @return 结果
     */
    public int insertWmsTickets(WmsTickets wmsTickets);

    /**
     * 修改出入库单
     *
     * @param wmsTickets 出入库单
     * @return 结果
     */
    public int updateWmsTickets(WmsTickets wmsTickets);

    /**
     * 批量删除出入库单
     *
     * @param ids 需要删除的出入库单主键集合
     * @return 结果
     */
    public int deleteWmsTicketsByIds(Integer[] ids);

    /**
     * 删除出入库单信息
     *
     * @param id 出入库单主键
     * @return 结果
     */
    public int deleteWmsTicketsById(Integer id);

    public boolean notice(String sn);

    public boolean cGInventoryCallback(String sn);
}
