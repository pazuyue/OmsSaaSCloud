export const settingsPath = '/oms-order/afterSaleRules/settings'
export const settingsTabs = [{ name: 'intake', label: '退款接入' }, { name: 'authorization', label: '平台授权' }, { name: 'automation', label: '自动处理' }, { name: 'returns', label: '退货与入库' }]
const yes = value => value === true || Number(value) === 1
const gate = (ok, label, tab) => ({ ok: !!ok, label, tab })

// These are saved local prerequisites. Each live refund still checks platform operations and goods facts.
export function configurationChecks(row) {
  const p = row.flow || {}; const a = row.access || {}; const r = row.returns || {}; const api = row.api_sync || {}
  const shop = gate(yes(row.shop_enabled), '店铺已启用', 'automation')
  const auth = gate(yes(a.available) && yes(a.can_query), a.available === false ? '授权状态待重新读取' : '有效店铺授权与平台对接', 'authorization')
  const read = gate(yes(a.read_enabled), '允许查询退款', 'authorization')
  const write = gate(yes(a.write_enabled), '允许提交售后操作', 'authorization')
  const master = gate(yes(p.enabled), '自动处理总开关已开启', 'automation')
  const stores = (r.store_codes || []).length > 0 || !!r.create_store_code
  return [
    { id: 'api', label: '普通退款接口补充同步', requested: yes(api.enabled), gates: [shop, auth, read] },
    { id: 'return-review', label: '退货退款自动确认方案', requested: yes(p.auto_return_review), gates: [shop, master, gate(Number(p.return_review_limit)>0, '已设置自动确认金额上限', 'automation'), gate(yes(r.auto_create) && !!r.create_store_code && !!r.create_return_address && !!r.create_return_contact, '已配置自动退回及仓库资料', 'returns')] },
    { id: 'difference', label: '采用明确差价授权', requested: yes(p.auto_difference), gates: [shop, master] },
    { id: 'cancel', label: '未发全额退款按取消购买', requested: yes(p.auto_full_cancel), gates: [shop, master] },
    { id: 'small', label: '小额退款免退货', requested: yes(p.small_refund_enabled), gates: [shop, master, gate(Number(p.small_refund_limit) > 0, '已设置子订单累计免退上限', 'automation')] },
    { id: 'shipped', label: '已发剩余全额自动追回', requested: yes(p.auto_shipped_cancel), gates: [shop, master, gate(yes(r.auto_create) && !!r.create_store_code && !!r.create_return_address && !!r.create_return_contact, '已开启自动退回并配置退回资料', 'returns')] },
    { id: 'finish', label: '无差异实收入账自动结案', requested: yes(p.auto_finish_return), gates: [shop, master] },
    { id: 'agree', label: '自动同意平台退货', requested: yes(p.auto_agree_return), gates: [shop, master, auth, read, write, gate(!!p.seller_address_id, '已选择平台退回地址', 'automation')] },
    { id: 'feedback', label: '自动回告取消发货', requested: yes(p.auto_cancel_feedback), gates: [shop, master, auth, read, write] },
    { id: 'intercept', label: '自动申请在途拦截', requested: yes(p.auto_intercept), gates: [shop, master, auth, read, write] },
    { id: 'review', label: '自动审核平台退款', requested: yes(p.auto_review), gates: [shop, master, auth, read, write] },
    { id: 'pay', label: '自动退款付款', requested: yes(p.auto_pay) && p.funds_owner === 'OMS', gates: [shop, master, auth, read, write, gate(yes(a.payment_enabled), '允许实际退款付款', 'authorization'), gate(yes(a.payment_authorized), '付款子账号授权有效', 'authorization'), gate(yes(a.ignore_code), '商家已开通平台免验证码', 'authorization'), gate(yes(p.auto_review), '已启用自动审核', 'automation'), gate(Number(p.payment_limit) > 0, '已设置正数付款限额', 'automation')] },
    { id: 'create', label: '自动创建退回安排', requested: yes(r.auto_create), gates: [shop, gate(!!r.create_store_code && !!r.create_return_address && !!r.create_return_contact, '已配置退回仓、地址及联系人', 'returns')] },
    { id: 'merge', label: '自动合包', requested: r.merge_mode === 'AUTO', gates: [shop, gate(stores, '已指定适用退回仓', 'returns'), gate((r.logistics_sources || []).length > 0, '已选择可信物流来源', 'returns')] },
    { id: 'dispatch', label: '自动下发仓库', requested: yes(r.auto_dispatch), gates: [shop, gate(stores, '已指定适用退回仓', 'returns')] }
  ].map(item => ({ ...item, missing: item.gates.filter(g => !g.ok), state: !item.requested ? 'OFF' : item.gates.every(g => g.ok) ? 'READY' : 'WAIT' }))
}

