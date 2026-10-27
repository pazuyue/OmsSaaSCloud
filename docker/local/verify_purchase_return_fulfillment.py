"""Retained TEST acceptance. OMS mutations use business APIs and authenticated WMS callbacks.
No SQL edits to business status, inventory, jobs or queue policies.
"""
import hashlib,json,time,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-fulfillment'
SC='/supplychain';PO=SC+'/purchaseWorkspace';RET=SC+'/purchaseReturns';INV='qm_oms_saas_inventory';DB='qm_oms_saas_commodity'
DEFS=[('parcels','分次出库、多包裹与逐来源拒收收回','complete'),('active_continue','原执行未最终确认，同单继续并沿用占用','complete'),('concurrent_continue','最终短出后并发申请继续，同CGT只追加一次','complete'),('restore','停止释放后同单恢复，连续两轮保留库存流水','complete'),('fragment','同消息分片、多批次同SKU包裹明确原行','split'),('cumulative','带版本累计快照与迟到旧版本','cumulative'),('incomplete','明细不齐阻止停止和释放','split'),('bad','次品出库与按实际质量收回','complete'),('restore_conflict','释放后原来源被其他退仓占用，恢复被阻止','complete'),('supplier','先登记执行商品、后关联包裹且不重复验收','complete'),('ready','最终短出后保留待继续余量，供页面操作验收','complete')]

def baseline():
 return dict(protected=protected(),stock=sql("SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'WC\\_%' OR sku_sn LIKE 'PRQ\\_%' OR sku_sn LIKE 'SCIT\\_%') ORDER BY sku_sn",INV),original=sql("SELECT id,return_sn,state,quantity,accepted_quantity,posted_quantity,dispatch_state,receipt_final,stock_reserved,control_version FROM purchase_return WHERE company_code='QM' AND id IN(17,20,21) ORDER BY id",DB),reports={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'outputs/warehouse-control/manifest.json',ROOT/'outputs/purchase-return-iteration/scenarios.json']})

