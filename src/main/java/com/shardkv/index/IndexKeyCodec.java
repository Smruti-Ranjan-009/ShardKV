package com.shardkv.index;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public class IndexKeyCodec {

    private static final byte[] NAMESPACE = {(byte) 0xff, 'I', 'D', 'X', 0};

    public byte[] prefix(String field, Object value) {
        byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
        EncodedValue encodedValue = encodeValue(value);
        ByteBuffer buffer = ByteBuffer.allocate(
                NAMESPACE.length + Integer.BYTES + fieldBytes.length + 1
                        + Integer.BYTES + encodedValue.bytes().length);
        buffer.put(NAMESPACE).putInt(fieldBytes.length).put(fieldBytes);
        buffer.put(encodedValue.type()).putInt(encodedValue.bytes().length).put(encodedValue.bytes());
        return buffer.array();
    }

    public byte[] entry(String field, Object value, String key) {
        byte[] prefix = prefix(field, value);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(prefix.length + Integer.BYTES + keyBytes.length)
                .put(prefix).putInt(keyBytes.length).put(keyBytes).array();
    }

    public String keyFromEntry(byte[] entry, int prefixLength) {
        if (entry.length < prefixLength + Integer.BYTES) {
            throw new IllegalArgumentException("Malformed index entry");
        }
        ByteBuffer buffer = ByteBuffer.wrap(entry, prefixLength, entry.length - prefixLength);
        int length = buffer.getInt();
        if (length < 0 || length != buffer.remaining()) {
            throw new IllegalArgumentException("Malformed index entry key length");
        }
        byte[] key = new byte[length];
        buffer.get(key);
        return new String(key, StandardCharsets.UTF_8);
    }

    public boolean hasPrefix(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    private EncodedValue encodeValue(Object value) {
        if (value instanceof String text) {
            return new EncodedValue((byte) 'S', text.getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Long number) {
            return new EncodedValue((byte) 'L', ByteBuffer.allocate(Long.BYTES).putLong(number).array());
        }
        if (value instanceof Double number) {
            return new EncodedValue((byte) 'D', ByteBuffer.allocate(Long.BYTES)
                    .putLong(Double.doubleToLongBits(number)).array());
        }
        if (value instanceof Boolean flag) {
            return new EncodedValue((byte) 'B', new byte[] {(byte) (flag ? 1 : 0)});
        }
        throw new IllegalArgumentException("Unsupported indexed value type");
    }

    private record EncodedValue(byte type, byte[] bytes) {
    }
}
