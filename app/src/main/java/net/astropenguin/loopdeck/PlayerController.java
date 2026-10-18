package net.astropenguin.loopdeck;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.astropenguin.loopdeck.MusicLibrary.Entry;

/** The UI/media control boundary. PlaybackService's command methods are always local. */
final class PlayerController implements AutoCloseable {
    enum Action {
        TOGGLE("toggle"), RESUME("resume"), PAUSE("pause"), STOP("stop"), NEXT("next"), PREVIOUS("previous"),
        SEEK("seek"), MARK_A("a"), MARK_B("b"), MARKERS("markers"), LOOP("loop"), CLEAR("clear");
        final String wire;
        Action(String wire) { this.wire=wire; }
    }
    static final class Item {
        final String name; final boolean folder;
        private final Entry local;
        private final BluetoothLink.Item remote;
        Item(Entry entry) { name=entry.name; folder=entry.folder; local=entry; remote=null; }
        Item(BluetoothLink.Item entry) { name=entry.name; folder=entry.folder; local=null; remote=entry; }
    }
    /** Immutable rows and capabilities; the activity renders this without knowing the endpoint. */
    static final class Browser {
        final String title,rootLabel;
        final List<Item> items;
        final boolean paged,up,previous,next,ready;
        private final BluetoothLink.Listing listing;
        Browser(String title,List<Item> items,boolean up) {
            this.title=title; this.items=Collections.unmodifiableList(new ArrayList<>(items)); this.up=up;
            rootLabel="Music folder"; paged=previous=next=false; ready=true; listing=null;
        }
        Browser(BluetoothLink.Listing listing,boolean ready) {
            this.listing=listing; this.ready=ready; paged=true; rootLabel="Remote root"; up=ready && listing.up;
            previous=ready && listing.offset>0; next=ready && listing.offset+listing.items.size()<listing.total;
            title=listing.title+" · "+(listing.total==0 ? "0 items" : (listing.offset+1)+"–"+(listing.offset+listing.items.size())+" / "+listing.total);
            ArrayList<Item> rows=new ArrayList<>(); for (BluetoothLink.Item item:listing.items) rows.add(new Item(item));
            items=Collections.unmodifiableList(rows);
        }
        static Browser empty() { return new Browser("Select a folder on internal storage or microSD",Collections.emptyList(),false); }
    }
    private final PlaybackService player;
    private final BluetoothLink bluetooth;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private final ArrayList<String> parents=new ArrayList<>();
    private Uri tree;
    private String directory;
    private boolean trackView;
    private volatile boolean closed;
    private volatile Browser localBrowser=Browser.empty();
    private volatile String browserError="";
    private Browser remoteBrowser;

