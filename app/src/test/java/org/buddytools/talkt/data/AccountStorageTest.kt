package org.buddytools.talkt.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountStorageTest {
    @Test fun keepsGuestArchiveAndSeparatesUsers() {
        assertEquals("", accountStorageSuffix(null))
        assertEquals(accountStorageSuffix("user_alice"), accountStorageSuffix("user_alice"))
        assertNotEquals(accountStorageSuffix("user_alice"), accountStorageSuffix("user_bob"))
        assertTrue(accountStorageSuffix("../../user").matches(Regex("[0-9a-f]{64}")))
    }
}
