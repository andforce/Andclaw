package com.afwsamples.testdpc.policy.locktask

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupKioskModeActivityTest {
    @Test
    fun requiresManageAllFilesAccessPermission_android9_returnsFalse() {
        assertThat(requiresManageAllFilesAccessPermission(28)).isFalse()
    }

    @Test
    fun requiresManageAllFilesAccessPermission_android11_returnsTrue() {
        assertThat(requiresManageAllFilesAccessPermission(30)).isTrue()
    }
}
