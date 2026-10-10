"""One-shot source edit helper for the ticket workspace change."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'oms-modules/oms-supplychain/src/main/java/com/oms/supplychain'
def edit(path, fn):
    path.write_text(fn(path.read_text(encoding='utf-8')), encoding='utf-8')
def service(s):
    s=s.replace('.map(this::purchasePresentation)', '.map(this::purchasePresentation).map(com.oms.supplychain.service.wms.TicketPresentation::present)')
    start=s.index('        if(kind.equals("ticket"))return "SELECT t.*"')
    end=s.index('        throw new IllegalArgumentException("未知列表类型")', start)
    s=s[:start]+'''        if(kind.equals("ticket")) {
            String source="SELECT t.*"+warehouseColumns()+",o.id owner_id,w.id real_store_id,a.dispatch_state,a.receipt_state,c.provider,a.external_order,a.warehouse_status,a.last_query_result,a.last_query_time,a.query_message,a.last_error,a.cancel_reason,"
                +"CASE WHEN t.actual_warehouse=1 THEN a.receipt_state='COMPLETE' ELSE t.status_ticket=2 END receipt_final,"
                +"COALESCE(q.planned,0) number_expected,COALESCE(q.received,0) number_actually,CASE WHEN t.actual_warehouse=1 THEN COALESCE(r.posted,0) ELSE COALESCE(q.posted,0) END number_posted,"
                +"GREATEST(t.modify_time,COALESCE(a.modify_time,t.modify_time),COALESCE(q.updated,t.modify_time)) business_updated_time "
                +"FROM wms_tickets t"+joins("t")
                +" LEFT JOIN wms_inbound_task a ON a.ticket_id=t.id AND a.company_code=UPPER(t.company_code) LEFT JOIN wms_connection c ON c.id=a.connection_id AND c.company_code=a.company_code"
                +" LEFT JOIN (SELECT UPPER(company_code) company_code,sn,SUM(number_expected) planned,SUM(number_actually) received,SUM(CASE WHEN inventory_is_handle=2 THEN number_actually ELSE 0 END) posted,MAX(modify_time) updated FROM wms_tickets_goods GROUP BY UPPER(company_code),sn) q ON q.sn=t.sn AND q.company_code=UPPER(t.company_code)"
                +" LEFT JOIN (SELECT company_code,task_id,SUM(CASE WHEN posted=1 THEN good_qty+bad_qty ELSE 0 END) posted FROM wms_receipt_line GROUP BY company_code,task_id) r ON r.task_id=a.id AND r.company_code=a.company_code";
            return com.oms.supplychain.service.wms.TicketPresentation.enrich(source);
        }
        if(kind.equals("line"))return "SELECT g.*,t.ticket_type,t.original_sn,t.relation_sn,t.wms_simulation_code,t.status_ticket,t.inventory_status,t.actual_warehouse,t.owner_name,t.warehouse_name,t.real_warehouse_name,t.owner_id,t.real_store_id,t.dispatch_state,t.receipt_state,t.provider,t.external_order,t.warehouse_status,t.progress,t.receipt_final,t.short_quantity,t.business_updated_time,CASE WHEN t.actual_warehouse=1 THEN COALESCE((SELECT SUM(r.good_qty+r.bad_qty) FROM wms_receipt_line r WHERE r.ticket_line_id=g.id AND r.company_code=UPPER(g.company_code) AND r.posted=1),0) ELSE CASE WHEN g.inventory_is_handle=2 THEN g.number_actually ELSE 0 END END number_posted FROM wms_tickets_goods g JOIN ("+base("ticket")+") t ON t.sn=g.sn AND UPPER(t.company_code)=UPPER(g.company_code)";
''' +s[end:]
    s=s.replace('allowed.put("sn","sn");', 'allowed.put("progress","progress");allowed.put("ownerId","owner_id");allowed.put("realStoreId","real_store_id");allowed.put("actualWarehouse","actual_warehouse");allowed.put("provider","provider");allowed.put("externalOrder","external_order");allowed.put("sn","sn");')
    s=s.replace('String begin=text(q.get("beginTime"))', 'String updated=kind.equals("ticket")||kind.equals("line")?"business_updated_time":"modify_time";String begin=text(q.get("beginTime"))')
    s=s.replace('w.append(" AND x.modify_time>=?")','w.append(" AND x."+updated+">=?")').replace('w.append(" AND x.modify_time<?")','w.append(" AND x."+updated+"<?")')
    s=s.replace('+" ORDER BY x.modify_time DESC,x.id DESC', '+" ORDER BY x."+(kind.equals("ticket")||kind.equals("line")?"business_updated_time":"modify_time")+" DESC,x.id DESC')
    s=s.replace('rows("SELECT * FROM wms_tickets WHERE UPPER(company_code)=? AND original_sn=? ORDER BY id DESC",c,sn)', 'rows("SELECT x.* FROM ("+base("ticket")+") x WHERE UPPER(x.company_code)=? AND x.original_sn=? ORDER BY x.id DESC",c,sn)')
    s=s.replace('if(kind.equals("ticket"))result.put("lines",rows("SELECT * FROM wms_tickets_goods WHERE UPPER(company_code)=? AND sn=? ORDER BY id",c,sn));return result;', '''if(kind.equals("ticket")) {
            result.put("lines",rows("SELECT x.* FROM ("+base("line")+") x WHERE UPPER(x.company_code)=? AND x.sn=? ORDER BY x.id",c,sn));
            result.put("purchaseId",byCode(c,"purchase","po_sn",text(result.get("originalSn"))).get("id"));
            result.put("receiptId",byCode(c,"receipt","no_sn",text(result.get("relationSn"))).get("id"));
        }return result;''')
    pos=s.index('    public List<Map<String,Object>> purchaseLines(')
    s=s[:pos]+'''    public TableDataInfo receiptBatches(String supplied,long ticketId,int page,int size) {
        String c=company(supplied);Map<String,Object> ticket=one(c,"ticket",ticketId);
        String source=integer(ticket.get("actualWarehouse"))==1?
            "SELECT r.*,g.goods_name,e.message_id,e.final_receipt FROM wms_receipt_line r JOIN wms_inbound_task a ON a.id=r.task_id AND a.company_code=r.company_code JOIN wms_receipt_event e ON e.id=r.event_id AND e.company_code=r.company_code JOIN wms_tickets_goods g ON g.id=r.ticket_line_id AND UPPER(g.company_code)=r.company_code WHERE a.company_code=? AND a.ticket_id=?":
            "SELECT g.id,g.sku_sn,g.goods_name,g.batch_code,g.number_zp good_qty,g.number_cp bad_qty,CASE WHEN g.inventory_is_handle=2 THEN 1 WHEN g.inventory_is_handle=3 THEN 2 ELSE 0 END posted,g.error_info,g.wms_actually_time create_time,'自动虚拟收货' message_id,1 final_receipt FROM wms_tickets_goods g JOIN wms_tickets t ON t.sn=g.sn AND UPPER(t.company_code)=UPPER(g.company_code) WHERE UPPER(t.company_code)=? AND t.id=? AND t.status_ticket=2";
        TableDataInfo result=new TableDataInfo();result.setCode(200);result.setMsg("查询成功");result.setTotal(count("SELECT COUNT(*) FROM ("+source+") x",c,ticketId));
        int limit=Math.max(1,Math.min(size,100));result.setRows(rows("SELECT x.* FROM ("+source+") x ORDER BY x.id DESC LIMIT ? OFFSET ?",c,ticketId,limit,(long)(Math.max(1,page)-1)*limit));return result;
    }
''' +s[pos:]
    return s
if __name__ == '__main__': edit(JAVA/'service/warehouse/impl/PurchaseWorkspaceService.java',service)
def controller(s):
    at=s.index('    @PostMapping("/supplier")')
    s=s[:at]+'''    @GetMapping("/ticket/{id}/batches") public TableDataInfo batches(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){permission("ticket","query");return service.receiptBatches(company(),id,pageNum,pageSize);}
'''+s[at:]
    s=s.replace('"batchCode:批次"','"batchCode:计划批次"').replace('"inventoryIsHandle:入账结果"','"postingLabel:入账结果","numberPosted:已入账数量","modeLabel:执行方式","providerLabel:对接平台","progressLabel:单据进度"')
    s=s.replace('"statusTicket:执行状态","inventoryStatus:入账状态"','"modeLabel:执行方式","providerLabel:对接平台","externalOrder:仓库单号","progressLabel:单据进度","executionLabel:执行状态","postingLabel:入账状态"').replace('"numberActually:实际数量","modifyTime:更新时间"','"numberActually:实收数量","numberPosted:已入账数量","numberPending:待入账数量","shortQuantity:最终短收数量","businessUpdatedTime:更新时间"')
    return s
if __name__ == '__main__': edit(JAVA/'controller/warehouse/PurchaseWorkspaceController.java',controller)
