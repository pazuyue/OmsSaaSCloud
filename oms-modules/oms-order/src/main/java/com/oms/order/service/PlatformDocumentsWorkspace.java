package com.oms.order.service;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import com.ruoyi.common.core.web.page.TableDataInfo;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.map;

/** Read-only local workspace. A related lookup never needs platform access or a converted SO. */
@Service public class PlatformDocumentsWorkspace {
    @Resource OrderRepository repository;
    @Resource OrderCatalog catalog;
    @Resource OrderWorkspaceService orders;
    @Resource RefundService refunds;
    @Resource PlatformExchangeService exchanges;
    public Map<String,Object> options(String company,Set<String> allowed){List<Map<String,Object>> shops=new ArrayList<>();for(Map<String,Object> c:catalog.channels(company))if("TM".equals(text(c,"channel_type")))shops.add(map("channel_id",c.get("channel_id"),"channel_name",c.get("channel_name"),"channel_type",c.get("channel_type")));return map("types",allowed,"channels",shops);}
    private void requireKind(Set<String> allowed,String kind){require(Arrays.asList("ORDER","REFUND","EXCHANGE").contains(kind)&&allowed.contains(kind),"无权查看该类型平台单据");}
    public Map<String,Object> list(String company,Set<String> allowed,String kind,Map<String,String> input){requireKind(allowed,kind);Map<String,String> q=new HashMap<>(input);int page=PlatformDocumentQuery.page(q,"pageNum",1,1000),size=PlatformDocumentQuery.page(q,"pageSize",20,50);PlatformDocumentQuery.channel(q);if("ORDER".equals(kind)){TableDataInfo result=orders.list(company,"platform",PlatformDocumentQuery.channel(q),PlatformDocumentQuery.value(q,"tid"),PlatformDocumentQuery.value(q,"status"),page,size,"","","","",0,q);return map("rows",result.getRows(),"total",result.getTotal());}return "REFUND".equals(kind)?refunds.platformList(company,q):exchanges.list(company,q);}
    public Map<String,Object> related(String company,Set<String> allowed,Map<String,String> q){String tid=PlatformDocumentQuery.number(q,"platformTid");require(!tid.isEmpty(),"请填写完整平台主单号后查看原单关联");Long channel=PlatformDocumentQuery.channel(q);List<Map<String,Object>> groups=new ArrayList<>();
        for(String kind:Arrays.asList("ORDER","REFUND","EXCHANGE")){if(!allowed.contains(kind))continue;
            String fields,table,join;
            if("ORDER".equals(kind)){table="oms_platform_order";fields="p.id,p.channel_id,p.platform_tid document_no,p.platform_tid,'' platform_oid,p.platform_status,p.conversion_status conversion_state,p.conversion_error,p.source_modified,p.business_order_id business_id,b.order_sn business_no,CASE WHEN b.id IS NULL THEN NULL ELSE "+OrderReviewPolicy.businessStatusSql().replace("o.","b.")+" END business_state,p.payment amount";join=" LEFT JOIN oms_order b ON b.company_code=p.company_code AND b.id=p.business_order_id";}
            else if("REFUND".equals(kind)){table="oms_platform_refund";fields="p.id,p.channel_id,p.refund_id document_no,p.platform_tid,p.platform_oid,p.platform_status,p.conversion_state,p.conversion_error,p.source_state,p.source_error,p.source_modified,p.business_refund_id business_id,b.refund_sn business_no,b.state business_state,p.refund_fee amount";join=" LEFT JOIN oms_refund b ON b.company_code=p.company_code AND b.id=p.business_refund_id";}
            else {table="oms_platform_exchange";fields="p.id,p.channel_id,p.dispute_id document_no,p.platform_tid,p.platform_oid,p.platform_status,p.conversion_state,p.conversion_error,p.source_state,p.source_error,p.source_modified,p.business_exchange_id business_id,b.exchange_sn business_no,b.state business_state,p.requested_quantity quantity,p.original_description,p.target_description";join=" LEFT JOIN oms_exchange b ON b.company_code=p.company_code AND b.id=p.business_exchange_id";}
            String where=" WHERE p.company_code=? AND p.platform_tid=?"+("ORDER".equals(kind)?" AND p.source_kind='PLATFORM'":"");List<Object> args=new ArrayList<>(Arrays.asList(company,tid));if(channel!=null){where+=" AND p.channel_id=?";args.add(channel);}
            List<Map<String,Object>> rows=repository.db().query("SELECT "+fields+" FROM "+table+" p"+join+where+" ORDER BY p.id DESC LIMIT 51",ROW,args.toArray());boolean more=rows.size()>50;if(more)rows=new ArrayList<>(rows.subList(0,50));for(Map<String,Object> row:rows)row.put("record_key",kind+":"+row.get("id"));groups.add(map("kind",kind,"rows",rows,"has_more",more));
        }
        return map("platform_tid",tid,"channel_id",channel,"groups",groups);
    }
}