export function configurationOverview(row) {
  const p = row.flow || {}; const i = row.intake || {}; const api = row.api_sync || {}; const a = row.access || {}; const r = row.returns || {}
  const checks = configurationChecks(row); const automation = checks.filter(c => !['api', 'create', 'merge', 'dispatch'].includes(c.id) && c.requested)
  return {
    intake: !yes(row.shop_enabled) ? '店铺已停用，自动同步暂停' : i.last_error || api.last_error ? '同步异常' : yes(i.enabled) || yes(api.enabled) ? (i.last_success || api.last_success ? '最近同步成功' : '已开启，尚无成功记录') : Number(i.revision) || yes(api.enabled) ? '自动同步未开启' : '未配置自动同步',
    access: a.available === false ? '状态暂不可用' : !yes(a.can_query) ? a.authorization || '待授权' : !yes(a.write_enabled) ? '已授权，售后操作未开启' : !yes(a.payment_authorized) ? '售后操作已允许，付款未授权' : '售后及付款已授权',
    automation: !yes(row.shop_enabled) ? '店铺已停用，自动处理暂停' : !yes(p.enabled) ? '未开启' : !automation.length ? '已开启，未选择动作' : automation.some(c => c.state === 'WAIT') ? '部分条件未满足' : '已启用，按单据条件执行',
    returns: `${({ NONE: '不合包', MANUAL: '人工合包', AUTO: '自动合包' })[r.merge_mode] || '人工合包'} · ${yes(r.auto_dispatch) ? '自动下发' : '人工下发'}`,
    waiting: checks.filter(c => c.state === 'WAIT' && (yes(p.enabled) || ['api', 'create', 'merge', 'dispatch'].includes(c.id))),
    flow: [!yes(row.shop_enabled) ? '店铺停用，以下自动动作暂停' : '接入退款', yes(p.enabled) && (yes(p.auto_full_cancel) || yes(p.small_refund_enabled) || yes(p.auto_shipped_cancel) || yes(p.auto_return_review) || yes(p.auto_difference)) ? [yes(p.auto_return_review) ? '明确退货范围自动确认' : '', yes(p.auto_difference) ? '匹配有效差价授权后自动确认' : '', yes(p.small_refund_enabled) ? `累计≤${Number(p.small_refund_limit || 0).toFixed(2)}元优先免退` : '', yes(p.auto_full_cancel) ? '未发剩余全额自动取消' : '', yes(p.auto_shipped_cancel) ? '已发剩余全额自动追回' : '', '其余人工确认'].filter(Boolean).join('；') : '人工确认处理方案', yes(p.enabled) && yes(p.auto_agree_return) ? '满足条件后自动同意退货' : '人工处理平台退货', yes(r.auto_create) ? '自动创建退回安排' : '人工创建退回安排', `${r.merge_mode === 'AUTO' ? `自动合包等待${Number(r.collect_seconds || 0)}秒` : r.merge_mode === 'NONE' ? '独立退回' : '人工合包'}、${yes(r.auto_dispatch) ? '自动下发' : '人工下发'}`, yes(p.enabled) && yes(p.auto_review) ? (yes(p.auto_finish_return) ? '全部实收入账无差异后自动结案、审核' : '货物条件满足后自动审核') : '人工审核平台退款', p.funds_owner === 'OMS' && yes(p.enabled) && yes(p.auto_pay) ? `满足授权及限额后OMS提交付款（≤${Number(p.payment_limit || 0).toFixed(2)}元）` : p.funds_owner === 'OMS' ? '人工提交退款付款' : '平台处理付款，OMS核对结果']
  }
}
