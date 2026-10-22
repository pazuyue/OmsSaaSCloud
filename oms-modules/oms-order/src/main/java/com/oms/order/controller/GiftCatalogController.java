package com.oms.order.controller;

import com.oms.order.service.*;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController @RequestMapping("/giftCatalog/internal") public class GiftCatalogController {
    @Resource GiftService gifts;
    @InnerAuth @PostMapping("/references") public R<Map<String,Object>> references(@RequestParam("company_code") String company,@RequestBody Map<String,Object> query){return R.ok(gifts.catalogReferences(OrderValues.company(company),query));}
}
