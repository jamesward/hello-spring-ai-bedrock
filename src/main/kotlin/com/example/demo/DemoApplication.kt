package com.example.demo

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConfiguration


@SpringBootApplication
class DemoApplication(chatClientBuilder: ChatClient.Builder) {
    val chatClient = chatClientBuilder.build()

    /*
    A sample guardrail denies input topics and words including python.
     */
    @Bean
    fun commandLineRunner() = CommandLineRunner {
        val questionAboutPython = "should I use Python for AI Agents?"
        println("Asking: $questionAboutPython")
        val respBlocked = chatClient.prompt().user(questionAboutPython).call()
        println("Guarded finish reason: ${respBlocked.chatResponse()?.result?.metadata?.finishReason}")
        // trying to read respBlocked.content() will throw an exception

        val questionAboutJava = "should I use Java for AI Agents?"
        println("Asking: $questionAboutJava")
        val respAllowed = chatClient.prompt().user(questionAboutJava).call()
        println("Response: ${respAllowed.content()}")
    }
}

@Configuration
class BedrockGuardrailConfiguration(
    @param:Value($$"${spring.ai.bedrock.guardrail.id}")
    private val guardrailId: String,
    @param:Value($$"${spring.ai.bedrock.guardrail.version}")
    private val guardrailVersion: String,
    private val credentialsProvider: AwsCredentialsProvider,
) {

    // create our own BedrockRuntimeClient with a guardrail interceptor
    // this overrides the default BedrockRuntimeClient created in BedrockProxyChatModel
    @Bean
    fun bedrockRuntimeClient(): BedrockRuntimeClient {
        val region = DefaultAwsRegionProviderChain.builder().build().region

        val bedrockRuntimeClient = BedrockRuntimeClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()

        return GuardrailBedrockClient(bedrockRuntimeClient, guardrailId, guardrailVersion)
    }
}

// from: https://github.com/spring-projects/spring-ai/issues/1081
// intercepts the converse call and adds the configured guardrail
internal class GuardrailBedrockClient(
    private val instance: BedrockRuntimeClient,
    private val guardrailId: String,
    private val guardrailVersion: String,
) : BedrockRuntimeClient {
    override fun close() {
        instance.close()
    }

    override fun serviceName(): String? {
        return instance.serviceName()
    }

    override fun converse(converseRequest: ConverseRequest): ConverseResponse? {
        val newRequest = converseRequest.toBuilder()
            .guardrailConfig(
                GuardrailConfiguration.builder()
                    .guardrailIdentifier(guardrailId)
                    .guardrailVersion(guardrailVersion)
                    .build()
            )
            .build()

        return instance.converse(newRequest)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
