#!/usr/bin/env python3
"""Run the actual Java gateway over TLS with platform shims; never opens a PLC.
AndroidKeyStore remains a device-only check. Test server keys exist only in tempdir.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JSON_URL = 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar'
JSON_SHA256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
STUBS = {
 'android/content/Context.java': '''package android.content;
public class Context {
 private final android.content.res.AssetManager assets;
 public Context(String path){assets=new android.content.res.AssetManager(path);}
 public Context getApplicationContext(){return this;}
 public android.content.res.AssetManager getAssets(){return assets;}
}''',
 'android/content/res/AssetManager.java': '''package android.content.res;
public class AssetManager {
 private final String path;public AssetManager(String path){this.path=path;}
 public java.io.InputStream open(String name)throws java.io.IOException{return new java.io.FileInputStream(new java.io.File(path,name));}
}''',
 'android/util/Base64.java': '''package android.util;
public final class Base64 {
 public static final int NO_WRAP=2,URL_SAFE=8;
 public static String encodeToString(byte[] data,int flags){return java.util.Base64.getUrlEncoder().encodeToString(data);}
}''',
 'android/util/Log.java': '''package android.util;public final class Log {public static int w(String tag,String msg){return 0;}}''',
 'android/security/KeyPairGeneratorSpec.java': '''package android.security;
public final class KeyPairGeneratorSpec implements java.security.spec.AlgorithmParameterSpec {
 public static class Builder {
 public Builder(android.content.Context c){}
 public Builder setAlias(String v){return this;}
 public Builder setSubject(javax.security.auth.x500.X500Principal v){return this;}
 public Builder setSerialNumber(java.math.BigInteger v){return this;}
 public Builder setStartDate(java.util.Date v){return this;}
 public Builder setEndDate(java.util.Date v){return this;}
 public KeyPairGeneratorSpec build(){throw new UnsupportedOperationException("AndroidKeyStore must be checked on device");}
 }
}'''
}

def run(args):
 subprocess.run(args, check=True, cwd=ROOT)

with tempfile.TemporaryDirectory(prefix='hmi-remote-test-') as temp:
 path=Path(temp)
 jar=path/'json.jar'
 cache=os.environ.get('HMI_TEST_JSON_JAR')
 data=Path(cache).read_bytes() if cache else urllib.request.urlopen(JSON_URL, timeout=40).read()
 if hashlib.sha256(data).hexdigest()!=JSON_SHA256:
  raise RuntimeError('Pinned JSON test dependency digest mismatch')
 jar.write_bytes(data)
 for name,content in STUBS.items():
  dest=path/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(content)
 dest=path/'com/energyblue/househmi';dest.mkdir(parents=True)
 for source in [ROOT/'app/src/main/java/com/energyblue/househmi/RemoteGateway.java',ROOT/'scripts/remote/RemoteGatewayRuntimeTest.java']:
  shutil.copy2(source,dest/source.name)
 classes=path/'classes';classes.mkdir()
 run(['keytool','-genkeypair','-alias','test-only','-keyalg','RSA','-keysize','2048','-sigalg','SHA256withRSA','-dname','CN=HMI Runtime Test','-validity','2','-storetype','PKCS12','-keystore',str(path/'test.p12'),'-storepass','test-only-password','-keypass','test-only-password','-noprompt'])
 run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(classes)]+[str(p) for p in path.rglob('*.java')])
 run(['java','-cp',str(classes)+os.pathsep+str(jar),'com.energyblue.househmi.RemoteGatewayRuntimeTest',str(path/'test.p12'),str(ROOT/'app/src/main/assets')])
