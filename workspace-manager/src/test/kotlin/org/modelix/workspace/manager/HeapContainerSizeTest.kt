package org.modelix.workspace.manager

import kotlin.test.Test
import kotlin.test.assertEquals

class HeapContainerSizeTest {

    @Test fun heap100() = runTest(100, 200)

    @Test fun heap200() = runTest(200, 400)

    @Test fun heap511() = runTest(511, 1022)

    @Test fun heap512() = runTest(512, 1024)

    @Test fun heap513() = runTest(513, 1025)

    @Test fun heap768() = runTest(768, 1280)

    @Test fun heap1024() = runTest(1024, 1536)

    @Test fun heap1500() = runTest(1500, 2012)

    @Test fun heap2100() = runTest(2100, 2800)

    fun runTest(heapSizeMega: Long, containerSizeMega: Long) {
        assertEquals(
            containerSizeMega.toBigInteger(),
            containerLimitFromHeapSize((heapSizeMega * 1024L * 1024L).toBigDecimal()).toBigInteger() / (1024L * 1024L).toBigInteger(),
        )
        assertEquals(
            heapSizeMega.toBigInteger(),
            heapSizeFromContainerLimit((containerSizeMega * 1024L * 1024L).toBigDecimal()).toBigInteger() / (1024L * 1024L).toBigInteger(),
        )
        assertEquals(
            (heapSizeMega * 1024L * 1024L).toBigInteger(),
            heapSizeFromContainerLimit(containerLimitFromHeapSize((heapSizeMega * 1024L * 1024L).toBigDecimal())).toBigInteger(),
        )
        assertEquals(
            (containerSizeMega * 1024L * 1024L).toBigInteger(),
            containerLimitFromHeapSize(heapSizeFromContainerLimit((containerSizeMega * 1024L * 1024L).toBigDecimal())).toBigInteger(),
        )
    }
}
