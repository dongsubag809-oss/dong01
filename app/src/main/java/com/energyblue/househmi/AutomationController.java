package com.energyblue.househmi;

import android.content.Context;
import android.os.SystemClock;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** HMI-session-only scheduler. Persistent settings never confer output authority. */
public final class AutomationController implements AutoCloseable {
 public interface Host {
  boolean mayOperate();
  boolean commandBusy();
  Boolean output(int house,int slot);
  int houseOnCount(int house);
  long receivedAt(int house);
  boolean dispatch(int house,int slot,boolean on);
  default String blockReason(int house,int slot){return null;}
  void emit(String event,String message);
 }
 private final Host host;
 private final AutomationStore store;
 private final ScheduledExecutorService engine=Executors.newSingleThreadScheduledExecutor();
 private final ExecutorService historyWorker=Executors.newSingleThreadExecutor();
 private final Map<String,JSONObject> rules=new LinkedHashMap<>();
 private final Map<String,Run> runs=new HashMap<>();
 private final Set<String> paused=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
 private volatile boolean active=false,closed=false,loaded=false;
 private volatile String message="설정·기록 읽는 중";
 private long lastWall=0,lastMono=0;
 private static final class Run {
  boolean owned=false,pending=false,pendingOn=false,stopping=false;
  long started=0,lastOff=0,pendingAt=0;
  String status="자동 정지";
 }
 public AutomationController(Context context,Host host){
  this.host=host;this.store=new AutomationStore(context.getApplicationContext());
  engine.execute(()->{
   try{JSONArray stored=store.loadRules();for(int i=0;i<stored.length();i++){JSONObject r=stored.getJSONObject(i);if(AutomationLogic.validate(r)==null)rules.put(key(r),r);}loaded=true;message="설정 불러옴 · 자동운전은 별도 시작 필요";}
   catch(Exception ex){message="설정 읽기 실패: "+ex.getMessage();}
  });
  engine.scheduleWithFixedDelay(()->{try{tick();}catch(Exception ex){active=false;message="자동 판단 오류 · 운전 잠금: "+ex.getClass().getSimpleName();}},1,1,TimeUnit.SECONDS);
 }
 private static String key(JSONObject r){return AutomationLogic.key(r.optInt("house"),r.optInt("slot"));}
 private Run run(String key){Run r=runs.get(key);if(r==null){r=new Run();runs.put(key,r);}return r;}
 private void note(String text){
  message=text;
  if(!closed)historyWorker.execute(()->{try{store.event(text);}catch(Exception ignored){}});
  host.emit("autoNotice",text);
 }
 public boolean isActive(){return active;}
 public boolean mayStart(int board,int channel){
  int n=(board-1)*64+channel-1;
  return active&&!closed&&!paused.contains(AutomationLogic.key(n/24+1,n%24));
 }
 public void start(){if(closed)return;engine.execute(()->{
  if(!loaded){note("자동 설정을 아직 읽지 못했습니다.");return;}
  if(!host.mayOperate()){note("자동 시작 차단 · PLC 운전 승인과 실제 통신을 확인하세요.");return;}
  boolean enabled=false;for(JSONObject r:rules.values())if(r.optBoolean("enabled"))enabled=true;
  if(!enabled){note("자동 시작 차단 · 사용할 장비와 조건을 먼저 저장하세요.");return;}
  paused.clear();active=true;lastWall=0;lastMono=0;
  note("자동운전 활성 · 저장된 사용 장비만 판단 · 센서/리미트 미확인은 실행 차단");
 });}
 public void stop(String reason){if(closed)return;active=false;engine.execute(()->{
  for(Map.Entry<String,Run> e:runs.entrySet()){Run r=e.getValue();if(r.owned||r.pending)r.stopping=true;}
  note(reason+" · 자동 소유 출력 OFF 확인 진행, 수동 출력은 별도");
 });}
 public void override(int board,int channel){
  int absolute=(board-1)*64+channel-1,house=absolute/24+1,slot=absolute%24;
  if(!AutomationLogic.validDevice(house,slot)&&AutomationLogic.motor(slot))slot--;
  if(!AutomationLogic.validDevice(house,slot)||closed)return;
  final String key=AutomationLogic.key(house,slot);
  // This queue precedes any subsequent scheduler evaluation for this device.
  engine.execute(()->{Run r=run(key);paused.add(key);if(r.pending){r.stopping=r.owned;}else{r.owned=false;r.stopping=false;}r.status="수동 조작으로 해당 장비 자동 일시정지";});
 }
 public void outputResult(int board,int channel,String result){
  int absolute=(board-1)*64+channel-1,house=absolute/24+1,slot=absolute%24;
  if(!AutomationLogic.validDevice(house,slot)||closed)return;
  String key=AutomationLogic.key(house,slot);
  engine.execute(()->{
   Run r=run(key);if(!r.pending)return;
   if(result.equals("CONFIRMED")||result.equals("UNCHANGED")){
    r.pending=false;
    if(!r.pendingOn){r.owned=false;r.stopping=false;r.lastOff=SystemClock.elapsedRealtime();r.status="PLC OFF 실수신 확인";}
    else{r.owned=true;r.status="PLC ON 실수신 확인";}
   }else if(result.equals("DENIED")||result.equals("UNKNOWN")){
    r.pending=false;paused.add(key);r.status="자동 명령 "+result+" · 실제 출력 확인 필요";
    if(r.owned)r.stopping=true;
   }
  });
 }
 private void request(JSONObject rule,Run r,boolean on,long mono){
  if(host.commandBusy())return;
  if(host.dispatch(rule.optInt("house"),rule.optInt("slot"),on)){
   r.pending=true;r.pendingOn=on;r.pendingAt=mono;
   if(on){r.owned=true;r.started=mono;}
   r.status=on?"ON 요청 · PLC 재수신 대기":"OFF 요청 · PLC 재수신 대기";
  }
 }
 private void tick(){
  if(closed||!loaded)return;
  long now=System.currentTimeMillis(),mono=SystemClock.elapsedRealtime();
  if(active&&lastWall>0&&Math.abs((now-lastWall)-(mono-lastMono))>5000){
   active=false;for(Run r:runs.values())if(r.owned)r.stopping=true;note("HMI 시각 변경 감지 · 자동운전 중단, 시간 확인 후 다시 시작하세요.");
  }
  lastWall=now;lastMono=mono;
  if(active&&!host.mayOperate()){
   active=false;for(Run r:runs.values())if(r.owned)r.stopping=true;note("PLC 통신/운전 승인 상실 · 자동 재시작 금지 · 출력 정지 실수신 확인 필요");
  }
  for(JSONObject rule:rules.values()){
   String key=key(rule);Run r=run(key);int house=rule.optInt("house"),slot=rule.optInt("slot");
   Boolean actual=host.output(house,slot);
   if(r.pending){
    if(mono-r.pendingAt>30000){r.pending=false;paused.add(key);r.stopping=r.owned;r.status="명령 완료 지연 · 출력 미확인 · 자동 잠금";}
    else continue;
   }
   if(r.stopping){
    if(Boolean.FALSE.equals(actual)){r.owned=false;r.stopping=false;r.lastOff=mono;r.status="PLC OFF 수신 확인";}
    else if(host.mayOperate()&&!host.commandBusy())request(rule,r,false,mono);
    else r.status="정지 요청 보류 · 통신/승인 상실, 실제 OFF 미확인";
    continue;
   }
   if(!active||!rule.optBoolean("enabled")||paused.contains(key))continue;
   if(AutomationLogic.motor(slot)){r.status="자동 모터 차단 · 실제 리미트 DI/구동시간 맵 미확인";continue;}
   String block=host.blockReason(house,slot);if(block!=null){r.status=block;continue;}
   if(actual==null){r.status="PLC 실수신 없음 · 자동 차단";continue;}
   if(!rule.optString("mode").equals("time")){
    r.status="온습도 센서 미수신 · 제조사 통신맵 필요";
    if(r.owned){r.stopping=true;request(rule,r,false,mono);}continue;
   }
   boolean demand=AutomationLogic.demand(rule,now,null,null,false,false);
   if(r.owned&&mono-r.started>=rule.optLong("maxOnSec")*1000){
    paused.add(key);r.stopping=true;r.status="최대 연속 운전시간 도달 · 자동 재시작 잠금";request(rule,r,false,mono);continue;
   }
   if(!demand){
    if(r.owned){r.stopping=true;request(rule,r,false,mono);}else r.status="예약 시간 대기";
   }else if(!r.owned){
    if(actual){r.status="기존 ON 출력 · 자동 인수 안 함 · OFF 후 시작";continue;}
    if(mono-r.lastOff<rule.optLong("minOffSec")*1000){r.status="최소 정지시간 대기";continue;}
    r.status="예약 조건 만족 · PLC 명령 대기";request(rule,r,true,mono);
   }else r.status="자동 운전 · PLC "+(actual?"ON":"OFF")+" 실수신";
  }
 }
 public void board(int id,String bits,long now){if(!closed)historyWorker.execute(()->{try{store.board(id,bits,now);}catch(Exception ex){message="수신 이력 저장 오류: "+ex.getClass().getSimpleName();}});}
 public void disconnected(int id){if(!closed)historyWorker.execute(()->store.disconnected(id));}
 public void save(int house,int slot,String raw){
  if(closed)return;
  engine.execute(()->{
   try{
    if(raw==null||raw.length()>20000)throw new IllegalArgumentException("설정 크기 제한");
    JSONObject rule=new JSONObject(raw);rule.put("house",house).put("slot",slot);
    String error=AutomationLogic.validate(rule);if(error!=null)throw new IllegalArgumentException(error);
    String key=key(rule);paused.add(key);Run r=run(key);if(r.owned||r.pending)r.stopping=true;
    historyWorker.execute(()->{
     try{store.saveRules(new JSONArray().put(rule));engine.execute(()->{rules.put(key,rule);note("설정 저장 완료 · 해당 장비 자동 일시정지 · 자동 시작으로 재적용");});}
     catch(Exception ex){host.emit("autoNotice","저장 실패: "+ex.getMessage());}
    });
   }catch(Exception ex){host.emit("autoNotice","설정 저장 차단: "+ex.getMessage());}
  });
 }
 public void copy(int from,int to){
  if(closed)return;
  engine.execute(()->{
   try{
    if(from<1||from>5||to<1||to>5||from==to)throw new IllegalArgumentException("복사할 다른 동을 선택하세요.");
    JSONArray copies=new JSONArray();
    boolean any=false;for(JSONObject r:rules.values())if(r.optInt("house")==from)any=true;
    if(!any)throw new IllegalArgumentException("복사할 저장 설정이 없습니다.");
    for(int slot=0;slot<24;slot++)if(AutomationLogic.validDevice(from,slot)){
     JSONObject original=rules.get(AutomationLogic.key(from,slot));
     JSONObject copy=original==null?AutomationLogic.blank(to,slot):new JSONObject(original.toString());
     copy.put("house",to);copy.put("enabled",false);copies.put(copy);
    }
    for(int i=0;i<copies.length();i++){Run r=run(key(copies.getJSONObject(i)));if(r.owned||r.pending)r.stopping=true;paused.add(key(copies.getJSONObject(i)));}
    historyWorker.execute(()->{try{store.saveRules(copies);engine.execute(()->{for(int i=0;i<copies.length();i++){JSONObject r=copies.optJSONObject(i);rules.put(key(r),r);}note("다른 동으로 복사 완료 · 복사본 사용 OFF, 주소는 대상 동의 고정 주소 유지");});}catch(Exception ex){host.emit("autoNotice","복사 실패: "+ex.getMessage());}});
   }catch(Exception ex){host.emit("autoNotice",ex.getMessage());}
  });
 }
 public void snapshot(int house,int slot,String period,String date,int requestId){
  if(!AutomationLogic.validDevice(house,slot)||closed)return;
  engine.execute(()->{
   try{
    JSONObject rule=rules.get(AutomationLogic.key(house,slot));if(rule==null)rule=AutomationLogic.blank(house,slot);
    JSONObject response=new JSONObject().put("requestId",requestId).put("house",house).put("slot",slot).put("period",period).put("date",date)
     .put("rule",new JSONObject(rule.toString())).put("active",active).put("loaded",loaded).put("message",message)
     .put("temperature",JSONObject.NULL).put("humidity",JSONObject.NULL).put("sensorReceivedAt",JSONObject.NULL)
     .put("lastPlcAt",host.receivedAt(house)).put("houseOnCount",host.houseOnCount(house)).put("output",host.output(house,slot)==null?JSONObject.NULL:host.output(house,slot));
    JSONArray summary=new JSONArray();
    for(JSONObject r:rules.values()){
     Run state=run(key(r));summary.put(new JSONObject().put("house",r.optInt("house")).put("slot",r.optInt("slot")).put("enabled",r.optBoolean("enabled")).put("mode",r.optString("mode")).put("schedules",r.optJSONArray("schedules")).put("paused",paused.contains(key(r))).put("status",state.status));
    }
    response.put("rules",summary).put("status",run(AutomationLogic.key(house,slot)).status);
    historyWorker.execute(()->{
     try{response.put("history",store.history(house,slot,period,date));host.emit("automation",response.toString());}
     catch(Exception ex){host.emit("autoNotice","이력 조회 오류: "+ex.getMessage());}
    });
   }catch(Exception ex){host.emit("autoNotice","자동 상태 조회 오류: "+ex.getMessage());}
  });
 }
 @Override public void close(){closed=true;active=false;engine.shutdownNow();historyWorker.execute(store::close);historyWorker.shutdown();/* Drain history off UI; never auto-resume outputs. */}
}
