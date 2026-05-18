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
package com.clickhouse.jdbcbridge.core;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.TimeZone;
import java.util.UUID;

import org.testng.annotations.Test;

/**
 * Round-trip tests for the wider-integer, UUID, sized-Decimal, and
 * fixed-string write/read paths in {@link ByteBuffer}. These are the
 * read-intensive RowBinary primitives the bridge spends most of its
 * streaming-response budget on — a regression that silently truncates
 * an Int256 or misaligns a Decimal128 would be invisible without coverage.
 *
 * <p>The existing {@code ByteBufferTest#testWriteAndRead} covers the basic
 * Int8–Int64, Float32/64, plain Decimal, and the simpler Date/DateTime
 * path. This file fills the gap on what that single test doesn't touch.</p>
 */
public class ByteBufferRoundTripTest {

    private static ByteBuffer fresh() {
        return ByteBuffer.newInstance(512);
    }

    // ---------- 128- and 256-bit integers ----------

    @Test(groups = { "unit" })
    public void int128_roundTripsSignedAcrossZeroAndExtremes() {
        ByteBuffer b = fresh();
        BigInteger pos = BigInteger.valueOf(2).pow(100).add(BigInteger.valueOf(123));
        BigInteger neg = pos.negate();
        BigInteger zero = BigInteger.ZERO;
        BigInteger max = BigInteger.valueOf(2).pow(127).subtract(BigInteger.ONE);
        BigInteger min = BigInteger.valueOf(2).pow(127).negate();

        b.writeInt128(pos).writeInt128(neg).writeInt128(zero).writeInt128(max).writeInt128(min);

        assertEquals(b.readInt128(), pos);
        assertEquals(b.readInt128(), neg);
        assertEquals(b.readInt128(), zero);
        assertEquals(b.readInt128(), max);
        assertEquals(b.readInt128(), min);
    }

    @Test(groups = { "unit" })
    public void int256_roundTripsSignedAcrossZeroAndExtremes() {
        ByteBuffer b = fresh();
        BigInteger big = BigInteger.valueOf(2).pow(200).add(BigInteger.valueOf(42));
        BigInteger neg = big.negate();
        BigInteger max = BigInteger.valueOf(2).pow(255).subtract(BigInteger.ONE);
        BigInteger min = BigInteger.valueOf(2).pow(255).negate();

        b.writeInt256(big).writeInt256(neg).writeInt256(max).writeInt256(min);

        assertEquals(b.readInt256(), big);
        assertEquals(b.readInt256(), neg);
        assertEquals(b.readInt256(), max);
        assertEquals(b.readInt256(), min);
    }

    @Test(groups = { "unit" })
    public void uint128_roundTripsValuesBelowSignBit() {
        // Current contract: readUInt128 delegates to readInt128 (which is signed).
        // Values >= 2^127 come back as negative — a latent issue worth knowing about,
        // but pinning the existing behavior so the next reader doesn't get surprised.
        ByteBuffer b = fresh();
        BigInteger small = BigInteger.valueOf(42);
        BigInteger boundary = BigInteger.valueOf(2).pow(64); // doesn't fit in UInt64
        BigInteger highButPositive = BigInteger.valueOf(2).pow(126); // safe under signed read

        b.writeUInt128(small).writeUInt128(boundary).writeUInt128(highButPositive);

        assertEquals(b.readUInt128(), small);
        assertEquals(b.readUInt128(), boundary);
        assertEquals(b.readUInt128(), highButPositive);
    }

    @Test(groups = { "unit" })
    public void uint256_roundTripsValuesBelowSignBit() {
        // Same caveat as uint128: readUInt256 returns the value sign-extended.
        ByteBuffer b = fresh();
        BigInteger huge = BigInteger.valueOf(2).pow(200).add(BigInteger.ONE);
        BigInteger highButPositive = BigInteger.valueOf(2).pow(254);

        b.writeUInt256(huge).writeUInt256(highButPositive);

        assertEquals(b.readUInt256(), huge);
        assertEquals(b.readUInt256(), highButPositive);
    }

    // ---------- UUID ----------

    @Test(groups = { "unit" })
    public void uuid_roundTripsRandomAndAllZeroes() {
        ByteBuffer b = fresh();
        UUID rand = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID zero = new UUID(0L, 0L);
        UUID maxBits = new UUID(-1L, -1L); // all bits set

        b.writeUUID(rand).writeUUID(zero).writeUUID(maxBits);

        assertEquals(b.readUUID(), rand);
        assertEquals(b.readUUID(), zero);
        assertEquals(b.readUUID(), maxBits);
    }

    // ---------- sized Decimals ----------

    @Test(groups = { "unit" })
    public void decimal32_roundTripsAcrossScale() {
        ByteBuffer b = fresh();
        BigDecimal v = new BigDecimal("1234.5678");

        b.writeDecimal32(v, 4);

        BigDecimal r = b.readDecimal32(4);
        // Decimal32 is a fixed-point Int32 internally; values are scaled by 10^scale
        // and serialized as raw integers. Round-trip must preserve magnitude + sign.
        assertEquals(r.unscaledValue(), v.unscaledValue());
        assertEquals(r.scale(), 4);
    }

