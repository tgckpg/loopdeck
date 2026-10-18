package net.astropenguin.loopdeck;

import android.bluetooth.*;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.astropenguin.loopdeck.MusicLibrary.Entry;

/** One secure RFCOMM peer. File IO and socket IO never run on the playback/UI worker. */
final class BluetoothLink implements AutoCloseable {
    private static final UUID UUID_SERVICE=UUID.fromString("aca23821-cd14-47c2-9d9f-c821ef5c3aa0");
    static final int LOCAL=0, SERVER=1, CLIENT=2, PAGE_SIZE=50;
    private final PlaybackService player;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private long epoch;
    private BluetoothServerSocket listener;
    private BluetoothSocket pending;
    private Peer peer;
    volatile int mode=LOCAL;
    volatile boolean connected;
    volatile String status="Bluetooth off", error="";
    volatile PlaybackService.Snapshot remote=emptySnapshot();
    volatile Listing listing=new Listing(0,"Connect to a player",false,0,0,Collections.emptyList());

    static final class Item {
        final int id; final String name; final boolean folder;
        Item(int id,String name,boolean folder) { this.id=id; this.name=name; this.folder=folder; }
    }
    static final class Listing {
        final long version; final String title; final boolean up; final int offset,total; final List<Item> items;
        Listing(long version,String title,boolean up,int offset,int total,List<Item> items) {
            this.version=version; this.title=title; this.up=up; this.offset=offset; this.total=total;
            this.items=Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
    BluetoothLink(PlaybackService player) {
        this.player=player;
        BluetoothManager manager=(BluetoothManager)player.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null ? null : manager.getAdapter();
    }
    private static PlaybackService.Snapshot emptySnapshot() {
        return new PlaybackService.Snapshot("Remote player","","Not connected",0,0,-1,-1,false,false,false);
    }
    static JSONObject message(String type,Object... values) {
        JSONObject result=new JSONObject();
        try { result.put("type",type); for (int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return result;
    }
    synchronized void start(int requested,String address) {
        close(); error="";
        try {
            if (adapter==null) throw new IOException("This device has no Bluetooth adapter");
            if (!adapter.isEnabled()) throw new IOException("Enable Bluetooth in Android Settings first");
            mode=requested; final long run=epoch;
            remote=emptySnapshot(); listing=new Listing(0,"Connecting…",false,0,0,Collections.emptyList());
            status=requested==SERVER ? "Server · waiting for a paired remote" : "Client · connecting…";
            new Thread(() -> run(run,requested,address),"LoopDeck Bluetooth").start();
        } catch (Exception e) { mode=LOCAL; status="Bluetooth off"; error=e.getMessage(); }
    }
    private synchronized boolean current(long run) { return run==epoch && mode!=LOCAL; }
    private void run(long run,int role,String address) {
        try {
            if (role==SERVER) {
                BluetoothServerSocket server=adapter.listenUsingRfcommWithServiceRecord("LoopDeck",UUID_SERVICE);
                synchronized (this) { if (!current(run)) { server.close(); return; } listener=server; }
                while (current(run)) {
                    BluetoothSocket socket=server.accept();
                    if (socket.getRemoteDevice().getBondState()!=BluetoothDevice.BOND_BONDED) { socket.close(); continue; }
                    serve(run,socket,role);
                }
            } else {
                BluetoothDevice device=adapter.getRemoteDevice(address);
                if (device.getBondState()!=BluetoothDevice.BOND_BONDED) throw new IOException("Pair the player in Android Bluetooth Settings first");
                BluetoothSocket socket=device.createRfcommSocketToServiceRecord(UUID_SERVICE);
                synchronized (this) { if (!current(run)) { socket.close(); return; } pending=socket; }
                Runnable timeout=() -> { synchronized (BluetoothLink.this) {
                    if (current(run) && pending==socket) { error="Bluetooth connection timed out"; closeSocket(socket); }
                } };
                main.postDelayed(timeout,20000);
                try { socket.connect(); } finally { main.removeCallbacks(timeout); }
                serve(run,socket,role);
            }
        } catch (Exception e) {
            synchronized (this) { if (current(run)) { status="Bluetooth disconnected"; if (error.isEmpty()) error=detail(e); } }
        } finally {
            synchronized (this) {
                if (current(run)) {
                    closeSocket(pending); pending=null; closeListener(); connected=false;
                    if (role==CLIENT) listing=new Listing(0,"Disconnected · reconnect from Bluetooth",false,0,0,Collections.emptyList());
                    player.bluetoothChanged();
                }
            }
        }
    }
    private void serve(long run,BluetoothSocket socket,int role) {
        Peer active=new Peer(run,socket,role);
        synchronized (this) {
            if (!current(run)) { closeSocket(socket); return; }
            pending=null; peer=active;
        }
        try { active.read(); }
        catch (Exception | LinkageError e) {
            synchronized (this) { if (current(run) && !(e instanceof EOFException)) error=detail(e); }
        } finally {
            active.close();
            synchronized (this) {
                if (peer==active) {
                    peer=null; connected=false; remote=emptySnapshot();
                    listing=new Listing(0,"Disconnected · reconnect from Bluetooth",false,0,0,Collections.emptyList());
                    status=role==SERVER ? "Server · waiting for a paired remote" : "Client · disconnected";
                    player.bluetoothChanged();
                }
            }
        }
    }
    synchronized boolean send(JSONObject message) {
        if (!connected || peer==null) return false;
        if (!peer.out.offer(message.toString())) { error="Bluetooth is busy; reconnect"; peer.close(); return false; }
        return true;
    }
    void command(String name,Object... args) {
        error="";
        JSONObject json=message(name,args);
        if (!send(json)) error="Not connected to the player";
    }
    void open(Item item,Listing view) { command("open","version",view.version,"id",item.id); }
    void page(int offset) { command("list","offset",offset); }
    @Override public synchronized void close() {
        ++epoch; mode=LOCAL; connected=false; status="Bluetooth off"; error="";
        closeListener(); closeSocket(pending); pending=null;
        if (peer!=null) { peer.close(); peer=null; }
        remote=emptySnapshot(); listing=new Listing(0,"Connect to a player",false,0,0,Collections.emptyList());
    }
    private void closeListener() { if (listener!=null) try { listener.close(); } catch (IOException ignored) {} listener=null; }
    private static void closeSocket(BluetoothSocket socket) { if (socket!=null) try { socket.close(); } catch (IOException ignored) {} }
    private static String detail(Throwable e) { return e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage(); }

    private final class Peer implements AutoCloseable {
        final long run; final BluetoothSocket socket; final int role;
        final ArrayBlockingQueue<String> out=new ArrayBlockingQueue<>(32);
        volatile boolean alive=true,ready;
        Thread writer;
        ArrayList<Entry> entries=new ArrayList<>();
        final ArrayList<String> parents=new ArrayList<>();
        Uri tree; String directory,label,query=""; boolean tracks;
        long version;
        Peer(long run,BluetoothSocket socket,int role) { this.run=run; this.socket=socket; this.role=role; }
        void emit(JSONObject value) throws IOException {
            if (!alive || !current(run)) throw new IOException("Bluetooth disconnected");
            if (!out.offer(value.toString())) throw new IOException("Bluetooth peer is too slow");
        }
        void read() throws Exception {
            DataInputStream in=new DataInputStream(socket.getInputStream());
            writer=new Thread(this::write,"LoopDeck Bluetooth output"); writer.start();
            emit(message("hello","version",RemoteWire.VERSION,"role",role));
            // Close peers which connect but never finish the protocol handshake.
            Runnable timeout=() -> { if (!ready) close(); };
            main.postDelayed(timeout,10000);
            try {
                JSONObject hello=new JSONObject(RemoteWire.read(in));
                if (!"hello".equals(hello.getString("type")) || hello.getInt("version")!=RemoteWire.VERSION ||
                    hello.getInt("role")!=(role==SERVER ? CLIENT : SERVER)) throw new IOException("Incompatible LoopDeck Bluetooth peer");
                synchronized (BluetoothLink.this) {
                    if (!current(run)) return;
                    ready=true; connected=true; error="";
                    status=(role==SERVER ? "Server · " : "Client · ")+socket.getRemoteDevice().getName();
                    player.bluetoothChanged();
                }
                if (role==CLIENT) emit(message("root"));
                while (alive && current(run)) {
                    JSONObject request=new JSONObject(RemoteWire.read(in));
                    if (!alive || !current(run)) break;
                    if (role==CLIENT) { synchronized (BluetoothLink.this) { if (current(run) && peer==this) receive(request); } }
                    else {
                        try { handle(request); }
                        catch (Exception | LinkageError e) { emit(message("error","message",detail(e))); }
                    }
                }
            } finally { main.removeCallbacks(timeout); }
        }
        void write() {
            String previous=""; long last=0;
            try {
                DataOutputStream output=new DataOutputStream(socket.getOutputStream());
                while (alive && current(run)) {
                    String value=out.poll(250,TimeUnit.MILLISECONDS);
                    if (value!=null) RemoteWire.write(output,value);
                    long now=android.os.SystemClock.elapsedRealtime();
                    if (role==SERVER && ready && now-last>=500) {
                        String state=state(player.snapshot).toString();
                        if (!state.equals(previous)) { RemoteWire.write(output,state); previous=state; }
                        last=now;
                    }
                }
            } catch (Exception e) { if (alive && current(run)) error=detail(e); }
            finally { close(); }
        }
        @Override public void close() { alive=false; closeSocket(socket); if (writer!=null) writer.interrupt(); }
        void receive(JSONObject value) throws JSONException,IOException {
            switch (value.getString("type")) {
                case "state":
                    PlaybackService.Snapshot previous=remote;
                    remote=new PlaybackService.Snapshot(value.getString("title"),value.getString("format"),value.getString("status"),
                        value.getLong("position"),value.getLong("duration"),value.getLong("a"),value.getLong("b"),
                        value.getBoolean("playing"),value.getBoolean("loop"),value.getBoolean("loaded"));
                    if (!remote.title.equals(previous.title) || remote.playing!=previous.playing || remote.loaded!=previous.loaded || remote.duration!=previous.duration)
                        player.bluetoothChanged();
                    break;
                case "list":
                    ArrayList<Item> items=new ArrayList<>(); JSONArray array=value.getJSONArray("items");
                    if (array.length()>PAGE_SIZE) throw new IOException("Invalid folder page");
                    for (int i=0;i<array.length();i++) { JSONObject item=array.getJSONObject(i); items.add(new Item(item.getInt("id"),item.getString("name"),item.getBoolean("folder"))); }
                    listing=new Listing(value.getLong("version"),value.getString("title"),value.getBoolean("up"),value.getInt("offset"),value.getInt("total"),items); break;
                case "error": error=value.getString("message"); break;
                default: throw new IOException("Unknown Bluetooth response");
            }
        }
        void handle(JSONObject value) throws Exception {
            switch (value.getString("type")) {
                case "root": root(); break;
                case "up":
                    if (tree==null) { root(); break; }
                    if (tracks) loadFolder(directory);
                    else if (!parents.isEmpty()) { String parent=parents.get(parents.size()-1); loadFolder(parent); parents.remove(parents.size()-1); }
                    show(0); break;
                case "list": show(value.getInt("offset")); break;
                case "filter": query=value.getString("query"); show(0); break;
                case "open":
                    if (value.getLong("version")!=version) throw new IOException("Folder changed; refresh it before selecting");
                    int id=value.getInt("id");
                    if (id<0 || id>=entries.size()) throw new IOException("Invalid folder item");
                    Entry entry=entries.get(id);
                    if (entry.folder) { String parent=directory; loadFolder(entry.id); parents.add(parent); show(0); }
                    else if (entry.track!=null) play(entry);
                    else {
                        ArrayList<Track> found=MusicLibrary.tracks(player,entry,entries);
                        if (found.isEmpty()) { if (MusicLibrary.isCue(entry)) throw new IOException("No usable CUE tracks in this folder"); play(entry); }
                        else { entries.clear(); for (Track track:found) entries.add(new Entry(track)); tracks=true; label=entry.name; query=""; version++; show(0); }
                    } break;
                default:
                    synchronized (BluetoothLink.this) {
                        if (!alive || !current(run) || peer!=this) return;
                        control(value);
                    }
            }
        }
        void control(JSONObject value) throws Exception {
            switch (value.getString("type")) {
                case "toggle": player.toggle(); break;
                case "resume": player.resumePlayback(); break;
                case "pause": player.pausePlayback(); break;
                case "stop": player.stopPlayback(); break;
                case "next": player.next(1); break;
                case "previous": player.next(-1); break;
                case "seek": player.seek(milliseconds(value,"position",false)); break;
                case "a": player.markA(); break;
                case "b": player.markB(); break;
                case "loop": player.toggleLoop(); break;
                case "clear": player.clearLoop(); break;
                case "markers": player.markers(milliseconds(value,"a",true),milliseconds(value,"b",true)); break;
                default: throw new IOException("Unknown Bluetooth command");
            }
        }
        void root() throws Exception {
            String saved=player.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("tree",null);
            if (saved==null) throw new IOException("Choose a Music folder on the player first");
            Uri selected=Uri.parse(saved);
            String root=DocumentsContract.getTreeDocumentId(selected);
            ArrayList<Entry> found=MusicLibrary.list(player,selected,root);
            tree=selected; directory=root; entries=found; parents.clear(); tracks=false; query=""; label=root; version++; show(0);
        }
        void loadFolder(String target) throws IOException {
            ArrayList<Entry> found=MusicLibrary.list(player,tree,target);
            entries=found; directory=target; tracks=false; query=""; label=target; version++;
        }
        void show(int offset) throws Exception {
            if (tree==null) { root(); return; }
            ArrayList<Integer> matches=new ArrayList<>(); String needle=query.toLowerCase(Locale.ROOT);
            for (int i=0;i<entries.size();i++) if (entries.get(i).name.toLowerCase(Locale.ROOT).contains(needle)) matches.add(i);
            int from=Math.max(0,Math.min(offset,Math.max(0,matches.size()-1)));
            from=from/PAGE_SIZE*PAGE_SIZE;
            JSONArray items=new JSONArray();
            for (int i=from;i<Math.min(from+PAGE_SIZE,matches.size());i++) {
                int id=matches.get(i); Entry e=entries.get(id);
                items.put(message("item","id",id,"name",e.name,"folder",e.folder));
            }
            emit(message("list","version",version,"title",label+(query.isEmpty() ? "" : " · "+query),"up",tracks || !parents.isEmpty(),
                "offset",from,"total",matches.size(),"items",items));
        }
        void play(Entry entry) {
            ArrayList<Track> queue=new ArrayList<>(); int selected=0;
            for (Entry candidate:entries) if (!candidate.folder && (candidate==entry || candidate.audio)) {
                if (candidate==entry) selected=queue.size();
                queue.add(candidate.track!=null ? candidate.track : new Track(candidate.uri,candidate.name));
            }
            synchronized (BluetoothLink.this) { if (alive && current(run) && peer==this) player.play(queue,selected); }
        }
    }
    private static long milliseconds(JSONObject value,String key,boolean unset) throws JSONException,IOException {
        long ms=value.getLong(key);
        if (ms<(unset ? -1 : 0) || ms>Long.MAX_VALUE/384000) throw new IOException("Invalid playback time");
        return ms;
    }
    private static JSONObject state(PlaybackService.Snapshot s) {
        return message("state","title",s.title,"format",s.format,"status",s.status,"position",s.position,"duration",s.duration,
            "a",s.a,"b",s.b,"playing",s.playing,"loop",s.loop,"loaded",s.loaded);
    }
}
