export function carrierTerms(system) {
  const source = system === 'CHANNEL' ? '平台' : '仓库'
  return {
    source,
    list: source + '快递清单',
    item: source + '快递',
    code: source + '快递编码',
    name: source + '快递名称',
    config: system === 'CHANNEL' ? '渠道快递配置' : 'WMS 快递配置'
  }
}
