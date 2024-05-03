/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.command

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.airbyte.commons.jackson.MoreMappers
import io.airbyte.commons.json.Jsons
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@MicronautTest(rebuildContext = true)
class InputStateTest {

    @Inject lateinit var actual: InputState

    @Test
    fun testEmpty() {
        Assertions.assertEquals(EmptyInputState, actual)
    }

    @Test
    @Property(
        name = "airbyte.connector.state.resource",
        value = "command/vanilla-stream-states.json"
    )
    fun testVanillaStreamStates() {
        val expected =
            StreamInputState(
                mapOf(
                    AirbyteStreamNameNamespacePair("bar", "foo") to
                        StreamStateValue(primaryKey = mapOf("k1" to json(10), "k2" to json(20))),
                    AirbyteStreamNameNamespacePair("baz", "foo") to
                        StreamStateValue(cursors = mapOf("c" to json(30))),
                )
            )
        Assertions.assertEquals(Jsons.serialize(expected), Jsons.serialize(actual))
    }

    @Test
    @Property(
        name = "airbyte.connector.state.resource",
        value = "command/vanilla-global-states.json"
    )
    fun testVanillaGlobalStates() {
        val expected =
            GlobalInputState(
                global = GlobalStateValue(Jsons.emptyObject()),
                globalStreams =
                    mapOf(
                        AirbyteStreamNameNamespacePair("bar", "foo") to
                            StreamStateValue(primaryKey = mapOf("k1" to json(10), "k2" to json(20)))
                    ),
                nonGlobalStreams = mapOf()
            )
        Assertions.assertEquals(Jsons.serialize(expected), Jsons.serialize(actual))
    }

    @Test
    @Property(
        name = "airbyte.connector.state.resource",
        value = "command/vanilla-mixed-states.json"
    )
    fun testVanillaMixedStates() {
        val expected =
            GlobalInputState(
                global = GlobalStateValue(Jsons.emptyObject()),
                globalStreams =
                    mapOf(
                        AirbyteStreamNameNamespacePair("bar", "foo") to
                            StreamStateValue(primaryKey = mapOf("k1" to json(10), "k2" to json(20)))
                    ),
                nonGlobalStreams =
                    mapOf(
                        AirbyteStreamNameNamespacePair("baz", "foo") to
                            StreamStateValue(primaryKey = mapOf("k" to json(1)))
                    )
            )
        Assertions.assertEquals(Jsons.serialize(expected), Jsons.serialize(actual))
    }

    @Test
    @Property(name = "airbyte.connector.state.resource", value = "command/duplicate-states.json")
    fun testDuplicates() {
        val expected =
            GlobalInputState(
                global = GlobalStateValue(Jsons.emptyObject()),
                globalStreams =
                    mapOf(
                        AirbyteStreamNameNamespacePair("bar", "foo") to
                            StreamStateValue(primaryKey = mapOf("k1" to json(10), "k2" to json(20)))
                    ),
                nonGlobalStreams =
                    mapOf(
                        AirbyteStreamNameNamespacePair("baz", "foo") to
                            StreamStateValue(primaryKey = mapOf("k" to json(10)))
                    )
            )
        Assertions.assertEquals(Jsons.serialize(expected), Jsons.serialize(actual))
    }

    companion object {
        val nodeFactory: JsonNodeFactory = MoreMappers.initMapper().nodeFactory

        fun json(n: Long): JsonNode = nodeFactory.numberNode(n)
    }
}
