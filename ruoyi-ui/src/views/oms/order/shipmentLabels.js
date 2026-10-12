export const shipmentStates = {
  WAIT_CARRIER: '待匹配快递', WAIT_DISPATCH: '待下发仓库', DISPATCHING: '下发处理中',
  WAREHOUSE_PROCESSING: '仓库处理中', PART_SHIPPED: '部分出库', SHIPPED: '已出库',
  VOIDING: '作废处理中', VOIDED: '已作废'
}
export const shipmentLabel = (state, mode) => Number(mode) === 2 && state === 'WAIT_DISPATCH' ? '待虚拟出库' : Number(mode) === 2 && state === 'DISPATCHING' ? '虚拟出库处理中' : shipmentStates[state] || state || '—'
export const shipmentTone = state => state === 'SHIPPED' ? 'success' : ['VOIDING', 'VOIDED'].includes(state) ? 'info' : 'warning'
export const dispatchLabel = state => ({ NONE: '未提交下发', PENDING: '等待下发', PROCESSING: '下发处理中', UNKNOWN: '结果待确认', ACCEPTED: '仓库已接收', FAILED: '下发失败', CANCELED: '已停止下发' })[state] || state || '未提交下发'

export const virtualOutboundLabel = state => ({ NONE: '未提交出库', PENDING: '等待虚拟出库', UNKNOWN: '出库结果待确认', FAILED: '虚拟出库失败', COMPLETED: '虚拟出库完成' })[state] || state || '未提交出库'
