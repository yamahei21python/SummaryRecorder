package com.kohei.summaryrecorder.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.viewmodel.MainViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainScreenStateTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val mutableState = MutableStateFlow(MainViewModel.UiState())

    private fun mockViewModelWithFlow(): MainViewModel {
        val viewModel = mockk<MainViewModel>(relaxed = true)
        every { viewModel.uiState } returns mutableState.asStateFlow()
        return viewModel
    }

    @Test
    fun `state change updates button text`() {
        val viewModel = mockViewModelWithFlow()

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("録音開始").assertIsDisplayed()

        mutableState.value = mutableState.value.copy(isRecording = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("録音停止").assertIsDisplayed()
    }

    @Test
    fun `state change shows loading`() {
        val viewModel = mockViewModelWithFlow()

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("文字起こし完了待ち...").assertDoesNotExist()

        mutableState.value = mutableState.value.copy(isLoading = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("文字起こし完了待ち...").assertIsDisplayed()
    }

    @Test
    fun `state change shows summary`() {
        val viewModel = mockViewModelWithFlow()

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("テスト要約").assertDoesNotExist()

        mutableState.value = mutableState.value.copy(summary = "テスト要約")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("テスト要約").assertIsDisplayed()
    }

    @Test
    fun `state change shows error`() {
        val viewModel = mockViewModelWithFlow()

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("エラー発生").assertDoesNotExist()

        mutableState.value = mutableState.value.copy(error = "エラー発生")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("エラー発生").assertIsDisplayed()
    }
}
