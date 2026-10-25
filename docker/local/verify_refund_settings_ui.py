"""Read-only local acceptance. Save/disabled-shop cases use browser-only API fixtures."""
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

OUT = Path(__file__).resolve().parents[2] / 'outputs/refund-settings'
PATH = '/oms-order/afterSaleRules/settings'


def snapshot(a):
    queries = [
        "SELECT company_code,channel_id,enabled,auto_convert,source_key,seller_nick FROM oms_refund_config ORDER BY company_code,channel_id",
        "SELECT company_code,channel_id,enabled,CAST(policy_json AS CHAR) FROM oms_refund_flow_policy ORDER BY company_code,channel_id",
        "SELECT company_code,channel_id,enabled FROM oms_refund_api_cursor ORDER BY company_code,channel_id",
        "SELECT company_code,channel_id,CAST(policy AS CHAR) FROM oms_refund_parcel_config ORDER BY company_code,channel_id",
    ]
    values = [execute(q, 'qm_oms_saas_order') for q in queries]
    values.append(execute("SELECT company_code,channel_id,read_enabled,write_enabled,payment_enabled,ignore_code FROM channel_refund_access ORDER BY company_code,channel_id", 'qm_oms_saas_channel'))
    return {'settings_sha256': hashlib.sha256(json.dumps(values).encode()).hexdigest(), 'stock': a.snapshot(), 'preceding_stock': a.protected_order()}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    a = Acceptance()
    if '--snapshot' in sys.argv:
        (OUT / 'protected-before.json').write_text(json.dumps(snapshot(a), ensure_ascii=False, indent=2), 'utf-8')
        print('Saved configuration and protected inventory fingerprints; no settings changed')
        return
    for service in ['oms-local-channel', 'oms-local-order']:
        wait_health(service)
    before = json.loads((OUT / 'protected-before.json').read_text('utf-8'))
    assert snapshot(a) == before
    timing = []
    for _ in range(3):
        start = time.perf_counter()
        listing = a.api('/order/refunds/settings?pageSize=20&pageNum=1')
        timing.append(round((time.perf_counter() - start) * 1000, 1))
    assert listing['rows'] and len(listing['rows']) <= 20
    assert listing['capability_unavailable'] is False
    assert not any(word in json.dumps(listing).lower() for word in ['token_cipher', 'session_cipher', 'app_key', 'secret_cipher'])
    channel = str(a.c['channel_id'])
    refund = str(a.c['refunds']['kit']['id'])
    detail = a.api('/order/refunds/settings/' + channel)
    assert detail['channel_name']
    assert int(detail['flow']['enabled']) == int(detail['flow']['auto_pay']) == 0
    checks = ['paged overview batches local settings and exposes no credentials', 'saved automation and payment remain disabled']
    writes = []; errors = []; mocked_saves = []; requests = []
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
            if request.method not in ['GET', 'HEAD', 'OPTIONS']:
                writes.append(request.url)
        page.on('request', observe)
        page.goto(BASE + '/oms-order/businessRefunds', wait_until='networkidle')
        page.get_by_role('button', name=re.compile('售后设置$')).click()
        expect(page.locator('.after-sale-settings h2')).to_have_text('售后设置')
        for label in ['退款接入', '平台授权与权限', '自动处理', '退货与入库', '当前问题']:
            expect(page.locator('.after-sale-settings .el-table__header-wrapper').get_by_text(label, exact=True).first).to_be_visible()
        page.screenshot(path=str(OUT / 'overview.png'), full_page=True)
        checks.append('refund list provides a visible settings button and paged shop overview')
        page.goto(BASE + '/oms-order/businessRefunds?id=' + refund, wait_until='networkidle')
        dialog = page.get_by_role('dialog', name=re.compile('^业务退款详情'))
        dialog.get_by_role('button', name=re.compile('本店铺售后设置$')).click()
        expect(page).to_have_url(re.compile('channelId=' + channel))
        expect(page.get_by_role('tab', name='自动处理', exact=True)).to_have_attribute('aria-selected', 'true')
        expect(page.get_by_role('button', name='返回退款单', exact=True)).to_be_visible()
        checks.append('refund detail opens the current shop directly and retains return-to-refund context')
        # Change controls only. No save is submitted to the actual business configuration.
        form = page.locator('.automation-form')
        form.locator('.el-form-item').filter(has_text='启用自动处理').locator('.el-switch').click()
        form.locator('.el-form-item').filter(has_text='自动审核平台退款').locator('.el-switch').click()
        form.get_by_text('OMS提交付款', exact=True).click()
        form.locator('.el-form-item').filter(has_text='自动提交退款付款').locator('.el-switch').click()
        form.locator('.el-form-item').filter(has_text='单退款自动付款上限').locator('input').fill('100')
        form.locator('.el-form-item').filter(has_text='单退款自动付款上限').locator('input').press('Tab')
        expect(page.locator('.effect-panel')).to_contain_text('付款子账号授权有效')
        expect(page.locator('.effect-panel')).to_contain_text('条件未满足')
        expect(page.locator('.after-sale-settings .page-heading')).to_contain_text('有未保存修改')
        page.screenshot(path=str(OUT / 'automation-conditions.png'), full_page=True)
        page.get_by_role('tab', name='退款接入', exact=True).click()
        confirm = page.locator('.el-message-box:visible')
        confirm.get_by_role('button', name='继续编辑', exact=True).click()
        expect(page.locator('.automation-form')).to_be_visible()
        page.get_by_role('tab', name='退款接入', exact=True).click()
        page.locator('.el-message-box:visible').get_by_role('button', name='放弃修改', exact=True).click()
        expect(page.get_by_role('tab', name='退款接入', exact=True)).to_have_attribute('aria-selected', 'true')
        expect(page).to_have_url(re.compile('tab=intake'))
        checks.append('unsaved tab changes can be canceled or discarded; payment blockers name the missing requirements')
        page.get_by_role('tab', name='平台授权', exact=True).click()
        expect(page.locator('.configuration-form')).to_contain_text('付款子账号')
        expect(page.get_by_role('dialog')).to_have_count(0)
        page.screenshot(path=str(OUT / 'authorization.png'), full_page=True)
        page.get_by_role('tab', name='退货与入库', exact=True).click()
        expect(page.locator('.return-rule-editor .rule-actions')).to_be_visible()
        expect(page.locator('.return-config')).to_contain_text('平台退回地址用于告知买家')
        page.locator('.return-rule-editor').get_by_text('审核后自动创建', exact=True).click()
        expect(page.locator('.after-sale-settings .page-heading')).to_contain_text('有未保存修改')
        expect(page.locator('.flow-summary')).to_contain_text('自动创建退回安排')
        page.get_by_role('tab', name='平台授权', exact=True).click()
        page.locator('.el-message-box:visible').get_by_role('button', name='放弃修改', exact=True).click()
        checks.append('authorization and warehouse rules use the same page; warehouse changes update preview and have a discard guard')
        assert not any('/addresses' in url for url in requests), 'opening settings must not fetch vendor addresses'
        assert not writes, writes
        # Submit only to an explicitly mocked browser route. Persisted business flags stay unchanged.
        fixture = json.loads(json.dumps(detail))
        fixture['flow']['auto_review'] = 1; fixture['flow']['enabled'] = 1
        page.route('**/prod-api/order/refunds/settings/' + channel, lambda route: route.fulfill(json={'code': 200, 'data': fixture}))
        def save_mock(route):
            assert route.request.method == 'POST'
            body = route.request.post_data_json
            assert 'api_sync_enabled' not in body and 'api_sync' not in body
            mocked_saves.append(body)
            fixture['flow'].update(body)
            route.fulfill(json={'code': 200})
        page.route('**/prod-api/order/refunds/flow-configs/' + channel, save_mock)
        page.goto(BASE + PATH + '?channelId=' + channel + '&tab=automation', wait_until='networkidle')
        page.locator('.automation-form .el-form-item').filter(has_text='自动回告取消发货').locator('.el-switch').click()
        page.get_by_role('button', name='保存自动处理', exact=True).click()
        expect(page.locator('.after-sale-settings .page-heading')).to_contain_text('已读取保存配置')
        assert len(mocked_saves) == 1
        checks.append('browser-only save fixture submits automation without overwriting intake switches')
        fixture['shop_enabled'] = 0
        page.reload(wait_until='networkidle')
        expect(page.locator('.after-sale-settings .page-heading')).to_contain_text('店铺已停用，仅供查看')
        expect(page.get_by_role('button', name='保存自动处理', exact=True)).to_be_disabled()
        checks.append('disabled shop remains viewable and cannot save automatic execution rules')
        browser.close()
    assert not errors, errors
    assert len(writes) == 1 and '/flow-configs/' in writes[0], writes
    assert snapshot(a) == before, 'browser acceptance must not change actual settings or protected stock'
    (OUT / 'acceptance.json').write_text(json.dumps({'checks': checks, 'overview_20_ms': timing, 'browser_errors': errors, 'browser_mocked_save_count': len(mocked_saves), 'actual_settings_unchanged': True, 'protected_stock_unchanged': True, 'vendor_address_requests_on_open': 0, 'real_financial_or_warehouse_execution': False}, ensure_ascii=False, indent=2), 'utf-8')
    print('PASS unified after-sales settings:', len(checks), 'checks; actual settings and inventory unchanged')


if __name__ == '__main__':
    main()
