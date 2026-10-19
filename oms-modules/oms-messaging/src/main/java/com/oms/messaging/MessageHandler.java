package com.oms.messaging;
import java.util.Map;
public interface MessageHandler {
 String type();
 default java.util.List<String> rateKeys(String company,Map<String,Object> message){return java.util.Collections.emptyList();}
 default Map<String,Object> context(String company,Map<String,Object> message){return java.util.Collections.emptyMap();}
 Outcome handle(String company,Map<String,Object> message);
 default void failed(String company,Map<String,Object> message,String reason) {}
 void replay(String company,Map<String,Object> message,String actor,String reason);
 final class Outcome {
  public final String state,message;public final long delaySeconds;public boolean progress,preserveAttempts;public String problemCode="NONE";
  private Outcome(String s,String m,long d){state=s;message=m;delaySeconds=d;}
  public static Outcome done(String m){return new Outcome("SUCCESS",m,0);}
  public Outcome problem(String code){if(!MessageProblem.CODES.contains(code))throw new IllegalArgumentException("消息异常分类无效");problemCode=code;return this;}
  public static Outcome retry(String m,long seconds){return new Outcome("WAITING",m,Math.max(5,Math.min(seconds,86400))).problem("BUSINESS_CONDITION");}
  public static Outcome again(String m,long seconds){Outcome o=retry(m,seconds).problem("PROGRESS");o.progress=true;return o;}
  public static Outcome throttled(String m,long seconds){Outcome o=again(m,seconds).problem("RATE_LIMIT");o.preserveAttempts=true;return o;}
  public static Outcome failed(String m){return new Outcome("FAILED",m,0).problem("BUSINESS_CONDITION");}
 }
}
