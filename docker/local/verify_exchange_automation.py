"""Retained exchange automation acceptance; TEST WMS and SIMULATION shops only.

Original sales shipments are fixture prerequisites. No target review, agreement,
QC, allocation, carrier match, dispatch, consignment, advance or flow retry calls.
Buyer source events, signed WMS facts and explicit merchant business requests
(internal creation / continuing a terminated round) are legitimate inputs.
"""
import copy
import hashlib
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timedelta
from pathlib import Path
from verify_goods_workspace_ui import BASE,login,request
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign
import verify_allocation_rules_v2 as fixture
import verify_exchange_ownership as ownership
import verify_after_ship_refund_auto_cases as refund_fixture

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/exchange-automation'
ORDER,INV,SUPPLY,CHANNEL=ownership.ORDER,ownership.INVENTORY,ownership.SUPPLY,ownership.CHANNEL
TITLES={'01':'平台普通换货完整自动流程','02':'两张换货同运单等待120秒合包','03':'退款及换货同SKU共同退回','04':'明确授权的协商退回换货','05':'未发货换品自动撤回及原SO继续发货','06':'连续A换B再B换A按实际来源','07':'换出缺货后补货自动继续','08':'无明确SKU映射转人工保护','09':'并发重复实收与重复来源幂等','10':'次品退回阻止自动验收与换出','11':'平台关闭后自动截停与释放未发占用','12':'同一HH剩余继续换出自动审核新轮次'}

def dump(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str)+'\n','utf-8')

