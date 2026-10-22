"""Retained TEST-only gift configuration acceptance; no order or inventory writes."""
import copy
import json
import urllib.request
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, opener
from test_multi_virtual_shipping import sql, quote

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/gift-shop-groups'
DB = 'qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fixture = json.loads((ROOT / 'outputs/gifts/case.json').read_text('utf-8'))
    manifest = OUT / 'case.json'
    case = json.loads(manifest.read_text('utf-8')) if manifest.exists() else {
        'stamp': datetime.now().strftime('%Y%m%d%H%M%S'), 'checks': []}
    case.pop('original_plans_before', None)
    case.pop('original_plans_after', None)
    prefix = '[配置验收] ' + case['stamp']
    credential = login()

    def save():
        manifest.write_text(json.dumps(case, ensure_ascii=False, indent=2), 'utf-8')

    def check(message):
        if message not in case['checks']:
            case['checks'].append(message)
        save()
        print('PASS ' + message, flush=True)

    def api(path, body=None, rejected=False):
        req = urllib.request.Request(BASE + '/prod-api' + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization': 'Bearer ' + credential, 'Content-Type': 'application/json'})
        with opener.open(req, timeout=45) as response:
            result = json.load(response)
        if rejected:
            assert result['code'] != 200, path
            return result.get('msg', '')
        assert result['code'] == 200, (path, result.get('msg'))
        return result.get('data', result)

    catalog = api('/order/gifts/catalog')
    original_shop = next(s for s in catalog if int(s['channel_id']) == int(fixture['channel_id']))
    assert original_shop['channel_name'].startswith('[赠品验收]'), 'Only synthetic shop is allowed'
    if not case.get('second_shop'):
        name = prefix + ' 独立店铺'
        if not any(s['channel_name'] == name for s in catalog):
            api('/channel/channel', {'channelName': name, 'channelType': 'TM', 'enabled': 1,
                'toChannelEnabled': 0, 'syncEnabled': 0, 'mmodelType': 1})
        case['second_shop'] = int(next(s['channel_id'] for s in api('/order/gifts/catalog') if s['channel_name'] == name))
        save()

    def link(ident, more):
        return {'group_id': ident, 'continue_after_match': more}

    def scope(ident):
        return next(s for s in api('/order/gifts/shop-groups') if int(s['id']) == int(ident))

    for key, count in [('a', 1), ('b', 2)]:
        if not case.get('strategy_' + key):
            policy = {'start_time': '2026-10-01 00:00:00', 'end_time': '2026-11-30 23:59:59',
                'shipped_policy': 'RETURN', 'allow_waive': True, 'difference_affects_amount': False,
                'rules': [{'key': 'R_' + key, 'name': '满额赠 ' + str(count) + ' 件', 'type': 'AMOUNT',
                    'amount': 1, 'minimum': 1, 'cap': 100, 'skus': [], 'stop': False, 'tiers': [],
                    'gifts': [{'sku_sn': fixture['gift_sku'], 'quantity': count, 'goods_name': '[验收] 赠品'}]}]}
            case['strategy_' + key] = api('/order/gifts/groups', {'name': prefix + ' 公共策略 ' + key.upper(),
                'enabled': 1, 'policy': policy})['id']
            save()
    a, b = case['strategy_a'], case['strategy_b']
    for key, channel, links in [('first', fixture['channel_id'], [link(a, True), link(b, False)]),
                                ('second', case['second_shop'], [link(b, False), link(a, True)])]:
        if not case.get(key + '_scope'):
            case[key + '_scope'] = api('/order/gifts/shop-groups', {'name': prefix + ' 店铺组 ' + key,
                'enabled': 1, 'channels': [channel], 'links': links})['id']
            save()
    first, second = scope(case['first_scope']), scope(case['second_scope'])
    first['links'] = [link(a, True), link(b, False)]
    api('/order/gifts/shop-groups', first)
    first = scope(case['first_scope'])
    active = api('/order/gifts/shops/' + str(case['second_shop']) + '/effective')
    assert [s['group_id'] for s in active['shop_groups'][0]['executions']] == [b, a]
    assert all('channels' not in s['policy'] for s in api('/order/gifts/groups') if s['id'] in [a, b])
    check('公共策略复用，店铺范围、排序及继续设置属于各自店铺组')
    conflict = {'name': prefix + ' 冲突组', 'enabled': 1,
        'channels': [fixture['channel_id']], 'links': [link(a, False)]}
    assert '已属于' in api('/order/gifts/shop-groups', conflict, rejected=True)
    duplicate = copy.deepcopy(first)
    duplicate['links'] = [link(a, False), link(a, True)]
    assert '重复关联' in api('/order/gifts/shop-groups', duplicate, rejected=True)
    api('/order/gifts/shop-groups', first)
    assert '配置已变化' in api('/order/gifts/shop-groups', first, rejected=True)
    check('重复归组、重复关联及过期版本保存均被拒绝')
    order_id = fixture['orders']['pending']['id']
    trial = api('/order/gifts/orders/' + str(order_id) + '/preview', {})
    assert sum(g['quantity'] for g in trial['gifts']) == 3
    assert [g['id'] for g in trial['groups']] == [a, b]
    check('订单试算按当前店铺组串联，命中多组累计赠送')

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 1050})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(BASE + '/oms-order/gifts', wait_until='networkidle')
        page.locator('.scope-choice').filter(has_text=first['name']).click()
        expect(page.locator('.strategy-card')).to_have_count(2)
        page.screenshot(path=str(OUT / 'shop-groups.png'), full_page=True, animations='disabled')
        page.get_by_role('button', name='管理店铺与执行顺序', exact=True).click()
        dialog = page.locator('.el-dialog:visible')
        dialog.get_by_role('button', name='上移', exact=True).nth(1).click()
        with page.expect_response(lambda r: r.url.endswith('/gifts/shop-groups') and r.request.method == 'POST') as saved:
            dialog.get_by_role('button', name='保存店铺组与关联', exact=True).click()
        assert saved.value.json()['code'] == 200
        expect(dialog).not_to_be_visible()
        assert [l['group_id'] for l in scope(case['first_scope'])['links']] == [b, a]
        assert scope(case['second_scope'])['links'] == second['links']
        check('浏览器调整当前店铺组顺序并保存，其他店铺组不受影响')
        page.get_by_role('button', name='按店铺查看生效规则', exact=True).click()
        dialog = page.locator('.el-dialog:visible')
        dialog.locator('.el-select').click()
        page.locator('.el-select-dropdown:visible').get_by_text(original_shop['channel_name'], exact=True).click()
        expect(dialog.locator('.el-table__body-wrapper tbody tr')).to_have_count(2)
        page.screenshot(path=str(OUT / 'effective-rules.png'), full_page=True, animations='disabled')
        dialog.get_by_role('button', name='关闭', exact=True).click()
        page.get_by_role('tab', name='公共策略库', exact=True).click()
        row = page.locator('.el-tab-pane:visible .el-table__body-wrapper tbody tr').filter(has_text=prefix + ' 公共策略 A')
        expect(row).to_contain_text('2 个店铺组')
        fixed_row = page.locator('.el-tab-pane:visible .el-table__fixed-right tbody tr').filter(has_text=prefix + ' 公共策略 A')
        fixed_row.get_by_role('button', name='编辑', exact=True).click()
        expect(page.locator('.strategy-editor')).to_contain_text('编辑此公共策略会影响')
        page.screenshot(path=str(OUT / 'shared-strategy.png'), full_page=True, animations='disabled')
        page.get_by_role('button', name='返回赠品管理', exact=True).click()
        check('店铺生效预览及公共策略影响范围可见')
        page.get_by_role('tab', name='订单试算', exact=True).click()
        order_number = sql("SELECT order_sn FROM oms_order WHERE company_code='QM' AND id=" + str(int(order_id)), DB)
        page.get_by_placeholder('输入 OMS 单号 / 平台单号', exact=True).fill(order_number)
        page.get_by_role('button', name='试算当前规则', exact=True).click()
        expect(page.get_by_text('前序策略组命中后停止，本组未执行', exact=False)).to_be_visible()
        page.screenshot(path=str(OUT / 'trial-path.png'), full_page=True, animations='disabled')
        check('浏览器试算展示所属店铺组、命中结果和停止路径')
        page.get_by_role('tab', name='店铺组与执行顺序', exact=True).click()
        page.set_viewport_size({'width': 390, 'height': 844})
        page.screenshot(path=str(OUT / 'mobile.png'), full_page=True, animations='disabled')
        assert page.locator('.scope-detail').evaluate('(e)=>e.getBoundingClientRect().right') <= 391
        assert not errors, errors
        browser.close()
        check('窄屏店铺组布局及页面运行无错误')
    trial = api('/order/gifts/orders/' + str(order_id) + '/preview', {})
    assert sum(g['quantity'] for g in trial['gifts']) == 2
    assert len(trial['trace']) == 2 and trial['trace'][1]['skipped'] is True
    check('调整后按新顺序停止，试算明确显示后续策略未执行')
    print('PASS gift shop group API and browser acceptance:', len(case['checks']), flush=True)


if __name__ == '__main__':
    main()
