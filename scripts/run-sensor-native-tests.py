#!/usr/bin/env python3
"""Compile the unchanged production JNI files, use isolated PTY peers (no hardware).
A temporary /dev/ttyEBTest* symlink targets each newly allocated PTY, removed on exit.
Ubuntu CI uses sudo solely to create/remove these test-owned symlinks.
"""
from pathlib import Path
import os,pty,select,threading,tempfile,subprocess,shutil,time
ROOT=Path(__file__).resolve().parents[1]
def crc(data):
 c=65535
 for b in data:
  c^=b
  for _ in range(8):c=(c>>1)^0xa001 if c&1 else c>>1
 return c.to_bytes(2,'little')
def framed(data):return data+crc(data)
errors=[];stop=threading.Event();requests=[0,0,0]
def emulate(fd,sensor):
 pending=b''
 while not stop.is_set():
  try:
   if not select.select([fd],[],[],.05)[0]:continue
   pending+=os.read(fd,256)
   while len(pending)>=8:
    q,pending=pending[:8],pending[8:]
    if q[-2:]!=crc(q[:6]):raise AssertionError('request CRC')
    if sensor:
     if q[1]==6:
      reg=int.from_bytes(q[2:4],'big');value=int.from_bytes(q[4:6],'big')
      if not ((reg==256 and 1<=value<=247) or (reg==257 and value<=2)):raise AssertionError('illegal setting on wire')
      requests[2]+=1;os.write(fd,q);continue
     if q[1] not in (3,4):raise AssertionError('output write reached sensor wire')
     requests[1]+=1;n=requests[1]
     if n==4:continue
     if n==5:r=b'\x01\x03\x04'
     elif n==6:r=framed(bytes([q[0],q[1]|128,2]))
     else:r=framed(bytes([q[0],q[1],4,2,51,255 if n==2 else 0,155 if n==2 else 251]))
     if n==3:r=r[:-1]+bytes([r[-1]^1])
     os.write(fd,r[:2]);time.sleep(.005);os.write(fd,r[2:])
    else:
     requests[0]+=1
     if q[1]!=3 or q[2:6]!=bytes([0,112,0,4]):raise AssertionError('PLC request changed')
     os.write(fd,framed(bytes([q[0],3,8])+bytes(8)))
  except OSError:time.sleep(.01)
  except BaseException as ex:errors.append(str(ex));return
with tempfile.TemporaryDirectory(prefix='sensor-jni-') as name:
 tmp=Path(name);src=tmp/'com/energyblue/househmi';src.mkdir(parents=True)
 include=Path(os.environ.get('HMI_JNI_INCLUDE',str(Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))/'include')))
 assert (include/'jni.h').exists(),'JDK JNI headers required'
 for lib,file in [('energyblue_sensor','sensor_serial.c'),('energyblue_serial','serial.c')]:subprocess.run(['gcc','-shared','-fPIC','-Wall','-Wextra','-Werror','-I'+str(include),'-I'+str(include/'linux'),str(ROOT/'app/src/main/cpp'/file),'-o',str(tmp/('lib'+lib+'.so'))],check=True)
 for f in ['SensorSerial.java','SensorProtocol.java','SerialPort.java','ModbusReadOnly.java']:shutil.copy2(ROOT/'app/src/main/java/com/energyblue/househmi'/f,src/f)
 shutil.copy2(ROOT/'scripts/sensor/SensorNativeTest.java',src/'SensorNativeTest.java')
 log=tmp/'android/util/Log.java';log.parent.mkdir(parents=True);log.write_text('package android.util;public class Log {public static int e(String a,String b,Throwable c){return 0;}}')
 classes=tmp/'classes';classes.mkdir();subprocess.run(['javac','-encoding','UTF-8','-d',str(classes)]+[str(f) for f in tmp.rglob('*.java')],check=True)
 peers=[pty.openpty(),pty.openpty()];aliases=[Path('/dev/ttyEBTest'+str(os.getpid())+str(i)) for i in range(2)];prefix=[] if os.geteuid()==0 else ['sudo']
 try:
  for i,(master,slave) in enumerate(peers):
   subprocess.run(prefix+['ln','-s',os.ttyname(slave),str(aliases[i])],check=True)
   threading.Thread(target=emulate,args=(master,i==1),daemon=True).start()
  subprocess.run(['java','-Djava.library.path='+str(tmp),'-cp',str(classes),'com.energyblue.househmi.SensorNativeTest']+[str(a) for a in aliases],check=True,timeout=25)
  assert not errors,errors
  assert requests[0]==2 and requests[1]==8 and requests[2]==2,requests
  print('PASS: wire capture contains allowed reads and exact supplier ID/baud settings; no physical equipment opened')
 finally:
  stop.set()
  for alias in aliases:
   if alias.is_symlink():subprocess.run(prefix+['rm',str(alias)],check=True)
  for master,slave in peers:os.close(master);os.close(slave)
