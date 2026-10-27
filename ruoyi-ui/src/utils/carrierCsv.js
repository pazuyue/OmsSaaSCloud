// RFC 4180 CSV, parsed as strings to preserve exact external codes (001, sf, SF, etc.).
export function parseCarrierCsv(text) {
  const rows = []; let row = []; let cell = ''; let quoted = false
  text = text.replace(/^\uFEFF/, '')
  for (let i = 0; i < text.length; i++) { const ch = text[i]; if (ch === '"') { if (quoted && text[i + 1] === '"') { cell += '"'; i++ } else if (!cell || quoted) quoted = !quoted; else throw new Error('CSV 引号位置不正确') } else if (ch === ',' && !quoted) { row.push(cell); cell = '' } else if ((ch === '\n' || ch === '\r') && !quoted) { if (ch === '\r' && text[i + 1] === '\n') i++; row.push(cell); if (row.some(v => v !== '')) rows.push(row); row = []; cell = '' } else cell += ch }
  if (quoted) throw new Error('CSV 引号未闭合')
  row.push(cell); if (row.some(v => v !== '')) rows.push(row)
  if (!rows.length) throw new Error('文件为空')
  const headers = rows.shift(); if (headers.join(',') !== 'external_code,external_name,enabled,disabled_reason') throw new Error('请使用模板表头：external_code,external_name,enabled,disabled_reason')
  if (rows.length > 500 || !rows.length) throw new Error('单次导入需要 1 至 500 行')
  return rows.map((values, i) => { if (values.length !== 4) throw new Error(`第 ${i + 2} 行列数不正确`); if (!['0', '1'].includes(values[2])) throw new Error(`第 ${i + 2} 行启用状态须为 0 或 1`); return { external_code: values[0], external_name: values[1], enabled: Number(values[2]), disabled_reason: values[3] } })
}
