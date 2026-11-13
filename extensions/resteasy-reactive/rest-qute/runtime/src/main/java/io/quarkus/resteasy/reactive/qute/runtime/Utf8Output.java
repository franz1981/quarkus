package io.quarkus.resteasy.reactive.qute.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import io.quarkus.qute.TemplateInstance;

/**
 * A template rendered as UTF-8 bytes, without building the page as a {@link String}. It is the response entity of
 * endpoints returning a {@link TemplateInstance}; {@link #toString()} decodes it, once, for code that needs the text.
 * <p>
 * Unpaired surrogates are encoded as {@code '?'}, like {@link String#getBytes(java.nio.charset.Charset)}.
 */
public final class Utf8Output implements Consumer<String>, CharSequence {

    // bounds the unused tail of the last chunk and keeps every array far below the large object thresholds of the GCs
    private static final int MAX_CHUNK_SIZE = 16 * 1024;
    // chars batched per encoder call: the initial buffer size of the JDK StreamEncoder, far above the 32 chars of the
    // vectorized ASCII loop of the encoder
    private static final int SCRATCH_CHARS = 512;

    static CompletionStage<Utf8Output> render(TemplateInstance instance) {
        Utf8Output output = new Utf8Output();
        return instance.consume(output).thenApply(output::finish);
    }

    private byte[][] chunks = new byte[8][];
    private int[] lengths = new int[8];
    private int count;
    private ByteBuffer out;
    private int total;
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private char[] chars;
    private CharBuffer in;
    // chars in the scratch not encoded yet
    private int pending;
    private String string;

    private Utf8Output() {
    }

    @Override
    public void accept(String s) {
        int len = s.length();
        if (len == 0) {
            return;
        }
        if (chars == null) {
            chars = new char[SCRATCH_CHARS];
            in = CharBuffer.wrap(chars);
        }
        int off = 0;
        while (off < len) {
            if (pending == chars.length) {
                encode(false);
            }
            int n = Math.min(len - off, chars.length - pending);
            s.getChars(off, off + n, chars, pending);
            pending += n;
            off += n;
        }
    }

    /**
     * With REPLACE the encoder only overflows (the next chunk continues) or underflows; unless it is the last call, a
     * trailing high surrogate is left in the scratch to pair with the next char.
     * <p>
     * The first chunk has one byte per pending char: the exact size for ASCII, and the whole page when it fits in the
     * scratch. The next ones double.
     */
    private void encode(boolean last) {
        in.limit(pending).position(0);
        if (out == null) {
            nextChunk(pending);
        }
        while (encoder.encode(in, out, last).isOverflow()) {
            nextChunk(Math.min(out.capacity() * 2, MAX_CHUNK_SIZE));
        }
        pending = in.remaining();
        if (pending == 1) {
            chars[0] = chars[in.position()];
        }
    }

    private Utf8Output finish(Object ignored) {
        if (chars != null) {
            encode(true);
            while (encoder.flush(out).isOverflow()) {
                nextChunk(Math.min(out.capacity() * 2, MAX_CHUNK_SIZE));
            }
            lengths[count - 1] = out.position();
            total += out.position();
        }
        return this;
    }

    private void nextChunk(int size) {
        if (out != null) {
            lengths[count - 1] = out.position();
            total += out.position();
        }
        if (count == chunks.length) {
            chunks = Arrays.copyOf(chunks, count * 2);
            lengths = Arrays.copyOf(lengths, count * 2);
        }
        byte[] chunk = chunks[count++] = new byte[size];
        out = ByteBuffer.wrap(chunk);
    }

    /**
     * @return the number of bytes
     */
    int byteLength() {
        return total;
    }

    int chunkCount() {
        return count;
    }

    /**
     * @return the chunk at the given index; only its first {@link #chunkLength(int)} bytes are valid
     */
    byte[] chunk(int index) {
        return chunks[index];
    }

    int chunkLength(int index) {
        return lengths[index];
    }

    byte[] toByteArray() {
        byte[] bytes = new byte[total];
        int off = 0;
        for (int i = 0; i < count; i++) {
            System.arraycopy(chunks[i], 0, bytes, off, lengths[i]);
            off += lengths[i];
        }
        return bytes;
    }

    @Override
    public String toString() {
        String s = string;
        if (s == null) {
            s = string = count == 1
                    ? new String(chunks[0], 0, lengths[0], StandardCharsets.UTF_8)
                    : new String(toByteArray(), StandardCharsets.UTF_8);
        }
        return s;
    }

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().subSequence(start, end);
    }
}
