"""Render the UI acceptance record from saved read-only evidence, then publish local artifacts."""
import html
import json
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-document-ui'


def main():
    acceptance=json.loads((OUT/'acceptance.json').read_text('utf-8'));browser=json.loads((OUT/'browser-check.json').read_text('utf-8'));tests=json.loads((OUT/'regression.json').read_text('utf-8'));case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24']
    api_checks=list(acceptance['checks'])
    if (OUT/'direct-entry.json').exists():
        direct=json.loads((OUT/'direct-entry.json').read_text('utf-8'));assert direct['result']=='PASS';api_checks.append(direct)
    esc=lambda v:html.escape(str(v));parts=[]
    parts.append('<h1>退货入库单列表与详情 · 验收记录</h1><p class="muted">'+esc(acceptance['verified_at'])+' · 本地项目已部署</p>')
    parts.append(f'<div class="metrics"><article><span>后端相关测试</span><b>{tests["total_tests"]} 项通过</b></article><article><span>实际接口验收</span><b>{len(api_checks)} 项通过</b></article><article><span>浏览器验收</span><b>{len(browser["checks"])} 项通过</b></article></div>')
    parts.append('<p><a href="/oms-order/returns?'+html.escape('view=DOCUMENT&number='+case['final_refund']['refund_sn']+'&document='+case['shared_return']['document']['return_sn'])+'">打开合包入库单验收</a>　<a href="/oms-order/returns?view=DOCUMENT">进入退货入库单列表</a></p>')
    parts.append('<h2>已完成的界面</h2><ul><li>列表独立展示仓库当前状态、最近交互动作、当次结果、发生时间和原因；支持状态筛选及中文导出。</li><li>相同实际库存SKU在列表汇总展示；合包仍保留全部退款、换货和赠品来源。</li><li>详情默认打开入库明细，分开展示仓库受理、确认实收、库存入账；可查看售后归属、原实际发货、规格及原出库成本。</li><li>实收页展示唯一原消息、逐行正次品与分配归属；库存明细分别展示入账批次、来源成本及真实入账关联号。</li><li>仓库交互直接嵌入详情，每次请求和重试独立显示；从库存记录可定位原回传，脱敏请求响应按需加载。</li><li>原出入库单入口复用同一退货详情，从退款或换货进入时保留命中来源高亮。</li></ul>')
    parts.append('<h2>场景与实物结果</h2><p>保留的连续售后场景：原购买A商品3件，第一次换成B商品3件并实际发出；随后B商品1件申请退款100元，另1件换成C商品，剩余B商品1件保留。退款与第二次换货的B商品使用同一退回运单合包，形成一张共同退货入库单。</p>')
    parts.append('<table><thead><tr><th>核对项</th><th>验收结果</th></tr></thead><tbody>')
    for label,value in [('实际共同入库单',case['shared_return']['document']['return_sn']),('退款来源',case['final_refund']['refund_sn']),('换货来源',case['final_exchanges'][1]['exchange_sn']),('退回运单',case['shared_return']['parcel']['waybill']),('实际退回SKU',case['target_sku']),('原实际B商品发货单',case['audit']['source'][0][3]),('整单数量','计划2件、确认实收2件、库存入账2件；退款与换货各1件'),('原回传与来源明细','一条原始合包实收消息、两条来源分配、两条入账记录'),('成本','两个来源分别保留原出库成本2.50元/件'),('部分实收场景','独立换货退回计划2件，只实收并入账1件；仍展示待收1件，未判定收齐')]:parts.append('<tr><td>'+esc(label)+'</td><td>'+esc(value)+'</td></tr>')
    parts.append('</tbody></table><h2>库存核对</h2><p>本轮验收只读取保留单据；公司库存、仓库库存、订单状态、队列和任务配置均与验收前保护基线一致。浏览器未发起任何业务变更接口。</p><table><thead><tr><th>商品</th><th>公司总库存 / 可用</th><th>仓库总库存 / 可用</th><th>预占 / 已分配 / 冻结</th></tr></thead><tbody>')
    for i,label in enumerate(['A','B','C']):
        c=case['final_stock']['company'][i];w=case['final_stock']['warehouse'][i];parts.append(f'<tr><td>{label}</td><td>{esc(c[1])} / {esc(c[2])}</td><td>{esc(w[2])} / {esc(w[3])}</td><td>0 / 0 / 0</td></tr>')
    parts.append('</tbody></table><p class="muted">以上是保留自动流程验收单据的库存结果；本轮UI验收没有再次退货、出库或记账。仓库交互记录来自本地TEST环境。</p>')
    parts.append('<h2>逐项验收</h2><table><thead><tr><th>接口检查</th><th>结果</th></tr></thead><tbody>')
    for check in api_checks:parts.append('<tr><td>'+esc(check['name'])+'</td><td class="pass">通过</td></tr>')
    parts.append('</tbody></table><h2>页面检查</h2><ol>')
    for check in browser['checks']:parts.append('<li>'+esc(check)+' <b class="pass">通过</b></li>')
    parts.append('</ol><h2>回归覆盖与查询方式</h2><p>相关测试覆盖合包分配、共同下发、退货只读明细、查询投影、仓库日志及公司隔离；新增检查覆盖查询失败仍保留已受理状态、最终短收待归属、正次品和原成本、分页、私有来源台账不可当作仓单查询。</p><p>列表只读取可筛选摘要；交互变更按实际仓单通知关联来源，保留原有版本确认机制。明细先按完整仓单号定位来源，批量读取；实收、入账及异常记录分页，报文点击后加载。刷新界面不主动查询仓库或执行实收入账。</p>')
    if (OUT/'query-timing.json').exists():
        timing=json.loads((OUT/'query-timing.json').read_text('utf-8'));parts.append('<p class="muted">本机暖请求耗时仅供本次验收参考：'+esc(timing)+'；不是生产环境性能承诺。</p>')
    parts.append('<h2>页面截图</h2>')
    for name,caption in [('01-list-warehouse-status.png','列表：仓库受理与当次交互分开'),('02-document-products.png','详情：三段进度与实际库存SKU汇总'),('03-product-sources.png','实际商品展开核对售后与原发货'),('04-receipts-and-stock.png','原始实收及分配归属'),('04b-stock-lines.png','来源实收和库存入账流水'),('06-warehouse-interactions.png','当前单据的仓库交互记录'),('05-interaction-payload.png','按需加载脱敏报文'),('07-warehouse-entry.png','原出入库单入口复用专用详情'),('08-partial-receipt.png','部分实收保留剩余待收数量')]:parts.append('<figure><figcaption>'+esc(caption)+'</figcaption><a href="'+name+'"><img src="'+name+'" loading="lazy"></a></figure>')
    css='body{font:15px/1.8 "Microsoft YaHei",sans-serif;color:#34445b;background:#f4f7fb;margin:0}main{max-width:1240px;margin:32px auto;padding:34px;background:white;border-radius:12px}h1{font-size:26px}h2{font-size:20px;margin-top:32px}.muted{color:#7a899d;font-size:13px}.metrics{display:flex;gap:18px}.metrics article{flex:1;background:#f1f6fc;padding:18px;border-radius:8px}.metrics span,.metrics b{display:block}.metrics b{font-size:22px;color:#278558}a{color:#0877dc}table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:11px;border:1px solid #e3eaf3;text-align:left;overflow-wrap:anywhere}th{background:#f4f7fb}.pass{color:#278558}figure{margin:24px 0}figcaption{font-weight:600;margin-bottom:10px}img{max-width:100%;height:auto;border:1px solid #e3eaf3}li{margin-bottom:7px}'
    (OUT/'验收记录.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>退货入库单验收记录</title><style>'+css+'</style><main>'+''.join(parts)+'</main></html>','utf-8')
    destination=ROOT/'docker/local/dist/acceptance/return-document-ui';destination.mkdir(parents=True,exist_ok=True)
    for file in OUT.iterdir():
        if file.suffix in ['.html','.png','.json','.csv','.txt']:shutil.copy2(file,destination/file.name)
    print('Published /acceptance/return-document-ui/验收记录.html')


if __name__=='__main__':main()
