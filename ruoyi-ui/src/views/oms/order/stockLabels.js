export function logicalStockLabel(status) {
  return ({ WAIT_RESERVE: '待逻辑预占', PART_RESERVED: '部分逻辑预占', RESERVED: '逻辑预占完成', PART_CONSUMED: '逻辑预占部分核销', CONSUMED: '逻辑预占已核销', RELEASED: '逻辑预占已释放' })[status] || '状态待确认'
}

export function warehouseStockLabel(status) {
  return ({ NOT_OCCUPIED: '未占用', PART_OCCUPIED: '部分占用', OCCUPIED: '已占用', PART_OUTBOUND: '部分出库', OUTBOUND: '已出库', RELEASED: '已释放', UNKNOWN: '状态待确认' })[status] || '状态待确认'
}

export function warehouseStockTone(status) {
  return ['OCCUPIED', 'OUTBOUND'].includes(status) ? 'success' : ['PART_OCCUPIED', 'PART_OUTBOUND'].includes(status) ? 'warning' : 'info'
}
