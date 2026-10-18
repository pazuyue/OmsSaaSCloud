"""Retained TEST-only purchase cutoff fixtures; never scans or edits existing documents."""
import json,hashlib,subprocess,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from wms_qimen_simulator import sign

OUT=Path(__file__).resolve().parents[2]/'outputs/purchase-cutoff'
ROOT='/supplychain/purchaseWorkspace'

def save(value): (OUT/'cases.json').write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

def mark_entry(c,connection,body):
    payload={'customer':connection['customer_id'],'owner':c['task']['external_owner'],'warehouse':c['task']['external_warehouse'],'sn':c['task']['ticket_sn'],'external':c['task']['external_order'],'hash':hashlib.sha256(body.encode()).hexdigest()}
    script="""import json,sqlite3,datetime
from contextlib import closing
p=json.loads(REPLACE_PAYLOAD)
key=(p['customer'],'entry',p['owner'],p['warehouse'],p['sn'])
with closing(sqlite3.connect('/data/documents.sqlite',timeout=10)) as db,db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row is not None and row['external_id']==p['external'] and row['quantity']==10 and row['status'] in ('ACCEPT','PARTFULFILLED'),'TEST entry identity mismatch'
 prior=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='entry' AND sn=? AND action='PARTIAL_RECEIPT'",(p['customer'],p['sn'])).fetchall()
 assert not prior or all(r['body_hash']==p['hash'] for r in prior),'TEST receipt changed'
 if not prior:
  db.execute("UPDATE documents SET status='PARTFULFILLED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'entry',?,'PARTIAL_RECEIPT',?,?)",(p['customer'],p['sn'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
""".replace('REPLACE_PAYLOAD',repr(json.dumps(payload)))
    r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert r.returncode==0,'Local TEST entry state not confirmed'


def callback(c,connection,credential,message,quantity,late=False):
    task=c['task'];root=ET.Element('request');head=ET.SubElement(root,'entryOrder')
    for k,v in dict(entryOrderCode=task['ticket_sn'],entryOrderId=task['external_order'],warehouseCode=task['external_warehouse'],ownerCode=task['external_owner'],status='PARTFULFILLED',confirmType='1',outBizCode=message,totalOrderLines=1).items():ET.SubElement(head,k).text=str(v)
    line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
    for k,v in dict(itemCode=c['sku'],ownerCode=task['external_owner'],actualQty=quantity,batchCode=c['batch'],inventoryType='ZP').items():ET.SubElement(line,k).text=str(v)
    body=ET.tostring(root,encoding='unicode');
    if not late:mark_entry(c,connection,body)
    params=dict(app_key=connection['app_key'],customerId=connection['customer_id'],method='taobao.qimen.entryorder.confirm',v='2.0',format='xml',sign_method='md5',timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'));params['sign']=sign(params,body,credential['secret'])
    url=BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params)
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(urllib.request.Request(url,data=body.encode(),headers={'Content-Type':'application/xml'}),timeout=30) as response:reply=ET.fromstring(response.read())
    except Exception:raise RuntimeError('TEST callback response not confirmed; original message identity retained') from None
    assert reply.findtext('flag')==('failure' if late else 'success'),reply.findtext('message')
    c['callback_body']=body


