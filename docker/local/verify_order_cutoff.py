"""Retain new local TEST cases for cancellation, replan and blocked outbound review.
Never changes Quartz switches or existing business records; all WMS traffic targets the local simulator.
"""
import json,subprocess,sys,time,xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import wait_for,stock,sql
import verify_allocation_rules_v2 as fixture
from simulate_qimen_delivery import send,mark_simulated_delivery
OUT=Path(__file__).resolve().parents[2]/'outputs/order-cutoff'
def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();manifest=OUT/'cases.json';cases=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else []
    def api(path,data=None):return fixture.api(path,token,data,post=data is not None)
    def get(c):return api('/order/orders/business/'+str(c['order_id']))['data']
    def wait(c,predicate,message):return wait_for(lambda:get(c),predicate,message)
    def allocate(c):
        o=get(c);result=api('/order/orders/business/allocate-batch',{'items':[{'id':o['id'],'control_version':o['control_version']}]})['data'];assert result[0]['success'],result[0].get('message');return get(c)
    def dispatch(c):
        o=get(c);items=[{'id':s['id'],'control_version':s['control_version']} for s in o['shipments'] if s['can_dispatch']]
        if items:assert all(r['success'] for r in api('/order/orders/shipments/dispatch',{'items':items})['data'])
        due=sql("SELECT shipment_id FROM oms_order_shipment_dispatch WHERE company_code='QM' AND state IN ('PENDING','PROCESSING','UNKNOWN') AND next_attempt<=NOW()",'qm_oms_saas_order')
        assert {int(v) for v in due.splitlines() if v} <= {s['id'] for s in o['shipments']},'Other pending documents exist; do not run a company-wide scan for acceptance'
        job=next(j for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows'] if j['invokeTarget']=='fulfillmentScanTask.dispatch()')
        request('/schedule/job/run',{'jobId':job['jobId'],'jobGroup':job['jobGroup']},token=token,method='PUT')
        return wait(c,lambda o:all(s['wms_dispatch_state']=='ACCEPTED' for s in o['shipments'] if s['status']!='VOIDED'),'Local WMS acceptance incomplete')
    def cutoff(c,mode,ids=None):
        o=get(c);body={'mode':mode,'reason':'[验收测试] '+c['kind'],'control_version':o['control_version']}
        if ids is not None:body['shipment_ids']=ids
        api('/order/orders/business/'+str(o['id'])+'/cutoff',body)
        return wait(c,lambda o:o['cutoff_state']!='PROCESSING','Cutoff pending; original operation retained')
    def capture(c,stage):
        with sync_playwright() as p:
            b=p.chromium.launch(headless=True);ctx=b.new_context(viewport={'width':1560,'height':1100});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible').first
            button=drawer.get_by_role('button',name='截单 / 查看处理结果',exact=True);button.scroll_into_view_if_needed();button.click();d=page.get_by_role('dialog',name='订单截单与撤回',exact=True);expect(d.get_by_text('仓库截停',exact=True).first).to_be_visible();page.screenshot(path=str(OUT/(stage+'.png')),full_page=True);assert not errors,errors;b.close()
    try:
        jobs_before={j['jobId']:(j['status'],j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']}
        if not cases:
            stamp=datetime.now().strftime('%Y%m%d%H%M%S')
            for i,kind in enumerate(['replan','partial-terminate','warehouse-void','review-pending'],1):
                key=stamp+str(i);cases.append({'kind':kind,'case_id':key,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'CF_'+key,'channel_name':'[截单验收] '+kind+' '+key,'seller':'CUTOFF_'+key,'platform_tid':'996'+key,'platform_oid':'996'+key+'01','warehouses':[{'code':'CUTOFF_V1_'+key,'quantity':6,'package_count':0},{'code':'CUTOFF_V2_'+key,'quantity':4,'package_count':0}]})
            save(manifest,cases)
        for c in cases:
            fixture.MANIFEST=OUT/('case-'+c['kind']+'.json')
            if fixture.MANIFEST.exists():c.update(json.loads(fixture.MANIFEST.read_text(encoding='utf-8')))
            save(fixture.MANIFEST,c)
            if not c.get('seeded'):fixture.prepare_case(c,token);c['seeded']=True;save(fixture.MANIFEST,c)
            if not c.get('allocated'):allocate(c);c['allocated']=True;save(fixture.MANIFEST,c)
            if not c.get('dispatched'):dispatch(c);c['dispatched']=True;save(fixture.MANIFEST,c)
            o=get(c)
            if c['kind']=='replan' and not c.get('completed'):
                old=c.setdefault('original_xs',[s['shipment_sn'] for s in o['shipments']]);save(fixture.MANIFEST,c)
                if not c.get('first_withdrawal_verified'):cutoff(c,'REPLAN');c['first_withdrawal_verified']=True;save(fixture.MANIFEST,c)
                assert stock(c)['company'][0]['reserved']==10 and sum(w['locked'] for w in stock(c)['warehouse'])==0
                new=allocate(c) if not c.get('new_xs_generated') else get(c);assert len(new['shipments'])==4 and len([s for s in new['shipments'] if s['shipment_sn'] not in old])==2
                assert stock(c)['company'][0]['reserved']==10 and sum(w['locked'] for w in stock(c)['warehouse'])==10
                c['new_xs_generated']=True;save(fixture.MANIFEST,c);o=cutoff(c,'REPLAN') if get(c)['cutoff_state']!='REPLAN_READY' else get(c);assert o['cutoff_state']=='REPLAN_READY';assert stock(c)['company'][0]['reserved']==10 and sum(w['locked'] for w in stock(c)['warehouse'])==0
            elif c['kind']=='partial-terminate' and not c.get('completed'):
                first=next(s for s in o['shipments'] if s['quantity']==6)
                done=subprocess.run([sys.executable,str(Path(__file__).with_name('simulate_qimen_delivery.py')),first['shipment_sn']],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
                assert done.returncode==0,'Local TEST outbound did not complete; retained original receipt'
                o=wait(c,lambda x:x['shipped_quantity']==6,'Outbound not mirrored');c['outbound_verified']=True;save(fixture.MANIFEST,c)
                # UI must not expose a whole-order void once any actual outbound is posted.
                assert not o['can_void'];o=cutoff(c,'TERMINATE');assert o['status']=='CLOSED' and o['shipped_quantity']==6 and o['cancelled_quantity']==4
                st=stock(c);assert st['company'][0]['total']==4 and st['company'][0]['reserved']==0 and sum(w['actual'] for w in st['warehouse'])==4
            elif c['kind']=='warehouse-void' and not c.get('completed'):
                assert o['can_void'];result=api('/order/orders/business/void-batch',{'orders':[{'id':o['id'],'control_version':o['control_version']}],'reason':'[验收测试] 已下发仓库后作废','confirm_local_void':1})['data'];assert result[0]['submitted'];o=wait(c,lambda x:x['status']=='VOIDED','Warehouse-facing void pending');assert o['cancelled_quantity']==10 and stock(c)['company'][0]['reserved']==0 and stock(c)['company'][0]['total']==10
            elif c['kind']=='review-pending' and not c.get('completed'):
                first=next(s for s in o['shipments'] if s['quantity']==6);d=api('/order/orders/shipments/'+str(first['id']))['data'];route=d['warehouse_target'];connection=next(v for v in api('/supplychain/wmsIntegration/connections')['data'] if v['id']==route['connection_id']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
                credential=next(v for v in json.loads((Path(__file__).parent/'wms-simulator-credentials.json').read_text(encoding='utf-8')) if v['app_key']==connection['app_key'] and v['customer_id']==connection['customer_id'])
                root=ET.Element('request');head=ET.SubElement(root,'deliveryOrder');stamp=datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                for k,v in {'deliveryOrderCode':d['shipment_sn'],'deliveryOrderId':d['wms_external_order'],'warehouseCode':route['external_warehouse'],'ownerCode':route['external_owner'],'status':'DELIVERED','confirmType':'0','outBizCode':'CF_REVIEW_'+d['shipment_sn'],'totalOrderLines':1,'orderConfirmTime':stamp}.items():ET.SubElement(head,k).text=str(v)
                line=d['dispatch_lines'][0];element=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
                for k,v in {'orderLineNo':line['id'],'itemCode':c['sku'],'ownerCode':route['external_owner'],'batchCode':line['batch_code'],'inventoryType':'ZP','actualQty':6}.items():ET.SubElement(element,k).text=str(v)
                packs=ET.SubElement(root,'packages')
                for i in range(2):
                    pack=ET.SubElement(packs,'package')
                    for k,v in {'packageCode':'CFPKG_'+d['shipment_sn']+'_'+str(i),'expressCode':'TEST-CF-SHARED-'+c['case_id'],'logisticsCode':'SF','logisticsName':'顺丰快递'}.items():ET.SubElement(pack,k).text=v
                    item=ET.SubElement(ET.SubElement(pack,'items'),'item');ET.SubElement(item,'itemCode').text=c['sku'];ET.SubElement(item,'quantity').text='3'
                receipt={'callback_body':ET.tostring(root,encoding='unicode'),'external_owner':route['external_owner'],'external_warehouse':route['external_warehouse'],'shipment_sn':d['shipment_sn'],'external_order':d['wms_external_order'],'quantity':6};c['callback']=receipt;save(fixture.MANIFEST,c);mark_simulated_delivery(receipt,connection)
                try:send(receipt,connection,credential);raise RuntimeError('Expected conflicting waybill review')
                except AssertionError:pass
                wait(c,lambda x:x['receipt_review_pending_count']>0,'Receipt review missing');o=get(c);api('/order/orders/business/'+str(o['id'])+'/cutoff',{'mode':'TERMINATE','reason':'[验收测试] 回传待核对时截单','control_version':o['control_version']});o=wait(c,lambda x:x['cancelled_quantity']==4,'Unblocked XS did not cancel');assert o['status']=='CUTTING' and o['shipped_quantity']==0 and o['receipt_review_pending_count']>0 and stock(c)['company'][0]['reserved']==6
                c['pending_review_shipment']=first['shipment_sn']
            c.update(completed=True,business_url=BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']));save(fixture.MANIFEST,c);save(manifest,cases);capture(c,c['kind']);print(json.dumps({k:c[k] for k in ['kind','order_id','order_sn','business_url']},ensure_ascii=True),flush=True)
        jobs_after={j['jobId']:(j['status'],j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']};assert jobs_after==jobs_before
        save(OUT/'verification.json',{'cases':[{k:c[k] for k in ['kind','order_id','order_sn','business_url']} for c in cases],'new_xs_reallocation_verified':True,'warehouse_cancel_and_local_void_verified':True,'posted_outbound_preserved':True,'pending_receipt_retains_stock':True,'quartz_switches_and_crons_unchanged':True,'warehouse_environment':'local TEST simulator only'})
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
