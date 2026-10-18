package net.astropenguin.loopdeck;

import android.app.*;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import static net.astropenguin.loopdeck.PlayerController.Action.*;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER=10, BT_PERMISSION=11;
    private static final int TEXT=Color.rgb(232,240,235), MUTED=Color.rgb(159,182,168);
    private final Handler main=new Handler(Looper.getMainLooper());
    private PlayerController controller;
    private PlayerController.Browser browser=PlayerController.Browser.empty();
    private Bundle restoredBrowser;
    private Uri pendingFolder;
    private boolean bound,dragging,resumed;
    private TextView folderLabel, trackTitle, trackFormat, times, loopTimes, status;
    private Button choose, bluetoothButton, pageBack, pageNext, find;
    private LinearLayout remotePages;
    private int pendingMode;
    private Button play, setA, setB, loopButton, clear, previous, next, stop;
    private SeekBar seek;
    private EntryAdapter adapter;

    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            controller=((PlaybackService.LocalBinder)binder).service().controller;
            controller.restoreBrowser(restoredBrowser); restoredBrowser=null;
            if (pendingFolder!=null) { controller.musicFolder(pendingFolder); pendingFolder=null; }
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { controller=null; }
    };
    private final Runnable tick=new Runnable() {
        @Override public void run() { if (resumed) { render(); main.postDelayed(this,250); } }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
        bound=bindService(new Intent(this,PlaybackService.class),connection,BIND_AUTO_CREATE);
        restoredBrowser=saved;
        if (saved!=null && saved.getString("pendingFolder")!=null) pendingFolder=Uri.parse(saved.getString("pendingFolder"));
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density*value); }
    private TextView text(String content, int sp, int color) {
        TextView view=new TextView(this); view.setText(content); view.setTextSize(sp); view.setTextColor(color); return view;
    }
    private LinearLayout row() {
        LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private Button button(LinearLayout parent, String label, View.OnClickListener listener) {
        Button view=new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(14);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setPadding(dp(4),0,dp(4),0);
        view.setOnClickListener(listener); parent.addView(view,new LinearLayout.LayoutParams(0,dp(48),1)); return view;
    }
    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(6),dp(12),dp(6)); setContentView(root);
        LinearLayout top=row();
        TextView brand=text("LoopDeck",22,TEXT); top.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        choose=new Button(this); choose.setText("Music folder"); choose.setAllCaps(false); choose.setOnClickListener(v -> { if (controller!=null) controller.chooseFolder(this::pickFolder); });
        top.addView(choose); root.addView(top);
        LinearLayout bluetoothRow=row();
        bluetoothButton=button(bluetoothRow,"Bluetooth: off",v -> bluetoothMenu());
        root.addView(bluetoothRow);
        remotePages=row();
        pageBack=button(remotePages,"‹ Page",v -> { if (controller!=null) controller.page(-1); });
        find=button(remotePages,"Find / refresh",v -> remoteSearch());
        pageNext=button(remotePages,"Page ›",v -> { if (controller!=null) controller.page(1); });
        remotePages.setVisibility(View.GONE); root.addView(remotePages);
        LinearLayout navigation=row();
        Button up=new Button(this); up.setText("↑"); up.setContentDescription("Parent folder"); up.setOnClickListener(v -> goUp());
        navigation.addView(up,new LinearLayout.LayoutParams(dp(48),dp(44)));
        folderLabel=text("Select a folder on internal storage or microSD",12,MUTED); folderLabel.setMaxLines(2);
        navigation.addView(folderLabel,new LinearLayout.LayoutParams(0,dp(44),1)); root.addView(navigation);
        ListView list=new ListView(this); adapter=new EntryAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id) -> { if (controller!=null) controller.open(browser,position); });
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        View divider=new View(this); divider.setBackgroundColor(Color.rgb(54,75,63)); root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        trackTitle=text("Choose a track",18,TEXT); trackTitle.setMaxLines(2); trackTitle.setPadding(0,dp(6),0,0); root.addView(trackTitle);
        trackFormat=text("FFmpeg audio player",11,MUTED); trackFormat.setMaxLines(2); root.addView(trackFormat);
        times=text("0:00.000 / 0:00.000",13,TEXT); root.addView(times);
        seek=new SeekBar(this); seek.setMax(10000); root.addView(seek,new LinearLayout.LayoutParams(-1,dp(36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging=true; }
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                if (user && controller!=null) times.setText(time(controller.snapshot().duration*value/10000)+" / "+time(controller.snapshot().duration));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (controller!=null) controller.seek(controller.snapshot().duration*bar.getProgress()/10000); dragging=false;
            }
        });
        LinearLayout transport=row();
        previous=button(transport,"Previous",v -> control(PREVIOUS));
        play=button(transport,"Play",v -> control(TOGGLE));
        next=button(transport,"Next",v -> control(NEXT));
        stop=button(transport,"Stop",v -> control(STOP)); root.addView(transport);
        LinearLayout markers=row();
        setA=button(markers,"Set A",v -> control(MARK_A));
        setB=button(markers,"Set B",v -> control(MARK_B));
        loopButton=button(markers,"Loop off",v -> control(LOOP));
        clear=button(markers,"Clear",v -> control(CLEAR)); root.addView(markers);
        setA.setOnLongClickListener(v -> { editMarkers(); return true; });
        setB.setOnLongClickListener(v -> { editMarkers(); return true; });
        loopTimes=text("A —     B —   ·   Hold Set A/B to edit times",11,MUTED); root.addView(loopTimes);
        status=text("Ready",12,MUTED); status.setMaxLines(3); status.setTextIsSelectable(true); root.addView(status);
    }
    private void pickFolder() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_FOLDER);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=PICK_FOLDER || result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri chosen=data.getData();
        try {
            getContentResolver().takePersistableUriPermission(chosen,data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (controller!=null) controller.musicFolder(chosen); else pendingFolder=chosen;
        } catch (Exception e) { showError("Folder permission failed: "+e.getMessage()); }
    }
    private void control(PlayerController.Action action) { if (controller!=null) controller.perform(action); }
    private boolean goUp() { return controller!=null && controller.up(); }
    @Override public void onBackPressed() { if (!goUp()) super.onBackPressed(); }
    private void render() {
        if (controller==null) return;
        PlayerController.Browser current=controller.browser();
        if (browser!=current) { browser=current; adapter.notifyDataSetChanged(); }
        remotePages.setVisibility(browser.paged ? View.VISIBLE : View.GONE);
        replace(choose,browser.rootLabel); choose.setEnabled(browser.ready);
        replace(folderLabel,browser.title);
        replace(bluetoothButton,controller.bluetoothStatus());
        pageBack.setEnabled(browser.previous); pageNext.setEnabled(browser.next); find.setEnabled(browser.ready);
        PlaybackService.Snapshot s=controller.snapshot();
        replace(trackTitle,s.title); replace(trackFormat,s.format); replace(status,controller.status());
        replace(play,s.playing ? "Pause" : "Play"); replace(loopButton,s.loop ? "Loop on" : "Loop off");
        if (!dragging) {
            replace(times,time(s.position)+" / "+time(s.duration));
            int value=s.duration>0 ? (int)Math.min(10000,s.position*10000/s.duration) : 0;
            if (seek.getProgress()!=value) seek.setProgress(value);
        }
        boolean available=controller.available();
        previous.setEnabled(available); next.setEnabled(available);
        seek.setEnabled(available && s.loaded && s.duration>0);
        play.setEnabled(available && s.loaded); stop.setEnabled(available && s.loaded);
        setA.setEnabled(available && s.loaded); setB.setEnabled(available && s.loaded); loopButton.setEnabled(available && s.loaded); clear.setEnabled(available && s.loaded);
        replace(loopTimes,"A "+(s.a<0 ? "—" : time(s.a))+"     B "+(s.b<0 ? "—" : time(s.b))+"   ·   Hold to edit");
    }
    private static void replace(TextView view,String value) { if (!view.getText().toString().equals(value)) view.setText(value); }
    private static String time(long ms) {
        ms=Math.max(ms,0);
        return String.format(Locale.ROOT,"%d:%02d.%03d",ms/60000,ms/1000%60,ms%1000);
    }
    private void editMarkers() {
        if (controller==null || !controller.snapshot().loaded) return;
        PlaybackService.Snapshot s=controller.snapshot();
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),0,dp(20),0);
        form.addView(text("Times in milliseconds. Leave a field blank to unset it.",14,MUTED));
        EditText inputA=new EditText(this), inputB=new EditText(this);
        inputA.setHint("A (ms)"); inputB.setHint("B (ms)");
        inputA.setInputType(InputType.TYPE_CLASS_NUMBER); inputB.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (s.a>=0) inputA.setText(Long.toString(s.a)); if (s.b>=0) inputB.setText(Long.toString(s.b));
        form.addView(inputA); form.addView(inputB);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit A–B loop").setView(form)
            .setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
            try {
                long newA=inputA.getText().length()==0 ? -1 : Long.parseLong(inputA.getText().toString());
                long newB=inputB.getText().length()==0 ? -1 : Long.parseLong(inputB.getText().toString());
                if (newA>Long.MAX_VALUE/384000 || newB>Long.MAX_VALUE/384000) throw new NumberFormatException();
                if (newA>=0 && newB>=0 && newB<=newA) { inputB.setError("B must be after A"); return; }
                if (controller!=null) controller.markers(newA,newB); dialog.dismiss();
            } catch (NumberFormatException e) { inputA.setError("Enter valid milliseconds"); }
        })); dialog.show();
    }
    private void bluetoothMenu() {
        if (controller==null) return;
        new AlertDialog.Builder(this).setTitle("Bluetooth mode")
            .setItems(new String[]{"Local player / Bluetooth off","Server · play music here","Client · control a paired player"},(dialog,which) -> {
                if (which==0) { controller.stopBluetooth(); render(); }
                else requestBluetooth(which);
            }).setNegativeButton("Cancel",null).show();
    }
    private void requestBluetooth(int mode) {
        pendingMode=mode;
        if (Build.VERSION.SDK_INT>=31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},BT_PERMISSION); return;
        }
        configureBluetooth(mode);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if (request==BT_PERMISSION) {
            if (grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) configureBluetooth(pendingMode);
            else showError("Nearby devices permission is required for Bluetooth control.");
        }
    }
    private void configureBluetooth(int mode) {
        if (controller==null) return;
        try {
            BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter bluetooth=manager==null ? null : manager.getAdapter();
            if (bluetooth==null || !bluetooth.isEnabled()) { showError("Enable Bluetooth in Android Settings first."); return; }
            if (mode==BluetoothLink.SERVER) {
                if (!controller.hasMusicFolder()) { showError("Choose a Music folder on this player first."); return; }
                controller.startBluetooth(mode,null); render(); return;
            }
            ArrayList<BluetoothDevice> devices=new ArrayList<>(bluetooth.getBondedDevices());
            devices.sort((a,b) -> deviceLabel(a).compareToIgnoreCase(deviceLabel(b)));
            if (devices.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("Paired player · start Server there first")
                    .setMessage("Pair the phone and player in Android Bluetooth Settings first, then return here.")
                    .setPositiveButton("OK",null).show(); return;
            }
            String[] labels=new String[devices.size()];
            for (int i=0;i<labels.length;i++) labels[i]=deviceLabel(devices.get(i));
            new AlertDialog.Builder(this).setTitle("Select a paired device").setItems(labels,(dialog,index) -> {
                controller.startBluetooth(BluetoothLink.CLIENT,devices.get(index).getAddress()); render();
            }).setNegativeButton("Cancel",null).show();
        } catch (Exception e) { showError("Bluetooth: "+e.getMessage()); }
    }
    private static String deviceLabel(BluetoothDevice device) {
        String name=device.getName(); return (name==null ? "Bluetooth device" : name)+" · "+device.getAddress();
    }
    private void remoteSearch() {
        if (controller==null) return;
        EditText query=new EditText(this); query.setSingleLine(true); query.setHint("Filename in this folder (blank = all)");
        new AlertDialog.Builder(this).setTitle("Find in remote folder").setView(query)
            .setPositiveButton("Find",(dialog,which) -> { if (controller!=null) controller.filter(query.getText().toString()); })
            .setNeutralButton("Refresh root",(dialog,which) -> { if (controller!=null) controller.refresh(); })
            .setNegativeButton("Cancel",null).show();
    }
    private void showError(String message) { new AlertDialog.Builder(this).setTitle("LoopDeck").setMessage(message).setPositiveButton("OK",null).show(); }
    @Override protected void onResume() { super.onResume(); resumed=true; main.post(tick); }
    @Override protected void onPause() { resumed=false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (controller!=null) controller.saveBrowser(out);
        else if (restoredBrowser!=null) out.putAll(restoredBrowser);
        if (pendingFolder!=null) out.putString("pendingFolder",pendingFolder.toString());
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(tick);
        if (bound) unbindService(connection); super.onDestroy();
    }
    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return browser.items.size(); }
        @Override public Object getItem(int position) { return browser.items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position,View reusable,ViewGroup parent) {
            TextView view=reusable instanceof TextView ? (TextView)reusable : text("",15,TEXT);
            view.setPadding(dp(10),dp(12),dp(6),dp(12)); view.setMinHeight(dp(50)); view.setMaxLines(2);
            PlayerController.Item item=browser.items.get(position); boolean folder=item.folder; String name=item.name;
            view.setText((folder ? "▸  " : "")+name);
            view.setTextColor(folder ? Color.rgb(115,218,193) : TEXT); return view;
        }
    }
}
