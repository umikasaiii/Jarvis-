package com.simone.jarvismobile.core.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class SerialWriterTest {
    @Test fun `writes finish in submission order even when an earlier one is slower`() {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val w = SerialWriter(scope)
        val done = CopyOnWriteArrayList<String>()
        w.submit { delay(80); done.add("save-old") }
        w.submit { done.add("clear") }
        w.submit { done.add("save-new") }
        val end = System.currentTimeMillis() + 3000
        while (done.size < 3 && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(listOf("save-old", "clear", "save-new"), done.toList())
    }

    @Test fun `a failing write does not stop the writer`() {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val w = SerialWriter(scope)
        val done = CopyOnWriteArrayList<String>()
        w.submit { error("disk") }
        w.submit { done.add("after") }
        val end = System.currentTimeMillis() + 3000
        while (done.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(listOf("after"), done.toList())
    }
}
