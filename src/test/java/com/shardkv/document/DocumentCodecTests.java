package com.shardkv.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentCodecTests {

    private final DocumentCodec codec = new DocumentCodec(new ObjectMapper());

    @Test
    void explicitEnvelopeRoundTripsWithoutGuessingOrdinaryJson() {
        Document document = new Document(Map.of(
                "name", "Zoë",
                "experience", 7,
                "score", 9.5,
                "active", true));

        String encoded = codec.encode(document);

        assertThat(codec.decode(encoded)).contains(new Document(Map.of(
                "name", "Zoë",
                "experience", 7L,
                "score", 9.5d,
                "active", true)));
        assertThat(codec.decode("{\"fields\":{\"name\":\"ordinary kv text\"}}"))
                .isEmpty();
    }

    @Test
    void nestedObjectsArraysNullsAndNonFiniteNumbersAreRejected() {
        assertThatThrownBy(() -> codec.normalize(new Document(Map.of("nested", Map.of("x", 1)))))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> codec.normalize(new Document(Map.of("array", List.of(1, 2)))))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> codec.normalizeScalar(null))
                .isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> codec.normalizeScalar(Double.NaN))
                .isInstanceOf(InvalidDocumentException.class);
    }
}
