package com.energyblue.househmi;

/** COM5 RS485 port and restricted N4D4F64 read-only Modbus exchange. */
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
 private static native byte[] nativeReadExchange(byte[] request,int timeoutMs) throws java.io.IOException;
 private static boolean opened=false;
 public static synchronized String open(String path,int baud) {
  if(!LOADED)return "SERIAL_DRIVER_NOT_LOADED";
  if(path==null || !path.matches("/dev/tty[A-Za-z0-9._-]{1,64}"))return "COM5_DEVICE_PATH_REQUIRED";
  if(baud!=9600&&baud!=19200&&baud!=38400&&baud!=57600&&baud!=115200)return "UNSUPPORTED_BAUD";
  String result=nativeOpen(path,baud);
  opened="OPEN_OK".equals(result);
  return result;
 }
 public static synchronized byte[] exchangeRead(byte[] request) throws java.io.IOException {
  if(!LOADED)throw new java.io.IOException("SERIAL_DRIVER_NOT_LOADED");
  if(!opened)throw new java.io.IOException("COM5 포트를 먼저 여세요");
  if(request==null||request.length!=8||(request[1]&255)!=3||(request[0]&255)!=1&&(request[0]&255)!=2)
   throw new java.io.IOException("읽기 전용 FC03 / Slave ID 1·2만 허용");
  if((request[4]&255)!=0||(request[5]&255)!=4)throw new java.io.IOException("읽기 레지스터 4개만 허용");
  int address=((request[2]&255)<<8)|(request[3]&255);
  if(address!=112&&address!=192&&address!=128&&address!=144)throw new java.io.IOException("읽기 주소 허용 목록에 없음");
  int crc=ModbusReadOnly.crc16(request,6);
  if((request[6]&255)!=(crc&255)||(request[7]&255)!=(crc>>>8))throw new java.io.IOException("요청 CRC 형식 불일치");
  return nativeReadExchange(request,1200);
 }
 public static synchronized void close(){opened=false;if(LOADED)nativeClose();}
}
