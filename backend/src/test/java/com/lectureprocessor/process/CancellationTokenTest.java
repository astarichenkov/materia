package com.lectureprocessor.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void startsNotCancelledAndFlipsOnce() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();
        token.throwIfCancelled();
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
        assertThatThrownBy(token::throwIfCancelled)
                .isInstanceOf(ProcessCancelledException.class);
    }
}
