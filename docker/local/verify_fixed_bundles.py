"""Retained TEST fixed-kit case: two warehouses, real component stock, original parent units, one partial kit return."""
import json,sys,subprocess,urllib.parse,urllib.request,xml.etree.ElementTree as ET
from datetime import datetime,timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from verify_exchange_ownership import Case
import verify_allocation_rules_v2 as fixture
from wms_qimen_simulator import sign
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/fixed-bundles';SUPPLY='qm_oms_saas_commodity';ORDER='qm_oms_saas_order';INV='qm_oms_saas_inventory'
def main():
 OUT.mkdir(parents=True,exist_ok=True);token=login();case=Case('fixedbundle',token);c=case.c
 def api(path,body=None):
  r=request(path,token=token,method='POST' if body is not None else 'GET',payload=body);return r.get('data',r)
 def totals():return {sku:int(sql("SELECT COALESCE(SUM(total_stock),0) FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INV)) for sku in [c['sku'],c['a'],c['b']]}
 def receive(sku,warehouse,qty):
  payload={'relationSn':'KI'+c['case_id']+'_'+str(c['warehouses'].index(warehouse))+'_'+sku[-1],'wmsInventoryBatch':{'companyCode':'QM','storeCode':warehouse['code'],'skuSn':sku,'batchCode':'KIT-'+c['case_id']+'-'+sku,'zpActualNumber':qty,'cpActualNumber':0,'transactionPrice':2.5 if sku==c['a'] else 4.5}}
  r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),capture_output=True,timeout=45)
  assert r.returncode==0 and json.loads(r.stdout)['code']==200,'TEST component receipt failed: '+r.stdout.decode('utf-8',errors='replace')[-350:]+r.stderr.decode('utf-8',errors='replace')[-100:]
 try:
  def ready():
   try:return api('/order/orders/catalog')
   except (AssertionError,OSError):return None
  wait_for(ready,lambda r:r is not None,'Kit services not ready',180)
  if not c.get('kit_initialized'):
   c.update(sku='K'+c['case_id']+'S',a='K'+c['case_id']+'A',b='K'+c['case_id']+'B',seller='KIT_'+c['case_id'],channel_name='[套装验收] '+c['case_id'],received=True,warehouses=[{'code':'KIT_V1_'+c['case_id'],'quantity':3,'package_count':1},{'code':'KIT_V2_'+c['case_id'],'quantity':3,'package_count':1}]);case.save()
   sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
   for sku,kit in [(c['a'],0),(c['b'],0),(c['sku'],1)]:
    old=api('/goods/info/list?pageNum=1&pageSize=20',{'skuSn':sku})['rows']
    if not old:api('/goods/info',{'skuSn':sku,'goodsSn':sku,'goodsName':'[套装验收] '+('销售套装S' if kit else '实际组件'+sku[-1]),'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':10 if kit else 2,'validity':30,'isPackage':kit,'isFd':0})
   c['goods_id']=api('/goods/info/list?pageNum=1&pageSize=20',{'skuSn':c['sku']})['rows'][0]['id'];rule=api('/goods/info/'+str(c['goods_id'])+'/bundle')
   if not rule['revision']:api('/goods/info/'+str(c['goods_id'])+'/bundle',{'revision':0,'enabled':1,'components':[{'sku_sn':c['a'],'quantity':2},{'sku_sn':c['b'],'quantity':1}]})
   c['kit_initialized']=True;case.save()
  def before_conversion(current,credential):
   if not c.get('components_ready'):
    for wh in c['warehouses']:
     receive(c['a'],wh,2);receive(c['b'],wh,1)
    for sku in [c['a'],c['b']]:sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(sku)+',10,'+quote(json.dumps([w['code'] for w in c['warehouses']]))+')',INV)
    c['components_ready']=True;case.save()
  if not c.get('prepared'):
   fixture.MANIFEST=case.path;fixture.prepare_case(c,token,before_conversion=before_conversion,order_quantity=2);c['prepared']=True;case.save()
  o=case.order();assert len(o['bundles'])==1 and o['bundles'][0]['quantity']==2 and o['bundles'][0]['revision']==1
  assert sorted((l['sku_sn'],l['quantity'],l['component_quantity']) for l in o['lines'])==sorted([(c['a'],4,2),(c['b'],2,1)])
  assert {l['platform_oid'] for l in o['lines']}=={c['platform_oid']} and not any(l['sku_sn']==c['sku'] for l in o['lines'])
  assert int(sql("SELECT COUNT(*) FROM oms_order_stock_demand WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+" AND platform_tid="+quote(c['platform_tid']),INV))==2
  assert totals()[c['sku']]==0
  # Show an order snapshot survives composition changes; restore the original recipe as v3 for future orders.
  if not c.get('version_checked'):
   rule=api('/goods/info/'+str(c['goods_id'])+'/bundle');rule['components'][0]['quantity']=1;api('/goods/info/'+str(c['goods_id'])+'/bundle',rule)
   assert case.order()['bundles'][0]['revision']==1 and case.order()['bundles'][0]['component_quantity']==6
   rule=api('/goods/info/'+str(c['goods_id'])+'/bundle');rule['components'][0]['quantity']=2;api('/goods/info/'+str(c['goods_id'])+'/bundle',rule);c['version_checked']=True;case.save()
  # The default whole-set rule must retain 2A+1B in each virtual warehouse.
  o=case.allocate();assert len(o['shipments'])==2 and all(s['quantity']==3 for s in o['shipments'])
  for s in o['shipments']:
   detail=case.shipment(s['id']);assert sorted((l['sku_sn'],l['quantity']) for l in detail['lines'])==sorted([(c['a'],2),(c['b'],1)])
  for s in o['shipments']:case.dispatch_ship(s)
  o=case.order();assert o['bundles'][0]['shipped_sets']==2 and o['bundles'][0]['shipped_component_quantity']==6
  if not c.get('returned_a'):assert totals()=={c['sku']:0,c['a']:0,c['b']:0}
  # Explicit enrollment is idempotent and only touches the local platform simulator.
  for s in o['shipments']:api('/order/orders/shipments/'+str(s['id'])+'/platform-shipping',{})
  reports=[api('/order/orders/shipments/'+str(s['id'])+'/platform-shipping') for s in o['shipments']]
  assert sum(int(t['snapshot']['goods'].get(c['platform_oid'],0)) for report in reports for t in report['tasks'])==2,reports
  assert all(t['quantity']==3 for report in reports for t in report['tasks'])
  if not c.get('refund_id'):
   source=next(s for s in api('/order/orders/sources') if s.get('database_name')=='refund_acceptance_source')
   configs=api('/order/refunds/configs')['configs']
   if not any(p['channel_id']==c['channel_id'] for p in configs):api('/order/refunds/configs',{'channel_id':c['channel_id'],'source_key':source['source_key'],'seller_nick':c['seller'],'initial_time':c['created'],'revision':0,'enabled':0,'auto_convert':0,'interval_seconds':30})
   rid='997'+c['case_id']+'99';modified=datetime.now().strftime('%Y-%m-%d %H:%M:%S');raw={'refund_id':rid,'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':'WAIT_SELLER_CONFIRM_GOODS','refund_fee':'5.00','num':1,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','refund_phase':'aftersale','refund_version':'1','reason':'[验收] 整套退1套，组件分次实收','created':modified,'modified':modified}
   vals=[quote(str(x)) for x in [rid,c['platform_tid'],c['platform_oid'],raw['status'],c['seller'],modified,modified,modified,json.dumps({'refund_get_response':{'refund':raw}},ensure_ascii=False)]]
   sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(vals)+')','refund_acceptance_source')
   task=api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[rid],'request_key':'KIT_REFUND_'+c['case_id']});wait_for(lambda:api('/order/refunds/tasks/'+str(task['id'])),lambda t:t['state']=='COMPLETED','Kit refund intake incomplete',150)
   platform=api('/order/refunds/platform?number='+rid)['rows'][0];converted=api('/order/refunds/platform/'+str(platform['id'])+'/convert',{});assert converted['state']=='SUCCESS';c['refund_id']=converted['id'];case.save()
  rb='/order/refunds/business/'+str(c['refund_id']);b=api(rb)
  if b['review_state']!='APPROVED':api(rb+'/review',{'control_version':b['control_version'],'platform_version':b['platform']['version'],'purpose':'CANCEL','goods_action':'RETURN','reason':'[验收] 整套退回，组件按实际来源入账','evidence':'TEST-KIT-RETURN'})
  b=api(rb);c['refund_sn']=b['refund_sn'];case.save()
  if not api(rb+'/goods')['bundle_returns']:api(rb+'/goods/bundle-plans',{'control_version':b['control_version'],'bundle_id':o['bundles'][0]['id'],'quantity':1,'store_code':c['warehouses'][0]['code'],'return_address':'[验收] 套装退回测试地址，无实际寄送','return_contact':'TEST-KIT','reason':'[验收] 应退A2+B1，先实收A2','evidence':'TEST-KIT-MANIFEST','confirmed':True})
  plans=api(rb+'/goods')['plans'];assert sorted((p['sku_sn'],p['quantity']) for p in plans)==sorted([(c['a'],2),(c['b'],1)])
  # One actual return parcel and one shared THK contain both component source lines.
  if not c.get('return_connection'):
   connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['connection_id']);credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id']);body={k:connection[k] for k in ['provider','api_version','environment','endpoint','app_key','customer_id']};body.update(name='[套装退回验收] '+c['case_id'],secret=credential['secret'],enabled=1,shared_return_waybill=1);c['return_connection']=api('/supplychain/wmsIntegration/connections',body);case.save()
   w=api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']));request('/supplychain/simulationStore',token=token,method='PUT',payload={'id':w['id'],'wmsSimulationCode':w['wmsSimulationCode'],'wmsSimulationName':w['wmsSimulationName'],'ownerWarehouseId':w['ownerWarehouseId'],'inboundMode':w['inboundMode'],'outboundMode':w['outboundMode'],'connectionId':c['return_connection'],'externalWarehouse':w['externalWarehouse'],'externalOwner':w['externalOwner'],'status':w['status']})
  parcels=api(rb+'/parcels')
  for p in plans:
   if any(any(m['plan_id']==p['id'] for m in box['members']) for box in parcels):continue
   b=api(rb);body={'control_version':b['control_version'],'plan_version':p['control_version'],'carrier_code':'SF','waybill':'TEST-KIT-RETURN-'+c['case_id'],'confirmed':True,'reason':'[验收] 套装组件同一实际包裹','evidence':'TEST-KIT-MANIFEST'}
   if parcels:body['join_parcel_id']=parcels[0]['id']
   api(rb+'/parcels/plans/'+str(p['id']),body);parcels=api(rb+'/parcels')
  parcel=parcels[0]
  if not parcel.get('warehouse_document'):api(rb+'/parcels/'+str(parcel['id'])+'/document/dispatch',{'parcel_version':parcel['control_version'],'confirmed_shared':True,'reason':'[验收] 同一包裹共同THK','evidence':'TEST-KIT-MANIFEST'})
  parcel=api(rb+'/parcels')[0];doc=parcel['warehouse_document'];assert doc['dispatch_state']=='ACCEPTED' and doc['quantity']==3;c.update(parcel_id=parcel['id'],return_sn=doc['return_sn']);case.save()
  connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['return_connection']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway';credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
  path=OUT/'partial-a-callback.xml'
  if not path.exists():
   member=next(m for m in parcel['members'] if m['sku_sn']==c['a']);root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
   for k,v in {'returnOrderCode':doc['return_sn'],'returnOrderId':doc['external_order'],'warehouseCode':parcel['external_warehouse'],'ownerCode':parcel['external_owner'],'outBizCode':'KIT_PARTIAL_A_'+c['case_id'],'orderType':'THRK','confirmType':'1'}.items():ET.SubElement(head,k).text=str(v)
   line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
   for k,v in {'orderLineNo':member['id'],'itemCode':c['a'],'ownerCode':parcel['external_owner'],'actualQty':2,'inventoryType':'ZP','batchCode':'KIT_RETURN_A'}.items():ET.SubElement(line,k).text=str(v)
   path.write_text(ET.tostring(root,encoding='unicode'),encoding='utf-8')
  wire=path.read_text('utf-8')
  marker={'customer':connection['customer_id'],'owner':parcel['external_owner'],'warehouse':parcel['external_warehouse'],'sn':doc['return_sn'],'external':doc['external_order']}
  script="import json,sqlite3\np=json.loads("+repr(json.dumps(marker))+")\nwith sqlite3.connect('/data/documents.sqlite',timeout=10) as db:\n row=db.execute('SELECT external_id,quantity,status FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',(p['customer'],'return',p['owner'],p['warehouse'],p['sn'])).fetchone()\n assert row and row[0]==p['external'] and row[1]==3 and row[2] in ('ACCEPT','PARTFULFILLED')\n db.execute(\"UPDATE documents SET status='PARTFULFILLED' WHERE customer=? AND kind='return' AND owner=? AND warehouse=? AND sn=?\",(p['customer'],p['owner'],p['warehouse'],p['sn']))\n"
  marked=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30);assert marked.returncode==0,'TEST WMS partial return state not recorded'
  params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret'])
  for _ in range(2):
   req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
   with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=40) as reply:response=ET.fromstring(reply.read())
   assert response.findtext('flag')=='success',response.findtext('message')
  api(rb+'/goods/refresh',{});goods=api(rb+'/goods');scope=goods['bundle_returns'][0];assert scope['quantity']==1 and scope['pending_quantity']==1
  assert sum(p['posted_quantity'] for p in goods['plans'])==2 and totals()=={c['sku']:0,c['a']:2,c['b']:0};assert api(rb)['goods_state']!='DONE';c['returned_a']=True;case.save()
  with sync_playwright() as pw:
   browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1800,'height':1160});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
   page.goto(BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']),wait_until='networkidle');page.get_by_role('tab',name='商品明细',exact=True).click();expect(page.locator('.kit-summary')).to_contain_text('完整发出 2 套');page.locator('.kit-summary').evaluate("el=>el.scrollIntoView({block:'start'})");page.locator('.kit-summary').screenshot(path=str(OUT/'order-parent-components.png'),animations='disabled')
   page.goto(BASE+'/oms-order/businessRefunds?id='+str(c['refund_id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);dialog.get_by_role('button',name='查看本退款货物进度',exact=True).click();expect(dialog).to_contain_text('整套退回');expect(dialog).to_contain_text('尚待退回／差异处理 1 件组件');dialog.locator('.plan').filter(has_text='整套退回').first.evaluate("el=>el.scrollIntoView({block:'start'})");page.screenshot(path=str(OUT/'return-missing-component.png'),animations='disabled');dialog.get_by_role('button',name='创建退回安排',exact=True).click();form=page.get_by_role('dialog',name='创建退款退回安排',exact=True);expect(form).to_contain_text('整套退回');expect(form).to_contain_text('指定组件退回');form.get_by_role('button',name='返回',exact=True).click();assert not errors,errors;browser.close()
  result={'order_sn':c['order_sn'],'order_url':BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']),'refund_sn':c['refund_sn'],'refund_url':BASE+'/oms-order/businessRefunds?id='+str(c['refund_id']),'sales_sets':2,'physical_shipped':6,'shipment_count':2,'shared_return_thk':c['return_sn'],'return_sets':1,'returned_a':2,'still_awaiting_b':1,'parent_stock_created':False,'duplicate_callback_no_extra_stock':True,'real_platform_write':False};(OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS retained fixed kit: parent2, A4+B2, two whole-set warehouses, returnA2 posted once and B1 still pending')
 finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
