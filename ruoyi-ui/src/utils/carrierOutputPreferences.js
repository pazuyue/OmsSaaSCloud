// Summaries arrive in one request for the whole editor or batch.
export function outputPreferences(items, summaries) {
  const key = row => JSON.stringify([String(row.standard_id), row.product_code || ''])
  const counts = new Map()
  items.forEach(row => counts.set(key(row), (counts.get(key(row)) || 0) + 1))
  return items.map((row, i) => {
    const summary = summaries[i] || {}, batchCount = counts.get(key(row))
    const choices = Number(summary.other_count || 0) + batchCount
    let preferred = Number(row.is_default || 0)
    if (choices === 1) preferred = 1
    else if (!row.id) preferred = Number(summary.default_count || 0) > 0 || batchCount > 1 ? 0 : 1
    return { choices, preferred, defaultCode: summary.default_code || '' }
  })
}
