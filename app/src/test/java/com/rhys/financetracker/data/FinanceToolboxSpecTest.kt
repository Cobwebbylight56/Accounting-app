package com.rhys.financetracker.data

import com.rhys.financetracker.data.claude.FinanceToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tools Claude is given are well formed: the API refuses a malformed one. */
class FinanceToolboxSpecTest {

    @Test
    fun `every tool has a unique name, a description and typed inputs`() {
        val names = FinanceToolbox.SPECS.map { it.name }
        assertEquals(names.distinct(), names)
        FinanceToolbox.SPECS.forEach { spec ->
            assertTrue(spec.name, Regex("^[a-zA-Z0-9_-]{1,64}$").matches(spec.name))
            assertTrue(spec.name, spec.description.length > 20)
            spec.properties.forEach { (name, schema) -> assertTrue("${spec.name}.$name", schema["type"] in setOf("string", "integer", "number")) }
            assertTrue(spec.name, spec.properties.keys.containsAll(spec.required))
        }
    }

    @Test
    fun `there is no tool that changes anything`() {
        FinanceToolbox.SPECS.forEach { spec ->
            assertTrue(spec.name, spec.name.startsWith("get_") || spec.name.startsWith("search_"))
        }
    }
}
