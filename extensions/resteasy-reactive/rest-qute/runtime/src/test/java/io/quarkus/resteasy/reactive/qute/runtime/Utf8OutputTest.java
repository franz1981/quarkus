package io.quarkus.resteasy.reactive.qute.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

public class Utf8OutputTest {

    static final String CYR = "\u0422\u043E\u0432\u0430\u0440";
    static final String EMOJI = "\uD83D\uDE00";
    static final String HIGH = "\uD83D";
    static final String LOW = "\uDE00";

    private final Engine engine = Engine.builder().addDefaults().build();

    private Utf8Output assertSameBytes(Template template, Map<String, Object> data) {
        byte[] expected = template.data(data).render().getBytes(StandardCharsets.UTF_8);
        Utf8Output output = Utf8Output.render(template.data(data)).toCompletableFuture().join();
        assertThat(output.toByteArray()).isEqualTo(expected);
        assertThat(output.byteLength()).isEqualTo(expected.length);
        int sum = 0;
        for (int i = 0; i < output.chunkCount(); i++) {
            sum += output.chunkLength(i);
        }
        assertThat(sum).isEqualTo(expected.length);
        assertThat(output.toString()).isEqualTo(new String(expected, StandardCharsets.UTF_8));
        return output;
    }

    @Test
    public void testSimple() {
        assertSameBytes(engine.parse("Hello {name}!"), Map.of("name", "world"));
        assertSameBytes(engine.parse("Hello {name}!"), Map.of("name", CYR + " " + EMOJI));
        assertThat(assertSameBytes(engine.parse(""), Map.of()).chunkCount()).isZero();
    }

    @Test
    public void testSurrogates() {
        // a pair split between two pieces
        assertSameBytes(engine.parse("{a}{b}"), Map.of("a", "x" + HIGH, "b", LOW + "y"));
        assertSameBytes(engine.parse("{a}{b}"), Map.of("a", HIGH, "b", LOW));
        // unpaired
        assertSameBytes(engine.parse("{a}{b}"), Map.of("a", "x" + HIGH, "b", "y"));
        assertSameBytes(engine.parse("{a}-{b}"), Map.of("a", "x" + HIGH, "b", LOW));
        assertSameBytes(engine.parse("{a}"), Map.of("a", "x" + HIGH));
        assertSameBytes(engine.parse("{a}"), Map.of("a", LOW + "x"));
    }

    @Test
    public void testLargeOutput() {
        Template loop = engine.parse("<ul>{#for i in items}<li>{i}</li>{/for}</ul>");
        for (String unit : new String[] { "a", CYR, EMOJI, "ab" + EMOJI, CYR + EMOJI + "x" }) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                items.add(unit.repeat(1 + i % 7));
            }
            assertThat(assertSameBytes(loop, Map.of("items", items)).chunkCount()).isGreaterThan(1);
        }
        // one piece larger than a chunk and the scratch, with pairs at every boundary
        assertSameBytes(engine.parse("{a}"), Map.of("a", (CYR + EMOJI + "abc").repeat(20_000)));
    }

    @Test
    public void testSmallAsciiPageIsOneExactChunk() {
        Utf8Output output = assertSameBytes(engine.parse("<p>{a}</p>"), Map.of("a", "Hello"));
        assertThat(output.chunkCount()).isEqualTo(1);
        assertThat(output.chunk(0)).hasSize(output.byteLength());
    }

    @Test
    public void testSingleCharPieces() {
        // surrogate pairs split between pieces, also across the scratch boundaries
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            pieces.add(switch (i % 4) {
                case 0 -> "a";
                case 1 -> CYR.substring(0, 1);
                case 2 -> HIGH;
                default -> LOW;
            });
        }
        assertSameBytes(engine.parse("{#for p in pieces}{p}{/for}"), Map.of("pieces", pieces));
    }

    @Test
    public void testCharSequence() {
        String text = (CYR + EMOJI + "abc").repeat(5_000);
        CharSequence output = assertSameBytes(engine.parse("{a}"), Map.of("a", text));
        assertThat(output.length()).isEqualTo(text.length());
        assertThat(output.charAt(5)).isEqualTo(text.charAt(5));
        assertThat(output.subSequence(3, 12).toString()).isEqualTo(text.substring(3, 12));
    }
}
