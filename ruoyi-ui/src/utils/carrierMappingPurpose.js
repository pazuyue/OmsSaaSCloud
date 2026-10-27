const rules = {
  CHANNEL_SHIPPING: { direction: 'OUT', label: '销售发货回传', description: '将 OMS 标准快递转换为该平台的发货物流标识。' },
  CHANNEL_EXCHANGE_LOGISTICS: { direction: 'OUT', label: '换货发出物流回传', description: '将换出包裹的实际快递转换为该平台要求的物流标识。' },
  CHANNEL_RETURN_IDENTIFY: { direction: 'IN', label: '识别买家寄回物流', description: '按平台提供的买家寄回物流标识，识别对应的 OMS 标准快递。' },
  CHANNEL_RETURN_BACKFILL: { direction: 'OUT', label: '向平台回填寄回物流', description: '向平台补填寄回物流时，将 OMS 标准快递转换为平台要求的物流标识。' },
  WMS_OUTBOUND_CREATE: { direction: 'OUT', label: '下发编码转换', description: '选择 OMS 标准快递，下发时转换为该 WMS 的出库快递编码。' },
  WMS_RETURN_CREATE: { direction: 'OUT', label: '下发编码转换', description: '选择 OMS 标准快递，下发时转换为该 WMS 的退回入库快递编码。' },
  WMS_OUTBOUND_ACTUAL: { direction: 'IN', label: '回传快递识别', description: '按 WMS 实际出库回传的原始编码，识别对应的 OMS 标准快递。' },
  WMS_RETURN_ACTUAL: { direction: 'IN', label: '回传快递识别', description: '按 WMS 实际入库回传的原始编码，识别对应的 OMS 标准快递。' }
}

export function carrierMappingRule(row) {
  return row && row.purpose && row.purpose.startsWith(row.system_type + '_') ? rules[row.purpose] || null : null
}

export function mappingForm(row) {
  const result = { ...row }, rule = carrierMappingRule(result)
  if (rule) {
    result.direction = rule.direction
    if (rule.direction === 'IN') result.is_default = 0
  }
  return result
}

export function mappingFlow(row) {
  const rule = carrierMappingRule(row), direction = rule ? rule.direction : row.direction
  const standard = 'OMS ' + row.standard_code, external = (row.system_type === 'WMS' ? 'WMS ' : '平台 ') + row.external_code
  return direction === 'IN' ? external + ' → ' + standard : standard + (direction === 'BOTH' ? ' ↔ ' : ' → ') + external
}
