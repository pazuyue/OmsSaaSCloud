"""Retained TEST acceptance via ordinary business APIs and signed Qimen warehouse callbacks.
SQL only reads OMS evidence; writes to simulator SQLite represent independent TEST WMS facts.
"""
import hashlib,json,sys,time,subprocess,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/warehouse-control';SC='/supplychain';PO=SC+'/purchaseWorkspace';API=SC+'/warehouseControl';INV='qm_oms_saas_inventory';DB='qm_oms_saas_commodity'
DEFS=[('full',10,0),('split',10,0),('short',10,0),('cancel',10,0),('cancel0',10,0),('over',10,0),('wrong',10,0),('bad',3,2),('mixed',3,2),('race',10,0),('reconcile',10,0),('frozen',10,0),('missing',10,0),('stale',10,0),('pending',10,0),('gain',10,0),('reject',10,0)]

def main():
 OUT.mkdir(exist_ok=True);path=OUT/'manifest.json';state=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),cases=[],results=[],commands={},bodies={});stamp=state['stamp'];token=login()
 def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
 def api(p,body=None):return request(p,payload=body,token=token)
 def protected_procurement():
  return dict(stock=sql("SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'PRQ_%' OR sku_sn LIKE 'SCIT%') ORDER BY sku_sn",INV),original_return=sql("SELECT state,receipt_final,quantity,accepted_quantity,posted_quantity,control_version FROM purchase_return WHERE return_sn='CGT5d411f13bbde41b8a53949a3053caaed'",DB),reports={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'outputs/purchase-return-iteration/scenarios.json',ROOT/'outputs/purchase-returns/manifest.json',ROOT/'outputs/supplychain-iteration/manifest.json']})
 if 'protected' not in state:state['protected']=protected();state['protected_procurement']=protected_procurement();save()
 def wait(fn,ok,label):
  last=None
  for _ in range(90):
   last=fn()
   if ok(last):return last
   time.sleep(1)
  raise AssertionError((label,last))
 def post(tag,p,body):
  if tag not in state['commands']:state['commands'][tag]=dict(path=p,body=body);save()
  cmd=state['commands'][tag];return api(cmd['path'],cmd['body'])
 def get(c):return api(API+'/transfers/'+str(c['transfer_id']))['data']
 def stock(c):
  sku=c['sku'];r=api('/inventory/productInventory?'+urllib.parse.urlencode(dict(skuSn=sku)))['rows'][0];assert r['checkStatus']=='CONSISTENT',r
  physical=sql("SELECT store_code,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,zp_lock_number,cp_lock_number FROM wms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku)+" ORDER BY store_code",INV)
  transit=int(sql("SELECT COALESCE(SUM(out_quantity-in_quantity-recovered_quantity),0) FROM oms_stock_transfer_line WHERE company_code='QM' AND sku_sn="+quote(sku),INV));return dict(sku=sku,total=r['totalStock'],available=r['availableStock'],allocated=r['allocatedStock'],reserved=r['reservedStock'],frozen=r['frozenStock'],transit=transit,owned=r['totalStock']+transit,warehouses=physical)
 def check(c,total,available,allocated=0,frozen=0,transit=0):
  r=stock(c);assert [r[k] for k in ['total','available','allocated','frozen','transit']]==[total,available,allocated,frozen,transit],r;return r
 def record(key,title,scenario,steps,evidence,before=None):
  if any(r['key']==key for r in state['results']):return
  after=stock(scenario) if scenario else None;state['results'].append(dict(key=key,title=title,scenario=steps,result='PASS',sku=scenario['sku'] if scenario else None,transfer_id=scenario.get('transfer_id') if scenario else None,before_stock=before,after_stock=after,evidence=evidence,verified_at=datetime.now().isoformat()));save();print('PASS',key,title,flush=True)
 def failure(p,body,contains):
  try:api(p,body)
  except AssertionError as e:assert contains in str(e),(contains,str(e));return str(e)
  raise AssertionError('Expected business rejection '+contains)
 credentials=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
 wait(lambda:api(API+'/options'),lambda r:isinstance(r.get('data'),list),'Warehouse-control deployment unavailable')
 if not state.get('owner'):state['owner']=api(SC+'/owner',dict(ownerCode='WCO_'+stamp,ownerName='[库存对账调拨TEST] '+stamp,isEnable=2))['data'];save()
 if not state.get('connection'):state['connection']=api(SC+'/wmsIntegration/connections',dict(name='[库存对账调拨TEST] '+stamp,provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=credentials['app_key'],customer_id=credentials['customer_id'],secret=credentials['secret'],enabled=1,partial_inbound_cancel=1))['data'];save()
 for side in ['source','target']:
  if not state.get('real_'+side):state['real_'+side]=api(SC+'/realStore',dict(realStoreCode='WCW_'+side+'_'+stamp,wmsName='[库存调拨TEST] '+side+' '+stamp,wmsType=1,status=2,director='[TEST]仓库收件人',mobilePhone='13000000000',province='浙江省',city='杭州市',district='西湖区',address='仅本地验收测试地址'))['data'];save()
  if not state.get('relation_'+side):state['relation_'+side]=api(SC+'/ownerWarehouse',dict(ownerId=state['owner'],realStoreId=state['real_'+side],status=2))['data'];save()
 for side in ['source','source2','target']:
  if not state.get('store_'+side):
   real='source' if side.startswith('source') else 'target';code='WCV_'+side+'_'+stamp;api(SC+'/simulationStore',dict(wmsSimulationCode=code,wmsSimulationName='[库存调拨TEST] '+side+' '+stamp,ownerWarehouseId=state['relation_'+real],inboundMode=1,outboundMode=1,status=2,connectionId=state['connection'],externalWarehouse='WC_EXT_'+real+'_'+stamp,externalOwner='WC_OWNER_'+stamp));state['store_'+side]=code;save()
 if not state.get('supplier'):state['supplier']=api(PO+'/supplier',dict(supplierName='[库存调拨TEST] '+stamp,companyName='仅本地验收供应商',status=2))['data'];state['supplier_sn']=api(PO+'/supplier/'+str(state['supplier']))['data']['supplierSn'];save()
 connection=next(c for c in api(SC+'/wmsIntegration/connections')['data'] if c['id']==state['connection']);assert connection['environment']=='TEST'
 def tags(parent,values):
  for k,v in values.items():ET.SubElement(parent,k).text=str(v)
 def wire(tag,method,root,expected='success'):
  if tag not in state['bodies']:state['bodies'][tag]=ET.tostring(root,encoding='unicode');save()
  body=state['bodies'][tag];params=dict(method='taobao.qimen.'+method,timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=credentials['app_key'],customerId=credentials['customer_id'],v='2.0',sign_method='md5');params['sign']=sign(params,body,credentials['secret']);url=BASE+'/prod-api'+SC+'/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params);r=ET.fromstring(urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40).read());assert r.findtext('flag')==expected,r.findtext('message');return r.findtext('message')
 catalog=api('/goods/info/options')['data']
 if not state['cases']:state['cases']=[dict(key=k,good=g,bad=b,sku='WC_'+stamp+'_'+str(i).zfill(2)) for i,(k,g,b) in enumerate(DEFS,1)];save()
 def purchase(c,side,good,bad=0):
  prefix='purchase_'+side
  if not c.get(prefix):c[prefix]=api(PO+'/purchase',dict(poName='[库存调拨TEST] '+c['key']+' '+side,supplierSn=state['supplier_sn'],wmsSimulationCode=state['store_'+side],lines=[dict(skuSn=c['sku'],quantity=good+bad,purchasePrice=2.5)]))['data'];save()
  p=api(PO+'/purchase/'+str(c[prefix]))['data']
  if p['poState']==1:api(PO+'/purchase/'+str(c[prefix])+'/approve',{})
  if not c.get('receipt_'+side):c['receipt_'+side]=api(PO+'/purchase/'+str(c[prefix])+'/receipt',dict(lines=[dict(lineId=p['lines'][0]['id'],quantity=good+bad)]))['data'];save()
  if not c.get('ticket_'+side):
   r=api(PO+'/receipt/'+str(c['receipt_'+side]))['data'];c['ticket_'+side]=r['tickets'][0]['id'] if r['tickets'] else api(PO+'/receipt/'+str(c['receipt_'+side])+'/approve',{})['data'];save()
  task=wait(lambda:api(SC+'/wmsIntegration/tickets/'+str(c['ticket_'+side]))['data'],lambda x:x['dispatch_state']=='ACCEPTED','Purchase WMS dispatch incomplete');root=ET.Element('request');h=ET.SubElement(root,'entryOrder');quantities=[(good,'ZP'),(bad,'CC')];quantities=[x for x in quantities if x[0]];tags(h,dict(entryOrderCode=task['ticket_sn'],entryOrderId=task['external_order'],warehouseCode=task['external_warehouse'],ownerCode=task['external_owner'],status='FULFILLED',confirmType=0,outBizCode='WC_IN_'+c['key']+'_'+side,totalOrderLines=len(quantities)));group=ET.SubElement(root,'orderLines')
  for q,grade in quantities:tags(ET.SubElement(group,'orderLine'),dict(itemCode=c['sku'],ownerCode=task['external_owner'],batchCode='WC_BATCH_'+c['key'],actualQty=q,inventoryType=grade))
  wire('purchase_'+c['key']+'_'+side,'entryorder.confirm',root);wait(lambda:api(PO+'/ticket/'+str(c['ticket_'+side]))['data'],lambda x:x['numberPosted']==good+bad,'Purchase actual inventory not posted')
 for c in state['cases']:
  if not c.get('product'):c['product']=api('/goods/info',dict(skuSn=c['sku'],goodsSn=c['sku'],goodsName='[库存对账调拨TEST] '+c['key'],marketPrice=10,validity=30,categoryCode=next(x['id'] for x in catalog['category'] if x['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))['data'];save()
  if not c.get('seeded'):
   purchase(c,'source',6 if c['key']=='reconcile' else c['good'],c['bad'])
   if c['key']=='reconcile':purchase(c,'source2',4)
   c['seeded']=True;c['initial_stock']=stock(c);save()
 cases={c['key']:c for c in state['cases']}
 def new_transfer(c,quantities,tag=None):
  tag=tag or c['key'];src=api(API+'/sources',dict(store_code=state['store_source'],skus=[c['sku']]))['data'];lines=[]
  for type,q in quantities:
   b=next(b for b in src['batches'] if b['store_code']==state['store_source']);lines.append(dict(source_batch_id=b['id'],sku_sn=c['sku'],inventory_type=type,quantity=q))
  return post('create_'+tag,API+'/transfers',dict(request_key='WC_'+stamp+'_'+tag,source_store=state['store_source'],target_store=state['store_target'],reason='[本地TEST] '+tag,lines=lines))['data']
 def approve(c):
  d=get(c)
  if d['state']=='DRAFT':post('approve_'+str(d['id']),API+'/transfers/'+str(d['id'])+'/approve',dict(control_version=d['control_version']))
  return wait(lambda:get(c),lambda d:d['stock_state']=='HELD' and any(e['side']=='OUT' and e['dispatch_state']=='ACCEPTED' for e in d['executions']),'Transfer automatic reserve/dispatch incomplete')
 def transfer_fact(c,side,message,quantities,complete=True,expected='success',wrong_batch=False):
  d=get(c);e=[e for e in d['executions'] if e['side']==side][-1];out=side=='OUT';assert e['external_order'];root=ET.Element('request');h=ET.SubElement(root,'deliveryOrder' if out else 'entryOrder');values=dict(warehouseCode=e['route']['external_warehouse'],ownerCode=e['route']['external_owner'],outBizCode=message,status=('DELIVERED' if complete else 'PARTDELIVERED') if out else ('FULFILLED' if complete else 'PARTFULFILLED'),confirmType=0 if complete else 1,totalOrderLines=len(quantities),logisticsCode='SF',expressCode='WC_REAL_WAYBILL_'+stamp+'_'+message);values.update({'deliveryOrderCode' if out else 'entryOrderCode':e['document_sn'],'deliveryOrderId' if out else 'entryOrderId':e['external_order'],'orderType' if out else 'entryOrderType':'DBCK' if out else 'DBRK'});tags(h,values);group=ET.SubElement(root,'orderLines')
  for l,q in zip(e['plan'],quantities):tags(ET.SubElement(group,'orderLine'),dict(orderLineNo=l['line_key'],itemCode=l['sku_sn'],ownerCode=e['route']['external_owner'],batchCode='WRONG_BATCH' if wrong_batch else l['batch_code'],inventoryType='CC' if l['inventory_type']=='CP' else 'ZP',actualQty=q))
  return wire(c['key']+'_'+message,'stockout.confirm' if out else 'entryorder.confirm',root,expected)
 def wait_qty(c,key,q):return wait(lambda:get(c),lambda d:sum(int(l[key]) for l in d['lines'])==q,'Transfer quantity not posted '+key)
 def ready_in(c):return wait(lambda:get(c),lambda d:any(e['side']=='IN' and e['dispatch_state']=='ACCEPTED' for e in d['executions']),'Destination automatic dispatch not accepted')
 def done(key):return any(r['key']==key for r in state['results'])
 def ensure(c,quantities):
  if not c.get('transfer_id'):c['transfer_id']=new_transfer(c,quantities);save()
  d=get(c)
  if d['state']=='DRAFT':approve(c)
  return get(c)
 c=cases['full']
 if not done('01'):
  before=stock(c);ensure(c,[('ZP',10)]);held=check(c,10,0,10);transfer_fact(c,'OUT','FULL_OUT',[10]);wait_qty(c,'out_quantity',10);in_transit=check(c,0,0,0,0,10);ready_in(c);transfer_fact(c,'IN','FULL_IN',[10]);wait_qty(c,'in_quantity',10);check(c,10,10);record('01','10件完整调拨，原批次原成本及公司拥有量守恒',c,'源仓10件→审核占用10件→自动下发DBCK→仓库实出10件→自动下发DBRK→目的仓实收10件。每步核对公司、仓库、批次及在途。',dict(held=held,in_transit=in_transit,document=get(c)),before)
 c=cases['split']
 if not done('02'):
  before=stock(c);ensure(c,[('ZP',10)]);transfer_fact(c,'OUT','SPLIT_O1',[4],False);wait_qty(c,'out_quantity',4);partial=check(c,6,0,6,0,4);assert next(e for e in get(c)['executions'] if e['side']=='IN')['dispatch_state']=='PENDING';transfer_fact(c,'OUT','SPLIT_O2',[6]);wait_qty(c,'out_quantity',10);ready_in(c);transfer_fact(c,'IN','SPLIT_I1',[4],False);wait_qty(c,'in_quantity',4);mid=check(c,4,4,0,0,6);transfer_fact(c,'IN','SPLIT_I2',[6]);wait_qty(c,'in_quantity',10);check(c,10,10);record('02','分两次出库和分两次入库，每次增量准确入账',c,'10件先出4件中间确认，保留6件源仓占用；再出6件最终确认后目的仓计划为10件，目的仓先收4件再收6件。',dict(partial_out=partial,partial_in=mid),before)
 c=cases['short']
 if not done('03'):
  before=stock(c);ensure(c,[('ZP',10)]);transfer_fact(c,'OUT','SHORT_O',[10]);wait_qty(c,'out_quantity',10);ready_in(c);transfer_fact(c,'IN','SHORT_I',[8]);wait_qty(c,'in_quantity',8);check(c,8,8,0,0,2);record('03','发10收8，最终短收保留2件在途并进入统一待办',c,'两侧仓库均最终确认，目的仓只收8件，差异2件不得自动补成目的仓库存，也不得加回原仓。',dict(document=get(c),operations=api(PO+'/operations?number='+get(c)['document_sn'])['data']),before)
 if not done('04'):
  before=stock(c);d=get(c);post('recover_short_1',API+'/transfers/'+str(c['transfer_id'])+'/recover',dict(control_version=d['control_version'],reason='[TEST]短收的2件实际安排退回原仓',lines=[dict(line_key=d['lines'][0]['id'],quantity=2)]));wait(lambda:get(c),lambda d:any(e['side']=='RECOVER' and e['dispatch_state']=='ACCEPTED' for e in d['executions']),'Recovery DBRK not dispatched');transfer_fact(c,'RECOVER','SHORT_R1',[1]);wait_qty(c,'recovered_quantity',1);check(c,9,9,0,0,1);record('04','独立收回计划2件只实收1件，只增加原仓1件',c,'先创建关联原调拨的独立DBH收回仓单；原仓实际只收到1件且最终确认，保留另1件在途，原出库流水仍保留10件。',dict(document=get(c)),before)
 if not done('05'):
  before=stock(c);d=get(c);post('recover_short_2',API+'/transfers/'+str(c['transfer_id'])+'/recover',dict(control_version=d['control_version'],reason='[TEST]剩余1件继续实际收回',lines=[dict(line_key=d['lines'][0]['id'],quantity=1)]));wait(lambda:get(c),lambda d:[e for e in d['executions'] if e['side']=='RECOVER'][-1]['dispatch_state']=='ACCEPTED','Second recovery not dispatched');transfer_fact(c,'RECOVER','SHORT_R2',[1]);wait_qty(c,'recovered_quantity',2);check(c,10,10);record('05','连续收回剩余在途，累计不超过原实出',c,'第二张独立收回单仅安排剩余1件并实际收货。公司实物恢复10件，原仓2件、目的仓8件，在途归零。',dict(document=get(c)),before)
 c=cases['cancel']
 if not done('06'):
  before=stock(c);ensure(c,[('ZP',10)]);transfer_fact(c,'OUT','CANCEL_O',[4],False);wait_qty(c,'out_quantity',4);d=get(c);post('stop_partial',API+'/transfers/'+str(c['transfer_id'])+'/stop',dict(control_version=d['control_version'],reason='[TEST]停止源仓未出6件'));wait(lambda:get(c),lambda d:d['stock_state']=='RELEASED','Unshipped hold not released');after_stop=check(c,6,6,0,0,4);ready_in(c);transfer_fact(c,'IN','CANCEL_I',[4]);wait_qty(c,'in_quantity',4);check(c,10,10);record('06','部分实出后停止，只释放6件，已出4件仍按实收处理',c,'计划10件源仓实际出4件后申请停止；仓库取消确认后释放未出6件，目的仓仅收到原实际发出的4件。',dict(after_stop=after_stop,document=get(c)),before)
 c=cases['cancel0']
 if not done('07'):
  before=stock(c);ensure(c,[('ZP',3)]);d=get(c);post('stop_zero',API+'/transfers/'+str(c['transfer_id'])+'/stop',dict(control_version=d['control_version'],reason='[TEST]仓库未出库，停止全部'));wait(lambda:get(c),lambda d:d['stock_state']=='RELEASED','Full stop not released');check(c,10,10);record('07','未出库全部停止，不产生在途，不创建目的仓实收',c,'3件审核占用且仓库已受理，但实际出库为0；停止后只释放3件占用，原10件实物不变。',dict(document=get(c)),before)
 for k,num,bad_batch in [('over','08',False),('wrong','09',True)]:
  c=cases[k]
  if done(num):continue
  before=stock(c);ensure(c,[('ZP',3)]);held=stock(c);reason=transfer_fact(c,'OUT',k.upper()+'_INVALID',[3 if bad_batch else 4],expected='failure',wrong_batch=bad_batch);assert stock(c)==held
  transfer_fact(c,'OUT',k.upper()+'_CORRECT',[3]);wait_qty(c,'out_quantity',3);ready_in(c);transfer_fact(c,'IN',k.upper()+'_IN',[3]);wait_qty(c,'in_quantity',3);check(c,10,10);record(num,'错误批次回传拒绝后仓库更正' if bad_batch else '超出原占用数量的出库拒绝后更正',c,'原计划3件；回传错误批次或4件超出数量时不扣库存，保留待核对。仓库按正确批次3件更正后自动扣账及入库。',dict(rejected=reason,document=get(c)),before)
 c=cases['bad']
 if not done('10'):
  before=stock(c);ensure(c,[('CP',2)]);transfer_fact(c,'OUT','BAD_O',[2]);wait_qty(c,'out_quantity',2);ready_in(c);transfer_fact(c,'IN','BAD_I',[1]);wait_qty(c,'in_quantity',1);check(c,4,3,0,1,1);d=get(c);post('recover_bad',API+'/transfers/'+str(c['transfer_id'])+'/recover',dict(control_version=d['control_version'],reason='[TEST]次品短收1件收回原仓',lines=[dict(line_key=d['lines'][0]['id'],quantity=1)]));wait(lambda:get(c),lambda d:any(e['side']=='RECOVER' and e['dispatch_state']=='ACCEPTED' for e in d['executions']),'Bad recovery not accepted');transfer_fact(c,'RECOVER','BAD_R',[1]);wait_qty(c,'recovered_quantity',1);check(c,5,3,0,2);record('10','次品调拨、短收与收回全程不转为可售正品',c,'原仓正品3件次品2件，调出2件次品，目的仓只收1件次品；剩余1件通过独立收回单回原仓。最终总5件、可售3件、不可售2件。',dict(document=get(c)),before)
 c=cases['mixed']
 if not done('11'):
  before=stock(c);ensure(c,[('ZP',2),('CP',1)]);transfer_fact(c,'OUT','MIX_O',[2,1]);wait_qty(c,'out_quantity',3);ready_in(c);transfer_fact(c,'IN','MIX_I',[2,1]);wait_qty(c,'in_quantity',3);check(c,5,3,0,2);ledger=sql("SELECT operation_type,inventory_type,request_id,change_quantity FROM wms_inventory_change_history WHERE company_code='QM' AND relation_sn="+quote(get(c)['document_sn'])+" ORDER BY log_id",INV);record('11','同SKU同批次正次品混合调拨，流水身份互不冲突',c,'原正品3次品2；同单调出正品2、次品1并完整入库。四条实物流水分别记录原质量，未将次品变为正品。',dict(ledger=ledger,document=get(c)),before)
 if not done('12'):
  c=cases['full'];before=stock(c);transfer_fact(c,'OUT','FULL_OUT',[10]);transfer_fact(c,'IN','FULL_IN',[10]);assert stock(c)==before;record('12','已完成两侧实物消息重放，不重复扣账或加账',c,'同一调拨完成后重复发送原始outBizCode及原始报文，验签通过并返回原结果，库存、在途及流水数不重复增加。',dict(document=get(c)),before)
 c=cases['race']
 if not done('13'):
  before=stock(c)
  if not c.get('transfer_id'):c['transfer_id']=new_transfer(c,[('ZP',7)],'race1');c['second_id']=new_transfer(c,[('ZP',7)],'race2');save()
  def approve_id(id):return api(API+'/transfers/'+str(id)+'/approve',dict(control_version=1))
  with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(approve_id,[c['transfer_id'],c['second_id']]))
  docs=wait(lambda:[api(API+'/transfers/'+str(id))['data'] for id in [c['transfer_id'],c['second_id']]],lambda ds:sum(d['stock_state']=='HELD' for d in ds)==1 and any(d['last_error'] for d in ds),'Concurrent stock reserve not resolved');check(c,10,3,7);record('13','两张各7件调拨并发审核，10件库存只允许一张占用',c,'两张待审核调拨同时提交。审核进入后台占用，只有一张锁定7件并下发；另一张明确显示可调库存不足且不下发。合计占用7，可用3，实物10。',dict(documents=docs),before)
 # Independent WMS-side stock facts are configured only inside the local TEST simulator.
 def wms_stock(c,physical,frozen=0,batch=None,grade='ZP',clear=False):
  payload=dict(customer=credentials['customer_id'],owner='WC_OWNER_'+stamp,warehouse='WC_EXT_source_'+stamp,sku=c['sku'],batch=batch or 'WC_BATCH_'+c['key'],quality=grade,unfrozen=physical-frozen,frozen=frozen,clear=clear)
  code="import json,sqlite3,sys\np=json.load(sys.stdin)\nassert p['sku'].startswith('WC_') and p['warehouse'].startswith('WC_EXT_')\nwith sqlite3.connect('/data/documents.sqlite') as d:\n d.execute('DELETE FROM inventory_stock WHERE customer=? AND owner=? AND warehouse=? AND sku=?',(p['customer'],p['owner'],p['warehouse'],p['sku']))\n if not p['clear']:d.execute('INSERT INTO inventory_stock VALUES (?,?,?,?,?,?,?,?)',tuple(p[k] for k in ['customer','owner','warehouse','sku','batch','quality','unfrozen','frozen']))\n"
  r=subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-wms-simulator','python','-c',code],input=json.dumps(payload),text=True,capture_output=True);assert r.returncode==0,r.stderr
 def reconcile(c,tag,method='inventory.query'):
  id=post('reconcile_'+tag,API+'/reconciliations',dict(request_key='WC_DZ_'+stamp+'_'+tag,store_code=state['store_source'],skus=[c['sku']],method=method,batch_code=''))['data'];c.setdefault('reconciliation_ids',{})[tag]=id;save();return wait(lambda:api(API+'/reconciliations/'+str(id))['data'],lambda d:d['state'] in ['COMPLETED','FAILED'],'Inventory check incomplete')
 def adjustment(c,d,tag,quantity,store=None):
  return post('adjust_'+tag,API+'/reconciliations/'+str(d['id'])+'/adjustment',dict(request_key='WC_TZ_'+stamp+'_'+tag,reason='[TEST]真实盘盈盘亏已核实',evidence='[本地TEST仓库盘点] 无待入账单据，按原批次2.50元成本',lines=[dict(sku_sn=c['sku'],store_code=store or state['store_source'],batch_code='WC_BATCH_'+c['key'],inventory_type='ZP',quantity=quantity,unit_cost=2.5)]))['data']
 def review(id,tag,decision='APPROVE'):return post('review_'+tag,API+'/adjustments/'+str(id)+'/review',dict(control_version=1,decision=decision))['data']
 c=cases['reconcile']
 if not done('14'):
  before=stock(c);wms_stock(c,10);d=reconcile(c,'aggregate');assert d['state']=='COMPLETED',d;rows=d['result']['rows'];assert len(rows)==1 and rows[0]['oms_physical']==10 and rows[0]['status']=='MATCHED',d;assert len(rows[0]['sources'])==2;assert stock(c)==before;record('14','同货主同实仓两个虚仓6+4，只与WMS10比较一次',c,'源实仓下虚仓一实物6件，虚仓二实物4件；WMS按实仓返回10件。OMS聚合为10，差额0，保存两边快照时间与虚仓来源，查询不改库存。',dict(reconciliation=d),before)
 if not done('15'):
  before=stock(c);wms_stock(c,8);d=reconcile(c,'loss');assert d['result']['difference_count']==1 and d['result']['rows'][0]['difference']==-2 and d['result']['can_adjust'];assert stock(c)==before;aid=adjustment(c,d,'loss',-2);after_apply=stock(c);assert after_apply==before;review(aid,'loss');d=wait(lambda:api(API+'/reconciliations/'+str(d['id']))['data'],lambda d:d['adjustments'][0]['state']=='POSTED','Reviewed loss not posted');check(c,8,8);record('15','明确缺2件，独立盘亏申请不改账，审核后仅扣2件',c,'WMS实物8、OMS两个虚仓合计10，差额-2。申请指定原虚仓和批次并填写盘点凭证，申请时库存不变；独立审核后公司/该虚仓/该批次减少2件。',dict(before_review=after_apply,reconciliation=d),before)
 c=cases['frozen']
 if not done('16'):
  before=stock(c);wms_stock(c,10,3);d=reconcile(c,'frozen');assert d['result']['rows'][0]['status']=='FROZEN_CHECK' and d['difference_count']==0 and not d['result']['can_adjust'];assert stock(c)==before;record('16','WMS未冻结7+冻结3等于实物10，冻结差异不误报缺货',c,'OMS实物10、业务占用0；WMS未冻结7、冻结3。实物一致，只提示冻结来源待核查，不把可用7误认为库存少3，也不自动调账。',dict(reconciliation=d),before)
 c=cases['missing']
 if not done('17'):
  before=stock(c);wms_stock(c,0,clear=True);d=reconcile(c,'missing');assert d['result']['rows'][0]['status']=='MISSING_WMS' and not d['result']['can_adjust'];assert stock(c)==before;record('17','仓库成功响应但缺失SKU，显示未返回而非缺10件',c,'仓库接口成功但items为空。OMS保留实物10件，结论是仓库未返回该库存，既不推断为0，也不开放实物差异调整。',dict(reconciliation=d),before)
 c=cases['stale']
 if not done('18'):
  before=stock(c);wms_stock(c,9);d=reconcile(c,'stale');aid=adjustment(c,d,'stale',-1);ensure(c,[('ZP',1)]);error=failure(API+'/adjustments/'+str(aid)+'/review',dict(control_version=1,decision='APPROVE'),'待入账');check(c,10,9,1);record('18','对账后发生调拨占用，旧盘亏审核被阻止',c,'先得到OMS10/WMS9的差额并申请盘亏1件；审核前另有调拨1件已占用且仓库未出库。旧快照存在待确认单据，审核不得扣账。库存版本保护另由真实事务并发回归覆盖。',dict(rejected=error,reconciliation=api(API+'/reconciliations/'+str(d['id']))['data']),before)
 c=cases['pending']
 if not done('19'):
  before=stock(c);ensure(c,[('ZP',2)]);wms_stock(c,8,2);d=reconcile(c,'pending');assert d['result']['pending_documents'] and not d['result']['can_adjust'];assert d['result']['rows'][0]['difference']==-2 and d['result']['rows'][0]['status']=='WAITING_POST';check(c,10,8,2);record('19','差额与未完成调拨同时存在，展示待确认单号并等待复查',c,'OMS10件中调拨锁定2件、尚未回传出库；WMS实物8件且冻结2。保留-2差额和关联DB单号，先核对出库/入账，不允许盘亏代替业务出库。',dict(reconciliation=d),before)
 c=cases['gain']
 if not done('20'):
  before=stock(c);wms_stock(c,12);d=reconcile(c,'gain','stock.query');assert d['state']=='COMPLETED' and d['result']['rows'][0]['difference']==2,d;aid=adjustment(c,d,'gain',2);review(aid,'gain');wait(lambda:api(API+'/reconciliations/'+str(d['id']))['data'],lambda d:d['adjustments'][0]['state']=='POSTED','Gain not posted');check(c,12,12);record('20','批次分页查询盘盈2件，原成本凭证审核后加2件',c,'使用stock.query批次分页查询，校验totalCount完整性。OMS10/WMS12，申请原虚仓原批次盘盈2件并核对2.50元成本，审核后总量和可用增加2件。',dict(reconciliation=api(API+'/reconciliations/'+str(d['id']))['data']),before)
 c=cases['reject']
 if not done('21'):
  before=stock(c);wms_stock(c,9);d=reconcile(c,'reject');aid=adjustment(c,d,'reject',-1);review(aid,'reject','REJECT');assert stock(c)==before;record('21','差异调整被驳回，盘点证据保留而库存不变',c,'OMS10/WMS9形成差异申请，审核人驳回。不执行库存变更，不删除原申请及盘点凭证。重新核对须建立新的快照。',dict(reconciliation=api(API+'/reconciliations/'+str(d['id']))['data']),before)
 if not done('22'):
  c=cases['reconcile'];before=stock(c);root=ET.Element('request');tags(root,dict(totalPage=1,currentPage=1,pageSize=1,warehouseCode='WC_EXT_source_'+stamp,ownerCode='WC_OWNER_'+stamp,checkOrderCode='WC_CHECK_'+stamp,outBizCode='WC_REPORT_'+stamp,checkTime=datetime.now().strftime('%Y-%m-%d %H:%M:%S'),adjustType='CHECK'));group=ET.SubElement(root,'items');tags(ET.SubElement(group,'item'),dict(itemCode=c['sku'],batchCode='WC_BATCH_reconcile',inventoryType='ZP',quantity=-2,totalQty=8));wire('inventory_report','inventory.report',root);wire('inventory_report','inventory.report',root);assert stock(c)==before;rs=api(API+'/inventory-reports')['data']['rows'];matches=[r for r in rs if r['check_order']=='WC_CHECK_'+stamp];assert len(matches)==1 and matches[0]['report']['lines'][0]['delta']==-2;record('22','仓库盘点通知-2为变化量，重复通知仅留一份证据',c,'inventory.report回传盘亏变化-2、盘点总量8，重复发送相同outBizCode。本地只保留一份验签盘点证据，不把-2当成绝对库存，也不在已审核盘亏后再次扣2件。',dict(report=matches[0]),before)
 if not done('23'):
  c=cases['full'];d=get(c);execution=d['executions'][0];logs=api(SC+'/wmsIntegration/log-center?'+urllib.parse.urlencode(dict(number=execution['document_sn'],numberType='DOCUMENT',allTime='true',businessType='TRANSFER')))['data']['rows'];assert logs and all(l['business_type']=='TRANSFER' for l in logs);record('23','仓库日志中心能按调拨执行单查询原单交互',c,'按DBO原仓执行单定位，日志独立识别跨实仓调拨，展示下发、回传及原仓库单号，不归到销售发货或采购入库。',dict(logs=logs))
 if not done('24'):
  c=cases['missing'];before=stock(c)
  if not state.get('draft_transfer_id'):state['draft_transfer_id']=new_transfer(c,[('ZP',2)],'ui_draft');save()
  d=api(API+'/transfers/'+str(state['draft_transfer_id']))['data'];assert d['state']=='DRAFT' and not d['executions'];assert stock(c)==before
  record('24','调拨草稿仅保存计划，未审核不占库存不下发',c,'新建2件调拨并停留待审核，未触发库存占用或任何仓库执行单；该草稿保留用于界面验收。',dict(draft=d),before)
 if not done('25'):
  c=cases['missing'];before=stock(c);src=api(API+'/sources',dict(store_code=state['store_source'],skus=[c['sku']]))['data'];b=src['batches'][0]
  base=dict(request_key='WC_INVALID_'+stamp,source_store=state['store_source'],reason='[TEST]仓库归属约束',lines=[dict(source_batch_id=b['id'],sku_sn=c['sku'],inventory_type='ZP',quantity=1)])
  same=failure(API+'/transfers',dict(base,target_store=state['store_source2']),'同一货主不同实仓')
  other=next(w for w in api(API+'/options')['data'] if w['owner_code']!='WCO_'+stamp)
  cross=failure(API+'/transfers',dict(base,target_store=other['store_code']),'同一货主不同实仓');assert stock(c)==before
  record('25','同实仓移动和跨货主调拨均被当前范围约束拒绝',c,'来源虚仓和另一虚仓属于同一实仓时不得当作跨实仓调拨；目标属于其他货主时也拒绝。两项失败均不建仓库执行、不变库存。',dict(same_real=same,cross_owner=cross),before)
 if not done('26'):
  c=cases['frozen'];before=stock(c);wms_stock(c,10,0);d=reconcile(c,'latest_clear');assert d['result']['rows'][0]['status']=='MATCHED' and d['result']['rows'][0]['stock_unit']=='件'
  previous=api(API+'/reconciliations/'+str(c['reconciliation_ids']['frozen']))['data'];todo=api(PO+'/operations?number='+previous['document_sn'])['data'];assert todo['total']==0,todo;assert stock(c)==before
  record('26','重新检查已一致，旧冻结差异保留快照但退出当前待办',c,'仓库解除原3件冻结后重新检查，实物10且冻结0与OMS一致。新旧快照都保留；同范围最新成功快照通过索引定位，旧差异不再重复出现在当前待办。',dict(latest=d,old_snapshot=previous,operations=todo),before)
 if not done('27'):
  c=dict(cases['missing']);before=stock(c)
  if not state.get('audit_transfer_id'):state['audit_transfer_id']=new_transfer(c,[('ZP',2)],'audit_round');save()
  c['transfer_id']=state['audit_transfer_id'];approve(c);d=get(c);post('stop_audit',API+'/transfers/'+str(c['transfer_id'])+'/stop',dict(control_version=d['control_version'],reason='[TEST]核对人工决策审计后停止未出2件'));wait(lambda:get(c),lambda d:d['stock_state']=='RELEASED','Audited stop not settled');d=get(c);assert {a['action'] for a in d['audit']} >= {'CREATE','APPROVE','STOP'};assert stock(c)==before
  record('27','创建、审核、停止均保留实际操作人与原因',c,'新建2件调拨，经明确审核后由后台锁定并下发。仓库尚未出库时停止并释放；创建人、审核人、停止原因与时间均可追溯，仓库交互和库存事实另列。',dict(document=d),before)
 if not done('28'):
  c=dict(cases['missing']);before=stock(c)
  if not state.get('transit_transfer_id'):state['transit_transfer_id']=new_transfer(c,[('ZP',2)],'transit_keep');save()
  c['transfer_id']=state['transit_transfer_id'];approve(c);transfer_fact(c,'OUT','TRANSIT_KEEP_OUT',[2]);wait_qty(c,'out_quantity',2);ready_in(c);check(c,8,8,0,0,2)
  r=api('/inventory/productInventory?skuSn='+c['sku'])['rows'][0];assert r['totalStock']==8 and r['transitStock']==2 and r['ownedStock']==10
  record('28','保留真实在途2件供用户验收，公司拥有10件可直接查询',c,'独立测试调拨实际出库2件，目的仓已接单但尚未回传实收。保留该等待收货状态：仓库实物8件、调拨在途2件、公司拥有10件，可用仅8件。商品库存列表和详情直接展示三项数量。',dict(document=get(c),inventory=r),before)
 assert protected()==state['protected'],'Previous order/after-sale/global configuration changed'
 assert protected_procurement()==state['protected_procurement'],'Previous procurement data changed'
 state['completed_at']=datetime.now().isoformat();state['protected_baseline_unchanged']=True;state['final_stock']={c['key']:stock(c) for c in state['cases']};save();print('PASS retained warehouse-control scenarios',len(state['results']),flush=True)

if __name__=='__main__':main()
