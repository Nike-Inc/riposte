package com.nike.todoservice

import com.nike.backstopper.handler.riposte.config.BackstopperRiposteConfigHelper.defaultErrorHandler
import com.nike.backstopper.handler.riposte.config.BackstopperRiposteConfigHelper.defaultUnhandledErrorHandler
import com.nike.riposte.server.Server
import com.nike.riposte.server.config.ServerConfig
import com.nike.riposte.server.error.handler.RiposteErrorHandler
import com.nike.riposte.server.error.handler.RiposteUnhandledErrorHandler
import com.nike.riposte.server.http.Endpoint
import com.nike.riposte.server.logging.AccessLogger
import com.nike.todoservice.endpoints.TodoItemsEndpoint
import com.nike.todoservice.error.ProjectApiErrorsImpl
import com.nike.backstopper.handler.ApiExceptionHandlerUtils.DEFAULT_IMPL as DEFAULT_API_EXCEPTION_HANDLER_UTILS

fun main(args : Array<String>) {
    val server = Server(AppServerConfig())
    server.startup()
}

open class AppServerConfig : ServerConfig {
    private val endpoints = setOf<Endpoint<*>>(
            TodoItemsEndpoint.Get(),
            TodoItemsEndpoint.Post(),
            TodoItemsEndpoint.Put(),
            TodoItemsEndpoint.Delete()
    )
    private val accessLogger = AccessLogger()
    private val projectApiErrors = ProjectApiErrorsImpl()

    override fun appEndpoints(): Collection<Endpoint<*>> {
        return endpoints
    }

    override fun accessLogger(): AccessLogger {
        return accessLogger
    }

    override fun riposteErrorHandler(): RiposteErrorHandler {
        return defaultErrorHandler(projectApiErrors, DEFAULT_API_EXCEPTION_HANDLER_UTILS)
    }

    override fun riposteUnhandledErrorHandler(): RiposteUnhandledErrorHandler {
        return defaultUnhandledErrorHandler(projectApiErrors, DEFAULT_API_EXCEPTION_HANDLER_UTILS)
    }
}
