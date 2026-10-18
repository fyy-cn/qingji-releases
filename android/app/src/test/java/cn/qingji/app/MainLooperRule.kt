package cn.qingji.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Robolectric replaces its looper between tests; do not reuse a cached Android main handler. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainLooperRule : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher()) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
