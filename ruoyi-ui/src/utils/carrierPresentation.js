export function carrierInfo(row) {
  const value = row && (row.carrier_resolution_json || row.carrier_snapshot || row.carrier_conversion)
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(value) || {} } catch (_) { return {} }
}
