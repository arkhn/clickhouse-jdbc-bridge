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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class DataAccessExceptionTest {

    @Test(groups = { "unit" })
    public void messageEmbedsDataSourceIdAndExplicitMessage() {
        Throwable cause = new RuntimeException("ignored when explicit message present");
        DataAccessException ex = new DataAccessException("my-ds", "boom", cause);

        assertTrue(ex.getMessage().contains("my-ds"),
                "expected datasource id in message, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("boom"),
                "expected explicit message text in message, got: " + ex.getMessage());
        assertSame(ex.getCause(), cause);
    }

    @Test(groups = { "unit" })
    public void nullMessageFallsBackToCauseMessage() {
        Throwable cause = new RuntimeException("from-cause");
        DataAccessException ex = new DataAccessException("ds-X", null, cause);

        assertTrue(ex.getMessage().contains("ds-X"));
        assertTrue(ex.getMessage().contains("from-cause"),
                "expected cause's message to surface when no explicit message; got: " + ex.getMessage());
    }

    @Test(groups = { "unit" })
    public void emptyMessageFallsBackToCauseMessage() {
        Throwable cause = new RuntimeException("from-cause-empty");
        DataAccessException ex = new DataAccessException("ds-Y", "", cause);

        assertTrue(ex.getMessage().contains("from-cause-empty"));
    }

    @Test(groups = { "unit" })
    public void twoArgConstructorUsesCauseMessage() {
        Throwable cause = new IllegalStateException("two-arg-cause");
        DataAccessException ex = new DataAccessException("ds-Z", cause);

        assertTrue(ex.getMessage().contains("ds-Z"));
        assertTrue(ex.getMessage().contains("two-arg-cause"));
        assertSame(ex.getCause(), cause);
    }

    @Test(groups = { "unit" })
    public void unknownErrorSentinelWhenCauseIsNullAndMessageIsEmpty() {
        // Reaches buildErrorMessage's UNKNOWN_ERROR branch via static helper —
        // we can't construct DataAccessException with a null cause (Objects.requireNonNull
        // on cause), so call the helper directly.
        String msg = DataAccessException.buildErrorMessage("ds-U", null, null);
        assertEquals(msg,
                DataAccessException.ERROR_BEGIN + "ds-U" + DataAccessException.ERROR_END
                        + DataAccessException.UNKNOWN_ERROR);
    }

    @Test(groups = { "unit" })
    public void nullDataSourceIdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new DataAccessException(null, "x", new RuntimeException()));
    }

    @Test(groups = { "unit" })
    public void nullCauseIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new DataAccessException("ds", "x", null));
    }
}