def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();manifest=OUT/'cases.json';data=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':[]};stamp=data['stamp']
    def api(path,payload=None):return request(path,payload=payload,token=token)
    def get(kind,id):return api(ROOT+'/'+kind+'/'+str(id))['data']
    def stock(c):return int(sql("SELECT COALESCE(SUM(zp_actual_number),0) FROM wms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory') or 0)
    def remaining(c):return sum(int(l['quantity'])-int(l['scheduledQuantity']) for l in get('purchase',c['purchase_id'])['lines'])
    def report(c):return api(ROOT+'/receipt/'+str(c['receipt_id'])+'/cutoff')['data']
    def cutoff(c,kind='receipt',mode='WITHDRAW'):
        key=c['purchase_id'] if kind=='purchase' else c['receipt_id'];d=get(kind,key)
        if not c.get('cutoff_id'):c['cutoff_id']=api(ROOT+'/'+kind+'/'+str(key)+'/cutoff',{'reason':'[验收测试] '+c['kind'],'mode':mode,'cutoff_version':d['cutoffVersion']})['data']['id'];save(data)
        return wait_for(lambda:report(c),lambda r:any(o['id']==c['cutoff_id'] and o['state'] in ['COMPLETED','WAIT_REVIEW'] for o in r['operations']),'Purchase cutoff outcome not confirmed')
    try:
        jobs_before={j['jobId']:(j['status'],j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']}
        credentials=json.loads((Path(__file__).parent/'wms-simulator-credentials.json').read_text(encoding='utf-8'));credential=credentials[0]
        if not data.get('owner'):
            data['owner']=api('/supplychain/owner',{'ownerCode':'PCTO_'+stamp,'ownerName':'[采购截单验收] 货主 '+stamp,'isEnable':2})['data'];save(data)
        if not data.get('warehouse'):
            data['warehouse']=api('/supplychain/realStore',{'realStoreCode':'PCTW_'+stamp,'wmsName':'[采购截单验收] 模拟仓 '+stamp,'wmsType':1,'status':2})['data'];save(data)
        if not data.get('relation'):
            data['relation']=api('/supplychain/ownerWarehouse',{'ownerId':data['owner'],'realStoreId':data['warehouse'],'status':2})['data'];save(data)
        for capability in [0,1]:
            key='connection_'+str(capability)
            if not data.get(key):data[key]=api('/supplychain/wmsIntegration/connections',dict(name='[采购截单验收] '+stamp+' 部分取消'+str(capability),provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=credential['app_key'],customer_id=credential['customer_id'],secret=credential['secret'],enabled=1,partial_inbound_cancel=capability))['data'];save(data)
            vk='virtual_'+str(capability)
            if not data.get(vk):
                data[vk]='PCTV'+str(capability)+'_'+stamp;api('/supplychain/simulationStore',{'wmsSimulationCode':data[vk],'wmsSimulationName':'[采购截单验收] '+stamp+' 能力'+str(capability),'ownerWarehouseId':data['relation'],'inboundMode':1,'outboundMode':2,'connectionId':data[key],'externalWarehouse':'PCT_EXT_'+stamp,'externalOwner':'PCT_OWNER_'+stamp,'status':2});save(data)
        if not data.get('supplier_id'):
            data['supplier_id']=api(ROOT+'/supplier',{'supplierName':'[采购截单验收] '+stamp,'companyName':'[采购截单验收] '+stamp+' 测试供应商','status':2})['data'];data['supplier_sn']=get('supplier',data['supplier_id'])['supplierSn'];save(data)
        if not data['cases']:
            data['cases']=[{'kind':kind,'sku':'PCT_'+stamp+'_'+str(i),'batch':'PCT_BATCH_'+stamp+'_'+str(i)} for i,kind in enumerate(['local','partial','manual','late-review','terminate'])];save(data)
        catalog=api('/goods/info/options')['data']
        for c in data['cases']:
            if not c.get('product'):
                c['product']=api('/goods/info',dict(skuSn=c['sku'],goodsSn=c['sku'],goodsName='[采购截单验收] '+c['kind'],marketPrice=10,validity=30,categoryCode=next(v['id'] for v in catalog['category'] if v['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))['data'];save(data)
            if not c.get('purchase_id'):
                c['purchase_id']=api(ROOT+'/purchase',dict(poName='[采购截单验收] '+c['kind']+' '+stamp,supplierSn=data['supplier_sn'],wmsSimulationCode=data['virtual_0' if c['kind']=='manual' else 'virtual_1'],lines=[dict(skuSn=c['sku'],quantity=100 if c['kind']=='local' else 10,purchasePrice=2.5)]))['data'];save(data);api(ROOT+'/purchase/'+str(c['purchase_id'])+'/approve',{})
            po=get('purchase',c['purchase_id']);c['purchase_sn']=po['poSn']
            if not c.get('receipt_id'):
                c['receipt_id']=api(ROOT+'/purchase/'+str(c['purchase_id'])+'/receipt',{'lines':[{'lineId':po['lines'][0]['id'],'quantity':30 if c['kind']=='local' else 10}]})['data'];save(data)
            n=get('receipt',c['receipt_id']);c['receipt_sn']=n['noSn'];c['url']=BASE+'/oms-supplychain/poInfo?receiptId='+str(c['receipt_id']);save(data)
            if c['kind']!='local' and not c.get('ticket_id'):
                c['ticket_id']=api(ROOT+'/receipt/'+str(c['receipt_id'])+'/approve',{})['data'];save(data)
            if c.get('ticket_id'):
                c['task']=wait_for(lambda:api('/supplychain/wmsIntegration/tickets/'+str(c['ticket_id']))['data'],lambda t:t['dispatch_state'] in ['ACCEPTED','CANCELED'],'New TEST inbound task not accepted')
                connection=next(v for v in api('/supplychain/wmsIntegration/connections')['data'] if v['id']==c['task']['connection_id']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
                # Only public metadata enters the manifest, never credentials or callback routes.
                c['task']={k:c['task'][k] for k in ['ticket_sn','external_order','external_owner','external_warehouse','connection_id']};save(data)
                if c['kind']=='manual' and c.get('received'):
                    mark_entry(c,connection,c['callback_body']);q=api('/supplychain/wmsIntegration/tickets/'+str(c['ticket_id'])+'/query',{})['data'];assert q['warehouseStatus']=='PARTFULFILLED'
                if c['kind'] in ['partial','manual'] and not c.get('received'):
                    callback(c,connection,credential,'PCT_PART_'+c['task']['ticket_sn'],4);wait_for(lambda:get('ticket',c['ticket_id']),lambda t:t['numberPosted']==4,'Partial receipt not posted');assert stock(c)==4;c['received']=True;save(data)
            if c['kind']=='manual' and c.get('cutoff_id') and get('receipt',c['receipt_id'])['cutoffState']!='WAIT_REVIEW':
                d=get('receipt',c['receipt_id']);api(ROOT+'/receipt/'+str(c['receipt_id'])+'/cutoff/retry',{'cutoff_version':d['cutoffVersion']});wait_for(lambda:get('receipt',c['receipt_id']),lambda n:n['cutoffState']=='WAIT_REVIEW','Manual review not reflected on receipt')
            r=cutoff(c,'purchase' if c['kind']=='terminate' else 'receipt','TERMINATE' if c['kind']=='terminate' else 'WITHDRAW');n=get('receipt',c['receipt_id'])
            if c['kind']=='local':
                assert n['noState']==6 and n['cancelledQuantity']==30 and stock(c)==0
                if not c.get('new_receipt'):c['new_receipt']=api(ROOT+'/purchase/'+str(c['purchase_id'])+'/receipt',{'lines':[{'lineId':get('purchase',c['purchase_id'])['lines'][0]['id'],'quantity':20}]})['data'];save(data)
                assert remaining(c)==80-(10 if data.get('ui_receipt_id') and get('receipt',data['ui_receipt_id'])['noState']!=6 else 0)
                logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'ARRIVAL','number':c['receipt_sn'],'allTime':'true'}))['data']['rows'];assert not logs
            elif c['kind']=='partial':assert n['noState']==5 and n['cancelledQuantity']==6 and n['numberActually']==4 and stock(c)==4 and remaining(c)==6
            elif c['kind']=='manual':assert n['noState']==3 and r['operations'][0]['state']=='WAIT_REVIEW' and stock(c)==4 and remaining(c)==0
            elif c['kind']=='late-review':
                if not c.get('late_sent'):callback(c,connection,credential,'PCT_LATE_'+c['task']['ticket_sn'],2,True);c['late_sent']=True;save(data)
                assert any(v['state']=='WAIT_REVIEW' for v in report(c)['reviews']) and stock(c)==0 and remaining(c)==10
            elif c['kind']=='terminate':assert get('purchase',c['purchase_id'])['poState']==5 and n['noState']==6 and stock(c)==0
            if c['kind']!='local':
                logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'ARRIVAL','number':c['receipt_sn'],'allTime':'true'}))['data']['rows'];assert any(v['action']=='CREATE' for v in logs);assert all(v['receipt_sn']==c['receipt_sn'] for v in logs)
                purchase_logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'PURCHASE','number':c['purchase_sn'],'allTime':'true'}))['data']['rows'];assert purchase_logs
                if c['kind']=='manual':assert not any(v['action']=='CANCEL' for v in logs)
            c['verified']=True;save(data);print(json.dumps({k:c[k] for k in ['kind','receipt_id','receipt_sn','url']},ensure_ascii=True),flush=True)
        local=next(c for c in data['cases'] if c['kind']=='local')
        if not data.get('ui_receipt_id'):
            po=get('purchase',local['purchase_id']);data['ui_receipt_id']=api(ROOT+'/purchase/'+str(local['purchase_id'])+'/receipt',{'lines':[{'lineId':po['lines'][0]['id'],'quantity':10}]})['data'];save(data)
        late=next(c for c in data['cases'] if c['kind']=='late-review')
        if not data.get('ui_review_id'):
            connection=next(v for v in api('/supplychain/wmsIntegration/connections')['data'] if v['id']==late['task']['connection_id']);old=late.get('callback_body');callback(late,connection,credential,'PCT_UI_REJECT_'+late['task']['ticket_sn'],1,True);late['ui_callback_body']=late['callback_body'];late['callback_body']=old
            data['ui_review_id']=next(r['id'] for r in report(late)['reviews'] if r['message_id']=='PCT_UI_REJECT_'+late['task']['ticket_sn']);save(data)
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1050});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];writes=[];page.on('pageerror',lambda e:errors.append(str(e)));page.on('request',lambda r:writes.append(r.url.split('/prod-api/')[-1]) if r.method in ['POST','PUT','DELETE'] and '/prod-api/' in r.url else None)
            for c in data['cases']:
                page.goto(c['url'],wait_until='networkidle');drawer=page.locator('.el-drawer:visible').first;expect(drawer.get_by_text(c['receipt_sn'],exact=True)).to_be_visible();button=drawer.get_by_role('button',name='截单 / 查看处理结果',exact=True);button.scroll_into_view_if_needed();button.click();d=page.get_by_role('dialog',name='到货截单与采购终止',exact=True);expect(d.get_by_text('仓库截停',exact=True).first).to_be_visible();page.screenshot(path=str(OUT/(c['kind']+'.png')),animations='disabled')
                if c['kind']=='late-review':expect(drawer.get_by_text('仓库取消后出现新的收货回传，待人工核对；该回传尚未入账',exact=True)).to_be_visible()
                if c['kind']=='manual':d.get_by_role('button',name='登记仓库截停确认',exact=True).first.click();m=page.get_by_role('dialog',name='登记仓库截停确认',exact=True);expect(m.get_by_role('button',name='登记确认并继续核对',exact=True)).to_be_disabled();m.get_by_role('button',name='取消',exact=True).click()
                if c['kind']=='late-review':d.get_by_role('button',name='人工核对',exact=True).first.click();review=page.get_by_role('dialog',name='核对取消后收货回传',exact=True);expect(review.get_by_role('button',name='确认核对结果',exact=True)).to_be_disabled();page.screenshot(path=str(OUT/'late-review-detail.png'),animations='disabled');review.get_by_role('button',name='取消',exact=True).click()
            page.goto(BASE+'/oms-supplychain/poInfo?receiptId='+str(data['ui_receipt_id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible').first
            if get('receipt',data['ui_receipt_id'])['noState']!=6:
                drawer.get_by_role('button',name='申请到货单截单',exact=True).click();d=page.get_by_role('dialog',name='到货截单与采购终止',exact=True);expect(d.get_by_role('button',name='申请到货单截单',exact=True)).to_be_disabled();d.get_by_placeholder('填写截单原因（必填）',exact=True).fill('[验收测试] UI 实际提交本地到货截单');d.get_by_role('button',name='申请到货单截单',exact=True).click();expect(d.get_by_text('全部处理完成',exact=True)).to_be_visible(timeout=15000);assert get('receipt',data['ui_receipt_id'])['noState']==6
                page.set_viewport_size({'width':1280,'height':720});page.screenshot(path=str(OUT/'ui-submit-compact.png'),animations='disabled');box=d.get_by_role('button',name='关闭',exact=True).bounding_box();assert box and box['y']+box['height']<=720
            if next(r['state'] for r in report(late)['reviews'] if r['id']==data['ui_review_id'])=='WAIT_REVIEW':
                page.goto(late['url'],wait_until='networkidle');page.locator('.el-drawer:visible').first.get_by_role('button',name='截单 / 查看处理结果',exact=True).click();d=page.get_by_role('dialog',name='到货截单与采购终止',exact=True);row=d.locator('tr').filter(has_text='PCT_UI_REJECT_'+late['task']['ticket_sn']).first;row.get_by_role('button',name='人工核对',exact=True).click();r=page.get_by_role('dialog',name='核对取消后收货回传',exact=True);r.get_by_placeholder('填写核对依据与处理说明（必填）',exact=True).fill('[验收测试] 已核实此条错误回传，拒绝且不入账');r.get_by_role('button',name='确认核对结果',exact=True).click();expect(r).not_to_be_visible();assert next(r['state'] for r in report(late)['reviews'] if r['id']==data['ui_review_id'])=='REJECTED'
            assert stock(late)==0 and remaining(local)==80
            allowed={ROOT.lstrip('/')+'/receipt/'+str(data['ui_receipt_id'])+'/cutoff',ROOT.lstrip('/')+'/inbound-reviews/'+str(data['ui_review_id'])};assert set(writes)<=allowed,writes;assert not errors,errors;browser.close()

        assert jobs_before=={j['jobId']:(j['status'],j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']}
        (OUT/'verification.json').write_text(json.dumps({'cases':[{k:c[k] for k in ['kind','receipt_id','receipt_sn','url']} for c in data['cases']],'test_simulator_only':True,'partial_stock_preserved':True,'booking_not_released_before_confirmation':True,'manual_and_late_review_retained':True,'no_scheduler_changes':True,'ui_submit_and_reject_verified':True,'arrival_and_purchase_log_filters_verified':True,'mock_partial_state_matches_receipt':True,'browser_errors':[]},ensure_ascii=False,indent=2),encoding='utf-8');print('PASS retained purchase cutoff API and UI acceptance')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
