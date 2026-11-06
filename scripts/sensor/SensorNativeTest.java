package com.energyblue.househmi;
import java.lang.reflect.*;
public final class SensorNativeTest {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void plc()throws Exception{byte[] response=SerialPort.exchangeRead(ModbusReadOnly.request(1,112));check(ModbusReadOnly.parse64(1,response).length==64,"COM5 fd changed");}
 public static void main(String[] args)throws Exception{
  check("OPEN_OK".equals(SerialPort.open(args[0],9600)),"PLC PTY open");
  check("OPEN_OK".equals(SensorSerial.open(args[1],9600)),"sensor PTY open");plc();
  byte[] r=SensorSerial.read(1,3,"h0t1",300);check(SensorProtocol.parse(r,1,3,"h0t1").temperature==25.1,"fragmented native read");
  r=SensorSerial.read(1,3,"h0t1",300);check(SensorProtocol.parse(r,1,3,"h0t1").temperature==-10.1,"signed response");
  r=SensorSerial.read(1,3,"h0t1",300);try{SensorProtocol.parse(r,1,3,"h0t1");throw new AssertionError("CRC accepted");}catch(java.io.IOException expected){checks++;}
  long start=System.nanoTime();r=SensorSerial.read(1,3,"h0t1",300);check(r.length==0,"silent timeout");check((System.nanoTime()-start)/1000000<1300,"bounded timeout");
  r=SensorSerial.read(1,3,"h0t1",300);check(r.length==3,"partial raw response preserved");
  r=SensorSerial.read(1,3,"h0t1",300);check(r.length==5,"Modbus exception");try{SensorProtocol.parse(r,1,3,"h0t1");throw new AssertionError();}catch(java.io.IOException expected){check(expected.getMessage().contains("예외"),"exception decoded");}
  r=SensorSerial.read(1,4,"h0t1",300);check(SensorProtocol.parse(r,1,4,"h0t1").humidity==56.3,"FC04 native read");
  Method method=SensorSerial.class.getDeclaredMethod("nativeReadExchange",byte[].class,int.class);method.setAccessible(true);
  for(int fc:new int[]{5,6,16}){byte[] q=SensorProtocol.request(1,3,"h0t1");q[1]=(byte)fc;int crc=SensorProtocol.crc(q,6);q[6]=(byte)crc;q[7]=(byte)(crc>>8);try{method.invoke(null,q,300);throw new AssertionError("write escaped native guard");}catch(InvocationTargetException e){check(e.getCause() instanceof java.io.IOException,"native write rejection");}}
  SensorSerial.close();plc();check("OPEN_OK".equals(SensorSerial.open(args[1],4800)),"4800 native open");
  SerialPort.close();r=SensorSerial.read(1,3,"h0t1",300);check(SensorProtocol.parse(r,1,3,"h0t1").temperature==25.1,"COM5 close must not close COM6");SensorSerial.close();
  System.out.println("PASS: "+checks+" actual JNI/PTY checks: independent COM5/COM6 fds, fragmented RX, CRC, signed values, silent/partial timeouts, exceptions, FC04 and native write rejection");
 }
}
