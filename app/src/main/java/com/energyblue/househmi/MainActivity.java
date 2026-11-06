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
 private final Object plcGuard=new Object();
 private final long[] lastGoodRead=new long[2];
 private final boolean[] lastM0Shorted=new boolean[2];
 // Physical boards present on COM5: default single ID1 for isolated commissioning.
 private int activeBoardCount=1;
 private long armSequence=0;
 // Off by default on every app launch; armed only for explicit no-load bench commissioning.
 private boolean noLoadMultiOutput=false;
 private long outputArmUntil=0;
 private static final long OUTPUT_ARM_MS=60000L;
 private void markRead(int id,boolean shorted){
  synchronized(plcGuard){
   lastGoodRead[id-1]=android.os.SystemClock.elapsedRealtime();
   lastM0Shorted[id-1]=shorted;
  }
 }
 private void invalidateReads(){
  synchronized(plcGuard){lastGoodRead[0]=0;lastGoodRead[1]=0;outputArmUntil=0;noLoadMultiOutput=false;armSequence++;}
  emit("plcArm","수동 출력 시험 해제 · 통신 재확인 필요");
 }
 private void setActiveBoardCount(int count){
  if(count!=1&&count!=2)throw new IllegalArgumentException("보드 수량은 1장 또는 2장");
  synchronized(plcGuard){
   activeBoardCount=count;
   lastGoodRead[0]=0;lastGoodRead[1]=0;outputArmUntil=0;noLoadMultiOutput=false;armSequence++;
  }
 }
 private int activeBoards(){synchronized(plcGuard){return activeBoardCount;}}
 private boolean noLoadTestAuthorized(){
  synchronized(plcGuard){return noLoadMultiOutput&&outputArmUntil==-1;}
 }
 private static boolean validMode(String mode){return "default".equals(mode)||"m0_short".equals(mode);}
 private void invalidateBoard(int id){
  synchronized(plcGuard){lastGoodRead[id-1]=0;outputArmUntil=0;noLoadMultiOutput=false;armSequence++;}
  emit("plcArm","수동 출력 시험 해제 · ID "+id+" 통신 오류");
 }
 private boolean armedAndFresh(int id,boolean shorted){
  synchronized(plcGuard){
   long now=android.os.SystemClock.elapsedRealtime();
   // Arming has verified only physically connected boards. Every write
   // rechecks all active outputs and safety registers before transmission.
   int count=activeBoardCount;
   return id>=1&&id<=count&&(outputArmUntil>now||(outputArmUntil==-1&&noLoadMultiOutput))
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
 private boolean otherOutputActive(boolean[][] states,int slave,int channel,int count){
  for(int id=0;id<count;id++)
   for(int c=0;c<64;c++)
    if(states[id][c]&&(id+1!=slave||c+1!=channel))return true;
  return false;
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
   if(!plcAutoPaused&&plcBusy.compareAndSet(false,true)){
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
   if(plcBusy.get())return "COM5_BUSY · 송수신 중 포트 변경 차단";
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
   if(plcBusy.get())return "COM5_BUSY · 실제 비상정지는 외부 하드웨어로 실행";
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
   emit("plcArm","PLC ID 1"+(boardCount==2?"·2":"")+" 읽기 중 · 출력 시험은 잠금 상태");
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
   * Output arming expires after 60 seconds and resets on failure.
   */
  @JavascriptInterface public void armManualOutputs(String mode1,String mode2,int boardCount,boolean noLoadBench){
   // No-load bench tests may target 1 or 2 physically present boards.
   // The full actual FC03 CRC and safety configuration of each selected board
   // must be verified before any outputs can be commissioned.
   if((boardCount!=1&&boardCount!=2)||!validMode(mode1)||(boardCount==2&&!validMode(mode2))){
    emit("plcArm","수동 출력 승인 실패: 보드 수량 또는 M0 모드 오류");return;
   }
   if(!plcBusy.compareAndSet(false,true)){
    emit("plcArm","수동 출력 승인 대기 실패: COM5 송수신 중입니다.");return;
   }
   final boolean[] modes={"m0_short".equals(mode1),"m0_short".equals(mode2)};
   setActiveBoardCount(boardCount);
   final long armingTicket;
   synchronized(plcGuard){armingTicket=armSequence;}
   emit("plcArm","PLC ID 1"+(boardCount==2?"·2":"")+" 재조회 및 안전 레지스터 검사 중 · 출력 잠금");
   plcWorker.execute(()->{
    String outcome;
    try{
     if(destroyed||Thread.currentThread().isInterrupted())throw new java.io.IOException("통신 작업 중단");
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
      if(armingTicket!=armSequence||destroyed)
       throw new java.io.IOException("사용자가 시험 승인을 취소했거나 COM5 상태가 변경되었습니다");
      outputArmUntil=noLoadBench?-1:android.os.SystemClock.elapsedRealtime()+OUTPUT_ARM_MS;
      noLoadMultiOutput=noLoadBench;
     }
     outcome="ARM_OK · PLC ID 1"+(boardCount==2?"·2":"")+
       (noLoadBench?" · 무부하 다채널 시험(다른 DO ON 허용)":" · 단일 DO 시험(동시 ON 차단)")+
       (noLoadBench?" · 세션 종료/시험 잠금까지 유효":" · 60초 유효");
    }catch(Exception ex){
     synchronized(plcGuard){outputArmUntil=0;noLoadMultiOutput=false;lastGoodRead[0]=0;lastGoodRead[1]=0;armSequence++;}
     outcome="수동 출력 시험 차단: "+(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());
    }finally{plcBusy.set(false);}
    emit("plcArm",outcome);
   });
  }
  @JavascriptInterface public String disarmManualOutputs(){
   synchronized(plcGuard){outputArmUntil=0;noLoadMultiOutput=false;armSequence++;}
   return "수동 DO 시험 잠금. 이미 ON인 출력은 자동으로 OFF하지 않습니다.";
  }
  /** Single-channel commissioning, no silent retries; both board outputs checked for conflicts.
   * Physical limit DI and direction mapping unknown: never assume a safe motor pairing.
   */
  @JavascriptInterface public void setDoOutput(int slave,int channel,boolean on,String mode){
   if(slave<1||slave>activeBoards()||channel<1||channel>64||!validMode(mode)){
    writeStatus(slave,channel,"DENIED","현재 연결 수량에서 ID "+slave+" 출력은 시험 제외 또는 주소 오류");return;
   }
   if(!plcBusy.compareAndSet(false,true)){
    writeStatus(slave,channel,"DENIED","다른 COM5 송수신 작업 중");return;
   }
   final boolean shorted="m0_short".equals(mode);
   plcWorker.execute(()->{
    boolean commandSent=false;
    try{
     if(destroyed||!armedAndFresh(slave,shorted))
      throw new java.io.IOException("출력 시험 미활성·선택 모드 불일치·승인 종료");
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
      // Commissioning bench mode is explicitly armed only when NO loads are connected.
      // Preserve the global one-active-channel rule for ordinary/single-output mode.
      if(!noLoadTestAuthorized()&&otherOutputActive(states,slave,channel,boardCount))
       throw new java.io.IOException("다른 DO가 ON입니다. 단일 출력 시험 모드에서는 추가 ON 차단. 무부하 시험 모드를 선택하려면 먼저 출력 부하를 모두 분리하세요.");
      // Keep the existing 0.5s pre-actuation delay and fresh CRC/safety checks.
      Thread.sleep(500);
      if(!armedAndFresh(slave,shorted))
       throw new java.io.IOException("재점검 중 수동 승인/통신 신선도 만료");
      states=scanActiveBoards(modes,boardCount);
      if(!noLoadTestAuthorized()&&otherOutputActive(states,slave,channel,boardCount))
       throw new java.io.IOException("단일 출력 모드: 다른 DO ON 감지, ON 명령 취소");
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
    }finally{plcBusy.set(false);}
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
