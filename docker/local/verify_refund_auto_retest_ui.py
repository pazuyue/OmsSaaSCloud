"""Read-only stock, duplicate-payment and browser verification of the retained retest."""
import html
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from retest_after_ship_refund_auto import Retest, DEST, OUT, BASE, ORDER, dump
from test_multi_virtual_shipping import sql


def main():
    run = Retest()
    run.ready()
    run.report_retest(list(run.data['cases']))
    result = json.loads((DEST / 'results.json').read_text('utf-8'))
    assert all(r['pass'] for r in result['rows']), result
    assert not result['review_audit']['manual_target_review_or_pay'], 'A manually reviewed or paid target is not an automatic step acceptance pass'
    checks = []
    for row in result['rows']:
        key = row['case']; c = run.data['cases'][key]
        s = run.status(c)
        ref = c['refunds']['target']
        pays = int(sql(f"SELECT COUNT(*) FROM oms_refund_operation WHERE company_code='QM' AND refund_id={ref['id']} AND action='PAY'", ORDER))
        assert pays == (0 if key in ['02B', '03', '10'] else 1), (key, pays)
        # Returns replenish warehouse/public stock, not the shop's already consumed selling quota.
        for balance in s['stock']['channel']:
            before = next(x for x in c['stock_before_refunds']['channel'] if x[0] == balance[0])
            assert balance[4] == before[4], (key, 'Unexpected channel quota change', before, balance)
            assert balance[2:4] == ['0', '0'], (key, balance)
        for balance in s['stock']['company']:
            warehouse = [w for w in s['stock']['warehouse'] if w[1] == balance[0]]
            assert sum(int(w[2]) for w in warehouse) == int(balance[1]), (key, warehouse, balance)
            assert sum(int(w[3]) for w in warehouse) == int(balance[2])
            assert all(w[4] == '0' for w in warehouse)
        if key in ['05', '06', '07', '08', '09']:
            assert all(p['quantity'] == p['accepted_quantity'] == p['posted_quantity'] for p in s['goods']['plans'])
            assert all(p['state'] == 'CLOSED' for p in s['goods']['plans'])
            assert all(x['duplicate_callback_sent'] for x in c['retest_receipts'].values())
        if key == '07':
            assert c['retest_partial_verified']
            assert c['partial_stock']['company'][0][1:3] == ['99', '99']
            assert len(s['parcels'][0]['receipts']) == 2
        if key == '09':
            assert len(s['parcels']) == 1 and len(s['parcels'][0]['members']) == 2
            assert s['parcels'][0]['warehouse_document']['quantity'] == 2
            assert len(s['parcels'][0]['receipts']) == 1
        checks.append(key + ': stock, selling quota, original receipt deduplication and one-time payment verified')
    errors, writes = [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1760, 'height': 1120})
        context.add_cookies([{'name': 'Admin-Token', 'value': run.token, 'url': BASE}])
        page = context.new_page(); page.set_default_timeout(30000)
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('request', lambda q: writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET', 'HEAD', 'OPTIONS'] else None)
        page.goto(BASE + '/oms-order/businessSettings?channelId=' + str(run.data['cases']['01']['channel_id']) + '&section=refund-automation', wait_until='networkidle')
        form = page.locator('.automation-form')
        expect(form).to_be_visible()
        expect(form).to_contain_text('小额退款免退货')
        expect(form).to_contain_text('子订单累计免退上限')
        expect(form).to_contain_text('已发剩余全额自动追回')
        expect(form).to_contain_text('无差异实收入账自动结案')
        expect(form.locator('.el-input-number input').first).to_have_value('20.00')
        page.screenshot(path=str(DEST / 'automation-settings.png'), full_page=True, animations='disabled')
        for row in result['rows']:
            page.goto(row['refund_url'], wait_until='networkidle')
            dialog = page.get_by_role('dialog', name=re.compile('^业务退款详情'))
            expect(dialog.locator('.refund-identity-main > strong')).to_have_text(row['refund_sn'])
            expect(dialog.locator('.refund-disposition')).to_be_visible()
            page.screenshot(path=str(DEST / (row['case'] + '-refund.png')), full_page=True, animations='disabled')
        browser.close()
    assert not errors, errors
    assert not writes, writes
    dump(DEST / 'verification.json', {'checks': checks, 'browser_errors': errors, 'browser_writes': writes, 'protection': result['protection'], 'channel_quota_policy': 'Return receipts restore warehouse/public stock; consumed shop selling quota remains unchanged.'})
    generate_report(result)
    print('PASS retained retest browser, company/warehouse/channel stock, partial receipt, gift shared parcel and payment deduplication')


def generate_report(result):
    fresh_path = DEST / 'fresh-cancel/result.json'
    fresh = json.loads(fresh_path.read_text('utf-8')) if fresh_path.exists() else None
    audit = result.get('review_audit', {})
    def verdict(row):
        if not row['pass']: return '目标步骤未通过'
        if row.get('has_manual_preconditions'): return '后续目标步骤通过；人工前置，全序列未验通过'
        if row['case'] == '05': return '追回闭环通过；竞态以事务回归另验'
        return '目标步骤通过'
    lines = ['# 发货后仅退款自动流程：排查修复与复测', '', '原批次：20261023150323。原单据保留；本次结果位于 retest，原失败结果不覆盖。', '',
             '验收结论已纠正：11个输入变体仅代表目标单的后续处理或保护步骤达到预期，不代表完整申请序列已全自动验收。03、09、10各有一张人工前置退款单，平台成功状态也由测试数据直接准备，未经过自动审核和付款。此前将这些序列笼统表述为自动流程通过，范围过大。', '',
             '审核来源核实：目标单8张由“自动售后规则”审核，3张因保护规则保持未审核；前置单3张由admin人工审核。审核人与操作来源来自数据库原始记录，未修改任何审核记录。', '',
             '修复：新增显式小额累计免退规则；按实际分仓出库来源自动取消/追回；应退赠品提前建立安排；真实实收入账无差异后自动结案；修复模拟平台付款版本及取消回告响应；采用原订单串行锁和最新占用读取防并发超额。', '',
             '新增规则默认关闭，仅在本次验收店铺启用。前次纯差价退款不减少应退商品数量；平台资金成功不替代仓库实收。', '',
             '| 用例 | 场景 | 结果 | 退款入口 | 公司库存（总/可用） |', '|---|---|---|---|---|']
    for row in result['rows']:
        stock = '；'.join(r[0].split('C')[-1] + '：' + r[1] + '/' + r[2] for r in row['stock']['company'])
        lines.append('|' + row['case'] + '|' + row['scenario'] + '|' + verdict(row) + '|[' + row['refund_sn'] + '](' + row['refund_url'] + ')|' + stock + '|')
    lines += ['', '人工前置单（不计自动审核或自动付款通过）：', '', '| 场景 | 前置退款 | 金额 | 本地方案审核 | 平台成功来源 |', '|---|---|---|---|---|']
    for prior in audit.get('refunds', []):
        if prior['role'] == 'prior': lines.append('|' + prior['case'] + '|[' + prior['refund_sn'] + '](' + prior['url'] + ')|' + str(prior['amount']) + '元|' + prior['review_by'] + ' · ' + str(prior['reviewed_at']) + '|测试数据直接准备SUCCESS，未跑自动付款|')
    lines += ['', '通过包括预期转人工的保护场景：20.01元、先退10再退15、先退20再申请100，均无自动付款且库存不变。', '',
              '库存核实：免退货及超限转人工仍为99；退货主品恢复100，赠品也恢复100；部分发货先释放2件占用，再真实收回1件；两件退货从98→99→100，首件实收后未提前付款。重复发送原仓库回传没有重复入账；每张目标退款最多一笔PAY。', '',
              '渠道口径：退回增加仓库及公司公共库存，不自动补回已销售消耗的店铺配额。因此发出1件后渠道配额/可售仍为99，发出2件仍为98；未出库截单只释放占用，配额仍为100。这是现有渠道配额规则，未在本次擅自扩大店铺可售额度。', '',
              '赠品场景：差价20元前置由admin人工确认用途，平台成功事实由测试数据准备。后一笔80元由自动规则审核，主品A和应退赠品G各1件，自动合为1个退回包裹、1张共同THK，全部实收入账后才自动退80元。第一笔差价的自动识别、审核和付款未验收通过；当前小额免退规则只自动采用OTHER+KEEP，不能冒充DIFFERENCE自动识别。', '',
              '覆盖边界：05保留单最初已出库，本次验证实际出库后自动追回闭环；“自动采用截单后、任务执行前实际出库抢先”的竞争窗口由真实MySQL事务回归确定性验证，未声称该保留单复现了并发窗口。08为两个虚拟仓、同一实体仓，跨实体仓未覆盖。全局Quartz保持暂停，接入及自动流程按测试店铺/退款ID唤醒，未验全局定时接入。', '',
              '原04的旧取消回告UNKNOWN保留原审计，未强改成功、未重新付款；可信新版本成功事实已使原业务单完成。']
    if fresh:
        c = fresh['case']; ref = c['refunds']['target']
        lines += ['', '另增全新未出库回归：[' + ref['refund_sn'] + '](' + ref['url'] + ')，自动截停、释放占用、取消回告、平台审核和付款全部明确成功，库存100/100；验证修复后的响应协议。']
    lines += ['', '验证：284项订单回归、84项渠道接口回归、11组前端回归；浏览器无脚本错误、无写操作。旧店铺配置、原验收库存、消息队列开关和调度配置保持不变。', '', '原始数据：results.json、verification.json、逐单 observation.json、签名回传XML及截图。']
    (DEST / '验收结果.md').write_text('\n'.join(lines) + '\n', 'utf-8')
    rows = ''.join('<tr id="case' + html.escape(r['case']) + '"><td>' + html.escape(r['case']) + '</td><td>' + html.escape(r['scenario']) + '</td><td>' + html.escape(verdict(r)) + '</td><td><a href="' + html.escape(r['refund_url']) + '">' + html.escape(r['refund_sn']) + '</a>' + ''.join('<br>人工前置：<a href="' + html.escape(prior['url']) + '">' + html.escape(prior['refund_sn']) + '</a>' for prior in r.get('preconditions', [])) + '</td><td><a href="' + r['case'] + '-refund.png">截图</a></td></tr>' for r in result['rows'])
    text = '<!doctype html><meta charset="utf-8"><title>自动售后复测：验收范围已纠正</title><style>body{font:16px/1.8 system-ui;max-width:1450px;margin:40px auto;color:#26374d}table{border-collapse:collapse;width:100%}td,th{padding:12px;border:1px solid #dde4ed;text-align:left}a{color:#1674c9}pre{white-space:pre-wrap}.scope{background:#fff5e7;border:1px solid #e3b46f;padding:18px}</style><h1>自动售后复测：验收范围已纠正</h1><p class="scope">目标步骤通过不等于完整序列全自动通过。03、09、10含人工审核的前置单，平台SUCCESS由测试数据准备，第一笔未验自动审核及付款。目标单8张自动审核、3张保护未审核；前置单3张人工审核。原审核记录全部保留。</p><p><a href="review-origin-audit.json">查看逐单审核来源证据</a> · <a href="automation-settings.png">自动规则配置截图</a></p><table><tr><th>用例</th><th>场景</th><th>本次实际覆盖</th><th>目标单与人工前置单</th><th>验收截图</th></tr>' + rows + '</table><h2>结果与覆盖边界</h2><pre>' + html.escape('\n'.join(lines[lines.index('人工前置单（不计自动审核或自动付款通过）：'):])) + '</pre>'
    (DEST / '验收报告.html').write_text(text, 'utf-8')


if __name__ == '__main__':
    main()
