"""Live read-only API/menu checks plus explicitly mocked UI fixtures.
Backend lifecycle is tested against order_workspace_test. UI fixtures never write business or stock data.
"""
import json, copy, re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE


def main():
    token=login()
    output=Path(__file__).resolve().parents[2]/'outputs/order-shipments'
    output.mkdir(parents=True,exist_ok=True)
    try:
        live=request('/order/orders/shipments/list',token=token)
        assert live['code']==200 and all(0<=s['shipped_quantity']<=s['quantity'] for s in live['rows'])
        assert all('receiver_phone' not in s and 'receiver_address' not in s for s in live['rows'])
        for state in ['WAIT_CARRIER','WAIT_DISPATCH','DISPATCHING','WAREHOUSE_PROCESSING','PART_SHIPPED','SHIPPED','VOIDING','VOIDED']:
            rows=request('/order/orders/shipments/list?status='+state,token=token)['rows']
            assert all(s['status']==state for s in rows)
        orders=request('/order/orders/business/list?pageSize=100',token=token)['rows']
        assert orders
        actual=next((o for o in orders if o['status']=='WAIT_ALLOCATION'),orders[0])
        source=request('/order/orders/business/'+str(actual['id']),token=token)['data']
        assert all(k in source for k in ['shipments','shipment_count','shipped_quantity','pending_allocation_quantity'])
        # Deliberate UI fixtures for non-empty states; production tables are not changed.
        shipment=dict(id=999999991,shipment_sn='FH_UI_ACCEPTANCE',order_id=999999992,order_sn='SO_UI_ACCEPTANCE',channel_id=actual['channel_id'],channel_name=actual.get('channel_name','验收店铺'),platform_tid='999999999123456',generation_no=1,owner_code='UI_OWNER',real_store_code='UI_R1',real_store_name='UI 验收仓',quantity=6,shipped_quantity=0,status='WAIT_CARRIER',order_status='WAIT_ALLOCATION',create_time='2026-10-11 12:00:00',created_by='UI 验收',cancelled_at=None)
        detail={**shipment,'receiver_name':'验收收件人','receiver_phone':'脱敏号码','receiver_address':'验收地址','fulfillment_block':'','lines':[dict(id=1,allocation_id=101,platform_oid='99999999912345601',sku_sn='UI_SKU',goods_name='UI 验收商品',store_code='UI_V1',batch_id=101,source_type='PUBLIC_EXTRA',quantity=6,shipped_quantity=0)]}
        business=copy.deepcopy(source)
        business.update(id=shipment['order_id'],order_sn=shipment['order_sn'],platform_tid=shipment['platform_tid'],status='WAIT_ALLOCATION',business_status='PART_ALLOCATED',review_status='APPROVED',manual_hold=0,processing_status='NORMAL',system_hold_reason='',change_pending=0,fulfillment_block_reason='',inventory_status='RESERVED',logical_inventory_status='RESERVED',demand_quantity=10,reserved_quantity=10,pending_quantity=0,pending_allocation_quantity=4,allocation_status='PART',allocation_quantity=6,warehouse_occupied_quantity=6,warehouse_required_quantity=10,warehouse_inventory_status='PART_OCCUPIED',allocation_error='',shipments=[shipment],shipment_count=1,shipment_quantity=6,shipped_quantity=0,unshipped_quantity=10,release_blocks=[],release_eligible=True,can_generate_shipments=False,can_allocate=False,can_void=False,can_review=False,can_hold=False,can_resume=False,can_reserve=False)
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1500,'height':1050})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/shipments',wait_until='networkidle')
            expect(page.locator('h1')).to_have_text('发货单')
            expect(page.get_by_role('tab',name='待匹配快递',exact=True)).to_be_visible()
            expect(page.locator('.shipment-workspace .toolbar')).to_contain_text('共 '+str(live['total'])+' 张发货单')
            page.screenshot(path=str(output/'shipment-list-live.png'),full_page=True,animations='disabled')
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(actual['id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            expect(drawer).to_contain_text(actual['order_sn'])
            drawer.get_by_role('tab',name='发货单',exact=True).click()
            expect(drawer.get_by_text('已发货数量',exact=True)).to_be_visible()
            expect(drawer.get_by_text('待分仓数量',exact=True)).to_be_visible()
            page.screenshot(path=str(output/'order-shipments-live.png'),full_page=True,animations='disabled')
            def fulfill(route,body):route.fulfill(status=200,content_type='application/json',body=json.dumps(body,ensure_ascii=False))
            page.route('**/order/orders/shipments/list?*',lambda route:fulfill(route,{'code':200,'msg':'查询成功','rows':[shipment],'total':1}))
            page.route('**/order/orders/shipments/'+str(shipment['id']),lambda route:fulfill(route,{'code':200,'data':detail}))
            page.route('**/order/orders/business/'+str(shipment['order_id']),lambda route:fulfill(route,{'code':200,'data':business}))
            page.goto(BASE+'/oms-order/shipments?number=FH_UI_ACCEPTANCE',wait_until='networkidle')
            expect(page.locator('.shipment-workspace .toolbar')).to_contain_text('共 1 张发货单')
            # Element UI's fixed action column also renders hidden copies of cells.
            expect(page.locator('.shipment-workspace:visible').get_by_text('已发货 0 件',exact=True).first).to_be_visible()
            page.get_by_role('button',name=shipment['shipment_sn'],exact=True).first.click()
            shipping=page.locator('.el-drawer:visible')
            expect(shipping.get_by_text('待匹配快递',exact=True)).to_be_visible()
            expect(shipping.get_by_text('UI_SKU',exact=True)).to_be_visible()
            expect(shipping.get_by_text('分仓回执 101',exact=True)).to_be_visible()
            page.screenshot(path=str(output/'shipment-detail-ui-fixture.png'),full_page=True,animations='disabled')
            shipping.get_by_role('button',name='查看来源订单',exact=True).click()
            expect(page).to_have_url(re.compile('orderId='+str(shipment['order_id'])))
            order=page.locator('.el-drawer:visible')
            expect(order).to_contain_text(shipment['order_sn'])
            order.get_by_role('tab',name='发货单',exact=True).click()
            expect(order.locator('.el-tab-pane:visible').get_by_text('4 件',exact=True)).to_be_visible()
            order.get_by_role('button',name=shipment['shipment_sn'],exact=True).first.click()
            expect(page.locator('.shipment-detail:visible')).to_contain_text('UI 验收仓')
            assert not errors,errors
            browser.close()
        print(json.dumps({'live_api':'passed','live_navigation':'passed','ui_fixtures':'passed','shipment_status_filters':8,'live_shipments':live['total'],'data_mutations':0},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
