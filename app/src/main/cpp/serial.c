#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>

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
