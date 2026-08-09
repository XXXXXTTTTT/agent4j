package com.agent.sandbox.browser;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserModelTest {

    @Test
    void definesExactAsynchronousBrowserContract() throws Exception {
        assertThat(AutoCloseable.class).isAssignableFrom(BrowserAutomation.class);
        assertThat(BrowserAutomation.class
                .getMethod("navigate", URI.class, Duration.class)
                .getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(BrowserAutomation.class
                .getMethod("click", String.class, Duration.class)
                .getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(BrowserAutomation.class
                .getMethod("extractDom")
                .getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(BrowserAutomation.class
                .getMethod("extractDom", Duration.class)
                .getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(BrowserAutomation.class
                .getMethod("screenshot", Duration.class)
                .getReturnType()).isEqualTo(CompletableFuture.class);
    }

    @Test
    void storesNavigationUrisAndOptionalStatus() {
        URI requested = URI.create("https://example.test/start");
        URI finalUri = URI.create("https://example.test/final");

        NavigationResult result = new NavigationResult(
                requested, finalUri, OptionalInt.of(200));

        assertThat(result.requestedUrl()).isEqualTo(requested);
        assertThat(result.finalUrl()).isEqualTo(finalUri);
        assertThat(result.statusCode()).hasValue(200);
    }

    @Test
    void rejectsNullNavigationFields() {
        URI uri = URI.create("https://example.test");

        assertThatThrownBy(() -> new NavigationResult(null, uri, OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NavigationResult(uri, null, OptionalInt.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NavigationResult(uri, uri, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defensivelyCopiesPngBytesOnConstructionAndAccess() {
        byte[] source = {1, 2, 3};
        BrowserScreenshot screenshot = new BrowserScreenshot(source, "image/png");

        source[0] = 9;
        byte[] returned = screenshot.pngBytes();
        returned[1] = 9;

        assertThat(screenshot.pngBytes()).containsExactly(1, 2, 3);
        assertThat(screenshot.mediaType()).isEqualTo("image/png");
    }

    @Test
    void rejectsEmptyPngAndNonPngMediaType() {
        assertThatThrownBy(() -> new BrowserScreenshot(new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pngBytes");
        assertThatThrownBy(() -> new BrowserScreenshot(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image/png");
    }
}
