package com.nike.todoservice

import com.nike.riposte.server.Server
import io.restassured.RestAssured.given
import io.restassured.http.ContentType.JSON
import io.restassured.specification.RequestSpecification
import org.junit.Assert.assertEquals
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.IOException
import java.net.ServerSocket

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TodoEndpointTest {
    companion object {
        data class TodoRecord(val id: Long?, val name: String, val task: String)
        val path = "/todos"
        val pathWithParam = "/todos/{todoId}"
        val postPayload = "{ \"name\": \"TaskOne\", \"task\" : \"My first task\" }"
        val putPayload : (Long) -> String = { "{\"id\" : $it,  \"name\": \"TaskTwo\", \"task\" : \"My New task\" }"}
        private var server: Server? = null
        private var serverConfig: AppServerConfigForTesting? = null
        var todoIdCreated: Long = 0

        @Throws(IOException::class)
        private fun findFreePort(): Int {
            ServerSocket(0).use { serverSocket -> return serverSocket.localPort }
        }

        @BeforeClass @JvmStatic fun setup() {
            serverConfig = AppServerConfigForTesting(findFreePort())
            server = Server(serverConfig!!)
            server!!.startup()
        }

        @AfterClass @JvmStatic fun teardown() {
            server!!.shutdown()
        }
    }


    @Test
    fun `1 A TODO Item is created successfully`() {
        val response =
                given()
                    .port(serverConfig!!.endpointsPort())
                    .request().contentType("application/json")
                    .body(postPayload)
                .When()
                    .post(path)
                .then()
                    .statusCode(201)
                    .contentType(JSON)
                    .extract()

        val todoRecord = response.`as`(TodoRecord::class.java)
        todoIdCreated = todoRecord.id?:0

        assertEquals("TaskOne", todoRecord.name)
        assertEquals("My first task", todoRecord.task)
    }


    @Test
    fun `2 A TODO Item can be queried successfully`() {
        val response =
                given()
                    .port(serverConfig!!.endpointsPort())
                    .request()
                    .contentType("application/json")
                .When()
                    .pathParam("todoId", todoIdCreated)
                    .get(pathWithParam )
                .then()
                    .statusCode(200)
                    .contentType(JSON).extract()

        val todoRecord = response.`as`(TodoRecord::class.java)
        assertEquals("TaskOne", todoRecord.name)
        assertEquals("My first task", todoRecord.task)
    }


    @Test
    fun `3 A TODO Item can be updated`() {
        val response =
                given()
                        .port(serverConfig!!.endpointsPort())
                        .request().contentType("application/json")
                .When()
                        .pathParam("todoId", todoIdCreated)
                        .body(putPayload(todoIdCreated))
                        .put(pathWithParam )
                .then()
                        .statusCode(200).contentType(JSON).extract()

        val todoRecord = response.`as`(TodoRecord::class.java)
        assertEquals("TaskTwo", todoRecord.name)
        assertEquals("My New task", todoRecord.task)
    }


    @Test
    fun `4 A TODO Item can be deleted`() {
        given()
                .port(serverConfig!!.endpointsPort())
                .request().contentType("application/json")
        .When()
                .pathParam("todoId", todoIdCreated)
                .delete(pathWithParam )
        .then()
                .statusCode(204)
                .contentType(JSON)
    }

    //https://github.com/rest-assured/rest-assured/wiki/Usage#kotlin
    fun RequestSpecification.When(): RequestSpecification {
        return this.`when`()
    }


    class AppServerConfigForTesting(private val port: Int) : AppServerConfig() {
        override fun endpointsPort(): Int {
            return port
        }
    }
}
