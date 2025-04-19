package org.modelix.workspace.manager

import io.kubernetes.client.custom.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals

class QuantityArithmeticTest {

    @Test
    fun `add same unit`() {
        assertEquals(Quantity.fromString("5Gi"), Quantity.fromString("2Gi") + Quantity.fromString("3Gi"))
    }

    @Test
    fun `add different unit`() {
        assertEquals(Quantity.fromString("2065Mi"), (Quantity.fromString("2Gi") + Quantity.fromString("17Mi")))
    }
}
