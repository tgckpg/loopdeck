package net.astropenguin.loopdeck;

import java.util.List;

public final class CueSheetTest {
    private static void expect(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        List<CueSheet.Part> tracks=CueSheet.parse("\ufeffTITLE \"Album\"\r\nFILE \"image.tak\" WAVE\r\n"+
            "  TRACK 01 AUDIO\r\n    TITLE \"日本語 🎵\"\r\n    INDEX 01 00:00:00\r\n"+
            "  TRACK 02 AUDIO\r\n    INDEX 00 03:58:00\r\n    INDEX 01 04:00:37\r\n");
        expect(tracks.size()==2);
        expect(tracks.get(0).title.equals("日本語 🎵"));
        expect(tracks.get(0).file.equals("image.tak"));
        expect(tracks.get(0).start==0 && tracks.get(0).end==240493);
        expect(tracks.get(1).start==240493 && tracks.get(1).end==-1 && tracks.get(1).title.equals("Track 2"));
        tracks=CueSheet.parse("FILE a.tta WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n"+
            "FILE b.tta WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00");
        expect(tracks.size()==2 && tracks.get(0).end==-1);
        expect(CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:60:00").isEmpty());
        expect(CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:75").isEmpty());
        expect(CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 02:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00").isEmpty());
        expect(CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00\nINDEX 01 00:01:00").isEmpty());
        expect(CueSheet.parse("TRACK 01 MODE1/2352\nINDEX 01 00:00:00").isEmpty());
        tracks=CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 100:00:00");
        expect(tracks.size()==2 && tracks.get(0).end==6000000);
        tracks=CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00\nTITLE \"After index\"");
        expect(tracks.get(0).title.equals("After index"));
        System.out.println("CUE parsing passed");
    }
}
