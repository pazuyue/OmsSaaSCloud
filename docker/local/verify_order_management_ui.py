"""Acceptance of the published management menu and real source/configuration APIs."""
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, LOGS
import json
import time

BASE = 'http://127.0.0.1:8088'

def main():
    token = login()
    try:
        sources = request('/order/orders/sources', token=token)['data']
        assert {s['source_key'] for s in sources} >= {'sysinfo100', 'sysinfo113'}
        assert all('password' not in json.dumps(s).lower() for s in sources)
        catalog=request('/order/orders/catalog',token=token)['data']
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1500, 'height': 1000})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            for view in ['platform', 'business', 'exceptions']:
                page.goto(BASE+'/oms-order/'+view+'?view='+view, wait_until='networkidle')
                expect(page.locator('.order-toolbar').get_by_role('button', name='拉单配置')).to_have_count(0)
                if view != 'platform':
                    expect(page.locator('.order-toolbar').get_by_role('button', name='补拉')).to_have_count(0)
                if view == 'business':
                    expect(page.locator('.order-workspace .el-table__header-wrapper').get_by_text('预占进度（件）').first).to_be_visible()
            page.goto(BASE+'/oms-order/management', wait_until='networkidle')
            expect(page.get_by_role('tab', name='店铺配置')).to_be_visible()
            page.get_by_role('button', name='配置店铺', exact=True).click()
            dialog = page.get_by_role('dialog', name='店铺接入配置', exact=True)
            expect(dialog).to_be_visible()
            expect(dialog.locator('..')).to_have_css('opacity', '1')
            assert dialog.locator('.el-form-item').filter(has_text='货主').count()==0
            assert dialog.locator('.el-form-item').filter(has_text='允许使用的虚仓').count()==0
            expect(dialog.get_by_text('允许公共库存补占',exact=True)).to_be_visible()
            expect(dialog.get_by_text('库存预占策略',exact=True)).to_be_visible()
            expect(dialog.get_by_text('审单方式',exact=True)).to_be_visible()
            dialog.get_by_text('人工审核',exact=True).click()
            expect(dialog.locator('.el-radio').filter(has_text='人工审核')).to_have_class(__import__('re').compile('is-checked'))
            dialog.get_by_text('自动审核',exact=True).click()
            expect(dialog.locator('.el-form-item').filter(has_text='人工匹配需复核').locator('.el-switch')).to_have_class(__import__('re').compile('is-checked'))
            types=dialog.locator('.el-form-item').filter(has_text='允许交易类型')
            expect(types.locator('.el-select__tags-text').filter(has_text=__import__('re').compile('^tmall_i18n$'))).to_be_visible()
            expect(dialog.get_by_role('button', name='保存', exact=True)).to_be_enabled()
            expect(dialog.locator('.el-form-item').filter(has_text='自动拉单').locator('.el-switch')).not_to_have_class(__import__('re').compile('is-disabled'))
            conversion=dialog.locator('.el-form-item').filter(has_text='自动转单').locator('.el-switch')
            expect(conversion).not_to_have_class(__import__('re').compile('is-disabled'))
            conversion.click()
            expect(conversion).to_have_class(__import__('re').compile('is-checked'))
            shop=next(s for s in catalog['channels'] if s.get('enabled') and s.get('seller_nick'))
            source=next(s for s in catalog['sources'] if s['key']=='sysinfo100')
            dialog.locator('.el-form-item').filter(has_text='天猫店铺').locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text(shop['channel_name'],exact=True).click()
            dialog.locator('.el-form-item').filter(has=page.locator('label',has_text=__import__('re').compile('^推送库$'))).locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text(source['label'],exact=True).click()
            seller=dialog.get_by_placeholder('与源表 seller_nick 一致')
            seller.fill('__UI_SOURCE_VERIFY_'+str(int(time.time())))
            confirmation=dialog.get_by_text('确认该推送库账号的订单归属所选 OMS 店铺',exact=True)
            expect(confirmation).to_be_visible()
            confirmation.click()
            expect(dialog.locator('.el-checkbox').filter(has_text='确认该推送库账号')).to_have_class(__import__('re').compile('is-checked'))
            dialog.get_by_role('button',name='验证拉单来源',exact=True).click()
            expect(dialog.get_by_text('所选时间范围内未找到订单',exact=False)).to_be_visible(timeout=25000)
            seller.fill('__UI_SOURCE_CHANGED_'+str(int(time.time())))
            expect(dialog.locator('.el-checkbox').filter(has_text='确认该推送库账号')).not_to_have_class(__import__('re').compile('is-checked'))
            expect(dialog.get_by_text('所选时间范围内未找到订单',exact=False)).to_have_count(0)
            page.screenshot(path=str(LOGS/'order-management-shop-review.png'), full_page=True)
            dialog.get_by_role('button', name='取消', exact=True).click()
            page.get_by_role('tab', name='推送库配置').click()
            expect(page.locator('.order-management .el-table:visible .el-table__body-wrapper tr')).to_have_count(len(sources))
            page.screenshot(path=str(LOGS/'order-management-sources-review.png'), full_page=True)
            page.locator('.order-management .el-table:visible').get_by_role('button', name='编辑', exact=True).first.click()
            dialog = page.get_by_role('dialog', name='编辑推送库', exact=True)
            expect(dialog.locator('..')).to_have_css('opacity', '1')
            expect(dialog.get_by_placeholder('留空保留原密码，输入新密码即可修改')).to_have_value('')
            page.screenshot(path=str(LOGS/'order-management-source-edit-review.png'), full_page=True)
            dialog.get_by_role('button', name='取消', exact=True).click()
            page.get_by_role('tab', name='拉单任务').click()
            expect(page.locator('.order-toolbar')).to_be_visible()
            assert not errors, errors
            browser.close()
        print('PASS published UI: mapping confirmation, read-only source verification, changed mapping clears confirmation/result, independent switches, no browser errors')
    finally:
        request('/auth/logout', token=token, method='DELETE')

if __name__ == '__main__':
    main()
