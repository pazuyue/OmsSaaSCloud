"""Fresh-browser quota UI acceptance. Only creates disabled test strategies; no orders/stock writes."""
import json
import urllib.request
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, opener

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-quota'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    token = login()

    def api(path, body=None):
        req = urllib.request.Request(BASE + '/prod-api' + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with opener.open(req, timeout=45) as response:
            result = json.load(response)
        assert result['code'] == 200, (path, result.get('msg'))
        return result.get('data', result)

    name = '[额度验收] ' + datetime.now().strftime('%Y%m%d%H%M%S')
    body = {'name': name, 'enabled': 0, 'policy': {
        'start_time': '2026-10-01 00:00:00', 'end_time': '2026-11-01 00:00:00',
        'shipped_policy': 'RETURN', 'allow_waive': True, 'rules': [{
            'key': 'QUOTA', 'name': '整套倍赠限额', 'type': 'MULTIPLE', 'amount': 0,
            'minimum': 3, 'cap': 100, 'max_count': 2, 'skus': [fixture['sku']],
            'gifts': [{'sku_sn': fixture['gift_sku'], 'quantity': 2}], 'tiers': [], 'stop': True}]}}
    saved = api('/order/gifts/groups', body)
    ident = saved['id']
    checks = []

    def check(message):
        checks.append(message)
        (OUT / 'case.json').write_text(json.dumps({'strategy_id': ident, 'name': name, 'checks': checks}, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS ' + message, flush=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1500, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        try:
            page.goto(BASE + '/oms-order/gift-editor/index?id=' + str(ident), wait_until='networkidle')
            expect(page.locator('.quota-input input')).to_have_value('2')
            expect(page.locator('.quota-usage')).to_contain_text('已占用 0 次')
            expect(page.locator('.quota-usage')).to_contain_text('剩余 2 次')
            expect(page.locator('.live-summary')).to_contain_text('累计最多赠送2次')
            expect(page.locator('.type-option.chosen')).to_contain_text('最多100倍')
            check('单单倍赠上限与活动累计上限分别展示，已占用和剩余次数正确')
            page.locator('.quota-input input').fill('1')
            page.locator('.quota-input input').press('Tab')
            expect(page.locator('.quota-usage')).to_contain_text('剩余 1 次')
            page.get_by_role('button', name='刷新用量', exact=True).click()
            expect(page.locator('.quota-input input')).to_have_value('1')
            expect(page.locator('.footer-status')).to_contain_text('有未保存修改')
            page.screenshot(path=str(OUT / 'editor.png'), full_page=True, animations='disabled')
            check('修改限额实时更新摘要和剩余次数，刷新用量保留未保存配置')
            page.get_by_role('tab', name='模拟试算', exact=True).click()
            page.get_by_placeholder('选择付款时间', exact=True).fill('2026-10-20 12:00:00')
            page.get_by_placeholder('选择付款时间', exact=True).press('Tab')
            page.get_by_role('button', name='选择模拟购买商品', exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('tab', name='批量粘贴SKU', exact=True).click()
            dialog.locator('textarea').fill(fixture['sku'])
            dialog.get_by_role('button', name='校验SKU', exact=True).click()
            expect(dialog.get_by_role('button', name='加入有效商品（1）', exact=True)).to_be_enabled()
            dialog.get_by_role('button', name='加入有效商品（1）', exact=True).click()
            dialog.get_by_role('button', name='确认选择', exact=False).click()
            inputs = page.locator('#pane-simulation .el-table__body-wrapper tbody tr').first.locator('.el-input-number input')
            inputs.nth(0).fill('9')
            inputs.nth(1).fill('300')
            inputs.nth(1).press('Tab')
            with page.expect_response(lambda response: response.url.endswith('/gifts/simulate')) as response:
                page.get_by_role('button', name='试算未保存配置', exact=True).click()
            result = response.value.json()['data']
            assert result['gifts'][0]['quantity'] == 2, result
            trace = result['trace'][0]['rules'][0]
            assert trace['requested_count'] == 3 and trace['granted_count'] == 1, trace
            expect(page.locator('.trial-result')).to_contain_text('预计消耗 1 次 / 原应赠 3 次')
            assert next(g for g in api('/order/gifts/groups') if g['id'] == ident)['quota_usage']['QUOTA']['used_count'] == 0
            page.screenshot(path=str(OUT / 'simulation.png'), full_page=True, animations='disabled')
            check('未保存限额参与试算，按完整赠品套数截断，试算不消耗额度')
            page.get_by_role('button', name='保存为停用', exact=True).click()
            expect(page.locator('.saved-result')).to_contain_text('策略组已保存为停用')
            row = next(g for g in api('/order/gifts/groups') if g['id'] == ident)
            assert row['policy']['rules'][0]['max_count'] == 1 and row['quota_usage']['QUOTA']['used_count'] == 0
            page.reload(wait_until='networkidle')
            expect(page.locator('.quota-input input')).to_have_value('1')
            check('保存和重新打开保留累计限额，配置修改不创建订单或占用次数')
            page.set_viewport_size({'width': 390, 'height': 844})
            expect(page.locator('.quota-input input')).to_be_visible()
            assert page.evaluate('document.documentElement.scrollWidth<=window.innerWidth+1')
            page.screenshot(path=str(OUT / 'mobile.png'), full_page=True, animations='disabled')
            assert not errors, errors
            check('窄屏可配置和查看额度，浏览器无运行错误')
        finally:
            context.close()
            browser.close()


if __name__ == '__main__':
    main()
