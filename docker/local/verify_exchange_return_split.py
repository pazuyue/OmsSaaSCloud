"""Case 24: completed A3->B3, then B1 refund and B1->C1 in one return parcel.

No target manual review, allocation, dispatch, receipt reconciliation or flow retry.
Only new fixtures, buyer source events and signed TEST warehouse callbacks.
"""
import copy
import hashlib
import json
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta
from pathlib import Path
import verify_exchange_partial_continuous as pc
import verify_exchange_automation as base
from verify_exchange_partial_continuous import ROOT,ORDER,INV,SUPPLY,CHANNEL,sql,quote

OUT=ROOT/'outputs/exchange-return-split'
pc.OUT=OUT;base.OUT=OUT
pc.TITLES['24']='A三件换B后，一件退货退款、一件换C';pc.SPECS['24']=(3,3,1)

class Run(pc.Run):
    def protected(self):
        result=super().protected();p=ROOT/'outputs/exchange-partial-continuous/manifest.json'
        result['partial_manifest']=hashlib.sha256(p.read_bytes()).hexdigest()
        result['partial_stock']=sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn LIKE 'EXAUTO_PC_20261025112353%' ORDER BY sku_sn",INV)
        return result
    def prepare(self,key='24'):
        template=sql("SELECT v.wms_simulation_code FROM wms_simulation_store_info v JOIN wms_connection x ON x.id=v.connection_id AND UPPER(x.company_code)=UPPER(v.company_code) WHERE UPPER(v.company_code)='QM' AND v.status=2 AND v.outbound_mode=1 AND x.environment='TEST' AND x.shared_return_waybill=1 AND x.endpoint='http://wms-simulator:8080/qimen/gateway' ORDER BY v.id LIMIT 1",SUPPLY);assert template
        return super().prepare('24',template)
    def mixed_rules(self,c):
        if c.get('mixed_rules'):return
        p=self.api('/order/exchanges/flow-configs/'+str(c['channel_id']));self.api('/order/exchanges/flow-configs/'+str(c['channel_id']),{**p,'auto_return_merge':1,'auto_mixed_return_merge':1,'auto_allocate_receipt':1,'collect_seconds':120})
        base.Run.configure_refund(self,c);c['mixed_rules']=True;self.save()
    def start_refund(self,c):
        if c['refunds'].get('split'):return
        now=datetime.now().strftime('%Y-%m-%d %H:%M:%S');ref={'refund_id':'88'+self.data['run_id']+'241','amount':'100.00'};c['refunds']['split']=ref;self.save()
        self.publish_refund(c,ref,{'refund_id':ref['refund_id'],'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':'WAIT_SELLER_AGREE','refund_fee':'100.00','num':1,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','good_status':'BUYER_RECEIVED','refund_phase':'aftersale','refund_version':'1','reason':'[自动验收24] 原A三件全部换B后，B一件退货退款100元，另一件换C','modified':now,'created':now})
    def refund_progress(self,c):
        ref=c['refunds'].get('split')
        if not ref:return None
        if not ref.get('id'):
            rows=self.api('/order/refunds/platform?number='+ref['refund_id'])['rows'];found=next((x for x in rows if int(x['channel_id'])==int(c['channel_id']) and x['refund_id']==ref['refund_id']),{})
            if not found.get('business_refund_id'):return None
            ref['id']=found['business_refund_id'];self.save()
        b=self.api('/order/refunds/business/'+str(ref['id']));c['last_refund_progress']={'id':b['id'],'state':b['state'],'review_state':b['review_state'],'goods_state':b['goods_state'],'funds_state':b['funds_state'],'automatic_flow':b.get('automatic_flow'),'goods_tasks':b.get('goods_tasks',[])};self.save()
        p=json.loads(sql("SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id']),CHANNEL))['refund_get_response']['refund']
        second_ready=len(c['claims'])==2 and c['claims'][1].get('id') and self.e(c['claims'][1]).get('approved_version')
        if p['status']=='WAIT_BUYER_RETURN_GOODS' and not ref.get('buyer_sent') and second_ready:
            p.update(status='WAIT_SELLER_CONFIRM_GOODS',company_name='SF',sid='PC24RET_'+c['case_id'],refund_version=str(int(p['refund_version'])+1),modified=max(datetime.now(),datetime.strptime(p['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'));self.publish_refund(c,ref,p);ref['buyer_sent']=True;self.save()
        return b
    def second_return(self,c,claim,e):
        if not c['refunds'].get('split',{}).get('buyer_sent') or claim.get('buyer_returned') or e['platform_status']!='2':return
        p=json.loads(sql("SELECT payload FROM channel_exchange_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND dispute_id='+quote(claim['dispute_id']),CHANNEL));p.update(status='3',buyer_logistic_name='SF',buyer_logistic_no='PC24RET_'+c['case_id'],modified=max(datetime.now(),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'));self.publish(c,claim,p);claim['buyer_returned']=True;self.save()
    def common_receipt(self,c,claim,e):
        if c.get('common_received'):return
        d=self.api('/order/exchanges/'+str(e['id'])+'/shared-return');c['shared_return']=d;self.save()
        if not d.get('document') or d['document']['dispatch_state']!='ACCEPTED' or len(d['members'])!=2:return
        assert {x['owner_kind'] for x in d['members']}=={'REFUND','EXCHANGE'} and sum(int(x['quantity']) for x in d['members'])==2
        actual=e['return_result'];conn,secret=self.connection(actual['connection_id']);doc=d['document'];root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':doc['return_sn'],'returnOrderId':doc['external_order'],'warehouseCode':actual['external_warehouse'],'ownerCode':actual['external_owner'],'outBizCode':'PC24_COMMON_'+str(doc['id']),'orderType':'THRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
        lines=ET.SubElement(root,'orderLines')
        for m in d['members']:
            assert m['sku_sn']==c['target_sku'] and int(m['quantity'])==1
            line=ET.SubElement(lines,'orderLine')
            for k,v in {'orderLineNo':m['id'],'itemCode':m['sku_sn'],'ownerCode':actual['external_owner'],'actualQty':1,'inventoryType':'ZP','batchCode':'PC24_'+str(m['id'])}.items():ET.SubElement(line,k).text=str(v)
        wire=ET.tostring(root,encoding='unicode');(OUT/'24-common-return.xml').write_text(wire,'utf-8');self.checkpoint(c,'COMMON_RETURN_BEFORE')
        self.simulator(conn,'return',doc['return_sn'],doc['external_order'],actual['external_owner'],actual['external_warehouse'],2,'FULFILLED','PC24_COMMON',wire);self.send(conn,secret,'taobao.qimen.returnorder.confirm',wire);c['common_received']=True;self.checkpoint(c,'COMMON_RETURN_AFTER_CALLBACK')
    def finish24(self,c,b):
        if len(c['claims'])!=2 or not b or b['state']!='COMPLETED':return False
        es=[self.e(x) for x in c['claims']]
        if any(x['state']!='COMPLETED' for x in es):return False
        snap=self.snapshot(c);values={x[0]:list(map(int,x[1:])) for x in snap['company']}
        for sku,total in [(c['sku'],10),(c['target_sku'],9),(c['third_sku'],9)]:assert values[sku]==[total,total,0,0,0],(sku,values[sku])
        for r in snap['warehouse']:assert int(r[2])==values[r[1]][0] and r[2]==r[3] and all(int(v)==0 for v in r[4:])
        assert all(x['review_by']=='自动换货规则' for x in es) and b['review_by'].startswith('自动')
        assert es[1]['original_sku']==c['target_sku'] and int(es[1]['requested_quantity'])==1
        d=self.api('/order/exchanges/'+str(es[1]['id'])+'/shared-return');assert len(d['members'])==2 and all(int(m['accepted_quantity'])==1 and int(m['posted_quantity'])==1 for m in d['members'])
        assert float(b['refund_fee'])==100 and b['funds_state']=='SUCCESS'
        def rows(q,db):return [x.split('\t') for x in sql(q,db).splitlines()]
        scope=','.join(str(e['id']) for e in es);actual_b=es[1]['source_shipment_line_id'];audit={
            'exchange_returns':rows("SELECT exchange_id,return_sn,original_shipment_sn,sku_sn,quantity,accepted_quantity,posted_quantity,unit_cost FROM wms_exchange_return WHERE company_code='QM' AND exchange_id IN ("+scope+') ORDER BY exchange_id,generation',SUPPLY),
            'refund_returns':rows("SELECT plan_id,return_sn,original_shipment_sn,sku_sn,quantity,accepted_quantity,posted_quantity,unit_cost FROM wms_refund_return WHERE company_code='QM' AND refund_id="+str(b['id'])+' ORDER BY id',SUPPLY),
            'refund_plans':rows("SELECT id,shipment_line_id,shipment_sn,sku_sn,quantity,accepted_quantity,posted_quantity,state FROM oms_refund_return_plan WHERE company_code='QM' AND refund_id="+str(b['id'])+' ORDER BY id',ORDER),
            'source':rows("SELECT sl.id,sl.sku_sn,sl.shipped_quantity,s.shipment_sn FROM oms_order_shipment_line sl JOIN oms_order_shipment s ON s.company_code=sl.company_code AND s.id=sl.shipment_id WHERE sl.company_code='QM' AND sl.id="+str(actual_b),ORDER),
            'shipments':rows("SELECT s.shipment_sn,s.business_type,COALESCE(s.exchange_id,0),sl.sku_sn,sl.quantity,sl.shipped_quantity,sl.cancelled_quantity FROM oms_order_shipment s JOIN oms_order_shipment_line sl ON sl.company_code=s.company_code AND sl.shipment_id=s.id WHERE s.company_code='QM' AND s.order_id="+str(c['order_id'])+' ORDER BY s.id,sl.id',ORDER),
            'claims':rows("SELECT 'EXCHANGE',exchange_sn,requested_quantity FROM oms_exchange WHERE company_code='QM' AND source_shipment_line_id="+str(actual_b)+" AND approved_version IS NOT NULL AND state NOT IN ('REJECTED','CANCELED') UNION ALL SELECT 'REFUND',CAST(refund_id AS CHAR),quantity FROM oms_refund_return_plan WHERE company_code='QM' AND shipment_line_id="+str(actual_b),ORDER),
            'demand':rows("SELECT platform_tid,sku_sn,demand_quantity,cancelled_quantity,reserved_quantity,outbound_quantity,status FROM oms_order_stock_demand WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' ORDER BY id',INV)}
        assert len(audit['exchange_returns'])==2 and len(audit['refund_returns'])==1 and len(audit['refund_plans'])==1
        assert audit['refund_plans'][0][1]==str(actual_b) and audit['refund_returns'][0][3]==c['target_sku']
        assert [int(x[5]) for x in audit['shipments']]==[3,3,1] and all(int(x[6])==0 for x in audit['shipments'])
        assert sum(int(x[2]) for x in audit['claims'])==2 and len(audit['claims'])==2 and int(audit['source'][0][2])==3
        for row in [audit['exchange_returns'][1],audit['refund_returns'][0]]:assert row[2]==audit['source'][0][3] and row[3]==c['target_sku'] and list(map(int,row[4:7]))==[1,1,1] and float(row[7])==2.5
        assert sql("SELECT COUNT(*) FROM wms_shared_return_document WHERE company_code='QM' AND parcel_id="+str(d['parcel']['id']),SUPPLY)=='1'
        last_member=max(datetime.fromisoformat(str(x['create_time']).replace('T',' ')) for x in d['members']);due=datetime.fromisoformat(str(d['parcel']['dispatch_after']).replace('T',' '));created=datetime.fromisoformat(str(d['document']['create_time']).replace('T',' '));assert created>=due>=last_member+timedelta(seconds=120)
        assert int(d['document']['accepted_quantity'])==2 and int(d['document']['posted_quantity'])==2 and int(d['document']['pending_quantity'])==0
        assert self.protected()==self.data['protected_baseline']
        if c.get('last_error'):c.setdefault('driver_observations',[]).append({'message':c.pop('last_error'),'scope':'Transient observer result before return document existed; business workflow was not manually advanced'})
        self.data['boundary']='SIMULATION platform and signed TEST WMS; original sale is prerequisite; no target manual review, advance, retry, allocation or dispatch'
        c.update(result='PASS',final_stock=snap,final_exchanges=es,automatic_flows=[self.flow(x) for x in c['claims']],final_refund=b,shared_return=d,audit=audit,stock_verified=True,protected_baseline_unchanged=True,completed_at=datetime.now().isoformat());self.save();print('PASS case24: A10 B9 C9; refund100; shared B return 1+1',flush=True);return True
    def run24(self):
        c=self.prepare();self.configure(c)
        if not c['claims']:self.create_claim(c)
        deadline=time.monotonic()+1800
        while not c.get('result'):
            try:
                first=self.find_claim(c,c['claims'][0])
                if first:
                    if first['state']!='COMPLETED':
                        self.buyer_returned(c,c['claims'][0],first)
                        if (first.get('return_result') or {}).get('dispatch_state')=='ACCEPTED' and not c['claims'][0].get('return_parts'):self.return_event(c,c['claims'][0],first,3,True,1)
                        self.shipping(c,c['claims'][0],self.e(c['claims'][0]));self.buyer_confirmed(c,c['claims'][0],self.e(c['claims'][0]))
                    else:
                        if not c.get('first_completed'):c['first_completed']=copy.deepcopy(first);self.checkpoint(c,'FIRST_EXCHANGE_COMPLETED')
                        self.mixed_rules(c)
                        self.start_refund(c);b=self.refund_progress(c)
                        if b and b['review_state']=='APPROVED' and len(c['claims'])==1:self.create_claim(c,2)
                        if len(c['claims'])==2:
                            claim=c['claims'][1];e=self.find_claim(c,claim)
                            if e and e.get('approved_version'):
                                self.second_return(c,claim,e);e=self.e(claim);self.common_receipt(c,claim,e);self.shipping(c,claim,self.e(claim));self.buyer_confirmed(c,claim,self.e(claim));self.finish24(c,b)
                c['last_progress']={'at':datetime.now().isoformat(),'exchanges':[{'id':e['id'],'state':e['state'],'platform_status':e['platform_status'],'flow':self.flow(x)['task']} for x in c['claims'] if x.get('id') for e in [self.e(x)]]};self.save()
            except Exception as failure:c['last_error']=str(failure);self.save();print('WAIT',str(failure)[:350],flush=True)
            if c.get('result'):return
            if time.monotonic()>deadline:raise RuntimeError('Case24 pending; evidence preserved')
            print('Pending case24',flush=True);time.sleep(3)

if __name__=='__main__':
    if sys.argv[1:]==['--new-attempt'] and (OUT/'manifest.json').exists():
        previous=json.loads((OUT/'manifest.json').read_text('utf-8'));archive=OUT/'attempts'/previous['run_id'];archive.mkdir(parents=True,exist_ok=True)
        for path in OUT.iterdir():
            if path.is_file():shutil.copy2(path,archive/path.name)
        fresh={'run_id':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':{},'prior_attempts':previous.get('prior_attempts',[])+[{'run_id':previous['run_id'],'manifest':str(archive/'manifest.json'),'result':'FAILED_SOURCE_SELECTION','retained':True}]}
        (OUT/'manifest.json').write_text(json.dumps(fresh,ensure_ascii=False,indent=2),'utf-8')
    elif sys.argv[1:]:raise SystemExit('Usage: verify_exchange_return_split.py [--new-attempt]')
    Run().run24()
