"""Verify the retained completed shipment; browser writes are restricted to its WMS query."""
import json
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
SN='XS870a08cfdade45b5be4ad60d3cc802ab'
DETAIL='/order/orders/shipments/3'
OUTPUT=Path(__file__).resolve().parents[2]/'outputs/warehouse-shipping'
def signature(row):
    return {key:row.get(key) for key in ['id','shipment_sn','status','quantity','shipped_quantity','packaged_quantity','package_count','unpacked_quantity']} | {'lines':[{key:line.get(key) for key in ['id','sku_sn','quantity','shipped_quantity','packaged_quantity']} for line in row['lines']]}
def main():
    OUTPUT.mkdir(parents=True,exist_ok=True);token=login();errors=[];queries=[];blocked=[];api_errors=[]
    try:
        before=request(DETAIL,token=token)['data'];assert before['status']=='SHIPPED' and before['shipped_quantity']==6
        assert before['wms_fulfillment_state']=='SHIPPED' and '尚未更新' not in before['wms_progress_message']
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
            def restrict(route):
                path=urlparse(route.request.url).path
                if route.request.method=='GET':route.continue_()
                elif route.request.method=='POST' and path=='/prod-api'+DETAIL+'/query-warehouse':queries.append(path);route.continue_()
                else:blocked.append({'path':path,'method':route.request.method});route.abort()
            def check_response(response):
                if '/prod-api/order/orders/shipments/list' in response.url:
                    reply=response.json()
                    if reply.get('code',200)!=200:api_errors.append(reply.get('msg','list failed'))
            page.on('response',check_response)
            page.route('**/prod-api/**',restrict)
            page.goto(BASE+'/oms-order/shipments?number='+SN,wait_until='networkidle')
            page.locator('.shipment-workspace .el-table__fixed-right').get_by_role('button',name='核对仓库结果',exact=True).first.click()
            dialog=page.locator('.shipment-dispatch-dialog .el-dialog:visible')
            expect(dialog.locator('.el-tag').get_by_text('全部出库',exact=True)).to_be_visible();expect(dialog.locator('.fulfillment-summary')).to_contain_text('实际出库 6 / 6 件');expect(dialog.locator('.fulfillment-summary')).to_contain_text('1 个包裹');expect(dialog.locator('.row-message')).not_to_contain_text('尚未更新')
            dialog.get_by_role('button',name='核对仓库结果（1 张）',exact=True).click()
            expect(dialog.get_by_role('button',name='刷新进度',exact=True)).to_be_visible();expect(dialog.locator('.el-tag').get_by_text('全部出库',exact=True)).to_be_visible();expect(dialog.locator('.row-message')).to_contain_text('库存扣账完成');expect(dialog.locator('.row-message')).not_to_contain_text('等待完整')
            body=dialog.locator('.dispatch-lines .el-table__body-wrapper').first;expect(body).to_contain_text('2 / 0 件');expect(body).to_contain_text('4 / 0 件')
            dialog.get_by_role('button',name='刷新进度',exact=True).click();expect(dialog.locator('.fulfillment-summary')).to_contain_text('实际出库 6 / 6 件');page.wait_for_timeout(400);expect(page.locator('.el-message--error')).to_have_count(0);expect(page.locator('.shipment-workspace .el-alert--error')).to_have_count(0);page.screenshot(path=str(OUTPUT/'completed-warehouse-reconciliation.png'));browser.close()
        after=request(DETAIL,token=token)['data'];assert signature(before)==signature(after),'Query must not change outbound quantities or parcels'
        assert len(queries)==1 and not blocked and not errors and not api_errors
        OUTPUT.joinpath('reconciliation-verification.json').write_text(json.dumps({'shipment':SN,'result':signature(after),'warehouse_status':after['wms_warehouse_status'],'fulfillment_state':after['wms_fulfillment_state'],'progress_message':after['wms_progress_message'],'browser_queries':len(queries),'no_dispatch_or_inventory_requests':True,'query_did_not_change_fulfillment':True,'browser_errors':errors},ensure_ascii=False,indent=2),encoding='utf8')
        print('PASS retained shipment: completed 6/6, 1 parcel, SKU actuals 2+4, refreshed WMS query without duplicate outbound')
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
