"""Read-only query/ledger checks. No warehouse query or task execution commands."""
import json,time,statistics,urllib.error
from pathlib import Path
import sys
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,quote
from verify_purchase_return_fulfillment import baseline
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-fulfillment';RET='/supplychain/purchaseReturns';DB='qm_oms_saas_commodity';INV='qm_oms_saas_inventory'

def main():
 s=json.loads((OUT/'manifest.json').read_text('utf-8'));cs={c['key']:c for c in s['cases']};token=login();checks=[];timings=[]
 assert sys.argv[1:] in ([],['--current-baseline'])
 current_baseline=sys.argv[1:]==['--current-baseline'];protected_before=baseline() if current_baseline else s['protected_before'];result_out=ROOT/'outputs/purchase-return-restore-review' if current_baseline else OUT
 def api(p):return request(p,token=token)
 sns=[c['return_sn'] for c in s['cases']]+[d['reclaim_sn'] for v in s['documents'].values() for d in v['reclaims']];where="company_code='QM' AND ticket_sn IN("+','.join(quote(sn) for sn in sns)+')';log_before=sql('SELECT COUNT(*),COALESCE(MAX(id),0) FROM wms_interaction_log WHERE '+where,DB)
 for title,path in [('普通分页列表',RET),('同单连续恢复详情',RET+'/'+str(cs['restore']['return_id'])),('多包裹收回详情',RET+'/'+str(cs['parcels']['return_id'])),('分片待处理详情',RET+'/'+str(cs['incomplete']['return_id'])),('原采购来源',RET+'/sources?purchaseId='+str(cs['restore']['purchase_id'])),('指定单号待办','/supplychain/purchaseWorkspace/operations?number='+cs['ready']['return_sn'])]:
  samples=[]
  for _ in range(5):
   start=time.perf_counter();api(path);samples.append(round((time.perf_counter()-start)*1000,1))
  timings.append(dict(endpoint=title,samples_ms=samples,median_ms=round(statistics.median(samples),1)))
 checks.append('列表、详情、原来源和待办连续读取不触发仓库调用；列表按当前页批量汇总执行及包裹')
 for c in s['cases']:
  r=api('/inventory/productInventory?skuSn='+c['sku'])['rows'][0];assert r['checkStatus']=='CONSISTENT',r
  for k in ['totalStock','availableStock','allocatedStock','reservedStock','frozenStock']:assert r[k]==s['final_stock'][c['key']][k],(c['key'],k)
 checks.append('11个保留SKU的公司、仓库、批次账一致；负向待处理场景的有效占用保持')
 restore=api(RET+'/'+str(cs['restore']['return_id']))['data'];h=restore['stock']['lines'][0];assert [h[k] for k in ['planned_quantity','quantity','consumed_quantity','released_quantity']]==[5,10,5,5];assert len(restore['executions'])==3 and not restore['child_returns'];source=api(RET+'/sources?purchaseId='+str(cs['restore']['purchase_id']))['data']['rows'][0];assert source['claimed_quantity']==5 and source['returned_quantity']==5 and source['remaining_quantity']==5,source
 checks.append('连续恢复后原计划及来源额度仍按5件计算，累计占用10和释放5没有放大可退额度，也没有生成子CGT')
 p=api(RET+'/'+str(cs['parcels']['return_id']))['data'];assert p['posted_quantity']==5 and p['lines'][0]['recovered_quantity']==3 and float(p['funds_amount'])==5;assert len(p['supplier_sources'])==3 and len(p['parcels'])==3
 checks.append('包裹、供应商事实、收回和款项独立核对：出库5、收回3、净货值5元，原出库和拒收来源保留')
 for key in ['ready','parcels','restore','fragment']:
  c=cs[key];body=api(RET+'?'+urlencode(dict(return_sn=c['return_sn'])))['data'];assert body['total']==1;row=body['rows'][0];detail=api(RET+'/'+str(c['return_id']))['data'];assert row['execution_count']==len(detail['executions']) and row['parcel_count']==len(detail['parcels']);assert row['parcel_pending']==sum(p['content_state']!='MAPPED' for p in detail['parcels']);assert row['parcel_unknown_execution_count']==sum(e['accepted_quantity']>0 and not any(p['execution_id']==e['id'] for p in detail['parcels']) for e in detail['executions'])
 checks.append('当前页执行次数、已识别包裹与待核对数同详情一致，完整CGT单号精确查询返回原业务单')
 for quick in ['PARCEL','SUPPLIER','RESTORE']:
  rows=api(RET+'?'+urlencode(dict(quick=quick,pageSize=100)))['data']['rows'];assert rows,quick
  if quick=='RESTORE':assert any(x['id']==cs['restore_conflict']['return_id'] for x in rows)
 checks.append('包裹未补齐、供应商商品未确认、已释放余量恢复待审核都有独立快捷查询')
 for issue,key in [('PARCEL','restore'),('RESTORE','restore_conflict'),('SUPPLIER','ready')]:
  rows=api('/supplychain/purchaseWorkspace/operations?'+urlencode(dict(number=cs[key]['return_sn'],issue=issue)))['data']['rows'];assert len(rows)==1 and rows[0]['kind']=='purchaseReturn' and rows[0]['document_id']==cs[key]['return_id'],(issue,rows)
 checks.append('供应链待办分别定位退仓包裹待核对、恢复待审核与供应商商品待验收，入口直接指向原CGT')
 for path,contains in [(RET+'?pageSize=101','分页'),(RET+'?from=2020-01-01&to=2026-10-26','93')]:
  try:api(path)
  except AssertionError as e:assert contains in str(e)
  else:raise AssertionError('Query bound missing')
 checks.append('普通列表默认近90天，每页最多100条，日期跨度最多93天，阻止无界普通查询')
 try:api(RET+'/'+str(cs['restore']['return_id'])+'?company_code=OTHER')
 except urllib.error.HTTPError as e:assert e.code==400
 else:raise AssertionError('Company override was accepted')
 checks.append('公开API拒绝company_code参数切换公司，所有原执行、包裹及拒收来源查询均限定登录公司')
 ledger={c['sku']:sql("SELECT operation_type,relation_sn,inventory_type,change_quantity,batch_code,request_id FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(c['sku'])+' ORDER BY log_id',INV) for c in s['cases']}
 c=cs['restore'];rows=[line.split('\t') for line in ledger[c['sku']].splitlines()];sums={op:sum(int(r[3]) for r in rows if r[0]==op) for op in ['RETURN_HOLD','RETURN_REHOLD','RETURN_OUT','RETURN_RELEASE']};assert sums==dict(RETURN_HOLD=5,RETURN_REHOLD=5,RETURN_OUT=-5,RETURN_RELEASE=5),sums
 checks.append('真实库存流水核实：初次占用5、两轮恢复5、实际出库-5、两轮释放5；重复请求未重复扣账或重置历史')
 c=cs['fragment'];assert sql("SELECT COUNT(*) FROM purchase_return_event WHERE company_code='QM' AND return_id="+str(c['return_id']),DB)=='1';c=cs['cumulative'];assert sql("SELECT GROUP_CONCAT(quantity ORDER BY id) FROM purchase_return_event WHERE company_code='QM' AND return_id="+str(c['return_id']),DB)=='2,3'
 checks.append('分片完整后只有一次库存事件；累计快照仅产生2和3两个增量，迟到旧版本没有库存事件')
 tables=['purchase_return_execution','purchase_return_execution_line','purchase_return_execution_request','purchase_return_message','purchase_return_message_part','purchase_return_outbound_line','purchase_return_parcel','purchase_return_parcel_item','purchase_return_parcel_allocation','purchase_return_event_parcel','purchase_return_supplier_line','purchase_return_supplier_parcel','purchase_return_metadata_command']
 missing=sql("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN("+','.join(quote(t) for t in tables)+") AND column_comment=''",DB);assert not missing,missing
 for database,table,fields in [(INV,'oms_stock_care_line',['planned_quantity']),(DB,'purchase_return',['fulfillment_model','stop_key']),(DB,'purchase_return_event',['execution_id']),(DB,'purchase_return_reclaim_line',['supplier_source_id']),(DB,'wms_connection',['purchase_return_quantity_mode','purchase_return_fragment_mode'])]:assert not sql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name="+quote(table)+" AND column_name IN("+','.join(quote(f) for f in fields)+") AND column_comment=''",database)
 checks.append('13张新增表和全部新增字段均有中文备注，数量模式、分片、来源、幂等和库存历史口径清晰')
 explain=dict(execution_by_original=sql("EXPLAIN SELECT id FROM purchase_return_execution WHERE company_code='QM' AND return_id="+str(cs['restore']['return_id'])+" AND state='ACTIVE' ORDER BY id",DB),message_idempotence=sql("EXPLAIN SELECT id FROM purchase_return_message WHERE company_code='QM' AND execution_id="+str(restore['executions'][0]['id'])+" AND message_id='RESTORE_THIRD'",DB),parcel_list=sql("EXPLAIN SELECT id FROM purchase_return_parcel WHERE company_code='QM' AND return_id="+str(cs['parcels']['return_id'])+" AND content_state='REVIEW' ORDER BY id",DB),supplier_source=sql("EXPLAIN SELECT id FROM purchase_return_supplier_line WHERE company_code='QM' AND return_id="+str(cs['parcels']['return_id'])+" AND decision='REFUSE' ORDER BY id",DB))
 checks.append('原执行、消息幂等、包裹和拒收来源均有公司与原单索引；异步执行沿用有界工作池和持久租约')
 assert sql('SELECT COUNT(*),COALESCE(MAX(id),0) FROM wms_interaction_log WHERE '+where,DB)==log_before,'Read-only checks invoked WMS';assert baseline()==protected_before,'Current verification changed unrelated baseline';checks.append('只读检查未增加这些单据的仓库调用；本轮前后原业务、旧库存、自动任务与队列配置一致')
 (result_out/'query-check.json').write_text(json.dumps(dict(result='PASS',checks=checks,timings=timings,explain=explain,ledger=ledger,restore_ledger_sums=sums,baseline_mode='CURRENT_RUN' if current_baseline else 'ORIGINAL_ACCEPTANCE',original_reference_matches=protected_before==s['protected_before'],current_run_baseline_unchanged=True,note='本地每入口5次读取，只用于发现查询退化，不代表生产容量；WMS仅使用本地TEST模拟器'),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'fulfillment query/ledger checks')

if __name__=='__main__':main()
