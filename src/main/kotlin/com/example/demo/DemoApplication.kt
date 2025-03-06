package com.example.demo

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class DemoApplication(@Autowired val chatClientBuilder: ChatClient.Builder) {

    val chatClient = chatClientBuilder.build()

    @Bean
    fun commandLineRunner() = CommandLineRunner {
        val resp = chatClient.prompt().user("say hello").call().content()
        println(resp)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
