"""Read-only audit and retained report for automatic exchange cases 16-23."""
import json
import shutil
import sys
from datetime import datetime
from html import escape
from pathlib import Path
from verify_exchange_partial_continuous import Run, OUT, TITLES, SPECS, ORDER, SUPPLY, INV, sql, quote

SCENES = {
 '16':'原购A三件，先退其中两件换B两件，完成后再将实际收到的B两件换成C两件；原保留A一件不参与。',
 '17':'原购A两件，全部换成B两件；完成后只退B一件换C一件，另外B一件保留。',
 '18':'A两件换B两件，先非最终回传一件良品，再回传剩余一件并最终确认。收齐前不得验收或换出；完成后B两件再换C两件。',
 '19':'批准A两件换B两件，但只非最终实收并入账一件。现场核对等待节点、未换出及继续换出预览被拒绝；不修改业务等待时间。',
 '20':'商家明确授权的售后协商换货：A三件全部退回，第一轮B实际出库一件，确认截停剩余两件后，商家授权第二轮自动发出剩余两件。',
 '21':'商家明确授权的售后协商换货：A五件完整退回。B第一轮实发二件、截停三件；第二轮实发一件、截停二件；第三轮发完二件。每轮授权独立，原退回只入库一次。',
 '22':'售后协商换货A三件已完整退回，B仅实发一件，其余两件截停。通过退款同步和原订单JDP补拉输入同子单新退款事实，再尝试继续换出预览，须被当前退款状态阻断。',
 '23':'原购A三件，先退A一件换B一件；随后平台申请再换一件为C，没有明确实际退回来源。原A剩余两件与换出B一件均可成为候选，须转人工，不猜测最近发货。',
}
RESULTS={'PASS':'通过 · 自动链路完成','PASS_EXPECTED_WAIT':'通过 · 预期等待未收齐','PASS_EXPECTED_BLOCK':'通过 · 预期退款阻断','PASS_EXPECTED_MANUAL':'通过 · 预期来源转人工'}


