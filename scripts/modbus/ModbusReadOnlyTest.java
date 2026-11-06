package com.energyblue.househmi;

import java.io.IOException;
import java.util.Arrays;

/** Test frames and channel endian order against the older N4D4F64 Python implementation. */
public final class ModbusReadOnlyTest {
 private static void check(boolean okay,String msg){if(!okay)throw new AssertionError(msg);}
 private static byte[] response(int id,int[] words){
  byte[] result=new byte[13];result[0]=(byte)id;result[1]=3;result[2]=8;
  for(int i=0;i<4;i++){result[3+i*2]=(byte)(words[i]>>>8);result[4+i*2]=(byte)(words[i]);}
  int crc=ModbusReadOnly.crc16(result,11);
  result[11]=(byte)crc;result[12]=(byte)(crc>>>8);
  return result;
 }
 public static void main(String[] args)throws Exception{
  check(Arrays.equals(ModbusReadOnly.addresses(false),new int[]{112,192}),"default addresses");
  check(Arrays.equals(ModbusReadOnly.addresses(true),new int[]{128,144}),"shorted M0 addresses");
  byte[] f=ModbusReadOnly.request(1,112);
  check((f[0]&255)==1&&(f[1]&255)==3&&(f[2]&255)==0&&(f[3]&255)==112&&(f[5]&255)==4,"FC03 default frame");
  check(ModbusReadOnly.crc16(f,6)==(((f[7]&255)<<8)|(f[6]&255)),"request CRC little endian");
  byte[] data=response(2,new int[]{0x8001,0x0000,0x0002,0x4000});
  boolean[] bits=ModbusReadOnly.parse64(2,data);
  check(bits.length==64&&bits[0]&&bits[15]&&bits[33]&&bits[62],"channel bit order");
  check(!bits[1]&&!bits[16]&&!bits[63],"inactive bit order");
  data[3]^=1;
  try{ModbusReadOnly.parse64(2,data);throw new AssertionError("CRC corruption accepted");}
  catch(IOException expected){check(expected.getMessage().contains("CRC"),"CRC failure reason");}
  try{ModbusReadOnly.request(1,77);throw new AssertionError("unknown address accepted");}
  catch(IllegalArgumentException expected){}
  byte[] exception=new byte[]{1,(byte)0x83,2,0,0};
  int ecrc=ModbusReadOnly.crc16(exception,3);exception[3]=(byte)ecrc;exception[4]=(byte)(ecrc>>>8);
  try{ModbusReadOnly.parse64(1,exception);throw new AssertionError("exception accepted");}
  catch(IOException expected){check(expected.getMessage().contains("예외"),"modbus error handling");}
  System.out.println("PASS: N4D4F64 FC03 CRC, register maps, DI/DO bit order and exception handling");
 }
}