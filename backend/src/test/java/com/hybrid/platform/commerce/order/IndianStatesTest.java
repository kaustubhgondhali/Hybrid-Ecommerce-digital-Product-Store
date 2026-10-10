package com.hybrid.platform.commerce.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndianStatesTest {

    @Test
    void matchesNamesLoosely() {
        assertThat(IndianStates.codeForName("Jammu & Kashmir")).isEqualTo("01");
        assertThat(IndianStates.codeForName("tamil nadu")).isEqualTo("33");
        assertThat(IndianStates.codeForName("  MAHARASHTRA ")).isEqualTo("27");
        assertThat(IndianStates.codeForName("Dadra & Nagar Haveli and Daman & Diu")).isEqualTo("26");
    }

    @Test
    void unknownNamesGiveNull() {
        assertThat(IndianStates.codeForName("Atlantis")).isNull();
        assertThat(IndianStates.codeForName(" ")).isNull();
        assertThat(IndianStates.codeForName(null)).isNull();
    }

    @Test
    void validatesCodes() {
        assertThat(IndianStates.isValid("29")).isTrue();
        assertThat(IndianStates.isValid("99")).isFalse();
        assertThat(IndianStates.name("07")).isEqualTo("Delhi");
    }
}
