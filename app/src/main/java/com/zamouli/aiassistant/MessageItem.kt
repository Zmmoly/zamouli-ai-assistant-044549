package com.example.aiassistant

/**
 * Data class representing a chat message
 * @param text The content of the message
 * @param isUser Whether the message is from the user (true) or AI (false)
 */
data class MessageItem(
    val text: String,
    val isUser: Boolean
)
