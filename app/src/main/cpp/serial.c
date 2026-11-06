#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <poll.h>
#include <time.h>
#include <stdint.h>

static void raise_io(JNIEnv *env, const char *message) {
 jclass klass=(*env)->FindClass(env,"java/io/IOException");
 if(klass)(*env)->ThrowNew(env,klass,message);
}
static int64_t now_msec(void){
 struct timespec t;
 clock_gettime(CLOCK_MONOTONIC,&t);
 return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int await_fd(int target,short events,int64_t deadline){
 struct pollfd p={.fd=target,.events=events,.revents=0};
 while(1){
  int64_t remain=deadline-now_msec();
  if(remain<=0)return 0;
  int wait=(remain>2000)?2000:(int)remain;
  int result=poll(&p,1,wait);
  if(result<0&&errno==EINTR)continue;
  if(result<=0)return result;
  if(p.revents&(POLLERR|POLLNVAL|POLLHUP)){errno=EIO;return -1;}
  if(p.revents&events)return 1;
 }
}


static int fd = -1;
static speed_t speed_of(int baud) {
 switch (baud) {
 case 9600:return B9600; case 19200:return B19200; case 38400:return B38400;
 case 57600:return B57600; case 115200:return B115200; default:return 0;
 }
}
JNIEXPORT jstring JNICALL Java_com_energyblue_househmi_SerialPort_nativeOpen(
 JNIEnv *env,jclass cls,jstring device,jint baud) {
 (void)cls;
 const char *p=(*env)->GetStringUTFChars(env,device,0);
 if (!p) return (*env)->NewStringUTF(env,"INVALID_PATH");
 if (strncmp(p,"/dev/tty",8)!=0 || strstr(p,"..")) {
  (*env)->ReleaseStringUTFChars(env,device,p);
  return (*env)->NewStringUTF(env,"INVALID_DEVICE");
 }
 if (fd >= 0) {close(fd);fd=-1;}
 fd=open(p,O_RDWR|O_NOCTTY|O_NONBLOCK|O_CLOEXEC);
 int saved=errno;
 (*env)->ReleaseStringUTFChars(env,device,p);
 if (fd<0) {
  char error[128]; snprintf(error,sizeof(error),"OPEN_FAILED errno=%d (%s)",saved,strerror(saved));
  return (*env)->NewStringUTF(env,error);
 }
 speed_t sp=speed_of(baud);
 if (!sp) {close(fd);fd=-1;return (*env)->NewStringUTF(env,"UNSUPPORTED_BAUD");}
 struct termios cfg;
 if (tcgetattr(fd,&cfg)!=0) {
  saved=errno;close(fd);fd=-1;
  char error[100];snprintf(error,sizeof(error),"TERMIOS_FAILED errno=%d",saved);
  return (*env)->NewStringUTF(env,error);
 }
 cfmakeraw(&cfg);
 cfg.c_cflag=(cfg.c_cflag&~(CSIZE|PARENB|CSTOPB|CRTSCTS))|CS8|CLOCAL|CREAD;
 cfg.c_iflag &= ~(IXON|IXOFF|IXANY);
 cfg.c_cc[VMIN]=0;cfg.c_cc[VTIME]=0;
 cfsetispeed(&cfg,sp);cfsetospeed(&cfg,sp);
 if(tcsetattr(fd,TCSANOW,&cfg)!=0) {
  saved=errno;close(fd);fd=-1;
  char error[100];snprintf(error,sizeof(error),"CONFIG_FAILED errno=%d",saved);
  return (*env)->NewStringUTF(env,error);
 }
 tcflush(fd,TCIFLUSH);
 return (*env)->NewStringUTF(env,"OPEN_OK");
}
JNIEXPORT void JNICALL Java_com_energyblue_househmi_SerialPort_nativeClose(JNIEnv *env,jclass cls) {
 (void)env;(void)cls;
 if(fd>=0){close(fd);fd=-1;}
}

/* Restricted interface: exact four-register FC03 from ID 1/2 only.
 * Native layer rejects all write functions even if Java validation changes.
 * Response CRC and slave ID checked again in ModbusReadOnly.parse64().
 */
JNIEXPORT jbyteArray JNICALL Java_com_energyblue_househmi_SerialPort_nativeReadExchange(
 JNIEnv *env,jclass cls,jbyteArray request,jint timeout_ms){
 (void)cls;
 if(fd<0){raise_io(env,"COM5_NOT_OPEN");return NULL;}
 if(!request||(*env)->GetArrayLength(env,request)!=8){raise_io(env,"INVALID_READ_LENGTH");return NULL;}
 jbyte bytes[8];
 (*env)->GetByteArrayRegion(env,request,0,8,bytes);
 unsigned char *q=(unsigned char*)bytes;
 int addr=(q[2]<<8)|q[3];
 int count=(q[4]<<8)|q[5];
 if((q[0]!=1&&q[0]!=2)||q[1]!=3||
    !((count==4&&(addr==112||addr==192||addr==128||addr==144||addr==245))
      ||(count==1&&addr==250))){
  raise_io(env,"FC03_REGISTER_WHITELIST_ENFORCED");return NULL;
 }
 if(timeout_ms<100||timeout_ms>5000){raise_io(env,"INVALID_TIMEOUT");return NULL;}
 int64_t deadline=now_msec()+timeout_ms;
 if(tcflush(fd,TCIFLUSH)!=0){raise_io(env,"SERIAL_RX_FLUSH_FAILED");return NULL;}
 size_t sent=0;
 while(sent<8){
  int ok=await_fd(fd,POLLOUT,deadline);
  if(ok<=0){raise_io(env,ok==0?"TX_TIMEOUT":"TX_POLL_ERROR");return NULL;}
  ssize_t n=write(fd,q+sent,8-sent);
  if(n>0){sent+=(size_t)n;continue;}
  if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;
  raise_io(env,"SERIAL_WRITE_FAILED");return NULL;
 }
 unsigned char response[32]={0};
 size_t received=0,need=3;
 while(received<need){
  int ok=await_fd(fd,POLLIN,deadline);
  if(ok<=0){raise_io(env,ok==0?"RESPONSE_TIMEOUT":"RX_POLL_ERROR");return NULL;}
  ssize_t n=read(fd,response+received,need-received);
  if(n>0){received+=(size_t)n;}
  else if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;
  else if(n<0){raise_io(env,"SERIAL_READ_FAILED");return NULL;}
  if(received>=3&&need==3){
   if(response[1]==(unsigned char)0x83)need=5;
   else if(response[1]==3&&response[2]==count*2)need=5+count*2;
   else{raise_io(env,"UNEXPECTED_RESPONSE_HEADER");return NULL;}
  }
 }
 jbyteArray out=(*env)->NewByteArray(env,(jsize)need);
 if(out)(*env)->SetByteArrayRegion(env,out,0,(jsize)need,(jbyte*)response);
 return out;
}


/* Manual N4D4F64 commissioning write. Validate against legacy command 1/2
 * single-channel frames in native and Java, then return PLC echo unchanged.
 * Never repeat a write after a timeout: actuation status is then UNKNOWN.
 */
JNIEXPORT jbyteArray JNICALL Java_com_energyblue_househmi_SerialPort_nativeOutputExchange(
 JNIEnv *env,jclass cls,jbyteArray request,jint timeout_ms){
 (void)cls;
 if(fd<0){raise_io(env,"COM5_NOT_OPEN");return NULL;}
 if(!request||(*env)->GetArrayLength(env,request)!=8){
  raise_io(env,"INVALID_OUTPUT_LENGTH");return NULL;
 }
 jbyte bytes[8];
 (*env)->GetByteArrayRegion(env,request,0,8,bytes);
 unsigned char *q=(unsigned char*)bytes;
 int address=(q[2]<<8)|q[3],value=(q[4]<<8)|q[5],fc=q[1];
 if((q[0]!=1&&q[0]!=2)||address>63||
   !((fc==6&&(value==0x0100||value==0x0200))||
     (fc==5&&(value==0xFF00||value==0)))){
  raise_io(env,"DISALLOWED_N4D4F64_OUTPUT_FRAME");return NULL;
 }
 if(timeout_ms<100||timeout_ms>5000){raise_io(env,"INVALID_WRITE_TIMEOUT");return NULL;}
 int64_t deadline=now_msec()+timeout_ms;
 if(tcflush(fd,TCIFLUSH)!=0){raise_io(env,"OUTPUT_RX_FLUSH_FAILED");return NULL;}
 size_t sent=0;
 while(sent<8){
  int ok=await_fd(fd,POLLOUT,deadline);
  if(ok<=0){raise_io(env,ok==0?"OUTPUT_TX_TIMEOUT":"OUTPUT_TX_POLL_ERROR");return NULL;}
  ssize_t n=write(fd,q+sent,8-sent);
  if(n>0){sent+=(size_t)n;continue;}
  if(n<0&&(errno==EINTR||errno==EAGAIN||errno==EWOULDBLOCK))continue;
  raise_io(env,"OUTPUT_WRITE_FAILED");return NULL;
 }
 if(tcdrain(fd)!=0){raise_io(env,"OUTPUT_TRANSMIT_DRAIN_FAILED");return NULL;}
 unsigned char response[8]={0};size_t got=0,need=2;
 while(got<need){
  int ok=await_fd(fd,POLLIN,deadline);
  if(ok<=0){raise_io(env,ok==0?"OUTPUT_ECHO_TIMEOUT":"OUTPUT_ECHO_POLL_ERROR");return NULL;}
  ssize_t n=read(fd,response+got,need-got);
  if(n>0){got+=(size_t)n;}
  else if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;
  else if(n<0){raise_io(env,"OUTPUT_READ_FAILED");return NULL;}
  if(got==2&&need==2){
   if(response[1]==(unsigned char)(fc|0x80))need=5;
   else if(response[1]==fc)need=8;
   else{raise_io(env,"OUTPUT_RESPONSE_FUNC_MISMATCH");return NULL;}
  }
 }
 jbyteArray out=(*env)->NewByteArray(env,(jsize)need);
 if(out)(*env)->SetByteArrayRegion(env,out,0,(jsize)need,(jbyte*)response);
 return out;
}
