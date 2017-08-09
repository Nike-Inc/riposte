package com.nike.todoservice

import com.github.amitsk.roomratecalculator.endpoints.TodoItemsEndpoint
import com.nike.riposte.server.Server
import com.nike.riposte.server.config.ServerConfig
import com.nike.riposte.server.http.Endpoint
import com.nike.riposte.server.logging.AccessLogger

object Main {
    open class AppServerConfig : ServerConfig {
        private val endpoints = setOf<Endpoint<*>>(
                TodoItemsEndpoint.Get(),
                TodoItemsEndpoint.Post(),
                TodoItemsEndpoint.Put(),
                TodoItemsEndpoint.Delete()
        )
        private val accessLogger = AccessLogger()

        override fun appEndpoints(): Collection<Endpoint<*>> {
            return endpoints
        }

        override fun accessLogger(): AccessLogger {
            return accessLogger
        }
    }

    @Throws(Exception::class)
    @JvmStatic fun main(args: Array<String>) {
        val server = Server(AppServerConfig())
        server.startup()
    }
}
