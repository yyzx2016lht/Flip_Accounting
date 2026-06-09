package com.taostudio.tapaccounting.chat.agent

import android.content.Intent

sealed class UiAction {
    data class Navigate(val intent: Intent) : UiAction()
    data class ShowToast(val message: String) : UiAction()
    data class ShowDialog(val title: String, val message: String) : UiAction()
    data class UpdateUi(val key: String, val value: Any) : UiAction()
}
