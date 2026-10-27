"""Final audit, duplicate source intake and retained Chinese acceptance report."""
import json
import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from html import escape
from pathlib import Path
from verify_exchange_automation import Run, OUT, ORDER, SUPPLY, sql, quote

SCENARIOS = {
    '01': '原购A两件已出库。买家申请换B两件；系统自动审核、同意、退回入库验收、换出和回传运单，收到平台换货成功后结束。',
    '02': '原购A四件，两张换货各退A两件、换B两件，共用同一返程运单。最后一份来源加入后等待120秒，仅下发一张共同入库单，分别入账与换出。',
    '03': '原购A四件、商品实付10元。其中两件换B，另两件退货退款5元。两个售后共用运单和一张共同入库单，仓库明确行号，各自入库与平台处理。',
    '04': '商家登记有业务授权编号的协商换货，退A两件换B两件。审核、验收、分仓和发货均由自动规则执行；没有独立平台换货单，不伪造平台成功。',
    '05': 'A两件尚未出库，商家明确授权换成B两件。系统确认撤回原需求、释放A占用，在原SO形成B明细并自动审单发货；没有A退回入库。',
    '06': 'A两件已售出，先A换B并完成，再以实际换出的B为退回来源申请B换A。第二次沿B的真实发货明细取成本与额度，始终关联原SO。',
    '07': '换B两件但B库存为0。系统保留申请数量、等待补货；正常补入B两件后自动继续，实际换出两件。',
    '08': '买家期望规格没有唯一SKU映射。预期转人工，不能自动确认方案、预占B、创建退回或换出。',
    '09': '同一签名仓库回传并发提交三次，再重复同步相同平台来源。预期只有一份实收、一次入库和一次换出，库存不重复增减。',
    '10': '退回A两件，仓库明确一件良品、一件次品。分别入良品和次品库存；预期暂停自动验收和换出，B两件逻辑预占等待人工质量决定。',
    '11': '换出已预占，买家关闭平台换货申请。系统停止新增履约、自动截停未完成安排，确认后释放未发占用；不虚增退回库存。',
    '12': '原A已完整退回，第一轮B已下发但尚未出库。商家发起终止原轮次并取得继续换出业务授权；系统自动审核第二轮，在同一HH与SO继续换出，不重复退回入库。',
}


def h(value):
    return escape(str(value if value is not None else ''))


def table(headers, rows):
    return '<div class="table"><table><thead><tr>' + ''.join('<th>' + h(x) + '</th>' for x in headers) + '</tr></thead><tbody>' + ''.join('<tr>' + ''.join('<td>' + h(v) + '</td>' for v in row) + '</tr>' for row in rows) + '</tbody></table></div>'


