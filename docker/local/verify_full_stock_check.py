"""Retained full-census acceptance. OMS writes use task APIs only; WMS fixture writes are new TEST-only SKUs."""
import hashlib,json,subprocess,sys,time,urllib.parse
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/full-stock-check';API='/supplychain/warehouseControl';INV='qm_oms_saas_inventory';DB='qm_oms_saas_commodity'


def baseline():
    data=protected()
    data.update(stock=sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock,version FROM oms_inventory WHERE company_code='QM' ORDER BY sku_sn",INV),physical=sql("SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,cp_actual_number,cp_available_number,cp_lock_number,version FROM wms_inventory WHERE company_code='QM' ORDER BY store_code,sku_sn",INV),batches=sql("SELECT id,store_code,sku_sn,batch_code,zp_actual_number,zp_available_number,zp_lock_number,cp_actual_number,cp_available_number,cp_lock_number,version FROM wms_inventory_batch WHERE company_code='QM' ORDER BY id",INV),journals=sql("SELECT COUNT(*),COALESCE(MAX(log_id),0) FROM wms_inventory_change_history WHERE company_code='QM'",INV),returns=sql("SELECT id,return_sn,state,quantity,accepted_quantity,posted_quantity,control_version FROM purchase_return WHERE company_code='QM' ORDER BY id",DB))
    return data


def main():
    OUT.mkdir(parents=True,exist_ok=True);file=OUT/'manifest.json';state=json.loads(file.read_text('utf-8')) if file.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),ids={},checks=[])
    def save():file.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
    if 'before' not in state:state['before']=baseline();save()
    old=json.loads((ROOT/'outputs/warehouse-control/manifest.json').read_text('utf-8'));state['store']=old['store_source'];state['stores']=[old['store_source'],old['store_source2']];state['owner']='WC_OWNER_'+old['stamp'];state['warehouse']='WC_EXT_source_'+old['stamp'];save()
    token=login();api=lambda path,body=None:request(path,payload=body,token=token)['data']
    def wait(id,terminal=True):
        for _ in range(120):
            d=api(API+'/reconciliations/'+str(id))
            if not terminal or d['state'] in ['COMPLETED','PARTIAL','FAILED','STOPPED']:return d
            time.sleep(.5)
        raise AssertionError(('Task did not settle',d))
    def create(tag,extra=None):
        if tag not in state['ids']:state['ids'][tag]=api(API+'/reconciliations',dict(scope_mode='FULL',store_code=state['store'],request_key='FULL_ACCEPT_'+state['stamp']+'_'+tag,**(extra or {})));save()
        return state['ids'][tag]
    def check(key,scenario,result):
        if not any(c['key']==key for c in state['checks']):state['checks'].append(dict(key=key,scenario=scenario,result=result));save();print('PASS',key,flush=True)
    def rows(id,**filters):return api(API+'/reconciliations/'+str(id)+'/results?'+urllib.parse.urlencode(dict(pageSize=100,**filters)))
    id=create('oms');doc=wait(id);assert doc['state']=='COMPLETED',doc
    actual=rows(id);expected=set(sql("SELECT DISTINCT sku_sn FROM wms_inventory WHERE company_code='QM' AND store_code IN ("+','.join("'"+s+"'" for s in state['stores'])+")",INV).splitlines());assert expected.issubset({r['sku_sn'] for r in actual['rows']});assert int(doc['progress']['total'])==int(doc['progress']['successful']);assert doc['coverage']=='OMS_LIST';assert 'warehouse_skus' not in doc['scope']
    check('01','按原验收实仓全盘检查；无需传SKU，自动合并两个虚仓，保留零库存与占用记录。',dict(task=id,sku_count=doc['progress']['total'],result_rows=actual['total'],covers_all_oms_stock_skus=True,coverage='OMS清单，不声称发现仓库独有商品'))
    missing=rows(id,status='MISSING_WMS');assert missing['total']>0 and all(r.get('wms_physical') is None and r.get('difference') is None for r in missing['rows'])
    check('02','仓库未返回部分商品的库存。',dict(unknown_rows=missing['total'],wms_quantity=None,difference=None,conclusion='未知，不推断为零或缺货'))
    frozen_sku=next(c['sku'] for c in old['cases'] if c['key']=='frozen');frozen=rows(id,sku_sn=frozen_sku)['rows'][0];assert frozen['oms_held']==0 and frozen['wms_frozen']==0 and frozen['status']=='MATCHED'
    check('03','核对保留的frozen商品最新仓库事实；旧验收后已恢复为冻结0，不能沿用旧截图当成本次预期。',dict(row=frozen,conclusion='本次实物10、冻结0，正确为一致；冻结与业务占用分列，差异保护另由后端回归覆盖'))
    page1=api(API+'/reconciliations/'+str(id)+'/results?pageSize=2&pageNum=1');page2=api(API+'/reconciliations/'+str(id)+'/results?pageSize=2&pageNum=2');assert len(page1['rows'])==2 and not set(r['result_id'] for r in page1['rows'])&set(r['result_id'] for r in page2['rows'])
    check('04','分页查看全仓结果并按异常类型定位。',dict(page_size=2,total=page1['total'],distinct_pages=True))
    credentials=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
    def wms_fixture(fix=False):
        payload=dict(customer=credentials['customer_id'],owner=state['owner'],warehouse=state['warehouse'],only='WC_FULL_ONLY_'+state['stamp'],bad='WC_FULL_BAD_'+state['stamp'],fix=fix)
        script="""import json,sqlite3,sys
p=json.load(sys.stdin)
assert p['only'].startswith('WC_FULL_ONLY_') and p['bad'].startswith('WC_FULL_BAD_') and p['warehouse'].startswith('WC_EXT_')
with sqlite3.connect('/data/documents.sqlite') as d:
 for sku,q in [(p['only'],7),(p['bad'],0 if p['fix'] else -1)]:
  d.execute('INSERT OR REPLACE INTO inventory_stock VALUES(?,?,?,?,?,?,?,?)',(p['customer'],p['owner'],p['warehouse'],sku,'FULL_BATCH','ZP',q-(3 if sku==p['only'] else 0),3 if sku==p['only'] else 0))
 print(json.dumps([r[0] for r in d.execute('SELECT DISTINCT sku FROM inventory_stock WHERE customer=? AND owner=? AND warehouse=? ORDER BY sku',(p['customer'],p['owner'],p['warehouse']))]))
"""
        cmd=['docker','exec','-i','oms-local-wms-simulator','python','-c',script]
        if sys.platform=='win32':cmd=['wsl.exe','--exec']+cmd
        result=subprocess.run(cmd,input=json.dumps(payload),text=True,capture_output=True);assert result.returncode==0,result.stderr;return json.loads(result.stdout)
    if 'warehouse_skus' not in state:state['warehouse_skus']=wms_fixture();save()
    manifest=state['warehouse_skus'];(OUT/'仓库完整商品清单.csv').write_text('sku_sn\n'+'\n'.join(manifest),'utf-8-sig')
    id=create('dual',dict(warehouse_skus=manifest,warehouse_list_evidence='本地TEST模拟仓库完整导出 '+state['stamp'],warehouse_list_confirmed=True));doc=wait(id)
    if not state.get('retried'):
        assert doc['state']=='PARTIAL',doc
        failed=rows(id,status='QUERY_FAILED');assert failed['total']==1 and failed['rows'][0]['wms_physical'] is None
        state['partial']=doc;state['successful_before_retry']=sql('SELECT child_id,row_hash FROM warehouse_reconciliation_result WHERE parent_id='+str(id)+" AND company_code='QM' AND status<>'QUERY_FAILED' ORDER BY child_id,row_hash",DB);save()
        check('05','仓库新增一个OMS无记录的商品实物7件，另一个商品回传非法负数。导入本仓完整SKU清单后检查。',dict(task=id,coverage=doc['coverage'],failed=failed,conclusion='发现仓库独有商品；单商品查询失败使整仓显示部分失败'))
        wms_fixture(True);api(API+'/reconciliations/'+str(id)+'/control/retryFailed',{});doc=wait(id);assert doc['state']=='COMPLETED';state['retried']=True;save()
    assert state['successful_before_retry']==sql('SELECT child_id,row_hash FROM warehouse_reconciliation_result WHERE parent_id='+str(id)+" AND company_code='QM' AND sku_sn<>'WC_FULL_BAD_"+state['stamp']+"' ORDER BY child_id,row_hash",DB)
    only=rows(id,sku_sn='WC_FULL_ONLY_'+state['stamp'])['rows'][0];assert only['oms_physical']==0 and only['wms_physical']==7 and only['difference']==7
    check('06','修正失败商品仓库回传后，只重试失败商品。',dict(task=id,successful_rows_unchanged=True,remaining_failed=rows(id,status='QUERY_FAILED')['total'],warehouse_only_row=only))
    if 'recheck' not in state['ids']:
        sku=next(c['sku'] for c in old['cases'] if c['key']=='frozen');state['ids']['recheck']=api(API+'/reconciliations/'+str(id)+'/recheck',dict(request_key='FULL_RECHECK_'+state['stamp'],skus=[sku]));save()
    fresh=wait(state['ids']['recheck']);assert fresh['source_check_id']==id and fresh['scope_mode']=='SELECTED' and fresh['state']=='COMPLETED';assert len(fresh['scope']['skus'])==1
    check('07','从全仓异常结果选一个商品创建最新复查。',dict(new_task=fresh['id'],source_task=id,scope=fresh['scope'],can_adjust=fresh['result']['can_adjust'],conclusion='新独立快照；保留库存版本与原有调整审核防护'))
    try:api(API+'/reconciliations/'+str(id)+'/adjustment',{})
    except AssertionError as e:assert '全仓结果仅用于发现差异' in str(e)
    else:raise AssertionError('Full task must not adjust stock directly')
    check('08','尝试直接用全仓结果申请实物调整。',dict(rejected=True,reason='须先创建选定商品的最新复查'))
    state['after']=baseline();state['unchanged']={k:state['before'][k]==v for k,v in state['after'].items()};assert all(state['unchanged'].values()),state['unchanged'];state['stage']='VERIFIED';save()
    print('PASS',len(state['checks']),'retained full-stock-check scenarios; all company stock and protected records unchanged',flush=True)


if __name__=='__main__':main()
