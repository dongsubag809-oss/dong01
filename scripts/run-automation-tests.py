#!/usr/bin/env python3
"""Compile production decisions/controller/history; execute SQL through SQLite JDBC.
Android shims are test-only. No real COM port or control output is opened.
"""
from pathlib import Path
import hashlib,os,shutil,subprocess,tempfile,urllib.request
ROOT=Path(__file__).resolve().parents[1]
DEPS=[
 ('json.jar','https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar','3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'),
 ('sqlite-jdbc.jar','https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.46.0.0/sqlite-jdbc-3.46.0.0.jar','e697df15be3f95219d80773c5f1002030e33e932adda186c1c86fd51df6691a9'),
 ('slf4j-api.jar','https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar','e7c2a48e8515ba1f49fa637d57b4e2f590b3f5bd97407ac699c3aa5efb1204a9')]
STUBS={
'android/content/Context.java':'''package android.content;public class Context {public final java.io.File root;public Context(String path){root=new java.io.File(path);root.mkdirs();}public Context getApplicationContext(){return this;}}''',
'android/content/ContentValues.java':'''package android.content;public class ContentValues extends java.util.LinkedHashMap<String,Object>{private static final long serialVersionUID=1L;}''',
'android/os/SystemClock.java':'''package android.os;public final class SystemClock {public static long elapsedRealtime(){return java.lang.System.nanoTime()/1000000;}}''',
'android/database/Cursor.java':'''package android.database;public final class Cursor implements AutoCloseable {private final java.util.List<Object[]> rows;private int position=-1;public Cursor(java.sql.ResultSet result)throws java.sql.SQLException {rows=new java.util.ArrayList<>();int cols=result.getMetaData().getColumnCount();while(result.next()){Object[] row=new Object[cols];for(int i=0;i<cols;i++)row[i]=result.getObject(i+1);rows.add(row);}}public boolean moveToNext(){return ++position<rows.size();}public String getString(int i){return String.valueOf(rows.get(position)[i]);}public long getLong(int i){return ((Number)rows.get(position)[i]).longValue();}public int getInt(int i){return ((Number)rows.get(position)[i]).intValue();}public double getDouble(int i){return ((Number)rows.get(position)[i]).doubleValue();}public boolean isNull(int i){return rows.get(position)[i]==null;}public void close(){}}''',
'android/database/sqlite/SQLiteDatabase.java':'''package android.database.sqlite;
import java.sql.*;import java.util.*;import android.content.ContentValues;import android.database.Cursor;
public final class SQLiteDatabase implements AutoCloseable {
 public static final int CONFLICT_REPLACE=5;final Connection c;boolean success=false;
 public SQLiteDatabase(java.io.File file){try{Class.forName("org.sqlite.JDBC");c=DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());}catch(Exception e){throw new RuntimeException(e);}}
 public void setForeignKeyConstraintsEnabled(boolean enabled){execSQL("PRAGMA foreign_keys="+(enabled?1:0));}
 public void execSQL(String sql){execSQL(sql,new Object[0]);}
 public void execSQL(String sql,Object[] args){try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);p.execute();}catch(SQLException e){throw new RuntimeException(sql,e);}}
 private void bind(PreparedStatement p,Object[] args)throws SQLException{if(args!=null)for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);}
 public Cursor rawQuery(String sql,String[] args){try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);try(ResultSet r=p.executeQuery()){return new Cursor(r);}}catch(SQLException e){throw new RuntimeException(sql,e);}}
 private String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
 public long insertOrThrow(String table,String nullable,ContentValues values){return insertWithOnConflict(table,nullable,values,0);}
 public long insertWithOnConflict(String table,String nullable,ContentValues values,int mode){String columns=String.join(",",values.keySet());execSQL("INSERT "+(mode==5?"OR REPLACE ":"")+"INTO "+table+"("+columns+") VALUES("+marks(values.size())+")",values.values().toArray());try(Cursor r=rawQuery("SELECT last_insert_rowid()",null)){r.moveToNext();return r.getLong(0);}}
 public int update(String table,ContentValues values,String where,String[] args){List<Object> bound=new ArrayList<>(values.values());if(args!=null)bound.addAll(Arrays.asList(args));List<String> columns=new ArrayList<>();for(String key:values.keySet())columns.add(key+"=?");execSQL("UPDATE "+table+" SET "+String.join(",",columns)+" WHERE "+where,bound.toArray());return 1;}
 public int delete(String table,String where,String[] args){execSQL("DELETE FROM "+table+" WHERE "+where,args);return 1;}
 public void beginTransaction(){try{success=false;c.setAutoCommit(false);}catch(SQLException e){throw new RuntimeException(e);}}
 public void setTransactionSuccessful(){success=true;}
 public void endTransaction(){try{if(success)c.commit();else c.rollback();c.setAutoCommit(true);}catch(SQLException e){throw new RuntimeException(e);}}
 public void close(){try{c.close();}catch(SQLException e){throw new RuntimeException(e);}}
}''',
'android/database/sqlite/SQLiteOpenHelper.java':'''package android.database.sqlite;import android.content.Context;import android.database.Cursor;
public abstract class SQLiteOpenHelper implements AutoCloseable {private final java.io.File file;private final int version;private SQLiteDatabase db;public SQLiteOpenHelper(Context context,String name,Object factory,int version){file=new java.io.File(context.root,name);this.version=version;}public void onConfigure(SQLiteDatabase db){}public abstract void onCreate(SQLiteDatabase db);public abstract void onUpgrade(SQLiteDatabase db,int old,int next);public synchronized SQLiteDatabase getWritableDatabase(){if(db==null){db=new SQLiteDatabase(file);onConfigure(db);int old;try(Cursor c=db.rawQuery("PRAGMA user_version",null)){c.moveToNext();old=c.getInt(0);}if(old==0)onCreate(db);else if(old!=version)onUpgrade(db,old,version);db.execSQL("PRAGMA user_version="+version);}return db;}public SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}public synchronized void close(){if(db!=null){db.close();db=null;}}}'''
}
with tempfile.TemporaryDirectory(prefix='hmi-auto-test-') as name:
 temp=Path(name);jars=[]
 for filename,url,digest in DEPS:
  path=temp/filename;cache=os.environ.get('HMI_TEST_DEPS')
  data=(Path(cache)/filename).read_bytes() if cache else urllib.request.urlopen(url,timeout=40).read()
  if hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Dependency digest mismatch: '+filename)
  path.write_bytes(data);jars.append(str(path))
 for filename,content in STUBS.items():
  path=temp/filename;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
 target=temp/'com/energyblue/househmi';target.mkdir(parents=True)
 for filename in ['AutomationLogic.java','AutomationStore.java','AutomationController.java']:
  shutil.copy2(ROOT/'app/src/main/java/com/energyblue/househmi'/filename,target/filename)
 shutil.copy2(ROOT/'scripts/automation/AutomationRuntimeTest.java',target/'AutomationRuntimeTest.java')
 classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(jars)
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes)]+[str(p) for p in temp.rglob('*.java')],check=True)
 subprocess.run(['java','-cp',str(classes)+os.pathsep+cp,'com.energyblue.househmi.AutomationRuntimeTest',str(temp/'db')],check=True)