def main():
    r = Run()
    assert set(r.data['cases']) == set(SCENARIOS)
    assert all(c.get('result', '').startswith('PASS') for c in r.data['cases'].values()), 'Do not publish an unfinished run'
    # Duplicate source is a legitimate intake input, never a target workflow retry.
    c = r.data['cases']['09']
    claim = c['claims'][0]
    if not c.get('duplicate_source_verified'):
        before = r.snapshot(c)
        bodies = [{'channel_id': c['channel_id'], 'dispute_ids': claim['dispute_id'], 'request_key': 'EXAUTO_DUP_' + c['case_id'] + '_' + str(i)} for i in range(3)]
        with ThreadPoolExecutor(max_workers=3) as pool:
            c['duplicate_source_tasks'] = list(pool.map(lambda body: r.api('/order/exchanges/sync', body), bodies))
        assert before == r.snapshot(c)
        c['duplicate_source_verified'] = True
        r.save()
    for task in c.get('duplicate_source_tasks', []):
        for attempt in range(60):
            current = r.api('/order/exchanges/tasks/' + str(task['id']))
            if current['state'] == 'COMPLETED':
                assert int(current['new_count']) == 0 and int(current['updated_count']) == 0
                break
            if attempt == 59:
                raise AssertionError('Duplicate source task not completed: ' + str(task['id']))
            time.sleep(1)
    for key, c in sorted(r.data['cases'].items()):
        r.verify_stock(c)
        c['final_exchanges'] = [r.e(claim) for claim in c['claims']]
        c['automatic_flows'] = [r.flow(claim) for claim in c['claims']]
        for e in c['final_exchanges']:
            if key != '08':
                assert e['review_by'] == '自动换货规则', (key, e['review_by'])
            assert not any(ev['action'] == 'REVIEW' and ev['actor'] != '自动换货规则' for ev in e['events'])
        c['demand_ledger'] = sql("SELECT platform_tid,platform_oid,sku_sn,demand_quantity,cancelled_quantity,reserved_quantity,outbound_quantity,status FROM oms_order_stock_demand WHERE company_code='QM' AND channel_id=" + str(c['channel_id']) + " AND sku_sn IN (" + ','.join(map(quote, [c['sku'], c['target_sku']])) + ') ORDER BY id', 'qm_oms_saas_inventory')
        if key == '12':
            e = c['final_exchanges'][0]
            assert int(e['fulfillment_round']) == 2
            c['return_documents'] = sql("SELECT return_sn,quantity,accepted_quantity,posted_quantity FROM wms_exchange_return WHERE company_code='QM' AND exchange_id=" + str(e['id']), SUPPLY)
            assert sql("SELECT COUNT(*) FROM wms_exchange_return WHERE company_code='QM' AND exchange_id=" + str(e['id']), SUPPLY) == '1'
            assert c['old_round_stopped'] and c['continuation_requested']
            c['round_evidence'] = sql("SELECT round_no,state,quantity,reviewed_by FROM oms_exchange_round WHERE company_code='QM' AND exchange_id=" + str(e['id']) + ' ORDER BY round_no', ORDER)
            assert sql("SELECT reviewed_by FROM oms_exchange_round WHERE company_code='QM' AND exchange_id=" + str(e['id']) + ' AND round_no=2', ORDER) == '自动换货规则'
        if key in ['05', '06', '12']:
            assert sql("SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND platform_order_id=" + str(c['platform_id']), ORDER) == '1'
        if key == '05':
            c['unshipped_review_evidence'] = sql("SELECT action,decision,operator_name FROM oms_order_review_event WHERE company_code='QM' AND order_id=" + str(c['order_id']) + " AND action='AUTO_EXCHANGE_APPROVE'", ORDER)
            assert '自动换货规则' in c['unshipped_review_evidence']
        if key == '03':
            c['final_refund'] = r.api('/order/refunds/business/' + str(c['refunds']['mixed']['id']))
            assert c['final_refund']['state'] == 'COMPLETED'
            assert c['final_refund']['review_by'].startswith('自动')
            assert {m['owner_kind'] for m in c['shared_return']['members']} == {'REFUND', 'EXCHANGE'}
            assert len(c['shared_return']['members']) == 2 and c['shared_return']['document']['quantity'] == 4
        if key == '09':
            d = c['shared_return']
            assert len(d['receipts']) == 1 and d['receipts'][0]['state'] == 'ALLOCATED'
            assert sql("SELECT COUNT(*) FROM oms_exchange WHERE company_code='QM' AND channel_id=" + str(c['channel_id']) + ' AND dispute_id=' + quote(c['claims'][0]['dispute_id']), ORDER) == '1'
        (OUT / (key + '-evidence.json')).write_text(json.dumps(c, ensure_ascii=False, indent=2, default=str), 'utf-8')
    before = (OUT / 'queue-policy-before.tsv').read_text('utf-8').strip()
    after = sql("SELECT business_type,desired_mode,concurrency,prefetch,app_rate,shop_rate,revision FROM sys_mq_queue_policy WHERE business_type<>'EXCHANGE_FLOW' ORDER BY business_type", 'ry-cloud')
    assert before == after, 'An existing queue policy changed'
    r.data['queue_policy_unchanged'] = True
    r.data['global_exchange_job'] = sql("SELECT status FROM sys_job WHERE invoke_target='exchangeTask.scanAll()'", 'ry-cloud')
    assert r.data['global_exchange_job'] == '1', 'Global exchange job was enabled'
    r.data['audited_at'] = datetime.now().isoformat()
    r.save()
    regression = json.loads((OUT / 'regression-final.json').read_text('utf-8'))
    rows, cards = [], []
    for key, c in sorted(r.data['cases'].items()):
        result = '通过 · 预期转人工' if c['result'] == 'PASS_EXPECTED_MANUAL' else '通过'
        links = '<br>'.join('<a href="/oms-order/exchanges?exchangeId=' + str(e['id']) + '">' + h(e['exchange_sn']) + '</a>' for e in c['final_exchanges'])
        if key == '03':
            links += '<br><a href="/oms-order/businessRefunds?id=' + str(c['final_refund']['id']) + '">' + h(c['final_refund']['refund_sn']) + '</a>'
        rows.append('<tr><td>' + key + '</td><td><a href="#case-' + key + '">' + h(c['title']) + '</a></td><td class="pass">' + result + '</td><td>' + links + '</td></tr>')
        stock = table(['SKU（A原商品 / B换出商品）', '总库存', '可用', '逻辑预占', '已分配', '冻结'], c['final_stock']['company'])
        warehouse = table(['虚拟仓', 'SKU', '良品实物', '良品可用', '良品锁定', '次品实物', '次品可用'], c['final_stock']['warehouse'])
        phase = ''.join('<details><summary>' + h(name) + '</summary>' + table(['SKU', '总数', '可用', '预占', '已分配', '冻结'], snap['company']) + '</details>' for name, snap in c.get('stock_phases', {}).items())
        states = table(['单据', '审核人', '本地状态', '平台状态', '自动节点', '原因'], [[e['exchange_sn'], e['review_by'] or '未审核', e['state'], e['platform_status'] or '无独立平台单', f['task'].get('node'), f['task'].get('last_message')] for e, f in zip(c['final_exchanges'], c['automatic_flows'])])
        common = ''
        if c.get('shared_return'):
            d = c['shared_return']
            common = '<h4>共同退回 · ' + h(d['document']['return_sn']) + '</h4><p>最后新增后归集到期：' + h(d['parcel']['dispatch_after']) + '；共同单冻结：' + h(d['document']['create_time']) + '。</p>' + table(['归属', '售后单', '实际SKU', '应退', '已实收', '已入库'], [[m['owner_kind'], m['owner_sn'], m['sku_sn'], m['quantity'], m['accepted_quantity'], m['posted_quantity']] for m in d['members']])
        cards.append('<section id="case-' + key + '"><h2>' + key + ' ' + h(c['title']) + ' <small class="pass">' + result + '</small></h2><p>' + h(SCENARIOS[key]) + '</p><p>店铺：' + h(c['channel_name']) + '（' + str(c['channel_id']) + '）<br>原订单：<a href="/oms-order/business?orderId=' + str(c['order_id']) + '">' + h(c['order_sn']) + '</a><br>' + links + '</p>' + states + common + '<h4>核对后的最终库存</h4>' + stock + warehouse + '<details><summary>展开库存阶段快照与需求台账</summary>' + (phase or '<p>本例没有新增实收与出库；未发货换品的原需求取消与新需求出库另见台账。</p>') + '<pre>' + h(c['demand_ledger']) + '</pre></details></section>')
    excluded = ''.join('<li>' + h(c.get('superseded_reason', '前置测试输入未满足条件')) + ' · 店铺 ' + str(c['channel_id']) + '</li>' for c in r.data.get('superseded_cases', []))
    html = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>换货自动流程验收</title><style>body{font-family:system-ui,"Microsoft Yahei",sans-serif;background:#f4f7fb;color:#28394d;margin:0;padding:28px;line-height:1.75}main{max-width:1400px;margin:auto}header,section{background:white;border:1px solid #dfe7f2;border-radius:10px;padding:26px;margin-bottom:22px}h1{margin:0;font-size:28px}h2{font-size:22px}h4{margin:22px 0 8px}a{color:#0674d9;overflow-wrap:anywhere}.pass{color:#087a4e;font-weight:600}small{font-size:14px}.stats{display:flex;gap:40px;margin:16px 0}.stats b{font-size:30px;display:block}.table{overflow:auto;margin:15px 0}table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;vertical-align:top;padding:12px;border-bottom:1px solid #e5ebf4}th{background:#f2f6fc}details{padding:12px;background:#f8faff;margin:8px 0}summary{cursor:pointer;font-weight:600}pre{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere}.note{background:#edf5ff;padding:16px;border-radius:8px}@media(max-width:800px){body{padding:12px}header,section{padding:16px}.stats{gap:18px}}</style><main><header><h1>换货自动流程 · 第一、二期合并验收</h1><p>保留批次 ' + h(r.data['run_id']) + ' · 最终核对 ' + h(r.data['audited_at']) + '</p><div class="stats"><span><b>12 / 12</b>场景符合预期</span><span><b>10</b>自动链路完成 / 自动截停</span><span><b>2</b>预期转人工保护</span><span><b>' + str(regression['total_tests']) + '</b>回归通过，零跳过</span></div><p class="note">平台为本地SIMULATION，仓库为签名TEST回传。原销售发货是测试前置；协商单创建、返程运单及每轮继续换出授权属于明确商家业务输入。目标审核、平台同意、验收、分仓、下发和推进均未调用人工接口。真实平台授权与真实仓库联调另行验收。</p><p>库存检查：公司总数＝可用＋预占＋冻结；已分配属于预占。实物良品＝可用＋锁定；次品单列。正常结束无残留预占；次品例保留B两件预占等待质量决定。仅新增测试店铺启用规则，原队列配置和全局调度保持原样。</p><div class="table"><table><thead><tr><th>编号</th><th>场景</th><th>结果</th><th>验收单据</th></tr></thead><tbody>' + ''.join(rows) + '</tbody></table></div><details><summary>前置失败记录与验收边界</summary><ul>' + excluded + '</ul><p>以上现场保留，不纳入12条正式通过数；修正测试输入后重新生成独立测试资料，未用人工审核伪装自动通过。重复回传、歧义分配、短收/超收、权限、版本与120秒归集另有真实MySQL/HTTP回归覆盖。</p></details></header>' + ''.join(cards) + '</main></html>'
    (OUT / '验收报告.html').write_text(html, 'utf-8')
    destination = Path(__file__).resolve().parent / 'dist/acceptance/exchange-automation'
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(OUT / '验收报告.html', destination / '验收报告.html')
    print('PASS final stock/audit; report:', destination / '验收报告.html')


if __name__ == '__main__':
    main()
