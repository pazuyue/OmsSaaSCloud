"""Read-only browser verification of the repaired test document's actual log history."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

SN='XS870a08cfdade45b5be4ad60d3cc802ab'
ROOT='/supplychain/wmsIntegration'
OUTPUT=Path(__file__).resolve().parents[2]/'outputs/wms-logs'

def main():
    OUTPUT.mkdir(parents=True,exist_ok=True);token=login()
    try:
        summary=request(ROOT+'/log-summary?sn='+SN,token=token)['data']
        assert summary['first_create']['id']==34 and summary['first_create']['result']=='UNKNOWN'
        assert summary['latest_create']['result']=='SUCCESS' and summary['latest_create']['connection_id']==10
        rows=request(ROOT+'/logs?sn='+SN+'&action=OUTBOUND_CREATE',token=token)['data'];assert len(rows)==2
        filtered=request(ROOT+'/logs?sn='+SN+'&action=OUTBOUND_CREATE&result=UNKNOWN',token=token)['data'];assert len(filtered)==1 and filtered[0]['id']==34
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];writes=[]
            page.on('pageerror',lambda error:errors.append(str(error)))
            def readonly(route):
                if route.request.method!='GET':writes.append(route.request.url);route.abort()
                else:route.continue_()
            page.route('**/prod-api/**',readonly)
            page.goto(BASE+'/oms-order/shipments',wait_until='networkidle');page.get_by_role('button',name=SN,exact=True).click();drawer=page.locator('.el-drawer:visible');expect(drawer.locator('.identity').get_by_text(SN,exact=True)).to_be_visible();expect(drawer.get_by_text('test01',exact=True)).to_be_visible();drawer.locator('.footer').get_by_role('button',name='仓库交互日志',exact=True).click();dialog=page.locator('.el-dialog:visible')
            expect(dialog.get_by_text('首次下发',exact=True)).to_be_visible();expect(dialog.get_by_text('最近下发',exact=True)).to_be_visible();expect(dialog.get_by_role('button',name='查看首次下发报文',exact=True)).to_be_visible();page.wait_for_timeout(350);page.screenshot(path=str(OUTPUT/'first-and-latest-create.png'))
            dialog.locator('input[placeholder="全部动作"]').click();page.locator('.el-select-dropdown:visible').get_by_text('下发发货单',exact=True).click();dialog.get_by_role('button',name='查询',exact=True).click();expect(dialog.locator('.el-table__body-wrapper tr')).to_have_count(2);page.screenshot(path=str(OUTPUT/'create-action-filter.png'))
            dialog.locator('input[placeholder="全部结果"]').click();page.locator('.el-select-dropdown:visible').get_by_text('结果待确认',exact=True).click();dialog.get_by_role('button',name='查询',exact=True).click();expect(dialog.locator('.el-table__body-wrapper tr')).to_have_count(1);expect(dialog.get_by_text('最近下发',exact=True)).to_be_visible()
            dialog.get_by_role('button',name='查看首次下发报文',exact=True).click();payload=page.locator('.el-dialog:visible').last;expect(payload.get_by_text('交互报文（已脱敏）',exact=True)).to_be_visible();expect(payload.locator('pre').first).to_contain_text('***');page.screenshot(path=str(OUTPUT/'original-create-payload.png'))
            assert not errors,errors;assert not writes,writes;browser.close()
        OUTPUT.joinpath('verification.json').write_text(json.dumps({'actual_document':SN,'original_create_preserved':34,'latest_create_success':True,'first_and_latest_outside_pagination':True,'action_and_result_filters':True,'browser_errors':errors,'browser_write_requests':writes},ensure_ascii=False,indent=2),encoding='utf8')
        print('PASS actual WMS log summary, action/result filters and original redacted payload; browser GET-only')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
