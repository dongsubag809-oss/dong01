package com.energyblue.househmi;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fixed ID1..5 -> house1..5. Supplier settings are sent once, then power-cycle/readback. */
public final class SensorController implements AutoCloseable {
 public interface Host {
  String plcPath();
  boolean persist(String config);
  void emit(String json);
  void reading(int house,double temperature,double humidity,long at);
  void invalidate(int house);
 }
 public interface Port {
  String open(String path,int baud);
  byte[] read(int id,int fc,String map,int timeout)throws IOException;
  default byte[] setting(int id,int register,int value)throws IOException{throw new IOException("설정 쓰기 미지원");}
  void close();
 }
 private final Host host;private final Port port;
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
 private final AtomicLong generation=new AtomicLong();
 private final AtomicBoolean busy=new AtomicBoolean();
 private volatile JSONObject[] configs=new JSONObject[5];
 private volatile JSONObject pending;
 private volatile JSONObject state=new JSONObject();
 private volatile String openedPath="";
 private volatile int selected=1,setup=0;
 private int openedBaud;private volatile boolean closed=false;
 private final SensorProtocol.Reading[] readings=new SensorProtocol.Reading[5];
 private final long[] receivedAt=new long[5],receivedMono=new long[5];
 private final String[] statuses={"수신 대기","수신 대기","수신 대기","수신 대기","수신 대기"};
 private String tx="",rx="",error="",message="COM6 ID1~5 / 1~5동 수신 준비";
 private boolean oldConfirmed=false;
 public SensorController(Host host,Port port,String saved){
  this.host=host;this.port=port;
  try{
   JSONObject root=new JSONObject(saved==null?"{}":saved);JSONArray list=root.optJSONArray("sensors");
   String path=list==null?root.optString("path","/dev/ttyS6"):list.getJSONObject(0).optString("path","/dev/ttyS6");
   int baud=list==null?root.optInt("baud",9600):list.getJSONObject(0).optInt("baud",9600);
   for(int h=1;h<=5;h++){
    JSONObject c=list!=null&&list.length()==5?list.getJSONObject(h-1):new JSONObject().put("path",path).put("baud",baud);
    if(h==1&&list==null&&root.has("path"))c=root;
    configs[h-1]=normalize(c,h);configs[h-1].put("id",h);
   }
   selected=Math.max(1,Math.min(5,root.optInt("selected",1)));setup=root.optInt("setup",0);
   if(setup<0||setup>5)setup=0;
   if(list!=null&&setup>0)configs[setup-1]=normalize(list.getJSONObject(setup-1),setup);
   pending=root.optJSONObject("pending");
   if(pending!=null){validatePending(pending);setup=pending.getInt("house");selected=setup;message="이전 설정 변경 보류 · 센서 전원 재인가 후 변경 결과 확인 필요";}
  }catch(Exception ex){
   for(int h=1;h<=5;h++)try{configs[h-1]=normalize(new JSONObject(),h);}catch(Exception fatal){throw new IllegalArgumentException(fatal);}
   setup=0;pending=null;message="저장 설정 형식 오류 · ID1~5 기본 읽기 설정으로 표시: "+ex.getMessage();
  }
  publish();
  worker.scheduleWithFixedDelay(()->{try{poll();}catch(Exception ex){notice("센서 처리 오류: "+ex.getMessage());}},1,1,TimeUnit.SECONDS);
 }
 static JSONObject normalize(JSONObject in,int house)throws Exception{
  String path=in.optString("path","/dev/ttyS6"),map=in.optString("map","h0t1");
  int baud=in.optInt("baud",9600),id=in.optInt("id",house),fc=in.optInt("fc",3);
  if(house<1||house>5||!path.matches("/dev/tty[A-Za-z0-9._-]{1,64}")||!SensorProtocol.validMap(map)||id<1||id>247||(fc!=3&&fc!=4))throw new IllegalArgumentException("센서 경로/ID/통신 설정 오류");
  SensorProtocol.baudCode(baud);
  return new JSONObject().put("house",house).put("path",path).put("baud",baud).put("id",id).put("fc",fc).put("map",map).put("enabled",in.optBoolean("enabled",true)).put("verified",(fc==3&&map.equals("h0t1"))||in.optBoolean("verified",false));
 }
 private static void validatePending(JSONObject p)throws Exception{
  int house=p.getInt("house"),reg=p.getInt("register"),value=p.getInt("value");
  JSONObject old=normalize(p.getJSONObject("old"),house),next=normalize(p.getJSONObject("next"),house);
  SensorProtocol.setting(old.getInt("id"),reg,value);
  if(!old.getString("path").equals(next.getString("path"))||next.getInt("id")!=house||!old.getString("map").equals("h0t1")||old.getInt("fc")!=3)throw new IllegalArgumentException("보류 설정 경계 오류");
  if(reg==0x100&&(next.getInt("id")!=value||old.getInt("baud")!=next.getInt("baud")))throw new IllegalArgumentException("보류 ID 오류");
  if(reg==0x101&&(old.getInt("id")!=next.getInt("id")||SensorProtocol.baudCode(next.getInt("baud"))!=value))throw new IllegalArgumentException("보류 속도 오류");
 }
 private static long mono(){return System.nanoTime()/1000000;}
 private JSONObject[] copyConfigs()throws Exception{JSONObject[] c=new JSONObject[5];for(int i=0;i<5;i++)c[i]=new JSONObject(configs[i].toString());return c;}
 private boolean save(JSONObject[] next,JSONObject change,int setupHouse)throws Exception{
  JSONArray a=new JSONArray();for(JSONObject c:next)a.put(c);
  JSONObject root=new JSONObject().put("schema",2).put("selected",selected).put("setup",setupHouse).put("sensors",a).put("pending",change==null?JSONObject.NULL:change);
  // Synchronous durable commit occurs only on this worker, before any FC06 transmission.
  if(!host.persist(root.toString())){notice("설정 저장 실패 · 추가 작업 중지, 현재 보류 상태 유지");return false;}
  synchronized(SensorSerial.class){configs=next;pending=change;setup=setupHouse;}
  return true;
 }
 private void submit(Runnable task){if(closed)return;worker.execute(()->{if(!closed)task.run();});}
 public void selectHouse(int house){if(house<1||house>5||closed)return;submit(()->{selected=house;publish();});}
 public boolean reservesPath(String path){if(closed)return false;for(JSONObject c:configs)if(c!=null&&SensorSerial.samePath(path,c.optString("path")))return true;return !openedPath.isEmpty()&&SensorSerial.samePath(path,openedPath);}
 public void configure(String raw){
  if(closed)return;generation.incrementAndGet();
  submit(()->{try{
   if(pending!=null||busy.get())throw new IOException("변경 결과 확인/검색 종료 후 설정하세요.");
   if(raw==null||raw.length()>3000)throw new IOException("설정 크기 오류");
   JSONObject in=new JSONObject(raw);int house=in.optInt("house",selected);JSONObject c=normalize(in,house);
   if(setup>0&&setup!=house)throw new IOException("신규 설정 중인 동을 먼저 완료하세요.");
   if(setup==0)c.put("id",house); // Fixed identity; editing cannot remap another ID to a house.
   synchronized(SensorSerial.class){
    if(SensorSerial.samePath(c.optString("path"),host.plcPath()))throw new IOException("COM5 PLC 경로 중복 차단");
    JSONObject[] next=copyConfigs();next[house-1]=c;if(!save(next,null,setup))return;
   }
   selected=house;clear(house);closePort();notice("수신 설정 저장 · ID와 동 번호 고정 연결");
  }catch(Exception ex){notice("설정 차단: "+ex.getMessage());}});
 }
 public void stop(){if(closed)return;submit(()->{try{if(pending!=null||busy.get())throw new IOException("변경 확인/검색 종료 후 중지하세요.");JSONObject[] c=copyConfigs();c[selected-1].put("enabled",false);if(save(c,null,setup)){clear(selected);notice("선택 동 감시 중지");}}catch(Exception ex){notice(ex.getMessage());}});}
 public void beginSetup(int house){if(house<1||house>5||closed)return;generation.incrementAndGet();submit(()->{
  try{
   if(pending!=null||busy.get()||setup>0)throw new IOException("진행 중인 센서 설정을 먼저 완료하세요.");
   JSONObject[] next=copyConfigs();next[house-1].put("id",1).put("baud",9600).put("fc",3).put("map","h0t1").put("verified",true);
   selected=house;if(!save(next,null,house))return;clearAll();closePort();notice("신규 "+house+"동 설정 · 다른 센서를 분리하고 새 센서 한 대만 연결하세요. 임시 ID 값은 동 이력에 기록하지 않습니다.");
  }catch(Exception ex){notice("신규 설정 차단: "+ex.getMessage());}
 });}
 public void finishSetup(){submit(()->{try{
  if(pending!=null||busy.get()||setup==0)throw new IOException("보류 변경을 먼저 확인하세요.");int h=setup;JSONObject c=configs[h-1];
  if(c.optInt("id")!=h||!fresh(h))throw new IOException("ID를 동 번호로 설정하고 새 ID의 실제 응답을 먼저 확인하세요.");
  JSONObject[] next=copyConfigs();next[h-1].put("enabled",true);
  if(!save(next,null,0))return;notice("ID "+h+" → "+h+"동 등록 완료 · 전체 감시 재개");
 }catch(Exception ex){notice("등록 차단: "+ex.getMessage());}});}
 public void leaveSetup(){submit(()->{try{
  if(pending!=null||busy.get())throw new IOException("이미 전송된 설정의 새/기존 응답을 먼저 확인하세요.");if(setup==0)return;
  JSONObject[] next=copyConfigs();int h=setup;next[h-1].put("id",h);if(save(next,null,0)){clearAll();notice("신규 설정 종료 · 고정 ID1~5 수신 재개");}
 }catch(Exception ex){notice("설정 종료 차단: "+ex.getMessage());}});}
 public void cancelSearch(){generation.incrementAndGet();}
 public void search(){
  if(closed||!busy.compareAndSet(false,true))return;long token=generation.incrementAndGet();
  submit(()->{try{
   if(pending!=null||setup==0)throw new IOException("신규 센서 1대 설정을 시작한 뒤 검색하세요. 일반 감시는 고정 ID1~5입니다.");
   clearAll();int house=setup;JSONObject c=configs[house-1];int first=c.optInt("id");
   Set<Integer> rates=new LinkedHashSet<>();rates.add(c.optInt("baud"));rates.add(9600);rates.add(4800);rates.add(2400);
   for(int baud:rates){
    if(cancelled(token))return;ensureOpen(c.optString("path"),baud);
    for(int n=0;n<247;n++){
     if(cancelled(token))return;int id=n==0?first:(n<first?n:n+1);
     try{
      SensorProtocol.Reading r=read(c,id,baud,180);
      if(cancelled(token))return;JSONObject[] next=copyConfigs();next[house-1].put("id",id).put("baud",baud);
      if(!save(next,null,setup))return;accept(house,r);notice("센서 ID "+id+" / "+baud+"bps 발견 · 설정 후 ID "+house+"로 등록하세요.");return;
     }catch(IOException ex){error=ex.getMessage();if(n%10==0)notice("단일 센서 검색 중 · "+baud+"bps / ID "+id);}
     java.util.concurrent.locks.LockSupport.parkNanos(20000000L);
    }
   }
   notice("센서 응답 없음 · 전원/장치 경로/A·B 확인");
  }catch(Exception ex){notice("검색 차단/오류: "+ex.getMessage());}
  finally{busy.set(false);if(cancelled(token))notice("검색 취소");else publish();}});
 }
 private boolean cancelled(long token){return closed||generation.get()!=token;}
 private void ensureOpen(String path,int baud)throws IOException{
  synchronized(SensorSerial.class){
   if(closed)throw new IOException("앱 종료");if(SensorSerial.samePath(path,host.plcPath()))throw new IOException("COM5 PLC 경로 중복 차단");
   if(path.equals(openedPath)&&baud==openedBaud)return;closePort();String result=port.open(path,baud);
   if(!"OPEN_OK".equals(result))throw new IOException(result);openedPath=path;openedBaud=baud;
  }
 }
 private void closePort(){port.close();openedPath="";openedBaud=0;}
 private SensorProtocol.Reading read(JSONObject c,int id,int baud,int timeout)throws IOException{
  ensureOpen(c.optString("path"),baud);tx=SensorProtocol.hex(SensorProtocol.request(id,c.optInt("fc"),c.optString("map")));rx="";
  byte[] b=port.read(id,c.optInt("fc"),c.optString("map"),timeout);rx=SensorProtocol.hex(b);
  return SensorProtocol.parse(b,id,c.optInt("fc"),c.optString("map"));
 }
 private void poll(){
  if(closed||busy.get()||pending!=null)return;long token=generation.get();
  for(int h=1;h<=5;h++){
   if(cancelled(token))return;JSONObject c=configs[h-1];
   if(setup>0&&setup!=h)continue;if(setup==0&&!c.optBoolean("enabled")){clear(h);continue;}
   try{SensorProtocol.Reading r=read(c,c.optInt("id"),c.optInt("baud"),700);if(cancelled(token))return;accept(h,r);}
   catch(Exception ex){if(cancelled(token))return;clear(h);statuses[h-1]="미수신 · "+ex.getMessage();error=ex.getMessage();closePort();}
  }
  publish();
 }
 private boolean fresh(int h){return readings[h-1]!=null&&mono()-receivedMono[h-1]<=12000;}
 private void accept(int h,SensorProtocol.Reading r){if(closed)return;readings[h-1]=r;receivedAt[h-1]=System.currentTimeMillis();receivedMono[h-1]=mono();statuses[h-1]=setup>0?"신규 설정 임시 수신 · 미등록":"실수신";error="";
  JSONObject c=configs[h-1];if(setup==0&&pending==null&&c.optInt("id")==h&&c.optBoolean("verified"))host.reading(h,r.temperature,r.humidity,receivedAt[h-1]);
 }
 private void clear(int h){readings[h-1]=null;receivedAt[h-1]=0;receivedMono[h-1]=0;host.invalidate(h);}
 private void clearAll(){for(int h=1;h<=5;h++)clear(h);}
 public void confirm(){submit(()->{try{if(pending!=null||busy.get()||!fresh(selected))throw new IOException("정상 수신을 먼저 확인하세요.");JSONObject[] c=copyConfigs();c[selected-1].put("verified",true);if(save(c,pending,setup))notice("OLED 수신값 확인됨");}catch(Exception ex){notice(ex.getMessage());}});}
 public void changeId(int target,boolean oneDevice){change(0x100,target,oneDevice);}
 public void changeBaud(int baud,boolean oneDevice){try{change(0x101,SensorProtocol.baudCode(baud),oneDevice);}catch(Exception ex){notice(ex.getMessage());}}
 private void change(int register,int value,boolean oneDevice){
  if(closed||!busy.compareAndSet(false,true))return;
  submit(()->{try{
   if(pending!=null)throw new IOException("이전 변경을 새 설정에서 확인하기 전에는 재전송할 수 없습니다.");
   if(!oneDevice||setup==0)throw new IOException("신규 설정 모드에서 센서 한 대만 연결했는지 확인하세요.");
   int h=setup;JSONObject old=new JSONObject(configs[h-1].toString());
   if(old.optInt("fc")!=3||!old.optString("map").equals("h0t1"))throw new IOException("제공 매뉴얼 FC03 습도0/온도1 프로필에서만 설정 가능합니다.");
   if(register==0x100&&value!=h)throw new IOException("ID는 대상 동 번호 "+h+"로 설정해야 합니다.");
   if(register==0x101&&old.optInt("id")!=h)throw new IOException("먼저 ID를 동 번호로 설정·재확인하세요.");
   int nextBaud=register==0x101?new int[]{2400,4800,9600}[value]:old.optInt("baud");
   if((register==0x100&&old.optInt("id")==value)||(register==0x101&&old.optInt("baud")==nextBaud))throw new IOException("현재 설정과 같습니다. 쓰지 않았습니다.");
   // Always re-read current device immediately before the write; stale UI cannot authorize FC06.
   read(old,old.optInt("id"),old.optInt("baud"),1000);
   if(register==0x100){
    byte[] probe=port.read(value,3,"h0t1",700);
    if(SensorProtocol.envelope(probe,value,3))throw new IOException("변경할 ID에서 이미 응답합니다. 중복 ID 설정 차단");
   }
   JSONObject next=new JSONObject(old.toString()).put("id",h).put("baud",nextBaud).put("verified",true);
   JSONObject change=new JSONObject().put("house",h).put("old",old).put("next",next).put("register",register).put("value",value).put("phase","PREPARED");validatePending(change);
   if(!save(copyConfigs(),change,setup))return;clearAll();oldConfirmed=false;
   byte[] request=SensorProtocol.setting(old.optInt("id"),register,value);tx=SensorProtocol.hex(request);rx="";
   try{
    // Exactly one write; never retry on timeout, exception, cancellation or restart.
    byte[] echo=port.setting(old.optInt("id"),register,value);rx=SensorProtocol.hex(echo);SensorProtocol.echo(echo,request);
    change.put("phase","ACKNOWLEDGED");message="설정 응답 수신 · 센서 전원을 껐다 켠 뒤 변경 결과 확인을 누르세요. 아직 적용 확인 전입니다.";
   }catch(Exception ex){change.put("phase","UNKNOWN");error=ex.getMessage();message="설정 적용 여부 미확인 · 재전송하지 않습니다. 전원 재인가 후 변경 결과를 확인하세요: "+error;}
   // PREPARED remains durable if this status update fails; both states require readback.
   save(copyConfigs(),change,setup);closePort();publish();
  }catch(Exception ex){notice("설정 쓰기 차단: "+ex.getMessage());}
  finally{busy.set(false);publish();}});
 }
 public void verifyChange(){
  if(closed||!busy.compareAndSet(false,true))return;submit(()->{
   try{
    if(pending==null)throw new IOException("확인할 보류 변경이 없습니다.");validatePending(pending);JSONObject next=pending.getJSONObject("next"),old=pending.getJSONObject("old");int h=pending.getInt("house");clearAll();oldConfirmed=false;
    try{
     SensorProtocol.Reading r=read(next,next.getInt("id"),next.getInt("baud"),1000);
     java.util.concurrent.locks.LockSupport.parkNanos(100000000L);
     r=read(next,next.getInt("id"),next.getInt("baud"),1000);
     JSONObject[] c=copyConfigs();c[h-1]=new JSONObject(next.toString());if(!save(c,null,h))return;
     accept(h,r);notice("변경한 ID·속도에서 2회 실제 수신 확인 · "+h+"동 등록·전체 감시 재개를 누르세요.");
    }catch(IOException ex){
     error=ex.getMessage();try{read(old,old.getInt("id"),old.getInt("baud"),1000);oldConfirmed=true;notice("새 설정 응답 없음 · 기존 설정에서 응답 확인. 전원 재인가 후 재확인하거나 기존 설정 복구를 선택하세요.");}
     catch(IOException oldError){notice("새 설정·기존 설정 모두 미수신 · 전원/배선 확인 후 다시 결과 확인. 쓰기는 재전송하지 않습니다.");}
    }
   }catch(Exception ex){notice("변경 확인 오류: "+ex.getMessage());}
   finally{busy.set(false);publish();}
  });
 }
 public void restoreOld(){submit(()->{try{
  if(pending==null||busy.get()||!oldConfirmed)throw new IOException("변경 결과 확인에서 기존 설정 응답을 먼저 확인하세요.");
  int h=pending.getInt("house");JSONObject old=pending.getJSONObject("old");SensorProtocol.Reading r=read(old,old.getInt("id"),old.getInt("baud"),1000);
  JSONObject[] c=copyConfigs();c[h-1]=new JSONObject(old.toString());if(save(c,null,h)){accept(h,r);oldConfirmed=false;notice("기존 설정의 실제 응답으로 복구 · 추가 쓰기 없음");}
 }catch(Exception ex){notice("기존 설정 복구 차단: "+ex.getMessage());}});}
 private void notice(String text){message=text;publish();}
 private void publish(){if(closed)return;try{
  JSONArray houses=new JSONArray();for(int h=1;h<=5;h++){
   boolean live=fresh(h)&&pending==null;SensorProtocol.Reading r=readings[h-1];JSONObject c=configs[h-1];
   houses.put(new JSONObject().put("house",h).put("config",new JSONObject(c.toString())).put("connected",live).put("registered",setup==0&&c.optInt("id")==h)
    .put("temperature",live?r.temperature:JSONObject.NULL).put("humidity",live?r.humidity:JSONObject.NULL).put("receivedAt",live?receivedAt[h-1]:0).put("status",statuses[h-1]));
  }
  JSONObject s=new JSONObject(houses.getJSONObject(selected-1).toString()).put("selected",selected).put("houses",houses).put("setup",setup).put("pending",pending==null?JSONObject.NULL:new JSONObject(pending.toString())).put("oldConfirmed",oldConfirmed)
   .put("searching",busy.get()).put("tx",tx).put("rx",rx).put("error",error).put("message",message);
  state=s;host.emit(s.toString());
 }catch(Exception ignored){}}
 public String snapshot(){try{return new JSONObject(state.toString()).put("searching",busy.get()).toString();}catch(Exception ex){return state.toString();}}
 public static JSONArray devices(String plcPath){JSONArray result=new JSONArray();File[] list=new File("/dev").listFiles();if(list!=null){Arrays.sort(list,(a,b)->a.getName().compareTo(b.getName()));for(File f:list)if(f.getName().matches("tty(?:S|USB|ACM|AMA|HS)[A-Za-z0-9._-]*")&&!SensorSerial.samePath(f.getAbsolutePath(),plcPath))result.put(f.getAbsolutePath());}return result;}
 @Override public void close(){if(closed)return;closed=true;generation.incrementAndGet();clearAll();worker.shutdownNow();new Thread(()->port.close(),"COM6-close").start();}
}
