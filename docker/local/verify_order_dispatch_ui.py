"""Read-only live preview and intercepted dispatch/query UI fixtures; never submit real shipments."""
import copy
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE


def main():
    token=login()
    output=Path(__file__).resolve().parents[2]/'outputs/order-dispatch'
    output.mkdir(parents=True,exist_ok=True)
    try:
        live=request('/order/orders/shipments/list',token=token)
        assert live['code']==200 and all('wms_dispatch_state' in s and 'can_dispatch' in s for s in live['rows'])
        candidates=[s for s in live['rows'] if s['can_dispatch']]
        if candidates:
            preview=request('/order/orders/shipments/dispatch-preview',{'items':[{'id':candidates[0]['id'],'control_version':candidates[0]['control_version']}]},token)['data']
            assert len(preview)==1 and 'success' in preview[0]
            assert all('secret' not in str(s.get('route',{})).lower() and 'endpoint' not in s.get('route',{}) for s in preview)
        for state in ['DISPATCHING','PART_DISPATCHED','WAREHOUSE_PROCESSING']:
            assert request('/order/orders/business/list?documentStatus='+state,token=token)['code']==200
        source=request('/order/orders/business/'+str(request('/order/orders/business/list',token=token)['rows'][0]['id']),token=token)['data']
        shipments=[]
        def shipping(index,state='NONE'):
            status='DISPATCHING' if state=='UNKNOWN' else 'WAIT_DISPATCH'
            return dict(id=999999800+index,shipment_sn='FH_WMS_UI_'+str(index),order_id=999999880,order_sn='SO_WMS_UI',channel_id=source['channel_id'],channel_name='UI 验收店铺',platform_tid='999999999880',generation_no=1,owner_code='UI_OWNER',real_store_code='UI_R1',real_store_name='UI 验收仓',quantity=5,shipped_quantity=0,status=status,create_time='2026-10-12 12:00:00',created_by='UI 验收',control_version=1,carrier_code='SF',carrier_name='UI 快递',warehouse_carrier_code='SF',carrier_match_status='MATCHED',carrier_match_mode='AUTO',carrier_match_reason='按规则匹配',can_match_carrier=state=='NONE',fulfillment_block='',receiver_province='北京',wms_dispatch_state=state,wms_error='仓库请求超时，结果待确认' if state=='UNKNOWN' else '仓库明确拒绝下发：字段错误' if state=='FAILED' else '',wms_query_message='',wms_external_order='',can_dispatch=state in ['NONE','FAILED'],can_query_warehouse=state!='NONE')
        shipments.extend([shipping(1),shipping(2,'UNKNOWN'),shipping(3,'FAILED')])
        target=dict(connection_id=7,connection_name='UI 仓库连接',provider='QIMEN',api_version='2.0',environment='TEST',external_warehouse='EXT-W',external_owner='EXT-O')
        captured=[]
        blocked={'id':None}
        def fulfill(route,body):route.fulfill(status=200,content_type='application/json',body=json.dumps(body,ensure_ascii=False))
        def find(ident):return next(s for s in shipments if s['id']==ident)
        def business():
            b=copy.deepcopy(source)
            b.update(id=999999880,order_sn='SO_WMS_UI',platform_tid='999999999880',channel_name='UI 验收店铺',status='WAIT_ALLOCATION',business_status='DISPATCHING' if any(s['status']=='DISPATCHING' for s in shipments) else 'PART_DISPATCHED',review_status='APPROVED',review_reason='人工审核通过',manual_hold=0,processing_status='NORMAL',system_hold_reason='',allocation_status='FULL',allocation_quantity=15,allocation_error='',demand_quantity=15,reserved_quantity=15,pending_quantity=0,pending_allocation_quantity=0,shipment_count=3,shipment_quantity=15,warehouse_accepted_quantity=sum(s['quantity'] for s in shipments if s['status']=='WAREHOUSE_PROCESSING'),shipped_quantity=0,shipments=copy.deepcopy(shipments),receiver_name='UI 收件人',receiver_phone='脱敏号码',receiver_address='UI 验收地址',stock_message='已逻辑预占，已分仓',inventory_status='RESERVED',logical_inventory_status='RESERVED',warehouse_inventory_status='OCCUPIED',warehouse_occupied_quantity=15,warehouse_required_quantity=15,release_blocks=[],can_generate_shipments=False,can_allocate=False,can_void=False,can_hold=False,can_resume=False,logs=[])
            b['lines']=[dict(sku_sn='UI_SKU',goods_name='UI 商品',platform_oid='99999999988001',quantity=15,reserved_quantity=15,payment=0)]
            return b
        def dispatch(route):
            data=route.request.post_data_json;captured.append(('dispatch',data));results=[]
            for item in data['items']:
                row=find(item['id']);assert row['can_dispatch'] and item['control_version']==row['control_version'] and row['id']!=blocked['id']
                row.update(status='DISPATCHING',wms_dispatch_state='PENDING',can_dispatch=False,can_match_carrier=False,can_query_warehouse=True,wms_error='',control_version=row['control_version']+1)
                results.append(dict(id=row['id'],success=True,message='已提交，等待仓库下发结果'))
            fulfill(route,{'code':200,'data':results})
        def query(route):
            ident=int(route.request.url.split('/shipments/')[1].split('/')[0]);row=find(ident);captured.append(('query',ident))
            if ident==shipments[1]['id']:
                row['wms_query_message']='仓库查无单据，请核对受理记录；本次不会重新下发'
                result=dict(dispatch_state='UNKNOWN',query_success=False,query_message=row['wms_query_message'])
            else:
                row.update(status='WAREHOUSE_PROCESSING',wms_dispatch_state='ACCEPTED',wms_external_order='EXT-UI-'+str(ident),wms_query_message='仓库状态：ACCEPT',can_dispatch=False,can_match_carrier=False,control_version=row['control_version']+1)
                result=dict(dispatch_state='ACCEPTED',query_success=True,external_order=row['wms_external_order'],query_message='仓库状态：ACCEPT')
            fulfill(route,{'code':200,'data':result})
        def preview(route):
            data=route.request.post_data_json;captured.append(('preview',data));results=[]
            for item in data['items']:
                row=find(item['id']);valid=row['id']!=blocked['id']
                results.append(dict(id=row['id'],success=valid,route=target if valid else None,message='可提交下发' if valid else '虚仓为自动虚拟出库，不能下发真实WMS'))
            fulfill(route,{'code':200,'data':results})
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/shipments',wait_until='networkidle');expect(page.get_by_role('button',name='批量下发仓库',exact=True)).to_be_visible();page.screenshot(path=str(output/'shipment-dispatch-live.png'),full_page=True,animations='disabled')
            page.route('**/order/orders/shipments/list?*',lambda r:fulfill(r,{'code':200,'rows':shipments,'total':3}))
            page.route('**/order/orders/shipments/dispatch-preview',preview);page.route('**/order/orders/shipments/dispatch',dispatch);page.route('**/order/orders/shipments/*/query-warehouse',query)
            def detail(route):
                ident=int(route.request.url.rsplit('/',1)[1]);row=find(ident);d=dict(**row,receiver_name='UI 收件人',receiver_phone='脱敏号码',receiver_address='UI 地址',warehouse_target=target,lines=[dict(sku_sn='UI_SKU',goods_name='UI 商品',platform_oid='99999999988001',store_code='UI_V1',batch_id=1,allocation_id=1,source_type='PUBLIC_EXTRA',quantity=5,shipped_quantity=0)],logs=[])
                fulfill(route,{'code':200,'data':d})
            for row in shipments:page.route('**/order/orders/shipments/'+str(row['id']),detail)
            page.route('**/order/orders/business/'+str(999999880),lambda r:fulfill(r,{'code':200,'data':business()}));page.route('**/order/orders/business/list?*',lambda r:fulfill(r,{'code':200,'rows':[business()],'total':1}))
            page.route('**/supplychain/wmsIntegration/logs?*',lambda r:fulfill(r,{'code':200,'data':[dict(id=777,provider='QIMEN',connection_name='UI 仓库连接',ticket_sn=shipments[0]['shipment_sn'],action='OUTBOUND_CREATE',direction='OUT',result='SUCCESS',duration_ms=12,create_time='2026-10-12 12:00:00',error_info='')]}))
            page.goto(BASE+'/oms-order/shipments?number=FH_WMS_UI',wait_until='networkidle')
            def action_row(index):return page.locator('.shipment-workspace:visible .el-table__fixed-right .el-table__fixed-body-wrapper tr').nth(index)
            expect(action_row(1).get_by_role('button',name='重试下发',exact=True)).to_have_count(0)
            action_row(0).get_by_role('button',name='下发仓库',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog).to_contain_text('测试环境');expect(dialog).to_contain_text('EXT-W');page.screenshot(path=str(output/'dispatch-preview-ui-fixture.png'),full_page=True,animations='disabled');dialog.get_by_role('button',name='确认下发（1 张）',exact=True).click();expect(dialog).to_contain_text('已提交');dialog.get_by_role('button',name='关闭',exact=True).click();expect(page.locator('.shipment-workspace')).to_contain_text('等待下发');expect(action_row(0).get_by_role('button',name='调整快递',exact=True)).to_have_count(0)
            action_row(0).get_by_role('button',name='查询仓库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='查询仓库结果',exact=True).click();expect(dialog).to_contain_text('仓库状态：ACCEPT');dialog.get_by_role('button',name='关闭',exact=True).click();expect(page.locator('.shipment-workspace')).to_contain_text('仓库已接收');expect(page.locator('.shipment-workspace')).to_contain_text('已发货 0 件')
            sent=len([c for c in captured if c[0]=='dispatch']);action_row(1).get_by_role('button',name='查询仓库',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='查询仓库结果',exact=True).click();expect(dialog).to_contain_text('本次不会重新下发');expect(dialog).to_contain_text('待核对');dialog.get_by_role('button',name='关闭',exact=True).click();assert len([c for c in captured if c[0]=='dispatch'])==sent
            action_row(2).get_by_role('button',name='重试下发',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.get_by_role('button',name='确认下发（1 张）',exact=True).click();expect(dialog).to_contain_text('已提交');dialog.get_by_role('button',name='关闭',exact=True).click()
            page.get_by_role('button',name=shipments[0]['shipment_sn'],exact=True).first.click();drawer=page.locator('.shipment-detail:visible');expect(drawer).to_contain_text('EXT-W / EXT-O');drawer.get_by_role('button',name='仓库交互日志',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog).to_contain_text('下发发货单');dialog.get_by_role('button',name='关闭',exact=True).click();drawer.get_by_role('button',name='关闭',exact=True).click()
            shipments[0].update(status='WAIT_DISPATCH',wms_dispatch_state='NONE',can_dispatch=True,can_query_warehouse=False);shipments[2].update(status='WAIT_DISPATCH',wms_dispatch_state='FAILED',can_dispatch=True);blocked['id']=shipments[2]['id']
            page.goto(BASE+'/oms-order/business?view=business&orderId=999999880',wait_until='networkidle');panel=page.locator('.order-panel:visible');panel.get_by_role('tab',name='发货单',exact=True).click();tab=page.locator('.order-shipments:visible');tab.get_by_role('button',name='下发全部待下发发货单（2）',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog).to_contain_text('自动虚拟出库');dialog.get_by_role('button',name='确认下发（1 张）',exact=True).click();expect(dialog).to_contain_text('已提交');assert [i['id'] for i in next(data for name,data in reversed(captured) if name=='dispatch')['items']]==[shipments[0]['id']];dialog.get_by_role('button',name='关闭',exact=True).click();tab.get_by_role('button',name='下发全部待下发发货单（1）',exact=True).scroll_into_view_if_needed();page.screenshot(path=str(output/'business-dispatch-ui-fixture.png'),full_page=True,animations='disabled');assert not errors,errors;browser.close()
        print(json.dumps({'live_api_preview':'passed','single_batch_retry_query_logs_business_detail':'passed','unknown_not_recreated':'passed','real_business_mutations':0},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
