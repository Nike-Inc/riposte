package com.github.amitsk.roomratecalculator.endpoints

import com.nike.backstopper.apierror.sample.SampleCoreApiError
import com.nike.backstopper.exception.ApiException
import com.nike.riposte.server.http.RequestInfo
import com.nike.riposte.server.http.ResponseInfo
import com.nike.riposte.server.http.StandardEndpoint
import com.nike.riposte.util.Matcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.amitsk.roomratecalculator.error.ProjectApiError

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


object TodoItemsEndpoint {
    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val mapper = ObjectMapper().registerKotlinModule()

    private val MATCHING_PATH = "/todos"
    private val MATCHING_PATH_WITH_ID = MATCHING_PATH + "/{id}"
    private val ID_PARAM = "id"

    private val COUNTER = AtomicLong()

    private val todos = ConcurrentHashMap<Long, TodoItem>()

    data class TodoItem(val id: Long?, val name: String, val task: String)

    class Get : StandardEndpoint<Void, TodoItem>() {

        override fun execute(request: RequestInfo<Void>,
                             longRunningTaskExecutor: Executor,
                             ctx: ChannelHandlerContext): CompletableFuture<ResponseInfo<TodoItem?>> {
            val key = request.getPathParam(ID_PARAM)?.toLong() ?: -1
            validateKeyExists(key)
            logger.info("Key {}  found and returned ", key)

            return CompletableFuture.completedFuture<ResponseInfo<TodoItem?>>(
                    ResponseInfo.newBuilder(todos[key]).build()
            )
        }

        override fun requestMatcher(): Matcher {
            return Matcher.match(MATCHING_PATH_WITH_ID, HttpMethod.GET)
        }
    }

    class Post : StandardEndpoint<TodoItem, TodoItem?>() {

        override fun customRequestContentDeserializer(request: RequestInfo<*>?): ObjectMapper {
            return mapper
        }

        override fun execute(
                request: RequestInfo<TodoItem>, longRunningTaskExecutor: Executor, ctx: ChannelHandlerContext
        ): CompletableFuture<ResponseInfo<TodoItem?>> {
            val contentTodoItem = request.content ?: throw ApiException(SampleCoreApiError.MISSING_EXPECTED_CONTENT)
            val key = COUNTER.incrementAndGet()
            val responseTodoItem = contentTodoItem.copy(id = key)
            logger.info("Key {}  Created and returned ", key)
            todos.put(key, responseTodoItem)

            return CompletableFuture.completedFuture<ResponseInfo<TodoItem?>>(
                    ResponseInfo.newBuilder(todos[key]).withHttpStatusCode(HttpResponseStatus.CREATED.code()).build()
            )
        }

        override fun requestMatcher(): Matcher {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST)
        }
    }

    class Put : StandardEndpoint<TodoItem, TodoItem?>() {

        override fun customRequestContentDeserializer(request: RequestInfo<*>?): ObjectMapper {
            return mapper
        }

        override fun execute(
                request: RequestInfo<TodoItem>, longRunningTaskExecutor: Executor, ctx: ChannelHandlerContext
        ): CompletableFuture<ResponseInfo<TodoItem?>> {

            val key = request.getPathParam(ID_PARAM)?.toLong() ?: -1
            val contentTodoItem = request.content ?:
                    throw ApiException(SampleCoreApiError.MISSING_EXPECTED_CONTENT)
            validateKeyExists(key)
            todos.put(key, contentTodoItem)
            logger.info("Key {}  Updated and returned ", key)
            return CompletableFuture.completedFuture<ResponseInfo<TodoItem?>>(
                    ResponseInfo.newBuilder(todos[key]).withHttpStatusCode(HttpResponseStatus.OK.code()).build()
            )
        }

        override fun requestMatcher(): Matcher {
            return Matcher.match(MATCHING_PATH_WITH_ID, HttpMethod.PUT)
        }
    }

    class Delete : StandardEndpoint<Void, Void>() {
        override fun execute(
                request: RequestInfo<Void>, longRunningTaskExecutor: Executor, ctx: ChannelHandlerContext
        ): CompletableFuture<ResponseInfo<Void>> {
            val key = request.getPathParam(ID_PARAM)?.toLong() ?: -1
            validateKeyExists(key)

            todos.remove(key)
            logger.info(" Item deleted")

            return CompletableFuture.completedFuture<ResponseInfo<Void>>(
                    ResponseInfo.newBuilder<Void>().withHttpStatusCode(HttpResponseStatus.NO_CONTENT.code()).build()
            )
        }

        override fun requestMatcher(): Matcher {
            return Matcher.match(MATCHING_PATH_WITH_ID, HttpMethod.DELETE)
        }
    }

    private fun validateKeyExists(key: Long) {
        if (!todos.containsKey(key)) {
            throw ApiException.newBuilder()
                    .withExceptionMessage("Key not found")
                    .withApiErrors(ProjectApiError.TODO_ITEM_NOT_FOUND_1)
                    .build()
            logger.info("Key {} Not found ", key)
        }
    }
}
