package com.oms.supplychain.mapper.warehouse;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.supplychain.model.dto.warehouse.WmsTicketsDto;
import com.oms.supplychain.model.entity.warehouse.WmsTickets;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 出入库单 Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-08-03
 */
public interface WmsTicketsMapper extends BaseMapper<WmsTickets> {

    @Select("select * from wms_tickets WHERE sn =#{sn}")
    @Results({
            @Result(property = "sn", column = "sn"),
            @Result(property = "wmsTicketsGoodsList", column = "sn", javaType = List.class,
                    many = @Many(select = "com.oms.supplychain.mapper.warehouse.WmsTicketsGoodsMapper.selectBySn"))
    })
    WmsTicketsDto getOneWithDetali(String sn);
}
