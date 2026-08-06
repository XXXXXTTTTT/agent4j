package com.agent.web.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredActorResolverTest {

    @Test
    void preservesConfiguredIdentityExactly() {
        Actor actor = new ConfiguredActorResolver("User-Case-01", "本地开发者").current();

        assertThat(actor.userId()).isEqualTo("User-Case-01");
        assertThat(actor.displayName()).isEqualTo("本地开发者");
    }

    @Test
    void rejectsBlankConfiguredIdentity() {
        assertThatThrownBy(() -> new ConfiguredActorResolver(" ", "本地开发者"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }
}
