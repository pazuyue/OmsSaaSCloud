from update_ticket_workspace import ROOT, edit
UI=ROOT/'ruoyi-ui/src'
edit(UI/'api/purchase/workspace.js',lambda s:s+"\nexport const receiptBatches = (id, params) => request({ url: `${root}/ticket/${id}/batches`, params })\n")
def panel(s):
    s=s.replace('收货完成','收货结束').replace('查询仓库结果','查询仓库状态')
    s=s.replace('v-hasPermi="[\'warehouse:tickets:retry\']" size="small" :loading="acting" @click="perform(\'query\')"','v-hasPermi="[\'warehouse:tickets:queryWarehouse\']" size="small" :loading="acting" @click="perform(\'query\')"')
    s=s.replace('>申请取消入库</el-button>',">{{ task.dispatch_state === 'PENDING' ? '取消入库单' : '向仓库申请取消' }}</el-button>")
    s=s.replace('@click="load">刷新进度','@click="refresh">刷新进度')
    s=s.replace('      <div class="actions">','''      <p v-if="task.last_query_time" :class="task.last_query_result === 'SUCCESS' ? '' : 'error'">最近查询 {{ parseTime(task.last_query_time) }} · {{ task.query_message }}</p>
      <div class="actions">''')
    s=s.replace('    </template>','''      <p class="muted">刷新进度读取本地记录；查询仓库状态会请求对接仓库，实收数量及库存仍以收货回传为准。</p>
      <p v-if="task.receipt_state !== 'WAITING'" class="muted">已收货或收货已结束，不能取消入库单。</p>
    </template>''')
    a=s.index('async perform(action)');b=s.index('\n}',a)
    s=s[:a]+'''async refresh() { await this.load(); this.$emit('changed') },
    async perform(action) {
      if (this.acting) return
      let data = {}
      if (action === 'cancel') {
        try { const r = await this.$prompt(this.task.dispatch_state === 'PENDING' ? '填写取消原因，尚未下发的入库单将直接取消。' : '填写取消原因。申请后须等待仓库确认，期间仍可能收到收货回传。', '取消入库', { inputType: 'textarea', inputValidator: v => !!v && !!v.trim() && v.length <= 500 || '请填写 1 至 500 字原因' }); data = { reason: r.value } } catch (_) { return }
      }
      this.acting = true
      try {
        const r = await inboundAction(this.ticketId, action, data)
        const result = r.data
        if (result.success) this.$modal.msgSuccess(result.message)
        else this.$modal.msgWarning(result.message + '；' + (result.nextAction || '请查看交互日志'))
        await this.load(); this.$emit('changed')
      } catch (_) { /* HTTP errors are shown by the shared request handler. */ } finally { this.acting = false }
    }
  }'''+s[b:]
    return s
