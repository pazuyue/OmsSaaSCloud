"""Retained cases 16-23: partial returns and continuous exchanges.

Only new isolated fixtures, buyer events, explicit merchant authorizations and
signed TEST warehouse facts. No target manual review/QC/allocation/dispatch,
receipt reconciliation, flow advance/recheck or business-state SQL updates.
Original sales fulfillment is a documented fixture prerequisite.
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
from datetime import datetime, timedelta
from pathlib import Path
import verify_exchange_automation as base
from verify_exchange_automation import ROOT, ORDER, INV, SUPPLY, CHANNEL, sql, quote, dump
from verify_goods_workspace_ui import BASE, login, request
from wms_qimen_simulator import sign
import verify_allocation_rules_v2 as fixture

OUT = ROOT / 'outputs/exchange-partial-continuous'
TITLES = {'16':'原购部分换货后再全部换出', '17':'收到换出商品后只换其中一件',
          '18':'分两批退回收齐后连续换货', '19':'退回未收齐禁止继续换出',
          '20':'首轮部分实发后续发剩余', '21':'三轮部分实发累计不超换',
          '22':'部分实发后退款冲突阻止续发', '23':'部分换货后实际来源不唯一'}
SPECS = {'16':(3,2,2), '17':(2,2,1), '18':(2,2,2), '19':(2,2,0),
         '20':(3,3,0), '21':(5,5,0), '22':(3,3,0), '23':(3,1,1)}
base.OUT = OUT
base.TITLES.update(TITLES)


class Run(base.Run):
    def __init__(self):
        OUT.mkdir(parents=True,exist_ok=True);self.path=OUT/'manifest.json';self.token=login()
        self.data=json.loads(self.path.read_text('utf-8')) if self.path.exists() else {'run_id':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':{},'boundary':'SIMULATION platform and signed TEST WMS; only merchant creation/cutoff/continuation inputs; no target manual execution'}
        if 'protected_baseline' not in self.data:
            self.data['protected_baseline']=self.protected()
        self.save()
    def protected(self):
        prior=ROOT/'outputs/exchange-automation/manifest.json'
        return {'prior_manifest_hash':hashlib.sha256(prior.read_bytes()).hexdigest(),
                'stock':sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'EXAUTO_20261024235906%' OR sku_sn IN ('RR20261024185730C08A','RR20261024185730C08G')) ORDER BY sku_sn",INV),
                'refunds':sql("SELECT id,state,review_state,goods_state,funds_state,refund_fee FROM oms_refund WHERE company_code='QM' AND id IN (118,119,121,122,126,127) ORDER BY id",ORDER),
                'queues':sql('SELECT business_type,desired_mode,concurrency,prefetch,app_rate,shop_rate,revision FROM sys_mq_queue_policy ORDER BY business_type','ry-cloud'),
                'job':sql("SELECT status FROM sys_job WHERE invoke_target='exchangeTask.scanAll()'",'ry-cloud')}
    def api(self,path,body=None,method=None):
        if body is not None and (path.startswith('/order/exchanges/') and any(p in path for p in ['/review','/execute','/advance','/qc','/allocate','/automatic-flow/recheck','/return-documents/']) or path.startswith('/order/orders/shipments/') and any(p in path for p in ['/dispatch','/match-carrier','/continue-dispatch','/reconcile-receipts'])):
            raise AssertionError('Manual target execution prohibited: '+path)
        return super().api(path,body,method)
    def snapshot(self,c):
        codes=','.join(map(quote,[c['sku'],c['target_sku'],c['third_sku']]))
        def rows(q,db):return [x.split('\t') for x in sql(q,db).splitlines()]
        return {'at':datetime.now().isoformat(),'company':rows("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+codes+') ORDER BY sku_sn',INV),
                'warehouse':rows("SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,cp_actual_number,cp_available_number FROM wms_inventory WHERE company_code='QM' AND sku_sn IN ("+codes+') ORDER BY sku_sn',INV),
                'channel':rows("SELECT sku_sn,quota_stock,quota_reserved_stock,available_stock,reserved_stock,allocated_stock FROM oms_channel_inventory WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND sku_sn IN ('+codes+') ORDER BY sku_sn',INV)}
    def checkpoint(self,c,label):
        c.setdefault('stock_phases',{})[label]=self.snapshot(c);self.save()
    def receive_stock(self,c,sku,quantity,key):
        assert sku in [c['sku'],c['target_sku'],c['third_sku']] and sku.startswith('EXAUTO_PC_')
        body={'relationSn':'TEST-PC-'+c['case_id']+'-'+key,'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':sku,'batchCode':'PC_'+c['case_id']+'_'+key,'zpActualNumber':quantity,'zpAvailableNumber':quantity,'transactionPrice':2.5}}
        r=subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(body).encode(),capture_output=True,timeout=45)
        assert r.returncode==0 and json.loads(r.stdout)['code']==200,'Fixture receipt failed'
    def complete_trade(self,c,credential):
        if sql("SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' AND platform_tid='+quote(c['platform_tid']),ORDER):return
        amount=str(100*c['quantity']);trade={'tid':c['platform_tid'],'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':c['seller'],'created':c['created'],'modified':c['created'],'pay_time':c['created'],'payment':amount,'post_fee':'0.00','discount_fee':'0.00','receiver_name':'部分连续换货验收','receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'验收测试区','receiver_address':'本地TEST地址，无实际配送','orders':{'order':[{'oid':c['platform_oid'],'outer_sku_id':c['sku'],'num':c['quantity'],'payment':amount,'divide_order_fee':amount,'price':'100.00','discount_fee':'0.00','adjust_fee':'0.00','title':'[部分连续换货验收] 原购A'}]}}
        wire=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False)
        values=[c['platform_tid'],'WAIT_SELLER_SEND_GOODS','fixed',c['seller'],c['created'],c['created'],c['created'],hashlib.sha256(wire.encode()).hexdigest(),wire,'WAITING']
        sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',"+str(c['channel_id'])+','+','.join(map(quote,values))+')',ORDER)
    def prepare(self,key,template_code='VC0001'):
        if key not in self.data['cases']:
            stamp=self.data['run_id'];tag='EXAUTO_PC_'+stamp+key;tid='86'+stamp+key
            self.data['cases'][key]={'key':key,'title':TITLES[key],'case_id':stamp+key,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':tag+'A','target_sku':tag+'B','third_sku':tag+'C','channel_name':'[部分连续换货'+key+'] '+TITLES[key]+' '+stamp,'seller':tag,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':tag+'V','quantity':10,'package_count':1}],'quantity':SPECS[key][0],'claims':[],'refunds':{}}
            self.save()
        c=self.data['cases'][key];helper=self.helper(c);fixture.MANIFEST=helper.path
        if not c.get('prepared'):
            original_api=fixture.api
            def scoped_api(path,credential,payload=None,post=False):
                if path=='/supplychain/simulationStore/list?pageSize=100':path+='&wmsSimulationCode='+urllib.parse.quote(template_code)
                return original_api(path,credential,payload,post)
            fixture.api=scoped_api
            try:fixture.prepare_case(c,self.token,order_quantity=c['quantity'],before_conversion=self.complete_trade,template_code=template_code)
            finally:fixture.api=original_api
            c['prepared']=True;self.save()
        for label,sku in [('B',c['target_sku']),('C',c['third_sku'])]:
            if label in c.setdefault('products_ready',[]):continue
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(sku),SUPPLY):
                sample=self.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
                self.api('/goods/info',{'skuSn':sku,'goodsSn':sku,'goodsName':'[部分连续换货'+key+'] '+label,'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':100,'validity':30})
            self.receive_stock(c,sku,10,'INITIAL-'+label)
            sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(sku)+',10,'+quote(json.dumps([c['warehouses'][0]['code']]))+')',INV)
            c['products_ready'].append(label);self.save()
        if not c.get('original_shipped'):
            original=helper.dispatch_ship(helper.allocate()['shipments'][0]);c.update(original_shipment_id=original['id'],original_shipment_sn=original['shipment_sn'],original_shipment_line_id=original['lines'][0]['id'],original_shipped=True);self.save()
        if not c.get('authorized'):helper.authorize();self.save()
        if not c.get('before_target'):c['before_target']=self.snapshot(c);self.save()
        return c
    def configure(self,c):
        if c.get('flow_configured'):return
        old=self.api('/order/exchanges/flow-configs/'+str(c['channel_id']));p={**old,'enabled':1,'quantity_limit':5,'return_store':c['warehouses'][0]['code'],'platform_address_id':'991','return_address':'本地TEST退回地址','return_contact':'TEST-PC','collect_seconds':0,'return_wait_days':1}
        for flag in ['auto_review','auto_internal_review','auto_continue_review','auto_agree','auto_return_create','auto_return_dispatch','auto_qc','auto_receive','auto_allocate','auto_carrier','auto_dispatch','auto_consign','auto_stop','allow_original_recipient']:p[flag]=1
        p['mappings']=[{'source_sku':src,'platform_target':dst,'target_sku':dst,'no_surcharge_basis':'TEST-PC-'+c['case_id']+' 商家明确同价免补差授权'} for src,dst in [(c['sku'],c['target_sku']),(c['target_sku'],c['third_sku']),(c['sku'],c['third_sku'])]]
        self.api('/order/exchanges/flow-configs/'+str(c['channel_id']),p);c['flow_configured']=True;self.save()
    def create_claim(self,c,index=1):
        if len(c['claims'])>=index:return c['claims'][index-1]
        quantity=SPECS[c['key']][index];target=c['target_sku'] if index==1 else c['third_sku'];internal=c['key'] in ['20','21','22']
        if internal:
            order=self.api('/order/orders/business/'+str(c['order_id']));line=next(l for l in order['lines'] if l['business_type']=='SALE' and l['sku_sn']==c['sku'])
            body={'order_id':c['order_id'],'order_line_id':line['id'],'platform_oid':c['platform_oid'],'quantity':quantity,'target_sku':target,'fulfillment_mode':'RETURN','source_phase':'AFTER_SALE','return_store':c['warehouses'][0]['code'],'return_address':'本地TEST退回地址','return_contact':'TEST-PC','source_shipment_line_id':c['original_shipment_line_id'],'automation_authorized':True,'authorization_reference':'TEST-PC-MERCHANT-'+c['case_id'],'replacement_recipient':{'name':'部分连续换货验收','mobile':'TEST-MASKED','province':'北京市','city':'北京市','area':'验收测试区','detailAddress':'本地TEST地址，无实际配送'},'reason':'[商家业务输入] 明确售后换货及免补差授权','request_key':'PC_INTERNAL_'+c['case_id']}
            claim={'index':index,'quantity':quantity,'target_sku':target,'internal':True,'id':self.api('/order/exchanges',body)['id']};c['claims'].append(claim);self.save();return claim
        now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');claim={'index':index,'quantity':quantity,'target_sku':target,'dispute_id':'87'+self.data['run_id']+c['key']+str(index)}
        c['claims'].append(claim);self.save();self.publish(c,claim,{'dispute_id':claim['dispute_id'],'biz_order_id':c['platform_oid'],'num':quantity,'status':'1','good_status':'已发货','modified':now,'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'按原实际发货来源核对','exchange_sku':target,'speed_exchange':0,'refund_phase':'aftersale'});return claim
    def buyer_returned(self,c,claim,e):
        if claim.get('internal') or claim.get('buyer_returned') or e['platform_status']!='2':return
        p=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));p.update(status='3',buyer_logistic_name='SF',buyer_logistic_no='PCRET_'+c['case_id']+'_'+str(claim['index']),modified=max(datetime.now(),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'));self.publish(c,claim,p);claim['buyer_returned']=True;self.save()
    def connection(self,identity):
        conn=next(x for x in self.api('/supplychain/wmsIntegration/connections') if int(x['id'])==int(identity));assert conn['environment']=='TEST' and conn['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        secret=next(x['secret'] for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==conn['app_key'] and x['customer_id']==conn['customer_id']);return conn,secret
    def send(self,conn,secret,method,wire):
        p={'app_key':conn['app_key'],'customerId':conn['customer_id'],'method':method,'v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};p['sign']=sign(p,wire,secret)
        req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+conn['callback_key']+'?'+urllib.parse.urlencode(p),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as response:reply=ET.fromstring(response.read())
        assert reply.findtext('flag')=='success',reply.findtext('message')
    def simulator(self,c,kind,sn,external,owner,warehouse,total,state,event,wire):
        payload={'customer':c['customer_id'],'kind':kind,'sn':sn,'external':external,'owner':owner,'warehouse':warehouse,'total':total,'state':state,'event':event,'hash':hashlib.sha256(wire.encode()).hexdigest()}
        script="""import json,sqlite3,datetime
