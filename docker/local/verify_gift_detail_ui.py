"""Read-only browser acceptance for order/refund detail hierarchy and request reuse."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-detail'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    fixtures = fixture['orders']
    token = login()
    checks, errors, reads = [], [], []

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={'width': 1500, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('request', lambda request: reads.append(request.url) if request.method == 'GET' else None)
        try:
            order = fixtures['pending']
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(order['id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            expect(drawer).to_contain_text(order['order_sn'])
            expect(drawer.get_by_role('tab', name='商品明细', exact=True)).to_have_attribute('aria-selected', 'true')
            expect(drawer).to_contain_text('不计入购买数量与实付金额')
            product_tables = drawer.locator('.business-stock-detail > .el-table')
            expect(product_tables.first.get_by_text('平台子订单号', exact=True)).to_be_visible()
            expect(product_tables.first).to_contain_text(order['oid'])
            expect(product_tables.nth(1).get_by_text('无平台子单', exact=True)).to_be_visible()
            assert drawer.get_by_role('tab', name='商品明细', exact=True).bounding_box()['y'] < 550
            gift_path = '/order/gifts/orders/' + str(order['id'])
            assert not any(gift_path in url for url in reads)
            drawer.screenshot(path=str(OUT / 'order-products.png'))
            check('订单默认商品明细，购买与赠品分组；首屏直接可见页签，赠品报告尚未请求')
            drawer.get_by_role('button', name='查看赠品', exact=True).click()
            expect(drawer).to_contain_text('售后申请尚未确认')
            assert page.url.endswith('orderId=' + str(order['id']))
            first_reads = sum(gift_path in url for url in reads)
            assert first_reads == 1, first_reads
            drawer.get_by_role('tab', name='订单资料', exact=True).click()
            drawer.get_by_role('tab', name='赠品', exact=False).first.click()
            page.wait_for_timeout(250)
            assert sum(gift_path in url for url in reads) == first_reads
            drawer.screenshot(path=str(OUT / 'order-gifts.png'))
            check('当前订单内切换赠品，待确认资格明确提示，往返页签复用一次报告请求')
            drawer.get_by_role('tab', name='履约与发货', exact=True).click()
            expect(drawer.get_by_role('tab', name='发货单', exact=True)).to_be_visible()
            drawer.get_by_role('tab', name='库存预占与依据', exact=True).click()
            expect(drawer).to_contain_text('逻辑预占状态')
            drawer.get_by_role('tab', name='退款与换货', exact=True).click()
            expect(drawer.get_by_role('tab', name='退款记录', exact=True)).to_be_visible()
            drawer.get_by_role('tab', name='审核与异常', exact=True).click()
            expect(drawer).to_contain_text('审核与履约')
            check('履约、库存、退款换货及审核入口可访问，原操作分区保留')

            returned = fixtures['return']
            reads.clear()
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(returned['refund_id']), wait_until='networkidle')
            dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='本次处理', exact=True))
            expect(dialog).to_contain_text('本退款关联赠品')
            expect(dialog).to_contain_text('已退回入账')
            expect(dialog).to_contain_text('平台已退款')
            application = dialog.locator('.refund-application-goods')
            bound = dialog.locator('.refund-bound-gifts')
            expect(application).to_contain_text(fixture['sku'])
            expect(application).not_to_contain_text(fixture['gift_sku'])
            expect(application).to_contain_text(returned['oid'])
            expect(application.locator('.el-table__body-wrapper').first.get_by_text('2 件', exact=True)).to_be_visible()
            expect(bound).to_contain_text(fixture['gift_sku'])
            gift_row = bound.locator('.el-table__body-wrapper').first.locator('tr.el-table__row')
            expect(gift_row).to_have_count(1)
            expect(gift_row.locator('td').nth(2)).to_have_text('1 件')
            expect(gift_row.locator('td').nth(4)).to_have_text('1 件')
            expect(dialog.locator('.order-gift-context > .el-collapse-item > .el-collapse-item__wrap')).not_to_be_visible()
            goods_path = '/order/refunds/business/' + str(returned['refund_id']) + '/goods'
            parcel_path = '/order/refunds/business/' + str(returned['refund_id']) + '/parcels'
            assert not any(goods_path in url or parcel_path in url for url in reads), reads
            expect(dialog.get_by_role('heading', name='退款申请商品', exact=True)).to_be_in_viewport()
            expect(dialog.locator('.current-action')).to_be_in_viewport()
            dialog.screenshot(path=str(OUT / 'refund-current.png'))
            bound.get_by_role('button', name='判定依据', exact=True).click()
            expect(bound).to_contain_text('累计判定包含其他售后影响')
            dialog.screenshot(path=str(OUT / 'refund-gifts.png'))
            bound.get_by_role('button', name='判定依据', exact=True).click()
            check('默认页分别展示申请商品2件和本退款赠品安排1件，整单累计判定折叠，当前操作一屏可见')
            dialog.get_by_role('button', name='查看退回进度', exact=True).click()
            expect(dialog.get_by_role('tab', name='退回与包裹', exact=True)).to_have_attribute('aria-selected', 'true')
            expect(dialog).to_contain_text('本退款退回与包裹')
            expect(dialog).to_contain_text('赠品（1）')
            expect(dialog).to_contain_text('已入账')
            returns = dialog.locator('.refund-goods')
            expect(returns.locator('.plan-expanded')).not_to_be_visible()
            returns.get_by_role('button', name='查看并处理', exact=True).click()
            expect(returns.locator('.plan-expanded')).to_be_visible()
            page.wait_for_load_state('networkidle')
            goods_reads = sum(goods_path in url and '/plans/' not in url for url in reads)
            assert goods_reads == 1, goods_reads
            dialog.screenshot(path=str(OUT / 'refund-returns.png'))
            dialog.get_by_role('tab', name='本次处理', exact=True).click()
            dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
            page.wait_for_timeout(250)
            assert sum(goods_path in url for url in reads) == goods_reads
            check('赠品入口直达赠品退回筛选，安排默认表格展示、展开处理，重复切换不重复请求')
            dialog.get_by_role('tab', name='审核与来源', exact=True).click()
            expect(dialog).to_contain_text('本次采用方案')
            dialog.get_by_role('tab', name='处理记录', exact=True).click()
            expect(dialog).to_contain_text('操作人')
            dialog.get_by_role('tab', name='本次处理', exact=True).click()
            page.set_viewport_size({'width': 900, 'height': 1100})
            expect(dialog.get_by_role('tab', name='本次处理', exact=True)).to_be_visible()
            dialog.screenshot(path=str(OUT / 'refund-narrow.png'))
            check('审核来源、处理记录及窄屏可用')

            page.set_viewport_size({'width': 1500, 'height': 1100})
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(fixtures['pending']['refund_id']), wait_until='networkidle')
            dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='本次处理', exact=True))
            expect(dialog.get_by_text('本退款暂无关联赠品', exact=True)).to_be_visible()
            expect(dialog.locator('.refund-bound-gifts .el-table')).to_have_count(0)
            expect(dialog.locator('.current-action')).not_to_contain_text('原订单赠品')
            original = dialog.locator('.order-gift-context > .el-collapse-item')
            expect(original.locator('> .el-collapse-item__wrap')).not_to_be_visible()
            dialog.screenshot(path=str(OUT / 'refund-unbound.png'))
            pending_gift_path = '/order/gifts/orders/' + str(fixtures['pending']['id'])
            before_expand = sum(pending_gift_path in url for url in reads)
            original.locator('.el-collapse-item__header').first.click()
            expect(original.get_by_text('售后申请尚未确认', exact=False)).to_be_visible()
            assert sum(pending_gift_path in url for url in reads) == before_expand
            check('未绑定的原订单赠品保持折叠，不冒充退款申请商品或本单关联赠品，不占用本单主要待办')

            headers = {'Authorization': 'Bearer ' + token}
            business_data = context.request.get(BASE + '/prod-api/order/refunds/business/' + str(returned['refund_id']), headers=headers).json()['data']
            page.goto(BASE + '/oms-order/platformRefunds?id=' + str(business_data['platform_refund_id']), wait_until='networkidle')
            platform_dialog = page.locator('.el-dialog:visible')
            expect(platform_dialog).to_contain_text('平台退款详情')
            expect(platform_dialog).to_contain_text('推送原文与来源版本')
            expect(platform_dialog.locator('.refund-bound-gifts')).to_have_count(0)
            check('平台退款详情的来源及转单入口仍可查看')

            # Browser-only responses exercise the independent gift workflow; no mutation is submitted.
            gift_data = context.request.get(BASE + '/prod-api/order/gifts/orders/' + str(returned['id']), headers=headers).json()['data']
            goods_data = context.request.get(BASE + '/prod-api/order/refunds/business/' + str(returned['refund_id']) + '/goods', headers=headers).json()['data']
            business_data.update(goods_state='DONE', state='COMPLETED')
            document = {'id': 900001, 'generation': 1, 'return_sn': 'THK-UI-ONLY', 'quantity': 1, 'accepted_quantity': 0, 'posted_quantity': 0, 'dispatch_state': 'PENDING', 'cancel_state': 'NONE', 'receipt_state': 'WAITING'}
            actual = dict(document, documents=[document], reviews=[], lines=[], pending_review_count=0)
            gift_plan = next(plan for plan in goods_data['plans'] if plan.get('gift_plan_id'))
            gift_plan.update(quantity=1, accepted_quantity=0, posted_quantity=0, waived_quantity=0, state='ACTIVE', actual=actual, parcel_state='WAIT_LOGISTICS', parcel_resolution=None)
            goods_data['plans'] = [gift_plan]
            for line in gift_data['lines']:
                for plan in line.get('return_plans', []):
                    if str(plan['refund_id']) == str(returned['refund_id']):
                        plan.update(quantity=1, accepted_quantity=0, posted_quantity=0, waived_quantity=0, state='ACTIVE', actual=actual)
            def mocked(data):
                return lambda route: route.fulfill(json={'code': 200, 'data': data})
            context.route('**/prod-api/order/refunds/business/' + str(returned['refund_id']), mocked(business_data))
            context.route('**/prod-api/order/refunds/business/' + str(returned['refund_id']) + '/goods', mocked(goods_data))
            context.route('**/prod-api/order/gifts/orders/' + str(returned['id']), mocked(gift_data))
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(returned['refund_id']), wait_until='networkidle')
            dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='本次处理', exact=True))
            expect(dialog.locator('.current-action')).to_contain_text('本退款关联赠品待处理')
            dialog.locator('.refund-footer').get_by_role('button', name='查看赠品退回', exact=True).click()
            dialog.locator('.refund-goods').get_by_role('button', name='查看并处理', exact=True).click()
            expect(dialog.get_by_role('button', name='下发本次退回单', exact=True)).to_be_enabled()
            check('浏览器模拟：购买商品处理完成后，已绑定赠品仍有独立待办及下发入口，未提交业务操作')
            assert not errors, errors
            check('全部页面无浏览器脚本错误')
        finally:
            (OUT / 'verification.json').write_text(json.dumps({'checks': checks, 'errors': errors}, ensure_ascii=False, indent=2), 'utf-8')
            browser.close()


if __name__ == '__main__':
    main()
