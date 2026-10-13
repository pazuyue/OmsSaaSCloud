"""Current-order issue acceptance; stock is read-only, acknowledgements use intercepted fixtures."""
import copy
import json
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

SN = 'SOb3c545e6286742cf8d176b320414f9be'
SKU = 'ACCEPT_OUT_20261012164334'
DETAIL = '/order/orders/business/29'
OUTPUT = Path(__file__).resolve().parents[2] / 'outputs/order-issues'


def signature(order):
    return {key: order.get(key) for key in ['inventory_status', 'review_status', 'allocation_status', 'allocation_quantity', 'reserved_quantity', 'shipped_quantity', 'control_version']} | {'lines': [{key: line.get(key) for key in ['sku_sn', 'platform_oid', 'quantity', 'reserved_quantity', 'shipped_quantity', 'stock_sources']} for line in order['lines']]}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    token = login()
    errors, blocked, captured = [], [], []
    try:
        before = request(DETAIL, token=token)['data']
        report = request(DETAIL + '/issues/recheck', token=token, method='POST')['data']
        issue = next(issue for issue in report['issues'] if issue['sku_sn'] == SKU and issue['state'] != 'RESOLVED')
        assert issue['category'] == 'QUOTA' and issue['scope'] == 'CHANNEL' and issue['blocking'] == 0
        assert issue['evidence']['public_reserved'] == 2 and issue['evidence']['excess_quantity'] == 2 and issue['evidence']['pending'] == 0
        assert issue['evidence']['basis'] == 'CURRENT_BALANCE' and not issue['evidence']['last_decision']
        selected = request('/order/orders/business/list?tid=' + SN + '&issueCategory=QUOTA&issueScope=CHANNEL&issueBlocking=0&issueState=OPEN', token=token)
        assert selected['total'] == 1 and selected['rows'][0]['id'] == 29
        empty = request('/order/orders/business/list?tid=' + SN + '&issueCategory=SHORTAGE', token=token)
        assert empty['total'] == 0

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))

            def restrict(route):
                path = urlparse(route.request.url).path
                if route.request.method == 'GET' or (route.request.method == 'POST' and path == '/prod-api' + DETAIL + '/issues/recheck'):
                    route.continue_()
                else:
                    blocked.append(path)
                    route.abort()

            page.route('**/prod-api/**', restrict)
            page.goto(BASE + '/oms-order/business?view=business', wait_until='networkidle')
            page.get_by_placeholder('输入完整 OMS 或平台单号').fill(SN)
            page.locator('.order-filters').get_by_role('button', name='查询', exact=False).click()
            table = page.locator('.order-workspace > .el-table > .el-table__body-wrapper')
            expect(table.locator('tr')).to_have_count(1)
            expect(table.locator('.order-issue-badges .issue-tag').first).to_have_text('额度不足')
            expect(table.locator('.stock-progress')).to_contain_text('公共补占 6 件')
            table.locator('.order-issue-badges .issue-tag').first.scroll_into_view_if_needed()
            page.screenshot(path=str(OUTPUT / 'current-order-list.png'))
            table.locator('.order-issue-badges .issue-tag').first.click()
            dialog = page.locator('.order-issues-dialog .el-dialog:visible')
            current = dialog.locator('.issue-card').filter(has=page.locator('strong', has_text=SKU))
            expect(current).to_contain_text('提醒，不阻断当前预占结果')
            expect(current).to_contain_text('超额度预占 2 件')
            expect(current).not_to_contain_text('库存不足')
            current.locator('.el-collapse-item__header').click()
            expect(current).to_contain_text('未保存预占时快照')
            page.wait_for_timeout(350)
            page.screenshot(path=str(OUTPUT / 'quota-warning-evidence.png'))
            dialog.get_by_role('tab', name='历史与处理记录', exact=True).click()
            expect(dialog).to_contain_text('问题发生')
            dialog.get_by_role('button', name='关闭', exact=True).click()

            page.goto(BASE + '/oms-order/business?view=business&orderId=29', wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            goods = drawer.locator('.business-stock-detail')
            row = goods.locator('.el-table__body-wrapper').first.locator('tr').filter(has_text=SKU)
            expect(row).to_contain_text('渠道锁库 0 件 + 公共补占 2 件')
            expect(row).to_contain_text('额度不足')
            expect(row).to_contain_text('逻辑预占完成')
            row.locator('.source-breakdown').scroll_into_view_if_needed()
            page.screenshot(path=str(OUTPUT / 'sku-source-and-issue.png'))
            drawer.locator('.order-detail-footer').get_by_role('button', name='关闭', exact=True).click()
            page.get_by_role('button', name='高级筛选', exact=False).click()
            page.get_by_placeholder('全部问题类型').click()
            page.locator('.el-select-dropdown:visible').get_by_text('额度不足', exact=True).click()
            page.get_by_placeholder('全部层级').click()
            page.locator('.el-select-dropdown:visible').get_by_text('渠道', exact=True).click()
            page.get_by_placeholder('全部影响').click()
            page.locator('.el-select-dropdown:visible').get_by_text('提醒', exact=True).click()
            page.get_by_placeholder('当前未解除').click()
            page.locator('.el-select-dropdown:visible').get_by_text('待处理', exact=True).click()
            page.locator('.order-filters').get_by_role('button', name='查询', exact=False).click()
            expect(table.locator('tr')).to_have_count(1)
            page.screenshot(path=str(OUTPUT / 'issue-filters.png'))

            # Acknowledging a blocking issue remains blocking. These writes never reach the server.
            fixture = copy.deepcopy(report)
            sample = copy.deepcopy(issue)
            sample.update(id=9900911, category='LEDGER', reason_code='SOURCE_MISSING', blocking=1, severity='ERROR', state='OPEN', message='预占来源缺失，请核对台账')
            fixture['issues'] = [sample]
            fixture['events'] = [dict(issue_id=9900911, action='OPENED', actor='验收任务', message=sample['message'], evidence=sample['evidence'], create_time='2026-10-13 17:00:00')]

            def fixture_report(route):
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 200, 'data': fixture}, ensure_ascii=False))

            def acknowledge(route):
                assert route.request.method == 'POST'
                captured.append(route.request.post_data_json)
                assert captured[-1]['note'] == '验收：已知悉，等待修复来源'
                sample.update(state='ACKNOWLEDGED', acknowledged_by='验收人员', acknowledgement=captured[-1]['note'], acknowledged_at='2026-10-13 17:10:00')
                fixture['events'].insert(0, dict(issue_id=9900911, action='ACKNOWLEDGED', actor='验收人员', message=captured[-1]['note'], evidence=sample['evidence'], create_time='2026-10-13 17:10:00'))
                route.fulfill(status=200, content_type='application/json', body=json.dumps({'code': 200}))

            page.route('**/prod-api' + DETAIL + '/issues', fixture_report)
            page.route('**/prod-api' + DETAIL + '/issues/9900911/acknowledge', acknowledge)
            table.locator('.order-issue-badges .issue-tag').first.click()
            expect(dialog).to_contain_text('台账异常')
            dialog.get_by_role('button', name='确认已知悉', exact=True).click()
            prompt = page.locator('.el-message-box:visible')
            prompt.locator('input').fill('验收：已知悉，等待修复来源')
            prompt.get_by_role('button', name='确定', exact=True).click()
            expect(dialog).to_contain_text('已确认')
            expect(dialog).to_contain_text('阻断相关处理')
            assert sample['blocking'] == 1 and len(captured) == 1
            dialog.get_by_role('tab', name='历史与处理记录', exact=True).click()
            expect(dialog).to_contain_text('确认知悉')
            assert not errors and not blocked, (errors, blocked)
            browser.close()

        after = request(DETAIL, token=token)['data']
        assert signature(before) == signature(after), 'Problem reconciliation must not reserve, allocate or ship stock'
        OUTPUT.joinpath('verification.json').write_text(json.dumps({'order': SN, 'sku': SKU, 'category': 'QUOTA', 'public_reserved': 2, 'excess_quantity': 2, 'pending_quantity': 0, 'reminder_only': True, 'no_fake_historical_snapshot': True, 'four_filters': True, 'source_breakdown': True, 'acknowledgement_fixture_only': True, 'blocking_preserved_after_ack': True, 'stock_and_workflow_unchanged': True}, ensure_ascii=False, indent=2), encoding='utf8')
        print('PASS current-order quota reminder, source breakdown, evidence/history, filters and fixture acknowledgement; stock unchanged')
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
