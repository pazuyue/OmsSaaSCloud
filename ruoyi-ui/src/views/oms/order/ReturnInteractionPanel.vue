<template>
  <section class="return-interactions">
    <div class="toolbar"><span>本单交互记录 · 每次请求和重试独立保留</span><div><el-select v-model="result" clearable placeholder="全部交互结果" size="small" @change="load(false)"><el-option v-for="(v,k) in results" :key="k" :value="k" :label="v" /></el-select><el-button size="small" @click="load(false)">刷新交互记录</el-button></div></div>
    <el-alert title="以下结果属于交互发生时；仓库当前受理、实收及入账状态分别以上方进度和库存明细为准。" type="info" :closable="false" />
    <el-alert v-if="!canLogs" title="当前账号没有查看仓库交互记录权限" type="info" :closable="false" />
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" />
    <el-table v-if="canLogs" v-loading="loading" :data="rows" size="small" empty-text="尚无符合条件的仓库交互记录">
      <el-table-column label="时间 / 方向" min-width="170"><template slot-scope="{row}">{{ row.create_time }}<div class="muted">{{ direction(row.direction) }}</div></template></el-table-column>
      <el-table-column label="动作 / 当次结果" min-width="190"><template slot-scope="{row}"><strong>{{ actions[row.action] || row.action }}</strong><div><el-tag size="mini" :type="resultTone(row.result)">{{ results[row.result] || row.result }}</el-tag> {{ row.duration_ms }} ms</div><div class="muted">{{ row.result_summary || '查看当次响应与执行结果' }}</div></template></el-table-column>
      <el-table-column label="仓库 / 环境 / 请求编号" min-width="240"><template slot-scope="{row}">{{ row.connection_name || '连接待核对' }} · {{ providerName(row.provider) }}<div class="muted">{{ row.environment==='PRODUCTION'?'正式环境':row.environment==='TEST'?'测试环境':'环境未知' }}</div><div class="muted">请求 {{ row.request_id }}</div></template></el-table-column>
      <el-table-column label="本次失败或待确认原因" min-width="200"><template slot-scope="{row}"><span class="danger">{{ row.error_info || '—' }}</span></template></el-table-column>
      <el-table-column label="操作" width="100"><template slot-scope="{row}"><el-button type="text" @click="show(row.id)">交互详情</el-button></template></el-table-column>
    </el-table>
    <el-button v-if="next" :loading="loading" size="small" class="more" @click="load(true)">加载更早记录</el-button>
    <el-dialog title="本次仓库交互" :visible.sync="visible" width="min(1050px,94vw)" append-to-body :close-on-click-modal="false">
      <div v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="selected"><el-descriptions border :column="2" size="small"><el-descriptions-item label="实际入库单">{{ selected.ticket_sn }}</el-descriptions-item><el-descriptions-item label="动作 / 当次结果">{{ actions[selected.action] || selected.action }} · {{ results[selected.result] }}</el-descriptions-item><el-descriptions-item label="发生时间">{{ selected.create_time }}</el-descriptions-item><el-descriptions-item label="方向 / 耗时">{{ direction(selected.direction) }} · {{ selected.duration_ms }} ms</el-descriptions-item><el-descriptions-item label="请求编号">{{ selected.request_id }}</el-descriptions-item><el-descriptions-item label="仓库单号（当前）">{{ selected.external_order || '尚未返回' }}</el-descriptions-item></el-descriptions><p>{{ selected.result_summary }}</p><p class="danger">{{ selected.error_info }}</p><el-alert v-if="!canPayload" title="当前账号没有查看交互报文权限" type="info" :closable="false" /><template v-else><el-button v-if="!payload" :loading="payloadLoading" size="small" @click="loadPayload">加载已脱敏请求与响应</el-button><template v-else><div class="toolbar"><el-radio-group v-model="part" size="small"><el-radio-button label="request_body">请求报文</el-radio-button><el-radio-button label="response_body">响应报文</el-radio-button></el-radio-group><el-input v-model="find" clearable placeholder="查找报文内容" size="small" /><el-button v-clipboard:copy="payloadText" size="small">复制报文</el-button></div><pre><template v-for="(segment,i) in segments"><mark v-if="segment.hit" :key="i">{{ segment.text }}</mark><template v-else>{{ segment.text }}</template></template></pre><p class="muted">报文已脱敏；仅在点击后加载。此处只读，不发起仓库命令。</p></template></template></template></div>
      <span slot="footer"><el-button @click="visible=false">关闭</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import { logCenter, logCenterDetail, interactionLog } from '@/api/warehouse/integration'
