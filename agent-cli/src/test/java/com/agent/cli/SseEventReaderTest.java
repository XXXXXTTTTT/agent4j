package com.agent.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventReaderTest {

    @Test
    void joinsMultilineDataAndIgnoresComments() {
        List<SseEventReader.SseEvent> events = SseEventReader.read(Stream.of(
                ": heartbeat",
                "id: event-1",
                "event: trace",
                "data: first",
                "data: second",
                ""));

        assertThat(events).containsExactly(new SseEventReader.SseEvent(
                "event-1", "trace", "first\nsecond"));
    }

    @Test
    void deliversEachCompletedFrameToConsumer() {
        List<SseEventReader.SseEvent> events = new ArrayList<>();

        SseEventReader.follow(Stream.of(
                "id: 1", "event: trace", "data: first", "",
                "id: 2", "event: trace", "data: second", ""), events::add);

        assertThat(events).containsExactly(
                new SseEventReader.SseEvent("1", "trace", "first"),
                new SseEventReader.SseEvent("2", "trace", "second"));
    }
}
