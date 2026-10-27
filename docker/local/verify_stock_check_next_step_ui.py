"""Read-only acceptance of the user's blocked check and navigation to its pending transfer."""
import json,urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote
from verify_full_stock_check import baseline

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/stock-check-next-step';API='/supplychain/warehouseControl';SN='DZ29190e8932304d07b9b9c8d3a3799d64'


def main():
    OUT.mkdir(parents=True,exist_ok=True);id=int(sql("SELECT id FROM warehouse_reconciliation WHERE company_code='QM' AND document_sn="+quote(SN),'qm_oms_saas_commodity'));token=login();api=lambda path:request(path,token=token)['data'];document=api(API+'/reconciliations/'+str(id));pending=document['result']['pending_documents'];assert len(pending)==1;transfer_sn=pending[0]['document_sn'];transfer_id=int(sql("SELECT id FROM warehouse_transfer WHERE company_code='QM' AND document_sn="+quote(transfer_sn),'qm_oms_saas_commodity'));transfer=api(API+'/transfers/'+str(transfer_id));before=baseline();checks=[];errors=[];mutations=[]
    def evidence():return dict(document=api(API+'/reconciliations/'+str(id)),transfer=api(API+'/transfers/'+str(transfer_id)),task_count=sql("SELECT COUNT(*) FROM warehouse_reconciliation WHERE company_code='QM'",'qm_oms_saas_commodity'),warehouse_logs=sql("SELECT COUNT(*) FROM wms_interaction_log WHERE company_code='QM'",'qm_oms_saas_commodity'))
    original=evidence()
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1660,'height':1200});ctx.add_cookies([dict(name='Admin-Token',value=token,domain='127.0.0.1',path='/')]);page=ctx.new_page();page.set_default_timeout(30000);page.on('pageerror',lambda e:errors.append(str(e)))
        def readonly(route):
            if route.request.method in ['GET','OPTIONS']:route.continue_()
            else:mutations.append(dict(path=urllib.parse.urlparse(route.request.url).path,method=route.request.method));route.abort()
        ctx.route('**/prod-api/**',readonly)
        try:
            page.goto(BASE+'/oms-supplychain/warehouseReconciliation?id='+str(id));drawer=page.locator('.control-detail:visible');panel=drawer.locator('.check-next-step');expect(drawer.locator('.doc-head h2')).to_have_text(SN);expect(panel.get_by_text('下一步：先核查关联单据，暂不能调整库存',exact=True)).to_be_visible();expect(panel.get_by_text('当前差额尚不能认定为真实盘盈盘亏。',exact=False)).to_be_visible();expect(drawer.get_by_role('button',name='申请差异调整',exact=True)).to_have_count(0);checks.append('原单检查完成但仍有调拨阻断，顶部明确解释不能调整，不隐藏处理原因')
            row=drawer.locator('.el-table__body-wrapper tr.el-table__row').filter(has_text='WC_BATCH_pending');expect(row).to_have_count(1);assert '-2' in row.inner_text();expect(drawer.get_by_role('button',name='查看调拨单',exact=True)).to_be_visible();drawer.screenshot(path=str(OUT/'01-pending-next-step.png'));checks.append('差额-2与关联调拨单号保留，待确认单据增加直接查看入口')
            panel.get_by_role('button',name='核查关联调拨',exact=True).click();page.wait_for_url('**/warehouseTransfers?**');drawer=page.locator('.control-detail:visible');expect(drawer.locator('.doc-head h2')).to_have_text(transfer_sn);expect(drawer.get_by_role('button',name='返回来源库存检查',exact=True)).to_be_visible();assert urllib.parse.parse_qs(urllib.parse.urlparse(page.url).query)['return_check_id']==[str(id)];drawer.screenshot(path=str(OUT/'02-associated-transfer.png'));checks.append('通过只读精确单号查询打开正确调拨，保留来源库存检查回跳入口')
            drawer.get_by_role('button',name='返回来源库存检查',exact=True).click();page.wait_for_url('**/warehouseReconciliation?**');drawer=page.locator('.control-detail:visible');expect(drawer.locator('.doc-head h2')).to_have_text(SN);panel=drawer.locator('.check-next-step');expect(panel).to_be_visible();checks.append('从关联调拨返回原检查，不丢失单据上下文')
            panel.get_by_role('button',name='处理后重新检查库存',exact=True).click();form=page.locator('.inventory-check-create:visible');expect(form.locator('textarea')).to_have_value(document['scope']['skus'][0]);expect(form.get_by_role('button',name='指定商品检查',exact=False)).to_have_class('active');expect(form.get_by_role('button',name='提交库存检查',exact=True)).to_be_enabled();form.screenshot(path=str(OUT/'03-prefilled-recheck.png'));form.get_by_role('button',name='取消',exact=True).click();checks.append('后续复查预填原货主实仓、SKU与查询方式；取消后不新增检查任务')
            assert not errors,errors;assert not mutations,mutations
        finally:browser.close()
    after=evidence();assert original==after,'Read-only navigation changed a task, transfer, query count or warehouse evidence';assert before==baseline(),'Inventory or protected business records changed'
    result=dict(result='PASS',document_sn=SN,document_id=id,transfer_sn=transfer_sn,transfer_id=transfer_id,checks=checks,js_errors=errors,unexpected_mutations=mutations,stock_unchanged=True,warehouse_not_called=True,task_not_created=True,evidence=dict(state=document['state'],row=document['result']['rows'][0],pending=pending,transfer_state=transfer['state'],lines=transfer['lines'],executions=[{k:e[k] for k in ['side','dispatch_state','receipt_final','document_sn']} for e in transfer['executions']]))
    (OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'next-step browser checks; no new tasks, warehouse requests or stock changes')


if __name__=='__main__':main()
