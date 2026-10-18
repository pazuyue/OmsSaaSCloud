"""Isolated TEST acceptance: negotiated after-sales return, discrepancy and replacement."""
import json,subprocess,sys,urllib.request,urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from wms_qimen_simulator import sign
import verify_exchange_ownership as ownership
OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-after-sales'
ORDER='qm_oms_saas_order'

def partial_return(case,e):
 c=case.c;actual=case.exchange(e)['return_result'];connection=next(x for x in case.api('/supplychain/wmsIntegration/connections')['data'] if x['id']==c['connection_id'])
 assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
 credential=next(x for x in json.loads(Path(__file__).with_name('wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
 root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
 for k,v in {'returnOrderCode':e['return_sn'],'returnOrderId':actual['external_order'],'warehouseCode':actual['external_warehouse'],'ownerCode':actual['external_owner'],'outBizCode':'TEST_PARTIAL_'+str(e['id']),'orderType':'HHRK','confirmType':'1'}.items():ET.SubElement(head,k).text=str(v)
 line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
 for k,v in {'orderLineNo':'1','itemCode':c['sku'],'ownerCode':actual['external_owner'],'actualQty':1,'inventoryType':'ZP','batchCode':'TEST_PARTIAL_'+str(e['id'])}.items():ET.SubElement(line,k).text=str(v)
 body=ET.tostring(root,encoding='unicode')
 event={'customer':connection['customer_id'],'owner':actual['external_owner'],'warehouse':actual['external_warehouse'],'sn':e['return_sn'],'external':actual['external_order'],'hash':__import__('hashlib').sha256(body.encode()).hexdigest()}
 script="""import json,sqlite3,datetime
p=json.loads(PAYLOAD)
key=(p['customer'],'return',p['owner'],p['warehouse'],p['sn'])
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row and row['external_id']==p['external'] and row['quantity']==2 and row['status'] in ('ACCEPT','PARTFULFILLED')
 old=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='return' AND sn=? AND action='RETURN_CONFIRM_PARTIAL'",(p['customer'],p['sn'])).fetchall()
 assert not old or all(v['body_hash']==p['hash'] for v in old)
 if not old:
  db.execute("UPDATE documents SET status='PARTFULFILLED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'return',?,'RETURN_CONFIRM_PARTIAL',?,?)",(p['customer'],p['sn'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
""".replace('PAYLOAD',repr(json.dumps(event)))
 r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30);assert r.returncode==0,'TEST partial return identity mismatch'
 def callback():
  params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,body,credential['secret'])
  req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
  with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=40) as response:reply=ET.fromstring(response.read())
  assert reply.findtext('flag')=='success',reply.findtext('message')
 callback();before=sql("SELECT total_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory');callback();assert before==sql("SELECT total_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory')

def main():
 OUT.mkdir(exist_ok=True);ownership.OUT=OUT;token=login();case=ownership.Case('nego',token)
 try:
  case.prepare();c=case.c;assert c['sku'].startswith('EXCH_NEGO_')
  if not c.get('extra_stock'):
   payload={'relationSn':'TEST-NEGOTIATED-EXTRA-'+c['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':c['target_sku'],'batchCode':'TEST-NEGOTIATED-EXTRA-'+c['case_id'],'zpActualNumber':2,'zpAvailableNumber':2,'transactionPrice':2.5}}
   r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),capture_output=True,timeout=45);assert r.returncode==0 and json.loads(r.stdout)['code']==200
   c['extra_stock']=True;case.save()
  original=case.dispatch_ship(case.allocate()['shipments'][0]);c['original_shipment_id']=original['id'];source=original['lines'][0]
  def ensure(label):
   if not c.get(label+'_id'):
    recipient=case.api('/order/exchanges/create-sources?orderId='+str(c['order_id']))['data']['recipient']
    created=case.api('/order/exchanges',{'order_id':c['order_id'],'order_line_id':source['order_line_id'],'platform_oid':c['platform_oid'],'source_shipment_line_id':source['id'],'fulfillment_mode':'RETURN','source_phase':'AFTER_SALE','quantity':2,'target_sku':c['target_sku'],'return_store':c['warehouses'][0]['code'],'return_address':'[验收] 买家寄回地址，无实际配送','return_contact':'TEST-RETURN-CONTACT','replacement_recipient':recipient,'reason':'[验收] 客服协商售后换货 '+label,'request_key':'NEGOTIATED_'+label+'_'+c['case_id']})['data'];c[label+'_id']=created['id'];case.save()
   e=case.exchange({'id':c[label+'_id']})
   if not e.get('approved_version'):
    case.act(e,'review',{'target_sku':c['target_sku'],'source_shipment_line_id':source['id'],'return_store':c['warehouses'][0]['code'],'return_address':'[验收] 买家寄回地址，无实际配送','return_contact':'TEST-RETURN-CONTACT','recipient_confirmed':True,'replacement_recipient':e['payload']['replacement_recipient'],'reason':'[验收] 已核对实际来源与协商依据'})
   e=case.exchange(e)
   if not e.get('return_sn'):case.act(e,'advance');e=case.exchange(e)
   assert e['return_sn'] and e['return_result']['dispatch_state']=='ACCEPTED'
   c[label+'_sn']=e['exchange_sn'];case.save();return e
  pending=ensure('pending')
  partial_return(case,pending);c['partial_received']=True;case.save()
  pending=case.exchange(pending)
  if pending['return_qc']!='ISSUE':case.act(pending,'qc',{'decision':'WAIT_SUPPLEMENT','issue_type':'SHORT','reason':'[验收] 应退2件，实收1件，等待买家补退','evidence':'TEST-NEGOTIATED-PARTIAL'})
  pending=case.exchange(pending)
  if not pending.get('return_logistics'):case.act(pending,'return-logistics',{'items':[{'carrier':'TEST承运商','tracking_no':'TEST-RETURN-'+c['case_id'],'shipped_at':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'note':'[验收] 登记物流不增加库存'}]})
  completed=ensure('complete')
  if completed['return_qc']!='PASSED':case.returned(completed,c['sku']);case.act(completed,'qc',{'reason':'[验收] 实收足量，正常验收'})
  completed=case.exchange(completed)
  if not completed.get('fulfillment_line_id'):case.act(completed,'advance');completed=case.exchange(completed)
  if completed['allocation_status']!='FULL':case.act(completed,'allocate')
  completed=case.exchange(completed)
  for shipment in completed['shipments']:case.dispatch_ship(shipment)
  completed=case.exchange(completed)
  if completed['state']!='COMPLETED':case.act(completed,'advance')
  completed=case.exchange(completed);assert completed['state']=='COMPLETED' and completed['platform_operation']=='NONE' and not completed['dispute_id']
  pending=case.exchange(pending);case.act(pending,'advance');pending=case.exchange(pending);case.act(pending,'return/query');pending=case.exchange(pending);report=case.api('/order/exchanges/'+str(pending['id'])+'/return')['data']
  assert pending['return_qc']=='ISSUE' and not pending['fulfillment_line_id'] and report['actual']['posted_quantity']==1 and report['actual']['grades']['good_qty']==1
  assert case.order()['shipped_quantity']==10 and len(case.order()['after_sales_lines'])==1 and case.order()['manual_hold']==0
  filtered=case.api('/order/exchanges/workbench?phase=AFTER_SALE&kind=INTERNAL&number='+c['platform_tid'])['data'];assert filtered['total']==2
  with sync_playwright() as p:
   b=p.chromium.launch(headless=True);ctx=b.new_context(viewport={'width':1700,'height':1150});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();page.set_default_timeout(30000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
   page.goto(BASE+'/oms-order/exchanges?exchangeId='+str(pending['id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('售后换货',exact=True)).to_be_visible();expect(drawer.get_by_text('客服协商',exact=True)).to_be_visible();drawer.get_by_role('tab',name='退回与验收',exact=True).click();panel=drawer.locator('.exchange-returns');expect(panel.get_by_text('差异待处理',exact=True)).to_be_visible();expect(panel.get_by_text('TEST-RETURN-'+c['case_id'],exact=True)).to_be_visible();expect(panel.get_by_text('TEST-NEGOTIATED-PARTIAL',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'partial-return-review.png'),full_page=False,animations='disabled');panel.get_by_text('验收与差异处理',exact=True).scroll_into_view_if_needed();page.screenshot(path=str(OUT/'return-receipts-and-evidence.png'),full_page=False,animations='disabled')
   panel.get_by_role('button',name='处理退回差异',exact=True).click();dialog=page.get_by_role('dialog',name='退回验收与差异处理',exact=True);dialog.locator('.el-select').first.click();normal=page.locator('.el-select-dropdown:visible').get_by_text('正常验收通过',exact=True);expect(normal.locator('..')).to_have_class(__import__('re').compile('is-disabled'));page.screenshot(path=str(OUT/'partial-cannot-pass.png'),full_page=False,animations='disabled');dialog.get_by_role('button',name='取消',exact=True).click()
   page.goto(BASE+'/oms-order/exchanges',wait_until='networkidle');page.get_by_role('button',name='创建协商换货',exact=True).click();create=page.get_by_role('dialog',name='创建协商换货',exact=True);create.get_by_text('退回后换出',exact=True).click();create.get_by_placeholder('输入完整 OMS 或平台单号',exact=True).fill(c['order_sn']);create.get_by_role('button',name='查找',exact=True).click();expect(create.get_by_text('已选原单：'+c['order_sn'],exact=True)).to_be_visible()
   expect(create.get_by_role('button',name='选择实际发货来源',exact=True)).to_be_visible();create.get_by_role('button',name='选择实际发货来源',exact=True).click();source_dialog=page.get_by_role('dialog',name='选择本次实际退回来源',exact=True);source_dialog.locator('.el-table__row').filter(has_text=original['shipment_sn']).get_by_role('button',name='选择此来源',exact=True).click();expect(source_dialog).not_to_be_visible();expect(create.locator('.el-input-number input')).to_be_enabled();create.locator('.el-input-number input').fill('2');create.locator('.el-input-number input').blur();expect(create.locator('.el-input-number input')).to_have_value('2');expect(create.get_by_text('退回虚拟仓',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'create-negotiated-after-sale.png'),full_page=False,animations='disabled');create.get_by_role('button',name='取消',exact=True).click();assert not errors,errors;b.close()
  c.update(verified=True,pending_partial=True,original_sales_shipped=10,completed_exchange=completed['exchange_sn'],no_platform_exchange_write=True);case.save();print('PASS negotiated after-sales: shared source, actual partial return, QC hold, complete replacement and UI; pending '+pending['exchange_sn'])
 finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
