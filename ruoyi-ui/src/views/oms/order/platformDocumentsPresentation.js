export const documentKinds = { ORDER: '平台订单', REFUND: '平台退款', EXCHANGE: '平台换货' }
export const legacyDocumentKinds = { '/oms-order/platform': 'ORDER', '/oms-order/platformRefunds': 'REFUND', '/oms-order/platformExchanges': 'EXCHANGE' }
export function documentLocation(type, query = {}) {
  const values = { ...query, type }
  if (!values.platformTid && values.tid) values.platformTid = values.tid
  if (!values.platformOid && values.oid) values.platformOid = values.oid
  if (!values.modifiedFrom && values.from) values.modifiedFrom = values.from
  if (!values.modifiedTo && values.to) values.modifiedTo = values.to
  for (const key of ['view', 'kind', 'tid', 'oid', 'from', 'to']) delete values[key]
  for (const key of Object.keys(values)) if (values[key] == null || values[key] === '') delete values[key]
  return { path: '/oms-order/platformDocuments', query: values }
}
export function sharedDocumentFilters(q = {}) {
  const get = key => typeof q[key] === 'string' ? q[key] : ''
  return { channelId: get('channelId'), platformTid: get('platformTid'), platformOid: get('platformOid'), modifiedFrom: get('modifiedFrom'), modifiedTo: get('modifiedTo') }
}
