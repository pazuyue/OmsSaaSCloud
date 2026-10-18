export const conversionStates = { WAITING: '待转单', FAILED: '转单失败', SUCCESS: '已转单 / 已关联', SKIPPED: '无需转单' }
export const sourceStates = { VALID: '来源正常', INVALID: '资料异常', CONFLICT: '版本冲突' }
export const phases = { DURING_SALE: '售中换货', AFTER_SALE: '售后换货', UNKNOWN: '阶段待确认' }
export const outcomes = { NEW: '新增', UPDATED: '更新', DUPLICATE: '内容相同', OLDER: '旧版本保留', CONFLICT: '版本冲突', INVALID: '资料异常', FAILED: '读取失败' }
export const fields = { biz_order_id: '平台子单号', num: '申请数量', bought_sku: '原商品规格', exchange_sku: '目标商品规格', status: '平台状态', refund_phase: '申请阶段', speed_exchange: '极速换货标识', time_out: '处理截止时间', buyer_address: '收件地址', buyer_name: '收件人', buyer_phone: '联系方式', oaid: '收件信息标识', buyer_logistic_no: '买家寄回运单', seller_logistic_no: '卖家换出运单' }
export function deadline(row) { if (['4', '5', '14'].includes(String(row.platform_status))) return '平台流程已结束'; if (!row.platform_deadline) return '平台未提供时限'; const t = Date.parse(String(row.platform_deadline).replace(' ', 'T') + '+08:00'); if (!Number.isFinite(t)) return '时限待核对'; return t < Date.now() ? '已超处理时限' : `截止 ${row.platform_deadline}` }
