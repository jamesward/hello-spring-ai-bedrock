package com.example.demo

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Beans shared by both apps ([com.example.demo.filter.FilterApp] and [com.example.demo.synth.SynthApp]). */
@Configuration
class AppConfig {

    /** Tool-enabled client (tool calls are logged, responses truncated). */
    @Bean
    fun chatClient(toolCallbackProvider: ToolCallbackProvider, builder: ChatClient.Builder): ChatClient =
        builder.defaultTools(LoggingToolCallback.wrap(toolCallbackProvider)).build()

    /** Plain, tool-free client used to derive filters (Group A) and to plan/summarize (Group B). */
    @Bean
    fun secondaryChatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}
