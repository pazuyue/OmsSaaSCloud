"""Read-only real API/browser QA; unusual product/amount fixtures exist only in the browser.

No refund reviews, warehouse commands or stock writes are submitted.
"""
import copy
import json
import re
import time
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-scenarios-ui'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    checks, errors, mutations, timings = [], [], [], {}

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    def api(path):
        result = request(path, token=token)
        assert result['code'] == 200, result.get('msg')
        return result['data']

    def listed(**params):
        query = '&'.join(str(k) + '=' + str(v) for k, v in params.items())
        return api('/order/refunds/business?' + query)

    try:
        baseline = listed(pageSize=50)
        assert baseline['rows'], 'Local UI acceptance needs an existing read-only refund'
        for scenario in ['REFUND_RETURN', 'REFUND_GIFT_ONLY', 'PAID_PENDING', 'MISSING_DOCUMENT', 'UNPOSTED']:
            start = time.perf_counter()
            data = listed(scenario=scenario, pageSize=50)
            timings[scenario] = {'total': data['total'], 'elapsed_ms': round((time.perf_counter() - start) * 1000, 2)}
            for row in data['rows']:
                if scenario == 'REFUND_RETURN':
                    assert row['dispute_type'] == 'REFUND' and row['has_purchase_return']
                if scenario == 'REFUND_GIFT_ONLY':
                    assert row['dispute_type'] == 'REFUND' and not row['has_purchase_return'] and row['has_gift_return']
                if scenario == 'PAID_PENDING':
                    assert row['funds_state'] == 'SUCCESS' and (row['purchase_return_pending'] or row['gift_pending'])
                if scenario == 'MISSING_DOCUMENT':
                    assert row['missing_purchase_document']
                if scenario == 'UNPOSTED':
                    assert any(p and p['accepted_quantity'] > p['posted_quantity'] for p in [row.get('purchase_progress'), row.get('gift_progress')])
            first = listed(scenario=scenario, pageSize=1, pageNum=1)
            second = listed(scenario=scenario, pageSize=1, pageNum=2)
            assert first['total'] == second['total'] == data['total']
            if first['rows'] and second['rows']:
                assert first['rows'][0]['id'] != second['rows'][0]['id']
        combined = listed(scenario='REFUND_RETURN', task='GOODS', goodsAction='RETURN', pageSize=50)
        assert all(row['goods_action'] == 'RETURN' and row['review_state'] == 'APPROVED' and row['goods_state'] not in ['DONE', 'CANCELED'] for row in combined['rows'])
        check('真实接口：五类场景在分页前筛选，场景、待办及货物处理可组合，翻页总数一致')

        sample = baseline['rows'][0]
        detail = api('/order/refunds/business/' + str(sample['id']))
        assert isinstance(detail['sales_context']['line'], dict)
        for key in ['purchase_progress', 'gift_progress']:
            if detail.get(key):
                assert sum(part['return_quantity'] for part in detail[key]['unit_progress']) == detail[key]['return_quantity']
        skus = sorted(set(line['sku_sn'] for line in detail['lines']))
        stock_sql = "SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + ','.join(quote(s) for s in skus) + ') ORDER BY sku_sn'
        before = sql(stock_sql, 'qm_oms_saas_inventory') if skus else ''

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(20000)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.on('request', lambda req: mutations.append(req.url) if req.method not in ['GET', 'HEAD', 'OPTIONS'] and '/prod-api/order/' in req.url else None)
            page.goto(BASE + '/oms-order/businessRefunds', wait_until='networkidle')
            root = page.locator('.business-refund-list')
            expect(root.locator('.refund-scenarios')).to_contain_text('仅退款含商品退回')

            def matching(params):
                return lambda response: response.request.method == 'GET' and urlsplit(response.url).path.endswith('/order/refunds/business') and all(parse_qs(urlsplit(response.url).query, keep_blank_values=True).get(k, ['']) == [v] for k, v in params.items())

            with page.expect_response(matching({'scenario': 'REFUND_RETURN', 'pageNum': '1'})) as result:
                root.locator('.refund-scenarios .el-radio-button').filter(has_text=re.compile('^仅退款含商品退回$')).click()
            assert result.value.json()['data']['total'] == timings['REFUND_RETURN']['total']
            with page.expect_response(matching({'scenario': 'REFUND_RETURN', 'task': 'GOODS'})):
                root.locator('.task-views .el-radio-button').filter(has_text=re.compile('^货物待处理$')).click()
            page.screenshot(path=str(OUT / 'real-scenario-filter.png'), full_page=True, animations='disabled')
            with page.expect_response(matching({'scenario': '', 'task': ''})):
                root.get_by_role('button', name='重置', exact=True).click()
            check('真实页面：快捷场景与待办独立组合，重置清空场景，列表无逐行详情请求')

            # Browser fixture uses real endpoint shape, with no persisted business edits.
            fake = copy.deepcopy(sample)
            fake.update(dispute_type='REFUND', state='PROCESSING', review_state='APPROVED', goods_action='RETURN', goods_state='WAIT_ACTION', funds_state='NONE',
                        has_purchase_return=1, has_gift_return=0, purchase_return_pending=1, missing_purchase_document=1, gift_pending=False)
            fake.pop('purchase_progress', None); fake.pop('gift_progress', None)
            context.route('**/prod-api/order/refunds/business?**', lambda route: route.fulfill(json={'code': 200, 'data': {'rows': [fake], 'total': 1}}))
            root.locator('.list-toolbar .el-icon-refresh').locator('..').click()
            body = root.locator('.refund-table > .el-table__body-wrapper')
            expect(body).to_contain_text('商品需退回')
            expect(body).to_contain_text('商品退回单尚未生成')
            page.screenshot(path=str(OUT / 'refund-only-return.png'), full_page=True, animations='disabled')

            current = copy.deepcopy(detail)
            current.update(fake)
            current['platform'] = copy.deepcopy(detail['platform'])
            current['platform'].update(dispute_type='REFUND', platform_quantity=1, refund_fee='30.00')
            current.update(refund_fee='20.00', approved_version=1, lines=[{'id': 1, 'platform_oid': '9007199254740993123', 'sku_sn': 'BAG_UI', 'goods_name': '【UI验收】销售福袋', 'quantity': 3, 'platform_spec': '上装＋下装'}], platform_oid='9007199254740993123')
            current['sales_context'] = {'line': {'sales_amount': '100.00', 'discount_total': '15.00', 'goods_payment': '85.00'}}
            current['bundles'] = [{'id': 8, 'platform_oid': current['platform_oid'], 'sku_sn': 'BAG_UI', 'goods_name': '【UI验收】销售福袋', 'quantity': 3, 'business_type': 'LUCKY_BAG', 'units': [
                {'unit_id': 1001, 'unit': 1, 'return_refund_id': current['id'], 'components': [{'sku_sn': 'A_UI', 'goods_name': '上装', 'quantity': 1}, {'sku_sn': 'B_UI', 'goods_name': '下装', 'quantity': 1}]},
                {'unit_id': 1002, 'unit': 2, 'return_refund_id': current['id'] + 1, 'components': []}]}]
            current['purchase_progress'] = {'plan_count': 1, 'document_count': 0, 'return_quantity': 2, 'accepted_quantity': 1, 'posted_quantity': 0, 'remaining_quantity': 1, 'unit_progress': [{'physical_unit': '件', 'return_quantity': 2, 'accepted_quantity': 1, 'posted_quantity': 0}]}
            current['platform']['reason'] = '【UI验收】仅退款，经协商退回指定原袋'
            detail_path = '**/prod-api/order/refunds/business/' + str(sample['id'])
            context.route(detail_path, lambda route: route.fulfill(json={'code': 200, 'data': current}))
            root.locator('.el-table__fixed-right').get_by_role('button', name='详情', exact=True).click()
            dialog = page.locator('.el-dialog:visible').filter(has=page.locator('.refund-application-goods'))
            app = dialog.locator('.refund-application-goods')
            expect(app).to_contain_text('原购买数量3 袋')
            expect(app).to_contain_text('本次申请数量1 袋')
            expect(app).to_contain_text('已安排 2 件')
            expect(app.get_by_role('button', name='复制申请商品平台子订单号')).to_have_count(1)
            expect(dialog).to_contain_text('平台申请金额已变化')
            app.get_by_text('销售金额与退款依据', exact=True).click()
            expect(app).to_contain_text('¥85.00'); expect(app).to_contain_text('¥20.00'); expect(app).to_contain_text('¥30.00')
            app.get_by_text('销售商品组成与实际退回范围', exact=True).click()
            expect(app).to_contain_text('本退款已关联整袋退回 1 袋')
            expect(app).to_contain_text('原袋号 #1001'); expect(app).not_to_contain_text('原袋号 #1002')
            page.screenshot(path=str(OUT / 'application-lucky-bag.png'), full_page=True, animations='disabled')
            check('浏览器模拟：销售父商品、原购3袋、本次1袋、退回2件清晰区分，只显示本退款关联原袋，金额新旧并列')
            dialog.locator('> .el-dialog__header > .el-dialog__headerbtn').click()
            current['bundles'] = []
            current['lines'][0].update(stocked_bundle_snapshot={'components': [{'sku_sn': 'A_UI', 'goods_name': '成品组件', 'quantity': 2}]})
            current['purchase_progress']['unit_progress'] = [{'physical_unit': '套', 'return_quantity': 1, 'accepted_quantity': 0, 'posted_quantity': 0}]
            current['purchase_progress'].update(return_quantity=1, accepted_quantity=0)
            root.locator('.el-table__fixed-right').get_by_role('button', name='详情', exact=True).click()
            expect(app).to_contain_text('实物组合单品'); expect(app).to_contain_text('本次申请数量1 套'); expect(app).to_contain_text('已安排 1 套')
            page.set_viewport_size({'width': 1100, 'height': 1000})
            page.screenshot(path=str(OUT / 'application-stocked-narrow.png'), full_page=True, animations='disabled')
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
            dialog.locator('> .el-dialog__header > .el-dialog__headerbtn').click()
            fake.update(has_purchase_return=0, has_gift_return=1, purchase_return_pending=0, missing_purchase_document=0, goods_action='KEEP', goods_state='DONE')
            root.locator('.list-toolbar .el-icon-refresh').locator('..').click()
            expect(body).to_contain_text('仅赠品退回')
            check('浏览器模拟：实物组合按套、组件按件，窄屏无页面溢出，仅退赠品有独立场景')
            assert not errors, errors
            assert not mutations, mutations
            browser.close()
        if skus:
            assert before == sql(stock_sql, 'qm_oms_saas_inventory')
        check('全程未提交退款或仓库业务变更，关联SKU库存保持一致，浏览器无脚本错误')
        (OUT / 'result.json').write_text(json.dumps({'checks': checks, 'errors': errors, 'mutations': mutations, 'scenario_api_timings': timings, 'real_refund_id': sample['id'], 'browser_product_fixtures_only': True}, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
