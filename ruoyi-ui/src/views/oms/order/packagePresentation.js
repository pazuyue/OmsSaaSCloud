const amount = value => Number(value || 0)
export const warehouseKey = row => JSON.stringify([row.store_code || '', row.real_store_code || '', row.owner_code || ''])

export const warehouseFulfillmentGroups = order => {
  const groups = new Map()
  const groupFor = row => {
    const key = warehouseKey(row)
    if (!groups.has(key)) groups.set(key, { key, name: row.store_code ? '虚拟仓 ' + row.store_code : '虚拟仓未确认', code: row.store_code, realName: row.real_store_name || row.real_store_code || '实体仓未确认', realCode: row.real_store_code, owner: row.owner_code, allocated: 0, shipments: [], hasAllocations: false })
    return groups.get(key)
  }
  ;(order.warehouse_allocations || []).forEach(row => { const group = groupFor(row); group.hasAllocations = true; group.allocated += amount(row.quantity) })
  ;(order.shipments || []).forEach(row => groupFor(row).shipments.push(row))
  return [...groups.values()].map(group => {
    const active = group.shipments.filter(row => !['VOIDING', 'VOIDED'].includes(row.status))
    const sum = key => group.shipments.reduce((total, row) => total + amount(row[key]), 0)
    const planned = group.hasAllocations ? group.allocated : active.reduce((total, row) => total + amount(row.quantity), 0)
    return { ...group, allocated: order.status === 'VOIDED' ? 0 : planned, shipped: sum('shipped_quantity'), packages: sum('package_count'), packaged: sum('packaged_quantity'), unpacked: sum('unpacked_quantity'), remaining: order.status === 'VOIDED' ? 0 : Math.max(0, planned - sum('shipped_quantity')) }
  })
}

export const packageSkuGroups = lines => {
  const groups = new Map()
  ;(lines || []).forEach(line => {
    const key = String(line.sku_sn || '')
    if (!groups.has(key)) groups.set(key, { sku: key, name: line.goods_name, quantity: 0 })
    groups.get(key).quantity += amount(line.quantity)
  })
  return [...groups.values()]
}

// This marker is emitted by the explicit local simulation tool, not by a real carrier.
export const simulatedPackage = pack => /^SIMPKG_/.test(pack.package_code || '') && /^TEST-/.test(pack.express_code || '')
