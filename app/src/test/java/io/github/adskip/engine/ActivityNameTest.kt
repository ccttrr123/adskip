package io.github.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Activity 名识别的回归测试。
 *
 * 其中 `android.widget.FrameLayout` 和 `org.qiyi.android.video.MainActivity`
 * 都直接来自 爱奇艺 的真机日志。
 */
class ActivityNameTest {

    @Test
    fun `真机样本_控件类名不是 Activity`() {
        // 爱奇艺 的弹窗/浮层窗口上报的就是这个
        assertFalse(ActivityName.isActivityLike("android.widget.FrameLayout"))
        assertFalse(ActivityName.isActivityLike("android.widget.TextView"))
        assertFalse(ActivityName.isActivityLike("android.view.View"))
        assertFalse(ActivityName.isActivityLike("androidx.recyclerview.widget.RecyclerView"))
        assertFalse(ActivityName.isActivityLike("com.android.internal.policy.DecorView"))
    }

    @Test
    fun `真机样本_真正的 Activity 名要认出来`() {
        assertTrue(ActivityName.isActivityLike("org.qiyi.android.video.MainActivity"))
        assertTrue(ActivityName.isActivityLike("tv.danmaku.bili.MainActivityV2"))
        assertTrue(ActivityName.isActivityLike("com.taobao.idlefish.SplashActivity"))
        assertTrue(ActivityName.isActivityLike("com.jingdong.app.mall.MainFrameActivity"))
    }

    @Test
    fun `前缀判断不能误杀包名里含 android 的应用`() {
        // 这一条是专门防"用包含判断"这个常见错误的
        assertTrue(ActivityName.isActivityLike("org.qiyi.android.video.MainActivity"))
        assertTrue(ActivityName.isActivityLike("com.example.androidclient.MainActivity"))
    }

    @Test
    fun `各种非法输入`() {
        assertFalse(ActivityName.isActivityLike(null))
        assertFalse(ActivityName.isActivityLike(""))
        assertFalse(ActivityName.isActivityLike("   "))
        // 没有包名的类名不可能是全限定名
        assertFalse(ActivityName.isActivityLike("MainActivity"))
        // 内部类/匿名类
        assertFalse(ActivityName.isActivityLike("com.example.MainActivity\$1"))
        // 以控件后缀结尾的类名
        assertFalse(ActivityName.isActivityLike("com.example.widget.AdLayout"))
        assertFalse(ActivityName.isActivityLike("com.example.widget.PromoView"))
        assertFalse(ActivityName.isActivityLike("com.example.PopupWindow"))
    }

    @Test
    fun `extractOrNull 合法时返回类名_非法时返回 null`() {
        assertEquals(
            "org.qiyi.android.video.MainActivity",
            ActivityName.extractOrNull("  org.qiyi.android.video.MainActivity  ")
        )
        assertNull(ActivityName.extractOrNull("android.widget.FrameLayout"))
        assertNull(ActivityName.extractOrNull(null))
    }
}