edit(UI/'views/oms/warehouse/WmsInboundPanel.vue',panel)
def workspace(s):
    s=s.replace("import WmsInboundPanel", "import TicketSummary from './TicketSummary'\nimport ReceiptBatches from './ReceiptBatches'\nimport WmsInboundPanel")
    s=s.replace('components: { WmsInboundPanel, WmsLogs }','components: { WmsInboundPanel, WmsLogs, TicketSummary, ReceiptBatches }')
    s=s.replace("batchCode: '' })", "batchCode: '', progress: '', ownerId: null, realStoreId: null, actualWarehouse: null, provider: '', externalOrder: '' })")
    s=s.replace('<el-form-item :label="kind === \'purchase\' ? \'单据状态\' : \'状态\'">','<el-form-item v-if="kind !== \'ticket\'" :label="kind === \'purchase\' ? \'单据状态\' : \'状态\'">')
    s=s.replace('          <el-form-item label="来源单号">','''          <el-form-item label="货主"><el-select v-model="query.ownerId" clearable filterable><el-option v-for="o in ownerChoices" :key="o.id" :label="o.name" :value="o.id" /></el-select></el-form-item>
          <el-form-item label="实体仓库"><el-select v-model="query.realStoreId" clearable filterable><el-option v-for="o in realChoices" :key="o.id" :label="o.name" :value="o.id" /></el-select></el-form-item>
          <el-form-item label="执行方式"><el-select v-model="query.actualWarehouse" clearable><el-option label="真实入库" :value="1" /><el-option label="虚拟入库" :value="2" /></el-select></el-form-item>
          <el-form-item label="对接平台"><el-select v-model="query.provider" clearable><el-option label="奇门" value="QIMEN" /><el-option label="京东虎符" value="JD_HUFU" /></el-select></el-form-item>
          <el-form-item label="仓库单号"><el-input v-model.trim="query.externalOrder" clearable placeholder="输入完整仓库单号" /></el-form-item>
          <el-form-item label="来源单号">''')
    s=s.replace('    <div class="toolbar">\n      <el-button v-if="kind', '''    <el-radio-group v-if="kind === 'ticket'" v-model="query.progress" class="progress-filters" size="small" @change="search"><el-radio-button label="">全部</el-radio-button><el-radio-button label="PROCESSING">处理中</el-radio-button><el-radio-button label="ATTENTION">待处理异常</el-radio-button><el-radio-button label="COMPLETE">已完成</el-radio-button><el-radio-button label="CANCELED">已取消</el-radio-button></el-radio-group>
    <div class="toolbar">
      <el-button v-if="kind''',1)
    s=s.replace('placeholder="按批次查询"','placeholder="按计划批次查询"').replace('v-if="view === \'line\'" label="批次"','v-if="view === \'line\'" label="计划批次"')
    s=s.replace("{{ kind === 'purchase' ? s.row.poSn : s.row.originalSn }}</div>","{{ kind === 'purchase' ? s.row.poSn : s.row.originalSn }}</div><div v-if=\"kind === 'ticket'\" class=\"muted\">{{ s.row.sourceLabel }} <el-button type=\"text\" size=\"mini\" @click=\"copy(s.row.sn)\">复制单号</el-button></div>")
    anchor='        <el-table-column v-if="view !== \'line\'"'
    s=s.replace(anchor,'''        <el-table-column v-if="kind === 'ticket'" label="执行方式" width="115"><template slot-scope="s">{{ s.row.modeLabel }}<div class="muted">{{ s.row.providerLabel }}</div></template></el-table-column>
'''+anchor,1)
    s=s.replace(": '执行状态'\" :width=\"kind === 'purchase' ? 180 : 115", ": '单据 / 执行进度'\" :width=\"kind === 'purchase' ? 180 : 180")
    s=s.replace(":type=\"s.row.stateNeedsReview ? 'danger' : stateColor(kind === 'purchase' ? s.row.poState : s.row.statusTicket)\"", ":type=\"kind === 'ticket' ? progressColor(s.row) : s.row.stateNeedsReview ? 'danger' : stateColor(s.row.poState)\"")
    s=s.replace('{{ documentState(kind, s.row) }}</el-tag>', '{{ documentState(kind, s.row) }}</el-tag><div v-if="kind === \'ticket\'" class="muted">{{ s.row.executionLabel }}</div>')
    s=s.replace('label="库存入账" width="110"', 'label="库存入账" width="130"')
    s=s.replace(": '计划 / 实收'\" width=\"130\"", ": '计划 / 实收 / 入账'\" width=\"160\"")
    s=s.replace('{{ s.row.numberExpected }} / {{ s.row.numberActually }}<div', '{{ s.row.numberExpected }} / {{ s.row.numberActually }}<span v-if="kind === \'ticket\'"> / {{ s.row.numberPosted }}</span><div',1)
    s=s.replace('差异 {{ s.row.numberDifActually }}','{{ s.row.receiptFinal ? \'最终短收 \' + Math.max(0, s.row.numberExpected - s.row.numberActually) : \'收货未结束\' }}')
    s=s.replace('      <el-table-column label="操作" fixed="right"', '''      <el-table-column v-if="kind === 'ticket'" label="最近更新时间" min-width="160"><template slot-scope="s">{{ parseTime(s.row.businessUpdatedTime) }}</template></el-table-column>
      <el-table-column label="操作" fixed="right"''',1)
    s=s.replace("view === 'line' ? 90 : 115", "kind === 'ticket' ? 165 : 115")
    s=s.replace('>详情</el-button>\n        <el-button v-if="kind', '''>详情</el-button>
        <el-button v-if="kind === 'ticket' && can('ticket', 'query') && !['COMPLETE', 'CANCELED'].includes(s.row.progress)" type="text" size="mini" @click="openRow(s.row)">{{ s.row.progress === 'ATTENTION' ? '处理异常' : '跟踪进度' }}</el-button>
        <el-button v-if="kind''',1)
    s=s.replace('document.noName || document.sn', "document.noName || document.sourceLabel")
    s=s.replace('          <wms-inbound-panel v-if=', '''          <ticket-summary v-if="detailKind === 'ticket'" :document="document" />
          <wms-inbound-panel v-if=''',1)
    s=s.replace('>完成虚拟入库</el-button>','>重试虚拟入库</el-button>')
    s=s.replace("document.inventoryStatus === 3 ? '重试失败明细' : '库存入账'", "document.inventoryStatus === 3 ? '重试入账' : '继续入账'")
    s=s.replace("@click=\"reasonAction('cancel')\">作废单据", "@click=\"reasonAction('cancel')\">{{ detailKind === 'ticket' ? '取消入库单' : '作废单据' }}")
    s=s.replace('v-if="document.lines" label="商品明细"', 'v-if="document.lines" :label="detailKind === \'ticket\' ? \'商品汇总\' : \'商品明细\'"')
    s=s.replace('v-if="detailKind === \'ticket\'" label="批次"', 'v-if="detailKind === \'ticket\'" label="计划批次"')
    s=s.replace("{{ entryStates[s.row.inventoryIsHandle] || '状态异常' }}",'{{ s.row.postingLabel }} · 已入账 {{ s.row.numberPosted }}')
    s=s.replace('            <el-tab-pane v-if="document.receipts"', '''            <el-tab-pane v-if="detailKind === 'ticket'" label="实际收货批次" name="batches" lazy><receipt-batches :key="document.id" :ticket-id="Number(document.id)" :real="document.actualWarehouse === 1" /></el-tab-pane>
            <el-tab-pane v-if="detailKind === 'ticket'" label="关联单据" name="related"><el-descriptions :column="1" border><el-descriptions-item label="来源采购"><el-button type="text" :disabled="!can('purchase', 'query')" @click="show('purchase', { id: document.purchaseId })">{{ document.originalSn }}</el-button></el-descriptions-item><el-descriptions-item label="到货单"><el-button type="text" :disabled="!can('purchase', 'query')" @click="show('receipt', { id: document.receiptId })">{{ document.relationSn }}</el-button></el-descriptions-item></el-descriptions></el-tab-pane>
            <el-tab-pane v-if="detailKind === 'ticket' && document.actualWarehouse === 1 && canWmsLogs" label="交互日志" name="logs" lazy><wms-logs :key="document.id" embedded :ticket-sn="document.sn" /></el-tab-pane>
            <el-tab-pane v-if="document.receipts"''',1)
    s=s.replace("{{ states('ticket')[s.row.statusTicket] }}", "{{ s.row.progressLabel }}<div class=\"muted\">{{ s.row.executionLabel }}</div>")
    s=s.replace('    canWmsLogs()', '''    ownerChoices() { return [...new Map(this.choices.warehouses.filter(w => w.ownerId).map(w => [w.ownerId, { id: w.ownerId, name: w.ownerName }])).values()] },
    realChoices() { return [...new Map(this.choices.warehouses.filter(w => w.realStoreId).map(w => [w.realStoreId, { id: w.realStoreId, name: w.wmsName }])).values()] },
    canWmsLogs()''')
    s=s.replace("documentState(kind, row) {", "progressColor(row) { return { COMPLETE: 'success', ATTENTION: 'danger', CANCELED: 'info', PROCESSING: '' }[row.progress] },\n    documentState(kind, row) { if (kind === 'ticket') return row.progressLabel;")
    s=s.replace('entryLabel(row) { return', 'entryLabel(row) { if (row.postingLabel) return row.postingLabel; return')
    s=s.replace("if (key === 'inventoryStatus') return this.entryStates[value]", "if (key === 'inventoryStatus') return this.document.postingLabel || this.entryStates[value]")
    s=s.replace('else f.push([\'ticketType\', \'出入库类型\']', "else f.push(['modeLabel', '执行方式'], ['providerLabel', '对接平台'], ['externalOrder', '仓库单号'], ['ticketType', '出入库类型']")
    s=s.replace('.receipt-options{','.progress-filters{display:flex;flex-wrap:wrap;margin-bottom:18px}.receipt-options{',1)
    return s
edit(UI/'views/oms/purchase/PurchaseWorkspace.vue',workspace)
def logs(s):
    s=s.replace('<el-dialog title="仓库交互日志"', '<component :is="embedded ? \'div\' : \'el-dialog\'" title="仓库交互日志"',1)
    at=s.index('\n  </el-dialog>\n</template>');s=s[:at]+s[at:].replace('</el-dialog>','</component>',1)
    s=s.replace('<el-form inline', '<el-form v-if="!embedded" inline',1).replace('<div slot="footer"><el-button @click="visible = false">','<div v-if="!embedded" slot="footer"><el-button @click="visible = false">',1)
    s=s.replace('export default { data()', "export default { props: { embedded: Boolean, ticketSn: { type: String, default: '' } }, watch: { ticketSn: { immediate: true, handler(v) { if (this.embedded) { this.sn = v; this.page = 1; this.load() } } } }, data()")
    return s
edit(UI/'views/oms/warehouse/WmsLogs.vue',logs)
