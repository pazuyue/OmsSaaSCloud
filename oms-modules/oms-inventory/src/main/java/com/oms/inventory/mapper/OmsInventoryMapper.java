package com.oms.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.inventory.model.entity.OmsInventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


public interface OmsInventoryMapper extends BaseMapper<OmsInventory> {

    @Insert("<script>" +
            " INSERT INTO oms_inventory(sku_sn, total_stock, available_stock,company_code, version) VALUES " +
            " (#{omsInventory.skuSn}, #{omsInventory.totalStock}, #{omsInventory.availableStock},#{omsInventory.companyCode}, 1)" +
            " ON DUPLICATE KEY UPDATE total_stock = total_stock + #{omsInventory.totalStock}, available_stock = available_stock + #{omsInventory.availableStock}," +
            " version = version + 1" +
            " </script>")
    int insertOrUpdate(@RelationSn("relationSn") String relationSn,@Param("omsInventory") OmsInventory omsInventory);

    @Insert("<script>" +
            " INSERT INTO oms_inventory(sku_sn, total_stock, available_stock, reserved_stock, frozen_stock, safety_stock, min_stock,company_code, version) VALUES " +
            " (#{omsInventory.skuSn}, 0, -#{omsInventory.reservedStock}, #{omsInventory.reservedStock}, 0, 0, 0, #{omsInventory.companyCode},1)" +
            " ON DUPLICATE KEY UPDATE available_stock = available_stock - #{omsInventory.reservedStock}, reserved_stock = reserved_stock + #{omsInventory.reservedStock}," +
            " version = version + 1" +
            " </script>")
    int reserveStock(@Param("omsInventory") OmsInventory omsInventory);

    @Select("SELECT * FROM oms_inventory WHERE sku_sn = #{skuSn}")
    OmsInventory selectBySkuSn(@Param("skuSn") String skuSn);

}