import { actions, results, resultTone, providerName } from '../warehouse/wmsLogLabels'
import { checkPermi } from '@/utils/permission'
export default {
  props: { sn: { type: String, required: true } },
  data: () => ({ actions, results, result: '', rows: [], next: null, loading: false, error: '', sequence: 0, detailSequence: 0, visible: false, selected: null, detailLoading: false, detailError: '', payload: null, payloadLoading: false, part: 'request_body', find: '' }),
  computed: {
    canLogs() { return checkPermi(['warehouse:wms:log']) }, canPayload() { return checkPermi(['warehouse:wms:payload']) },
    payloadText() { const raw = this.payload && this.payload[this.part]; if (!raw) return '尚无报文'; try { return JSON.stringify(JSON.parse(raw), null, 2) } catch (_) { return raw } },
    segments() { const text = this.payloadText; if (!this.find) return [{ text }]; const out = []; let offset = 0; let i; while ((i = text.toLowerCase().indexOf(this.find.toLowerCase(), offset)) >= 0 && out.length < 2000) { out.push({ text: text.slice(offset, i) }, { text: text.slice(i, i + this.find.length), hit: true }); offset = i + this.find.length } out.push({ text: text.slice(offset) }); return out }
  },
  watch: { sn: { immediate: true, handler() { this.visible = false; this.detailSequence++; this.load(false) } } }, beforeDestroy() { this.sequence++; this.detailSequence++ },
  methods: {
    resultTone, providerName,
    direction(value) { return value === 'IN' ? '仓库 → OMS' : value === 'LOCAL' ? 'OMS 本地处理' : 'OMS → 仓库' },
    async load(more) { if (!this.canLogs) return; const seq = ++this.sequence; this.loading = true; this.error = ''; const cursor = more ? this.next : null; if (!more) { this.rows = []; this.next = null } try { const r = (await logCenter({ numberType: 'DOCUMENT', number: this.sn, allTime: 'true', result: this.result, ...(cursor || {}) })).data; if (seq !== this.sequence) return; this.rows = more ? this.rows.concat(r.rows || []) : r.rows || []; this.next = r.next } catch (_) { if (seq === this.sequence) this.error = '仓库交互记录加载失败，请刷新重试' } finally { if (seq === this.sequence) this.loading = false } },
    async show(id) { if (!this.canLogs) return; const seq = ++this.detailSequence; this.visible = true; this.selected = null; this.detailError = ''; this.detailLoading = true; this.payload = null; this.payloadLoading = false; this.find = ''; this.part = 'request_body'; try { const row = (await logCenterDetail(id)).data; if (seq !== this.detailSequence) return; if (row.ticket_sn !== this.sn) throw new Error('单据不匹配'); this.selected = row } catch (_) { if (seq === this.detailSequence) this.detailError = '本次交互加载失败或不属于当前入库单' } finally { if (seq === this.detailSequence) this.detailLoading = false } },
    async loadPayload() { if (!this.canPayload || !this.selected || this.payloadLoading) return; const seq = this.detailSequence; this.payloadLoading = true; try { const row = (await interactionLog(this.selected.id)).data; if (seq === this.detailSequence && row.ticket_sn === this.sn) this.payload = row } catch (_) { if (seq === this.detailSequence) this.detailError = '报文加载失败，请重试' } finally { if (seq === this.detailSequence) this.payloadLoading = false } }
  }
}
</script>
<style scoped>.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:14px 0;flex-wrap:wrap}.toolbar .el-select,.toolbar .el-input{width:210px}.toolbar .el-button{margin-left:8px}.el-alert{margin:12px 0}.muted{font-size:12px;color:#7b899b;line-height:1.8;overflow-wrap:anywhere}.danger{color:#bf4949;font-size:12px;overflow-wrap:anywhere}.more{margin:14px 0}pre{background:#f4f7fa;border:1px solid #e3eaf3;padding:16px;max-height:48vh;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7;font-size:12px}mark{background:#ffe4a3}.return-interactions ::v-deep td{vertical-align:top}.return-interactions ::v-deep .el-tag{margin-top:7px}</style>
