package net.astropenguin.loopdeck;

import android.net.Uri;

final class Track {
    final Uri uri;
    final String name;
    Track(Uri uri, String name) { this.uri = uri; this.name = name; }
}
