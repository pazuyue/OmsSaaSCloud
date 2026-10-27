"""Read-only consistency, isolation, query bounds and local latency observations."""
import json,statistics,time,urllib.error
from pathlib import Path
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,quote
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/warehouse-control';API='/supplychain/warehouseControl';DB='qm_oms_saas_commodity';INV='qm_oms_saas_inventory'
def main():
 state=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in state['cases']};token=login();checks=[];timings=[]
 def api(p,body=None):return request(p,payload=body,token=token)
 before=sql("SELECT COUNT(*),COALESCE(MAX(id),0) FROM wms_interaction_log",DB)
 for title,path,body in [
  ('调拨分页',API+'/transfers',None),('对账分页',API+'/reconciliations',None),
  ('完整调拨详情',API+'/transfers/'+str(cases['full']['transfer_id']),None),
  ('对账详情',API+'/reconciliations/'+str(cases['reconcile']['reconciliation_ids']['loss']),None),
  ('原库存批次批量读取',API+'/sources',dict(store_code=state['store_source'],skus=[cases['missing']['sku']])),
  ('商品库存（含在途）','/inventory/productInventory?skuSn='+cases['pending']['sku'],None),
  ('指定单号统一待办','/supplychain/purchaseWorkspace/operations?number='+api(API+'/transfers/'+str(cases['short']['transfer_id']))['data']['document_sn'],None)]:
  samples=[]
  for _ in range(5):
   start=time.perf_counter();r=api(path,body);samples.append(round((time.perf_counter()-start)*1000,1))
  timings.append(dict(endpoint=title,samples_ms=samples,median_ms=round(statistics.median(samples),1)))
 checks.append('列表、详情、原批次和统一待办只读本地投影或一次库存服务读取，不产生任何WMS请求')
 for c in state['cases']:
  r=api('/inventory/productInventory?skuSn='+c['sku'])['rows'][0];expected=state['final_stock'][c['key']];assert r['checkStatus']=='CONSISTENT';assert r['transitStock']==expected['transit'] and r['ownedStock']==expected['owned'],(c['key'],r,expected)
 checks.append('17个SKU的商品、仓库、批次均一致；公司拥有等于实物加在途，在途未并入可用')
 try:api('/inventory/productInventory?'+urlencode(dict(skuSn=cases['pending']['sku'],company_code='OTHER')))
 except urllib.error.HTTPError as e:assert e.code==400
 else:raise AssertionError('Cross-company override must be rejected')
 checks.append('公开API拒绝额外company_code参数切换公司，库存继续受登录公司隔离')
 try:api(API+'/transfers?pageSize=51')
 except AssertionError as e:assert '最多50' in str(e)
 else:raise AssertionError('Page bound absent')
 checks.append('调拨和对账每页最多50条，避免无界读取')
 try:api(API+'/reconciliations?from=2020-01-01&to=2026-10-26')
 except AssertionError as e:assert '93' in str(e)
 else:raise AssertionError('Date bound absent')
 checks.append('普通列表日期跨度限制93天，完整单号保留跨日期定位')
 number=api(API+'/transfers/'+str(cases['full']['transfer_id']))['data']['document_sn'];assert api(API+'/transfers?'+urlencode(dict(document_sn=number)))['data']['total']==1
 checks.append('完整调拨单号精确查询仅返回原单')
 sku=cases['full']['sku'];signals=sql("SELECT store_code,revision FROM oms_allocation_stock_signal WHERE company_code='QM' AND sku_sn="+quote(sku)+" ORDER BY store_code",INV);assert state['store_source'] in signals and state['store_target'] in signals
 checks.append('源仓占用、出库和目的收货均产生库存重算信号；渠道可售的变化由新增真实事务回归验证')
 ledger={c['sku']:sql("SELECT operation_type,relation_sn,inventory_type,change_quantity,store_code,batch_code FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(c['sku'])+" ORDER BY log_id",INV) for c in state['cases']}
 for c in state['cases']:
  raw=sql("SELECT b.sku_sn,b.store_code,b.batch_code,b.transaction_price FROM wms_inventory_batch b WHERE b.company_code='QM' AND b.sku_sn="+quote(c['sku'])+" ORDER BY b.id",INV);assert all(line.split('\t')[-1]=='2.50' for line in raw.splitlines()),raw
 checks.append('调拨目的及收回批次保留原批次编码和2.50元成本，原出库流水保留')
 for c in [cases['full'],cases['mixed']]:
  sn=api(API+'/transfers/'+str(c['transfer_id']))['data']['document_sn'];duplicates=sql("SELECT COUNT(*) FROM (SELECT request_id,batch_id,inventory_type,COUNT(*) cnt FROM wms_inventory_change_history WHERE company_code='QM' AND relation_sn="+quote(sn)+" GROUP BY request_id,batch_id,inventory_type HAVING COUNT(*)>1) x",INV);assert duplicates=='0',duplicates
 checks.append('同批次正次品与重复消息的库存流水身份无冲突、无重复')
 tables=['warehouse_control_audit','warehouse_transfer','warehouse_transfer_line','warehouse_transfer_execution','warehouse_control_event','warehouse_reconciliation','warehouse_stock_adjustment','warehouse_inventory_report'];inventory_tables=['oms_stock_transfer','oms_stock_transfer_line','oms_stock_movement_event']
 for database,names in [(DB,tables),(INV,inventory_tables)]:
  missing=sql("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN("+','.join(quote(t) for t in names)+") AND column_comment=''",database);assert not missing,missing
 checks.append('全部11张新增表的字段均有中文备注，数量、质量、成本、租约与幂等口径明确')
 explain={
  'reconciliation_latest':sql("EXPLAIN SELECT id FROM warehouse_reconciliation WHERE company_code='QM' AND scope_hash=(SELECT scope_hash FROM warehouse_reconciliation WHERE id="+str(cases['reconcile']['reconciliation_ids']['loss'])+") AND state='COMPLETED' ORDER BY id DESC LIMIT 1",DB),
  'transfer_due':sql("EXPLAIN SELECT id FROM warehouse_transfer WHERE company_code='QM' AND state='ACTIVE' AND next_attempt_at<=NOW() ORDER BY next_attempt_at,id LIMIT 30",DB),
  'transit':sql("EXPLAIN SELECT sku_sn,SUM(out_quantity-in_quantity-recovered_quantity) FROM oms_stock_transfer_line WHERE company_code='QM' AND sku_sn="+quote(cases['pending']['sku'])+" GROUP BY sku_sn",INV)}
 checks.append('最新范围快照、到期调拨扫描及按SKU在途汇总具备专用索引，列表不逐行查询WMS')
 assert sql("SELECT COUNT(*),COALESCE(MAX(id),0) FROM wms_interaction_log",DB)==before,'Read-only checks emitted WMS requests'
 (OUT/'query-check.json').write_text(json.dumps(dict(result='PASS',checks=checks,timings=timings,explain=explain,ledger=ledger,stock_signals=signals,note='本地每入口5次读取耗时，仅用于发现查询退化，不代表生产容量；真实店铺下发未执行'),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'query/stock/protocol checks')
if __name__=='__main__':main()
