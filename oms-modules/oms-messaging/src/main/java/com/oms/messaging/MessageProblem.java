package com.oms.messaging;

import java.util.*;

/** Stable business-facing classifications. Never expose exception messages or credentials. */
public final class MessageProblem {
 private MessageProblem() {}
 public static final Set<String> CODES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("NONE","RATE_LIMIT","NETWORK","SERVICE_ERROR","CONFIGURATION","BUSINESS_CONDITION","RETRY_EXHAUSTED","DELIVERY","PROGRESS")));
 public static String exception(Throwable e){for(Throwable t=e;t!=null;t=t.getCause()){if(t instanceof java.net.SocketTimeoutException||t instanceof java.net.ConnectException||t instanceof java.net.UnknownHostException||t instanceof java.net.SocketException)return "NETWORK";}if(e instanceof org.springframework.dao.EmptyResultDataAccessException)return "CONFIGURATION";return e instanceof IllegalArgumentException?"BUSINESS_CONDITION":"SERVICE_ERROR";}
 public static String summary(Throwable e){switch(exception(e)){case "CONFIGURATION":return "原任务所需的店铺或业务配置缺失，请核对原业务配置";case "BUSINESS_CONDITION":return "原任务业务条件校验未通过，请打开业务单据核对";case "NETWORK":return "业务服务连接暂不可用，保留原任务重试";default:return "业务服务处理异常，保留原任务重试";}}
 public static Map<String,Object> view(Map<String,Object> row){String code=MessageOutbox.s(row,"problem_code");if(code.isEmpty())code="NONE";String state=MessageOutbox.s(row,"process_state");if("NONE".equals(code)&&!MessageOutbox.s(row,"last_publish_error").isEmpty())code="DELIVERY";if("SUCCESS".equals(state))code="NONE";String advice;
  switch(code){case "RATE_LIMIT":advice="等待调用额度恢复，系统会按下次处理时间自动继续，无需人工重试";break;case "NETWORK":advice="核对服务连接；系统会自动重试，达到上限后再人工处理";break;case "SERVICE_ERROR":advice="核对服务运行与内部日志，系统将按配置重试";break;case "CONFIGURATION":advice="打开业务单据核对店铺授权、商品及服务配置，修正后申请重试";break;case "BUSINESS_CONDITION":advice="打开业务单据和交互日志核对具体原因，补齐业务条件后申请重试";break;case "RETRY_EXHAUSTED":advice="自动重试已停止；查看最后失败原因，修正后按原业务申请重试";break;case "DELIVERY":advice="核对代理连接与队列路由；原任务仍保留，系统会重新投递或补偿";break;case "PROGRESS":advice="断点已保存，等待下一轮自动继续";break;default:advice="SUCCESS".equals(state)?"业务处理已完成，无需重试":"等待系统处理";}
  Map<String,Object> r=new LinkedHashMap<>();r.put("code",code);r.put("advice",advice);r.put("retryable","FAILED".equals(state)&&MessageOutbox.n(row,"has_newer")==0);return r;
 }
}
