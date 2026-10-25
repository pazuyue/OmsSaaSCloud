"""Read-only acceptance of the configuration center. Saves use browser-only fixtures."""
import hashlib
import json
import re
import sys
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from test_multi_virtual_shipping import sql as execute
from verify_merged_composite_gift_refunds import Acceptance
from verify_goods_workspace_ui import BASE
from verify_queue_management import wait_health

OUT = Path(__file__).resolve().parents[2] / 'outputs/business-settings'
PATH = '/oms-order/businessSettings'


def snapshot(a):
    tables = ['oms_order_pull_config', 'oms_order_push_source', 'oms_order_review_config', 'oms_order_allocation_config', 'oms_order_carrier_config', 'oms_order_platform_shipping_config', 'oms_refund_config', 'oms_refund_flow_policy', 'oms_refund_api_cursor', 'oms_refund_parcel_config', 'oms_exchange_config']
    values = [execute('SELECT * FROM ' + table + ' ORDER BY company_code,channel_id' if table != 'oms_order_push_source' else 'SELECT * FROM oms_order_push_source ORDER BY company_code,source_key', 'qm_oms_saas_order') for table in tables]
    values.append(execute('SELECT * FROM channel_refund_access ORDER BY company_code,channel_id', 'qm_oms_saas_channel'))
    return {'configuration_sha256': hashlib.sha256(json.dumps(values).encode()).hexdigest(), 'stock': a.snapshot(), 'preceding_stock': a.protected_order()}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    a = Acceptance()
    if '--snapshot' in sys.argv:
        (OUT / 'protected-before.json').write_text(json.dumps(snapshot(a), ensure_ascii=False, indent=2), 'utf-8')
        print('Saved all order/after-sales configuration and protected inventory fingerprints')
        return
    wait_health('oms-local-order')
    before = json.loads((OUT / 'protected-before.json').read_text('utf-8'))
    assert snapshot(a) == before
    timing = []
    for _ in range(3):
        start = time.perf_counter()
        listing = a.api('/order/business-settings?pageSize=20&pageNum=1')
        timing.append(round((time.perf_counter() - start) * 1000, 1))
    assert listing['rows'] and len(listing['rows']) <= 20
    assert not any(word in json.dumps(listing).lower() for word in ['token_cipher', 'password_cipher', 'session_cipher', 'secret_cipher'])
    channel = str(a.c['channel_id'])
    detail = a.api('/order/business-settings/shops/' + channel)
    refund_detail = a.api('/order/refunds/settings/' + channel)
    checks = ['paged local overview exposes no credentials']
    errors, writes, addresses, mocked, requests = [], [], [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={'width': 1720, 'height': 1120})
        ctx.add_cookies([{'name': 'Admin-Token', 'value': a.token, 'url': BASE}])
        page = ctx.new_page(); page.set_default_timeout(30000)
        page.on('pageerror', lambda error: errors.append(str(error)))
        def observe(request):
            if '/prod-api/' not in request.url:
                return
            requests.append(request.url)
            if '/addresses' in request.url:
                addresses.append(request.url)
            if request.method not in ['GET', 'HEAD', 'OPTIONS']:
                writes.append(request.url)
        page.on('request', observe)
        page.goto(BASE + PATH, wait_until='networkidle')
        expect(page.locator('.business-settings h2')).to_have_text('业务设置')
        expect(page.locator('.business-settings .summary-cards')).to_be_visible()
        page.screenshot(path=str(OUT / 'overview.png'), full_page=True)
        checks.append('one business settings entry and independent task entry')
        page.goto(BASE + PATH + '?channelId=' + channel, wait_until='networkidle')
        nav = page.locator('.section-nav')
        expect(nav.locator('button')).to_have_count(16)
        summary_reads = sum('/order/business-settings/shops/' in url for url in requests)
        labels = ['平台授权与能力', '订单接入', '退款接入', '换货接入', '转单与预占', '审单规则', '分仓规则', '适用赠品策略', '快递匹配', '仓库处理方式', '平台发货回传', '退款自动处理', '退货与入库', '换货处理', '执行状态与检查']
        for label in labels:
            nav.get_by_role('button', name=label, exact=True).click()
            expect(page.locator('.settings-content > .section-heading h3')).to_have_text(label)
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.locator('.business-settings > .el-alert--error')).to_have_count(0)
            if label == '订单接入':
                expect(page.locator('.settings-content')).to_contain_text('转单与预占')
            if label == '平台发货回传':
                expect(page.locator('.settings-content')).to_contain_text('模拟')
            if label == '退货与入库':
                page.screenshot(path=str(OUT / 'returns.png'), full_page=True)
        checks.append('all sixteen store sections render inline without nested dialogs or automatic writes')
        assert sum('/order/business-settings/shops/' in url for url in requests) == summary_reads, 'switching sections must reuse the shop summary'
        checks.append('section switching reuses shop summary and loads only the requested editor')
        nav.get_by_role('button', name='退款自动处理', exact=True).click()
        form = page.locator('.automation-form')
        form.locator('.el-form-item').filter(has_text='启用自动处理').locator('.el-switch').click()
        expect(page.locator('.settings-heading')).to_contain_text('有未保存修改')
        nav.get_by_role('button', name='订单接入', exact=True).click()
        page.locator('.el-message-box:visible').get_by_role('button', name='继续编辑', exact=True).click()
        expect(form).to_be_visible()
        nav.get_by_role('button', name='订单接入', exact=True).click()
        page.locator('.el-message-box:visible').get_by_role('button', name='放弃修改', exact=True).click()
        expect(page.locator('.settings-content > .section-heading h3')).to_have_text('订单接入')
        checks.append('section navigation preserves changes on cancel and discards only with confirmation')
        # Verify the extracted ordinary order editor reports edits to its parent as well.
        page.locator('.settings-content .el-form-item').filter(has_text='自动拉单').first.locator('.el-switch').click()
        expect(page.locator('.settings-heading')).to_contain_text('有未保存修改')
        page.locator('.settings-heading').scroll_into_view_if_needed()
        page.screenshot(path=str(OUT / 'ordinary-editor.png'), full_page=True)
        page.locator('.shop-context .el-button').click()
        page.locator('.el-message-box:visible').get_by_role('button', name='继续编辑', exact=True).click()
        expect(page.locator('.settings-content > .section-heading h3')).to_have_text('订单接入')
        nav.get_by_role('button', name='店铺概览', exact=True).click()
        page.locator('.el-message-box:visible').get_by_role('button', name='放弃修改', exact=True).click()
        expect(page.locator('.el-message-box:visible')).to_have_count(0)
        expect(page.locator('.settings-heading')).not_to_contain_text('有未保存修改')
        page.screenshot(path=str(OUT / 'shop-overview.png'), full_page=True)
        checks.append('ordinary order editor participates in store and section navigation guards')
        page.goto(BASE + '/oms-order/settings?channelId=' + channel + '&tab=review', wait_until='networkidle')
        expect(page.locator('.settings-content > .section-heading h3')).to_have_text('审单规则')
        page.goto(BASE + '/oms-order/management?channelId=' + channel + '&tab=tasks&kind=REFUND', wait_until='networkidle')
        expect(page.locator('.order-sync-tasks h2')).to_have_text('同步任务')
        expect(page.locator('.order-sync-tasks')).to_contain_text('任务编号')
        checks.append('old entry routes preserve shop and task context through redirects')
        for scope in ['sources', 'carriers-base']:
            page.goto(BASE + PATH + '?scope=' + scope, wait_until='networkidle')
            expect(page.locator('.business-settings .el-table')).to_be_visible()
        checks.append('company sources and carrier warehouse coverage have independent shared-data sections')
        assert not writes, writes
        assert not addresses, addresses
        # Saving in the browser is explicitly intercepted: actual settings remain unchanged.
        fixture = json.loads(json.dumps(refund_detail))
        page.route('**/prod-api/order/refunds/settings/' + channel, lambda route: route.fulfill(json={'code': 200, 'data': fixture}))
        def save_mock(route):
            body = route.request.post_data_json
            assert 'api_sync_enabled' not in body and 'api_sync' not in body
            mocked.append(body); fixture['flow'].update(body)
            route.fulfill(json={'code': 200})
        page.route('**/prod-api/order/refunds/flow-configs/' + channel, save_mock)
        page.goto(BASE + PATH + '?channelId=' + channel + '&section=refund-automation', wait_until='networkidle')
        page.locator('.automation-form .el-form-item').filter(has_text='启用自动处理').locator('.el-switch').click()
        page.get_by_role('button', name='保存自动处理', exact=True).click()
        expect(page.locator('.settings-heading')).not_to_contain_text('有未保存修改')
        assert len(mocked) == 1
        checks.append('embedded save refreshes its revision and leaves intake configuration separate')
        fixture['shop_enabled'] = 0
        disabled = {**detail, 'shop_enabled': 0}
        page.route('**/prod-api/order/business-settings/shops/' + channel, lambda route: route.fulfill(json={'code': 200, 'data': disabled}))
        page.reload(wait_until='networkidle')
        expect(page.locator('.shop-context')).to_contain_text('已停用，仅供查看')
        expect(page.get_by_role('button', name='保存自动处理', exact=True)).to_be_disabled()
        checks.append('disabled shop is visible but cannot save automation')
        def restricted_info(route):
            response = route.fetch(); info = response.json()
            info['permissions'] = ['order:refund:config']
            route.fulfill(response=response, json=info)
        page.route('**/prod-api/system/user/getInfo', restricted_info)
        page.goto(BASE + PATH + '?channelId=' + channel + '&section=overview', wait_until='networkidle')
        visible_sections = page.locator('.section-nav button').all_text_contents()
        assert '退款接入' in visible_sections and '转单与预占' not in visible_sections and '平台发货回传' not in visible_sections
        page.goto(BASE + PATH + '?channelId=' + channel + '&section=conversion', wait_until='networkidle')
        expect(page.locator('.business-settings > .el-alert--error')).to_contain_text('没有这项配置的访问权限')
        checks.append('restricted permission fixture hides unauthorized sections and blocks direct section routes')
        assert not errors, errors
        browser.close()
    assert snapshot(a) == before, 'configuration or protected stock changed'
    report = {'checks': checks, 'overview_20_ms': timing, 'browser_errors': errors, 'mocked_saves': len(mocked), 'real_configuration_writes': 0, 'vendor_address_requests': 0, 'configuration_unchanged': True, 'protected_stock_unchanged': True}
    (OUT / 'acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print('PASS business settings:', len(checks), 'checks; configuration and protected stock unchanged')


if __name__ == '__main__':
    main()