def main():
 OUT.mkdir(parents=True,exist_ok=True);path=OUT/'manifest.json';s=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),commands={},bodies={},results=[],cases=[],protected_before=baseline());stamp=s['stamp'];token=login()
 def save():path.write_text(json.dumps(s,ensure_ascii=False,indent=2),'utf-8')
 def api(p,b=None):return request(p,payload=b,token=token)
 def post(tag,p,b):
  if tag not in s['commands']:s['commands'][tag]=dict(path=p,body=b);save()
  c=s['commands'][tag]
  if c.get('response') is not None and (c['path'].endswith('/approve') or c['path'].endswith('/cancel')):return c['response']
  result=api(c['path'],c['body']);c['response']=result;save();return result
 def wait(fn,ok,label):
  for _ in range(90):
   value=fn()
   if ok(value):return value
   time.sleep(1)
  raise AssertionError(label+': '+json.dumps(value,ensure_ascii=False,default=str)[:1500])
 def doc(c):return api(RET+'/'+str(c['return_id']))['data']
 def check(c,total,available,allocated=0,frozen=0):
  r=api('/inventory/productInventory?'+urllib.parse.urlencode(dict(skuSn=c['sku'])))['rows'][0];assert r['checkStatus']=='CONSISTENT',r
  result=dict(sku=c['sku'],total=r['totalStock'],available=r['availableStock'],allocated=r['allocatedStock'],reserved=r['reservedStock'],frozen=r['frozenStock'])
  assert [result[k] for k in ['total','available','allocated','frozen']]==[total,available,allocated,frozen],result
  result['warehouses']=sql("SELECT store_code,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,zp_lock_number,cp_lock_number FROM wms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku'])+" ORDER BY store_code",INV)
  result['batches']=sql("SELECT store_code,batch_code,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,zp_lock_number,cp_lock_number,transaction_price FROM wms_inventory_batch WHERE company_code='QM' AND sku_sn="+quote(c['sku'])+" ORDER BY id",INV)
  return result
 def record(key,title,c,scenario,fn):
  if any(r['key']==key for r in s['results']):return
  evidence=fn();s['results'].append(dict(key=key,title=title,scenario=scenario,return_id=c['return_id'],return_sn=c['return_sn'],sku=c['sku'],result='PASS',evidence=evidence,verified_at=datetime.now().isoformat()));save();print('PASS',key,title,flush=True)
 def failure(p,b,contains):
  try:api(p,b)
  except AssertionError as e:assert contains in str(e),(contains,str(e));return str(e)
  raise AssertionError('Expected rejection: '+contains)
 def command(c,tag,**fields):return dict(request_key='PRF_'+stamp+'_'+tag,control_version=doc(c)['control_version'],**fields)
 def tags(p,values):
  for k,v in values.items():ET.SubElement(p,k).text=str(v)
 credentials=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
 save()
 if not s.get('owner'):s['owner']=api(SC+'/owner',dict(ownerCode='PRFO_'+stamp,ownerName='[同单分次退仓TEST] '+stamp,isEnable=2))['data'];save()
 if not s.get('warehouse'):s['warehouse']=api(SC+'/realStore',dict(realStoreCode='PRFW_'+stamp,wmsName='[同单分次退仓TEST] '+stamp,wmsType=1,status=2))['data'];save()
 if not s.get('relation'):s['relation']=api(SC+'/ownerWarehouse',dict(ownerId=s['owner'],realStoreId=s['warehouse'],status=2))['data'];save()
 for mode in ['complete','split','cumulative']:
  if not s.get('connection_'+mode):s['connection_'+mode]=api(SC+'/wmsIntegration/connections',dict(name='[同单分次退仓TEST] '+mode+' '+stamp,provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=credentials['app_key'],customer_id=credentials['customer_id'],secret=credentials['secret'],enabled=1,partial_inbound_cancel=1,purchase_return_quantity_mode='CUMULATIVE' if mode=='cumulative' else 'INCREMENTAL',purchase_return_fragment_mode='SPLIT' if mode=='split' else 'COMPLETE'))['data'];save()
  if not s.get('store_'+mode):
   code='PRFV_'+mode+'_'+stamp;api(SC+'/simulationStore',dict(wmsSimulationCode=code,wmsSimulationName='[同单分次退仓TEST] '+mode+' '+stamp,ownerWarehouseId=s['relation'],inboundMode=1,outboundMode=1,status=2,connectionId=s['connection_'+mode],externalWarehouse='PRF_EXT_'+stamp,externalOwner='PRF_OWNER_'+stamp));s['store_'+mode]=code;save()
 if not s.get('supplier'):s['supplier']=api(PO+'/supplier',dict(supplierName='[同单分次退仓TEST] '+stamp,companyName='本地TEST供应商',status=2))['data'];s['supplier_sn']=api(PO+'/supplier/'+str(s['supplier']))['data']['supplierSn'];save()
 connections={c['id']:c for c in api(SC+'/wmsIntegration/connections')['data']}
 def wire(tag,mode,method,root,expected='success'):
  if tag not in s['bodies']:s['bodies'][tag]=ET.tostring(root,encoding='unicode');save()
  body=s['bodies'][tag];connection=connections[s['connection_'+mode]];assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
  params=dict(method='taobao.qimen.'+method,timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=credentials['app_key'],customerId=credentials['customer_id'],v='2.0',sign_method='md5');params['sign']=sign(params,body,credentials['secret'])
  url=BASE+'/prod-api'+SC+'/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params);r=ET.fromstring(urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40).read());assert r.findtext('flag')==expected,r.findtext('message');return r.findtext('message')
 catalog=api('/goods/info/options')['data']
 if not s['cases']:s['cases']=[dict(key=k,title=t,mode=m,sku='PRF_'+stamp+'_'+str(i).zfill(2)) for i,(k,t,m) in enumerate(DEFS,1)];save()
 known={c['key'] for c in s['cases']}
 for i,(k,t,m) in enumerate(DEFS,1):
  if k not in known:s['cases'].append(dict(key=k,title=t,mode=m,sku='PRF_'+stamp+'_'+str(i).zfill(2)));save()
 def purchase(c,batch_quantities):
  if not c.get('purchase_id'):c['purchase_id']=api(PO+'/purchase',dict(poName='[同单分次退仓TEST] '+c['title'],supplierSn=s['supplier_sn'],wmsSimulationCode=s['store_'+c['mode']],lines=[dict(skuSn=c['sku'],quantity=10,purchasePrice=2.5)]))['data'];save()
  p=api(PO+'/purchase/'+str(c['purchase_id']))['data'];c['po_sn']=p['poSn']
  if p['poState']==1:post('PO_APPROVE_'+c['key'],PO+'/purchase/'+str(c['purchase_id'])+'/approve',{})
  for i,(good,bad) in enumerate(batch_quantities):
   tag=c['key']+'_'+str(i)
   if not c.get('receipt_'+str(i)):c['receipt_'+str(i)]=post('PO_RECEIPT_'+tag,PO+'/purchase/'+str(c['purchase_id'])+'/receipt',dict(lines=[dict(lineId=p['lines'][0]['id'],quantity=good+bad)]))['data'];save()
   if not c.get('ticket_'+str(i)):
    receipt=api(PO+'/receipt/'+str(c['receipt_'+str(i)]))['data'];c['ticket_'+str(i)]=receipt['tickets'][0]['id'] if receipt['tickets'] else post('PO_RECEIPT_APPROVE_'+tag,PO+'/receipt/'+str(c['receipt_'+str(i)])+'/approve',{})['data'];save()
   task=wait(lambda:api(SC+'/wmsIntegration/tickets/'+str(c['ticket_'+str(i)]))['data'],lambda x:x['dispatch_state']=='ACCEPTED','Purchase dispatch')
   root=ET.Element('request');h=ET.SubElement(root,'entryOrder');facts=[(q,g) for q,g in [(good,'ZP'),(bad,'CC')] if q];tags(h,dict(entryOrderCode=task['ticket_sn'],entryOrderId=task['external_order'],warehouseCode=task['external_warehouse'],ownerCode=task['external_owner'],status='FULFILLED',confirmType=0,outBizCode='PRF_IN_'+tag,totalOrderLines=len(facts)));ls=ET.SubElement(root,'orderLines')
   for q,g in facts:tags(ET.SubElement(ls,'orderLine'),dict(itemCode=c['sku'],ownerCode=task['external_owner'],batchCode='PRF_BATCH_'+tag,actualQty=q,inventoryType=g))
   wire('IN_'+tag,c['mode'],'entryorder.confirm',root);wait(lambda:api(PO+'/ticket/'+str(c['ticket_'+str(i)]))['data'],lambda x:x['numberPosted']==good+bad,'Purchase posting')
 def new_return(c,tag,quantities):
  source=api(RET+'/sources?purchaseId='+str(c['purchase_id']))['data'];rows=[r for r in source['rows'] if r['inventory_type']==('CP' if c['key']=='bad' else 'ZP')];assert len(rows)>=len(quantities)
  return post('NEW_'+tag,RET,dict(request_key='PRF_'+stamp+'_'+tag,purchase_id=c['purchase_id'],receiver=dict(name='[TEST]供应商商品验收',mobile='13000000000',province='浙江省',city='杭州市',area='西湖区',detailAddress='本地验收TEST地址'),carrier_code='SF',reason='[同单分次退仓TEST] '+tag,lines=[dict(source_key=r['source_key'],inventory_type=r['inventory_type'],quantity=q) for r,q in zip(rows,quantities)]))['data']
 for c in s['cases']:
  if not c.get('product'):c['product']=api('/goods/info',dict(skuSn=c['sku'],goodsSn=c['sku'],goodsName='[同单分次退仓TEST] '+c['title'],marketPrice=10,validity=30,categoryCode=next(x['id'] for x in catalog['category'] if x['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))['data'];save()
  if not c.get('seeded'):purchase(c,[(5,0),(5,0)] if c['key'] in ['fragment','incomplete'] else [(7,3)] if c['key']=='bad' else [(10,0)]);c['initial_stock']=check(c,10,7 if c['key']=='bad' else 10,0,3 if c['key']=='bad' else 0);c['seeded']=True;save()
  if not c.get('return_id'):c['return_id']=new_return(c,c['key'],[2,2] if c['key'] in ['fragment','incomplete'] else [3] if c['key'] in ['bad','supplier'] else [5]);save()
  d=doc(c);c['return_sn']=d['return_sn'];save()
  if d['state']=='DRAFT':post('APPROVE_'+c['key'],RET+'/'+str(c['return_id'])+'/approve',dict(control_version=d['control_version']))
  if not c.get('approved_stock'):wait(lambda:doc(c),lambda d:d['dispatch_state']=='ACCEPTED','Automatic hold/dispatch');c['approved_stock']=check(c,10,7 if c['key']=='bad' else 10-(4 if c['key'] in ['fragment','incomplete'] else 3 if c['key']=='supplier' else 5),3 if c['key'] in ['bad','supplier'] else 4 if c['key'] in ['fragment','incomplete'] else 5,0);save()
 cases={c['key']:c for c in s['cases']}
 def out(c,tag,quantities,end=True,packs=None,revision=None,execution=None,message=None,total=None):
  d=doc(c);e=next(x for x in d['executions'] if x['id']==execution) if execution else d['executions'][0];facts=[(e['lines'][i],q) for i,q in quantities];root=ET.Element('request');h=ET.SubElement(root,'deliveryOrder');tags(h,dict(deliveryOrderCode=e['execution_sn'],deliveryOrderId=e['external_order'],orderType='CGTH',ownerCode='PRF_OWNER_'+stamp,warehouseCode='PRF_EXT_'+stamp,outBizCode=message or tag,status='DELIVERED' if end else 'PARTDELIVERED',confirmType=0 if end else 1,totalOrderLines=len(facts) if total is None else total,logisticsCode='SF',logisticsName='[TEST]顺丰',expressCode='PRF_WAYBILL_'+c['key']))
  if revision is not None:tags(ET.SubElement(h,'extendProps'),dict(omsSnapshotVersion=revision))
  group=ET.SubElement(root,'orderLines')
  for l,q in facts:tags(ET.SubElement(group,'orderLine'),dict(orderLineNo=l['id'],itemCode=l['sku_sn'],ownerCode='PRF_OWNER_'+stamp,batchCode=l['batch_code'],inventoryType='CC' if l['inventory_type']=='CP' else 'ZP',actualQty=q))
  if packs:
   ps=ET.SubElement(root,'packages')
   for code,contents in packs:
    p=ET.SubElement(ps,'package');tags(p,dict(packageCode=code,logisticsCode='SF',expressCode='PRF_SHARED_'+c['key']));items=ET.SubElement(p,'items')
    for index,q in contents:
     l=e['lines'][index or 0];values=dict(itemCode=l['sku_sn'],quantity=q)
     if index is not None:values.update(orderLineNo=l['id'],batchCode=l['batch_code'],inventoryType='CC' if l['inventory_type']=='CP' else 'ZP')
     tags(ET.SubElement(items,'item'),values)
  return wire(tag,c['mode'],'stockout.confirm',root)
 def posted(c,q):return wait(lambda:doc(c),lambda d:d['posted_quantity']==q,'Stock posting')
 def continue_(c,tag):return post(tag,RET+'/'+str(c['return_id'])+'/continue',command(c,tag,reason='[TEST]供应商同意原CGT剩余数量继续退仓'))['data']
 def next_execution(c):return wait(lambda:doc(c),lambda d:len(d['executions'])>=2 and d['executions'][0]['dispatch_state']=='ACCEPTED','Next warehouse execution')
 def supplier(c,tag,decision,lines):return post(tag,RET+'/'+str(c['return_id'])+'/supplier-receipt',command(c,tag,decision=decision,evidence='[TEST]供应商实际商品验收 '+tag,lines=lines))['data']
 def supplement(c,tag,package,q):
  e=doc(c)['executions'][0];l=e['lines'][0];return post(tag,RET+'/'+str(c['return_id'])+'/parcels/supplement',command(c,tag,execution_id=e['id'],package_code=package,carrier_code='SF',waybill='PRF_LATE_'+tag,evidence='[TEST]仓库补充原装箱凭证',items=[dict(sku_sn=l['sku_sn'],line_key=l['id'],quantity=q,batch_code=l['batch_code'],inventory_type=l['inventory_type'])]))['data']
 def reclaim(c,tag,lines):
  id=post(tag,RET+'/'+str(c['return_id'])+'/reclaims',command(c,tag,reason='[TEST]按实际拒收来源收回',carrier_code='SF',waybill='PRF_BACK_'+tag,lines=lines))['data'];c['reclaim_id']=id;save();r=api(RET+'/reclaims/'+str(id))['data']
  if r['state']=='DRAFT':post(tag+'_APPROVE',RET+'/reclaims/'+str(id)+'/approve',dict(control_version=r['control_version']))
  return wait(lambda:api(RET+'/reclaims/'+str(id))['data'],lambda r:r['dispatch_state']=='ACCEPTED','Reclaim dispatch')
 def receive(c,tag,facts):
  r=api(RET+'/reclaims/'+str(c['reclaim_id']))['data'];root=ET.Element('request');h=ET.SubElement(root,'entryOrder');tags(h,dict(entryOrderCode=r['reclaim_sn'],entryOrderId=r['external_order'],entryOrderType='QTRK',ownerCode='PRF_OWNER_'+stamp,warehouseCode='PRF_EXT_'+stamp,outBizCode=tag,status='FULFILLED',confirmType=0,totalOrderLines=len(facts)));group=ET.SubElement(root,'orderLines')
  for index,grade,q in facts:
   l=r['lines'][index];tags(ET.SubElement(group,'orderLine'),dict(orderLineNo=l['id'],itemCode=l['sku_sn'],ownerCode='PRF_OWNER_'+stamp,batchCode=l['batch_code'],inventoryType=grade,actualQty=q))
  wire(tag,c['mode'],'entryorder.confirm',root);return wait(lambda:api(RET+'/reclaims/'+str(c['reclaim_id']))['data'],lambda r:r['state']=='COMPLETED','Reclaim actual receipt posting')
 c=cases['parcels']
 def split_packages():
  out(c,'PARCELS_M1',[(0,2)],False,[('P1',[(0,1)]),('P2',[(0,1)])]);posted(c,2);mid=check(c,8,5,3);out(c,'PARCELS_M1',[(0,2)],False,[('P1',[(0,1)]),('P2',[(0,1)])]);assert doc(c)['posted_quantity']==2;out(c,'PARCELS_M2',[(0,3)],True,[('P3',[(0,3)])]);d=posted(c,5);assert len(d['parcels'])==3 and len(d['parcel_allocations'])==3;assert len({p['waybill'] for p in d['parcels']})==1;return dict(initial=c['initial_stock'],approved=c['approved_stock'],after_first=mid,after_second=check(c,5,5),document=d)
 record('01','一次执行分两次出库，三个包裹共用运单且不重复扣账',c,'采购实收10件，退仓5件。先实出2件装在P1/P2，重放消息；再实出3件装在P3。期望只扣5件，保留三个独立包裹和逐行商品关联。',split_packages)
 def supplier_parcels():
  d=doc(c);alloc={a['package_code']:a for a in d['parcel_allocations']};supplier(c,'P1_ACCEPT','ACCEPT',[dict(allocation_id=alloc['P1']['id'],quantity=1)]);supplier(c,'P2_REFUSE','REFUSE',[dict(allocation_id=alloc['P2']['id'],quantity=1)]);d=supplier(c,'P3_REFUSE','REFUSE',[dict(allocation_id=alloc['P3']['id'],quantity=3)]);assert sum(x['quantity'] for x in d['supplier_sources'] if x['decision']=='REFUSE')==4;return dict(document=d,stock=check(c,5,5))
 record('02','按三个包裹分别接受1件、拒收1件及拒收3件',c,'同一原商品来自三个物理包裹。供应商按P1接受1、P2拒收1、P3拒收3；物流签收不代替商品验收，登记不增加本仓库存。',supplier_parcels)
 def reclaim_sources():
  d=doc(c);sources={x['parcel_bindings'][0]['package_code']:x for x in d['supplier_sources'] if x['decision']=='REFUSE'};r=reclaim(c,'MULTI_REFUSAL',[dict(supplier_source_id=sources['P2']['id'],quantity=1),dict(supplier_source_id=sources['P3']['id'],quantity=2)]);assert len(r['lines'])==2 and r['lines'][0]['return_line_id']==r['lines'][1]['return_line_id'];before=check(c,5,5);r=receive(c,'MULTI_REFUSAL_IN',[(0,'ZP',1),(1,'ZP',1),(1,'CC',1)]);receive(c,'MULTI_REFUSAL_IN',[(0,'ZP',1),(1,'ZP',1),(1,'CC',1)]);d=doc(c);assert d['posted_quantity']==5 and sum(x['recovered_quantity'] for x in d['lines'])==3;return dict(before_actual=before,after_actual=check(c,8,7,0,1),reclaim=r,document=d)
 record('03','同一原行的两条拒收来源合在一张收回单，按实际质量入账',c,'P2拒收1件、P3拒收3件，从两条来源分别安排1件和2件；审核受理不加库存。仓库实际收到正品2、次品1，重放实收不重复入账。原出库5件保留，总库存5→8，可用5→7，次品冻结1。',reclaim_sources)
 def caps_and_funds():
  d=doc(c);source=next(x for x in d['supplier_sources'] if x['decision']=='REFUSE' and x['quantity']==3);err=failure(RET+'/'+str(c['return_id'])+'/reclaims',command(c,'RECLAIM_OVER',reason='[TEST]超额收回',carrier_code='SF',waybill='TEST_OVER',lines=[dict(supplier_source_id=source['id'],quantity=2)]),'超过该次供应商拒收');post('FUNDS_REFUND',RET+'/'+str(c['return_id'])+'/funds',command(c,'FUNDS_REFUND',funds_state='REFUND',amount=3,evidence='[TEST]已收到退款凭证3元'));d=post('FUNDS_CREDIT',RET+'/'+str(c['return_id'])+'/funds',command(c,'FUNDS_CREDIT',funds_state='CREDIT',amount=2,evidence='[TEST]已核对抵扣凭证2元'))['data'];assert float(d['net_posted_amount'])==5 and float(d['funds_amount'])==5;return dict(rejection=err,document=d,stock=check(c,8,7,0,1))
 record('04','逐拒收来源限制收回，款项按净退仓货值分别登记',c,'P3拒收3件已安排2件，只剩1件可收回，再申请2件应拒绝。已出库货值12.50元、收回7.50元，净货值5元；登记退款3加抵扣2，库存不变。',caps_and_funds)
 c=cases['active_continue']
 def active_continue():
  out(c,'ACTIVE_M1',[(0,2)],False,[('A1',[(0,2)])],message='M1');posted(c,2);e1=doc(c)['executions'][0];before=check(c,8,5,3);continue_(c,'ACTIVE_CONTINUE');d=next_execution(c);assert len(d['executions'])==2 and d['executions'][1]['cancel_state']=='CONFIRMED' and d['executions'][0]['hold_action']=='EXISTING';held=check(c,8,5,3);out(c,'ACTIVE_M2',[(0,3)],True,[('A2',[(0,3)])],message='M1');d=posted(c,5);return dict(before_continue=before,after_continue=held,after_second=check(c,5,5),original_execution=e1,document=d)
 record('05','原执行未结束先确认停止，再同单追加执行，三件占用始终保留',c,'退仓5件先中间出库2件。继续剩余3件时自动调用原执行order.cancel，确认停止后在原CGT追加-02；不释放、不重新抢库存。第二次执行复用消息号M1仍独立入账，最终库存5、可用5。',active_continue)
 c=cases['concurrent_continue']
 def concurrent_continue():
  if not c.get('concurrent_results'):
   out(c,'CONCURRENT_FIRST',[(0,2)]);posted(c,2);c['concurrent_bodies']=[command(c,'CONCURRENT_'+str(i),reason='[TEST]两人同时继续剩余3件') for i in range(2)];save()
  bodies=c['concurrent_bodies']
  def submit(body):
   try:return dict(ok=True,result=api(RET+'/'+str(c['return_id'])+'/continue',body))
   except AssertionError as e:return dict(ok=False,error=str(e))
  if not c.get('concurrent_results'):
   with ThreadPoolExecutor(max_workers=2) as pool:c['concurrent_results']=list(pool.map(submit,bodies));save()
  results=c['concurrent_results']
  assert sum(r['ok'] for r in results)==1,results;d=next_execution(c);assert len(d['executions'])==2;assert sql("SELECT COUNT(*) FROM purchase_return WHERE company_code='QM' AND purchase_id="+str(c['purchase_id']),DB)=='1';held=check(c,8,5,3);out(c,'CONCURRENT_FIRST',[(0,2)]);assert doc(c)['posted_quantity']==2;out(c,'CONCURRENT_SECOND',[(0,3)]);d=posted(c,5);return dict(concurrent_results=results,after_continue=held,after_second=check(c,5,5),document=d)
 record('06','最终短出并发继续只追加一张执行，原CGT和库存占用唯一',c,'仓库最终确认仅出2件，两人使用相同页面版本同时申请继续3件。期望仅一份申请成功、仅一个-02执行、业务CGT仍只有一张，旧消息重放不扣第二次执行额度。',concurrent_continue)
 c=cases['restore']
 def restore_cycles():
  out(c,'RESTORE_FIRST',[(0,2)]);posted(c,2);post('RESTORE_STOP1',RET+'/'+str(c['return_id'])+'/cancel',command(c,'RESTORE_STOP1',reason='[TEST]停止未出3件'));wait(lambda:doc(c),lambda d:d['state']=='CANCELED','First stop');released1=check(c,8,8)
  continue_(c,'RESTORE_REQUEST1');d=doc(c);r=d['execution_requests'][0];assert r['state']=='REVIEW';waiting=check(c,8,8);post('RESTORE_APPROVE1',RET+'/'+str(c['return_id'])+'/restore/'+str(r['id'])+'/approve',dict(control_version=d['control_version']));next_execution(c);reheld1=check(c,8,5,3);out(c,'RESTORE_SECOND',[(0,1)],True);posted(c,3);post('RESTORE_STOP2',RET+'/'+str(c['return_id'])+'/cancel',command(c,'RESTORE_STOP2',reason='[TEST]停止仍未出2件'));wait(lambda:doc(c),lambda d:d['state']=='CANCELED','Second stop');released2=check(c,7,7);continue_(c,'RESTORE_REQUEST2');d=doc(c);r=d['execution_requests'][0];post('RESTORE_APPROVE2',RET+'/'+str(c['return_id'])+'/restore/'+str(r['id'])+'/approve',dict(control_version=d['control_version']));wait(lambda:doc(c),lambda d:len(d['executions'])==3 and d['executions'][0]['dispatch_state']=='ACCEPTED','Third execution');reheld2=check(c,7,5,2);out(c,'RESTORE_THIRD',[(0,2)]);d=posted(c,5);h=d['stock']['lines'][0];assert [h[k] for k in ['planned_quantity','quantity','consumed_quantity','released_quantity']]==[5,10,5,5];return dict(after_first_release=released1,pending_review=waiting,after_first_restore=reheld1,after_second_release=released2,after_second_restore=reheld2,final=check(c,5,5),document=d)
 record('07','同CGT连续停止释放与恢复两轮，原释放流水不被覆盖',c,'原计划5件先出2，释放3；恢复申请待审核时库存保持8/8，审核后重新占3；再出1释放2，再审核恢复2并出完。最终仍一张CGT、三次执行，原计划5、累计占用10、实际出库5、累计释放5。',restore_cycles)
 c=cases['fragment']
 def fragments():
  out(c,'FRAGMENT_PART1',[(0,2)],True,[('AMBIGUOUS',[(None,2)])],message='FRAGMENT_M1',total=2);d=doc(c);assert d['accepted_quantity']==0 and len(d['pending_messages'])==1;pending=check(c,10,6,4);out(c,'FRAGMENT_PART1',[(0,2)],True,[('AMBIGUOUS',[(None,2)])],message='FRAGMENT_M1',total=2);out(c,'FRAGMENT_PART2',[(1,2)],True,[('EXPLICIT',[(1,2)])],message='FRAGMENT_M1',total=2);d=posted(c,4);p=next(p for p in d['parcels'] if p['package_code']=='AMBIGUOUS');assert p['content_state']=='REVIEW';return dict(incomplete=pending,after_complete=check(c,6,6),document=d)
 record('08','同一消息分两片，齐全前不扣库存，跨批次同SKU不猜包裹归属',c,'采购同一SKU两个批次各5件，退仓各2件。第一片只含第一原行、声明本消息2行，重复发送仍待齐；第二片补齐另一行后一次扣4件。无批次提示的包裹保留待核对，另一明确原行包裹自动关联。',fragments)
 def allocate_fragment():
  d=doc(c);p=next(p for p in d['parcels'] if p['package_code']=='AMBIGUOUS');i=p['items'][0];e=d['executions'][0];body=command(c,'ALLOCATE',evidence='[TEST]核对仓库原始装箱单，明确属于第一个批次',lines=[dict(item_id=i['id'],execution_line_id=e['lines'][0]['id'],quantity=2)]);post('ALLOCATE',RET+'/'+str(c['return_id'])+'/parcels/allocate',body);post('ALLOCATE',RET+'/'+str(c['return_id'])+'/parcels/allocate',body);d=doc(c);assert all(p['content_state']=='MAPPED' for p in d['parcels']);return dict(document=d,stock=check(c,6,6))
 record('09','明确包裹原批次后补关联，不重复扣库存',c,'按原装箱单将无来源包裹的2件明确到第一批次，重放同一关联请求。期望两包裹归属完整、原出库仍4件、库存仍6件。',allocate_fragment)
 c=cases['cumulative']
 def cumulative():
  out(c,'CUMULATIVE_V1',[(0,2)],False,revision=1);posted(c,2);mid=check(c,8,5,3);out(c,'CUMULATIVE_V3',[(0,5)],True,revision=3);posted(c,5);out(c,'CUMULATIVE_LATE_V2',[(0,3)],False,revision=2);d=doc(c);assert [e['quantity'] for e in reversed(d['events'])]==[2,3];assert sql("SELECT COUNT(*) FROM purchase_return_message WHERE company_code='QM' AND return_id="+str(c['return_id'])+" AND state='IGNORED'",DB)=='1';return dict(after_v1=mid,after_v3_and_late_v2=check(c,5,5),document=d)
 record('10','累计快照2→5只扣增量2和3，迟到版本2不回退库存',c,'与TEST仓库约定累计口径和omsSnapshotVersion。先版本1累计2件，再版本3累计5件，最后迟到版本2累计3件；旧版本只留证，不产生负出库或重复扣减。',cumulative)
 c=cases['incomplete']
 def incomplete():
  out(c,'INCOMPLETE_PART',[(0,2)],True,message='INCOMPLETE_M1',total=2);post('INCOMPLETE_STOP',RET+'/'+str(c['return_id'])+'/cancel',command(c,'INCOMPLETE_STOP',reason='[TEST]尝试在回传明细未齐时停止'));d=wait(lambda:doc(c),lambda d:'完整' in d['last_error'],'Incomplete message must block release');assert d['state']=='CANCELING' and d['posted_quantity']==0;return dict(document=d,stock=check(c,10,6,4))
 record('11','回传分片未齐时停止流程保留占用，等待仓库补齐',c,'两个批次各退2件，只收到声明2行中的第一行。申请停止后仍处于停止待确认并明确提示明细未齐；不能把未知实出当作0，库存总量10、可用6、占用4。保留该待处理单供查看。',incomplete)
 c=cases['bad']
 def bad_out_recover():
  out(c,'BAD_OUT',[(0,3)],True,[('BAD_PACKAGE',[(0,3)])]);d=posted(c,3);before=check(c,7,7);a=d['parcel_allocations'][0];d=supplier(c,'BAD_REFUSE','REFUSE',[dict(allocation_id=a['id'],quantity=1)]);source=next(x for x in d['supplier_sources'] if x['decision']=='REFUSE');r=reclaim(c,'BAD_RECLAIM',[dict(supplier_source_id=source['id'],quantity=1)]);r=receive(c,'BAD_ACTUAL_GOOD',[(0,'ZP',1)]);return dict(after_bad_out=before,after_actual_good=check(c,8,8),reclaim=r,document=doc(c))
 record('12','次品退仓不减正品可用，收回按实际质检正品入账',c,'采购正品7、次品3，退仓次品3后总库存7、可用仍7、次品0。供应商拒收1件，仓库实收质检为正品；独立收回后总8、可用8，原次品出库事实保留。',bad_out_recover)
 c=cases['restore_conflict']
 def restore_conflict():
  out(c,'CONFLICT_FIRST',[(0,2)]);posted(c,2);post('CONFLICT_STOP',RET+'/'+str(c['return_id'])+'/cancel',command(c,'CONFLICT_STOP',reason='[TEST]先结束剩余3件'));wait(lambda:doc(c),lambda d:d['state']=='CANCELED','Stop before conflict');continue_(c,'CONFLICT_RESTORE');d=doc(c);r=d['execution_requests'][0]
  if not c.get('other_return_id'):c['other_return_id']=new_return(c,'OTHER_SOURCE_CLAIM',[7]);save()
  other=api(RET+'/'+str(c['other_return_id']))['data']
  if other['state']=='DRAFT':post('OTHER_SOURCE_APPROVE',RET+'/'+str(c['other_return_id'])+'/approve',dict(control_version=other['control_version']))
  other=wait(lambda:api(RET+'/'+str(c['other_return_id']))['data'],lambda d:d['dispatch_state']=='ACCEPTED','Other approved source claim');err=failure(RET+'/'+str(c['return_id'])+'/restore/'+str(r['id'])+'/approve',dict(control_version=doc(c)['control_version']),'原入库来源已被其他退仓占用');assert doc(c)['state']=='CANCELED';return dict(rejection=err,document=doc(c),other_document=other,stock=check(c,8,1,7))
 record('13','已释放余量被其他退仓占用，恢复审核拒绝且不超占',c,'原实收10件，原CGT已退2并释放3。另一独立退仓审核7件后只余1件来源额度；原CGT恢复3件应在审核阶段被阻止。保留原CGT恢复待审核及另一独立退仓的7件有效占用，不创建原CGT子业务单。',restore_conflict)
 c=cases['supplier']
 def unbound_then_bind():
  out(c,'UNBOUND_OUT',[(0,3)]);d=posted(c,3);execution=d['execution_lines'][0];supplier(c,'UNBOUND_ACCEPT','ACCEPT',[dict(execution_line_id=execution['id'],quantity=1)]);d=supplement(c,'LATE_PACKAGE','LATE_P1',3);source=next(x for x in d['supplier_sources'] if x['decision']=='ACCEPT');a=d['parcel_allocations'][0];body=command(c,'BIND_EXISTING',evidence='[TEST]已有供应商验收凭证补关联原包裹',lines=[dict(supplier_line_id=source['id'],allocation_id=a['id'],quantity=1)]);post('BIND_EXISTING',RET+'/'+str(c['return_id'])+'/supplier-bind',body);post('BIND_EXISTING',RET+'/'+str(c['return_id'])+'/supplier-bind',body);d=doc(c);assert len(d['supplier_sources'])==1 and d['supplier_sources'][0]['unbound_quantity']==0 and d['lines'][0]['supplier_received_quantity']==1;return dict(document=d,stock=check(c,7,7))
 record('14','先登记执行商品接受1件，补包裹和关联后不再增加验收数量',c,'退仓3件只回传头部运单，先把供应商接受1件登记到原执行商品行；后补原包裹3件，再把已有1件事实关联到包裹。重放补关联不产生新接受、不产生新拒收、不重复扣库。',unbound_then_bind)
 def supplier_caps():
  d=doc(c);a=d['parcel_allocations'][0];err=failure(RET+'/'+str(c['return_id'])+'/supplier-receipt',command(c,'UNBOUND_OVER',decision='REFUSE',evidence='[TEST]超过同执行尚未确认额度',lines=[dict(allocation_id=a['id'],quantity=3)]),'超过该执行已扣账');d=supplier(c,'UNBOUND_REFUSE_TWO','REFUSE',[dict(allocation_id=a['id'],quantity=2)]);assert len(d['supplier_sources'])==2 and d['lines'][0]['supplier_received_quantity']==1 and d['lines'][0]['supplier_refused_quantity']==2;return dict(rejection=err,document=d,stock=check(c,7,7))
 record('15','包裹登记不能绕过已登记的执行级收货数量',c,'原3件中已有执行级接受1件并补关联。再按包裹拒收3件应拒绝，正确登记拒收2件成功；总接受1加拒收2恰好等于实际出库3件，库存保持7。',supplier_caps)
 c=cases['cumulative']
 def metadata_after_final():
  d=doc(c);l=d['execution_lines'][0];supplier(c,'CUM_ACCEPT','ACCEPT',[dict(execution_line_id=l['id'],quantity=5)]);d=supplement(c,'CUM_LATE_PARCEL','CUM_LATE_P1',5);assert len(d['parcels'])==1 and d['posted_quantity']==5;return dict(document=d,stock=check(c,5,5))
 record('16','执行最终结束后可补录包裹信息，不伪造额外出库消息',c,'累计口径执行已结束并出库5件，登记供应商接受5件后补录原仓装箱单中的包裹5件；这是一条独立物流补充操作，原出库事件仍只有2与3两次增量。',metadata_after_final)
 c=cases['ready']
 def ready():
  out(c,'READY_FINAL_SHORT',[(0,2)],True,[('READY_P1',[(0,2)])]);d=posted(c,2);assert d['progress_code']=='REMAINING' and len(d['executions'])==1;return dict(document=d,stock=check(c,8,5,3))
 record('17','保留最终短出后待继续的原CGT，供界面验收',c,'采购10件、退仓计划5件、仓库最终实出2件；保留原计划中3件有效占用。页面应能明确选择同CGT继续，或停止余量并释放；不预先替用户操作。',ready)
 c=cases['bad']
 def partial_binding():
  d=doc(c);l=d['execution_lines'][0];d=supplier(c,'BAD_ACCEPT_REMAINING','ACCEPT',[dict(execution_line_id=l['id'],quantity=2)]);source=next(x for x in d['supplier_sources'] if x['decision']=='ACCEPT');allocation=d['parcel_allocations'][0]
  stages=[]
  for i in [1,2]:
   tag='PARTIAL_BIND_'+str(i);body=command(c,tag,evidence='[TEST]同一已有验收分次补关联同一包裹，不增加验收额度',lines=[dict(supplier_line_id=source['id'],allocation_id=allocation['id'],quantity=1)]);post(tag,RET+'/'+str(c['return_id'])+'/supplier-bind',body);post(tag,RET+'/'+str(c['return_id'])+'/supplier-bind',body);stages.append(doc(c))
  d=doc(c);source=next(x for x in d['supplier_sources'] if x['decision']=='ACCEPT');assert source['quantity']==2 and source['bound_quantity']==2 and source['unbound_quantity']==0 and len(source['parcel_bindings'])==1
  err=failure(RET+'/'+str(c['return_id'])+'/supplier-bind',command(c,'PARTIAL_BIND_OVER',evidence='[TEST]关联超过原验收数量',lines=[dict(supplier_line_id=source['id'],allocation_id=allocation['id'],quantity=1)]),'超过原收货事实或包裹未确认数量')
  return dict(stages=stages,over_rejection=err,document=d,stock=check(c,8,8))
 record('18','同一验收分两次关联同一包裹，每次重放都不重复累计',c,'已有接受2件先未绑定包裹。第一次关联1件并重放，第二次再关联1件并重放；期望原事实仍2件、同一绑定合计2、包裹累计接受2加拒收1为3。再多关联1件被拒绝；本仓库存保持8。',partial_binding)
 s['final_stock']={c['key']:api('/inventory/productInventory?'+urllib.parse.urlencode(dict(skuSn=c['sku'])))['rows'][0] for c in s['cases']};s['documents']={c['key']:doc(c) for c in s['cases']};s['protected_after']=baseline();assert s['protected_before']==s['protected_after'],'Unrelated baseline changed';s['protected_baseline_unchanged']=True;s['completed_at']=datetime.now().isoformat();save();print('PASS',len(s['results']),'retained fulfillment scenarios; unrelated baseline unchanged',flush=True)

if __name__=='__main__':main()
