package com.energyblue.househmi;

import java.io.IOException;

/**
 * N4D4F64 read-only FC03. Addresses are from previously used n4d4f64.py.
 * Default M0 open: DO 112, DI 192; M0 shorted: DO 128, DI 144.
 * No FC05/FC06/FC0F/FC10 writes are supported in this Android class.
 */
public final class ModbusReadOnly {
 private ModbusReadOnly(){}
 public static int[] addresses(boolean m0Shorted){
  return m0Shorted ? new int[]{128,144} : new int[]{112,192};
 }
 public static int crc16(byte[] data,int size){
  int crc=0xffff;
  for(int i=0;i<size;i++){
   crc^=data[i]&0xff;
   for(int j=0;j<8;j++)crc=(crc&1)!=0 ? (crc>>>1)^0xA001 : crc>>>1;
  }
  return crc&0xffff;
 }
 public static byte[] request(int slave,int address) {
  if(slave!=1&&slave!=2)throw new IllegalArgumentException("Slave ID must be 1 or 2");
  if(address!=112&&address!=192&&address!=128&&address!=144)throw new IllegalArgumentException("Unverified register address");
  byte[] body=new byte[]{(byte)slave,3,(byte)(address>>>8),(byte)address,0,4,0,0};
  int crc=crc16(body,6);
  body[6]=(byte)crc;
  body[7]=(byte)(crc>>>8);
  return body;
 }
 public static boolean[] parse64(int slave,byte[] response)throws IOException{
  if(response==null||response.length<5)throw new IOException("응답 길이 부족");
  if((response[0]&255)!=slave)throw new IOException("PLC Slave ID 불일치");
  int received=((response[response.length-1]&255)<<8)|(response[response.length-2]&255);
  int computed=crc16(response,response.length-2);
  if(received!=computed)throw new IOException("CRC 오류: 수신값과 계산값 불일치");
  if((response[1]&255)==0x83)throw new IOException("PLC Modbus 예외 코드 "+(response[2]&255));
  if((response[1]&255)!=3||response.length!=13||(response[2]&255)!=8)
   throw new IOException("FC03 응답 코드/8바이트 길이 오류");
  boolean[] channels=new boolean[64];
  for(int i=0;i<4;i++){
   int word=((response[3+i*2]&255)<<8)|(response[4+i*2]&255);
   for(int bit=0;bit<16;bit++)channels[i*16+bit]=((word>>>bit)&1)!=0;
  }
  return channels;
 }
 public static boolean[] read64(int slave,int address)throws IOException{
  return parse64(slave,SerialPort.exchangeRead(request(slave,address)));
 }
 public static String summarize(boolean[] values){
  StringBuilder sb=new StringBuilder();
  int count=0;
  for(int i=0;i<values.length;i++){
   if(!values[i])continue;
   if(count>0)sb.append(", ");
   sb.append(i+1);
   count++;
  }
  return "ON "+count+"/64 채널: "+(count==0?"없음":sb.toString());
 }
}
