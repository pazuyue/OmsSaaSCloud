"""Read-only lucky-bag inventory UX acceptance; draft overrides stay in the browser."""
import copy
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE


def main():
    root = Path(__file__).resolve().parents[2]
    case = json.loads((root / 'outputs/lucky-bags/luckybag-case.json').read_text(encoding='utf-8'))
    output = root / 'outputs/lucky-bags/stock-check'
    output.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        fixture = copy.deepcopy(request('/goods/info/%s/lucky-bag' % case['goods_id'], token=token))
        draft = fixture['data']['draft']
        draft.update(mode='PRESET', preset_quantity=200, max_cost=5)
        for c in draft['groups'][0]['candidates']:
            c['fixed_quantity'] = 100
        draft['groups'][1]['candidates'][0]['fixed_quantity'] = 200
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(15000)
            errors, stock_requests, writes = [], [], []
            page.on('pageerror', lambda error: errors.append(str(error)))

            def observe(req):
                if req.url.endswith('/lucky-bags/stock-check'):
                    stock_requests.append(req.post_data_json)
                if '/lucky-bag/' in req.url and req.method != 'GET':
                    writes.append(req.url)

            page.on('request', observe)
            page.route('**/prod-api/goods/info/%s/lucky-bag' % case['goods_id'],
                       lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps(fixture)))
            page.goto(BASE + '/oms-goods/lucky-bag/index?id=' + str(case['goods_id']), wait_until='networkidle')
            page.get_by_role('tab', name='内容规则', exact=True).click()
            controls = page.locator('.stock-check-controls')
            controls.locator('.el-select__input').click()
            with page.expect_response(lambda r: r.url.endswith('/lucky-bags/stock-check')) as checked:
                page.locator('.el-select-dropdown:visible').get_by_text(case['warehouses'][0]['code'], exact=False).click()
                page.keyboard.press('Escape')
            stock = checked.value.json()
            assert stock['code'] == 200, stock
            expect(controls).to_contain_text('当前无法配齐200袋')
            cards = page.locator('.group-card')
            availability = {r['sku_sn']: r['available'] for r in stock['data']['warehouses'][0]['components']}
            rows = cards.nth(0).locator('.el-table__body-wrapper tbody tr')
            expect(rows.nth(0)).to_contain_text('缺%s件' % (100 - availability[case['a']]))
            expect(cards.nth(1)).to_contain_text('缺%s件' % (200 - availability[case['d']]))
            page.screenshot(path=str(output / 'contents-shortage.png'), full_page=True, animations='disabled')

            before = len(stock_requests)
            field = rows.nth(0).locator('.quota-input input')
            field.fill('99'); field.press('Tab')
            expect(rows.nth(0)).to_contain_text('缺%s件' % (99 - availability[case['a']]))
            page.wait_for_timeout(450)
            assert len(stock_requests) == before, 'Quota edits must recalculate locally, without another stock query'
            field.fill('100'); field.press('Tab')

            def complete_trial():
                page.get_by_role('tab', name='试算与发布', exact=True).click()
                with page.expect_response(lambda r: r.url.endswith('/lucky-bags/trial')) as result:
                    page.get_by_role('button', name='开始试算', exact=True).click()
                body = result.value.json()
                assert body['code'] == 200, body
                return body['data']['warehouses'][0]

            trial = complete_trial()
            assert trial['code'] == 'STOCK_SHORTAGE', trial
            expect(page.locator('.trial-card')).to_contain_text('组件库存不足')
            expect(page.locator('.trial-diagnostics')).to_contain_text('本批需要 200 件')
            page.screenshot(path=str(output / 'trial-shortage.png'), full_page=True, animations='disabled')

            page.get_by_role('tab', name='内容规则', exact=True).click()
            field = page.locator('.batch-settings input'); field.fill('4'); field.press('Tab')
            for i in (0, 1):
                field = cards.nth(0).locator('.quota-input input').nth(i); field.fill('2'); field.press('Tab')
            field = cards.nth(1).locator('.quota-input input'); field.fill('4'); field.press('Tab')
            expect(controls).to_contain_text('本仓组件数量满足4袋需求')
            page.get_by_role('tab', name='库存与履约', exact=True).click()
            field = page.locator('.el-tab-pane:visible .el-input-number input'); field.fill('3'); field.press('Tab')
            cost_trial = complete_trial()
            assert cost_trial['code'] == 'COST_LIMIT', cost_trial
            expect(page.locator('.trial-card')).to_contain_text('成本超过上限')
            page.screenshot(path=str(output / 'cost-limit.png'), full_page=True, animations='disabled')

            page.get_by_role('tab', name='库存与履约', exact=True).click()
            field = page.locator('.el-tab-pane:visible .el-input-number input'); field.fill('5'); field.press('Tab')
            ready_trial = complete_trial()
            assert ready_trial['code'] == 'READY' and ready_trial['quantity'] == 4, ready_trial
            expect(page.locator('.trial-card')).to_contain_text('本次可配齐4袋')
            page.screenshot(path=str(output / 'trial-ready.png'), full_page=True, animations='disabled')

            page.get_by_role('tab', name='内容规则', exact=True).click()
            page.route('**/prod-api/inventory/lucky-bags/stock-check',
                       lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 500, 'msg': 'Acceptance: inventory unavailable'})))
            controls.get_by_role('button', name='刷新库存', exact=True).click()
            expect(controls).to_contain_text('库存查询失败，不能判断是否缺货')
            expect(cards.nth(0)).to_contain_text('查询失败')
            assert not errors and not writes, (errors, writes)
            report = {'component_availability': availability, 'shortage_diagnostics': trial,
                      'cost_diagnostics': cost_trial, 'ready_trial': ready_trial, 'quota_edits_reuse_stock_snapshot': True,
                      'query_failure_does_not_mean_zero_stock': True, 'stock_request_count': len(stock_requests),
                      'draft_or_publish_writes': len(writes), 'browser_errors': errors}
            (output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print('PASS lucky bag stock UI: per-SKU shortage, local recalculation, full trial, cost separation and request failure')
            print(json.dumps({'availability': availability, 'shortages': trial['diagnostics']['issues'], 'business_writes': writes}, ensure_ascii=False))
            browser.close()
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
