package com.energyblue.househmi;

/** COM5: bounded FC03 reads plus guarded single DO commissioning writes. */
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
 private static native byte[] nativeOutputExchange(byte[] request,int timeoutMs) throws java.io.IOException;
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
  int address=((request[2]&255)<<8)|(request[3]&255);
  int count=((request[4]&255)<<8)|(request[5]&255);
  if(!((count==4&&(address==112||address==192||address==128||address==144||address==245))
      ||(count==1&&address==250)))
   throw new java.io.IOException("검증되지 않은 읽기 주소/길이 차단");
  int crc=ModbusReadOnly.crc16(request,6);
  if((request[6]&255)!=(crc&255)||(request[7]&255)!=(crc>>>8))throw new java.io.IOException("요청 CRC 형식 불일치");
  return nativeReadExchange(request,1200);
 }
 /** No retries for physical output operations. Call only from guarded background worker. */
 public static synchronized byte[] exchangeOutput(byte[] request)throws java.io.IOException{
  if(!LOADED||!opened)throw new java.io.IOException("COM5_NOT_OPEN");
  if(request==null||request.length!=8)throw new java.io.IOException("잘못된 출력 명령 길이");
  int slave=request[0]&255,fc=request[1]&255;
  int addr=((request[2]&255)<<8)|(request[3]&255);
  int value=((request[4]&255)<<8)|(request[5]&255);
  if((slave!=1&&slave!=2)||addr>63||
      !((fc==6&&(value==0x0100||value==0x0200))
        ||(fc==5&&(value==0xFF00||value==0))))
   throw new java.io.IOException("허용되지 않은 출력 명령 / Slave / 주소");
  int crc=ModbusReadOnly.crc16(request,6);
  if((request[6]&255)!=(crc&255)||(request[7]&255)!=(crc>>>8))
   throw new java.io.IOException("출력 요청 CRC 오류");
  return nativeOutputExchange(request,1200);
 }
 public static synchronized boolean isOpen(){return LOADED&&opened;}
 public static synchronized void close(){opened=false;if(LOADED)nativeClose();}
}
