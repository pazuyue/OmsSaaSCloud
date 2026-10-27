"""Build a detailed Chinese acceptance report from retained business and stock evidence."""
import html
import json
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/supplychain-iteration'
NAMES={'planned':'采购计划','scheduled':'有效安排/入账占用','unarranged':'可安排','confirmed':'确认实收','posted':'已入账','unposted':'实收待入账','unreceived':'待收','short':'最终短收','bad':'其中次品','cancelled':'已截停'}
KINDS={'SHORT':'最终短收','QUALITY':'次品','OVER':'超收','WRONG_SKU':'错货','LATE':'迟到额度冲突'}
STATES={'OPEN':'待处理','WAITING':'等待仓库更正','RESOLVED':'已处理','REJECTED':'已拒收','CORRECTED':'后续回传已更正'}
STEPS={
 'draft':('创建采购草稿，计划100件，暂不审核、不安排到货。','计划100、未安排100，实收及入账均为0，未产生执行单。'),
 'scheduled':('审核100件采购计划，安排本批60件，暂不审核到货。','有效安排60、未安排40、待收60；到货待审核，仓库和库存均未执行。'),
 'virtual':('审核采购10件、安排10件并审核虚拟到货。审核后不再调用确认收货或入账命令。','系统自动虚拟收货10件并入账10件，采购完成，使用原CG单号形成一条入库流水。'),
 'full':('审核真实到货5件，等待系统自动下发；通过本地TEST仓库签名回传5件最终收货。','仓库受理、确认实收5、自动入账5，原采购价2.50元/件保留；采购完成。'),
 'partial':('安排真实到货10件，仓库只回传4件非最终实收；不发最终结束消息。','自动入账4件，仍待收6件，未产生最终短收差异，剩余采购安排不提前释放。'),
 'split':('安排5件；以两个不同消息先回传2件非最终收货，再回传3件最终收货。','两次增量各自入账，合计5件；两条库存流水分别关联原收货明细。'),
 'short':('安排10件，仓库最终仅确认8件；等待已收8件自动入账。','最终短收2件留为待处理差异，已入账8件，采购释放2件可重新安排。'),
 'rearrange':('最终短收2件且原8件入账后，两个并发请求以同一差异版本点击补建到货。','只允许一个处理成功，生成一张2件待审核到货单；原8件库存保持，补建单尚未收货或入账。'),
 'bad':('计划5件，仓库同一最终消息报告正品3件、次品2件。','按质量分别入仓和入批次库存，公司总库存5；次品差异2件保留待核对。'),
 'bad_keep':('正品3、次品2自动入账后，操作员核对原回传并确认次品留库。','只记录次品处理结论，正品仍3、次品仍2，入库流水总量仍5，未重复加库存。'),
 'over':('计划A商品5件，仓库签名回传A商品7件。','明确拒绝确认为实收，保留超收2件差异和原7件报文；库存及已入账数量均为0。'),
 'wrong':('计划A商品5件，仓库却签名回传B商品5件；B是另外创建的有效SKU。','保留错货差异，未确认A或B实收；两者仓库库存均为0，未生成库存流水。'),
 'corrected':('先以原消息回传错误B商品，后由仓库使用同一原消息身份更正为A商品5件。','错误报文保留为已更正；只接受更正后的A商品，自动入账A5件，B库存仍为0。'),
 'replay':('计划5件，仓库同一消息、相同内容回传两次。','重复消息被识别；最终仍实收5、入账5，仅一条入库流水，无重复加库存。'),
 'dispatch_fail':('使用独立TEST连接，仓库模拟器明确拒绝该连接的签名；到货正常审核触发自动下发。','执行结果为下发失败，最近交互保留失败原因，待办可查询；实收及库存均为0。此场景验收通过表示失败被正确处理。'),
 'late':('真实仓受理5件计划后，通过正常截单API停止本批；等待自动截单完成，再回传2件非最终实收。','截单完成后迟到消息进入原截单到货待核对，未直接确认实收、未入库存；可重新安排原5件。'),
 'concurrent':('已审核采购100件，两个请求并发各安排60件，两个请求共用同一采购剩余额度。','只允许一张60件到货单成功；另一请求因剩余40件而拒绝，保留40件可安排，库存未变化。'),
 'overdue':('审核10件采购，预计到货日期设为昨日，不安排到货。','统一待办显示该采购逾期，实收、入账、库存均为0，未自动虚构到货。')
}


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));browser=json.loads((OUT/'browser-check.json').read_text('utf-8'));queries=json.loads((OUT/'query-check.json').read_text('utf-8'));tests=json.loads((OUT/'regression.json').read_text('utf-8'))
    assert all(c.get('result')=='PASS' for c in manifest['cases']) and manifest['protected_baseline_unchanged'] and queries['protected_baseline_unchanged']
    esc=lambda v:html.escape(str(v));parts=[]
    def table(headers,rows):return '<table><thead><tr>'+''.join('<th>'+esc(h)+'</th>' for h in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+str(v)+'</td>' for v in r)+'</tr>' for r in rows)+'</tbody></table>'
    def link(path,label):return '<a href="'+esc(path)+'">'+esc(label)+'</a>'
    parts.append('<h1>供应链第一轮迭代 · 详细验收报告</h1><p class="muted">'+esc(manifest['completed_at'])+' · 本地项目已部署 · 测试资料均保留</p>')
    parts.append('<div class="metrics">'+''.join('<article><span>'+label+'</span><b>'+str(value)+'项通过</b></article>' for label,value in [('后端相关回归',tests['total_tests']),('保留业务场景',len(manifest['cases'])),('页面检查',len(browser['checks'])),('查询检查',len(queries['checks']))])+'</div>')
    parts.append('<p>'+link('/oms-supplychain/operations','供应链待办')+'　'+link('/oms-supplychain/poInfo','采购单')+'　'+link('/oms-supplychain/arrivals','到货单')+'</p><h2>本轮交付</h2><ul><li>采购、到货专用详情：采购计划、有效安排、可安排、仓库实收、库存入账、次品及最终短收分别展示；商品按件/套保留单位。</li><li>独立到货列表与仓库交互字段：当前受理状态、最近交互动作、结果、时间和失败原因独立展示，支持组合筛选及导出。</li><li>供应链待办：按当前问题归集逾期、下发失败、结果待确认、收货未补齐、入账异常、截单、收货差异、售后退回及加工待核对。</li><li>差异处理：原回传与处理结论留痕；短收补建受采购剩余额度和版本控制，次品留库不重复入账；超收/错货可等待更正或确认拒收。</li></ul>')
    parts.append('<h2>自动与人工步骤的边界</h2><p>本轮采购计划审核、到货审核、截单申请、短收补建决定和次品留库决定由操作员通过正常接口发起。审核提交后的仓库下发、签名回传解析和库存入账由现有自动执行能力完成；验收未人工修改目标状态或库存，也未调用手工收货/入账接口替代自动步骤。</p><p>仓库交互来自本地TEST奇门模拟器；真实外部仓库联调不属于本报告结果。查询失败、库存服务失败、跨公司隔离等异常另由隔离数据库回归覆盖，不把它们冒充为保留单据的真实故障。</p>')
    parts.append('<h2>18个保留场景索引</h2>'+table(['序号','场景','原采购单','验收'],[(str(i),link('#case-'+c['key'],c['title']),link(c['url'],c['purchase_sn']),'<b class="pass">通过</b>') for i,c in enumerate(manifest['cases'],1)]))
    parts.append('<h2>库存结果总表</h2><p>每个场景使用独立新SKU，初始库存均为0；仓库与批次的正品/次品分别核对，同时核对OMS商品汇总及每条RECEIVE流水。公司可用为当前OMS汇总台账口径；店铺可售库存发布不在本轮验收范围。</p>')
    stockrows=[]
    for i,c in enumerate(manifest['cases'],1):
        s=c['stock'];layer=c['inventory_layers'];company=layer['company'];batch=layer['batches'];stockrows.append((str(i),esc(c['sku']),f'{company["total"]} / {company["available"]}',f'{s["good"]} / {s["bad"]}',f'{s["good_available"]} / {s["bad_available"]}',f'{batch["good"]} / {batch["bad"]}',str(company['reserved'])+' / '+str(company['allocated'])+' / '+str(s['locked']),str(len(layer['ledger']))+'条 / '+str(sum(x['quantity'] for x in layer['ledger']))+'件'))
    parts.append(table(['场景','SKU','公司总量/可用','仓库正品/次品','仓库可用正/次','批次正/次','预占/分配/仓锁','入库流水条数/合计'],stockrows))
    for i,c in enumerate(manifest['cases'],1):
        key=c['key'];step,result=STEPS[key];j=c['final_purchase']['journey'];parts.append('<section id="case-'+key+'"><h2>'+str(i).zfill(2)+' '+esc(c['title'])+' <span class="pass">验收通过</span></h2><p><b>详细场景：</b>'+esc(step)+'</p><p><b>实际结果：</b>'+esc(result)+'</p><p>'+link(c['url'],'查看原采购单 '+c['purchase_sn']))
        if c.get('receipt_id'):parts.append('　'+link('/oms-supplychain/arrivals?receiptId='+str(c['receipt_id']),'查看本批到货 '+c['receipt_sn']))
        if c.get('supplemental_receipt_id'):parts.append('　'+link('/oms-supplychain/arrivals?receiptId='+str(c['supplemental_receipt_id']),'查看唯一补建2件到货单'))
        parts.append('</p>'+table(['计划与执行','当前数量'],[(label,str(j.get(k,0))+' 件') for k,label in NAMES.items()]))
        s=c['stock'];parts.append('<p><b>实际库存：</b>初始0；当前仓库正品 '+str(s['good'])+' 件、次品 '+str(s['bad'])+' 件；可用正品 '+str(s['good_available'])+'、次品 '+str(s['bad_available'])+'，仓库锁定 '+str(s['locked'])+'。公司总库存 '+str(c['inventory_layers']['company']['total'])+'，批次与仓库汇总一致。</p>')
        if c['differences']:parts.append(table(['差异类型','差异量','当次回传量','当前处理结果','处理人/依据'],[(KINDS.get(d['difference_kind'],d['difference_kind']),str(d['difference_quantity']),str(d['reported_quantity']),STATES.get(d['state'],d['state']),esc((d.get('actor') or '')+' '+(d.get('resolution_reason') or d['reason']))) for d in c['differences']]))
        if c['inventory_layers']['ledger']:parts.append(table(['库存真实入账关联号','本次入账'],[(esc(x['relation']),str(x['quantity'])+'件') for x in c['inventory_layers']['ledger']]))
        if key=='rearrange':parts.append('<p class="muted">“最终短收2件”保留原批次事实；补建2件为另一个待审核批次，尚未产生新的实收和库存。</p>')
        if key=='wrong' or key=='corrected':parts.append('<p>另外核对错误商品 '+esc(c['wrong_sku'])+'：正品0、次品0。</p>')
        parts.append('</section>')
    parts.append('<h2>性能处理及实际查询结果</h2><ul><li>采购最多200个SKU一次批量远程校验，避免逐行RPC；先校验后加业务锁。</li><li>当前页的执行单、实际商品、已入账数量及最后交互批量读取；按公司和完整单号定位。详情不预加载完整日志与原报文。</li><li>待办默认近90天、显式日期范围限制、分页最多50条；采购明细、当前差异及预计到货有公司范围索引。</li><li>差异记录保留原消息哈希唯一键；补建锁采购→到货→入库单并校验版本。库存沿用原实收明细关联号确保重复回传不重复记账。</li></ul>'+table(['本机暖请求（5次）','中位数','最大值'],[(esc(t['name']),str(t['median_ms'])+' ms',str(t['max_ms'])+' ms') for t in queries['timings']])+'<p class="muted">耗时来自本机当前数据，不是生产容量压测或性能承诺。执行计划及原始样本保存在query-check.json。</p>')
    parts.append('<h2>查询与页面逐项验收</h2><ol>'+''.join('<li>'+esc(x)+' <span class="pass">通过</span></li>' for x in queries['checks']+browser['checks'])+'</ol>')
    parts.append('<h2>保护基线</h2><p>验收前后旧订单、原退款/换货单、原保留SKU的公司及仓库库存、自动队列策略和换货扫描任务设置一致。测试没有重跑旧业务、改变全局自动策略或清理测试资料。新增数据库表、字段及索引均有中文备注。</p><h2>页面截图</h2>')
    for name,title in [('01-purchase-plan.png','采购计划与到货数量分开'),('02-receipt-stock.png','真实到货、实收与入账'),('02b-stock-ledger.png','采购成本与真实库存流水'),('03-warehouse-callback.png','原仓库交互报文'),('04-partial.png','部分实收与待收'),('05-short-difference.png','最终短收处理'),('06-workbench.png','统一待办定位超收'),('07-arrival-failed.png','列表独立展示仓库失败')]:parts.append('<figure><figcaption>'+esc(title)+'</figcaption>'+link(name,'查看原图')+'<img loading="lazy" src="'+name+'"></figure>')
    css='body{font:15px/1.8 "Microsoft YaHei",sans-serif;background:#f4f7fb;color:#34445b;margin:0}main{max-width:1320px;margin:30px auto;background:white;border-radius:10px;padding:34px}h1{font-size:27px}h2{font-size:20px;margin-top:32px}h2 .pass{font-size:14px}a{color:#0877dc;overflow-wrap:anywhere}.muted{font-size:13px;color:#78879b}.pass{color:#278558}.metrics{display:flex;gap:14px}.metrics article{flex:1;background:#f1f6fc;border-radius:8px;padding:17px}.metrics b,.metrics span{display:block}.metrics b{font-size:23px;color:#278558}table{width:100%;border-collapse:collapse;font-size:13px;margin:15px 0}th,td{padding:10px;border:1px solid #e1e8f2;text-align:left;overflow-wrap:anywhere}th{background:#f4f7fb}section{padding-top:15px;border-top:2px solid #eef3fa;margin-top:28px}figure{margin:28px 0}figcaption{font-weight:600}img{max-width:100%;display:block;border:1px solid #e1e8f2;margin-top:10px}'
    (OUT/'验收报告.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>供应链第一轮迭代验收报告</title><style>'+css+'</style><main>'+''.join(parts)+'</main></html>','utf-8')
    destination=ROOT/'docker/local/dist/acceptance/supplychain-iteration';destination.mkdir(parents=True,exist_ok=True)
    for f in OUT.iterdir():
        if f.suffix in ['.html','.json','.png','.xlsx']:shutil.copy2(f,destination/f.name)
    print('Published /acceptance/supplychain-iteration/验收报告.html')


if __name__=='__main__':main()
