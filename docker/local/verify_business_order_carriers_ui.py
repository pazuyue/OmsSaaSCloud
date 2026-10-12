"""Exercise carrier actions inside business order details using intercepted fixtures only."""
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
        orders = request('/order/orders/business/list?pageSize=100', token=token)['rows']
        source = request('/order/orders/business/'+str(orders[0]['id']), token=token)['data']
        order_id = 999999970
        shipments = []
        def shipment(index, status='WAIT_CARRIER', allowed=True):
            matched = status != 'WAIT_CARRIER'
            return dict(id=999990000+index, shipment_sn='FH_BUSINESS_UI_'+str(index), order_id=order_id, order_sn='SO_BUSINESS_CARRIER_UI', channel_id=source['channel_id'], platform_tid='999999999770', generation_no=index, owner_code='UI_OWNER', real_store_code='UI_R'+str(index), real_store_name='UI 实体仓 '+str(index), quantity=1, shipped_quantity=0, status=status, create_time='2026-10-12 11:00:00', control_version=1, carrier_code='UI_A' if matched else '', carrier_name='UI 快递甲' if matched else '', carrier_match_status='MATCHED' if matched else 'FAILED', carrier_match_mode='AUTO', carrier_match_reason='' if matched else '该区域暂无可用快递', can_match_carrier=allowed, fulfillment_block='' if allowed else '来源订单已人工挂起')
        shipments.extend([shipment(1), shipment(2), shipment(3, allowed=False), shipment(4, 'WAIT_DISPATCH'), shipment(5, 'WAREHOUSE_PROCESSING', False)])
        reads, calls, errors = [], [], []
        scenario = {'network_failure':False}
        def business():
            result = copy.deepcopy(source)
            total = len(shipments)
            result.update(id=order_id, order_sn='SO_BUSINESS_CARRIER_UI', platform_tid='999999999770', status='WAIT_ALLOCATION', business_status='WAIT_CARRIER' if any(s['status']=='WAIT_CARRIER' for s in shipments) else 'WAIT_DISPATCH', allocation_status='FULL', allocation_quantity=total, allocation_error='', allocation_enabled=1, logical_inventory_status='RESERVED', inventory_status='RESERVED', warehouse_inventory_status='OCCUPIED', warehouse_occupied_quantity=total, warehouse_required_quantity=total, demand_quantity=total, reserved_quantity=total, pending_quantity=0, pending_allocation_quantity=0, shipment_count=total, shipment_quantity=total, shipped_quantity=0, shipments=copy.deepcopy(shipments), receiver_name='UI 收件人', receiver_phone='脱敏号码', receiver_address='UI 验收地址', review_status='APPROVED', manual_hold=0, processing_status='NORMAL', system_hold_reason='', release_blocks=[], logs=[], can_generate_shipments=False, can_allocate=False, can_hold=False, can_resume=False, can_void=False)
            result['lines'] = [dict(goods_name='UI 商品', sku_sn='UI_SKU', platform_oid='99999999977001', quantity=total, reserved_quantity=total, payment=0)]
            result.update(channel_name='UI 验收店铺', payment=100, stock_message='逻辑预占已完成，商品已按实体仓分仓',review_reason='人工审核通过',review_by='UI 审核人')
            return result
        def fulfill(route, body): route.fulfill(status=200, content_type='application/json', body=json.dumps(body, ensure_ascii=False))
        def detail(route):
            reads.append(1)
            fulfill(route, {'code':200,'data':business()})
        def match(route):
            body = route.request.post_data_json
            calls.append(body)
            assert len(body['items']) <= 20
            if scenario['network_failure'] and len(calls)==2:
                route.abort('failed')
                return
            results = []
            for item in body['items']:
                s = next(s for s in shipments if s['id']==item['id'])
                assert item['control_version']==s['control_version'] and s['can_match_carrier']
                success = body['mode']=='MANUAL' or s['id']!=999990002
                if success:
                    s.update(status='WAIT_DISPATCH', carrier_code=body['carrier_code'] if body['mode']=='MANUAL' else 'UI_A', carrier_name='UI 快递乙' if body['mode']=='MANUAL' else 'UI 快递甲', carrier_match_status='MATCHED', carrier_match_mode=body['mode'], carrier_match_reason=body.get('reason',''), control_version=s['control_version']+1)
                else:
                    s.update(carrier_match_status='FAILED',carrier_match_reason='该区域暂无可用快递',control_version=s['control_version']+1)
                results.append(dict(id=s['id'], shipment_sn=s['shipment_sn'], success=success, message='已匹配 '+s['carrier_name'] if success else '该区域暂无可用快递'))
            fulfill(route, {'code':200,'data':results})
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width':1550,'height':1100})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page = context.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.route('**/order/orders/business/list?*', lambda r: fulfill(r, {'code':200,'rows':[business()],'total':1}))
            page.route('**/order/orders/business/'+str(order_id), detail)
            page.route('**/order/orders/shipments/*/carrier-options', lambda r: fulfill(r, {'code':200,'data':{'receiver_province':'北京','carriers':[dict(carrier_code='UI_B',carrier_name='UI 快递乙',available=True,reason='')]}}))
            page.route('**/order/orders/shipments/match-carrier', match)
            def open_order():
                page.goto(BASE+'/oms-order/business?view=business&orderId='+str(order_id), wait_until='networkidle')
                drawer = page.locator('.order-panel:visible')
                expect(drawer).to_contain_text('SO_BUSINESS_CARRIER_UI')
                drawer.get_by_role('tab',name='发货单',exact=True).click()
                return page.locator('.order-shipments:visible')
            tab = open_order()
            expect(tab.get_by_role('button',name='匹配全部待匹配发货单（2）',exact=True)).to_be_enabled()
            def row(index): return tab.locator('.el-table__body-wrapper').first.locator('tr').nth(index)
            def action_row(index): return tab.locator('.el-table__fixed-right .el-table__fixed-body-wrapper').first.locator('tr').nth(index)
            try:
                expect(action_row(0).get_by_role('button',name='匹配快递',exact=True)).to_be_visible()
            except AssertionError:
                print('Shipment tab diagnostic:', tab.inner_text(), tab.get_by_role('button').all_text_contents(), tab.locator('.el-table__body-wrapper').count())
                page.screenshot(path=str(output/'business-carriers-diagnostic.png'),full_page=True)
                raise
            expect(action_row(0).get_by_role('button',name='手工选择',exact=True)).to_be_visible()
            expect(action_row(2).get_by_role('button',name='匹配快递',exact=True)).to_have_count(0)
            expect(action_row(3).get_by_role('button',name='调整快递',exact=True)).to_be_visible()
            expect(action_row(4).get_by_role('button',name='调整快递',exact=True)).to_have_count(0)
            tab.get_by_role('button',name='匹配全部待匹配发货单（2）',exact=True).scroll_into_view_if_needed()
            page.screenshot(path=str(output/'business-carriers-entry-ui-fixture.png'),full_page=True,animations='disabled')
            previous_reads = len(reads)
            tab.get_by_role('button',name='匹配全部待匹配发货单（2）',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('button',name='开始匹配',exact=True).click()
            expect(dialog).to_contain_text('成功')
            expect(dialog).to_contain_text('失败')
            expect(dialog).to_contain_text('该区域暂无可用快递')
            expect(dialog).to_contain_text('已匹配 UI 快递甲')
            expect(tab.get_by_role('button',name='匹配全部待匹配发货单（1）',exact=True)).to_be_visible()
            assert len(reads)>previous_reads and [s['id'] for s in calls[0]['items']]==[999990001,999990002]
            dialog.get_by_role('button',name='关闭',exact=True).click()
            # Single automatic retry uses that shipment's refreshed version.
            action_row(1).get_by_role('button',name='匹配快递',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('button',name='开始匹配',exact=True).click()
            expect(dialog).to_contain_text('该区域暂无可用快递')
            assert len(calls[-1]['items'])==1 and calls[-1]['items'][0]['id']==999990002
            dialog.get_by_role('button',name='关闭',exact=True).click()
            action_row(1).get_by_role('button',name='手工选择',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.locator('.el-select').click()
            page.get_by_text('UI 快递乙（UI_B）',exact=True).click()
            dialog.locator('textarea').fill('客户指定可配送快递')
            dialog.get_by_role('button',name='确认快递',exact=True).click()
            expect(dialog).to_contain_text('已匹配 UI 快递乙')
            expect(row(1)).to_contain_text('手工确认')
            dialog.get_by_role('button',name='关闭',exact=True).click()
            action_row(1).get_by_role('button',name='调整快递',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            expect(dialog).to_contain_text('手工选择 / 调整快递')
            expect(dialog.locator('.el-select input')).to_have_value('UI 快递乙（UI_B）')
            dialog.get_by_role('button',name='取消',exact=True).click()
            tab.get_by_role('button',name='匹配全部待匹配发货单（0）',exact=True).scroll_into_view_if_needed()
            page.screenshot(path=str(output/'business-carriers-refreshed-ui-fixture.png'),full_page=True,animations='disabled')
            # 41 pending documents: first batch completes, second loses its response;
            # the third is never submitted, and confirmed documents are not retried.
            shipments[:] = [shipment(index) for index in range(10,51)]
            calls.clear()
            scenario['network_failure'] = True
            tab = open_order()
            tab.get_by_role('button',name='匹配全部待匹配发货单（41）',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('button',name='开始匹配',exact=True).click()
            expect(dialog).to_contain_text('本次请求结果未确认，请刷新后核对再重试')
            expect(dialog).to_contain_text('尚未提交，请刷新后重新匹配')
            expect(tab.get_by_role('button',name='匹配全部待匹配发货单（21）',exact=True)).to_be_visible()
            assert len(calls)==2 and all(len(c['items'])==20 for c in calls)
            assert len({s['id'] for c in calls for s in c['items']})==40
            page.screenshot(path=str(output/'business-carriers-batch-recovery-ui-fixture.png'),full_page=True,animations='disabled')
            dialog.get_by_role('button',name='关闭',exact=True).click()
            shipments[:] = [shipment(70),shipment(71)]
            calls.clear()
            scenario['network_failure'] = False
            tab = open_order()
            tab.get_by_role('button',name='匹配全部待匹配发货单（2）',exact=True).click()
            dialog = page.locator('.el-dialog:visible')
            dialog.get_by_role('button',name='开始匹配',exact=True).click()
            expect(dialog).to_contain_text('已匹配 UI 快递甲')
            expect(tab.get_by_role('button',name='匹配全部待匹配发货单（0）',exact=True)).to_be_disabled()
            state = page.locator('.order-panel:visible .detail-statuses > span').first.locator('.el-tag')
            expect(state).to_contain_text('待下发仓库')
            dialog.get_by_role('button',name='关闭',exact=True).click()
            assert not errors, errors
            browser.close()
        print(json.dumps({'business_detail_single_manual_adjust_batch_refresh':'passed','blocked_and_confirmed_excluded':'passed','41_document_batch_failure_recovery':'passed','real_business_mutations':0},ensure_ascii=False))
    finally:
        request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__': main()
