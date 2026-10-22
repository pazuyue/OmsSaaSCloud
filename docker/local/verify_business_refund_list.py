"""Read-only acceptance of the business refund list, filters and detail entry points."""
import copy
import json
import re
from urllib.parse import parse_qs, urlsplit
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/business-refund-list'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    returned = fixture['orders']['return']
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
        headers = {'Authorization': 'Bearer ' + token}
        endpoint = BASE + '/prod-api/order/refunds/business'

        def listed(**params):
            response = context.request.get(endpoint, params=params, headers=headers).json()
            assert response.get('code') == 200, response
            return response['data']

        def form_item(root, label):
            return root.locator('.el-form-item').filter(has=page.locator('label').filter(has_text=re.compile('^' + label + '$')))

        def list_request(params):
            return lambda response: response.request.method == 'GET' and urlsplit(response.url).path.endswith('/order/refunds/business') and all(parse_qs(urlsplit(response.url).query, keep_blank_values=True).get(key, ['']) == [value] for key, value in params.items())

        try:
            data = listed(number=returned['refund_number'])
            row = next(row for row in data['rows'] if str(row['id']) == str(returned['refund_id']))
            number = row['refund_id']
            assert fixture['sku'] in [product['sku_sn'] for product in row['application_products']]
            assert fixture['gift_sku'] not in [product['sku_sn'] for product in row['application_products']]
            assert row['platform_quantity'] == 2 and row['gift_progress']['posted_quantity'] == 1
            check('真实接口区分申请商品与关联赠品，申请数量取平台数量，赠品进度单独汇总')

            page.goto(BASE + '/oms-order/businessRefunds?number=' + str(number), wait_until='networkidle')
            root = page.locator('.business-refund-list')
            expect(root).to_be_visible()
            body = root.locator('.refund-table > .el-table__body-wrapper')
            expect(body.locator('tr.el-table__row')).to_have_count(1)
            expect(body).to_contain_text(fixture['sku'])
            expect(body).not_to_contain_text(fixture['gift_sku'])
            expect(body).to_contain_text('2 件')
            expect(body).to_contain_text(returned['oid'])
            expect(body).to_contain_text('赠品 已安排 1 件 · 实收 1 件 · 入账 1 件')
            assert not any(re.search(r'/order/refunds/business/\d+', url) or '/order/gifts/orders/' in url for url in reads), reads
            page.screenshot(path=str(OUT / 'list-desktop.png'), full_page=True)
            check('列表显示平台子单号、申请商品与数量、独立赠品进度；首屏无逐行详情或赠品请求')

            root.get_by_role('button', name='查看关联单号', exact=True).first.click()
            popover = page.locator('.el-popover:visible').filter(has=page.locator('.related-numbers'))
            expect(popover).to_contain_text(row['platform_tid'])
            expect(popover).to_contain_text(number)
            root.locator('.list-toolbar').click()
            check('关联单号按需展开，默认表格保留主退款号和平台子单号')

            with page.expect_response(list_request({'task': ''})):
                root.get_by_role('button', name='重置', exact=True).click()
            for label, value in [('待审核', 'REVIEW'), ('货物待处理', 'GOODS'), ('赠品待处理', 'GIFT'), ('异常待核对', 'ISSUE'), ('已完成', 'DONE')]:
                with page.expect_response(list_request({'task': value})) as response:
                    root.locator('.task-views .el-radio-button').filter(has_text=re.compile('^' + label + '$')).click()
                actual = response.value.json()['data']
                expected = listed(task=value, pageNum=1, pageSize=20)
                assert actual['total'] == expected['total']
                expect(body.locator('tr.el-table__row')).to_have_count(len(actual['rows']))
                if value == 'GIFT':
                    assert all(item['gift_pending'] for item in actual['rows'])
                if value == 'DONE':
                    assert all(item['state'] == 'COMPLETED' and not item['gift_pending'] for item in actual['rows'])
            check('五类待办均通过后端筛选分页，已完成排除仍有赠品待办的退款')

            root.get_by_role('button', name='更多筛选', exact=False).click()
            form_item(root, '资金状态').locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text('平台已退款', exact=True).click()
            with page.expect_response(list_request({'task': 'DONE', 'fundsState': 'SUCCESS'})) as response:
                root.locator('.list-filters .el-button--primary').click()
            assert all(item['funds_state'] == 'SUCCESS' for item in response.value.json()['data']['rows'])
            expect(form_item(root, '创建时间')).to_be_visible()
            page.screenshot(path=str(OUT / 'list-filters.png'), full_page=True)
            with page.expect_response(list_request({'task': '', 'fundsState': '', 'createdFrom': '', 'createdTo': ''})):
                root.get_by_role('button', name='重置', exact=True).click()
            check('高级筛选与待办组合查询，重置同时清空待办、资金状态及日期条件')

            if listed()['total'] > 20:
                first_numbers = body.locator('.refund-number').all_text_contents()
                with page.expect_response(list_request({'pageNum': '2'})):
                    root.locator('.el-pagination .btn-next').click()
                expect(root.locator('.el-pagination .number.active')).to_have_text('2')
                assert set(first_numbers).isdisjoint(body.locator('.refund-number').all_text_contents())
                check('翻页请求后端第二页且返回不同退款，待办切换重新从第一页查询')

            with page.expect_response(list_request({'task': 'REVIEW', 'pageNum': '1'})) as response:
                root.locator('.task-views .el-radio-button').filter(has_text=re.compile('^待审核$')).click()
            if response.value.json()['data']['rows']:
                root.locator('.el-table__fixed-right').get_by_role('button', name='核对方案', exact=True).first.click()
                dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='审核与来源', exact=True))
                expect(dialog.get_by_role('tab', name='审核与来源', exact=True)).to_have_attribute('aria-selected', 'true')
                dialog.locator('> .el-dialog__header > .el-dialog__headerbtn').click()
                expect(dialog).not_to_be_visible()
                expect(root.locator('.task-views .el-radio-button.is-active')).to_have_text('待审核')
                check('核对方案入口直达审核页，关闭详情后保留列表待办筛选')

            page.set_viewport_size({'width': 1100, 'height': 1000})
            expect(root.locator('.el-table__fixed-right')).to_be_visible()
            page.evaluate('window.scrollTo(0, 0)')
            page.screenshot(path=str(OUT / 'list-narrow.png'), full_page=True, animations='disabled')
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
            check('窄屏使用表格内横向滚动并固定操作列，页面无整体横向溢出')

            # Browser-only fixture: no business state is changed or submitted.
            simulated = copy.deepcopy(row)
            simulated.update(state='COMPLETED', goods_state='DONE', goods_action='KEEP', review_state='APPROVED', gift_pending=True)
            simulated['gift_progress']['unit_progress'] = [{'physical_unit': '件', 'return_quantity': 1, 'accepted_quantity': 0, 'posted_quantity': 0}]
            simulated['gift_progress'].update(return_quantity=1, accepted_quantity=0, posted_quantity=0, waived_quantity=0, remaining_quantity=1, unposted_quantity=0, pending_count=0, unfinished_count=1)
            def gifted(route):
                route.fulfill(json={'code': 200, 'data': {'rows': [simulated], 'total': 1}})
            pattern = '**/prod-api/order/refunds/business?**'
            context.route(pattern, gifted)
            page.set_viewport_size({'width': 1500, 'height': 1100})
            page.goto(BASE + '/oms-order/businessRefunds', wait_until='networkidle')
            expect(body).to_contain_text('本退款关联赠品待处理')
            root.locator('.el-table__fixed-right').get_by_role('button', name='处理赠品', exact=True).click()
            dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='退回与包裹', exact=True))
            expect(dialog.get_by_role('tab', name='退回与包裹', exact=True)).to_have_attribute('aria-selected', 'true')
            expect(dialog.locator('.refund-goods .el-radio-button.is-active')).to_contain_text('赠品')
            dialog.locator('> .el-dialog__header > .el-dialog__headerbtn').click()
            expect(dialog).not_to_be_visible()
            page.screenshot(path=str(OUT / 'list-gift-task.png'), full_page=True, animations='disabled')
            check('浏览器模拟：商品已完成但关联赠品未处理时，列表保留赠品待办并直达赠品退回页')
            context.unroute(pattern, gifted)

            closed = copy.deepcopy(row)
            closed.update(state='CLOSED', review_state='CLOSED', funds_state='CLOSED', goods_state='CANCELED', goods_action='RETURN', gift_pending=False)
            def ended(route):
                route.fulfill(json={'code': 200, 'data': {'rows': [closed], 'total': 1}})
            context.route(pattern, ended)
            root.locator('.list-toolbar .el-icon-refresh').locator('..').click()
            expect(body).to_contain_text('已关闭')
            expect(root.locator('.el-table__fixed-right').get_by_role('button', name='处理货物', exact=True)).to_have_count(0)
            context.unroute(pattern, ended)
            check('浏览器模拟：已关闭且货物处理已取消的退款不再显示货物待办')

            def failed(route):
                route.fulfill(json={'code': 500, 'msg': '列表验收模拟失败'})
            context.route(pattern, failed)
            root.locator('.list-toolbar .el-icon-refresh').locator('..').click()
            expect(root.locator('.el-alert')).to_contain_text('业务退款列表加载失败，请重试')
            expect(body.locator('tr.el-table__row')).to_have_count(0)
            context.unroute(pattern, failed)
            root.locator('.list-toolbar .el-icon-refresh').locator('..').click()
            expect(root.locator('.el-alert')).to_have_count(0)
            expect(body.locator('tr.el-table__row').first).to_be_visible()
            check('加载失败明确提示且清除旧行，刷新后恢复真实列表')
            assert not errors, errors
            check('浏览器无脚本错误，全程未提交退款或赠品业务变更')
        finally:
            page.screenshot(path=str(OUT / 'list-last-state.png'), full_page=True)
            (OUT / 'verification.json').write_text(json.dumps({'checks': checks, 'errors': errors}, ensure_ascii=False, indent=2), 'utf-8')
            browser.close()


if __name__ == '__main__':
    main()
