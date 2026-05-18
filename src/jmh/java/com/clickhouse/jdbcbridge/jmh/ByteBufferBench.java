/*
 * Copyright 2019-2021, Zhichun Wu
 * Copyright 2024-2026, Arkhn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clickhouse.jdbcbridge.jmh;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.clickhouse.jdbcbridge.core.ByteBuffer;

/**
 * Micro-bench the RowBinary serialization path. This is the hot inner loop for every workload
 * that reads bulk data through the bridge — regressions here show up as a 10-30% drop in W3 QPS,
 * but are hard to spot in noisy end-to-end measurements.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ByteBufferBench {

    private ByteBuffer buf;

    @Setup
    public void setup() {
        // 64 KiB initial — typical for a single bridge response chunk
        buf = ByteBuffer.newInstance(65536);
    }

    @Benchmark
    public void writeInt64x1k(Blackhole bh) {
        ByteBuffer b = ByteBuffer.newInstance(8192);
        for (int i = 0; i < 1000; i++) {
            b.writeInt64(i);
        }
        bh.consume(b);
    }

    @Benchmark
    public void writeStringx1k(Blackhole bh) {
        ByteBuffer b = ByteBuffer.newInstance(65536);
        for (int i = 0; i < 1000; i++) {
            b.writeString("hits/" + i);
        }
        bh.consume(b);
    }

    @Benchmark
    public void writeMixedRowx100(Blackhole bh) {
        ByteBuffer b = ByteBuffer.newInstance(16384);
        for (int i = 0; i < 100; i++) {
            b.writeInt64(i);            // watchid
            b.writeInt64(i * 7L);       // userid
            b.writeString("Title " + i);
            b.writeUInt8(i & 0xff);     // os
            b.writeFloat32((float) (i / 7.0));
        }
        bh.consume(b);
    }
}
