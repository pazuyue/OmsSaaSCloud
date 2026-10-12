"""Live read-only previews and intercepted virtual-outbound fixtures; never posts real stock."""
import copy
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE


def main():
    token=login()
    output=Path(__file__).resolve().parents[2]/'outputs/order-virtual-outbound'
    output.mkdir(parents=True,exist_ok=True)
    try:
        live=request('/order/orders/shipments/list',token=token)
        assert all('can_virtual_outbound' in row and 'outbound_mode' in row for row in live['rows'])
        candidates=[row for row in live['rows'] if row['can_virtual_outbound']]
        if candidates:
            row=candidates[0]
            preview=request('/order/orders/shipments/virtual-outbound-preview',{'items':[{'id':row['id'],'control_version':row['control_version']}]},token)['data']
            assert preview[0]['success'] and preview[0]['quantity']==row['quantity']-row['shipped_quantity']
            assert not row['can_dispatch']
        for stage in ['PART_SHIPPED','SHIPPED']:
            assert request('/order/orders/business/list?documentStatus='+stage,token=token)['code']==200
        for status in ['PART_CONSUMED','CONSUMED']:
            assert request('/order/orders/business/list?status='+status,token=token)['code']==200
        source=request('/order/orders/business/'+str(request('/order/orders/business/list',token=token)['rows'][0]['id']),token=token)['data']
        def shipping(index,state='NONE',mode=2):
            return dict(id=999999900+index,shipment_sn='FH_VIRTUAL_UI_'+str(index),order_id=999999990,order_sn='SO_VIRTUAL_UI',channel_id=source['channel_id'],channel_name='UI 验收店铺',platform_tid='999999999990',generation_no=1,owner_code='UI_OWNER',real_store_code='UI_R1',real_store_name='UI 验收仓',quantity=10,shipped_quantity=0,status='DISPATCHING' if state=='UNKNOWN' else 'WAIT_DISPATCH',create_time='2026-10-12 15:00:00',created_by='UI 验收',control_version=1,carrier_code='SF',carrier_name='UI 快递',carrier_match_status='MATCHED',carrier_match_mode='AUTO',carrier_match_reason='按规则匹配',can_match_carrier=state=='NONE',fulfillment_block='',outbound_mode=mode,wms_dispatch_state='NONE',wms_error='',can_dispatch=mode==1,can_query_warehouse=False,virtual_outbound_state=state,virtual_outbound_quantity=4 if state!='NONE' else 0,virtual_outbound_error='原出库结果未确认' if state=='UNKNOWN' else '库存余额异常' if state=='FAILED' else '',can_virtual_outbound=mode==2 and state=='NONE',can_retry_virtual_outbound=state=='FAILED',can_query_virtual_outbound=state in ['UNKNOWN','FAILED'])
        shipments=[shipping(1),shipping(2,'UNKNOWN'),shipping(3,'FAILED'),shipping(4,mode=1)]
        captured=[];pending={};blocked={'id':None}
        def find(ident):return next(s for s in shipments if s['id']==ident)
        def fulfill(route,body):route.fulfill(status=200,content_type='application/json',body=json.dumps(body,ensure_ascii=False))
        def detail(route):
            row=find(int(route.request.url.rsplit('/',1)[1]));data=copy.deepcopy(row)
            data.update(receiver_name='UI 收件人',receiver_phone='脱敏号码',receiver_address='UI 地址',logs=[],lines=[dict(id=row['id']+100,allocation_id=row['id']+200,platform_oid='99999999999001',sku_sn='UI_SKU',goods_name='UI 商品',quantity=10,shipped_quantity=row['shipped_quantity'],store_code='UI_V1',batch_id=1,stock_source_id=1,source_type='PUBLIC_EXTRA')])
            fulfill(route,{'code':200,'data':data})
        def preview(route):
            body=route.request.post_data_json;captured.append(('preview',body));results=[]
            for item in body['items']:
                row=find(item['id']);quantity=sum(line['quantity'] for line in item['lines']) if 'lines' in item else row['quantity']-row['shipped_quantity'];valid=row['id']!=blocked['id'] and row['can_virtual_outbound']
                results.append(dict(id=row['id'],success=valid,quantity=quantity if valid else 0,message='可虚拟出库' if valid else '店铺未允许部分发货'))
            fulfill(route,{'code':200,'data':results})
        def submit(route):
            body=route.request.post_data_json;captured.append(('submit',body));results=[]
            for item in body['items']:
                row=find(item['id']);assert row['can_virtual_outbound'] and row['id']!=blocked['id'] and item['control_version']==row['control_version'];quantity=sum(line['quantity'] for line in item['lines']) if 'lines' in item else row['quantity']-row['shipped_quantity'];pending[row['id']]=quantity
                row.update(status='DISPATCHING',virtual_outbound_state='PENDING',virtual_outbound_quantity=quantity,can_virtual_outbound=False,can_match_carrier=False,can_query_virtual_outbound=True,control_version=row['control_version']+1)
                results.append(dict(id=row['id'],success=True,message='已提交虚拟出库，等待库存确认'))
            fulfill(route,{'code':200,'data':results})
        def query(route):
            row=find(int(route.request.url.split('/shipments/')[1].split('/')[0]));captured.append(('query',row['id']))
            if row['id']==shipments[1]['id']:result=dict(id=row['id'],success=False,message='库存暂无原出库回执，本次仅核对，不重新记账')
            else:
                row['shipped_quantity']+=pending.pop(row['id']);row.update(status='SHIPPED' if row['shipped_quantity']==10 else 'PART_SHIPPED',virtual_outbound_state='COMPLETED',virtual_outbound_error='',can_query_virtual_outbound=False,can_virtual_outbound=row['shipped_quantity']<10,control_version=row['control_version']+1)
                result=dict(id=row['id'],success=True,message='原虚拟出库已确认，发货数量已更新')
            fulfill(route,{'code':200,'data':result})
        def retry(route):
            row=find(int(route.request.url.split('/shipments/')[1].split('/')[0]));assert route.request.post_data_json['control_version']==row['control_version'];captured.append(('retry',row['id']));pending[row['id']]=4;row.update(status='DISPATCHING',virtual_outbound_state='PENDING',can_retry_virtual_outbound=False,can_query_virtual_outbound=True,control_version=row['control_version']+1)
            fulfill(route,{'code':200,'data':dict(id=row['id'],success=True,message='已提交原虚拟出库任务重试')})
        def business():
            data=copy.deepcopy(source);shipped=sum(s['shipped_quantity'] for s in shipments)
            data.update(id=999999990,order_sn='SO_VIRTUAL_UI',platform_tid='999999999990',channel_name='UI 验收店铺',status='WAIT_ALLOCATION',business_status='PART_SHIPPED' if shipped else 'WAIT_DISPATCH',review_status='APPROVED',manual_hold=0,processing_status='NORMAL',system_hold_reason='',allocation_status='FULL',allocation_quantity=40,demand_quantity=40,reserved_quantity=40,pending_quantity=0,pending_allocation_quantity=0,shipment_count=4,shipment_quantity=40,shipped_quantity=shipped,warehouse_accepted_quantity=0,warehouse_inventory_status='PART_OUTBOUND' if shipped else 'OCCUPIED',warehouse_occupied_quantity=40-shipped,warehouse_required_quantity=40,warehouse_outbound_quantity=shipped,logical_inventory_status='PART_CONSUMED' if shipped else 'RESERVED',logical_active_quantity=40-shipped,logical_outbound_quantity=shipped,shipments=copy.deepcopy(shipments),can_void=False,can_allocate=False,can_generate_shipments=False,receiver_name='UI 收件人',receiver_phone='脱敏号码',receiver_address='UI 地址',release_blocks=[],logs=[])
            data['lines']=[dict(id=1,sku_sn='UI_SKU',goods_name='UI 商品',platform_oid='99999999999001',quantity=40,reserved_quantity=40,pending_quantity=0,shipped_quantity=shipped,logical_active_quantity=40-shipped)]
            return data
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/shipments',wait_until='networkidle');expect(page.get_by_role('button',name='批量虚拟出库',exact=True)).to_be_visible();page.screenshot(path=str(output/'virtual-outbound-live.png'),full_page=True,animations='disabled')
            page.route('**/order/orders/shipments/list?*',lambda r:fulfill(r,{'code':200,'rows':shipments,'total':4}));page.route('**/order/orders/shipments/virtual-outbound-preview',preview);page.route('**/order/orders/shipments/virtual-outbound',submit);page.route('**/order/orders/shipments/*/query-virtual-outbound',query);page.route('**/order/orders/shipments/*/retry-virtual-outbound',retry)
            for row in shipments:page.route('**/order/orders/shipments/'+str(row['id']),detail)
            page.route('**/order/orders/business/999999990',lambda r:fulfill(r,{'code':200,'data':business()}));page.route('**/order/orders/business/list?*',lambda r:fulfill(r,{'code':200,'rows':[business()],'total':1}))
            page.goto(BASE+'/oms-order/shipments?number=FH_VIRTUAL_UI',wait_until='networkidle')
            def actions(index):return page.locator('.shipment-workspace:visible .el-table__fixed-right .el-table__fixed-body-wrapper tr').nth(index)
            expect(actions(0).get_by_role('button',name='下发仓库',exact=True)).to_have_count(0);expect(actions(3).get_by_role('button',name='虚拟出库',exact=True)).to_have_count(0)
            actions(0).get_by_role('button',name='虚拟出库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.locator('.el-input-number input').fill('4');dialog.locator('.el-input-number input').blur();dialog.get_by_role('button',name='核对出库数量',exact=True).click();expect(dialog).to_contain_text('可虚拟出库');page.screenshot(path=str(output/'partial-virtual-preview-fixture.png'),full_page=True,animations='disabled');dialog.get_by_role('button',name='确认虚拟出库（4 件）',exact=True).click();expect(dialog).to_contain_text('等待库存确认');dialog.get_by_role('button',name='关闭',exact=True).click();assert shipments[0]['shipped_quantity']==0
            actions(0).get_by_role('button',name='核对出库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='核对出库回执',exact=True).click();expect(dialog).to_contain_text('已更新');dialog.get_by_role('button',name='关闭',exact=True).click();assert shipments[0]['shipped_quantity']==4;expect(actions(0).get_by_role('button',name='虚拟出库',exact=True)).to_be_visible()
            sent=len([r for r in captured if r[0]=='submit']);actions(1).get_by_role('button',name='核对出库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='核对出库回执',exact=True).click();expect(dialog).to_contain_text('不重新记账');dialog.get_by_role('button',name='关闭',exact=True).click();assert len([r for r in captured if r[0]=='submit'])==sent
            actions(2).get_by_role('button',name='重试虚拟出库',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-input-number')).to_have_count(0);dialog.get_by_role('button',name='确认重试原任务',exact=True).click();expect(dialog).to_contain_text('原虚拟出库任务重试');dialog.get_by_role('button',name='关闭',exact=True).click()
            page.get_by_role('button',name=shipments[0]['shipment_sn'],exact=True).first.click();drawer=page.locator('.shipment-detail:visible');drawer.get_by_role('button',name='虚拟出库',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-input-number input')).to_have_value('6');dialog.get_by_role('button',name='取消',exact=True).click();drawer.get_by_role('button',name='关闭',exact=True).click()
            # Business-detail batch preview excludes a blocked fixture, and keeps the WMS entry separate.
            shipments[2].update(status='WAIT_DISPATCH',virtual_outbound_state='NONE',can_virtual_outbound=True,can_retry_virtual_outbound=False,can_query_virtual_outbound=False);blocked['id']=shipments[2]['id']
            page.screenshot(path=str(output/'after-detail-fixture.png'),full_page=True,animations='disabled');page.locator('.shipment-workspace .toolbar button').filter(has_text='刷新').click();page.locator('.shipment-workspace .el-table__body-wrapper').first.locator('tr .el-checkbox').nth(0).click();page.locator('.shipment-workspace .el-table__body-wrapper').first.locator('tr .el-checkbox').nth(2).click();page.get_by_role('button',name='批量虚拟出库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='核对出库数量',exact=True).click();expect(dialog).to_contain_text('未允许部分发货');dialog.get_by_role('button',name='取消',exact=True).click()
            page.goto(BASE+'/oms-order/business?view=business&orderId=999999990',wait_until='networkidle');panel=page.locator('.order-panel:visible');panel.get_by_role('tab',name='发货单',exact=True).click();tab=page.locator('.order-shipments:visible');tab.get_by_role('button',name='虚拟出库全部待出库发货单（2）',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='核对出库数量',exact=True).click();expect(dialog).to_contain_text('未允许部分发货');dialog.get_by_role('button',name='确认虚拟出库（6 件）',exact=True).click();expect(dialog).to_contain_text('已提交');assert next(body for action,body in reversed(captured) if action=='submit')['items']==[{'id':shipments[0]['id'],'control_version':3}];dialog.get_by_role('button',name='关闭',exact=True).click();expect(dialog).not_to_be_visible();page.screenshot(path=str(output/'business-virtual-outbound-fixture.png'),full_page=True,animations='disabled');assert not errors,errors;browser.close()
        print(json.dumps({'live_preview':'passed','partial_batch_retry_query_and_mode_entries':'passed','unknown_never_posted':'passed','real_stock_mutations':0},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
