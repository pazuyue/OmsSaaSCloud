"""Read-only acceptance of own/shared return scope, mixed units and action stages."""
import copy
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_merged_composite_gift_refunds import Acceptance
from verify_goods_workspace_ui import BASE, request

OUT = Path(__file__).resolve().parents[2] / 'outputs/refund-return-ui'


def main():
    acceptance = Acceptance()
    errors, checks = [], []
    before = {'stock': acceptance.snapshot(), 'preceding': acceptance.protected_order()}
    OUT.mkdir(exist_ok=True)
    original = json.loads((OUT / 'before.json').read_text('utf-8'))
    assert original == before, 'Stock changed during build/deployment'
    refund_id = acceptance.c['refunds']['kit']['id']
    base = '/order/refunds/business/' + str(refund_id)
    try:
        goods, parcels, refund = acceptance.api(base + '/goods'), acceptance.api(base + '/parcels'), acceptance.api(base)
        assert len(goods['plans']) == 9 and len(parcels) == 1
        assert len(parcels[0]['members']) == 14
        assert {p['stock_unit'] for p in goods['plans']} == {'件', '套'}
        assert all(p['goods_name'] and p['source_kind'] for p in goods['plans'])
        assert sum(p['stock_unit'] == '套' for p in goods['plans']) == 1
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': acceptance.token, 'url': BASE}])

            def open_page(fixtures=None, width=1700):
                page = context.new_page()
                page.set_viewport_size({'width': width, 'height': 1100})
                page.set_default_timeout(30000)
                page.on('pageerror', lambda error: errors.append(str(error)))
                for suffix, value in (fixtures or {}).items():
                    page.route('**/prod-api' + base + suffix,
                               lambda route, _request, value=value: route.fulfill(json={'code': 200, 'data': value}))
                page.goto(BASE + '/oms-order/businessRefunds?id=' + str(refund_id), wait_until='networkidle')
                dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
                dialog.get_by_role('tab', name='退货与入库', exact=True).click()
                expect(dialog.locator('.return-document')).to_have_count(1)
                return page, dialog

            page, dialog = open_page()
            summary = dialog.locator('.refund-summary-table')
            expect(summary).to_contain_text('购买商品')
            expect(summary).to_contain_text('关联赠品')
            expect(summary).to_contain_text('10 件 + 1 套')
            expect(summary).to_contain_text('7 件 + 1 套')
            expect(dialog.locator('.return-document')).to_contain_text(acceptance.c['return_sn'])
            expect(dialog.locator('.return-document')).to_contain_text('全部实收')
            expect(dialog.locator('.return-document')).to_contain_text('全部入账')
            expect(dialog.locator('.return-plans-table')).to_contain_text('整套入库')
            dialog.locator('.refund-overview').scroll_into_view_if_needed()
            page.screenshot(path=str(OUT / 'refund-return-overview.png'), full_page=True, animations='disabled')
            dialog.locator('.return-documents').scroll_into_view_if_needed()
            page.screenshot(path=str(OUT / 'return-document.png'), full_page=True, animations='disabled')
            dialog.locator('.return-plans-table').get_by_role('button', name='查看并处理', exact=True).first.click()
            expanded = dialog.locator('.plan-expanded')
            expanded.get_by_text('退回来源、地址与处理依据', exact=True).click()
            expect(expanded).to_contain_text(acceptance.c['shipment_sn'])
            expect(expanded).to_contain_text(acceptance.c['oids']['kit'])
            page.screenshot(path=str(OUT / 'return-item-source.png'), full_page=True, animations='disabled')
            dialog.locator('.return-plans-table').get_by_role('button', name='查看并处理', exact=True).first.click()
            dialog.get_by_role('button', name='查看包裹及处理实收', exact=True).click()
            drawer = page.locator('.refund-parcel-drawer')
            expect(drawer).to_contain_text('合包退回 · 4 张退款单')
            expect(drawer.locator('.drawer-own-scope')).to_contain_text('10 件 + 1 套')
            expect(drawer.locator('.parcel-metrics')).to_contain_text('14 件 + 2 套')
            expect(drawer.get_by_text('16 件', exact=True)).to_have_count(0)
            expect(drawer.locator('.parcel-refund-groups .el-table__body-wrapper').first.locator('tbody > tr')).to_have_count(1)
            expect(drawer.get_by_role('button', name='核对未入账实收', exact=True)).to_have_count(0)
            expect(drawer.get_by_role('button', name='截停整张单据剩余收货', exact=True)).to_have_count(0)
            page.screenshot(path=str(OUT / 'parcel-current-refund.png'), full_page=True, animations='disabled')
            drawer.locator('.parcel-refund-groups .el-table__expand-icon').first.click()
            expect(drawer.locator('.el-table__expanded-cell')).to_contain_text('关联赠品')
            expect(drawer.locator('.el-table__expanded-cell')).to_contain_text('福袋内商品')
            page.screenshot(path=str(OUT / 'parcel-item-sources.png'), full_page=True, animations='disabled')
            drawer.locator('.parcel-refund-groups .el-table__expand-icon').first.click()
            drawer.get_by_text('全部关联退款（4张）', exact=True).click()
            expect(drawer.locator('.parcel-refund-groups .el-table__body-wrapper').first.locator('tbody > tr')).to_have_count(4)
            page.screenshot(path=str(OUT / 'parcel-all-refunds.png'), full_page=True, animations='disabled')
            page.close()
            checks.append('实际合包：本退款10件+1套、整包14件+2套；1张THK、4张退款，默认本退款')

            # Browser-only response fixtures exercise intermediate states without
            # modifying any refund, inventory balance or warehouse ticket.
            pending_goods, pending_parcels, pending_refund = copy.deepcopy(goods), copy.deepcopy(parcels), copy.deepcopy(refund)
            member = next(m for m in pending_parcels[0]['members'] if m['stock_unit'] == '套' and str(m['refund_id']) == str(refund_id))
            member.update(accepted_quantity=0, posted_quantity=0, receipt_state='WAITING')
            plan = next(p for p in pending_goods['plans'] if p['id'] == member['plan_id'])
            plan.update(accepted_quantity=0, posted_quantity=0, state='ACTIVE')
            plan['actual']['parcel_pending_count'] = 1
            pending_refund.update(goods_state='WAIT_RETURN', state='PROCESSING')
            pending_parcels[0]['receipts'] = [{'id': 999999, 'state': 'WAIT_ALLOCATION', 'quantity': 1,
                'message_id': 'UI_ONLY', 'payload': {'ticketSn': acceptance.c['return_sn'],
                'lines': [{'sku': member['sku_sn'], 'lineNo': str(member['id']), 'good': 1, 'bad': 0}]}}]
            page, dialog = open_page({'/goods': pending_goods, '/parcels': pending_parcels, '': pending_refund})
            expect(dialog.locator('.refund-overview')).to_contain_text('实收待分配')
            expect(dialog.get_by_role('button', name='确认货物处理完成', exact=True)).to_be_disabled()
            dialog.get_by_role('button', name='查看包裹及处理实收', exact=True).click()
            drawer = page.locator('.refund-parcel-drawer')
            expect(drawer.locator('.parcel-metrics .pending')).to_contain_text('1 套')
            expect(drawer.get_by_role('button', name='分配待确认实收', exact=True)).to_be_visible()
            drawer.get_by_role('button', name='更多操作', exact=True).click()
            page.get_by_role('button', name='截停整张单据剩余收货', exact=True).click()
            confirm = page.get_by_role('dialog', name='共同退货入库单处理', exact=True)
            expect(confirm).to_contain_text('整单截停影响下方全部退款')
            expect(confirm.get_by_role('button', name='确认整单截停', exact=True)).to_be_disabled()
            expect(confirm.locator('.el-table__body-wrapper').first.locator('tbody > tr')).to_have_count(14)
            page.close()
            checks.append('待分配：套单位正确，不计入本退款已收或入库，禁止结案；截停确认覆盖全部退款')

            unposted_goods, unposted_parcels = copy.deepcopy(goods), copy.deepcopy(parcels)
            unposted_goods['plans'][0]['posted_quantity'] -= 1
            next(m for m in unposted_parcels[0]['members'] if m['plan_id'] == unposted_goods['plans'][0]['id'])['posted_quantity'] -= 1
            page, dialog = open_page({'/goods': unposted_goods, '/parcels': unposted_parcels, '': pending_refund})
            expect(dialog.locator('.refund-overview')).to_contain_text('尚未入库')
            dialog.get_by_role('button', name='查看包裹及处理实收', exact=True).click()
            expect(page.locator('.refund-parcel-drawer').get_by_role('button', name='核对未入账实收', exact=True)).to_be_visible()
            page.close()
            checks.append('已收未入账：明确显示待入库，并展示当前处理动作')

            # A standalone document uses the same stages and keeps earlier
            # stopped execution distinct from the current re-arranged ticket.
            single = copy.deepcopy(goods)
            plan = single['plans'][0]
            plan.update(quantity=3, accepted_quantity=2, posted_quantity=1, state='PARTIAL', parcel_state='WAIT_LOGISTICS')
            old = {'id': 991, 'return_sn': 'THK_UI_OLD', 'quantity': 3, 'accepted_quantity': 1, 'posted_quantity': 1,
                   'stock_unit': '件', 'dispatch_state': 'ACCEPTED', 'receipt_state': 'PARTIAL', 'cancel_state': 'CONFIRMED', 'generation': 1}
            current = {**old, 'id': 992, 'return_sn': 'THK_UI_CURRENT', 'quantity': 2, 'accepted_quantity': 1, 'posted_quantity': 0,
                       'cancel_state': 'NONE', 'generation': 2}
            plan['actual'] = {'shared_document': False, 'documents': [current, old], 'lines': [], 'reviews': []}
            single['plans'] = [plan]
            single['bundle_returns'] = []
            page = context.new_page();page.set_default_timeout(30000)
            page.on('pageerror', lambda error: errors.append(str(error)))
            for suffix, value in {'/goods': single, '/parcels': [], '': pending_refund}.items():
                page.route('**/prod-api' + base + suffix, lambda route, _request, value=value: route.fulfill(json={'code': 200, 'data': value}))
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(refund_id), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
            dialog.get_by_role('tab', name='退货与入库', exact=True).click()
            expect(dialog.locator('.return-document')).to_have_count(2)
            expect(dialog.locator('.previous-document')).to_contain_text('此前安排')
            expect(dialog.locator('.return-document').first).to_contain_text('部分实收')
            expect(dialog.locator('.return-document').first).to_contain_text('待入账')
            expect(dialog.locator('.refund-summary-table')).to_contain_text('3 件')
            page.close()
            checks.append('独立入库：当前与此前截停安排区分，重排不增加本退款计划数量')

            page, dialog = open_page(width=1280)
            expect(dialog.locator('.refund-summary-table')).to_contain_text('10 件 + 1 套')
            assert dialog.evaluate('(el) => el.scrollWidth <= el.clientWidth + 2'), 'Dialog overflows at 1280px'
            page.screenshot(path=str(OUT / 'refund-return-1280.png'), full_page=True, animations='disabled')
            page.close()
            browser.close()
        after = {'stock': acceptance.snapshot(), 'preceding': acceptance.protected_order()}
        assert after == before, 'Read-only acceptance changed inventory or preceding case'
        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps({'refund_id': refund_id, 'order_id': acceptance.c['order_id'],
            'checks': checks, 'browser_errors': errors, 'stock_unchanged': True, 'preceding_case_unchanged': True,
            'after': after}, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS refund return UI:', len(checks), 'scenarios; stock and previous acceptance unchanged')
    finally:
        request('/auth/logout', token=acceptance.token, method='DELETE')


if __name__ == '__main__':
    main()
