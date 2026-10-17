package net.astropenguin.loopdeck;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 1;
    public final class LocalBinder extends Binder { PlaybackService service() { return PlaybackService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private interface Command { void run() throws Exception; }
    private final LinkedBlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread worker;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private MediaSession session;
    private volatile boolean foreground;

    // Everything below, except the immutable published snapshot, belongs to the worker.
    private long decoder;
    private AudioTrack output;
    private List<Track> queue = Collections.emptyList();
    private int index = -1, rate;
    private long sourceStart, trackEnd;
    private long duration, a = -1, b = -1, cursor, base, written, headEpoch, lastHead;
    private boolean playing, loop, eof, resumeOnFocus;
    private String title = "Choose a music folder", format = "", status = "Ready";
    private final float[] block = new float[4096]; // 2048 stereo frames
    private int blockSamples, blockOffset;
    private float[] cache;
    private int cacheFilled, cacheRead;
    private long lastPublish, lastSession;

    static final class Snapshot {
        final String title, format, status;
        final long position, duration, a, b;
        final boolean playing, loop, loaded;
        Snapshot(String title, String format, String status, long position, long duration,
                 long a, long b, boolean playing, boolean loop, boolean loaded) {
            this.title=title; this.format=format; this.status=status; this.position=position;
            this.duration=duration; this.a=a; this.b=b; this.playing=playing; this.loop=loop; this.loaded=loaded;
        }
    }
    volatile Snapshot snapshot = new Snapshot(title, "", status, 0, 0, -1, -1, false, false, false);

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoopDeck:playback");
        wake.setReferenceCounted(false);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> submit(() -> focusChanged(change)), new Handler(getMainLooper())).build();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "LoopDeck");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { submit(() -> setPlaying(true)); }
            @Override public void onPause() { submit(() -> { resumeOnFocus=false; setPlaying(false); }); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSkipToNext() { next(1); }
            @Override public void onSkipToPrevious() { next(-1); }
            @Override public void onSeekTo(long pos) { seek(pos); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        worker = new Thread(this::work, "LoopDeck playback");
        worker.start();
    }
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { submit(() -> { resumeOnFocus=false; setPlaying(false); }); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Satisfy startForegroundService before doing any file IO.
        startForeground(NOTIFICATION, notification()); foreground = true;
        if (intent != null) {
            String action = intent.getAction();
            if ("toggle".equals(action)) toggle();
            if ("next".equals(action)) next(1);
            if ("previous".equals(action)) next(-1);
            if ("stop".equals(action)) stopPlayback();
        }
        return START_NOT_STICKY;
    }
    private void submit(Command command) { if (running) commands.offer(command); }
    void play(List<Track> tracks, int selected) {
        List<Track> copy = new ArrayList<>(tracks);
        submit(() -> { queue=copy; load(selected); });
    }
    void toggle() { submit(() -> { resumeOnFocus=false; setPlaying(!playing); }); }
    void next(int direction) { submit(() -> { if (!queue.isEmpty()) load(Math.floorMod(index + direction, queue.size())); }); }
    void seek(long ms) { submit(() -> {
        if (decoder == 0) return;
        long target = LoopMath.frame(Math.max(0, duration > 0 ? Math.min(ms,duration) : ms), rate);
        if (loop && (target < a || target >= b)) { loop=false; cache=null; }
        resetAt(target); status=loop ? "A–B loop" : "Ready";
    }); }
    void markA() { submit(() -> { if (decoder!=0) changeMarkers(positionFrames(), b); }); }
    void markB() { submit(() -> { if (decoder!=0) changeMarkers(a, positionFrames()); }); }
    void markers(long aMs, long bMs) { submit(() -> {
        if (decoder!=0) changeMarkers(aMs < 0 ? -1 : LoopMath.frame(aMs,rate), bMs < 0 ? -1 : LoopMath.frame(bMs,rate));
    }); }
    void clearLoop() { submit(() -> {
        if (decoder==0) return;
        long here=positionFrames(); loop=false; a=b=-1; cache=null; resetAt(here); status="Loop cleared";
    }); }
    void toggleLoop() { submit(() -> {
        if (decoder==0) return;
        if (!LoopMath.valid(a,b)) { status="Set A, then set B after A"; return; }
        if (loop) { long here=positionFrames(); loop=false; cache=null; resetAt(here); status="Loop off"; }
        else activateLoop();
    }); }
    void stopPlayback() { submit(() -> {
        setPlaying(false); releaseAudio(); title="Stopped"; format=""; status="Choose a track";
        a=b=-1; loop=false; duration=0; updateSession();
        new Handler(getMainLooper()).post(() -> { stopForeground(true); foreground=false; stopSelf(); });
    }); }
    private void changeMarkers(long newA, long newB) throws IOException {
        long total=duration > 0 ? LoopMath.frame(duration,rate) : Long.MAX_VALUE;
        if (newA < -1 || newB < -1 || newA > total || newB > total) { status="Marker is outside the track"; return; }
        // Never leave a previous loop running after an invalid edit.
        long here=positionFrames(); loop=false; cache=null;
        a=newA; b=newB;
        if (LoopMath.valid(a,b)) activateLoop();
        else { resetAt(here); status=a < 0 ? "Set A first" : "A set; set B after A"; }
    }
    private void activateLoop() throws IOException {
        loop=true;
        int samples=LoopMath.cacheSamples(b-a);
        cache=null;
        if (samples > 0) { try { cache=new float[samples]; } catch (OutOfMemoryError ignored) { /* seek fallback */ } }
        cacheFilled=cacheRead=0;
        resetAt(a);
        status=cache != null ? "A–B loop · buffering first pass" : "A–B loop · seek mode";
    }
    private void load(int selected) throws Exception {
        if (selected < 0 || selected >= queue.size()) return;
        setPlaying(false); releaseAudio();
        index=selected; title=queue.get(index).name; format=""; status="Opening…"; resumeOnFocus=false;
        a=b=-1; loop=false; duration=0; publish(); updateSession();
        try (ParcelFileDescriptor file=getContentResolver().openFileDescriptor(queue.get(index).uri,"r")) {
            if (file==null) throw new IOException("Cannot open selected file");
            decoder=NativeDecoder.open(file.getFd());
        }
        int sourceRate=NativeDecoder.sourceRate(decoder);
        Track selectedTrack=queue.get(index);
        long sourceDuration=NativeDecoder.duration(decoder);
        long end=selectedTrack.endMs;
        if (sourceDuration>0) end=end<0 ? sourceDuration : Math.min(end,sourceDuration);
        if (end>=0 && end<=selectedTrack.startMs) throw new IOException("Track is outside the audio image");
        duration=end<0 ? 0 : end-selectedTrack.startMs;
        format=NativeDecoder.description(decoder);
        rate=sourceRate;
        try { output=createOutput(rate); }
        catch (IllegalArgumentException | UnsupportedOperationException e) {
            rate=48000; output=createOutput(rate);
        }
        NativeDecoder.outputRate(decoder,rate);
        sourceStart=LoopMath.frame(selectedTrack.startMs,rate);
        trackEnd=end<0 ? Long.MAX_VALUE : LoopMath.frame(end,rate)-sourceStart;
        if (sourceStart>0) NativeDecoder.seek(decoder,sourceStart);
        format += "\nOutput: stereo float PCM · " + rate + " Hz (requested)";
        cursor=base=written=headEpoch=lastHead=0; blockSamples=blockOffset=0; eof=false;
        status=sourceRate==rate ? "Ready" : "Resampling to 48000 Hz";
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build());
        setPlaying(true);
    }
    private AudioTrack createOutput(int hz) {
        int minimum=AudioTrack.getMinBufferSize(hz,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) throw new IllegalArgumentException("Unsupported output sample rate: " + hz);
        AudioTrack result = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(hz).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(Math.max(minimum,hz / 10 * 8)).setTransferMode(AudioTrack.MODE_STREAM).build();
        if (result.getState()!=AudioTrack.STATE_INITIALIZED) { result.release(); throw new IllegalArgumentException("Audio output initialization failed"); }
        return result;
    }
    private void setPlaying(boolean value) throws IOException {
        if (output==null) return;
        if (value && eof && playedFrames()>=written) resetAt(loop ? a : 0);
        if (value && !playing) {
            if (audioManager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status="Audio focus denied"; return; }
            output.play(); playing=true; if (!wake.isHeld()) wake.acquire();
        } else if (!value) {
            output.pause(); playing=false; if (wake.isHeld()) wake.release(); audioManager.abandonAudioFocusRequest(focus);
        }
        publish(); updateSession();
    }
    private void focusChanged(int change) throws IOException {
        if (change==AudioManager.AUDIOFOCUS_GAIN) {
            if (output!=null) output.setVolume(1f);
            if (resumeOnFocus) { resumeOnFocus=false; setPlaying(true); }
        } else if (change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (output!=null) output.setVolume(0.2f);
        } else if (change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Keep the focus request registered so Android can send the gain event.
            resumeOnFocus=playing;
            if (output!=null) output.pause();
            playing=false; if (wake.isHeld()) wake.release();
            publish(); updateSession();
        } else if (change==AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocus=false; setPlaying(false);
        }
    }
    private long playedFrames() {
        if (output==null) return 0;
        long head=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
        if (head<lastHead) headEpoch+=1L<<32;
        lastHead=head; return headEpoch+head;
    }
    private long positionFrames() { return output==null ? 0 : LoopMath.position(base,playedFrames(),a,b,loop); }
    private void resetAt(long target) throws IOException {
        // A seek invalidates decoder state as well as queued PCM. One worker owns both.
        boolean wasPlaying=playing;
        output.pause(); output.flush(); headEpoch=lastHead=written=0;
        base=cursor=target; blockSamples=blockOffset=0; eof=false;
        if (cache!=null && cacheFilled==cache.length && loop) cacheRead=(int)((target-a)*2);
        else {
            if (cache!=null) cacheFilled=cacheRead=0;
            NativeDecoder.seek(decoder,sourceStart+target);
        }
        if (wasPlaying) output.play();
    }
    private boolean pump() throws Exception {
        if (blockOffset<blockSamples) {
            int n=output.write(block,blockOffset,blockSamples-blockOffset,AudioTrack.WRITE_NON_BLOCKING);
            if (n<0) throw new IOException("Audio output error " + n);
            if ((n&1)!=0) throw new IOException("Unaligned stereo audio write");
            blockOffset+=n; written+=n/2;
            return n>0;
        }
        if (eof) {
            if (playedFrames()>=written) {
                if (index+1<queue.size()) load(index+1);
                else { setPlaying(false); status="End of queue"; }
            }
            return false;
        }
        if (loop && cursor>=b) {
            cursor=a;
            if (cache!=null && cacheFilled==cache.length) { cacheRead=0; status="A–B loop · cached"; }
            else { cache=null; NativeDecoder.seek(decoder,sourceStart+a); status="A–B loop · seek mode"; }
        }
        long remaining=(loop ? b : trackEnd)-cursor;
        if (remaining<=0) { eof=true; return false; }
        int requested=(int)Math.min(block.length/2,remaining);
        int count;
        if (cache!=null && cacheFilled==cache.length) {
            count=Math.min(requested,(cache.length-cacheRead)/2);
            System.arraycopy(cache,cacheRead,block,0,count*2); cacheRead+=count*2;
        } else {
            count=NativeDecoder.read(decoder,block,requested);
            if (cache!=null && count>0) { System.arraycopy(block,0,cache,cacheFilled,count*2); cacheFilled+=count*2; }
        }
        if (count==0) {
            if (loop) throw new IOException("Loop ends beyond decoded audio; clear or adjust B");
            eof=true; return false;
        }
        cursor+=count; blockSamples=count*2; blockOffset=0;
        return true;
    }
    private void work() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try {
            while (running) {
                try {
                    Command command=commands.poll(playing ? 0 : 100,TimeUnit.MILLISECONDS);
                    if (command!=null) { command.run(); publish(); updateSession(); }
                    if (playing && output!=null && !pump()) Thread.sleep(5);
                    long now=SystemClock.elapsedRealtime();
                    if (now-lastPublish>=200) { publish(); lastPublish=now; }
                    if (now-lastSession>=1000) { updateSession(false); lastSession=now; }
                } catch (InterruptedException e) { if (!running) break; }
                catch (Exception | LinkageError e) {
                    releaseAudio(); loop=false; status="Playback failed: " + (e.getMessage()==null ? e.toString() : e.getMessage());
                    publish(); updateSession();
                }
            }
        } finally { releaseAudio(); session.setActive(false); session.release(); }
    }
    private void releaseAudio() {
        playing=false; cache=null; blockSamples=blockOffset=0;
        if (output!=null) { output.pause(); output.flush(); output.release(); output=null; }
        if (decoder!=0) { NativeDecoder.close(decoder); decoder=0; }
        if (wake.isHeld()) wake.release();
        audioManager.abandonAudioFocusRequest(focus);
    }
    private void publish() {
        snapshot=new Snapshot(title,format,status,output==null ? 0 : LoopMath.millis(positionFrames(),rate),duration,
            a<0 ? -1 : LoopMath.millis(a,rate), b<0 ? -1 : LoopMath.millis(b,rate),playing,loop,decoder!=0);
    }
    private void updateSession() { updateSession(true); }
    private void updateSession(boolean notify) {
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,snapshot.position,playing ? 1f : 0f).build());
        if (notify && foreground) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification());
    }
    private PendingIntent action(String name) {
        return PendingIntent.getService(this,name.hashCode(),new Intent(this,PlaybackService.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Snapshot s=snapshot;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_music).setContentTitle(s.title)
            .setContentText(s.playing ? "Playing" : "Paused").setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(s.playing).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"Previous",action("previous")).build())
            .addAction(new Notification.Action.Builder(s.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,s.playing ? "Pause" : "Play",action("toggle")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"Next",action("next")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop",action("stop")).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    @Override public void onDestroy() {
        unregisterReceiver(noisy); running=false; worker.interrupt();
        // Native IO may still be unwinding; the worker releases its own resources.
        super.onDestroy();
    }
}
