"""Retained synthetic source/conversion acceptance, with no platform writes or warehouse shipment."""
import json
import urllib.parse
from datetime import datetime,timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for,stock
import verify_exchange_ownership as ownership
import verify_allocation_rules_v2 as fixture

OUT=Path(__file__).resolve().parents[2]/'outputs/platform-exchanges'
ORDER='qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    ownership.OUT=OUT
    token=login()
    try:
        case=ownership.Case('platform',token);c=case.c
        def api(path,body=None):return request(path,token=token,payload=body,method='POST' if body is not None else 'GET')
        def ready():
            try:return api('/order/exchanges/platform')['data']
            except AssertionError:return None
        wait_for(ready,lambda x:x is not None,'Platform exchange service not ready',180)
        if not c.get('prepared'):
            c['channel_name']='[平台换货验收] 来源、转单与阻断 '+c['case_id'];case.save();fixture.MANIFEST=case.path
            fixture.prepare_case(c,token,approve=False);c['prepared']=True;case.save()
        channel=c['channel_id']
        if not c.get('authorized'):
            app=next(a for a in api('/channel/platform/apps')['data'] if int(a.get('simulation_mode',0))==1)
            api(f'/channel/platform/shops/{channel}/binding',{'app_id':app['id'],'expected_shop_id':'700'+str(channel)})
            api(f'/channel/platform/shops/{channel}/simulate',{})
            cap=api(f'/order/exchanges/capability/{channel}')['data'];assert cap['simulation']
            api(f'/channel/exchangePlatform/{channel}/access',{'revision':cap['revision'],'read_enabled':1,'write_enabled':0})
            api('/order/exchanges/configs',{'channel_id':channel,'revision':0,'enabled':0,'write_enabled':0,'auto_convert':0,'initial_time':c['created']})
            c['authorized']=True;case.save()
        def configs():return next(x for x in api('/order/exchanges/configs')['data'] if x['channel_id']==channel)
        before_stock=stock(c);cursor=configs()['cursor_time']
        def payload(index):
            return {'dispute_id':'999'+c['case_id']+str(index).zfill(2),'biz_order_id':c['platform_oid'] if index!=2 else '991'+c['case_id']+'99','num':0 if index==3 else 2,'status':'4' if index==4 else '1','good_status':'未发货','modified':c['created'],'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'[验收] 平台原规格 A','exchange_sku':'[验收] 平台目标规格 B','speed_exchange':0,'refund_phase':'onsale','buyer_phone':'TEST-CONTACT-NOT-FOR-LIST'}
        def seed(p):sql(f"INSERT INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{channel},{quote(p['dispute_id'])},{quote(json.dumps(p,ensure_ascii=False))},{quote(p['modified'])}) ON DUPLICATE KEY UPDATE payload=VALUES(payload),modified=VALUES(modified)",'qm_oms_saas_channel')
        if not c.get('source_task'):
            for i in range(1,5):seed(payload(i))
            c['source_task']=api('/order/exchanges/sync',{'channel_id':channel,'dispute_ids':','.join(payload(i)['dispute_id'] for i in range(1,5)),'request_key':'PLATFORM_SOURCE_'+c['case_id']})['data']['id'];case.save()
        wait_for(lambda:api('/order/exchanges/tasks/'+str(c['source_task']))['data'],lambda t:t['state']=='COMPLETED','Source sync incomplete',120)
        def rows():return api('/order/exchanges/platform?'+urllib.parse.urlencode({'channelId':channel,'pageSize':50}))['data']['rows']
        records={r['dispute_id']:r for r in rows()}
        p1=records[payload(1)['dispute_id']];p2=records[payload(2)['dispute_id']]
        assert records[payload(3)['dispute_id']]['source_state']=='INVALID'
        assert records[payload(4)['dispute_id']]['conversion_state']=='SKIPPED'
        assert all('payload' not in r and 'TEST-CONTACT-NOT-FOR-LIST' not in json.dumps(r) for r in records.values())
        if p1.get('business_exchange_id'):c['manual_verified']=True;case.save()
        if not c.get('manual_verified'):
            assert sql(f"SELECT COUNT(*) FROM oms_exchange WHERE company_code='QM' AND channel_id={channel}",ORDER)=='0'
            assert api('/order/exchanges/platform/'+str(p2['id'])+'/convert',{})['data']['state']=='FAILED'
        order=case.order();assert order.get('exchange_block_reason') and any(i['domain']=='PLATFORM_EXCHANGE' for i in order['issues'])
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1800,'height':1120});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];calls=[]
            page.on('pageerror',lambda e:errors.append(str(e)));page.on('request',lambda r:calls.append(r.url))
            page.goto(BASE+'/oms-order/platformExchanges?'+urllib.parse.urlencode({'number':payload(1)['dispute_id']}),wait_until='networkidle')
            expect(page.get_by_role('heading',name='平台换货',exact=True,level=1)).to_be_visible()
            assert not any('/capability/' in u for u in calls),'Listing must not probe platform'
            page.screenshot(path=str(OUT/'platform-list.png'),animations='disabled')
            page.locator('.platform-exchanges .el-table__fixed-right .el-table__row').get_by_role('button',name='详情',exact=True).click()
            detail=page.get_by_role('dialog',name='平台换货详情',exact=True);expect(detail).to_be_visible()
            if not p1.get('business_exchange_id'):
                detail.get_by_role('button',name='转业务换货单',exact=True).click()
                expect(detail.get_by_role('button',name='查看业务换货',exact=True)).to_be_visible()
            detail.get_by_role('tab',name='来源版本与变更',exact=True).click()
            detail.get_by_role('button',name='查看本次原文',exact=True).first.click()
            expect(page.get_by_role('dialog',name='平台换货来源原文',exact=True)).to_be_visible()
            page.get_by_role('dialog',name='平台换货来源原文',exact=True).get_by_role('button',name='Close',exact=True).click()
            detail.get_by_role('tab',name='业务关联与审核方案',exact=True).click()
            page.screenshot(path=str(OUT/'platform-detail.png'),animations='disabled')
            detail.get_by_role('button',name='查看业务换货',exact=True).click()
            expect(page.get_by_role('heading',name='业务换货',exact=True,level=2)).to_be_visible()
            expect(page.get_by_text('平台来源',exact=False).first).to_be_visible()
            page.goto(BASE+'/oms-order/platformExchanges?number='+payload(2)['dispute_id'],wait_until='networkidle')
            expect(page.locator('.platform-exchanges .el-table__body-wrapper').get_by_text('转单失败',exact=True)).to_be_visible()
            page.screenshot(path=str(OUT/'conversion-failed.png'),animations='disabled')
            assert not errors,errors
            browser.close()
        c['manual_verified']=True;case.save()
        p1=next(r for r in rows() if r['dispute_id']==payload(1)['dispute_id'])
        original_business=p1['business_exchange_id']
        assert api('/order/exchanges/platform/'+str(p1['id'])+'/convert',{})['data']['id']==original_business
        if not c.get('update_verified'):
            changed=payload(1);changed['modified']=(datetime.now()+timedelta(seconds=2)).strftime('%Y-%m-%d %H:%M:%S');changed['num']=3;seed(changed)
            updated=api('/order/exchanges/platform/'+str(p1['id'])+'/refresh',{})['data'];assert updated['requested_quantity']==3 and len(updated['snapshots'])==2 and updated['business_exchange_id']==original_business
            c['update_verified']=True;case.save()
        if not c.get('auto_verified'):
            seed(payload(5));task=api('/order/exchanges/sync',{'channel_id':channel,'dispute_ids':payload(5)['dispute_id'],'request_key':'PLATFORM_AUTO_'+c['case_id']})['data']['id']
            wait_for(lambda:api('/order/exchanges/tasks/'+str(task))['data'],lambda t:t['state']=='COMPLETED','Second source sync incomplete',120)
            cfg=configs();api('/order/exchanges/configs',{**cfg,'auto_convert':1})
            wait_for(rows,lambda rs:any(r['dispute_id']==payload(5)['dispute_id'] and r['business_exchange_id'] for r in rs),'Automatic conversion incomplete',120)
            cfg=configs();api('/order/exchanges/configs',{**cfg,'auto_convert':0});c['auto_verified']=True;case.save()
        assert configs()['cursor_time']==cursor and configs()['enabled']==0 and configs()['write_enabled']==0
        assert stock(c)==before_stock,'Source sync/conversion changed inventory'
        assert sql(f"SELECT COUNT(*) FROM oms_order_shipment WHERE company_code='QM' AND order_id={c['order_id']}",ORDER)=='0'
        report={'channel_id':channel,'order_id':c['order_id'],'order_sn':c['order_sn'],'task_id':c['source_task'],'sources':rows(),'manual_conversion_idempotent':True,'auto_conversion_verified':True,'inventory_unchanged':True,'automatic_cursor_unchanged':True,'platform_write_enabled':False,'browser_errors':[],'url':BASE+'/oms-order/platformExchanges'}
        (OUT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({k:report[k] for k in ['channel_id','order_sn','task_id','manual_conversion_idempotent','auto_conversion_verified','inventory_unchanged']},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
