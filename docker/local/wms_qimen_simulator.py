"""Persistent local Qimen fixture. Acceptance never emits receipts or changes OMS stock."""
import argparse
import hashlib
import hmac
import json
import re
import sqlite3
from contextlib import closing
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def sign(params, body, secret):
    values=''.join(k+v for k,v in sorted(params.items()) if k!='sign' and v)
    return hashlib.md5((secret+values+body+secret).encode('utf8')).hexdigest().upper()


def reply(success,code,message,fields=None,head=None):
    root=ET.Element('response')
    for key,value in [('flag','success' if success else 'failure'),('code',code),('message',message)]:
        ET.SubElement(root,key).text=str(value)
    target=ET.SubElement(root,head) if head else root
    for key,value in (fields or {}).items():ET.SubElement(target,key).text=str(value)
    return ET.tostring(root,encoding='unicode')


class Simulator:
    def __init__(self,database,credentials):
        self.database=str(database);self.credentials=credentials
        with closing(sqlite3.connect(self.database)) as db, db:
            db.executescript('''CREATE TABLE IF NOT EXISTS documents(customer TEXT,kind TEXT,owner TEXT,warehouse TEXT,sn TEXT,external_id TEXT,body_hash TEXT,quantity INTEGER,status TEXT,created_at TEXT,PRIMARY KEY(customer,kind,owner,warehouse,sn));
                CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,customer TEXT,kind TEXT,sn TEXT,action TEXT,body_hash TEXT,created_at TEXT);
                CREATE TABLE IF NOT EXISTS inventory_stock(customer TEXT,owner TEXT,warehouse TEXT,sku TEXT,batch TEXT,quality TEXT,unfrozen INTEGER,frozen INTEGER,PRIMARY KEY(customer,owner,warehouse,sku,batch,quality));''')

    def handle(self,params,body):
        selected=next((c for c in self.credentials if c['app_key']==params.get('app_key') and c['customer_id']==params.get('customerId')),None)
        if not selected or not hmac.compare_digest(params.get('sign',''),sign(params,body,selected['secret'])):
            return reply(False,'SIGN_INVALID','模拟接口签名校验失败')
        try:
            sent=datetime.strptime(params['timestamp'],'%Y-%m-%d %H:%M:%S').replace(tzinfo=timezone(timedelta(hours=8))).timestamp()
            if abs(time.time()-sent)>300:raise ValueError('模拟请求时间已过期')
            if params.get('v')!='2.0' or params.get('format')!='xml':raise ValueError('模拟接口仅支持2.0 XML')
            if re.search(r'<!DOCTYPE|<!ENTITY',body,re.I):raise ValueError('模拟报文不允许DTD或实体')
            root=ET.fromstring(body)
            if root.tag!='request':raise ValueError('模拟请求必须使用request根节点')
            method=params.get('method','');prefix='taobao.qimen.'
            if not method.startswith(prefix):raise ValueError('不支持的模拟方法')
            method=method[len(prefix):]
            if method in ('inventory.query','stock.query'):
                criteria=root.findall('criteriaList/criteria') if method=='inventory.query' else [root]
                if not 1<=len(criteria)<=100:raise ValueError('库存查询条件无效')
                records=[]
                with closing(sqlite3.connect(self.database)) as db:
                    db.row_factory=sqlite3.Row
                    for criterion in criteria:
                        owner=criterion.findtext('ownerCode','');warehouse=criterion.findtext('warehouseCode','');sku=criterion.findtext('itemCode','');batch=criterion.findtext('batchCode','')
                        if not owner or not warehouse or not sku:raise ValueError('库存范围缺失')
                        rows=db.execute('SELECT * FROM inventory_stock WHERE customer=? AND owner=? AND warehouse=? AND sku=?'+(' AND batch=?' if batch else '')+' ORDER BY batch,quality',(selected['customer_id'],owner,warehouse,sku,*([batch] if batch else []))).fetchall()
                        records.extend(rows)
                total=len(records)
                if method=='stock.query':
                    page=int(root.findtext('page','0'));size=int(root.findtext('pageSize','0'))
                    if page<1 or not 1<=size<=100:raise ValueError('库存分页无效')
                    records=records[(page-1)*size:page*size]
                result=ET.fromstring(reply(True,'0','TEST库存快照，仅返回显式配置的仓库事实',{'totalCount':total} if method=='stock.query' else {}));items=ET.SubElement(result,'items')
                for row in records:
                    item=ET.SubElement(items,'item')
                    for key,val in [('warehouseCode',row['warehouse']),('ownerCode',row['owner']),('itemCode',row['sku']),('batchCode',row['batch']),('inventoryType',row['quality']),('quantity',row['unfrozen']),('lockQuantity',row['frozen'])]:ET.SubElement(item,key).text=str(val)
                return ET.tostring(result,encoding='unicode')
            if method not in ('stockout.create','stockout.query','deliveryorder.create','deliveryorder.query','entryorder.create','entryorder.query','returnorder.create','returnorder.query','order.cancel','storeprocess.create'):raise ValueError('不支持的模拟方法')
            cancel=method=='order.cancel';process=method=='storeprocess.create' or cancel and root.findtext('orderType')=='CNJG';kind='process' if process else 'return' if method.startswith('return') or cancel and root.findtext('orderType') in ('HHRK','THRK') else 'entry' if method.startswith('entry') or cancel and root.findtext('orderType') in ('CGRK','QTRK','DBRK') else 'delivery'
            create=method.endswith('.create');head=root if process else root.find(kind+'Order') if create else root
            if head is None:raise ValueError('模拟请求缺少单据头')
            number_field=kind+'OrderCode' if create or kind in ('entry','return') and not cancel else 'orderCode'
            sn=head.findtext(number_field,'') or head.findtext('deliveryOrderCode','') or head.findtext('orderCode','');owner=head.findtext('ownerCode','');warehouse=head.findtext('warehouseCode','')
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',sn) or not owner or not warehouse:raise ValueError('模拟请求缺少有效单号、货主或仓库')
            total=0
            if method.startswith('stockout') or cancel and root.findtext('orderType')=='CGTH':
                if create and head.findtext('orderType') not in ('CGTH','DBCK'):raise ValueError('采购退仓须使用CGTH类型')
            if create and process:
                if root.findtext('serviceType') not in ('1','2') or root.findtext('orderType')!='CNJG':raise ValueError('加工类型无效')
                for group in ['materialitems','productitems']:
                    items=root.findall(group+'/item')
                    if not 1<=len(items)<=500:raise ValueError('加工投入产出明细缺失')
                    for item in items:
                        if not item.findtext('itemCode') or not 0<int(item.findtext('quantity','0'))<=100000000:raise ValueError('加工SKU或数量无效')
                total=int(root.findtext('planQty','0'))
                if total<=0:raise ValueError('加工计划数量无效')
            if create and not process:
                lines=root.findall('orderLines/orderLine')
                if not 1<=len(lines)<=500:raise ValueError('模拟请求商品明细须为1至500行')
                seen=set()
                for line in lines:
                    number=line.findtext('orderLineNo','');quantity=int(line.findtext('planQty','0'))
                    if not number or number in seen or not 0<quantity<=100000000 or not line.findtext('itemCode') or kind!='return' and not line.findtext('batchCode'):raise ValueError('模拟商品明细行号、SKU、批次或数量无效')
                    seen.add(number);total+=quantity
            digest=hashlib.sha256(body.encode('utf8')).hexdigest();customer=selected['customer_id'];key=(customer,kind,owner,warehouse,sn);now=datetime.now(timezone.utc).isoformat()
            with closing(sqlite3.connect(self.database,timeout=10)) as db, db:
                db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
                old=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
                db.execute('INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,?,?,?,?,?)',(customer,kind,sn,'CANCEL' if cancel else 'CREATE' if create else 'QUERY',digest,now))
                if create and old is None:
                    external='SIMPROC' if process else 'SIMOUT' if kind=='delivery' else 'SIMIN';external+='_'+uuid.uuid4().hex[:20]
                    db.execute('INSERT INTO documents VALUES (?,?,?,?,?,?,?,?,?,?)',(*key,external,digest,total,'ACCEPT',now))
                    old=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
                elif create and old['body_hash']!=digest:return reply(False,'DUPLICATE','模拟单号已存在且商品快照不同',{kind+'OrderId':old['external_id']})
                if old is None:return reply(False,'ORDER_NOT_FOUND','本地模拟仓库查无此单')
                provided=(root.findtext(kind+'OrderId','') or root.findtext('orderId','')) if kind in ('entry','return') and not create and not cancel else (root.findtext('deliveryOrderId','') or root.findtext('orderId','')) if not create else ''
                if provided and provided!=old['external_id']:return reply(False,'IDENTITY_MISMATCH','模拟仓库单号不一致')
                state='CANCELED' if cancel else old['status']
                if cancel:db.execute('UPDATE documents SET status=? WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',(state,*key))
                fields={kind+'OrderId':old['external_id']}
                if not create:fields.update({kind+'OrderCode':sn,'ownerCode':owner,'warehouseCode':warehouse,'status':state})
                return reply(True,'0','本地模拟仓库已受理' if create else '本地模拟仓库查询成功',fields,'deliveryOrder' if kind=='delivery' and not create and not cancel else None)
        except (ValueError,KeyError,ET.ParseError):return reply(False,'INVALID_REQUEST','模拟请求格式、时间或商品明细无效')


