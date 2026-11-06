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
import java.text.SimpleDateFormat;
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
 private final ExecutorService connections=Executors.newFixedThreadPool(4);
 private final SecureRandom random=new SecureRandom();
 private volatile boolean running=false,allowControl=false;
 private volatile String pairCode="",token="",fingerprint="";
 private volatile long codeExpiry=0,tokenExpiry=0;
 private int failedPairs=0;
 private long pairLockUntil=0;
 private SSLServerSocket server;
 private Thread listener;
 public RemoteGateway(Context context,Controller controller){this.context=context.getApplicationContext();this.controller=controller;}
 public synchronized JSONObject enable(boolean controls)throws Exception{
  close();
  SSLContext tls=sslContext();
  server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(PORT,6);
  server.setEnabledProtocols(new String[]{"TLSv1.2"});
  server.setSoTimeout(1000);
  running=true;allowControl=controls;
  pairCode=String.format(Locale.US,"%06d",random.nextInt(1000000));
  codeExpiry=System.currentTimeMillis()+300000;
  token="";tokenExpiry=0;failedPairs=0;pairLockUntil=0;
  listener=new Thread(this::serve,"hmi-remote-tls");
  listener.setDaemon(true);listener.start();
  return information();
 }
 public synchronized JSONObject information()throws Exception{
  return new JSONObject().put("enabled",running).put("port",PORT)
   .put("allowControl",allowControl).put("pairCode",running&&System.currentTimeMillis()<codeExpiry?pairCode:"만료 · 원격 서버 재시작")
   .put("certificateSha256",fingerprint);
 }
 public boolean isRunning(){return running;}
 public synchronized void setControls(boolean enabled){
  allowControl=enabled;
  // Changing privilege revokes all existing sessions. Re-pairing is required.
  token="";tokenExpiry=0;
  pairCode=String.format(Locale.US,"%06d",random.nextInt(1000000));
  codeExpiry=System.currentTimeMillis()+300000;
 }
 public synchronized void close(){
  running=false;allowControl=false;token="";tokenExpiry=0;pairCode="";codeExpiry=0;
  if(server!=null)try{server.close();}catch(Exception ignored){}
  server=null;
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
  byte[] digest=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
  StringBuilder sb=new StringBuilder();
  for(byte b:digest)sb.append(String.format(Locale.US,"%02X",b&255));
  fingerprint=sb.toString();
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
  km.init(store,null);
  SSLContext tls=SSLContext.getInstance("TLSv1.2");
  tls.init(km.getKeyManagers(),null,new SecureRandom());
  return tls;
 }
 private void serve(){
  while(running){
   try{
    SSLSocket socket=(SSLSocket)server.accept();
    socket.setEnabledProtocols(new String[]{"TLSv1.2"});
    socket.setSoTimeout(6000);
    connections.execute(()->handle(socket));
   }catch(SocketTimeoutException expected){}
    catch(Exception ex){if(running)android.util.Log.w("EB-REMOTE","TLS accept: "+ex.getClass().getSimpleName());}
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
 private void handle(SSLSocket socket){
  try(SSLSocket ss=socket){
   ss.startHandshake();
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
    sendBytes(out,200,"text/html; charset=utf-8",asset);return;
   }
   if(method.equals("POST")&&path.equals("/api/pair")){
    String given=payload.optString("code","");
    synchronized(this){
     long now=System.currentTimeMillis();
     if(now<pairLockUntil||now>codeExpiry||!running){
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
   if(!authorized(authorization)){
    send(out,401,"application/json","{\"error\":\"pairing required\"}");return;
   }
   if(method.equals("GET")&&path.equals("/api/status")){
    JSONObject state=controller.status();
    state.put("remoteControl",allowControl&&controller.locallyAllowed());
    send(out,200,"application/json",state.toString());return;
   }
   if(method.equals("POST")&&path.equals("/api/command")){
    if(!allowControl||!controller.locallyAllowed()){
     send(out,403,"application/json","{\"error\":\"local operation not authorized\"}");return;
    }
    String result=controller.command(payload);
    send(out,200,"application/json",new JSONObject().put("result",result).toString());return;
   }
   send(out,404,"application/json","{\"error\":\"not found\"}");
  }catch(Exception ex){
   android.util.Log.w("EB-REMOTE","Request rejected: "+ex.getClass().getSimpleName());
  }
 }
 private synchronized boolean authorized(String header){
  if(!running||token.isEmpty()||System.currentTimeMillis()>tokenExpiry)return false;
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
