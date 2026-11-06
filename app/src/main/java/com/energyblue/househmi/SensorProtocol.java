package com.energyblue.househmi;

import java.io.IOException;

/** Read-only commissioning profiles. Similar appearance does not identify firmware. */
public final class SensorProtocol {
 private SensorProtocol(){}
 public static boolean validMap(String map){return "h0t1".equals(map)||"t0h1".equals(map);}
 public static byte[] request(int id,int fc,String map){
  if(id<1||id>247||(fc!=3&&fc!=4)||!validMap(map))throw new IllegalArgumentException("센서 ID/읽기 기능/맵 오류");
  return frame(id,fc,0,2);
 }
 private static byte[] frame(int id,int fc,int address,int count){
  byte[] q={(byte)id,(byte)fc,(byte)(address>>8),(byte)address,0,(byte)count,0,0};
  int crc=crc(q,6);q[6]=(byte)crc;q[7]=(byte)(crc>>8);return q;
 }
 public static int crc(byte[] data,int length){int crc=0xffff;for(int i=0;i<length;i++){crc^=data[i]&255;for(int b=0;b<8;b++)crc=(crc&1)!=0?(crc>>1)^0xa001:crc>>1;}return crc;}
 public static final class Reading {
  public final double temperature,humidity;
  Reading(double t,double h){temperature=t;humidity=h;}
 }
 public static Reading parse(byte[] r,int id,int fc,String map)throws IOException{
  if(!validMap(map))throw new IOException("통신맵 오류");
  if(r==null||r.length<5)throw new IOException("응답 없음/짧은 프레임");
  int crc=crc(r,r.length-2);
  if((r[r.length-2]&255)!=(crc&255)||(r[r.length-1]&255)!=(crc>>8))throw new IOException("응답 CRC 불일치");
  if((r[0]&255)!=id)throw new IOException("응답 ID 불일치");
  if((r[1]&255)==(fc|128)&&r.length==5)throw new IOException("Modbus 예외 코드 "+(r[2]&255));
  if(r.length!=9||(r[1]&255)!=fc||(r[2]&255)!=4)throw new IOException("응답 기능/길이 불일치");
  int a=((r[3]&255)<<8)|(r[4]&255),b=((r[5]&255)<<8)|(r[6]&255);
  double t=(short)("h0t1".equals(map)?b:a)/10.0,h=("h0t1".equals(map)?a:b)/10.0;
  if(t< -40||t>80||h<0||h>100)throw new IOException("변환값 범위 초과 · 순서/배율 확인 필요");
  return new Reading(t,h);
 }
 public static String hex(byte[] bytes){if(bytes==null)return "";StringBuilder s=new StringBuilder();for(byte b:bytes){if(s.length()>0)s.append(' ');s.append(String.format(java.util.Locale.ROOT,"%02X",b&255));}return s.toString();}
}
