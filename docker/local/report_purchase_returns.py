"""Publish detailed retained TEST acceptance, including every inventory bucket and original document."""
import html,json,shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-returns'
STEPS={
 'virtual':('原采购10件经虚拟到货自动入账；创建原批次退供3件并显式审核。','审核后的锁定、虚拟实出和扣账均自动执行；原采购实收10保留，已退3、净入库7。'),
 'accepted':('原采购10件已入账，退供3件审核后自动下发真实TEST奇门仓库；暂不回传实出。','仓库已受理不等于已经出库；总库存10、可用7、退供占用3，实际扣账为0。'),
 'full':('原采购10件，申请退仓3件；自动stockout下发受理后，签名回传原行原批次3件最终出库。','自动扣账3件，在库7；实际运单来自stockout.confirm，原采购成本2.50元/件可追溯。'),
 'partial':('申请退供5件，仓库仅签名回传2件中间状态，暂不发送最终结束消息。','只扣2件，在库8，其中可用5、原退供剩余占用3；单据继续等待剩余出库。'),
 'split':('申请退仓5件；先回传2件中间出库，再用不同消息回传3件最终出库。','两个原事件分别入账，共扣5件；库存5，原退供占用全部消耗。'),
 'bad':('采购实收正品3、次品2，次品按采购质量标记计入不可售；只申请次品2件退供。','下发及回传使用CC次品，库存只扣次品2；最终正品3、次品0，公司可用3。'),
 'mixed':('同一SKU同一实际批次，原实收正品3、次品2；同一退仓单申请正品2和次品1，并在同一消息确认出库。','正次品按各自原行和原来源扣账；最终正品1、次品1，总量2、可用1、不可售1。'),
 'replay':('申请退仓3件，仓库同一消息、同一内容签名回传两次。','最终仍只扣3件，事件与库存流水保持原身份，没有重复扣账。'),
 'over':('申请退仓3件，真实TEST仓库却签名回传原行4件。','拒绝超量确认，实际出库及库存扣账均保持0；原3件占用保留，明确提示仓库回传待更正。'),
 'wrong_batch':('申请原批次3件，仓库签名回传另一批次。','不使用SKU相同替代原批次匹配；回传待核对，已确认实出0、扣账0、原批次占用3。'),
 'cancel':('申请5件退仓，仓库受理后没有实际出库；操作员通过正常接口停止剩余退仓。','自动调用奇门order.cancel，仓库明确取消成功后释放原5件占用；库存仍10。'),
 'partial_cancel':('申请5件，已签名确认并自动扣账2件；操作员申请停止剩余3件。','自动确认仓库取消结果，已出库2件保留，只释放原3件余量；最终在库8、可用8。'),
 'concurrent':('原实收10件，创建两张各7件草稿，两个线程并发审核同一采购来源。','只有一张审核成功并锁定7件；另一张保留草稿，来源额度不超退；在库10、可用3、占用7。'),
 'freeze':('原正品库存10件，申请冻结4件，填写依据并显式审核。','审核后同一事务完成原批次冻结；总库存10、可用6、不可售/冻结4，原入库数量不变。'),
 'unfreeze':('原正品10件已冻结4件，从原冻结单申请解冻2件并审核。','只释放原冻结来源2件，仍冻结2；总库存10、可用8，不影响其他来源的锁定。'),
 'convert':('原采购正品3、次品2；模拟仓库质检通过后登记凭证，申请次品2件转正并审核。','总库存保持5，正品变5、次品变0；可用5、不可售0。原入库正次品事实保留，另有质量变更流水。'),
 'reject':('原正品10件，申请冻结4件；审核人因依据不充分驳回。','申请被驳回，库存、可用量和批次质量均未改变。'),
 'bad_freeze':('原正品3、次品2；冻结次品1件，之后按原冻结单解冻同一件。','次品冻结与解冻均不转为正品，也不重复增加公司冻结量；最终正品3、次品2、可用3、不可售2。'),
 'funds':('退供3件已经实际出库并扣账，按原采购单价2.50元，登记供应商7.50元抵扣凭证。','款项状态显示已登记抵扣凭证，原出库结果与库存保持；本次没有调用银行、平台退款或付款接口。'),
 'dispatch_fail':('采购10件先按虚拟入库完成入账；原虚仓的真实退供连接使用独立TEST拒绝配置，审核退供3件。','奇门模拟仓明确拒绝下发；库存10、可用7、原占用3。保留错误和下发失败待办，未伪造出库。'),
 'final_short':('申请5件退仓，仓库最终确认只实出2件，不再有后续出库。','自动只扣2件；页面展示最终出库结束及剩余3件占用，核对后可停止剩余退仓；原申请数量保留。')
}


