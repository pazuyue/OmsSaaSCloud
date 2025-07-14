package com.oms.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oms.inventory.model.entity.WmsInventoryBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 仓库库存表-批次维度：sku_sn + company_code + batch_code + store_code Mapper 接口
 * </p>
 *
 * @author 月光光
 * @since 2023-12-08
 */
public interface WmsInventoryBatchMapper extends BaseMapper<WmsInventoryBatch> {

    @Insert("<script>" +
            " INSERT INTO wms_inventory_batch(store_code,sku_sn,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,remark,batch_code,transaction_price,brand_code,company_code,version) VALUES " +
            " (#{wms.storeCode},#{wms.skuSn},#{wms.zpActualNumber},#{wms.cpActualNumber},#{wms.zpAvailableNumber},#{wms.cpAvailableNumber},#{wms.remark},#{wms.batchCode},#{wms.transactionPrice},#{wms.brandCode},#{wms.companyCode},1)" +
            " ON DUPLICATE KEY UPDATE zp_actual_number = zp_actual_number+#{wms.zpActualNumber},zp_available_number = zp_available_number+#{wms.zpAvailableNumber}," +
            " cp_actual_number = cp_actual_number+#{wms.cpActualNumber},cp_available_number = cp_available_number+#{wms.cpAvailableNumber}," +
            " version = version + 1"+
            " </script>")
    int insertOrUpdate(@Param("wms") WmsInventoryBatch wms);

    /**
     * 锁定批次库存 - 增加锁定数量，减少可用数量
     * @param storeCodes 仓库编码列表
     * @param sku SKU编号
     * @param quantity 锁定数量
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE wms_inventory_batch SET " +
            "zp_lock_number = zp_lock_number + #{quantity}, " +
            "zp_available_number = zp_available_number - #{quantity}, " +
            "version = version + 1 " +
            "WHERE sku_sn = #{sku} " +
            "AND store_code IN " +
            "<foreach item='storeCode' index='index' collection='storeCodes' open='(' separator=',' close=')'>" +
            "#{storeCode}" +
            "</foreach>" +
            "AND zp_available_number >= #{quantity}" +
            "</script>")
    int lockInventory(@Param("storeCodes") List<String> storeCodes, @Param("sku") String sku, @Param("quantity") BigDecimal quantity);

    /**
     * 解锁批次库存 - 减少锁定数量，增加可用数量
     * @param storeCodes 仓库编码列表
     * @param sku SKU编号
     * @param quantity 解锁数量
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE wms_inventory_batch SET " +
            "zp_lock_number = zp_lock_number - #{quantity}, " +
            "zp_available_number = zp_available_number + #{quantity}, " +
            "version = version + 1 " +
            "WHERE sku_sn = #{sku} " +
            "AND store_code IN " +
            "<foreach item='storeCode' index='index' collection='storeCodes' open='(' separator=',' close=')'>" +
            "#{storeCode}" +
            "</foreach>" +
            "AND zp_lock_number >= #{quantity}" +
            "</script>")
    int unlockInventory(@Param("storeCodes") List<String> storeCodes, @Param("sku") String sku, @Param("quantity") BigDecimal quantity);
}
