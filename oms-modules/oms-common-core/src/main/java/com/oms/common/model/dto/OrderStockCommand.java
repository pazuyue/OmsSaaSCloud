package com.oms.common.model.dto;

import lombok.Data;
import java.util.*;

/** Demand identity is the platform line plus physical SKU; virtual kit parents hold no stock. */
@Data
public class OrderStockCommand {
    private long channelId;
    private String platformTid;
    private boolean allowPublicStock;
    private List<Line> lines=new ArrayList<>();
    @Data public static class Line {
        private String platformOid;
        private String skuSn;
        private int quantity;
        private int componentQuantity;
        /** OMS-owned physical gift: no platform OID exists. */
        private boolean gift;
    }
}
