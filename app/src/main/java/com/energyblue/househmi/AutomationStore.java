package com.energyblue.househmi;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** App-private persistent configuration and received-data history. Called off the UI thread. */
public final class AutomationStore extends SQLiteOpenHelper {
 private static final long RETENTION=366L*86400000L;
 private final long[] segmentId={0,0},lastTime={0,0};
 private final String[] lastBits={null,null};
 private long lastPrune=0;
 public AutomationStore(Context context){super(context,"energyblue_automation_v1.db",null,1);}
 @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE rules(h INTEGER NOT NULL,s INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(h,s))");
  db.execSQL("CREATE TABLE spans(id INTEGER PRIMARY KEY,board INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,bits TEXT NOT NULL)");
  db.execSQL("CREATE INDEX span_time ON spans(board,end,start)");
  db.execSQL("CREATE TABLE environment(h INTEGER NOT NULL,ts INTEGER NOT NULL,t_min REAL,t_max REAL,t_sum REAL NOT NULL DEFAULT 0,t_n INTEGER NOT NULL DEFAULT 0,r_min REAL,r_max REAL,r_sum REAL NOT NULL DEFAULT 0,r_n INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(h,ts))");
  db.execSQL("CREATE TABLE events(ts INTEGER NOT NULL,message TEXT NOT NULL)");
  db.execSQL("CREATE INDEX event_time ON events(ts)");
 }
 @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported history upgrade; preserve existing DB");}
 public synchronized JSONArray loadRules()throws Exception{
  JSONArray result=new JSONArray();
  try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM rules ORDER BY h,s",null)){while(c.moveToNext())result.put(new JSONObject(c.getString(0)));}
  return result;
 }
 public synchronized void saveRules(JSONArray rules)throws Exception{
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{
   for(int i=0;i<rules.length();i++){
    JSONObject rule=rules.getJSONObject(i);ContentValues values=new ContentValues();
    values.put("h",rule.getInt("house"));values.put("s",rule.getInt("slot"));values.put("body",rule.toString());
    db.insertWithOnConflict("rules",null,values,SQLiteDatabase.CONFLICT_REPLACE);
   }
   db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 public synchronized void board(int id,String bits,long now){
  if(id<1||id>2||bits==null||!bits.matches("[01]{64}"))return;
  SQLiteDatabase db=getWritableDatabase();int index=id-1;
  if(segmentId[index]>0&&bits.equals(lastBits[index])&&now>=lastTime[index]&&now-lastTime[index]<=20000){
   ContentValues v=new ContentValues();v.put("end",now);db.update("spans",v,"id=?",new String[]{Long.toString(segmentId[index])});
  }else{
   ContentValues v=new ContentValues();v.put("board",id);v.put("start",now);v.put("end",now);v.put("bits",bits);
   segmentId[index]=db.insertOrThrow("spans",null,v);lastBits[index]=bits;
  }
  lastTime[index]=now;prune(db,now);
 }
 public synchronized void disconnected(int id){if(id>=1&&id<=2){segmentId[id-1]=0;lastTime[id-1]=0;lastBits[id-1]=null;}}
 // Reserved for a future verified COM6 decoder. No JS/phone injection endpoint exists.
 synchronized void sensor(int house,double temperature,double humidity,long receivedAt){
  if(house<1||house>5||!AutomationLogic.finite(temperature)||!AutomationLogic.finite(humidity)||humidity<0||humidity>100)return;
  long minute=receivedAt/60000*60000;SQLiteDatabase db=getWritableDatabase();
  db.execSQL("INSERT OR IGNORE INTO environment(h,ts) VALUES(?,?)",new Object[]{house,minute});
  db.execSQL("UPDATE environment SET t_min=CASE WHEN t_n=0 THEN ? ELSE MIN(t_min,?) END,t_max=CASE WHEN t_n=0 THEN ? ELSE MAX(t_max,?) END,t_sum=t_sum+?,t_n=t_n+1,r_min=CASE WHEN r_n=0 THEN ? ELSE MIN(r_min,?) END,r_max=CASE WHEN r_n=0 THEN ? ELSE MAX(r_max,?) END,r_sum=r_sum+?,r_n=r_n+1 WHERE h=? AND ts=?",new Object[]{temperature,temperature,temperature,temperature,temperature,humidity,humidity,humidity,humidity,humidity,house,minute});
  prune(db,receivedAt);
 }
 public synchronized void event(String message){
  ContentValues v=new ContentValues();v.put("ts",System.currentTimeMillis());v.put("message",message);getWritableDatabase().insertOrThrow("events",null,v);
 }
 private void prune(SQLiteDatabase db,long now){
  if(now-lastPrune<86400000L)return;lastPrune=now;
  String[] cutoff={Long.toString(now-RETENTION)};
  db.delete("spans","end<?",cutoff);db.delete("environment","ts<?",cutoff);db.delete("events","ts<?",cutoff);
 }
 public synchronized JSONObject history(int house,int slot,String period,String date)throws Exception{
  Calendar end=Calendar.getInstance(AutomationLogic.ZONE);
  if(date!=null&&!date.isEmpty()){
   if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("날짜 형식 오류");
   String[] p=date.split("-");end.clear();end.setLenient(false);end.set(Integer.parseInt(p[0]),Integer.parseInt(p[1])-1,Integer.parseInt(p[2]));end.getTimeInMillis();end.add(Calendar.DAY_OF_MONTH,1);
  }
  long to=end.getTimeInMillis();Calendar begin=(Calendar)end.clone();
  long step;
  switch(period){
   case "live":begin.add(Calendar.HOUR_OF_DAY,-1);step=60000;break;
   case "day":begin.add(Calendar.DAY_OF_MONTH,-1);step=15*60000;break;
   case "week":begin.add(Calendar.DAY_OF_MONTH,-7);step=3*3600000L;break;
   case "month":begin.add(Calendar.MONTH,-1);step=86400000L;break;
   default:throw new IllegalArgumentException("조회 기간 오류");
  }
  long from=begin.getTimeInMillis();SQLiteDatabase db=getReadableDatabase();JSONArray environment=new JSONArray(),segments=new JSONArray(),bars=new JSONArray(),events=new JSONArray();
  long environmentOffset=step==86400000L?9*3600000L:0;
  String[] args={Long.toString(environmentOffset),Long.toString(step),Integer.toString(house),Long.toString(from),Long.toString(to)};
  try(Cursor c=db.rawQuery("SELECT ((ts + ?) / ?) AS bucket, MIN(t_min),MAX(t_max),SUM(t_sum)/NULLIF(SUM(t_n),0),MIN(r_min),MAX(r_max),SUM(r_sum)/NULLIF(SUM(r_n),0),SUM(t_n),SUM(r_n) FROM environment WHERE h=? AND ts>=? AND ts<? GROUP BY bucket ORDER BY bucket LIMIT 2000",args)){
   while(c.moveToNext()){
    JSONObject point=new JSONObject().put("ts",c.getLong(0)*step-environmentOffset);
    String[] names={"tMin","tMax","temperature","rMin","rMax","humidity"};
    for(int i=0;i<names.length;i++)point.put(names[i],c.isNull(i+1)?JSONObject.NULL:c.getDouble(i+1));
    point.put("tCount",c.getInt(7)).put("rCount",c.getInt(8));environment.put(point);
   }
  }
  int absolute=(house-1)*24+slot,board=absolute/64+1,channel=absolute%64;
  long barStep=period.equals("live")?600000:period.equals("day")?3600000:86400000L;
  // Align daily bars to KST midnight; hourly/minute buckets remain equally spaced.
  long offset=barStep==86400000L?9*3600000L:0;
  TreeMap<Long,long[]> totals=new TreeMap<>();long validMs=0,onMs=0;boolean truncated=false;
  try(Cursor c=db.rawQuery("SELECT start,end,bits FROM spans WHERE board=? AND end>=? AND start<? ORDER BY start LIMIT 10001",new String[]{Integer.toString(board),Long.toString(from),Long.toString(to)})){
   int count=0;
   while(c.moveToNext()){
    if(++count>10000){truncated=true;break;}
    long a=Math.max(from,c.getLong(0)),b=Math.min(to,c.getLong(1));if(b<=a)continue;
    boolean on=c.getString(2).charAt(channel)=='1';validMs+=b-a;if(on)onMs+=b-a;
    if(segments.length()<1000)segments.put(new JSONObject().put("start",a).put("end",b).put("on",on));
    else truncated=true;
    for(long at=a;at<b;){long bucket=(at+offset)/barStep*barStep-offset,next=Math.min(b,bucket+barStep);long[] duration=totals.get(bucket);if(duration==null){duration=new long[2];totals.put(bucket,duration);}duration[0]+=next-at;if(on)duration[1]+=next-at;at=next;}
   }
  }
  for(Map.Entry<Long,long[]> e:totals.entrySet())bars.put(new JSONObject().put("ts",e.getKey()).put("observedMs",e.getValue()[0]).put("onMs",e.getValue()[1]));
  try(Cursor c=db.rawQuery("SELECT ts,message FROM events WHERE ts>=? AND ts<? ORDER BY ts DESC LIMIT 30",new String[]{Long.toString(from),Long.toString(to)})){while(c.moveToNext())events.put(new JSONObject().put("ts",c.getLong(0)).put("message",c.getString(1)));}
  return new JSONObject().put("from",from).put("to",to).put("bucketMs",step).put("barBucketMs",barStep).put("environment",environment).put("segments",segments).put("bars",bars).put("events",events).put("observedMs",validMs).put("onMs",onMs).put("truncated",truncated).put("retentionDays",366);
 }
}
