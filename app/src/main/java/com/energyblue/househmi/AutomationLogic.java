package com.energyblue.househmi;

import org.json.*;
import java.util.*;

/** Pure decisions only. No serial, hardware addresses, timers or fabricated sensors. */
public final class AutomationLogic {
 public static final TimeZone ZONE=TimeZone.getTimeZone("Asia/Seoul");
 private AutomationLogic(){}
 public static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
 public static boolean motor(int slot){return slot>=4&&slot<=19;}
 public static boolean validDevice(int house,int slot){return house>=1&&house<=5&&slot>=0&&slot<24&&(!motor(slot)||slot%2==0);}
 public static String key(int house,int slot){return house+":"+slot;}
 public static JSONObject blank(int house,int slot)throws JSONException{
  return new JSONObject().put("house",house).put("slot",slot).put("enabled",false).put("mode","time")
   .put("join","all").put("timeGate",false).put("temperature",threshold()).put("humidity",threshold())
   .put("maxOnSec",JSONObject.NULL).put("minOffSec",0).put("schedules",new JSONArray());
 }
 private static JSONObject threshold()throws JSONException{return new JSONObject().put("direction","high").put("on",JSONObject.NULL).put("off",JSONObject.NULL);}
 public static String validate(JSONObject rule){
  int h=rule.optInt("house",0),s=rule.optInt("slot",-1);
  if(!validDevice(h,s))return "동 또는 장비 주소 오류";
  String mode=rule.optString("mode");
  if(!Arrays.asList("time","temperature","humidity","combined").contains(mode))return "자동 조건 선택 오류";
  if(!Arrays.asList("all","any").contains(rule.optString("join","all")))return "조건 조합 오류";
  JSONArray windows=rule.optJSONArray("schedules");
  if(windows==null||windows.length()>16)return "예약은 장비당 최대 16개입니다.";
  boolean[] minutes=new boolean[7*1440];
  for(int i=0;i<windows.length();i++){
   JSONObject w=windows.optJSONObject(i);if(w==null)return "예약 형식 오류";
   int days=w.optInt("days",0),start=w.optInt("start",-1),end=w.optInt("end",-1);
   boolean all=w.optBoolean("allDay",false);
   if(days<1||days>127||start<0||start>=1440||end<0||end>=1440||(!all&&start==end))return "요일과 시작·종료 시간을 확인하세요. 하루 종일은 별도로 선택하세요.";
   int interval=w.optInt("repeatMin",0),run=w.optInt("runSec",0);
   if(interval<0||interval>1440||(interval>0&&(run<1||run>interval*60)))return "반복 운전시간은 반복 간격 이하여야 합니다.";
   int length=all?1440:(end-start+1440)%1440;
   for(int day=0;day<7;day++)if((days&(1<<day))!=0){
    int from=day*1440+(all?0:start);
    for(int m=0;m<length;m++){
     int n=(from+m)%minutes.length;
     if(minutes[n])return "예약 시간이 겹칩니다. 자정 이후 구간도 확인하세요.";
     minutes[n]=true;
    }
   }
  }
  if(!rule.optBoolean("enabled",false))return null; // Incomplete disabled drafts may be saved.
  double max=rule.optDouble("maxOnSec",Double.NaN),off=rule.optDouble("minOffSec",Double.NaN);
  if(!finite(max)||max<1||max>86400||max!=Math.floor(max))return "최대 연속 운전시간을 1~86400초로 입력하세요.";
  if(!finite(off)||off<0||off>86400||off!=Math.floor(off))return "최소 정지시간을 0~86400초로 입력하세요.";
  if((mode.equals("time")||rule.optBoolean("timeGate"))&&windows.length()==0)return "예약 시간을 한 개 이상 등록하세요.";
  if(mode.equals("temperature")||mode.equals("combined")){
   String error=validateThreshold(rule.optJSONObject("temperature"),false);if(error!=null)return error;
  }
  if(mode.equals("humidity")||mode.equals("combined")){
   String error=validateThreshold(rule.optJSONObject("humidity"),true);if(error!=null)return error;
  }
  return null;
 }
 private static String validateThreshold(JSONObject t,boolean humidity){
  if(t==null)return "센서 조건 설정 누락";
  double on=t.optDouble("on",Double.NaN),off=t.optDouble("off",Double.NaN);
  String d=t.optString("direction");
  if(!finite(on)||!finite(off))return "켜짐·꺼짐 기준을 모두 입력하세요.";
  if(humidity&&(on<0||on>100||off<0||off>100))return "습도는 0~100% 범위입니다.";
  if((d.equals("high")&&on>off)||(d.equals("low")&&on<off))return null;
  return "높을 때 작동은 켜짐 > 꺼짐, 낮을 때 작동은 켜짐 < 꺼짐으로 설정하세요.";
 }
 public static boolean threshold(JSONObject setting,double value,boolean previous){
  double on=setting.optDouble("on",Double.NaN),off=setting.optDouble("off",Double.NaN);
  if(!finite(value)||!finite(on)||!finite(off))return false;
  if(setting.optString("direction").equals("low"))return previous?value<off:value<=on;
  return previous?value>off:value>=on;
 }
 public static boolean inWindow(JSONObject w,long timestamp){
  Calendar c=Calendar.getInstance(ZONE);c.setTimeInMillis(timestamp);
  int day=c.get(Calendar.DAY_OF_WEEK)-1,minute=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE),second=c.get(Calendar.SECOND);
  int days=w.optInt("days"),start=w.optInt("start"),end=w.optInt("end");
  int anchor=day,elapsed;
  if(w.optBoolean("allDay")){elapsed=minute*60+second;}
  else if(end>start){if(minute<start||minute>=end)return false;elapsed=(minute-start)*60+second;}
  else{if(minute>=start)elapsed=(minute-start)*60+second;else if(minute<end){anchor=(day+6)%7;elapsed=(minute+1440-start)*60+second;}else return false;}
  if((days&(1<<anchor))==0)return false;
  int repeat=w.optInt("repeatMin",0);
  return repeat<=0||elapsed%(repeat*60)<w.optInt("runSec",0);
 }
 public static boolean scheduled(JSONArray windows,long now){
  if(windows==null)return false;
  for(int i=0;i<windows.length();i++){JSONObject w=windows.optJSONObject(i);if(w!=null&&inWindow(w,now))return true;}
  return false;
 }
 public static boolean demand(JSONObject rule,long now,Double temperature,Double humidity,boolean oldTemp,boolean oldHumidity){
  String mode=rule.optString("mode");
  boolean time=scheduled(rule.optJSONArray("schedules"),now);
  if(mode.equals("time"))return time;
  if(rule.optBoolean("timeGate")&&!time)return false;
  boolean t=temperature!=null&&threshold(rule.optJSONObject("temperature"),temperature,oldTemp);
  boolean h=humidity!=null&&threshold(rule.optJSONObject("humidity"),humidity,oldHumidity);
  if(mode.equals("temperature"))return t;
  if(mode.equals("humidity"))return h;
  return rule.optString("join","all").equals("any")?(t||h):(t&&h);
 }
}
