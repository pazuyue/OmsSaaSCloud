"""Read-only UI acceptance; preserves the operator's paused compensation switches."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

OUT=Path(__file__).resolve().parents[2]/'outputs/immediate-fulfillment'

def main():
    case=json.loads((OUT/'case.json').read_text(encoding='utf8'))
    result=json.loads((OUT/'verification.json').read_text(encoding='utf8'))
    token=login()
    try:
        jobs=request('/schedule/job/list?pageNum=1&pageSize=100&invokeTarget=fulfillmentScanTask.',token=token)['rows']
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1700,'height':1200})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[];writes=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            def readonly(route):
                if route.request.method=='GET':route.continue_()
                else:writes.append(route.request.url.split('?')[0]);route.abort()
            page.route('**/prod-api/**',readonly)
            page.goto(BASE+'/monitor/job?scope=fulfillment',wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').first.locator('tbody tr')).to_have_count(7)
            for job in jobs:
                row=page.locator('.el-table__body-wrapper tr').filter(has_text=job['jobName']).first
                expect(row).to_contain_text(job['cronExpression'])
                if job['status']=='0':expect(row.locator('.el-switch')).to_have_class(re.compile('is-checked'))
                else:expect(row.locator('.el-switch')).not_to_have_class(re.compile('is-checked'))
            expect(page.locator('body')).to_contain_text('仓库出库回传记账补偿扫描')
            expect(page.locator('body')).to_contain_text('销售出库单同步补偿扫描')
            page.screenshot(path=str(OUT/'compensation-jobs.png'),full_page=True,animations='disabled')
            page.goto(case['business_url'],wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            drawer.get_by_role('tab',name='发货单',exact=True).click()
            groups=drawer.locator('.order-warehouse-group');expect(groups).to_have_count(2)
            for i,w in enumerate(case['warehouses']):
                expect(groups.nth(i)).to_contain_text(w['code'])
                expect(groups.nth(i)).to_contain_text('已出库 '+str(w['quantity'])+' / '+str(w['quantity'])+' 件')
                expect(groups.nth(i)).to_contain_text(str(w['package_count'])+' 个包裹')
            groups.first.scroll_into_view_if_needed()
            page.screenshot(path=str(OUT/'immediate-order.png'),full_page=True,animations='disabled')
            for row in result['sales_documents']:
                shipment,sn,*_=row.split('\t')
                w=next(w for w in case['warehouses'] if str(w['shipment_id'])==shipment)
                page.goto(BASE+'/oms-supplychain/wmsTickets?documentType=SALE_OUT&noSn='+w['shipment_sn'],wait_until='networkidle')
                page.get_by_role('button',name=sn,exact=True).click()
                drawer=page.locator('.el-drawer:visible');expect(drawer).to_contain_text(case['sku'])
                drawer.get_by_role('tab',name='实际出库记录',exact=True).click()
                expect(drawer).to_contain_text('扣账成功')
                expect(drawer).to_contain_text('出库回传即时记账')
                page.screenshot(path=str(OUT/('sales-'+shipment+'.png')),full_page=True,animations='disabled')
            assert not errors and not writes,(errors,writes)
            browser.close()
        (OUT/'ui-verification.json').write_text(json.dumps({'renamed_compensation_jobs_visible':True,'operator_switches_preserved':True,'two_virtual_warehouses_three_packages_visible':True,'two_actual_sales_movements_visible':True,'browser_errors':errors,'browser_writes':writes},ensure_ascii=False,indent=2),encoding='utf8')
        print('PASS immediate UI: compensation names and switches, two shipments/three packages, actual sales records; GET-only')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
