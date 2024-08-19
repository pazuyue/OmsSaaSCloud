package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 虚仓表
 * </p>
 *
 * @author 月光光
 * @since 2023-08-02
 */
@TableName("wms_simulation_store_info")
@Data
public class WmsSimulationStoreInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 1: 未开启，2:开启
     */
    private Byte status;

    /**
     * 虚仓编码
     */
    private String wmsSimulationCode;

    /**
     * 虚仓名称
     */
    private String wmsSimulationName;

    /**
     * 货主编码
     */
    private String ownerCode;

    /**
     * 货主名称
     */
    private String ownerName;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;
}
