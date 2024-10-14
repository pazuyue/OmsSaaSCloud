package com.oms.supplychain.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.supplychain.model.entity.warehouse.WmsTicketsGoods;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 出入库明细表表 Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
public interface WmsTicketsGoodsMapper extends BaseMapper<WmsTicketsGoods> {

    @Select("select * from wms_tickets_goods WHERE sn =#{sn}")
    List<WmsTicketsGoods> selectBySn(String sn);
}
