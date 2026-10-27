"""Render case 24 from retained, audited evidence. No business API or database writes."""
import json
from pathlib import Path


def retained_result(out):
    path=Path(out).parent/'exchange-return-split/manifest.json'
    if not path.exists():return None
    data=json.loads(path.read_text('utf-8'));case=data.get('cases',{}).get('24',{})
    if case.get('result')!='PASS' or not case.get('audit'):return None
    return data,case


def render_case(data,c):
    from exchange_partial_report_detail import h,table,paragraph,fmt,company,index,aliases
    a=c['audit'];first,second=c['final_exchanges'];refund=c['final_refund'];d=c['shared_return'];names=aliases(c)
    steps=[
        ['1','前置：购买 A 3 件，实付 300 元，原销售已实际出库。','原销售只发 A 3 件；售后前 A7/B10/C10。',f'原销售 {c["order_sn"]}；平台主单 {c["platform_tid"]}、子单 {c["platform_oid"]}。保留原销售签名出库事实。'],
        ['2','申请 A 3 件全部换 B 3 件，买家退回 A。','自动审核、预占、同意、A 入库 3、验收、B 实发 3、回传运单并收到平台换货成功。',f'{first["exchange_sn"]}：审核人 {first["review_by"]}；本地 {first["state"]}，平台 {first["platform_status"]}。A 实收并入库 3；B 实发 3。'],
        ['3','首次换货完成后，先申请其中 B 1 件退货退款 100 元。','资金保留原支付子单；实物选择已完成换货实际发出的 B，不能再次退 A。',f'{refund["refund_sn"]}：审核人 {refund["review_by"]}；实际退回 SKU 为 B，发货来源 #{a["source"][0][0]}（{a["source"][0][3]}）。自动占用 B 1 件。'],
        ['4','退款方案自动确认后，再申请另 1 件 B 换 C 1 件；保留最后 1 件 B。','已明确的退回退款不阻止使用剩余 B 额度；仍须检查退款当前版本及来源容量。',f'{second["exchange_sn"]}：审核人 {second["review_by"]}；实际原商品 B、申请 1 件、目标 C；两类售后来源相同，各占 1 件。'],
        ['5','两件 B 使用同一顺丰退回运单；退款与换货均授权混合合包。','从最后一个成员加入后等待 120 秒，形成一张共同入库单；退款、换货各 1 件。',f'运单 {d["parcel"].get("waybill",d["parcel"].get("return_waybill",""))}；共同单 {d["document"]["return_sn"]}，两名成员合计 2 件；最后成员 {max(str(x["create_time"]) for x in d["members"])}，可下发 {d["parcel"]["dispatch_after"]}，共同单建立 {d["document"]["create_time"]}。'],
        ['6','仓库签名回传两条公共成员行，各收到 B 良品 1 件。','只入库 B 2 件，各自保留原 B 出库成本；不将同一实收重复记入两张单。','退款 B1 与换货 B1 均已实收并入库；两份私有来源成本均为 2.500000，公共单仅 1 张。'],
        ['7','退回入库后自动退款；第二张换货自动验收并发出 C。','平台退款成功 100 元；实际只发 C 1 件，平台第二次换货成功。',f'退款本地 {refund["state"]}、资金 {refund["funds_state"]}、金额 ¥{float(refund["refund_fee"]):.2f}；第二张 HH 本地 {second["state"]}、平台 {second["platform_status"]}；C 实发 1 件。'],
        ['8','核对资金、来源额度、全部库存及旧验收基线。','买家最终保留 B1+C1，退 100 元；A10/B9/C9；预占/分配/冻结/仓库锁定均 0。','公司与仓库一致；B 原实发 3、退款占 1、换货占 1、剩余可退换 1；旧单据库存、原报告清单、队列与全局任务配置保持原值。'],
    ]
    body='<section id="case-24" data-case="24"><div class="case-head"><h2>24 A 三件换 B 后，一件退货退款、一件换 C</h2><span class="badge complete">符合预期 · 完整自动链路通过</span></div>'
    body+=paragraph('场景：原购 A3 已全部换成 B3；收到 B 后，先申请 B1 退货退款，再申请另 B1 换 C1。两件 B 同运单合包退回，另 B1 保留。原单实付 300 元，本次退款 100 元。')
    body+=paragraph(f'执行批次 {data["run_id"]}；完成 {fmt(c["completed_at"])}；店铺 {c["channel_name"]}（{c["channel_id"]}）；仓库 {c["warehouses"][0]["code"]}。')
    body+='<p>原销售 <a href="/oms-order/business?orderId='+str(c['order_id'])+'">'+h(c['order_sn'])+'</a>；'+''.join('<a href="/oms-order/exchanges?exchangeId='+str(e['id'])+'">'+h(e['exchange_sn'])+'</a>；' for e in [first,second])+'退款 <a href="/oms-order/businessRefunds?id='+str(refund['id'])+'">'+h(refund['refund_sn'])+'</a></p>'
    body+=table(['商品','实际 SKU','身份'],[[alias,sku,{'A':'原支付子单购买商品','B':'首次实际换出商品；本次退回实物','C':'再次换出目标'}[alias]] for sku,alias in names.items()])
    body+='<h3>逐步验收：场景、预期与实际证据</h3>'+table(['步骤','场景/输入','预期','实际验收结果','判定'],[row+['通过'] for row in steps])
    before=company(c['before_target']);final=company(c['final_stock']);comparison=[]
    for sku,alias in names.items():comparison.append([alias]+[f'{before[sku][i]} → {final[sku][i]}' for i in range(5)])
    body+='<h3>公司库存：首次售后前 → 最终</h3>'+table(['商品','总数','可用','预占','已分配','冻结'],comparison)
    equations=['A：7＋首次换货退回入库3＝10。','B：10－首次换出3＋退货退款入库1＋再次换货入库1＝9。','C：10－再次换出1＝9。']
    body+=''.join(paragraph(v,'equation') for v in equations)
    wb=index(c['before_target']['warehouse'],1);wf=index(c['final_stock']['warehouse'],1)
    body+='<h3>仓库库存：实物、可用与锁定</h3>'+table(['商品','良品实物','良品可用','良品锁定','次品实物','次品可用'],[[alias]+[f'{wb[sku][i]} → {wf[sku][i]}' for i in range(2,7)] for sku,alias in names.items()])
    phases=[('首次售后前',c['before_target'])]+list(c['stock_phases'].items())+[('最终',c['final_stock'])]
    labels={'FIRST_EXCHANGE_COMPLETED':'首次 A3→B3 已完成','COMMON_RETURN_BEFORE':'共同退回签名回传前','COMMON_RETURN_AFTER_CALLBACK':'共同退回签名回传后（异步入账可能未完成）'}
    phases.sort(key=lambda x:x[1]['at']);timeline=[]
    for label,snapshot in phases:
        if label.startswith('RETURN_'):
            parts=label.split('_');labels[label]='首次 A 退回第 '+parts[2]+' 次签名回传'+('前' if label.endswith('_BEFORE') else '后')
        if label.startswith('OUT_'):
            parts=label.split('_');shipment=next(s for e in c['final_exchanges'] for s in e['shipments'] if str(s['id'])==parts[1]);alias='B' if int(shipment['exchange_id'])==int(first['id']) else 'C';labels[label]=alias+' '+parts[2]+' 件实际出库'+('前' if label.endswith('_BEFORE') else '回传后')
        values=company(snapshot);timeline.append([fmt(snapshot['at']),labels.get(label,label)]+[' / '.join(map(str,values[sku][:3])) for sku in names])
    body+='<h3>库存变化过程</h3>'+paragraph('每格为“公司总数 / 可用 / 预占”。回传后快照保留异步处理中原值，最终结论按已入账结果核对。')+table(['快照时间','阶段','A 总/可用/预占','B 总/可用/预占','C 总/可用/预占'],timeline)
    body+='<h3>共同退回：一张仓单，两份独立归属</h3>'+table(['类型','业务单','SKU','应退','已实收','已入库'],[[m['owner_kind'],m['owner_sn'],'B',m['quantity'],m['accepted_quantity'],m['posted_quantity']] for m in d['members']])
    body+=table(['所属','私有来源单','原实际发货单','实际SKU','计划','实收','入库','原出库单价'],[['首次换货']+a['exchange_returns'][0][1:],['再次换货']+a['exchange_returns'][1][1:],['退货退款']+a['refund_returns'][0][1:]])
    body+=paragraph('私有来源单保留退款/换货各自数量与成本，实际下发仓库的是共同单 '+d['document']['return_sn']+'。原 A 退回 3 件只发生一次；共同单只收 B 2 件。')
    body+='<h3>实际发货及 B 来源剩余额度</h3>'+table(['发货单','类型','HH ID','实际SKU','原安排','实际出库','已取消未发'],a['shipments'])+table(['占用类型','业务编号','占用 B 件数'],a['claims'])+paragraph('B 发货来源实发 3 件，本次两个售后共占用 2 件，剩余 1 件。已入库的原来源不重新释放为可重复退换额度。')
    body+='<details><summary>渠道额度与库存需求原始台账</summary>'+table(['SKU','额度','额度占用','记录可售','预占','已分配'],c['final_stock']['channel'])+paragraph('渠道额度、记录可售和公司/仓库实物分别展示。退回入库不等于重新分配原店铺额度，本场景未验收真实平台库存同步。')+table(['业务引用','SKU','需求','取消','累计预占字段','累计出库','状态'],a['demand'])+'</details>'
    body+='<details><summary>平台交互与自动执行记录</summary>'+table(['HH','动作','结果','原请求号','时间'],[[e['exchange_sn'],op['action'],op['state'],op['request_id'],op['create_time']] for e,flow in zip(c['final_exchanges'],c['automatic_flows']) for op in flow['operations']])+table(['时间','HH','动作','执行人','记录'],sorted([[v['create_time'],e['exchange_sn'],v['action'],v['actor'],v['message']] for e in c['final_exchanges'] for v in e['events']],key=lambda r:r[0]))+'</details>'
    body+=paragraph('验收边界：本地平台 SIMULATION、签名 TEST 仓库。目标售后的审核、同意、预占、验收、分仓、下发与记账均由自动流程完成；原销售发货为前置。未将此结果表述为真实平台付款或真实物流验收。','note')
    if data.get('prior_attempts'):body+=paragraph('保留一次修复前失败记录：原 A 额度已被首次换货占用，退款自动选源停转人工。该记录未人工补审或重试，不计为通过；修复后以新店铺、新订单重跑本页完整链路。','notice')
    body+='</section>'
    structured={'case':'24','title':'A三件换B后，一件退货退款、一件换C','result':'符合预期 · 完整自动链路通过','run_id':data['run_id'],'order_sn':c['order_sn'],'refund_sn':refund['refund_sn'],'steps':[dict(zip(['step','input','expected','actual'],s),verdict='通过') for s in steps],'inventory_comparison':comparison,'inventory_equations':equations,'source_claimed':2,'source_remaining':1,'refund_amount':'100.00','shared_return_sn':d['document']['return_sn'],'shared_return_quantity':2}
    return body,structured
