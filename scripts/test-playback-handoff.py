#!/usr/bin/env python3
"""Host simulation of the real playback service; uses fake Android and native IO.
Requires JDK 17+. It does not test real AudioTrack timing or codecs.
"""
from pathlib import Path
import tempfile, subprocess
work=tempfile.TemporaryDirectory(prefix='loopdeck-playback-')
root=Path(work.name)
repo=Path(__file__).resolve().parents[1]
def put(name,text):
 p=root/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
classes={
'android/content/Context':'public static final String AUDIO_SERVICE="audio",POWER_SERVICE="power",NOTIFICATION_SERVICE="notification"; public Object getSystemService(String s){ return s.equals(AUDIO_SERVICE)?new android.media.AudioManager():s.equals(POWER_SERVICE)?new android.os.PowerManager():new android.app.NotificationManager(); } public ContentResolver getContentResolver(){return new ContentResolver();} public void registerReceiver(BroadcastReceiver r,IntentFilter f){} public void unregisterReceiver(BroadcastReceiver r){}',
'android/content/ContentResolver':'public android.os.ParcelFileDescriptor openFileDescriptor(android.net.Uri u,String mode){return new android.os.ParcelFileDescriptor(u.id);}',
'android/content/Intent':'String action; public Intent(Context c,Class<?> t){} public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}',
'android/content/IntentFilter':'public IntentFilter(String a){}',
'android/content/BroadcastReceiver':'public abstract void onReceive(Context c,Intent i);',
'android/app/Service':'public static final int START_NOT_STICKY=2; public void onCreate(){} public android.os.IBinder onBind(android.content.Intent i){return null;} public int onStartCommand(android.content.Intent i,int f,int id){return 0;} public void onDestroy(){} public android.os.Looper getMainLooper(){return new android.os.Looper();} public void startForeground(int i,Notification n){} public void stopForeground(boolean b){} public void stopSelf(){}',
'android/app/NotificationManager':'public static final int IMPORTANCE_LOW=1; public void createNotificationChannel(NotificationChannel c){} public void notify(int i,Notification n){}',
'android/app/NotificationChannel':'public NotificationChannel(String a,String b,int c){}',
'android/app/PendingIntent':'public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2; public static PendingIntent getService(android.content.Context c,int i,android.content.Intent n,int f){return new PendingIntent();} public static PendingIntent getActivity(android.content.Context c,int i,android.content.Intent n,int f){return new PendingIntent();}',
'android/os/IBinder':'',
'android/os/Binder':'',
'android/os/Looper':'',
'android/os/Handler':'public Handler(Looper l){} public void post(Runnable r){r.run();}',
'android/os/PowerManager':'public static final int PARTIAL_WAKE_LOCK=1; public WakeLock newWakeLock(int a,String b){return new WakeLock();} public static class WakeLock {boolean held; public void setReferenceCounted(boolean b){} public boolean isHeld(){return held;} public void acquire(){held=true;} public void release(){held=false;}}',
'android/os/Process':'public static final int THREAD_PRIORITY_AUDIO=-16,THREAD_PRIORITY_BACKGROUND=10; public static void setThreadPriority(int p){}',
'android/os/SystemClock':'public static long elapsedRealtime(){return System.nanoTime()/1000000;}',
'android/os/ParcelFileDescriptor':'int fd; public ParcelFileDescriptor(int id){fd=id;} public int getFd(){return fd;} public void close(){}',
'android/net/Uri':'public final int id; public Uri(int id){this.id=id;}',
'android/media/AudioManager':'public static final int AUDIOFOCUS_GAIN=1,AUDIOFOCUS_REQUEST_GRANTED=1,AUDIOFOCUS_LOSS=-1,AUDIOFOCUS_LOSS_TRANSIENT=-2,AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK=-3; public static final String ACTION_AUDIO_BECOMING_NOISY="noisy"; public int requestAudioFocus(AudioFocusRequest f){return 1;} public void abandonAudioFocusRequest(AudioFocusRequest f){}',
'android/media/AudioFocusRequest':'public static class Builder { public Builder(int i){} public Builder setAudioAttributes(AudioAttributes a){return this;} public Builder setOnAudioFocusChangeListener(java.util.function.IntConsumer c,android.os.Handler h){return this;} public AudioFocusRequest build(){return new AudioFocusRequest();}}',
'android/media/AudioAttributes':'public static final int USAGE_MEDIA=1,CONTENT_TYPE_MUSIC=1; public static class Builder{ public Builder setUsage(int i){return this;} public Builder setContentType(int i){return this;} public AudioAttributes build(){return new AudioAttributes();}}',
'android/media/AudioFormat':'public static final int CHANNEL_OUT_STEREO=2,ENCODING_PCM_FLOAT=4; public static class Builder{public Builder setSampleRate(int i){return this;} public Builder setEncoding(int i){return this;} public Builder setChannelMask(int i){return this;} public AudioFormat build(){return new AudioFormat();}}',
'android/media/MediaMetadata':'public static final String METADATA_KEY_TITLE="title",METADATA_KEY_DURATION="duration"; public static class Builder{public Builder putString(String k,String v){return this;} public Builder putLong(String k,long v){return this;} public MediaMetadata build(){return new MediaMetadata();}}',
'android/media/session/MediaSession':'public MediaSession(android.content.Context c,String s){} public void setCallback(Callback c){} public void setActive(boolean b){} public void release(){} public Object getSessionToken(){return this;} public void setMetadata(android.media.MediaMetadata m){} public void setPlaybackState(PlaybackState p){} public static class Callback{public void onPlay(){} public void onPause(){} public void onStop(){} public void onSkipToNext(){} public void onSkipToPrevious(){} public void onSeekTo(long p){}}',
'android/media/session/PlaybackState':'public static final long ACTION_PLAY=1,ACTION_PAUSE=2,ACTION_PLAY_PAUSE=4,ACTION_STOP=8,ACTION_SEEK_TO=16,ACTION_SKIP_TO_NEXT=32,ACTION_SKIP_TO_PREVIOUS=64; public static final int STATE_PLAYING=1,STATE_PAUSED=2; public static class Builder{public Builder setActions(long a){return this;} public Builder setState(int s,long p,float v){return this;} public PlaybackState build(){return new PlaybackState();}}',
'android/R':'public static class drawable{public static final int ic_media_previous=1,ic_media_pause=2,ic_media_play=3,ic_media_next=4,ic_menu_close_clear_cancel=5;}',
'net/astropenguin/loopdeck/R':'public static class drawable{public static final int ic_music=1;}',
'net/astropenguin/loopdeck/MainActivity':'',
}
for name,body in classes.items():
 pkg,cls=name.rsplit('/',1); modifier='abstract ' if cls=='BroadcastReceiver' else ''; kind='interface' if cls=='IBinder' else 'class';extends=' extends android.content.Context' if cls=='Service' else ' implements IBinder' if cls=='Binder' else ' implements AutoCloseable' if cls=='ParcelFileDescriptor' else ''
 put(name,f'package {pkg.replace("/",".")}; public {modifier}{kind} {cls}{extends} '+'{'+body+'}')
