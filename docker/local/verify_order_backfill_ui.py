"""Backfill UI acceptance. All submissions/retries are intercepted; live APIs are read-only."""
import json
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

OUTPUT=Path(__file__).resolve().parents[2]/'outputs/order-backfill'
LONG=['9007199254740993','9007199254740994','987654321012345678']

def main():
    OUTPUT.mkdir(parents=True,exist_ok=True);token=login()
    try:
        catalog=request('/order/orders/catalog',token=token)['data'];shop=next(s for s in catalog['channels'] if s['pull_configured'])
        options=request('/order/orders/backfill-options',token=token)['data'];assert options['max_tids']==100
        assert 'rows' in request('/order/orders/tasks/list?pageNum=1&pageSize=1',token=token)
        submitted=[];retries=[]
        base={'company_code':'QM','channel_id':shop['channel_id'],'channel_name':shop['channel_name'],'task_type':'MANUAL','status':'PARTIAL','start_time':'2026-10-13 09:45:00','end_time':'2026-10-13 09:45:00','page_time':'2026-10-13 09:45:00','operator_name':'UI验收','error_message':'1 条补拉失败，可仅重试失败项','pulled_count':1,'inserted_count':1,'duplicate_count':0}
        tasks={900001:dict(base,id=900001,pull_mode='TID',requested_tids=json.dumps(LONG),results=[{'result':'NEW','count':1},{'result':'NOT_FOUND','count':1},{'result':'FAILED','count':1}]),900002:dict(base,id=900002,pull_mode='TIME',status='SUCCESS',start_time='2026-06-01 00:00:00',end_time='2026-10-13 09:45:00',page_time='2026-10-13 09:45:00',error_message='',results=[{'result':'NEW','count':1}]),900003:dict(base,id=900003,pull_mode='TID',status='SUCCESS',requested_tids=json.dumps([LONG[2]]),error_message='',results=[{'result':'NEW','count':1}])}
        rows={900001:[{'id':910001,'platform_tid':LONG[0],'result':'NEW','message':'已新增平台订单','platform_order_id':990001,'conversion_status':'WAITING','change_pending':0},{'id':910002,'platform_tid':LONG[1],'result':'NOT_FOUND','message':'该店铺推送库未找到此单号；未查询天猫平台接口'},{'id':910003,'platform_tid':LONG[2],'result':'FAILED','message':'平台数据保存失败，请重试该单号'}],900002:[{'id':910004,'platform_tid':LONG[0],'result':'NEW','message':'已新增平台订单','conversion_status':'WAITING'}],900003:[{'id':910005,'platform_tid':LONG[2],'result':'NEW','message':'已新增平台订单','conversion_status':'WAITING'}]}
        def route(handler):
            req=handler.request;path=urlparse(req.url).path;params=parse_qs(urlparse(req.url).query)
            if path.endswith('/backfill-options'):reply={'code':200,'data':{'server_time':'2026-10-13 09:45:00','max_tids':100}}
            elif path.endswith('/backfill'):
                assert req.method=='POST';body=req.post_data_json;submitted.append(body)
                if body['mode']=='TID':assert body['tids']==LONG and all(isinstance(t,str) for t in body['tids']);reply={'code':200,'data':{'task_ids':[900001],'mode':'TID','tid_count':3,'range_clamped':False}}
                else:assert body['start_time']=='2026-06-01 00:00:00' and body['end_time']=='2026-10-13 10:00:00';reply={'code':200,'data':{'task_ids':[900002],'mode':'TIME','range_clamped':True,'start_time':body['start_time'],'end_time':'2026-10-13 09:45:00'}}
            elif '/tasks/90000' in path:
                ident=int(path.split('/tasks/')[1].split('/')[0])
                if path.endswith('/retry-items'):assert req.method=='POST';retries.append(req.post_data_json);assert req.post_data_json.get('item_ids')==[910003];reply={'code':200,'data':{'task_ids':[900003],'mode':'TID'}}
                elif path.endswith('/items'):
                    items=rows[ident];result=params.get('result',[''])[0]
                    if result:items=[r for r in items if r['result']==result]
                    reply={'code':200,'rows':items,'total':len(items)}
                else:reply={'code':200,'data':tasks[ident]}
            else:
                assert req.method=='GET','Unexpected live business write: '+path;handler.continue_();return
            handler.fulfill(status=200,content_type='application/json',body=json.dumps(reply,ensure_ascii=False))
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)));page.route('**/prod-api/order/orders/**',route)
            page.goto(BASE+'/oms-order/platform?view=platform',wait_until='networkidle')
            page.screenshot(path=str(OUTPUT/'entry-debug.png'),full_page=False)
            page.get_by_role('button',name='补拉订单').click();dialog=page.locator('.el-dialog:visible').filter(has_text='补拉方式')
            expect(dialog.get_by_role('button',name='近7天',exact=True)).to_be_visible();dialog.get_by_text('按平台单号',exact=True).click()
            dialog.locator('.el-select .el-input__suffix').click();page.locator('.el-select-dropdown:visible').get_by_text(shop['channel_name'],exact=True).click()
            textarea=dialog.locator('textarea');textarea.fill('\n'.join(LONG+[LONG[0],'12e3']))
            expect(dialog.get_by_text('有效 3 个',exact=False)).to_be_visible();dialog.get_by_role('button',name='提交补拉',exact=True).click();assert not submitted
            textarea.fill('\n'.join(LONG+[LONG[0]]));expect(dialog.locator('.el-form-item__error')).to_have_count(0);page.screenshot(path=str(OUTPUT/'tid-validation.png'),full_page=False)
            dialog.get_by_role('button',name='提交补拉',exact=True).click();drawer=page.locator('.el-drawer:visible').filter(has_text='补拉任务结果')
            expect(drawer.get_by_text(LONG[0],exact=True)).to_be_visible();expect(drawer.get_by_text('源库未找到',exact=True)).to_be_visible()
            page.wait_for_function('(element)=>Math.abs(element.getBoundingClientRect().right-window.innerWidth)<2',arg=drawer.element_handle());page.screenshot(path=str(OUTPUT/'tid-results.png'),full_page=False)
            failed=drawer.locator('.el-table__body-wrapper tbody tr').filter(has_text=LONG[2]);failed.locator('.el-checkbox').click();drawer.get_by_role('button',name='重试选中失败项（1）',exact=True).click()
            expect(drawer.get_by_text(LONG[2],exact=True)).to_be_visible();expect(drawer.get_by_text('新增成功',exact=True)).to_be_visible();assert len(retries)==1
            drawer.locator('.el-drawer__close-btn').click();page.get_by_role('button',name='补拉订单').click();dialog=page.locator('.el-dialog:visible').filter(has_text='补拉方式')
            dialog.locator('.el-select .el-input__suffix').click();page.locator('.el-select-dropdown:visible').get_by_text(shop['channel_name'],exact=True).click()
            dialog.get_by_text('补拉方式',exact=True).click()
            inputs=dialog.locator('.el-range-input');inputs.nth(0).fill('2026-06-01 00:00:00');inputs.nth(0).press('Tab');inputs.nth(1).fill('2026-10-13 10:00:00');inputs.nth(1).press('Tab')
            expect(dialog.get_by_text('结束时间晚于当前时间',exact=False)).to_be_visible();dialog.get_by_text('补拉方式',exact=True).click();page.screenshot(path=str(OUTPUT/'time-range-clamp.png'),full_page=False)
            dialog.get_by_role('button',name='提交补拉',exact=True).click();drawer=page.locator('.el-drawer:visible').filter(has_text='补拉任务结果')
            expect(drawer.get_by_text('2026-06-01 00:00:00 至 2026-10-13 09:45:00（不含）',exact=False)).to_be_visible()
            assert len(submitted)==2 and len({r['request_key'] for r in submitted})==2;assert not errors,errors
            browser.close()
        OUTPUT.joinpath('verification.json').write_text(json.dumps({'live_writes':0,'long_tid_precision':True,'invalid_tid_blocked':True,'duplicates_removed':True,'future_end_clamped':True,'over_90_day_range_submitted':True,'targeted_failed_item_retry':True,'live_task_list_route':True},indent=2),encoding='utf8')
        print('PASS long TID input, validation, duplicate removal, results, selected failure retry and large/future time range; no live submissions')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
