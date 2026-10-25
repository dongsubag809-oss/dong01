package com.energyblue.househmi;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONObject;

public final class MainActivity extends Activity {
 private WebView browser;
 @Override public void onCreate(Bundle b) { super.onCreate(b);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
  browser=new WebView(this); browser.setWebViewClient(new WebViewClient()); browser.setWebChromeClient(new WebChromeClient());
  browser.getSettings().setJavaScriptEnabled(true);
  browser.getSettings().setAllowFileAccess(true);
  browser.getSettings().setAllowFileAccessFromFileURLs(false);
  browser.getSettings().setAllowUniversalAccessFromFileURLs(false);
  browser.addJavascriptInterface(new NativeDiagnostics(),"NativeDiagnostics");
  setContentView(browser); browser.loadUrl("file:///android_asset/index.html");
 }
 @Override protected void onDestroy(){if(browser!=null){browser.destroy();}super.onDestroy();}
 public final class NativeDiagnostics {
  @JavascriptInterface public String probe(String path){
   try {JSONObject obj=new JSONObject();
    if(path==null||!path.matches("/dev/[a-zA-Z0-9_./-]+")||path.contains("..")){obj.put("result","장치 경로 형식 오류");return obj.toString();}
    File file=new File(path);obj.put("path",path);obj.put("exists",file.exists());obj.put("readable",file.canRead());obj.put("writable",file.canWrite());
    obj.put("result",file.exists()?"장치 파일 확인됨 (RS485 통신 미검증)":"장치 파일 없음");return obj.toString();
   }catch(Exception ex){return "{\"result\":\"진단 오류\"}";}
  }
 }
}