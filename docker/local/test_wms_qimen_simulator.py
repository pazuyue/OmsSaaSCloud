"""Isolated simulator tests; fixtures never contact business services."""
import concurrent.futures
import sqlite3
from contextlib import closing
import tempfile
import unittest
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from pathlib import Path
from wms_qimen_simulator import Simulator,sign

class SimulatorTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.db=Path(self.temp.name)/'documents.sqlite';self.credentials=[dict(app_key='FIXTURE',customer_id='CUSTOMER',secret='fixture-secret')];self.sim=Simulator(self.db,self.credentials)
        self.create='<request><deliveryOrder><deliveryOrderCode>XS_FIXTURE</deliveryOrderCode><ownerCode>OWNER</ownerCode><warehouseCode>WH</warehouseCode></deliveryOrder><orderLines><orderLine><orderLineNo>1</orderLineNo><itemCode>SKU</itemCode><batchCode>B1</batchCode><planQty>3</planQty></orderLine></orderLines></request>'
        self.query='<request><orderCode>XS_FIXTURE</orderCode><ownerCode>OWNER</ownerCode><warehouseCode>WH</warehouseCode></request>'
    def tearDown(self):self.temp.cleanup()
    def call(self,action,body,sim=None):
        params=dict(app_key='FIXTURE',customerId='CUSTOMER',method='taobao.qimen.'+action,v='2.0',format='xml',sign_method='md5',timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'));params['sign']=sign(params,body,'fixture-secret');return ET.fromstring((sim or self.sim).handle(params,body))
    def test_create_query_and_restart_keep_one_identified_order(self):
        first=self.call('deliveryorder.create',self.create);external=first.findtext('deliveryOrderId');self.assertTrue(external.startswith('SIMOUT_'));self.assertEqual(external,self.call('deliveryorder.create',self.create).findtext('deliveryOrderId'))
        response=self.call('deliveryorder.query',self.query,Simulator(self.db,self.credentials));self.assertEqual('ACCEPT',response.findtext('deliveryOrder/status'));self.assertEqual(external,response.findtext('deliveryOrder/deliveryOrderId'));self.assertEqual('XS_FIXTURE',response.findtext('deliveryOrder/deliveryOrderCode'))
        with closing(sqlite3.connect(self.db)) as db:self.assertEqual((1,3),db.execute('SELECT COUNT(*),SUM(quantity) FROM documents').fetchone())
    def test_missing_query_never_creates_an_order(self):
        self.assertEqual('ORDER_NOT_FOUND',self.call('deliveryorder.query',self.query).findtext('code'))
        with closing(sqlite3.connect(self.db)) as db:self.assertEqual(0,db.execute('SELECT COUNT(*) FROM documents').fetchone()[0])
    def test_customer_warehouse_and_snapshot_mismatches_are_rejected(self):
        self.call('deliveryorder.create',self.create);self.assertEqual('DUPLICATE',self.call('deliveryorder.create',self.create.replace('<planQty>3','<planQty>4')).findtext('code'));self.assertEqual('ORDER_NOT_FOUND',self.call('deliveryorder.query',self.query.replace('WH','OTHER')).findtext('code'))
        with closing(sqlite3.connect(self.db)) as db:self.assertEqual(3,db.execute('SELECT quantity FROM documents').fetchone()[0])
    def test_bad_signature_and_unsupported_methods_do_not_create(self):
        self.assertEqual('SIGN_INVALID',ET.fromstring(self.sim.handle({'app_key':'FIXTURE','customerId':'CUSTOMER','sign':'INVALID'},self.create)).findtext('code'));self.assertEqual('failure',self.call('anything.create',self.create).findtext('flag'))
        with closing(sqlite3.connect(self.db)) as db:self.assertEqual(0,db.execute('SELECT COUNT(*) FROM documents').fetchone()[0])
    def test_concurrent_replay_returns_one_external_id(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:ids=list(pool.map(lambda _:self.call('deliveryorder.create',self.create).findtext('deliveryOrderId'),range(8)))
        self.assertEqual(1,len(set(ids)))
    def test_entry_order_queries_work_without_receipts(self):
        body=self.create.replace('deliveryOrder','entryOrder').replace('XS_FIXTURE','CG_FIXTURE');query=self.query.replace('XS_FIXTURE','CG_FIXTURE');external=self.call('entryorder.create',body).findtext('entryOrderId');self.assertTrue(external.startswith('SIMIN_'));self.assertEqual(external,self.call('entryorder.query',query).findtext('entryOrderId'));self.assertEqual('ACCEPT',self.call('entryorder.query',query).findtext('status'))
    def test_invalid_quantities_and_entities_are_rejected(self):
        for body in [self.create.replace('<planQty>3','<planQty>0'),self.create.replace('<planQty>3','<planQty>1.5'),'<!DOCTYPE request [<!ENTITY x "test">]>'+self.create]:self.assertEqual('failure',self.call('deliveryorder.create',body).findtext('flag'))

if __name__=='__main__':unittest.main()
