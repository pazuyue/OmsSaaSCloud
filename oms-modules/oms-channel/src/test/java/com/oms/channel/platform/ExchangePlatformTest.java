package com.oms.channel.platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ExchangePlatformTest {
    Map<String,Object> parse(String body,String operation)throws Exception{return ExchangePlatformService.normalize(new ObjectMapper().readTree(body),ExchangePlatformService.METHODS.get(operation),operation);}
    @Test void listNormalizesOfficialWrappedArray()throws Exception{assertEquals(1,parse("{\"tmall_exchange_receive_get_response\":{\"page_results\":1,\"results\":{\"exchange\":[{\"dispute_id\":\"1\"}]}}}","LIST").get("page_results"));}
    @Test void emptyListIsAllowedOnlyWhenCountIsZero()throws Exception{assertTrue(((List<?>)parse("{\"tmall_exchange_receive_get_response\":{\"page_results\":0}}","LIST").get("records")).isEmpty());assertThrows(TmallClient.Failure.class,()->parse("{\"tmall_exchange_receive_get_response\":{\"page_results\":1}}","LIST"));}
    @Test void successHttpCannotHideBusinessFailure(){TmallClient.Failure e=assertThrows(TmallClient.Failure.class,()->parse("{\"tmall_exchange_agree_response\":{\"result\":{\"success\":false,\"msg_code\":\"NO_AUTH\",\"message\":\"PRIVATE_TOKEN\"}}}","AGREE"));assertEquals("NO_AUTH",e.code);assertFalse(e.unknown);assertFalse(e.getMessage().contains("PRIVATE_TOKEN"));}
    @Test void missingBusinessResultIsUnknown(){assertTrue(assertThrows(TmallClient.Failure.class,()->parse("{\"tmall_exchange_agree_response\":{}}","AGREE")).unknown);}
    @Test void missingExchangeIdentityIsUnknown(){assertTrue(assertThrows(TmallClient.Failure.class,()->parse("{\"tmall_exchange_agree_response\":{\"result\":{\"success\":true,\"exchange\":{}}}}","AGREE")).unknown);}
    @Test void detailNormalizesOfficialResult()throws Exception{Map<String,Object> r=parse("{\"tmall_exchange_get_response\":{\"result\":{\"success\":true,\"exchange\":{\"dispute_id\":\"1\",\"num\":2}}}}","DETAIL");assertEquals("1",((Map<?,?>)r.get("exchange")).get("dispute_id"));assertEquals(false,r.get("simulation"));}
    @Test void reasonsDoNotUseTheExchangeResponseShape()throws Exception{assertEquals(1,((List<?>)parse("{\"tmall_exchange_refusereason_get_response\":{\"result\":{\"results\":{\"reason\":[{\"reason_id\":4002}]}}}}","REASONS").get("reasons")).size());}
    @Test void unknownResponseDoesNotBecomeAnEmptySuccessfulPage(){assertThrows(TmallClient.Failure.class,()->parse("{}","LIST"));}
}
