package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("بو لايت", appName)
  }

  @Test
  fun `main view model initializes safely`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = MainViewModel(application)
    org.junit.Assert.assertNotNull(viewModel.detectedGroupName.value)
    org.junit.Assert.assertNotNull(viewModel.postRepository)
  }
}
