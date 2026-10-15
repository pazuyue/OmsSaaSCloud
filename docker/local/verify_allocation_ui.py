"""Verify allocation configuration UI on retained isolated acceptance shop 39; restore policy."""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import stock
OUT=Path(__file__).resolve().parents[2]/'outputs/allocation-ui'

def main():
    OUT.mkdir(exist_ok=True)
    case=json.loads((OUT.parent/'order-review/case-human-confirmed.json').read_text(encoding='utf-8'))
    assert case['channel_id']==39 and case['order_id']==42 and case['sku'].startswith('RV_')
    token=login();path='/order/orders/allocation-config/39'
    original=request(path,token=token)['data'];policy=original['policy'];before=stock(case)
    order_before=request('/order/orders/business/42',token=token)['data']
    v1,v2=policy['warehouse_order'];assert policy['province_priorities']=={'北京':[v1]} and policy['allow_multi']==1 and policy['allow_split_sku']==1
    restored=False;errors=[]
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1560,'height':1080})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
            def open_config():
                page.goto(BASE+'/oms-order/settings?tab=allocation&channelId=39',wait_until='networkidle')
                d=page.get_by_role('dialog',name='店铺分仓规则',exact=True)
                expect(d.get_by_text('何时执行分仓',exact=True)).to_be_visible();return d
            def jump(d,name):d.get_by_role('button',name=name,exact=True).click()
            d=open_config();page.screenshot(path=str(OUT/'execution.png'))
            d.get_by_text('人工执行分仓',exact=True).click()
            expect(d.get_by_role('switch',name='余量自动补充分仓',exact=True)).to_be_disabled()
            expect(d.locator('.scope-grid .el-select').last.locator('input').last).to_be_enabled()
            jump(d,'2 允许仓与顺序')
            d.get_by_role('button',name='默认仓库下移 '+v1,exact=True).click()
            assert v2 in d.locator('.rank-row').first.inner_text()
            d.locator('.province-rule').first.evaluate('(e)=>e.scrollIntoView({block: "center"})');d.locator('.province-row .el-select').nth(1).locator('.el-input__suffix').click()
            page.locator('.el-select-dropdown:visible').get_by_text(re.compile(re.escape(v2))).click();d.get_by_text('省份优先仓',exact=True).click()
            d.get_by_role('button',name='省份仓库上移 '+v2,exact=True).click()
            assert v2 in d.locator('.province-rank').first.inner_text()
            d.get_by_text('添加省份优先规则',exact=True).click()
            d.get_by_role('button',name='保存分仓规则',exact=True).click()
            expect(page.get_by_text('请完整填写省份优先仓，或删除空白规则',exact=True)).to_be_visible()
            assert request(path,token=token)['data']['revision']==original['revision']
            d.get_by_role('button',name='删除省份规则 2',exact=True).click()
            jump(d,'3 选仓策略');d.get_by_text('优先整单',exact=True).click()
            jump(d,'4 库存与拆分');d.get_by_role('switch',name='允许多虚拟仓发货',exact=True).click()
            expect(d.get_by_role('switch',name='同SKU跨虚拟仓拆分',exact=True)).to_be_disabled()
            expect(d.get_by_role('switch',name='同SKU跨虚拟仓拆分',exact=True)).not_to_be_checked()
            expect(d.locator('.limits-condition input').first).to_be_disabled()
            expect(d.locator('.effective-summary')).to_contain_text('限 1 个虚拟仓')
            d.get_by_role('switch',name='允许部分分仓',exact=True).click();expect(d.locator('.threshold')).to_have_count(0)
            page.screenshot(path=str(OUT/'single-warehouse.png'))
            d.get_by_role('button',name='保存分仓规则',exact=True).click();expect(d).not_to_be_visible()
            changed=request(path,token=token)['data']['policy']
            assert changed['execution_mode']=='MANUAL' and changed['auto_replenish']==policy['auto_replenish'] and changed['allow_multi']==0 and changed['allow_split_sku']==0 and changed['allow_partial']==0 and changed['strategy']=='FEWEST_SHIPMENTS'
            assert changed['warehouse_order']==[v2,v1] and changed['province_priorities']=={'北京':[v2,v1]}
            row=page.locator('.el-table__body tr').filter(has_text=case['channel_name']).filter(has_text='配置分仓规则').first;expect(row).to_contain_text('限 1 个虚拟仓')
            d=open_config();d.get_by_text('满足条件自动分仓',exact=True).click()
            jump(d,'2 允许仓与顺序');d.get_by_role('button',name='默认仓库下移 '+v2,exact=True).click()
            d.get_by_role('button',name='省份仓库上移 '+v1,exact=True).click()
            # Remove the additional province warehouse using the select tag's close control.
            tags=d.locator('.province-row .el-select').nth(1).locator('.el-tag')
            tags.filter(has_text=v2).locator('.el-tag__close').click()
            page.screenshot(path=str(OUT/'warehouse-priority.png'))
            jump(d,'3 选仓策略');d.get_by_text('优先省份配置仓',exact=True).click();page.screenshot(path=str(OUT/'strategy.png'))
            jump(d,'4 库存与拆分');d.get_by_role('switch',name='允许多虚拟仓发货',exact=True).click();d.get_by_role('switch',name='同SKU跨虚拟仓拆分',exact=True).click();d.get_by_role('switch',name='允许部分分仓',exact=True).click();expect(d.locator('.threshold')).to_be_visible()
            page.screenshot(path=str(OUT/'limits.png'))
            d.get_by_role('button',name='保存分仓规则',exact=True).click();expect(d).not_to_be_visible()
            assert request(path,token=token)['data']['policy']==policy;restored=True
            page.screenshot(path=str(OUT/'settings-list.png'))
            page.set_viewport_size({'width':1280,'height':720});d=open_config();jump(d,'4 库存与拆分');expect(d.get_by_role('button',name='保存分仓规则',exact=True)).to_be_in_viewport();expect(d.get_by_text('库存不足与拆分限制',exact=True)).to_be_in_viewport()
            assert d.locator('.allocation-layout').evaluate('(e)=>e.scrollWidth<=e.clientWidth+1')
            page.screenshot(path=str(OUT/'compact-screen.png'));assert not errors,errors;browser.close()
        after=request('/order/orders/business/42',token=token)['data']
        assert after['control_version']==order_before['control_version'] and after['review_assessment']==order_before['review_assessment'] and stock(case)==before
        (OUT/'verification.json').write_text(json.dumps({'four_sections_and_navigation':True,'manual_keeps_warehouse_rules_editable':True,'default_and_province_orders_saved':True,'incomplete_province_validation':True,'multi_and_sku_dependencies':True,'partial_ratio_visibility':True,'single_warehouse_effective_limit':True,'policy_saved_and_restored':True,'order_and_stock_unchanged':True,'compact_screen_footer_visible':True,'browser_errors':errors},indent=2),encoding='utf-8')
        print('PASS allocation UI: grouping, navigation, order persistence, dependencies, validation, save/restore and compact screen; order and stock unchanged')
    finally:
        if not restored:
            current=request(path,token=token)['data']
            if current['policy']!=policy:request('/order/orders/allocation-config',{'channel_id':39,'revision':current['revision'],'policy':policy},token=token)
        request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
