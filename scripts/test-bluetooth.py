#!/usr/bin/env python3
"""Exercise the actual BluetoothLink/MusicLibrary over fake RFCOMM (loopback TCP).
JDK 17+ and a host org.json jar are required: python3 scripts/test-bluetooth.py /path/json.jar
The jar is test-only; the APK uses Android's built-in org.json.
"""
from pathlib import Path
import subprocess, tempfile, sys
repo=Path(__file__).resolve().parents[1]
json=Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='loopdeck-bluetooth-') as temp:
 root=Path(temp)
 def put(name,body):
  p=root/(name+'.java'); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(body)
 def stub(name,body,base=''):
  package,cls=name.rsplit('/',1)
  put(name,'package '+package.replace('/','.')+'; public class '+cls+base+' {'+body+'}')
 stub('android/os/Looper','public static Looper getMainLooper(){return new Looper();}')
 stub('android/os/Handler','''
 static final java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
 final java.util.Map<Runnable,java.util.concurrent.Future<?>> jobs=new java.util.concurrent.ConcurrentHashMap<>();
 public Handler(Looper l){} public void postDelayed(Runnable r,long ms){jobs.put(r,timer.schedule(r,ms,java.util.concurrent.TimeUnit.MILLISECONDS));}
 public void removeCallbacks(Runnable r){java.util.concurrent.Future<?> f=jobs.remove(r);if(f!=null)f.cancel(false);}
 ''')
 stub('android/os/SystemClock','public static long elapsedRealtime(){return System.nanoTime()/1000000;}')
 stub('android/net/Uri','public final String id; public Uri(String id){this.id=id;} public static Uri parse(String id){return new Uri(id);} public String toString(){return id;}')
 stub('android/os/ParcelFileDescriptor','public int getFd(){return 1;} public void close(){}',' implements AutoCloseable')
 stub('android/content/Context','public static final String BLUETOOTH_SERVICE="bluetooth"; public static final int MODE_PRIVATE=0; public Object getSystemService(String n){return null;} public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences();} public ContentResolver getContentResolver(){return new ContentResolver();}')
 stub('android/content/SharedPreferences','public String getString(String k,String fallback){return "root";}')
 stub('android/content/ContentResolver','''
 public android.database.Cursor query(android.net.Uri uri,String[] columns,String a,String[] b,String c){
  java.util.List<String[]> rows=new java.util.ArrayList<>();
  if(uri.id.equals("root")){
   rows.add(new String[]{"album","Album","dir"});
   for(int i=0;i<127;i++)rows.add(new String[]{"track"+i,String.format(java.util.Locale.ROOT,"Track %03d 日本語🎵.flac",i),"audio/flac"});
  }else if(uri.id.equals("album")){
   rows.add(new String[]{"image","album.flac","audio/flac"}); rows.add(new String[]{"cue","album.cue","text/plain"});
  }
  return new android.database.Cursor(rows);
 }
 public android.os.ParcelFileDescriptor openFileDescriptor(android.net.Uri u,String m){return new android.os.ParcelFileDescriptor();}
 public java.io.InputStream openInputStream(android.net.Uri u){return new java.io.ByteArrayInputStream(("FILE \\\"album.flac\\\" WAVE\\n TRACK 01 AUDIO\\n TITLE \\\"One\\\"\\n INDEX 01 00:00:00\\n TRACK 02 AUDIO\\n TITLE \\\"Two\\\"\\n INDEX 01 01:00:00\\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 ''')
 stub('android/database/Cursor','final java.util.List<String[]> rows;int pos=-1;public Cursor(java.util.List<String[]> rows){this.rows=rows;} public boolean moveToNext(){return ++pos<rows.size();} public String getString(int col){return rows.get(pos)[col];} public void close(){}',' implements AutoCloseable')
 stub('android/provider/DocumentsContract','''
 public static String getTreeDocumentId(android.net.Uri u){return u.id;}
 public static android.net.Uri buildChildDocumentsUriUsingTree(android.net.Uri t,String id){return new android.net.Uri(id);}
 public static android.net.Uri buildDocumentUriUsingTree(android.net.Uri t,String id){return new android.net.Uri(id);}
 public static class Document {public static final String COLUMN_DOCUMENT_ID="id",COLUMN_DISPLAY_NAME="name",COLUMN_MIME_TYPE="mime",MIME_TYPE_DIR="dir";}
 ''')
 stub('android/bluetooth/BluetoothManager','final BluetoothAdapter adapter; public BluetoothManager(BluetoothAdapter a){adapter=a;} public BluetoothAdapter getAdapter(){return adapter;}')
 stub('android/bluetooth/BluetoothAdapter','''
 public static final java.util.Map<String,BluetoothAdapter> adapters=new java.util.concurrent.ConcurrentHashMap<>();
 public volatile BluetoothServerSocket server; public final String name;
 public BluetoothAdapter(String name){this.name=name;adapters.put(name,this);}
 public boolean isEnabled(){return true;} public BluetoothDevice getRemoteDevice(String address){return new BluetoothDevice(address);}
 public BluetoothServerSocket listenUsingRfcommWithServiceRecord(String n,java.util.UUID id)throws java.io.IOException{server=new BluetoothServerSocket();return server;}
 ''')
 stub('android/bluetooth/BluetoothDevice','''
 public static final int BOND_BONDED=12; final String name;public BluetoothDevice(String n){name=n;}
 public int getBondState(){return BOND_BONDED;} public String getName(){return name;}
 public BluetoothSocket createRfcommSocketToServiceRecord(java.util.UUID id){return new BluetoothSocket(this);}
 ''')
 stub('android/bluetooth/BluetoothServerSocket','''
 final java.net.ServerSocket socket; public BluetoothServerSocket()throws java.io.IOException{socket=new java.net.ServerSocket(0);}
 public BluetoothSocket accept()throws java.io.IOException{return new BluetoothSocket(socket.accept(),new BluetoothDevice("phone"));}
 public void close()throws java.io.IOException{socket.close();}
 ''')
 stub('android/bluetooth/BluetoothSocket','''
 final BluetoothDevice device; java.net.Socket socket;
 public BluetoothSocket(BluetoothDevice device){this.device=device;}
 public BluetoothSocket(java.net.Socket socket,BluetoothDevice device){this.device=device;this.socket=socket;}
 public void connect()throws java.io.IOException{socket=new java.net.Socket("127.0.0.1",BluetoothAdapter.adapters.get(device.name).server.socket.getLocalPort());}
 public BluetoothDevice getRemoteDevice(){return device;}
 public java.io.InputStream getInputStream()throws java.io.IOException{return socket.getInputStream();}
 public java.io.OutputStream getOutputStream()throws java.io.IOException{return socket.getOutputStream();}
 public void close()throws java.io.IOException{if(socket!=null)socket.close();}
 ''')
 stub('net/astropenguin/loopdeck/NativeDecoder','''
 static int openCount; static long open(int fd){openCount++;return 1;} static void close(long h){openCount--;}
 static String cueSheet(long h){return "";} static String[] chapters(long h){return new String[0];} static long duration(long h){return 120000;}
 ''')
 stub('net/astropenguin/loopdeck/PlaybackService','''
 final android.bluetooth.BluetoothAdapter adapter;
 PlaybackService(String name){adapter=new android.bluetooth.BluetoothAdapter(name);}
 public Object getSystemService(String n){return new android.bluetooth.BluetoothManager(adapter);}
 static final class Snapshot {
 final String title,format,status; final long position,duration,a,b; final boolean playing,loop,loaded;
 Snapshot(String t,String f,String s,long p,long d,long a,long b,boolean play,boolean loop,boolean loaded){title=t;format=f;status=s;position=p;duration=d;this.a=a;this.b=b;playing=play;this.loop=loop;this.loaded=loaded;}
 }
 volatile Snapshot snapshot=new Snapshot("日本語🎵","FLAC 96k","Ready",1234,120000,-1,-1,true,false,true);
 final java.util.List<String> calls=new java.util.concurrent.CopyOnWriteArrayList<>();
 volatile java.util.List<Track> queue; volatile int selected;
 void play(java.util.List<Track> q,int i){queue=q;selected=i;calls.add("play");}
 void bluetoothChanged(){}
 void toggle(){calls.add("toggle");} void resumePlayback(){calls.add("resume");} void pausePlayback(){calls.add("pause");}
 void stopPlayback(){calls.add("stop");} void next(int d){calls.add(d>0?"next":"previous");}
 void seek(long ms){calls.add("seek:"+ms);} void markA(){calls.add("a");} void markB(){calls.add("b");}
 void toggleLoop(){calls.add("loop");} void clearLoop(){calls.add("clear");} void markers(long a,long b){calls.add("markers:"+a+":"+b);}
 ''',' extends android.content.Context')
 put('net/astropenguin/loopdeck/BluetoothTest',r'''package net.astropenguin.loopdeck;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import android.bluetooth.*;
public final class BluetoothTest {
 static void expect(boolean ok){if(!ok)throw new AssertionError();}
 static void await(BooleanSupplier test)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!test.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Timed out");Thread.sleep(5);}}
 static void fails(byte[] bytes)throws Exception{try{RemoteWire.read(new DataInputStream(new ByteArrayInputStream(bytes)));throw new AssertionError("accepted bad frame");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  RemoteWire.write(out,"日本語🎵");RemoteWire.write(out,"second");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
  expect(RemoteWire.read(in).equals("日本語🎵") && RemoteWire.read(in).equals("second"));
  fails(new byte[]{0,1,0,1}); fails(new byte[]{-1,-1,-1,-1}); fails(new byte[]{0,0,0,0});
  fails(new byte[]{0,0,0,3,65}); fails(new byte[]{0,0,0,1,(byte)0xff});
  try{RemoteWire.write(out,"x".repeat(RemoteWire.MAX_BYTES+1));throw new AssertionError();}catch(IOException expected){}
  PlaybackService serverPlayer=new PlaybackService("fiio"),clientPlayer=new PlaybackService("phone");
  BluetoothLink server=new BluetoothLink(serverPlayer),client=new BluetoothLink(clientPlayer);
  try {
   server.start(BluetoothLink.SERVER,null);await(()->serverPlayer.adapter.server!=null);
   client.start(BluetoothLink.CLIENT,"fiio");await(()->client.connected && server.connected && client.listing.total==128);
   expect(client.listing.items.size()==BluetoothLink.PAGE_SIZE);
   await(()->client.remote.title.equals("日本語🎵"));expect(client.remote.position==1234 && client.remote.playing);
   BluetoothLink.Listing root=client.listing;
   client.page(50);await(()->client.listing.offset==50);expect(client.listing.items.get(0).id==50);
   client.open(client.listing.items.get(0),client.listing);await(()->serverPlayer.calls.contains("play"));
   expect(serverPlayer.queue.size()==127 && serverPlayer.selected==49); // Includes tracks outside this page.
   expect(serverPlayer.queue.get(49).uri.toString().equals("track49"));
   client.command("filter","query","126");await(()->client.listing.total==1);expect(client.listing.items.get(0).name.contains("126"));
   client.command("root");await(()->client.listing.total==128 && client.listing.version!=root.version);
   client.open(root.items.get(1),root);await(()->client.error.contains("Folder changed")); // Reject stale indices.
   client.open(client.listing.items.get(0),client.listing);await(()->client.listing.total==2);
   BluetoothLink.Listing album=client.listing;
   client.open(album.items.get(0),album);await(()->client.listing.items.get(0).name.equals("1. One"));
   expect(client.listing.total==2 && client.listing.up);
   client.open(client.listing.items.get(1),client.listing);await(()->serverPlayer.queue.size()==2);
   expect(serverPlayer.selected==1 && serverPlayer.queue.get(1).startMs==60000);
   client.command("up");await(()->client.listing.items.get(0).name.equals("album.cue"));
   client.command("up");await(()->client.listing.total==128);
   for(String command:List.of("toggle","resume","pause","next","previous","a","b","loop","clear","stop"))client.command(command);
   client.command("seek","position",3456);client.command("markers","a",1000,"b",2500);
   await(()->serverPlayer.calls.contains("markers:1000:2500"));
   expect(serverPlayer.calls.containsAll(List.of("toggle","resume","pause","next","previous","a","b","loop","clear","stop","seek:3456")));
   client.command("seek","position",-5);await(()->client.error.equals("Invalid playback time"));
   expect(!serverPlayer.calls.contains("seek:-5"));
   client.close();await(()->!server.connected);expect(!client.remote.loaded && client.listing.items.isEmpty());
   client.start(BluetoothLink.CLIENT,"fiio");await(()->client.connected && client.listing.total==128);
   // A malformed peer is disconnected; the server can accept the next client.
   client.close();await(()->!server.connected);
   BluetoothSocket invalid=clientPlayer.adapter.getRemoteDevice("fiio").createRfcommSocketToServiceRecord(java.util.UUID.randomUUID());invalid.connect();
   RemoteWire.write(new DataOutputStream(invalid.getOutputStream()),BluetoothLink.message("hello","version",999,"role",BluetoothLink.CLIENT).toString());
   await(()->server.error.contains("Incompatible"));invalid.close();
   client.start(BluetoothLink.CLIENT,"fiio");await(()->client.connected && client.listing.total==128);
   expect(NativeDecoder.openCount==0);
  }finally{client.close();server.close();}
  expect(client.mode==BluetoothLink.LOCAL && server.mode==BluetoothLink.LOCAL);
  System.out.println("Bluetooth: framing bounds/UTF-8, handshake, folder paging/filter, full-folder queue, CUE tracks, stale selection rejection, all commands, invalid times, disconnect/reconnect and malformed peer recovery passed");
 }
}''')
 app=repo/'app/src/main/java/net/astropenguin/loopdeck'
 sources=list(root.rglob('*.java'))+[app/(name+'.java') for name in ['BluetoothLink','RemoteWire','MusicLibrary','Track','CueSheet']]
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-cp',str(json),'-d',str(root/'classes'),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',str(root/'classes')+':'+str(json),'net.astropenguin.loopdeck.BluetoothTest'],check=True,timeout=40)
