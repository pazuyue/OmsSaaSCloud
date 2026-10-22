"""Accept the explicitly selected TK72 through review and background automation.

Only the original local TEST warehouse is allowed. Never simulate receipts or
create/dispatch plans manually. Save the pre-change snapshot before mutation.
"""
import json
import re
from pathlib import Path

from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request
from test_multi_virtual_shipping import sql, wait_for

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-target-auto'
REFUND = 'TKb5775de4e3c74eb0858491b7b47ffd75'
PREFIX = '/order/refunds/business/72'
STORE = 'GIFT_V_20261019234013'
SKU = 'GIFT_A_20261019234013'
GIFT = 'GIFT_G_20261019234013'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    checks, errors = [], []

    def api(path, body=None):
        result = request(path, payload=body, token=token,
                         method='POST' if body is not None else 'GET')
        return result.get('data', result)

    def save(name, value):
        (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), 'utf-8')

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    def business():
        return api(PREFIX)

    def plans():
        return api(PREFIX + '/goods')['plans']

    def inventory():
        return sql("SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,"
                   "frozen_stock,pending_public_stock FROM oms_inventory WHERE company_code='QM' "
                   "AND sku_sn IN ('" + SKU + "','" + GIFT + "') ORDER BY sku_sn",
                   'qm_oms_saas_inventory')

    def gift_state(rows):
        return [{k: p[k] for k in ['id', 'gift_plan_id', 'quantity', 'accepted_quantity',
                                  'posted_quantity', 'state']} | {
            'return_sn': p['actual']['return_sn'],
            'documents': [d['return_sn'] for d in p['actual']['documents']]}
            for p in rows if p.get('gift_plan_id')]

    b = business()
    assert b['refund_sn'] == REFUND and b['channel_id'] == 73 and b['order_id'] == 124
    assert b['platform']['platform_quantity'] == 2 and b['platform']['source_state'] == 'VALID'
    assert b['purpose'] == 'CANCEL' and b['goods_action'] == 'RETURN'
    assert b['platform']['dispute_type'] == 'REFUND_AND_RETURN'
    config = api('/order/refunds/parcel-configs/73')
    warehouse = next(w for w in config['warehouses'] if w['store_code'] == STORE)
    assert warehouse['usable'] == 1
    connection = next(c for c in api('/supplychain/wmsIntegration/connections') if c['id'] == 10)
    assert connection['enabled'] == 1 and connection['environment'] == 'TEST'
    assert connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
    before_path = OUT / 'before.json'
    if not before_path.exists():
        assert not any(not p.get('gift_plan_id') for p in plans()), 'Unexpected existing purchase plan'
        save('before.json', {'business': b, 'plans': plans(), 'policy': config['policy'],
                             'inventory': inventory()})
    before = json.loads(before_path.read_text('utf-8'))
    assert before['business']['refund_sn'] == REFUND
    check('已保存原始规则、退款、赠品退货单和库存；下发目标为原发货对应的本地 TEST 仓')

    p = config['policy']
    desired = {'auto_create': 1, 'auto_dispatch': 1, 'merge_mode': 'NONE',
               'create_store_code': STORE, 'store_codes': [STORE],
               'create_return_address': '[赠品验收] 退回地址，无实际配送',
               'create_return_contact': 'TEST-GIFT-RETURN'}
    if (any(p.get(k) != v for k, v in desired.items() if k != 'merge_mode') or
            any(p[k] != 0 for k in ['enabled', 'manual_enabled', 'auto_enabled'])):
        p.update(desired)
        api('/order/refunds/parcel-configs/73', p)
    p = api('/order/refunds/parcel-configs/73')['policy']
    assert p['auto_create'] == p['auto_dispatch'] == 1
    assert p['enabled'] == p['manual_enabled'] == p['auto_enabled'] == 0
    save('policy.json', p)
    check('店铺配置已开启审核后自动创建、不合包、自动下发本地测试仓')

    b = business()
    if not b.get('return_auto') or (b['return_auto']['state'] == 'PENDING' and
                                   not any(not p.get('gift_plan_id') for p in plans())):
        api(PREFIX + '/review', {'control_version': b['control_version'],
            'platform_version': b['platform']['version'], 'purpose': 'CANCEL',
            'goods_action': 'RETURN', 'reason': '[指定退款自动流程验收] 核对原发货购买商品退两件，保留原赠品退货记录',
            'evidence': 'TEST-TK72-AUTO-20261020'})
    elif (b['return_auto']['state'] == 'MANUAL' and
          b['return_auto']['message'] == '店铺退货规则已变化，请核对后重试自动创建'):
        # A resumed acceptance may have saved the rule after its first review.
        # Refresh that task through the supported optimistic retry, never SQL.
        api(PREFIX + '/goods/auto-create/retry', {'control_version': b['control_version']})
    elif b['return_auto']['state'] in ['MANUAL', 'CANCELED']:
        raise AssertionError(b['return_auto'])
    rows = wait_for(plans, lambda rows: any(not p.get('gift_plan_id') for p in rows),
                    '指定退款未自动创建购买商品退回安排', 150)
    rows = wait_for(plans, lambda rows: any(not p.get('gift_plan_id') and
        p.get('actual') and p['actual']['dispatch_state'] == 'ACCEPTED' and
        p['auto_dispatch_state'] == 'SENT' for p in rows), '指定退款未自动下发本地仓库', 150)
    purchase = [p for p in rows if not p.get('gift_plan_id')]
    assert len(purchase) == 1
    plan = purchase[0]
    assert plan['sku_sn'] == SKU and plan['quantity'] == 2 and plan['shipment_line_id'] == 75
    assert plan['store_code'] == STORE and not plan.get('return_waybill')
    assert len(plan['actual']['documents']) == 1 and plan['actual']['connection_id'] == 10
    assert plan['accepted_quantity'] == plan['posted_quantity'] == 0
    assert business()['return_auto']['state'] == 'DONE'
    check('后台自动创建购买商品 2 件退回安排，无物流单号、不合包也自动下发成功：' + plan['actual']['return_sn'])
    save('after-first-dispatch.json', {'business': business(), 'plans': rows})

    for _ in range(2):
        api(PREFIX + '/parcels/auto-run', {})
    rows = plans()
    purchase = [p for p in rows if not p.get('gift_plan_id')]
    assert len(purchase) == 1 and purchase[0]['id'] == plan['id']
    assert len(purchase[0]['actual']['documents']) == 1
    assert purchase[0]['actual']['return_sn'] == plan['actual']['return_sn']
    assert gift_state(rows) == gift_state(before['plans'])
    assert inventory() == before['inventory']
    after = business()
    assert after['funds_state'] == before['business']['funds_state']
    assert after['refund_fee'] == before['business']['refund_fee']
    check('重复自动检查两次未重复建单；原赠品退货单、退款金额和库存保持一致')
    save('after.json', {'business': after, 'plans': rows, 'inventory': inventory()})

    logs = api('/supplychain/wmsIntegration/log-center?numberType=REFUND&number=' +
               REFUND + '&allTime=true&action=REFUND_RETURN_CREATE&result=SUCCESS')
    save('warehouse-log-summary.json', logs)
    purchase_logs = [row for row in logs['rows'] if row['ticket_sn'] == plan['actual']['return_sn']]
    assert len(purchase_logs) == 1 and purchase_logs[0]['environment'] == 'TEST'
    assert purchase_logs[0]['connection_id'] == 10 and purchase_logs[0]['result'] == 'SUCCESS'
    check('仓库交互日志保留购买商品退货单创建成功记录，TEST 仓受理且没有重复请求')

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(25000)
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(BASE + '/oms-order/afterSaleRules/returns?channelId=73&refundId=72', wait_until='networkidle')
        editor = page.locator('.return-rule-editor:visible')
        item = lambda label: editor.locator('.el-form-item').filter(
            has=page.locator('label').filter(has_text=re.compile('^' + label + '$')))
        expect(item('创建方式').locator('.el-radio.is-checked')).to_contain_text('审核后自动创建')
        page.screenshot(path=str(OUT / 'rules-auto-create.png'), full_page=True, animations='disabled')
        editor.get_by_role('tab', name='仓库下发', exact=True).click()
        page.screenshot(path=str(OUT / 'rules-auto-dispatch.png'), full_page=True, animations='disabled')
        editor.get_by_role('tab', name='合包规则', exact=True).click()
        expect(editor.locator('.el-tab-pane:visible .el-radio.is-checked').first).to_contain_text('不合包')
        page.screenshot(path=str(OUT / 'rules-no-merge.png'), full_page=True, animations='disabled')
        page.goto(BASE + '/oms-order/businessRefunds?id=72', wait_until='networkidle')
        dialog = page.locator('.el-dialog:visible').filter(
            has=page.get_by_role('tab', name='退回与包裹', exact=True))
        dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
        expect(dialog.locator('.auto-return-status')).to_contain_text('已自动创建购买商品退回安排')
        expect(dialog.locator('.refund-goods')).to_contain_text(SKU)
        dialog.locator('.plan-groups .el-radio-button').filter(has_text='购买商品').click()
        dialog.locator('.return-plans-table').get_by_role('button', name='查看并处理', exact=True).first.click()
        expect(dialog.locator('.refund-goods')).to_contain_text('仓库已接收')
        expect(dialog.locator('.refund-goods')).to_contain_text(plan['actual']['return_sn'])
        page.screenshot(path=str(OUT / 'refund-auto-result.png'), animations='disabled')
        dialog.locator('.plan-groups .el-radio-button').filter(has_text='赠品').click()
        expect(dialog.locator('.return-plans-table')).to_contain_text(GIFT)
        page.screenshot(path=str(OUT / 'refund-gift-preserved.png'), animations='disabled')
        assert not errors, errors
        browser.close()
    check('规则设置和退款详情真实页面回显正确，无浏览器脚本错误')
    save('verification.json', {'refund_sn': REFUND, 'purchase_return_sn': plan['actual']['return_sn'],
         'purchase_quantity': 2, 'accepted_quantity': 0, 'posted_quantity': 0,
         'checks': checks, 'errors': errors, 'receipt_simulated': False,
         'scope': '即时自动创建与独立下发；未模拟仓库实收，未启用本地已暂停的定时补偿任务'})


if __name__ == '__main__':
    main()
