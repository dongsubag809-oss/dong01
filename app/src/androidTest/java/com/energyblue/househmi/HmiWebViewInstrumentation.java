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
 private void picture(String name)throws Exception{
  AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{
   android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(web.getWidth(),web.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
   web.draw(new android.graphics.Canvas(b));java.io.File f=new java.io.File(getTargetContext().getExternalFilesDir(null),name);
   try(java.io.FileOutputStream stream=new java.io.FileOutputStream(f)){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}b.recycle();
  }catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new Exception(failure.get());
 }
 private void sensorLayouts()throws Exception{
  // Only UI fixture data; no native command or physical output is invoked by these scenarios.
  js("window.sensorUiFixture=function(mode){var rows=[];for(var h=1;h<=5;h++)rows.push({house:h,config:{house:h,id:h,path:'/dev/ttyS6',baud:9600,fc:3,map:'h0t1',enabled:true,verified:true},registered:mode==='normal',connected:h===2,receivedAt:h===2?Date.now():0,temperature:h===2?24.6:null,humidity:h===2?61.2:null});var d=JSON.parse(JSON.stringify(rows[1]));d.houses=rows;d.selected=2;d.setup=mode==='normal'?0:2;d.searching=false;d.pending=null;if(mode==='setup'){d.config.id=1;d.houses[1].config.id=1;}if(mode==='pending'){d.pending={house:2,register:256,old:{id:1,baud:9600},next:{id:2,baud:9600},phase:'ACKNOWLEDGED'};d.connected=false;d.receivedAt=0;}EBSensor.receive(d);document.getElementById('sensorRoot').scrollIntoView();};true");
  for(int width:new int[]{1024,800}){
   runOnMainSync(()->{android.view.ViewGroup.LayoutParams lp=web.getLayoutParams();lp.width=width;lp.height=600;web.setLayoutParams(lp);});waitJs("window.innerWidth==="+width);
   for(String mode:new String[]{"normal","setup","pending"}){
    Object result=js("(function(){sensorUiFixture('"+mode+"');var root=document.getElementById('sensorRoot'),bounds=root.getBoundingClientRect(),nodes=root.querySelectorAll('button,summary,select,input,.sensor-steps li'),buttons=[];for(var n=0;n<nodes.length;n++){var e=nodes[n];if(!e.offsetWidth||!e.offsetHeight)continue;var r=e.getBoundingClientRect();if(r.left<bounds.left-1||r.right>bounds.right+1||e.scrollWidth>e.clientWidth+2)return 'overflow '+e.id+' '+e.tagName;if(e.tagName==='BUTTON')buttons.push(r);}for(var i=0;i<buttons.length;i++)for(var j=i+1;j<buttons.length;j++){var a=buttons[i],b=buttons[j];if(Math.min(a.right,b.right)-Math.max(a.left,b.left)>1&&Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top)>1)return 'overlapping buttons';}return 'ok';})()");
    check("ok".equals(result),width+" "+mode+" sensor layout: "+result);picture("sensor-"+width+"-"+mode+".png");
   }
   check(Boolean.TRUE.equals(js("(function(){sensorUiFixture('normal');document.getElementById('sensorAdvanced').open=true;var root=document.getElementById('sensorRoot'),bounds=root.getBoundingClientRect(),nodes=root.querySelectorAll('button,select,input');for(var i=0;i<nodes.length;i++){var e=nodes[i],r=e.getBoundingClientRect();if(e.offsetWidth&&(r.right>bounds.right+1||r.left<bounds.left-1||e.scrollWidth>e.clientWidth+2))return false;}return true;})()")),width+" advanced controls fit");
   js("document.getElementById('sensorAdvanced').open=false;true");
  }
  js("EBSensor.reload();true");
 }
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
  sensorLayouts();
  js("window.reportHmiFault('진단 시험','sensor.js',27,5,'test-stack');true");
  check(Boolean.TRUE.equals(js("document.getElementById('hmiFault').textContent.indexOf('sensor.js')>=0 && document.getElementById('hmiFault').textContent.indexOf('27')>=0")),"File/line diagnostic preserved");
  out.putString("userAgent",String.valueOf(js("navigator.userAgent")));out.putString("stream","HMI_WEBVIEW_PASS checks="+checks+" "+out.getString("javaBridgeProbe")+" "+out.getString("userAgent"));finish(Activity.RESULT_OK,out);
 }catch(Throwable e){out.putString("stream","HMI_WEBVIEW_FAIL "+e.toString());finish(Activity.RESULT_CANCELED,out);}
 finally{if(activity!=null)runOnMainSync(()->activity.finish());}}
}