put('android/app/Notification','''package android.app;
public class Notification {
public static final int VISIBILITY_PUBLIC=1;
public static class Builder {
public Builder(android.content.Context c,String ch){}
public Builder setSmallIcon(int i){return this;} public Builder setContentTitle(String s){return this;} public Builder setContentText(String s){return this;} public Builder setContentIntent(PendingIntent p){return this;} public Builder setOnlyAlertOnce(boolean b){return this;} public Builder setOngoing(boolean b){return this;} public Builder setVisibility(int i){return this;} public Builder addAction(Action a){return this;} public Builder setStyle(MediaStyle m){return this;} public Notification build(){return new Notification();}
}
public static class Action{ public static class Builder{public Builder(int i,String s,PendingIntent p){} public Action build(){return new Action();}}}
public static class MediaStyle{public MediaStyle setMediaSession(Object o){return this;} public MediaStyle setShowActionsInCompactView(int... i){return this;}}
}''')
put('android/media/AudioTrack','''package android.media;
public class AudioTrack {
public static final int STATE_INITIALIZED=1,MODE_STREAM=1,WRITE_NON_BLOCKING=1;
public static int created,flushed,rejectedRate; public long head,written; public boolean playing;
public final java.util.ArrayList<Float> samples=new java.util.ArrayList<>();
public static int getMinBufferSize(int a,int b,int c){return a==rejectedRate?-1:4096;}
public int getState(){return 1;} public void release(){} public void pause(){playing=false;} public void play(){playing=true;}
public void flush(){flushed++;head=written=0; samples.clear();} public void setVolume(float f){} public int getPlaybackHeadPosition(){return (int)head;}
public int write(float[] data,int offset,int count,int mode){int n=(int)Math.min(count,Math.max(0,4410-(written-head))*2);for(int i=0;i<n;i++) samples.add(data[offset+i]);written+=n/2;return n;}
public void advance(int frames){if(playing)head=Math.min(written,head+frames);}
public static class Builder{public Builder setAudioAttributes(AudioAttributes a){return this;}public Builder setAudioFormat(AudioFormat a){return this;}public Builder setBufferSizeInBytes(int i){return this;}public Builder setTransferMode(int i){return this;}public AudioTrack build(){created++;return new AudioTrack();}}
}''')
put('net/astropenguin/loopdeck/NativeDecoder','''package net.astropenguin.loopdeck;
final class NativeDecoder {
static long seq; static final java.util.concurrent.ConcurrentHashMap<Long,int[]> states=new java.util.concurrent.ConcurrentHashMap<>();
static synchronized long open(int fd){long h=++seq;states.put(h,new int[]{fd,0});return h;}
static void close(long h){if(states.remove(h)==null)throw new AssertionError("double close");}
static int sourceRate(long h){return states.get(h)[0]==4?96000:44100;}
static long duration(long h){return size(states.get(h)[0])*1000/sourceRate(h);}
static int size(int id){return id==1?7003:id==2?9007:6001;}
static String description(long h){return "fake lossless";}
static void outputRate(long h,int r){}
static void seek(long h,long frame){states.get(h)[1]=(int)frame;}
static int read(long h,float[] out,int frames){int[] s=states.get(h);int n=Math.min(frames,size(s[0])-s[1]);for(int i=0;i<n;i++){out[i*2]=s[0]*100000+s[1];out[i*2+1]=-out[i*2];s[1]++;}return n;}
}''')

