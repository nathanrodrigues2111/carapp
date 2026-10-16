package com.lazyneil.lazycar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncTest {
    @Test fun freshInstallAdoptsPeer() {
        assertTrue(remoteWins(remoteReal = true, remoteTs = 5, localReal = false, localTs = 0, localIsTablet = false))
        assertTrue(remoteWins(true, 0, false, 0, true))          // peer value never stamped, still real
    }
    @Test fun defaultNeverOverwritesReal() {
        assertFalse(remoteWins(remoteReal = false, remoteTs = 0, localReal = true, localTs = 0, localIsTablet = true))
        assertFalse(remoteWins(false, 0, false, 0, false))
    }
    @Test fun lastWriterWinsAfterThat() {
        assertTrue(remoteWins(true, 20, true, 10, true))
        assertFalse(remoteWins(true, 10, true, 20, false))
    }
    @Test fun tieGoesToTablet() {
        assertTrue(remoteWins(true, 0, true, 0, localIsTablet = false))
        assertFalse(remoteWins(true, 0, true, 0, localIsTablet = true))
    }
}
