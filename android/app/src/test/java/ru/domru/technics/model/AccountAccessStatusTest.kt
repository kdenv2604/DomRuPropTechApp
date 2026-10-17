package ru.domru.technics.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAccessStatusTest {
    @Test
    fun onlyConfirmedLostLoginAllowsRemovalOrNewPassword() {
        AccountAccessStatus.entries.forEach { status ->
            if (status == AccountAccessStatus.NEEDS_PASSWORD) {
                assertTrue(status.allowsAccountRecoveryActions())
            } else {
                assertFalse(status.allowsAccountRecoveryActions())
            }
        }
    }
}