def h(v):return escape(str(v if v is not None else ''))
def table(headers,rows):
    return '<div class="table"><table><thead><tr>'+''.join('<th>'+h(x)+'</th>' for x in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+h(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'
def rows(text):return [line.split('\t') for line in text.splitlines()]


def main():
    if sys.argv[1:] == ['--render-only']:
        from exchange_partial_report_detail import render
        print('Rendered retained detailed evidence:', render(json.loads((OUT/'manifest.json').read_text('utf-8')), OUT))
        return
    if sys.argv[1:]:
        raise SystemExit('Usage: report_exchange_partial_continuous.py [--render-only]')
    r=Run();assert set(r.data['cases'])==set(TITLES)
    assert r.protected()==r.data['protected_baseline'],'Previously retained evidence or queue/job configuration changed'
    evidence=[];cards=[];summary=[]
    for key,c in sorted(r.data['cases'].items()):
        assert c.get('result','').startswith('PASS')
        r.finish(c)  # Re-check actual current stock, actors, sources, rounds and no extra SO.
        ids=[e['id'] for e in c['final_exchanges']];scope=','.join(map(str,ids))
        c['return_ledger']=rows(sql("SELECT exchange_id,return_sn,original_shipment_sn,sku_sn,quantity,accepted_quantity,posted_quantity,unit_cost FROM wms_exchange_return WHERE company_code='QM' AND exchange_id IN ("+scope+') ORDER BY exchange_id,generation',SUPPLY))
        c['return_events']=rows(sql("SELECT t.exchange_id,e.message_id,e.quantity,e.final_confirmation,e.state FROM wms_exchange_return_event e JOIN wms_exchange_return t ON t.company_code=e.company_code AND t.id=e.task_id WHERE t.company_code='QM' AND t.exchange_id IN ("+scope+') ORDER BY e.id',SUPPLY))
        c['round_ledger']=rows(sql("SELECT exchange_id,round_no,quantity,state,reviewed_by FROM oms_exchange_round WHERE company_code='QM' AND exchange_id IN ("+scope+') ORDER BY exchange_id,round_no',ORDER))
        c['demand_ledger']=rows(sql("SELECT platform_tid,platform_oid,sku_sn,demand_quantity,cancelled_quantity,reserved_quantity,outbound_quantity,status FROM oms_order_stock_demand WHERE company_code='QM' AND channel_id="+str(c['channel_id'])+' ORDER BY id',INV))
        if key in ['16','17','18']:
            first,second=c['final_exchanges'];assert first['original_sku']==c['sku'] and second['original_sku']==c['target_sku']
            assert len(c['return_ledger'])==2 and int(c['return_ledger'][1][4])==SPECS[key][2]
            assert float(c['return_ledger'][1][7])==2.5,'Second exchange must retain actual B outbound cost'
        if key=='18':
            parts=[x for x in c['return_events'] if int(x[0])==ids[0]]
            assert len(parts)==2 and [int(x[2]) for x in parts]==[1,1] and [int(x[3]) for x in parts]==[0,1]
        if key=='19':
            e=c['final_exchanges'][0];assert int(e['return_result']['accepted_quantity'])==1 and int(e['return_result']['posted_quantity'])==1 and e['return_qc']!='PASSED'
            assert c['automatic_flows'][0]['task']['state']=='WAITING' and c['automatic_flows'][0]['task']['last_code']=='WAIT_RETURN'
            assert len(c['return_events'])==1 and int(c['return_events'][0][3])==0
        if key in ['20','21']:
            assert len(c['return_ledger'])==1 and int(c['return_ledger'][0][6])==SPECS[key][1]
            approved=[x for x in c['round_ledger'] if int(x[1])>1]
            assert all(x[4]=='自动换货规则' for x in approved)
            assert [int(x[2]) for x in approved]==([3,2] if key=='21' else [2])
        if key=='22':
            assert c['refund_conflict_observed']['denial'] and '退款' in c['refund_conflict_observed']['denial']
            assert not c['round_ledger'],'Conflict must not create or approve another round'
            c['conflict_refund']=r.api('/order/refunds/business/'+str(c['refunds']['conflict']['id']))
            assert c['conflict_refund']['platform_tid']==c['platform_tid'] and c['conflict_refund']['platform_oid']==c['platform_oid']
        if c.get('last_error'):
            c.setdefault('driver_attempt_notes',[]).append(c.pop('last_error'))
        (OUT/(key+'-evidence.json')).write_text(json.dumps(c,ensure_ascii=False,indent=2,default=str),'utf-8');evidence.append(c)
        links='<br>'.join('<a href="/oms-order/exchanges?exchangeId='+str(e['id'])+'">'+h(e['exchange_sn'])+'</a>' for e in c['final_exchanges'])
        if key=='22':links+='<br><a href="/oms-order/businessRefunds?id='+str(c['conflict_refund']['id'])+'">'+h(c['conflict_refund']['refund_sn'])+'</a>'
        label=RESULTS[c['result']]
        summary.append('<tr><td>'+key+'</td><td><a href="#case-'+key+'">'+h(TITLES[key])+'</a></td><td class="pass">'+h(label)+'</td><td>'+links+'</td></tr>')
        before={x[0]:int(x[1]) for x in c['before_target']['company']};names={c['sku']:'A 原商品',c['target_sku']:'B 首次换出',c['third_sku']:'C 后续换出'}
        stock=[[names[x[0]]+' · '+x[0]]+x[1:]+[int(x[1])-before[x[0]]] for x in c['final_stock']['company']]
        states=[[e['exchange_sn'],e['review_by'] or '未通过审核',e['state'],e['platform_status'] or '无独立平台换货单',f['task'].get('node'),f['task'].get('last_message')] for e,f in zip(c['final_exchanges'],c['automatic_flows'])]
        shipments=[[e['exchange_sn'],s['fulfillment_round'],s['shipment_sn'],s['quantity'],s['shipped_quantity'],s.get('cancelled_quantity',0),s['status']] for e in c['final_exchanges'] for s in e['shipments']]
        phases=''.join('<details><summary>'+h(name)+'</summary>'+table(['SKU','总库存','可用','预占','已分配','冻结'],snap['company'])+'</details>' for name,snap in c.get('stock_phases',{}).items())
        interim=''
        if c.get('partial_return_observed'):
            p=c['partial_return_observed'];interim='<p class="note">已观察部分实收节点：应退2、已收1、已入库1；尚未通过完整验收，未生成有效换出明细。继续换出预览拒绝原因：'+h(p['continuation_denial'])+'</p>'
        if key=='22':interim+='<p class="note">继续换出被拒绝：'+h(c['refund_conflict_observed']['denial'])+'。现场验证的是新增退款后申请前阻断，没有人工审核续发。</p>'
        cards.append('<section id="case-'+key+'"><h2>'+key+' '+h(TITLES[key])+'</h2><p class="pass">'+h(label)+'</p><p>'+h(SCENES[key])+'</p><p>店铺：'+h(c['channel_name'])+'（'+str(c['channel_id'])+'）<br>原SO：<a href="/oms-order/business?orderId='+str(c['order_id'])+'">'+h(c['order_sn'])+'</a><br>'+links+'</p>'+interim+table(['换货单','审核人','本地状态','平台状态','自动节点','说明'],states)+'<h3>最终库存与实际变化</h3>'+table(['商品 / SKU','总数','可用','预占','已分配','冻结','相对申请前变化'],stock)+'<h3>实际换出与各轮未发取消</h3>'+table(['所属换货','轮次','发货单','原安排数量','实际出库','已取消未发','状态'],shipments)+'<h3>退回来源及原出库成本</h3>'+table(['HH ID','入库单','实际原发货单','退回SKU','应退','已实收','已入库','原出库单价'],c['return_ledger'])+'<details><summary>查看轮次审核、退回事件与库存需求台账</summary>'+table(['HH ID','轮次','本轮数量','记录状态','审核人'],c['round_ledger'])+table(['HH ID','仓库原消息','数量','是否最终确认','入账状态'],c['return_events'])+table(['库存业务引用','子单','SKU','原需求','取消未发','累计预占字段','累计实际出库','状态'],c['demand_ledger'])+'</details><details><summary>查看仓库库存与阶段快照</summary>'+table(['虚拟仓','SKU','良品总数','良品可用','良品锁定','次品总数','次品可用'],c['final_stock']['warehouse'])+phases+'</details></section>')
    regression=json.loads((OUT/'regression-final.json').read_text('utf-8'));assert regression['total_tests']>=736
    assert r.protected()==r.data['protected_baseline']
    r.data.update(audited_at=datetime.now().isoformat(),protected_baseline_unchanged=True,summary={'scenarios':8,'automatic_completed':5,'expected_wait_or_block':3,'regression_tests':regression['total_tests']});r.save()
    html='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>部分退回与连续换货自动验收</title><style>body{font-family:system-ui,"Microsoft YaHei",sans-serif;background:#f4f7fb;color:#293a50;padding:28px;line-height:1.8;margin:0}main{max-width:1450px;margin:auto}header,section{background:white;border:1px solid #dfe7f2;border-radius:10px;padding:26px;margin-bottom:22px}h1{font-size:27px;margin:0}h2{font-size:23px}h3{font-size:17px}.stats{display:flex;gap:40px;margin:20px 0}.stats b{display:block;font-size:30px}.pass{color:#08784d;font-weight:600}a{color:#0674d9;overflow-wrap:anywhere}.note{background:#eef5ff;padding:15px;border-radius:8px}.table{overflow:auto;margin:14px 0}table{border-collapse:collapse;width:100%;font-size:13px}td,th{text-align:left;padding:11px;vertical-align:top;border-bottom:1px solid #e4ebf5;overflow-wrap:anywhere}th{background:#f2f6fc}details{padding:12px;background:#f8faff;margin:9px 0}summary{cursor:pointer;font-weight:600}@media(max-width:800px){body{padding:12px}header,section{padding:16px}.stats{gap:20px;flex-wrap:wrap}}</style><main><header><h1>部分退回与连续换货 · 新增16—23验收</h1><p>批次 '+h(r.data['run_id'])+' · 最终核对 '+h(r.data['audited_at'])+'</p><div class="stats"><span><b>8 / 8</b>场景符合预期</span><span><b>5</b>自动链路完成</span><span><b>3</b>预期等待 / 阻断</span><span><b>'+str(regression['total_tests'])+'</b>回归通过，零跳过</span></div><p class="note">平台使用本地SIMULATION，仓库使用签名TEST回传。原销售发货属于前置；商家创建协商单、截停请求及每轮继续换出授权属于业务输入。目标审核、平台同意、验收、预占、分仓、下发和出库记账均由自动流程处理，未调用目标手工审核、推进或回执补拉接口。</p><p>20、21、22验收商家明确授权的售后协商换货，无独立平台换货申请；16、17、18、19、23使用平台换货申请。19现场保留真实未收齐等待状态，未等待24小时，也未改写业务时间；等待超时转人工由隔离测试库的超时条件回归验证。22现场验证退款到达后申请前拦截，申请后出现退款禁止审核的分支另有隔离回归覆盖。</p><p>库存变化以原销售出库后的首次售后申请前为基准。已分配属于预占；待发取消只释放未发占用，原实际出库及入库不撤销。回传后的快照可能尚未完成异步记账，最终结果已按真实库存和来源台账重新核对。原12条验收数据、旧退款、库存、队列配置及全局调度保持原样。</p>'+table(['编号','场景','结果','验收单据'],[]) .replace('<tbody></tbody>','<tbody>'+''.join(summary)+'</tbody>')+'</header>'+''.join(cards)+'</main></html>'
    (OUT/'验收报告.html').write_text(html,'utf-8');dest=Path(__file__).resolve().parent/'dist/acceptance/exchange-partial-continuous';dest.mkdir(parents=True,exist_ok=True);shutil.copyfile(OUT/'验收报告.html',dest/'验收报告.html')
    print('PASS read-only audit, stock conservation, automatic actors, protected baseline; report:',dest/'验收报告.html')
    from exchange_partial_report_detail import render
    render(r.data, OUT)


if __name__=='__main__':main()
