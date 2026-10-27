"""Fresh-browser return document acceptance. All business requests are GET only."""
import json
import csv
from pathlib import Path
from urllib.parse import urlencode
from playwright.sync_api import sync_playwright
from verify_goods_workspace_ui import login

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-document-ui';BASE='http://127.0.0.1:8088'


def main():
    case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24'];refund=case['final_refund']['refund_sn'];exchange=case['final_exchanges'][1]['exchange_sn'];sn=case['shared_return']['document']['return_sn'];checks=[];errors=[];requests=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1920,'height':1140},accept_downloads=True);context.add_cookies([{'name':'Admin-Token','value':login(),'domain':'127.0.0.1','path':'/'}]);page=context.new_page();page.set_default_timeout(45000);page.on('pageerror',lambda e:errors.append(str(e)));page.on('request',lambda r:requests.append({'method':r.method,'url':r.url}))
        def wait_url(part,action):
            with page.expect_response(lambda r:part in r.url and r.request.method=='GET',timeout=45000) as captured:action()
            response=captured.value;assert response.status==200;assert response.json().get('code')==200,response.json();page.wait_for_timeout(300);return response.json().get('data')
        result=wait_url('/order/returns?',lambda:page.goto(BASE+'/oms-order/returns?'+urlencode({'view':'DOCUMENT','number':refund})))
        assert result['total']==1 and len(result['rows'][0]['warehouse_documents'])==1
        page.locator('.warehouse-status').get_by_role('button',name='仓库已受理',exact=True).wait_for();page.screenshot(path=str(OUT/'01-list-warehouse-status.png'));checks.append('列表显示独立仓库状态和最近交互结果，一张共同仓单只显示一行')
        wait_url('/order/returns/document?',lambda:page.locator('.return-management .number').filter(has_text=sn).first.click())
        detail=page.locator('.return-document-detail:visible');detail.get_by_role('tab',name='入库明细',exact=True).wait_for();assert detail.get_by_role('tab').count()==4;assert detail.locator('.document-products .el-table__body-wrapper').first.locator('tr.el-table__row').count()==1;assert case['target_sku'] in detail.inner_text();assert '已确认实收均已入账' in detail.inner_text();detail.screenshot(path=str(OUT/'02-document-products.png'));checks.append('专用详情有四个页签，默认按实际B商品汇总整单2件并分开展示三段进度')
        assert not any('/wmsIntegration/log-center?' in r['url'] for r in requests);assert not any('/wmsIntegration/logs/' in r['url'] for r in requests);checks.append('初次查看商品不预加载日志或原始报文')
        detail.locator('.document-products .el-table__expand-icon').first.click();detail.get_by_text(refund,exact=True).wait_for();assert exchange in detail.inner_text();assert case['audit']['source'][0][3] in detail.inner_text();detail.screenshot(path=str(OUT/'03-product-sources.png'));checks.append('展开实际商品可核对退款和换货各1件及原实际发货和成本')
        detail.get_by_role('tab',name='售后归属',exact=True).click();assert detail.locator('tr.matched-source').filter(has_text=refund).count()>=1;assert detail.locator('tr.matched-source td').first.evaluate('(e)=>getComputedStyle(e).backgroundColor')=='rgb(237, 245, 255)';checks.append('来源页保留整单归属并高亮从当前退款进入的来源')
        detail.get_by_role('tab',name='实收与库存',exact=True).click();detail.locator('.return-ledger').nth(1).get_by_text('入账成功',exact=True).first.wait_for();assert detail.locator('.return-ledger').count()==3;receipts=detail.locator('.return-ledger').nth(0);stock=detail.locator('.return-ledger').nth(1);assert receipts.locator('tr.el-table__row').count()==1;assert stock.locator('tr.el-table__row').count()==2;assert 'REFUND_RETURN_' in stock.inner_text() and 'EXCHANGE_RETURN_' in stock.inner_text();receipts.locator('.el-table__expand-icon').first.click();receipts.screenshot(path=str(OUT/'04-receipts-and-stock.png'));stock.screenshot(path=str(OUT/'04b-stock-lines.png'));checks.append('实收页仅一条原回传，库存页显示两条来源入账和各自流水关联号')
        stock.get_by_role('button',name='查看回传',exact=True).first.click();dialog=page.locator('.el-dialog__wrapper:visible').last;dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).wait_for();assert sn in dialog.inner_text();assert not any('/wmsIntegration/logs/' in r['url'] for r in requests);checks.append('从库存明细定位原仓库交互，报文仍按需加载')
        wait_url('/wmsIntegration/logs/',lambda:dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).click());dialog.locator('pre').wait_for();dialog.get_by_text('响应报文',exact=True).click();assert dialog.locator('pre').inner_text();dialog.screenshot(path=str(OUT/'05-interaction-payload.png'));checks.append('交互详情可查看已脱敏请求响应，并区分当次结果和当前仓单状态')
        dialog.get_by_role('button',name='关闭',exact=True).click();detail.locator('.return-interactions table').first.wait_for();detail.screenshot(path=str(OUT/'06-warehouse-interactions.png'));checks.append('仓库交互页在原详情内展示下发回传时间动作结果及请求编号')
        page.locator('.el-drawer:visible .el-drawer__close-btn').click()
        with page.expect_download() as download:page.get_by_role('button',name='导出查询结果',exact=True).click()
        export=OUT/'document-export.csv';download.value.save_as(str(export));csv_rows=list(csv.reader(export.read_text('utf-8-sig').splitlines()));assert len(csv_rows)==2 and '仓库受理状态' in csv_rows[0] and '仓库已受理' in csv_rows[1] and '成功' in csv_rows[1];checks.append('导出同步包含中文仓库受理及最近交互结果')
        select=page.locator('.filters .el-form-item').filter(has_text='仓库交互').locator('.el-select');select.click();page.locator('.el-select-dropdown:visible').get_by_text('下发失败',exact=True).click();result=wait_url('/order/returns?',lambda:page.locator('.filters .el-button--primary').click());assert result['total']==0;checks.append('列表仓库交互筛选有效，已受理共同单不误归为下发失败')
        url=BASE+'/oms-supplychain/wmsTickets?'+urlencode({'sn':sn,'documentId':case['shared_return']['document']['ticket_id'],'sourceNumber':refund});page.goto(url);detail=page.locator('.return-document-detail:visible');detail.get_by_role('tab',name='入库明细',exact=True).wait_for();detail.get_by_role('tab',name='售后归属',exact=True).click();assert detail.locator('tr.matched-source').count()==1;detail.screenshot(path=str(OUT/'07-warehouse-entry.png'));checks.append('原出入库单入口也使用同一专用退货详情，保留退款来源高亮')
        partial=json.loads((ROOT/'outputs/exchange-partial-continuous/manifest.json').read_text('utf-8'))['cases']['19'];result=wait_url('/order/returns?',lambda:page.goto(BASE+'/oms-order/returns?'+urlencode({'view':'DOCUMENT','number':partial['claims'][0]['exchange_sn']})));assert result['rows'][0]['units'][0]['quantity']==2 and result['rows'][0]['units'][0]['posted_quantity']==1;wait_url('/order/returns/document?',lambda:page.locator('.return-management .number').first.click());detail=page.locator('.return-document-detail:visible');assert '部分实收' in detail.inner_text();assert '待收 1 件' in detail.inner_text();detail.screenshot(path=str(OUT/'08-partial-receipt.png'));checks.append('部分实收单保留计划2件实收1件入账1件和待收1件')
        business_posts=[r for r in requests if r['method'] not in ['GET','OPTIONS'] and '/prod-api/' in r['url']];assert not business_posts,business_posts;assert not errors,errors;checks.append('验收页面无JavaScript错误且未调用任何业务变更接口');browser.close()
    (OUT/'browser-check.json').write_text(json.dumps({'checks':checks,'javascript_errors':errors,'business_mutations':business_posts,'requests':requests},ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'browser checks; no JavaScript errors or business commands')


if __name__=='__main__':main()
