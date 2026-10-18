"""Read-only browser/API acceptance of the unified WMS log center."""
import json,sys
from pathlib import Path
from urllib.parse import urlencode
from datetime import datetime,timedelta
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

OUT=Path(__file__).resolve().parents[2]/'outputs/wms-log-center'

def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();checks={}
    def api(path,params=None):return request('/supplychain/wmsIntegration/'+path+('?' + urlencode(params) if params else ''),token=token)['data']
    try:
        page=api('log-center');assert page['rows'];sample=next((r for r in page['rows'] if r['action']=='OUTBOUND_CANCEL'),page['rows'][0]);sn=sample['ticket_sn']
        exact={'numberType':'DOCUMENT','number':sn,'allTime':'true'};records=api('log-center',exact)['rows'];assert all(r['ticket_sn']==sn for r in records);assert not any('request_body' in r or 'payload_json' in r for r in records);checks['exact_number_and_lazy_payload']=True
        summary=api('log-center-summary',exact);assert summary['total']==len(records);checks['summary_matches_query']=True
        if sample.get('order_sn'):
            assert api('log-center',{'numberType':'ORDER','number':sample['order_sn'],'allTime':'true'})['rows'];checks['order_number_search']=True
        if sample.get('platform_tid'):
            assert api('log-center',{'numberType':'PLATFORM','number':sample['platform_tid'],'allTime':'true'})['rows'];checks['platform_number_search']=True
        inbound=api('log-center',{'businessType':'PURCHASE_IN','begin':(datetime.now()-timedelta(days=30)).strftime('%Y-%m-%d %H:%M:%S'),'end':datetime.now().strftime('%Y-%m-%d %H:%M:%S')})['rows']
        before_ids=[r['id'] for r in records]
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1680,'height':1050});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);tab=context.new_page();errors=[];writes=[];payload_calls=[]
            tab.on('pageerror',lambda e:errors.append(str(e)))
            tab.on('request',lambda r:writes.append(r.url.split('/prod-api/')[-1]) if r.method in ['POST','PUT','DELETE'] and '/prod-api/' in r.url else None)
            tab.on('request',lambda r:payload_calls.append(r.url) if '/wmsIntegration/logs/' in r.url else None)
            tab.goto(BASE+'/oms-supplychain/wmsLogs',wait_until='networkidle');expect(tab.get_by_role('heading',name='仓库交互日志',exact=True).last).to_be_visible();expect(tab.get_by_text('默认最近 24 小时，每次时间查询最多 31 天。输入完整单号或请求编号后，可选择本单全部时间。',exact=True)).to_be_visible();tab.screenshot(path=str(OUT/'center.png'),animations='disabled');checks['standalone_menu_page']=True
            tab.get_by_placeholder('输入完整单号',exact=True).fill(sn);tab.get_by_text('本单全部时间',exact=True).click();tab.locator('.filters').get_by_text('查询',exact=True).click();expect(tab.locator('.records .el-table__body-wrapper').first.locator('tbody tr')).to_have_count(len(records));checks['ui_exact_search']=True
            expect(tab.locator('.records')).to_contain_text('取消发货单');assert not payload_calls;checks['cancel_action_has_chinese_label']=True
            tab.locator('.records').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=tab.locator('.el-drawer:visible');expect(drawer.get_by_text('当前单据处理进度（不代表本次交互发生时的状态）',exact=True)).to_be_visible();expect(drawer.get_by_text('单据交互链路',exact=True)).to_be_visible();assert not payload_calls;checks['chain_and_current_progress_separated']=True
            drawer.get_by_role('tab',name='本次交互报文',exact=True).click();drawer.get_by_role('button',name='加载已脱敏报文',exact=True).click();expect(drawer.locator('pre')).to_be_visible();assert len(payload_calls)==1;drawer.get_by_placeholder('查找报文内容',exact=True).fill('request');tab.screenshot(path=str(OUT/'payload.png'),animations='disabled');checks['payload_on_demand_and_search']=True
            if sample.get('shipment_id'):
                drawer.get_by_role('button',name='打开发货单',exact=True).click();tab.wait_for_url('**/oms-order/shipments?**');tab.wait_for_load_state('networkidle');expect(tab.get_by_text(sn,exact=True).first).to_be_visible();checks['open_source_shipment']=True
            tab.goto(BASE+'/oms-supplychain/wmsLogs?'+urlencode(exact),wait_until='networkidle');tab.set_viewport_size({'width':1280,'height':720});tab.screenshot(path=str(OUT/'center-compact.png'),animations='disabled');assert tab.evaluate('document.documentElement.scrollWidth <= window.innerWidth');checks['compact_layout_without_page_overflow']=True
            # Existing shipment shortcut must navigate to the same page with its exact number.
            if sample.get('shipment_id'):
                tab.goto(BASE+'/oms-order/shipments?'+urlencode({'number':sn}),wait_until='networkidle');tab.get_by_role('button',name=sn,exact=True).first.click();d=tab.locator('.el-drawer:visible').first;d.get_by_role('button',name='仓库交互日志',exact=True).click();tab.wait_for_url('**/oms-supplychain/wmsLogs?**');expect(tab.get_by_placeholder('输入完整单号',exact=True)).to_have_value(sn);checks['shipment_shortcut_uses_center']=True
            if inbound:
                incoming=next((r for r in inbound if r.get('ticket_id')),None)
                if incoming:
                    iq={'numberType':'DOCUMENT','number':incoming['ticket_sn'],'allTime':'true'}
                    tab.goto(BASE+'/oms-supplychain/wmsLogs?'+urlencode(iq),wait_until='networkidle');tab.locator('.records').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=tab.locator('.el-drawer:visible');drawer.get_by_role('button',name='打开入库单',exact=True).click();tab.wait_for_url('**/oms-supplychain/wmsTickets?**');expect(tab.locator('.el-drawer:visible').get_by_text(incoming['ticket_sn'],exact=True).first).to_be_visible();checks['inbound_source_document_opens']=True
                    tab.locator('.el-drawer:visible').get_by_role('tab',name='交互日志',exact=True).click();tab.get_by_role('button',name='查看本单仓库交互日志',exact=True).click();tab.wait_for_url('**/oms-supplychain/wmsLogs?**');expect(tab.get_by_placeholder('输入完整单号',exact=True)).to_have_value(incoming['ticket_sn']);checks['purchase_shortcut_uses_center']=True
            assert not errors,errors;assert not writes,writes;checks['no_browser_errors_or_business_writes']=True
            context.unroute_all(behavior='wait');browser.close()
        assert before_ids==[r['id'] for r in api('log-center',exact)['rows']];checks['original_logs_unchanged']=True
        (OUT/'verification.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(checks,ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