    @Test(groups = { "unit" })
    public void decimal64_roundTripsLargerValues() {
        ByteBuffer b = fresh();
        BigDecimal v = new BigDecimal("9999999999.123456");

        b.writeDecimal64(v, 6);

        BigDecimal r = b.readDecimal64(6);
        assertEquals(r.unscaledValue(), v.unscaledValue());
        assertEquals(r.scale(), 6);
    }

    @Test(groups = { "unit" })
    public void decimal128_roundTrips() {
        ByteBuffer b = fresh();
        BigDecimal v = new BigDecimal("123456789012345678.901234567890");

        b.writeDecimal128(v, 12);

        BigDecimal r = b.readDecimal128(12);
        assertEquals(r.unscaledValue(), v.unscaledValue());
    }

    @Test(groups = { "unit" })
    public void decimal256_roundTrips() {
        ByteBuffer b = fresh();
        BigDecimal v = new BigDecimal("1").movePointRight(40); // 10^40

        b.writeDecimal256(v, 0);

        BigDecimal r = b.readDecimal256(0);
        assertEquals(r.unscaledValue(), v.unscaledValue());
    }

    @Test(groups = { "unit" })
    public void decimal_negativeValuesRoundTrip() {
        // Use compareTo for numeric equality — readDecimal32 normalizes the
        // result (-12.5 instead of -12.50) which would fail an unscaledValue
        // comparison even though the numeric value is correct.
        ByteBuffer b = fresh();
        BigDecimal neg = new BigDecimal("-12.50");

        b.writeDecimal32(neg, 2);

        BigDecimal r = b.readDecimal32(2);
        assertEquals(r.compareTo(neg), 0,
                "expected " + neg + " numerically, got " + r);
        assertTrue(r.signum() < 0, "negative decimal must come back negative");
    }

    // ---------- fixed string + charset overload ----------

    @Test(groups = { "unit" })
    public void fixedString_paddedRoundTripAscii() {
        ByteBuffer b = fresh();
        b.writeFixedString("hi", 8);

        String r = b.readFixedString(8);
        // FixedString pads with NULs; bridge's read returns the padded buffer as a
        // String, so the result starts with the source string. A regression that
        // dropped padding would corrupt the wire offsets for the next field.
        assertTrue(r.startsWith("hi"), "expected prefix 'hi', got: " + r);
        assertEquals(r.length(), 8);
    }

    @Test(groups = { "unit" })
    public void fixedString_explicitCharsetIsHonored() {
        ByteBuffer b = fresh();
        // UTF-16BE encodes "ab" as 4 bytes (0x00 0x61 0x00 0x62).
        b.writeFixedString("ab", 4, StandardCharsets.UTF_16BE);

        String r = b.readFixedString(4, StandardCharsets.UTF_16BE);
        assertTrue(r.startsWith("ab"), "expected prefix 'ab' under UTF-16BE round-trip, got: " + r);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void fixedString_oversizedInputThrowsRatherThanTruncates() {
        // Pre-existing contract: writeFixedString routes through Utils.checkArgument
        // which throws when the encoded bytes exceed `length`. Truncation would
        // corrupt the wire offsets for the next field, so the throw is the
        // correct behavior to pin.
        ByteBuffer b = fresh();
        b.writeFixedString("abcdef", 3);
    }

    // ---------- DateTime with TimeZone overloads ----------

    @Test(groups = { "unit" })
    public void dateTime_writeWithTimezoneRoundTripsViaSameZone() {
        ByteBuffer b = fresh();
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Timestamp t = new Timestamp(1_700_000_000_000L);
        // The seconds-precision DateTime path drops sub-second fractions; trim
        // ours to match so the round-trip comparison is meaningful.
        t = new Timestamp((t.getTime() / 1000L) * 1000L);

        b.writeDateTime(t, utc);

        Timestamp r = b.readDateTime(utc);
        assertEquals(r.getTime(), t.getTime(),
                "DateTime(UTC) must round-trip to the same instant");
    }

    @Test(groups = { "unit" })
    public void dateTime64_acrossScales() {
        // DateTime64 carries fractional seconds at the requested scale. The
        // common scales in practice are 3 (ms), 6 (us), 9 (ns); pin the
        // common case of 3 to confirm the millisecond-level path.
        ByteBuffer b = fresh();
        Timestamp t = new Timestamp(1_700_000_000_123L);
        TimeZone utc = TimeZone.getTimeZone("UTC");

        b.writeDateTime64(t, 3, utc);

        Timestamp r = b.readDateTime64(utc);
        assertEquals(r.getTime(), t.getTime(),
                "DateTime64(scale=3) must preserve millisecond precision");
    }

    // ---------- enum read/write ----------

    @Test(groups = { "unit" })
    public void enum8AndEnum16Write_int_and_byte_overloads() {
        ByteBuffer b = fresh();
        b.writeEnum8((byte) 1);
        b.writeEnum8(2); // int overload
        b.writeEnum16((short) 100);
        b.writeEnum16(200); // int overload

        // Read back as enum bytes / shorts via the underlying primitive reads.
        assertEquals(b.readInt8(), (byte) 1);
        assertEquals(b.readInt8(), (byte) 2);
        assertEquals(b.readInt16(), (short) 100);
        assertEquals(b.readInt16(), (short) 200);
    }
}
