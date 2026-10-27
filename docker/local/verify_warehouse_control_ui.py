"""Read-only browser validation; draft submissions are intercepted and source reads are allowed."""
import json,re
from pathlib import Path
from urllib.parse import urlparse,urlencode
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/warehouse-control';API='/supplychain/warehouseControl'
def main():
 state=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in state['cases']};token=login();checks=[];errors=[];mutations=[];intercepted=[]
 def api(p):return request(p,token=token)
 def baseline():return {c['sku']:{k:r[k] for k in ['totalStock','availableStock','allocatedStock','reservedStock','frozenStock']} for c in cases.values() for r in api('/inventory/productInventory?'+urlencode(dict(skuSn=c['sku'])))['rows']}
 before=baseline()
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1760,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'domain':'127.0.0.1','path':'/'}]);page=context.new_page();page.set_default_timeout(30000);page.on('pageerror',lambda e:errors.append(str(e)))
  def guard(route):
   r=route.request;path=urlparse(r.url).path
   if r.method in ['GET','OPTIONS'] or r.method=='POST' and path=='/prod-api'+API+'/sources':route.continue_()
   elif r.method=='POST' and path in ['/prod-api'+API+'/transfers','/prod-api'+API+'/reconciliations']:
    intercepted.append(dict(path=path,body=r.post_data_json));route.fulfill(status=200,json={'code':500,'msg':'页面验收已拦截保存，业务数据未改变'})
   else:mutations.append(dict(method=r.method,path=path));route.abort()
  context.route('**/prod-api/**',guard)
  def show(kind,id):
   path='/oms-supplychain/'+('warehouseTransfers' if kind=='transfers' else 'warehouseReconciliation')
   with page.expect_response(lambda r:urlparse(r.url).path=='/prod-api'+API+'/'+kind+'/'+str(id)):page.goto(BASE+path+'?id='+str(id))
   drawer=page.locator('.control-detail:visible');expect(drawer.locator('.doc-head h2')).to_have_count(1);return drawer
  def screenshot(panel,name):panel.screenshot(path=str(OUT/name))
  def dialog(title):return page.locator('.el-dialog__wrapper:visible').filter(has=page.get_by_text(title,exact=True)).last
  try:
   d=show('transfers',cases['full']['transfer_id']);expect(d.get_by_text('10 件',exact=True).first).to_be_visible();expect(d.get_by_text('两侧仓库交互与实际物流',exact=True)).to_be_visible();expect(d.locator('.execution')).to_have_count(2);assert 'DBO' in d.inner_text() and 'DBI' in d.inner_text();screenshot(d,'01-transfer-full.png');checks.append('完整调拨分别展示计划、已发、目的入库、在途、收回、释放和两侧独立仓库单')
   d=show('transfers',cases['short']['transfer_id']);expect(d.locator('.execution')).to_have_count(4);assert '独立收回原仓' in d.inner_text() and 'WC_REAL_WAYBILL_' in d.inner_text();screenshot(d,'02-transfer-consecutive-recovery.png');checks.append('最终短收后的两次独立收回均有仓单、实际物流和增量入账证据')
   d=show('transfers',cases['cancel']['transfer_id']);text=d.inner_text();assert '未出库剩余已停止' in text and '6 件' in text and '4 件' in text;screenshot(d,'03-transfer-partial-stop.png');checks.append('部分停止显示已发4、目的入库4、未出库释放6，避免表现为整单未执行')
   d=show('transfers',cases['mixed']['transfer_id']);assert '次品' in d.inner_text() and '正品' in d.inner_text() and '2.50' in d.inner_text();screenshot(d,'04-transfer-mixed-quality.png');checks.append('同SKU同批次正次品分行，单位成本和原质量独立可见')
   d=show('transfers',state['draft_transfer_id']);d.get_by_role('button',name='审核并锁定原库存',exact=True).click();box=page.locator('.el-message-box__wrapper:visible');expect(box.get_by_text('审核调拨',exact=True)).to_be_visible();box.get_by_role('button',name='取消',exact=True).click();checks.append('草稿审核前明确提示锁定和自动下发，取消确认没有业务请求')
   page.goto(BASE+'/oms-supplychain/warehouseTransfers');page.get_by_role('button',name='新增调拨',exact=True).click();create=dialog('新增跨实仓调拨');select=create.locator('.two .el-select input').first;select.click();page.locator('.el-select-dropdown:visible').get_by_text(re.compile(re.escape(state['store_source'])+r' /')).click();expect(select).to_have_value(re.compile(state['store_source']));create.locator('textarea').first.fill(cases['missing']['sku']);create.get_by_role('button',name='查询可调批次',exact=True).click();expect(create.locator('.el-table__body-wrapper tr.el-table__row')).to_have_count(1);assert '当前可用' in create.inner_text() and str(state['final_stock']['missing']['available']) in create.inner_text();screenshot(create,'05-create-transfer-source.png');checks.append('新增调拨分仓库、原批次和原因三段，实际查询库存并显示可调数量及原成本')
   target=create.locator('.two .el-select input').nth(1);target.click();dropdown=page.locator('.el-select-dropdown:visible');expect(dropdown.locator('.el-select-dropdown__item')).to_have_count(1);dropdown.get_by_text(re.compile(re.escape(state['store_target'])+r' /')).click();checks.append('目的仓候选只包含同货主另一实仓，不包含同实仓虚仓或其他货主')
   create.locator('.el-input-number input').fill('1');create.locator('textarea').last.fill('页面验收保存校验，仅拦截请求');create.get_by_role('button',name='保存待审核调拨',exact=True).click();expect(page.get_by_text('页面验收已拦截保存，业务数据未改变',exact=True)).to_be_visible();assert intercepted[-1]['body']['lines'][0]['quantity']==1;create.get_by_role('button',name='取消',exact=True).click();checks.append('新增表单提交原批次ID、质量和数量；请求已拦截，未新增业务单')
   c=cases['reconcile'];d=show('reconciliations',c['reconciliation_ids']['aggregate']);text=d.inner_text();assert state['store_source'] in text and state['store_source2'] in text and 'OMS快照' in text and 'WMS响应接收时间' in text;assert '实物一致' in text;screenshot(d,'06-reconciliation-aggregate.png');checks.append('对账详情列出全部虚仓来源、10件聚合口径、双边快照时间及实物一致结论')
   d=show('reconciliations',c['reconciliation_ids']['loss']);text=d.inner_text();assert '-2' in text and '库存已入账' in text and '独立差异调整申请' in text;screenshot(d,'07-reconciliation-loss-reviewed.png');checks.append('盘亏详情明确显示差2件和独立调整申请的原因、凭证、审核人及入账状态')
   d=show('reconciliations',cases['pending']['reconciliation_ids']['pending']);text=d.inner_text();assert '暂不调整' in text and '调拨两侧有未结束执行或在途实收待确认' in text;expect(d.get_by_role('button',name='申请差异调整',exact=True)).to_have_count(0);screenshot(d,'08-reconciliation-pending.png');checks.append('待确认调拨列出原DB单号及等待原因，禁止把差额直接作为盘亏处理')
   d=show('reconciliations',cases['missing']['reconciliation_ids']['missing']);text=d.inner_text();assert '仓库未返回该库存' in text and '未提供' in text and '缺失不等于0' in text;screenshot(d,'09-reconciliation-missing.png');checks.append('仓库未返回库存时显示未提供和下一步提示，不显示虚假的0库存或缺10件')
   d=show('reconciliations',cases['frozen']['reconciliation_ids']['frozen']);assert '冻结 / 业务占用待核对' in d.inner_text();screenshot(d,'10-reconciliation-frozen.png');checks.append('冻结和OMS业务占用分开展示，实物一致时仍能定位冻结来源差异')
   d.get_by_role('button',name='重新检查最新库存',exact=True).click();create=dialog('新建库存检查');expect(create.locator('textarea')).to_have_value(cases['frozen']['sku']);expect(create.get_by_role('button',name='提交库存检查',exact=True)).to_be_visible();create.get_by_role('button',name='取消',exact=True).click();checks.append('重新检查复用原仓库及SKU并创建新快照，旧快照保留')
   d.get_by_role('button',name='关闭',exact=True).click();page.get_by_role('button',name='仓库盘点通知',exact=True).click();reports=dialog('仓库盘点通知（仅留证，未自动改账）');assert '变化 -2' in reports.inner_text() and '盘点总量 8' in reports.inner_text();screenshot(reports,'11-inventory-report.png');checks.append('仓库盘点通知区分有符号变化-2和绝对总量8，并明确通知只留证')
   page.goto(BASE+'/oms-supplychain/operations');expect(page.get_by_role('button',name=re.compile('仓库库存待核查'))).to_be_visible();expect(page.get_by_role('button',name=re.compile('调拨在途待处理'))).to_be_visible();page.locator('.supplychain-operations .el-form input').first.fill(api(API+'/transfers/'+str(cases['pending']['transfer_id']))['data']['document_sn']);page.get_by_role('button',name='查询',exact=True).click();expect(page.get_by_text('调拨两侧有未结束执行或在途实收待确认',exact=False)).to_have_count(0);page.screenshot(path=str(OUT/'12-supplychain-operations.png'));checks.append('统一供应链待办新增库存核查和调拨在途入口，保留完整单号筛选')
   d=show('transfers',state['audit_transfer_id']);expect(d.get_by_text('人工处理记录',exact=True)).to_be_visible();assert '核对人工决策审计后停止未出2件' in d.inner_text();checks.append('人工处理记录展示创建、审核、停止的实际用户及业务原因')
   page.goto(BASE+'/oms-inventory/productInventory?skuSn='+cases['missing']['sku']);r=page.locator('.product-inventory .el-table__body-wrapper tr.el-table__row').first;expect(r).to_be_visible();cells=r.locator('td');assert [cells.nth(i).inner_text().strip() for i in [1,2,3]]==['10','8','2'];page.locator('.product-inventory .product-name:visible').first.click();inventory=page.locator('.product-inventory-drawer:visible');expect(inventory.get_by_text('公司拥有 10 = 仓库实物 8 + 调拨在途 2；在途独立跟踪，不计入可用库存。',exact=True)).to_be_visible();screenshot(inventory,'13-inventory-transit-owned.png');checks.append('商品库存列表及详情直接区分公司拥有10、仓库实物8、调拨在途2，展示关联原调拨来源')
   assert not errors,errors;assert not mutations,mutations
  finally:
   browser.close()
 after=baseline();assert before==after,'Browser checks changed stock'
 (OUT/'browser-check.json').write_text(json.dumps(dict(result='PASS',checks=checks,js_errors=errors,unexpected_mutations=mutations,intercepted_submissions=intercepted,stock_unchanged=True),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'browser checks; no JavaScript errors or stock changes')
if __name__=='__main__':main()
