package com.energyblue.househmi;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class SensorTest {
 static int checks;
 static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
 static byte[] response(int id,int fc,int a,int b){byte[] r={(byte)id,(byte)fc,4,(byte)(a>>8),(byte)a,(byte)(b>>8),(byte)b,0,0};int crc=SensorProtocol.crc(r,7);r[7]=(byte)crc;r[8]=(byte)(crc>>8);return r;}
 static void denied(byte[] r,int id,int fc,String map){try{SensorProtocol.parse(r,id,fc,map);throw new AssertionError("invalid response accepted");}catch(java.io.IOException expected){checks++;}}
 interface Condition{boolean ok()throws Exception;}
 static void until(Condition c)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!c.ok()){if(System.nanoTime()>end)throw new AssertionError("async deadline");Thread.sleep(10);}checks++;}
 static final class Host implements SensorController.Host {
  volatile String config,json,plc="/dev/ttyS5";AtomicInteger received=new AtomicInteger(),invalidated=new AtomicInteger();
  public String plcPath(){return plc;}public void persist(String s){config=s;}public void emit(String s){json=s;}public void reading(double t,double h,long at){received.incrementAndGet();}public void invalidate(){invalidated.incrementAndGet();}
 }
 static final class Port implements SensorController.Port {
  volatile int target=1,opens,closes;volatile boolean fail=false;volatile long delay=0;
  public String open(String p,int b){opens++;return "OPEN_OK";}
  public byte[] read(int id,int fc,String map,int timeout)throws java.io.IOException{if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("interrupted");}if(fail||id!=target)throw new java.io.IOException("TIMEOUT");return response(id,fc,563,251);}
  public void close(){closes++;}
 }
 public static void main(String[] args)throws Exception{
  check(SensorProtocol.hex(SensorProtocol.request(1,3,"h0t1")).equals("01 03 00 00 00 02 C4 0B"),"manufacturer read CRC");
  for(int fc:new int[]{3,4})for(int id:new int[]{1,17,247}){
   SensorProtocol.Reading r=SensorProtocol.parse(response(id,fc,563,0xff9b),id,fc,"h0t1");check(r.temperature==-10.1&&r.humidity==56.3,"signed scale/order");
   r=SensorProtocol.parse(response(id,fc,251,563),id,fc,"t0h1");check(r.temperature==25.1&&r.humidity==56.3,"T0H1");
  }
  byte[] bad=response(1,3,563,251);bad[4]^=1;denied(bad,1,3,"h0t1");
  denied(response(2,3,563,251),1,3,"h0t1");denied(response(1,4,563,251),1,3,"h0t1");denied(new byte[0],1,3,"h0t1");
  denied(response(1,3,1001,251),1,3,"h0t1");denied(response(1,3,500,801),1,3,"h0t1");denied(response(1,3,500,0xfe6f),1,3,"h0t1");
  for(int id:new int[]{0,248,255})try{SensorProtocol.request(id,3,"h0t1");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  for(int fc:new int[]{5,6,16})try{SensorProtocol.request(1,fc,"h0t1");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  Host h=new Host();Port p=new Port();SensorController c=new SensorController(h,p,"{}");
  try{
   until(()->new JSONObject(c.snapshot()).optBoolean("connected"));
   check(h.received.get()==0,"unconfirmed profile must not enter history");c.confirm();until(()->h.received.get()==1);
   check(new JSONObject(h.config).optBoolean("verified"),"confirmation persisted");
   c.configure("{\"path\":\"/dev/ttyS5\"}");check(h.json.contains("차단"),"PLC collision rejected");
   c.configure("{\"path\":\"/dev/ttyS6\",\"id\":2,\"verified\":true}");until(()->!new JSONObject(c.snapshot()).optBoolean("connected"));
   check(!new JSONObject(h.config).optBoolean("verified"),"reconfigure always invalidates mapping confirmation");
   int before=h.received.get();c.confirm();Thread.sleep(80);check(h.received.get()==before,"stale reading cannot be confirmed for new ID");
   p.target=7;c.search();until(()->new JSONObject(h.config).optInt("id")==7);
   check(!new JSONObject(h.config).optBoolean("verified"),"discovery is not physical mapping verification");
   until(()->new JSONObject(c.snapshot()).optBoolean("connected"));c.confirm();until(()->h.received.get()>before);
   p.fail=true;until(()->!new JSONObject(c.snapshot()).optBoolean("connected"));
   check(new JSONObject(c.snapshot()).isNull("temperature")&&h.invalidated.get()>0,"loss clears live value");
   p.delay=100;c.search();Thread.sleep(60);c.cancelSearch();until(()->!new JSONObject(c.snapshot()).optBoolean("searching"));
   c.stop();until(()->!new JSONObject(h.config).optBoolean("enabled"));
  }finally{c.close();}
  System.out.println("PASS: "+checks+" sensor CRC, signed scale, protocol limits, async discovery, cancellation, confirmation and stale-data checks");
 }
}
