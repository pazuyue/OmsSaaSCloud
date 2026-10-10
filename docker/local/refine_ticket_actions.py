from update_ticket_workspace import ROOT, JAVA, edit
def service(s):
    s=s.replace('allowed.put("progress","progress");','allowed.put("postingState","posting_state");allowed.put("progress","progress");')
    s=s.replace('if(kind.equals("line"))return "SELECT g.*', 'if(kind.equals("line"))return "SELECT z.*,CASE WHEN z.status_ticket=5 THEN \'NONE\' WHEN z.inventory_is_handle=3 THEN \'ERROR\' WHEN z.number_posted=z.number_actually AND (z.number_posted>0 OR z.receipt_final=1 AND z.inventory_is_handle=2) THEN \'POSTED\' WHEN z.number_posted>0 THEN \'PARTIAL\' ELSE \'NOT_STARTED\' END posting_state FROM (SELECT g.*')
    s=s.replace('t.sn=g.sn AND UPPER(t.company_code)=UPPER(g.company_code)";\n        throw', 't.sn=g.sn AND UPPER(t.company_code)=UPPER(g.company_code)) z";\n        throw')
    return s
edit(JAVA/'service/warehouse/impl/PurchaseWorkspaceService.java',service)
def ui(s):
    s=s.replace('v-model="query.inventoryStatus"','v-model="query.postingState"').replace('v-for="(label, value) in entryStates" :key="value" :label="label" :value="Number(value)"','v-for="(label, value) in postingStates" :key="value" :label="label" :value="value"')
    s=s.replace("externalOrder: '' })", "externalOrder: '', postingState: '' })")
    s=s.replace("entryStates: {", "postingStates: { NOT_STARTED: '未入账', PARTIAL: '部分入账', POSTED: '全部实收入账', ERROR: '入账异常', NONE: '无需入账' }, entryStates: {")
    s=s.replace("entryType(row) { return", "entryType(row) { if (row.postingState) return { ERROR: 'danger', POSTED: 'success', NONE: 'info', NOT_STARTED: 'warning', PARTIAL: 'warning' }[row.postingState]; return")
    return s
edit(ROOT/'ruoyi-ui/src/views/oms/purchase/PurchaseWorkspace.vue',ui)
edit(ROOT/'ruoyi-ui/src/views/oms/purchase/TicketSummary.vue',lambda s:s.replace('    <p v-if="document.cancelBlockedReason"', '''    <p v-if="document.errorStage" class="warning">异常环节：{{ document.errorStage }} · 最近更新 {{ parseTime(document.businessUpdatedTime) }}<br>{{ document.lastError || (document.errorStage === '库存入账' ? '请在实际收货批次中查看每条失败原因。' : document.nextAction) }}</p>
    <p v-if="document.cancelBlockedReason"'''))
def actions(s):
    s=s.replace('if(Arrays.asList("FULFILLED","CLOSED").contains(reply.status)', 'if(reply.success&&Arrays.asList("FULFILLED","CLOSED").contains(reply.status)')
    s=s.replace('SET warehouse_status=?,last_query_result=', "SET warehouse_status=CASE WHEN ? THEN ? ELSE warehouse_status END,last_query_result=").replace('reply.status,outcome,message,taskId,company);', 'reply.success&&known,reply.status,outcome,message,taskId,company);')
    s=s.replace('logs.finish(logId,reply.success?"SUCCESS":"FAILED",body.replace(secret,"***"),reply.message.replace(secret,"***"),', 'logs.finish(logId,outcome.equals("SUCCESS")?"SUCCESS":"FAILED",body.replace(secret,"***"),(outcome.equals("SUCCESS")?reply.message:message).replace(secret,"***"),')
    s=s.replace('String outcome=e instanceof java.net.SocketTimeoutException?"TIMEOUT":"UNKNOWN";', 'String outcome=e instanceof java.net.SocketTimeoutException?"TIMEOUT":e instanceof IllegalArgumentException?"FAILED":"UNKNOWN";')
    s=s.replace('String error=e instanceof IllegalArgumentException?text(e.getMessage()):"仓库交互未确认："+e.getClass().getSimpleName();','String error=e instanceof java.net.SocketTimeoutException?"仓库请求超时，执行结果待确认":e instanceof IllegalArgumentException?text(e.getMessage()):"仓库交互未确认："+e.getClass().getSimpleName();')
    return s
edit(JAVA/'service/wms/WmsInboundService.java',actions)
edit(ROOT/'oms-modules/oms-supplychain/src/test/java/com/oms/supplychain/PurchaseWorkspaceTest.java',lambda s:s.replace('assertEquals("PROCESSING",service.detail("QM","ticket",t).get("progress"));assertEquals(0,calls.get());','assertEquals("ATTENTION",service.detail("QM","ticket",t).get("progress"));assertEquals(0,calls.get());'))
edit(ROOT/'docker/local/verify_purchase_workspace_ui.py',lambda s:s.replace("to_contain_text('入账成功')","to_contain_text('全部实收入账')").replace("name='完成虚拟入库'","name='重试虚拟入库'"))
edit(ROOT/'docker/local/verify_wms_inbound_ui.py',lambda s:s.replace("to_contain_text('收货完成')","to_contain_text('收货结束')").replace("to_contain_text('入账成功')","to_contain_text('全部实收入账')"))
