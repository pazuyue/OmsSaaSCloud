const operationNames = { AGREE: '同意换货', REFUSE: '拒绝换货', RECEIVED: '确认退回收货', CONSIGN: '回传换出物流' }
export function platformProgress(e = {}) {
  const status = (code, title, description, tone = 'info') => ({ code, title, description, tone })
  if (e.linked_exchange_id) return status('MANUAL', '平台操作需人工核对', '实物履约沿用关联协商单；平台同意、收货及物流需人工在平台处理。')
  if (e.source_kind === 'INTERNAL') return e.fulfillment_mode === 'UNSHIPPED'
    ? status('ORIGINAL_ORDER', '原订单继续发货', '无独立平台换货申请；实际销售发货从原订单处理，本换货单不调用平台换货接口。')
    : status('LOCAL_ONLY', '仅 OMS 内履约，无需换货回传', '无独立平台换货申请；退回验收及换出完成后在 OMS 结束，不向平台提交换货结果。')
  const operation = e.platform_action || ''
  const name = operationNames[operation] || '平台操作'
  if (['UNKNOWN', 'SENDING'].includes(e.platform_operation)) return status('UNKNOWN', name + '结果待确认', '已有平台处理请求，请先核对原请求结果，避免重复提交。', 'warning')
  if (e.platform_operation === 'FAILED') return status('FAILED', name + '失败', '请核对平台交互日志及平台最新申请状态，再从本单继续处理。', 'danger')
  if (e.platform_operation === 'PENDING') return status('PENDING', name + '待执行', '处理请求已登记；登记请求不代表平台已接收或处理成功。')
  if (e.platform_operation === 'STOCK_WAIT') return status('STOCK_WAIT', '库存不足，尚未同意平台申请', '保留原申请数量，等待换出库存后继续原处理任务。', 'warning')
  if (e.state === 'COMPLETED' && ['13', '5'].includes(String(e.platform_status))) return status('CONFIRMED', '平台已确认换出物流', '平台最新查询已确认换出发货状态；本次换货履约已完成。', 'success')
  if (['CLOSED', 'CANCELED', 'REJECTED'].includes(e.state)) return status('ENDED', '本次处理已结束', '已执行的接口结果保留；结束 OMS 处理不代表平台已完成换货，请核对平台申请状态。')
  if (Number(e.fulfillment_hold)) return status('PAUSED', '履约已暂停', '暂停期间不提交新的换货物流，请先处理履约控制。', 'warning')
  if (Number(e.speed_exchange)) return status('MANUAL', '极速换货需在平台处理', '当前执行流程不支持极速换货，请人工核对平台申请。', 'warning')
  if (e.state === 'BLOCKED') return status('BLOCKED', '本单待核对，暂缓平台处理', '请先处理本单问题及平台最新状态，尚不能提交新的换出物流。', 'warning')
  if (['13', '5'].includes(String(e.platform_status))) return status('PLATFORM_REVIEW', '平台已有发货状态，本次履约待核对', '请核对平台物流与本次实际出库；已有平台状态不代表新一轮换出已经回传。', 'warning')
  if (!e.approved_version) return status('WAIT_REVIEW', '待审核换货方案', '尚未提交换出物流；先核对来源、商品、数量及收货信息。')
  if (String(e.platform_status) !== '12') return status('WAIT_PLATFORM', '尚未进入物流回传阶段', '需先完成平台同意、退回验收及收货确认；平台状态允许换出后再回传物流。')
  const required = Number(e.requested_quantity || 0)
  const shipped = Number(e.platform_shipped_quantity || 0)
  const packed = Number(e.platform_packaged_quantity || 0)
  if (!required || shipped !== required) return status('WAIT_SHIPMENT', shipped > required ? '换出数量待核对' : '待换出商品全部出库', `已出库 ${shipped} / ${required} 件，尚未提交换出物流。`)
  if (packed !== shipped) return status('WAIT_PACKAGE', '待完整关联包裹与运单', `已出库 ${shipped} 件，已关联包裹 ${packed} 件，数量一致后才能回传。`, 'warning')
  if (Number(e.platform_tracking_count) !== 1) return status('MANUAL_TRACKING', '运单需人工核对', '当前回传支持一组快递与运单；请核对多运单在平台的处理方式，暂不提交。', 'warning')
  if (!Number(e.platform_write_enabled)) return status('WRITE_DISABLED', '待开启店铺换货写权限', '出库与包裹已齐备；请在换货接入设置中核对写操作开关。', 'warning')
  return status('READY', '待回传换出物流', '出库与运单已齐备，尚未提交。请人工发起回传；仓库发货完成不会自动通知平台。', 'warning')
}
