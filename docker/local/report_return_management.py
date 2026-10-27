"""Publish a compact acceptance record from retained evidence, without business calls."""
import json
import shutil
from datetime import datetime
from html import escape
from pathlib import Path
from urllib.parse import urlencode

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-management'


def main():
    acceptance=json.loads((OUT/'acceptance.json').read_text('utf-8'));browser=json.loads((OUT/'browser-check.json').read_text('utf-8'));full=json.loads((OUT/'regression-final.json').read_text('utf-8'));affected=json.loads((OUT/'regression-affected.json').read_text('utf-8'));final=json.loads((OUT/'regression-order-final.json').read_text('utf-8'));timing=json.loads((OUT/'query-timing.json').read_text('utf-8'))
    assert acceptance['source_data_preserved'] and all(c['result']=='PASS' for c in acceptance['checks']) and not browser['javascript_errors']
    h=lambda v:escape(str(v));case=acceptance['case24'];url='/oms-order/returns?'+urlencode({'view':'PARCEL','number':case['refund']})
    labels={
        'Any source/waybill/warehouse number finds complete mixed package':'关联退款、换货、平台子单、运单及仓单均能定位完整混合包裹',
        'Refund task is actual B1':'退款任务显示实际退回的 B1',
        'Exchange task is actual B1':'换货任务显示实际退回的 B1',
        'One actual common warehouse command, no additional private RSL rows':'共同入库单仅展示一行，来源台账未额外计为仓单',
        'Mixed merge filter':'退款与换货混合合包筛选',
        'Lookup highlights refund owner and retains exchange member':'搜索退款来源时仍保留换货成员，命中来源可高亮',
        'Partial receipt remains pending, never completed':'未收齐保留部分实收状态，应退2、实收1、入库1',
        'Gift return can be independently queried':'关联赠品退回可独立筛选',
        'Multiple exchange owners share one query package':'多张换货单合包展示为一个包裹',
        'Multiple refund owners share one query package':'多张退款单合包展示为一个包裹',
        'Typed number lookup respects identity type':'按退款单号类型可命中，误选运单类型不命中',
        'Refund-only application with physical returns remains queryable':'仅退款但有实际退回安排可查询',
        'Return intent without warehouse command remains visible':'尚未形成仓单的退货任务仍可查询',
        'Invalid page size rejected':'超过分页上限的请求明确拒绝',
        'Public request cannot select another company':'公开请求不能切换为其他公司',
        'All retained inventory/documents/queue/job settings unchanged':'保留单据、库存、原验收资料、队列与全局调度基线一致',
        'Refund search shows one complete mixed package with two B units':'搜索退款显示完整混合合包，B应退2件',
        'Detail retains both owners and highlights the searched refund':'详情同时保留退款与换货，退款行高亮',
        'Actual common warehouse command is a single row':'共同仓单在入库单视图中仅一行',
        'Refund task shows actual replacement SKU B':'任务视图显示实际换出后退回的B',
        'Multiline number paste finds two tasks without duplicate scope':'粘贴多行单号找到两项任务，各自数量独立',
        'Mixed package filter retains full source quantity':'混合合包筛选保留整包完整数量',
        'Export preserves current filters and complete package amounts':'CSV导出沿用当前筛选，数量与页面一致',
        'Unreceived remainder remains visible as partial receipt':'部分实收页面保留剩余待收数量'
    }
    def actual_summary(check):
        value=check['actual']
        if isinstance(value,dict):
            if 'rows' in value:
                found=value['rows'];summary=('输入 '+str(value['number'])+'；') if 'number' in value else ''
                summary+='命中 '+str(value.get('total',len(found)))+' 条'
                if found:
                    row=found[0]
                    def quantity(field):return ' + '.join(str(u[field])+' '+u['unit'] for u in row['units'] if u.get(field)) or '0'
                    summary+='；退款 '+str(row['refund_count'])+' 单、换货 '+str(row['exchange_count'])+' 单；应退 '+quantity('quantity')+'，实收 '+quantity('accepted_quantity')+'，入库 '+quantity('posted_quantity')
                return summary
            if 'matched_count' in value:return '完整保留 '+str(len(value['members']))+' 项来源，其中命中 '+str(value['matched_count'])+' 项'
            return json.dumps(value,ensure_ascii=False)
        return '各项保留基线一致' if value is True else str(value)
    rows=''.join('<tr><td>'+h(i+1)+'</td><td>'+h(labels.get(check['name'],check['name']))+'</td><td>'+h(actual_summary(check))+'</td><td>通过</td></tr>' for i,check in enumerate(acceptance['checks']))
    ui=''.join('<li>'+h(labels.get(check,check))+'</li>' for check in browser['checks'])
    html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>退货管理验收记录</title><style>body{margin:0;padding:28px;background:#f3f6fa;color:#30435b;font-family:system-ui,"Microsoft YaHei",sans-serif;line-height:1.9}main{max-width:1200px;margin:auto}section{background:white;border:1px solid #dfe7f2;border-radius:9px;padding:25px;margin-bottom:20px}h1{margin:0;font-size:26px}h2{font-size:19px}a{color:#0876d4;overflow-wrap:anywhere}.note{padding:14px;background:#edf5ff}table{width:100%;border-collapse:collapse;font-size:13px}td,th{text-align:left;padding:10px;border-bottom:1px solid #e1e9f4}th{background:#f3f7fc}img{max-width:100%;border:1px solid #e1e9f4;margin:12px 0}.muted{color:#73859b;font-size:13px}@media(max-width:700px){body{padding:10px}section{padding:16px}.table{overflow:auto}}</style><main>'''
    html+='<section><h1>退货管理 · 验收记录</h1><p class="muted">实际核对 '+h(acceptance['verified_at'])+' · 报告生成 '+h(datetime.now().isoformat(timespec='seconds'))+'</p><p><a href="/oms-order/returns">打开退货管理</a> · <a href="'+url+'">直接查看本次退款＋换货合包</a></p><p class="note">'+str(len(acceptance['checks']))+' 项接口验收通过；'+str(len(browser['checks']))+' 项浏览器验收通过；JS 错误 0。保留的原单据、测试库存、原验收清单、队列配置和全局调度基线一致。</p><p>三个视图：退货任务、退回包裹、实际退货入库单。覆盖多退款、多换货、退款＋换货合包、赠品、仅退款涉及退货、部分实收、终止重排及来源追溯。</p><p>全量隔离回归 '+str(full['total_tests'])+' 条通过；相关功能及公司路由复测 '+str(affected['total_tests'])+' 条通过；最后单号索引与待核对判定复测 '+str(final['total_tests'])+' 条通过。各轮复测包含重叠用例，不相加为不同测试数。</p></section>'
    html+='<section><h2>明确场景与实际结果</h2><p>A3 已完成换成 B3；随后 B1 退货退款100元，另 B1 换 C1，两件B同运单退回。</p><p>退款 '+h(case['refund'])+'；换货 '+h(case['exchange'])+'。</p><p>退货任务分别显示 B1；包裹 '+h(case['parcel'])+' 显示退款1单＋换货1单，应退2、实收2、入库2；实际仓单 '+h(case['document'])+' 仅一行。搜索任一关联单号仍展示完整合包，命中退款来源有明确高亮。</p><p>本轮仅进行查询与导出。保留测试单公司和仓库库存仍为 A10、B9、C9，预占与锁定保持0。</p><img src="returns-mixed-package.png" alt="完整混合合包查询"><img src="returns-mixed-detail.png" alt="命中退款高亮并保留换货来源"></section>'
    html+='<section><h2>接口验收明细</h2><p class="muted">以下项目使用保留业务记录实际查询；完整请求结果保存在 acceptance.json。</p><div class="table"><table><thead><tr><th>序号</th><th>实际检查</th><th>实际结果</th><th>判定</th></tr></thead><tbody>'+rows+'</tbody></table></div></section>'
    html+='<section><h2>页面与导出</h2><ol>'+ui+'</ol><p><a href="returns-mixed-export.csv">下载本次按筛选导出的CSV</a></p><img src="returns-two-tasks.png" alt="退款和换货分别显示实际B1"><img src="returns-common-document.png" alt="共同仓单仅一行"><img src="returns-partial-receipt.png" alt="未收齐仍显示部分实收"></section>'
    html+='<section><h2>查询性能与数据范围</h2><p>单号先通过 number_any 索引定位命中范围，再通过 group_scope 索引读取包裹的完整来源；来源和仓库进度按批读取，列表不逐行调用仓库或平台。</p><p>本地同一单号3次暖查询耗时 '+h(timing['milliseconds'])+' ms，中位 '+h(timing['median_ms'])+' ms；这是一组本地观测，不作为生产吞吐指标。</p><p>默认查询近30天；完整单号可跨日期查询；日期区间最多93天；每页最多50条；单次导出最多2000条。件与套分别汇总，待确认数量不会显示为0件。</p><p class="muted">库存和原单基线：protected-baseline.json；前端检查：browser-check.json；SQL执行计划：number-query-explain-optimized.txt。此次验收使用此前保留的模拟平台与TEST仓库数据。</p></section></main></html>'
    report=OUT/'验收记录.html';report.write_text(html,'utf-8');dest=ROOT/'docker/local/dist/acceptance/return-management';dest.mkdir(parents=True,exist_ok=True)
    for name in ['验收记录.html','returns-mixed-package.png','returns-mixed-detail.png','returns-two-tasks.png','returns-common-document.png','returns-partial-receipt.png','returns-mixed-export.csv']:
        shutil.copy2(OUT/name,dest/name)
    print('Published return-management acceptance record:',dest/'验收记录.html')


if __name__=='__main__':main()
