package com.energyblue.househmi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReleaseUpdater {
 public interface Reporter {void report(String event,String message);}
 private final Activity activity;
 private final Reporter report;
 private final ExecutorService executor=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private volatile boolean closed=false;
 private volatile String apkUrl=null;
 private volatile String shaUrl=null;
 private volatile String expectedName=null;
 private volatile File verifiedApk=null;
 private static final String API="https://api.github.com/repos/dongsubag809-oss/dong01/releases/latest";
 ReleaseUpdater(Activity a,Reporter r){activity=a;report=r;}
 void close(){closed=true;executor.shutdownNow();}
 private void send(String type,String msg){if(!closed)report.report(type,msg);}
 void check(){executor.execute(()->{
  try{
   JSONObject release=new JSONObject(getText(API,350000));
   String tag=release.optString("tag_name","?");
   JSONArray assets=release.getJSONArray("assets");
   String apk=null,sha=null,name=null;
   for(int i=0;i<assets.length();i++){
    JSONObject item=assets.getJSONObject(i);
    String n=item.optString("name");
    String url=item.optString("browser_download_url");
    if(n.endsWith(".apk") && n.startsWith("ENERGYBLUE_")){apk=url;name=n;}
   }
   if(apk==null){send("update","릴리스에 ENERGYBLUE_*.apk 파일이 없습니다.");return;}
   for(int i=0;i<assets.length();i++){
    JSONObject item=assets.getJSONObject(i);
    if(item.optString("name").equals(name+".sha256"))sha=item.optString("browser_download_url");
   }
   if(sha==null){send("update","SHA-256 검증 파일이 없어 설치할 수 없습니다. "+name+".sha256 파일 필요");return;}
   if(!trusted(apk)||!trusted(sha))throw new IOException("잘못된 다운로드 주소");
   apkUrl=apk;shaUrl=sha;expectedName=name;verifiedApk=null;
   send("update","최신 배포: "+tag+" / 현재 "+BuildConfig.VERSION_NAME+" · 다운로드 준비 완료");
  }catch(Exception e){send("update","업데이트 확인 실패: "+e.getMessage());}
 });}
 void download(){executor.execute(()->{
  String link=apkUrl,sha=shaUrl,name=expectedName;
  if(link==null||sha==null){send("update","먼저 업데이트 확인을 눌러 주세요.");return;}
  File out=new File(activity.getCacheDir(),"energyblue-verified.apk");
  try{
   verifiedApk=null;
   String checksum=getText(sha,10000).trim();
   String expected=checksum.split("\\s+")[0];
   if(!expected.matches("(?i)[0-9a-f]{64}"))throw new IOException("SHA-256 파일 형식 오류");
   MessageDigest digest=MessageDigest.getInstance("SHA-256");
   HttpURLConnection conn=connect(link);
   int status=conn.getResponseCode();
   if(status!=200)throw new IOException("APK HTTP "+status);
   int len=conn.getContentLength();
   if(len>150_000_000)throw new IOException("APK 용량 초과");
   long total=0;
   try(InputStream in=conn.getInputStream();FileOutputStream stream=new FileOutputStream(out)){
    byte[] buf=new byte[32768];int n;
    while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("중단됨");total+=n;if(total>150_000_000)throw new IOException("APK 용량 초과");digest.update(buf,0,n);stream.write(buf,0,n);}
   }finally{conn.disconnect();}
   StringBuilder actual=new StringBuilder();
   for(byte b:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
   if(!actual.toString().equalsIgnoreCase(expected))throw new IOException("파일 SHA-256 불일치");
   verifiedApk=out;send("update","다운로드 및 SHA-256 검증 완료: "+name+" / 설치 버튼을 누르세요.");
  }catch(Exception e){out.delete();verifiedApk=null;send("update","다운로드 실패: "+e.getMessage());}
 });}
 void install(){
  File file=verifiedApk;
  if(file==null||!file.exists()){send("update","검증된 APK가 없습니다. 먼저 다운로드해 주세요.");return;}
  main.post(()->{
   try{
    if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
     send("update","알 수 없는 앱 설치 권한을 허용한 뒤 설치 버튼을 다시 눌러 주세요.");
     Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));
     activity.startActivity(settings);return;
    }
    Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".files",file);
    Intent intent=new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(uri,"application/vnd.android.package-archive");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    activity.startActivity(intent);
    send("update","Android 설치 승인 화면을 열었습니다. 기존 앱과 서명이 다르면 업데이트가 거부됩니다.");
   }catch(Exception e){send("update","설치 요청 실패: "+e.getMessage());}
  });
 }
 private static boolean trusted(String url){
  try{URL u=new URL(url);return "https".equalsIgnoreCase(u.getProtocol())&&
   ("api.github.com".equalsIgnoreCase(u.getHost())||"github.com".equalsIgnoreCase(u.getHost())||"objects.githubusercontent.com".equalsIgnoreCase(u.getHost())||
    "release-assets.githubusercontent.com".equalsIgnoreCase(u.getHost()));}catch(Exception e){return false;}
 }
 private static HttpURLConnection connect(String url)throws IOException{
  if(!trusted(url))throw new IOException("허용되지 않은 주소");
  URL current=new URL(url);
  for(int i=0;i<6;i++){
   HttpURLConnection c=(HttpURLConnection)current.openConnection();
   c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
   c.setRequestProperty("User-Agent","ENERGYBLUE-HMI-Updater");
   int status=c.getResponseCode();
   if(status>=300&&status<400){
    String location=c.getHeaderField("Location");c.disconnect();
    if(location==null)throw new IOException("리다이렉트 주소 없음");
    current=new URL(current,location);
    if(!"https".equalsIgnoreCase(current.getProtocol())||!(
      current.getHost().equalsIgnoreCase("github.com")||
      current.getHost().equalsIgnoreCase("objects.githubusercontent.com")||
      current.getHost().equalsIgnoreCase("release-assets.githubusercontent.com")))
      throw new IOException("허용되지 않은 리다이렉트");
   }else return c;
  }
  throw new IOException("리다이렉트 반복");
 }
 private static String getText(String url,int max)throws IOException {
  HttpURLConnection c=connect(url);
  try{
   if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   try(InputStream in=c.getInputStream()){
    byte[] buffer=new byte[4096];int n;
    while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>max)throw new IOException("응답 크기 초과");}
   }
   return out.toString("UTF-8");
  }finally{c.disconnect();}
 }
}