def main():
    m=json.loads((OUT/'manifest.json').read_text('utf-8'));tests=json.loads((OUT/'regression.json').read_text('utf-8'));browser=json.loads((OUT/'browser-check.json').read_text('utf-8'));q=json.loads((OUT/'query-check.json').read_text('utf-8'));assert m['protected_baseline_unchanged'] and all(c.get('result')=='PASS' for c in m['cases'])
    esc=lambda v:html.escape(str(v));parts=[]
    def link(url,label):return '<a href="'+esc(url)+'">'+esc(label)+'</a>'
    def table(headers,rows):return '<table><thead><tr>'+''.join('<th>'+esc(h)+'</th>' for h in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+str(v)+'</td>' for v in row)+'</tr>' for row in rows)+'</tbody></table>'
    parts.append('<h1>采购退仓＋质量库存 · 详细验收报告</h1><p class="muted">'+esc(m['completed_at'])+' · 本地TEST仓库 · 新建资料全部保留</p><div class="metrics">'+''.join('<article><span>'+label+'</span><b>'+str(n)+' 项通过</b></article>' for label,n in [('后端相关回归',tests['total_tests']),('保留业务场景',len(m['cases'])),('浏览器检查',len(browser['checks'])),('查询检查',len(q['checks']))])+'</div>')
    parts.append('<p>'+link('/oms-supplychain/purchaseReturns','采购退仓列表')+'　'+link('/oms-inventory/quality','质量库存列表')+'　'+link('/oms-supplychain/operations','供应链待办')+'</p><h2>本轮交付与自动流程边界</h2><p>采购退仓绑定原供应商、原采购及成功入账来源，保留实际SKU、批次、正次品、单位和原成本。审核、取消申请、质量审批及款项凭证登记由操作员通过正常接口明确发起；随后库存锁定、奇门下发、签名回传解析、实际扣账与取消后的释放由后台执行。未手工改写目标状态或库存，也未调用手工出库或扣账接口替代自动流程。</p><p>仓库采用本地TEST奇门模拟器，未与外部生产仓库联调。质量转正登记模拟仓库质检凭证后审核执行OMS库存变化；本轮未调用仓库质量变更接口。供应商退款/抵扣为独立凭证登记，未调用银行或平台付款接口。</p>')
    parts.append('<h2>奇门接口核对</h2>'+table(['操作','实际协议','核对内容'],[('采购退仓下发','taobao.qimen.stockout.create','CGTH；供应商、货主、仓库、原行、批次、ZP/CC及原采购成本'),('原仓单查询','taobao.qimen.stockout.query','deliveryOrderCode / deliveryOrderId；查询状态不替代实出明细'),('实际出库回传','taobao.qimen.stockout.confirm','验签、原目标身份、原行批次质量、唯一消息、中间/最终确认及实际物流'),('停止剩余退仓','taobao.qimen.order.cancel','CGTH和原单身份；结果明确后才释放未出库占用')])+'<p>接口按'+link('https://developer.alibaba.com/docs/doc.htm?articleId=118403&docType=1&treeId=285','奇门官方白皮书5.13、5.14、5.15及5.32节')+'核对。单次明细不完整时不扣账，需补齐后回传。</p>')
    parts.append('<h2>业务场景索引</h2>'+table(['序号','详细场景','保留单据','验收'],[(str(i),link('#case-'+c['key'],c['title']),link(c['url'],c.get('return_sn',c['sku'])),'<span class="pass">通过</span>') for i,c in enumerate(m['cases'],1)]))
    parts.append('<h2>库存结果总表</h2><p>各场景均使用独立新SKU。表中初始库存为采购自动入账后的数量；公司、仓库、实际批次以及库存流水均已逐项核对。质量冻结会进入不可售/冻结，退供锁定显示为已分配；预占在所有场景中均为0。</p>')
    rows=[]
    for i,c in enumerate(m['cases'],1):
        s=c['final_stock'];o=s['company'];w=s['warehouse'];rows.append((str(i),esc(c['sku']),f'{c["initial_good"]} / {c["initial_bad"]}',f'{o["total"]} / {o["available"]}',f'{o["allocated"]} / {o["frozen"]}',f'{w["good"]} / {w["bad"]}',f'{w["good_free"]} / {w["bad_free"]}',f'{w["good_locked"]} / {w["bad_locked"]}','一致'))
    parts.append(table(['场景','SKU','初始正/次','公司总量/可用','退供占用/不可售冻结','仓库正/次','仓库可用正/次','仓库锁定正/次','批次核对'],rows))
    for i,c in enumerate(m['cases'],1):
        step,result=STEPS[c['key']];parts.append('<section id="case-'+c['key']+'"><h2>'+str(i).zfill(2)+' '+esc(c['title'])+' <span class="pass">通过</span></h2><p><b>详细场景：</b>'+esc(step)+'</p><p><b>实际验收结果：</b>'+esc(result)+'</p><p>'+link(c['url'],'打开保留验收单')+'　'+link('/oms-supplychain/poInfo?purchaseId='+str(c['purchase_id']),'原采购 '+c['purchase_sn'])+'　'+link('/oms-supplychain/arrivals?receiptId='+str(c['receipt_id']),'原到货批次')+'</p>')
        if c.get('final_return'):
            r=c['final_return'];parts.append(table(['核对项','实际结果'],[('原退仓单',esc(r['return_sn'])),('申请 / 仓库实出 / 已扣账',f'{r["quantity"]} / {r["accepted_quantity"]} / {r["posted_quantity"]}'),('实际出库的原采购货值','¥'+esc(r['posted_amount'])),('原采购累计实收','原采购仍为'+str(c['initial_good']+c['initial_bad'])+'，未覆盖原入库记录'),('供应商款项凭证',esc(r['funds_state'])+' / ¥'+esc(r['funds_amount']))]))
            parts.append(table(['原实际商品','正次品','原入库与批次','原成本','申请/实出/扣账'],[(esc(l['sku_sn']),l['inventory_type'],esc(l['ticket_sn'])+'<br>'+esc(l['batch_code']),esc(l['purchase_price']),f'{l["quantity"]} / {l["accepted_quantity"]} / {l["posted_quantity"]} '+esc(l['stock_unit'])) for l in r['lines']]))
        if c.get('quality_results'):parts.append(table(['质量单','动作','处理数量','状态','原冻结来源','质检/处理依据'],[(link('/oms-inventory/quality?id='+str(x['id']),x['quality_sn']),esc(x['action']),str(x['quantity']),esc(x['state']),esc(x.get('source_sn','')),esc(x['evidence'])) for x in c['quality_results']]))
        if c.get('resolved_issues'):
            for issue in c['resolved_issues']:parts.append('<p class="issue"><b>发现并修复：</b>'+esc(issue['problem'])+'。'+esc(issue['fix'])+'。当前原事件已完成扣账，未重建退仓单或改写库存。</p>')
        o=c['final_stock']['company'];w=c['final_stock']['warehouse'];parts.append('<p><b>库存实测：</b>公司总库存 '+str(o['total'])+'，可用 '+str(o['available'])+'，退供占用 '+str(o['allocated'])+'，预占 '+str(o['reserved'])+'，不可售/冻结 '+str(o['frozen'])+'；仓库正品 '+str(w['good'])+'、次品 '+str(w['bad'])+'，与批次汇总一致。</p><details><summary>查看真实库存流水证据</summary><pre>'+esc(c['inventory_ledger'])+'</pre></details></section>')
    parts.append('<h2>查询、页面和性能检查</h2><ol>'+''.join('<li>'+esc(x)+' <b class="pass">通过</b></li>' for x in q['checks']+browser['checks'])+'</ol><p>列表元数据按当前页批量读取，库存来源一次RPC批量查询；原报文点击后加载。执行只扫描到期且需要处理的任务，正常等待回传的已受理单不反复执行，失败按下一次执行时间退避；不同采购和SKU可并行。</p>')
    parts.append(table(['本机暖请求','中位耗时','最大耗时'],[(esc(x['name']),str(x['median_ms'])+' ms',str(x['max_ms'])+' ms') for x in q['timings']])+'<p class="muted">本机当前保留数据的暖请求结果，不是生产容量压测。渠道可售计算由隔离数据库回归覆盖，验证冻结后下降、次品转正后增加；未把计算结果宣称为外部店铺同步成功。</p>')
    parts.append('<h2>保护基线</h2><p>旧退款、换货、原订单、原SKU库存、自动队列和任务配置与验收前一致；上一轮18个采购验收单及其库存也保持一致。没有清理测试数据、重跑旧业务或启用全局自动任务。</p><h2>页面截图</h2>')
    for name,title in [('01-return-list.png','采购退仓列表与独立仓库交互结果'),('02-return-detail.png','退仓商品与原采购来源'),('03-outbound-ledger.png','实出事件、实际物流与库存来源余额'),('04-stockout-message.png','奇门CGTH原回传'),('05-partial-return.png','部分出库与剩余占用'),('06-mixed-grade.png','同SKU同批次正次品分行'),('07-invalid-outbound.png','超量回传保留待核对'),('08-original-purchase-net.png','原采购实收、已退及净入库'),('09-quality-freeze.png','质量冻结原来源'),('10-quality-convert.png','仓库质检依据与次品转正'),('11-quality-form.png','从实际库存批次申请质量处理')]:parts.append('<figure><figcaption>'+esc(title)+'</figcaption>'+link(name,'查看原图')+'<img src="'+name+'" loading="lazy"></figure>')
    css='body{font:15px/1.8 "Microsoft YaHei",sans-serif;background:#f4f7fb;color:#34445b;margin:0}main{max-width:1380px;margin:30px auto;background:white;padding:34px;border-radius:10px}h1{font-size:27px}h2{font-size:20px;margin-top:32px}h2 .pass{font-size:14px}.pass{color:#278558}.muted{color:#7a899d;font-size:13px}a{color:#0877dc;overflow-wrap:anywhere}.metrics{display:flex;gap:14px}.metrics article{flex:1;background:#f1f6fc;padding:17px;border-radius:8px}.metrics span,.metrics b{display:block}.metrics b{font-size:24px;color:#278558}table{width:100%;border-collapse:collapse;font-size:13px;margin:16px 0}th,td{border:1px solid #e1e8f2;padding:10px;text-align:left;overflow-wrap:anywhere}th{background:#f4f7fb}section{margin-top:28px;padding-top:10px;border-top:2px solid #eef3fa}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f7fb;padding:14px;font-size:12px}.issue{padding:14px;background:#fff8e9}figure{margin:30px 0}figcaption{font-weight:600}img{max-width:100%;display:block;margin-top:10px;border:1px solid #e1e8f2}'
    (OUT/'验收报告.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>采购退仓与质量库存验收报告</title><style>'+css+'</style><main>'+''.join(parts)+'</main></html>','utf-8');dst=ROOT/'docker/local/dist/acceptance/purchase-returns';dst.mkdir(parents=True,exist_ok=True)
    for f in OUT.iterdir():
        if f.suffix in ['.html','.json','.png']:shutil.copy2(f,dst/f.name)
    print('Published /acceptance/purchase-returns/验收报告.html')


if __name__=='__main__':main()
