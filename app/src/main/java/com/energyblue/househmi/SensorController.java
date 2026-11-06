package com.energyblue.househmi;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** COM6 commissioning only; no control-output authority, no ID writes. */
public final class SensorController implements AutoCloseable {
 public interface Host {
  String plcPath();
  void persist(String config);
  void emit(String json);
  void reading(double temperature,double humidity,long at);
  void invalidate();
 }
 public interface Port {
  String open(String path,int baud);
  byte[] read(int id,int fc,String map,int timeout)throws IOException;
  void close();
 }
 private final Host host;
 private final Port port;
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private final AtomicLong generation=new AtomicLong();
 private volatile JSONObject config,state=new JSONObject();
 private volatile String openedPath="";
 private int openedBaud=0;
 private volatile boolean closed=false,searching=false;
 private SensorProtocol.Reading last;
 private long lastMono=0,lastAt=0,lastGeneration=-1;
 private String tx="",rx="",error="";
 public SensorController(Host host,Port port,String saved){
  this.host=host;this.port=port;
  try{config=normalize(new JSONObject(saved==null?"{}":saved));}catch(Exception ex){try{config=normalize(new JSONObject());}catch(Exception fatal){throw new IllegalArgumentException(fatal);}}
  publish("COM6 읽기 준비 · 기본 경로는 현장 확인 필요");
  worker.scheduleWithFixedDelay(()->{try{poll();}catch(Exception ex){fail("센서 처리 오류: "+ex.getMessage());}},1,3,TimeUnit.SECONDS);
 }
 static JSONObject normalize(JSONObject in)throws Exception{
  String path=in.optString("path","/dev/ttyS6"),map=in.optString("map","h0t1");
  int baud=in.optInt("baud",9600),id=in.optInt("id",1),fc=in.optInt("fc",3);
  if(!path.matches("/dev/tty[A-Za-z0-9._-]{1,64}")||!SensorProtocol.validMap(map)||(baud!=4800&&baud!=9600)||id<1||id>247||(fc!=3&&fc!=4))throw new IllegalArgumentException("센서 경로/ID/통신 설정 오류");
  return new JSONObject().put("path",path).put("baud",baud).put("id",id).put("fc",fc).put("map",map).put("enabled",in.optBoolean("enabled",true)).put("verified",in.optBoolean("verified",false));
 }
 private static long mono(){return System.nanoTime()/1000000;}
 public boolean reservesPath(String path){return !closed&&(SensorSerial.samePath(path,config.optString("path"))||(!openedPath.isEmpty()&&SensorSerial.samePath(path,openedPath)));}
 public void configure(String raw){
  if(closed)return;
  try{
   if(raw==null||raw.length()>2000)throw new IllegalArgumentException("설정 크기 오류");
   JSONObject next=normalize(new JSONObject(raw));next.put("verified",false);
   synchronized(SensorSerial.class){if(SensorSerial.samePath(next.optString("path"),host.plcPath()))throw new IllegalArgumentException("COM5 PLC와 같은 장치 경로는 사용할 수 없습니다.");config=next;generation.incrementAndGet();}
   host.persist(next.toString());host.invalidate();
   worker.execute(()->{last=null;lastAt=0;lastMono=0;port.close();openedPath="";openedBaud=0;publish("센서 설정 적용 · 새 응답 확인 중");});
  }catch(Exception ex){publish("설정 차단: "+ex.getMessage());}
 }
 public void stop(){if(closed)return;try{JSONObject next=new JSONObject(config.toString()).put("enabled",false);configure(next.toString());}catch(Exception ignored){}}
 public void cancelSearch(){synchronized(SensorSerial.class){generation.incrementAndGet();host.invalidate();}}
 public void search(){
  if(closed||searching)return;searching=true;long token=generation.incrementAndGet();host.invalidate();
  worker.execute(()->{
   last=null;lastAt=0;lastMono=0;JSONObject c=config;
   try{
    if(!c.optBoolean("enabled")){publish("먼저 연결·설정 적용을 누르세요.");return;}
    String map=c.optString("map");int fc=c.optInt("fc"),first=c.optInt("id");
    for(int baud:new int[]{c.optInt("baud"),c.optInt("baud")==9600?4800:9600}){
     if(cancelled(token))return;ensureOpen(c.optString("path"),baud);
     for(int n=0;n<247;n++){
      if(cancelled(token))return;int id=n==0?first:(n<=first-1?n:n+1);
      tx=SensorProtocol.hex(SensorProtocol.request(id,fc,map));rx="";
      try{
       byte[] bytes=port.read(id,fc,map,180);rx=SensorProtocol.hex(bytes);
       SensorProtocol.Reading reading=SensorProtocol.parse(bytes,id,fc,map);
       if(cancelled(token))return;
       JSONObject found=new JSONObject(c.toString()).put("id",id).put("baud",baud).put("verified",false);
       synchronized(SensorSerial.class){if(cancelled(token))return;config=found;host.persist(found.toString());accept(reading,token);}
       publish("센서 응답 발견 · OLED와 온도·습도 일치 확인 필요");return;
      }catch(IOException ex){error=ex.getMessage();if(n%10==0||!rx.isEmpty())publish("ID 검색 중 · "+baud+"bps / ID "+id+" · "+error);}
      // Bounded inter-frame silence, off UI; cancel visible within one timeout.
      if(Thread.currentThread().isInterrupted())return;
      java.util.concurrent.locks.LockSupport.parkNanos(10000000L);
     }
    }
    fail("ID1~247 / 4800·9600 검색 응답 없음 · 경로/전원/A·B/기능코드 확인");
   }catch(Exception ex){fail("센서 검색 실패: "+ex.getMessage());}
   finally{searching=false;if(!closed)publish(cancelled(token)?"센서 검색 취소 · 새 설정으로 감시":"검색 종료 · 선택 ID 감시");}
  });
 }
 private boolean cancelled(long token){return closed||generation.get()!=token;}
 private void ensureOpen(String path,int baud)throws IOException{
  synchronized(SensorSerial.class){
   if(SensorSerial.samePath(path,host.plcPath()))throw new IOException("COM5 PLC 경로 중복 차단");
   if(path.equals(openedPath)&&baud==openedBaud)return;
   port.close();openedPath="";openedBaud=0;
   String result=port.open(path,baud);if(!"OPEN_OK".equals(result))throw new IOException(result);
   openedPath=path;openedBaud=baud;
  }
 }
 private void poll(){
  if(closed||searching)return;JSONObject c=config;long token=generation.get();
  if(!c.optBoolean("enabled")){last=null;lastAt=0;lastMono=0;host.invalidate();publish("COM6 감시 중지");return;}
  try{
   ensureOpen(c.optString("path"),c.optInt("baud"));
   tx=SensorProtocol.hex(SensorProtocol.request(c.optInt("id"),c.optInt("fc"),c.optString("map")));rx="";
   byte[] bytes=port.read(c.optInt("id"),c.optInt("fc"),c.optString("map"),700);rx=SensorProtocol.hex(bytes);
   SensorProtocol.Reading reading=SensorProtocol.parse(bytes,c.optInt("id"),c.optInt("fc"),c.optString("map"));
   if(cancelled(token))return;accept(reading,token);
   publish(c.optBoolean("verified")?"1번동 내부 온습도 수신 정상":"시험 수신 · 순서/배율을 OLED와 대조한 뒤 일치 확인을 누르세요.");
  }catch(Exception ex){if(!cancelled(token)){port.close();openedPath="";openedBaud=0;fail(ex.getMessage());}}
 }
 private void accept(SensorProtocol.Reading reading,long token){synchronized(SensorSerial.class){if(cancelled(token))return;last=reading;lastAt=System.currentTimeMillis();lastMono=mono();lastGeneration=token;error="";if(config.optBoolean("verified"))host.reading(reading.temperature,reading.humidity,lastAt);}}
 public void confirm(){if(closed)return;worker.execute(()->{
  try{
   if(searching||last==null||mono()-lastMono>10000||lastGeneration!=generation.get())throw new IOException("현재 설정의 정상 응답을 먼저 확인하세요.");
   synchronized(SensorSerial.class){
    if(lastGeneration!=generation.get()||closed)throw new IOException("설정이 변경되었습니다. 새 응답을 확인하세요.");
    config=new JSONObject(config.toString()).put("verified",true);host.persist(config.toString());host.reading(last.temperature,last.humidity,lastAt);
   }
   publish("OLED 일치 확인됨 · 1번동 현재값·이력 연결");
  }catch(Exception ex){publish("일치 확인 차단: "+ex.getMessage());}
 });}
 private void fail(String message){last=null;lastAt=0;lastMono=0;error=message;host.invalidate();publish("COM6 미수신 · "+message);}
 private void publish(String message){
  if(closed)return;
  try{
   boolean fresh=last!=null&&mono()-lastMono<=10000&&lastGeneration==generation.get();
   JSONObject s=new JSONObject().put("config",new JSONObject(config.toString())).put("connected",fresh).put("searching",searching)
    .put("temperature",fresh?last.temperature:JSONObject.NULL).put("humidity",fresh?last.humidity:JSONObject.NULL).put("receivedAt",fresh?lastAt:0)
    .put("tx",tx).put("rx",rx).put("error",error).put("message",message);
   state=s;host.emit(s.toString());
  }catch(Exception ignored){}
 }
 public String snapshot(){return state.toString();}
 public static JSONArray devices(String plcPath){JSONArray result=new JSONArray();File[] list=new File("/dev").listFiles();if(list!=null){Arrays.sort(list,(a,b)->a.getName().compareTo(b.getName()));for(File f:list)if(f.getName().matches("tty(?:S|USB|ACM|AMA|HS)[A-Za-z0-9._-]*")&&!SensorSerial.samePath(f.getAbsolutePath(),plcPath))result.put(f.getAbsolutePath());}return result;}
 @Override public void close(){if(closed)return;closed=true;generation.incrementAndGet();host.invalidate();worker.shutdownNow();new Thread(()->port.close(),"COM6-close").start();}
}
