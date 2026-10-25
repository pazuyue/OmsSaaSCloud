import { label } from './refundLabels'

const actions = { RETURN: '退货入库', CANCEL: '取消未发商品', KEEP: '保留商品 / 继续履约', INTERCEPT: '在途拦截', REFUSAL: '拒收退回' }
export function refundGoodsProcessing(refund) {
  let action = actions[refund.goods_action] || '待确认商品处理'
  let state = refund.goods_state === 'DONE' ? '已完成' : label(refund.goods_state)
  if (refund.goods_action === 'MIXED') {
    const tasks = (Array.isArray(refund.goods_tasks) ? refund.goods_tasks : []).filter(t => t.state !== 'CANCELED' && Number(t.quantity) > 0)
    const kinds = [...new Set(tasks.map(t => t.action))]
    action = !kinds.length || kinds.some(k => !actions[k]) ? '分项方案待核对' : kinds.length === 1 ? actions[kinds[0]] : '分项处理：' + kinds.map(k => actions[k]).join('、')
    if (refund.goods_state === 'DONE' && (!tasks.length || tasks.some(t => t.state !== 'DONE'))) state = '任务结果待核对'
  }
  return { action, state }
}
