"""Retained local intake acceptance: isolated seller, empty JDP and trusted simulation.
No original/customer orders, platform consents, warehouse receipts or stock quantities are changed.
"""
import json
import time
import urllib.parse
from datetime import datetime,timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for

HERE=Path(__file__).resolve().parent
OUT=HERE.parents[1]/'outputs/order-intake'
ORDER='qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    manifest=OUT/'case.json'
    c=json.loads(manifest.read_text(encoding='utf-8')) if manifest.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S')}
    c.setdefault('name','[接入汇总验收] '+c['stamp'])
    c.setdefault('seller','INTAKE_TEST_'+c['stamp'])
    c.setdefault('initial',(datetime.now()-timedelta(hours=1)).strftime('%Y-%m-%d %H:%M:%S'))
    def save():manifest.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
    save()
    credential=login()
    try:
        def api(path,body=None):return request(path,token=credential,payload=body,method='POST' if body is not None else 'GET')
        def ready():
            try:return api('/order/intake/overview')['data']
            except AssertionError:return None
        wait_for(ready,lambda r:r is not None,'Unified intake service not ready',90)
        if not c.get('channel_id'):
            api('/channel/channel',{'channelName':c['name'],'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1})
            c['channel_id']=int(sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name="+quote(c['name']),'qm_oms_saas_channel'));save()
        channel=c['channel_id']
        source=next(s for s in api('/order/orders/sources')['data'] if s['database_name']=='refund_acceptance_source' and s['enabled'])
        assert source['host']=='mysql'
        if not c.get('configured'):
            api('/order/orders/configs',{'channel_id':channel,'source_key':source['source_key'],'seller_nick':c['seller'],'confirm_seller_mapping':1,'enabled':0,'auto_convert':0,'allowed_trade_types':['fixed'],'allow_public_stock':0,'interval_seconds':30,'initial_time':c['initial']})
            api('/order/refunds/configs',{'channel_id':channel,'source_key':source['source_key'],'seller_nick':c['seller'],'enabled':0,'auto_convert':0,'interval_seconds':30,'initial_time':c['initial'],'revision':0})
            app=next(a for a in api('/channel/platform/apps')['data'] if int(a.get('simulation_mode',0))==1)
            api(f'/channel/platform/shops/{channel}/binding',{'app_id':app['id'],'expected_shop_id':'700'+str(channel)})
            api(f'/channel/platform/shops/{channel}/simulate',{})
            cap=api(f'/order/exchanges/capability/{channel}')['data']
            assert cap['simulation'] is True
            api(f'/channel/exchangePlatform/{channel}/access',{'revision':cap['revision'],'read_enabled':1,'write_enabled':0})
            api('/order/exchanges/configs',{'channel_id':channel,'enabled':0,'write_enabled':0,'initial_time':c['initial'],'revision':0})
            c['configured']=True;save()
        for table in ['jdp_tb_trade','jdp_tb_refund']:
            assert sql('SELECT COUNT(*) FROM '+table+' WHERE seller_nick='+quote(c['seller']),'refund_acceptance_source')=='0'
        def seed(index):
            dispute='999'+c['stamp']+str(index).zfill(2)
            p={'dispute_id':dispute,'biz_order_id':'997'+c['stamp']+str(index).zfill(2),'num':1,'status':'1','good_status':'已发货','modified':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'[接入验收] 原规格','exchange_sku':'[接入验收] 目标规格','speed_exchange':0,'refund_phase':'aftersale'}
            sql(f"INSERT IGNORE INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{channel},{quote(dispute)},{quote(json.dumps(p,ensure_ascii=False))},{quote(p['modified'])})",'qm_oms_saas_channel')
            return dispute
        first='999'+c['stamp']+'01';second='999'+c['stamp']+'02'
        if not c.get('tasks'):
            seed(1)
            order=api('/order/orders/backfill',{'channel_ids':[channel],'mode':'TIME','start_time':c['initial'],'end_time':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'request_key':'INTAKE_ORDER_'+c['stamp']})['data']['task_ids'][0]
            refund=api('/order/refunds/sync',{'channel_id':channel,'mode':'IDS','refund_ids':['998'+c['stamp']+'01'],'request_key':'INTAKE_REFUND_'+c['stamp']})['data']['id']
            exchange=api('/order/exchanges/sync',{'channel_id':channel,'dispute_ids':first+','+second,'request_key':'INTAKE_EXCHANGE_'+c['stamp']})['data']['id']
            c['tasks']={'ORDER':order,'REFUND':refund,'EXCHANGE':exchange};save()
        exchange_id=c['tasks']['EXCHANGE']
        def task(kind):return api('/order/intake/tasks?'+urllib.parse.urlencode({'kind':kind,'taskId':c['tasks'][kind]}))['data']['rows'][0]
        wait_for(lambda:task('ORDER'),lambda t:t['state']=='COMPLETED','Order backfill incomplete',120)
        wait_for(lambda:task('REFUND'),lambda t:t['state']=='COMPLETED','Refund backfill incomplete',120)
        exchange_task=wait_for(lambda:task('EXCHANGE'),lambda t:t['state'] in ['RETRY','FAILED','COMPLETED'],'Exchange sync did not settle',120)
        def first_logs():return api('/channel/platform/log-center?'+urllib.parse.urlencode({'numberType':'PLATFORM_EXCHANGE','number':first,'allTime':'true'}))['data']['rows']
        prior_ids={r['id'] for r in first_logs()}
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1680,'height':1120})
            context.add_cookies([{'name':'Admin-Token','value':credential,'url':BASE}])
            page=context.new_page();errors=[];calls=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.on('request',lambda r:calls.append(r.url))
            page.goto(BASE+'/oms-order/management',wait_until='networkidle')
            expect(page.get_by_role('heading',name='订单与售后接入',exact=True,level=1)).to_be_visible()
            assert not any('/capability/' in url for url in calls),'Overview probes platform per shop'
            page.get_by_placeholder('搜索店铺名称 / 渠道 ID').fill(str(channel))
            row=page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__body-wrapper .el-table__row').filter(has_text=c['name'])
            expect(row).to_have_count(1)
            page.screenshot(path=str(OUT/'shop-overview.png'),animations='disabled')
            page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__fixed-right .el-table__row').filter(has_text='接入设置').get_by_role('button',name='接入设置',exact=True).click()
            chooser=page.get_by_role('dialog',name='店铺数据接入',exact=True)
            expect(chooser.get_by_role('button',name='配置换货接入',exact=True)).to_be_visible()
            page.screenshot(path=str(OUT/'shop-configurations.png'),animations='disabled')
            chooser.get_by_role('button',name='配置退款接入',exact=True).click()
            refund_dialog=page.get_by_role('dialog',name='店铺退款接入',exact=True)
            expect(refund_dialog.get_by_text('退款读取检查',exact=True)).to_be_visible()
            refund_dialog.get_by_role('button',name='取消',exact=True).click()
            page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__fixed-right .el-table__row').filter(has_text='接入设置').get_by_role('button',name='接入设置',exact=True).click()
            chooser.get_by_role('button',name='配置换货接入',exact=True).click()
            exchange_dialog=page.get_by_role('dialog',name='换货接入设置',exact=True)
            expect(exchange_dialog.get_by_text('启用换货查询',exact=True)).to_be_visible()
            page.screenshot(path=str(OUT/'exchange-access.png'),animations='disabled')
            exchange_dialog.get_by_role('button',name='取消',exact=True).click()
            for kind,number_name in [('ORDER','平台订单号'),('REFUND','平台退款单号'),('EXCHANGE','平台换货单号')]:
                page.goto(BASE+'/oms-order/management?'+urllib.parse.urlencode({'kind':kind,'action':'backfill','channelId':channel}),wait_until='networkidle')
                backfill=page.get_by_role('dialog',name='补拉订单与售后数据',exact=True)
                backfill.get_by_role('radio',name='指定单号',exact=True).check()
                expect(backfill.locator('textarea')).to_have_attribute('placeholder',f'填写完整{number_name}，换行或逗号分隔；最多'+str({'ORDER':100,'REFUND':50,'EXCHANGE':20}[kind])+'个')
                backfill.get_by_role('button',name='取消',exact=True).click()
            page.goto(BASE+'/oms-order/management?tab=tasks&kind=EXCHANGE',wait_until='networkidle')
            page.get_by_placeholder('配合业务类型精确查询').fill(str(exchange_id))
            page.get_by_role('button',name='查询',exact=True).click()
            task_row=page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__body-wrapper .el-table__row')
            expect(task_row).to_have_count(1)
            if exchange_task['state']!='COMPLETED':
                expect(task_row.get_by_text('等待重试',exact=True)).to_be_visible()
                page.screenshot(path=str(OUT/'exchange-partial-result.png'),animations='disabled')
                seed(2)
                page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__fixed-right .el-table__row').get_by_role('button',name='重试',exact=True).click()
            result=wait_for(lambda:task('EXCHANGE'),lambda t:t['state']=='COMPLETED','Exchange retry incomplete',120)
            assert result['new_count']==2 and result['failed_count']==0
            assert {r['id'] for r in first_logs()}==prior_ids,'Retry re-read the already successful application'
            page.get_by_role('button',name='刷新任务',exact=True).click()
            page.locator('.order-management > .el-tabs .el-tab-pane:visible .el-table__fixed-right .el-table__row').get_by_role('button',name='结果',exact=True).click()
            detail=page.get_by_role('dialog',name='换货同步任务 #'+str(exchange_id),exact=True)
            expect(detail.locator('.el-table__row')).to_have_count(2)
            page.screenshot(path=str(OUT/'exchange-task-results.png'),animations='disabled')
            for route,button,kind_label in [('platform','补拉订单','订单'),('platformRefunds','补拉退款','退款'),('exchanges','同步平台申请','换货')]:
                page.goto(BASE+'/oms-order/'+route,wait_until='networkidle')
                page.get_by_role('button',name=button).click()
                shared=page.get_by_role('dialog',name='补拉订单与售后数据',exact=True)
                expect(shared).to_be_visible()
                expect(shared.locator('.el-radio-button.is-active')).to_have_text(kind_label)
                shared.get_by_role('button',name='取消',exact=True).click()
            assert not errors,errors
            browser.close()
        start=time.perf_counter();overview=api('/order/intake/overview')['data'];elapsed=round((time.perf_counter()-start)*1000)
        shop=next(r for r in overview['rows'] if r['channel_id']==channel)
        assert all(shop[k]['cursor_time']==c['initial'] for k in ['order','refund','exchange'])
        assert all(shop[k]['enabled']==0 for k in ['order','refund','exchange'])
        assert sql(f"SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND channel_id={channel}",ORDER)=='0'
        assert sql(f"SELECT COUNT(*) FROM oms_order_shipment WHERE company_code='QM' AND channel_id={channel}",ORDER)=='0'
        details=api('/order/exchanges/tasks/'+str(exchange_id))['data']
        assert len(details['items'])==2
        for item in details['items']:
            exchange=api('/order/exchanges/'+str(item['exchange_id']))['data']
            assert exchange['approved_version'] is None and exchange['stock_request'] is None
        report={'channel_id':channel,'shop':c['name'],'tasks':{k:task(k) for k in c['tasks']},'exchange_items':details['items'],'overview_ms':elapsed,'all_manual_cursors_unchanged':True,'retry_preserved_successful_item':True,'no_business_orders_or_shipments_created':True,'platform_write_enabled':False,'browser_errors':[],'url':BASE+'/oms-order/management'}
        (OUT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'channel_id':channel,'tasks':c['tasks'],'overview_ms':elapsed,'browser_errors':0,'manual_cursors_unchanged':True,'exchange_retry_completed':True},ensure_ascii=False))
    finally:request('/auth/logout',token=credential,method='DELETE')


if __name__=='__main__':main()
