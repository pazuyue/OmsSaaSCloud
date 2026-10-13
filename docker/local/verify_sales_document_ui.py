"""Read-only UI acceptance. Sales examples are intercepted responses, never live business records."""
import json
import sys
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

OUTPUT=Path(__file__).resolve().parents[2]/'outputs/sales-documents'

def main():
    OUTPUT.mkdir(parents=True,exist_ok=True)
    token=login()
    root='/supplychain/purchaseWorkspace'
    try:
        live=request(root+'/ticket/list?documentType=SALE_OUT',token=token)
        assert live['total']==0,'Historical shipments must not be backfilled'
        choices=request(root+'/options',token=token)['data']
        assert {t['code'] for t in choices['documentTypes']} >= {'SALE_OUT','PURCHASE_IN'}
        choices['documentTypes'].append({'code':'TRANSFER_OUT','label':'调拨出库'})
        base={'id':9999921,'sn':'XS_UI_ACCEPT_001','documentType':'SALE_OUT','documentTypeLabel':'销售出库','directionLabel':'出库','ticketType':2,'originalSn':'SO_UI_ACCEPT','relationSn':'XS_UI_SOURCE','salesOrderId':9999920,'salesShipmentId':9999922,'channelName':'验收店铺','channelId':27,'platformTid':'990_UI_ACCEPT','ownerName':'验收货主','realWarehouseName':'验收实体仓','warehouseName':'验收虚仓','wmsSimulationCode':'VC0001','actualWarehouse':2,'modeLabel':'虚拟出库','providerLabel':'','numberExpected':8,'numberActually':4,'numberPosted':4,'numberPending':0,'remainingQuantity':4,'inventoryStatus':2,'statusTicket':1,'statusNotify':0,'progress':'PROCESSING','progressLabel':'部分出库','executionLabel':'部分出库','postingLabel':'已出库数量已扣账','postingState':'POSTED','sourceLabel':'销售出库','receiptFinal':0,'businessUpdatedTime':'2026-10-12 18:00:00','receiptMissing':False,'step':1,'nextAction':'剩余 4 件待出库；请从来源发货单继续处理。','cancelBlockedReason':'销售出库单由来源发货单统一控制。','errorStage':'','events':[]}
        line={'id':9999923,'goodsName':'验收销售商品','skuSn':'UI-SALE-SKU','batchCode':'UI-BATCH-01','numberExpected':8,'numberActually':4,'numberZp':4,'numberCp':0,'numberPosted':4,'inventoryIsHandle':2,'postingLabel':'已出库数量已扣账','errorInfo':''}
        sale=dict(base,lines=[line])
        purchase=dict(base,id=9999931,sn='RK_UI_ACCEPT',documentType='PURCHASE_IN',documentTypeLabel='采购入库',ticketType=1,directionLabel='入库',modeLabel='虚拟入库',sourceLabel='采购入库',progress='COMPLETE',progressLabel='已完成',executionLabel='收货结束',postingLabel='全部实收入账',numberActually=8,numberPosted=8,receiptFinal=1)
        transfer=dict(base,id=9999941,sn='DB_UI_ACCEPT',documentType='TRANSFER_OUT',documentTypeLabel='调拨出库',sourceLabel='调拨出库')
        seen=[]
        def route(handler):
            req=handler.request
            assert req.method=='GET','UI must not execute any business writes'
            parsed=urlparse(req.url);path=parsed.path;params=parse_qs(parsed.query)
            if path.endswith('/options'):reply={'code':200,'data':choices}
            elif path.endswith('/ticket/list') or path.endswith('/line/list'):
                seen.append(params);rows=[sale,purchase,transfer]
                if params.get('documentType',[''])[0]:rows=[r for r in rows if r['documentType']==params['documentType'][0]]
                if params.get('ticketType',[''])[0]:rows=[r for r in rows if str(r['ticketType'])==params['ticketType'][0]]
                if params.get('sn',[''])[0]:rows=[r for r in rows if r['sn']==params['sn'][0]]
                if path.endswith('/line/list'):rows=[{**r,**line,'id':9999923} for r in rows]
                reply={'code':200,'rows':rows,'total':len(rows)}
            elif path.endswith('/ticket/9999921/batches'):reply={'code':200,'rows':[{'id':9999924,'skuSn':'UI-SALE-SKU','goodsName':'验收销售商品','batchCode':'UI-BATCH-01','goodQty':4,'badQty':0,'posted':1,'messageId':'VO_UI_ACCEPT','sourceMode':'VIRTUAL','operatorName':'验收操作人','createTime':'2026-10-12 18:00:00'}],'total':1}
            elif path.endswith('/ticket/9999921'):reply={'code':200,'data':sale}
            else:raise AssertionError('Unexpected document request: '+path)
            handler.fulfill(status=200,content_type='application/json',body=json.dumps(reply,ensure_ascii=False))
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.route('**/prod-api/supplychain/purchaseWorkspace/**',route)
            page.goto(BASE+'/oms-supplychain/wmsTickets',wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').get_by_text('调拨出库',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUTPUT/'all-types-desktop.png'),full_page=True)
            page.goto(BASE+'/oms-supplychain/wmsTickets?documentType=SALE_OUT',wait_until='networkidle')
            expect(page.get_by_role('button',name='XS_UI_ACCEPT_001',exact=True)).to_be_visible()
            assert not page.get_by_role('button',name='RK_UI_ACCEPT',exact=True).count()
            page.get_by_role('button',name='XS_UI_ACCEPT_001',exact=True).click()
            drawer=page.locator('.el-drawer:visible')
            page.wait_for_function("(element) => Math.abs(element.getBoundingClientRect().right - window.innerWidth) < 2", arg=drawer.element_handle())
            expect(drawer.get_by_text('实际出库',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='查看来源发货单',exact=True)).to_be_visible()
            assert not drawer.get_by_role('button',name='重试虚拟入库',exact=True).count()
            assert not drawer.get_by_role('button',name='取消入库单',exact=True).count()
            assert not drawer.get_by_text('单价',exact=True).count()
            page.screenshot(path=str(OUTPUT/'sale-detail-desktop.png'),full_page=True)
            drawer.get_by_role('tab',name='实际出库记录',exact=True).click()
            expect(drawer.get_by_text('VO_UI_ACCEPT',exact=True)).to_be_visible()
            expect(drawer.get_by_text('扣账成功',exact=True)).to_be_visible()
            page.screenshot(path=str(OUTPUT/'sale-movements-desktop.png'),full_page=True)
            drawer.get_by_role('tab',name='关联单据',exact=True).click()
            expect(drawer.get_by_text('990_UI_ACCEPT',exact=False)).to_be_visible()
            drawer.get_by_role('button',name='关闭',exact=True).click()
            page.get_by_role('button',name='商品明细',exact=True).click()
            expect(page.get_by_text('UI-SALE-SKU',exact=True).first).to_be_visible()
            page.screenshot(path=str(OUTPUT/'sale-lines-desktop.png'),full_page=True)
            page.set_viewport_size({'width':390,'height':844})
            page.screenshot(path=str(OUTPUT/'sale-lines-mobile.png'),full_page=True)
            assert not errors,errors
            assert any(q.get('documentType')==['SALE_OUT'] for q in seen)
            browser.close()
        OUTPUT.joinpath('verification.json').write_text(json.dumps({'live_historical_backfill':0,'ui_fixture_only':True,'new_xs_type_filter':True,'sales_lines_and_movements':True,'no_purchase_actions_on_sales':True,'browser_errors':[]},indent=2),encoding='utf8')
        print('PASS sales document types, XS list, detail, movements, related links and line view; no live writes')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
