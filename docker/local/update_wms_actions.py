from update_ticket_workspace import ROOT, JAVA, edit

fields={
 'warehouse_status': "VARCHAR(40) NOT NULL DEFAULT '' COMMENT '最近查询的仓库状态，仅展示，不替代实收回传'",
 'last_query_result': "VARCHAR(24) NOT NULL DEFAULT '' COMMENT '最近查询结果：SUCCESS、FAILED、TIMEOUT、UNKNOWN、UNSUPPORTED'",
 'last_query_time': "DATETIME NULL COMMENT '最近主动查询时间'",
 'query_message': "VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '最近仓库查询说明'",
 'cancel_reason': "VARCHAR(500) NOT NULL DEFAULT '' COMMENT '用户申请取消原因'"
}
edit(ROOT/'docker/local/migrations/20261010_wms_integration.sql',lambda s:s.replace(" attempts INT",''.join(' '+n+' '+v+',\n' for n,v in fields.items())+' attempts INT'))
edit(ROOT/'docker/local/migrate_wms_integration.py',lambda s:s.replace('    fields = {', '    task_fields = '+repr(fields)+"\n    task_columns = {r.split('\\t')[0] for r in execute('SHOW COLUMNS FROM wms_inbound_task', db).splitlines()}\n    for name, ddl in task_fields.items():\n        if name not in task_columns:\n            execute(f'ALTER TABLE wms_inbound_task ADD COLUMN {name} {ddl}', db)\n    fields = {"))
def inbound(s):
    s=s.replace('public void query(String company,long ticket)', 'public Map<String,Object> query(String company,long ticket)').replace('send(company,t,"QUERY");}', 'return send(company,t,"QUERY");}')
    s=s.replace('public void cancel(String company,long ticket){','public Map<String,Object> cancel(String company,long ticket,String reason,String user){\n        require(!text(reason).isEmpty()&&text(reason).length()<=500,"请填写 1 至 500 字取消原因");')
    s=s.replace('if("PENDING".equals(t.get("dispatch_state"))){','db.jdbc.update("UPDATE wms_inbound_task SET cancel_reason=? WHERE id=?",text(reason),t.get("id"));t.put("cancel_reason",text(reason));purchase.recordWmsEvent(company,text(t.get("ticket_sn")),"申请取消入库",user+"："+text(reason));if("PENDING".equals(t.get("dispatch_state"))){')
    s=s.replace('if(task!=null)send(company,task,"CANCEL");','return task!=null?send(company,task,"CANCEL"):map("success",true,"outcome","CANCELED","message","未下发入库单已取消");')
    s=s.replace('private void send(String company,Map<String,Object> task,String action){','private Map<String,Object> send(String company,Map<String,Object> task,String action){\n        Map<String,Object> result=map("success",false,"outcome","UNKNOWN","localUpdated",false,"warehouseStatus","","message","仓库交互结果待确认","nextAction","查看交互日志后核对仓库结果");')
    s=s.replace('WmsProtocol.Reply reply=p.reply(action,body);updateReply(company,task,action,reply);','''WmsProtocol.Reply reply=p.reply(action,body);
            Map<String,Object> before=task(company,id(task.get("ticket_id")));updateReply(company,task,action,reply);
            Map<String,Object> after=task(company,id(task.get("ticket_id")));
            boolean known=Arrays.asList("NEW","ACCEPT","PARTFULFILLED","FULFILLED","CLOSED","CANCELED").contains(reply.status);
            String outcome=reply.success?(action.equals("QUERY")&&!known?"UNSUPPORTED":"SUCCESS"):"FAILED";
            String message=reply.success?(action.equals("QUERY")?"仓库状态："+reply.status:action.equals("CANCEL")?"取消申请已发送，请查看确认进度":"下发成功"):"仓库返回失败："+reply.message;
            String next="等待仓库收货回传后自动入账";
            if(action.equals("QUERY")) {
                if(!known&&reply.success){message="仓库查询未返回可识别的业务状态："+reply.status;next="查看交互日志，核对接口是否支持入库状态查询";}
                if(!reply.success)next="查看仓库错误信息；查无单据也不能直接重新下发，请先核对仓库";
                if(Arrays.asList("FULFILLED","CLOSED").contains(reply.status)&&!"COMPLETE".equals(after.get("receipt_state"))){message="仓库已完成，收货回传待核对";next="联系仓库补传完整实收明细；查询状态不会增加库存";}
                db.jdbc.update("UPDATE wms_inbound_task SET warehouse_status=?,last_query_result=?,last_query_time=NOW(),query_message=? WHERE id=? AND company_code=?",reply.status,outcome,message,taskId,company);
            }
            result=map("success",outcome.equals("SUCCESS"),"outcome",outcome,"warehouseStatus",reply.status,"localUpdated",!Objects.equals(before.get("dispatch_state"),after.get("dispatch_state"))||!Objects.equals(before.get("external_order"),after.get("external_order")),"message",message,"nextAction",next);''')
    s=s.replace('db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state=CASE WHEN dispatch_state=\'SENDING\' THEN \'UNKNOWN\' ELSE dispatch_state END,last_error=?,lease_until=NULL WHERE id=? AND company_code=?",error,taskId,company);','''String outcome=e instanceof java.net.SocketTimeoutException?"TIMEOUT":"UNKNOWN";
            result=map("success",false,"outcome",outcome,"warehouseStatus","","localUpdated",false,"message",error,"nextAction","稍后查询仓库状态或查看交互日志，不要重复下发");
            if(action.equals("QUERY"))db.jdbc.update("UPDATE wms_inbound_task SET last_query_result=?,last_query_time=NOW(),query_message=? WHERE id=? AND company_code=?",outcome,error,taskId,company);
            else db.jdbc.update("UPDATE wms_inbound_task SET dispatch_state=CASE WHEN dispatch_state='SENDING' THEN 'UNKNOWN' ELSE dispatch_state END,last_error=?,lease_until=NULL WHERE id=? AND company_code=?",error,taskId,company);''')
    s=s.replace('}finally{db.jdbc.update("UPDATE wms_inbound_task SET lease_until=NULL WHERE id=? AND company_code=?",taskId,company);}\n    }','}finally{db.jdbc.update("UPDATE wms_inbound_task SET lease_until=NULL WHERE id=? AND company_code=?",taskId,company);}\n        return result;\n    }')
    s=s.replace('external_order=?,last_error=? WHERE id=?",reply.externalOrder,"仓库状态："+reply.status+"；实收数量以收货回传为准",task.get("id"))','external_order=CASE WHEN ?=\'\' THEN external_order ELSE ? END,last_error=\'\' WHERE id=?",reply.externalOrder,reply.externalOrder,task.get("id"))')
    return s
