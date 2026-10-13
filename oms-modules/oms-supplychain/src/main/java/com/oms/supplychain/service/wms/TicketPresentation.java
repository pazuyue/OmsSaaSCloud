package com.oms.supplychain.service.wms;

import java.util.Map;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** One read model for list, related documents, detail and export. Never advances workflow. */
public final class TicketPresentation {
    private TicketPresentation() {}
    public static String typeLabel(String code){Map<String,String> types=new java.util.LinkedHashMap<>();types.put("PURCHASE_IN","采购入库");types.put("SALE_OUT","销售出库");types.put("SALE_RETURN_IN","销售退货入库");types.put("PURCHASE_RETURN_OUT","采购退货出库");types.put("TRANSFER_IN","调拨入库");types.put("TRANSFER_OUT","调拨出库");types.put("ADJUST_IN","其他入库");types.put("ADJUST_OUT","其他出库");types.put("OTHER_IN","其他入库");types.put("OTHER_OUT","其他出库");return types.getOrDefault(code,code);}
    public static String enrich(String source) {
        String totals = "SELECT b.*,GREATEST(number_actually-number_posted,0) number_pending,"
            + "CASE WHEN status_ticket=5 THEN 'CANCELED' WHEN inventory_status=3 OR dispatch_state IN('FAILED','UNKNOWN') OR (warehouse_status IN('FULFILLED','CLOSED') AND receipt_final=0) THEN 'ATTENTION' "
            + "WHEN receipt_final=1 AND inventory_status=2 AND number_posted=number_actually THEN 'COMPLETE' ELSE 'PROCESSING' END progress "
            + "FROM (" + source + ") b";
        return "SELECT p.*,CASE WHEN status_ticket=5 THEN 'NONE' WHEN inventory_status=3 THEN 'ERROR' WHEN number_posted>0 AND number_posted=number_actually OR receipt_final=1 AND number_actually=0 AND inventory_status=2 THEN 'POSTED' WHEN number_posted>0 THEN 'PARTIAL' ELSE 'NOT_STARTED' END posting_state,CASE WHEN receipt_final=1 THEN GREATEST(number_expected-number_actually,0) ELSE NULL END short_quantity FROM ("+totals+") p";
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
        if(progress.equals("CANCELED")) execution="已取消";
        long postingState=id(r.containsKey("inventoryIsHandle")?r.get("inventoryIsHandle"):r.get("inventoryStatus"));
        r.put("numberPending",Math.max(0,received-posted));
        r.put("shortQuantity",ended?Math.max(0,id(r.get("numberExpected"))-received):null);
        String posting=postingState==3?"入账异常":progress.equals("CANCELED")?"无需入账":posted>0&&posted==received?"全部实收入账":posted>0?"部分入账":ended&&received==0?"无实收待入账":"未入账";
        String label=progress.equals("COMPLETE")?"已完成":progress.equals("ATTENTION")?"待处理异常":progress.equals("CANCELED")?"已取消":"处理中";
        if(progress.equals("COMPLETE")&&id(r.get("shortQuantity"))>0) label+=" · 短收 "+r.get("shortQuantity");
        String next=progress.equals("COMPLETE")?"本批实收已全部入账。":progress.equals("CANCELED")?"本批入库已取消。":id(r.get("inventoryStatus"))==3?"查看实际收货批次中的失败原因，重试入账。":dispatch.equals("FAILED")?"仓库明确拒绝下发；核对失败原因后重试下发。":dispatch.equals("UNKNOWN")?"查询仓库状态，确认是否受理，避免重复下发。":dispatch.equals("CANCEL_PENDING")?"等待仓库确认取消，期间仍须接收收货回传。":ended?"收货已结束，等待实收全部入账。":real?"等待仓库收货回传，收到后自动入账。":"系统自动收货并入账；中断时可重试虚拟入库。";
        boolean missing=real&&!ended&&(text(r.get("warehouseStatus")).equals("FULFILLED")||text(r.get("warehouseStatus")).equals("CLOSED"));
        if(missing) next="仓库已完成，收货回传待核对。请联系仓库补传完整实收明细；状态查询不会增加库存。";
        r.put("errorStage",missing?"收货回传":postingState==3?"库存入账":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"仓库下发":"");
        r.put("executionLabel",execution);r.put("postingLabel",posting);r.put("progressLabel",label);r.put("nextAction",next);r.put("receiptMissing",missing);
        r.put("step",progress.equals("COMPLETE")?(real?4:2):ended?(real?3:1):real?(dispatch.equals("ACCEPTED")||dispatch.equals("CANCEL_PENDING")?2:dispatch.equals("SENDING")?1:0):0);
        r.put("cancelBlockedReason",received>0||posted>0||ended?"已收货或已入账，不能取消入库单。":progress.equals("CANCELED")?"入库单已取消。":real&&!dispatch.equals("PENDING")&&!dispatch.equals("ACCEPTED")?"请先确认仓库受理结果，当前不能重复申请取消。":"");
        return r;
    }
    private static Map<String,Object> outbound(Map<String,Object> r,boolean real,boolean ended,long actual,long posted,String progress,String dispatch){
        String execution=ended?"全部出库":actual>0?"部分出库":real?"等待仓库出库":"待虚拟出库";
        if(real&&!ended&&actual==0){if(dispatch.equals("PENDING"))execution="待下发";if(dispatch.equals("SENDING"))execution="下发中";if(dispatch.equals("FAILED"))execution="下发失败";if(dispatch.equals("UNKNOWN"))execution="下发结果待确认";}
        if(progress.equals("CANCELED"))execution="已取消";else if(text(r.get("sourceStatus")).equals("WAIT_CARRIER"))execution="待匹配快递";else if(text(r.get("sourceStatus")).equals("VOIDING"))execution="取消处理中";
        boolean missing=real&&!ended&&(text(r.get("warehouseStatus")).equals("FULFILLED")||text(r.get("warehouseStatus")).equals("CLOSED"));
        String posting=progress.equals("CANCELED")?"无需扣账":posted>0&&posted==actual?"已出库数量已扣账":posted>0?"部分扣账":"未扣账";
        r.put("progressLabel",progress.equals("COMPLETE")?"已完成":progress.equals("ATTENTION")?"待处理异常":progress.equals("CANCELED")?"已取消":actual>0?"部分出库":"处理中");
        r.put("executionLabel",execution);r.put("postingLabel",posting);r.put("numberPending",Math.max(0,actual-posted));r.put("remainingQuantity",Math.max(0,id(r.get("numberExpected"))-actual));r.put("shortQuantity",null);
        r.put("nextAction",progress.equals("COMPLETE")?"实际出库明细已记录，库存扣账完成。":progress.equals("CANCELED")?"销售出库单已取消，记录保留。":missing?"仓库已完成，仍有未确认的出库数量；请进入来源发货单核对出库回传。":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"请进入来源发货单核对仓库下发结果。":actual>0?"剩余 "+r.get("remainingQuantity")+" 件待出库；请从来源发货单继续处理。":real?"在来源发货单跟踪下发和仓库回传，确认出库后记录库存扣账。":"在来源发货单执行虚拟出库，确认后记录实际出库和库存扣账。");
        r.put("receiptMissing",missing);r.put("errorStage",missing?"出库回传":dispatch.equals("FAILED")||dispatch.equals("UNKNOWN")?"仓库下发":"");r.put("step",progress.equals("COMPLETE")?(real?4:2):actual>0?(real?3:1):real&&dispatch.equals("ACCEPTED")?2:0);r.put("cancelBlockedReason","销售出库单由来源发货单统一控制。");return r;
    }
}