p=json.loads(PAYLOAD)
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE');key=(p['customer'],p['kind'],p['owner'],p['warehouse'],p['sn'])
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row and row['external_id']==p['external'] and row['quantity']==p['total'] and row['status']!='CANCELED'
 old=db.execute('SELECT body_hash FROM events WHERE customer=? AND kind=? AND sn=? AND action=?',(p['customer'],p['kind'],p['sn'],p['event'])).fetchall()
 assert not old or all(x['body_hash']==p['hash'] for x in old)
 if not old:
  db.execute('UPDATE documents SET status=? WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',(p['state'],*key))
  db.execute('INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,?,?,?,?,?)',(p['customer'],p['kind'],p['sn'],p['event'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
""".replace('PAYLOAD',repr(json.dumps(payload)))
        r=subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30);assert r.returncode==0,'TEST WMS identity mismatch: '+r.stderr.decode(errors='replace')[-200:]
    def return_event(self,c,claim,e,quantity,final,part):
        marker='RETURN_'+str(e['id'])+'_'+str(part)
        if marker in c.get('sent_events',[]):return
        actual=e['return_result'];conn,secret=self.connection(actual['connection_id']);sn=e['return_sn'];root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':sn,'returnOrderId':actual['external_order'],'warehouseCode':actual['external_warehouse'],'ownerCode':actual['external_owner'],'outBizCode':'PC_'+marker,'orderType':'HHRK','confirmType':'0' if final else '1'}.items():ET.SubElement(head,k).text=str(v)
        line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in {'orderLineNo':1,'itemCode':e['original_sku'],'ownerCode':actual['external_owner'],'actualQty':quantity,'inventoryType':'ZP','batchCode':'PC_'+c['case_id']+'_'+str(claim['index'])+'_'+str(part)}.items():ET.SubElement(line,k).text=str(v)
        wire=ET.tostring(root,encoding='unicode');(OUT/(marker+'.xml')).write_text(wire,'utf-8');self.checkpoint(c,marker+'_BEFORE')
        self.simulator(conn,'return',sn,actual['external_order'],actual['external_owner'],actual['external_warehouse'],int(e['requested_quantity']),'FULFILLED' if final else 'PARTFULFILLED','PC_'+marker,wire)
        self.send(conn,secret,'taobao.qimen.returnorder.confirm',wire);c.setdefault('sent_events',[]).append(marker);claim['return_parts']=part;claim['return_final_sent']=final;self.checkpoint(c,marker+'_AFTER_CALLBACK')
    def outbound_event(self,c,ship,quantity,final):
        marker='OUT_'+str(ship['id'])+'_'+str(int(ship['shipped_quantity'])+quantity)
        if marker in c.get('sent_events',[]):return
        route=ship['warehouse_target'];conn,secret=self.connection(route['connection_id']);plans=ship['dispatch_lines'];actual={int(x['id']):x for x in ship['lines']};remaining=quantity;chosen=[]
        for plan in plans:
            q=min(remaining,int(plan['quantity'])-int(actual[int(plan['id'])]['shipped_quantity']));remaining-=q
            if q:chosen.append({**plan,'actual':q})
        assert remaining==0 and chosen
        root=ET.Element('request');head=ET.SubElement(root,'deliveryOrder')
        for k,v in {'deliveryOrderCode':ship['shipment_sn'],'deliveryOrderId':ship['wms_external_order'],'warehouseCode':route['external_warehouse'],'ownerCode':route['external_owner'],'status':'DELIVERED' if final else 'PARTDELIVERED','confirmType':'0' if final else '1','outBizCode':'PC_'+marker,'totalOrderLines':len(chosen),'orderConfirmTime':datetime.now().strftime('%Y-%m-%d %H:%M:%S')}.items():ET.SubElement(head,k).text=str(v)
        lines=ET.SubElement(root,'orderLines');totals={}
        for plan in chosen:
            line=ET.SubElement(lines,'orderLine')
            for k,v in {'orderLineNo':plan['id'],'itemCode':plan['sku_sn'],'ownerCode':route['external_owner'],'batchCode':plan['batch_code'],'inventoryType':'ZP','actualQty':plan['actual']}.items():ET.SubElement(line,k).text=str(v)
            totals[plan['sku_sn']]=totals.get(plan['sku_sn'],0)+plan['actual']
        pack=ET.SubElement(ET.SubElement(root,'packages'),'package')
        for k,v in {'packageCode':'PCPKG_'+marker,'expressCode':'TEST-PC-'+marker,'logisticsCode':ship['warehouse_carrier_code'],'logisticsName':ship['carrier_name']}.items():ET.SubElement(pack,k).text=str(v)
        items=ET.SubElement(pack,'items')
        for sku,q in totals.items():item=ET.SubElement(items,'item');ET.SubElement(item,'itemCode').text=sku;ET.SubElement(item,'quantity').text=str(q)
        wire=ET.tostring(root,encoding='unicode');(OUT/(marker+'.xml')).write_text(wire,'utf-8');self.checkpoint(c,marker+'_BEFORE')
        self.simulator(conn,'delivery',ship['shipment_sn'],ship['wms_external_order'],route['external_owner'],route['external_warehouse'],int(ship['quantity']),'DELIVERED' if final else 'PARTDELIVERED','PC_'+marker,wire)
        self.send(conn,secret,'taobao.qimen.deliveryorder.confirm',wire);c.setdefault('sent_events',[]).append(marker);self.checkpoint(c,marker+'_AFTER_CALLBACK')
    def expected_denial(self,path):
        try:self.api(path)
        except AssertionError as failure:return str(failure)
        raise AssertionError('Expected business guard was bypassed: '+path)
    def partial_return(self,c,claim,e):
        actual=e.get('return_result') or {}
        if actual.get('dispatch_state')!='ACCEPTED':return
        split=c['key'] in ['18','19'] and claim['index']==1
        if not claim.get('return_parts'):self.return_event(c,claim,e,1 if split else claim['quantity'],not split,1);return
        if split and int(actual.get('posted_quantity',0))==1 and not c.get('partial_return_observed'):
            assert e['return_qc']!='PASSED' and not e.get('fulfillment_line_id')
            c['partial_return_observed']={'exchange':copy.deepcopy(e),'flow':self.flow(claim),'stock':self.snapshot(c),'continuation_denial':self.expected_denial('/order/exchanges/'+str(e['id'])+'/continuation-preview')};self.save()
        if c['key']=='18' and split and c.get('partial_return_observed') and not claim.get('return_final_sent'):self.return_event(c,claim,e,1,True,2)
    def continue_input(self,c,e,preview):
        return {'control_version':preview['control_version'],'quantity':preview['quantity'],'replacement_recipient':preview['recipient'],'recipient_confirmed':True,'buyer_confirmed':True,'refund_confirmed':True,'automation_authorized':True,'authorization_reference':'TEST-PC-CONTINUE-'+c['case_id']+'-'+str(int(e['fulfillment_round'])+1),'reason':'[商家业务输入] 买家确认只继续尚未实际换出的全部剩余数量','evidence':'TEST-PC-MERCHANT-'+c['case_id']}
    def shipping(self,c,claim,e):
        special=c['key'] in ['20','21','22'];round_no=int(e['fulfillment_round']);stop=special and (round_no==1 or c['key']=='21' and round_no==2)
        ships=[x for x in e['shipments'] if int(x['fulfillment_round'])==round_no and x['status'] not in ['VOIDED','CLOSED']]
        if not ships:return
        for hint in ships:
            ship=self.api('/order/orders/shipments/'+str(hint['id']))
            if ship['wms_dispatch_state']!='ACCEPTED' and not(stop and str(round_no) in c.get('cutoffs',{})):continue
            if stop:
                part=2 if c['key']=='21' and round_no==1 else 1
                if not ship['shipped_quantity']:self.outbound_event(c,ship,part,False);return
                if int(ship['shipped_quantity'])!=part:raise AssertionError('Unexpected partial shipment quantity')
                ops=c.setdefault('cutoffs',{})
                if str(round_no) not in ops:
                    self.checkpoint(c,'ROUND_'+str(round_no)+'_PARTIAL_POSTED');reply=self.api('/order/exchanges/'+str(e['id'])+'/control/request',{'control_version':e['control_version'],'kind':'OUTBOUND','document_id':ship['id'],'mode':'TERMINATE','reason':'[商家业务输入] 终止本轮未发部分，确认后另行授权继续换出'})
                    ops[str(round_no)]={'shipment_id':ship['id'],'posted':part,'at':datetime.now().isoformat()};self.save();return
                e=self.e(claim)
                if e['control_state']!='HOLD':return
                report=self.api('/order/exchanges/'+str(e['id'])+'/controls')
                if any(x['state']!='COMPLETED' for x in report['operations']):return
                if c['key']=='22':self.refund_conflict(c,claim,e);return
                if str(round_no+1) not in c.setdefault('continuations',{}):
                    preview=self.api('/order/exchanges/'+str(e['id'])+'/continuation-preview');assert int(preview['quantity'])==SPECS[c['key']][1]-sum(int(x['shipped_quantity']) for x in e['shipments'])
                    self.checkpoint(c,'ROUND_'+str(round_no)+'_CUTOFF_CONFIRMED');self.api('/order/exchanges/'+str(e['id'])+'/continuations',self.continue_input(c,e,preview));c['continuations'][str(round_no+1)]={'quantity':preview['quantity'],'at':datetime.now().isoformat()};self.save()
                return
            remaining=int(ship['quantity'])-int(ship['shipped_quantity'])-int(ship.get('cancelled_quantity',0))
            if remaining:self.outbound_event(c,ship,remaining,True)
    def refund_conflict(self,c,claim,e):
        # Both refund and order facts enter normal durable source intake.
        source=next(x for x in self.api('/order/orders/sources') if x['database_name']=='refund_acceptance_source' and x['enabled'])
        if not c.get('conflict_refund_source'):
            configs=self.api('/order/refunds/configs')['configs'];old=next((x for x in configs if int(x['channel_id'])==int(c['channel_id'])),{})
            self.api('/order/refunds/configs',{'channel_id':c['channel_id'],'source_key':source['source_key'],'seller_nick':c['seller'],'initial_time':c['created'],'revision':old.get('revision',0),'enabled':1,'auto_convert':1,'interval_seconds':10})
            now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');ref={'refund_id':'88'+self.data['run_id']+c['key']+'1','amount':'100.00'};c['refunds']['conflict']=ref
            self.publish_refund(c,ref,{'refund_id':ref['refund_id'],'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':'WAIT_SELLER_AGREE','refund_fee':'100.00','num':1,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','good_status':'BUYER_RECEIVED','refund_phase':'aftersale','refund_version':'1','reason':'[部分续发验收] 继续换出前新增冲突退货退款，待核对实际货物范围','modified':now,'created':now})
            c['conflict_refund_source']=True;self.save();return
        ref=c['refunds']['conflict']
        if not ref.get('id'):
            rows=self.api('/order/refunds/platform?number='+ref['refund_id'])['rows'];found=next((x for x in rows if int(x['channel_id'])==int(c['channel_id']) and x['refund_id']==ref['refund_id']),{})
            if not found.get('business_refund_id'):return
            ref['id']=found['business_refund_id'];self.save()
        if not c.get('conflict_order_source'):
            config=next(x for x in self.api('/order/orders/configs') if int(x['channel_id'])==int(c['channel_id']))
            self.api('/order/orders/configs',{**config,'channel_id':c['channel_id'],'source_key':source['source_key'],'seller_nick':c['seller'],'confirm_seller_mapping':1,'reset_cursor':1,'enabled':0,'auto_convert':0,'review_mode':'MANUAL','manual_match_review':1,'allowed_trade_types':['fixed'],'allow_public_stock':1,'interval_seconds':30,'initial_time':c['created']})
            raw=json.loads(sql("SELECT raw_payload FROM oms_platform_order WHERE company_code='QM' AND id="+str(c['platform_id']),ORDER));trade=raw['trade_fullinfo_get_response']['trade'];now=datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            trade.update(status='WAIT_BUYER_CONFIRM_GOODS',refund_status='WAIT_SELLER_AGREE',refund_id=ref['refund_id'],modified=now)
            trade['orders']['order'][0].update(status='WAIT_BUYER_CONFIRM_GOODS',refund_status='WAIT_SELLER_AGREE',refund_id=ref['refund_id'])
            wire=json.dumps(raw,ensure_ascii=False);values=[c['platform_tid'],trade['status'],'fixed',c['seller'],c['created'],now,now,wire]
            sql('INSERT INTO jdp_tb_trade(tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(map(quote,values))+') ON DUPLICATE KEY UPDATE status=VALUES(status),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified),jdp_response=VALUES(jdp_response)','refund_acceptance_source')
            c['conflict_order_source']=self.api('/order/orders/backfill',{'mode':'TID','channel_ids':[c['channel_id']],'tids':[c['platform_tid']],'request_key':'PC_REFUND_ORDER_'+c['case_id']});self.save();return
        raw=sql("SELECT raw_payload FROM oms_platform_order WHERE company_code='QM' AND id="+str(c['platform_id']),ORDER)
        if ref['refund_id'] not in raw:return
        c['refund_conflict_input']=True
        if not c.get('refund_conflict_observed'):
            c['refund_conflict_observed']={'denial':self.expected_denial('/order/exchanges/'+str(e['id'])+'/continuation-preview'),'exchange':e,'stock':self.snapshot(c)};self.save()
    def finish(self,c):
        if any(not claim.get('id') for claim in c['claims']):return False
        key=c['key'];states=[self.e(x) for x in c['claims']];flows=[self.flow(x) for x in c['claims']]
        if key=='19':
            if not c.get('partial_return_observed'):return False
            assert states[0]['return_qc']!='PASSED' and not states[0].get('fulfillment_line_id')
            result='PASS_EXPECTED_WAIT'
        elif key=='22':
            if not c.get('refund_conflict_observed'):return False
            assert int(states[0]['fulfillment_round'])==1 and states[0]['fulfillment_hold']
            result='PASS_EXPECTED_BLOCK'
        elif key=='23':
            if len(states)!=2 or flows[-1]['task'].get('state')!='MANUAL':return False
            assert states[-1].get('approved_version') is None and '来源' in flows[-1]['task'].get('last_message','')
            result='PASS_EXPECTED_MANUAL'
        else:
            if any(e['state']!='COMPLETED' for e in states) or key in ['16','17','18'] and len(states)!=2:return False
            result='PASS'
        for e in states:
            if e.get('approved_version'):assert e['review_by']=='自动换货规则'
            assert not any(x['action']=='REVIEW' and x['actor']!='自动换货规则' for x in e['events'])
        deltas={'16':(2,0,-2),'17':(2,-1,-1),'18':(2,0,-2),'19':(1,0,0),'20':(3,-3,0),'21':(5,-5,0),'22':(3,-1,0),'23':(1,-1,0)}[key]
        final=self.snapshot(c);before={x[0]:int(x[1]) for x in c['before_target']['company']}
        for row in final['company']:
            sku,total,available,reserved,allocated,frozen=row;values=list(map(int,row[1:]));assert values[0]==values[1]+values[2]+values[4] and values[3]<=values[2]
            pos=[c['sku'],c['target_sku'],c['third_sku']].index(sku);assert int(total)-before[sku]==deltas[pos],(key,'company delta',row,deltas)
            assert int(reserved)==(2 if key=='19' and sku==c['target_sku'] else 0) and int(allocated)==0 and int(frozen)==0,(key,'occupation',row)
        for row in final['warehouse']:
            _,sku,total,available,locked,bad,bad_available=row;pos=[c['sku'],c['target_sku'],c['third_sku']].index(sku);assert int(total)-before[sku]==deltas[pos] and int(total)==int(available) and int(locked)==0 and int(bad)==0
        assert sql("SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND platform_order_id="+str(c['platform_id']),ORDER)=='1'
        if key in ['16','17','18']:
            assert states[1]['original_sku']==c['target_sku'] and states[1]['source_shipment_line_id']!=c['original_shipment_line_id']
        if key in ['20','21']:
            assert len(states)==1 and int(states[0]['fulfillment_round'])==(3 if key=='21' else 2)
            assert sql("SELECT COUNT(*) FROM wms_exchange_return WHERE company_code='QM' AND exchange_id="+str(states[0]['id']),SUPPLY)=='1'
            assert sum(int(x['shipped_quantity']) for x in states[0]['shipments'])==SPECS[key][1]
        c.update(result=result,final_stock=final,final_exchanges=states,automatic_flows=flows,stock_verified=True,completed_at=datetime.now().isoformat());self.save();dump(OUT/(key+'-evidence.json'),c);print(key,TITLES[key],result,flush=True);return True
    def run(self,keys):
        for key in keys:self.prepare(key)
        for key in keys:
            c=self.data['cases'][key];self.configure(c)
            if not c['claims']:self.create_claim(c)
        until=time.monotonic()+2400
        while True:
            pending=[]
            for key in keys:
                c=self.data['cases'][key]
                if c.get('result','').startswith('PASS'):continue
                pending.append(key)
                try:
                    first=self.find_claim(c,c['claims'][0])
                    if not first:continue
                    if key in ['16','17','18','23'] and first['state']=='COMPLETED' and len(c['claims'])==1:self.create_claim(c,2)
                    for claim in c['claims']:
                        e=self.find_claim(c,claim)
                        if not e:continue
                        flow=self.flow(claim);c['last_progress']={'at':datetime.now().isoformat(),'id':e['id'],'state':e['state'],'round':e['fulfillment_round'],'node':flow['task'].get('node'),'code':flow['task'].get('last_code'),'message':flow['task'].get('last_message')};self.save()
                        if key=='23' and claim['index']==2:continue
                        self.buyer_returned(c,claim,e);self.merchant_logistics(c,claim,e);self.partial_return(c,claim,e);e=self.e(claim);self.shipping(c,claim,e);self.buyer_confirmed(c,claim,self.e(claim))
                    self.finish(c)
                except Exception as failure:c['last_error']=str(failure);self.save();print(key,'WAIT',str(failure)[:300],flush=True)
            if not pending:break
            if time.monotonic()>until:raise RuntimeError('Pending: '+','.join(pending))
            print('Pending',','.join(pending),flush=True);time.sleep(3)
        assert self.protected()==self.data['protected_baseline'],'Prior retained documents, stock or queue settings changed'


if __name__=='__main__':
    r=Run();keys=sys.argv[1:] or list(TITLES)
    if keys[0]=='--prepare':
        for key in keys[1:] or list(TITLES):r.prepare(key)
    else:r.run(keys)
