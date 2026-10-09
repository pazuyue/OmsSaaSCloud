package com.oms.inventory.model.dto;

import lombok.Data;

/** Each callback describes one order line and one SKU; requestId is stable across retries. */
@Data
public class ReservationCommand {
    private String skuSn;
    private String orderLine;
    private String requestId;
    private Long channelId;
    private Integer quantity;
}
