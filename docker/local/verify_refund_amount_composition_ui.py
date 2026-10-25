"""Read-only UI acceptance of 20 + 80 = 100, related refund navigation and actual goods actions."""
import json,re,time
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import BASE,login,request
from test_multi_virtual_shipping import sql

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-amount-composition'
expect.set_options(timeout=20000)

def main():
    token=login();deadline=time.monotonic()+100
    while True:
        try:
            data=request('/order/refunds/business/121/amount-records?pageNum=1&pageSize=20',token=token)['data']
            assert len(data['rows'])==1 and data['rows'][0]['business_refund_id']==118 and data['rows'][0]['confirmed_amount']==20
            break
        except Exception:
            if time.monotonic()>deadline:raise
            time.sleep(3)
    before=json.loads((OUT/'before.json').read_text('utf-8'));refs={id:request('/order/refunds/business/'+str(id),token=token)['data'] for id in [121,118,122]}
    errors=[];writes=[];related_reads=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1640,'height':1080});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
        def observe(page):
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.on('request',lambda r:writes.append(r.method+' '+r.url) if '/prod-api/' in r.url and r.method not in ['GET','HEAD','OPTIONS'] else None)
            page.on('request',lambda r:related_reads.append(r.url) if '/amount-records' in r.url else None)
        page=ctx.new_page();observe(page);page.goto(BASE+'/oms-order/businessRefunds?id=121',wait_until='networkidle');d=page.get_by_role('dialog',name=re.compile('^业务退款详情'))
        expect(d.locator('.refund-progress')).to_contain_text('本退款处理完成');basis=d.locator('.refund-amount-basis')
        expect(basis.locator('.basis-values strong')).to_have_text(['¥20.00','¥80.00','¥100.00','¥0.00'])
        expect(basis).not_to_contain_text('其他进行中退款占用');expect(d.locator('.application-quantities')).to_contain_text('退货入库');expect(d.locator('.application-quantities')).to_contain_text('已完成');expect(d).not_to_contain_text('分项处理未发与已发商品')
        assert not related_reads,'Related refunds must load only after clicking'
        basis.scroll_into_view_if_needed();page.screenshot(path=str(OUT/'20-plus80-equals100.png'),animations='disabled')
        basis.get_by_role('button',name='查看其他单已退 ¥20.00',exact=True).click();records=page.get_by_role('dialog',name='关联退款金额明细',exact=True)
        expect(records).to_be_visible();expect(records.locator('.el-table__body-wrapper tbody tr')).to_have_count(1);expect(records).to_contain_text('退差价');expect(records).to_contain_text('¥20.00');expect(records).not_to_contain_text(refs[121]['refund_sn']);assert len(related_reads)==1
        expect(records.get_by_role('button',name='下一页',exact=True)).to_be_disabled();page.screenshot(path=str(OUT/'related-difference20.png'),animations='disabled')
        with ctx.expect_page() as new_page:records.get_by_role('link',name=refs[118]['refund_sn'],exact=True).click()
        prior=new_page.value;observe(prior);prior.wait_for_load_state('networkidle');pd=prior.get_by_role('dialog',name=re.compile('^业务退款详情'));expect(pd.locator('.refund-identity-main > strong')).to_have_text(refs[118]['refund_sn']);expect(pd.locator('.basis-values strong')).to_have_text(['¥80.00','¥20.00','¥100.00','¥0.00']);expect(pd.locator('.refund-amount-basis')).not_to_contain_text('此前已退');prior.close()
        records.get_by_role('button',name='返回当前退款',exact=True).click();expect(records).not_to_be_visible()
        page.goto(BASE+'/oms-order/businessRefunds?id=122',wait_until='networkidle');d=page.get_by_role('dialog',name=re.compile('^业务退款详情'));expect(d.locator('.basis-values strong')).to_have_text(['¥20.00','¥80.00']);expect(d.locator('.refund-progress')).to_contain_text('申请金额超出可退额度');browser.close()
    current=request('/order/refunds/business/121',token=token)['data']
    assert before['refund']=={k:current[k] for k in before['refund']};assert before['amount_context']==current['amount_context']
    for id,old in refs.items():
        new=request('/order/refunds/business/'+str(id),token=token)['data']
        for k in ['state','review_state','review_by','refund_fee','goods_state','funds_state','control_version']:assert old[k]==new[k],(id,k)
    stock=sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ('RR20261024185730C08A','RR20261024185730C08G') ORDER BY sku_sn",'qm_oms_saas_inventory');assert stock==before['stock']
    assert not errors and not writes,(errors,writes)
    result={'status':'PASS','refund_id':121,'sku':'RR20261024185730C08A','amount_composition':[20,80,100,0],'related_refund_id':118,'related_reads_after_click':1,'on_demand':True,'page_errors':errors,'write_requests':writes,'stock_unchanged':True,'refunds_unchanged':True,'checks':['Completed amount shows other20 + current80 = cumulative100, remaining0','zero occupation hidden; pending limit still80','all actual tasks RETURN shows return stock completed','click20 reads scoped prior refund and opens correct business detail','older refund uses other80 instead of mislabeling the later80 as earlier']}
    (OUT/'ui-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS amount composition, lazy related refunds, reciprocal navigation, goods wording, no writes or stock changes')

if __name__=='__main__':main()
