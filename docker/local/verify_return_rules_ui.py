"""Read-only acceptance of independent shop return rules and contextual shortcuts."""
import json
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
    refund_id=case['refunds'][-1]['id']
    token=login()
    def api(path):return request(path,token=token)['data']
    try:
        def ready():
            try:return api('/order/refunds/parcel-configs/catalog')
            except (AssertionError,OSError):return None
        shops=wait_for(ready,lambda r:r is not None,'Return rules catalog not ready',120)
        refund=api('/order/refunds/business/'+str(refund_id));channel=refund['channel_id']
        shop=next(s for s in shops if str(s['channel_id'])==str(channel))
        before=api('/order/refunds/parcel-configs/'+str(channel))['policy']
        def find(nodes,path):
            for node in nodes:
                if node['path']==path:return node
                found=find(node.get('children',[]),path)
                if found:return found
        routes=api('/system/menu/getRouters')
        group=find(routes,'afterSaleRules');assert group and group['meta']['title']=='售后规则'
        page_route=find(group.get('children',[]),'returns');assert page_route and page_route['component']=='oms/order/RefundReturnRules'
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1600,'height':1100})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[];writes=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.on('request',lambda r:writes.append(r.url) if r.method=='POST' and '/refunds/' in r.url else None)
            page.goto(BASE+'/oms-order/management',wait_until='networkidle')
            expect(page.get_by_role('button',name='退回包裹',exact=True)).to_have_count(0)
            expect(page.get_by_role('button',name='合包与自动处理配置',exact=True)).to_have_count(0)
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(refund_id),wait_until='networkidle')
            dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True)
            dialog.get_by_role('button',name='本店铺退货规则',exact=True).click()
            page.wait_for_url('**/afterSaleRules/returns?**')
            editor=page.locator('.return-rule-editor')
            expect(editor).to_contain_text(shop['channel_name'])
            expect(editor).to_contain_text('渠道 '+str(channel))
            expect(editor).to_contain_text('配置版本 '+str(before['revision']))
            expect(editor.get_by_role('heading',name='1. 包裹关联',exact=True)).to_be_visible()
            for field,key in [('开启自动合包','auto_enabled'),('关联后自动下发','auto_dispatch')]:
                switch=editor.locator('.el-form-item').filter(has_text=field).locator('.el-switch')
                assert ('is-checked' in (switch.get_attribute('class') or ''))==bool(before[key])
            expect(editor.locator('.el-form-item').filter(has_text='合包归集等待').locator('input')).to_have_value(str(before['collect_seconds']))
            expect(page.get_by_role('link',name='返回退款单',exact=True)).to_be_visible()
            page.screenshot(path=str(OUT/'independent-return-rules.png'),animations='disabled')
            editor.get_by_role('heading',name='3. 实收分配',exact=True).scroll_into_view_if_needed()
            page.screenshot(path=str(OUT/'dispatch-and-receipt-rules.png'),animations='disabled')
            # Unknown shops must not fall back to another company's or the first shop's policy.
            page.goto(BASE+PATH+'?channelId=999999999',wait_until='networkidle')
            expect(page.get_by_text('未找到当前公司下的指定店铺，请重新选择',exact=True)).to_be_visible()
            expect(page.locator('.return-rule-editor')).to_have_count(0)
            page.goto(BASE+PATH,wait_until='networkidle')
            expect(page.get_by_text('选择店铺后查看包裹关联、仓库下发及实收分配规则',exact=True)).to_be_visible()
            expect(page.locator('.return-rule-editor')).to_have_count(0)
            # The business list also offers a standalone entry.
            page.goto(BASE+'/oms-order/businessRefunds',wait_until='networkidle')
            page.get_by_role('button',name='退货规则',exact=True).click()
            page.wait_for_url('**/afterSaleRules/returns')
            assert not errors,errors
            assert not writes,writes
            browser.close()
        assert api('/order/refunds/parcel-configs/'+str(channel))['policy']==before
        print('PASS after-sales menu, standalone rules, current-shop shortcuts, invalid-shop guard and unchanged policy; no business writes')
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