def handler(simulator):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass  # Signing parameters and customer data must not enter console logs.
        def do_GET(self):
            if self.path!='/health':self.send_error(404);return
            body=b'{"status":"UP","simulation":true}';self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def do_POST(self):
            parsed=urlparse(self.path)
            if parsed.path!='/qimen/gateway':self.send_error(404);return
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=1024*1024:self.send_error(413);return
                body=self.rfile.read(length).decode('utf8');raw=parse_qs(parsed.query,keep_blank_values=True)
                if any(len(v)!=1 for v in raw.values()):raise ValueError()
                response=simulator.handle({k:v[0] for k,v in raw.items()},body).encode('utf8')
            except (ValueError,UnicodeError):response=reply(False,'INVALID_REQUEST','模拟请求编码或参数无效').encode('utf8')
            self.send_response(200);self.send_header('Content-Type','application/xml;charset=UTF-8');self.send_header('Content-Length',str(len(response)));self.end_headers();self.wfile.write(response)
    return Handler


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);parser.add_argument('--db',required=True);parser.add_argument('--port',type=int,default=8080);args=parser.parse_args()
    Path(args.db).parent.mkdir(parents=True,exist_ok=True)
    simulator=Simulator(args.db,json.loads(Path(args.config).read_text(encoding='utf8')))
    print('Local Qimen simulator ready; receipt callbacks and stock posting are disabled.',flush=True)
    ThreadingHTTPServer(('0.0.0.0',args.port),handler(simulator)).serve_forever()

if __name__=='__main__':main()