put("net/astropenguin/loopdeck/PlaybackHandoffTest",'package net.astropenguin.loopdeck;\nimport java.lang.reflect.*;\nimport java.util.*;\nimport android.media.*;\nimport android.media.session.*;\nimport android.os.*;\nimport android.net.*;\npublic class PlaybackHandoffTest {\n static Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}\n static Object get(Object o,String name)throws Exception{return field(o,name).get(o);}\n static void set(Object o,String name,Object value)throws Exception{field(o,name).set(o,value);}\n static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){throw new AssertionError(name,e.getCause());}}\n static Object call(Object o,String name)throws Exception{return call(o,name,new Class<?>[0]);}\n static void expect(boolean b){if(!b)throw new AssertionError();}\n static PlaybackService create(List<Track> queue)throws Exception{\n  PlaybackService s=new PlaybackService();set(s,"audioManager",new AudioManager());set(s,"wake",new PowerManager().newWakeLock(1,"test"));set(s,"session",new MediaSession(s,"test"));set(s,"queue",queue);call(s,"load",new Class<?>[]{int.class},0);return s;\n }\n static void ready(PlaybackService s)throws Exception{\n  Object slot=get(s,"nextSlot");if(slot==null)return;\n  long end=System.nanoTime()+2_000_000_000L;\n  while(get(slot,"value")==null&&System.nanoTime()<end)Thread.sleep(1);\n  expect(get(slot,"value")!=null);\n }\n static void finish(PlaybackService s)throws Exception{call(s,"releaseAudio");((java.util.concurrent.ExecutorService)get(s,"prefetcher")).shutdown();expect(((java.util.concurrent.ExecutorService)get(s,"prefetcher")).awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS));expect(NativeDecoder.states.isEmpty());}\n public static void main(String[]args)throws Exception{\n  List<Track> queue=List.of(new Track(new Uri(1),"one"),new Track(new Uri(2),"two"),new Track(new Uri(3),"three"));\n  PlaybackService s=create(queue);ready(s);AudioTrack out=(AudioTrack)get(s,"output");int created=AudioTrack.created,flushed=AudioTrack.flushed;\n  int lastIndex=0;\n  for(int step=0;step<10000;step++){\n   call(s,"adoptTransition");int index=(int)get(s,"index");if(index!=lastIndex){ready(s);lastIndex=index;}\n   call(s,"pump");call(s,"publish");\n   // Metadata/position must follow the audible track, even while next PCM is queued.\n   long musicHead=out.head-44100;\n   int audible=musicHead<7003?0:musicHead<16010?1:2;\n   if(musicHead<0)expect(s.snapshot.position==0);\n   if(musicHead!=7003 && musicHead!=16010)expect(((int)get(s,"index"))==audible);\n   out.advance(64);\n   if(!(boolean)get(s,"playing"))break;\n  }\n  expect(AudioTrack.created==created && AudioTrack.flushed==flushed);\n  expect(out.samples.size()==(44100+7003+9007+6001)*2);\n  int pos=0;for(int i=0;i<44100*2;i++)expect(out.samples.get(pos++)==0f);\n  for(int id=1;id<=3;id++)for(int i=0;i<NativeDecoder.size(id);i++){\n   expect(out.samples.get(pos++)==id*100000+i);expect(out.samples.get(pos++)==-(id*100000+i));\n  }\n  expect(s.snapshot.position==NativeDecoder.size(3)*1000L/44100);\n  finish(s);\n  s=create(queue);ready(s);out=(AudioTrack)get(s,"output");\n  // Reach a queued transition while the old track is still audible, then seek.\n  for(int step=0;step<10000 && get(s,"transition")==null;step++){call(s,"pump");out.advance(16);}\n  expect(get(s,"transition")!=null && out.head<44100+7003);\n  call(s,"pump"); // Put some prefetched samples into the output queue.\n  call(s,"resetAt",new Class<?>[]{long.class},1000L);\n  expect(get(s,"transition")==null && (int)get(s,"index")==0);\n  call(s,"pump");call(s,"pump");\n  expect(out.samples.get(0)==101000f); // Outgoing decoder is correctly restored.\n  finish(s);\n  // Seeking during warmup discards all silence and starts exactly at the target.\n  s=create(List.of(queue.get(0)));out=(AudioTrack)get(s,"output");\n  call(s,"pump");call(s,"pump");expect(out.samples.get(0)==0f);\n  call(s,"resetAt",new Class<?>[]{long.class},1000L);\n  call(s,"pump");call(s,"pump");expect(out.samples.get(0)==101000f);\n  finish(s);\n  // Replay after EOF warms the existing output again.\n  s=create(List.of(queue.get(0)));out=(AudioTrack)get(s,"output");\n  for(int step=0;step<10000 && (boolean)get(s,"playing");step++){call(s,"pump");out.advance(64);}\n  expect(!(boolean)get(s,"playing"));\n  call(s,"setPlaying",new Class<?>[]{boolean.class},true);\n  call(s,"pump");call(s,"pump");call(s,"publish");\n  expect(out.samples.get(0)==0f && s.snapshot.position==0);\n  finish(s);\n  // Always request the file rate, with the existing 48 kHz unsupported-rate fallback.\n  s=create(List.of(new Track(new Uri(4),"96k")));\n  expect((int)get(s,"rate")==96000 && (long)get(s,"warmupRemaining")==96000);\n  finish(s);\n  AudioTrack.rejectedRate=96000;\n  s=create(List.of(new Track(new Uri(4),"96k fallback")));\n  expect((int)get(s,"rate")==48000 && (long)get(s,"warmupRemaining")==48000);\n  expect(((String)get(s,"format")).contains("48000 Hz"));\n  finish(s);AudioTrack.rejectedRate=0;\n  System.out.println("Playback service: one-second silent startup, EOF replay, warmup seek cancellation, three-track exact concatenation, single AudioTrack, audible metadata, seek cancellation and resource cleanup passed");\n }\n}\n')

app=repo/'app/src/main/java/net/astropenguin/loopdeck'
classes=root/'classes'
files=list(root.rglob('*.java'))+[app/(name+'.java') for name in ('PlaybackService','Track','LoopMath','PcmPrebuffer','PrefetchSlot')]
subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(classes),*map(str,files)],check=True)
subprocess.run(['java','-cp',str(classes),'net.astropenguin.loopdeck.PlaybackHandoffTest'],check=True)
