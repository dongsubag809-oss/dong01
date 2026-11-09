package com.energyblue.househmi;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.*;
import org.json.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs production assets with the REAL Java bridge on an Android emulator. No physical IO. */
public final class HmiWebViewInstrumentation extends Instrumentation {
 private WebView web;private Activity activity;private int checks;
 public static final class Probe { @JavascriptInterface public String echo(String text){return text;} }
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 private Object js(String script)throws Exception{
  CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
  runOnMainSync(()->web.evaluateJavascript(script,r->{value.set(r);latch.countDown();}));
  if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("JS evaluation timeout");
  return new JSONTokener(value.get()).nextValue();
 }
 private void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
 private void waitJs(String expression)throws Exception{long until=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<until){if(Boolean.TRUE.equals(js(expression))){checks++;return;}SystemClock.sleep(100);}throw new AssertionError("JS condition timeout: "+expression);}
 @Override public void onStart(){Bundle out=new Bundle();try{
  Intent i=new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"com.energyblue.househmi.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  activity=startActivitySync(i);Field field=activity.getClass().getDeclaredField("browser");field.setAccessible(true);web=(WebView)field.get(activity);
  waitJs("typeof window.nativeMessage==='function' && !!document.getElementById('sensorRegistry')");
  check("".equals(js("document.getElementById('hmiFault').textContent")),"Actual Android startup script fault");
  runOnMainSync(()->{web.addJavascriptInterface(new Probe(),"JavaProbe");web.reload();});
  waitJs("typeof JavaProbe!=='undefined' && typeof window.EBSensor==='object' && !!document.getElementById('sensorRegistry')");
  out.putString("javaBridgeProbe",String.valueOf(js("JSON.stringify((function(){try{return {applyType:typeof JavaProbe.echo.apply,value:JavaProbe.echo.apply(JavaProbe,['probe'])};}catch(e){return {applyType:typeof JavaProbe.echo.apply,error:String(e)};}})())")));
  check("probe".equals(js("JavaProbe.echo('probe')")),"Direct real Java bridge call");
  js("window.EBAutomation.refresh(true);true");waitJs("window.EBAutomation._state.data!==null");
  js("window.EBSensor.selectHouse(2);true");waitJs("document.getElementById('sensorHouse').value==='2'");
  check(((Number)js("document.querySelectorAll('#sensorRegistry tr').length")).intValue()==5,"Five sensor houses rendered");
  js("show('env');true");check(Boolean.TRUE.equals(js("document.getElementById('env').className.indexOf('active')>=0")),"Environment navigation");
  check("한글".equals(js("(function(){kbBase='';kbState={l:'',v:'',t:''};'ㅎㅏㄴㄱㅡㄹ'.split('').forEach(function(c){kbWriteJamo(c);});return kbText();})()")),"Korean touch IME");
  check("".equals(js("document.getElementById('hmiFault').textContent")),"No script fault after actual bridge/refresh/navigation");
  js("window.reportHmiFault('진단 시험','sensor.js',27,5,'test-stack');true");
  check(Boolean.TRUE.equals(js("document.getElementById('hmiFault').textContent.indexOf('sensor.js')>=0 && document.getElementById('hmiFault').textContent.indexOf('27')>=0")),"File/line diagnostic preserved");
  out.putString("userAgent",String.valueOf(js("navigator.userAgent")));out.putString("stream","HMI_WEBVIEW_PASS checks="+checks+" "+out.getString("javaBridgeProbe")+" "+out.getString("userAgent"));finish(Activity.RESULT_OK,out);
 }catch(Throwable e){out.putString("stream","HMI_WEBVIEW_FAIL "+e.toString());finish(Activity.RESULT_CANCELED,out);}
 finally{if(activity!=null)runOnMainSync(()->activity.finish());}}
}
