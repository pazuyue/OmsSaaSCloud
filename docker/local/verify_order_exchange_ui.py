"""Read-only acceptance of original SO exchange visibility and immutable product lineage."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
OUT=Path(__file__).resolve().parents[2]/'outputs/order-exchange-ui'
CASES=OUT.parent/'exchange-ownership'

def main():
    OUT.mkdir(exist_ok=True)
    after=json.loads((CASES/'after-case.json').read_text(encoding='utf-8'))
    before=json.loads((CASES/'before-case.json').read_text(encoding='utf-8'))
    token=login()
    try:
        detail=request('/order/orders/business/'+str(after['order_id']),token=token)['data']
        assert detail['shipped_quantity']==10 and detail['demand_quantity']==10
        assert len(detail['after_sales_lines'])==3
        returned_a=next(l for l in detail['after_sales_lines'] if l['sku_sn']==after['sku'])
        assert returned_a['id']!=detail['lines'][0]['id'] and returned_a['exchange_generation']==2
        assert [n['sku_sn'] for n in returned_a['lineage']]==[after['sku'],after['target_sku'],after['sku']]
        assert detail['exchange_active_count']>=1
        assert any(not e.get('fulfillment_line_id') for e in detail['exchange_records'])
        none=request('/order/orders/business/list?exchangeStatus=NONE&tid='+after['order_sn'],token=token)
        active=request('/order/orders/business/list?exchangeStatus=ACTIVE&tid='+after['order_sn'],token=token)
        assert none['total']==0 and active['total']==1
        before_detail=request('/order/orders/business/'+str(before['order_id']),token=token)['data']
        assert len(before_detail['lines'])==3 and not before_detail['after_sales_lines']
        assert sum(l['quantity']-l.get('cancelled_quantity',0) for l in before_detail['lines'])==10
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1680,'height':1120})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();page.set_default_timeout(30000);errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(after['order_id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            expect(drawer.locator('.detail-exchange-notice')).to_contain_text('进行中')
            drawer.get_by_role('tab',name='商品明细',exact=True).click()
            stock=drawer.locator('.business-stock-detail')
            stock.get_by_text('换货换出（3 条）',exact=True).click()
            rows=stock.locator('.after-sale-lines .line-identity')
            expect(rows).to_have_count(3)
            a=rows.filter(has_text='第 2 次换货后发出')
            expect(a).to_contain_text(after['sku']);expect(a).to_contain_text('换货换出商品')
            expect(a).to_contain_text(after['target_sku'])
            a.scroll_into_view_if_needed();page.screenshot(path=str(OUT/'after-sale-products.png'),full_page=True)
            a.get_by_role('button',name='查看换货链路',exact=True).click()
            lineage=page.locator('.order-lineage-dialog:visible')
            expect(lineage.locator('.lineage li')).to_have_count(3)
            expect(lineage.locator('.lineage li').first).to_contain_text(after['sku'])
            expect(lineage.locator('.lineage li').nth(1)).to_contain_text(after['target_sku'])
            expect(lineage.locator('.lineage li').last).to_contain_text(after['sku'])
            page.screenshot(path=str(OUT/'A-B-A-product-chain.png'),full_page=True)
            lineage.get_by_role('button',name='关闭',exact=True).click()
            drawer.get_by_role('tab',name='换货记录',exact=False).click()
            records=drawer.locator('.order-exchange-records')
            expect(records.locator('.exchange-record')).to_have_count(detail['exchange_active_count'])
            records.locator('.el-radio-button').last.click()
            expect(records.locator('.exchange-record')).to_have_count(detail['exchange_total_count'])
            for claim in after['claims']:expect(records.get_by_text(claim['exchange_sn'],exact=True)).to_be_visible()
            records.scroll_into_view_if_needed();page.screenshot(path=str(OUT/'all-exchange-records.png'),full_page=True)
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(before['order_id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible');drawer.get_by_role('tab',name='商品明细',exact=True).click()
            stock=drawer.locator('.business-stock-detail');expect(stock.locator('.sale-products .line-identity')).to_have_count(2)
            stock.locator('.history-toggle .el-switch').click()
            expect(stock.locator('.sale-products .line-identity')).to_have_count(3)
            expect(stock.get_by_text('已替换 · 不再发货',exact=True)).to_be_visible()
            stock.locator('.history-toggle').scroll_into_view_if_needed();page.screenshot(path=str(OUT/'before-sale-products-history.png'),full_page=True)
            # Verify list entry and drill-through without changing business data.
            page.goto(BASE+'/oms-order/business?view=business',wait_until='networkidle')
            page.get_by_placeholder('OMS / 平台 / 发货 / 售后 / 运单号',exact=True).fill(after['order_sn'])
            page.locator('.order-filters').get_by_role('button',name='查询',exact=False).click()
            summary=page.locator('.order-workspace > .el-table > .el-table__fixed .list-exchange-summary');expect(summary).to_have_count(1)
            expect(summary).to_contain_text('换货中');page.screenshot(path=str(OUT/'order-list-exchange-summary.png'),full_page=True)
            summary.get_by_role('button').click()
            expect(page.locator('.el-drawer:visible .order-exchange-records')).to_be_visible()
            assert not errors,errors
            browser.close()
        result={'original_sales_shipped':10,'replacement_lines':3,'distinct_A_B_A_lineage':True,'pending_application_visible':True,'exchange_filters_match':True,'terminated_history_collapsed':True,'list_drillthrough':True,'page_errors':[]}
        (OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print('PASS order exchange UI: per-line identity, original vs replacement totals, A-B-A lineage, pending records and list entry')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
