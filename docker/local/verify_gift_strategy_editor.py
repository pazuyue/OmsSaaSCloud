"""Local gift editor acceptance: synthetic goods/configuration, no order or stock writes."""
import json
import sys
import urllib.request
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, opener

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-strategy-editor'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    scopes_fixture = json.loads((ROOT / 'outputs/gift-shop-groups/case.json').read_text('utf-8'))
    stamp = datetime.now().strftime('%Y%m%d%H%M%S')
    prefix = '[编辑验收] ' + stamp
    case = {'prefix': prefix, 'checks': []}
    credential = login()

    def api(path, body=None):
        req = urllib.request.Request(BASE + '/prod-api' + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json'})
        with opener.open(req, timeout=45) as response:
            result = json.load(response)
        assert result['code'] == 200, (path, result.get('msg'))
        return result.get('data', result)

    def check(message):
        case['checks'].append(message)
        (OUT / 'case.json').write_text(json.dumps(case, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS ' + message, flush=True)

    scope_id = api('/order/gifts/shop-groups', {'name': prefix + ' 店铺组', 'enabled': 0,
        'channels': [scopes_fixture['second_shop']], 'links': []})['id']
    case['scope_id'] = scope_id
    target = next(s for s in api('/order/gifts/shop-groups') if s['id'] == scope_id)
    initial_links = target['links']
    resolved = api('/order/gifts/goods-resolve', {'skus': [fixture['sku'], 'MISSING_' + stamp], 'use': 'PURCHASE'})
    assert resolved[0]['valid'] and not resolved[1]['valid']
    check('公司商品批量校验返回名称、规格与无效SKU原因')

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1500, 'height': 1050})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))

        try:
            def basic(name):
                page.get_by_placeholder('例如：双十一满额赠礼', exact=True).fill(name)
                inputs = page.locator('.basic-form .el-range-input')
                inputs.nth(0).fill('2026-10-01 00:00:00')
                inputs.nth(0).press('Tab')
                inputs.nth(1).fill('2026-11-30 23:59:59')
                inputs.nth(1).press('Tab')
                page.locator('.editor-heading h2').click()
                page.get_by_placeholder('例如：满200元赠保温杯', exact=True).fill('满额赠礼')
    
            def choose(button, sku, invalid=False, occurrence=0):
                page.get_by_role('button', name=button, exact=True).nth(occurrence).click()
                dialog = page.locator('.el-dialog:visible')
                dialog.get_by_role('tab', name='批量粘贴SKU', exact=True).click()
                dialog.locator('textarea').fill(sku + ('\nMISSING_' + stamp if invalid else ''))
                dialog.get_by_role('button', name='校验SKU', exact=True).click()
                expect(dialog.get_by_role('button', name='加入有效商品（1）', exact=True)).to_be_enabled()
                if invalid:
                    expect(dialog.get_by_text('商品不存在或SKU不唯一', exact=True)).to_be_visible()
                dialog.get_by_role('button', name='加入有效商品（1）', exact=True).click()
                dialog.get_by_role('button', name='确认选择', exact=True).click()
                expect(dialog).not_to_be_visible()
    
            def simulate(quantity):
                with page.expect_response(lambda r: r.url.endswith('/gifts/simulate')) as response:
                    page.get_by_role('button', name='试算未保存配置', exact=True).click()
                result = response.value.json()
                assert result['code'] == 200, result
                assert sum(g['quantity'] for g in result['data']['gifts']) == quantity, result
                return result['data']
    
            page.goto(BASE + '/oms-order/gifts', wait_until='networkidle')
            page.get_by_role('button', name='新增公共策略组', exact=True).click()
            expect(page.locator('.editor-heading h2')).to_have_text('新增赠品策略组')
            basic(prefix + ' 公共策略')
            choose('选择赠品商品', fixture['gift_sku'], invalid=True)
            expect(page.locator('.live-summary')).to_contain_text('实付满200元')
            expect(page.locator('.reward-table')).to_contain_text(fixture['gift_sku'])
            check('独立新增页、明确全部购买范围、赠品表格及批量无效SKU反馈')
            page.locator('.rule-entry').first.get_by_role('button', name='复制', exact=True).click()
            page.get_by_placeholder('例如：满200元赠保温杯', exact=True).fill('买多赠多')
            page.locator('.type-option').filter(has=page.get_by_text('买多赠多', exact=True)).click()
            choose('选择购买商品 / 批量添加SKU', fixture['sku'])
            expect(page.locator('.live-summary')).to_contain_text('每满2件')
            expect(page.locator('.rule-entry')).to_have_count(2)
            page.screenshot(path=str(OUT / 'editor.png'), full_page=True, animations='disabled')
            check('规则目录、规则复制、赠送方式示例及实时摘要')
            page.get_by_role('tab', name='模拟试算', exact=True).click()
            page.get_by_placeholder('选择付款时间', exact=True).fill('2026-10-20 12:00:00')
            page.get_by_placeholder('选择付款时间', exact=True).press('Tab')
            choose('选择模拟购买商品', fixture['sku'])
            cart_inputs = page.locator('#pane-simulation .el-table__body-wrapper tbody tr').first.locator('.el-input-number input')
            cart_inputs.nth(0).fill('4');cart_inputs.nth(0).press('Tab')
            cart_inputs.nth(1).fill('400');cart_inputs.nth(1).press('Tab')
            simulate(3)
            assert not any(g['name'].startswith(prefix) for g in api('/order/gifts/groups'))
            check('未保存配置可模拟累计赠送，试算不会创建策略、订单或库存操作')
            page.locator('.rule-select').first.click()
            page.get_by_text('结束本组', exact=True).click()
            page.get_by_role('tab', name='模拟试算', exact=True).click()
            result = simulate(1)
            assert result['trace'][0]['rules'][1]['skipped'] is True
            page.screenshot(path=str(OUT / 'simulation.png'), full_page=True, animations='disabled')
            page.locator('.rule-select').first.click()
            page.get_by_text('继续下一条规则，赠品累计', exact=True).click()
            page.get_by_role('tab', name='模拟试算', exact=True).click()
            expect(page.get_by_text('配置或模拟清单已修改，以下为上次结果，请重新试算。', exact=True)).to_be_visible()
            simulate(3)
            check('组内停止路径与修改后试算过期提示')
            with page.expect_response(lambda r: r.url.endswith('/gifts/groups') and r.request.method == 'POST') as saved:
                page.locator('.editor-footer').get_by_role('button', name='保存为停用', exact=True).click()
            response = saved.value.json();assert response['code'] == 200, response
            case['strategy_id'] = response['data']['id']
            expect(page.locator('.saved-result')).to_contain_text('保存为停用')
            page.locator('.saved-result').get_by_role('button', name='关联到店铺组', exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text(target['name'] + '（停用）', exact=True).click()
            dialog.get_by_role('button', name='保存关联并返回店铺组', exact=True).click()
            expect(page.locator('.scope-detail h3')).to_have_text(target['name'])
            updated = next(s for s in api('/order/gifts/shop-groups') if s['id'] == target['id'])
            assert updated['links'][:-1] == initial_links and updated['links'][-1]['group_id'] == case['strategy_id']
            check('保存为停用、保存后关联及返回原店铺组，保留已有执行顺序')
            page.get_by_role('button', name='新增并关联策略组', exact=True).click()
            basic(prefix + ' 组内新增')
            choose('选择赠品商品', fixture['gift_sku'])
            expect(page.get_by_text('保存后关联并返回此店铺组', exact=True)).to_be_visible()
            with page.expect_response(lambda r: r.url.endswith('/gifts/groups') and r.request.method == 'POST') as saved:
                page.locator('.editor-footer').get_by_role('button', name='保存并启用', exact=True).click()
            response = saved.value.json();assert response['code'] == 200, response
            case['scoped_strategy_id'] = response['data']['id']
            expect(page.locator('.scope-detail h3')).to_have_text(target['name'])
            updated = next(s for s in api('/order/gifts/shop-groups') if s['id'] == target['id'])
            assert updated['links'][-1]['group_id'] == case['scoped_strategy_id']
            check('从店铺组新增策略，保存并启用后自动关联并返回')
            page.goto(BASE + '/oms-order/gift-editor/index?id=' + str(case['scoped_strategy_id']), wait_until='networkidle')
            expect(page.get_by_text('编辑此公共策略会影响', exact=False)).to_be_visible()
            page.get_by_role('button', name='另存为独立策略组', exact=True).click()
            expect(page.locator('.editor-heading h2')).to_have_text('新增赠品策略组')
            with page.expect_response(lambda r: r.url.endswith('/gifts/groups') and r.request.method == 'POST') as saved:
                page.locator('.editor-footer').get_by_role('button', name='保存为停用', exact=True).click()
            response = saved.value.json();assert response['code'] == 200, response
            case['copy_id'] = response['data']['id']
            assert case['copy_id'] != case['scoped_strategy_id']
            check('共享影响提示与另存独立策略，原策略不被覆盖')
            page.set_viewport_size({'width': 390, 'height': 844})
            page.get_by_role('tab', name='规则配置', exact=True).click()
            page.screenshot(path=str(OUT / 'mobile.png'), full_page=True, animations='disabled')
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2')
            page.reload(wait_until='networkidle')
            expect(page.get_by_placeholder('例如：双十一满额赠礼', exact=True)).to_have_value(prefix + ' 组内新增（副本）')
            assert not errors, errors
            check('窄屏布局无页面横向溢出，已保存编辑页可刷新恢复')
        except Exception:
            page.screenshot(path=str(OUT / 'failure.png'), full_page=True, animations='disabled')
            print('BROWSER ERRORS', errors, 'URL', page.url, flush=True)
            print(page.locator('body').inner_text()[-1600:], flush=True)
            raise
        browser.close()
    print('PASS gift strategy editor API and browser acceptance:', len(case['checks']), flush=True)


def verify_types():
    """Exercise the other two rule editors against saved TEST configuration without saving."""
    case = json.loads((OUT / 'case.json').read_text('utf-8'))
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    credential = login()
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1500, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        page.goto(BASE + '/oms-order/gift-editor/index?id=' + str(case['strategy_id']), wait_until='networkidle')

        def choose(button, sku, occurrence=0):
            page.get_by_role('button', name=button, exact=True).nth(occurrence).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('tab', name='批量粘贴SKU', exact=True).click()
            dialog.locator('textarea').fill(sku)
            dialog.get_by_role('button', name='校验SKU', exact=True).click()
            dialog.get_by_role('button', name='加入有效商品（1）', exact=True).click()
            dialog.get_by_role('button', name='确认选择', exact=True).click()
            expect(dialog).not_to_be_visible()

        def trial(quantity):
            with page.expect_response(lambda r: r.url.endswith('/gifts/simulate')) as response:
                page.get_by_role('button', name='试算未保存配置', exact=True).click()
            result = response.value.json()
            assert result['code'] == 200, result
            assert sum(g['quantity'] for g in result['data']['gifts']) == quantity, result
            return result['data']

        page.locator('.rule-select').nth(1).click()
        page.locator('.type-option').filter(has=page.get_by_text('指定商品赠', exact=True)).click()
        expect(page.locator('.live-summary')).to_contain_text('仅赠一次')
        page.get_by_role('tab', name='模拟试算', exact=True).click()
        page.get_by_placeholder('选择付款时间', exact=True).fill('2026-10-20 12:00:00')
        page.get_by_placeholder('选择付款时间', exact=True).press('Tab')
        choose('选择模拟购买商品', fixture['sku'])
        inputs = page.locator('#pane-simulation .el-table__body-wrapper tbody tr').first.locator('.el-input-number input')
        inputs.nth(0).fill('4');inputs.nth(0).press('Tab')
        inputs.nth(1).fill('400');inputs.nth(1).press('Tab')
        product = trial(2)
        page.locator('.trial-result').screenshot(path=str(OUT / 'product-trial.png'), animations='disabled')
        page.locator('.rule-select').nth(1).click()
        page.locator('.type-option').filter(has=page.get_by_text('阶梯赠', exact=True)).click()
        choose('选择赠品商品', fixture['gift_sku'])
        page.get_by_role('button', name='添加阶梯', exact=True).click()
        page.locator('.tier-card').nth(1).locator('.el-input-number input').first.fill('4')
        page.locator('.tier-card').nth(1).locator('.el-input-number input').first.press('Tab')
        choose('选择赠品商品', fixture['gift_sku'], 1)
        quantity = page.locator('.tier-card').nth(1).locator('.reward-table .el-input-number input')
        quantity.fill('3');quantity.press('Tab')
        page.get_by_role('tab', name='模拟试算', exact=True).click()
        tier = trial(4)
        page.locator('.trial-result').screenshot(path=str(OUT / 'tier-trial.png'), animations='disabled')
        browser.close()
    for message in ['指定商品赠达到门槛只赠一次，购买更多不倍赠', '阶梯赠只取最高满足档，不累计各档赠品']:
        if message not in case['checks']:
            case['checks'].append(message)
        print('PASS ' + message, flush=True)
    (OUT / 'case.json').write_text(json.dumps(case, ensure_ascii=False, indent=2), 'utf-8')
    (OUT / 'type-trials.json').write_text(json.dumps({'product': product, 'tier': tier}, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    verify_types() if '--types-only' in sys.argv else main()
