package com.oms.supplychain.model.vo.warehouse;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class OwnerInfoVO {
    /**
     * 编主编码
     */
    @NotBlank(message = "编主编码不能为空")
    private String ownerCode;

    /**
     * 编主名称
     */
    @NotBlank(message = "编主名称不能为空")
    private String ownerName;
}
