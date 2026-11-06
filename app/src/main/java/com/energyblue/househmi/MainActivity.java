package com.energyblue.househmi;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
 private WebView browser;
 private LanDiscovery discovery;
 private final ExecutorService plcWorker=Executors.newSingleThreadExecutor();
 private final AtomicBoolean plcBusy=new AtomicBoolean(false);
 private final AtomicBoolean outputCommandPending=new AtomicBoolean(false);
 private final AtomicBoolean armRequestPending=new AtomicBoolean(false);
 private final Object plcGuard=new Object();
 private final long[] lastGoodRead=new long[2];
 private final boolean[] lastM0Shorted=new boolean[2];
 // Physical boards present on COM5: default single ID1 for isolated commissioning.
 private int activeBoardCount=1;
 private long armSequence=0;
 // Manual control authority resets on disconnect, user lock and app shutdown.
 private long outputArmUntil=0;
 private void markRead(int id,boolean shorted){
  synchronized(plcGuard){
   lastGoodRead[id-1]=android.os.SystemClock.elapsedRealtime();
   lastM0Shorted[id-1]=shorted;
  }
 }
 private void invalidateReads(){
  synchronized(plcGuard){lastGoodRead[0]=0;lastGoodRead[1]=0;outputArmUntil=0;armSequence++;}
  emit("plcArm","수동 제어 잠금 · 통신 재확인 필요");
 }
 private void setActiveBoardCount(int count){
  if(count!=1&&count!=2)throw new IllegalArgumentException("보드 수량은 1장 또는 2장");
  synchronized(plcGuard){
   activeBoardCount=count;
   lastGoodRead[0]=0;lastGoodRead[1]=0;outputArmUntil=0;armSequence++;
  }
 }
 private int activeBoards(){synchronized(plcGuard){return activeBoardCount;}}
 private static boolean validMode(String mode){return "default".equals(mode)||"m0_short".equals(mode);}
 private void invalidateBoard(int id){
  synchronized(plcGuard){lastGoodRead[id-1]=0;outputArmUntil=0;armSequence++;}
  emit("plcArm","수동 제어 잠금 · ID "+id+" 통신 오류");
 }
 private boolean armedAndFresh(int id,boolean shorted){
  synchronized(plcGuard){
   // A manually armed commissioning session does not expire after 60 seconds.
   // It is revoked by disarm, communication failure, board selection or app exit.
   int count=activeBoardCount;
   return id>=1&&id<=count&&outputArmUntil==-1
     && lastGoodRead[0]>0&&(count==1||lastGoodRead[1]>0)
     && lastM0Shorted[id-1]==shorted;
  }
 }
 private boolean[] modesSnapshot(){
  synchronized(plcGuard){return new boolean[]{lastM0Shorted[0],lastM0Shorted[1]};}
 }
 private void notifyBoard(int id,boolean shorted,boolean[] di,boolean[] dout)throws Exception{
  JSONObject obj=new JSONObject();
  obj.put("id",id).put("status","OK")
   .put("di",bits(di)).put("do",bits(dout))
   .put("diSummary",ModbusReadOnly.summarize(di))
   .put("doSummary",ModbusReadOnly.summarize(dout))
   .put("mode",shorted?"M0 점퍼 연결":"기본 모드");
  emit("plc",obj.toString());
 }
 private void writeStatus(int id,int ch,String status,String message){
  try{
   JSONObject obj=new JSONObject().put("id",id).put("ch",ch).put("status",status).put("message",message);
   emit("plcWrite",obj.toString());
  }catch(Exception ignored){emit("plcWrite","{\"status\":\"ERROR\",\"message\":\"상태 형식 오류\"}");}
 }
 private boolean[][] scanActiveBoards(boolean[] modes,int count)throws Exception{
  if(count!=1&&count!=2)throw new java.io.IOException("설정된 보드 수량 오류");
  boolean[][] states=new boolean[2][];
  for(int id=1;id<=count;id++){
   int addr=ModbusReadOnly.addresses(modes[id-1])[0];
   states[id-1]=ModbusReadOnly.read64(id,addr);
  }
  return states;
 }
 /** Verified historical logical map: 5 houses x 24 DO, motor pairs at slots 4..19.
  * Each adjacent even/odd pair is OPEN/CLOSE. 8 spare channels remain unpaired.
  * A PLC channel never crosses a board boundary within its pair.
  */
 static int pairedMotorChannel(int slave,int channel){
  if((slave!=1&&slave!=2)||channel<1||channel>64)return -1;
  int absolute=(slave-1)*64+channel-1;
  if(absolute>=120)return -1;
  int slot=absolute%24;
  if(slot<4||slot>19)return -1;
  int otherAbsolute=absolute+((slot%2==0)?1:-1);
  if(otherAbsolute/64!=absolute/64)return -1;
  return otherAbsolute%64+1;
 }
 private void validateMotorPairOn(boolean[][] states,int slave,int channel)throws java.io.IOException{
  int reverse=pairedMotorChannel(slave,channel);
  if(reverse>0&&states[slave-1][reverse-1])
   throw new java.io.IOException("모터 정·역 인터록: ID "+slave+" DO"+reverse+
     " 반대 방향이 ON입니다. 정지 후 0.5초 재확인이 필요합니다.");
 }

 private ReleaseUpdater updater;
 private final Handler ui = new Handler(Looper.getMainLooper());
 private volatile boolean plcAutoPaused=false;
 private SharedPreferences plcPreferences(){
  return getSharedPreferences("hmi_com5_v1",Context.MODE_PRIVATE);
 }
 private boolean autoM0(int id){return plcPreferences().getBoolean("m0_id"+id,false);}
 private void sendLink(int id,boolean online,String reason){
  try {
   JSONObject o=new JSONObject().put("id",id).put("connected",online)
     .put("detail",reason==null?"":reason);
   emit("plcLink",o.toString());
  }catch(Exception ignored){}
 }
 private void autoScanOnce(){
  if(destroyed||plcAutoPaused)return;
  if(!SerialPort.isOpen()){
   String path=plcPreferences().getString("device","/dev/ttyS5");
   int baud=plcPreferences().getInt("baud",9600);
   String opened=SerialPort.open(path,baud);
   emit("plcPort",opened);
   if(!"OPEN_OK".equals(opened)){
    sendLink(1,false,"COM5 "+opened);sendLink(2,false,"COM5 "+opened);
    return;
   }
  }
  boolean anyOnline=false;
  for(int id=1;id<=2;id++){
   if(destroyed||plcAutoPaused||Thread.currentThread().isInterrupted())return;
   try{
    boolean shorted=autoM0(id);
    int[] addresses=ModbusReadOnly.addresses(shorted);
    boolean[] dout=ModbusReadOnly.read64(id,addresses[0]);
    boolean[] di=ModbusReadOnly.read64(id,addresses[1]);
    anyOnline=true;
    markRead(id,shorted);
    notifyBoard(id,shorted,di,dout);
    sendLink(id,true,"CRC 확인 · DI/DO 64채널 실수신");
   }catch(Exception ex){
    if(id<=activeBoards())invalidateBoard(id);
    String message=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
    sendLink(id,false,message);
    try{
     JSONObject fail=new JSONObject().put("id",id).put("status","ERROR")
       .put("message","자동 점검: "+message);
     emit("plc",fail.toString());
    }catch(Exception ignored){}
   }
  }
  if(!anyOnline){
   SerialPort.close(); // Retry port next cycle, but never transmit DO commands on reconnect.
   emit("plcPort","COM5 재연결 대기");
  }
 }
 private final Runnable plcAutoPoll=new Runnable(){
  @Override public void run(){
   if(destroyed)return;
   if(!plcAutoPaused&&!armRequestPending.get()&&!outputCommandPending.get()&&plcBusy.compareAndSet(false,true)){
    plcWorker.execute(()->{
     try{autoScanOnce();}
     finally{plcBusy.set(false);}
    });
   }
   ui.postDelayed(this,5000);
  }
 };
 private volatile boolean destroyed = false;
 @Override public void onCreate(Bundle b) { super.onCreate(b);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
  browser=new WebView(this);
  browser.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !"file:///android_asset/index.html".equals(url);}
   @Override public boolean shouldOverrideUrlLoading(WebView view,android.webkit.WebResourceRequest request){
    return !"file:///android_asset/index.html".equals(request.getUrl().toString());
   }
  });
  browser.setWebChromeClient(new WebChromeClient());
  browser.getSettings().setJavaScriptEnabled(true);
  browser.getSettings().setDomStorageEnabled(true);
  browser.getSettings().setAllowFileAccess(true);
  browser.getSettings().setAllowFileAccessFromFileURLs(false);
  browser.getSettings().setAllowUniversalAccessFromFileURLs(false);
  browser.addJavascriptInterface(new NativeDiagnostics(),"NativeDiagnostics");
  updater = new ReleaseUpdater(this, this::emit);
  // Updates must be requested from the HMI UI. Never download APKs automatically on startup.
  discovery = new LanDiscovery(); discovery.start();
  setContentView(browser); browser.loadUrl("file:///android_asset/index.html");
  ui.postDelayed(plcAutoPoll,1500);
 }
 private void emit(String event, String message) {
  ui.post(()->{if(destroyed||browser==null)return;
   JSONObject data=new JSONObject();
   try{data.put("type", event);data.put("message",message);}catch(Exception ignored){}
   browser.evaluateJavascript("window.nativeMessage("+JSONObject.quote(data.toString())+")",null);
  });
 }
 static String localIp() {
  try {
   for(NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())){
    if(!nif.isUp()||nif.isLoopback()||nif.isVirtual())continue;
    String name=nif.getName().toLowerCase(java.util.Locale.ROOT);
    if(!name.startsWith("wlan")&&!name.startsWith("eth"))continue;
    for(InetAddress ip:Collections.list(nif.getInetAddresses()))
     if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress())return ip.getHostAddress();
   }
  }catch(Exception ignored){}
  return "IP 확인 불가 (Wi-Fi 연결 확인)";
 }
 @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
  if(updater!=null&&updater.onExportResult(requestCode,resultCode,data))return;
  super.onActivityResult(requestCode,resultCode,data);
 }
 @Override protected void onDestroy(){plcAutoPaused=true;ui.removeCallbacks(plcAutoPoll);invalidateReads();destroyed=true;plcWorker.shutdownNow();new Thread(()->SerialPort.close(),"COM5-async-close").start();if(discovery!=null)discovery.close();if(updater!=null)updater.close();if(browser!=null){browser.destroy();browser=null;}super.onDestroy();}
 private static String bits(boolean[] values){
  StringBuilder out=new StringBuilder(64);
  for(boolean v:values)out.append(v?'1':'0');
  return out.toString();
 }
 public final class NativeDiagnostics {
  @JavascriptInterface public String probe(String path){
   try {JSONObject obj=new JSONObject();
    if(path==null||!path.matches("/dev/[a-zA-Z0-9_./-]+")||path.contains("..")){obj.put("result","장치 경로 형식 오류");return obj.toString();}
    File file=new File(path);obj.put("path",path);obj.put("exists",file.exists());obj.put("readable",file.canRead());obj.put("writable",file.canWrite());
    obj.put("result",file.exists()?"장치 파일 확인됨 (RS485 통신 미검증)":"장치 파일 없음");return obj.toString();
   }catch(Exception ex){return "{\"result\":\"진단 오류\"}";}
  }
  @JavascriptInterface public String openCom5(String path,int baud){
   if(plcBusy.get()||outputCommandPending.get())return "COM5_BUSY · 송수신 중 포트 변경 차단";
   invalidateReads();
   String result=SerialPort.open(path,baud);
   if("OPEN_OK".equals(result)){
    plcPreferences().edit().putString("device",path).putInt("baud",baud).apply();
    plcAutoPaused=false;
   }
   emit("plcPort",result);
   return result+" | ID 1·2 자동 실수신 감시 · DO 자동 출력 없음";
  }
  @JavascriptInterface public String closeCom5(){
   if(plcBusy.get()||outputCommandPending.get())return "COM5_BUSY · 실제 비상정지는 외부 하드웨어로 실행";
   plcAutoPaused=true;
   invalidateReads();
   SerialPort.close();
   sendLink(1,false,"사용자가 포트를 닫음");sendLink(2,false,"사용자가 포트를 닫음");
   emit("plcPort","COM5 닫힘 · 수동 재개 가능");
   return "COM5 닫힘 · 자동 연결 일시중지";
  }
  @JavascriptInterface public String getAutoPlcConfig(){
   try{
    return new JSONObject().put("path",plcPreferences().getString("device","/dev/ttyS5"))
      .put("baud",plcPreferences().getInt("baud",9600))
      .put("mode1",autoM0(1)?"m0_short":"default")
      .put("mode2",autoM0(2)?"m0_short":"default").toString();
   }catch(Exception ex){return "{}";}
  }
  @JavascriptInterface public void setAutoPlcModes(String first,String second){
   if(!validMode(first)||!validMode(second))return;
   plcPreferences().edit().putBoolean("m0_id1","m0_short".equals(first))
     .putBoolean("m0_id2","m0_short".equals(second)).apply();
   invalidateReads();
   emit("plcPort","PLC 통신 모드 변경 · 다음 자동 점검에서 ID 1·2 재확인");
  }
  /** Read only the physically connected N4D4F64 IDs; FC03 map unchanged. */
  @JavascriptInterface public void readPlc(String mode1,String mode2,int boardCount){
   if((boardCount!=1&&boardCount!=2)||!validMode(mode1)||(boardCount==2&&!validMode(mode2))){
    emit("plcArm","실제 연결 보드 수량 또는 M0 모드 선택 오류");return;
   }
   final boolean[] modes={"m0_short".equals(mode1),"m0_short".equals(mode2)};
   if(!plcBusy.compareAndSet(false,true)){
    emit("plcArm","COM5 처리 중입니다. 이전 작업 완료 후 읽어 주세요.");return;
   }
   setActiveBoardCount(boardCount);
   emit("plcArm","PLC ID 1"+(boardCount==2?"·2":"")+" 읽기 중 · 수동 제어 잠금");
   plcWorker.execute(()->{
    try{
     for(int slave=1;slave<=boardCount;slave++){
      if(destroyed||Thread.currentThread().isInterrupted())return;
      try{
       int[] addrs=ModbusReadOnly.addresses(modes[slave-1]);
       boolean[] doState=ModbusReadOnly.read64(slave,addrs[0]);
       boolean[] diState=ModbusReadOnly.read64(slave,addrs[1]);
       markRead(slave,modes[slave-1]);
       notifyBoard(slave,modes[slave-1],diState,doState);
      }catch(Exception ex){
       invalidateBoard(slave);
       JSONObject fail=new JSONObject();
       try{fail.put("id",slave);fail.put("status","ERROR");fail.put("message",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());}catch(Exception ignored){}
       emit("plc",fail.toString());
      }
     }
    }finally{plcBusy.set(false);}
   });
  }
  /** Fresh read and safety validation before enabling any output, count 1 or 2.
   * Output arming persists within current session and resets on failure.
   */
  /** Manual output activation waits for ongoing automatic FC03 scans instead of
   * rejecting while COM5 is busy. No output is sent during arming.
   */
  @JavascriptInterface public void armManualOutputs(String mode1,String mode2,int boardCount){
   if((boardCount!=1&&boardCount!=2)||!validMode(mode1)||(boardCount==2&&!validMode(mode2))){
    emit("plcArm","수동 시험 차단: 연결 보드 수 또는 M0 모드 오류");return;
   }
   if(!armRequestPending.compareAndSet(false,true)){
    emit("plcArm","PLC 출력 시험 승인 요청이 이미 진행 중입니다.");return;
   }
   final boolean[] modes={"m0_short".equals(mode1),"m0_short".equals(mode2)};
   final long ticket;
   synchronized(plcGuard){
    armSequence++;
    ticket=armSequence;
    outputArmUntil=0;
    
   }
   emit("plcArm","PLC ID 1"+(boardCount==2?"·2":"")+" 자동 통신 완료 후 실제 상태 및 안전 설정 확인 중 · 현재 출력 잠금");
   plcWorker.execute(()->{
    String outcome;
    plcBusy.set(true);
    try{
     if(destroyed||Thread.currentThread().isInterrupted())throw new java.io.IOException("통신 작업 중단");
     synchronized(plcGuard){
      if(ticket!=armSequence)throw new java.io.IOException("사용자가 시험 시작을 취소했습니다.");
      activeBoardCount=boardCount;
      lastGoodRead[0]=0;lastGoodRead[1]=0;
     }
     for(int id=1;id<=boardCount;id++){
      int[] addrs=ModbusReadOnly.addresses(modes[id-1]);
      boolean[] dout=ModbusReadOnly.read64(id,addrs[0]);
      boolean[] di=ModbusReadOnly.read64(id,addrs[1]);
      String blocked=ModbusReadOnly.safetyProblem(id);
      if(blocked!=null)throw new java.io.IOException(blocked);
      markRead(id,modes[id-1]);
      notifyBoard(id,modes[id-1],di,dout);
     }
     synchronized(plcGuard){
      if(ticket!=armSequence||destroyed)throw new java.io.IOException("시험 승인 취소 또는 통신 상태 변경");
      outputArmUntil=-1;
     }
     outcome="ARM_OK · PLC ID 1"+(boardCount==2?"·2":"")+
       " 수동 제어 활성 · 단일 DO 출력 제한 · 사용자 잠금/통신 장애/앱 종료까지 유지";
    }catch(Exception ex){
     synchronized(plcGuard){
      if(ticket==armSequence){
       outputArmUntil=0;
       lastGoodRead[0]=0;lastGoodRead[1]=0;
       armSequence++;
      }
     }
     outcome="수동 시험 차단: "+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());
    }finally{
     plcBusy.set(false);
     armRequestPending.set(false);
    }
    emit("plcArm",outcome);
   });
  }
  @JavascriptInterface public String disarmManualOutputs(){
   synchronized(plcGuard){outputArmUntil=0;armSequence++;}
   return "수동 제어 잠금 · 이미 ON인 DO 출력은 자동으로 OFF되지 않습니다.";
  }
  /** Single-channel commissioning, no silent retries; both board outputs checked for conflicts.
   * Physical limit DI and direction mapping unknown: never assume a safe motor pairing.
   */
  @JavascriptInterface public void setDoOutput(int slave,int channel,boolean on,String mode){
   if(slave<1||slave>activeBoards()||channel<1||channel>64||!validMode(mode)){
    writeStatus(slave,channel,"DENIED","현재 연결 수량에서 ID "+slave+" 출력은 시험 제외 또는 주소 오류");return;
   }
   if(!outputCommandPending.compareAndSet(false,true)){
    writeStatus(slave,channel,"DENIED","이전 DO 명령 처리 중");return;
   }
   final boolean shorted="m0_short".equals(mode);
   writeStatus(slave,channel,"WORKING","COM5 통신 작업 순서 대기 · 기존 자동 감시 종료 후 전송");
   plcWorker.execute(()->{
    plcBusy.set(true);
    boolean commandSent=false;
    try{
     if(destroyed||!armedAndFresh(slave,shorted))
      throw new java.io.IOException("수동 제어 미활성 · 연결 상태 또는 모드 확인 필요");
     boolean[] modes=modesSnapshot();
     int boardCount=activeBoards();
     if(slave>boardCount)throw new java.io.IOException("연결되지 않은 PLC ID 출력 차단");
     writeStatus(slave,channel,"WORKING","ID "+slave+" DO"+channel+" 최신 상태·안전 레지스터 점검 중");
     boolean[][] states=scanActiveBoards(modes,boardCount);
     // Existing verified Python worker checks these exact remote/relationship registers.
     String guard=ModbusReadOnly.safetyProblem(slave);
     if(guard!=null)throw new java.io.IOException(guard);
     boolean current=states[slave-1][channel-1];
     if(current==on){
      writeStatus(slave,channel,"UNCHANGED","PLC 실수신이 이미 "+(on?"ON":"OFF")+" 상태입니다.");
      return;
     }
     if(on){
      // Unrelated lamps, fans and irrigation remain independently switchable.
      // A motor's reverse direction is NEVER allowed simultaneously.
      validateMotorPairOn(states,slave,channel);
      // Keep the existing 0.5s pre-actuation delay and fresh CRC/safety checks.
      Thread.sleep(500);
      if(!armedAndFresh(slave,shorted))
       throw new java.io.IOException("재점검 중 수동 승인/통신 신선도 만료");
      states=scanActiveBoards(modes,boardCount);
      validateMotorPairOn(states,slave,channel);
      guard=ModbusReadOnly.safetyProblem(slave);
      if(guard!=null)throw new java.io.IOException("출력 직전 "+guard);
     }
     if(Thread.currentThread().isInterrupted()||!armedAndFresh(slave,shorted))
      throw new java.io.IOException("출력 전 승인 만료/통신 작업 중단");
     commandSent=true;
     ModbusOutput.writeOnce(slave,shorted,channel,on);
     int[] addrs=ModbusReadOnly.addresses(shorted);
     boolean[] doAfter=ModbusReadOnly.read64(slave,addrs[0]);
     boolean[] diAfter=ModbusReadOnly.read64(slave,addrs[1]);
     markRead(slave,shorted);
     notifyBoard(slave,shorted,diAfter,doAfter);
     if(doAfter[channel-1]!=on)
      throw new java.io.IOException("명령 응답 후 DO 실수신이 요청과 불일치합니다");
     writeStatus(slave,channel,"CONFIRMED","ID "+slave+" DO"+channel+
      " "+(on?"ON":"OFF")+" · Modbus 응답 CRC 및 DO 실수신 일치 (부하 실측 아님)");
    }catch(Exception ex){
     invalidateBoard(slave);
     writeStatus(slave,channel,commandSent?"UNKNOWN":"DENIED",
      (commandSent?"출력 명령 송신 후 상태 불확실 · 실제 출력 확인 필요: ":"명령 차단: ")+
      (ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));
    }finally{plcBusy.set(false);outputCommandPending.set(false);}
   });
  }
  /** Atomic motor OPEN / STOP / CLOSE with a verified paired DO index.
   * STOP issues OFF to BOTH motor DOs, regardless of manual-arm state. This is
   * a software stop, NOT a replacement for a hardwired emergency stop.
   * Reversing always confirms OFF before waiting >=500ms and switching ON.
   */
  @JavascriptInterface public void motorCommand(int slave,int openCh,int closeCh,int direction,String mode){
   if(slave<1||slave>activeBoards()||!validMode(mode)||
      (direction!=-1&&direction!=0&&direction!=1)||
      pairedMotorChannel(slave,openCh)!=closeCh||
      openCh<1||closeCh<1||openCh>64||closeCh>64||
      (((slave-1)*64+openCh-1)%24)%2!=0){
    writeStatus(slave,openCh,"DENIED","모터 정·역 DO 주소 또는 명령 오류");return;
   }
   if(!outputCommandPending.compareAndSet(false,true)){
    writeStatus(slave,openCh,"DENIED","이전 출력 명령 처리 중");return;
   }
   final boolean shorted="m0_short".equals(mode);
   writeStatus(slave,openCh,"WORKING","모터 "+(direction==1?"열림":direction==-1?"닫힘":"정지")+" 명령 대기");
   plcWorker.execute(()->{
    plcBusy.set(true);
    boolean sent=false;
    try{
     if(destroyed)throw new java.io.IOException("앱 종료");
     if(direction!=0&&!armedAndFresh(slave,shorted))
      throw new java.io.IOException("수동제어 활성 필요");
     boolean[] modes=modesSnapshot();
     int count=activeBoards();
     boolean[][] state=scanActiveBoards(modes,count);
     String blocked=ModbusReadOnly.safetyProblem(slave);
     if(blocked!=null&&direction!=0)throw new java.io.IOException(blocked);
     int addr=ModbusReadOnly.addresses(shorted)[0];
     // STOP or reverse direction: drop the other direction first.
     int opposite=direction==1?closeCh:openCh;
     int selected=direction==1?openCh:closeCh;
     if(direction==0){
      if(state[slave-1][openCh-1]){
       sent=true;ModbusOutput.writeOnce(slave,shorted,openCh,false);
      }
      if(state[slave-1][closeCh-1]){
       sent=true;ModbusOutput.writeOnce(slave,shorted,closeCh,false);
      }
     }else{
      if(state[slave-1][opposite-1]){
       sent=true;ModbusOutput.writeOnce(slave,shorted,opposite,false);
      }
     }
     boolean[] afterOff=ModbusReadOnly.read64(slave,addr);
     if(direction==0){
      if(afterOff[openCh-1]||afterOff[closeCh-1])
       throw new java.io.IOException("정지 후 DO OFF 재수신 불일치");
     }else{
      if(afterOff[opposite-1])
       throw new java.io.IOException("반대 방향 DO OFF 확인 실패");
      // 0.5s break-before-make is required even when the opposite output
      // was already OFF, to prevent accidental direction overlap.
      Thread.sleep(500);
      if(!armedAndFresh(slave,shorted)||Thread.currentThread().isInterrupted())
       throw new java.io.IOException("방향 전환 중 수동제어 취소 또는 통신 중단");
      boolean[] rechecked=ModbusReadOnly.read64(slave,addr);
      if(rechecked[opposite-1])
       throw new java.io.IOException("방향 전환 직전 반대 DO가 ON으로 변경됨");
      blocked=ModbusReadOnly.safetyProblem(slave);
      if(blocked!=null)throw new java.io.IOException(blocked);
      if(!rechecked[selected-1]){
       sent=true;ModbusOutput.writeOnce(slave,shorted,selected,true);
      }
     }
     boolean[] finalDo=ModbusReadOnly.read64(slave,addr);
     boolean[] finalDi=ModbusReadOnly.read64(slave,ModbusReadOnly.addresses(shorted)[1]);
     markRead(slave,shorted);
     notifyBoard(slave,shorted,finalDi,finalDo);
     if(finalDo[openCh-1]&&finalDo[closeCh-1])
      throw new java.io.IOException("정·역 DO 동시 ON 감지");
     if(direction==0&&(finalDo[openCh-1]||finalDo[closeCh-1]))
      throw new java.io.IOException("정지 출력 확인 실패");
     if(direction==1&&!finalDo[openCh-1])
      throw new java.io.IOException("열림 출력 확인 실패");
     if(direction==-1&&!finalDo[closeCh-1])
      throw new java.io.IOException("닫힘 출력 확인 실패");
     writeStatus(slave,openCh,"CONFIRMED",
       "ID "+slave+" 모터 "+(direction==0?"정지":direction==1?"열림":"닫힘")+
       " · 정/역 인터록, 0.5초 전환 및 DO 실수신 확인");
    }catch(Exception ex){
     invalidateBoard(slave);
     writeStatus(slave,openCh,sent?"UNKNOWN":"DENIED",
       (sent?"모터 명령 후 상태 불확실: ":"모터 명령 차단: ")+
       (ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage()));
    }finally{plcBusy.set(false);outputCommandPending.set(false);}
   });
  }
  @JavascriptInterface public String getIp(){return localIp();}
  @JavascriptInterface public String getAppVersion(){return BuildConfig.VERSION_NAME;}
  @JavascriptInterface public void checkUpdate(){updater.check();}
  @JavascriptInterface public void downloadUpdate(){updater.download();}
  @JavascriptInterface public void installUpdate(){updater.install();}
 }
 private final class LanDiscovery extends Thread {
  private volatile boolean active=true;
  private DatagramSocket socket;
  LanDiscovery(){super("HMI-LAN-Discovery");setDaemon(true);}
  @Override public void run() {
   try(DatagramSocket s = new DatagramSocket(38541)){
    socket=s;s.setSoTimeout(2000);
    byte[] buf=new byte[512];
    while(active){
     DatagramPacket request=new DatagramPacket(buf,buf.length);
     try{s.receive(request);}catch(java.net.SocketTimeoutException timeout){continue;}
     String message=new String(request.getData(),request.getOffset(),request.getLength(),java.nio.charset.StandardCharsets.US_ASCII);
     if(!"ENERGYBLUE_HMI_DISCOVER_V1".equals(message))continue;
     JSONObject reply=new JSONObject();
     reply.put("type","ENERGYBLUE_HMI_V1");reply.put("model","IXP101H1");reply.put("version",BuildConfig.VERSION_NAME);reply.put("ip",localIp());
     byte[] payload=reply.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
     s.send(new DatagramPacket(payload,payload.length,request.getAddress(),request.getPort()));
    }
   }catch(Exception e){emit("discovery","LAN 검색 서비스 오류: "+e.getClass().getSimpleName());}
  }
  void close(){active=false;if(socket!=null)socket.close();}
 }
}
