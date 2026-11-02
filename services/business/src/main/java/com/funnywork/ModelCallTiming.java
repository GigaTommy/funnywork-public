package com.funnywork;
import java.util.*;

/** Gateway-observed timings, only settled successful calls; never GPU-only decode measurements. */
final class ModelCallTiming {
 static Map<String,Object> of(Map<String,Object> row){
  var result=new LinkedHashMap<String,Object>();
  if(!"SUCCEEDED".equals(row.get("ticket_state")))return result;
  Long admitted=number(row,"admitted_at"),granted=number(row,"granted_at"),started=number(row,"started_at"),ended=number(row,"ended_at"),first=number(row,"first_output_at");
  if(admitted==null||granted==null||started==null||ended==null||admitted>granted||granted>started||started>ended)return result;
  result.put("queue_ms",granted-admitted);result.put("dispatch_ms",started-granted);result.put("request_ms",ended-started);result.put("total_ms",ended-admitted);
  if(first!=null&&first>=started&&first<=ended){
   result.put("first_output_ms",first-started);
   if(ended>first&&"COMPLETE".equals(row.get("usage_quality"))){
    Long output=number(row,"output_tokens");if(output!=null){result.put("decode_ms",ended-first);result.put("decode_tokens",output);}
   }
  }
  return result;
 }
 private static Long number(Map<String,Object> row,String key){Object v=row.get(key);if(!(v instanceof Number n))return null;long x=n.longValue();return x>=0&&n.doubleValue()==(double)x?x:null;}
}
