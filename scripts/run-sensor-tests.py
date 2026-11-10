#!/usr/bin/env python3
"""Production sensor Java/worker tests with a fake transport; no real device access."""
from pathlib import Path
import os,subprocess,tempfile,shutil,urllib.request,hashlib
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='hmi-sensor-') as name:
 p=Path(name);src=p/'com/energyblue/househmi';src.mkdir(parents=True)
 for f in ['SensorProtocol.java','SensorController.java','SensorSerial.java']:shutil.copy2(root/'app/src/main/java/com/energyblue/househmi'/f,src/f)
 shutil.copy2(root/'scripts/sensor/SensorTest.java',src/'SensorTest.java')
 log=p/'android/util/Log.java';log.parent.mkdir(parents=True);log.write_text('package android.util;public class Log {public static int e(String a,String b,Throwable c){return 0;}}')
 cache=os.environ.get('HMI_TEST_DEPS');data=(Path(cache)/'json.jar').read_bytes() if cache else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(data).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
 jar=p/'json.jar';jar.write_bytes(data);classes=p/'classes';classes.mkdir()
 subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(classes)]+[str(f) for f in p.rglob('*.java')],check=True)
 subprocess.run(['java','-cp',str(classes)+os.pathsep+str(jar),'com.energyblue.househmi.SensorTest'],check=True)
