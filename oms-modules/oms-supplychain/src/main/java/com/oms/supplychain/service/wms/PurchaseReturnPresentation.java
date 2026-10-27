package com.oms.supplychain.service.wms;

import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Read model shared by list/detail. Warehouse responses never stand in for stock facts. */
public final class PurchaseReturnPresentation {
 private static long n(Map<String,Object>d,String k){return d.get(k)==null?0:id(d.get(k));}
 public static void enrich(Map<String,Object>d){
  String state=text(d.get("state")),dispatch=text(d.get("dispatch_state"));long qty=n(d,"quantity"),actual=n(d,"accepted_quantity"),posted=n(d,"posted_quantity");
  d.put("unshipped_quantity",Math.max(0,qty-actual));d.put("pending_post_quantity",Math.max(0,actual-posted));
  d.put("released_quantity","CANCELED".equals(state)?qty-posted:0);
  d.put("remaining_hold",n(d,"stock_reserved")==1&&!Arrays.asList("CANCELED","COMPLETED").contains(state)?Math.max(0,qty-posted):0);
  String code,label,hint,action="";
  if("DRAFT".equals(state)){code="DRAFT";label="待审核";hint="审核后按原来源锁定库存，并执行仓库退仓。";action="approve";}
  else if(n(d,"receipt_review_pending")==1){code="REVIEW";label="出库回传待核对";hint="仓库回传与原商品、批次或额度冲突，取得有效更正前保留原占用。";action="logs";}
  else if("CANCELING".equals(state)){code="STOPPING";label="停止剩余退仓待确认";hint="正在确认仓库停止结果和原占用释放，已出库部分保留。";}
  else if("CANCELED".equals(state)){code=posted>0?"PARTIAL_CLOSED":"CANCELED";label=posted>0?"部分完成，剩余已停止":"已取消退仓";hint=posted>0?"已退仓部分保留，未出库占用已经按原来源释放。":"没有实际退仓出库，申请已结束。";if("WAIT_RELEASE".equals(d.get("continuation_state"))){action="retry";hint="原单已结束，正在生成关联后续草稿；原请求重试不会重复建单。";}}
  else if("COMPLETED".equals(state)){code="COMPLETED";label="全部退仓完成";hint="全部申请数量已实际出库并扣账，供应商收货和款项单独跟踪。";}
  else if(n(d,"posting_failed_count")>0||actual>posted){code="POSTING";label="实出已确认，库存待扣账";hint="按原出库事件完成库存扣账；不会重新要求仓库出库。";action="retry";}
  else if(n(d,"receipt_final")==1&&actual<qty){code="REMAINING";label=actual>0?"部分出库，剩余待处理":"仓库已结束，未出库待处理";hint="仓库已最终确认本次出库，未出库部分仍占用，请选择结束或继续退仓。";action="remaining";}
  else if(Arrays.asList("SENDING","UNKNOWN").contains(dispatch)){code="UNKNOWN";label="仓库受理结果待确认";hint="沿用原单查询结果，确认前保留原占用。";action="query";}
  else if("FAILED".equals(dispatch)){code="DISPATCH_FAILED";label="仓库下发失败";hint="仓库明确拒绝本次下发，核对配置后可沿用原单重试。";action="retry";}
  else if("APPROVING".equals(state)){code="RESERVING";label="库存锁定中";hint="后台正在校验并锁定原来源库存。";if(!text(d.get("last_error")).isEmpty())action="retry";}
  else if(actual>0){code="PARTIAL";label="部分出库，等待后续";hint="仓库尚未最终结束，剩余数量继续占用等待后续出库。";}
  else{code="WAITING";label="等待仓库出库";hint="仓库实出回传后自动扣账，当前占用数量仍保留。";}
  d.put("progress_code",code);d.put("progress_label",label);d.put("progress_hint",hint);d.put("primary_action",action);
  java.math.BigDecimal value=new java.math.BigDecimal(text(d.getOrDefault("posted_amount",0))),recorded=new java.math.BigDecimal(text(d.getOrDefault("funds_amount",0)));
  d.put("funds_remaining",value.subtract(recorded).max(java.math.BigDecimal.ZERO));
 }
 private PurchaseReturnPresentation(){}
}