edit(JAVA/'service/wms/WmsInboundService.java',inbound)
for file in ['QimenProtocol.java','JdHufuProtocol.java']:
    edit(JAVA/'service/wms'/file,lambda s:s.replace('"cancelReason","OMS申请取消采购入库"','"cancelReason",task.get("cancel_reason")'))
def controller(s):
    a=s.index('    @PostMapping("/wmsIntegration/tickets/{id}/{action}")')
    b=s.index('    @GetMapping("/wmsIntegration/logs")',a)
    return s[:a]+'''    @PostMapping("/wmsIntegration/tickets/{id}/{action}") public AjaxResult action(@PathVariable long id,@PathVariable String action,@RequestBody(required=false) Map<String,Object> body){
        switch(action){
            case "retry":AuthUtil.checkPermi("warehouse:tickets:retry");inbound.retry(company(),id);return AjaxResult.success(map("success",true,"message","已安排重试下发，请刷新进度"));
            case "query":AuthUtil.checkPermi("warehouse:tickets:queryWarehouse");return AjaxResult.success(inbound.query(company(),id));
            case "cancel":AuthUtil.checkPermi("warehouse:tickets:remove");return AjaxResult.success(inbound.cancel(company(),id,body==null?"":text(body.get("reason")),com.ruoyi.common.security.utils.SecurityUtils.getUsername()));
            default:throw new IllegalArgumentException("未知仓库操作");
        }
    }
'''+s[b:]
edit(JAVA/'controller/warehouse/WmsIntegrationController.java',controller)
edit(ROOT/'ruoyi-ui/src/api/warehouse/integration.js',lambda s:s.replace('(id, action) =>','(id, action, data) =>').replace("method: 'post', timeout", "method: 'post', data, timeout"))
edit(ROOT/'docker/local/configure_wms_runtime.py',lambda s:s.replace("('查看仓库交互报文','warehouse:wms:payload')", "('查看仓库交互报文','warehouse:wms:payload'),('查询仓库状态','warehouse:tickets:queryWarehouse')"))
edit(ROOT/'oms-modules/oms-supplychain/src/test/java/com/oms/supplychain/PurchaseWorkspaceTest.java',lambda s:s.replace('inbound().cancel("QM",t)', 'inbound().cancel("QM",t,"测试取消","tester")'))
