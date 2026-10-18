"""Read-only acceptance using retained platform/refund/exchange fixtures."""
import json
from pathlib import Path
from urllib.parse import urlencode, urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import wait_for

OUT=Path(__file__).resolve().parents[2]/'outputs/platform-documents'
TID='99420261017223725'
REFUND_TID='99420261017204001'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    token=login()
    checks=[]
    def api(path):return request(path,token=token,method='GET')['data']
    try:
        def ready():
            try:return api('/order/platform-documents/options')
            except (AssertionError, OSError):return None
        wait_for(ready,lambda x:x is not None,'Platform documents service not ready',180)
        for kind,expected in [('ORDER',1),('REFUND',0),('EXCHANGE',4)]:
            result=api('/order/platform-documents/list?'+urlencode({'kind':kind,'platformTid':TID,'channelId':57}))
            assert result['total']==expected,(kind,result['total'])
        related=api('/order/platform-documents/related?'+urlencode({'platformTid':TID,'channelId':57}))
        assert [len(g['rows']) for g in related['groups']]==[1,0,4]
        assert 'payload' not in json.dumps(related)
        checks.append('Exact shared filters and bounded local relations across all three source types')
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1800,'height':1120})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[];calls=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.on('request',lambda r:calls.append(r.url))
            def goto(path,query=None):
                page.goto(BASE+path+('?' +urlencode(query) if query else ''),wait_until='networkidle')
            def body():return page.locator('.platform-documents .app-container .el-table__body-wrapper').first
            def rows(n):expect(body().locator('.el-table__row')).to_have_count(n)
            def tab(name):
                page.locator('.platform-documents > .el-tabs').get_by_role('tab',name=name,exact=True).click()
                page.wait_for_load_state('networkidle')
            goto('/oms-order/platformDocuments',{'platformTid':TID,'channelId':57})
            expect(page.get_by_role('heading',name='平台单据',level=1,exact=True)).to_be_visible()
            rows(1)
            initial=[parse_qs(urlparse(u).query)['kind'][0] for u in calls if '/platform-documents/list?' in u]
            assert initial==['ORDER'],initial
            assert not any('/platform-documents/related?' in u or '/capability/' in u for u in calls)
            assert page.locator('.sidebar-container a[href="/oms-order/platformDocuments"]').count()==1
            for old in ['platform','platformRefunds','platformExchanges']:
                assert page.locator(f'.sidebar-container a[href="/oms-order/{old}"]').count()==0
            page.screenshot(path=str(OUT/'orders.png'),animations='disabled')
            tab('平台换货');rows(4)
            expect(page.get_by_placeholder('填写完整平台主单号')).to_have_value(TID)
            page.locator('.platform-exchanges').get_by_role('button',name='更多筛选',exact=True).click()
            page.locator('.platform-exchanges .el-form-item').filter(has_text='来源状态').locator('input').click()
            page.locator('.el-select-dropdown:visible').get_by_text('资料异常',exact=True).click()
            page.locator('.platform-exchanges').get_by_role('button',name='查询',exact=True).click();rows(1)
            tab('平台订单');rows(1)
            tab('平台换货');rows(1)
            page.locator('.platform-exchanges').get_by_role('button',name='清除本类筛选',exact=True).click();rows(4)
            page.screenshot(path=str(OUT/'exchanges.png'),animations='disabled')
            tab('平台退款');rows(0)
            expect(page.get_by_placeholder('填写完整平台主单号')).to_have_value(TID)
            page.get_by_role('button',name='查看原单关联',exact=True).click()
            drawer=page.locator('.el-drawer').filter(has_text='原单关联单据')
            expect(drawer).to_be_visible()
            expect(drawer.get_by_text('HH125604afa30544078541c4dc9ce14403',exact=True).first).to_be_visible()
            expect(drawer.get_by_text('SOb28157aa28b14d27a4041c4a3f065efd',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'relations.png'),animations='disabled')
            drawer.locator('.relations-body > section').nth(2).locator('.el-table__fixed-right').get_by_role('button',name='平台详情',exact=True).first.click()
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('dialog',name='平台换货详情',exact=True)).to_be_visible()
            checks.append('Lazy first tab, common filters, cached type filters, related source detail and one visible menu')
            goto('/oms-order/platformRefunds',{'channelId':55,'tid':REFUND_TID,'id':1})
            assert '/platformDocuments?' in page.url and 'type=REFUND' in page.url
            rows(2)
            expect(page.get_by_role('dialog',name='平台退款详情',exact=True)).to_be_visible()
            goto('/oms-order/platformExchanges',{'number':'9992026101722372502'})
            assert 'type=EXCHANGE' in page.url
            rows(1)
            expect(body().get_by_text('转单失败',exact=True)).to_be_visible()
            goto('/oms-order/platform',{'id':145,'tid':TID,'channelId':57})
            assert 'type=ORDER' in page.url
            expect(page.locator('.el-drawer[aria-label="平台订单详情"]')).to_be_visible()
            checks.append('All three legacy links preserve type, original order, source ID and unmatched application filters')
            goto('/oms-order/platformDocuments',{'type':'REFUND','platformTid':REFUND_TID,'channelId':55})
            rows(2)
            page.get_by_role('button',name='查看原单关联',exact=True).click()
            expect(drawer.get_by_text('TKb7d3ce465da7470680308f8aa5a5e84c',exact=True).first).to_be_visible()
            expect(drawer.get_by_text('TKd6e70d8509394025adc749eea4b636c2',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'refund-relations.png'),animations='disabled')
            drawer.get_by_role('button',name='关闭',exact=True).click()
            page.set_viewport_size({'width':1366,'height':900})
            page.screenshot(path=str(OUT/'refunds-1366.png'),animations='disabled')
            assert not errors,errors
            checks.append('Refund TK associations, desktop layout, zero browser errors and zero platform writes')
            browser.close()
        (OUT/'verification.json').write_text(json.dumps({'checks':checks,'read_only':True},ensure_ascii=False,indent=2),encoding='utf-8')
        print('PASS platform document browser acceptance:',len(checks),'groups')
    finally:
        request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
