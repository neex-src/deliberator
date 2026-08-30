package com.neex.tellij.java

import com.intellij.psi.PsiMethod
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.tellij.java.endpoint.DtoShapeWalker
import com.neex.tellij.java.endpoint.EndpointResolver
import com.neex.tellij.java.endpoint.EndpointText
import com.neex.tellij.java.endpoint.ParamWhere
import com.neex.tellij.spi.StepError

class EndpointResolverTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST

    override fun setUp() {
        super.setUp()
        SpringStubs.project(myFixture)
        myFixture.addClass(SpringStubs.ORDERS_CONTROLLER)
    }

    private fun method(name: String): PsiMethod {
        val cls = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass("com.acme.api.OrdersController", com.intellij.psi.search.GlobalSearchScope.allScope(project))!!
        return cls.findMethodsByName(name, false).first { it.parameterList.parametersCount > 0 || name == "helper" }
    }

    fun testCreateMergesInterfaceAnnotations() {
        val e = EndpointResolver.resolve(method("create"))!!
        assertEquals("POST /api/orders", e.title)
        assertEquals("Create an order", e.summary)
        assertEquals("Stores the order with status NEW and returns it.", e.description)
        assertEquals(listOf("orders"), e.tags)
        assertEquals(listOf(ParamWhere.BODY to "request", ParamWhere.HEADER to "X-Trace"), e.params.map { it.where to it.name })
        assertTrue(e.params[0].required); assertEquals("The order to create", e.params[0].description)
        assertFalse(e.params[1].required); assertEquals("Trace id", e.params[1].description)
        assertEquals(listOf(201 to "OrderResponse", 400 to null), e.responses.map { it.status to it.type })
        assertEquals("Created", e.responses[0].description)
        assertEquals("OrdersController.create ← OrdersApi", e.impl.display)
        assertEquals("com.acme.api.OrdersController", e.impl.classFqn)
        assertTrue(e.impl.firstStatementLine > e.impl.line)
        val body = e.body!!
        assertEquals("CreateOrderRequest", body.type)
        assertEquals(listOf("orderId", "customer", "amount", "items", "gift_note"), body.fields.map { it.name })
        assertTrue(body.fields[0].required && body.fields[2].required && !body.fields[3].required)
        assertEquals("Client-chosen id (e.g. ord-1)", body.fields[0].description)
        assertEquals(listOf("> 0"), body.fields[2].constraints)
        assertEquals("List<Item>", body.fields[3].type)
        assertEquals(listOf("sku", "qty"), body.fields[3].nested!!.fields.map { it.name })
        assertEquals(listOf("≥ 1"), body.fields[3].nested!!.fields[1].constraints)
        val text = EndpointText.render(e)
        assertEquals("POST /api/orders → 201 OrderResponse", text[0])
        assertTrue(text.toString(), text.any { it.startsWith("  body") && it.contains("CreateOrderRequest") && it.contains("required") && it.contains("The order to create") })
        assertTrue(text.toString(), text.any { it.trim().startsWith("sku") && it.contains("required") })
    }

    fun testGetDeletePayAndImplOverride() {
        val get = EndpointResolver.resolve(method("get"))!!
        assertEquals("GET /api/orders/{id} → 200 OrderResponse", EndpointText.headline(get))
        assertEquals(listOf(ParamWhere.PATH to "id", ParamWhere.QUERY to "verbose"), get.params.map { it.where to it.name })
        assertEquals("false", get.params[1].default); assertFalse(get.params[1].required)
        assertEquals("The order id", get.params[0].description)
        val shape = get.body ?: DtoShapeWalker.walk(method("get").returnType!!)!!
        assertEquals(listOf("orderId", "status", "lines", "self"), shape.fields.map { it.name })
        assertEquals("Status(NEW|PAID)", shape.fields[1].type)
        assertEquals(listOf("sku", "qty"), shape.fields[2].nested!!.fields.map { it.name })
        assertNull("cycle guard", shape.fields[3].nested?.fields?.get(3)?.nested?.fields?.get(3)?.nested)

        val del = EndpointResolver.resolve(method("delete"))!!
        assertEquals("DELETE /api/orders/{id} → 204", EndpointText.headline(del))
        val pay = EndpointResolver.resolve(method("pay"))!!
        assertEquals("PUT /api/orders/{id}/pay", pay.title)
        assertEquals(listOf(ParamWhere.PATH, ParamWhere.QUERY), pay.params.map { it.where })
        val bulk = EndpointResolver.resolve(method("bulk"))!!
        assertEquals("POST /bulk", bulk.title)
        assertNull(EndpointResolver.resolve(method("helper")))
    }

    fun testFindMethod() {
        assertEquals("create", EndpointResolver.findMethod(project, "OrdersController.create", null).name)
        assertEquals("get", EndpointResolver.findMethod(project, "com.acme.api.OrdersController.get", null).name)
        try { EndpointResolver.findMethod(project, "Nope.x", null); fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("no class 'Nope'")) }
        try { EndpointResolver.findMethod(project, "OrdersController.bulk", null); fail() } catch (e: StepError) { assertTrue(e.hint!!.contains("bulk(CreateOrderRequest) | bulk(String)")) }
        assertEquals("String", EndpointResolver.findMethod(project, "OrdersController.bulk", "bulk(String)").parameterList.parameters[0].type.presentableText)
        try { EndpointResolver.findMethod(project, "OrdersController.nope", null); fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("no method 'nope'")) }
    }
}
