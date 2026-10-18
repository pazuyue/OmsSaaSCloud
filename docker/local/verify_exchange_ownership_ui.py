"""Read-only browser acceptance of retained exchange lineage and ownership."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-ownership'

def main():
    after=json.loads((OUT/'after-case.json').read_text(encoding='utf-8'))
    before=json.loads((OUT/'before-case.json').read_text(encoding='utf-8'))
    assert after.get('completed') and before.get('completed')
    token=login()
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True)
            context=browser.new_context(viewport={'width':1700,'height':1150})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
            page=context.new_page();page.set_default_timeout(30000);errors=[]
            page.on('pageerror',lambda error:errors.append(str(error)))
            claim=after['claims'][1]
            page.goto(BASE+'/oms-order/exchanges?exchangeId='+str(claim['id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            expect(drawer.get_by_text('本次换回商品',exact=True)).to_be_visible()
            expect(drawer.get_by_text('发货明细 #'+str(claim['source_shipment_line_id']),exact=False).first).to_be_visible()
            expect(drawer.locator('.product-panel').first).to_contain_text(after['target_sku'])
            expect(drawer.locator('.product-panel').last).to_contain_text(after['sku'])
            page.screenshot(path=str(OUT/'after-A-B-A-source.png'),full_page=True)
            drawer.get_by_role('tab',name='换出发货',exact=True).click()
            expect(drawer.get_by_role('button',name=claim['shipment_sn'],exact=False).first).to_be_visible()
            page.screenshot(path=str(OUT/'exchange-owned-shipment.png'),full_page=True)
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(after['order_id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            drawer.get_by_role('tab',name='换货记录',exact=False).click()
            drawer.locator('.order-exchange-records .el-radio-button').last.click()
            for claim in after['claims']:
                expect(drawer.get_by_text(claim['exchange_sn'],exact=True).first).to_be_visible()
                expect(drawer.get_by_role('button',name=claim['shipment_sn'],exact=False).first).to_be_visible()
            page.screenshot(path=str(OUT/'one-SO-after-sales-records.png'),full_page=True)
            before_detail=request('/order/orders/business/'+str(before['order_id']),token=token)['data']
            assert all(l.get('oms_goods_name') for l in before_detail['lines'])
            assert all(not l.get('platform_spec') and not l.get('manual_sku_sn') for l in before_detail['lines'] if l.get('exchange_id'))
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(before['order_id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            drawer.get_by_role('tab',name='商品明细',exact=True).click()
            drawer.locator('.history-toggle .el-switch').click()
            for claim in before['claims']:expect(drawer.get_by_text(claim['exchange_sn'],exact=True).first).to_be_visible()
            expect(drawer.get_by_text('有效需求（件）',exact=True)).to_be_visible()
            drawer.get_by_text('有效需求（件）',exact=True).evaluate("el => el.scrollIntoView({block: 'center'})")
            page.screenshot(path=str(OUT/'before-A-B-A-lines.png'),full_page=True)
            claim=after['claims'][1]
            tickets=request('/supplychain/purchaseWorkspace/ticket/list?sn='+claim['return_sn'],token=token)['rows']
            assert len(tickets)==1
            ticket=tickets[0]
            d=request('/supplychain/purchaseWorkspace/ticket/'+str(ticket['id']),token=token)['data']
            assert d['numberPosted']==2 and d['lines'][0]['numberPosted']==2 and d['lines'][0]['postingState']=='POSTED'
            batches=request('/supplychain/purchaseWorkspace/ticket/'+str(ticket['id'])+'/batches',token=token)['rows']
            assert len(batches)==1 and batches[0]['goodQty']==2 and batches[0]['posted']==1
            page.goto(BASE+'/oms-supplychain/wmsTickets?documentId='+str(ticket['id']),wait_until='networkidle')
            drawer=page.locator('.el-drawer:visible')
            expect(drawer.get_by_role('button',name='查看所属换货单',exact=True)).to_be_visible()
            drawer.get_by_role('tab',name='实际收货批次',exact=True).click()
            expect(drawer.get_by_text('TEST_RETURN_'+str(claim['id']),exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'exchange-return-batches.png'),full_page=True)
            drawer.get_by_role('tab',name='关联单据',exact=True).click()
            expect(drawer.get_by_text(claim['exchange_sn'],exact=True).first).to_be_visible()
            expect(drawer.get_by_text(after['order_sn'],exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT/'exchange-ledger-owner.png'),full_page=True)
            assert not errors,errors
            browser.close()
        (OUT/'ui-verification.json').write_text(json.dumps({'source_visible':True,'exchange_shipments_visible':True,'root_after_sales_records_visible':True,'immutable_before_lines_visible':True,'return_batches_visible':True,'exchange_ledger_owner_visible':True,'page_errors':[]},indent=2),encoding='utf-8')
        print('PASS exchange ownership UI: source lineage, own shipments, original SO and immutable lines')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
