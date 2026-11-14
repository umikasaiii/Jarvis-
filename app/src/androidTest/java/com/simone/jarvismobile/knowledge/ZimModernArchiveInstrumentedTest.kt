package com.simone.jarvismobile.knowledge

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimHtml
import com.simone.jarvismobile.core.knowledge.zim.ZimTitleIndex
import com.simone.jarvismobile.core.knowledge.zim.ZimTitleSearch
import com.simone.jarvismobile.knowledge.zim.AndroidZimDecompressor
import com.simone.jarvismobile.knowledge.zim.SafByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ZIM HOTFIX — the REAL Android path: SafByteSource (ContentResolver file descriptor + positional reads) and the real
 * zstd-jni / xz decompressors on the emulator, over tiny SYNTHETIC archives (committed assets generated from the public
 * spec by ZimFixtureGenerator in :core): a modern writer's layout (header title pointer = absent sentinel, titles ordered
 * through X/listing/titleOrdered/v1) in zstd and xz, and the legacy layout. This does NOT prove that the user's real
 * Wikipedia/Wiktionary/Wikibooks files open: that is the Honor 200 procedure.
 */
@RunWith(AndroidJUnit4::class)
class ZimModernArchiveInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): Uri {
        val f = File(ctx.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("zim/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(f)
    }

    private fun check(name: String, mode: ZimTitleIndex) {
        SafByteSource(ctx, fixture(name)).use { src ->
            val zim = ZimArchive.open(src, AndroidZimDecompressor)
            assertEquals(name, mode, zim.titleIndexMode)
            assertEquals("Fixture", zim.metadata("Title"))
            val hits = ZimTitleSearch().search(zim, "Qual è la capitale della Francia, Parigi?")
            assertTrue(name + hits, hits.any { it.entry.displayTitle == "Parigi" })
            val e = zim.resolve(zim.titlesWithPrefix('C', "Parigi", 1).first())!!
            assertTrue(ZimHtml.toText(String(zim.content(e)!!)).text.contains("capitale della Francia"))
            assertTrue(zim.diagnostics().contains("titleIndex=${mode.name}"))
        }
    }

    @Test fun modernListingZstd() = check("modern_listing_v1_zstd.zim", ZimTitleIndex.LISTING_V1)
    @Test fun modernListingXz() = check("modern_listing_v1_xz.zim", ZimTitleIndex.LISTING_V1)
    @Test fun legacyListZstd() = check("legacy_v0_zstd.zim", ZimTitleIndex.LEGACY_V0)

    @Test fun redirectReachedThroughPathIndexOnModernLayout() {
        SafByteSource(ctx, fixture("modern_listing_v1_zstd.zim")).use { src ->
            val zim = ZimArchive.open(src, AndroidZimDecompressor)
            val hits = ZimTitleSearch().search(zim, "parlami di Berlin")
            assertTrue(hits.toString(), hits.any { it.entry.displayTitle == "Berlino" })
        }
    }
}
