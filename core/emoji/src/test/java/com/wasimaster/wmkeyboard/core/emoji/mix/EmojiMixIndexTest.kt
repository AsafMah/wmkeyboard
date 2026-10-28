package com.wasimaster.wmkeyboard.core.emoji.mix

import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class EmojiMixIndexTest {

    private val host = "https://mixes.example.test/art"

    /** The shape of the community index: emoji entries, each listing its pairs. */
    private val communityJson = """
        {"knownSupportedEmoji":["1f603","1f601","263a-fe0f","1f62e-200d-1f4a8"],
         "data":{
          "1f603":{"alt":"smiley","emoji":"😃","emojiCodepoint":"1f603","gBoardOrder":2,"keywords":["face","smile"],
            "combinations":{
              "1f601":[
                {"gStaticUrl":"$host/20201001/u1f603/u1f603_u1f601.png","alt":"a","leftEmoji":"😃",
                 "leftEmojiCodepoint":"1f603","rightEmoji":"😁","rightEmojiCodepoint":"1f601",
                 "date":"20201001","isLatest":true,"gBoardOrder":2}],
              "263a-fe0f":[
                {"gStaticUrl":"$host/20201001/u263a-ufe0f/u263a-ufe0f_u1f603.png","leftEmojiCodepoint":"263a-fe0f",
                 "rightEmojiCodepoint":"1f603","date":"20201001","isLatest":false},
                {"gStaticUrl":"$host/20230301/u263a-ufe0f/u263a-ufe0f_u1f603.png","leftEmojiCodepoint":"263a-fe0f",
                 "rightEmojiCodepoint":"1f603","date":"20230301","isLatest":true}]}},
          "1f601":{"emoji":"😁","emojiCodepoint":"1f601","gBoardOrder":1,
            "combinations":{"1f603":[
                {"gStaticUrl":"$host/20201001/u1f603/u1f603_u1f601.png","leftEmojiCodepoint":"1f603",
                 "rightEmojiCodepoint":"1f601","date":"20201001","isLatest":true}],
              "1f62e-200d-1f4a8":[
                {"gStaticUrl":"$host/20210218/u1f62e-u200d-u1f4a8/u1f62e-u200d-u1f4a8_u1f601.png",
                 "leftEmojiCodepoint":"1f62e-200d-1f4a8","rightEmojiCodepoint":"1f601","date":"20210218","isLatest":true}]}},
          "263a-fe0f":{"emojiCodepoint":"263a-fe0f","gBoardOrder":3,"combinations":{}},
          "1f62e-200d-1f4a8":{"emojiCodepoint":"1f62e-200d-1f4a8","gBoardOrder":4,"combinations":{}}
         }}
    """.trimIndent()

    private fun index(text: String): MixIndex {
        val result = MixSourceReader.read(ByteArrayInputStream(text.toByteArray()))
        assertTrue("expected pairs, got $result", result is MixSourceReader.Result.Read)
        return (result as MixSourceReader.Result.Read).index
    }

    @Test
    fun communityIndexReadsEveryPairOnce() {
        val index = index(communityJson)
        assertEquals(3, index.pairCount)
        // Every address follows the pattern, so none is stored.
        assertFalse(index.find("😃", "😁")!!.url != null)
        assertNotNull(index.template)
    }

    @Test
    fun findWorksInEitherOrderAndRebuildsTheAddressExactly() {
        val index = index(communityJson)
        val forward = index.find("😃", "😁")!!
        val backward = index.find("😁", "😃")!!
        assertEquals(forward, backward)
        assertEquals("1f603", forward.left)
        assertEquals("$host/20201001/u1f603/u1f603_u1f601.png", index.template!!.fill(forward.left, forward.right, forward.date))
    }

    @Test
    fun latestArtWinsOverAnOlderDate() {
        val entry = index(communityJson).find("☺️", "😃")!!
        assertEquals("20230301", entry.date)
        assertEquals("263a-fe0f", entry.left)
    }

    @Test
    fun variationSelectorsAndSkinTonesDoNotStopAMatch() {
        val index = index(communityJson)
        // ☺ typed without its FE0F.
        assertNotNull(index.find("☺", "😃"))
        // A toned emoji matches its base: 👍🏽 is not in this index, but the
        // canonical form drops the tone the same way for any emoji.
        assertEquals("1f44d", MixSeq.canonicalOf("👍🏽"))
    }

    @Test
    fun zwjSequencesKeepTheirAddressShape() {
        val index = index(communityJson)
        val entry = index.find("😁", "😮‍💨")!!
        assertEquals(
            "$host/20210218/u1f62e-u200d-u1f4a8/u1f62e-u200d-u1f4a8_u1f601.png",
            index.template!!.fill(entry.left, entry.right, entry.date),
        )
    }

    @Test
    fun partnersFollowTheSourcesOrder() {
        val index = index(communityJson)
        // 😁 is first (gBoardOrder 1), then 😃 (2), ☺️ (3), 😮‍💨 (4).
        assertEquals(listOf("1f601", "1f603", "263a-fe0f", "1f62e-200d-1f4a8"), index.keys())
        val partners = index.partners("😃").map { if (it.left == "1f603") it.right else it.left }
        assertEquals(listOf("1f601", "263a-fe0f"), partners)
    }

    @Test
    fun indexSurvivesWritingAndReading() {
        val index = index(communityJson)
        val bytes = ByteArrayOutputStream().also { index.write(it) }.toByteArray()
        val back = MixIndex.read(ByteArrayInputStream(bytes))
        assertEquals(index.pairCount, back.pairCount)
        assertEquals(index.keys(), back.keys())
        assertEquals(index.find("😃", "😁"), back.find("😁", "😃"))
        assertEquals(index.template, back.template)
    }

    @Test
    fun plainTextListsWork() {
        val text = """
            # pairs, one per line
            $host/20201001/u1f603/u1f603_u1f601.png
            😺,🐶,https://elsewhere.example.test/x/cat-dog.png
            1f600 1f602
        """.trimIndent()
        val index = index(text)
        assertEquals(3, index.pairCount)
        assertEquals("https://elsewhere.example.test/x/cat-dog.png", index.find("🐶", "😺")!!.url)
        // A pair named without an address has none of its own.
        val bare = index.find("😀", "😂")!!
        assertEquals("", bare.date)
    }

    @Test
    fun aBareArrayOfAddressesWorks() {
        val index = index("""["$host/20201001/u1f603/u1f603_u1f601.png","$host/20230301/u2615/u2615_u1f603.png"]""")
        assertEquals(2, index.pairCount)
        assertEquals("20230301", index.find("😃", "☕")!!.date)
    }

    @Test
    fun aWebPageIsNotAnIndex() {
        val result = MixSourceReader.read(ByteArrayInputStream("<!doctype html><p>hi".toByteArray()))
        assertEquals(MixSourceReader.Result.NotAnIndex, result)
        assertEquals(MixSourceReader.Result.Empty, MixSourceReader.read(ByteArrayInputStream("{}".toByteArray())))
    }

    @Test
    fun templatesAreInferredFromOneExample() {
        val dated = MixTemplate.infer("$host/20201001/u263a-ufe0f/u263a-ufe0f_u2615.png")!!
        assertEquals("$host/{date}/{left_u}/{left_u}_{right_u}.png", dated.template.pattern)
        assertEquals("263a-fe0f", dated.left)
        assertEquals("2615", dated.right)
        assertEquals("20201001", dated.date)

        val flat = MixTemplate.infer("https://api.example.test/s/1f603_1f601?size=128")!!
        assertEquals("https://api.example.test/s/{left_hex}_{right_hex}?size={size}", flat.template.pattern)
        assertEquals("https://api.example.test/s/1f600_2615?size=512", flat.template.fill("1f600", "2615"))

        val encoded = MixTemplate.infer("https://api.example.test/mix/%F0%9F%98%83_%F0%9F%98%81.png")!!
        assertEquals("https://api.example.test/mix/{left}_{right}.png", encoded.template.pattern)
        assertEquals("1f603", encoded.left)

        assertNull(MixTemplate.infer("https://example.test/index.json"))
    }

    @Test
    fun sourceKindsAreToldApartByShape() {
        assertEquals(EmojiMixSourceKind.INDEX, EmojiMixer.detect("https://example.test/data/metadata.json"))
        assertEquals(EmojiMixSourceKind.IMAGE_ADDRESS, EmojiMixer.detect("$host/20201001/u1f603/u1f603_u1f601.png"))
        assertEquals(EmojiMixSourceKind.MIXER, EmojiMixer.detect("https://api.example.test/s/1f603_1f601?size=128"))
        assertEquals(EmojiMixSourceKind.MIXER, EmojiMixer.detect("https://api.example.test/{left}+{right}"))
        assertEquals(EmojiMixSourceKind.IMAGE_ADDRESS, EmojiMixer.detect("https://x.example.test/{date}/{left_u}_{right_u}.png"))
        assertEquals(EmojiMixSourceKind.PACK, EmojiMixer.detect("content://com.android.externalstorage/tree/x"))
    }

    @Test
    fun packFilesAreNamedForTheirPair() {
        assertEquals("1f601", MixTemplate.pairOfFile("1f603_1f601.png")!!.right)
        assertEquals("1f601", MixTemplate.pairOfFile("mixes/u1f603_u1f601.webp")!!.right)
        assertEquals("1f601", MixTemplate.pairOfFile("😃_😁.png")!!.right)
        assertEquals("20201001", MixTemplate.pairOfFile("20201001/u1f603/u1f603_u1f601.png")!!.date)
        assertNull(MixTemplate.pairOfFile("readme.txt"))
        assertNull(MixTemplate.pairOfFile("cover.png"))
    }

    @Test
    fun mixerAloneAnswersAnyPairUnverified() {
        val mixer = EmojiMixer(
            listOf(EmojiMixer.Loaded(EmojiMixSource("m", EmojiMixSourceKind.MIXER, "https://api.example.test/s/{left_hex}_{right_hex}"))),
        )
        assertTrue(mixer.usable)
        assertFalse(mixer.canList)
        val mix = mixer.mix("😃", "😁")!!
        assertFalse(mix.verified)
        assertEquals("https://api.example.test/s/1f603_1f601", mix.url)
    }

    @Test
    fun anImageAddressRepointsTheIndex() {
        val index = index(communityJson)
        val mixer = EmojiMixer(
            listOf(
                EmojiMixer.Loaded(EmojiMixSource("i", EmojiMixSourceKind.INDEX, "https://example.test/i.json"), index),
                EmojiMixer.Loaded(
                    EmojiMixSource("a", EmojiMixSourceKind.IMAGE_ADDRESS, "https://mirror.example.test/k/20201001/u1f603/u1f603_u1f601.png"),
                ),
            ),
        )
        val mix = mixer.mix("😁", "😃")!!
        assertTrue(mix.verified)
        assertEquals("😃", mix.left)
        assertEquals("https://mirror.example.test/k/20201001/u1f603/u1f603_u1f601.png", mix.url)
        assertEquals(2, mixer.partners("😃").size)
        // Both emoji are in the index, which has no pair of them: none to find.
        assertNull(mixer.mix("☺️", "😮‍💨"))
    }

    @Test
    fun aPackResolvesToItsFiles() {
        val builder = MixIndexBuilder()
        val pair = MixTemplate.pairOfFile("1f603_1f601.png")!!
        builder.add(pair.left, pair.right, pair.date, "1f603_1f601.png")
        val dir = File("/data/packs/p1")
        val mixer = EmojiMixer(
            listOf(EmojiMixer.Loaded(EmojiMixSource("p", EmojiMixSourceKind.PACK, "Mine.zip"), builder.build(), dir)),
        )
        assertEquals(File(dir, "1f603_1f601.png").toURI().toString(), mixer.mix("😁", "😃")!!.url)
    }

    @Test
    fun mixIdIgnoresOrderAndSpelling() {
        assertEquals(EmojiMixer.mixId("😃", "☺️"), EmojiMixer.mixId("☺", "😃"))
    }

    @Test
    fun trailingEmojiReadsOnlyWhatEndsTheText() {
        assertEquals(listOf("😃", "😁"), MixSeq.trailingEmoji("hi 😃😁", 2))
        assertEquals(listOf("😁"), MixSeq.trailingEmoji("😃 x 😁", 2))
        assertEquals(listOf("😁"), MixSeq.trailingEmoji("😃😁", 1))
        assertEquals(emptyList<String>(), MixSeq.trailingEmoji("hello", 2))
        assertEquals(emptyList<String>(), MixSeq.trailingEmoji("😃 ", 2))
        // Joined, toned, keycap and flag sequences stay whole.
        assertEquals(listOf("☕", "😮‍💨"), MixSeq.trailingEmoji("☕😮‍💨", 2))
        assertEquals(listOf("👍🏽", "❤️"), MixSeq.trailingEmoji("ok👍🏽❤️", 2))
        assertEquals(listOf("1️⃣"), MixSeq.trailingEmoji("a1️⃣", 2))
        assertEquals(listOf("🇺🇸", "🐱"), MixSeq.trailingEmoji("🇺🇸🐱", 2))
        assertEquals(listOf("👩‍❤️‍👨"), MixSeq.trailingEmoji("x👩‍❤️‍👨", 2))
        // A digit is not an emoji without its keycap.
        assertEquals(listOf("😃"), MixSeq.trailingEmoji("1😃", 2))
    }
}
