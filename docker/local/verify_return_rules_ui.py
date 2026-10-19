"""Read-only acceptance of the paged shop rule list and contextual view/edit drawers."""
import json
import re
import urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import wait_for

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-return-rules'
PATH='/oms-order/afterSaleRules/returns'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    case=json.loads((ROOT/'outputs/refund-shared-thk/case.json').read_text('utf-8'))
    refund_id=case['refunds'][-1]['id'];token=login()
    def api(path):return request(path,token=token)['data']
    try:
        def ready():
            try:return api('/order/refunds/parcel-configs?pageNum=1&pageSize=20')
            except (AssertionError,OSError):return None
        listing=wait_for(ready,lambda r:r is not None,'Return rules list not ready',120)
        assert listing['total']>0 and len(listing['rows'])<=20
        refund=api('/order/refunds/business/'+str(refund_id));channel=refund['channel_id']
        target=api('/order/refunds/parcel-configs?channelId='+str(channel))['rows'][0]
        before=api('/order/refunds/parcel-configs/'+str(channel))['policy']
        defaults=api('/order/refunds/parcel-configs?configState=DEFAULT&pageSize=1')
        if defaults['rows']:
            default=defaults['rows'][0];assert default['configured'] is False and default['revision']==0
            assert default['enabled']==1 and default['auto_enabled']==0 and default['collect_seconds']==120
        def find(nodes,path):
            for node in nodes:
                if node['path']==path:return node
                found=find(node.get('children',[]),path)
                if found:return found
        routes=api('/system/menu/getRouters');group=find(routes,'afterSaleRules')
        assert group and find(group.get('children',[]),'returns')['component']=='oms/order/RefundReturnRules'
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1700,'height':1100})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[];writes=[];policy_gets=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            def track(r):
                path=urllib.parse.urlsplit(r.url).path
                if r.method=='POST' and '/refunds/' in path:writes.append(path)
                if r.method=='GET' and '/refunds/parcel-configs/' in path and path.rsplit('/',1)[-1].isdigit():policy_gets.append(path)
            page.on('request',track)
            page.goto(BASE+PATH,wait_until='networkidle')
            table=page.locator('.return-rules > .el-table')
            expect(table).to_be_visible();assert not policy_gets,'list must not fetch a config for every shop'
            assert table.locator('.el-table__body-wrapper tbody tr').count()==len(listing['rows'])
            expect(page.locator('.return-rules-drawer')).not_to_be_visible()
            expect(table).to_contain_text('严格自动匹配')
            page.screenshot(path=str(OUT/'shop-rule-list.png'),animations='disabled')
            # Exercise server paging rather than repeatedly slicing the first page.
            if listing['total']>20:
                first=table.locator('.el-table__body-wrapper tbody tr').first.inner_text()
                page.locator('.pagination-container .btn-next').click()
                expect(table.locator('.el-table__body-wrapper tbody tr').first).not_to_have_text(first)
            page.get_by_placeholder('店铺名称 / 渠道ID').fill(target['channel_name'])
            page.get_by_role('button',name=re.compile('查询$')).click()
            expect(table.locator('.el-table__body-wrapper tbody tr')).to_have_count(1)
            expect(table).to_contain_text(target['channel_name'])
            page.get_by_role('button',name='查看',exact=True).click()
            drawer=page.locator('.return-rules-drawer');expect(drawer).to_be_visible()
            editor=drawer.locator('.return-rule-editor')
            expect(editor).to_contain_text('只读查看')
            expect(editor.get_by_role('button',name='保存退货规则',exact=True)).to_have_count(0)
            expect(editor.locator('.el-switch').first).to_have_class(re.compile('is-disabled'))
            drawer.get_by_role('button',name='关闭并返回列表',exact=True).click()
            page.get_by_role('button',name='编辑',exact=True).click()
            expect(drawer).to_be_visible();expect(editor).to_contain_text('配置版本 '+str(before['revision']))
            for field,key in [('开启自动合包','auto_enabled'),('关联后自动下发','auto_dispatch')]:
                switch=editor.locator('.el-form-item').filter(has_text=field).locator('.el-switch')
                assert ('is-checked' in (switch.get_attribute('class') or ''))==bool(before[key])
            expect(editor.locator('.el-form-item').filter(has_text='合包归集等待').locator('input')).to_have_value(str(before['collect_seconds']))
            page.screenshot(path=str(OUT/'edit-rule-drawer.png'),animations='disabled')
            drawer.get_by_role('button',name='关闭并返回列表',exact=True).click()
            # Summary cards are filters; they must not overwrite rules.
            page.get_by_role('button',name=re.compile('已保存配置')).first.click()
            expect(page.locator('.return-rules')).not_to_contain_text('退货规则列表加载失败')
            page.goto(BASE+'/oms-order/management',wait_until='networkidle')
            expect(page.get_by_role('button',name='退回包裹',exact=True)).to_have_count(0)
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(refund_id),wait_until='networkidle')
            dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True)
            dialog.get_by_role('button',name='本店铺退货规则',exact=True).click()
            page.wait_for_url('**/afterSaleRules/returns?**')
            expect(drawer).to_be_visible();expect(editor).to_contain_text(target['channel_name'])
            expect(page.locator('.return-rules > .el-table .focused-shop').first).to_contain_text(target['channel_name'])
            drawer.get_by_role('button',name='关闭并返回列表',exact=True).click()
            expect(page.get_by_role('link',name='返回退款单',exact=True)).to_be_visible()
            page.goto(BASE+PATH+'?channelId=999999999',wait_until='networkidle')
            expect(page.get_by_text('未找到当前公司下的指定店铺，请重新选择',exact=True)).to_be_visible()
            expect(page.locator('.return-rule-editor')).to_have_count(0)
            assert not errors,errors
            assert not writes,writes
            browser.close()
        assert api('/order/refunds/parcel-configs/'+str(channel))['policy']==before
        print('PASS paged batch rule list, default values, read-only/edit drawers, contextual focus and unchanged policy; no business writes')
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
