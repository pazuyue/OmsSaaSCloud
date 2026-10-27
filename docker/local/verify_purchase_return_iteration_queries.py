"""Read-only query/protocol/performance evidence for procurement-return iteration."""
import json,time,statistics
from pathlib import Path
from urllib.parse import urlencode
from datetime import datetime
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-iteration';RET='/supplychain/purchaseReturns';DB='qm_oms_saas_commodity'
def main():
 s=json.loads((OUT/'scenarios.json').read_text('utf-8'));b=json.loads((OUT/'base/manifest.json').read_text('utf-8'));cases={c['key']:c for c in b['cases']};t=login();checks=[];timings=[]
 def get(path,q=None):return request(path+('?' +urlencode(q) if q else ''),token=t)['data']
 full=cases['full'];parent=get(RET+'/'+str(cases['final_short']['return_id']));assert parent['progress_code']=='PARTIAL_CLOSED' and parent['remaining_hold']==0 and len(parent['child_returns'])==1;checks.append('部分完成与唯一后续单关联正确，原占用已释放')
 original=get(RET+'/17');assert original['progress_code']=='REMAINING' and original['remaining_hold']==3 and original['stock']['lines'][0]['zp_available_number']==5;checks.append('用户原单仍占用3件，释放预览使用真实批次可用5件')
 sources=get(RET+'/sources',dict(purchaseId=full['purchase_id']));r=next(x for x in sources['rows'] if x['source_key'].startswith('R'));new=next(x for x in sources['rows'] if x['source_key'].startswith('S'));assert r['source_quantity']==10 and r['returned_quantity']==3 and r['selectable_quantity']==7 and new['source_quantity']==1 and new['returned_quantity']==1 and new['selectable_quantity']==0;checks.append('原来源及收回新增来源独立核算，可退额度与实际库存一致')
 projection=get(RET,dict(purchase_id=full['purchase_id']));assert sum(x['quantity'] for x in projection['returned_by_sku'])==4 and sum(x['quantity'] for x in projection['recovered_by_sku'])==1;checks.append('采购净入库10减4加1等于7，原采购实收保留')
 d=get(RET+'/'+str(full['return_id']));assert len(d['funds'])==4 and float(d['funds_amount'])==5 and float(d['net_posted_amount'])==5 and not d['funds_over_recorded'];checks.append('多笔凭证及冲销累计5元，与净退仓货值一致')
 r=get(RET+'/reclaims/'+str(s['ids']['real_reclaim']));assert r['state']=='COMPLETED' and r['receipt_lines'][0]['inventory_type']=='CP' and r['receipt_lines'][0]['posted']==1;checks.append('独立收回保留次品质检及真实库存入账来源')
 logs=get('/supplychain/wmsIntegration/log-center',dict(numberType='DOCUMENT',number=r['reclaim_sn'],allTime='true',businessType='PURCHASE_RECLAIM',size=100));assert logs['rows'] and all(x['business_type']=='PURCHASE_RECLAIM' for x in logs['rows']);checks.append('仓库交互按独立采购退仓收回业务分类查询')
 raw=sql("SELECT action,request_body FROM wms_interaction_log WHERE company_code='QM' AND ticket_sn="+quote(r['reclaim_sn'])+' ORDER BY id',DB);assert '<orderType>QTRK</orderType>' in raw and '<entryOrderType>QTRK</entryOrderType>' in raw and 'PRI_BACK_RECLAIM_FULL' in raw;checks.append('实际奇门QTRK报文与供应商退回本仓运单字段核对通过')
 for key,expected in s['current_stock'].items():
  row=request('/inventory/productInventory?'+urlencode(dict(skuSn=cases[key]['sku'])),token=t)['rows'][0];assert row['checkStatus']=='CONSISTENT' and [row[k] for k in ['totalStock','availableStock','allocatedStock','frozenStock']]==[expected[k] for k in ['total','available','allocated','frozen']]
 checks.append('全部8个验收SKU商品、仓库与批次库存一致')
 assert sql("SELECT COUNT(*),COUNT(DISTINCT request_id),SUM(change_quantity) FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(full['sku'])+" AND relation_sn LIKE 'PURCHASE_RECLAIM_%'",'qm_oms_saas_inventory').split('\t')==['1','1','1'];checks.append('更正及重复回传仅增加次品1件，收回流水仅1条')
 assert any(x['code']=='SF' for x in get(RET+'/carriers',dict(purchaseId=full['purchase_id'],province='北京市')));checks.append('快递目录使用现有货主实仓配置')
 from urllib.error import HTTPError
 try:get(RET,dict(company_code='OTHER'));raise RuntimeError('company override allowed')
 except (AssertionError,HTTPError):pass
 checks.append('公开接口拒绝覆盖登录公司身份')
 tables=['purchase_return_continuation','purchase_return_fund','purchase_return_supplier_receipt','purchase_return_reclaim','purchase_return_reclaim_line','purchase_return_reclaim_event','purchase_return_reclaim_receipt_line']
 assert sql('SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='+quote(DB)+' AND table_name IN('+','.join(map(quote,tables))+') AND column_comment='+quote(''),DB)=='0';checks.append('新增表全部字段有明确备注')
 for label,path,q in [('退仓列表',RET,dict(pageSize=20)),('完整单号',RET,dict(return_sn=full['return_sn'])),('来源批量库存',RET+'/sources',dict(purchaseId=full['purchase_id'])),('收回详情',RET+'/reclaims/'+str(r['id']),None)]:
  values=[]
  for _ in range(5):start=time.perf_counter();get(path,q);values.append(round((time.perf_counter()-start)*1000,1))
  timings.append(dict(endpoint=label,samples_ms=values,median_ms=statistics.median(values)))
 plans={name:sql(query,DB) for name,query in {'凭证来源':'EXPLAIN SELECT * FROM purchase_return_fund WHERE company_code='+quote('QM')+' AND return_id='+str(full['return_id'])+' ORDER BY id DESC LIMIT 200','原拒收额度':'EXPLAIN SELECT * FROM purchase_return_reclaim_line WHERE company_code='+quote('QM')+' AND return_line_id='+str(d['lines'][0]['id']),'实收防重':'EXPLAIN SELECT * FROM purchase_return_reclaim_event WHERE company_code='+quote('QM')+' AND reclaim_id='+str(r['id'])+' AND message_id='+quote('PRI_IN_REAL_RECLAIM')}.items()}
 assert protected()==s['protected'];checks.append('原订单退款库存与任务队列策略基线未改变')
 (OUT/'query-check.json').write_text(json.dumps(dict(result='PASS',checked_at=datetime.now().isoformat(),checks=checks,timings=timings,explain=plans,protected_baseline_unchanged=True),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'query/protocol checks')
if __name__=='__main__':main()
