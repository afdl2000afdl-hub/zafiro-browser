package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.iab.IabManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowserBuiltin] 的参数契约与冷启动门控的 JVM 单测（不依赖 Android 运行时）。
 * 门控断言是变异检查点：若有人删掉 [IabManager.isReady] 前置判断，
 * 这些用例会挂起或抛 MainDispatcher 异常（而不是干净的 IAB_UNAVAILABLE）。
 */
class BrowserBuiltinTest {

    private val tool = BrowserBuiltin()

    private fun invoke(json: String) = runBlocking {
        tool.invoke(BuiltinToolRequest(name = "browser", argumentsJson = json))
    }

    @Test
    fun missingActionFailsCleanly() {
        val r = invoke("""{"url":"https://example.com"}""")
        assertFalse(r.ok)
        assertEquals("MISSING_ACTION", r.code)
        assertTrue(r.fieldErrors.containsKey("action"))
    }

    @Test
    fun unknownActionFailsCleanly() {
        val r = invoke("""{"action":"teleport"}""")
        assertFalse(r.ok)
        assertEquals("UNSUPPORTED_ACTION", r.code)
    }

    @Test
    fun malformedArgumentsFailCleanly() {
        val r = invoke("not json at all")
        assertFalse(r.ok)
        assertEquals("INVALID_ARGUMENTS", r.code)
    }

    @Test
    fun coldStartReturnsUnavailableWithoutHanging() {
        // JVM 单测没有 Android 上下文 → isReady() 必须为 false，且调用立即返回（不挂起、不抛）。
        assertTrue("JVM test environment must not have IAB context", !IabManager.isReady())
        val r = invoke("""{"action":"navigate","url":"https://example.com"}""")
        assertFalse(r.ok)
        assertEquals(IabManager.IAB_UNAVAILABLE, r.code)
        assertEquals("IAB_UNAVAILABLE", r.code)
    }

    @Test
    fun schemaIsValidJsonAndEnumMatchesKnownActions() {
        val schemaJson = Json.parseToJsonElement(tool.inputSchemaJson!!)
        val properties = schemaJson.jsonObject["properties"]!!.jsonObject
        val enum = properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        // schema 的 enum 必须与分发表一一对应（少一个 action → 模型无法调用；多一个 → 必落 else 分支报错）
        assertTrue(enum.contains("navigate"))
        assertTrue(enum.contains("screenshot"))
        assertTrue(enum.contains("file_upload"))
        assertTrue(enum.contains("emulate_desktop"))
        assertEquals(listOf("action"), schemaJson.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun toolIsRegisteredInDefaultRegistry() {
        // 回归：新工具必须出现在 BuiltinToolRegistry.default()（否则模型永远看不到它）
        val registry = com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry.default()
        val found = registry.find("browser")
        assertNotNull("browser tool missing from default registry", found)
        assertEquals("browser", found!!.name)
        // description 必须覆盖主要 action 名（模型靠 description 学用法）
        val d = found.description
        assertTrue(d.contains("navigate"))
        assertTrue(d.contains("screenshot"))
    }

    @Test
    fun defaultEnabledIsTrue() {
        assertTrue(tool.defaultEnabled)
    }
}
