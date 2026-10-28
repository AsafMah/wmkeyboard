package com.wasimaster.wmkeyboard.core.imagegen

import com.wasimaster.wmkeyboard.core.providers.ProviderChannel
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.settings.ProviderResolution
import com.wasimaster.wmkeyboard.core.settings.providerChain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts of the online sticker engines that need no network: addresses, the ComfyUI graph, borrowed keys, the default list. */
class OnlineImageGenTest {

    private val env = ProviderEnv(ProviderChannel.GITHUB, offline = false, onDeviceTranslate = false, offlineGifPacks = false)

    @Test
    fun `an OpenAI-compatible address gets a version only when it has none`() {
        assertEquals("https://api.deepinfra.com/v1/openai", OnlineImageGen.compatibleBase("https://api.deepinfra.com/v1/openai/"))
        assertEquals("http://192.168.1.5:8080/v1", OnlineImageGen.compatibleBase("192.168.1.5:8080"))
        assertEquals("https://host/v1", OnlineImageGen.compatibleBase("https://host/v1/images/generations"))
        assertEquals("https://host/api/v2beta", OnlineImageGen.compatibleBase("https://host/api/v2beta"))
    }

    @Test
    fun `the ComfyUI graph carries the request's values`() {
        val graph = OnlineImageGen.comfyGraph("dreamshaper_8.safetensors", "a cat", "text", 42L, 512, 2)
        fun inputs(node: String) = (graph[node] as JsonObject)["inputs"] as JsonObject
        assertEquals("dreamshaper_8.safetensors", (inputs("4")["ckpt_name"] as JsonPrimitive).content)
        assertEquals("a cat", (inputs("6")["text"] as JsonPrimitive).content)
        assertEquals("text", (inputs("7")["text"] as JsonPrimitive).content)
        assertEquals("42", (inputs("3")["seed"] as JsonPrimitive).content)
        assertEquals("2", (inputs("5")["batch_size"] as JsonPrimitive).content)
        // Links are [node, output slot]: the VAE is the checkpoint's third output.
        assertEquals(listOf("\"4\"", "2"), (inputs("8")["vae"] as JsonArray).map { it.toString() })
        assertEquals("SaveImage", ((graph["9"] as JsonObject)["class_type"] as JsonPrimitive).content)
    }

    @Test
    fun `an image kind borrows the AI list's key, and needs one when there is none`() {
        val entry = ProviderEntry(ProviderTypes.OPENAI_IMAGE.id)
        val borrowed = ProviderResolution.resolve(
            entry,
            listOf(ProviderCredential(ProviderTypes.OPENAI.id, ProviderTypes.OPENAI.id, apiKey = " sk-ai ")),
            env,
        )
        assertEquals("sk-ai", borrowed.apiKey)
        assertNull(borrowed.problem)
        val own = ProviderResolution.resolve(
            entry,
            listOf(
                ProviderCredential(ProviderTypes.OPENAI.id, ProviderTypes.OPENAI.id, apiKey = "sk-ai"),
                ProviderCredential(ProviderTypes.OPENAI_IMAGE.id, ProviderTypes.OPENAI_IMAGE.id, apiKey = "sk-img"),
            ),
            env,
        )
        assertEquals("sk-img", own.apiKey)
        assertEquals(ProviderProblem.NEEDS_KEY, ProviderResolution.resolve(entry, emptyList(), env).problem)
    }

    @Test
    fun `the free services need nothing, the keyed ones a key`() {
        assertNull(ProviderResolution.resolve(ProviderEntry(ProviderTypes.POLLINATIONS.id), emptyList(), env).problem)
        assertNull(ProviderResolution.resolve(ProviderEntry(ProviderTypes.AI_HORDE.id), emptyList(), env).problem)
        assertEquals(ProviderProblem.NEEDS_KEY, ProviderResolution.resolve(ProviderEntry(ProviderTypes.STABILITY.id), emptyList(), env).problem)
        assertEquals(ProviderProblem.NEEDS_SERVER, ProviderResolution.resolve(ProviderEntry(ProviderTypes.COMFYUI.id), emptyList(), env).problem)
        val offline = env.copy(offline = true)
        assertEquals(ProviderProblem.UNAVAILABLE, ProviderResolution.resolve(ProviderEntry(ProviderTypes.POLLINATIONS.id), emptyList(), offline).problem)
        assertNull(ProviderResolution.resolve(ProviderEntry(ProviderTypes.ON_DEVICE_IMAGE.id), emptyList(), offline).problem)
    }

    @Test
    fun `a new list draws on the phone, with the free services listed but off`() {
        val chain = KeyboardSettings().providerChain(ProviderTool.IMAGE_GEN, env)
        assertEquals(
            listOf(ProviderTypes.ON_DEVICE_IMAGE.id, ProviderTypes.POLLINATIONS.id, ProviderTypes.AI_HORDE.id),
            chain.entries.map { it.typeId },
        )
        assertEquals(listOf(true, false, false), chain.entries.map { it.enabled })
        assertFalse(OnlineImageGen.probes(ProviderTypes.FAL.id))
    }
}
