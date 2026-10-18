package net.astropenguin.loopdeck;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Length-prefixed UTF-8 JSON; bounded before allocating or parsing. */
final class RemoteWire {
    static final int VERSION=1, MAX_BYTES=64*1024;
    static String read(DataInputStream in) throws IOException {
        int size=in.readInt();
        if (size<1 || size>MAX_BYTES) throw new IOException("Invalid Bluetooth message size");
        byte[] bytes=new byte[size]; in.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Invalid Bluetooth UTF-8",e); }
    }
    static void write(DataOutputStream out,String json) throws IOException {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length<1 || bytes.length>MAX_BYTES) throw new IOException("Bluetooth message is too large");
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
}
