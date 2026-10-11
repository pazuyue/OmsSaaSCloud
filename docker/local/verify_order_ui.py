"""Read-only browser/API acceptance of the independent order service."""
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE, LOGS

def main():
    token=login()
    try:
        catalog=request('/order/orders/catalog',token=token)['data']
        assert any(x['key']=='sysinfo113' for x in catalog['sources']), '113 source must be configured'
        print('Order catalog: shops',len(catalog['channels']),'eligible warehouses',len(catalog['stores']),'113 source configured')
        for view in ['platform','business','exceptions','tasks']:
            result=request('/order/orders/'+view+'/list',token=token)
            assert 'total' in result
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1500,'height':1000})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.set_default_timeout(25000)
            errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
            for view,title in [('platform','平台订单'),('business','业务订单'),('exceptions','转单异常'),('tasks','拉单任务')]:
                page.goto(BASE+'/oms-order/'+view+'?view='+view,wait_until='networkidle')
                expect(page.locator('.order-heading h2')).to_have_text(title)
                expect(page.get_by_text('订单服务加载失败，请刷新重试',exact=True)).not_to_be_visible()
                page.screenshot(path=str(LOGS/('order-'+view+'.png')),full_page=True)
            page.locator('.order-toolbar button').filter(has_text='拉单配置').click()
            expect(page.get_by_text('天猫拉单配置',exact=True)).to_be_visible()
            page.get_by_role('button',name='配置店铺',exact=True).click()
            expect(page.get_by_role('dialog',name='店铺拉单与转单配置')).to_be_visible()
            page.screenshot(path=str(LOGS/'order-config.png'),full_page=True)
            assert not errors,errors
            browser.close()
        print('PASS order API and four pages, pull configuration dialog; no browser errors')
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
