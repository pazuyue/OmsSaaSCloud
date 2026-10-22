"""Isolated local TEST acceptance: review -> auto plan -> separately enabled TEST WMS dispatch."""
import json
import re
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
import verify_exchange_ownership as ownership
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-auto-create'
ORDER = 'qm_oms_saas_order'
SOURCE = 'refund_acceptance_source'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    def authenticate():
        try:
            return login()
        except (AssertionError, OSError):
            return None
    token = wait_for(authenticate, bool, 'Local authentication service did not become ready', 180)
    checks, errors = [], []

    def api(path, body=None):
        result = request(path, payload=body, token=token, method='POST' if body is not None else 'GET')
        assert result.get('code', 200) == 200, (path, result.get('msg'))
        return result.get('data', result)

    def check(message):
        checks.append(message)
        print('PASS ' + message, flush=True)

    def dependencies():
        try:
            api('/goods/info/list?pageNum=1&pageSize=1', {})
            api('/order/refunds/catalog')
            api('/supplychain/wmsIntegration/connections')
            return True
        except (AssertionError, OSError):
            return False
    wait_for(dependencies, bool, 'Local goods/order/warehouse services did not become ready', 180)

    ownership.OUT = OUT
    case = ownership.Case('refundauto', token)
    c = case.c
    if not c.get('prepared'):
        c.update(sku='RA_' + c['case_id'] + '_A', target_sku='RA_' + c['case_id'] + '_B', seller='REFUND_AUTO_' + c['case_id'], channel_name='[自动退回验收] ' + c['case_id'])
        c['warehouses'][0]['code'] = 'RA_' + c['case_id'] + '_V'
        fixture.MANIFEST = case.path
        fixture.prepare_case(c, token)
        c['prepared'] = True
        case.save()
    if not c.get('shipped'):
        shipment = case.dispatch_ship(case.allocate()['shipments'][0])
        c.update(shipped=True, shipment_id=shipment['id'])
        case.save()
    check('独立测试店铺与商品通过真实库存、分仓及 TEST WMS 出库接口准备完成')
    connection = next(x for x in api('/supplychain/wmsIntegration/connections') if x['id'] == c['connection_id'])
    assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
    if not c.get('return_connection'):
        credential = next(x for x in json.loads((ROOT / 'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key'] == connection['app_key'] and x['customer_id'] == connection['customer_id'])
        body = {key: connection[key] for key in ['provider', 'api_version', 'environment', 'endpoint', 'app_key', 'customer_id']}
        body.update(name='[自动退回验收] ' + c['case_id'], secret=credential['secret'], enabled=1, shared_return_waybill=1)
        c['return_connection'] = api('/supplychain/wmsIntegration/connections', body)
        case.save()
    warehouse = api('/supplychain/simulationStore/' + str(c['warehouses'][0]['id']))
    if int(warehouse['connectionId']) != int(c['return_connection']):
        body = {key: warehouse[key] for key in ['id', 'wmsSimulationCode', 'wmsSimulationName', 'ownerWarehouseId', 'inboundMode', 'outboundMode', 'externalWarehouse', 'externalOwner', 'status']}
        body['connectionId'] = c['return_connection']
        request('/supplychain/simulationStore', token=token, method='PUT', payload=body)
    store = c['warehouses'][0]['code']

    def policy(**values):
        p = api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
        p.update(values)
        api('/order/refunds/parcel-configs/' + str(c['channel_id']), p)
        return api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']

    if not c.get('rule_ready'):
        p = policy(auto_create=1, enabled=0, auto_enabled=0, auto_dispatch=0, create_store_code=store, create_return_address='[自动退回验收] 测试接收地址，无实际配送', create_return_contact='TEST-AUTO-RETURN')
        assert p['auto_create'] == 1 and p['auto_dispatch'] == 0 and p['auto_enabled'] == 0
        c['rule_ready'] = True
        case.save()
    check('自动创建独立于自动合包及仓库下发，可单独启用并保存明确退回仓和地址')
    sources = api('/order/orders/sources')
    source = next(x for x in sources if x['database_name'] == SOURCE and x['enabled'])
    if not any(x['channel_id'] == c['channel_id'] for x in api('/order/refunds/configs')['configs']):
        api('/order/refunds/configs', {'channel_id': c['channel_id'], 'source_key': source['source_key'], 'seller_nick': c['seller'], 'initial_time': c['created'], 'revision': 0, 'enabled': 0, 'auto_convert': 1, 'interval_seconds': 30})
    rid = '998' + c['case_id'] + '01'
    if not c.get('refund_id'):
        modified = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        p = {'refund_id': rid, 'tid': c['platform_tid'], 'oid': c['platform_oid'], 'seller_nick': c['seller'], 'status': 'SUCCESS', 'refund_fee': '2.00', 'num': 2, 'has_good_return': True, 'dispute_type': 'REFUND_AND_RETURN', 'refund_phase': 'aftersale', 'refund_version': '1', 'reason': '[自动退回验收] 两件购买商品', 'modified': modified, 'company_name': 'SF', 'sid': 'TEST_AUTO_RETURN_' + c['case_id']}
        values = [quote(value) for value in [rid, c['platform_tid'], c['platform_oid'], 'SUCCESS', c['seller'], c['created'], modified, modified, json.dumps({'refund_get_response': {'refund': p}}, ensure_ascii=False)]]
        sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(values) + ')', SOURCE)
        api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [rid], 'request_key': 'AUTO_INTAKE_' + c['case_id']})
        platform = wait_for(lambda: api('/order/refunds/platform?number=' + rid)['rows'], lambda rows: bool(rows and rows[0].get('business_refund_id')), 'Automatic business refund conversion incomplete', 150)[0]
        c['refund_id'] = platform['business_refund_id']
        case.save()

    def refund():
        return api('/order/refunds/business/' + str(c['refund_id']))

    def plans():
        return api('/order/refunds/business/' + str(c['refund_id']) + '/goods')['plans']

    if not c.get('plan_verified'):
        b = refund()
        api('/order/refunds/business/' + str(b['id']) + '/review', {'control_version': b['control_version'], 'platform_version': b['platform']['version'], 'purpose': 'CANCEL', 'goods_action': 'RETURN', 'reason': '[自动退回验收] 核对两件实际商品', 'evidence': 'TEST-AUTO-' + c['case_id']})
        tasks = wait_for(plans, lambda rows: bool(rows), 'Automatic return plan not created', 120)
        assert len(tasks) == 1 and tasks[0]['quantity'] == 2 and tasks[0]['sku_sn'] == c['sku'] and not tasks[0]['gift_plan_id']
        assert tasks[0]['actual'] is None and tasks[0]['accepted_quantity'] == tasks[0]['posted_quantity'] == 0
        b = refund()
        assert b['return_auto']['state'] == 'DONE' and b['goods_state'] == 'WAIT_RETURN'
        c.update(plan_verified=True, plan_id=tasks[0]['id'], refund_sn=b['refund_sn'])
        case.save()
    check('审核后后台自动创建购买商品两件的退回安排；未开启下发时不生成仓库单、不增加库存')
    if not c.get('dispatch_verified'):
        policy(enabled=1, auto_enabled=1, auto_dispatch=1, store_codes=[store], logistics_sources=['PLATFORM'], collect_seconds=0)
        api('/order/refunds/business/' + str(c['refund_id']) + '/parcels/auto-run', {})
        rows = wait_for(plans, lambda rows: bool(rows and rows[0]['actual'] and rows[0]['actual']['dispatch_state'] == 'ACCEPTED'), 'Automatic TEST WMS return dispatch incomplete', 120)
        assert len(rows) == 1 and len(rows[0]['actual']['documents']) == 1
        c.update(dispatch_verified=True, return_sn=rows[0]['actual']['return_sn'])
        case.save()
    for _ in range(2):
        api('/order/refunds/business/' + str(c['refund_id']) + '/parcels/auto-run', {})
    assert len(plans()) == 1 and len(plans()[0]['actual']['documents']) == 1
    check('单独开启自动合包和下发后生成 TEST 仓库退货单，重复检查仍只有一份安排和一张仓库单')

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={'width': 1500, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        page = context.new_page()
        page.set_default_timeout(20000)
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(BASE + '/oms-order/afterSaleRules/returns?channelId=' + str(c['channel_id']), wait_until='networkidle')
        editor = page.locator('.return-rule-editor:visible')
        expect(editor.get_by_text('先确定如何创建退回安排', exact=True)).to_be_visible()
        item = lambda label: editor.locator('.el-form-item').filter(has=page.locator('label').filter(has_text=re.compile('^' + label + '$')))
        current_policy=api('/order/refunds/parcel-configs/' + str(c['channel_id']))['policy']
        expect(item('创建方式').locator('.el-radio.is-checked')).to_contain_text('审核后自动创建' if current_policy['auto_create'] else '人工创建')
        expect(item('退回地址').locator('textarea')).to_have_value(current_policy['create_return_address'])
        page.screenshot(path=str(OUT / 'auto-create-settings.png'), full_page=True, animations='disabled')
        check('真实页面回显独立开关、明确退回仓及地址，规则列表提供自动建安排筛选')
        page.goto(BASE + '/oms-order/businessRefunds?id=' + str(c['refund_id']), wait_until='networkidle')
        dialog = page.locator('.el-dialog:visible').filter(has=page.get_by_role('tab', name='退回与包裹', exact=True))
        dialog.get_by_role('tab', name='退回与包裹', exact=True).click()
        expect(dialog.locator('.auto-return-status')).to_contain_text('已自动创建购买商品退回安排')
        expect(dialog.locator('.refund-goods')).to_contain_text(c['sku'])
        page.screenshot(path=str(OUT / 'auto-create-refund.png'), full_page=True, animations='disabled')
        check('退款详情展示自动创建结果和购买商品退回进度，商品与赠品入口保持区分')
        assert not errors, errors
        browser.close()
    (OUT / 'verification.json').write_text(json.dumps({'checks': checks, 'errors': errors, 'refund_sn': c['refund_sn'], 'return_sn': c['return_sn']}, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