    PlayerController(PlaybackService player) {
        this.player=player; bluetooth=new BluetoothLink(player);
        String saved=player.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getString("tree",null);
        if (saved!=null) { tree=Uri.parse(saved); directory=DocumentsContract.getTreeDocumentId(tree); listFolder(); }
    }
    private boolean remote() { return bluetooth.mode==BluetoothLink.CLIENT; }
    boolean bluetoothEnabled() { return bluetooth.mode!=BluetoothLink.LOCAL; }
    boolean available() { return !remote() || bluetooth.connected; }
    boolean hasMusicFolder() { return tree!=null; }
    PlaybackService.Snapshot snapshot() { return remote() ? bluetooth.remote : player.snapshot; }
    String bluetoothStatus() { return bluetooth.status; }
    String status() {
        String error=!bluetooth.error.isEmpty() ? "Bluetooth: "+bluetooth.error : remote() ? "" : browserError;
        return error.isEmpty() ? snapshot().status : error+"\n"+snapshot().status;
    }
    Browser browser() {
        if (!remote()) return localBrowser;
        BluetoothLink.Listing listing=bluetooth.listing;
        if (remoteBrowser==null || remoteBrowser.listing!=listing || remoteBrowser.ready!=bluetooth.connected)
            remoteBrowser=new Browser(listing,bluetooth.connected);
        return remoteBrowser;
    }
    void perform(Action action) { execute(action,0,0); }
    void seek(long position) { execute(Action.SEEK,position,0); }
    void markers(long a,long b) { execute(Action.MARKERS,a,b); }
    private void execute(Action action,long first,long second) {
        if (closed) return;
        if (remote()) {
            switch (action) {
                case SEEK: bluetooth.command(action.wire,"position",first); break;
                case MARKERS: bluetooth.command(action.wire,"a",first,"b",second); break;
                default: bluetooth.command(action.wire);
            }
            return;
        }
        switch (action) {
            case TOGGLE: foreground(); player.toggle(); break;
            case RESUME: foreground(); player.resumePlayback(); break;
            case PAUSE: player.pausePlayback(); break;
            case STOP: player.stopPlayback(); break;
            case NEXT: foreground(); player.next(1); break;
            case PREVIOUS: foreground(); player.next(-1); break;
            case SEEK: player.seek(first); break;
            case MARK_A: player.markA(); break;
            case MARK_B: player.markB(); break;
            case MARKERS: player.markers(first,second); break;
            case LOOP: player.toggleLoop(); break;
            case CLEAR: player.clearLoop(); break;
        }
    }
    private void foreground() { player.startForegroundService(new Intent(player,PlaybackService.class)); }
    void startBluetooth(int mode,String address) {
        boolean wasRemote=remote();
        if (mode==BluetoothLink.CLIENT) { generation.incrementAndGet(); player.pausePlayback(); }
        foreground(); bluetooth.start(mode,address); remoteBrowser=null;
        if (!remote() && (wasRemote || mode==BluetoothLink.CLIENT)) listFolder();
        player.bluetoothChanged();
    }
    void stopBluetooth() {
        boolean wasRemote=remote();
        if (wasRemote) generation.incrementAndGet();
        bluetooth.close(); remoteBrowser=null;
        if (wasRemote) listFolder();
        player.bluetoothStopped();
    }
    /** Only the activity owns the Android folder picker; destination selection stays here. */
    void chooseFolder(Runnable picker) {
        if (remote()) bluetooth.command("root"); else picker.run();
    }
    void musicFolder(Uri chosen) {
        tree=chosen; directory=DocumentsContract.getTreeDocumentId(tree); parents.clear();
        player.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString("tree",tree.toString()).apply();
        listFolder();
    }
    void page(int direction) {
        if (!remote() || !bluetooth.connected) return;
        bluetooth.page(bluetooth.listing.offset+direction*BluetoothLink.PAGE_SIZE);
    }
    void filter(String query) { if (remote()) bluetooth.command("filter","query",query); }
    void refresh() {
        if (remote()) bluetooth.command("root"); else listFolder();
    }
    boolean up() {
        if (remote()) {
            if (!browser().up) return false;
            bluetooth.command("up"); return true;
        }
        if (trackView) { listFolder(); return true; }
        if (parents.isEmpty()) return false;
        directory=parents.remove(parents.size()-1); listFolder(); return true;
    }
    void open(Browser view,int position) {
        if (closed || position<0 || position>=view.items.size()) return;
        Item item=view.items.get(position);
        if (remote()) {
            if (view.listing!=null && bluetooth.connected) bluetooth.open(item.remote,view.listing);
            return;
        }
        if (view!=localBrowser || item.local==null) return;
        Entry entry=item.local;
        if (entry.folder) { parents.add(directory); directory=entry.id; listFolder(); }
        else if (entry.track!=null) playEntry(entry,view.items);
        else openTrackView(entry,view.items);
    }
    private void playEntry(Entry entry,List<Item> items) {
        ArrayList<Track> tracks=new ArrayList<>(); int selected=0;
        for (Item item:items) {
            Entry candidate=item.local;
            if (!candidate.folder && (candidate==entry || candidate.audio)) {
                if (candidate==entry) selected=tracks.size();
                tracks.add(candidate.track!=null ? candidate.track : new Track(candidate.uri,candidate.name));
            }
        }
        foreground(); player.play(tracks,selected);
    }
    private boolean current(int request) { return !closed && request==generation.get() && !remote(); }
    private void listFolder() {
        if (closed || remote()) return;
        if (tree==null) { localBrowser=Browser.empty(); return; }
        final Uri selectedTree=tree; final String selectedDirectory=directory;
        trackView=false; browserError="";
        final int request=generation.incrementAndGet();
        localBrowser=new Browser("Loading folder…",Collections.emptyList(),!parents.isEmpty());
        files.submit(() -> {
            ArrayList<Item> rows=new ArrayList<>(); String error="";
            try { for (Entry entry:MusicLibrary.list(player,selectedTree,selectedDirectory)) rows.add(new Item(entry)); }
            catch (Exception e) { error="Cannot read folder: "+e.getMessage()+". Choose the folder again if the card changed."; }
            final String failure=error;
            main.post(() -> {
                if (!current(request)) return;
                browserError=failure; localBrowser=new Browser(selectedDirectory+" · "+rows.size()+" items",rows,!parents.isEmpty());
            });
        });
    }
    private void openTrackView(Entry entry,List<Item> items) {
        ArrayList<Entry> folder=new ArrayList<>(); for (Item item:items) folder.add(item.local);
        final int request=generation.incrementAndGet(); browserError="";
        localBrowser=new Browser("Reading track information…",items,!parents.isEmpty());
        files.submit(() -> {
            ArrayList<Track> found=new ArrayList<>(); String error=null;
            try { found=MusicLibrary.tracks(player,entry,folder); }
            catch (Exception | LinkageError e) { error=e.getMessage(); }
            final ArrayList<Track> tracks=found; final String failure=error;
            main.post(() -> {
                if (!current(request)) return;
                if (!tracks.isEmpty()) {
                    trackView=true; ArrayList<Item> rows=new ArrayList<>();
                    for (Track track:tracks) rows.add(new Item(new Entry(track)));
                    localBrowser=new Browser(entry.name+" · "+tracks.size()+" tracks",rows,true);
                } else {
                    localBrowser=new Browser(directory+" · "+items.size()+" items",items,!parents.isEmpty());
                    if (MusicLibrary.isCue(entry)) browserError=failure==null ? "No track information found" : failure;
                    else playEntry(entry,items);
                }
            });
        });
    }
    void saveBrowser(Bundle out) {
        if (tree!=null) { out.putString("tree",tree.toString()); out.putString("directory",directory); out.putStringArrayList("parents",new ArrayList<>(parents)); }
    }
    void restoreBrowser(Bundle saved) {
        if (saved==null || saved.getString("tree")==null) return;
        Uri savedTree=Uri.parse(saved.getString("tree"));
        String savedDirectory=saved.getString("directory",DocumentsContract.getTreeDocumentId(savedTree));
        ArrayList<String> savedParents=saved.getStringArrayList("parents");
        if (savedParents==null) savedParents=new ArrayList<>();
        if (tree!=null && tree.toString().equals(savedTree.toString()) && Objects.equals(directory,savedDirectory) && parents.equals(savedParents)) return;
        tree=savedTree; directory=savedDirectory; parents.clear(); parents.addAll(savedParents); listFolder();
    }
    @Override public void close() {
        closed=true; generation.incrementAndGet(); files.shutdownNow(); bluetooth.close();
    }
}
