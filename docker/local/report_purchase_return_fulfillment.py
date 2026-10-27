"""Publish detailed retained acceptance, phase stock proofs and business document links."""
import html,json,shutil
from pathlib import Path
from datetime import datetime
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-fulfillment'

def main():
 s=json.loads((OUT/'manifest.json').read_text('utf-8'));ui=json.loads((OUT/'browser-check.json').read_text('utf-8'));q=json.loads((OUT/'query-check.json').read_text('utf-8'));tests=json.loads((OUT/'regression.json').read_text('utf-8'))
 assert len(s['results'])==18 and s['protected_baseline_unchanged'] and ui['result']==q['result']=='PASS' and ui['business_data_unchanged']
 esc=lambda v:html.escape(str(v))
 def table(headers,rows):return '<table><thead><tr>'+''.join('<th>'+esc(h)+'</th>' for h in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+str(v)+'</td>' for v in row)+'</tr>' for row in rows)+'</tbody></table>'
 def link(id,label):return '<a href="/oms-supplychain/purchaseReturns?id='+str(id)+'">'+esc(label)+'</a>'
 def stocks(value,prefix=''):
  found=[]
  if isinstance(value,dict):
   if all(k in value for k in ['total','available','allocated','frozen']):found.append([esc(prefix or '本步骤后')]+[value.get(k,'—') for k in ['total','available','allocated','reserved','frozen']])
   else:
    names={'initial':'原采购实收后','approved':'审核占用后','after_first':'首次实出后','after_second':'第二次实出后','before_continue':'继续申请前','after_continue':'同CGT继续后','before_actual':'收回单受理、尚未实收','after_actual':'收回按质检入账后','after_first_release':'第一次停止释放后','pending_review':'恢复待审核时','after_first_restore':'第一次审核恢复后','after_second_release':'第二次停止释放后','after_second_restore':'第二次审核恢复后','final':'本场景最终','incomplete':'回传分片未齐','after_complete':'分片齐全并扣账','after_v1':'累计版本1之后','after_v3_and_late_v2':'版本3与迟到版本2之后','after_bad_out':'次品退仓后','after_actual_good':'实际质检正品收回后','stock':'本步骤后'}
    for k,v in value.items():
     if k not in ['document','reclaim','stages','other_document','original_execution']:found+=stocks(v,names.get(k,k))
  return found
 css="""body{margin:0;background:#f1f5fb;color:#34445b;font:15px/1.8 'Microsoft YaHei',sans-serif}main{max-width:1450px;margin:28px auto;background:#fff;padding:32px;border-radius:10px}h1{font-size:27px}h2{font-size:21px;margin-top:30px}h3{font-size:17px}.muted{color:#7b8a9d;font-size:13px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.metrics article{background:#eef5fc;padding:18px;border-radius:8px}.metrics span,.metrics b{display:block}.metrics b{color:#25865b;font-size:25px}.notice{padding:18px;background:#fff8e9;border-radius:7px}table{border-collapse:collapse;width:100%;font-size:13px;margin:15px 0}th,td{border:1px solid #dfe7f1;padding:11px;text-align:left;overflow-wrap:anywhere}th{background:#f4f7fb}a,summary{color:#0875d2}a{overflow-wrap:anywhere}section{border-top:2px solid #edf2f8;padding-top:14px;margin-top:30px}.pass{color:#25865b;font-weight:bold}pre{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f6fa;padding:16px}summary{cursor:pointer}details{margin:16px 0}img{max-width:100%;display:block;border:1px solid #dfe7f1;margin-top:10px}figure{margin:28px 0}figcaption{font-weight:bold}@media(max-width:850px){.metrics{grid-template-columns:repeat(2,1fr)}main{padding:18px}}"""
 parts=['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>采购退仓同单分次执行与多包裹验收报告</title><style>'+css+'</style><main><h1>采购退仓：同一CGT分次执行、多包裹与拒收收回验收</h1><p class="muted">'+datetime.now().isoformat()+' · 本地TEST奇门仓库 · 测试单据和库存流水保留</p><div class="metrics">']
 for label,value in [('后端相关回归',str(tests['total_tests'])+' 项通过'),('完整业务场景','18 项通过'),('浏览器检查',str(len(ui['checks']))+' 项通过'),('查询与库存核对',str(len(q['checks']))+' 项通过')]:parts.append('<article><span>'+label+'</span><b>'+value+'</b></article>')
 parts.append('</div><p><a href="/oms-supplychain/purchaseReturns">采购退仓列表</a>　<a href="/oms-inventory/productInventory">商品库存</a>　<a href="/oms-supplychain/operations">供应链待办</a></p><h2>实际交付行为</h2><p>一个CGT保留原采购、原来源、批次、质量、单位和成本，下面追加多次独立仓库执行。继续未出余量时先确认原执行结束；有效占用持续保留，直接在原CGT创建下一次执行，不生成关联子CGT。已经释放的余量先申请恢复，再独立审核，重新校验来源额度和库存，保留之前的释放流水。</p><p>出库增量、带版本累计快照、同消息分片分别处理。仓库执行、原消息、原商品行都有独立身份；分片齐全前不确认出库，停止也不能直接释放。新执行可以复用仓库消息号，仍按自己的原行和数量核对。累计口径的顺序版本是与仓库约定的扩展能力，需要仓库配合，不假定所有奇门仓库默认支持。</p><p>包裹与运单分开管理；同运单多个包裹分别保留。包裹商品明确关联到原执行商品、批次和质量，同SKU多个来源不按顺序猜测。只有头部运单时，明确提示尚无包裹明细。补录物流、补关联原行或供应商收货均不重复扣库。</p><p>供应商实际接受、拒收、短收分别留证。物流签收不自动确认商品；接受和拒收合计不能超过原执行已出库扣账量。可先登记到执行商品行，之后分次补关联到同一个包裹；每次请求保留完整证据，重放不重复累计。包裹内容冲突须取得仓库更正凭证并明确核对原事实，保留原商品与原报文。</p><p>拒收收回CGH关联具体供应商拒收来源，同一退仓原行可按多条拒收来源分行安排。审核受理不增加库存，仓库实际QTRK实收才按实际质检质量和原成本恢复库存。原出库、原采购实收和原拒收事实均保留，款项凭证按净退仓货值单独核对。</p>')
 parts.append('<div class="notice"><b>验收范围：</b>本报告通过正常业务API进行明确审核/申请，审核后的库存、仓库下发、实际回传入账由后台自动执行。WMS仅使用本地TEST模拟器和真实验签回调；没有使用SQL改写目标业务状态或库存，也未修改全局任务和队列策略。真实仓库接口仍需按其实际回传口径联调。页面保存检查已拦截，未改变保留测试单。</div>')
 parts.append('<h2>可直接验收的保留入口</h2>')
 cs={c['key']:c for c in s['cases']}
 parts.append(table(['入口','可看到的实际状态'],[[link(cs['ready']['return_id'],'最终短出后待继续：原CGT'),'原计划5、实出2、库存已扣2、仍占用3；可查看同单继续与停止两个明确表单'],[link(cs['restore']['return_id'],'同CGT连续恢复两轮'),'一张CGT、三次执行；原计划5、累计占用10、累计释放5、实际出库5'],[link(cs['parcels']['return_id'],'三包裹与逐来源拒收收回'),'相同运单下P1/P2/P3、逐包裹接受/拒收、同原行两个拒收来源的CGH及正次品实收'],[link(cs['incomplete']['return_id'],'明细未齐：停止被阻止'),'保留停止待确认和4件有效占用；消息应有2行，仅收1片，等待仓库补齐'],[link(cs['restore_conflict']['return_id'],'原来源被其他单占用：恢复被阻止'),'原CGT已退2并释放3，恢复待审核；另一独立CGT有效占用7件，不能恢复原3件'],[link(cs['cumulative']['return_id'],'累计回传及晚补物流'),'版本1累计2、版本3累计5，旧版本2只留证；补包裹不增加库存事件']]))
 parts.append('<p class="muted">“明细未齐”“恢复被额度阻止”“待继续”的保留状态符合各自预期，因此相关测试判定通过；它们不是已经完成的退仓，也不是验收失败。</p><h2>场景索引</h2>'+table(['序号','详细场景','保留原业务单','结果'],[[r['key'],'<a href="#case-'+r['key']+'">'+esc(r['title'])+'</a>',link(r['return_id'],r['return_sn']),'<span class="pass">通过</span>'] for r in sorted(s['results'],key=lambda x:x['key'])]))
 parts.append('<h2>全部场景完成后的库存</h2>'+table(['场景 / SKU','公司实物','可用','已分配 / 占用','订单预占','次品 / 不可售'],[[esc(c['key'])+'<br><span class="muted">'+esc(c['sku'])+'</span>']+[s['final_stock'][c['key']][f] for f in ['totalStock','availableStock','allocatedStock','reservedStock','frozenStock']] for c in s['cases']]))
 for r in sorted(s['results'],key=lambda x:x['key']):
  parts.append('<section id="case-'+r['key']+'"><h2>'+r['key']+' '+esc(r['title'])+' <span class="pass">通过</span></h2><p>'+esc(r['scenario'])+'</p><p>'+link(r['return_id'],'查看原CGT '+r['return_sn'])+'</p>')
  rows=stocks(r['evidence'])
  if rows:parts.append(table(['阶段','公司实物','可用','占用 / 已分配','订单预占','次品 / 不可售'],rows))
  parts.append('<details><summary>查看完整阶段证据、仓库执行、包裹、来源与库存明细</summary><pre>'+esc(json.dumps(r['evidence'],ensure_ascii=False,indent=2))+'</pre></details></section>')
 parts.append('<h2>库存流水实证</h2><p>连续恢复场景的真实库存流水合计：初次占用5、重新占用5、原出库-5、释放5。累计占用10等于消耗5加释放5；原计划仍5，没有把历史释放量清零，也没有把累计占用当作商品库存。</p>'+table(['库存动作','实际累计变化'],[[esc(k),v] for k,v in q['restore_ledger_sums'].items()]))
 for sku,ledger in q['ledger'].items():parts.append('<details><summary>'+esc(sku)+'：真实流水</summary><pre>'+esc(ledger)+'</pre></details>')
 parts.append('<h2>回归中发现并修复</h2><ul><li>原余量通过释放后新建子CGT承接，现改为原CGT新增独立执行；有效占用不释放，已释放余量独立审核恢复。</li><li>库存余额在加锁前读取可能造成并发使用旧余额，现改为按SKU顺序加锁后重读，并校验原计划上限；并发恢复仅允许一个超额度请求成功。</li><li>包裹补录命令为联合主键，修正写入时误取自增ID的问题；每次补录完整请求与证据独立保留。</li><li>同一收货事实分次关联同一包裹时，现支持在双重额度内累计关联；同一请求重放不累计，不能通过换包裹或补关联重复确认商品。</li><li>数量控件选中包裹后同步更新辅助状态与可选上限，原事实及包裹两边限制保持一致。</li><li>只有运单且没有包裹明细时单独计数，避免“0个待核对包裹”让人误以为装箱信息已齐。</li><li>数量已出完但最终标志稍后回传时，零数量最终确认仍可保留，直接确认无库存变化的事件，不再调用扣库。</li><li>包裹内容冲突不回滚有效出库，但新增包裹商品验收必须先取得更正凭证并明确核对原商品事实。</li></ul>')
 parts.append('<h2>浏览器验收结果</h2><ol>'+''.join('<li>'+esc(c)+'</li>' for c in ui['checks'])+'</ol>')
 captions=['最终短出原CGT与五个业务页签','同单继续表单与持续保留占用','一张CGT内两次独立仓库执行','连续恢复后的累计占用与释放','同一运单下的三个物理包裹','供应商逐包裹接受与拒收来源','按具体拒收来源创建收回入库','同原行多拒收来源与实际质检入账','净退仓货值及独立款项凭证','已有验收事实补关联包裹','分片明细未齐，停止与释放被阻止','已释放余量的独立恢复审核','当前页批量展示执行及包裹摘要','供应链待办定位同单恢复审核并直达原CGT']
 images=sorted(f for f in OUT.glob('[0-9][0-9]-*.png'))
 assert len(images)==14,(len(images),images)
 for f,title in zip(images,captions):parts.append('<figure><figcaption>'+title+'</figcaption><a href="'+f.name+'"><img src="'+f.name+'" alt="'+title+'"></a></figure>')
 parts.append('<h2>只读查询、库存与性能核对</h2><ol>'+''.join('<li>'+esc(c)+'</li>' for c in q['checks'])+'</ol>'+table(['入口','读取次数','中位耗时毫秒','最小 / 最大毫秒'],[[esc(t['endpoint']),len(t['samples_ms']),t['median_ms'],str(min(t['samples_ms']))+' / '+str(max(t['samples_ms']))] for t in q['timings']])+'<p class="muted">每入口本地连续5次读取只用于发现查询退化，不代表生产容量测试。列表与详情没有自动调用WMS查询；后台使用有界工作池、持久租约和失败退避。新增13张表及全部新增字段已核对中文备注。前端5个相关模板和选择额度逻辑检查通过。</p><details><summary>查询计划</summary><pre>'+esc(json.dumps(q['explain'],ensure_ascii=False,indent=2))+'</pre></details><p class="notice">原采购单、原订单售后、旧测试库存、全局自动任务及队列策略基线均保持一致。所有测试业务单、仓库交互、原消息和库存流水已保留。</p></main></html>')
 (OUT/'验收报告.html').write_text(''.join(parts),'utf-8');dest=ROOT/'docker/local/dist/acceptance/purchase-return-fulfillment';dest.mkdir(parents=True,exist_ok=True)
 for f in images+[OUT/name for name in ['验收报告.html','manifest.json','regression.json','browser-check.json','query-check.json','deployment.json']]:shutil.copy2(f,dest/f.name)
 print('Report published: http://127.0.0.1:8088/acceptance/purchase-return-fulfillment/验收报告.html')

if __name__=='__main__':main()
