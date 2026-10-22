package com.oms.order.service;

import com.oms.common.lucky.LuckyBagRules;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static com.oms.common.lucky.LuckyBagRules.*;
import static org.junit.jupiter.api.Assertions.*;

class LuckyBagRulesTest {
    Map<String,Object> candidate(String sku,long weight,Object fixed){return map("sku_sn",sku,"weight",weight,"fixed_quantity",fixed,"clearance_priority",0);}
    Map<String,Object> group(String key,Map<String,Object>... choices){return map("key",key,"name",key,"candidates",Arrays.asList(choices));}
    Map<String,Object> rule(){return map("enabled",1,"mode","RANDOM","selection","WEIGHTED","groups",new ArrayList<>(Arrays.asList(group("top",candidate("A",1,null),candidate("B",1,null)),group("bottom",candidate("C",1,null)))));}
    List<Map<String,Object>> stock(){return Arrays.asList(map("batch_id",1,"sku_sn","A","available",6,"unit_cost",2),map("batch_id",2,"sku_sn","B","available",4,"unit_cost",3),map("batch_id",3,"sku_sn","C","available",10,"unit_cost",4));}
    @Test void everyBagHasEveryGroupAndConsumesEachBatchAtMostOncePerAvailablePiece(){List<Map<String,Object>> units=plan(normalize(rule()),stock(),10,42);assertEquals(10,units.size());Map<String,Long> counts=new HashMap<>();for(Map<String,Object> unit:units){assertEquals(2,rows(unit.get("components")).size());for(Map<String,Object> p:rows(unit.get("components")))counts.merge(text(p,"sku_sn"),1L,Long::sum);}assertEquals(6,counts.get("A"));assertEquals(4,counts.get("B"));assertEquals(10,counts.get("C"));}
    @Test void replayingAPlanningSeedIsStable(){Map<String,Object> rule=normalize(rule());assertEquals(plan(rule,stock(),8,42),plan(rule,stock(),8,42));}
    @Test void fixedQuantitiesAreExactForTheWholePresetBatch(){Map<String,Object> input=rule();input.put("mode","PRESET");input.put("preset_quantity",10);rows(rows(input.get("groups")).get(0).get("candidates")).get(0).put("fixed_quantity",6);List<Map<String,Object>> units=plan(normalize(input),stock(),10,4);assertEquals(6,units.stream().filter(u->rows(u.get("components")).stream().anyMatch(c->text(c,"sku_sn").equals("A"))).count());}
    @Test void costCapUsesActualBatchCostsAndRejectsTheWholeInsufficientPlan(){Map<String,Object> input=rule();input.put("mode","PRESET");input.put("preset_quantity",10);input.put("max_cost",6);assertThrows(IllegalArgumentException.class,()->plan(normalize(input),stock(),10,42));input.put("max_cost",7);for(Map<String,Object> unit:plan(normalize(input),stock(),10,42))assertTrue(((BigDecimal)unit.get("cost")).compareTo(new BigDecimal("7"))<=0);}
    @Test void overlappingGroupsCannotDoubleCountOneSku(){Map<String,Object> input=rule();List<Map<String,Object>> groups=rows(input.get("groups"));groups.add(group("other",candidate("A",1,null)));input.put("groups",groups);assertThrows(IllegalArgumentException.class,()->normalize(input));}
    @Test void shortagesAndQuotaInconsistenciesFailBeforeAnyMutation(){assertThrows(IllegalArgumentException.class,()->plan(normalize(rule()),stock(),11,42));Map<String,Object> input=rule();input.put("mode","PRESET");input.put("preset_quantity",10);rows(rows(input.get("groups")).get(0).get("candidates")).get(0).put("fixed_quantity",11);assertThrows(IllegalArgumentException.class,()->normalize(input));}
    @Test void clearancePriorityIsAppliedBeforeWeightedSelection(){Map<String,Object> input=rule();input.put("selection","CLEARANCE");rows(rows(input.get("groups")).get(0).get("candidates")).get(1).put("clearance_priority",10);List<Map<String,Object>> units=plan(normalize(input),stock(),4,42);for(Map<String,Object> unit:units)assertTrue(rows(unit.get("components")).stream().anyMatch(c->text(c,"sku_sn").equals("B")));}
    @Test void randomModeCannotPretendToGuaranteeFixedQuotasOrCostCaps(){Map<String,Object> input=rule();input.put("max_cost",10);assertThrows(IllegalArgumentException.class,()->normalize(input));}
}
