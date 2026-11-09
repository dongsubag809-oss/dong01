package com.energyblue.househmi;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
@SuppressWarnings("resource")
public final class SensorTest {
 static int checks;
 static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
 static byte[] response(int id,int fc,int a,int b){byte[] r={(byte)id,(byte)fc,4,(byte)(a>>8),(byte)a,(byte)(b>>8),(byte)b,0,0};int crc=SensorProtocol.crc(r,7);r[7]=(byte)crc;r[8]=(byte)(crc>>8);return r;}
 static void denied(byte[] r,int id,int fc,String map){try{SensorProtocol.parse(r,id,fc,map);throw new AssertionError("invalid response accepted");}catch(java.io.IOException expected){checks++;}}
 interface Condition{boolean ok()throws Exception;}
 static void until(Condition c)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!c.ok()){if(System.nanoTime()>end)throw new AssertionError("async deadline");Thread.sleep(10);}checks++;}
 static final class Host implements SensorController.Host {
  volatile String config,json,plc="/dev/ttyS5";volatile boolean failSave=false;
  final AtomicInteger[] received=new AtomicInteger[5];final double[] t=new double[5];AtomicInteger invalidated=new AtomicInteger();
  Host(){for(int i=0;i<5;i++)received[i]=new AtomicInteger();}
  public String plcPath(){return plc;}public boolean persist(String s){if(failSave)return false;config=s;return true;}public void emit(String s){json=s;}
  public void reading(int house,double temperature,double humidity,long at){t[house-1]=temperature;received[house-1].incrementAndGet();}public void invalidate(int h){invalidated.incrementAndGet();}
 }
 static final class Device {volatile int id,baud=9600,nextId,nextBaud;Device(int id){this.id=id;}void power(){if(nextId>0){id=nextId;nextId=0;}if(nextBaud>0){baud=nextBaud;nextBaud=0;}}}
 static final class Port implements SensorController.Port {
  final List<Device> devices=new CopyOnWriteArrayList<>();final Host h;int baud;volatile int writes=0;volatile boolean timeout=false,noEffect=false;volatile long delay;
  Port(Host h){this.h=h;}
  public String open(String p,int b){baud=b;return "OPEN_OK";}
  public byte[] read(int id,int fc,String map,int timeout)throws java.io.IOException{
   if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("interrupted");}
   for(Device d:devices)if(d.id==id&&d.baud==baud)return response(id,fc,500+id,200+id);
   return new byte[0];
  }
  public byte[] setting(int id,int register,int value)throws java.io.IOException{
   try{check(new JSONObject(h.config).getJSONObject("pending").getString("phase").equals("PREPARED"),"durable pending BEFORE transmission");}catch(JSONException e){throw new AssertionError(e);}
   writes++;
   for(Device d:devices)if(d.id==id&&d.baud==baud){if(!noEffect){if(register==256)d.nextId=value;else d.nextBaud=new int[]{2400,4800,9600}[value];}if(timeout)throw new java.io.IOException("ECHO_TIMEOUT");return SensorProtocol.setting(id,register,value);}
   throw new java.io.IOException("missing device");
  }
  public void close(){}
 }
 static JSONObject state(SensorController c)throws Exception{return new JSONObject(c.snapshot());}
 static void idle(SensorController c)throws Exception{until(()->!state(c).optBoolean("searching"));}
 static void configure(SensorController c,int h,int id,int baud)throws Exception{c.configure(new JSONObject().put("house",h).put("id",id).put("baud",baud).toString());until(()->state(c).getJSONObject("config").optInt("id")==id&&state(c).getJSONObject("config").optInt("baud")==baud);}
 public static void main(String[] args)throws Exception{
  check(SensorProtocol.hex(SensorProtocol.request(1,3,"h0t1")).equals("01 03 00 00 00 02 C4 0B"),"read CRC");
  check(SensorProtocol.hex(SensorProtocol.setting(1,256,2)).equals("01 06 01 00 00 02 09 F7"),"supplier ID vector");
  check(SensorProtocol.hex(SensorProtocol.setting(1,257,2)).equals("01 06 01 01 00 02 58 37"),"supplier baud vector");
  for(int fc:new int[]{3,4})for(int id:new int[]{1,17,247}){
   SensorProtocol.Reading r=SensorProtocol.parse(response(id,fc,563,0xff9b),id,fc,"h0t1");check(r.temperature==-10.1&&r.humidity==56.3,"signed scale/order");
   r=SensorProtocol.parse(response(id,fc,251,563),id,fc,"t0h1");check(r.temperature==25.1&&r.humidity==56.3,"legacy profile preserved");
  }
  byte[] bad=response(1,3,563,251);bad[4]^=1;denied(bad,1,3,"h0t1");
  denied(new byte[]{1,3,4,0,(byte)0x92,(byte)0xff,(byte)0x9b,0x5a,0x3d},1,3,"h0t1");
  check(SensorProtocol.parse(new byte[]{1,3,4,2,(byte)0x92,(byte)0xff,(byte)0x9b,0x5a,0x3d},1,3,"h0t1").humidity==65.8,"corrected supplier RH example");
  denied(response(2,3,563,251),1,3,"h0t1");denied(response(1,4,563,251),1,3,"h0t1");denied(new byte[0],1,3,"h0t1");denied(response(1,3,1001,251),1,3,"h0t1");
  for(int[] v:new int[][]{{0,256,1},{1,2,1},{1,256,0},{1,256,248},{1,257,3}})try{SensorProtocol.setting(v[0],v[1],v[2]);throw new AssertionError("bad setting allowed");}catch(IllegalArgumentException expected){checks++;}
  byte[] q=SensorProtocol.setting(1,256,5);SensorProtocol.echo(q,q);checks++;q=q.clone();q[4]=1;try{SensorProtocol.echo(q,SensorProtocol.setting(1,256,5));throw new AssertionError();}catch(java.io.IOException expected){checks++;}
  Host h=new Host();Port p=new Port(h);for(int id=1;id<=5;id++)p.devices.add(new Device(id));SensorController c=new SensorController(h,p,"{}");final AtomicReference<SensorController> active=new AtomicReference<>(c);
  try{
   until(()->h.received[4].get()>0);for(int i=0;i<5;i++)check(h.t[i]==(201+i)/10.0,"ID maps to matching house "+(i+1));
   c.configure("{\"house\":3,\"id\":5}");c.selectHouse(3);until(()->state(active.get()).optInt("selected")==3);check(state(active.get()).getJSONObject("config").getInt("id")==3,"normal map cannot remap ID5 to house3");
   c.beginSetup(5);until(()->state(active.get()).optInt("setup")==5);p.devices.clear();Device d=new Device(1);p.devices.add(d);
   until(()->state(active.get()).optBoolean("connected"));int count=h.received[4].get();Thread.sleep(1200);check(h.received[4].get()==count,"setup preview never enters house5 history");
   c.changeId(5,false);idle(c);check(p.writes==0,"single-device acknowledgment required");
   c.changeId(4,true);idle(c);check(p.writes==0,"ID must equal selected house");
   Device collision=new Device(5);p.devices.add(collision);c.changeId(5,true);idle(c);check(p.writes==0,"target occupancy blocked");p.devices.remove(collision);
   h.failSave=true;c.changeId(5,true);idle(c);check(p.writes==0,"failed durable commit prevents transmission");h.failSave=false;
   c.changeId(5,true);until(()->!state(active.get()).isNull("pending"));idle(c);check(p.writes==1,"single write");check(state(active.get()).getJSONObject("pending").getString("phase").equals("ACKNOWLEDGED"),"echo is pending, not applied");
   c.changeId(5,true);idle(c);check(p.writes==1,"pending suppresses repeated writes");
   c.verifyChange();idle(c);check(!state(active.get()).isNull("pending")&&state(active.get()).optBoolean("oldConfirmed"),"before power-cycle only old settings respond");check(p.writes==1,"verification does not write");
   d.power();c.verifyChange();idle(c);check(state(active.get()).isNull("pending")&&state(active.get()).getJSONObject("config").getInt("id")==5,"new ID readback confirmed");
   until(()->state(active.get()).optInt("setup")==0);until(()->h.received[4].get()>count);check(h.t[4]==20.5,"ID5 recorded to house5");check(!state(active.get()).getJSONArray("houses").getJSONObject(0).optBoolean("connected"),"house1 not populated by ID5");
   c.editSetup(5);until(()->state(active.get()).optInt("setup")==5);c.changeBaud(4800,true);idle(c);check(p.writes==2,"baud write exactly once");d.power();c.verifyChange();idle(c);check(state(active.get()).getJSONObject("config").getInt("baud")==4800,"new baud confirmed");until(()->state(active.get()).optInt("setup")==0);
   c.beginSetup(4);until(()->state(active.get()).optInt("setup")==4);p.devices.clear();Device u=new Device(1);p.devices.add(u);p.timeout=true;c.changeId(4,true);idle(c);check(state(active.get()).getJSONObject("pending").getString("phase").equals("UNKNOWN"),"timeout retained as unknown");int writes=p.writes;String saved=h.config;c.close();
   c=new SensorController(h,p,saved);active.set(c);final SensorController resumed=c;Thread.sleep(1100);check(p.writes==writes&&!state(active.get()).isNull("pending"),"restart never repeats unknown write");u.power();p.timeout=false;c.verifyChange();idle(c);check(state(active.get()).isNull("pending"),"restart pending readback recovers");
   c.leaveSetup();until(()->state(resumed).optInt("setup")==0);
   c.beginSetup(2);until(()->state(resumed).optInt("setup")==2);p.devices.clear();p.devices.add(new Device(1));p.noEffect=true;c.changeId(2,true);idle(c);c.verifyChange();idle(c);check(state(active.get()).optBoolean("oldConfirmed"),"failed new setting recognizes old response");c.restoreOld();until(()->state(resumed).isNull("pending"));check(p.writes==writes+1,"restore performs no extra write");c.finishSetup();Thread.sleep(80);check(state(active.get()).optInt("setup")==2,"cannot register ID1 to house2");
   p.delay=20;c.search();Thread.sleep(60);c.cancelSearch();idle(c);check(p.writes==writes+1,"search/cancel read only");
  }finally{c.close();}
  Host id2Host=new Host();Port id2Port=new Port(id2Host);Device device=new Device(1);id2Port.devices.add(device);
  SensorController id2=new SensorController(id2Host,id2Port,"{}");
  try{
   id2.beginSetup(2);until(()->state(id2).optInt("setup")==2);id2.changeId(2,true);idle(id2);
   check(!state(id2).isNull("pending")&&id2Host.received[1].get()==0,"ID echo alone cannot show house2 measurement");
   device.power();until(()->state(id2).optInt("setup")==0&&state(id2).isNull("pending"));until(()->id2Host.received[1].get()>0);
   check(id2Host.t[1]==20.2&&id2Port.writes==1,"ID2 auto-registers actual house2 without extra registration click or write");
   Thread.sleep(1200);check(state(id2).optString("tx").startsWith("02 03")&&!state(id2).optString("rx").isEmpty()&&state(id2).optString("error").isEmpty(),"selected ID2 diagnostics independent of missing ID5");
   JSONObject legacy=new JSONObject(id2Host.config).put("schema",2).put("setup",2);id2.close();
   SensorController restored=new SensorController(id2Host,id2Port,legacy.toString());try{until(()->state(restored).optInt("setup")==0);check(id2Port.writes==1,"V5.10 registration wait recovers with reads only");}finally{restored.close();}
  }finally{id2.close();}
  System.out.println("PASS: "+checks+" sensor checks: supplied CRC vectors, fixed ID1..5 routing, registration isolation, collision guards, durable single writes, power-cycle/readback, timeout/restart recovery, no blind retries, baud changes and cancellation");
 }
}
