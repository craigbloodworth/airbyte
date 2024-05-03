/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.oracle

import io.airbyte.cdk.command.SyncsTestFixture
import org.junit.jupiter.api.Test

class OracleSpecIntegrationTest {

    @Test
    fun testSpec() {
        SyncsTestFixture.testSpec("expected-spec.json")
    }
}
