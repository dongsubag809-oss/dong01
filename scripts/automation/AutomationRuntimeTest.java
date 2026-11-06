package com.energyblue.househmi;
import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class AutomationRuntimeTest {
 static int checks=0;static String root;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static JSONObject window(int days,int start,int end,boolean all)throws Exception{return new JSONObject().put("days",days).put("start",start).put("end",end).put("allDay",all).put("repeatMin",0).put("runSec",0);}
 static long at(int day,int hour,int minute){Calendar c=Calendar.getInstance(AutomationLogic.ZONE);c.clear();c.set(2026,Calendar.NOVEMBER,day,hour,minute,0);return c.getTimeInMillis();}
 static JSONObject rule(int slot)throws Exception{return AutomationLogic.blank(1,slot).put("enabled",true).put("maxOnSec",1).put("minOffSec",0).put("schedules",new JSONArray().put(window(127,0,0,true)));}
 static void await(BooleanSupplier condition,String message)throws Exception{long deadline=System.nanoTime()+7_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(25);check(condition.getAsBoolean(),message);}
 static final class Host implements AutomationController.Host {
  volatile boolean allowed=true,value=false;final AtomicInteger on=new AtomicInteger(),off=new AtomicInteger();volatile AutomationController controller;volatile String message="";
  public boolean mayOperate(){return allowed;}public boolean commandBusy(){return false;}public Boolean output(int h,int s){return allowed?value:null;}public int houseOnCount(int h){return allowed?(value?1:0):-1;}public long receivedAt(int h){return allowed?System.currentTimeMillis():0;}
  public boolean dispatch(int h,int s,boolean v){if(!allowed)return false;value=v;if(v)on.incrementAndGet();else off.incrementAndGet();controller.outputResult(1,s+1,"CONFIRMED");return true;}
  public void emit(String event,String value){message=value;}
 }
 static boolean packet(Host h,int id){try{return new JSONObject(h.message).optInt("requestId")==id;}catch(Exception ex){return false;}}
 static AutomationController setup(String name,JSONObject rule,Host host)throws Exception{
  Context context=new Context(root+"/"+name);AutomationStore store=new AutomationStore(context);store.saveRules(new JSONArray().put(rule));store.close();AutomationController c=new AutomationController(context,host);host.controller=c;Thread.sleep(200);return c;
 }
 public static void main(String[] args)throws Exception{
  root=args[0];
  JSONObject w=window(2,23*60,60,false); // Monday -> Tuesday, 2026-11-02 is Monday.
  check(AutomationLogic.inWindow(w,at(2,23,30)),"Monday late window");check(AutomationLogic.inWindow(w,at(3,0,30)),"Overnight keeps previous weekday");check(!AutomationLogic.inWindow(w,at(3,1,0)),"End is exclusive");check(!AutomationLogic.inWindow(w,at(2,0,30)),"Does not borrow wrong previous weekday");
  JSONObject base=rule(0);base.put("schedules",new JSONArray().put(w).put(window(4,30,120,false)));check(AutomationLogic.validate(base).contains("겹칩니다"),"Overnight next-day overlaps rejected");
  base.put("schedules",new JSONArray().put(window(127,0,0,false)));check(AutomationLogic.validate(base)!=null,"Equal times need explicit all day");
  JSONObject repeat=window(127,0,0,true).put("repeatMin",10).put("runSec",30);check(AutomationLogic.inWindow(repeat,at(6,12,0)),"Repeat begins at anchor");check(!AutomationLogic.inWindow(repeat,at(6,12,1)),"Repeat stops between pulses");
  JSONObject t=new JSONObject().put("direction","high").put("on",30).put("off",27);check(AutomationLogic.threshold(t,31,false),"High temperature on");check(AutomationLogic.threshold(t,28,true),"Hysteresis holds");check(!AutomationLogic.threshold(t,27,true),"High temperature off");check(!AutomationLogic.threshold(t,28,false),"Hysteresis does not prematurely restart");
  t.put("direction","low").put("on",18).put("off",21);check(AutomationLogic.threshold(t,17,false)&&!AutomationLogic.threshold(t,21,true),"Low trigger direction");
  base=rule(0).put("mode","combined").put("temperature",new JSONObject().put("direction","high").put("on",30).put("off",27)).put("humidity",new JSONObject().put("direction","high").put("on",80).put("off",70));check(AutomationLogic.validate(base)==null,"Valid combined rule");check(!AutomationLogic.demand(base,at(6,12,0),31.0,60.0,false,false),"ALL rejects one missing condition");base.put("join","any");check(AutomationLogic.demand(base,at(6,12,0),31.0,60.0,false,false),"ANY accepts one satisfied condition");check(!AutomationLogic.demand(base,at(6,12,0),null,null,false,false),"Missing sensors do not become measured zero");
  base.put("maxOnSec",JSONObject.NULL);check(AutomationLogic.validate(base)!=null,"An enabled rule requires explicit max runtime");
  for(int h=1;h<=5;h++)for(int s=0;s<24;s++)check(AutomationLogic.validDevice(h,s)==(!AutomationLogic.motor(s)||s%2==0),"Fixed address/pair eligibility");
  Context context=new Context(root+"/history");AutomationStore db=new AutomationStore(context);db.saveRules(new JSONArray().put(rule(0)));db.saveRules(new JSONArray().put(rule(0).put("minOffSec",5)));check(db.loadRules().length()==1&&db.loadRules().getJSONObject(0).getInt("minOffSec")==5,"Settings replace by stable device identity");
  long now=System.currentTimeMillis(),start=(now/(15*60000)-1)*15*60000+60000;
  String zeros="0".repeat(64),on="1"+zeros.substring(1);
  db.board(1,zeros,start);db.board(1,zeros,start+5000);db.board(1,on,start+6000);db.board(1,on,start+16000);db.board(1,on,start+120000);db.board(1,on,start+125000);
  db.sensor(1,10,40,start);db.sensor(1,20,60,start+1000);db.sensor(1,50,80,start+60000);
  JSONObject history=db.history(1,0,"day","");check(history.getLong("observedMs")==20000,"Disconnected observation gap is not filled");check(history.getLong("onMs")==15000,"ON accumulation from received intervals only");JSONArray env=history.getJSONArray("environment");check(env.length()==1,"Minute samples bucket into requested history period");check(Math.abs(env.getJSONObject(0).getDouble("temperature")-80.0/3)<.0001,"Weighted means use sample counts, not mean of means");check(env.getJSONObject(0).getDouble("tMin")==10&&env.getJSONObject(0).getDouble("tMax")==50,"Min/max survive aggregation");
  JSONObject monthly=db.history(1,0,"month","");long bucket=monthly.getJSONArray("environment").getJSONObject(0).getLong("ts");Calendar day=Calendar.getInstance(AutomationLogic.ZONE);day.setTimeInMillis(bucket);check(day.get(Calendar.HOUR_OF_DAY)==0,"Monthly sensor buckets aligned to KST midnight");
  check(db.history(2,0,"day","").getJSONArray("environment").length()==0,"No sensor data fabricated for another house");db.close();db=new AutomationStore(context);check(db.loadRules().length()==1&&db.history(1,0,"day","").getLong("onMs")==15000,"History and rules persist after closing/reopening DB");db.close();
  Host five=new Host();AutomationController fiveController=setup("five-sensors",rule(0),five);
  try{
   for(int h=1;h<=5;h++)fiveController.sensor(h,20+h,60+h,System.currentTimeMillis());
   for(int h=1;h<=5;h++){
    final int id=900+h;fiveController.snapshot(h,0,"day","",id);await(()->packet(five,id),"Sensor snapshot received for house "+h);
    JSONObject value=new JSONObject(five.message);check(value.getDouble("temperature")==20+h&&value.getDouble("humidity")==60+h,"Each house has its own live received values");
    JSONArray points=value.getJSONObject("history").getJSONArray("environment");check(points.length()==1&&points.getJSONObject(0).getDouble("temperature")==20+h,"Native sensor data saved in matching house SQL history");
   }
   fiveController.sensorDisconnected(3);fiveController.snapshot(3,0,"day","",999);await(()->packet(five,999),"Disconnected snapshot received");check(new JSONObject(five.message).isNull("temperature"),"Disconnect clears only affected house current value");
   fiveController.snapshot(5,0,"day","",1000);await(()->packet(five,1000),"Unaffected house snapshot");check(new JSONObject(five.message).getDouble("temperature")==25,"Other house current data survives disconnect");
  }finally{fiveController.close();}
  Host host=new Host();AutomationController c=setup("timer",rule(0),host);
  Host timerHost=host;
  try{Thread.sleep(1100);check(timerHost.on.get()==0,"Saved configuration must not auto-start");c.start();await(()->timerHost.on.get()==1,"Explicit start dispatches one scheduled ON");await(()->timerHost.off.get()==1,"Maximum runtime dispatches OFF");Thread.sleep(1300);check(timerHost.on.get()==1,"Max-runtime lock prevents repeated immediate ON");}finally{c.close();}
  host=new Host();Host motorHost=host;c=setup("motor",rule(4),host);try{c.start();Thread.sleep(1300);check(motorHost.on.get()==0,"No motor automation without verified limit map");}finally{c.close();}
  host=new Host();Host sensorHost=host;c=setup("sensor",rule(0).put("mode","temperature").put("temperature",new JSONObject().put("direction","high").put("on",30).put("off",27)),host);try{c.start();Thread.sleep(1300);check(sensorHost.on.get()==0,"No physical ON when environmental sensor map/data is missing");}finally{c.close();}
  host=new Host();Host manualHost=host;c=setup("manual",rule(0).put("maxOnSec",60),host);try{c.start();await(()->manualHost.on.get()==1,"Manual-priority scenario starts");c.override(1,1);Thread.sleep(1200);manualHost.value=false;Thread.sleep(1200);check(manualHost.on.get()==1,"Manual override does not immediately auto-restart the device");}finally{c.close();}
  host=new Host();Host lossHost=host;c=setup("loss",rule(0).put("maxOnSec",60),host);try{c.start();await(()->lossHost.on.get()==1,"Communication-loss scenario starts");lossHost.allowed=false;Thread.sleep(1200);check(!c.isActive(),"Communication loss revokes automatic session");lossHost.allowed=true;lossHost.value=false;Thread.sleep(1400);check(lossHost.on.get()==1,"Reconnection cannot restart automatic ON");}finally{c.close();}
  System.out.println("PASS: "+checks+" automation checks: midnight/weekdays/overlap/repeat/hysteresis, missing sensor and limit guards, actual production SQLite SQL and reopening, KST monthly aggregation, weighted min/mean/max, received-only gaps, explicit start, maximum runtime, manual priority and reconnect lock. Physical equipment not connected.");
 }
}
