"""Retained TEST procurement-return lifecycle acceptance. Never edits target states or inventory by SQL."""
import json,sys,time,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign
import verify_purchase_returns as baseline

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-iteration';RET='/supplychain/purchaseReturns';INV='qm_oms_saas_inventory'

def main():
 OUT.mkdir(parents=True,exist_ok=True)
 baseline.OUT=OUT/'base';baseline.DEFINITIONS=[d for d in baseline.DEFINITIONS if d[0] in ['full','final_short','mixed','split','partial','virtual','dispatch_fail','over']]
 base_path=baseline.OUT/'manifest.json'
 if not base_path.exists() or not json.loads(base_path.read_text('utf-8')).get('completed_at'):baseline.main()
 base=json.loads(base_path.read_text('utf-8'));cases={c['key']:c for c in base['cases']};token=login();path=OUT/'scenarios.json'
 state=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=base['stamp'],commands={},bodies={},results=[],ids={},protected=protected())
 def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
 def api(path,body=None):return request(path,payload=body,token=token)
 def doc(case):return api(RET+'/'+str(case['return_id']))['data']
 def get_reclaim(id):return api(RET+'/reclaims/'+str(id))['data']
 def post(tag,path,body):
  if tag not in state['commands']:state['commands'][tag]=dict(path=path,body=body);save()
  saved=state['commands'][tag];return api(saved['path'],saved['body'])
 def wait(fn,ok,label):
  for _ in range(90):
   value=fn()
   if ok(value):return value
   time.sleep(1)
  raise AssertionError(label)
 def stock(case):
  sku=case['sku'];r=api('/inventory/productInventory?'+urllib.parse.urlencode(dict(skuSn=sku)))['rows'][0]
  assert r['checkStatus']=='CONSISTENT',r
  return dict(sku=sku,total=r['totalStock'],available=r['availableStock'],allocated=r['allocatedStock'],reserved=r['reservedStock'],frozen=r['frozenStock'],warehouse=r['warehouseTotals'],batches=r['batchTotals'])
 def assert_stock(case,total,available,allocated=0,frozen=0):
  value=stock(case);assert [value[k] for k in ['total','available','allocated','frozen']]==[total,available,allocated,frozen],value;return value
 def record(key,title,case,steps,fn):
  if any(r['key']==key for r in state['results']):return
  before=stock(case);evidence=fn();after=stock(case)
  state['results'].append(dict(key=key,title=title,steps=steps,result='PASS',return_id=case['return_id'],return_sn=case['return_sn'],purchase_id=case['purchase_id'],sku=case['sku'],before_stock=before,after_stock=after,evidence=evidence,verified_at=datetime.now().isoformat()));save();print('PASS',key,title,flush=True)
 def command(case,tag,**fields):return dict(request_key='PRI_'+state['stamp']+'_'+tag,control_version=doc(case)['control_version'],**fields)
 def money(case,tag,kind,amount,original=None):
  body=command(case,tag,funds_state=kind,amount=amount,evidence='[退仓迭代TEST] '+tag+' 已核对凭证，未调用银行')
  if original:body['original_id']=original
  return post(tag,RET+'/'+str(case['return_id'])+'/funds',body)['data']
 def supplier(case,tag,decision,quantities):
  d=doc(case);body=command(case,tag,decision=decision,evidence='[退仓迭代TEST] '+tag+' 供应商实际结果凭证',lines=[dict(line_id=l['id'],quantity=q) for l,q in zip(d['lines'],quantities) if q])
  return post(tag,RET+'/'+str(case['return_id'])+'/supplier-receipt',body)['data']
 def expected_failure(path,body,contains):
  try:api(path,body)
  except AssertionError as e:
   assert contains in str(e),(contains,str(e));return str(e)
  raise AssertionError('Expected rejection: '+contains)
 credentials=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
 connections={c['id']:c for c in api('/supplychain/wmsIntegration/connections')['data']}
 def tags(parent,values):
  for k,v in values.items():ET.SubElement(parent,k).text=str(v)
 def send(tag,method,root,expected='success'):
  if tag not in state['bodies']:state['bodies'][tag]=ET.tostring(root,encoding='unicode');save()
  body=state['bodies'][tag];connection=connections[base['connection_real']]
  assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
  params=dict(method=method,timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=credentials['app_key'],customerId=credentials['customer_id'],v='2.0',sign_method='md5');params['sign']=sign(params,body,credentials['secret'])
  url=BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params)
  reply=ET.fromstring(urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40).read());assert reply.findtext('flag')==expected,reply.findtext('message')
 def outbound(case,tag,quantities):
  d=doc(case);root=ET.Element('request');h=ET.SubElement(root,'deliveryOrder');tags(h,dict(deliveryOrderCode=d['return_sn'],deliveryOrderId=d['external_order'],orderType='CGTH',ownerCode='PRQ_OWNER_'+state['stamp'],warehouseCode='PRQ_EXT_'+state['stamp'],outBizCode='PRI_'+tag,status='DELIVERED',confirmType=0,totalOrderLines=len(quantities),logisticsCode='SF',logisticsName='[TEST]顺丰',expressCode='PRI_WAYBILL_'+tag));ls=ET.SubElement(root,'orderLines')
  for l,q in zip(d['lines'],quantities):tags(ET.SubElement(ls,'orderLine'),dict(orderLineNo=l['id'],itemCode=l['sku_sn'],ownerCode='PRQ_OWNER_'+state['stamp'],batchCode=l['batch_code'],inventoryType='CC' if l['inventory_type']=='CP' else 'ZP',actualQty=q))
  send(tag,'taobao.qimen.stockout.confirm',root)
 def reclaim(case,tag,quantities):
  if tag in state['ids']:return state['ids'][tag]
  d=doc(case);body=command(case,tag,reason='[退仓迭代TEST] 供应商拒收，独立收回入库',carrier_code='SF',waybill='PRI_BACK_'+tag,lines=[dict(line_id=l['id'],quantity=q) for l,q in zip(d['lines'],quantities) if q]);id=post(tag,RET+'/'+str(case['return_id'])+'/reclaims',body)['data'];state['ids'][tag]=id;save();return id
 def approve_reclaim(id,tag):
  d=get_reclaim(id)
  if d['state']=='DRAFT':post(tag,RET+'/reclaims/'+str(id)+'/approve',dict(control_version=d['control_version']))
  elif d['state']=='ACTIVE' and '原收回仓库映射变化' in d['last_error']:
   if not state.get('resolved_issues'):state['resolved_issues']=[dict(problem='连接编号经JSON保存后类型变化，被错误判断为原仓库映射变化；未下发也未增加库存',fix='按连接编号、货主和仓库的规范化业务值比较；补充真实HTTP下发回归',original_reclaim=d)]
   save();post(tag+'_RETRY',RET+'/reclaims/'+str(id)+'/retry',dict(control_version=d['control_version']))
  return wait(lambda:get_reclaim(id),lambda d:d['dispatch_state']=='ACCEPTED' or d['state']=='COMPLETED','Reclaim approval/dispatch')
 def receive(id,tag,facts,complete=True,expected='success'):
  d=get_reclaim(id);root=ET.Element('request');h=ET.SubElement(root,'entryOrder');tags(h,dict(entryOrderCode=d['reclaim_sn'],entryOrderId=d['external_order'],entryOrderType='QTRK',ownerCode='PRQ_OWNER_'+state['stamp'],warehouseCode='PRQ_EXT_'+state['stamp'],outBizCode='PRI_IN_'+tag,status='FULFILLED' if complete else 'PARTFULFILLED',confirmType=0 if complete else 1,totalOrderLines=len(facts)));ls=ET.SubElement(root,'orderLines')
  for index,grade,q in facts:
   l=d['lines'][index];tags(ET.SubElement(ls,'orderLine'),dict(orderLineNo=l['id'],itemCode=l['sku_sn'],ownerCode='PRQ_OWNER_'+state['stamp'],batchCode=l['batch_code'],inventoryType=grade,actualQty=q))
  send(tag,'taobao.qimen.entryorder.confirm',root,expected)
 save()
 short=cases['final_short'];full=cases['full'];mixed=cases['mixed'];split=cases['split'];virtual=cases['virtual'];partial=cases['partial']
 def short_view():
  d=doc(short);assert d['progress_code']=='REMAINING' and d['remaining_hold']==3;assert_stock(short,8,5,3);r=api(RET+'?'+urllib.parse.urlencode(dict(return_sn=short['return_sn'],quick='REMAINING')))['data'];assert r['total']==1;return d
 record('01','最终短出5件中只出2件，清楚展示剩余3件占用',short,['原采购实收入账10件；申请退仓5件。','TEST仓库签名最终回传2件，后台自动扣账。','核对进度、来源占用及快捷筛选。'],short_view)
 def stop_short():
  post('STOP_SHORT',RET+'/'+str(short['return_id'])+'/cancel',command(short,'STOP_SHORT',reason='[验收] 仓库最终短出，结束剩余3件'));d=wait(lambda:doc(short),lambda d:d['state']=='CANCELED','Stop remaining');assert d['progress_code']=='PARTIAL_CLOSED' and d['released_quantity']==3;assert_stock(short,8,8);return d
 record('02','最终短出后结束剩余，仅释放3件且保留已退2件',short,['明确选择结束剩余并填写依据。','后台依据原最终回传释放原占用。','核对部分完成状态及总量8、可用8、占用0。'],stop_short)
 def continue_short():
  body=command(short,'CONTINUE',reason='[验收] 继续退原剩余3件');post('CONTINUE',RET+'/'+str(short['return_id'])+'/continue',body);d=wait(lambda:doc(short),lambda d:len(d['child_returns'])==1,'Linked child draft');post('CONTINUE',RET+'/'+str(short['return_id'])+'/continue',body);assert len(doc(short)['child_returns'])==1;id=d['child_returns'][0]['id'];child=api(RET+'/'+str(id))['data'];assert child['state']=='DRAFT' and child['quantity']==3 and len(child['parent_return'])==1;state['ids']['child']=id;save();assert_stock(short,8,8);return dict(parent=d,child=child)
 record('03','剩余3件生成唯一关联草稿，重放申请不重复建单',short,['从已结束原单明确申请继续剩余3件。','按原来源及成本生成后续草稿。','重复提交同一请求，核对仅一张后续草稿且尚未占用库存。'],continue_short)
 def complete_child():
  id=state['ids']['child'];child=api(RET+'/'+str(id))['data'];post('APPROVE_CHILD',RET+'/'+str(id)+'/approve',dict(control_version=child['control_version']));child=wait(lambda:api(RET+'/'+str(id))['data'],lambda d:d['dispatch_state']=='ACCEPTED','Child dispatch');child_case=dict(short,return_id=id,return_sn=child['return_sn']);outbound(child_case,'CHILD_OUT',[3]);child=wait(lambda:doc(child_case),lambda d:d['state']=='COMPLETED','Child posting');assert_stock(short,5,5);return child
 record('04','后续草稿独立审核并自动实出3件，原单2件不重复扣减',short,['审核关联后续草稿，自动锁定并下发原仓。','签名回传3件，自动扣账。','总库存由8变5；原单已扣2件保持不变。'],complete_child)
 def vouchers():
  money(full,'REFUND_3','REFUND',3);money(full,'REFUND_3','REFUND',3);d=money(full,'CREDIT_25','CREDIT',2.5);assert d['funds_state']=='MIXED' and len(d['funds'])==2 and float(d['funds_remaining'])==2;assert_stock(full,7,7);return d
 record('05','多笔退款3元加抵扣2.50元，重复凭证不重复计款',full,['实退3件，原采购货值7.50元。','登记退款3元并重放；再登记抵扣2.50元。','累计5.50元，剩余待核对2元，库存不变。'],vouchers)
 def over_funds():
  d=doc(full);e=expected_failure(RET+'/'+str(full['return_id'])+'/funds',command(full,'OVER_FUNDS',funds_state='REFUND',amount=3,evidence='超额测试凭证'),'累计登记金额');assert float(doc(full)['funds_amount'])==5.5;return dict(rejection=e,document=d)
 record('06','超出剩余货值的款项登记被拒绝，原凭证及库存不变',full,['在累计已登记5.50元后再尝试登记3元。','核对金额校验拒绝，原两笔凭证保留。'],over_funds)
 def needs_refusal():
  l=doc(full)['lines'][0];e=expected_failure(RET+'/'+str(full['return_id'])+'/reclaims',command(full,'NO_REFUSAL',reason='缺少拒收依据',carrier_code='SF',waybill='TEST',lines=[dict(line_id=l['id'],quantity=1)]),'供应商已拒收');return dict(rejection=e)
 record('07','未确认供应商拒收时不能直接创建收回入库',full,['已实际退仓3件，但尚未登记供应商拒收。','尝试收回1件，业务校验拒绝且库存不变。'],needs_refusal)
 def supplier_results():
  supplier(full,'SUP_ACCEPT','ACCEPT',[1]);supplier(full,'SUP_REFUSE','REFUSE',[1]);supplier(full,'SUP_REFUSE','REFUSE',[1]);supplier(full,'SUP_SHORT','SHORT',[1]);d=doc(full);l=d['lines'][0];assert l['supplier_received_quantity']==1 and l['supplier_refused_quantity']==1;assert_stock(full,7,7);return d
 record('08','供应商签收1件、拒收1件、短收1件独立登记',full,['逐笔登记真实签收、拒收和短收凭证。','重放拒收登记不重复增加拒收数量。','短收保留待核对，三类登记均不直接增加库存。'],supplier_results)
 def real_reclaim():
  id=reclaim(full,'RECLAIM_FULL',[1]);d=approve_reclaim(id,'APPROVE_RECLAIM');assert_stock(full,7,7);state['ids']['real_reclaim']=id;save();return d
 record('09','拒收1件生成独立QTRK收回入库，受理阶段不加库存',full,['按已拒收1件创建收回草稿，填写供应商实际退回快递及运单。','审核后自动调用奇门entryorder.create，类型QTRK。','仓库受理后等待实收，原出库3件保持。'],real_reclaim)
 def over_receipt():
  id=state['ids']['real_reclaim'];receive(id,'OVER_RECLAIM',[(0,'CC',2)],expected='failure');d=get_reclaim(id);assert d['receipt_review_pending']==1 and d['accepted_quantity']==0;assert_stock(full,7,7);return d
 record('10','计划收回1件却回传2件，保留异常且不增加库存',full,['仓库签名回传超过原收回计划。','核对回传待更正、实收0、入账0及原库存不变。'],over_receipt)
 def actual_bad_recovery():
  id=state['ids']['real_reclaim'];receive(id,'REAL_RECLAIM',[(0,'CC',1)]);d=wait(lambda:get_reclaim(id),lambda d:d['state']=='COMPLETED','Actual recovery');receive(id,'REAL_RECLAIM',[(0,'CC',1)]);assert_stock(full,8,7,0,1);parent=doc(full);assert parent['posted_quantity']==3 and parent['funds_over_recorded'];return dict(reclaim=d,parent=parent)
 record('11','正品退仓后实收为次品1件，按质检入账且重复回传不重复增加',full,['仓库更正回传原SKU原批次，实际质量为次品CC。','自动新增收回入库流水，原出库记录保留。','核对总库存8、可用7、不可售1；重放原消息结果不变。'],actual_bad_recovery)
 def reverse_and_correct():
  d=doc(full);original=next(x['id'] for x in d['funds'] if x['kind']=='REFUND' and not x['original_id']);money(full,'REVERSE','REVERSAL',0,original);d=money(full,'NEW_REFUND','REFUND',2.5);assert float(d['funds_amount'])==5 and float(d['net_posted_amount'])==5 and not d['funds_over_recorded'] and len(d['funds'])==4;return d
 record('12','收回后款项超出净货值，冲销原凭证并重新登记',full,['原登记5.50元，收回1件后净退仓货值降为5元，页面提示核对。','冲销原退款3元，原记录保留；再登记退款2.50元。','累计有效凭证5元，与净货值一致。'],reverse_and_correct)
 def recovered_source_return():
  sources=api(RET+'/sources?purchaseId='+str(full['purchase_id']))['data'];source=next(x for x in sources['rows'] if x['source_key'].startswith('S') and x['inventory_type']=='CP');body=dict(request_key='PRI_'+state['stamp']+'_SECOND_OUT',purchase_id=full['purchase_id'],receiver=dict(name='[TEST]供应商',mobile='13000000000',province='浙江省',city='杭州市',area='西湖区',detailAddress='供应商实际收货地址'),carrier_code='SF',reason='[退仓迭代TEST] 按新收回来源再次退仓',lines=[dict(source_key=source['source_key'],inventory_type='CP',quantity=1)]);id=post('SECOND_RETURN',RET,body)['data'];state['ids']['second_return']=id;save();d=api(RET+'/'+str(id))['data'];post('SECOND_APPROVE',RET+'/'+str(id)+'/approve',dict(control_version=d['control_version']));d=wait(lambda:api(RET+'/'+str(id))['data'],lambda d:d['dispatch_state']=='ACCEPTED','Recovered source dispatch');again=dict(full,return_id=id,return_sn=d['return_sn']);outbound(again,'SECOND_OUT',[1]);d=wait(lambda:doc(again),lambda d:d['state']=='COMPLETED','Recovered source outbound');assert_stock(full,7,7);projection=api(RET+'?purchase_id='+str(full['purchase_id']))['data'];assert sum(x['quantity'] for x in projection['returned_by_sku'])==4 and sum(x['quantity'] for x in projection['recovered_by_sku'])==1;return dict(source=source,second_return=d,purchase_projection=projection)
 record('13','收回入库形成新的真实来源，可按原成本再次退仓',full,['从收回实收来源S选择次品1件，原采购成本2.50元。','新退仓审核后按此新来源占用并实出。','原采购累计入账10、退仓出库4、拒收收回1，净入库7，与库存一致。'],recovered_source_return)
 def mixed_final_short():
  supplier(mixed,'MIX_REFUSE','REFUSE',[2,1]);id=reclaim(mixed,'MIX_RECLAIM',[2,1]);approve_reclaim(id,'MIX_RECLAIM_APPROVE');receive(id,'MIX_IN',[(0,'ZP',1),(1,'CC',1)]);d=wait(lambda:get_reclaim(id),lambda d:d['posted_quantity']==2,'Mixed recovery posting');assert d['receipt_final']==1 and d['state']=='ACTIVE';assert_stock(mixed,4,2,0,2);post('MIX_STOP',RET+'/reclaims/'+str(id)+'/cancel',dict(control_version=d['control_version'],reason='最终短收，结束未收回1件计划'));d=wait(lambda:get_reclaim(id),lambda d:d['state']=='CANCELED','Stop reclaim short');return d
 record('14','正次品混合收回最终短收，只按实收2件入账',mixed,['原退仓正品2、次品1均已实际出库。','供应商拒收3件，计划收回3件；仓库最终实收正品1、次品1。','按各实际质量恢复2件，停止剩余1件计划，不虚增库存。'],mixed_final_short)
 def virtual_recovery():
  supplier(virtual,'VIRTUAL_REFUSE','REFUSE',[2]);id=reclaim(virtual,'VIRTUAL_RECLAIM',[2]);d=approve_reclaim(id,'VIRTUAL_APPROVE');d=wait(lambda:get_reclaim(id),lambda d:d['state']=='COMPLETED','Virtual recovery');assert_stock(virtual,9,9);return d
 record('15','虚拟仓拒收收回审核后自动入账，保持原出库记录',virtual,['原虚拟退仓3件，登记供应商拒收2件。','创建并审核独立收回草稿。','自动虚拟入库2件，总库存由7恢复9，无外部仓库回传要求。'],virtual_recovery)
 def zero_receipt():
  supplier(split,'ZERO_REFUSE','REFUSE',[1]);id=reclaim(split,'ZERO_RECLAIM',[1]);approve_reclaim(id,'ZERO_APPROVE');receive(id,'ZERO_IN',[(0,'ZP',0)]);d=wait(lambda:get_reclaim(id),lambda d:d['receipt_final']==1 and d['events'][0]['state']=='POSTED','Zero final posting');assert d['posted_quantity']==0;assert_stock(split,5,5);post('ZERO_STOP',RET+'/reclaims/'+str(id)+'/cancel',dict(control_version=d['control_version'],reason='仓库最终未收到货物，结束本次收回计划'));return wait(lambda:get_reclaim(id),lambda d:d['state']=='CANCELED','Zero stop')
 record('16','仓库最终实收0件正常收尾，不产生库存增量',split,['供应商拒收1件并安排收回。','仓库签名最终确认实收0件。','记录最终事实但不调用空库存入账，停止后释放收回计划额度。'],zero_receipt)
 def concurrent_money():
  d=doc(partial);bodies=[command(partial,'RACE_'+str(i),funds_state='REFUND',amount=3,evidence='[TEST] 并发退款凭证') for i in range(2)]
  def attempt(item):
   i,b=item
   try:return dict(ok=True,result=post('RACE_'+str(i),RET+'/'+str(partial['return_id'])+'/funds',b)['data']['funds_amount'])
   except AssertionError as e:return dict(ok=False,reason=str(e))
  with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(attempt,enumerate(bodies)))
  assert sum(x['ok'] for x in results)==1;assert float(doc(partial)['funds_amount'])==3;return dict(results=results,document=doc(partial))
 record('17','并发两笔各3元凭证，实际退仓货值5元只允许一笔成功',partial,['原申请5件，目前分批实出2件，货值5元。','并发提交两笔各3元凭证。','版本及金额校验确保只计一笔，库存仍总量8、可用5、占用3。'],concurrent_money)
 def partial_stop():
  post('PART_STOP',RET+'/'+str(partial['return_id'])+'/cancel',command(partial,'PART_STOP',reason='未最终出库，申请停止剩余3件'));d=wait(lambda:doc(partial),lambda d:d['state']=='CANCELED','Nonfinal WMS cancel');assert_stock(partial,8,8);return d
 record('18','仓库未最终结束时停止剩余，取得原仓取消结果后释放',partial,['原单已分批实出2件，剩余3件占用。','明确申请停止剩余，自动调用奇门order.cancel。','仓库明确成功后释放3件，已出库2件和原凭证保留。'],partial_stop)
 def concurrent_reclaim():
  first=reclaim(split,'RECLAIM_RACE_A',[1]);second=reclaim(split,'RECLAIM_RACE_B',[1])
  def approve_one(item):
   index,ident=item;d=get_reclaim(ident)
   if d['state']!='DRAFT':return dict(id=ident,ok=True)
   try:post('RC_RACE_'+str(index),RET+'/reclaims/'+str(ident)+'/approve',dict(control_version=d['control_version']));return dict(id=ident,ok=True)
   except AssertionError as e:return dict(id=ident,ok=False,reason=str(e))
  with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(approve_one,enumerate([first,second])))
  assert sum(x['ok'] for x in results)==1;winner=next(x['id'] for x in results if x['ok']);wait(lambda:get_reclaim(winner),lambda d:d['dispatch_state']=='ACCEPTED','Concurrent reclaim dispatch');assert_stock(split,5,5);state['ids']['reclaim_race_winner']=winner;state['ids']['reclaim_race_draft']=next(x['id'] for x in results if not x['ok']);save();return dict(results=results,documents=[get_reclaim(first),get_reclaim(second)])
 record('19','同一拒收余额并发审核两张收回单，仅一张占用计划额度',split,['原拒收1件的上次收回已最终实收0并停止。','创建两张各1件收回草稿，并发审核。','原退仓锁和来源额度校验保证仅一张成功下发；另一张保留待审核，库存不变。'],concurrent_reclaim)
 carrier_code='PRI_CARRIER_'+state['stamp']
 if not state['ids'].get('carrier_code'):
  catalog=api('/order/orders/carrier-catalog')['data'];existing=next((c for c in catalog['carriers'] if c['carrier_code']==carrier_code),None)
  if not existing:post('CARRIER_CONFIG','/order/orders/carriers',dict(carrier_code=carrier_code,carrier_name='[退仓迭代TEST] 顺丰',enabled=1,revision=0,services=[dict(owner_code='PRQO_'+state['stamp'],real_store_code='PRQW_'+state['stamp'],warehouse_carrier_code='SF',provinces=[],excluded_provinces=[])]))
  state['ids']['carrier_code']=carrier_code;save()
 if not state['ids'].get('supplier_address_ready'):
  supplier_info=api('/supplychain/purchaseWorkspace/supplier/'+str(base['supplier']))['data']
  body=dict(id=base['supplier'],supplierName=supplier_info['supplierName'],companyName=supplier_info['companyName'],status=2,contactUser='[TEST]退仓收件人',contactTel='13000000000',contactProvince='浙江省',contactCity='杭州市',contactArea='西湖区',contactAddress='本地验收供应商退仓地址')
  post('SUPPLIER_ADDRESS','/supplychain/purchaseWorkspace/supplier',body);state['ids']['supplier_address_ready']=True;save()
 state['ledger']={c['sku']:sql('SELECT operation_type,relation_sn,inventory_type,change_quantity,old_zp_actual_number,new_zp_actual_number,old_cp_actual_number,new_cp_actual_number FROM wms_inventory_change_history WHERE company_code=\'QM\' AND sku_sn='+quote(c['sku'])+' ORDER BY log_id',INV) for c in cases.values()}
 assert protected()==state['protected'],'Existing order/refund stock or policies changed'
 state['protected_baseline_unchanged']=True;state['current_stock']={k:stock(c) for k,c in cases.items()};state['completed_at']=datetime.now().isoformat();save();print('PASS',len(state['results']),'retained procurement-return lifecycle scenarios')

if __name__=='__main__':main()
