package com.oms.supplychain.service.wms;

import java.util.Map;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** One read model for list, related documents, detail and export. Never advances workflow. */
public final class TicketPresentation {
    private TicketPresentation() {}
    public static String typeLabel(String code){Map<String,String> types=new java.util.LinkedHashMap<>();types.put("PURCHASE_IN","采购入库");types.put("SALE_OUT","销售出库");types.put("REFUND_IN","退款退回入库");types.put("EXCHANGE_IN","换货退回入库");types.put("EXCHANGE_OUT","换货换出出库");types.put("SALE_RETURN_IN","销售退货入库");types.put("PURCHASE_RETURN_OUT","采购退货出库");types.put("TRANSFER_IN","调拨入库");types.put("TRANSFER_OUT","调拨出库");types.put("ADJUST_IN","其他入库");types.put("ADJUST_OUT","其他出库");types.put("OTHER_IN","其他入库");types.put("OTHER_OUT","其他出库");return types.getOrDefault(code,code);}
    public static String enrich(String source) {
        String totals = "SELECT b.*,GREATEST(number_actually-number_posted,0) number_pending,"
            + "CASE WHEN cutoff_state='COMPLETED' AND cancelled_quantity>0 AND number_actually>0 THEN 'CLOSED' WHEN status_ticket=5 THEN 'CANCELED' WHEN shared_pending_count>0 OR inventory_status=3 OR dispatch_state IN('FAILED','UNKNOWN') OR (warehouse_status IN('FULFILLED','CLOSED') AND receipt_final=0) THEN 'ATTENTION' "
            + "WHEN receipt_final=1 AND inventory_status=2 AND number_posted=number_actually THEN 'COMPLETE' ELSE 'PROCESSING' END progress "
            + "FROM (" + source + ") b";
        return "SELECT p.*,CASE WHEN status_ticket=5 THEN 'NONE' WHEN inventory_status=3 THEN 'ERROR' WHEN number_posted>0 AND number_posted=number_actually OR receipt_final=1 AND number_actually=0 AND inventory_status=2 THEN 'POSTED' WHEN number_posted>0 THEN 'PARTIAL' ELSE 'NOT_STARTED' END posting_state,CASE WHEN receipt_final=1 THEN GREATEST(number_expected-number_actually-cancelled_quantity,0) ELSE NULL END short_quantity FROM ("+totals+") p";
    }
    public static Map<String,Object> present(Map<String,Object> r) {
        if (!r.containsKey("progress")) return r;
        String progress=text(r.get("progress")), dispatch=text(r.get("dispatchState")), receipt=text(r.get("receiptState"));
        boolean real=id(r.get("actualWarehouse"))==1, ended=id(r.get("receiptFinal"))==1;
        long received=id(r.get("numberActually")),posted=id(r.get("numberPosted"));
        boolean outbound=id(r.get("ticketType"))==2;String type=text(r.get("documentType"));if(type.isEmpty())type=outbound?"OTHER_OUT":"PURCHASE_IN";
        r.put("documentType",type);r.put("documentTypeLabel",typeLabel(type));r.put("directionLabel",outbound?"出库":"入库");
        r.put("modeLabel",real?"真实"+(outbound?"出库":"入库"):"虚拟"+(outbound?"出库":"入库"));
        r.put("sourceLabel",typeLabel(type));
        r.put("providerLabel","QIMEN".equals(r.get("provider"))?"奇门":"JD_HUFU".equals(r.get("provider"))?"京东虎符":text(r.get("provider")));
        if(outbound)return outbound(r,real,ended,received,posted,progress,dispatch);
        String execution=ended?"收货结束":received>0?"部分收货":real?"等待收货回传":"待自动收货";
        if(real) {
            if(dispatch.equals("PENDING")) execution="待下发";
            if(dispatch.equals("SENDING")) execution="下发中";
            if(dispatch.equals("FAILED")) execution="下发失败";
            if(dispatch.equals("UNKNOWN")) execution="下发结果待确认";
            if(dispatch.equals("CANCEL_PENDING")) execution="取消待确认";
        }
        if(progress.equals("CANCELED")) execution="已取消";if(progress.equals("CLOSED"))execution="已关闭（部分收货）";
        long postingState=id(r.containsKey("inventoryIsHandle")?r.get("inventoryIsHandle"):r.get("inventoryStatus"));
        r.put("numberPending",Math.max(0,received-posted));
        r.put("shortQuantity",ended?Math.max(0,id(r.get("numberExpected"))-received-id(r.get("cancelledQuantity"))):null);
        String posting=postingState==3?"入账异常":progress.equals("CANCELED")?"无需入账":posted>0&&posted==received?"全部实收入账":posted>0?"部分入账":ended&&received==0?"无实收待入账":"未入账";
        String label=progress.equals("CLOSED")?"已关闭（部分收货）":progress.equals("COMPLETE")?"已完成":progress.equals("ATTENTION")?"待处理异常":progress.equals("CANCELED")?"已取消":"处理中";
        if(progress.equals("COMPLETE")&&id(r.get("shortQuantity"))>0) label+=" · 短收 "+r.get("shortQuantity");
        String next=progress.equals("CLOSED")?"本批剩余收货已截停，已实收及库存保留；采购单仍有效时可重新安排剩余需求。":progress.equals("COMPLETE")?"本批实收已全部入账。":progress.equals("CANCELED")?"本批入库已取消。":id(r.get("inventoryStatus"))==3?"查看实际收货批次中的失败原因，重试入账。":dispatch.equals("FAILED")?"仓库明确拒绝下发；核对失败原因后重试下发。":dispatch.equals("UNKNOWN")?"查询仓库状态，确认是否受理，避免重复下发。":dispatch.equals("CANCEL_PENDING")?"等待仓库确认取消，期间仍须接收收货回传。":ended?"收货已结束，等待实收全部入账。":real?"等待仓库收货回传，收到后自动入账。":"系统自动收货并入账；中断时可重试虚拟入库。";
        boolean missing=real&&!ended&&(text(r.get("warehouseStatus")).equals("FULFILLED")||text(r.get("warehouseStatus")).equals("CLOSED"));
        if(progress.equals("CLOSED")&&type.equals("EXCHANGE_IN"))next="本单剩余退回已停止，实收入账保留；请在所属换货单处理补退、重新安排或结束方案。";
        if(progress.equals("CLOSED")&&type.equals("REFUND_IN"))next="本单剩余退回已停止，实收入账保留；请在所属退款单处理重新安排或差异结案。";
        if(missing) next="仓库已完成，收货回传待核对。请联系仓库补传完整实收明细；状态查询不会增加库存。";
        r.put("errorStage",missing?"收货回传":postingState==3?"库存入账":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"仓库下发":"");
        r.put("remainingQuantity",Math.max(0,id(r.get("numberExpected"))-received-id(r.get("cancelledQuantity"))));r.put("executionLabel",execution);r.put("postingLabel",posting);r.put("progressLabel",label);r.put("nextAction",next);r.put("receiptMissing",missing);
        r.put("step",progress.equals("COMPLETE")?(real?4:2):ended?(real?3:1):real?(dispatch.equals("ACCEPTED")||dispatch.equals("CANCEL_PENDING")?2:dispatch.equals("SENDING")?1:0):0);
        r.put("cancelBlockedReason",received>0||posted>0||ended?"已收货或已入账，不能取消入库单。":progress.equals("CANCELED")?"入库单已取消。":real&&!dispatch.equals("PENDING")&&!dispatch.equals("ACCEPTED")?"请先确认仓库受理结果，当前不能重复申请取消。":"");
        if(r.get("sharedDocumentId")!=null)r.put("cancelBlockedReason","共同THK请到退款退回包裹中核对全部关联退款后统一截停。");
        if(r.get("sharedPendingCount")!=null&&id(r.get("sharedPendingCount"))>0){r.put("progress","ATTENTION");r.put("progressLabel","实收归属待核对");r.put("executionLabel","仓库已回传，待确认归属");r.put("postingLabel","未分配部分未入账");r.put("errorStage","实收归属");r.put("receiptMissing",false);r.put("nextAction","仓库已回传 "+r.get("sharedPendingQuantity")+" 件待核对；请到关联退款的退回包裹确认商品来源。下方实收为已确认数量，未分配部分未入账。");}
        return r;
    }
    private static Map<String,Object> outbound(Map<String,Object> r,boolean real,boolean ended,long actual,long posted,String progress,String dispatch){
        String execution=ended?"全部出库":actual>0?"部分出库":real?"等待仓库出库":"待虚拟出库";
        if(real&&!ended&&actual==0){if(dispatch.equals("PENDING"))execution="待下发";if(dispatch.equals("SENDING"))execution="下发中";if(dispatch.equals("FAILED"))execution="下发失败";if(dispatch.equals("UNKNOWN"))execution="下发结果待确认";}
        if(progress.equals("CLOSED"))execution="剩余已截停";else if(progress.equals("CANCELED"))execution="已取消";else if(text(r.get("sourceStatus")).equals("WAIT_CARRIER"))execution="待匹配快递";else if(text(r.get("sourceStatus")).equals("VOIDING"))execution="取消处理中";
        boolean missing=real&&!ended&&(text(r.get("warehouseStatus")).equals("FULFILLED")||text(r.get("warehouseStatus")).equals("CLOSED"));
        String posting=progress.equals("CANCELED")?"无需扣账":posted>0&&posted==actual?"已出库数量已扣账":posted>0?"部分扣账":"未扣账";
        r.put("progressLabel",progress.equals("CLOSED")?"已结束（剩余已截停）":progress.equals("COMPLETE")?"已完成":progress.equals("ATTENTION")?"待处理异常":progress.equals("CANCELED")?"已取消":actual>0?"部分出库":"处理中");
        r.put("executionLabel",execution);r.put("postingLabel",posting);r.put("numberPending",Math.max(0,actual-posted));r.put("remainingQuantity",Math.max(0,id(r.get("numberExpected"))-actual-id(r.get("cancelledQuantity"))));r.put("shortQuantity",null);
        r.put("nextAction",progress.equals("CLOSED")?"剩余出库已截停，实际出库及扣账保留；后续请到来源单据处理。":progress.equals("COMPLETE")?"实际出库明细已记录，库存扣账完成。":progress.equals("CANCELED")?"销售出库单已取消，记录保留。":missing?"仓库已完成，仍有未确认的出库数量；请进入来源发货单核对出库回传。":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"请进入来源发货单核对仓库下发结果。":actual>0?"剩余 "+r.get("remainingQuantity")+" 件待出库；请从来源发货单继续处理。":real?"在来源发货单跟踪下发和仓库回传，确认出库后记录库存扣账。":"在来源发货单执行虚拟出库，确认后记录实际出库和库存扣账。");
        r.put("receiptMissing",missing);r.put("errorStage",missing?"出库回传":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"仓库下发":"");r.put("step",progress.equals("COMPLETE")?(real?4:2):actual>0?(real?3:1):real&&dispatch.equals("ACCEPTED")?2:0);r.put("cancelBlockedReason","销售出库单由来源发货单统一控制。");return r;
    }
}
