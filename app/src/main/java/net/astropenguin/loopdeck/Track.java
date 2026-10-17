package net.astropenguin.loopdeck;

import android.net.Uri;

final class Track {
    final Uri uri;
    final String name;
    final long startMs, endMs;
    Track(Uri uri, String name) { this(uri,name,0,-1); }
    Track(Uri uri, String name, long startMs, long endMs) {
        this.uri=uri; this.name=name; this.startMs=startMs; this.endMs=endMs;
    }
}
