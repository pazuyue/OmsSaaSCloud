"""Read-only acceptance: discoverable actions and truthful exchange/platform progress."""
import json
import re
import urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

OUT = Path(__file__).resolve().parents[2] / 'outputs/platform-logs'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        def api(path, **query):
            suffix = '?' + urllib.parse.urlencode(query) if query else ''
            return request(path + suffix, token=token)['data']

        local = api('/order/exchanges/13')
        platform = api('/order/exchanges/10')
        assert local['source_kind'] == 'INTERNAL' and local['state'] == 'COMPLETED'
        assert local['platform_shipped_quantity'] == local['platform_packaged_quantity'] == 2
        assert local['platform_tracking_count'] == 1
        for number in [local['exchange_sn'], 'XS2239dd629a204f17b4d27cf894c9d490']:
            context = api('/channel/platform/log-center/context', number=number, numberType='AUTO')
            assert context['available'] and any(row['id'] == local['id'] and row['source_kind'] == 'INTERNAL' for row in context['rows'])
        query = dict(number=local['exchange_sn'], numberType='AUTO', category='EXCHANGE', method='tmall.exchange.consigngoods', allTime='true')
        assert not api('/channel/platform/log-center', **query)['rows'], 'No synthetic platform call for local-only fulfillment'
        assert api('/channel/platform/log-center/context', number=platform['dispute_id'])['available']
        known = api('/channel/platform/log-center', number=platform['exchange_sn'], allTime='true', category='EXCHANGE', method='tmall.exchange.get')['rows']
        assert known and all(row['interface_name'] == 'tmall.exchange.get' for row in known)

        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1520, 'height': 1050})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-order/exchanges?exchangeId=13', wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            card = drawer.locator('.platform-status')
            expect(card).to_have_attribute('data-status', 'LOCAL_ONLY')
            expect(card).to_contain_text('仅 OMS 内履约，无需换货回传')
            expect(card).to_contain_text('不向平台提交换货结果')
            page.screenshot(path=str(OUT / 'exchange-platform-progress.png'), animations='disabled')
            card.get_by_role('button', name='平台交互日志', exact=True).click()
            expect(page).to_have_url(re.compile('/oms-channel/platformLogs.*category=EXCHANGE'))
            expect(page.locator('.business-context .platform-status')).to_have_attribute('data-status', 'LOCAL_ONLY')
            expect(page.locator('.business-tabs .is-active')).to_contain_text('换货处理')
            action = page.locator('.el-form-item').filter(has=page.locator('label', has_text=re.compile('^业务动作$')))
            action.locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text('回传换出物流', exact=True).click()
            page.locator('.platform-log-center .filters .el-button--primary').click()
            expect(page.locator('.el-table__empty-text')).to_contain_text('未检索到符合条件的调用记录')
            expect(page.locator('.business-context')).to_contain_text('无需换货回传')
            page.screenshot(path=str(OUT / 'non-call-explanation.png'), animations='disabled')

            # Resetting filters and switching back to ALL must not send category=0/ALL.
            page.locator('.platform-log-center .filters').get_by_text('重置', exact=True).click()
            page.locator('.business-tabs').get_by_role('tab', name='换货处理', exact=True).click()
            page.locator('.business-tabs').get_by_role('tab', name='全部', exact=True).click()
            expect(page.locator('.business-tabs .is-active')).to_contain_text('全部')
            field = page.get_by_placeholder('输入完整订单号、换货单号或发货单号')
            field.fill(platform['exchange_sn'])
            page.locator('.platform-log-center .filters .el-button--primary').click()
            expect(page.get_by_text('本单全部时间', exact=True).locator('..')).to_have_class(re.compile('is-checked'))
            expect(page.locator('.el-table__body-wrapper')).to_contain_text('查询换货申请')
            expect(page.locator('.business-context')).to_contain_text(platform['exchange_sn'])
            page.screenshot(path=str(OUT / 'business-action-search.png'), animations='disabled')
            assert not errors, errors
            browser.close()

        after = api('/order/exchanges/13')
        for field in ['state', 'control_version', 'platform_operation', 'platform_status', 'platform_shipped_quantity', 'platform_packaged_quantity']:
            assert after[field] == local[field], field
        (OUT / 'progress-acceptance.json').write_text(json.dumps({
            'local_exchange': local['exchange_sn'], 'platform_exchange': platform['exchange_sn'],
            'automatic_number_lookup': True, 'shipment_to_exchange_context': True,
            'chinese_action_filter': True, 'local_completion_not_platform_success': True,
            'read_only_business_facts_unchanged': True, 'browser_errors': []
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS read-only platform progress, automatic number lookup, Chinese actions and browser acceptance')
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
