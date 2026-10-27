"""Fresh browser checks of public carrier configuration and retained parcel facts."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));token=login();checks=[];errors=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport=dict(width=1760,height=1180));ctx.add_cookies([dict(name='Admin-Token',value=token,url=BASE)])
        page=ctx.new_page();page.set_default_timeout(25000);page.on('pageerror',lambda e:errors.append(str(e)))
        def goto(route):
            page.goto(BASE+route,wait_until='networkidle');expect(page.locator('.app-container')).to_be_visible()
        for slug,title in [('standards','标准快递资料'),('channels','渠道快递配置'),('wms','WMS 快递配置'),('capabilities','仓库服务能力'),('todos','快递关联待办')]:
            goto('/common-config/carriers/'+slug);expect(page.locator('.carriers h2')).to_have_text(title)
            assert not page.locator('.carriers > .el-alert--error').count(),page.locator('.carriers').inner_text()
            page.screenshot(path=str(OUT/('ui-'+slug+'.png')),full_page=True);checks.append(title+'列表正常加载')
        goto('/common-config/carriers/channels');page.get_by_role('button',name='关联标准快递',exact=True).click()
        dialog=page.locator('.el-dialog:visible');dialog.locator('.el-form-item').filter(has_text='渠道平台').locator('.el-select').click()
        for name in ['京东（后续扩展）','抖音（后续扩展）']:
            option=page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=name);expect(option).to_have_class(re.compile('is-disabled'))
        page.keyboard.press('Escape');page.screenshot(path=str(OUT/'ui-channel-scope.png'),full_page=True);dialog.get_by_role('button',name='取消',exact=True).click();checks.append('当前仅天猫可配置；京东、抖音入口保留并禁用')
        page.get_by_role('tab',name='平台快递清单',exact=True).click();page.get_by_role('button',name='补录平台快递',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-dialog__title')).to_have_text('补录平台快递');expect(dialog.get_by_text('平台快递编码',exact=True)).to_be_visible();dialog.get_by_role('button',name='取消',exact=True).click()
        page.get_by_role('button',name='导入快递清单',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-dialog__title')).to_have_text('导入平台快递清单');dialog.get_by_role('button',name='关闭',exact=True).click();checks.append('渠道页面统一使用平台快递清单、补录平台快递及导入清单名称')
        goto('/common-config/carriers/wms');page.get_by_role('button',name='关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible')
        select=dialog.locator('.el-form-item').filter(has_text='WMS 连接').locator('.el-select');select.click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text='[公共快递TEST] connection '+manifest['stamp']).click()
        dialog.get_by_role('radio',name='货主与实仓',exact=True).click()
        dialog.locator('.el-form-item').filter(has_text=re.compile('^货主')).locator('.el-select').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text='SCO_'+manifest['stamp']).click()
        dialog.locator('.el-form-item').filter(has_text='实体仓').locator('.el-select').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text='SCW_'+manifest['stamp']).click()
        page.screenshot(path=str(OUT/'ui-wms-scope.png'),full_page=True);dialog.get_by_role('button',name='取消',exact=True).click();checks.append('WMS 连接、货主与实仓可逐级选择，表单使用独立标准身份')
        page.get_by_role('tab',name='仓库快递清单',exact=True).click();page.get_by_role('button',name='补录仓库快递',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-dialog__title')).to_have_text('补录仓库快递');expect(dialog.get_by_text('仓库快递编码',exact=True)).to_be_visible();dialog.get_by_role('button',name='取消',exact=True).click()
        page.get_by_role('button',name='导入快递清单',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-dialog__title')).to_have_text('导入仓库快递清单');dialog.get_by_role('button',name='关闭',exact=True).click();checks.append('WMS页面统一使用仓库快递清单、补录仓库快递及导入清单名称')
        goto('/common-config/carriers/todos');page.locator('.carriers .toolbar .el-input input').first.fill(manifest['return_sn']);page.get_by_role('button',name='查询',exact=True).click();page.wait_for_timeout(400)
        page.locator('.carriers .el-table__body-wrapper').get_by_text(manifest['return_sn'],exact=True).first.click();page.wait_for_url('**/oms-supplychain/purchaseReturns?**');expect(page.locator('.el-drawer:visible')).to_be_visible()
        page.get_by_role('tab',name='包裹与物流',exact=True).click();page.locator('.el-drawer:visible .execution').last.scroll_into_view_if_needed();page.screenshot(path=str(OUT/'ui-return-parcels.png'),full_page=True)
        panel=page.locator('.el-drawer:visible').last
        assert 'TEST-SC-UNKNOWN-001' in panel.inner_text() and 'TEST-SC-B-001' in panel.inner_text()
        assert 'OMS：' in panel.inner_text() and '身份已识别' in panel.inner_text();checks.append('待办可直达原退仓详情；包裹分别展示原始编码、标准身份和识别状态')
        goto('/oms-supplychain/purchaseReturns?reclaimId='+str(manifest['ids']['raw_reclaim']));panel=page.locator('.el-drawer:visible');expect(panel).to_be_visible()
        expect(panel.get_by_text('001/Unknown.a',exact=True)).to_be_visible();assert '标准快递尚未核实' in panel.inner_text()
        panel.locator('.shared-carrier-select .el-select').click()
        expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=manifest['commands']['STANDARD_A']['response']['data']['standard_name'])).to_be_visible()
        page.keyboard.press('Escape');page.screenshot(path=str(OUT/'ui-raw-return.png'),full_page=True);checks.append('未知供应商退回物流原值保留；审核区域提供标准快递及核对凭证，收货选项可正常查询')
        assert not errors,errors
        browser.close()
    (OUT/'browser.json').write_text(json.dumps(dict(checks=checks,page_errors=errors),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'fresh-browser checks; no page errors',flush=True)


if __name__=='__main__':main()
