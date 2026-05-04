package com.kohei.summaryrecorder.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.ChunkStatus
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
@Config(sdk = [31])
class MainScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun mockViewModel(initialState: MainViewModel.UiState = MainViewModel.UiState()): MainViewModel {
        val viewModel = mockk<MainViewModel>(relaxed = true)
        val stateFlow = MutableStateFlow(initialState)
        every { viewModel.uiState } returns stateFlow.asStateFlow()
        return viewModel
    }

    @Test
    fun `shows start button initially`() {
        val viewModel = mockViewModel()

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("録音開始").assertIsDisplayed()
    }

    @Test
    fun `button shows stop when recording`() {
        val viewModel = mockViewModel(
            MainViewModel.UiState(isRecording = true)
        )

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("録音停止").assertIsDisplayed()
    }

    @Test
    fun `shows chunk status list`() {
        val chunks = listOf(
            MainViewModel.ChunkUiItem(index = 0, status = ChunkStatus.DONE, transcription = null),
            MainViewModel.ChunkUiItem(index = 1, status = ChunkStatus.UPLOADING, transcription = null)
        )
        val viewModel = mockViewModel(
            MainViewModel.UiState(chunks = chunks)
        )

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("チャンク 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("完了").assertIsDisplayed()
        composeTestRule.onNodeWithText("送信中").assertIsDisplayed()
    }

    @Test
    fun `shows summary card`() {
        val viewModel = mockViewModel(
            MainViewModel.UiState(summary = "要約テキスト")
        )

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("要約テキスト").assertIsDisplayed()
    }

    @Test
    fun `shows loading indicator`() {
        val viewModel = mockViewModel(
            MainViewModel.UiState(isLoading = true)
        )

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("文字起こし完了待ち...").assertIsDisplayed()
    }

    @Test
    fun `shows error card`() {
        val viewModel = mockViewModel(
            MainViewModel.UiState(error = "API error")
        )

        composeTestRule.setContent {
            MainScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("API error").assertIsDisplayed()
    }
}
