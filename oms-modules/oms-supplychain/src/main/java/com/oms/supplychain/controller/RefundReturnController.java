package com.oms.supplychain.controller;
import com.oms.supplychain.service.wms.RefundReturnService;
import com.oms.supplychain.service.wms.RefundParcels;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/refundReturns/internal") public class RefundReturnController {
    @Resource private RefundReturnService returns;
    @Resource private RefundParcels parcels;
    @InnerAuth @GetMapping("/parcels/capabilities") public R<List<Map<String,Object>>> capabilities(@RequestParam("company_code") String c){return R.ok(parcels.capabilities(c));}
    @InnerAuth @GetMapping("/parcels/{parcel}/inspect") public R<Map<String,Object>> inspect(@RequestParam("company_code") String c,@PathVariable long parcel,@RequestParam long channel,@RequestParam String tid){return R.ok(parcels.inspect(c,parcel,channel,tid));}
    @InnerAuth @GetMapping("/parcels") public R<List<Map<String,Object>>> parcels(@RequestParam("company_code") String c,@RequestParam("refund") long refund){return R.ok(parcels.list(c,refund));}
    @InnerAuth @GetMapping("/parcels/candidates") public R<List<Map<String,Object>>> candidates(@RequestParam("company_code") String c,@RequestParam String carrier,@RequestParam String waybill){return R.ok(parcels.candidates(c,carrier,waybill));}
    @InnerAuth @PostMapping("/parcels/plans/{plan}") public R<Map<String,Object>> attach(@RequestParam("company_code") String c,@PathVariable long plan,@RequestBody Map<String,Object> body){return R.ok(parcels.attach(c,plan,body));}
    @InnerAuth @PostMapping("/parcels/{parcel}/transport") public R<Map<String,Object>> transport(@RequestParam("company_code") String c,@PathVariable long parcel,@RequestParam long refund,@RequestBody Map<String,Object> body){return R.ok(parcels.transport(c,refund,parcel,body));}
    @InnerAuth @PostMapping("/parcels/{parcel}/receipts/{event}") public R<Boolean> allocate(@RequestParam("company_code") String c,@PathVariable long parcel,@PathVariable long event,@RequestParam long refund,@RequestBody Map<String,Object> body){parcels.allocate(c,refund,parcel,event,body,returns);return R.ok(true);}
    @InnerAuth @PostMapping("/parcels/plans/{plan}/detach") public R<Boolean> detach(@RequestParam("company_code") String c,@PathVariable long plan,@RequestParam long refund){parcels.detach(c,refund,plan);return R.ok(true);}
    @InnerAuth @PostMapping("/parcels/automatic-scan") public R<Boolean> automaticScan(@RequestParam("company_code") String c){parcels.reconcileAutomatic(c,returns);return R.ok(true);}
    @InnerAuth @PostMapping("/parcels/{parcel}/receipts/{event}/automatic") public R<Map<String,Object>> automatic(@RequestParam("company_code") String c,@PathVariable long parcel,@PathVariable long event,@RequestParam long refund,@RequestBody Map<String,Object> input){return R.ok(parcels.evaluateAutomatic(c,refund,parcel,event,input,returns));}
    @InnerAuth @GetMapping("/pending") public R<List<Map<String,Object>>> pending(@RequestParam("company_code") String company){return R.ok(returns.pending(company));}
    @InnerAuth @PostMapping("/{plan}/acknowledge") public R<Boolean> acknowledge(@RequestParam("company_code") String company,@PathVariable long plan,@RequestBody Map<String,Object> body){returns.acknowledge(company,plan,body);return R.ok(true);}
    @InnerAuth @PostMapping("/{plan}") public R<Map<String,Object>> prepare(@RequestParam("company_code") String company,@PathVariable long plan,@RequestBody Map<String,Object> body){return R.ok(returns.prepare(company,plan,body));}
    @InnerAuth @GetMapping("/{plan}") public R<Map<String,Object>> detail(@RequestParam("company_code") String company,@PathVariable long plan){return R.ok(returns.detail(company,plan));}
    @InnerAuth @PostMapping("/{plan}/documents/{document}/{action}") public R<Map<String,Object>> document(@RequestParam("company_code") String company,@PathVariable long plan,@PathVariable long document,@PathVariable String action,@RequestBody Map<String,Object> body){return R.ok(returns.documentAction(company,plan,document,action,body));}
}
