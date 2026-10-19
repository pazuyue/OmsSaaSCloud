"""Retained TEST assembly, disassembly, partial-stop, callback replay and UI acceptance."""
import json,subprocess,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from wms_qimen_simulator import sign
from verify_exchange_ownership import Case
import verify_allocation_rules_v2 as fixture
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/kit-process';MANIFEST=OUT/'case.json';SUPPLY='qm_oms_saas_commodity';INV='qm_oms_saas_inventory'
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 c=json.loads(MANIFEST.read_text('utf-8')) if MANIFEST.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S')}
 def save():MANIFEST.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
 save();token=login()
 def api(path,body=None,method=None):
  r=request(path,token=token,payload=body,method=method or ('POST' if body is not None else 'GET'));return r.get('data',r)
 def internal(path,body=None):
  args=['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json']
  if body is not None:args+=['--data-binary','@-']
  args+=['http://localhost:9303'+path];r=subprocess.run(args,input=json.dumps(body).encode() if body is not None else None,capture_output=True,timeout=45);assert r.returncode==0,'TEST inventory endpoint unavailable';r=json.loads(r.stdout);assert r['code']==200,r.get('msg');return r.get('data')
 def total(sku):return int(sql("SELECT COALESCE(SUM(total_stock),0) FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INV))
 def detail(key):return api('/supplychain/wmsProcess/'+str(key))
 def action(key,op,extra=None):return api('/supplychain/wmsProcess/'+str(key)+'/'+op,{'control_version':detail(key)['control_version'],**(extra or {})})
 try:
  def ready():
   try:return api('/supplychain/wmsProcess/options')
   except (AssertionError,OSError):return None
  wait_for(ready,lambda v:v is not None,'Process services not ready',180)
  c.update(a='P'+c['stamp']+'A',b='P'+c['stamp']+'B',sku='P'+c['stamp']+'S',store='PROCESS_V_'+c['stamp']);save()
  template=next(w for w in api('/supplychain/simulationStore/list?pageSize=100')['rows'] if w['wmsSimulationCode']=='VC0001')
  source=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==template['connectionId']);assert source['environment']=='TEST' and source['endpoint']=='http://wms-simulator:8080/qimen/gateway'
  cred=next(x for x in json.loads(Path(__file__).with_name('wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==source['app_key'] and x['customer_id']==source['customer_id'])
  if not c.get('connection_id'):
   cfg={k:source[k] for k in ['provider','api_version','environment','endpoint','app_key','customer_id']};cfg.update(name='[加工验收] '+c['stamp'],secret=cred['secret'],enabled=1,storeprocess_enabled=1,storeprocess_cancel=1);c['connection_id']=api('/supplychain/wmsIntegration/connections',cfg);save()
  cfg=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['connection_id']);c['owner']=template['ownerCode'];save()
  if not sql("SELECT id FROM wms_simulation_store_info WHERE UPPER(company_code)='QM' AND wms_simulation_code="+quote(c['store']),SUPPLY):
   api('/supplychain/simulationStore',{'wmsSimulationCode':c['store'],'wmsSimulationName':'[加工验收] 独立虚拟仓','ownerWarehouseId':template['ownerWarehouseId'],'inboundMode':2,'outboundMode':1,'connectionId':c['connection_id'],'externalWarehouse':template['externalWarehouse'],'externalOwner':template['externalOwner'],'status':2})
  sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
  for sku,kit in [(c['a'],0),(c['b'],0),(c['sku'],1)]:
   found=api('/goods/info/list?pageNum=1&pageSize=10',{'skuSn':sku})['rows']
   if not found:api('/goods/info',{'skuSn':sku,'goodsSn':sku,'goodsName':'[加工验收] '+('实物套装S' if kit else '组件'+sku[-1]),'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':20,'validity':30,'isPackage':kit,'isFd':0})
  c['goods_id']=api('/goods/info/list?pageNum=1&pageSize=10',{'skuSn':c['sku']})['rows'][0]['id'];rule=api('/goods/info/'+str(c['goods_id'])+'/bundle')
  if not rule['revision']:api('/goods/info/'+str(c['goods_id'])+'/bundle',{'revision':0,'enabled':1,'inventory_mode':'STOCKED','components':[{'sku_sn':c['a'],'quantity':2},{'sku_sn':c['b'],'quantity':1}]})
  if not c.get('stock_received'):
   for sku,qty,price in [(c['a'],20,2.5),(c['b'],10,4.5)]:internal('/api/WmsInventory/addInventory?company_code=QM',{'relationSn':'PR'+c['stamp']+sku[-1],'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['store'],'skuSn':sku,'batchCode':'RAW_'+sku[-1],'zpActualNumber':qty,'cpActualNumber':0,'transactionPrice':price}})
   c['stock_received']=True;save()
  if not c.get('assembly_id'):c['assembly_id']=api('/supplychain/wmsProcess',{'operation':'ASSEMBLE','sku_sn':c['sku'],'store_code':c['store'],'quantity':4,'reason':'[验收] 组装4套，先完成2套，再停止剩余','request_key':'PR_A_'+c['stamp']})['id'];save()
  a=detail(c['assembly_id'])
  if a['state'] in ['DRAFT','RESERVING']:a=action(a['id'],'reserve')
  if a['dispatch_state'] in ['PENDING','FAILED','UNKNOWN']:a=action(a['id'],'dispatch')
  assert a['dispatch_state'] in ['ACCEPTED','CANCELED'],a.get('last_error')
  def item(sku,batch,qty):return {'itemCode':sku,'batchCode':batch,'quantity':qty,'inventoryType':'ZP','ownerCode':template['externalOwner']}
  def callback(t,message,qty,materials,products):
   root=ET.Element('request')
   for k,v in {'processOrderCode':t['process_sn'],'processOrderId':t['external_order'],'ownerCode':t['external_owner'],'warehouseCode':t['external_warehouse'],'outBizCode':message,'orderType':'CNJG','actualQty':qty}.items():ET.SubElement(root,k).text=str(v)
   for name,lines in [('materialitems',materials),('productitems',products)]:
    group=ET.SubElement(root,name)
    for line in lines:
     node=ET.SubElement(group,'item')
     for k,v in line.items():ET.SubElement(node,k).text=str(v)
   body=ET.tostring(root,encoding='unicode');(OUT/(message+'.xml')).write_text(body,encoding='utf-8');params={'app_key':cfg['app_key'],'customerId':cfg['customer_id'],'method':'taobao.qimen.storeprocess.confirm','format':'xml','sign_method':'md5','v':'2.0','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,body,cred['secret']);req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/QM/'+cfg['callback_key']+'?'+urllib.parse.urlencode(params),data=body.encode(),headers={'Content-Type':'application/xml'})
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as response:r=ET.fromstring(response.read())
   assert r.findtext('flag')=='success',r.findtext('message');return detail(t['id'])
  message='PR_ASSEMBLY_'+c['stamp'];finished='FIN_'+c['stamp']
  a=callback(a,message,2,[item(c['a'],'RAW_A',4),item(c['b'],'RAW_B',2)],[item(c['sku'],finished,2)]);assert a['completed_quantity']==2 and a['events'][0]['state']=='POSTED',a
  a=callback(a,message,2,[item(c['a'],'RAW_A',4),item(c['b'],'RAW_B',2)],[item(c['sku'],finished,2)]);assert len(a['events'])==1
  if a['state']!='STOPPED':a=action(a['id'],'stop',{'reason':'[验收] 已完成2套，明确停止剩余2套'})
  assert a['state']=='STOPPED' and a['completed_quantity']==2 and len(a['tickets'])==2
  if not c.get('disassembly_id'):
   batch=api('/supplychain/wmsProcess/batches?'+urllib.parse.urlencode({'sku':c['sku'],'store':c['store']}))[0];c['disassembly_id']=api('/supplychain/wmsProcess',{'operation':'DISASSEMBLE','sku_sn':c['sku'],'store_code':c['store'],'quantity':1,'source_batch_id':batch['id'],'reason':'[验收] 原批次拆卸1套，恢复组件库存及原成本','request_key':'PR_D_'+c['stamp']})['id'];save()
  d=detail(c['disassembly_id'])
  if d['state'] in ['DRAFT','RESERVING']:d=action(d['id'],'reserve')
  if d['dispatch_state'] in ['PENDING','FAILED','UNKNOWN']:d=action(d['id'],'dispatch')
  d=callback(d,'PR_DISASSEMBLY_'+c['stamp'],1,[item(c['a'],'REC_A_'+c['stamp'],2),item(c['b'],'REC_B_'+c['stamp'],1)],[item(c['sku'],finished,1)]);assert d['state']=='COMPLETED' and d['completed_quantity']==1 and len(d['tickets'])==2,d
  d=callback(d,'PR_DISASSEMBLY_'+c['stamp'],1,[item(c['a'],'REC_A_'+c['stamp'],2),item(c['b'],'REC_B_'+c['stamp'],1)],[item(c['sku'],finished,1)])
  assert total(c['a'])==18 and total(c['b'])==9 and total(c['sku'])==(0 if c.get('sales_shipped') else 1)
  assert int(sql("SELECT COALESCE(SUM(zp_lock_number),0) FROM wms_inventory WHERE company_code='QM' AND store_code="+quote(c['store']),INV))==0
  logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'number':a['process_sn'],'numberType':'DOCUMENT','allTime':'true'}))['rows'];assert {r['action'] for r in logs}>={'PROCESS_CREATE','PROCESS_RECEIPT','PROCESS_CANCEL'} and all(r['business_type']=='PROCESS_ASSEMBLY' for r in logs)
  # Use the stocked parent in an actual OMS order and WMS shipment, without expanding/deducting components again.
  ordercase=Case('stockedprocess',token);oc=ordercase.c
  if not oc.get('prepared'):
   oc.update(sku=c['sku'],channel_name='[实物套装验收] '+c['stamp'],seller='PROCESS_'+c['stamp'],received=True,warehouses=[{'code':c['store'],'quantity':1,'package_count':1}]);ordercase.save();fixture.MANIFEST=ordercase.path
   def scope(current,credential):sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(current['channel_id'])+','+quote(c['sku'])+',1,'+quote(json.dumps([c['store']]))+')',INV)
   fixture.prepare_case(oc,token,order_quantity=1,template_code=c['store'],before_conversion=scope);oc['prepared']=True;ordercase.save()
  order=ordercase.order();assert len(order['lines'])==1 and order['lines'][0]['sku_sn']==c['sku'] and order['lines'][0]['quantity']==1 and order['lines'][0]['stocked_bundle_snapshot'] and not order['bundles'],order
  order=ordercase.allocate();assert len(order['shipments'])==1 and order['shipments'][0]['quantity']==1
  for shipment in order['shipments']:ordercase.dispatch_ship(shipment)
  order=ordercase.order();assert order['shipped_quantity']==1;assert total(c['a'])==18 and total(c['b'])==9 and total(c['sku'])==0;c['sales_shipped']=True;c['order_id']=order['id'];c['order_sn']=order['order_sn'];save()
  for process in [a,d]:
   for voucher in process['tickets']:
    document=api('/supplychain/purchaseWorkspace/ticket/'+str(voucher['id']));assert document['processId']==process['id'] and document['modeLabel']=='加工记账凭证' and document['numberPosted']==document['numberActually'] and all(l['numberPosted']==l['numberActually'] for l in document['lines']),document
    batches=api('/supplychain/purchaseWorkspace/ticket/'+str(voucher['id'])+'/batches');assert batches['total']==len(document['lines']) and all(l['posted']==1 for l in batches['rows'])
  with sync_playwright() as pw:
   browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1720,'height':1080});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
   page.goto(BASE+'/oms-inventory/kitProcess?id='+str(a['id']),wait_until='networkidle');expect(page.locator('.kit-process h2')).to_be_visible();expect(page.locator('.detail')).to_contain_text('剩余已停止');expect(page.locator('.detail')).to_contain_text('投入与产出均已记账');page.screenshot(path=str(OUT/'assembly-partial-stop.png'),animations='disabled')
   page.goto(BASE+'/oms-inventory/kitProcess?id='+str(d['id']),wait_until='networkidle');expect(page.locator('.detail')).to_contain_text('加工完成');page.screenshot(path=str(OUT/'disassembly-completed.png'),animations='disabled')
   page.goto(BASE+'/oms-inventory/kitProcess',wait_until='networkidle');page.get_by_role('button',name='新建加工单',exact=True).click();expect(page.get_by_role('dialog',name='新建套装加工单')).to_contain_text('实物套装');assert not errors,errors;browser.close()
  result={'assembly_sn':a['process_sn'],'assembly_url':BASE+'/oms-inventory/kitProcess?id='+str(a['id']),'planned_sets':4,'assembled_sets':2,'stopped_sets':2,'disassembly_sn':d['process_sn'],'disassembly_url':BASE+'/oms-inventory/kitProcess?id='+str(d['id']),'disassembled_sets':1,'stock':{'A':18,'B':9,'S':0},'order_sn':c['order_sn'],'order_url':BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']),'voucher_count':4,'callback_replay_no_extra_stock':True,'warehouse_log_classification':True,'environment':'TEST'};(OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS TEST processing: assembled2/stopped2, disassembled1, sold S1 with no second component deduction, A18 B9 S0, four process vouchers, callback idempotency and UI')
 finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
