package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

private class WookieAdvisor : BaseAdvisor {

    private val logger = LoggerFactory.getLogger(WookieAdvisor::class.java)

    override fun before(request: ChatClientRequest, chain: AdvisorChain): ChatClientRequest =
        request.mutate()
            .prompt(request.prompt().augmentSystemMessage("speak like a wookie"))
            .build()

    override fun after(response: ChatClientResponse, chain: AdvisorChain): ChatClientResponse {
        logger.info("LLM response metadata: {}", response.chatResponse()?.metadata)
        return response
    }

    override fun getOrder() = 0
}

@SpringBootApplication
class DemoApplication(chatClientBuilder: ChatClient.Builder) {

    val chatClient = chatClientBuilder
        .defaultAdvisors(WookieAdvisor())
        .build()

    @Bean
    fun commandLineRunner() = CommandLineRunner {
        val resp = chatClient.prompt().user("say hello").call().content()
        println(resp)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
