package com.alibaba.fastjson2;

import com.alibaba.fastjson2.util.DoubleToDecimal;
import com.alibaba.fastjson2.util.IOUtils;
import com.alibaba.fastjson2.writer.ObjectWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.alibaba.fastjson2.JSONFactory.*;
import static com.alibaba.fastjson2.JSONWriter.Feature.*;
import static com.alibaba.fastjson2.util.IOUtils.*;
import static com.alibaba.fastjson2.util.JDKUtils.*;
import static com.alibaba.fastjson2.util.TypeUtils.isInt64;

class JSONWriterUTF8
        extends JSONWriter {
    static final long REF;
    static final short QUOTE2_COLON, QUOTE_COLON;
    static final short[] HEX256;

    static {
        short[] digits = new short[16 * 16];

        for (int i = 0; i < 16; i++) {
            short hi = (short) (i < 10 ? i + '0' : i - 10 + 'a');

            for (int j = 0; j < 16; j++) {
                short lo = (short) (j < 10 ? j + '0' : j - 10 + 'a');
                digits[(i << 4) + j] = BIG_ENDIAN ? (short) ((hi << 8) | lo) : (short) (hi | (lo << 8));
            }
        }

        HEX256 = digits;

        byte[] chars = {'{', '"', '$', 'r', 'e', 'f', '"', ':'};
        REF = UNSAFE.getLong(chars, ARRAY_CHAR_BASE_OFFSET);
        QUOTE2_COLON = UNSAFE.getShort(chars, ARRAY_CHAR_BASE_OFFSET + 6);
        chars[6] = '\'';
        QUOTE_COLON = UNSAFE.getShort(chars, ARRAY_CHAR_BASE_OFFSET + 6);
    }

    final CacheItem cacheItem;
    protected byte[] bytes;
    protected final long byteVectorQuote;

    JSONWriterUTF8(Context ctx) {
        super(ctx, null, false, StandardCharsets.UTF_8);
        int cacheIndex = System.identityHashCode(Thread.currentThread()) & (CACHE_ITEMS.length - 1);
        cacheItem = CACHE_ITEMS[cacheIndex];
        byte[] bytes = BYTES_UPDATER.getAndSet(cacheItem, null);
        if (bytes == null) {
            bytes = new byte[8192];
        }
        this.bytes = bytes;
        this.byteVectorQuote = this.useSingleQuote ? 0x2727_2727_2727_2727L : 0x2222_2222_2222_2222L;
    }

    public final void writeNull() {
        int off = this.off;
        byte[] bytes = this.bytes;
        if (off + 4 > bytes.length) {
            bytes = grow(off + 4);
        }
        UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off, NULL_32);
        this.off = off + 4;
    }

    @Override
    public final void writeReference(String path) {
        this.lastReference = path;
        int off = this.off;
        byte[] bytes = this.bytes;
        if (off + 8 > bytes.length) {
            bytes = grow(off + 8);
        }
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, REF);
        this.off = off + 8;
        writeString(path);
        off = this.off;
        bytes = this.bytes;
        if (off == bytes.length) {
            bytes = grow(off + 1);
        }
        bytes[off] = '}';
        this.off = off + 1;
    }

    @Override
    public final void writeBase64(byte[] value) {
        int charsLen = ((value.length - 1) / 3 + 1) << 2; // base64 character count

        int off = this.off;
        int minCapacity = off + charsLen + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;

        int eLen = (value.length / 3) * 3; // Length of even 24-bits.

        for (int s = 0; s < eLen; ) {
            // Copy next three bytes into lower 24 bits of int, paying attension to sign.
            int i = (value[s++] & 0xff) << 16 | (value[s++] & 0xff) << 8 | (value[s++] & 0xff);

            // Encode the int into four chars
            bytes[off] = (byte) CA[(i >>> 18) & 0x3f];
            bytes[off + 1] = (byte) CA[(i >>> 12) & 0x3f];
            bytes[off + 2] = (byte) CA[(i >>> 6) & 0x3f];
            bytes[off + 3] = (byte) CA[i & 0x3f];
            off += 4;
        }

        // Pad and encode last bits if source isn't even 24 bits.
        int left = value.length - eLen; // 0 - 2.
        if (left > 0) {
            // Prepare the int
            int i = ((value[eLen] & 0xff) << 10) | (left == 2 ? ((value[value.length - 1] & 0xff) << 2) : 0);

            // Set last four chars
            bytes[off] = (byte) CA[i >> 12];
            bytes[off + 1] = (byte) CA[(i >>> 6) & 0x3f];
            bytes[off + 2] = left == 2 ? (byte) CA[i & 0x3f] : (byte) '=';
            bytes[off + 3] = '=';
            off += 4;
        }

        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeHex(byte[] values) {
        if (values == null) {
            writeNull();
            return;
        }

        int charsLen = values.length * 2 + 3;

        int off = this.off;
        int minCapacity = off + charsLen + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = 'x';
        bytes[off + 1] = '\'';
        off += 2;

        for (int i = 0; i < values.length; i++) {
            byte b = values[i];
            int a = b & 0xFF;
            int b0 = a >> 4;
            int b1 = a & 0xf;

            bytes[off] = (byte) (b0 + (b0 < 10 ? 48 : 55));
            bytes[off + 1] = (byte) (b1 + (b1 < 10 ? 48 : 55));
            off += 2;
        }

        bytes[off] = '\'';
        this.off = off + 1;
    }

    @Override
    public final void close() {
        byte[] bytes = this.bytes;
        if (bytes.length > CACHE_THRESHOLD) {
            return;
        }

        BYTES_UPDATER.lazySet(cacheItem, bytes);
    }

    public final int size() {
        return off;
    }

    @Override
    public final byte[] getBytes() {
        return Arrays.copyOf(bytes, off);
    }

    @Override
    public final byte[] getBytes(Charset charset) {
        if (charset == StandardCharsets.UTF_8) {
            return Arrays.copyOf(bytes, off);
        }

        return toString()
                .getBytes(charset);
    }

    @Override
    public final int flushTo(OutputStream to) throws IOException {
        int off = this.off;
        if (off > 0) {
            to.write(bytes, 0, off);
            this.off = 0;
        }
        return off;
    }

    @Override
    protected final void write0(char c) {
        int off = this.off;
        if (off == bytes.length) {
            ensureCapacity(off + 1);
        }
        bytes[off] = (byte) c;
        this.off = off + 1;
    }

    @Override
    public final void writeColon() {
        int off = this.off;
        if (off == bytes.length) {
            ensureCapacity(off + 1);
        }
        bytes[off] = ':';
        this.off = off + 1;
    }

    @Override
    public final void startObject() {
        if (++level > context.maxLevel) {
            overflowLevel();
        }

        startObject = true;

        int off = this.off;
        int minCapacity = off + 3 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) '{';

        if (pretty != PRETTY_NON) {
            off = indent(bytes, off);
        }
        this.off = off;
    }

    @Override
    public final void endObject() {
        level--;
        int off = this.off;
        int minCapacity = off + 1 + (pretty == 0 ? 0 : pretty * level + 1);
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (pretty != PRETTY_NON) {
            off = indent(bytes, off);
        }

        bytes[off] = (byte) '}';
        this.off = off + 1;
        startObject = false;
    }

    @Override
    public final void writeComma() {
        startObject = false;
        int off = this.off;
        int minCapacity = off + 2 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = ',';
        if (pretty != PRETTY_NON) {
            off = indent(bytes, off);
        }
        this.off = off;
    }

    @Override
    public final void startArray() {
        if (++level > context.maxLevel) {
            overflowLevel();
        }

        int off = this.off;
        int minCapacity = off + 3 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) '[';
        if (pretty != PRETTY_NON) {
            off = indent(bytes, off);
        }
        this.off = off;
    }

    @Override
    public final void endArray() {
        level--;
        int off = this.off;
        int minCapacity = off + 1 + (pretty == 0 ? 0 : pretty * level + 1);
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (pretty != PRETTY_NON) {
            off = indent(bytes, off);
        }
        bytes[off] = (byte) ']';
        this.off = off + 1;
        startObject = false;
    }

    public final void writeString(List<String> list) {
        if (pretty != PRETTY_NON) {
            super.writeString(list);
            return;
        }
        // startArray();
        int off = this.off;
        if (off == bytes.length) {
            grow0(off + 1);
        }
        bytes[off] = '[';
        this.off = off + 1;

        for (int i = 0, size = list.size(); i < size; i++) {
            if (i != 0) {
                off = this.off;
                if (off == bytes.length) {
                    grow0(off + 1);
                }
                bytes[off] = ',';
                this.off = off + 1;
            }

            writeString(
                    list.get(i));
        }

        off = this.off;
        if (off == bytes.length) {
            grow0(off + 1);
        }
        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeString(boolean value) {
        byte quote = (byte) this.quote;
        bytes[off++] = quote;
        writeBool(value);
        bytes[off++] = quote;
    }

    @Override
    public final void writeString(byte value) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) == 0;
        if (writeAsString) {
            writeQuote();
        }
        writeInt8(value);
        if (writeAsString) {
            writeQuote();
        }
    }

    @Override
    public final void writeString(short value) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) == 0;
        if (writeAsString) {
            writeQuote();
        }
        writeInt16(value);
        if (writeAsString) {
            writeQuote();
        }
    }

    @Override
    public final void writeString(int value) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) == 0;
        if (writeAsString) {
            writeQuote();
        }
        writeInt32(value);
        if (writeAsString) {
            writeQuote();
        }
    }

    @Override
    public final void writeString(long value) {
        boolean writeAsString = (context.features & (WriteNonStringValueAsString.mask | WriteLongAsString.mask)) == 0;
        if (writeAsString) {
            writeQuote();
        }
        writeInt64(value);
        if (writeAsString) {
            writeQuote();
        }
    }

    private void writeQuote() {
        if (off == bytes.length) {
            grow(off + 1);
        }
        bytes[off++] = (byte) quote;
    }

    @Override
    public void writeString(String str) {
        if (str == null) {
            writeStringNull();
            return;
        }

        char[] chars = getCharArray(str);

        boolean browserSecure = (context.features & BrowserSecure.mask) != 0;
        boolean escapeNoneAscii = (context.features & EscapeNoneAscii.mask) != 0;

        int off = this.off;
        // ensureCapacity
        int minCapacity = off
                + chars.length * 3 // utf8 3 bytes
                + 2;

        if (escapeNoneAscii || browserSecure) {
            minCapacity += chars.length * 3;
        }

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }

        bytes[off++] = (byte) quote;

        int i = 0;
        for (; i < chars.length; i++) {
            char c0 = chars[i];
            if (c0 == quote
                    || c0 == '\\'
                    || c0 < ' '
                    || c0 > 0x007F
                    || (browserSecure
                    && (c0 == '<' || c0 == '>' || c0 == '(' || c0 == ')'))
            ) {
                break;
            }
            bytes[off++] = (byte) c0;
        }

        if (i == chars.length) {
            bytes[off] = (byte) quote;
            this.off = off + 1;
            return;
        }

        this.off = off;
        if (i < chars.length) {
            writeStringEscapedRest(chars, chars.length, browserSecure, escapeNoneAscii, i);
        }

        this.bytes[this.off++] = (byte) quote;
    }

    public void writeStringLatin1(byte[] value) {
        boolean escape = false;
        if ((context.features & BrowserSecure.mask) != 0) {
            writeStringLatin1BrowserSecure(value);
            return;
        }

        final byte quote = (byte) this.quote;
        final long vecQuote = this.byteVectorQuote;
        int i = 0;
        final int upperBound = (value.length - i) & ~7;
        for (; i < upperBound; i += 8) {
            if (containsEscaped(IOUtils.getLongLittleEndian(value, i), vecQuote)) {
                break;
            }
        }
        for (; i < value.length; i++) {
            byte c = value[i];
            if (c == quote || c == '\\' || c < ' ') {
                escape = true;
                break;
            }
        }

        int off = this.off;
        if (escape) {
            writeStringEscaped(value);
            return;
        }

        int minCapacity = off + value.length + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = quote;
        System.arraycopy(value, 0, bytes, off + 1, value.length);
        off += value.length + 1;
        bytes[off] = quote;
        this.off = off + 1;
    }

    static boolean containsEscaped(long v, long quote) {
        /*
          for (int i = 0; i < 8; ++i) {
            byte c = (byte) data;
            if (c == quote || c == '\\' || c < ' ') {
                return true;
            }
            data >>>= 8;
          }
          return false;
         */
        long x22 = v ^ quote; // " -> 0x22, ' -> 0x27
        long x5c = v ^ 0x5C5C5C5C5C5C5C5CL;

        x22 = (x22 - 0x0101010101010101L) & ~x22;
        x5c = (x5c - 0x0101010101010101L) & ~x5c;

        return ((x22 | x5c | (0x7F7F7F7F7F7F7F7FL - v + 0x1010101010101010L) | v) & 0x8080808080808080L) != 0;
    }

    protected final void writeStringLatin1BrowserSecure(byte[] values) {
        boolean escape = false;

        final byte quote = (byte) this.quote;
        for (int i = 0; i < values.length; i++) {
            byte c = values[i];
            if (c == quote
                    || c == '\\'
                    || c < ' '
                    || c == '<'
                    || c == '>'
                    || c == '('
                    || c == ')'
            ) {
                escape = true;
                break;
            }
        }

        int off = this.off;
        if (!escape) {
            int minCapacity = off + values.length + 2;
            byte[] bytes = this.bytes;
            if (minCapacity > bytes.length) {
                bytes = grow(minCapacity);
            }
            bytes[off] = quote;
            System.arraycopy(values, 0, bytes, off + 1, values.length);
            off += values.length + 1;
            bytes[off] = quote;
            this.off = off + 1;
            return;
        }
        writeStringEscaped(values);
    }

    public final void writeStringUTF16(byte[] value) {
        if (value == null) {
            writeStringNull();
            return;
        }

        boolean escapeNoneAscii = (context.features & EscapeNoneAscii.mask) != 0;
        boolean browserSecure = (context.features & BrowserSecure.mask) != 0;

        int off = this.off;
        int minCapacity = off + value.length * 4 + 2;
        if (escapeNoneAscii) {
            minCapacity += value.length * 2;
        }

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;

        int valueOffset = 0;
        while (valueOffset < value.length) {
            byte b0 = value[valueOffset];
            byte b1 = value[valueOffset + 1];
            valueOffset += 2;

            if (b1 == 0 && b0 >= 0) {
//                bytes[off++] = b0;
                switch (b0) {
                    case '\\':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) '\\';
                        off += 2;
                        break;
                    case '\n':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'n';
                        off += 2;
                        break;
                    case '\r':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'r';
                        off += 2;
                        break;
                    case '\f':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'f';
                        off += 2;
                        break;
                    case '\b':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'b';
                        off += 2;
                        break;
                    case '\t':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 't';
                        off += 2;
                        break;
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('0' + (int) b0);
                        off += 6;
                        break;
                    case 11:
                    case 14:
                    case 15:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('a' + (b0 - 10));
                        off += 6;
                        break;
                    case 16:
                    case 17:
                    case 18:
                    case 19:
                    case 20:
                    case 21:
                    case 22:
                    case 23:
                    case 24:
                    case 25:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('0' + (b0 - 16));
                        off += 6;
                        break;
                    case 26:
                    case 27:
                    case 28:
                    case 29:
                    case 30:
                    case 31:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('a' + (b0 - 26));
                        off += 6;
                        break;
                    case '<':
                    case '>':
                    case '(':
                    case ')':
                        if (browserSecure) {
                            bytes[off] = '\\';
                            bytes[off + 1] = 'u';
                            bytes[off + 2] = '0';
                            bytes[off + 3] = '0';
                            bytes[off + 4] = (byte) DIGITS[(b0 >>> 4) & 15];
                            bytes[off + 5] = (byte) DIGITS[b0 & 15];
                            off += 6;
                        } else {
                            bytes[off++] = b0;
                        }
                        break;
                    default:
                        if (b0 == quote) {
                            bytes[off] = (byte) '\\';
                            bytes[off + 1] = (byte) quote;
                            off += 2;
                        } else {
                            bytes[off++] = b0;
                        }
                        break;
                }
            } else {
                char c = (char) ((b0 & 0xff) | ((b1 & 0xff) << 8));
                if (c < 0x800) {
                    // 2 bytes, 11 bits
                    bytes[off] = (byte) (0xc0 | (c >> 6));
                    bytes[off + 1] = (byte) (0x80 | (c & 0x3f));
                    off += 2;
                } else if (escapeNoneAscii) {
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = (byte) DIGITS[(c >>> 12) & 15];
                    bytes[off + 3] = (byte) DIGITS[(c >>> 8) & 15];
                    bytes[off + 4] = (byte) DIGITS[(c >>> 4) & 15];
                    bytes[off + 5] = (byte) DIGITS[c & 15];
                    off += 6;
                } else if (c >= '\uD800' && c < ('\uDFFF' + 1)) { //Character.isSurrogate(c) but 1.7
                    final int uc;
                    int ip = valueOffset - 1;
                    if (c < '\uDBFF' + 1) { // Character.isHighSurrogate(c)
                        if (value.length - ip < 2) {
                            uc = -1;
                        } else {
                            b0 = value[ip + 1];
                            b1 = value[ip + 2];
                            char d = (char) ((b0 & 0xff) | ((b1 & 0xff) << 8));
                            // d >= '\uDC00' && d < ('\uDFFF' + 1)
                            if (d >= '\uDC00' && d < ('\uDFFF' + 1)) { // Character.isLowSurrogate(d)
                                valueOffset += 2;
                                uc = ((c << 10) + d) + (0x010000 - ('\uD800' << 10) - '\uDC00'); // Character.toCodePoint(c, d)
                            } else {
                                bytes[off++] = '?';
                                continue;
                            }
                        }
                    } else {
                        //
                        // Character.isLowSurrogate(c)
                        bytes[off++] = '?';
                        continue;
                    }

                    if (uc < 0) {
                        bytes[off++] = (byte) '?';
                    } else {
                        bytes[off] = (byte) (0xf0 | ((uc >> 18)));
                        bytes[off + 1] = (byte) (0x80 | ((uc >> 12) & 0x3f));
                        bytes[off + 2] = (byte) (0x80 | ((uc >> 6) & 0x3f));
                        bytes[off + 3] = (byte) (0x80 | (uc & 0x3f));
                        off += 4;
                    }
                } else {
                    // 3 bytes, 16 bits
                    bytes[off] = (byte) (0xe0 | ((c >> 12)));
                    bytes[off + 1] = (byte) (0x80 | ((c >> 6) & 0x3f));
                    bytes[off + 2] = (byte) (0x80 | (c & 0x3f));
                    off += 3;
                }
            }
        }

        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    public final void writeString(final char[] chars) {
        if (chars == null) {
            writeStringNull();
            return;
        }

        boolean browserSecure = (context.features & BrowserSecure.mask) != 0;
        boolean escapeNoneAscii = (context.features & EscapeNoneAscii.mask) != 0;

        int off = this.off;
        int minCapacity = off
                + chars.length * 3 /* utf8 3 bytes */
                + 2;

        if (escapeNoneAscii || browserSecure) {
            minCapacity += chars.length * 3;
        }

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;

        int i = 0;
        for (; i < chars.length; i++) {
            char c = chars[i];
            if (c == quote
                    || c == '\\'
                    || c < ' '
                    || c > 0x007F
                    || (browserSecure
                    && (c == '<' || c == '>' || c == '(' || c == ')'))
            ) {
                break;
            }
            bytes[off++] = (byte) c;
        }

        this.off = off;
        if (i < chars.length) {
            writeStringEscapedRest(chars, chars.length, browserSecure, escapeNoneAscii, i);
        }

        this.bytes[this.off++] = (byte) quote;
    }

    public final void writeString(final char[] chars, int stroff, int strlen) {
        if (chars == null) {
            if (isEnabled(NullAsDefaultValue.mask | WriteNullStringAsEmpty.mask)) {
                writeString("");
                return;
            }

            writeNull();
            return;
        }

        int end = stroff + strlen;

        boolean browserSecure = (context.features & BrowserSecure.mask) != 0;
        boolean escapeNoneAscii = (context.features & EscapeNoneAscii.mask) != 0;

        int off = this.off;
        // ensureCapacity
        int minCapacity = off
                + strlen * 3 // utf8 3 bytes
                + 2;

        if (escapeNoneAscii || browserSecure) {
            minCapacity += strlen * 3;
        }

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;

        int i = stroff;
        for (; i < end; i++) {
            char c0 = chars[i];
            if (c0 == quote
                    || c0 == '\\'
                    || c0 < ' '
                    || c0 > 0x007F
                    || (browserSecure
                    && (c0 == '<' || c0 == '>' || c0 == '(' || c0 == ')'))
            ) {
                break;
            }
            bytes[off++] = (byte) c0;
        }
        this.off = off;
        if (i < end) {
            writeStringEscapedRest(chars, end, browserSecure, escapeNoneAscii, i);
        }

        this.bytes[this.off++] = (byte) quote;
    }

    protected final void writeStringEscaped(byte[] values) {
        int minCapacity = off + values.length * 6 + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }

        final boolean browserSecure = (context.features & BrowserSecure.mask) != 0;
        int off = this.off;
        bytes[off++] = (byte) quote;
        for (int i = 0; i < values.length; i++) {
            byte ch = values[i];
            switch (ch) {
                case '\\':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) '\\';
                    off += 2;
                    break;
                case '\n':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'n';
                    off += 2;
                    break;
                case '\r':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'r';
                    off += 2;
                    break;
                case '\f':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'f';
                    off += 2;
                    break;
                case '\b':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'b';
                    off += 2;
                    break;
                case '\t':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 't';
                    off += 2;
                    break;
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '0';
                    bytes[off + 5] = (byte) ('0' + (int) ch);
                    off += 6;
                    break;
                case 11:
                case 14:
                case 15:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '0';
                    bytes[off + 5] = (byte) ('a' + (ch - 10));
                    off += 6;
                    break;
                case 16:
                case 17:
                case 18:
                case 19:
                case 20:
                case 21:
                case 22:
                case 23:
                case 24:
                case 25:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '1';
                    bytes[off + 5] = (byte) ('0' + (ch - 16));
                    off += 6;
                    break;
                case 26:
                case 27:
                case 28:
                case 29:
                case 30:
                case 31:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '1';
                    bytes[off + 5] = (byte) ('a' + (ch - 26));
                    off += 6;
                    break;
                case '<':
                case '>':
                case '(':
                case ')':
                    if (browserSecure) {
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = (byte) DIGITS[(ch >>> 4) & 15];
                        bytes[off + 5] = (byte) DIGITS[ch & 15];
                        off += 6;
                    } else {
                        bytes[off++] = ch;
                    }
                    break;
                default:
                    if (ch == quote) {
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) quote;
                        off += 2;
                    } else if (ch < 0) {
                        // latin
                        int c = ch & 0xFF;
                        bytes[off] = (byte) (0xc0 | (c >> 6));
                        bytes[off + 1] = (byte) (0x80 | (c & 0x3f));
                        off += 2;
                    } else {
                        bytes[off++] = ch;
                    }
                    break;
            }
        }
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    protected final void writeStringEscapedRest(
            char[] chars,
            int end,
            boolean browserSecure,
            boolean escapeNoneAscii,
            int i
    ) {
        int rest = chars.length - i;
        int minCapacity = off + rest * 6 + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }

        int off = this.off;
        for (; i < end; ++i) { // ascii none special fast write
            char ch = chars[i];
            if (ch <= 0x007F) {
                switch (ch) {
                    case '\\':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) '\\';
                        off += 2;
                        break;
                    case '\n':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'n';
                        off += 2;
                        break;
                    case '\r':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'r';
                        off += 2;
                        break;
                    case '\f':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'f';
                        off += 2;
                        break;
                    case '\b':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'b';
                        off += 2;
                        break;
                    case '\t':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 't';
                        off += 2;
                        break;
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('0' + (int) ch);
                        off += 6;
                        break;
                    case 11:
                    case 14:
                    case 15:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('a' + (ch - 10));
                        off += 6;
                        break;
                    case 16:
                    case 17:
                    case 18:
                    case 19:
                    case 20:
                    case 21:
                    case 22:
                    case 23:
                    case 24:
                    case 25:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('0' + (ch - 16));
                        off += 6;
                        break;
                    case 26:
                    case 27:
                    case 28:
                    case 29:
                    case 30:
                    case 31:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('a' + (ch - 26));
                        off += 6;
                        break;
                    case '<':
                    case '>':
                    case '(':
                    case ')':
                        if (browserSecure) {
                            bytes[off] = '\\';
                            bytes[off + 1] = 'u';
                            bytes[off + 2] = '0';
                            bytes[off + 3] = '0';
                            bytes[off + 4] = (byte) DIGITS[(ch >>> 4) & 15];
                            bytes[off + 5] = (byte) DIGITS[ch & 15];
                            off += 6;
                        } else {
                            bytes[off++] = (byte) ch;
                        }
                        break;
                    default:
                        if (ch == quote) {
                            bytes[off] = (byte) '\\';
                            bytes[off + 1] = (byte) quote;
                            off += 2;
                        } else {
                            bytes[off++] = (byte) ch;
                        }
                        break;
                }
            } else if (escapeNoneAscii) {
                bytes[off] = '\\';
                bytes[off + 1] = 'u';
                bytes[off + 2] = (byte) DIGITS[(ch >>> 12) & 15];
                bytes[off + 3] = (byte) DIGITS[(ch >>> 8) & 15];
                bytes[off + 4] = (byte) DIGITS[(ch >>> 4) & 15];
                bytes[off + 5] = (byte) DIGITS[ch & 15];
                off += 6;
            } else if (ch >= '\uD800' && ch < ('\uDFFF' + 1)) { //  //Character.isSurrogate(c)
                final int uc;
                if (ch < '\uDBFF' + 1) { // Character.isHighSurrogate(c)
                    if (chars.length - i < 2) {
                        uc = -1;
                    } else {
                        char d = chars[i + 1];
                        // d >= '\uDC00' && d < ('\uDFFF' + 1)
                        if (d >= '\uDC00' && d < ('\uDFFF' + 1)) { // Character.isLowSurrogate(d)
                            uc = ((ch << 10) + d) + (0x010000 - ('\uD800' << 10) - '\uDC00'); // Character.toCodePoint(c, d)
                        } else {
//                            throw new JSONException("encodeUTF8 error", new MalformedInputException(1));
                            bytes[off++] = (byte) '?';
                            continue;
                        }
                    }
                } else {
                    //
                    // Character.isLowSurrogate(c)
                    bytes[off++] = (byte) '?';
                    continue;
//                        throw new JSONException("encodeUTF8 error", new MalformedInputException(1));
                }

                if (uc < 0) {
                    bytes[off++] = (byte) '?';
                } else {
                    bytes[off] = (byte) (0xf0 | ((uc >> 18)));
                    bytes[off + 1] = (byte) (0x80 | ((uc >> 12) & 0x3f));
                    bytes[off + 2] = (byte) (0x80 | ((uc >> 6) & 0x3f));
                    bytes[off + 3] = (byte) (0x80 | (uc & 0x3f));
                    off += 4;
                    i++; // 2 chars
                }
            } else if (ch > 0x07FF) {
                bytes[off] = (byte) (0xE0 | ((ch >> 12) & 0x0F));
                bytes[off + 1] = (byte) (0x80 | ((ch >> 6) & 0x3F));
                bytes[off + 2] = (byte) (0x80 | (ch & 0x3F));
                off += 3;
            } else {
                bytes[off] = (byte) (0xC0 | ((ch >> 6) & 0x1F));
                bytes[off + 1] = (byte) (0x80 | (ch & 0x3F));
                off += 2;
            }
        }
        this.off = off;
    }

    @Override
    public final void writeString(char[] chars, int offset, int len, boolean quoted) {
        boolean escapeNoneAscii = (context.features & EscapeNoneAscii.mask) != 0;

        // ensureCapacity
        int minCapacity = off
                + chars.length * 3 // utf8 3 bytes
                + 2;

        if (escapeNoneAscii) {
            minCapacity += len * 3;
        }

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        int off = this.off;
        if (quoted) {
            bytes[off++] = (byte) quote;
        }

        int end = offset + len;

        int i = offset;
        for (; i < end; i++) {
            char c0 = chars[i];
            if (c0 == quote
                    || c0 == '\\'
                    || c0 < ' '
                    || c0 > 0x007F) {
                break;
            }
            bytes[off++] = (byte) c0;
        }

        if (i == end) {
            if (quoted) {
                bytes[off++] = (byte) quote;
            }
            this.off = off;
            return;
        }

        for (; i < len; ++i) { // ascii none special fast write
            char ch = chars[i];
            if (ch <= 0x007F) {
                switch (ch) {
                    case '\\':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) '\\';
                        off += 2;
                        break;
                    case '\n':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'n';
                        off += 2;
                        break;
                    case '\r':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'r';
                        off += 2;
                        break;
                    case '\f':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'f';
                        off += 2;
                        break;
                    case '\b':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 'b';
                        off += 2;
                        break;
                    case '\t':
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) 't';
                        off += 2;
                        break;
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('0' + (int) ch);
                        off += 6;
                        break;
                    case 11:
                    case 14:
                    case 15:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '0';
                        bytes[off + 5] = (byte) ('a' + (ch - 10));
                        off += 6;
                        break;
                    case 16:
                    case 17:
                    case 18:
                    case 19:
                    case 20:
                    case 21:
                    case 22:
                    case 23:
                    case 24:
                    case 25:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('0' + (ch - 16));
                        off += 6;
                        break;
                    case 26:
                    case 27:
                    case 28:
                    case 29:
                    case 30:
                    case 31:
                        bytes[off] = '\\';
                        bytes[off + 1] = 'u';
                        bytes[off + 2] = '0';
                        bytes[off + 3] = '0';
                        bytes[off + 4] = '1';
                        bytes[off + 5] = (byte) ('a' + (ch - 26));
                        off += 6;
                        break;
                    default:
                        if (ch == quote) {
                            bytes[off] = (byte) '\\';
                            bytes[off + 1] = (byte) quote;
                            off += 2;
                        } else {
                            bytes[off++] = (byte) ch;
                        }
                        break;
                }
            } else if (escapeNoneAscii) {
                bytes[off] = '\\';
                bytes[off + 1] = 'u';
                bytes[off + 2] = (byte) DIGITS[(ch >>> 12) & 15];
                bytes[off + 3] = (byte) DIGITS[(ch >>> 8) & 15];
                bytes[off + 4] = (byte) DIGITS[(ch >>> 4) & 15];
                bytes[off + 5] = (byte) DIGITS[ch & 15];
                off += 6;
            } else if (ch >= '\uD800' && ch < ('\uDFFF' + 1)) { //  //Character.isSurrogate(c)
                final int uc;
                if (ch < '\uDBFF' + 1) { // Character.isHighSurrogate(c)
                    if (chars.length - i < 2) {
                        uc = -1;
                    } else {
                        char d = chars[i + 1];
                        // d >= '\uDC00' && d < ('\uDFFF' + 1)
                        if (d >= '\uDC00' && d < ('\uDFFF' + 1)) { // Character.isLowSurrogate(d)
                            uc = ((ch << 10) + d) + (0x010000 - ('\uD800' << 10) - '\uDC00'); // Character.toCodePoint(c, d)
                        } else {
//                            throw new JSONException("encodeUTF8 error", new MalformedInputException(1));
                            bytes[off++] = (byte) '?';
                            continue;
                        }
                    }
                } else {
                    //
                    // Character.isLowSurrogate(c)
                    bytes[off++] = (byte) '?';
                    continue;
//                        throw new JSONException("encodeUTF8 error", new MalformedInputException(1));
                }

                if (uc < 0) {
                    bytes[off++] = (byte) '?';
                } else {
                    bytes[off] = (byte) (0xf0 | ((uc >> 18)));
                    bytes[off + 1] = (byte) (0x80 | ((uc >> 12) & 0x3f));
                    bytes[off + 2] = (byte) (0x80 | ((uc >> 6) & 0x3f));
                    bytes[off + 3] = (byte) (0x80 | (uc & 0x3f));
                    off += 4;
                    i++; // 2 chars
                }
            } else if (ch > 0x07FF) {
                bytes[off] = (byte) (0xE0 | ((ch >> 12) & 0x0F));
                bytes[off + 1] = (byte) (0x80 | ((ch >> 6) & 0x3F));
                bytes[off + 2] = (byte) (0x80 | (ch & 0x3F));
                off += 3;
            } else {
                bytes[off] = (byte) (0xC0 | ((ch >> 6) & 0x1F));
                bytes[off + 1] = (byte) (0x80 | (ch & 0x3F));
                off += 2;
            }
        }

        if (quoted) {
            bytes[off++] = (byte) quote;
        }
        this.off = off;
    }

    public final void writeString(String[] strings) {
        if (strings == null) {
            writeArrayNull();
            return;
        }

        startArray();
        for (int i = 0; i < strings.length; i++) {
            if (i != 0) {
                writeComma();
            }

            String item = strings[i];
            if (item == null) {
                if (isEnabled(NullAsDefaultValue.mask | WriteNullStringAsEmpty.mask)) {
                    writeString("");
                } else {
                    writeNull();
                }
                continue;
            }
            writeString(item);
        }
        endArray();
    }

    @Override
    public final void writeChar(char ch) {
        int off = this.off;
        byte[] bytes = this.bytes;
        if (off + 8 > bytes.length) {
            bytes = grow(off + 8);
        }
        bytes[off++] = (byte) quote;
        if (ch <= 0x007F) {
            switch (ch) {
                case '\\':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) '\\';
                    off += 2;
                    break;
                case '\n':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'n';
                    off += 2;
                    break;
                case '\r':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'r';
                    off += 2;
                    break;
                case '\f':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'f';
                    off += 2;
                    break;
                case '\b':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 'b';
                    off += 2;
                    break;
                case '\t':
                    bytes[off] = (byte) '\\';
                    bytes[off + 1] = (byte) 't';
                    off += 2;
                    break;
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '0';
                    bytes[off + 5] = (byte) ('0' + (int) ch);
                    off += 6;
                    break;
                case 11:
                case 14:
                case 15:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '0';
                    bytes[off + 5] = (byte) ('a' + (ch - 10));
                    off += 6;
                    break;
                case 16:
                case 17:
                case 18:
                case 19:
                case 20:
                case 21:
                case 22:
                case 23:
                case 24:
                case 25:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '1';
                    bytes[off + 5] = (byte) ('0' + (ch - 16));
                    off += 6;
                    break;
                case 26:
                case 27:
                case 28:
                case 29:
                case 30:
                case 31:
                    bytes[off] = '\\';
                    bytes[off + 1] = 'u';
                    bytes[off + 2] = '0';
                    bytes[off + 3] = '0';
                    bytes[off + 4] = '1';
                    bytes[off + 5] = (byte) ('a' + (ch - 26));
                    off += 6;
                    break;
                default:
                    if (ch == quote) {
                        bytes[off] = (byte) '\\';
                        bytes[off + 1] = (byte) quote;
                        off += 2;
                    } else {
                        bytes[off++] = (byte) ch;
                    }
                    break;
            }
        } else if (ch >= '\uD800' && ch < ('\uDFFF' + 1)) { //  //Character.isSurrogate(c)
            throw new JSONException("illegal char " + ch);
        } else if (ch > 0x07FF) {
            bytes[off] = (byte) (0xE0 | ((ch >> 12) & 0x0F));
            bytes[off + 1] = (byte) (0x80 | ((ch >> 6) & 0x3F));
            bytes[off + 2] = (byte) (0x80 | (ch & 0x3F));
            off += 3;
        } else {
            bytes[off] = (byte) (0xC0 | ((ch >> 6) & 0x1F));
            bytes[off + 1] = (byte) (0x80 | (ch & 0x3F));
            off += 2;
        }

        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    /**
     * Return a big-endian packed integer for the 4 ASCII bytes for an input unsigned 2-byte integer.
     * {@code b0} is the most significant byte and {@code b1} is the least significant byte.
     * The integer is passed byte-wise to allow reordering of execution.
     */
    static int packDigits(int b0, int b1) {
        int v = HEX256[b0 & 0xff] | (HEX256[b1 & 0xff] << 16);
        return BIG_ENDIAN ? Integer.reverseBytes(v) : v;
    }

    /**
     * Return a big-endian packed long for the 8 ASCII bytes for an input unsigned 4-byte integer.
     * {@code b0} is the most significant byte and {@code b3} is the least significant byte.
     * The integer is passed byte-wise to allow reordering of execution.
     */
    static long packDigits(int b0, int b1, int b2, int b3) {
        short[] digits = HEX256;
        long v = (digits[b0 & 0xff]
                | (((long) digits[b1 & 0xff]) << 16)
                | (((long) digits[b2 & 0xff]) << 32))
                | (((long) digits[b3 & 0xff]) << 48);
        return BIG_ENDIAN ? Long.reverseBytes(v) : v;
    }

    @Override
    public final void writeUUID(UUID value) {
        if (value == null) {
            writeNull();
            return;
        }

        long msb = value.getMostSignificantBits();
        long lsb = value.getLeastSignificantBits();

        int off = this.off;
        int minCapacity = off + 38;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = '"';

        final long base = ARRAY_BYTE_BASE_OFFSET + off;

        UNSAFE.putLong(
                bytes,
                base + 1,
                packDigits((int) (msb >> 56), (int) (msb >> 48), (int) (msb >> 40), (int) (msb >> 32))
        );
        bytes[off + 9] = '-';
        UNSAFE.putLong(
                bytes,
                base + 10,
                packDigits(((int) msb) >> 24, ((int) msb) >> 16));
        bytes[off + 14] = '-';
        UNSAFE.putLong(
                bytes,
                base + 15,
                packDigits(((int) msb) >> 8, (int) msb));
        bytes[off + 19] = '-';
        UNSAFE.putLong(
                bytes,
                base + 20,
                packDigits((int) (lsb >> 56), (int) (lsb >> 48)));
        bytes[off + 24] = '-';
        UNSAFE.putLong(
                bytes,
                base + 25,
                packDigits(((int) (lsb >> 40)), (int) (lsb >> 32), ((int) lsb) >> 24, ((int) lsb) >> 16));
        UNSAFE.putLong(
                bytes,
                base + 33,
                packDigits(((int) lsb) >> 8, (int) lsb));
        bytes[off + 37] = '"';
        this.off += 38;
    }

    @Override
    public final void writeRaw(String str) {
        char[] chars = getCharArray(str);
        int off = this.off;
        int minCapacity = off
                + chars.length * 3; // utf8 3 bytes

        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if ((c >= 0x0001) && (c <= 0x007F)) {
                bytes[off++] = (byte) c;
            } else if (c > 0x07FF) {
                bytes[off] = (byte) (0xE0 | ((c >> 12) & 0x0F));
                bytes[off + 1] = (byte) (0x80 | ((c >> 6) & 0x3F));
                bytes[off + 2] = (byte) (0x80 | (c & 0x3F));
                off += 3;
            } else {
                bytes[off] = (byte) (0xC0 | ((c >> 6) & 0x1F));
                bytes[off + 1] = (byte) (0x80 | (c & 0x3F));
                off += 2;
            }
        }
        this.off = off;
    }

    @Override
    public final void writeRaw(byte[] raw) {
        int off = this.off;
        int minCapacity = off + raw.length;
        if (minCapacity > bytes.length) {
            grow(minCapacity);
        }
        System.arraycopy(raw, 0, bytes, off, raw.length);
        this.off = off + raw.length;
    }

    @Override
    public final void writeNameRaw(byte[] name) {
        int off = this.off;
        int minCapacity = off + name.length + 2 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }
        System.arraycopy(name, 0, bytes, off, name.length);
        this.off = off + name.length;
    }

    @Override
    public final void writeName2Raw(long name) {
        int off = this.off;
        int minCapacity = off + 10 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        this.off = off + 5;
    }

    private int indent(byte[] bytes, int off) {
        bytes[off] = '\n';
        int toIndex = off + 1 + pretty * level;
        Arrays.fill(bytes, off + 1, toIndex, pretty == PRETTY_TAB ? (byte) '\t' : (byte) ' ');
        return toIndex;
    }

    @Override
    public final void writeName3Raw(long name) {
        int off = this.off;
        int minCapacity = off + 10 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        this.off = off + 6;
    }

    @Override
    public final void writeName4Raw(long name) {
        int off = this.off;
        int minCapacity = off + 10 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        this.off = off + 7;
    }

    @Override
    public final void writeName5Raw(long name) {
        int off = this.off;
        int minCapacity = off + 10 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        this.off = off + 8;
    }

    @Override
    public final void writeName6Raw(long name) {
        int off = this.off;
        int minCapacity = off + 11 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        bytes[off + 8] = ':';
        this.off = off + 9;
    }

    @Override
    public final void writeName7Raw(long name) {
        int off = this.off;
        int minCapacity = off + 12 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name);
        bytes[off + 8] = (byte) quote;
        bytes[off + 9] = ':';
        this.off = off + 10;
    }

    @Override
    public final void writeName8Raw(long name) {
        int off = this.off;
        int minCapacity = off + 13 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        bytes[off] = (byte) quote;
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 1, name);
        UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 9, useSingleQuote ? QUOTE_COLON : QUOTE2_COLON);
        this.off = off + 11;
    }

    @Override
    public final void writeName9Raw(long name0, int name1) {
        int off = this.off;
        int minCapacity = off + 14 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        this.off = off + 12;
    }

    @Override
    public final void writeName10Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 18 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        this.off = off + 13;
    }

    @Override
    public final void writeName11Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 18 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        this.off = off + 14;
    }

    @Override
    public final void writeName12Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 18 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        this.off = off + 15;
    }

    @Override
    public final void writeName13Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 18 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        this.off = off + 16;
    }

    @Override
    public final void writeName14Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 19 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        bytes[off + 16] = ':';
        this.off = off + 17;
    }

    @Override
    public final void writeName15Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 20 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 16, useSingleQuote ? QUOTE_COLON : QUOTE2_COLON);
        this.off = off + 18;
    }

    @Override
    public final void writeName16Raw(long name0, long name1) {
        int off = this.off;
        int minCapacity = off + 21 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (startObject) {
            startObject = false;
        } else {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }

        bytes[off++] = (byte) quote;
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off, name0);
        UNSAFE.putLong(bytes, ARRAY_BYTE_BASE_OFFSET + off + 8, name1);
        UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 16, useSingleQuote ? QUOTE_COLON : QUOTE2_COLON);
        this.off = off + 18;
    }

    @Override
    public final void writeRaw(char ch) {
        if (ch > 128) {
            throw new JSONException("not support " + ch);
        }

        if (off == bytes.length) {
            ensureCapacity(off + 1);
        }
        bytes[off++] = (byte) ch;
    }

    @Override
    public final void writeRaw(char c0, char c1) {
        if (c0 > 128 || c1 > 128) {
            throw new JSONException("not support " + c0 + ", " + c1);
        }

        int off = this.off;
        byte[] bytes = this.bytes;
        if (off + 2 > bytes.length) {
            bytes = grow(off + 2);
        }
        bytes[off] = (byte) c0;
        bytes[off + 1] = (byte) c1;
        this.off = off + 2;
    }

    @Override
    public final void writeNameRaw(byte[] name, int coff, int len) {
        int off = this.off;
        int minCapacity = off + len + 2 + pretty * level;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }

        if (!startObject) {
            bytes[off++] = ',';
            if (pretty != PRETTY_NON) {
                off = indent(bytes, off);
            }
        }
        startObject = false;
        System.arraycopy(name, coff, bytes, off, len);
        this.off = off + len;
    }

    final void ensureCapacity(int minCapacity) {
        if (minCapacity > bytes.length) {
            grow0(minCapacity);
        }
    }

    private byte[] grow(int minCapacity) {
        grow0(minCapacity);
        return bytes;
    }

    private void grow0(int minCapacity) {
        // minCapacity is usually close to size, so this is a win:
        bytes = Arrays.copyOf(bytes, newCapacity(minCapacity, bytes.length));
    }

    public final void writeInt32(int[] values) {
        if (values == null) {
            writeNull();
            return;
        }

        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + values.length * 13 + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = '[';

        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                bytes[off++] = ',';
            }
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
            off = IOUtils.writeInt32(bytes, off, values[i]);
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
        }

        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeInt8(byte i) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + 5;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        off = IOUtils.writeInt8(bytes, off, i);
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        this.off = off;
    }

    @Override
    public final void writeInt8(byte[] values) {
        if (values == null) {
            writeNull();
            return;
        }

        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + values.length * 5 + 2;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = '[';

        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                bytes[off++] = ',';
            }
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
            off = IOUtils.writeInt8(bytes, off, values[i]);
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
        }

        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeInt16(short i) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + 7;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        off = IOUtils.writeInt16(bytes, off, i);
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        this.off = off;
    }

    @Override
    public final void writeInt32(Integer i) {
        if (i == null) {
            writeNumberNull();
        } else {
            writeInt32(i.intValue());
        }
    }

    @Override
    public final void writeInt32(int i) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + 13;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        off = IOUtils.writeInt32(bytes, off, i);
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        this.off = off;
    }

    @Override
    public final void writeListInt32(List<Integer> values) {
        if (values == null) {
            writeNull();
            return;
        }

        int size = values.size();
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;
        int off = this.off;
        int minCapacity = off + 2 + size * 23;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) '[';

        for (int i = 0; i < size; i++) {
            if (i != 0) {
                bytes[off++] = (byte) ',';
            }
            Number item = values.get(i);
            if (item == null) {
                UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off, NULL_32);
                off += 4;
                continue;
            }

            int v = item.intValue();
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
            off = IOUtils.writeInt32(bytes, off, v);
            if (writeAsString) {
                bytes[off++] = (byte) quote;
            }
        }

        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeInt64(long[] values) {
        if (values == null) {
            writeNull();
            return;
        }

        int off = this.off;
        int minCapacity = off + 2 + values.length * 23;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) '[';

        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                bytes[off++] = (byte) ',';
            }
            long v = values[i];
            boolean writeAsString = isWriteAsString(v, context.features);
            if (writeAsString) {
                bytes[off++] = (byte) this.quote;
            }
            off = IOUtils.writeInt64(bytes, off, v);
            if (writeAsString) {
                bytes[off++] = (byte) this.quote;
            }
        }

        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeListInt64(List<Long> values) {
        if (values == null) {
            writeNull();
            return;
        }

        int size = values.size();
        int off = this.off;
        int minCapacity = off + 2 + size * 23;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) '[';

        for (int i = 0; i < size; i++) {
            if (i != 0) {
                bytes[off++] = (byte) ',';
            }
            Long item = values.get(i);
            if (item == null) {
                UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off, NULL_32);
                off += 4;
                continue;
            }

            long v = item;
            boolean writeAsString = isWriteAsString(v, context.features);
            if (writeAsString) {
                bytes[off++] = (byte) this.quote;
            }
            off = IOUtils.writeInt64(bytes, off, v);
            if (writeAsString) {
                bytes[off++] = (byte) this.quote;
            }
        }

        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeInt64(long i) {
        final long features = context.features;
        boolean writeAsString = isWriteAsString(i, features);
        int off = this.off;
        int minCapacity = off + 23;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        }
        off = IOUtils.writeInt64(bytes, off, i);
        if (writeAsString) {
            bytes[off++] = (byte) quote;
        } else if ((features & WriteClassName.mask) != 0
                && (features & NotWriteNumberClassName.mask) == 0
                && i >= Integer.MIN_VALUE && i <= Integer.MAX_VALUE
        ) {
            bytes[off++] = 'L';
        }
        this.off = off;
    }

    @Override
    public final void writeInt64(Long i) {
        if (i == null) {
            writeNumberNull();
        } else {
            writeInt64(i.longValue());
        }
    }

    @Override
    public final void writeFloat(float value) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + 17;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }

        if (writeAsString) {
            bytes[off++] = '"';
        }

        int len = DoubleToDecimal.toString(value, bytes, off, true);
        off += len;

        if (writeAsString) {
            bytes[off++] = '"';
        }
        this.off = off;
    }

    @Override
    public final void writeDouble(double value) {
        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + 26;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = '"';
        }

        off += DoubleToDecimal.toString(value, bytes, off, true);

        if (writeAsString) {
            bytes[off++] = '"';
        }
        this.off = off;
    }

    @Override
    public final void writeFloat(float[] values) {
        if (values == null) {
            writeArrayNull();
            return;
        }

        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + values.length * (writeAsString ? 16 : 18) + 1;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = '[';
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                bytes[off++] = ',';
            }

            if (writeAsString) {
                bytes[off++] = '"';
            }

            float value = values[i];
            int len = DoubleToDecimal.toString(value, bytes, off, true);
            off += len;

            if (writeAsString) {
                bytes[off++] = '"';
            }
        }
        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeDouble(double[] values) {
        if (values == null) {
            writeNull();
            return;
        }

        boolean writeAsString = (context.features & WriteNonStringValueAsString.mask) != 0;

        int off = this.off;
        int minCapacity = off + values.length * 27 + 1;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = '[';
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                bytes[off++] = ',';
            }

            if (writeAsString) {
                bytes[off++] = '"';
            }

            double value = values[i];
            int len = DoubleToDecimal.toString(value, bytes, off, true);
            off += len;

            if (writeAsString) {
                bytes[off++] = '"';
            }
        }
        bytes[off] = ']';
        this.off = off + 1;
    }

    @Override
    public final void writeDateTime14(
            int year,
            int month,
            int dayOfMonth,
            int hour,
            int minute,
            int second
    ) {
        int off = this.off;
        int minCapacity = off + 16;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        if (year < 0 || year > 9999) {
            throw illegalYear(year);
        }
        int y01 = year / 100;
        int y23 = year - y01 * 100;
        final long base = ARRAY_BYTE_BASE_OFFSET + off;
        UNSAFE.putShort(bytes, base + 1, PACKED_DIGITS[y01]);
        UNSAFE.putShort(bytes, base + 3, PACKED_DIGITS[y23]);
        UNSAFE.putShort(bytes, base + 5, PACKED_DIGITS[month]);
        UNSAFE.putShort(bytes, base + 7, PACKED_DIGITS[dayOfMonth]);
        UNSAFE.putShort(bytes, base + 9, PACKED_DIGITS[hour]);
        UNSAFE.putShort(bytes, base + 11, PACKED_DIGITS[minute]);
        UNSAFE.putShort(bytes, base + 13, PACKED_DIGITS[second]);
        bytes[off + 15] = (byte) quote;
        this.off = off + 16;
    }

    @Override
    public final void writeDateTime19(
            int year,
            int month,
            int dayOfMonth,
            int hour,
            int minute,
            int second) {
        int off = this.off;
        int minCapacity = off + 21;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        off = IOUtils.writeLocalDate(bytes, off + 1, year, month, dayOfMonth);
        bytes[off] = ' ';
        IOUtils.writeLocalTime(bytes, off + 1, hour, minute, second);
        bytes[off + 9] = (byte) quote;
        this.off = off + 10;
    }

    @Override
    public final void writeLocalDate(LocalDate date) {
        if (date == null) {
            writeNull();
            return;
        }

        if (context.dateFormat != null
                && writeLocalDateWithFormat(date)) {
            return;
        }

        int off = this.off;
        int minCapacity = off + 18;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;
        off = IOUtils.writeLocalDate(bytes, off, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeLocalDateTime(LocalDateTime dateTime) {
        int off = this.off;
        int minCapacity = off + 38;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;
        LocalDate localDate = dateTime.toLocalDate();
        off = IOUtils.writeLocalDate(bytes, off, localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
        bytes[off++] = ' ';
        off = IOUtils.writeLocalTime(bytes, off, dateTime.toLocalTime());
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeDateYYYMMDD8(int year, int month, int dayOfMonth) {
        int off = this.off;
        int minCapacity = off + 10;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        if (year < 0 || year > 9999) {
            throw illegalYear(year);
        }
        int y01 = year / 100;
        int y23 = year - y01 * 100;
        UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off + 1, PACKED_DIGITS[y01]);
        UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off + 3, PACKED_DIGITS[y23]);
        UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 5, PACKED_DIGITS[month]);
        UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 7, PACKED_DIGITS[dayOfMonth]);
        bytes[off + 9] = (byte) quote;
        this.off = off + 10;
    }

    @Override
    public final void writeDateYYYMMDD10(int year, int month, int dayOfMonth) {
        int off = this.off;
        int minCapacity = off + 13;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;
        off = IOUtils.writeLocalDate(bytes, off, year, month, dayOfMonth);
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeTimeHHMMSS8(int hour, int minute, int second) {
        int off = this.off;
        int minCapacity = off + 10;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        IOUtils.writeLocalTime(bytes, off + 1, hour, minute, second);
        bytes[off + 9] = (byte) quote;
        this.off = off + 10;
    }

    @Override
    public final void writeLocalTime(LocalTime time) {
        int off = this.off;
        int minCapacity = off + 20;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off++] = (byte) quote;
        off = IOUtils.writeLocalTime(bytes, off, time);
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeZonedDateTime(ZonedDateTime dateTime) {
        if (dateTime == null) {
            writeNull();
            return;
        }

        ZoneId zone = dateTime.getZone();
        String zoneId = zone.getId();
        int zoneIdLength = zoneId.length();
        char firstZoneChar = '\0';
        int zoneSize;
        if (ZoneOffset.UTC == zone || (zoneIdLength <= 3 && ("UTC".equals(zoneId) || "Z".equals(zoneId)))) {
            zoneId = "Z";
            zoneSize = 1;
        } else if (zoneIdLength != 0 && ((firstZoneChar = zoneId.charAt(0)) == '+' || firstZoneChar == '-')) {
            zoneSize = zoneIdLength;
        } else {
            zoneSize = 2 + zoneIdLength;
        }

        int off = this.off;
        int minCapacity = off + zoneSize + 38;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        LocalDate localDate = dateTime.toLocalDate();
        off = IOUtils.writeLocalDate(bytes, off + 1, localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
        bytes[off] = 'T';
        off = IOUtils.writeLocalTime(bytes, off + 1, dateTime.toLocalTime());
        if (zoneSize == 1) {
            bytes[off++] = 'Z';
        } else if (firstZoneChar == '+' || firstZoneChar == '-') {
            zoneId.getBytes(0, zoneIdLength, bytes, off);
            off += zoneIdLength;
        } else {
            bytes[off++] = '[';
            zoneId.getBytes(0, zoneIdLength, bytes, off);
            off += zoneIdLength;
            bytes[off++] = ']';
        }
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeOffsetDateTime(OffsetDateTime dateTime) {
        if (dateTime == null) {
            writeNull();
            return;
        }

        int off = this.off;
        int minCapacity = off + 45;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        LocalDateTime ldt = dateTime.toLocalDateTime();
        LocalDate date = ldt.toLocalDate();
        off = IOUtils.writeLocalDate(bytes, off + 1, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        bytes[off] = 'T';
        off = IOUtils.writeLocalTime(bytes, off + 1, ldt.toLocalTime());

        ZoneOffset offset = dateTime.getOffset();
        if (offset.getTotalSeconds() == 0) {
            bytes[off++] = 'Z';
        } else {
            String zoneId = offset.getId();
            zoneId.getBytes(0, zoneId.length(), bytes, off);
            off += zoneId.length();
        }
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    public final void writeOffsetTime(OffsetTime time) {
        if (time == null) {
            writeNull();
            return;
        }

        int off = this.off;
        int minCapacity = off + 28;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        off = IOUtils.writeLocalTime(bytes, off + 1, time.toLocalTime());

        ZoneOffset offset = time.getOffset();
        if (offset.getTotalSeconds() == 0) {
            bytes[off++] = 'Z';
        } else {
            String zoneId = offset.getId();
            zoneId.getBytes(0, zoneId.length(), bytes, off);
            off += zoneId.length();
        }
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeBigInt(BigInteger value, long features) {
        if (value == null) {
            writeNumberNull();
            return;
        }

        if (isInt64(value) && features == 0) {
            writeInt64(value.longValue());
            return;
        }

        String str = value.toString(10);

        boolean writeAsString = isWriteAsString(value, context.features | features);

        int off = this.off;
        int strlen = str.length();

        int minCapacity = off + strlen + (writeAsString ? 2 : 0);
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = '"';
        }
        str.getBytes(0, strlen, bytes, off);
        off += strlen;
        if (writeAsString) {
            bytes[off++] = '"';
        }
        this.off = off;
    }

    @Override
    public final void writeDateTimeISO8601(
            int year,
            int month,
            int dayOfMonth,
            int hour,
            int minute,
            int second,
            int millis,
            int offsetSeconds,
            boolean timeZone
    ) {
        int zonelen;
        if (timeZone) {
            zonelen = offsetSeconds == 0 ? 1 : 6;
        } else {
            zonelen = 0;
        }

        int off = this.off;
        int minCapacity = off + 25 + zonelen;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        bytes[off] = (byte) quote;
        off = IOUtils.writeLocalDate(bytes, off + 1, year, month, dayOfMonth);
        bytes[off] = (byte) (timeZone ? 'T' : ' ');
        IOUtils.writeLocalTime(bytes, off + 1, hour, minute, second);
        off += 9;

        if (millis > 0) {
            int div = millis / 10;
            int div2 = div / 10;
            final int rem1 = millis - div * 10;

            if (rem1 != 0) {
                putInt(bytes, off, DIGITS_K_32[millis] & 0xffffff00 | '.');
                off += 4;
            } else {
                bytes[off++] = '.';
                final int rem2 = div - div2 * 10;
                if (rem2 != 0) {
                    UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off, PACKED_DIGITS[div]);
                    off += 2;
                } else {
                    bytes[off++] = (byte) (div2 + '0');
                }
            }
        }

        if (timeZone) {
            int offset = offsetSeconds / 3600;
            if (offsetSeconds == 0) {
                bytes[off++] = 'Z';
            } else {
                int offsetAbs = Math.abs(offset);
                bytes[off] = offset >= 0 ? (byte) '+' : (byte) '-';
                UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 1, PACKED_DIGITS[offsetAbs]);
                bytes[off + 3] = ':';
                int offsetMinutes = (offsetSeconds - offset * 3600) / 60;
                if (offsetMinutes < 0) {
                    offsetMinutes = -offsetMinutes;
                }
                UNSAFE.putShort(bytes, ARRAY_BYTE_BASE_OFFSET + off + 4, PACKED_DIGITS[offsetMinutes]);
                off += 6;
            }
        }
        bytes[off] = (byte) quote;
        this.off = off + 1;
    }

    @Override
    public final void writeDecimal(BigDecimal value, long features, DecimalFormat format) {
        if (value == null) {
            writeNumberNull();
            return;
        }

        if (format != null) {
            String str = format.format(value);
            writeRaw(str);
            return;
        }

        features |= context.features;

        int precision = value.precision();
        boolean writeAsString = isWriteAsString(value, features);

        int off = this.off;
        int minCapacity = off + precision + value.scale() + 7;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        if (writeAsString) {
            bytes[off++] = '"';
        }

        boolean asPlain = (features & WriteBigDecimalAsPlain.mask) != 0;
        long unscaleValue;
        int scale;
        if (precision < 19
                && (scale = value.scale()) >= 0
                && FIELD_DECIMAL_INT_COMPACT_OFFSET != -1
                && (unscaleValue = UNSAFE.getLong(value, FIELD_DECIMAL_INT_COMPACT_OFFSET)) != Long.MIN_VALUE
                && !asPlain
        ) {
            off = IOUtils.writeDecimal(bytes, off, unscaleValue, scale);
        } else {
            String str = asPlain ? value.toPlainString() : value.toString();
            str.getBytes(0, str.length(), bytes, off);
            off += str.length();
        }

        if (writeAsString) {
            bytes[off++] = '"';
        }
        this.off = off;
    }

    @Override
    public final void writeNameRaw(char[] chars) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public final void writeNameRaw(char[] bytes, int offset, int len) {
        throw new JSONException("UnsupportedOperation");
    }

    @Override
    public final void write(Map<?, ?> map) {
        if (pretty != PRETTY_NON) {
            super.write(map);
            return;
        }

        if (map == null) {
            this.writeNull();
            return;
        }

        if ((context.features & NONE_DIRECT_FEATURES) != 0) {
            ObjectWriter objectWriter = context.getObjectWriter(map.getClass());
            objectWriter.write(this, map, null, null, 0);
            return;
        }

        if (off == bytes.length) {
            grow(off + 1);
        }
        bytes[off++] = '{';

        boolean first = true;
        for (Map.Entry entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null && (context.features & WriteMapNullValue.mask) == 0) {
                continue;
            }

            if (!first) {
                if (off == bytes.length) {
                    ensureCapacity(off + 1);
                }
                bytes[off++] = ',';
            }

            first = false;
            Object key = entry.getKey();
            if (key instanceof String) {
                writeString((String) key);
            } else {
                writeAny(key);
            }

            if (off == bytes.length) {
                ensureCapacity(off + 1);
            }
            bytes[off++] = ':';

            if (value == null) {
                writeNull();
                continue;
            }

            Class<?> valueClass = value.getClass();
            if (valueClass == String.class) {
                writeString((String) value);
                continue;
            }

            if (valueClass == Integer.class) {
                writeInt32((Integer) value);
                continue;
            }

            if (valueClass == Long.class) {
                writeInt64((Long) value);
                continue;
            }

            if (valueClass == Boolean.class) {
                writeBool((Boolean) value);
                continue;
            }

            if (valueClass == BigDecimal.class) {
                writeDecimal((BigDecimal) value, 0, null);
                continue;
            }

            if (valueClass == JSONArray.class) {
                write((JSONArray) value);
                continue;
            }

            if (valueClass == JSONObject.class) {
                write((JSONObject) value);
                continue;
            }

            ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
            objectWriter.write(this, value, null, null, 0);
        }

        if (off == bytes.length) {
            grow(off + 1);
        }
        bytes[off++] = '}';
    }

    @Override
    public final void write(List array) {
        if (array == null) {
            this.writeArrayNull();
            return;
        }

        final long NONE_DIRECT_FEATURES = ReferenceDetection.mask
                | PrettyFormat.mask
                | NotWriteEmptyArray.mask
                | NotWriteDefaultValue.mask;

        if ((context.features & NONE_DIRECT_FEATURES) != 0) {
            ObjectWriter objectWriter = context.getObjectWriter(array.getClass());
            objectWriter.write(this, array, null, null, 0);
            return;
        }

        if (off == bytes.length) {
            grow(off + 1);
        }
        bytes[off++] = '[';

        boolean first = true;
        for (int i = 0; i < array.size(); i++) {
            Object o = array.get(i);
            if (!first) {
                if (off == bytes.length) {
                    grow(off + 1);
                }
                bytes[off++] = ',';
            }
            first = false;

            if (o == null) {
                writeNull();
                continue;
            }

            Class<?> valueClass = o.getClass();
            if (valueClass == String.class) {
                writeString((String) o);
                continue;
            }

            if (valueClass == Integer.class) {
                writeInt32((Integer) o);
                continue;
            }

            if (valueClass == Long.class) {
                writeInt64((Long) o);
                continue;
            }

            if (valueClass == Boolean.class) {
                writeBool((Boolean) o);
                continue;
            }

            if (valueClass == BigDecimal.class) {
                writeDecimal((BigDecimal) o, 0, null);
                continue;
            }

            if (valueClass == JSONArray.class) {
                write((JSONArray) o);
                continue;
            }

            if (valueClass == JSONObject.class) {
                write((JSONObject) o);
                continue;
            }

            ObjectWriter objectWriter = context.getObjectWriter(valueClass, valueClass);
            objectWriter.write(this, o, null, null, 0);
        }
        if (off == bytes.length) {
            grow(off + 1);
        }
        bytes[off++] = ']';
    }

    public void writeBool(boolean value) {
        int minCapacity = off + 5;
        byte[] bytes = this.bytes;
        if (minCapacity > bytes.length) {
            bytes = grow(minCapacity);
        }
        int off = this.off;
        if ((context.features & WriteBooleanAsNumber.mask) != 0) {
            bytes[off++] = (byte) (value ? '1' : '0');
        } else {
            if (!value) {
                bytes[off++] = 'f';
            }
            UNSAFE.putInt(bytes, ARRAY_BYTE_BASE_OFFSET + off, value ? TRUE : ALSE);
            off += 4;
        }
        this.off = off;
    }

    @Override
    public final String toString() {
        return new String(bytes, 0, off, StandardCharsets.UTF_8);
    }

    @Override
    public final int flushTo(OutputStream out, Charset charset) throws IOException {
        if (off == 0) {
            return 0;
        }

        if (charset == null || charset == StandardCharsets.UTF_8 || charset == StandardCharsets.US_ASCII) {
            int len = off;
            out.write(bytes, 0, off);
            off = 0;
            return len;
        }

        if (charset == StandardCharsets.ISO_8859_1) {
            boolean hasNegative = false;
            if (METHOD_HANDLE_HAS_NEGATIVE != null) {
                try {
                    hasNegative = (Boolean) METHOD_HANDLE_HAS_NEGATIVE.invoke(bytes, 0, bytes.length);
                } catch (Throwable ignored) {
                    // ignored
                }
            }
            if (!hasNegative) {
                int len = off;
                out.write(bytes, 0, off);
                off = 0;
                return len;
            }
        }

        String str = new String(bytes, 0, off);
        byte[] encodedBytes = str.getBytes(charset);
        out.write(encodedBytes);
        return encodedBytes.length;
    }
}
