"""Advance only retained synthetic TK2 through TEST receipts/cancellation. No real platform writes."""
import json
import subprocess
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-goods'
ORDER='qm_oms_saas_order'
SUPPLY='qm_oms_saas_commodity'
INVENTORY='qm_oms_saas_inventory'
TK='TKd6e70d8509394025adc749eea4b636c2'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    manifest=OUT/'case.json'
    state=json.loads(manifest.read_text('utf-8')) if manifest.exists() else {}
    token=login()
    def save():manifest.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    def api(path,body=None):return request(path,token=token,payload=body,method='POST' if body is not None else 'GET')['data']
    def refund():return api('/order/refunds/business/2')
    def plans():return api('/order/refunds/business/2/goods')['plans']
    def refresh():api('/order/refunds/business/2/goods/refresh',{});return plans()[0]
    def action(p,d,kind,body=None):return api(f"/order/refunds/business/2/goods/plans/{p['id']}/documents/{d['id'] if d else 0}/{kind}",{'control_version':refund()['control_version'],'plan_version':p['control_version'],'document_version':d['control_version'] if d else 0,**(body or {})})
    try:
        def ready():
            try:
                result=api('/order/refunds/business/2/goods');api('/supplychain/wmsIntegration/connections');return result
            except (AssertionError,OSError):return None
        wait_for(ready,lambda x:x is not None,'Refund goods service not ready',180)
        b=refund();assert b['refund_sn']==TK and b['goods_action']=='REFUSAL' and b['funds_state']=='SUCCESS'
        source=b['shipments'][0];sku=source['sku_sn'];assert sku=='EXCH_REFUND_20261017204001_A' and source['shipped_quantity']==10
        store=source['store_code'];connection_id=int(sql("SELECT connection_id FROM wms_simulation_store_info WHERE UPPER(company_code)='QM' AND wms_simulation_code="+quote(store),SUPPLY))
        connection=next(c for c in api('/supplychain/wmsIntegration/connections') if c['id']==connection_id)
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(c for c in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if c['app_key']==connection['app_key'] and c['customer_id']==connection['customer_id'])
        def stock():return int(sql("SELECT total_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INVENTORY))
        if 'stock_before' not in state:state['stock_before']=stock();save()
        if not plans():
            api('/order/refunds/business/2/goods/plans',{'control_version':b['control_version'],'shipment_line_id':source['shipment_line_id'],'quantity':10,'store_code':store,'return_address':'[验收] 拒收退回接收地址，无实际配送','return_contact':'TEST-REFUND-RETURN','return_waybill':'TEST-RETURN-TK2','reason':'[验收] 确认原包裹10件拒收退回','evidence':'TEST-REFUND-GOODS','confirmed':True,'request_key':'REFUND_GOODS_TK2_001'})
            assert stock()==state['stock_before']
        p=plans()[0]
        if not p['actual']:action(p,None,'dispatch');p=refresh()
        a=p['actual'];original=next(d for d in a['documents'] if d['generation']==1)
        assert original['return_sn'].startswith('THK') and original['external_order']
        state.update(refund_sn=TK,plan_id=p['id'],original_return_sn=original['return_sn']);save()
        def callback(document,quantity,message,complete=False,warehouse_state='PARTFULFILLED'):
            assert document['return_sn'].startswith('THK')
            root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
            for k,v in {'returnOrderCode':document['return_sn'],'returnOrderId':document['external_order'],'warehouseCode':a['external_warehouse'],'ownerCode':a['external_owner'],'outBizCode':message,'orderType':'THRK','confirmType':'0' if complete else '1'}.items():ET.SubElement(head,k).text=str(v)
            line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
            for k,v in {'itemCode':sku,'ownerCode':a['external_owner'],'actualQty':quantity,'inventoryType':'ZP','batchCode':'TEST_TK2_'+message}.items():ET.SubElement(line,k).text=str(v)
            body=ET.tostring(root,encoding='unicode')
            data={'customer':connection['customer_id'],'owner':a['external_owner'],'warehouse':a['external_warehouse'],'sn':document['return_sn'],'external':document['external_order'],'state':warehouse_state}
            script="""import json,sqlite3
p=json.loads(PAYLOAD)
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 row=db.execute("SELECT external_id,quantity FROM documents WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?",tuple(p[k] for k in ['customer','owner','warehouse','sn'])).fetchone()
 assert row and row[0]==p['external'] and row[1]==10
 db.execute("UPDATE documents SET status=? WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?",tuple(p[k] for k in ['state','customer','owner','warehouse','sn']))
""".replace('PAYLOAD',repr(json.dumps(data)))
            r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30)
            assert r.returncode==0,'TEST warehouse document identity mismatch'
            params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,body,credential['secret'])
            req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=50) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        if not state.get('partial'):
            callback(original,3,'PARTIAL_3');p=refresh();assert p['accepted_quantity']==p['posted_quantity']==3
            assert stock()==state['stock_before']+3
            callback(original,3,'PARTIAL_3');assert stock()==state['stock_before']+3
            state['partial']=True;save()
        p=refresh();original=next(d for d in p['actual']['documents'] if d['generation']==1)
        if original['cancel_state']!='CONFIRMED':action(p,original,'cancel',{'mode':'WITHDRAW','reason':'[验收] 撤回剩余7件，准备重新安排','evidence':'TEST-CUT-REMAINDER'})
        p=refresh();original=next(d for d in p['actual']['documents'] if d['generation']==1)
        assert original['accepted_quantity']==3 and original['cancelled_quantity']==7
        if len(p['actual']['documents'])==1:action(p,original,'rearrange',{'quantity':7,'store_code':store,'return_address':'[验收] 重新安排剩余退回地址','return_contact':'TEST-REFUND-RETURN','reason':'[验收] 保留已收3件，只补退剩余7件'})
        p=refresh();latest=p['actual']['documents'][0]
        if latest['dispatch_state']=='PENDING':action(p,latest,'dispatch')
        p=refresh();latest=p['actual']['documents'][0];assert latest['generation']==2 and latest['quantity']==7 and latest['dispatch_state']=='ACCEPTED'
        if not state.get('late'):
            original=next(d for d in p['actual']['documents'] if d['generation']==1)
            callback(original,1,'LATE_1',warehouse_state='PARTFULFILLED')
            job=sql("SELECT job_id,job_group,status FROM sys_job WHERE invoke_target='refundTask.scanAll()' LIMIT 1",'ry-cloud').split('\t')
            if job[2]=='1':
                assert sql("SELECT COUNT(*) FROM oms_refund_config WHERE enabled=1 OR auto_convert=1",ORDER)=='0'
                assert sql("SELECT COUNT(*) FROM oms_refund_sync WHERE state IN ('PENDING','RUNNING','RETRY')",ORDER)=='0'
                request('/schedule/job/run',token=token,payload={'jobId':int(job[0]),'jobGroup':job[1]},method='PUT')
                state['scan_validation']='one-off existing paused job; switch retained';save()
            wait_for(refund,lambda b:b['goods_state']=='WAIT_CHECK','Background refund progress scan did not project pending receipt',90);p=refresh()
            assert p['actual']['pending_review_count']==1 and p['accepted_quantity']==p['posted_quantity']==3
            assert stock()==state['stock_before']+3
            state['late']=True;save()
        p=refresh();b=refund();assert b['goods_state']=='WAIT_CHECK'
        wait_for(lambda:sql("SELECT COUNT(*) FROM wms_refund_return WHERE company_code='QM' AND refund_id=2 AND notify_pending=1",SUPPLY),lambda n:n=='0','Refund progress scan did not acknowledge warehouse facts',60)
        logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'REFUND','number':TK,'allTime':'true','size':50}))
        assert logs['rows'] and all(x['business_type']=='REFUND_IN' for x in logs['rows'])
        assert any(x['action']=='REFUND_RETURN_CREATE' for x in logs['rows']) and any(x['action']=='REFUND_RETURN_CANCEL' for x in logs['rows'])
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1800,'height':1100});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/businessRefunds?id=2',wait_until='networkidle')
            page.get_by_role('button',name='处理货物与退回单',exact=True).click()
            goods=page.locator('.refund-goods');expect(goods).to_be_visible();goods.scroll_into_view_if_needed()
            expect(goods.get_by_text('待人工核对',exact=True).first).to_be_visible()
            expect(goods.get_by_text(latest['return_sn'],exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'refund-return-documents.png'),full_page=True,animations='disabled')
            goods.get_by_role('button',name='核对处理',exact=True).first.click()
            review=page.get_by_role('dialog',name='核对异常实收',exact=True);expect(review).to_be_visible()
            expect(review.get_by_role('radio',name='拒绝本次回传',exact=True)).to_be_checked()
            expect(review.get_by_role('radio',name='确认真实实收',exact=True)).to_be_disabled()
            expect(review.get_by_text(latest['return_sn'],exact=False).first).to_be_visible()
            page.screenshot(path=str(OUT/'pending-receipt-review.png'),animations='disabled')
            review.get_by_role('button',name='返回',exact=True).click()
            goods.get_by_role('button',name='确认货物处理完成',exact=True).click()
            finish=page.get_by_role('dialog',name='确认货物处理完成',exact=True)
            expect(finish.get_by_text('当前不能完成，请先处理以下事项',exact=True)).to_be_visible()
            finish.locator('label.el-checkbox').click()
            expect(finish.get_by_role('checkbox')).to_be_checked()
            expect(finish.get_by_role('button',name='确认提交',exact=True)).to_be_disabled()
            page.screenshot(path=str(OUT/'goods-completion-blockers.png'),animations='disabled')
            finish.get_by_role('button',name='返回',exact=True).click()
            goods.get_by_role('button',name='截停本次安排剩余数量',exact=True).click()
            stop=page.get_by_role('dialog',name='截停本次安排剩余数量',exact=True)
            expect(stop.get_by_text('仅截停：'+latest['return_sn'],exact=True)).to_be_visible()
            page.screenshot(path=str(OUT/'single-arrangement-cutoff.png'),animations='disabled')
            stop.get_by_role('button',name='返回',exact=True).click()

            page.goto(BASE+'/oms-supplychain/wmsLogs?'+urllib.parse.urlencode({'numberType':'REFUND','number':TK,'allTime':'true'}),wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').get_by_text('退款退回入库',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'warehouse-logs.png'),animations='disabled')
            page.goto(BASE+'/oms-supplychain/wmsTickets?'+urllib.parse.urlencode({'sn':latest['return_sn'],'documentId':latest['ticket_id']}),wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').get_by_text('退款退回入库',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'inbound-document.png'),animations='disabled')
            assert not errors,errors
            browser.close()
        state.update(goods_state=b['goods_state'],latest_return_sn=latest['return_sn'],received=3,posted=3,pending_review=1,remaining=7,stock_after=stock(),real_platform_writes=False,url=BASE+'/oms-order/businessRefunds?id=2');save()
        print('PASS refund return acceptance: received/posted 3, remainder 7, late receipt 1 pending review; stock added once')
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
