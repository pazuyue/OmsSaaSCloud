"""Warehouse dispatch UI acceptance: all order writes use intercepted fixtures."""
import copy,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

OUTPUT=Path(__file__).resolve().parents[2]/'outputs/shipment-dispatch'
IDS=[9900101,9900102,9900103]

def main():
    OUTPUT.mkdir(parents=True,exist_ok=True)
    token=login();calls=[];errors=[];detail_reads={};states={};transport_failure=False;fulfilled={}
    line=lambda ident,sku,qty,store,batch:dict(id=ident,sku_sn=sku,goods_name='验收商品 '+sku,spec='颜色:蓝;尺码:M',quantity=qty,platform_oid='9007664503219726110',store_code=store,batch_code=batch,batch_id=ident)
    fixtures=[]
    for index,qty in enumerate([6,4,2]):
        ident=IDS[index];warehouse='W1' if index==0 else 'W2'
        fixtures.append(dict(id=ident,shipment_sn='XS_UI_DISPATCH_00'+str(index+1),order_id=9900001,order_sn='SO_UI_PARTIAL_10',platform_tid='9001717917894739110',channel_id=1,channel_name='验收店铺',owner_code='OWNER',real_store_code=warehouse,real_store_name='华东仓' if index==0 else '华北仓',quantity=qty,shipped_quantity=0,packaged_quantity=0,package_count=0,unpacked_quantity=0,create_time='2026-10-13 11:00:00',created_by='验收操作人',generation_no=1,status='WAIT_CARRIER' if index==2 else 'WAIT_DISPATCH',carrier_name='' if index==2 else '顺丰',carrier_code='' if index==2 else 'SF',warehouse_carrier_code='' if index==2 else 'SF',carrier_match_status='NONE' if index==2 else 'MATCHED',carrier_match_mode='AUTO',control_version=1,wms_dispatch_state='NONE',virtual_outbound_state='NONE',outbound_mode=1,can_dispatch=index!=2,can_match_carrier=True,can_query_warehouse=False,fulfillment_block=''))
    lines={IDS[0]:[line(1,'SKU-A',2,'V1','B001'),line(2,'SKU-A',4,'V2','B002')],IDS[1]:[line(3,'SKU-B',4,'V3','B003')],IDS[2]:[line(4,'SKU-C',2,'V3','B004')]}
    def route_for(index):return dict(connection_name='华东仓奇门连接' if index==0 else '华北仓虎符连接',provider='QIMEN' if index==0 else 'JD_HUFU',environment='PRODUCTION' if index==0 else 'TEST',external_warehouse='EXT-W'+str(index+1),external_owner='EXT-OWNER')
    def document(ident,poll=False):
        row=copy.deepcopy(fixtures[IDS.index(ident)]);state=states.get(ident,'NONE')
        if state=='PENDING' and poll:
            detail_reads[ident]=detail_reads.get(ident,0)+1
            if detail_reads[ident]>=2:state='ACCEPTED' if ident==IDS[0] else 'UNKNOWN';states[ident]=state
        row.update(wms_dispatch_state=state,can_dispatch=state in ('NONE','FAILED') and ident!=IDS[2],can_match_carrier=state=='NONE',can_query_warehouse=state!='NONE',control_version=3 if state=='FAILED' else 2 if state!='NONE' else 1,lines=copy.deepcopy(lines[ident]),logs=[])
        if state!='NONE':row.update(warehouse_target=route_for(IDS.index(ident)),dispatch_lines=copy.deepcopy(lines[ident]),dispatch_quantity=row['quantity'],wms_error='仓库快递编码未开通' if state=='FAILED' else '请求超时，仓库受理结果待确认' if state=='UNKNOWN' else '',wms_external_order='WMS_UI_001' if state=='ACCEPTED' else '',status='WAREHOUSE_PROCESSING' if state=='ACCEPTED' else 'WAIT_DISPATCH' if state=='FAILED' else 'DISPATCHING')
        if ident in fulfilled:
            shipped=fulfilled[ident];row.update(shipped_quantity=shipped,packaged_quantity=shipped,package_count=1,status='SHIPPED' if shipped==row['quantity'] else 'PART_SHIPPED',wms_warehouse_status='DELIVERED',wms_query_message='等待完整出库明细回传，实际发货数量尚未更新')
            for index,item in enumerate(row['lines']):item['shipped_quantity']=min(item['quantity'],max(0,shipped-(2 if index else 0)))
        return row
    def intercept(handler):
        nonlocal transport_failure
        req=handler.request;path=urlparse(req.url).path;params=parse_qs(urlparse(req.url).query)
        calls.append({'method':req.method,'path':path})
        if path.endswith('/catalog'):reply={'code':200,'data':{'channels':[{'channel_id':1,'channel_name':'验收店铺'}]}}
        elif path.endswith('/shipments/list'):
            assert params.get('status',[''])[0] in ('','WAIT_CARRIER','WAIT_DISPATCH','DISPATCHING','WAREHOUSE_PROCESSING','PART_SHIPPED','SHIPPED','VOIDING','VOIDED'),'All-tab must submit empty status, never its auto-generated pane index'
            rows=[document(ident) for ident in IDS]
            if params.get('dispatchState',[''])[0]:rows=[row for row in rows if row['wms_dispatch_state']==params['dispatchState'][0]]
            reply={'code':200,'rows':rows,'total':len(rows)}
        elif path.endswith('/dispatch-preview'):
            assert req.method=='POST';body=req.post_data_json;reply={'code':200,'data':[]}
            for item in body['items']:
                assert item['id'] in IDS
                ident=item['id'];row=document(ident);blocked=ident==IDS[2]
                row.update(success=not blocked,message='请先确认发货单快递与仓库快递编码' if blocked else '明确下发失败，将按原下发快照重试' if states.get(ident)=='FAILED' else '可提交下发',lines=copy.deepcopy(lines[ident]),route=None if blocked else route_for(IDS.index(ident)),retry=states.get(ident)=='FAILED',snapshot_scope='FROZEN' if states.get(ident)=='FAILED' else 'CURRENT')
                reply['data'].append(row)
        elif path.endswith('/shipments/dispatch'):
            assert req.method=='POST';body=req.post_data_json;selected=[item['id'] for item in body['items']];assert selected and set(selected)<=set(IDS[:2]),'Blocked shipment must never be submitted'
            if transport_failure:
                for ident in selected:states[ident]='UNKNOWN'
                handler.abort('timedout');return
            for ident in selected:states[ident]='PENDING'
            reply={'code':200,'data':[{'id':ident,'success':True,'message':'已提交，等待仓库下发结果'} for ident in selected]}
        elif re.search(r'/shipments/[0-9]+/query-warehouse$',path):
            ident=int(path.split('/')[-2]);assert ident in IDS and req.method=='POST';states[ident]='ACCEPTED' if ident in fulfilled else 'FAILED';reply={'code':200,'data':{'dispatch_state':states[ident],'message':'仓库快递编码未开通' if ident not in fulfilled else '等待完整出库明细回传，实际发货数量尚未更新'}}
        elif re.search(r'/shipments/[0-9]+$',path):
            ident=int(path.split('/')[-1]);assert ident in IDS and req.method=='GET';reply={'code':200,'data':document(ident,True)}
        else:handler.abort();raise AssertionError('Unexpected order request: '+req.method+' '+path)
        handler.fulfill(status=200,content_type='application/json',body=json.dumps(reply,ensure_ascii=False))
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.on('pageerror',lambda error:errors.append(str(error)));page.route('**/prod-api/order/orders/**',intercept)
            page.goto(BASE+'/oms-order/shipments',wait_until='networkidle');page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'initial-list.png'))
            def list_row(ident):return page.locator('.shipment-workspace .el-table__body-wrapper tr').filter(has_text=fixtures[IDS.index(ident)]['shipment_sn'])
            def dialog():return page.locator('.shipment-dispatch-dialog .el-dialog:visible')
            def open_single():page.get_by_role('button',name='下发仓库',exact=True).first.click();expect(dialog().get_by_role('button',name='确认下发 1 张 / 6 件',exact=True)).to_be_enabled()
            page.get_by_role('button',name='XS_UI_DISPATCH_001',exact=True).click();drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('快递已确认，可以核对商品明细后下发仓库。',exact=True)).to_be_visible();expect(drawer.locator('.footer').get_by_role('button',name='下发仓库',exact=True)).to_be_visible()
            page.wait_for_timeout(350);bounds=drawer.locator('.footer').bounding_box();assert bounds['y']+bounds['height']<=1101,'Footer must stay within the viewport';page.screenshot(path=str(OUTPUT/'detail-fixed-actions.png'))
            drawer.locator('.footer').get_by_role('button',name='下发仓库',exact=True).click();expect(dialog().get_by_role('button',name='确认下发 1 张 / 6 件',exact=True)).to_be_enabled();dialog().get_by_role('button',name='取消',exact=True).click();expect(dialog()).not_to_be_visible();drawer.locator('.footer').get_by_role('button',name='关闭',exact=True).click();expect(drawer).not_to_be_visible()
            open_single();expect(dialog().get_by_text('验收商品 SKU-A',exact=True)).to_be_visible();expect(dialog().get_by_text('6 件',exact=True).last).to_be_visible()
            dialog().locator('.dispatch-lines .el-table__expand-icon').first.click();expect(dialog().get_by_text('B001',exact=True)).to_be_visible();expect(dialog().get_by_text('B002',exact=True)).to_be_visible();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'single-product-batches.png'))
            page.set_viewport_size({'width':390,'height':844});page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'single-mobile.png'));page.set_viewport_size({'width':1600,'height':1100});dialog().get_by_role('button',name='取消',exact=True).click();expect(dialog()).not_to_be_visible()
            page.locator('.shipment-workspace .el-table__header-wrapper .el-checkbox').first.click();page.get_by_role('button',name='批量下发仓库',exact=True).click();expect(dialog().get_by_role('button',name='确认下发 2 张 / 10 件',exact=True)).to_be_enabled();expect(dialog().get_by_text('华东仓',exact=True)).to_be_visible();expect(dialog().get_by_text('华北仓',exact=True)).to_be_visible();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'batch-warehouse-groups.png'))
            dialog().get_by_role('tab',name='不可下发（1）',exact=True).click();dialog().locator('.el-collapse-item__header').filter(has_text='XS_UI_DISPATCH_003').click();expect(dialog().get_by_role('button',name='匹配 / 调整快递',exact=True)).to_be_visible();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'blocked-action.png'))
            dialog().get_by_role('tab',name='可下发（2）',exact=True).click();dialog().get_by_role('button',name='确认下发 2 张 / 10 件',exact=True).click()
            expect(dialog().locator('.el-tag').get_by_text('仓库已接收',exact=True)).to_be_visible(timeout=15000);expect(dialog().locator('.el-tag').get_by_text('结果待确认',exact=True)).to_be_visible();assert len([call for call in calls if call['path'].endswith('/shipments/dispatch')])==1
            dialog().locator('.el-collapse-item__header').filter(has_text='XS_UI_DISPATCH_002').click();expect(dialog().get_by_role('button',name='核对仓库结果',exact=True)).to_be_visible();assert not dialog().get_by_role('button',name='重试下发',exact=True).count();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'accepted-and-unknown.png'))
            dialog().get_by_role('button',name='核对仓库结果',exact=True).click();expect(dialog().get_by_role('button',name='重试下发',exact=True)).to_be_enabled();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'definitive-failure.png'));dialog().get_by_role('button',name='重试下发',exact=True).click();expect(dialog().get_by_role('button',name='确认下发 1 张 / 4 件',exact=True)).to_be_enabled();expect(dialog().get_by_text('原下发商品快照',exact=False)).to_be_visible();dialog().get_by_role('button',name='取消',exact=True).click();expect(dialog()).not_to_be_visible()
            page.locator('.el-input__inner[placeholder="全部下发状态"]').click();page.locator('.el-select-dropdown:visible').get_by_text('下发失败',exact=True).click();page.get_by_role('button',name='查询',exact=False).click();expect(list_row(IDS[1])).to_be_visible();assert list_row(IDS[0]).count()==0;page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'failed-state-filter.png'))
            states.clear();detail_reads.clear();transport_failure=True;page.reload(wait_until='networkidle');open_single();dialog().get_by_role('button',name='确认下发 1 张 / 6 件',exact=True).click();expect(dialog().locator('.el-tag').get_by_text('结果待确认',exact=True)).to_be_visible();assert not dialog().get_by_role('button',name='重试下发',exact=True).count();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'submit-timeout.png'))
            before=len([call for call in calls if re.search(r'/shipments/[0-9]+$',call['path'])]);dialog().get_by_role('button',name='关闭',exact=True).click();expect(dialog()).not_to_be_visible();page.wait_for_timeout(3500);assert len([call for call in calls if re.search(r'/shipments/[0-9]+$',call['path'])])==before,'Closing must stop polling'
            transport_failure=False;states[IDS[0]]='ACCEPTED';fulfilled[IDS[0]]=2;page.reload(wait_until='networkidle')
            page.locator('.shipment-workspace .el-table__fixed-right').get_by_role('button',name='核对仓库结果',exact=True).first.click()
            expect(dialog().locator('.el-tag').get_by_text('部分出库',exact=True)).to_be_visible();expect(dialog().locator('.fulfillment-summary')).to_contain_text('实际出库 2 / 6 件');expect(dialog().locator('.row-message')).not_to_contain_text('尚未更新')
            dialog().get_by_role('button',name='核对仓库结果（1 张）',exact=True).click()
            expect(dialog().locator('.el-tag').get_by_text('部分出库',exact=True)).to_be_visible();expect(dialog().locator('.dispatch-lines .el-table__body-wrapper').first).to_contain_text('2 / 4 件')
            fulfilled[IDS[0]]=6;dialog().get_by_role('button',name='刷新进度',exact=True).click()
            expect(dialog().locator('.el-tag').get_by_text('全部出库',exact=True)).to_be_visible();expect(dialog().locator('.fulfillment-summary')).to_contain_text('实际出库 6 / 6 件');expect(dialog().locator('.dispatch-lines .el-table__body-wrapper').first).to_contain_text('6 / 0 件');expect(dialog().locator('.row-message')).not_to_contain_text('等待完整')
            page.screenshot(path=str(OUTPUT/'completed-reconciliation.png'))
            assert not errors,errors
            OUTPUT.joinpath('verification.json').write_text(json.dumps({'fixture_only':True,'no_live_dispatch':True,'single_partial_quantity':6,'separate_source_batches':2,'mixed_selection_submitted_only_eligible':True,'auto_progress_read_only':True,'unknown_requires_query':True,'definitive_failure_retry_snapshot':True,'dispatch_state_filter':True,'timeout_no_resubmit':True,'close_stops_polling':True,'posted_receipts_override_stale_query':True,'partial_and_completed_quantities_refresh':True,'browser_errors':errors},ensure_ascii=False,indent=2),encoding='utf8')
            browser.close()
        print('PASS shipment dispatch: product snapshots, mixed warehouse batches, progress, unknown/query/retry, filters and timeout; no live order writes')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
