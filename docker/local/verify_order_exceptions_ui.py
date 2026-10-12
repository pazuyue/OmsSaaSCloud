"""Real UI acceptance including manual mapping to an existing disposable OMS SKU."""
import json,time,subprocess,re
from datetime import datetime,timedelta
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,LOGS
BASE='http://127.0.0.1:8088'
def main():
    token=login();channel=None;prefix='EXUI'+str(int(time.time()))
    def fixture(mode):
        return subprocess.check_output(['wsl.exe','-e','python3','/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local/order_exception_fixture.py',mode,str(channel),prefix],text=True,encoding='utf-8').strip()
    def orders():return request('/order/orders/exceptions/list?channelId='+str(channel),token=token)['rows']
    try:
        request('/channel/channel',{'channelName':prefix,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1},token,'POST')
        channel=request('/channel/channel/list?channelName='+prefix,token=token)['rows'][0]['channelId']
        request('/order/orders/configs',{'channel_id':channel,'source_key':'sysinfo100','seller_nick':prefix,'enabled':0,'review_mode':'AUTO','manual_match_review':1,'auto_convert':1,'allowed_trade_types':['fixed'],'allow_public_stock':0,'interval_seconds':30,'initial_time':(datetime.now()-timedelta(hours=1)).strftime('%Y-%m-%d %H:%M:%S')},token,'POST')
        ids=json.loads(fixture('create'))
        jobs=request('/schedule/job/list?invokeTarget=orderPullTask.scanAll()',token=token)['rows']
        job=next(j for j in jobs if j['invokeTarget']=='orderPullTask.scanAll()')
        if str(job['status'])=='1':request('/schedule/job/run',{'jobId':job['jobId'],'jobGroup':job['jobGroup']},token,'PUT')
        sample=request('/goods/info/list?pageNum=1&pageSize=1',{},token,'POST')['rows'][0]
        target=prefix+'TARGET'
        request('/goods/info',{'skuSn':target,'goodsSn':prefix,'goodsName':prefix+'测试商品','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':0,'validity':30},token,'POST')
        for attempt in range(60):
            rows=orders()
            if len(rows)==3:break
            time.sleep(1)
        assert len(rows)==3,rows
        assert {r['exception_category'] for r in rows}=={'PRODUCT','CONFIG','DATA'}
        assert 'num' in next(r for r in rows if r['exception_category']=='DATA')['conversion_error']
        assert all('exception_details' not in r for r in rows),'List should not fetch full detail JSON'
        assert request('/order/orders/exceptions/list?channelId='+str(channel)+'&category=PRODUCT&sku='+prefix+'A',token=token)['total']==1
        assert request('/order/orders/exceptions/list?channelId='+str(channel)+'&waitingMinutes=60',token=token)['total']==0
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1500,'height':1000})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            def open_list():
                page.goto(BASE+'/oms-order/exceptions?view=exceptions',wait_until='networkidle')
                page.locator('.order-filters .el-form-item').filter(has=page.locator('label',has_text=re.compile('^店铺$'))).locator('.el-select').click()
                page.locator('.el-select-dropdown:visible').get_by_text(prefix,exact=True).click()
                page.locator('.order-filters button.el-button--primary').click()
                expect(page.locator('.order-toolbar')).to_contain_text('共 3 条')
            open_list()
            expect(page.get_by_text('异常分类',exact=True).first).to_be_visible()
            page.screenshot(path=str(LOGS/'order-exceptions-list.png'),full_page=True)
            page.locator('.order-workspace .el-table__body-wrapper').first.get_by_role('button',name=prefix[4:]+'0',exact=True).click()
            drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('PRODUCT_INVALID',exact=False)).to_be_visible()
            expect(drawer.get_by_text(prefix+'A',exact=True).first).to_be_visible();expect(drawer.get_by_text(prefix+'B',exact=True).first).to_be_visible()
            page.screenshot(path=str(LOGS/'order-exceptions-detail.png'),full_page=True)
            drawer.get_by_role('button',name='匹配商品',exact=True).first.click()
            matching=page.get_by_role('dialog',name='匹配 OMS 商品',exact=True);expect(matching).to_be_visible()
            expect(matching.get_by_role('button',name='确认匹配并重试',exact=True)).to_be_disabled()
            matching.get_by_role('button',name='取消',exact=True).click()
            open_list()
            page.locator('.el-table__fixed-right').get_by_role('button',name='转单设置',exact=True).click()
            dialog=page.get_by_role('dialog',name='店铺转单设置',exact=True);expect(dialog).to_be_visible();expect(dialog).to_contain_text(prefix);expect(dialog.get_by_text('审单方式',exact=True)).to_be_visible()
            dialog.get_by_role('button',name='取消',exact=True).click()
            open_list()
            page.locator('.order-workspace .el-table__header-wrapper').first.locator('.el-checkbox').click()
            page.get_by_role('button',name='批量重试（3）',exact=True).click()
            result=page.get_by_role('dialog',name='本次重试结果',exact=True);expect(result).to_be_visible()
            # Lease checks may skip a busy shop; otherwise all three settle back to concrete exceptions.
            for attempt in range(20):
                page.wait_for_timeout(1000)
                if result.get_by_text('待处理',exact=True).count()==0 and result.get_by_text('处理中',exact=True).count()==0:break
            assert result.get_by_text('转单异常',exact=True).count()+result.get_by_text('未提交',exact=True).count()==3
            assert any(r['retry_count']>0 for r in orders()),'No accepted retry'
            page.screenshot(path=str(LOGS/'order-exceptions-retry.png'),full_page=True)
            result.get_by_role('button',name='关闭',exact=True).click()
            open_list()
            page.locator('.order-workspace .el-table__body-wrapper').first.get_by_role('button',name=prefix[4:]+'0',exact=True).click()
            drawer=page.locator('.el-drawer:visible');drawer.get_by_role('button',name='匹配商品',exact=True).first.click()
            matching=page.get_by_role('dialog',name='匹配 OMS 商品',exact=True)
            for line in range(2):
                matching.get_by_role('button',name='选择商品',exact=True).first.click()
                picker=page.get_by_role('dialog',name='选择 OMS 现有商品',exact=True)
                picker.get_by_placeholder('输入完整 SKU').fill(target);picker.locator('button.el-button--primary').click()
                expect(picker.get_by_text(target,exact=True)).to_be_visible();picker.get_by_role('button',name='选择',exact=True).first.click();expect(picker).to_be_hidden()
            matching.get_by_placeholder('说明为何将平台商品匹配到所选 OMS SKU').fill('验收：已核对平台商品规格，仅匹配当前订单')
            page.screenshot(path=str(LOGS/'order-product-matching.png'),full_page=True)
            original=request(f'/order/orders/platform/{ids[0]}/payload',token=token)['data']
            matching.get_by_role('button',name='确认匹配并重试',exact=True).click()
            for attempt in range(45):
                detail=request(f'/order/orders/platform/{ids[0]}',token=token)['data']
                if detail['conversion_status']=='SUCCESS':break
                time.sleep(1)
            assert detail['conversion_status']=='SUCCESS',detail.get('conversion_error')
            assert {l['outer_sku_id'] for l in detail['lines']}=={prefix+'A',prefix+'B'}
            assert all(l['manual_sku_sn']==target and l['sku_sn']==target and l['demand_prepared']==1 for l in detail['lines'])
            assert original==request(f'/order/orders/platform/{ids[0]}/payload',token=token)['data']
            business=request('/order/orders/business/'+str(detail['business_order_id']),token=token)['data']
            assert business['inventory_status']=='WAIT_RESERVE'
            assert len(business['lines'])==2 and all(l['sku_sn']==target and l['quantity']==1 for l in business['lines'])
            assert business['demand_quantity']==2 and business['pending_quantity']==2 and business['sku_count']==1
            assert all(l['inventory_reason_code']=='PUBLIC_DISABLED' for l in business['lines'])
            page.goto(BASE+'/oms-order/business?view=business',wait_until='networkidle')
            page.get_by_placeholder('输入完整订单号').fill(business['order_sn'])
            page.locator('.order-filters button.el-button--primary').click()
            expect(page.locator('.order-toolbar')).to_contain_text('共 1 条')
            expect(page.get_by_text('预占进度（件）',exact=True).first).to_be_visible()
            expect(page.get_by_text('剩余待预占 2 件',exact=True).first).to_be_visible()
            expect(page.get_by_text('需求 2 件 · 共 1 个 SKU',exact=True).first).to_be_visible()
            page.screenshot(path=str(LOGS/'business-orders-list.png'),full_page=True)
            page.get_by_role('button',name=business['order_sn'],exact=True).first.click()
            drawer=page.locator('.el-drawer:visible')
            expect(drawer.get_by_text('公共库存补占',exact=True)).to_be_visible()
            expect(drawer.get_by_text(target,exact=True).first).to_be_visible()
            expect(drawer.get_by_text('平台商品：',exact=False).first).to_be_visible()
            drawer.get_by_text('查看已预占库存来源',exact=True).click()
            expect(drawer.get_by_text('暂无已预占库存来源',exact=True)).to_be_visible()
            page.screenshot(path=str(LOGS/'business-orders-detail.png'),full_page=True)
            drawer.get_by_role('button',name='重新尝试预占',exact=True).click()
            retry=page.get_by_role('dialog',name='重新预占结果',exact=True)
            expect(retry).to_be_visible()
            expect(retry.get_by_text('仍待预占',exact=True)).to_be_visible(timeout=20000)
            page.screenshot(path=str(LOGS/'business-orders-retry.png'),full_page=True)
            final=request('/order/orders/business/'+str(business['id']),token=token)['data']
            assert final['stock_attempt_status']=='DONE' and final['reserved_quantity']==0
            assert any(l['action']=='STOCK_RETRY_REQUEST' for l in final['logs'])
            retry.get_by_role('button',name='关闭',exact=True).click()
            assert final['review_status']=='PENDING' and final['review_policy']=='MANUAL_MATCH'
            # Explicitly refresh the version used by review actions.
            drawer.get_by_role('button',name='刷新详情',exact=True).click()
            expect(drawer.get_by_role('button',name='审核通过',exact=True)).to_be_visible()
            drawer.get_by_role('button',name='挂起订单',exact=True).click()
            review=page.get_by_role('dialog',name='挂起订单',exact=True)
            review.get_by_placeholder('请填写挂起原因（必填）').fill('验收：核对订单，保留库存需求')
            review.get_by_role('button',name='确认挂起订单',exact=True).click()
            expect(review.get_by_text('成功',exact=True)).to_be_visible()
            review.get_by_role('button',name='关闭',exact=True).click()
            expect(drawer.get_by_role('button',name='解除挂起',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='重新尝试预占',exact=True)).to_have_count(0)
            drawer.get_by_role('button',name='审核通过',exact=True).click()
            review=page.get_by_role('dialog',name='审核通过',exact=True)
            review.get_by_role('button',name='确认审核通过',exact=True).click()
            expect(review.get_by_text('成功',exact=True)).to_be_visible()
            review.get_by_role('button',name='关闭',exact=True).click()
            after=request('/order/orders/business/'+str(business['id']),token=token)['data']
            assert after['review_status']=='APPROVED' and after['manual_hold']==1 and not after['release_eligible']
            assert after['pending_quantity']==2 and after['reserved_quantity']==0
            page.screenshot(path=str(LOGS/'business-order-review-held.png'),full_page=True)
            drawer.get_by_role('button',name='解除挂起',exact=True).click()
            review=page.get_by_role('dialog',name='解除挂起',exact=True)
            review.get_by_role('button',name='确认解除挂起',exact=True).click()
            expect(review.get_by_text('成功',exact=True)).to_be_visible()
            review.get_by_role('button',name='关闭',exact=True).click()
            after=request('/order/orders/business/'+str(business['id']),token=token)['data']
            assert after['review_status']=='APPROVED' and after['manual_hold']==0
            assert {'ORDER_HOLD','REVIEW_APPROVE','ORDER_RESUME'} <= {l['action'] for l in after['logs']}
            expect(review).to_be_hidden()
            drawer.get_by_role('button',name='刷新详情',exact=True).click()
            expect(drawer.get_by_role('button',name='作废订单',exact=True)).to_be_visible()
            drawer.get_by_role('button',name='作废订单',exact=True).click()
            void_dialog=page.get_by_role('dialog',name='作废业务订单',exact=True)
            expect(void_dialog.get_by_role('button',name='确认作废',exact=True)).to_be_disabled()
            void_dialog.get_by_placeholder('请填写作废原因（必填）；重试时保留原作废原因').fill('验收：整单作废，取消两行待预占需求')
            void_dialog.get_by_text('我已确认平台订单的后续处理，知悉仅作废 OMS 订单且无法恢复',exact=True).click()
            page.screenshot(path=str(LOGS/'business-order-void-confirm.png'),full_page=True)
            void_dialog.get_by_role('button',name='确认作废',exact=True).click()
            expect(void_dialog.get_by_text('已作废',exact=True)).to_be_visible(timeout=25000)
            void_dialog.get_by_role('button',name='关闭',exact=True).click()
            expect(void_dialog).to_be_hidden()
            final=request('/order/orders/business/'+str(business['id']),token=token)['data']
            assert final['status']=='VOIDED' and final['inventory_status']=='RELEASED'
            assert final['pending_quantity']==0 and final['reserved_quantity']==0 and final['demand_quantity']==2
            assert not final['can_reserve'] and not final['can_review'] and not final['can_resume'] and not final['can_void']
            assert any(l['action']=='VOID_COMPLETE' for l in final['logs'])
            drawer.get_by_role('button',name='刷新详情',exact=True).click()
            expect(drawer.get_by_text('作废原因',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='作废订单',exact=True)).to_have_count(0)
            drawer.locator('.el-drawer__body').evaluate('(el) => el.scrollTop = 0')
            page.screenshot(path=str(LOGS/'business-order-voided.png'),full_page=True)
            assert not errors,errors
            browser.close()
        print('PASS exception UI/API: filters, batch retry, two-line existing-SKU selection, immutable payload, business conversion and shortage demand')
    finally:
        if channel:print(fixture('delete'))
        request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
