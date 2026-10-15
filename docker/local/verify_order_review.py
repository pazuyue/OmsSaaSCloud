"""New review-rule acceptance. Stock is received through the normal internal API.
Keep all WMS submissions and real platform shipment notifications off.
"""
import json,sys,time
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import wait_for,stock,sql
import verify_allocation_rules_v2 as fixture
OUT=Path(__file__).resolve().parents[2]/'outputs/order-review'
def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();manifest=OUT/'cases.json'
    cases=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else []
    def api(path,data=None):return fixture.api(path,token,data,post=data is not None)
    def get(case):return api('/order/orders/business/'+str(case['order_id']))['data']
    def wait(case,predicate,message):return wait_for(lambda:get(case),predicate,message)
    def configure(case,credential):
        if case.get('review_configured'):return
        config=api('/order/orders/review-config/'+str(case['channel_id']))['data'];p={**config['policy'],'mode':'AUTO','stock_condition':'ANY' if case['kind']=='allow-shortage' else 'FULL_RESERVED'}
        if case['kind']=='human-confirmed':p.update(amount_enabled=1,amount_threshold='10.00',quantity_enabled=1,quantity_threshold=10,review_skus=[case['sku']])
        api('/order/orders/review-config',{'channel_id':case['channel_id'],'revision':config['revision'],'policy':p})
        config=api('/order/orders/allocation-config/'+str(case['channel_id']))['data'];p={**config['policy'],'execution_mode':'AUTO','auto_replenish':1,'strategy':'REGION_FIRST','max_virtual_warehouses':2,'max_shipments':3}
        api('/order/orders/allocation-config',{'channel_id':case['channel_id'],'revision':config['revision'],'policy':p});case['review_configured']=True;save(fixture.MANIFEST,case)
    try:
        if not cases:
            stamp=datetime.now().strftime('%Y%m%d%H%M%S')
            for i,kind in enumerate(['allow-shortage','stock-replenished','human-confirmed','wait-stock'],1):
                key=stamp+str(i);cases.append({'kind':kind,'case_id':key,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'RV_'+key,'channel_name':'[审单验收] '+kind+' '+key,'seller':'REVIEW_'+key,'platform_tid':'997'+key,'platform_oid':'997'+key+'01','warehouses':[{'code':'REVIEW_V1_'+key,'quantity':6,'package_count':0},{'code':'REVIEW_V2_'+key,'quantity':4,'package_count':0}]})
            save(manifest,cases)
        for case in cases:
            fixture.MANIFEST=OUT/('case-'+case['kind']+'.json')
            if fixture.MANIFEST.exists():case.update(json.loads(fixture.MANIFEST.read_text(encoding='utf-8')))
            save(fixture.MANIFEST,case)
            if not case.get('seeded'):
                fixture.prepare_case(case,token,approve=False,before_conversion=configure,order_quantity=10 if case['kind']=='human-confirmed' else 14,require_reserved=False);case['seeded']=True;save(fixture.MANIFEST,case)
            if case['kind']=='allow-shortage':
                current=wait(case,lambda o:o['review_decision']=='AUTO_APPROVED','Unrestricted stock did not allow automatic review');assert current['inventory_status']=='WAIT_RESERVE' and current['allocation_quantity']==0 and not current['shipments']
            elif case['kind']=='stock-replenished':
                if not case.get('restocked'):
                    current=wait(case,lambda o:o['review_decision']=='WAIT_STOCK','Full reservation rule did not wait');assert current['review_status']=='PENDING' and current['allocation_quantity']==0
                    case['wait_stock_verified']=True;save(fixture.MANIFEST,case)
                    w=case['warehouses'][0];api('/inventory/api/WmsInventory/addInventory?company_code=QM',{'relationSn':'TEST-REVIEW-RESTOCK-'+case['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':w['code'],'skuSn':case['sku'],'batchCode':'REVIEW-RESTOCK-'+case['case_id'],'zpActualNumber':4,'zpAvailableNumber':4,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0}});case['restocked']=True;save(fixture.MANIFEST,case)
                job=next(j for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows'] if j['invokeTarget']=='fulfillmentScanTask.allocation()')
                if job['status']!='0' and (get(case)['review_decision']!='AUTO_APPROVED' or get(case)['allocation_quantity']!=14):
                    request('/schedule/job/run',{'jobId':job['jobId'],'jobGroup':job['jobGroup']},token=token,method='PUT');case['compensation_invocation']='manual_existing_paused_job';save(fixture.MANIFEST,case)
                current=wait(case,lambda o:o['review_decision']=='AUTO_APPROVED' and o['allocation_quantity']==14,'Stock receipt did not wake review and allocation');assert sorted(s['quantity'] for s in current['shipments'])==[4,10]
            elif case['kind']=='human-confirmed':
                if not case.get('human_approved'):
                    current=wait(case,lambda o:o['review_decision']=='MANUAL_REQUIRED','Threshold rules did not require human review');hits=[c['code'] for c in current['review_assessment']['checks'] if c['result']=='HIT'];assert set(hits)=={'AMOUNT','QUANTITY','SKU'};before=stock(case);version=current['control_version'];preview=api('/order/orders/business/'+str(case['order_id'])+'/review-preview')['data'];assert preview['read_only'] and preview['decision']=='MANUAL_REQUIRED';assert stock(case)==before and get(case)['control_version']==version
                    with sync_playwright() as p:
                        browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                        page.goto(BASE+'/oms-order/settings?tab=review&channelId='+str(case['channel_id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='店铺审单规则',exact=True);expect(dialog.get_by_text('库存审核条件',exact=True)).to_be_visible();expect(dialog.get_by_text('要求整单逻辑预占完成',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'settings.png'),full_page=True);dialog.get_by_role('button',name='取消',exact=True).click()
                        page.goto(BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']),wait_until='networkidle');panel=page.locator('.el-drawer:visible .review-evidence');expect(panel.get_by_text('命中，转人工',exact=True)).to_have_count(3);page.screenshot(path=str(OUT/'manual-required.png'),full_page=True);panel.get_by_role('button',name='按当前规则试算',exact=True).click();trial=page.get_by_role('dialog',name='当前规则试算',exact=True);expect(trial).to_contain_text('不改变审核结果');trial.get_by_role('button',name='关闭',exact=True).click();assert stock(case)==before
                        panel.get_by_role('button',name='人工审核通过',exact=True).click();approval=page.get_by_role('dialog',name='审核通过',exact=True);approval.locator('textarea').fill('已核对金额、件数及指定SKU，确认正常订单');approval.get_by_role('button',name='确认审核通过',exact=True).click();expect(approval.get_by_text('成功',exact=True)).to_be_visible();approval.get_by_role('button',name='关闭',exact=True).click();assert not errors,errors;browser.close()
                    case['human_approved']=True;case['preview_read_only_verified']=True;save(fixture.MANIFEST,case)
                current=wait(case,lambda o:o['review_decision']=='HUMAN_APPROVED' and o['allocation_quantity']==10,'Human approval did not continue allocation');assert any(e['action']=='HUMAN_APPROVE' for e in current['review_events']);assert current['review_assessment']['decision']=='MANUAL_REQUIRED'
                # Current-rule trial must not overwrite the human result.
                api('/order/orders/business/'+str(case['order_id'])+'/review-preview');assert get(case)['review_decision']=='HUMAN_APPROVED'
            else:
                current=wait(case,lambda o:o['review_decision']=='WAIT_STOCK','Retained review-wait case missing');assert current['review_status']=='PENDING' and current['allocation_quantity']==0 and not current['shipments']
            assert all(s['wms_dispatch_state']=='NONE' and s['shipped_quantity']==s['package_count']==0 for s in current['shipments'])
            case.update(order_sn=current['order_sn'],review_decision=current['review_decision'],review_status=current['review_status'],allocation_quantity=current['allocation_quantity'],business_url=BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']),completed=True)
            case['shipments']=[{k:s[k] for k in ['shipment_sn','quantity','store_code','wms_dispatch_state']} for s in current['shipments']];save(fixture.MANIFEST,case);save(manifest,cases);print(json.dumps({k:case[k] for k in ['kind','order_id','order_sn','review_decision','allocation_quantity','business_url']},ensure_ascii=True),flush=True)
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1300});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();waiting=next(c for c in cases if c['kind']=='wait-stock');page.goto(waiting['business_url'],wait_until='networkidle');panel=page.locator('.el-drawer:visible .review-evidence');panel.scroll_into_view_if_needed();expect(panel.locator('.heading .el-tag')).to_have_text('待库存满足');page.screenshot(path=str(OUT/'waiting-stock.png'),full_page=True);browser.close()
        save(OUT/'verification.json',{'cases':[{k:c[k] for k in ['kind','order_id','order_sn','review_decision','allocation_quantity','business_url','shipments']} for c in cases],'compensation_schedule_preserved':'paused; existing job invoked manually for acceptance','stock_condition_configurable':True,'stock_progress_triggers_review':True,'approval_triggers_allocation':True,'human_hits_and_note_retained':True,'preview_read_only':True,'warehouse_submissions':0,'actual_outbound':0,'platform_notifications':0})
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
