"""New isolated cases only. Exercise review events, stock signals and the actual Quartz fallback.
Never submit XS documents to WMS or send platform shipment notifications.
"""
import json,sys,time
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
import verify_allocation_rules_v2 as fixture
from test_multi_virtual_shipping import sql,stock,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/auto-allocation'

def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();manifest=OUT/'cases.json'
    cases=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else []
    def api(path,data=None):return fixture.api(path,token,data,post=data is not None)
    def get(case):return api('/order/orders/business/'+str(case['order_id']))['data']
    def wait(case,predicate,message):return wait_for(lambda:get(case),predicate,message)
    try:
        if not cases:
            stamp=datetime.now().strftime('%Y%m%d%H%M%S')
            for index,kind in enumerate(['full','replenished','waiting'],1):
                key=stamp+str(index)
                cases.append({'kind':kind,'case_id':key,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'AUTO_ALLOC_'+key,'channel_name':'[自动分仓验收] '+kind+' '+key,'seller':'ACCEPT_AUTO_'+key,'platform_tid':'996'+key,'platform_oid':'996'+key+'01','warehouses':[{'code':'AUTO_V1_'+key,'quantity':6,'package_count':0},{'code':'AUTO_V2_'+key,'quantity':4,'package_count':0}]})
            save(manifest,cases)
        for case in cases:
            fixture.MANIFEST=OUT/('case-'+case['kind']+'.json')
            if fixture.MANIFEST.exists():case.update(json.loads(fixture.MANIFEST.read_text(encoding='utf-8')))
            save(fixture.MANIFEST,case)
            if not case.get('auto_configured'):
                current=fixture.prepare_case(case,token,approve=False)
                config=api('/order/orders/allocation-config/'+str(case['channel_id']))['data']
                policy={**config['policy'],'execution_mode':'AUTO','auto_replenish':1,'strategy':'REGION_FIRST','max_virtual_warehouses':2,'max_shipments':3,'min_first_allocation_percent':0}
                if case['kind']!='full':policy['warehouse_order']=[case['warehouses'][0]['code']]
                api('/order/orders/allocation-config',{'channel_id':case['channel_id'],'revision':config['revision'],'policy':policy})
                case['auto_configured']=True;save(fixture.MANIFEST,case)
            if not case.get('approved'):
                current=get(case)
                if case['kind']=='full' and not current['manual_hold']:
                    result=api('/order/orders/business/hold-batch',{'orders':[{'id':current['id'],'control_version':current['control_version']}],'reason':'验收挂起拦截自动分仓'})['data'][0];assert result['success'],result
                    current=get(case)
                if current['review_status']!='APPROVED':
                    result=api('/order/orders/business/approve-batch',{'orders':[{'id':current['id'],'control_version':current['control_version']}],'reason':'自动分仓新方案验收'})['data'][0];assert result['success'],result
                case['approved']=True;save(fixture.MANIFEST,case)
            if case['kind']=='full' and not case.get('completed'):
                current=wait(case,lambda o:o['auto_allocation']['state']=='WAIT_CONDITION','Held order did not wait');assert current['allocation_quantity']==0 and not current['shipments']
                before=stock(case)
                api('/order/orders/business/'+str(case['order_id'])+'/auto-allocation/pause',{'control_version':current['control_version']})
                current=get(case)
                result=api('/order/orders/business/resume-batch',{'orders':[{'id':current['id'],'control_version':current['control_version']}],'reason':'验收解除挂起不覆盖本单暂停'})['data'][0];assert result['success']
                current=get(case);assert current['auto_allocation']['state']=='PAUSED' and stock(case)==before
                with sync_playwright() as p:
                    browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                    page.goto(BASE+'/oms-order/settings?tab=allocation&channelId='+str(case['channel_id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='店铺分仓规则',exact=True);expect(dialog.get_by_text('自动分仓',exact=True)).to_be_visible();expect(dialog.get_by_text('自动补充分仓',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'settings.png'),full_page=True);dialog.get_by_role('button',name='取消',exact=True).click()
                    page.goto(BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']),wait_until='networkidle');panel=page.locator('.el-drawer:visible .auto-allocation');expect(panel.get_by_text('本单已暂停',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'paused.png'),full_page=True);panel.get_by_role('button',name='恢复自动分仓',exact=True).click();assert not errors,errors;browser.close()
                current=wait(case,lambda o:o['allocation_status']=='FULL','UI resume did not allocate');assert sorted(s['quantity'] for s in current['shipments'])==[4,6]
                case['completed']=True;case['paused_survives_order_resume']=True;case['ui_resume_verified']=True
            elif case['kind']=='replenished' and not case.get('completed'):
                current=wait(case,lambda o:o['allocation_quantity']==6 and o['auto_allocation']['state']=='WAIT_STOCK','Partial allocation did not wait');assert len(current['shipments'])==1
                case['partial_shipment_sn']=current['shipments'][0]['shipment_sn'];case['waiting_verified']=True;save(fixture.MANIFEST,case)
                if not case.get('restocked'):
                    warehouse=case['warehouses'][0]
                    api('/inventory/api/WmsInventory/addInventory?company_code=QM',{'relationSn':'TEST-AUTO-RESTOCK-'+case['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':warehouse['code'],'skuSn':case['sku'],'batchCode':'AUTO-RESTOCK-'+case['case_id'],'zpActualNumber':4,'zpAvailableNumber':4,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0}})
                    case['restocked']=True;save(fixture.MANIFEST,case)
                current=wait(case,lambda o:o['allocation_status']=='FULL','Actual Quartz stock wake did not supplement');assert sorted(s['quantity'] for s in current['shipments'])==[4,6];assert case['partial_shipment_sn'] in [s['shipment_sn'] for s in current['shipments']]
                assert all(s['store_code']==case['warehouses'][0]['code'] for s in current['shipments']);case['completed']=True;case['stock_signal_and_quartz_verified']=True
            elif case['kind']=='waiting':
                current=wait(case,lambda o:o['allocation_quantity']==6 and o['auto_allocation']['state']=='WAIT_STOCK','Retained shortage case missing');assert len(current['shipments'])==1;case['waiting_verified']=True
            current=get(case);assert all(s['wms_dispatch_state']=='NONE' and s['shipped_quantity']==s['package_count']==0 for s in current['shipments'])
            case['order_sn']=current['order_sn'];case['auto_state']=current['auto_allocation']['state'];case['allocation_quantity']=current['allocation_quantity'];case['business_url']=BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']);case['shipments']=[{k:s[k] for k in ['shipment_sn','quantity','store_code','wms_dispatch_state']} for s in current['shipments']];case['stock_after']=stock(case)
            save(fixture.MANIFEST,case);save(manifest,cases);print(json.dumps({k:case[k] for k in ['kind','order_sn','auto_state','allocation_quantity','business_url']},ensure_ascii=True),flush=True)
        waiting=next(c for c in cases if c['kind']=='waiting')
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page()
            page.goto(waiting['business_url'],wait_until='networkidle');panel=page.locator('.el-drawer:visible .auto-allocation');expect(panel.get_by_text('等待补货',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'waiting-detail.png'),full_page=True);browser.close()
        save(OUT/'verification.json',{'cases':[{k:c[k] for k in ['kind','order_sn','order_id','auto_state','allocation_quantity','business_url','shipments']} for c in cases],'no_warehouse_submission':True,'no_actual_outbound':True,'no_platform_send':True,'actual_quartz_stock_wake':True,'held_order_blocked':True,'pause_survives_order_resume':True,'ui_resume_verified':True})
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
