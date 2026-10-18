package net.astropenguin.loopdeck;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Shared local/remote folder and album reader. All methods run off the UI thread. */
final class MusicLibrary {
    static final class Entry {
        final String id,name; final Uri uri; final boolean folder,audio;
        final Track track;
        Entry(Track track) {
            this.track=track; id=track.uri.toString(); name=track.name; uri=track.uri; folder=false; audio=true;
        }
        Entry(String id,String name,String mime,Uri uri) {
            this.id=id; this.name=name; this.uri=uri; track=null;
            folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            audio=(mime!=null && mime.startsWith("audio/")) || name.toLowerCase(Locale.ROOT).matches(
                ".*\\.(mp3|mp2|mp1|flac|ape|tak|tta|wv|wavpack|wma|wav|wave|aif|aiff|aifc|m4a|mp4|aac|ogg|oga|opus|mpc|mpp|shn|dsf|dff|ac3|dts|amr|au|snd|caf|ra|rm|spx|mka)$");
        }
    }

    static ArrayList<Entry> list(Context context,Uri tree,String directory) throws IOException {
        ArrayList<Entry> result=new ArrayList<>();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,directory);
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c=context.getContentResolver().query(children,columns,null,null,null)) {
            if (c==null) throw new IOException("Storage provider returned no folder listing");
            while (c.moveToNext()) {
                String id=c.getString(0),name=c.getString(1);
                result.add(new Entry(id,name==null ? id : name,c.getString(2),DocumentsContract.buildDocumentUriUsingTree(tree,id)));
            }
        }
        result.sort((x,y) -> x.folder!=y.folder ? (x.folder ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
        return result;
    }
    static ArrayList<Track> tracks(Context context,Entry entry,List<Entry> input) throws IOException {
        ArrayList<Entry> folder=new ArrayList<>(input);
        ArrayList<Track> found=new ArrayList<>();
        if (isCue(entry)) {
            found=cueTracks(readCue(context,entry),folder,null);
            if (found.isEmpty()) throw new IOException("No usable audio tracks. CUE FILE names must match audio files in this folder.");
        } else {
            // Prefer a same-named sheet, then sheets that explicitly reference this image.
            String stem=entry.name.replaceFirst("\\.[^.]+$","");
            folder.sort((x,y) -> Boolean.compare(!x.name.equalsIgnoreCase(stem+".cue"),!y.name.equalsIgnoreCase(stem+".cue")));
            for (Entry candidate:folder) if (isCue(candidate)) {
                try { found=cueTracks(readCue(context,candidate),folder,entry); } catch (IOException ignored) { continue; }
                if (!found.isEmpty()) break;
            }
            if (found.isEmpty()) {
                long handle=0;
                try (ParcelFileDescriptor file=context.getContentResolver().openFileDescriptor(entry.uri,"r")) {
                    if (file==null) throw new IOException("Cannot open selected file");
                    handle=NativeDecoder.open(file.getFd());
                    found=cueTracks(NativeDecoder.cueSheet(handle),folder,entry,true);
                    if (found.isEmpty()) {
                        String[] chapters=NativeDecoder.chapters(handle);
                        long total=NativeDecoder.duration(handle), previousEnd=-1;
                        for (int i=0;i<chapters.length;i+=3) {
                            long start=Long.parseLong(chapters[i]), end=Long.parseLong(chapters[i+1]);
                            if (end<=start && i+3<chapters.length) end=Long.parseLong(chapters[i+3]);
                            if (end<=start) end=total;
                            if (start<0 || start<previousEnd || end<=start || (total>0 && end>total)) { found.clear(); break; }
                            found.add(new Track(entry.uri,(i/3+1)+". "+chapters[i+2],start,end)); previousEnd=end;
                        }
                    }
                } finally { if (handle!=0) NativeDecoder.close(handle); }
            }
        }
        return found;
    }
    static boolean isCue(Entry e) { return e.name.toLowerCase(Locale.ROOT).endsWith(".cue"); }
    private static String readCue(Context context,Entry e) throws IOException {
        try (InputStream in=context.getContentResolver().openInputStream(e.uri)) {
            if (in==null) throw new IOException("Cannot open CUE sheet");
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
            while ((n=in.read(buffer))!=-1) {
                if (out.size()+n>2*1024*1024) throw new IOException("CUE sheet is too large");
                out.write(buffer,0,n);
            }
            byte[] bytes=out.toByteArray();
            if (bytes.length>=2 && ((bytes[0]==(byte)0xff && bytes[1]==(byte)0xfe) ||
                (bytes[0]==(byte)0xfe && bytes[1]==(byte)0xff))) return new String(bytes,StandardCharsets.UTF_16);
            try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException invalidUtf8) { return new String(bytes,Charset.forName("windows-31j")); }
        }
    }
    private static ArrayList<Track> cueTracks(String text,List<Entry> folder,Entry image) {
        return cueTracks(text,folder,image,false);
    }
    private static ArrayList<Track> cueTracks(String text,List<Entry> folder,Entry image,boolean embedded) {
        ArrayList<Track> result=new ArrayList<>();
        for (CueSheet.Part part:CueSheet.parse(text)) {
            Entry target=null;
            String name=part.file.replace('\\','/');
            while (name.startsWith("./")) name=name.substring(2);
            if (image!=null && (embedded || name.isEmpty() || name.equalsIgnoreCase(image.name))) target=image;
            if (image==null) for (Entry candidate:folder)
                if (candidate.audio && candidate.name.equalsIgnoreCase(name)) { target=candidate; break; }
            if (target!=null) result.add(new Track(target.uri,part.number+". "+part.title,part.start,part.end));
            else if (image==null) return new ArrayList<>(); // Never play a partial/misresolved sheet.
        }
        return result;
    }
}
