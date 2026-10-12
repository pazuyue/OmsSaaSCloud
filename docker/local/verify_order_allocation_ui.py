"""Read-only live acceptance: allocation configuration, stage filters and order drawer.
No rule saves, order allocations or inventory mutations.
"""
import json, re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

def main():
    token=login()
    output=Path(__file__).resolve().parents[2]/'outputs/order-allocation'
    output.mkdir(parents=True,exist_ok=True)
    try:
        shops=request('/order/orders/configs',token=token)['data']
        assert shops,'An existing shop configuration is required for read-only UI acceptance'
        shop=shops[0]
        business_settings=request('/order/orders/conversion-configs',token=token)['data']
        allocation_settings=request('/order/orders/allocation-configs',token=token)['data']
        assert [s['channel_id'] for s in business_settings]==[s['channel_id'] for s in shops]
        assert [s['channel_id'] for s in allocation_settings]==[s['channel_id'] for s in shops]
        assert all(not any(k in s for k in ['source_key','seller_nick','cursor_time']) for s in business_settings)
        rule=request('/order/orders/allocation-config/'+str(shop['channel_id']),token=token)['data']
        assert all(all(k in s for k in ['owner_code','real_store_code','real_store_name','wms_simulation_code']) for s in rule['warehouses']),'Warehouse mapping fields missing'
        for stage in ['WAIT_REVIEW','WAIT_ALLOCATION','PART_ALLOCATED','WAIT_DISPATCH','VOIDING','VOIDED']:
            rows=request('/order/orders/business/list?documentStatus='+stage,token=token)
            assert all(r['business_status']==stage for r in rows['rows']),'Stage filter mismatch: '+stage
        business=request('/order/orders/business/list?pageSize=1',token=token)['rows']
        assert business,'Existing business order required'
        order=business[0]
        detail=request('/order/orders/business/'+str(order['id']),token=token)['data']
        assert detail['logical_inventory_status']==detail['inventory_status']
        assert 'warehouse_occupied_quantity' in detail and 'allocation_status' in detail
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1500,'height':1050})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/management',wait_until='networkidle')
            expect(page.get_by_role('tab',name='店铺接入',exact=True)).to_be_visible()
            assert page.get_by_role('button',name='分仓规则',exact=True).count()==0
            page.locator('.el-table__fixed-right:visible').get_by_role('button',name='接入设置',exact=True).first.click()
            access=page.get_by_role('dialog',name='店铺接入配置',exact=True)
            expect(access.get_by_text('自动拉单',exact=True)).to_be_visible()
            assert access.get_by_text('审单方式',exact=True).count()==0
            assert access.get_by_text('自动转单',exact=True).count()==0
            access.get_by_role('button',name='取消',exact=True).click()
            page.goto(BASE+'/oms-order/settings?tab=conversion&channelId='+str(shop['channel_id']),wait_until='networkidle')
            conversion=page.get_by_role('dialog',name='店铺转单设置',exact=True)
            expect(conversion).to_be_visible();expect(conversion).to_contain_text(shop['channel_name'])
            expect(conversion.get_by_text('审单方式',exact=True)).to_be_visible()
            expect(conversion.get_by_text('允许公共库存补占',exact=True)).to_be_visible()
            assert conversion.get_by_text('推送库',exact=True).count()==0
            assert conversion.get_by_text('首次拉取起点',exact=True).count()==0
            page.screenshot(path=str(output/'shop-conversion-settings.png'),full_page=True)
            conversion.get_by_role('button',name='取消',exact=True).click()
            page.get_by_role('tab',name='分仓规则',exact=True).click()
            expect(page).to_have_url(re.compile(r'/oms-order/settings\?tab=allocation$'))
            expect(page.locator('h1')).to_have_text('订单设置')
            page.screenshot(path=str(output/'order-settings.png'),full_page=True)
            page.get_by_role('tab',name='转单设置',exact=True).click()
            expect(conversion).to_be_hidden()
            page.goto(BASE+'/oms-order/settings?tab=allocation&channelId='+str(shop['channel_id']),wait_until='networkidle')
            dialog=page.get_by_role('dialog',name='店铺分仓规则',exact=True)
            expect(dialog).to_be_visible()
            expect(dialog.get_by_text('允许多仓发货',exact=True)).to_be_visible()
            expect(dialog.get_by_text('允许部分发货',exact=True)).to_be_visible()
            expect(dialog.get_by_text('同 SKU 跨仓拆分',exact=True)).to_be_visible()
            if rule['warehouses']:
                mapped=rule['warehouses'][0]
                owner=dialog.locator('.el-form-item').filter(has=page.locator('label',has_text=re.compile('^货主$')))
                owner_changed=rule['policy']['owner_code']!=mapped['owner_code']
                if owner_changed:
                    owner.locator('.el-select').click()
                    page.locator('.el-select-dropdown:visible').get_by_text(mapped['owner_name']+'（'+mapped['owner_code']+'）',exact=True).click()
                allowed=dialog.locator('.el-form-item').filter(has=page.locator('label',has_text=re.compile('^允许仓 / 默认顺序$'))).locator('.el-select')
                label=f"{mapped['wms_simulation_name']}（{mapped['wms_simulation_code']}） / {mapped['real_store_name']}（{mapped['real_store_code']}）"
                if owner_changed or mapped['wms_simulation_code'] not in rule['policy']['warehouse_order']:
                    allowed.click()
                    page.locator('.el-select-dropdown:visible').get_by_text(label,exact=True).click()
                dialog.get_by_text('省份优先仓',exact=True).click()
                expect(dialog.locator('.rank-row').filter(has_text=mapped['real_store_code']).first).to_be_visible()
                multi=dialog.locator('.el-form-item').filter(has=page.locator('label',has_text=re.compile('^允许多仓发货$'))).locator('.el-switch')
                split=dialog.locator('.el-form-item').filter(has=page.locator('label',has_text=re.compile('^同 SKU 跨仓拆分$'))).locator('.el-switch')
                if not rule['policy']['allow_multi']:
                    expect(split).to_have_class(re.compile('is-disabled'))
                    multi.click();expect(split).not_to_have_class(re.compile('is-disabled'))
                    multi.click();expect(split).to_have_class(re.compile('is-disabled'))
            dialog.get_by_role('button',name='保存分仓规则',exact=True).scroll_into_view_if_needed()
            page.screenshot(path=str(output/'shop-allocation-rules.png'),full_page=True)
            dialog.get_by_role('button',name='取消',exact=True).click()
            page.goto(BASE+'/oms-order/business?view=business',wait_until='networkidle')
            query=page.get_by_placeholder('输入完整 OMS 或平台单号')
            query.fill(order['platform_tid'])
            with page.expect_response(lambda r:'/orders/business/list' in r.url and order['platform_tid'] in r.url):query.press('Enter')
            expect(page.locator('.order-toolbar')).to_contain_text('共 1 条')
            expect(page.locator('.business-stage-tabs').get_by_text('部分分仓',exact=True)).to_be_visible()
            expect(page.locator('.business-stage-tabs').get_by_text('待下发仓库',exact=True)).to_be_visible()
            page.locator('.order-identity:visible .identity-number.primary').first.click()
            drawer=page.locator('.el-drawer:visible')
            drawer.get_by_role('tab',name='分仓明细',exact=True).click()
            expect(drawer.get_by_text('已分仓 / 原单需求',exact=True)).to_be_visible()
            expect(drawer.get_by_text('待分仓余量',exact=True)).to_be_visible()
            assert not errors,'Browser errors: '+', '.join(errors)
            browser.close()
        print(json.dumps({'api':'passed','browser':'passed','settings_separation':'passed','deep_links':2,'stage_filters':6,'mapped_warehouses':len(rule['warehouses']),'rule_enabled':rule['policy']['enabled'],'business_status':detail['business_status'],'warehouse_status':detail['warehouse_inventory_status']},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
