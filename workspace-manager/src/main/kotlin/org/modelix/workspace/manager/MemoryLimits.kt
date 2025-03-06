package org.modelix.workspace.manager

import java.math.BigDecimal

val BASE_IMAGE_MAX_HEAP_SIZE_MEGA: Int = 1024

fun heapSizeFromContainerLimit(containerLimit: BigDecimal): BigDecimal {
    // https://eclipse.dev/openj9/docs/xxusecontainersupport/
    return if (containerLimit < (1024L * 1024L * 1024L).toBigDecimal()) {
        containerLimit / 2.toBigDecimal()
    } else if (containerLimit < (2L * 1024L * 1024L * 1024L).toBigDecimal()) {
        containerLimit - (512L * 1024L * 1024L).toBigDecimal()
    } else {
        containerLimit * 0.75.toBigDecimal()
    }
}

fun containerLimitFromHeapSize(heapSize: BigDecimal): BigDecimal {
    return if (heapSize < (512L * 1024L * 1024L).toBigDecimal()) {
        heapSize * 2.toBigDecimal()
    } else if (heapSize < ((2L * 1024L - 512L) * 1024L * 1024L).toBigDecimal()) {
        heapSize + (512L * 1024L * 1024L).toBigDecimal()
    } else {
        heapSize / 0.75.toBigDecimal()
    }
}
