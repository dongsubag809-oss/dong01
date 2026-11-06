package com.energyblue.househmi;

import java.io.*;

/** Independent native fd/library. Only documented ID/baud FC06 settings are permitted; no output writes. */
public final class SensorSerial {
 private static final boolean LOADED;
 static {boolean ok=false;try{System.loadLibrary("energyblue_sensor");ok=true;}catch(UnsatisfiedLinkError e){android.util.Log.e("ENERGYBLUE-HMI","Sensor driver unavailable",e);}LOADED=ok;}
 private SensorSerial(){}
 private static native String nativeOpen(String path,int baud);
 private static native byte[] nativeReadExchange(byte[] request,int timeoutMs)throws IOException;
 private static native void nativeClose();
 private static native byte[] nativeSettingExchange(byte[] request,int timeoutMs)throws IOException;
 public static boolean samePath(String a,String b){try{return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath());}catch(Exception ex){return a!=null&&a.equals(b);}}
 public static synchronized String open(String path,int baud){
  if(!LOADED)return "SENSOR_DRIVER_NOT_LOADED";
  if(path==null||!path.matches("/dev/tty[A-Za-z0-9._-]{1,64}"))return "센서 장치 경로 형식 오류";
  if(baud!=2400&&baud!=4800&&baud!=9600)return "지원 속도: 2400/4800/9600";
  return nativeOpen(path,baud);
 }
 public static synchronized byte[] read(int id,int fc,String map,int timeout)throws IOException{
  if(!LOADED)throw new IOException("SENSOR_DRIVER_NOT_LOADED");
  if(timeout<100||timeout>1500)throw new IOException("센서 타임아웃 범위 오류");
  return nativeReadExchange(SensorProtocol.request(id,fc,map),timeout);
 }
 public static synchronized byte[] setting(int id,int register,int value)throws IOException{
  if(!LOADED)throw new IOException("SENSOR_DRIVER_NOT_LOADED");
  return nativeSettingExchange(SensorProtocol.setting(id,register,value),1500);
 }
 public static synchronized void close(){if(LOADED)nativeClose();}
}
