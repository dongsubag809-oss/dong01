package com.energyblue.househmi;

/** Opens the configured COM5 device only. No Modbus read/write or PLC address changes. */
public final class SerialPort {
 private static final boolean LOADED;
 static {
  boolean loaded=false;
  try {System.loadLibrary("energyblue_serial");loaded=true;}
  catch(UnsatisfiedLinkError e){android.util.Log.e("ENERGYBLUE-HMI","Serial native driver unavailable",e);}
  LOADED=loaded;
 }
 private SerialPort(){}
 private static native String nativeOpen(String device,int baud);
 private static native void nativeClose();
 public static synchronized String open(String path,int baud) {
  if(!LOADED)return "SERIAL_DRIVER_NOT_LOADED";
  if(path==null || !path.matches("/dev/tty[A-Za-z0-9._-]{1,64}"))return "COM5_DEVICE_PATH_REQUIRED";
  if(baud!=9600&&baud!=19200&&baud!=38400&&baud!=57600&&baud!=115200)return "UNSUPPORTED_BAUD";
  return nativeOpen(path,baud);
 }
 public static synchronized void close(){if(LOADED)nativeClose();}
}
