package com.afwsamples.testdpc.policy.locktask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndclawAgentReadinessTest {

    @Test
    fun snapshot_prioritizesBlockingRequirementBeforeRecommendedOnes() {
        val snapshot = AndclawAgentReadiness.Snapshot(
            items = listOf(
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.ACCESSIBILITY,
                    granted = false,
                    detail = "未开启"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.FILE_ACCESS,
                    granted = true,
                    detail = "已开启"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.BATTERY_OPTIMIZATION,
                    granted = false,
                    detail = "未加入"
                )
            )
        )

        assertFalse(snapshot.readyForLaunch)
        assertEquals(
            AndclawAgentReadiness.Requirement.ACCESSIBILITY,
            snapshot.nextActionableItem()?.requirement
        )
        assertTrue(snapshot.summaryText.contains("核心权限已就绪 1/2"))
    }

    @Test
    fun snapshot_usesRecommendedItemAfterCoreRequirementsAreReady() {
        val snapshot = AndclawAgentReadiness.Snapshot(
            items = listOf(
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.ACCESSIBILITY,
                    granted = true,
                    detail = "已开启"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.FILE_ACCESS,
                    granted = true,
                    detail = "已开启"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.OVERLAY,
                    granted = true,
                    detail = "已开启"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.RUNTIME_PERMISSIONS,
                    granted = true,
                    detail = "已授权"
                ),
                AndclawAgentReadiness.Item(
                    requirement = AndclawAgentReadiness.Requirement.USAGE_STATS,
                    granted = false,
                    detail = "未开启"
                )
            )
        )

        assertTrue(snapshot.readyForLaunch)
        assertEquals(
            AndclawAgentReadiness.Requirement.USAGE_STATS,
            snapshot.nextActionableItem()?.requirement
        )
        assertTrue(snapshot.summaryText.contains("核心权限已就绪"))
        assertTrue(snapshot.detailText.contains("[ ] 使用情况访问"))
    }
}
