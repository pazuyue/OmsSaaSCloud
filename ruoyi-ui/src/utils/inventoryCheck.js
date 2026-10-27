export const checkLabels = { PENDING:'排队中',RUNNING:'检查中',STOPPING:'等待当前商品结束',STOPPED:'已停止，可继续',PARTIAL:'部分查询失败',COMPLETED:'本次检查完成',FAILED:'清单收集或任务失败',MATCHED:'实物一致',PHYSICAL_DIFFERENCE:'实物数量不符',WAITING_POST:'有待入账单据',FROZEN_CHECK:'冻结 / 业务占用待核对',MISSING_WMS:'仓库未返回，数量未知',MISSING_FIELDS:'仓库数量字段缺失',MISSING_BATCH:'仓库批次缺失',UNSUPPORTED_TYPE:'质量类型待确认',QUERY_FAILED:'查询失败，数量未知' }
export function skuList(text, maximum = 100) {
 const rows = [...new Set(String(text || '').split(/[\s,，;；]+/).filter(Boolean))]
 if (!rows.length || rows.length > maximum || rows.some(s => s.length > 128)) throw new Error(`请输入1至${maximum}个完整SKU，每个不超过128字符`)
 return rows
}
// One-column UTF-8 warehouse export; strings preserve case and leading zeroes.
export function warehouseCsv(text) {
 const lines = String(text || '').replace(/^\uFEFF/, '').trim().split(/\r?\n/)
 if (lines.shift().trim().toLowerCase() !== 'sku_sn') throw new Error('首行必须为 sku_sn，之后每行一个SKU，不要用Excel另存为数字')
 return skuList(lines.map(line => { const s=line.trim(); if (s.startsWith('"') && s.endsWith('"')) { const value=s.slice(1,-1).replace(/""/g,'"'); if(/[\s,，;；]/.test(value)) throw new Error('SKU不能含分隔符，请保持一行一个完整编码'); return value } if (/[",\t]/.test(s)) throw new Error('请上传仅含 sku_sn 一列的完整商品清单'); return s }).join('\n'), 20000)
}
export const quantity = value => value == null ? '未知' : String(value)
export const signed = value => value == null ? '待核查' : Number(value)>0 ? '+'+value : String(value)

export const isTransferCheckDocument = row => /^DB[0-9a-f]{32}$/i.test(String(row && row.document_sn || ''))

// A completed query is a snapshot; its business blockers still determine the next step.
export function inventoryCheckNextStep(document, canAdjust) {
 const result = document.result || {}
 const rows = result.rows || []
 const pending = result.pending_documents || []
 if (pending.length) return {
  key:'pending', tone:'warning', title:'下一步：先核查关联单据，暂不能调整库存',
  message:`本次快照发现 ${pending.length} 张未完成或待入账的关联单据。请核查其最新仓库实物回传和库存入账结果，再重新检查；当前差额尚不能认定为真实盘盈盘亏。`
 }
 if ((document.adjustments || []).length) return {
  key:'adjustment', tone:'info', title:'下一步：查看已有差异调整申请',
  message:'本次快照已有独立调整申请，请查看其审核和入账结果。库存变化后如需再次核查，请创建新的检查快照。'
 }
 if (document.parent_id && rows.some(row => row.status !== 'MATCHED')) return {
  key:'fresh', tone:'info', title:'下一步：创建最新复查，再判断能否调整',
  message:'这是全仓任务保留的商品子快照。确认差异原因后，先创建该商品的最新检查，不能直接用此子快照调整库存。'
 }
 if (canAdjust) return {
  key:'adjust', tone:'warning', title:'下一步：核实差异并申请独立调整',
  message:'本次快照已具备申请条件。请先核实仓库盘点、归属虚仓、批次成本和凭证；申请须独立审核，审核时仍会校验最新单据和库存版本。'
 }
 if (rows.some(row => row.catalog_missing || !['MATCHED','PHYSICAL_DIFFERENCE','FROZEN_CHECK'].includes(row.status))) return {
  key:'evidence', tone:'warning', title:'下一步：补齐商品或仓库信息后重新检查',
  message:'当前存在数量、批次、质量或商品资料未确认的结果。请核对下方明细和仓库交互，补齐信息后复查；未知数量不能当作零或直接用于调整。'
 }
 if (rows.some(row => row.status === 'FROZEN_CHECK' || Number(row.frozen_difference || 0) !== 0)) return {
  key:'evidence', tone:'warning', title:'下一步：核对冻结与业务占用来源',
  message:'仓库冻结与OMS业务占用需要分别核查。请确认相关仓库业务，处理后重新检查，不能按实物盘盈盘亏直接调整。'
 }
 return {key:'matched',tone:'success',title:'本次快照一致，无需差异调整',message:'本次检查没有可申请的实物差异。需要核实后续库存变化时，可重新检查最新库存。'}
}
