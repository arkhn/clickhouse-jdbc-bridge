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
 * Round-trip tests for the wider-integer, UUID, sized-Decimal, and fixed-string
 * write/read paths in {@link ByteBuffer}.
 */
public class ByteBufferRoundTripTest {

    private static ByteBuffer fresh() {
        return ByteBuffer.newInstance(512);
    }

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
        // readUInt128 delegates to readInt128 (signed): values >= 2^127 come back negative.
        // Latent issue worth knowing about — pinning existing behavior.
        ByteBuffer b = fresh();
        BigInteger small = BigInteger.valueOf(42);
        BigInteger boundary = BigInteger.valueOf(2).pow(64);
        BigInteger highButPositive = BigInteger.valueOf(2).pow(126);

        b.writeUInt128(small).writeUInt128(boundary).writeUInt128(highButPositive);

        assertEquals(b.readUInt128(), small);
        assertEquals(b.readUInt128(), boundary);
        assertEquals(b.readUInt128(), highButPositive);
    }

    @Test(groups = { "unit" })
    public void uint256_roundTripsValuesBelowSignBit() {
        // Same caveat as uint128: readUInt256 returns sign-extended.
        ByteBuffer b = fresh();
        BigInteger huge = BigInteger.valueOf(2).pow(200).add(BigInteger.ONE);
        BigInteger highButPositive = BigInteger.valueOf(2).pow(254);

        b.writeUInt256(huge).writeUInt256(highButPositive);

        assertEquals(b.readUInt256(), huge);
        assertEquals(b.readUInt256(), highButPositive);
    }

    @Test(groups = { "unit" })
    public void uuid_roundTripsRandomAndAllZeroes() {
        ByteBuffer b = fresh();
        UUID rand = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID zero = new UUID(0L, 0L);
        UUID maxBits = new UUID(-1L, -1L);

        b.writeUUID(rand).writeUUID(zero).writeUUID(maxBits);

        assertEquals(b.readUUID(), rand);
        assertEquals(b.readUUID(), zero);
        assertEquals(b.readUUID(), maxBits);
    }

    @Test(groups = { "unit" })
    public void decimal32_roundTripsAcrossScale() {
        ByteBuffer b = fresh();
        BigDecimal v = new BigDecimal("1234.5678");

        b.writeDecimal32(v, 4);

        BigDecimal r = b.readDecimal32(4);
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
        BigDecimal v = new BigDecimal("1").movePointRight(40);

        b.writeDecimal256(v, 0);

        BigDecimal r = b.readDecimal256(0);
        assertEquals(r.unscaledValue(), v.unscaledValue());
    }

    @Test(groups = { "unit" })
    public void decimal_negativeValuesRoundTrip() {
        // Use compareTo: readDecimal32 normalizes (-12.5 vs -12.50) so unscaledValue would mismatch.
        ByteBuffer b = fresh();
        BigDecimal neg = new BigDecimal("-12.50");

        b.writeDecimal32(neg, 2);

        BigDecimal r = b.readDecimal32(2);
        assertEquals(r.compareTo(neg), 0,
                "expected " + neg + " numerically, got " + r);
        assertTrue(r.signum() < 0, "negative decimal must come back negative");
    }

    @Test(groups = { "unit" })
    public void fixedString_paddedRoundTripAscii() {
        // FixedString pads with NULs; dropping padding would corrupt wire offsets.
        ByteBuffer b = fresh();
        b.writeFixedString("hi", 8);

        String r = b.readFixedString(8);
        assertTrue(r.startsWith("hi"), "expected prefix 'hi', got: " + r);
        assertEquals(r.length(), 8);
    }

    @Test(groups = { "unit" })
    public void fixedString_explicitCharsetIsHonored() {
        ByteBuffer b = fresh();
        // UTF-16BE encodes "ab" as 4 bytes.
        b.writeFixedString("ab", 4, StandardCharsets.UTF_16BE);

        String r = b.readFixedString(4, StandardCharsets.UTF_16BE);
        assertTrue(r.startsWith("ab"), "expected prefix 'ab' under UTF-16BE round-trip, got: " + r);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void fixedString_oversizedInputThrowsRatherThanTruncates() {
        // Truncation would corrupt wire offsets — throw is correct.
        ByteBuffer b = fresh();
        b.writeFixedString("abcdef", 3);
    }

    @Test(groups = { "unit" })
    public void dateTime_writeWithTimezoneRoundTripsViaSameZone() {
        ByteBuffer b = fresh();
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Timestamp t = new Timestamp(1_700_000_000_000L);
        // DateTime is seconds-precision; trim sub-seconds so round-trip is meaningful.
        t = new Timestamp((t.getTime() / 1000L) * 1000L);

        b.writeDateTime(t, utc);

        Timestamp r = b.readDateTime(utc);
        assertEquals(r.getTime(), t.getTime(),
                "DateTime(UTC) must round-trip to the same instant");
    }

    @Test(groups = { "unit" })
    public void dateTime64_acrossScales() {
        // Scale 3 = milliseconds.
        ByteBuffer b = fresh();
        Timestamp t = new Timestamp(1_700_000_000_123L);
        TimeZone utc = TimeZone.getTimeZone("UTC");

        b.writeDateTime64(t, 3, utc);

        Timestamp r = b.readDateTime64(utc);
        assertEquals(r.getTime(), t.getTime(),
                "DateTime64(scale=3) must preserve millisecond precision");
    }

    @Test(groups = { "unit" })
    public void enum8AndEnum16Write_int_and_byte_overloads() {
        ByteBuffer b = fresh();
        b.writeEnum8((byte) 1);
        b.writeEnum8(2);
        b.writeEnum16((short) 100);
        b.writeEnum16(200);

        assertEquals(b.readInt8(), (byte) 1);
        assertEquals(b.readInt8(), (byte) 2);
        assertEquals(b.readInt16(), (short) 100);
        assertEquals(b.readInt16(), (short) 200);
    }
}
