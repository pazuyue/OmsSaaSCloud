"""Verify purpose-derived WMS direction using real pages, without saving configuration."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));token=login();checks=[];errors=[];writes=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport=dict(width=1760,height=1250));ctx.add_cookies([dict(name='Admin-Token',value=token,url=BASE)])
        page=ctx.new_page();page.set_default_timeout(20000);page.on('pageerror',lambda e:errors.append(str(e)))
        page.on('request',lambda r:writes.append(r.url) if '/sharedCarriers/' in r.url and r.method=='POST' and any(s in r.url for s in ['/save','/confirm','/retry']) else None)
        def go(slug):
            page.goto(BASE+'/common-config/carriers/'+slug,wait_until='networkidle');expect(page.locator('.carriers h2')).to_be_visible();assert not page.locator('.carriers > .el-alert--error').count()
        def form_item(dialog,label):return dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^'+label+'$')))
        go('wms');page.get_by_role('button',name='关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible')
        for purpose,outbound in [('WMS 出库下发',True),('WMS 实际出库回传',False),('WMS 退回入库下发',True),('WMS 实际入库回传',False)]:
            form_item(dialog,'业务用途').locator('.el-select').click();page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=re.compile('^'+purpose+'$')).click()
            expect(form_item(dialog,'转换方向')).to_have_count(0);expect(form_item(dialog,'业务用途')).to_contain_text('下发时转换' if outbound else '回传的原始编码')
            expect(form_item(dialog,'仓库下发首选项')).to_have_count(0)
            dialog.locator('.el-dialog__title').click();expect(page.locator('.el-select-dropdown:visible')).to_have_count(0);page.screenshot(animations='disabled',path=str(OUT/('direction-'+('outbound-create' if purpose=='WMS 出库下发' else 'outbound-actual' if purpose=='WMS 实际出库回传' else 'return-create' if outbound else 'return-actual')+'.png')))
            checks.append(purpose+'自动确定处理用途及默认输出可见性')
        dialog.get_by_role('button',name='取消',exact=True).click()
        page.locator('.carriers').get_by_role('button',name='编辑',exact=True).first.click();dialog=page.locator('.el-dialog:visible')
        expect(form_item(dialog,'转换方向')).to_have_count(0);expect(form_item(dialog,'业务用途')).to_be_visible();dialog.get_by_role('button',name='取消',exact=True).click();checks.append('编辑已有对照也使用固定处理用途')
        page.get_by_role('tab',name='仓库快递清单',exact=True).click();expect(page.locator('.carriers .el-table__body-wrapper .el-checkbox').first).to_be_visible();assert '快递关联与处理用途' not in page.locator('.carriers .el-table__header-wrapper').inner_text()
        for code,outbound in [('TEST-SC-PLAN-'+manifest['stamp'],True),('TEST-SC-B-001',False)]:
            page.locator('.carriers .toolbar .el-input input').first.fill(code);page.get_by_role('button',name='查询',exact=True).click()
            row=page.locator('.carriers .el-table__body-wrapper tr').filter(has_text=code).first;expect(row).to_be_visible();row.locator('.el-checkbox').click()
            page.get_by_role('button',name='批量关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.locator('.el-table__body-wrapper tr')).to_have_count(1);expect(dialog.locator('.el-alert--info')).to_contain_text('下发编码转换' if outbound else '回传快递识别')
            expect(dialog.locator('.el-table__header-wrapper')).to_contain_text('业务用途');assert '方向' not in dialog.locator('.el-table__header-wrapper').inner_text()
            assert '仓库下发首选项' not in dialog.locator('.el-table__header-wrapper').inner_text()
            page.screenshot(animations='disabled',path=str(OUT/('direction-batch-'+('out' if outbound else 'in')+'.png')));dialog.get_by_role('button',name='关闭',exact=True).click();checks.append(('下发' if outbound else '回传')+'目录批量关联标准快递无需选择方向')
        go('channels');page.get_by_role('button',name='关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(form_item(dialog,'转换方向')).to_have_count(0);expect(form_item(dialog,'业务用途').locator('input')).to_have_value('销售发货回传');dialog.get_by_role('button',name='取消',exact=True).click();checks.append('渠道关联也按业务用途确定方向，无需手选')
        assert not errors,errors;assert not writes,writes;browser.close()
    (OUT/'direction-browser.json').write_text(json.dumps(dict(checks=checks,page_errors=errors,configuration_writes=writes),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'WMS direction browser checks; no configuration writes or page errors')


if __name__=='__main__':main()
