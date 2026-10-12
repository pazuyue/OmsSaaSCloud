"""Read-only live checks and explicitly intercepted UI operations; no real carrier/order edits."""
import copy
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE


def main():
    token = login()
    output = Path(__file__).resolve().parents[2]/'outputs/order-carriers'
    output.mkdir(parents=True, exist_ok=True)
    try:
        catalog = request('/order/orders/carrier-catalog', token=token)['data']
        configs = request('/order/orders/carrier-configs', token=token)['data']
        live = request('/order/orders/shipments/list', token=token)
        assert live['code'] == 200 and all('control_version' in s and 'can_match_carrier' in s for s in live['rows'])
        for state in ['WAIT_CARRIER', 'WAIT_DISPATCH']:
            assert request('/order/orders/business/list?documentStatus='+state, token=token)['code'] == 200
        if live['rows']:
            actual = live['rows'][0]
            options = request('/order/orders/shipments/'+str(actual['id'])+'/carrier-options', token=token)['data']
            assert 'receiver_province' in options
            detail = request('/order/orders/shipments/'+str(actual['id']), token=token)['data']
            assert all(key in detail for key in ['logs', 'carrier_match_reason', 'warehouse_carrier_code'])
        shipment = dict(id=999999981, shipment_sn='FH_CARRIER_UI', order_id=999999982, order_sn='SO_CARRIER_UI', channel_id=1, channel_name='UI 验收店铺', platform_tid='99999999988881', generation_no=1, owner_code='UI_OWNER', real_store_code='UI_R1', real_store_name='UI 验收仓', quantity=5, shipped_quantity=0, status='WAIT_CARRIER', order_status='WAIT_ALLOCATION', create_time='2026-10-12 10:00:00', created_by='UI 验收', control_version=1, carrier_code='', carrier_name='', carrier_match_status='FAILED', carrier_match_mode='AUTO', carrier_match_reason='无可用快递；UI_A：该仓快递不配送收货省份', can_match_carrier=True, fulfillment_block='', receiver_province='北京')
        fixture_detail = dict(**shipment, receiver_name='UI 收件人', receiver_phone='脱敏号码', receiver_address='UI 验收地址', lines=[dict(allocation_id=101, platform_oid='9999999998888101', sku_sn='UI_SKU', goods_name='UI 验收商品', store_code='UI_V1', batch_id=101, source_type='PUBLIC_EXTRA', quantity=5, shipped_quantity=0)], logs=[])
        fixture_catalog = dict(carriers=[dict(carrier_code='UI_A', carrier_name='UI 快递甲', enabled=1, revision=1, services=[dict(owner_code='UI_OWNER', real_store_code='UI_R1', warehouse_carrier_code='WMS_A', provinces=[], excluded_provinces=['新疆'])], updated_by='UI 验收', update_time='2026-10-12 10:00:00'), dict(carrier_code='UI_B', carrier_name='UI 快递乙', enabled=1, revision=1, services=[], updated_by='UI 验收', update_time='2026-10-12 10:00:00')], warehouses=[dict(owner_code='UI_OWNER', owner_name='UI 货主', real_store_code='UI_R1', real_store_name='UI 验收仓', wms_simulation_code='UI_V1')], provinces=['北京', '上海', '新疆'])
        fixture_configs = [dict(channel_id=1, channel_name='UI 验收店铺', revision=1, configured=True, policy=dict(enabled=1, carrier_order=['UI_A', 'UI_B'], routes=[]))]
        captured = []
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width':1550, 'height':1050})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(BASE+'/oms-order/settings?tab=carriers', wait_until='networkidle')
            expect(page.get_by_role('tab', name='快递资料', exact=True)).to_be_visible()
            expect(page.get_by_role('tab', name='快递规则', exact=True)).to_be_visible()
            try:
                expect(page.get_by_role('button', name='新增快递')).to_be_visible()
            except AssertionError:
                print('Settings diagnostic:', page.url, page.locator('.app-container').inner_text(), errors)
                page.screenshot(path=str(output/'settings-failure.png'), full_page=True)
                raise
            page.goto(BASE+'/oms-order/shipments', wait_until='networkidle')
            expect(page.locator('.shipment-workspace')).to_contain_text('批量匹配快递')
            page.screenshot(path=str(output/'shipment-carrier-list-live.png'), full_page=True, animations='disabled')

            def fulfill(route, body): route.fulfill(status=200, content_type='application/json', body=json.dumps(body, ensure_ascii=False))
            page.route('**/order/orders/carrier-catalog', lambda r: fulfill(r, {'code':200, 'data':fixture_catalog}))
            page.route('**/order/orders/carrier-configs', lambda r: fulfill(r, {'code':200, 'data':fixture_configs}) if r.request.method=='GET' else (captured.append(('config',r.request.post_data_json)),fulfill(r,{'code':200})))
            page.route('**/order/orders/carriers', lambda r: (captured.append(('master',r.request.post_data_json)),fulfill(r,{'code':200})))
            page.route('**/order/orders/shipments/list?*', lambda r: fulfill(r, {'code':200,'rows':[shipment],'total':1}))
            page.route('**/order/orders/shipments/'+str(shipment['id']), lambda r: fulfill(r, {'code':200,'data':fixture_detail}))
            page.route('**/order/orders/shipments/'+str(shipment['id'])+'/carrier-options', lambda r: fulfill(r, {'code':200,'data':{'receiver_province':'北京','carriers':[dict(carrier_code='UI_A',carrier_name='UI 快递甲',available=False,reason='该仓快递不配送收货省份'),dict(carrier_code='UI_B',carrier_name='UI 快递乙',available=True,reason='')]}}))
            def match(route):
                body = route.request.post_data_json
                captured.append(('match', body))
                assert body['items'] == [{'id':shipment['id'],'control_version':shipment['control_version']}]
                if body['mode']=='MANUAL':
                    assert body['carrier_code']=='UI_B' and body['reason']=='客户指定可配送快递'
                    shipment.update(status='WAIT_DISPATCH', carrier_code='UI_B', carrier_name='UI 快递乙', carrier_match_status='MATCHED', carrier_match_mode='MANUAL', carrier_match_reason=body['reason'], control_version=shipment['control_version']+1)
                    fixture_detail.update(shipment)
                fulfill(route, {'code':200,'data':[dict(id=shipment['id'], shipment_sn=shipment['shipment_sn'], success=True, message='已匹配 UI 快递乙')]})
            page.route('**/order/orders/shipments/match-carrier', match)
            page.goto(BASE+'/oms-order/shipments?number=FH_CARRIER_UI', wait_until='networkidle')
            expect(page.locator('.shipment-workspace')).to_contain_text('匹配失败')
            page.get_by_role('button', name='手工选择', exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            expect(dialog).to_contain_text('北京')
            dialog.locator('.el-select').click()
            page.get_by_text('UI 快递乙（UI_B）', exact=True).click()
            dialog.locator('textarea').fill('客户指定可配送快递')
            page.screenshot(path=str(output/'manual-carrier-ui-fixture.png'), full_page=True, animations='disabled')
            dialog.get_by_role('button', name='确认快递', exact=True).click()
            expect(dialog).to_contain_text('已匹配 UI 快递乙')
            dialog.get_by_role('button', name='关闭', exact=True).click()
            expect(page.locator('.shipment-workspace')).to_contain_text('手工确认')
            expect(page.locator('.shipment-workspace')).to_contain_text('待下发仓库')
            page.get_by_role('button', name='FH_CARRIER_UI', exact=True).click()
            expect(page.locator('.shipment-detail:visible')).to_contain_text('客户指定可配送快递')
            page.locator('.shipment-detail:visible').get_by_role('button',name='关闭',exact=True).click()
            shipment.update(status='WAIT_CARRIER', carrier_code='', carrier_name='', carrier_match_status='FAILED')
            page.goto(BASE+'/oms-order/shipments?number=FH_CARRIER_UI', wait_until='networkidle')
            page.locator('.shipment-workspace .el-table__body-wrapper .el-checkbox').first.click()
            page.get_by_role('button', name='批量匹配快递', exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('button', name='开始匹配', exact=True).click()
            expect(dialog).to_contain_text('已匹配 UI 快递乙')
            dialog.get_by_role('button', name='关闭', exact=True).click()
            page.goto(BASE+'/oms-order/settings?tab=carriers', wait_until='networkidle')
            expect(page.locator('.carrier-settings')).to_contain_text('UI 快递甲')
            page.get_by_role('button',name='编辑资料',exact=True).first.click()
            dialog = page.locator('.el-dialog:visible')
            expect(dialog).to_contain_text('仓库服务范围')
            expect(dialog.locator('input').first).to_be_disabled()
            page.screenshot(path=str(output/'carrier-master-ui-fixture.png'), full_page=True, animations='disabled')
            dialog.get_by_role('button',name='保存快递资料',exact=True).click()
            expect(page.locator('.el-dialog:visible')).to_have_count(0)
            page.get_by_role('tab',name='快递规则',exact=True).click()
            page.get_by_role('button',name='配置规则',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            expect(dialog).to_contain_text('UI 验收店铺')
            dialog.get_by_role('button',name='下移',exact=True).first.click()
            page.screenshot(path=str(output/'carrier-rules-ui-fixture.png'), full_page=True, animations='disabled')
            dialog.get_by_role('button',name='保存匹配规则',exact=True).click()
            expect(page.locator('.el-dialog:visible')).to_have_count(0)
            assert next(body for name,body in captured if name=='config')['policy']['carrier_order']==['UI_B','UI_A']
            assert next(body for name,body in captured if name=='master')['revision']==1
            assert len([body for name,body in captured if name=='match'])==2
            assert not errors, errors
            browser.close()
        print(json.dumps({'live_api':'passed','ui_catalog_rules_manual_batch':'passed','live_carriers':len(catalog['carriers']),'live_shipments':live['total'],'real_business_mutations':0},ensure_ascii=False))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__': main()
