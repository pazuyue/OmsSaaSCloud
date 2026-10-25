"""Retained TEST acceptance. No target review, return creation/dispatch, payment or flow retry calls.

Only source fixture events, merchant rules/agreements and signed warehouse events
are supplied. Existing shops, global jobs, queue switches and retained stock stay untouched.
"""
import copy
import hashlib
import io
import json
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path
import openpyxl
import verify_after_ship_refund_auto_cases as old
import verify_allocation_rules_v2 as fixture
import verify_exchange_ownership as ownership
from verify_goods_workspace_ui import BASE, login, request
from test_multi_virtual_shipping import sql, quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/return-refund-auto'
ORDER,INV,GOODS,CHANNEL,SOURCE=old.ORDER,old.INV,old.GOODS,old.CHANNEL,old.SOURCE
TITLES={'01':'普通商品全退','02':'买家延后提供返程运单','03':'三件购买仅退一件','04':'主商品与赠品完整合退','05':'主商品先实收、赠品待补收','06':'四张退款最后加入后120秒合包','07':'套装福袋及套装福袋赠品','08':'自动退差价20后退货退款80含赠','09':'自动退差价20后退货退款100超额保护','10':'并发重复回传及付款响应丢失'}
def dump(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str)+'\n','utf-8')

class Acceptance(old.Run):
    def __init__(self):
        OUT.mkdir(exist_ok=True);self.path=OUT/'manifest.json';self.token=login()
        self.data=json.loads(self.path.read_text('utf-8')) if self.path.exists() else {'run_id':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':{},'scope':'new shops only; source-ID intake events, real MQ continuations; global scheduled intake not covered'}
        self.save()
    def api(self,path,body=None,method=None):
        forbidden=('/review','/goods/create','/goods/finish','/goods/refresh','/flow/retry','/platform/actions/','/parcels/auto-run')
        if body is not None and path.startswith('/order/refunds/business/') and any(v in path for v in forbidden):raise AssertionError('Manual target action forbidden: '+path)
        return super().api(path,body,method)
    def snapshot(self,c):
        skus=','.join(map(quote,c.get('physical_skus',[c['sku']])))
        def rows(q,db=INV):return [line.split('\t') for line in sql(q,db).splitlines()]
        result={'company':rows("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+skus+') ORDER BY sku_sn'),
                'warehouse':rows("SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number FROM wms_inventory WHERE company_code='QM' AND sku_sn IN ("+skus+') ORDER BY store_code,sku_sn'),
                'channel':rows("SELECT sku_sn,available_stock,reserved_stock,allocated_stock,quota_stock,quota_reserved_stock FROM oms_channel_inventory WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND sku_sn IN ('+skus+') ORDER BY sku_sn')}
        if c.get('parents'):
            parents=','.join(map(quote,c['parents'].values()));result['parent_channel']=rows("SELECT sku_sn,quota_stock,quota_reserved_stock FROM oms_channel_inventory WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND sku_sn IN ('+parents+') ORDER BY sku_sn')
        return result
    def product(self,c,code,kind='NORMAL',gift=False):
        sample=self.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
        found=self.api('/goods/info/list?pageNum=1&pageSize=1',{'skuSn':code})['rows']
        body={'skuSn':code,'goodsSn':code,'goodsName':'[退货退款自动验收'+c['key']+'] '+code[-2:],'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':100,'validity':30,'productType':kind,'isGift':int(gift)}
        if found:body['id']=found[0]['id'];self.api('/goods/info',body,'PUT');return body['id']
        return self.api('/goods/info',body)
    def protect_bags(self,c,codes):
        if c.get('bags_allocated'):return
        rule=self.api('/inventory/allocation',{'ruleName':'[退货退款自动验收] 福袋保障 '+c['case_id'],'ruleType':2,'allocationType':1,'ruleRange':2,'ruleMode':2,'stores':[c['warehouses'][0]['code']],'channels':[{'channelId':c['channel_id'],'channelName':c['channel_name'],'ruleType':2,'percentage':1,'decimalHandleType':1}]})
        book=openpyxl.Workbook();book.active.append(['SKU(必填)'])
        for code in codes:book.active.append([code])
        stream=io.BytesIO();book.save(stream);boundary='ReturnRefundFixture'
        wire=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="bags.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()+stream.getvalue()+('\r\n--'+boundary+'--\r\n').encode()
        req=urllib.request.Request(BASE+'/prod-api/inventory/allocation/'+str(rule['id'])+'/goods?revision='+str(rule['revision']),data=wire,headers={'Authorization':'Bearer '+self.token,'Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as reply:imported=json.load(reply)
        assert imported['code']==200,imported.get('msg')
        rule=self.api('/inventory/allocation/'+str(rule['id'])+'/submit?revision='+str(imported['data']['revision']),{})
        self.api('/inventory/allocation/'+str(rule['id'])+'/start?revision='+str(rule['revision'])+'&action=EXECUTE',{})
        result=self.api('/inventory/allocation/'+str(rule['id'])+'/step',{});assert result['status']==5,result
        c['bags_allocated']=rule['id'];self.save()
    def before_conversion(self,c,token):
        if c.get('source_order_ready'):return
        prefix=c['prefix'];gift=c['key'] in ['04','05','08'];composite=c['key']=='07'
        physical=[prefix+x for x in 'ABCDEFGH'] if composite else [prefix+x for x in 'ABCD'] if c['key']=='06' else [c['sku']]+([prefix+'G'] if gift else [])
        c['physical_skus']=physical
        if gift:c['gift']=prefix+'G'
        for code in physical:
            self.product(c,code,gift=gift and code==c['gift'])
            if code not in c.setdefault('received_skus',[]):self.inner_receive(c,code,c['warehouses'][0]['code'],100);c['received_skus'].append(code);self.save()
            sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(code)+',100,'+quote(json.dumps([c['warehouses'][0]['code']]))+')',INV)
        if composite:
            parents={name:prefix+name for name in ['K','L','GK','GL']};c['parents']=parents
            for key,components in [('K',['A','B']),('GK',['E','F'])]:
                ident=self.product(c,parents[key],'BUNDLE',key.startswith('G'));rule=self.api('/goods/info/'+str(ident)+'/bundle')
                if not rule['revision']:self.api('/goods/info/'+str(ident)+'/bundle',{'revision':0,'enabled':1,'inventory_mode':'VIRTUAL','components':[{'sku_sn':prefix+x,'quantity':1} for x in components]})
            for key,components in [('L',['C','D']),('GL',['G','H'])]:
                ident=self.product(c,parents[key],'LUCKY_BAG',key.startswith('G'));rule=self.api('/goods/info/'+str(ident)+'/lucky-bag')
                if not rule['revision']:self.api('/goods/info/'+str(ident)+'/lucky-bag/publish',{'revision':0,'draft_revision':rule['draft_revision'],'enabled':1,'mode':'RANDOM','selection':'WEIGHTED','max_cost':0,'preset_quantity':0,'groups':[{'key':x,'name':x,'candidates':[{'sku_sn':prefix+x,'weight':1}]} for x in components]})
            self.protect_bags(c,[parents['L'],parents['GL']])
            for code in [parents['K'],parents['GK']]:sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(code)+',100,'+quote(json.dumps([c['warehouses'][0]['code']]))+')',INV)
            sales=[(parents['K'],1,200),(parents['L'],1,100)]
        else:sales=[(code,c['quantity'] if len(physical)==1 else 1,(i+1)*100 if c['key']=='06' else 100*(c['quantity'] if len(physical)==1 else 1)) for i,code in enumerate(physical) if not gift or code!=c['gift']]
        c['sales']=[{'sku':code,'quantity':qty,'payment':fee,'oid':c['platform_tid']+str(i+1)} for i,(code,qty,fee) in enumerate(sales)];c['platform_oid']=c['sales'][0]['oid']
        if (gift or composite) and not c.get('gift_group'):
            specs=[([c['sku']],[c['gift']])] if gift else [([parents['K']],[parents['GK']]),([parents['L']],[parents['GL']])]
            rules=[{'key':'G'+str(i),'name':'买指定商品赠指定赠品','type':'PRODUCT','minimum':1,'cap':1,'max_count':1,'skus':buy,'amount':'0','gifts':[{'sku_sn':code,'quantity':1} for code in gifts],'tiers':[],'stop':False} for i,(buy,gifts) in enumerate(specs)]
            c['gift_group']=self.api('/order/gifts/groups',{'name':'[退货退款自动验收] '+c['case_id'],'enabled':1,'policy':{'start_time':(datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),'end_time':(datetime.now()+timedelta(days=3)).strftime('%Y-%m-%d %H:%M:%S'),'shipped_policy':'RETURN','allow_waive':False,'difference_affects_amount':False,'rules':rules}})['id'];self.save()
            c['gift_shop_group']=self.api('/order/gifts/shop-groups',{'name':'[退货退款自动验收] '+c['case_id'],'enabled':1,'channels':[c['channel_id']],'links':[{'group_id':c['gift_group'],'continue_after_match':False}]})['id'];self.save()
        # A new TEST connection belongs only to this run, and can receive a common return document.
        if not self.data.get('connection_id'):
            connection=next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id']==c['connection_id']);assert connection['environment']=='TEST'
            secret=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
            self.data['connection_id']=self.api('/supplychain/wmsIntegration/connections',{**{k:connection[k] for k in ['provider','api_version','environment','endpoint','app_key','customer_id']},'name':'[退货退款自动验收] '+self.data['run_id'],'secret':secret['secret'],'enabled':1,'shared_return_waybill':1});self.save()
        warehouse=self.api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']))
        if warehouse['connectionId']!=self.data['connection_id']:self.api('/supplychain/simulationStore',{**{k:warehouse[k] for k in ['id','wmsSimulationCode','wmsSimulationName','ownerWarehouseId','inboundMode','outboundMode','externalWarehouse','externalOwner','status']},'connectionId':self.data['connection_id']},'PUT')
        c['connection_id']=self.data['connection_id'];c['initial_stock']=self.snapshot(c)
        trade={'tid':c['platform_tid'],'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':c['seller'],'created':c['created'],'modified':c['created'],'pay_time':c['created'],'payment':str(sum(x['payment'] for x in c['sales'])),'post_fee':'0','receiver_name':'自动退货退款验收','receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'验收区','receiver_address':'本地测试地址，无实际配送','orders':{'order':[{'oid':x['oid'],'outer_sku_id':x['sku'],'num':x['quantity'],'payment':str(x['payment']),'divide_order_fee':str(x['payment']),'price':str(x['payment']/x['quantity']),'title':'[退货退款自动验收'+c['key']+'] '+x['sku']} for x in c['sales']]}}
        payload=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False)
        if not sql("SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND platform_tid='+quote(c['platform_tid']),ORDER):
            values=[c['platform_tid'],'WAIT_SELLER_SEND_GOODS','fixed',c['seller'],c['created'],c['created'],c['created'],hashlib.sha256(payload.encode()).hexdigest(),payload,'WAITING']
            sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',"+str(c['channel_id'])+','+','.join(map(quote,values))+')',ORDER)
        c['source_order_ready']=True;self.save()
    def configure_case(self,c):
        if not c.get('refund_configured'):super().configure(c)
        flow=self.api('/order/refunds/flow-configs/'+str(c['channel_id']))
        proposed={**flow,'enabled':1,'auto_return_review':1,'return_review_limit':'500.00','auto_difference':1,'auto_agree_return':1,'seller_address_id':'1','auto_full_cancel':0,'auto_shipped_cancel':0,'small_refund_enabled':0,'auto_review':1,'auto_pay':1,'auto_finish_return':1,'funds_owner':'OMS','payment_limit':'500.00'}
        if any(str(flow.get(k))!=str(v) for k,v in proposed.items() if k not in ['revision','api_sync']):self.api('/order/refunds/flow-configs/'+str(c['channel_id']),proposed)
        c['deployed_rules']=self.api('/order/refunds/flow-configs/'+str(c['channel_id']));self.save()
    def prepare(self,key):
        if key not in self.data['cases']:
            stamp=self.data['run_id'];slot=self.data.get('retry_slots',{}).get(key,key);prefix='RR'+stamp+'C'+slot;tid='86'+stamp+slot
            self.data['cases'][key]={'key':key,'slot':slot,'title':TITLES[key],'prefix':prefix,'case_id':stamp+'_'+slot,'sku':prefix+'A','seller':prefix,'channel_name':'[退货退款自动验收'+key+'] '+TITLES[key]+' '+stamp+' '+slot,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'platform_tid':tid,'platform_oid':tid+'1','quantity':3 if key=='03' else 1,'received':True,'warehouses':[{'code':prefix+'V','quantity':0,'package_count':1}],'refunds':{}}
            self.save()
        c=self.data['cases'][key];fixture.MANIFEST=OUT/(key+'-fixture.json');dump(fixture.MANIFEST,c)
        if not c.get('prepared'):
            try:fixture.prepare_case(c,self.token,before_conversion=self.before_conversion,order_quantity=c['quantity'])
            finally:self.save()
            c['prepared']=True;self.save()
        helper=ownership.Case.__new__(ownership.Case);helper.c=c;helper.token=self.token;helper.path=fixture.MANIFEST
        if not c.get('shipment_ready'):
            order=helper.allocate()
            for entry in order['shipments']:
                if entry['status'] in ['VOIDED','VOIDING','CLOSED']:continue
                shipment=helper.shipment(entry['id'])
                if shipment['carrier_match_status']!='MATCHED':assert self.api('/order/orders/shipments/match-carrier',{'items':[{'id':shipment['id'],'control_version':shipment['control_version']}]})[0]['success'];shipment=helper.shipment(entry['id'])
                if shipment['wms_dispatch_state']=='NONE':assert self.api('/order/orders/shipments/dispatch',{'items':[{'id':shipment['id'],'control_version':shipment['control_version']}]})[0]['success']
                if helper.shipment(entry['id'])['wms_dispatch_state']=='PENDING':self.api('/order/orders/shipments/'+str(entry['id'])+'/continue-dispatch',{'control_version':helper.shipment(entry['id'])['control_version']})
                until=time.monotonic()+120
                while helper.shipment(entry['id'])['wms_dispatch_state']!='ACCEPTED':
                    if time.monotonic()>until:raise RuntimeError('Owned TEST outbound not accepted')
                    time.sleep(2)
                helper.dispatch_ship(entry)
            c['shipment_ready']=True;c['stock_before_refunds']=self.snapshot(c);self.save()
        c['order_url']=BASE+'/oms-order/business?view=business&orderId='+str(c['order_id']);self.configure_case(c);print('PREPARED',key,c['order_sn'],flush=True);return c
    def reference(self,c,key,fee,index=0,returns=True):
        if key not in c['refunds']:
            c['refunds'][key]={'refund_id':'89'+self.data['run_id']+c.get('slot',c['key'])+str(len(c['refunds'])+1),'amount':str(fee),'oid':c['sales'][index]['oid'],'quantity':1 if c['key']=='03' else c['sales'][index]['quantity'],'returns':returns};self.save()
        return c['refunds'][key]
    def publish(self,c,ref,initial=False):
        rid=ref['refund_id'];raw=sql("SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(rid),CHANNEL)
        p=json.loads(raw)['refund_get_response']['refund'];p={k:v for k,v in p.items() if not k.startswith('_local_test')};wire=json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False)
        values=[rid,c['platform_tid'],ref['oid'],p['status'],c['seller'],p['created'],p['modified'],p['modified'],wire]
        sql('INSERT INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(map(quote,values))+') ON DUPLICATE KEY UPDATE status=VALUES(status),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified),jdp_response=VALUES(jdp_response)',SOURCE)
        ref['source_sequence']=ref.get('source_sequence',0)+1;self.save()
        self.api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[rid],'request_key':'RR_SRC_'+rid+'_'+str(ref['source_sequence'])})
    def inject(self,c,key,fee,index=0,returns=True):
        ref=self.reference(c,key,fee,index,returns);rid=ref['refund_id']
        if not ref.get('injected'):
            now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');p={'refund_id':rid,'tid':c['platform_tid'],'oid':ref['oid'],'seller_nick':c['seller'],'status':'WAIT_SELLER_AGREE','refund_fee':str(fee),'num':ref['quantity'],'has_good_return':returns,'dispute_type':'REFUND_AND_RETURN' if returns else 'REFUND','refund_phase':'aftersale','refund_version':'1','reason':'[退货退款自动验收'+c['key']+'] '+c['title'],'good_status':'BUYER_RECEIVED','created':now,'modified':now}
            if c['key']=='10':p['_local_test_pay_response_loss']=True
            sql("INSERT IGNORE INTO channel_refund_sim(company_code,channel_id,refund_id,payload,modified) VALUES ('qm',"+str(c['channel_id'])+','+quote(rid)+','+quote(json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False))+','+quote(now)+')',CHANNEL)
            self.publish(c,ref,True);ref['injected']=True;self.save()
        return ref
    def authorize_difference(self,c):
        ref=self.reference(c,'prior',20,returns=False)
        if not c.get('difference_authorization'):
            c['difference_authorization']=self.api('/order/refunds/flow-configs/'+str(c['channel_id'])+'/money-authorizations',{'confirmed':True,'platform_tid':c['platform_tid'],'platform_oid':ref['oid'],'refund_id':ref['refund_id'],'amount':'20.00','reference_sn':'RR_DIFF_'+c['case_id'],'evidence':'[本地TEST商家协议] 此订单子单明确优惠补差20元，保留原购买商品及赠品；授权在退款接入前登记。','expires_at':(datetime.now()+timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S')});self.save()
        return self.inject(c,'prior',20,returns=False)
    def observe(self,c,ref):
        if not ref.get('id'):
            raw=sql("SELECT id,refund_sn FROM oms_refund WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),ORDER)
            if not raw:return None
            ident,sn=raw.split('\t');ref.update(id=int(ident),refund_sn=sn,url=BASE+'/oms-order/businessRefunds?id='+ident);self.save()
        b=self.api('/order/refunds/business/'+str(ref['id']));goods=self.api('/order/refunds/business/'+str(ref['id'])+'/goods');parcels=self.api('/order/refunds/business/'+str(ref['id'])+'/parcels')
        flow=sql("SELECT state,last_message FROM oms_refund_flow_task WHERE company_code='QM' AND refund_id="+str(ref['id']),ORDER).split('\t',1)
        s={'business':b,'goods':goods,'parcels':parcels,'flow':flow};dump(OUT/(c['key']+'-'+str(ref['id'])+'-observation.json'),s);return s
    def buyer(self,c,ref):
        if ref.get('buyer_sent'):return
        raw=sql("SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL);p=json.loads(raw)['refund_get_response']['refund']
        assert p['status']=='WAIT_BUYER_RETURN_GOODS',p['status']
        p.update(status='WAIT_SELLER_CONFIRM_GOODS',company_name='SF',sid='RRRET_'+c['case_id'],refund_version=str(int(p['refund_version'])+1),modified=max(datetime.now(),datetime.strptime(p['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'))
        sql("UPDATE channel_refund_sim SET payload="+quote(json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False))+',modified='+quote(p['modified'])+" WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL)
        self.publish(c,ref);ref['buyer_sent']=True;ref['buyer_sent_at']=datetime.now().isoformat();self.save()
    def receive(self,c,states,parcel,only_skus=None,partial=False,tag='FINAL'):
        doc=parcel.get('warehouse_document');marker=str(parcel['id'])+'_'+tag
        if not doc or doc['dispatch_state']!='ACCEPTED' or marker in c.get('receipts',{}):return False
        planids={m['plan_id'] for m in parcel['members']};plans=[p for s in states for p in s['goods']['plans'] if p['id'] in planids]
        if not plans or not plans[0].get('actual'):return False
        quantities={}
        for p in plans:
            if only_skus and p['sku_sn'] not in only_skus:continue
            left=p['quantity']-p['accepted_quantity']
            if left>0:quantities[p['sku_sn']]=quantities.get(p['sku_sn'],0)+left
        if not quantities:return False
        actual=plans[0]['actual'];connection=next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id']==actual['connection_id']);assert connection['environment']=='TEST'
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for key,value in {'returnOrderCode':doc['return_sn'],'returnOrderId':doc['external_order'],'warehouseCode':actual['external_warehouse'],'ownerCode':actual['external_owner'],'outBizCode':'RR_'+c['case_id']+'_'+marker,'orderType':'THRK','confirmType':'1' if partial else '0'}.items():ET.SubElement(head,key).text=str(value)
        lines=ET.SubElement(root,'orderLines')
        for index,(sku,qty) in enumerate(sorted(quantities.items())):
            line=ET.SubElement(lines,'orderLine')
            for key,value in {'itemCode':sku,'ownerCode':actual['external_owner'],'actualQty':qty,'inventoryType':'ZP','batchCode':'RRRET_'+c['case_id']+'_'+str(index)}.items():ET.SubElement(line,key).text=str(value)
        wire=ET.tostring(root,encoding='unicode');(OUT/(c['key']+'-'+marker+'.xml')).write_text(wire,'utf-8')
        params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret'])
        def send():
            req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        if c['key']=='10':
            with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(lambda _:send(),range(3)))
        else:send()
        c.setdefault('receipts',{})[marker]={'quantities':quantities,'partial':partial,'sent_at':datetime.now().isoformat(),'concurrent_identical_callbacks':3 if c['key']=='10' else 1};self.save();return True
    def buyer_supplement(self,c,ref):
        if c.get('supplement_waybill'):return
        raw=sql("SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL);p=json.loads(raw)['refund_get_response']['refund']
        assert p['status']=='WAIT_SELLER_CONFIRM_GOODS'
        c['initial_return_waybill']=p['sid'];c['supplement_waybill']='RRSUP_'+c['case_id']
        p.update(sid=c['supplement_waybill'],refund_version=str(int(p['refund_version'])+1),modified=max(datetime.now(),datetime.strptime(p['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'))
        sql("UPDATE channel_refund_sim SET payload="+quote(json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False))+',modified='+quote(p['modified'])+" WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL)
        self.publish(c,ref);self.save()
    def run(self,keys,baseline=False):
        if 'protected' not in self.data:self.protect()
        for key in keys:self.prepare(key)
        if baseline:
            c=self.data['cases']['01'];ref=self.inject(c,'target',100);end=time.monotonic()+110
            while time.monotonic()<end:
                s=self.observe(c,ref)
                if s and s['flow'] and s['flow'][0]=='MANUAL':dump(OUT/'before-fix-01.json',s);print('BASELINE FAIL: '+s['flow'][-1],flush=True);return
                time.sleep(3)
            raise RuntimeError('Baseline not observed')
        for key in keys:
            c=self.data['cases'][key]
            assert c['deployed_rules'].get('auto_return_review')==1,'New automatic return rule not deployed'
            if key in ['08','09']:self.authorize_difference(c)
            elif key in ['06','07']:
                for i,sale in enumerate(c['sales']):self.inject(c,'target'+str(i+1),sale['payment'],i)
            else:self.inject(c,'target',100)
        end=time.monotonic()+850
        while time.monotonic()<end:
            done=0
            for key in keys:
                c=self.data['cases'][key]
                merge=self.data['cases'].get('06',{})
                if key!='06' and merge.get('merge_start') and not all(r.get('buyer_sent') for r in merge['refunds'].values()):continue
                if c.get('done'):done+=1;continue
                states=[]
                for role,ref in list(c['refunds'].items()):
                    s=self.observe(c,ref)
                    if not s:continue
                    b=s['business'];states.append(s)
                    if b['state']=='COMPLETED' and not ref.get('completed_at'):ref['completed_at']=datetime.now().isoformat();self.save()
                    if role=='prior':
                        if b['state']=='COMPLETED':
                            assert b['purpose']=='DIFFERENCE' and b['review_by']=='自动售后规则'
                            c.setdefault('stock_after_difference',self.snapshot(c));self.inject(c,'target',80 if key=='08' else 100)
                        continue
                    if key=='09' and s['flow'] and s['flow'][0]=='MANUAL' and '剩余可退 80.00' in s['flow'][-1]:c['protection_verified']=True;c['done']=True;self.save();continue
                    if b['state']=='COMPLETED':continue
                    if b['platform']['platform_status']=='WAIT_BUYER_RETURN_GOODS' and not ref.get('buyer_sent'):
                        if key=='02':
                            c.setdefault('no_waybill_since',time.time());assert b['funds_state']!='SUCCESS' and not any(p.get('warehouse_document') for p in s['parcels']);c['no_waybill_stock']=self.snapshot(c);self.save()
                            if time.time()-c['no_waybill_since']<40:continue
                            c['wait_logistics_verified']=True
                        if key=='06':
                            role_index=int(role[-1])-1;delay=[0,30,60,100][role_index]
                            if role_index==0:c.setdefault('merge_start',time.time())
                            if 'merge_start' not in c or time.time()-c['merge_start']<delay:continue
                        self.buyer(c,ref)
                parcels={p['id']:p for s in states for p in s['parcels']}
                if key=='06' and parcels:
                    timing=sql("SELECT TIMESTAMPDIFF(SECOND,MAX(m.create_time),p.dispatch_after),COUNT(DISTINCT m.refund_id),COUNT(DISTINCT d.id) FROM wms_refund_parcel p JOIN wms_refund_parcel_member m ON m.company_code=p.company_code AND m.parcel_id=p.id LEFT JOIN wms_refund_parcel_document d ON d.company_code=p.company_code AND d.parcel_id=p.id WHERE p.company_code='QM' AND p.id="+str(next(iter(parcels)))+' GROUP BY p.id',GOODS)
                    c['merge_timing']=timing
                    if len(c['refunds'])!=4 or not all(r.get('buyer_sent') for r in c['refunds'].values()):assert not any(p.get('warehouse_document') for p in parcels.values()),'Dispatched before last planned member joined'
                for parcel in parcels.values():
                    if key=='05':
                        if not c.get('main_received') and self.receive(c,states,parcel,[c['sku']],True,'MAIN'):c['main_received']=time.time();self.save();continue
                        if c.get('main_received') and not c.get('missing_gift_verified'):
                            target=states[0]['business'];assert target['funds_state']!='SUCCESS';stocks=self.snapshot(c)
                            if {r[0]:r[1] for r in stocks['company']}=={c['sku']:'100',c['gift']:'99'}:c['missing_gift_verified']=True;c['stock_main_only']=stocks;self.save()
                        if not c.get('missing_gift_verified') or time.time()-c['main_received']<35:continue
                        if not c.get('supplement_waybill'):
                            self.buyer_supplement(c,c['refunds']['target']);continue
                        target_state=next(s for s in states if s['business']['id']==c['refunds']['target']['id'])
                        if target_state['business']['platform'].get('payload'):
                            payload=target_state['business']['platform']['payload']
                            if isinstance(payload,str):payload=json.loads(payload)
                            claimed=payload.get('refund_get_response',{}).get('refund',payload.get('refund',{})).get('sid')
                            if claimed!=c['supplement_waybill']:continue
                        self.receive(c,states,parcel,[c['gift']],False,'GIFT_SUPPLEMENT')
                    else:self.receive(c,states,parcel)
                targets=[s for s in states if s['business']['id']!=c['refunds'].get('prior',{}).get('id')]
                expected_targets=4 if key=='06' else 2 if key=='07' else 1
                if key!='09' and len(targets)==expected_targets and all(s['business']['state']=='COMPLETED' for s in targets):c['done']=True;self.save();print('COMPLETED',key,flush=True)
            if done==len(keys):break
            time.sleep(3)
        self.report(keys)
    def report(self,keys):
        rows=[]
        for key in keys:
            c=self.data['cases'][key];states=[self.observe(c,r) for r in c['refunds'].values()];stock=self.snapshot(c);audits=[]
            for ref in c['refunds'].values():
                if not ref.get('id'):continue
                audits.extend(json.loads(x) for x in sql("SELECT JSON_OBJECT('refund_id',refund_id,'actor',actor,'action',action,'state',state,'policy_revision',policy_revision,'request_id',request_id) FROM oms_refund_operation WHERE company_code='QM' AND refund_id="+str(ref['id'])+' ORDER BY id',ORDER).splitlines())
            approved=[s['business'] for s in states if s and s['business']['review_state']=='APPROVED'];manual_reviews=any(b['review_by']!='自动售后规则' for b in approved)
            refund_ids=','.join(str(r['id']) for r in c['refunds'].values() if r.get('id'))
            manual_review_events=int(sql("SELECT COUNT(*) FROM oms_refund_event WHERE company_code='QM' AND action='REVIEW' AND actor NOT IN ('自动售后规则','自动售后流程') AND refund_id IN ("+refund_ids+')',ORDER)) if refund_ids else 0
            manual_reviews=manual_reviews or manual_review_events>0
            manual_ops=any(o['actor']!='自动售后流程' or o['policy_revision'] is None for o in audits)
            expected_total=98 if key=='03' else 99 if key=='09' else 100
            stock_ok=all(int(r[1])==expected_total and int(r[2])==expected_total and r[3:]==['0','0'] for r in stock['company']) and all(int(r[2])==expected_total and int(r[3])==expected_total and r[4]=='0' for r in stock['warehouse'])
            old_quota={r[0]:r[4:] for r in c['stock_before_refunds']['channel']};quota_ok=all(r[4:]==old_quota[r[0]] and r[2:4]==['0','0'] for r in stock['channel'])
            company_available={r[0]:int(r[2]) for r in stock['company']};quota_ok=quota_ok and all(int(r[1])==min(int(r[4]),company_available[r[0]]) for r in stock['channel'])
            paid=sum(float(s['business']['refund_fee']) for s in states if s and s['business']['funds_state']=='SUCCESS')
            expected_paid=1000 if key=='06' else 300 if key=='07' else 20 if key=='09' else 100
            ok=bool(c.get('done')) and not manual_reviews and not manual_ops and stock_ok and quota_ok and paid==expected_paid
            payment_counts=[]
            for ref in c['refunds'].values():
                business=next((s['business'] for s in states if s and s['business']['id']==ref.get('id')),None)
                if business is None:ok=False;continue
                expected_pay=1 if business['funds_state']=='SUCCESS' else 0;oms_pays=sum(o['refund_id']==ref['id'] and o['action']=='PAY' for o in audits)
                vendor_pays=int(sql("SELECT COUNT(*) FROM channel_refund_command WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id'])+" AND method='taobao.rp.refunds.agree'",CHANNEL));payment_counts.append({'refund_id':ref['id'],'oms_pay_requests':oms_pays,'vendor_pay_requests':vendor_pays,'expected':expected_pay});ok=ok and oms_pays==vendor_pays==expected_pay
            if key=='02':ok=ok and bool(c.get('wait_logistics_verified'))
            if key=='05':ok=ok and bool(c.get('missing_gift_verified'))
            if key=='06':
                timing=sql("SELECT p.id,MIN(m.create_time),MAX(m.create_time),p.dispatch_after,MIN(d.create_time),TIMESTAMPDIFF(SECOND,MAX(m.create_time),p.dispatch_after),TIMESTAMPDIFF(SECOND,p.dispatch_after,MIN(d.create_time)),COUNT(DISTINCT m.refund_id),COUNT(DISTINCT d.id) FROM wms_refund_parcel p JOIN wms_refund_parcel_member m ON m.company_code=p.company_code AND m.parcel_id=p.id JOIN wms_refund_parcel_document d ON d.company_code=p.company_code AND d.parcel_id=p.id WHERE p.company_code='QM' AND m.channel_id="+str(c['channel_id'])+' AND m.platform_tid='+quote(c['platform_tid'])+' GROUP BY p.id',GOODS)
                c['merge_dispatch_evidence']=timing;parts=timing.split('\t');ok=ok and len(parts)==9 and parts[5]=='120' and int(parts[6])>=0 and parts[7:]==['4','1']
            if key=='07':
                expected_gifts={'target1':{c['prefix']+'E',c['prefix']+'F'},'target2':{c['prefix']+'G',c['prefix']+'H'}};association=True
                for role,expected in expected_gifts.items():
                    state=next(s for s in states if s and s['business']['id']==c['refunds'][role]['id'])
                    association=association and {p['sku_sn'] for p in state['goods']['plans'] if p.get('gift_plan_id')}==expected
                c['gift_association_verified']=association;ok=ok and association
                if c['stock_before_refunds'].get('parent_channel') is not None:ok=ok and stock.get('parent_channel')==c['stock_before_refunds']['parent_channel']
            if key=='10':
                count=sql("SELECT state,COUNT(*) FROM channel_refund_command WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+" AND method='taobao.rp.refunds.agree' GROUP BY state",CHANNEL)
                c['pay_response_loss_result']=count;ok=ok and count=='UNKNOWN\t1'
            verdict='PASS' if ok else 'FAIL'
            if key=='05' and ok:
                if c.get('supplement_waybill'):c['coverage_note']='主品先到、赠品缺少时停止付款；买家补充新运单后，仓库沿原共同THK补收指定赠品，自动退款。原运单与最新运单均保留，不静默覆盖原包裹；未测试另建补充THK模式。'
                else:verdict='PARTIAL';c['coverage_note']='主品先到及赠品待收保护、原THK后续补充实收已验证；补寄运单输入尚未覆盖'
            row={'case':key,'scenario':c['title'],'status':verdict,'refunds':c['refunds'],'order_sn':c['order_sn'],'order_url':c['order_url'],'states':[{'id':s['business']['id'],'state':s['business']['state'],'review_by':s['business']['review_by'],'purpose':s['business']['purpose'],'funds':s['business']['funds_state'],'flow':s['flow']} for s in states if s],'stock':stock,'stock_ok':stock_ok,'quota_unchanged':quota_ok,'paid':paid,'expected_paid':expected_paid,'manual_reviews':bool(manual_reviews),'manual_review_events':manual_review_events,'manual_platform_operations':manual_ops,'payment_counts':payment_counts,'operations':audits,'merge_dispatch_evidence':c.get('merge_dispatch_evidence'),'gift_association_verified':c.get('gift_association_verified'),'note':c.get('coverage_note','')};rows.append(row)
        self.save()
        from verify_merged_composite_gift_refunds import Acceptance as Previous
        previous=Previous()
        protection={'old_settings_unchanged':self.settings_hash()==self.data['protected']['old_settings_hash'],'queues_unchanged':sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type','ry-cloud')==self.data['protected']['queues'],'jobs_unchanged':sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id','ry-cloud')==self.data['protected']['jobs'],'old_merged_stock_unchanged':previous.snapshot()==self.data['protected']['merged_stock'],'old_preceding_stock_unchanged':previous.protected_order()==self.data['protected']['preceding_stock']}
        result={'run_id':self.data['run_id'],'rows':rows,'protection':protection,'retained':True,'platform_mode':'SIMULATION','warehouse_environment':'TEST','manual_refund_actions':False,'flow_retry_called':False,'direct_business_or_stock_sql':False,'intake_scope':'针对新测试退款号投递接入事件，未验全局定时拉取','authorization_scope':'08/09商家差价授权预先登记；两笔退款本地审核与平台付款均由自动流程执行'}
        dump(OUT/'results.json',result)
        text=['# 退货退款自动流程验收','', '批次 '+self.data['run_id']+'；测试数据保留。本地SIMULATION平台、TEST仓库。','',result['intake_scope']+'。无人工退款审核、建退货安排、下发、付款或流程重试。','', '| 用例 | 场景 | 结论 | 业务订单 | 退款单 | 库存正确 | 累计已退 |','|---|---|---|---|---|---|---|']
        for r in rows:text.append('|'+r['case']+'|'+r['scenario']+'|'+r['status']+'|['+r['order_sn']+']('+r['order_url']+')|'+' / '.join('['+x.get('refund_sn',x['refund_id'])+']('+x.get('url','')+')' for x in r['refunds'].values())+'|'+str(r['stock_ok'])+'|'+str(r['paid'])+'|')
        text+=['','用例05：'+self.data['cases'].get('05',{}).get('coverage_note','未执行'),'','差价授权登记不属于自动推断差价，详见授权记录及各退款的自动审核来源。','',json.dumps(protection,ensure_ascii=False)]
        (OUT/'验收结果.md').write_text('\n'.join(text)+'\n','utf-8');print([(r['case'],r['status']) for r in rows],flush=True);assert all(protection.values()),protection

if __name__=='__main__':
    runner=Acceptance();keys=[x for x in sys.argv[1:] if not x.startswith('--')] or list(TITLES)
    if '--report' in sys.argv:runner.report(keys)
    elif '--prepare' in sys.argv:
        if 'protected' not in runner.data:runner.protect()
        for key in keys:runner.prepare(key)
    else:runner.run(keys,'--baseline' in sys.argv)
