package com.oms.common.model.dto;

import lombok.Data;
import java.util.*;

/** Immutable business identity is the platform order line, not a retry UUID. */
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
    }
}
