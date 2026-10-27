"""Rebuild only a specified return query cache to verify direct entry before indexing.

No refund, warehouse return, inventory, queue or job record is edited.
"""
import json
import time
from pathlib import Path
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-document-ui'


def main():
    token=login();case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24'];sn=case['shared_return']['document']['return_sn']
    def api(path,**params):return request(path+'?'+urlencode(params),token=token)['data']
    for attempt in range(100):
        try:
            r=api('/order/returns',view='DOCUMENT',number=sn)
            if r['total']==1 and not r['synchronization'].get('warning') and not r['synchronization'].get('updating'):break
        except Exception:
            if attempt==99:raise
        time.sleep(1)
    else:raise AssertionError('Query service not ready')
    before=protected();where="company_code='QM' AND view_kind='DOCUMENT' AND group_key="+quote(sn)
    assert sql('SELECT COUNT(*) FROM oms_return_query_row WHERE '+where,'qm_oms_saas_order')=='2'
    sql("START TRANSACTION; INSERT INTO oms_return_query_dirty(company_code,owner_kind,owner_id) SELECT DISTINCT company_code,owner_kind,owner_id FROM oms_return_query_row WHERE "+where+" ON DUPLICATE KEY UPDATE work_version=work_version+1; DELETE FROM oms_return_query_row WHERE "+where+"; COMMIT;",'qm_oms_saas_order')
    assert sql('SELECT COUNT(*) FROM oms_return_query_row WHERE '+where,'qm_oms_saas_order')=='0'
    result=api('/order/returns/detail',view='DOCUMENT',key=sn,number=case['final_refund']['refund_sn'])
    assert not result.get('loading') and len(result['members'])==2 and result['units'][0]['posted_quantity']==2,result
    assert protected()==before
    evidence={'result':'PASS','name':'直接打开尚未建立查询缓存的共同仓单可自动补齐全部来源','document':sn,'sources_after_rebuild':len(result['members']),'posted_quantity':2,'only_query_cache_rebuilt':True,'business_and_inventory_unchanged':True}
    (OUT/'direct-entry.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),'utf-8');print('PASS direct entry before indexing; complete two-source document rebuilt; business and inventory unchanged')


if __name__=='__main__':main()