class Run:
    def __init__(self):
        OUT.mkdir(parents=True,exist_ok=True);self.path=OUT/'manifest.json';self.token=login()
        self.data=json.loads(self.path.read_text('utf-8')) if self.path.exists() else {'run_id':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':{},'boundary':'local SIMULATION platform, signed TEST WMS; global jobs and existing shop settings unchanged'}
        self.save()
        if 'prior_refund_baseline' not in self.data:
            self.data['prior_refund_baseline']=sql("SELECT id,state,review_state,goods_state,funds_state,refund_fee FROM oms_refund WHERE company_code='QM' AND id IN (118,119,121,122,126,127) ORDER BY id",ORDER);self.save()
    def save(self):
        temp=self.path.with_suffix('.json.next');dump(temp,self.data);temp.replace(self.path)
    def api(self,path,body=None,method=None):
        if body is not None and path.startswith('/order/exchanges/') and any(part in path for part in ['/review','/execute','/advance','/qc','/allocate','/automatic-flow/recheck','/return-documents/']):raise AssertionError('Manual target action forbidden: '+path)
        value=request(path,payload=body,token=self.token,method=method or ('POST' if body is not None else 'GET'));assert value.get('code',200)==200,(path,value.get('msg'));return value.get('data',value)
    def helper(self,c):
        helper=ownership.Case.__new__(ownership.Case);helper.c=c;helper.token=self.token;helper.path=OUT/(c['key']+'-fixture.json');return helper
    def snapshot(self,c):
        codes=','.join(map(quote,[c['sku'],c['target_sku']]))
        def rows(query,db):return [line.split('\t') for line in sql(query,db).splitlines()]
        return {'company':rows("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+codes+') ORDER BY sku_sn',INV),
                'warehouse':rows("SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,cp_actual_number,cp_available_number FROM wms_inventory WHERE company_code='QM' AND sku_sn IN ("+codes+') ORDER BY store_code,sku_sn',INV),
                'channel':rows("SELECT sku_sn,quota_stock,quota_reserved_stock,available_stock,reserved_stock,allocated_stock FROM oms_channel_inventory WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND sku_sn IN ('+codes+') ORDER BY sku_sn',INV)}
    def receive_stock(self,c,sku,quantity,key):
        assert sku in [c['sku'],c['target_sku']] and sku.startswith('EXAUTO_')
        body={'relationSn':'TEST-EXAUTO-'+c['case_id']+'-'+key,'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':sku,'batchCode':'EXAUTO_'+c['case_id']+'_'+key,'zpActualNumber':quantity,'zpAvailableNumber':quantity,'transactionPrice':2.5}}
        proc=subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(body).encode(),capture_output=True,timeout=45);assert proc.returncode==0 and json.loads(proc.stdout)['code']==200,'TEST inventory receipt failed'
    def prepare(self,key):
        if key not in self.data['cases']:
            stamp=self.data['run_id'];revision=self.data.get('fixture_revisions',{}).get(key,0);tag='EXAUTO_'+stamp+key+('R'+str(revision) if revision else '');tid='86'+stamp+key
            self.data['cases'][key]={'key':key,'title':TITLES[key],'case_id':stamp+key+(str(revision) if revision else ''),'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':tag+'A','target_sku':tag+'B','channel_name':'[换货自动验收'+key+'] '+TITLES[key]+' '+stamp+(' 补测'+str(revision) if revision else ''),'seller':tag,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':tag+'V','quantity':10,'package_count':1}],'quantity':4 if key in ['02','03'] else 2,'claims':[],'refunds':{}}
            self.save()
        c=self.data['cases'][key];helper=self.helper(c);fixture.MANIFEST=helper.path
        if not c.get('prepared'):
            template='VC0001'
            if key in ['02','03','09']:
                template=sql("SELECT v.wms_simulation_code FROM wms_simulation_store_info v JOIN wms_connection x ON x.id=v.connection_id AND x.company_code=UPPER(v.company_code) WHERE UPPER(v.company_code)='QM' AND v.status=2 AND v.outbound_mode=1 AND x.environment='TEST' AND x.shared_return_waybill=1 AND x.endpoint='http://wms-simulator:8080/qimen/gateway' ORDER BY v.id LIMIT 1",SUPPLY);assert template,'No trusted TEST common-return connection'
            base_api=fixture.api
            def scoped_fixture_api(path,credential,payload=None,post=False):
                if path=='/supplychain/simulationStore/list?pageSize=100':path+='&wmsSimulationCode='+urllib.parse.quote(template)
                return base_api(path,credential,payload,post)
            fixture.api=scoped_fixture_api
            try:fixture.prepare_case(c,self.token,order_quantity=c['quantity'],template_code=template,before_conversion=self.financial_source if key=='03' else None)
            finally:fixture.api=base_api
            c['prepared']=True;self.save()
        if not c.get('target_ready'):
            sample=self.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(c['target_sku']),SUPPLY):self.api('/goods/info',{'skuSn':c['target_sku'],'goodsSn':c['target_sku'],'goodsName':'[换货自动验收'+key+'] 换出B','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30})
            if key!='07':self.receive_stock(c,c['target_sku'],4 if key=='02' else 2,'INITIAL-B')
            sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(c['target_sku'])+',10,'+quote(json.dumps([c['warehouses'][0]['code']]))+')',INV);c['target_ready']=True;self.save()
        if key!='05' and not c.get('original_shipped'):
            original=helper.dispatch_ship(helper.allocate()['shipments'][0]);c['original_shipment_id']=original['id'];c['original_shipment_sn']=original['shipment_sn'];c['original_shipment_line_id']=original['lines'][0]['id'];c['original_shipped']=True;self.save()
        if not c.get('authorized'):helper.authorize();self.save()
        if not c.get('before_target'):c['before_target']=self.snapshot(c);self.save()
        return c
    def financial_source(self,c,credential):
        # Complete buyer-paid goods fields are required for automated refund limits.
        if sql("SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND platform_tid='+quote(c['platform_tid']),ORDER):return
        trade={'tid':c['platform_tid'],'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':c['seller'],'created':c['created'],'modified':c['created'],'pay_time':c['created'],'payment':'10.00','post_fee':'0.00','discount_fee':'0.00','receiver_name':'换货自动验收','receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'验收测试区','receiver_address':'本地TEST地址，无实际配送','orders':{'order':[{'oid':c['platform_oid'],'outer_sku_id':c['sku'],'num':c['quantity'],'payment':'10.00','divide_order_fee':'10.00','price':'2.50','discount_fee':'0.00','adjust_fee':'0.00','title':'[换货退款共同退回验收] 原购商品'}]}}
        wire=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False)
        values=[c['platform_tid'],'WAIT_SELLER_SEND_GOODS','fixed',c['seller'],c['created'],c['created'],c['created'],hashlib.sha256(wire.encode()).hexdigest(),wire,'WAITING']
        sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',"+str(c['channel_id'])+','+','.join(map(quote,values))+')',ORDER)
    def configure(self,c):
        if c.get('flow_configured'):
            if c['key']=='03' and not c.get('refund_configured'):self.configure_refund(c)
            return
        old=self.api('/order/exchanges/flow-configs/'+str(c['channel_id']));p={**old,'enabled':1,'quantity_limit':4,'return_store':c['warehouses'][0]['code'],'platform_address_id':'991','return_address':'[自动换货验收] 本地TEST接收地址','return_contact':'TEST-EXCHANGE-AUTO','collect_seconds':120 if c['key'] in ['02','03'] else 0,'auto_return_merge':1 if c['key'] in ['02','03','09'] else 0,'auto_mixed_return_merge':1 if c['key']=='03' else 0}
        for flag in ['auto_review','auto_internal_review','auto_unshipped','auto_continue_review','auto_agree','auto_return_create','auto_return_dispatch','auto_qc','auto_receive','auto_allocate','auto_carrier','auto_dispatch','auto_consign','auto_stop','auto_allocate_receipt','allow_original_recipient']:p[flag]=1
        p['mappings']=[{'source_sku':c['sku'],'platform_target':c['target_sku'],'target_sku':c['target_sku'],'no_surcharge_basis':'TEST-MERCHANT-'+c['case_id']+' 同价规格调整明确免补差授权'},{'source_sku':c['target_sku'],'platform_target':c['sku'],'target_sku':c['sku'],'no_surcharge_basis':'TEST-MERCHANT-'+c['case_id']+' 连续换货明确免补差授权'}]
        self.api('/order/exchanges/flow-configs/'+str(c['channel_id']),p);c['flow_configured']=True;self.save()
        if c['key']=='03':self.configure_refund(c)
    def e(self,claim):return self.api('/order/exchanges/'+str(claim['id']))
    def flow(self,claim):return self.api('/order/exchanges/'+str(claim['id'])+'/automatic-flow')
    def publish(self,c,claim,payload=None):
        if payload is not None:
            sql("INSERT INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',"+str(c['channel_id'])+','+quote(claim['dispute_id'])+','+quote(json.dumps(payload,ensure_ascii=False))+','+quote(payload['modified'])+') ON DUPLICATE KEY UPDATE payload=VALUES(payload),modified=VALUES(modified)',CHANNEL)
        claim['source_sequence']=claim.get('source_sequence',0)+1;self.save()
        return self.api('/order/exchanges/sync',{'channel_id':c['channel_id'],'dispute_ids':claim['dispute_id'],'request_key':'EXAUTO_SOURCE_'+claim['dispute_id']+'_'+str(claim['source_sequence'])})
    def create_claim(self,c,index=1,internal=False):
        if len(c['claims'])>=index:return c['claims'][index-1]
        target=c['sku'] if c['key']=='06' and index==2 else c['target_sku'];quantity=2
        if internal:
            o=self.api('/order/orders/business/'+str(c['order_id']));line=next(l for l in o['lines'] if l['sku_sn']==c['sku'] and l['business_type']=='SALE')
            body={'order_id':c['order_id'],'order_line_id':line['id'],'platform_oid':c['platform_oid'],'quantity':quantity,'target_sku':target,'fulfillment_mode':'UNSHIPPED' if c['key']=='05' else 'RETURN','source_phase':'DURING_SALE' if c['key']=='05' else 'AFTER_SALE','return_store':c['warehouses'][0]['code'],'return_address':'TEST收货地址','return_contact':'TEST-EXCHANGE','automation_authorized':True,'authorization_reference':'TEST-MERCHANT-'+c['case_id'],'replacement_recipient':{'name':'自动换货验收','mobile':'TEST-MASKED','province':'北京市','city':'北京市','area':'测试区','detailAddress':'本地TEST地址，无实际配送'},'reason':'[商家业务输入] 买家明确申请换品，按店铺免补差规则自动处理','request_key':'EXAUTO_INTERNAL_'+c['case_id']}
            if c['key']!='05':body['source_shipment_line_id']=c['original_shipment_line_id']
            claim={'id':self.api('/order/exchanges',body)['id'],'internal':True,'target_sku':target,'index':index};c['claims'].append(claim);self.save();return claim
        now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');revision=self.data.get('fixture_revisions',{}).get(c['key'],0);claim={'dispute_id':'87'+self.data['run_id']+c['key']+(str(revision) if revision else '')+str(index),'target_sku':target,'index':index}
        payload={'dispute_id':claim['dispute_id'],'biz_order_id':c['platform_oid'],'num':quantity,'status':'1','good_status':'已发货','modified':now,'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'实际原发货商品','exchange_sku':'UNKNOWN-UNMAPPED-TARGET' if c['key']=='08' else target,'speed_exchange':0,'refund_phase':'aftersale'}
        c['claims'].append(claim);self.save();self.publish(c,claim,payload);return claim
    def find_claim(self,c,claim):
        if claim.get('id'):return self.e(claim)
        rows=[row for row in self.api('/order/exchanges?number='+claim['dispute_id'])['rows'] if int(row['channel_id'])==int(c['channel_id'])]
        if not rows:return None
        assert len(rows)==1;claim['id']=rows[0]['id'];claim['exchange_sn']=rows[0]['exchange_sn'];self.save();return self.e(claim)
    def buyer_returned(self,c,claim,e):
        if c['key']=='11' or claim.get('internal') or claim.get('buyer_returned') or e['platform_status']!='2':return
        if c['key']=='03' and not c['refunds'].get('mixed',{}).get('buyer_sent'):return
        payload=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));payload.update(status='3',buyer_logistic_name='SF',buyer_logistic_no='EXAUTORET_'+c['case_id'])
        payload['modified']=max(datetime.now(),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S');self.publish(c,claim,payload);claim['buyer_returned']=True;self.save()
    def merchant_logistics(self,c,claim,e):
        if not claim.get('internal') or c['key']=='05' or claim.get('logistics_recorded'):return
        self.api('/order/exchanges/'+str(e['id'])+'/return-logistics',{'control_version':e['control_version'],'items':[{'carrier':'SF','tracking_no':'EXAUTORET_'+c['case_id']}],'reason':'商家提供明确的本地TEST返程运单'});claim['logistics_recorded']=True;self.save()
    def warehouse_return(self,c,claim,e):
        if c['key']=='05' or claim.get('returned'):return False
        common=None
        if c['key'] in ['02','03','09']:
            common=self.api('/order/exchanges/'+str(e['id'])+'/shared-return')
            doc=common.get('document')
            if not doc or doc['dispatch_state']!='ACCEPTED':return False
            if c['key']=='03' and len(common['members'])<2:return False
            marker='COMMON_'+str(doc['id'])
            if marker in c.get('received_markers',[]):claim['returned']=True;self.save();return False
            rows=[{'sku':m['sku_sn'],'quantity':m['quantity']-m['accepted_quantity'],'line_no':m['id']} for m in common['members']]
            actual=e['return_result'];sn=doc['return_sn'];external=doc['external_order']
        else:
            actual=e.get('return_result') or {}
            if actual.get('dispatch_state')!='ACCEPTED':return False
            marker='HH_'+str(e['id']);sn=e['return_sn'];external=actual['external_order'];rows=[{'sku':e['original_sku'],'quantity':e['requested_quantity'],'line_no':1}]
        connection=next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id']==actual['connection_id']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':sn,'returnOrderId':external,'warehouseCode':actual['external_warehouse'],'ownerCode':actual['external_owner'],'outBizCode':'EXAUTO_'+c['case_id']+'_'+marker,'orderType':'THRK' if common else 'HHRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
        lines=ET.SubElement(root,'orderLines')
        for i,row in enumerate(rows):
            if row['quantity']<=0:continue
            item=ET.SubElement(lines,'orderLine')
            values={'orderLineNo':row['line_no'],'itemCode':row['sku'],'ownerCode':actual['external_owner'],'actualQty':row['quantity'],'batchCode':'EXAUTORET_'+c['case_id']+'_'+str(i),'inventoryType':'ZP'}
            if c['key']=='10':
                for k,v in values.items():
                    if k not in ['batchCode','inventoryType']:ET.SubElement(item,k).text=str(v)
                batches=ET.SubElement(item,'batchs')
                for grade in ['ZP','CC']:
                    batch=ET.SubElement(batches,'batch')
                    for k,v in {'batchCode':'EXAUTO_'+c['case_id']+'_'+grade,'inventoryType':grade,'actualQty':1}.items():ET.SubElement(batch,k).text=str(v)
            else:
                for k,v in values.items():ET.SubElement(item,k).text=str(v)
        wire=ET.tostring(root,encoding='unicode');(OUT/(c['key']+'-'+marker+'.xml')).write_text(wire,'utf-8')
        c.setdefault('stock_phases',{})[marker+'_BEFORE_RETURN']=self.snapshot(c);self.save()
        total=sum(r['quantity'] for r in rows)
        event={'customer':connection['customer_id'],'owner':actual['external_owner'],'warehouse':actual['external_warehouse'],'sn':sn,'external':external,'quantity':total,'hash':hashlib.sha256(wire.encode()).hexdigest()}
        script="""import json,sqlite3,datetime
p=json.loads(PAYLOAD)
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE');key=(p['customer'],'return',p['owner'],p['warehouse'],p['sn'])
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row and row['external_id']==p['external'] and row['quantity']==p['quantity'] and row['status'] in ('ACCEPT','FULFILLED')
 db.execute("UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
 db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'return',?,'EXAUTO_RETURN',?,?)",(p['customer'],p['sn'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
""".replace('PAYLOAD',repr(json.dumps(event)))
        proc=subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30);assert proc.returncode==0,'TEST return identity mismatch: '+proc.stderr.decode(errors='replace')[-300:]
        def send():
            params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret']);req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        if c['key']=='09':
            with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(lambda _:send(),range(3)))
        else:send()
        c.setdefault('stock_phases',{})[marker+'_AFTER_RETURN']=self.snapshot(c)
        c.setdefault('received_markers',[]).append(marker);claim['returned']=True;claim['return_sn']=sn;claim['return_sent_at']=datetime.now().isoformat();self.save();return True
    def actual_outbound(self,c,e):
        if c['key']=='10' or c['key']=='12' and not c.get('continuation_requested'):return
        if c['key']=='05':ships=self.api('/order/orders/business/'+str(c['order_id']))['shipments']
        else:ships=[s for s in e['shipments'] if int(s['fulfillment_round'])==int(e['fulfillment_round'])]
        for hint in ships:
            if hint['status'] in ['VOIDED','CLOSED']:continue
            ship=self.api('/order/orders/shipments/'+str(hint['id']))
            if ship['wms_dispatch_state']!='ACCEPTED' or ship['shipped_quantity']==ship['quantity']:continue
            assert ship['warehouse_target']['environment']=='TEST'
            c.setdefault('stock_phases',{})[ship['shipment_sn']+'_BEFORE_OUTBOUND']=self.snapshot(c);self.save()
            proc=subprocess.run([sys.executable,str(ROOT/'docker/local/simulate_qimen_delivery.py'),ship['shipment_sn']],capture_output=True,text=True,encoding='utf-8',timeout=150);assert proc.returncode==0,'Signed TEST outbound failed: '+proc.stdout[-300:]+proc.stderr[-300:]
            c.setdefault('stock_phases',{})[ship['shipment_sn']+'_AFTER_OUTBOUND']=self.snapshot(c);self.save()
    def buyer_confirmed(self,c,claim,e):
        if claim.get('internal') or e['platform_status']!='13' or claim.get('buyer_confirmed'):return
        claim['seller_shipped_not_final']=e['state']!='COMPLETED';assert claim['seller_shipped_not_final'],'Seller shipped was incorrectly marked exchange success'
        payload=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));payload['status']='5';payload['modified']=max(datetime.now(),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S');self.publish(c,claim,payload);claim['buyer_confirmed']=True;self.save()
    def special(self,c,claim,e,flow):
        key=c['key']
        if key=='11' and c.get('platform_closed'):
            if e['platform_status'] not in ['4','14'] and not c.get('closure_reconfirmed'):
                payload=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));payload['status']='4';payload['modified']=max(datetime.now(),datetime.strptime(payload['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S');self.publish(c,claim,payload);c['closure_reconfirmed']=True;self.save()
            return True
        if key=='07' and flow['task'].get('last_code')=='STOCK_SHORTAGE' and not c.get('restocked'):
            c['shortage_evidence']={'exchange':copy.deepcopy(e),'flow':flow,'stock':self.snapshot(c)};self.receive_stock(c,c['target_sku'],2,'RESTOCK');c['restocked']=True;self.save()
        if key=='11' and e['platform_status']=='2' and not c.get('platform_closed'):
            payload=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));payload['status']='4';payload['modified']=max(datetime.now(),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S');c['before_close']=self.snapshot(c);self.publish(c,claim,payload);c['platform_closed']=True;self.save();return True
        if key=='12' and not c.get('continuation_requested'):
            if not c.get('old_round_stopped'):
                ships=[s for s in e['shipments'] if int(s['fulfillment_round'])==1]
                if ships and ships[0]['wms_dispatch_state']=='ACCEPTED':
                    self.api('/order/exchanges/'+str(e['id'])+'/control/request',{'control_version':e['control_version'],'kind':'OUTBOUND','document_id':ships[0]['id'],'mode':'TERMINATE','reason':'[商家业务输入] 本轮原仓单终止，另行授权继续换出剩余数量'});c['old_round_stopped']=True;self.save()
                return bool(ships)
            if e['fulfillment_hold'] and e['control_state']=='HOLD':
                preview=self.api('/order/exchanges/'+str(e['id'])+'/continuation-preview');self.api('/order/exchanges/'+str(e['id'])+'/continuations',{'control_version':preview['control_version'],'quantity':preview['quantity'],'replacement_recipient':preview['recipient'],'recipient_confirmed':True,'buyer_confirmed':True,'refund_confirmed':True,'automation_authorized':True,'authorization_reference':'TEST-CONTINUE-'+c['case_id'],'reason':'[商家业务输入] 买家确认本HH剩余继续换出','evidence':'TEST-NO-REFUND-'+c['case_id']});c['continuation_requested']=True;self.save()
            return True
        return False
    def finish(self,c):
        if any(not claim.get('id') for claim in c['claims']):return False
        states=[self.e(claim) for claim in c['claims']];flows=[self.flow(claim) for claim in c['claims']]
        if c['key'] in ['08','10']:
            if flows[0]['task'].get('state')!='MANUAL':return False
            if c['key']=='10' and (not c['claims'][0].get('returned') or int((states[0].get('return_result') or {}).get('posted_quantity',0))!=2):return False
            if c['key']=='08':assert states[0].get('approved_version') is None and ('SKU' in flows[0]['task'].get('last_message','') or '映射' in flows[0]['task'].get('last_message',''))
            if c['key']=='10':assert states[0]['return_qc']!='PASSED' and not states[0].get('fulfillment_line_id')
        elif c['key']=='11':
            if states[0]['state'] not in ['CANCELED','CLOSED']:return False
        else:
            if any(e['state']!='COMPLETED' for e in states):return False
            if c['key']=='06' and len(states)<2:return False
            if c['key']=='02' and len(states)<2:return False
            if c['key']=='03' and not c.get('refund_completed'):return False
        for e in states:
            if c['key']!='08':assert e['review_by']=='自动换货规则',(c['key'],e['review_by'])
            assert not any(x['action']=='REVIEW' and x['actor']!='自动换货规则' for x in e['events']),'Manual target review detected'
        self.verify_stock(c);c['final_exchanges']=states;c['automatic_flows']=flows;c['result']='PASS_EXPECTED_MANUAL' if c['key'] in ['08','10'] else 'PASS';c['completed_at']=datetime.now().isoformat();self.save();dump(OUT/(c['key']+'-evidence.json'),c);print(c['key'],c['title'],c['result'],[e['exchange_sn'] for e in states],flush=True);return True
    def run(self,keys):
        for key in keys:self.prepare(key)
        for key in keys:
            c=self.data['cases'][key];self.configure(c)
            if not c['claims']:self.create_claim(c,internal=key in ['04','05','12'])
        until=time.monotonic()+1800
        while True:
            pending=[]
            for key in keys:
                c=self.data['cases'][key]
                if c.get('result','').startswith('PASS'):continue
                pending.append(key)
                try:
                    first=self.find_claim(c,c['claims'][0])
                    if not first:continue
                    if key=='02' and first.get('approved_version') and len(c['claims'])==1:self.create_claim(c,2)
                    if key=='06' and first['state']=='COMPLETED' and len(c['claims'])==1:self.create_claim(c,2)
                    if key=='03':self.refund_progress(c,first)
                    for claim in c['claims']:
                        e=self.find_claim(c,claim)
                        if not e:continue
                        flow=self.flow(claim);c['last_progress']={'at':datetime.now().isoformat(),'exchange_sn':e['exchange_sn'],'state':e['state'],'node':flow['task'].get('node'),'code':flow['task'].get('last_code'),'message':flow['task'].get('last_message')};self.save()
                        if key=='08':continue
                        if self.special(c,claim,e,flow):continue
                        self.buyer_returned(c,claim,e);self.merchant_logistics(c,claim,e);self.warehouse_return(c,claim,e)
                        e=self.e(claim);self.actual_outbound(c,e);e=self.e(claim);self.buyer_confirmed(c,claim,e)
                    self.finish(c)
                except Exception as ex:c['last_error']=str(ex);self.save();print(key,'WAIT',str(ex)[:240],flush=True)
            if not pending:return
            if time.monotonic()>until:raise RuntimeError('Automation acceptance still pending: '+','.join(pending))
            print('Pending',','.join(pending),flush=True);time.sleep(3)
    def verify_stock(self,c):
        snap=self.snapshot(c);key=c['key'];expected_a=8 if key in ['06','08','11'] else 10;expected_b=2 if key in ['06','08','10','11'] else 0
        company={row[0]:list(map(int,row[1:])) for row in snap['company']}
        assert company[c['sku']][0]==expected_a,(key,'A total',company)
        assert company[c['target_sku']][0]==expected_b,(key,'B total',company)
        for sku,values in company.items():
            total,available,reserved,allocated,frozen=values;assert total==available+reserved+frozen,(key,'logical conservation',sku,values);assert allocated<=reserved,(key,'allocated subset of reservation',sku,values)
            expected_reserved=2 if key=='10' and sku==c['target_sku'] else 0;assert reserved==expected_reserved and allocated==0,(key,'remaining occupation',sku,values)
            assert frozen==(1 if key=='10' and sku==c['sku'] else 0),(key,'quality frozen',sku,values)
        for row in snap['warehouse']:
            _,sku,zp,available,locked,cp,cp_available=row;assert int(zp)==int(available)+int(locked) and int(locked)==0,(key,'physical conservation',row)
            if sku==c['sku']:assert int(zp)==(9 if key=='10' else expected_a) and int(cp)==(1 if key=='10' else 0),(key,'A grade',row)
            else:assert int(zp)==expected_b and int(cp)==0,(key,'B physical',row)
        for row in snap['channel']:
            sku,quota,quota_reserved,available,reserved,allocated=row;assert int(quota_reserved)==(2 if key=='10' and sku==c['target_sku'] else 0),(key,'quota reservation',row)
        c['final_stock']=snap;c['stock_verified']=True
        if key in ['02','03','09']:
            c['shared_return']=self.api('/order/exchanges/'+str(c['claims'][0]['id'])+'/shared-return');d=c['shared_return'];assert d['bound'] and d['document']['quantity']==sum(int(m['quantity']) for m in d['members']);assert sql("SELECT COUNT(*) FROM wms_shared_return_document WHERE company_code='QM' AND parcel_id="+str(d['parcel']['id']),SUPPLY)=='1';assert datetime.fromisoformat(str(d['document']['create_time']).replace('T',' '))>=datetime.fromisoformat(str(d['parcel']['dispatch_after']).replace('T',' '))
            if key in ['02','03']:assert datetime.fromisoformat(str(d['parcel']['dispatch_after']).replace('T',' '))>=max(datetime.fromisoformat(str(m['create_time']).replace('T',' ')) for m in d['members'])+timedelta(seconds=120)
        assert self.data['prior_refund_baseline']==sql("SELECT id,state,review_state,goods_state,funds_state,refund_fee FROM oms_refund WHERE company_code='QM' AND id IN (118,119,121,122,126,127) ORDER BY id",ORDER),'Prior retained refund evidence changed'
    def configure_refund(self,c):
        if c.get('refund_configured'):return
        # Reuse only the configuration helper; no refund observe/retry/manual approval helpers.
        helper=refund_fixture.Run.__new__(refund_fixture.Run);helper.token=self.token;helper.data=self.data;helper.save=self.save
        def shared_shop_api(path,body=None,method=None):
            if path in ['/channel/platform/shops/'+str(c['channel_id'])+'/binding','/channel/platform/shops/'+str(c['channel_id'])+'/simulate'] and body is not None:
                assert self.api('/order/exchanges/capability/'+str(c['channel_id']))['simulation']
                if 'app_id' in body:assert sql("SELECT app_id FROM channel_platform_binding WHERE UPPER(company_code)='QM' AND channel_id="+str(c['channel_id']),CHANNEL)==str(body['app_id'])
                return {'reused_owned_simulation_authorization':True}
            return self.api(path,body,method)
        helper.api=shared_shop_api;helper.configure(c)
        flow=self.api('/order/refunds/flow-configs/'+str(c['channel_id']))
        self.api('/order/refunds/flow-configs/'+str(c['channel_id']),{**flow,'enabled':1,'auto_return_review':1,'return_review_limit':'500.00','auto_agree_return':1,'seller_address_id':'1','auto_full_cancel':0,'auto_shipped_cancel':0,'small_refund_enabled':0,'auto_review':1,'auto_pay':1,'auto_finish_return':1,'funds_owner':'OMS','payment_limit':'500.00'})
        policy=self.api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy'];self.api('/order/refunds/parcel-configs/'+str(c['channel_id']),{**policy,'allow_exchange_merge':1,'merge_mode':'AUTO','scope':'SAME_ORDER','allocation_mode':'STRICT','allow_partial_allocation':0,'collect_seconds':120});c['refund_configured']=True;self.save()
    def publish_refund(self,c,ref,payload):
        wire=json.dumps({'refund_get_response':{'refund':payload}},ensure_ascii=False);rid=ref['refund_id'];values=[rid,c['platform_tid'],c['platform_oid'],payload['status'],c['seller'],payload['created'],payload['modified'],payload['modified'],wire]
        sql('INSERT INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(map(quote,values))+') ON DUPLICATE KEY UPDATE status=VALUES(status),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified),jdp_response=VALUES(jdp_response)',refund_fixture.SOURCE)
        sql("INSERT INTO channel_refund_sim(company_code,channel_id,refund_id,payload,modified) VALUES ('qm',"+str(c['channel_id'])+','+quote(rid)+','+quote(wire)+','+quote(payload['modified'])+') ON DUPLICATE KEY UPDATE payload=VALUES(payload),modified=VALUES(modified)',CHANNEL)
        ref['source_sequence']=ref.get('source_sequence',0)+1;self.save();self.api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[rid],'request_key':'EXAUTO_REFUND_'+rid+'_'+str(ref['source_sequence'])})
    def refund_progress(self,c,e):
        if not e.get('approved_version'):return
        if 'mixed' not in c['refunds']:
            now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');ref={'refund_id':'88'+self.data['run_id']+c['key']+str(self.data.get('fixture_revisions',{}).get(c['key'],1)),'amount':'5.00'};c['refunds']['mixed']=ref;self.save()
            self.publish_refund(c,ref,{'refund_id':ref['refund_id'],'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':'WAIT_SELLER_AGREE','refund_fee':'5.00','num':2,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','good_status':'BUYER_RECEIVED','refund_phase':'aftersale','refund_version':'1','reason':'[自动换货合包验收] 原购买4件，2件换货、另2件退货退款5元','modified':now,'created':now})
        ref=c['refunds']['mixed']
        if not ref.get('id'):
            rows=self.api('/order/refunds/platform?number='+ref['refund_id'])['rows'];found=next((row for row in rows if row['refund_id']==ref['refund_id']),{})
            if not found.get('business_refund_id'):return
            ref['id']=found['business_refund_id'];self.save()
        current=self.api('/order/refunds/business/'+str(ref['id']));c['last_refund_progress']={'id':ref['id'],'state':current['state'],'review_state':current['review_state'],'funds_state':current['funds_state']};self.save()
        payload=json.loads(sql("SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL))['refund_get_response']['refund']
        if payload['status']=='WAIT_BUYER_RETURN_GOODS' and not ref.get('buyer_sent'):
            payload.update(status='WAIT_SELLER_CONFIRM_GOODS',company_name='SF',sid='EXAUTORET_'+c['case_id'],refund_version=str(int(payload['refund_version'])+1),modified=max(datetime.now(),datetime.strptime(payload['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'));self.publish_refund(c,ref,payload);ref['buyer_sent']=True;self.save()
        if current['state']=='COMPLETED':
            assert current['review_by']=='自动退款规则' or str(current.get('review_by','')).startswith('自动'),current.get('review_by');c['refund_completed']=True;c['final_refund']=current;self.save()

if __name__=='__main__':
    run=Run();keys=sys.argv[1:] or list(TITLES)
    if keys[0]=='--prepare':
        for key in keys[1:] or list(TITLES):run.prepare(key)
    else:run.run(keys)
