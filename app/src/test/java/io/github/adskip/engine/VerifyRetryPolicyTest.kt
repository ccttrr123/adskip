package io.github.adskip.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验空读重试策略（[VerifyRetryPolicy]）。
 *
 * 真机依据：2026-11-18 覆盖矩阵里云运动 2/2 次点击后 1000ms 读不到 root，
 * 旧逻辑直接不记结论；给一次 400ms 后的重试即可救回这类样本。
 */
class VerifyRetryPolicyTest {

    @Test
    fun `读不到界面且没重试过_应该重试`() {
        assertTrue(VerifyRetryPolicy.shouldRetry(rootReadable = false, retryCount = 0))
    }

    @Test
    fun `读得到界面_不重试`() {
        assertFalse(VerifyRetryPolicy.shouldRetry(rootReadable = true, retryCount = 0))
    }

    @Test
    fun `已经重试过一次_不再重试`() {
        assertFalse(VerifyRetryPolicy.shouldRetry(rootReadable = false, retryCount = 1))
        assertFalse(VerifyRetryPolicy.shouldRetry(rootReadable = false, retryCount = 2))
    }
}
