package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import javax.annotation.Resource;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Official expressinfo.query, bounded read. Unknown provider status is never silently treated as enabled. */
@Service
public class WmsCarrierDirectory {
    @Resource private WmsConnections connections;
    @Resource private WmsStore db;
    public Map<String,Object> query(String company,Map<String,Object> scope){Map<String,Object> c=connections.usable(company,id(scope.get("connection_id")));require("QIMEN".equals(c.get("provider"))&&"2.0".equals(c.get("api_version")),"本连接未接通奇门2.0快递清单查询");
        String owner="";if(!text(scope.get("owner_code")).isEmpty()){List<Map<String,Object>> stores=db.jdbc.queryForList("SELECT DISTINCT v.external_owner FROM wms_simulation_store_info v JOIN owner_warehouse r ON r.company_key=v.company_key AND r.id=v.owner_warehouse_id JOIN owner_info o ON o.company_key=r.company_key AND o.id=r.owner_id WHERE v.company_key=? AND v.connection_id=? AND o.owner_code=?",company,c.get("id"),scope.get("owner_code"));require(stores.size()==1&&!text(stores.get(0).get("external_owner")).isEmpty(),"货主外部编码不唯一或缺失");owner=text(stores.get(0).get("external_owner"));}
        String body="<request>"+QimenProtocol.tag("ownerCode",owner)+"</request>";Map<String,String> p=new TreeMap<>();p.put("app_key",text(c.get("app_key")));p.put("customerId",text(c.get("customer_id")));p.put("method","taobao.qimen.expressinfo.query");p.put("format","xml");p.put("sign_method","md5");p.put("v","2.0");p.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));p.put("sign",QimenProtocol.sign(p,body,connections.secret(c)));
        String url=text(c.get("endpoint"))+"?"+p.entrySet().stream().map(e->QimenProtocol.enc(e.getKey())+"="+QimenProtocol.enc(e.getValue())).collect(Collectors.joining("&"));
        try{return parse(WmsHttp.post(new WmsProtocol.Wire(url,body,"application/xml;charset=UTF-8")));}catch(java.io.IOException e){throw new IllegalArgumentException("快递清单查询网络失败，请核对连接和接口权限");}
    }
    static Map<String,Object> parse(String body){Element root=QimenProtocol.parse(body);require("response".equals(root.getTagName())&&"success".equals(value(root,"flag")),"快递清单接口未返回成功结果");Element group=QimenProtocol.child(root,"expressInfos");require(group!=null,"快递清单响应缺少expressInfos，不清空已有资料");List<Map<String,Object>> rows=new ArrayList<>();for(Element item:QimenProtocol.children(group,"express_info")){String code=value(item,"expressCode"),name=value(item,"expressName"),status=value(item,"status");require(!code.isEmpty()&&!name.isEmpty()&&code.length()<=128&&name.length()<=200,"快递清单编码或名称不完整");rows.add(map("external_code",code,"external_name",name,"enabled",0,"disabled_reason","接口原状态："+status+"；请按仓库正式状态说明人工核实启用"));}require(!rows.isEmpty()&&rows.size()<=500,"快递清单为空或超过单批500条，请按货主缩小范围");return map("state","SUCCESS","rows",rows);}
    private static String value(Element element,String name){Element child=QimenProtocol.child(element,name);return child==null?"":child.getTextContent();}
}
