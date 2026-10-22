"""Read-only browser acceptance for sales parents, compositions and shipment sources."""
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/order-sales-ui'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        random = json.loads((ROOT / 'outputs/lucky-bags-random/result.json').read_text(encoding='utf-8'))
        fixed = json.loads((ROOT / 'outputs/exchange-ownership/fixedbundle-case.json').read_text(encoding='utf-8'))
        normal_id = int(sql("SELECT order_id FROM oms_order_line WHERE company_code='QM' AND bundle_id IS NULL AND stocked_bundle_snapshot IS NULL AND gift_plan_id IS NULL AND exchange_id IS NULL ORDER BY id DESC LIMIT 1", 'qm_oms_saas_order'))
        stocked_id = int(sql("SELECT order_id FROM oms_order_line WHERE company_code='QM' AND stocked_bundle_snapshot IS NOT NULL ORDER BY id DESC LIMIT 1", 'qm_oms_saas_order'))
        skus = [random['sku'] + suffix for suffix in ['', 'A', 'B', 'D']]
        inventory_sql = "SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + ','.join(quote(s) for s in skus) + ') ORDER BY sku_sn'
        before = sql(inventory_sql, 'qm_oms_saas_inventory')
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page(); page.set_default_timeout(20000)
            errors = []; page.on('pageerror', lambda error: errors.append(str(error)))

            def open_order(order_id):
                page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(order_id), wait_until='networkidle')
                page.get_by_role('tab', name='商品明细', exact=True).click()
                view = page.locator('.business-stock-detail:visible').first
                expect(view.locator('.order-sales-products')).to_be_visible()
                return view

            view = open_order(random['order_id'])
            expect(view.locator('.sales-overview')).to_contain_text('购买 2袋')
            expect(view.locator('.sales-overview')).to_contain_text('包含组件 4 件')
            table = view.locator('.sales-products-table')
            expect(table.locator(':scope > .el-table__body-wrapper > table > tbody > tr.el-table__row')).to_have_count(1)
            expect(table.get_by_role('button', name='复制平台子订单号', exact=True)).to_have_count(1)
            expect(view.locator('.sales-bundles')).to_contain_text('第1袋')
            expect(view.locator('.sales-bundles')).to_contain_text('第2袋')
            expect(view.locator('.sales-bundles')).to_contain_text('青木WMS001')
            warehouse = view.locator('.warehouse-product-details > .el-collapse-item').first
            expect(warehouse.locator(':scope > .el-collapse-item__wrap')).not_to_be_visible()
            page.screenshot(path=str(OUT / 'random-sales.png'), animations='disabled')
            warehouse.locator(':scope > div > .el-collapse-item__header').click()
            expect(warehouse).to_contain_text('涉及2袋')
            expect(warehouse).not_to_contain_text('福袋原组成 #')
            warehouse.locator('.el-table__expand-icon').first.click()
            source = warehouse.locator('.physical-sources').first
            expect(source).to_contain_text('FDR-' + random['sku'][3:] + '-2')
            source.get_by_text('来源与履约明细', exact=True).click()
            for unit in random['actual_units']:
                expect(source).to_contain_text('原袋号 #' + str(unit['unit_id']))
            page.screenshot(path=str(OUT / 'component-sources.png'), animations='disabled')
            source.get_by_role('button', name='查看发货与包裹', exact=True).first.click()
            drawer = page.locator('.shipment-detail:visible')
            expect(drawer).to_contain_text('包裹与运单')
            expect(drawer.locator('.package-card')).to_have_count(1)
            expect(drawer).to_contain_text('福袋原组件保障')
            page.screenshot(path=str(OUT / 'shipment-packages.png'), animations='disabled')

            normal = request('/order/orders/business/' + str(normal_id), token=token)['data']
            view = open_order(normal_id)
            expect(view.locator('.sales-products-table')).to_contain_text('普通商品')
            if any(line.get('gift_plan_id') for line in normal['lines']):
                view.get_by_role('button', name='查看赠品', exact=True).click()
                expect(page.get_by_role('tab', name=re.compile('^赠品'))).to_have_class(re.compile('is-active'))
            page.screenshot(path=str(OUT / 'normal-sales.png'), animations='disabled')

            view = open_order(fixed['order_id'])
            expect(view.locator('.sales-products-table')).to_contain_text('虚拟组合单品')
            view.locator('.sales-products-table > .el-table__body-wrapper .el-table__expand-icon').first.click()
            expect(view.locator('.sales-composition')).to_contain_text('固定组合组件')
            expect(view.locator('.sales-overview')).to_contain_text('套')
            page.screenshot(path=str(OUT / 'virtual-bundle.png'), animations='disabled')

            view = open_order(stocked_id)
            expect(view.locator('.sales-products-table')).to_contain_text('实物组合单品')
            expect(view.locator('.sales-overview')).not_to_contain_text('包含组件')
            view.locator('.sales-products-table > .el-table__body-wrapper .el-table__expand-icon').first.click()
            expect(view.locator('.sales-composition')).to_contain_text('成品履约')
            view.get_by_text('商品配方（组装资料）', exact=True).click()
            page.screenshot(path=str(OUT / 'stocked-bundle.png'), animations='disabled')

            view = open_order(126)
            expect(view.locator('.sales-bundles')).to_contain_text('已安排原袋退回')
            page.screenshot(path=str(OUT / 'bag-return-arrangement.png'), animations='disabled')
            view.get_by_role('button', name='查看库存预占与履约依据', exact=True).click()
            expect(page.get_by_role('tab', name='履约与发货', exact=True)).to_have_class(re.compile('is-active'))
            expect(page.get_by_role('tab', name='库存预占与依据', exact=True)).to_have_class(re.compile('is-active'))
            assert not errors, errors
            browser.close()
        assert before == sql(inventory_sql, 'qm_oms_saas_inventory'), 'Read-only UI acceptance must not mutate stock'
        report = {'random_order': random['order_id'], 'normal_order': normal_id, 'virtual_bundle_order': fixed['order_id'], 'stocked_bundle_order': stocked_id,
                  'one_sales_parent': True, 'large_platform_oid_copy': True, 'bag_set_piece_units': True, 'both_original_bags_traced': True,
                  'warehouse_sources_collapsed': True, 'shipment_packages_opened': True, 'return_arrangement_separate': True,
                  'inventory_navigation_verified': True, 'inventory_unchanged': True, 'browser_errors': errors}
        (OUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS business order sales UI:', json.dumps(report, ensure_ascii=False))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
