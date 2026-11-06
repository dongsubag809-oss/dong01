package com.energyblue.househmi;

import java.io.IOException;
import java.util.Arrays;

/**
 * N4D4F64 one-channel ON/OFF commissioning.
 * Commands copied from previous functioning n4d4f64.py:
 * M0 open: FC06, 0-based channel, 0x0100 ON / 0x0200 OFF.
 * M0 shorted: FC05, 0-based channel, 0xFF00 ON / 0x0000 OFF.
 * No multi-coil, toggle, momentary, or address-changing commands.
 */
public final class ModbusOutput {
 private ModbusOutput(){}
 public static byte[] request(int slave,boolean m0Shorted,int channel,boolean on){
  if(slave!=1&&slave!=2)throw new IllegalArgumentException("Slave ID 1·2만 허용");
  if(channel<1||channel>64)throw new IllegalArgumentException("DO 채널 범위 1~64");
  int func=m0Shorted?5:6;
  int value=m0Shorted?(on?0xFF00:0x0000):(on?0x0100:0x0200);
  byte[] b=new byte[]{(byte)slave,(byte)func,0,(byte)(channel-1),(byte)(value>>>8),(byte)value,0,0};
  int crc=ModbusReadOnly.crc16(b,6);
  b[6]=(byte)crc;b[7]=(byte)(crc>>>8);
  return b;
 }
 public static void verifyEcho(byte[] sent,byte[] reply)throws IOException{
  if(reply==null||reply.length<5)throw new IOException("출력 명령 응답 없음");
  int crc=((reply[reply.length-1]&255)<<8)|(reply[reply.length-2]&255);
  if(crc!=ModbusReadOnly.crc16(reply,reply.length-2))
   throw new IOException("출력 응답 CRC 오류");
  if((reply[0]&255)!=(sent[0]&255))throw new IOException("출력 응답 Slave ID 불일치");
  if((reply[1]&255)==((sent[1]&255)|128))
   throw new IOException("출력 Modbus 예외 코드 "+(reply[2]&255));
  if(reply.length!=8||!Arrays.equals(sent,reply))
   throw new IOException("출력 응답 명령 반향 불일치");
 }
 public static void writeOnce(int slave,boolean m0Shorted,int channel,boolean on)throws IOException{
  byte[] req=request(slave,m0Shorted,channel,on);
  verifyEcho(req,SerialPort.exchangeOutput(req));
 }
}
