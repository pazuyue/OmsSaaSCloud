package com.oms.inventory.model.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

@Data
public class AllocationDraft {
    private Long id;
    private Integer revision;
    private String ruleName;
    private String remark;
    private Integer ruleType = 2;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime startTime;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime endTime;
    private Integer intervalMinutes = 5;
    private Integer dailyPriority = 100;
    private Integer allocationType = 1;
    private Integer ruleRange = 1;
    private Integer ruleMode = 2;
    private List<String> stores = new ArrayList<>();
    private List<Channel> channels = new ArrayList<>();

    @Data
    public static class Channel {
        private Integer channelId;
        private String channelName;
        private BigDecimal percentage;
        private Integer ruleType = 1;
        private Integer decimalHandleType = 1;
    }
}
