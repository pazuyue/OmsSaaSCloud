export const labels = {
  WAITING: '待转单', FAILED: '失败', SUCCESS: '成功', SKIPPED: '已跳过', INVALID: '资料异常', CONFLICT: '版本冲突', VALID: '校验通过',
  NEW: '新增', UPDATED: '已更新', DUPLICATE: '内容相同', OLDER: '旧版本已保留', NOT_FOUND: '推送库未找到',
  WAIT_RETURN:'待退回收货',PART_RETURNED:'部分实收',WAIT_CHECK:'待人工核对',WAIT_QC:'待确认货物处理完成',
  PENDING: '待处理', RUNNING: '处理中', RETRY: '等待重试', COMPLETED: '已完成', BLOCKED: '待核对', NEEDS_REVIEW: '待审核', PROCESSING: '处理中',
  APPROVED: '已审核', STALE: '方案待重审', NONE: '待平台处理', CLOSED: '已关闭', CANCELED: '已取消待执行方案', DONE: '已处理', WAIT_REVIEW: '待审核方案', WAIT_ACTION: '待货物处理',
  REFUND: '仅退款', REFUND_AND_RETURN: '退货退款', DURING_SALE: '售中', AFTER_SALE: '售后', UNKNOWN: '待确认',
  WAIT_SELLER_AGREE: '买家申请，等待卖家处理', WAIT_BUYER_RETURN_GOODS: '等待买家退货', WAIT_SELLER_CONFIRM_GOODS: '等待卖家确认收货', SELLER_REFUSE_BUYER: '卖家拒绝退款',
  CANCEL: '取消商品', DIFFERENCE: '退差价', COMPENSATION: '补偿', POSTAGE: '退运费', OTHER: '其他金额退款', PLATFORM_SPECIAL: '平台专项退款',
  KEEP: '继续履约 / 不追回商品', INTERCEPT: '在途拦截', REFUSAL: '买家拒收', RETURN: '协商退回',
  AUTO: '自动同步', MANUAL: '时间补拉', IDS: '指定退款单号', PLATFORM_CHANGED: '申请内容变化', SOURCE_CONFLICT: '来源资料异常'
}
export const label = value => labels[value] || value || '—'
export const purposes = ['CANCEL', 'DIFFERENCE', 'COMPENSATION', 'POSTAGE', 'OTHER', 'PLATFORM_SPECIAL']
export const goodsActions = ['KEEP', 'CANCEL', 'INTERCEPT', 'REFUSAL', 'RETURN']
