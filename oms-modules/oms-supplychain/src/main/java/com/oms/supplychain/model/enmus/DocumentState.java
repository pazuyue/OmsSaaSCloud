package com.oms.supplychain.model.enmus;

public enum DocumentState {

    CANCEL(-1,"已作废"),
    CREATE(1,"创建"),
    AUDIT(2,"待审核"),
    WAITWAREHOUSING(3,"待入库"),
    WAREHOUSINGCOMPLETED(4,"已入库"),
    WAREHOUSING(1,"入库"),
    OUTBOUND(2,"出库"),
    E_COMMERCE_WAREHOUSE(1,"电商仓库"),
    REAL_WAREHOUSE(1,"真实库存结算"),
    VIRTUALLY_WAREHOUSE(2,"虚拟库存结算"),
    ZP(1,"ZP"),
    CP(2,"CP"),
    UNTREATED(0,"未处理"),
    PROCESSING(1,"处理中"),
    PROCESSED_SUCCESS(2,"处理成功"),
    PROCESSED_FAIL(3,"处理失败"),
    STATUS_TICKET_TO_BE_CONFIRMED(0,"待确认"),
    STATUS_TICKET_CONFIRMED(1,"已确认待处理"),
    STATUS_TICKET_PROCESSED(2,"已处理完成"),
    STATUS_TICKET_PROCESSING_FAILED(3,"处理失败"),
    STATUS_TICKET_CANCEL(5,"已废弃完成"),
    STATUS_NOTIFY_TO_BE_CONFIRMED(0,"待通知"),
    STATUS_NOTIFY_PROCESSED_SUCCESS(1,"通知成功"),
    STATUS_NOTIFY_PROCESSED_FAIL(2,"通知失败"),
    STATUS_QUERY_TO_BE_CONFIRMED(0,"全部待查询"),
    STATUS_QUERY_PROCESSED_SUCCESS(1,"全部待查询"),
    STATUS_QUERY_PROCESSED_FAIL(3,"全部查询失败"),
    ;



    private int code;
    private String msg;
    private DocumentState(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode(){
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
