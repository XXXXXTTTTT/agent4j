package com.agent.sandbox.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserEvidenceSelectorTest {

    @Test
    void acceptsPageAndLocatorSelectorsWithoutChangingSpelling() {
        assertThat(BrowserEvidenceSelector.page().selector()).isEqualTo("page");
        assertThat(BrowserEvidenceSelector.locator("#result").selector()).isEqualTo("#result");
    }

    @Test
    void rejectsBlankAndOverlongLocatorSelectors() {
        assertThatThrownBy(() -> BrowserEvidenceSelector.locator(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selector");
        assertThatThrownBy(() -> BrowserEvidenceSelector.locator("x".repeat(2_049)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selector");
    }
}
