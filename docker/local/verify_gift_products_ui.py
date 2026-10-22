"""Read-only final UI/API checks against the completed owned composite-gift case."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote

OUT = Path(__file__).resolve().parents[2] / 'outputs/gift-products'


def main():
    c = json.loads((OUT / 'case.json').read_text('utf-8'))
    token, errors = login(), []

    def api(path, body=None):
        result = request(path, payload=body, token=token, method='POST' if body is not None else 'GET')
        assert result['code'] == 200, result.get('msg')
        return result.get('data', result)

    scope = "company_code='QM' AND sku_sn IN (" + ','.join(quote(c[k]) for k in ['sku', 'b', 'd', 'kit', 'stocked', 'random', 'preset']) + ')'
    def balances():
        return sql('SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,version FROM oms_inventory WHERE ' + scope + ' ORDER BY sku_sn', 'qm_oms_saas_inventory')

    try:
        before = balances()
        o = api('/order/orders/business/' + str(c['order_id']))
        assert o['bundle_sets'] == o['lucky_bag_quantity'] == o['bundle_count'] == 0
        assert o['status'] == 'VOIDED'
        g = api('/order/gifts/orders/' + str(c['order_id']))
        assert g['block_reason'] == '' and g['order_status'] == 'VOIDED'
        assert all(line['gift_state'] == 'CANCELED' and line['eligible_quantity'] == 0 for line in g['lines'])
        for kind, expected in [('NORMAL', 1), ('BUNDLE', 2), ('LUCKY_BAG', 2)]:
            result = api('/goods/info/list?pageSize=1', {'goodsSn': c['prefix'], 'productType': kind, 'isGift': 1})
            assert result['total'] == expected and all(row['productType'] == kind and row['isGift'] == 1 for row in result['rows'])
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1500, 'height': 1050})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page(); page.set_default_timeout(30000)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-goods/info?goodsSn=' + c['prefix'], wait_until='networkidle')
            table = page.locator('.goods-workspace .el-table__body-wrapper')
            expect(table.locator('tbody tr')).to_have_count(7)
            assert table.evaluate('element=>element.scrollWidth<=element.clientWidth+2'), 'Default table should fit a normal desktop'
            page.screenshot(path=str(OUT / 'goods-list.png'), full_page=True)
            page.goto(BASE + '/oms-goods/info?skuSn=' + c['random'], wait_until='networkidle')
            page.locator('.goods-row-actions .el-dropdown-selfdefine:visible').click()
            page.locator('.el-dropdown-menu:visible').get_by_text('修改', exact=True).click()
            dialog = page.get_by_role('dialog', name='修改商品资料', exact=True)
            expect(dialog.get_by_role('radio', name='福袋', exact=True)).to_be_checked()
            expect(dialog.get_by_role('switch')).to_be_checked()
            dialog.get_by_role('radio', name='固定套装', exact=True).check()
            expect(dialog.get_by_role('radio', name='福袋', exact=True)).not_to_be_checked()
            expect(dialog.get_by_role('switch')).to_be_checked()
            dialog.get_by_role('radio', name='福袋', exact=True).check()
            page.screenshot(path=str(OUT / 'goods-form.png'), full_page=True)
            dialog.get_by_role('button', name='取消', exact=True).click()
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            expect(drawer.locator('.order-identity')).not_to_contain_text('福袋 2 袋')
            expect(drawer.locator('.order-sales-products')).to_contain_text('购买 1件')
            drawer.get_by_role('tab', name='赠品', exact=True).click()
            expect(drawer.locator('.gift-parent')).to_have_count(5)
            expect(drawer.locator('.gift-contents')).to_contain_text('已终止赠送')
            page.screenshot(path=str(OUT / 'order-gifts-final.png'), full_page=True)
            bag = drawer.locator('.gift-parent').nth(3)
            bag.get_by_text('查看每袋实际内容', exact=True).click()
            bag.scroll_into_view_if_needed()
            expect(bag).to_contain_text('原袋 #')
            page.screenshot(path=str(OUT / 'order-gift-contents.png'), full_page=True)
            page.set_viewport_size({'width': 1120, 'height': 1000})
            page.goto(BASE + '/oms-goods/info?goodsSn=' + c['prefix'] + '&productType=LUCKY_BAG&isGift=1', wait_until='networkidle')
            expect(page.locator('.goods-workspace .el-table__body-wrapper tbody tr')).to_have_count(2)
            page.screenshot(path=str(OUT / 'goods-filter-narrow.png'), full_page=True)
            assert not errors, errors
            browser.close()
        assert balances() == before, 'Read-only acceptance changed stock'
        result = {'checks': ['形态与赠品资格组合筛选在分页前生效', '默认桌面表格无横向溢出，次要操作收进更多', '形态三选一且不改变赠品开关',
                            '购买汇总排除赠品套装和福袋', '原袋组成保留，作废赠品不再报缺货待办', '全部库存及版本保持不变'], 'browser_errors': errors}
        (OUT / 'ui-final.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
