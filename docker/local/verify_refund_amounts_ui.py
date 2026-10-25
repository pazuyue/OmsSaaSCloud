"""Read-only acceptance of refund identity, original paid money and refund budget scopes."""
import copy
import json
import sys
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request
from retest_after_ship_refund_auto import Retest

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-amounts-ui'


def main():
    OUT.mkdir(exist_ok=True)
    token = login()
    def api(id): return request('/order/refunds/business/' + str(id), token=token)['data']
    for attempt in range(70):
        try:
            pending = api(91); completed = api(101)
            assert 'amount_context' in pending
            break
        except Exception:
            if attempt == 69: raise
            time.sleep(2)
    r = Retest(); r.token = token
    cases = [r.data['cases'][key] for key in ['02B', '07', '09']]
    stocks = {c['key']: r.snapshot(c) for c in cases}
    assert pending['amount_context']['current_limit'] == 100
    assert completed['amount_context']['current_confirmed'] == 80
    assert completed['amount_context']['confirmed_total'] == 100
    assert completed['amount_context']['remaining_unpaid'] == 0
    errors, writes, checks = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={'width': 1640, 'height': 1000})
        ctx.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        def opened(row, fixture=False, width=1640):
            page = ctx.new_page(); page.set_viewport_size({'width': width, 'height': 1000}); page.set_default_timeout(30000)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('request', lambda q: writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET', 'HEAD', 'OPTIONS'] else None)
            if fixture: page.route('**/prod-api/order/refunds/business/' + str(row['id']), lambda route: route.fulfill(json={'code': 200, 'data': row}))
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(row['id']), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 仅退款', exact=True)
            expect(dialog.locator('.refund-identity-main > strong')).to_have_text(row['refund_sn'])
            return page, dialog

        page, d = opened(pending)
        identity = d.locator('.refund-order-identity')
        expect(identity).to_contain_text(pending['platform_tid']); expect(identity).to_contain_text(pending['platform_oid'])
        expect(identity.get_by_role('button', name='复制平台订单号', exact=True)).to_be_visible()
        expect(identity.get_by_role('button', name='复制平台子订单号', exact=True)).to_be_visible()
        expect(identity.locator('.refund-shop')).to_contain_text('自动仅退款验收02B')
        facts = d.locator('.application-money-summary strong')
        expect(facts).to_have_text(['¥100.00', '¥20.01', '¥0.00'])
        for item in facts.all(): expect(item).to_be_in_viewport()
        expect(d.locator('.refund-progress .progress-tracks')).not_to_be_visible()
        expect(d.locator('.refund-progress')).to_contain_text('20.01')
        page.screenshot(path=str(OUT / 'pending-first-screen.png'), animations='disabled')
        d.locator('.refund-progress').get_by_role('button', name='展开完整进度', exact=True).click()
        expect(d.locator('.progress-tracks')).to_be_visible(); expect(d.locator('.progress-track')).to_have_count(5)
        d.locator('.refund-progress').get_by_role('button', name='收起完整进度', exact=True).click()
        d.locator('.refund-footer').get_by_role('button', name='核对审核方案', exact=True).click()
        review = page.get_by_role('dialog', name='审核仅退款方案', exact=True)
        expect(review).to_be_visible(); review.get_by_role('button', name='取消', exact=True).click()
        checks.append('Real pending case: shop/order/child IDs, original paid and current application visible; compact progress and review action work')
        page.close()

        page, d = opened(completed)
        expect(d.locator('.application-money-summary strong')).to_have_text(['¥100.00', '¥80.00', '¥80.00'])
        basis = d.locator('.refund-amount-basis')
        expect(basis).to_contain_text('累计已退')
        expect(basis.locator('.basis-values strong')).to_have_text(['¥20.00', '¥80.00', '¥100.00', '¥0.00'])
        expect(basis).not_to_contain_text('本次可处理上限')
        page.screenshot(path=str(OUT / 'completed-after-prior-difference.png'), animations='disabled')
        checks.append('Real completed 20+80 case: current refund80, child total100, remaining0; gifted item money is not added')
        page.close()

        before_second = copy.deepcopy(completed)
        before_second.update(funds_state='NONE', state='NEEDS_REVIEW', review_state='PENDING', purpose='UNKNOWN', goods_action='UNKNOWN', goods_state='WAIT_REVIEW', approved_version=None)
        before_second['platform'].update(platform_status='WAIT_SELLER_AGREE')
        before_second['amount_context'].update(current_confirmed=0, confirmed_total=20, remaining_unpaid=80)
        page, d = opened(before_second, True)
        expect(d.locator('.basis-values strong')).to_have_text(['¥20.00', '¥80.00'])
        page.screenshot(path=str(OUT / 'pending-after-prior-difference.png'), animations='disabled'); page.close()
        checks.append('Browser fixture for second refund pending: prior20, other occupied0, current limit80')

        partial = copy.deepcopy(pending)
        partial['sales_context']['line']['quantity'] = 3
        partial['sales_context']['line']['goods_payment'] = 300
        partial['platform']['platform_quantity'] = 1
        partial['amount_context'].update(original_goods_payment=300, original_quantity=3, requested_goods_payment=None, current_limit=300)
        page, d = opened(partial, True)
        expect(d.locator('.application-money-summary strong').first).to_have_text('¥300.00')
        expect(d.locator('.requested-payment-scope')).to_contain_text('未提供')
        expect(d.locator('.requested-payment-scope')).to_contain_text('不按平均单价推算')
        expect(d.locator('.application-quantities')).to_contain_text('3 件')
        page.screenshot(path=str(OUT / 'partial-quantity-no-average.png'), animations='disabled'); page.close()
        checks.append('Partial-quantity fixture: original3 paid300; one requested unit does not get an invented100 paid amount')

        freight = copy.deepcopy(pending)
        freight['sales_context']['order'].update(payment=106, buyer_freight=6)
        page, d = opened(freight, True)
        d.get_by_text('查看金额明细（商品 / 原平台订单）', exact=True).click()
        expect(d.locator('.application-details')).to_contain_text('¥106.00')
        expect(d.locator('.application-details')).to_contain_text('买家运费（整单）')
        expect(d.locator('.application-details')).to_contain_text('¥6.00')
        expect(d.locator('.application-money-summary strong').first).to_have_text('¥100.00')
        page.screenshot(path=str(OUT / 'order-and-goods-amount-scope.png'), full_page=True, animations='disabled'); page.close()
        checks.append('Freight fixture: order106 includes freight6; child goods paid remains100')

        for kind, unit in [('VIRTUAL_BUNDLE', '套'), ('LUCKY_BAG', '袋')]:
            bundle = copy.deepcopy(partial)
            bundle['bundles'] = [{'id': 90001, 'platform_oid': bundle['platform_oid'], 'business_type': kind, 'quantity': 3, 'sku_sn': 'UI_ONLY_PARENT', 'goods_name': '金额口径验收组合商品', 'components': [{'sku_sn': 'PART_A', 'quantity': 2}], 'units': []}]
            page, d = opened(bundle, True)
            expect(d.locator('.application-quantities')).to_contain_text('3 ' + unit)
            expect(d.locator('.application-money-summary strong').first).to_have_text('¥300.00')
            expect(d.locator('.application-money-summary')).to_have_count(1)
            page.close()
        checks.append('Kit/bag browser fixtures retain parent sales units and show the parent paid amount once')

        page, d = opened(pending, width=1000)
        expect(d.locator('.application-money-summary strong').first).to_be_in_viewport()
        page.screenshot(path=str(OUT / 'narrow-screen.png'), animations='disabled'); page.close()
        browser.close()
    for row in [pending, completed]:
        after = api(row['id'])
        for field in ['state', 'review_state', 'review_by', 'control_version', 'goods_state', 'funds_state', 'events']:
            assert row[field] == after[field], (row['id'], field)
    assert stocks == {c['key']: r.snapshot(c) for c in cases}, 'The display acceptance changed inventory'
    assert not errors, errors
    assert not writes, writes
    (OUT / 'result.json').write_text(json.dumps({'checks': checks, 'page_errors': errors, 'browser_writes': writes, 'refund_states_unchanged': True, 'inventory_unchanged': True}, ensure_ascii=False, indent=2), 'utf-8')
    print('PASS refund amount/identity browser acceptance; no writes, refund-state or inventory changes')


if __name__ == '__main__':
    main()
