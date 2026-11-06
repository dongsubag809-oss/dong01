package com.energyblue.househmi;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

public final class RemoteGatewayRuntimeTest {
 static SSLContext serverTls,clientTls;
 static String fingerprint,assets;
 static int checks=0;
 static class Control implements RemoteGateway.Controller {
  boolean allowed=false;int commands=0;
  public JSONObject status()throws Exception{return new JSONObject().put("actualReadback",true);}
  public boolean locallyAllowed(){return allowed;}
  public String command(JSONObject request){commands++;return "queued";}
 }
 static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 static void field(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 static RemoteGateway gateway(Control control)throws Exception{
  RemoteGateway result=new RemoteGateway(new Context(assets),control,()->serverTls);
  field(result,"fingerprint",fingerprint);return result;
 }
 static SSLSocket connect(String host)throws Exception{
  SSLSocket socket=(SSLSocket)clientTls.getSocketFactory().createSocket();
  socket.connect(new InetSocketAddress(host,18443),2000);socket.setSoTimeout(4500);
  socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.startHandshake();return socket;
 }
 static class Reply {
  int code;String body;
  Reply(int code,String body){this.code=code;this.body=body;}
  JSONObject json(){return new JSONObject(body);}
 }
 static Reply request(String method,String path,String token,String json,String origin,String host)throws Exception{
  try(SSLSocket socket=connect(host)){
   byte[] body=(json==null?"":json).getBytes(StandardCharsets.UTF_8);
   String header=method+" "+path+" HTTP/1.1\r\nHost: "+host+":18443\r\nAuthorization: Bearer "+token+"\r\n"+
    (origin==null?"":"Origin: "+origin+"\r\n")+"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
   socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
   try{while((n=socket.getInputStream().read(buf))!=-1)bytes.write(buf,0,n);}catch(SocketException ex){if(bytes.size()==0)throw ex;}
   String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
   int split=text.indexOf("\r\n\r\n");
   if(split<0)throw new IOException("Incomplete HTTP reply: "+text);
   return new Reply(Integer.parseInt(text.split(" ")[1]),text.substring(split+4));
  }
 }
 static Reply req(String method,String path,String token,String json)throws Exception{return request(method,path,token,json,null,"127.0.0.1");}
 static String pair(RemoteGateway gateway)throws Exception{
  String code=gateway.information().getString("pairCode");
  Reply result=req("POST","/api/pair","",new JSONObject().put("code",code).toString());
  check(result.code==200,"Valid one-time pairing must succeed");return result.json().getString("token");
 }
 public static void main(String[] args)throws Exception{
  assets=args[1];
  KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(args[0])){keys.load(in,"test-only-password".toCharArray());}
  X509Certificate certificate=(X509Certificate)keys.getCertificate("test-only");
  StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()))hash.append(String.format(Locale.US,"%02X",b&255));fingerprint=hash.toString();
  serverTls=SSLContext.getInstance("TLSv1.2");serverTls.init(new KeyManager[]{RemoteGateway.tlsIdentity("test-only",(PrivateKey)keys.getKey("test-only","test-only-password".toCharArray()),new X509Certificate[]{certificate})},null,new SecureRandom());
  KeyStore trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null,null);trusted.setCertificateEntry("exact-hmi-test",certificate);
  TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(trusted);
  clientTls=SSLContext.getInstance("TLSv1.2");clientTls.init(null,trust.getTrustManagers(),new SecureRandom());
  Control control=new Control();RemoteGateway gateway=gateway(control);
  try{
   check(!gateway.information().getBoolean("enabled"),"Disabled on creation");
   JSONObject info=gateway.enable(false);
   check(info.getBoolean("selfTestOk")&&info.getBoolean("enabled"),"ON requires real pinned TLS and complete root page");
   check(info.getLong("lastTcpAt")==0&&info.getLong("lastPageAt")==0,"Loopback test must not pretend a phone connected");
   check(req("GET","/","",null).body.contains("HMI 연결 인증"),"Serve the real Korean mobile asset over TLS");
   check(req("GET","/api/status","",null).code==401,"Unauthenticated status denied");
   check(req("POST","/api/command","","{\"type\":\"emergency\"}").code==401,"Unauthenticated emergency denied");
   check(req("POST","/api/pair","","{\"code\":\"x\"}").code==403,"Wrong pair code denied");
   String firstCode=info.getString("pairCode"),token=pair(gateway);
   check(req("POST","/api/pair","",new JSONObject().put("code",firstCode).toString()).code==429,"Pairing code cannot be reused");
   check(req("GET","/api/status",token,null).json().getBoolean("actualReadback"),"Authorized status uses controller readback");
   check(req("POST","/api/command",token,"{\"type\":\"output\"}").code==403&&control.commands==0,"Monitor session cannot write");
   gateway.setControls(true);
   check(req("GET","/api/status",token,null).code==401,"Changing privileges revokes existing token");
   token=pair(gateway);
   check(req("POST","/api/command",token,"{\"type\":\"output\"}").code==403,"Control requires local manual authorization");
   check(req("POST","/api/command",token,"{\"type\":\"emergency\"}").code==200&&control.commands==1,"Authenticated control emergency reaches the same controller");
   control.allowed=true;
   check(req("POST","/api/command",token,"{\"type\":\"output\"}").code==200&&control.commands==2,"Locally authorized control reaches controller");
   check(request("POST","/api/command",token,"{\"type\":\"output\"}","https://other.example","127.0.0.1").code==403&&control.commands==2,"Cross-origin control rejected");
   field(gateway,"tokenExpiry",0L);
   check(req("GET","/api/status",token,null).code==401,"Expired token denied");
   gateway.enable(true);
   for(int i=0;i<5;i++)check(req("POST","/api/pair","","{\"code\":\"x\"}").code==403,"Bad code attempt "+i);
   check(req("POST","/api/pair","",new JSONObject().put("code",gateway.information().getString("pairCode")).toString()).code==429,"Five failures lock pairing");
   // A restart must release old sockets/listeners and create only a new session.
   SSLSocket old=connect("127.0.0.1");
   for(int i=0;i<6;i++){
    gateway.enable(true);
    check(req("GET","/","",null).code==200,"Restart keeps HTTPS accepting "+i);
   }
   try{old.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));old.getOutputStream().flush();check(old.getInputStream().read()==-1,"Old generation must be closed");}catch(IOException expected){checks++;}finally{old.close();}
   check(req("GET","/api/status",token,null).code==401,"Restart revokes prior session");
   // Try the host's actual non-loopback IPv4 address, so the diagnostic can
   // distinguish a browser attempt from its own loopback self-test.
   String lan=null;
   for(NetworkInterface nif:Collections.list(NetworkInterface.getNetworkInterfaces()))if(nif.isUp()&&!nif.isLoopback())for(InetAddress a:Collections.list(nif.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&!a.isLinkLocalAddress())lan=a.getHostAddress();
   if(lan!=null){
    try(Socket plain=new Socket(lan,18443)){
     plain.setSoTimeout(2000);plain.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));plain.getOutputStream().flush();plain.shutdownOutput();
     try{plain.getInputStream().read();}catch(IOException expected){}
    }
    for(int i=0;i<100&&gateway.information().getString("lastTlsError").isEmpty();i++)Thread.sleep(10);
    check(gateway.information().getLong("lastTcpAt")>0&&!gateway.information().getString("lastTlsError").isEmpty(),"Plain HTTP produces a visible TLS error after TCP arrival");
    check(request("GET","/","",null,null,lan).code==200,"Wildcard IPv4 listener serves LAN address");
    info=gateway.information();
    check(info.getLong("lastTlsAt")>0&&info.getLong("lastPageAt")>0&&info.getString("lastTlsError").isEmpty(),"Real page response clears old TLS error");
   }
   gateway.close();check(!gateway.information().getBoolean("enabled"),"Stop disables listener");
   try(ServerSocket occupied=new ServerSocket()){
    occupied.setReuseAddress(true);occupied.bind(new InetSocketAddress("0.0.0.0",18443));
    try{gateway.enable(false);throw new AssertionError("Port conflict must fail");}catch(BindException expected){checks++;}
    info=gateway.information();check(!info.getBoolean("enabled")&&info.getString("state").equals("failed")&&!info.getString("error").isEmpty(),"Port conflict must never display ON and must retain the cause");
   }
   field(gateway,"fingerprint","0".repeat(64));
   try{gateway.enable(false);throw new AssertionError("Wrong pinned certificate accepted");}catch(SSLException expected){checks++;}
   check(!gateway.isRunning()&&!gateway.information().getBoolean("selfTestOk"),"Wrong certificate fails closed before issuing pairing code");
   field(gateway,"fingerprint",fingerprint);
   gateway.enable(false);check(gateway.isRunning(),"Valid TLS recovers after failed startup");
  }finally{gateway.dispose();}
  try{gateway.enable(false);throw new AssertionError("Disposed gateway restarted");}catch(IOException expected){checks++;}
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  RemoteGateway cancelled=new RemoteGateway(new Context(assets),new Control(),()->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test timeout");return serverTls;});
  field(cancelled,"fingerprint",fingerprint);AtomicReference<Throwable> outcome=new AtomicReference<>();
  Thread worker=new Thread(()->{try{cancelled.enable(false);}catch(Throwable error){outcome.set(error);}});
  worker.start();check(entered.await(2,TimeUnit.SECONDS),"Slow key loading started");cancelled.close();release.countDown();worker.join(5000);
  check(!worker.isAlive()&&outcome.get()!=null&&!cancelled.isRunning(),"Stopping during key loading must cancel startup without resurrecting the listener");cancelled.dispose();
  System.out.println("PASS: "+checks+" real TLS/HTTP checks: pinned certificate, complete mobile page, authentication, privilege/session revocation, guards, restart/cancel, port conflict, IPv4 LAN and diagnostic failures. AndroidKeyStore/hardware not simulated.");
 }
}
