"""UI-only review iteration on the retained isolated acceptance shop.
Round-trip a stock option, restore the original policy, and preserve every business document.
"""
import json,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import stock
OUT=Path(__file__).resolve().parents[2]/'outputs/order-review'
def main():
    case=json.loads((OUT/'case-human-confirmed.json').read_text(encoding='utf-8'));assert case['sku'].startswith('RV_') and case['order_id']==42
    token=login();original=request('/order/orders/review-config/'+str(case['channel_id']),token=token)['data'];policy=original['policy'];before=stock(case)
    before_order=request('/order/orders/business/42',token=token)['data'];restored=False
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1560,'height':1250});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            def open_config():
                page.goto(BASE+'/oms-order/settings?tab=review&channelId='+str(case['channel_id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='店铺审单规则',exact=True);expect(dialog.get_by_text('选择审单方式',exact=True)).to_be_visible();return dialog
            dialog=open_config();expect(dialog.get_by_text('哪些订单需要人工确认',exact=True)).to_be_visible();expect(dialog.get_by_text('库存不足时如何处理',exact=True)).to_be_visible();dialog.get_by_text('全部人工审核',exact=True).click();expect(dialog.get_by_role('radio',name=re.compile('库存不足也允许自动审核'))).to_be_disabled();page.screenshot(path=str(OUT/'ui-manual-mode.png'),full_page=True)
            dialog.get_by_text('符合规则自动审核',exact=True).click();expect(dialog.get_by_role('radio',name=re.compile('库存不足也允许自动审核'))).to_be_enabled()
            amount=dialog.locator('.condition').filter(has_text='实付金额较高').locator('.el-input-number input');amount.fill('');dialog.get_by_role('button',name='保存审单规则',exact=True).click();expect(page.get_by_text('请填写大于 0 的实付金额阈值',exact=True)).to_be_visible();assert request('/order/orders/review-config/'+str(case['channel_id']),token=token)['data']['revision']==original['revision'];amount.fill('10');amount.press('Tab')
            dialog.get_by_text('库存不足也允许自动审核',exact=True).click();dialog.locator('.stock-section').scroll_into_view_if_needed();page.screenshot(path=str(OUT/'ui-stock-options.png'),full_page=True);dialog.get_by_role('button',name='保存审单规则',exact=True).click();expect(dialog).not_to_be_visible();changed=request('/order/orders/review-config/'+str(case['channel_id']),token=token)['data'];assert changed['policy']['stock_condition']=='ANY'
            dialog=open_config();dialog.get_by_text('库存满足后才自动审核',exact=True).click();dialog.get_by_role('button',name='保存审单规则',exact=True).click();expect(dialog).not_to_be_visible();assert request('/order/orders/review-config/'+str(case['channel_id']),token=token)['data']['policy']==policy;restored=True
            dialog=open_config();page.screenshot(path=str(OUT/'ui-auto-rules.png'),full_page=True);dialog.get_by_role('button',name='取消',exact=True).click();expect(page.get_by_text('库存不足时',exact=True).first).to_be_visible();page.screenshot(path=str(OUT/'ui-settings-list.png'),full_page=True)
            page.goto(BASE+'/oms-order/business?view=business&orderId=43',wait_until='networkidle');panel=page.locator('.el-drawer:visible .review-evidence');panel.scroll_into_view_if_needed();expect(panel.locator('.heading .el-tag')).to_have_text('待库存满足');expect(panel.get_by_text('该条件未启用，不参与判定',exact=True)).to_have_count(3);assert '阈值 ≥ 0' not in panel.inner_text();page.screenshot(path=str(OUT/'ui-review-evidence.png'),full_page=True);assert not errors,errors;browser.close()
        after=request('/order/orders/business/42',token=token)['data'];assert after['review_decision']=='HUMAN_APPROVED' and after['review_assessment']==before_order['review_assessment'] and after['control_version']==before_order['control_version'] and stock(case)==before
        (OUT/'ui-v2-verification.json').write_text(json.dumps({'three_sections_visible':True,'manual_disables_automatic_rules':True,'missing_threshold_validation':True,'stock_option_saved_and_restored':True,'policy_restored':True,'business_order_and_stock_unchanged':True,'inactive_thresholds_hidden':True,'browser_errors':0},indent=2),encoding='utf-8');print('PASS review UI: grouping, disabled states, threshold validation, stock option save/restore and evidence; business state unchanged')
    finally:
        if not restored:
            current=request('/order/orders/review-config/'+str(case['channel_id']),token=token)['data']
            if current['policy']!=policy:request('/order/orders/review-config',{'channel_id':case['channel_id'],'revision':current['revision'],'policy':policy},token=token)
        request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
