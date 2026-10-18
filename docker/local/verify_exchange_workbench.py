"""Retained local UI acceptance. Approves one synthetic plan, sends no platform writes."""
import json
import time
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from verify_exchange_ownership import Case
from test_multi_virtual_shipping import sql,quote

OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-workbench'
def main():
    OUT.mkdir(parents=True,exist_ok=True);manifest=OUT/'case.json';token=login()
    try:
        deadline=time.monotonic()+90
        while True:
            try:
                request('/order/exchanges/workbench',token=token)
                request('/goods/info/list?pageNum=1&pageSize=1',payload={},token=token,method='POST')
                break
            except Exception:
                if time.monotonic()>deadline:raise
                time.sleep(2)
        case=Case('ui',token);c=case.c
        case.prepare();case.authorize()
        original=case.dispatch_ship(case.allocate()['shipments'][0]);c['original_shipment_id']=original['id'];c['original_shipment_sn']=original['shipment_sn'];case.save()
        zero_sku=c['sku'][:-1]+'C'
        if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(zero_sku),'qm_oms_saas_commodity'):
            sample=case.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            case.api('/goods/info',{'skuSn':zero_sku,'goodsSn':zero_sku,'goodsName':'[换货 UI 验收] 零库存规格','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30})
        if manifest.exists():m=json.loads(manifest.read_text(encoding='utf-8'))
        else:
            e=case.inject(10,2);m={'exchange_id':e['id'],'exchange_sn':e['exchange_sn'],'order_id':c['order_id'],'platform_tid':c['platform_tid'],'target_sku':zero_sku};manifest.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
        def stock():return sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+quote(c['sku'])+','+quote(zero_sku)+") ORDER BY sku_sn",'qm_oms_saas_inventory')
        before=stock();errors=[];requests=[]
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1700,'height':1150});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.set_default_timeout(30000);page.on('pageerror',lambda error:errors.append(str(error)));page.on('request',lambda req:requests.append(req.url))
            page.goto(BASE+'/oms-order/exchanges',wait_until='networkidle');expect(page.get_by_role('tab',name='待审核',exact=False)).to_be_visible();page.screenshot(path=str(OUT/'workbench-list.png'),full_page=True)
            retained=Case('after',token).c
            page.goto(BASE+'/oms-order/exchanges?exchangeId='+str(retained['claims'][1]['id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('本次履约已完成',exact=True).first).to_be_visible();expect(drawer.get_by_role('button',name='更换商品',exact=True)).to_have_count(0);drawer.get_by_role('tab',name='来源与关联',exact=True).click();expect(drawer.locator('.lineage-node')).to_have_count(3);expect(drawer.locator('.lineage-node').nth(0)).to_contain_text(retained['sku']);expect(drawer.locator('.lineage-node').nth(2)).to_contain_text(retained['sku']);page.screenshot(path=str(OUT/'A-B-A-lineage.png'),full_page=True)
            page.goto(BASE+'/oms-order/exchanges?exchangeId='+str(m['exchange_id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text(m['exchange_sn'],exact=True)).to_be_visible();page.screenshot(path=str(OUT/'pending-plan.png'),full_page=True);e=request('/order/exchanges/'+str(m['exchange_id']),token=token)['data']
            if not e.get('approved_version'):
                drawer.get_by_role('button',name='选择换出商品',exact=True).click();picker=page.get_by_role('dialog',name='选择换出商品',exact=True);picker.get_by_placeholder('输入完整编码',exact=True).fill(c['sku']);picker.get_by_role('button',name='查询',exact=True).click();expect(picker.get_by_text(c['sku'],exact=True)).to_be_visible();picker.get_by_role('button',name='选择此规格',exact=True).click()
                drawer.get_by_role('button',name='更换商品',exact=True).click();picker=page.get_by_role('dialog',name='选择换出商品',exact=True);picker.get_by_placeholder('输入完整编码',exact=True).fill(zero_sku);picker.get_by_role('button',name='查询',exact=True).click();expect(picker.get_by_text('参考不足',exact=True)).to_be_visible();expect(picker.get_by_role('button',name='选择此规格',exact=True)).to_be_enabled();page.screenshot(path=str(OUT/'zero-stock-selectable.png'),full_page=True);picker.get_by_role('button',name='选择此规格',exact=True).click()
                drawer.get_by_role('button',name='更换实际来源',exact=True).click();source=page.get_by_role('dialog',name='选择本次实际退回来源',exact=True);row=source.locator('.el-table__row').filter(has_text=c['original_shipment_sn'] if c.get('original_shipment_sn') else case.shipment(c['original_shipment_id'])['shipment_sn']);row.get_by_role('button',name='选择此来源',exact=True).click()
                form=drawer.locator('.review-form');form.locator('.el-form-item').filter(has_text='退回虚拟仓').locator('input').click();page.locator('.el-select-dropdown:visible').get_by_text(c['warehouses'][0]['code'],exact=False).click();form.locator('.el-form-item').filter(has_text='平台退货地址 ID').locator('input').fill('991');form.get_by_text('已核对换出收货信息',exact=True).click();form.get_by_placeholder('填写核对结果及协商依据',exact=True).fill('[本地 UI 验收] 缺货可保存换货方案，保留申请 2 件；未同意平台申请')
                page.screenshot(path=str(OUT/'review-product-comparison.png'),full_page=True);drawer.get_by_role('button',name='保存审核方案',exact=True).click();expect(drawer.get_by_text('方案已审核，待同意申请',exact=True).first).to_be_visible()
            expect(drawer.get_by_role('button',name='更换商品',exact=True)).to_have_count(0);drawer.get_by_role('button',name='查询库存参考',exact=True).click();expect(drawer.get_by_text('参考缺口至少 2 件',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'approved-shortage-plan.png'),full_page=True)
            e=request('/order/exchanges/'+str(m['exchange_id']),token=token)['data'];assert e['state']=='APPROVED' and e['requested_quantity']==2 and e['target_sku']==zero_sku and e.get('stock_request') is None and e['platform_operation']=='NONE' and e['platform_status']=='1'
            assert stock()==before,'Search/approval changed stock';assert not errors,errors
            page.set_viewport_size({'width':1000,'height':900});page.screenshot(path=str(OUT/'detail-compact.png'),full_page=True);browser.close()
        m.update(verified=True,inventory_unchanged=True,no_platform_write=True,quantity_preserved=2,stock_reference_capacity=0,A_B_A_distinct=True,search_requests=len([u for u in requests if '/exchanges/goods-search' in u]),page_errors=[]);manifest.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(m,ensure_ascii=True))
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
