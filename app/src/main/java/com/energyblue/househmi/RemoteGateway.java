package com.energyblue.househmi;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** Opt-in, TLS-only remote gateway. NO raw Modbus frames or serial access.
 * Remote writes call the same guarded MainActivity methods as local HMI.
 * TLS key is generated inside AndroidKeyStore and is never exported.
 */
public final class RemoteGateway implements AutoCloseable {
 public interface Controller {
  JSONObject status() throws Exception;
  String command(JSONObject request);
  boolean locallyAllowed();
 }
 private static final int PORT=18443;
 private final Context context;
 private final Controller controller;
 // The TLS factory is injectable only within this package for real socket regression tests.
 interface TlsSource { SSLContext create() throws Exception; }
 private final TlsSource tlsSource;
 private final ThreadPoolExecutor connections=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,
   new ArrayBlockingQueue<Runnable>(12));
 private final Set<SSLSocket> activeSockets=Collections.newSetFromMap(new ConcurrentHashMap<SSLSocket,Boolean>());
 private final SecureRandom random=new SecureRandom();
 private volatile boolean running=false,ready=false,allowControl=false,disposed=false;
 private volatile String pairCode="",token="",fingerprint="";
 private volatile long codeExpiry=0,tokenExpiry=0;
 private int failedPairs=0;
 private long pairLockUntil=0,generation=0;
 private volatile String state="stopped",lastError="",lastTlsError="",lastClient="";
 private volatile long lastTcpAt=0,lastTlsAt=0,lastPageAt=0;
 private SSLServerSocket server;
 private Thread listener;
 public RemoteGateway(Context context,Controller controller){this(context,controller,null);}
 RemoteGateway(Context context,Controller controller,TlsSource source){
  this.context=context.getApplicationContext();this.controller=controller;
  this.tlsSource=source==null?this::sslContext:source;
 }
 public JSONObject enable(boolean controls)throws Exception{
  close();
  final long epoch;
  synchronized(this){
   if(disposed)throw new IOException("HMI gateway disposed");
   epoch=++generation;state="starting";lastError="";lastTlsError="";
   lastClient="";lastTcpAt=0;lastTlsAt=0;lastPageAt=0;
  }
  SSLServerSocket candidate=null;
  try{
   SSLContext tls=tlsSource.create();
   candidate=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
   candidate.setReuseAddress(true);
   // Displayed addresses are IPv4; listen explicitly on every IPv4 interface.
   candidate.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"),PORT),6);
   candidate.setEnabledProtocols(new String[]{"TLSv1.2"});
   candidate.setSoTimeout(1000);
   final SSLServerSocket bound=candidate;
   synchronized(this){
    if(epoch!=generation)throw new IOException("서버 시작 취소");
    server=bound;running=true;allowControl=controls;
    listener=new Thread(()->serve(bound,epoch),"hmi-remote-tls");
    listener.setDaemon(true);listener.start();
   }
   // A bound port is not proof that AndroidKeyStore can complete a TLS handshake.
   verifyLocalPage();
   synchronized(this){
    if(epoch!=generation||!running)throw new IOException("서버 시작 취소");
    ready=true;state="ready";
    pairCode=String.format(Locale.US,"%06d",random.nextInt(1000000));
    codeExpiry=System.currentTimeMillis()+300000;
    token="";tokenExpiry=0;failedPairs=0;pairLockUntil=0;
   }
   return information();
  }catch(Exception ex){
   shutdown(epoch,detail(ex),true);
   if(candidate!=null)try{candidate.close();}catch(Exception ignored){}
   throw ex;
  }
 }
 public synchronized JSONObject information()throws Exception{
  boolean enabled=running&&ready&&server!=null&&!server.isClosed()&&listener!=null&&listener.isAlive();
  return new JSONObject().put("enabled",enabled).put("port",PORT)
   .put("allowControl",allowControl).put("pairCode",enabled&&System.currentTimeMillis()<codeExpiry?pairCode:"만료 · 접속 켜기를 눌러 코드 재발급")
   .put("codeExpiresAt",codeExpiry).put("certificateSha256",fingerprint)
   .put("state",state).put("selfTestOk",enabled).put("error",lastError)
   .put("lastTlsError",lastTlsError).put("lastClient",lastClient)
   .put("lastTcpAt",lastTcpAt).put("lastTlsAt",lastTlsAt).put("lastPageAt",lastPageAt);
 }
 public boolean isRunning(){return running&&ready;}
 public synchronized void setControls(boolean enabled){
  allowControl=enabled;
  // Changing privilege revokes all existing sessions. Re-pairing is required.
  token="";tokenExpiry=0;
  pairCode=String.format(Locale.US,"%06d",random.nextInt(1000000));
  codeExpiry=System.currentTimeMillis()+300000;
 }
 public void close(){shutdown(-1,null,true);}
 private void shutdown(long expected,String error,boolean awaitListener){
  final SSLServerSocket bound;
  final Thread previous;
  final java.util.ArrayList<SSLSocket> sockets;
  synchronized(this){
   if(expected!=-1&&generation!=expected)return;
   ++generation;running=false;ready=false;state=error==null?"stopped":"failed";allowControl=false;
   if(error!=null)lastError=error;
   token="";tokenExpiry=0;pairCode="";codeExpiry=0;
   bound=server;server=null;previous=listener;listener=null;
   sockets=new java.util.ArrayList<>(activeSockets);activeSockets.clear();connections.getQueue().clear();
  }
  if(bound!=null)try{bound.close();}catch(Exception ignored){}
  for(SSLSocket socket:sockets)try{socket.close();}catch(Exception ignored){}
  // Closing a socket while accept() is in flight may defer the native FD release.
  // Wait outside the state lock, and only on the phone worker, before rebinding.
  if(awaitListener&&previous!=null&&previous!=Thread.currentThread()){
   try{previous.join(2000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
  }
 }
 public void dispose(){
  synchronized(this){disposed=true;}
  shutdown(-1,null,false);connections.shutdownNow();
 }
 private static String detail(Throwable error){
  StringBuilder text=new StringBuilder(error.getClass().getSimpleName());
  for(Throwable e=error;e!=null;e=e.getCause()){
   String message=e.getMessage();
   if(message!=null&&!message.isEmpty())text.append(": ").append(message);
   if(text.length()>350)break;
  }
  return text.substring(0,Math.min(text.length(),400));
 }
 private static String sha256(Certificate certificate)throws Exception{
  byte[] digest=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
  StringBuilder sb=new StringBuilder();
  for(byte b:digest)sb.append(String.format(Locale.US,"%02X",b&255));
  return sb.toString();
 }
 private void verifyLocalPage()throws Exception{
  final String expected=fingerprint;
  if(expected.length()!=64)throw new GeneralSecurityException("HTTPS 인증서 지문 없음");
  // This trust manager is confined to the loopback diagnostic and pins this HMI's
  // exact certificate. It never changes browser trust or accepts an arbitrary CA.
  X509TrustManager pinned=new X509TrustManager(){
   public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Client certificates not supported");}
   public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
    try{
     if(chain==null||chain.length==0||!expected.equals(sha256(chain[0])))throw new CertificateException("HMI 인증서 지문 불일치");
     chain[0].checkValidity();
    }catch(CertificateException ex){throw ex;}catch(Exception ex){throw new CertificateException(ex);}
   }
  };
  SSLContext client=SSLContext.getInstance("TLSv1.2");
  client.init(null,new TrustManager[]{pinned},new SecureRandom());
  try(SSLSocket socket=(SSLSocket)client.getSocketFactory().createSocket()){
   socket.connect(new InetSocketAddress("127.0.0.1",PORT),3000);
   socket.setSoTimeout(4000);socket.setEnabledProtocols(new String[]{"TLSv1.2"});
   socket.startHandshake();
   OutputStream out=socket.getOutputStream();
   out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:"+PORT+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
   InputStream in=socket.getInputStream();
   if(!"HTTP/1.1 200 OK".equals(readLine(in)))throw new IOException("HTTPS 첫 화면 응답 실패");
   int length=-1;
   for(int i=0;i<48;i++){
    String header=readLine(in);if(header.isEmpty())break;
    if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());
   }
   if(length<=0||length>512*1024)throw new IOException("휴대폰 페이지 길이 오류");
   byte[] body=new byte[length];int off=0;
   while(off<length){int n=in.read(body,off,length-off);if(n<0)throw new EOFException("휴대폰 페이지 수신 중단");off+=n;}
   if(!new String(body,StandardCharsets.UTF_8).contains("/api/pair"))throw new IOException("휴대폰 페이지 내용 오류");
  }
 }
 private SSLContext sslContext()throws Exception{
  final String alias="energyblue_hmi_remote_tls_v1";
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias(alias)){
   Calendar start=Calendar.getInstance(),end=Calendar.getInstance();end.add(Calendar.YEAR,10);
   KeyPairGeneratorSpec spec=new KeyPairGeneratorSpec.Builder(context)
      .setAlias(alias).setSubject(new X500Principal("CN=EnergyBlue HMI Local"))
      .setSerialNumber(BigInteger.valueOf(74019L))
      .setStartDate(start.getTime()).setEndDate(end.getTime()).build();
   KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
   gen.initialize(spec);gen.generateKeyPair();
   store.load(null);
  }
  Certificate cert=store.getCertificate(alias);
  if(cert==null)throw new GeneralSecurityException("HMI TLS certificate unavailable");
  fingerprint=sha256(cert);
  final PrivateKey key=(PrivateKey)store.getKey(alias,null);
  if(key==null)throw new GeneralSecurityException("HMI TLS private key unavailable");
  final Certificate[] storedChain=store.getCertificateChain(alias);
  if(storedChain==null||storedChain.length==0)throw new GeneralSecurityException("HMI TLS certificate chain unavailable");
  final X509Certificate[] chain=new X509Certificate[storedChain.length];
  for(int i=0;i<chain.length;i++)chain[i]=(X509Certificate)storedChain[i];
  // Explicitly select the existing AndroidKeyStore RSA identity; do not export
  // the private key, rotate the certificate or rely on provider alias enumeration.
  SSLContext tls=SSLContext.getInstance("TLSv1.2");
  tls.init(new KeyManager[]{tlsIdentity(alias,key,chain)},null,new SecureRandom());
  return tls;
 }
 static X509KeyManager tlsIdentity(final String alias,final PrivateKey key,final X509Certificate[] chain){
  return new X509KeyManager(){
   public String[] getClientAliases(String type,Principal[] issuers){return null;}
   public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return null;}
   public String[] getServerAliases(String type,Principal[] issuers){return "RSA".equals(type)?new String[]{alias}:null;}
   public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return "RSA".equals(type)?alias:null;}
   public X509Certificate[] getCertificateChain(String name){return alias.equals(name)?chain.clone():null;}
   public PrivateKey getPrivateKey(String name){return alias.equals(name)?key:null;}
  };
 }

 private synchronized boolean current(SSLServerSocket bound,long epoch){return running&&generation==epoch&&server==bound;}
 private void serve(SSLServerSocket bound,long epoch){
  while(current(bound,epoch)){
   try{
    final SSLSocket socket=(SSLSocket)bound.accept();
    socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setSoTimeout(6000);
    synchronized(this){
     if(!current(bound,epoch)){socket.close();continue;}
     activeSockets.add(socket);
     if(!socket.getInetAddress().isLoopbackAddress()){
      lastClient=socket.getInetAddress().getHostAddress();lastTcpAt=System.currentTimeMillis();
     }
    }
    try{connections.execute(()->handle(socket,bound,epoch));}
    catch(RejectedExecutionException busy){activeSockets.remove(socket);socket.close();}
   }catch(SocketTimeoutException expected){}
    catch(Exception ex){
     shutdown(epoch,"HTTPS 수신 중단: "+detail(ex),false);
     break;
    }
  }
 }
 private static String readLine(InputStream in)throws IOException{
  ByteArrayOutputStream line=new ByteArrayOutputStream();
  for(int i=0;i<4096;i++){
   int b=in.read();
   if(b==-1)throw new EOFException();
   if(b==10)break;
   if(b!=13)line.write(b);
  }
  if(line.size()>=4095)throw new IOException("Header too long");
  return line.toString("UTF-8");
 }
 private void handle(SSLSocket socket,SSLServerSocket bound,long epoch){
  boolean tlsComplete=false;
  final boolean external=!socket.getInetAddress().isLoopbackAddress();
  try(SSLSocket ss=socket){
   ss.startHandshake();
   tlsComplete=true;
   synchronized(this){if(!current(bound,epoch))return;if(external){lastTlsAt=System.currentTimeMillis();lastTlsError="";}}
   InputStream in=ss.getInputStream();
   OutputStream out=ss.getOutputStream();
   String request=readLine(in);
   String[] methodPath=request.split(" ");
   if(methodPath.length<2){send(out,400,"application/json","{\"error\":\"bad request\"}");return;}
   String method=methodPath[0],path=methodPath[1].split("\\?")[0];
   int size=0;
   String authorization="",origin="",host="";
   for(int n=0;n<48;n++){
    String header=readLine(in);
    if(header.isEmpty())break;
    int cut=header.indexOf(':');
    if(cut<0)continue;
    String key=header.substring(0,cut).trim().toLowerCase(Locale.ROOT),value=header.substring(cut+1).trim();
    if(key.equals("content-length")){try{size=Integer.parseInt(value);}catch(Exception ignored){size=-1;}}
    if(key.equals("authorization"))authorization=value;
    if(key.equals("origin"))origin=value;
    if(key.equals("host"))host=value;
   }
   if(size<0||size>2048){send(out,413,"application/json","{\"error\":\"payload\"}");return;}
   if(!origin.isEmpty()&&!origin.equals("https://"+host)){
    send(out,403,"application/json","{\"error\":\"origin\"}");return;
   }
   byte[] body=new byte[size];int off=0;
   while(off<size){int n=in.read(body,off,size-off);if(n<0)throw new EOFException();off+=n;}
   JSONObject payload=new JSONObject(size>0?new String(body,StandardCharsets.UTF_8):"{}");
   if(method.equals("GET")&&path.equals("/")){
    byte[] asset=readAsset();
    sendBytes(out,200,"text/html; charset=utf-8",asset);
    synchronized(this){if(current(bound,epoch)&&external)lastPageAt=System.currentTimeMillis();}
    return;
   }
   if(method.equals("POST")&&path.equals("/api/pair")){
    String given=payload.optString("code","");
    synchronized(this){
     long now=System.currentTimeMillis();
     if(now<pairLockUntil||now>codeExpiry||!ready||!current(bound,epoch)){
      send(out,429,"application/json","{\"error\":\"pairing locked or expired\"}");return;
     }
     if(given.length()!=6||!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),pairCode.getBytes(StandardCharsets.UTF_8))){
      if(++failedPairs>=5)pairLockUntil=now+300000;
      send(out,403,"application/json","{\"error\":\"invalid pairing code\"}");return;
     }
     byte[] bytes=new byte[32];random.nextBytes(bytes);
     token=Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE);
     tokenExpiry=now+2*60*60*1000L;
     codeExpiry=0; // one-time pairing
     send(out,200,"application/json",new JSONObject().put("token",token).put("role",allowControl?"control":"monitor").toString());
     return;
    }
   }
   if(!authorized(authorization,bound,epoch)){
    send(out,401,"application/json","{\"error\":\"pairing required\"}");return;
   }
   if(method.equals("GET")&&path.equals("/api/status")){
    JSONObject state=controller.status();
    state.put("remoteControl",allowControl&&controller.locallyAllowed());
    send(out,200,"application/json",state.toString());return;
   }
   if(method.equals("POST")&&path.equals("/api/command")){
    // Emergency requests remain available to an authenticated control session even
    // when ordinary manual outputs are locked; this never claims physical stop.
    if(!allowControl||(!controller.locallyAllowed()&&!"emergency".equals(payload.optString("type","")))){
     send(out,403,"application/json","{\"error\":\"local operation not authorized\"}");return;
    }
    String result=controller.command(payload);
    send(out,200,"application/json",new JSONObject().put("result",result).toString());return;
   }
   send(out,404,"application/json","{\"error\":\"not found\"}");
  }catch(Exception ex){
   synchronized(this){if(external&&current(bound,epoch)&&!tlsComplete)lastTlsError=detail(ex);}
   android.util.Log.w("EB-REMOTE","Request rejected: "+ex.getClass().getSimpleName());
  }finally{activeSockets.remove(socket);}
 }
 private synchronized boolean authorized(String header,SSLServerSocket bound,long epoch){
  if(!ready||!current(bound,epoch)||token.isEmpty()||System.currentTimeMillis()>tokenExpiry)return false;
  if(!header.startsWith("Bearer "))return false;
  return MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));
 }
 private byte[] readAsset()throws IOException{
  try(InputStream in=context.getAssets().open("mobile.html")){
   ByteArrayOutputStream buf=new ByteArrayOutputStream();
   byte[] bytes=new byte[4096];int n;
   while((n=in.read(bytes))!=-1)buf.write(bytes,0,n);
   return buf.toByteArray();
  }
 }
 private static void send(OutputStream out,int code,String type,String content)throws IOException{
  sendBytes(out,code,type,content.getBytes(StandardCharsets.UTF_8));
 }
 private static void sendBytes(OutputStream out,int code,String type,byte[] body)throws IOException{
  String status=code==200?"OK":code==400?"Bad Request":code==401?"Unauthorized":code==403?"Forbidden":code==404?"Not Found":code==413?"Payload Too Large":"Too Many Requests";
  String header="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: "+type+
    "\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"+
    "\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'"+
    "\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
  out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
 }
}
