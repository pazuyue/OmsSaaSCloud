"""Owned TEST fixtures verify independent dispatch, manual confirmation and the tabbed rule editor."""
import json
import re
import sys
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-dispatch-rules'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    c = json.loads((ROOT / 'outputs/refund-auto-create/refundauto-case.json').read_text('utf-8'))
    assert c['seller'].startswith('REFUND_AUTO_') and c['channel_name'].startswith('[自动退回验收]')
    manifest = OUT / 'case.json'
    state = json.loads(manifest.read_text('utf-8')) if manifest.exists() else {'refunds': []}
    token = login()
    checks, errors = [], []

    def api(path, body=None):
        response = request(path, payload=body, token=token, method='POST' if body is not None else 'GET')
        assert response.get('code', 200) == 200, (path, response.get('msg'))
        return response.get('data', response)

    def save():
        manifest.write_text(json.dumps(state, ensure_ascii=False, indent=2), 'utf-8')

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    store = c['warehouses'][0]['code']
    for automatic_create, automatic_dispatch, merge in ([] if '--ui-only' in sys.argv else [(0, 0, 'NONE'), (0, 1, 'NONE'), (1, 0, 'NONE'), (1, 1, 'NONE'), (0, 1, 'MANUAL')]):
        key = 'manualmerge_v2' if merge == 'MANUAL' else str(automatic_create) + str(automatic_dispatch)
        policy = api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
        policy.update(merge_mode=merge, enabled=0, auto_enabled=0, auto_create=automatic_create, auto_dispatch=automatic_dispatch, create_store_code=store, create_return_address='[独立下发验收] 接收地址，无实际配送', create_return_contact='TEST-INDEPENDENT-RETURN', store_codes=[store], logistics_sources=[])
        api('/order/refunds/parcel-configs/' + str(c['channel_id']), policy)
        assert api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']['auto_dispatch'] == automatic_dispatch
        entry = next((row for row in state['refunds'] if row['key'] == key), None)
        if not entry:
            rid = '998' + c['case_id'] + str(15 if merge == 'MANUAL' else 10 + automatic_create * 2 + automatic_dispatch)
            now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            payload = {'refund_id': rid, 'tid': c['platform_tid'], 'oid': c['platform_oid'], 'seller_nick': c['seller'], 'status': 'SUCCESS', 'refund_fee': '1.00', 'num': 1, 'has_good_return': True, 'dispute_type': 'REFUND_AND_RETURN', 'refund_phase': 'aftersale', 'refund_version': '1', 'reason': '[独立下发验收] ' + key, 'modified': now}
            values = [quote(value) for value in [rid, c['platform_tid'], c['platform_oid'], 'SUCCESS', c['seller'], c['created'], now, now, json.dumps({'refund': payload}, ensure_ascii=False)]]
            sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(values) + ')', 'refund_acceptance_source')
            api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [rid], 'request_key': 'INDEPENDENT_' + c['case_id'] + '_' + key})
            platform = wait_for(lambda: api('/order/refunds/platform?number=' + rid)['rows'], lambda rows: bool(rows and rows[0].get('business_refund_id')), 'Independent refund conversion incomplete', 150)[0]
            entry = {'key': key, 'id': platform['business_refund_id'], 'number': rid}
            state['refunds'].append(entry)
            save()
        path = '/order/refunds/business/' + str(entry['id'])
        b = api(path)
        if b['review_state'] != 'APPROVED':
            api(path + '/review', {'control_version': b['control_version'], 'platform_version': b['platform']['version'], 'purpose': 'CANCEL', 'goods_action': 'RETURN', 'reason': '[独立下发验收] 确认购买商品一件', 'evidence': 'TEST-INDEPENDENT-' + key})
        if automatic_create:
            plans = wait_for(lambda: api(path + '/goods')['plans'], bool, 'Automatic return creation incomplete', 120)
        else:
            plans = api(path + '/goods')['plans']
            if not plans:
                b = api(path)
                source = next(row for row in b['shipments'] if row['shipment_id'] == c['shipment_id'])
                api(path + '/goods/plans', {'control_version': b['control_version'], 'shipment_line_id': source['shipment_line_id'], 'quantity': 1, 'store_code': store, 'return_address': '[独立下发验收] 接收地址，无实际配送', 'return_contact': 'TEST-INDEPENDENT-RETURN', 'reason': '[独立下发验收] 人工明确一件来源', 'evidence': 'TEST-INDEPENDENT-' + key, 'confirmed': True, 'request_key': 'INDEPENDENT_PLAN_' + c['case_id'] + '_' + key})
                plans = api(path + '/goods')['plans']
        assert len(plans) == 1 and plans[0]['quantity'] == 1 and plans[0]['gift_plan_id'] is None
        if automatic_dispatch:
            if merge == 'MANUAL' and not entry.get('confirmed_parcel'):
                waiting = wait_for(lambda: api(path + '/goods')['plans'][0], lambda plan: plan['auto_dispatch_state'] == 'WAIT_ASSOCIATION', 'Manual parcel confirmation was not awaited', 120)
                assert waiting['actual'] is None
                parcel = api(path + '/parcels/plans/' + str(waiting['id']), {'control_version': api(path)['control_version'], 'plan_version': waiting['control_version'], 'carrier_code': 'SF', 'waybill': 'TEST_MANUAL_RULE_' + c['case_id'] + '_' + key, 'confirmed': True, 'reason': '[人工合包验收] 核实实际包裹范围', 'evidence': 'TEST-CONFIRMED-PARCEL'})
                entry['confirmed_parcel'] = parcel['id']
                save()
            plans = wait_for(lambda: api(path + '/goods')['plans'], lambda rows: bool(rows[0]['actual'] and rows[0]['actual']['dispatch_state'] == 'ACCEPTED'), 'Independent automatic dispatch incomplete', 120)
            assert plans[0]['auto_dispatch_state'] == 'SENT'
        elif not entry.get('manual_sent'):
            api(path + '/parcels/auto-run', {})
            assert api(path + '/goods')['plans'][0]['actual'] is None
            plan = api(path + '/goods')['plans'][0]
            api(path + '/goods/plans/' + str(plan['id']) + '/documents/0/dispatch', {'control_version': api(path)['control_version'], 'plan_version': plan['control_version']})
            entry['manual_sent'] = True
            save()
        plans = api(path + '/goods')['plans']
        assert plans[0]['accepted_quantity'] == plans[0]['posted_quantity'] == 0
        assert len(api(path + '/parcels')) == (1 if merge == 'MANUAL' else 0)
        api(path + '/parcels/auto-run', {})
        assert len(api(path + '/goods')['plans']) == 1
        entry.update(refund_sn=api(path)['refund_sn'], return_sn=plans[0]['actual']['return_sn'])
        save()
        check(('自动创建' if automatic_create else '人工创建') + '＋' + ('自动下发' if automatic_dispatch else '人工下发') + ('：人工确认合包范围后自动下发，无重复仓库单' if merge == 'MANUAL' else '：不合包，独立仓库单，无重复安排或库存入账'))

    policy = api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
    policy.update(merge_mode='NONE', auto_create=1, auto_dispatch=1)
    api('/order/refunds/parcel-configs/' + str(c['channel_id']), policy)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={'width': 1500, 'height': 1050})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(BASE + '/oms-order/afterSaleRules/returns?channelId=' + str(c['channel_id']), wait_until='networkidle')
        editor = page.locator('.return-rule-editor:visible')
        for label in ['退回安排', '仓库下发', '合包规则', '实收处理']:
            expect(editor.get_by_role('tab', name=label, exact=True)).to_be_visible()
        expect(editor.locator('.rule-flow')).to_contain_text('不合包')
        expect(editor.locator('.rule-flow')).to_contain_text('自动下发仓库')
        page.screenshot(path=str(OUT / 'rule-arrangements.png'), animations='disabled')
        editor.get_by_role('tab', name='仓库下发', exact=True).click()
        expect(editor.get_by_text('自动下发', exact=True)).to_be_visible()
        expect(editor.get_by_text('退回安排资料就绪后独立下发，不等待其他退款或合包。', exact=True)).to_be_visible()
        page.screenshot(path=str(OUT / 'rule-dispatch.png'), animations='disabled')
        editor.get_by_role('tab', name='合包规则', exact=True).click()
        expect(editor.locator('.el-tab-pane:visible .el-radio.is-checked')).to_contain_text('不合包')
        expect(editor.get_by_text('归集等待', exact=True)).not_to_be_visible()
        editor.get_by_text('人工合包', exact=True).click()
        editor.get_by_role('tab', name='仓库下发', exact=True).click()
        expect(editor.get_by_text('人工确认实际包裹范围后，后台自动下发仓库。', exact=True)).to_be_visible()
        editor.get_by_role('tab', name='合包规则', exact=True).click()
        editor.locator('.el-radio').filter(has_text=re.compile('^不合包$')).click()
        editor.get_by_role('tab', name='实收处理', exact=True).click()
        with page.expect_response(lambda response: response.request.method == 'POST' and '/refunds/parcel-configs/' in response.url):
            editor.get_by_role('button', name='保存退货规则', exact=True).click()
        expect(editor.get_by_role('tab', name='实收处理', exact=True)).to_have_attribute('aria-selected', 'true')
        saved = api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
        assert saved['enabled'] == 0 and saved['auto_dispatch'] == 1
        check('四页签切换与保存保持一致，不合包仍可自动下发；切换合包方式不重置下发开关')
        editor.get_by_role('tab', name='退回安排', exact=True).click()
        contact = editor.locator('.el-form-item').filter(has=page.locator('label').filter(has_text=re.compile('^退回联系人$'))).locator('input')
        contact.fill('')
        editor.get_by_role('tab', name='合包规则', exact=True).click()
        editor.get_by_role('button', name='保存退货规则', exact=True).click()
        expect(editor.get_by_role('tab', name='退回安排', exact=True)).to_have_attribute('aria-selected', 'true')
        expect(editor.locator('.el-form-item__error')).to_contain_text('退回联系人')
        editor.get_by_role('button', name='重新加载', exact=True).click()
        expect(contact).to_have_value('TEST-INDEPENDENT-RETURN')
        check('缺少必填资料时定位对应页签及字段；重新加载恢复已保存配置')
        page.set_viewport_size({'width': 1000, 'height': 950})
        expect(editor.get_by_role('button', name='保存退货规则', exact=True)).to_be_in_viewport()
        assert page.evaluate('document.documentElement.scrollWidth<=window.innerWidth')
        page.screenshot(path=str(OUT / 'rule-narrow.png'), animations='disabled')
        page.set_viewport_size({'width': 1500, 'height': 1050})
        page.locator('.el-drawer:visible .close-drawer').click()
        expect(editor).not_to_be_visible()
        expect(page.locator('.return-rules-drawer')).not_to_be_visible()
        table = page.locator('.return-rules-table')
        expect(table.locator('.el-table__header-wrapper th')).to_have_count(6)
        expect(table).to_contain_text('自动下发')
        expect(table).to_contain_text('不合包')
        page.screenshot(path=str(OUT / 'rule-list.png'), animations='disabled')
        check('列表仅保留六列，窄屏无整页横向溢出，保存操作始终可见')
        page.goto(BASE + '/oms-order/businessRefunds?id=' + str(next(row for row in state['refunds'] if row['key']=='11')['id']), wait_until='networkidle')
        dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='退回与包裹', exact=True))
        dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
        expect(dialog.locator('.return-plans-table')).to_contain_text('仓库已接收')
        expect(dialog.locator('.refund-parcels')).to_contain_text('当前不合包')
        page.screenshot(path=str(OUT / 'independent-return.png'), animations='disabled')
        check('退款详情显示真实仓库下发结果，并明确当前按独立退回处理')
        assert not errors, errors
        browser.close()
    (OUT / 'verification.json').write_text(json.dumps({'checks': checks, 'errors': errors}, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
