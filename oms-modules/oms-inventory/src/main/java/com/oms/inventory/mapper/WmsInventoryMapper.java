package com.oms.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.oms.inventory.model.entity.WmsInventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 仓库库存表维度：sku_sn + store_code Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {
    @Insert("<script>" +
            " INSERT INTO wms_inventory(store_code,sku_sn,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,remark,company_code,version) VALUES " +
            " (#{wms.storeCode},#{wms.skuSn},#{wms.zpActualNumber},#{wms.cpActualNumber},#{wms.zpAvailableNumber},#{wms.cpAvailableNumber},#{wms.remark},#{wms.companyCode},1)" +
            " on duplicate key update zp_actual_number = zp_actual_number+#{wms.zpActualNumber},zp_available_number = zp_available_number+#{wms.zpAvailableNumber}," +
            " cp_actual_number = cp_actual_number+#{wms.cpActualNumber},cp_available_number = cp_available_number+#{wms.cpAvailableNumber}," +
            " version = version + 1"+
            " </script>")
    int insertOrUpdate(@Param("wms") WmsInventory wms);

    @Select("<script>" +
            "SELECT sku_sn, SUM(zp_available_number) AS total_available " +
            "FROM wms_inventory " +
            "WHERE store_code IN " +
            "<foreach item='storeCode' index='index' collection='storeCodes' open='(' separator=',' close=')'>" +
            "#{storeCode}" +
            "</foreach>" +
            "<if test='skuSn != null and skuSn != \"\"'>" +
            "AND sku_sn = #{skuSn}" +
            "</if>" +
            "GROUP BY sku_sn" +
            "</script>")
    List<Map<String, Object>> selectSkuTotalAvailable(@Param("storeCodes") List<String> storeCodes, @Param("skuSn") String skuSn);

}
