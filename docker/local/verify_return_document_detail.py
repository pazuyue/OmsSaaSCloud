"""Read-only acceptance against preserved warehouse returns; never calls a business command."""
import json
import time
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode
from urllib.error import HTTPError
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-document-ui';OUT.mkdir(exist_ok=True)


def main():
    token=login();checks=[]
    def api(path,**params):return request(path+('?' + urlencode(params) if params else ''),token=token)['data']
    for attempt in range(100):
        try:
            result=api('/order/returns',view='DOCUMENT',pageSize=20)
            if not result['synchronization'].get('warning') and not result['synchronization'].get('updating'):break
        except Exception:
            if attempt==99:raise
        time.sleep(1)
    else:raise AssertionError('Read projection is not ready')
    case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24'];sn=case['shared_return']['document']['return_sn'];refund=case['final_refund']['refund_sn']
    def check(name,passed,actual):
        assert passed,(name,actual)
        checks.append({'name':name,'result':'PASS','actual':actual})
    r=api('/order/returns',view='DOCUMENT',number=refund);row=r['rows'][0]
    check('合包入库单列表只展示一次共同仓单和仓库交互摘要',r['total']==1 and len(row['warehouse_documents'])==1 and row['warehouse_documents'][0]['warehouse_state']=='ACCEPTED' and row['latest_interaction'].get('id'),row)
    check('列表摘要不读取请求响应报文',not any(k in row['latest_interaction'] for k in ['request_body','response_body']),row['latest_interaction'])
    check('同一实际SKU在列表汇总展示而非重复两行',row['product_count']==1 and len(row['products'])==1 and row['products'][0]['quantity']==2,{'products':row['products'],'sources':row['source_count']})
    d=api('/order/returns/detail',view='DOCUMENT',key=sn,number=refund,numberType='OWNER')
    check('整张仓单保留退款及换货B各1件并只高亮当前退款',len(d['members'])==2 and d['matched_count']==1 and all(x['sku_sn']==case['target_sku'] and x['posted_quantity']==1 for x in d['members']),d)
    check('实际商品名称及原实际发货沿换出B追溯',all(x['original_shipment_sn']==case['audit']['source'][0][3] for x in d['members']),[{'name':x['goods_name'],'shipment':x['original_shipment_sn']} for x in d['members']])
    summary=api('/order/returns/document',sn=sn);check('原出库成本按各来源保留',len(summary['sources'])==2 and all(float(x['unit_cost'])==2.5 for x in summary['sources']),summary)
    receipts=api('/order/returns/document',sn=sn,section='RECEIPTS');check('合包只显示一次原始回传和两条实际回传行',receipts['total']==1 and len(receipts['rows'][0]['lines'])==2 and receipts['rows'][0]['final_confirmation'] is True and receipts['rows'][0]['quantity']==2,receipts)
    allocations=receipts['rows'][0]['allocations'];check('原始回传直接展示分配给哪张退款和换货',len(allocations)==2 and {x['owner_kind'] for x in allocations}=={'REFUND','EXCHANGE'} and all(x.get('owner_sn') and x['sku_sn']==case['target_sku'] for x in allocations),allocations)
    check('实收时间使用统一可读格式',all('T' not in x['create_time'] for x in receipts['rows']),[x['create_time'] for x in receipts['rows']])
    lines=api('/order/returns/document',sn=sn,section='LINES');check('退款与换货分别入账且批次原成本和流水号可追溯',lines['total']==2 and sum(x['good_qty']+x['bad_qty'] for x in lines['rows'])==2 and all(x['posted']==1 and x['batch_code'] and x['inventory_relation'].startswith('REFUND_RETURN_' if x['owner_kind']=='REFUND' else 'EXCHANGE_RETURN_') for x in lines['rows']),lines)
    check('入账明细不暴露原始回传和私人地址',all(not any(k in x for k in ['payload','request_body','return_address','receiver_mobile']) for x in lines['rows']),True)
    a=api('/order/returns/document',sn=sn,section='LINES',pageNum=1,pageSize=1);b=api('/order/returns/document',sn=sn,section='LINES',pageNum=2,pageSize=1);check('入账明细分页不丢行不重复',a['total']==2 and b['total']==2 and a['rows'][0]['owner_kind']!=b['rows'][0]['owner_kind'],{'first':a,'second':b})
    logs=api('/supplychain/wmsIntegration/log-center',numberType='DOCUMENT',number=sn,allTime='true');check('交互记录绑定实际共同仓单并涵盖下发与回传',all(x['ticket_sn']==sn for x in logs['rows']) and {'AFTERSALE_RETURN_CREATE','AFTERSALE_RETURN_RECEIPT'}.issubset({x['action'] for x in logs['rows']}),logs)
    r=api('/order/returns',view='DOCUMENT',number=refund,warehouse='ACCEPTED');check('列表按仓库已受理筛选保持整单2件',r['total']==1 and r['rows'][0]['units'][0]['quantity']==2,r)
    r=api('/order/returns',view='DOCUMENT',number=refund,warehouse='FAILED');check('已受理仓单不被当作下发失败',r['total']==0,r)
    partial=json.loads((ROOT/'outputs/exchange-partial-continuous/manifest.json').read_text('utf-8'))['cases']['19'];r=api('/order/returns',view='DOCUMENT',number=partial['claims'][0]['exchange_sn']);pr=r['rows'][0];p=api('/order/returns/document',sn=pr['return_sn'],section='RECEIPTS')
    check('部分实收仍保留计划2实收1入账1且不是最终回传',pr['units'][0]['quantity']==2 and pr['units'][0]['accepted_quantity']==1 and pr['units'][0]['posted_quantity']==1 and p['total']==1 and not p['rows'][0]['final_confirmation'],{'row':pr,'receipts':p})
    gift=sql("SELECT return_sn FROM oms_return_query_row WHERE company_code='QM' AND view_kind='DOCUMENT' AND is_gift=1 ORDER BY id DESC LIMIT 1",'qm_oms_saas_order')
    if gift:
        g=api('/order/returns/detail',view='DOCUMENT',key=gift);f=api('/order/returns/document',sn=gift,section='LINES');check('含赠品仓单保持商品身份并支持来源入账查询',any(x['is_gift'] for x in g['members']) and f['total']>0,{'detail':g,'stock':f})
    legacy=sql("SELECT return_sn FROM wms_refund_parcel_document WHERE company_code='QM' ORDER BY id DESC LIMIT 1",'qm_oms_saas_commodity')
    if legacy:
        x=api('/order/returns/document',sn=legacy,section='RECEIPTS');y=api('/order/returns/document',sn=legacy,section='LINES');check('多退款共同THK同样可追溯原始实收及来源入账',x['receipt_kind']=='REFUND' and y['total']>0,{'receipts':x,'stock':y})
    for params in [dict(sn=sn,section='LINES',pageSize=51),dict(sn='RSL_PRIVATE',section='SUMMARY'),dict(sn=sn,section='INVALID'),dict(sn='THK_NOT_OWNED',section='LINES')]:
        try:
            api('/order/returns/document',**params);raise AssertionError('Unexpected acceptance')
        except (AssertionError,HTTPError) as error:
            if str(error)=='Unexpected acceptance':raise
            check('非法分页、私有台账或不存在仓单被拒绝',True,{'request':params,'result':str(error)})
    for params in [dict(view='DOCUMENT',warehouse='INVALID'),dict(view='DOCUMENT',company_code='OTHER')]:
        try:
            api('/order/returns',**params);raise AssertionError('Unexpected acceptance')
        except (AssertionError,HTTPError) as error:
            if str(error)=='Unexpected acceptance':raise
            check('筛选及公司边界校验有效',True,{'request':params,'result':str(error)})
    baseline=json.loads((ROOT/'outputs/return-management/protected-baseline.json').read_text('utf-8'));check('所有保留验收单据库存队列及任务配置保持一致',protected()==baseline,True)
    result={'verified_at':datetime.now().isoformat(),'checks':checks,'case':{'document':sn,'refund':refund,'source_shipment':case['audit']['source'][0][3]},'inventory_and_source_data_preserved':True}
    (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    (OUT/'regression.json').write_bytes((ROOT/'outputs/return-management/regression-affected.json').read_bytes())
    print('PASS',len(checks),'document acceptance checks; preserved inventory and business configuration')


if __name__=='__main__':main()
