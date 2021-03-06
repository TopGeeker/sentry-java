package io.sentry

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.anyOrNull
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.mockingDetails
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.whenever
import io.sentry.hints.ApplyScopeData
import io.sentry.hints.Cached
import io.sentry.hints.DiskFlushNotification
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Request
import io.sentry.protocol.SdkVersion
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryId
import io.sentry.protocol.User
import io.sentry.transport.AsyncConnection
import io.sentry.transport.HttpTransport
import io.sentry.transport.ITransportGate
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.io.ByteArrayInputStream
import java.lang.RuntimeException
import java.net.URL
import java.nio.charset.Charset
import java.util.Arrays
import java.util.UUID
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryClientTest {

    class Fixture {
        var sentryOptions: SentryOptions = SentryOptions().apply {
            dsn = dsnString
            sdkVersion = SdkVersion().apply {
                name = "test"
                version = "1.2.3"
            }
            isDebug = true
            setDiagnosticLevel(SentryLevel.DEBUG)
            setSerializer(GsonSerializer(mock(), envelopeReader))
        }
        var connection: AsyncConnection = mock()

        fun getSut() = SentryClient(sentryOptions, connection)
    }

    private val fixture = Fixture()

    @Test
    fun `when fixture is unchanged, client is enabled`() {
        val sut = fixture.getSut()
        assertTrue(sut.isEnabled)
    }

    @Test
    @Ignore("Not implemented")
    fun `when dsn is an invalid string, client is disabled`() {
        fixture.sentryOptions.dsn = "invalid-dsn"
        val sut = fixture.getSut()
        assertFalse(sut.isEnabled)
    }

    @Test
    fun `when dsn is an invalid string, client throws`() {
        fixture.sentryOptions.dsn = "invalid-dsn"
        assertFailsWith<InvalidDsnException> { fixture.getSut() }
    }

    @Test
    @Ignore("Not implemented")
    fun `when dsn is null, client is disabled`() {
        fixture.sentryOptions.dsn = null
        val sut = fixture.getSut()
        assertFalse(sut.isEnabled)
    }

    @Test
    fun `when dsn without private key is valid, client is enabled`() {
        fixture.sentryOptions.dsn = dsnString
        val sut = fixture.getSut()
        assertTrue(sut.isEnabled)
    }

    @Test
    fun `when dsn with secret is valid, client is enabled`() {
        fixture.sentryOptions.dsn = dsnStringLegacy
        val sut = fixture.getSut()
        assertTrue(sut.isEnabled)
    }

    @Test
    fun `when client is closed, client gets disabled`() {
        val sut = fixture.getSut()
        assertTrue(sut.isEnabled)
        sut.close()
        assertFalse(sut.isEnabled)
    }

    @Test
    fun `when beforeSend is set, callback is invoked`() {
        var invoked = false
        fixture.sentryOptions.setBeforeSend { e, _ -> invoked = true; e }
        val sut = fixture.getSut()
        sut.captureEvent(SentryEvent())
        assertTrue(invoked)
    }

    @Test
    fun `when beforeSend is returns null, event is dropped`() {
        fixture.sentryOptions.setBeforeSend { _: SentryEvent, _: Any? -> null }
        val sut = fixture.getSut()
        val event = SentryEvent()
        sut.captureEvent(event)
        verify(fixture.connection, never()).send(any())
    }

    @Test
    fun `when beforeSend is returns new instance, new instance is sent`() {
        val expected = SentryEvent().apply {
            setTag("test", "test")
        }
        fixture.sentryOptions.setBeforeSend { _, _ -> expected }
        val sut = fixture.getSut()
        val actual = SentryEvent()
        sut.captureEvent(actual)
        verify(fixture.connection).send(check {
            val event = getEventFromData(it.items.first().data)
            assertEquals("test", event.tags["test"])
        }, anyOrNull())
        verifyNoMoreInteractions(fixture.connection)
    }

    @Test
    fun `when beforeSend throws an exception, breadcrumb is added and event is sent`() {
        val exception = Exception("test")

        exception.stackTrace.toString()
        fixture.sentryOptions.setBeforeSend { _, _ -> throw exception }
        val sut = fixture.getSut()
        val actual = SentryEvent()
        sut.captureEvent(actual)

        assertEquals("test", actual.breadcrumbs.first().data["sentry:message"])
        assertEquals("SentryClient", actual.breadcrumbs.first().category)
        assertEquals(SentryLevel.ERROR, actual.breadcrumbs.first().level)
        assertEquals("BeforeSend callback failed.", actual.breadcrumbs.first().message)
    }

    @Test
    fun `when event captured with hint, hint passed to connection`() {
        val event = SentryEvent()
        fixture.sentryOptions.environment = "not to be applied"
        val sut = fixture.getSut()
        val expectedHint = Object()
        sut.captureEvent(event, expectedHint)
        verify(fixture.connection).send(any(), eq(expectedHint))
    }

    @Test
    fun `when captureMessage is called, sentry event contains formatted message`() {
        var sentEvent: SentryEvent? = null
        fixture.sentryOptions.setBeforeSend { e, _ -> sentEvent = e; e }
        val sut = fixture.getSut()
        val actual = "actual message"
        sut.captureMessage(actual, null)
        assertEquals(actual, sentEvent!!.message.formatted)
    }

    @Test
    fun `when captureMessage is called, sentry event contains level`() {
        var sentEvent: SentryEvent? = null
        fixture.sentryOptions.setBeforeSend { e, _ -> sentEvent = e; e }
        val sut = fixture.getSut()
        sut.captureMessage(null, SentryLevel.DEBUG)
        assertEquals(SentryLevel.DEBUG, sentEvent!!.level)
    }

    @Test
    fun `when event has release, value from options not applied`() {
        val event = SentryEvent()
        val expected = "original"
        fixture.sentryOptions.environment = "not to be applied"
        event.release = expected
        val sut = fixture.getSut()
        sut.captureEvent(event)
        assertEquals(expected, event.release)
    }

    @Test
    fun `when event doesn't have release, value from options applied`() {
        val event = SentryEvent()
        val expected = "original"
        fixture.sentryOptions.release = expected
        val sut = fixture.getSut()
        sut.captureEvent(event)
        assertEquals(expected, event.release)
    }

    @Test
    fun `when event has environment, value from options not applied`() {
        val event = SentryEvent()
        val expected = "original"
        fixture.sentryOptions.environment = "not to be applied"
        event.environment = expected
        val sut = fixture.getSut()
        sut.captureEvent(event)
        assertEquals(expected, event.environment)
    }

    @Test
    fun `when event doesn't have environment, value from options applied`() {
        val event = SentryEvent()
        val expected = "original"
        fixture.sentryOptions.environment = expected
        val sut = fixture.getSut()
        sut.captureEvent(event)
        assertEquals(expected, event.environment)
    }

    @Test
    fun `when captureEvent with scope, event should have its data if not set`() {
        val event = SentryEvent()
        val scope = createScope()

        val sut = fixture.getSut()

        sut.captureEvent(event, scope)
        assertEquals("message", event.breadcrumbs[0].message)
        assertEquals("extra", event.extras["extra"])
        assertEquals("tags", event.tags["tags"])
        assertEquals("fp", event.fingerprints[0])
        assertEquals("transaction", event.transaction)
        assertEquals("id", event.user.id)
        assertEquals(SentryLevel.FATAL, event.level)
    }

    @Test
    fun `when captureEvent with scope, event data has priority over scope but level and it should append extras, tags and breadcrumbs`() {
        val event = createEvent()

        val scope = createScope()

        val sut = fixture.getSut()

        sut.captureEvent(event, scope)

        // breadcrumbs are appending
        assertEquals("eventMessage", event.breadcrumbs[0].message)
        assertEquals("message", event.breadcrumbs[1].message)

        // extras are appending
        assertEquals("eventExtra", event.extras["eventExtra"])
        assertEquals("extra", event.extras["extra"])

        // tags are appending
        assertEquals("eventTag", event.tags["eventTag"])
        assertEquals("tags", event.tags["tags"])

        // fingerprint is replaced
        assertEquals("eventFp", event.fingerprints[0])
        assertEquals(1, event.fingerprints.size)

        assertEquals("eventTransaction", event.transaction)

        assertEquals("eventId", event.user.id)

        assertEquals(SentryLevel.FATAL, event.level)
    }

    @Test
    fun `when captureEvent with scope, event extras and tags are only append if key is absent`() {
        val event = createEvent()

        val scope = createScope()
        scope.setExtra("eventExtra", "extra")
        scope.setTag("eventTag", "tags")

        val sut = fixture.getSut()

        sut.captureEvent(event, scope)

        // extras are appending
        assertEquals("eventExtra", event.extras["eventExtra"])

        // tags are appending
        assertEquals("eventTag", event.tags["eventTag"])
    }

    @Test
    fun `when captureEvent with scope, event should have its level if set`() {
        val event = SentryEvent()
        event.level = SentryLevel.DEBUG
        val scope = createScope()

        val sut = fixture.getSut()

        sut.captureEvent(event, scope)
        assertEquals(SentryLevel.FATAL, event.level)
    }

    @Test
    fun `when captureEvent with sampling, some events not captured`() {
        fixture.sentryOptions.sampleRate = 0.000000001
        val sut = fixture.getSut()

        val allEvents = 10
        (0..allEvents).forEach { _ -> sut.captureEvent(SentryEvent()) }
        assertTrue(allEvents > mockingDetails(fixture.connection).invocations.size)
    }

    @Test
    fun `when captureEvent without sampling, all events are captured`() {
        fixture.sentryOptions.sampleRate = null
        val sut = fixture.getSut()

        val allEvents = 10
        (0..allEvents).forEach { _ -> sut.captureEvent(SentryEvent()) }
        assertEquals(allEvents, mockingDetails(fixture.connection).invocations.size - 1) // 1 extra invocation outside .send()
    }

    @Test
    fun `when captureUserFeedback, envelope is sent`() {
        val sut = fixture.getSut()

        sut.captureUserFeedback(userFeedback)

        verify(fixture.connection).send(check { actual ->
            assertEquals(userFeedback.eventId, actual.header.eventId)
            assertEquals(fixture.sentryOptions.sdkVersion, actual.header.sdkVersion)

            assertEquals(1, actual.items.count())
            val item = actual.items.first()
            assertEquals(SentryItemType.UserFeedback, item.header.type)
            assertEquals("application/json", item.header.contentType)

            assertEnvelopeItemDataForUserFeedback(item)
        })
    }

    private fun assertEnvelopeItemDataForUserFeedback(item: SentryEnvelopeItem) {
        val stream = ByteArrayOutputStream()
        val writer = stream.bufferedWriter(Charset.forName("UTF-8"))
        fixture.sentryOptions.serializer.serialize(userFeedback, writer)
        val expectedData = stream.toByteArray()
        assertTrue(Arrays.equals(expectedData, item.data))
    }

    @Test
    fun `when captureUserFeedback and connection throws, log exception`() {
        val sut = fixture.getSut()

        val exception = IOException("No connection")
        whenever(fixture.connection.send(any())).thenThrow(exception)

        val logger = mock<ILogger>()
        fixture.sentryOptions.setLogger(logger)

        sut.captureUserFeedback(userFeedback)

        verify(logger)
            .log(SentryLevel.WARNING, exception,
                "Capturing user feedback %s failed.", userFeedback.eventId);
    }

    @Test
    fun `when hint is Cached, scope is not applied`() {
        val sut = fixture.getSut()

        val event = SentryEvent()
        val scope = Scope(SentryOptions())
        scope.level = SentryLevel.FATAL
        sut.captureEvent(event, scope, mock<Cached>())

        assertNotEquals(scope.level, event.level)
    }

    @Test
    fun `when hint is not Cached, scope is applied`() {
        val sut = fixture.getSut()

        val event = SentryEvent()
        val scope = Scope(SentryOptions())
        scope.level = SentryLevel.FATAL
        sut.captureEvent(event, scope, Object())

        assertEquals(scope.level, event.level)
    }

    @Test
    fun `when hint is ApplyScopeData, scope is applied`() {
        val sut = fixture.getSut()

        val event = SentryEvent()
        val scope = Scope(SentryOptions())
        scope.level = SentryLevel.FATAL
        sut.captureEvent(event, scope, mock<ApplyScopeData>())

        assertEquals(scope.level, event.level)
    }

    @Test
    fun `when hint is Cached but also ApplyScopeData, scope is applied`() {
        val sut = fixture.getSut()

        val event = SentryEvent()
        val scope = Scope(SentryOptions())
        scope.level = SentryLevel.FATAL
        sut.captureEvent(event, scope, mock<CustomCachedApplyScopeDataHint>())

        assertEquals(scope.level, event.level)
    }

    @Test
    fun `when transport is NoOp, it should initialize it`() {
        fixture.getSut()
        assertTrue(fixture.sentryOptions.transport is HttpTransport)
    }

    @Test
    fun `when transport is set on options, it should use the custom transport`() {
        val sentryOptions: SentryOptions = SentryOptions().apply {
            dsn = dsnString
        }
        val transport = HttpTransport(sentryOptions, mock(), 500, 500, null, null, URL("https://key@sentry.io/proj"))
        sentryOptions.setTransport(transport)

        val connection = mock<AsyncConnection>()
        SentryClient(sentryOptions, connection)

        assertEquals(transport, sentryOptions.transport)
    }

    @Test
    fun `when transport gate is set on options, it should use the custom transport gate`() {
        val sentryOptions: SentryOptions = SentryOptions().apply {
            dsn = dsnString
        }
        val transportGate = CustomTransportGate()
        sentryOptions.setTransportGate(transportGate)

        val connection = mock<AsyncConnection>()
        SentryClient(sentryOptions, connection)

        assertEquals(transportGate, sentryOptions.transportGate)
    }

    @Test
    fun `when transport gate is null, it should init an always on transport gate`() {
        val sentryOptions: SentryOptions = SentryOptions().apply {
            dsn = dsnString
        }

        val connection = mock<AsyncConnection>()
        SentryClient(sentryOptions, connection)

        assertNotNull(sentryOptions.transportGate)
        assertTrue(sentryOptions.transportGate.isConnected)
    }

    @Test
    fun `when scope has event processors, they should be applied`() {
        val event = SentryEvent()
        val scope = createScope()
        val processor = mock<EventProcessor>()
        whenever(processor.process(any(), anyOrNull())).thenReturn(event)
        scope.addEventProcessor(processor)

        val sut = fixture.getSut()

        sut.captureEvent(event, scope)
        verify(processor).process(eq(event), anyOrNull())
    }

    @Test
    fun `when options have event processors, they should be applied`() {
        val processor = mock<EventProcessor>()
        fixture.sentryOptions.addEventProcessor(processor)

        val event = SentryEvent()

        fixture.getSut().captureEvent(event)
        verify(processor).process(eq(event), anyOrNull())
    }

    @Test
    fun `when captureSession and no release is set, do nothing`() {
        fixture.getSut().captureSession(createSession(""))
        verify(fixture.connection, never()).send(any<SentryEnvelope>())
    }

    @Test
    fun `when captureSession and release is set, send an envelope`() {
        fixture.getSut().captureSession(createSession())
        verify(fixture.connection).send(any<SentryEnvelope>(), anyOrNull())
    }

    @Test
    fun `when captureSession, sdkInfo should be in the envelope header`() {
        fixture.getSut().captureSession(createSession())
        verify(fixture.connection).send(check<SentryEnvelope> {
            assertNotNull(it.header.sdkVersion)
        }, anyOrNull())
    }

    @Test
    fun `when captureEnvelope and thres an exception, returns empty sentryId`() {
        whenever(fixture.connection.send(any<SentryEnvelope>(), anyOrNull())).thenThrow(IOException())

        val envelope = SentryEnvelope(SentryId(UUID.randomUUID()), null, setOf())
        val sentryId = fixture.getSut().captureEnvelope(envelope)
        assertEquals(SentryId.EMPTY_ID, sentryId)
    }

    @Test
    fun `when captureEnvelope and theres no exception, returns envelope header id`() {
        val expectedSentryId = SentryId(UUID.randomUUID())
        val envelope = SentryEnvelope(expectedSentryId, null, setOf())
        val sentryId = fixture.getSut().captureEnvelope(envelope)
        assertEquals(expectedSentryId, sentryId)
    }

    @Test
    fun `When event is non handled, mark session as Crashed`() {
        val scope = Scope(fixture.sentryOptions)
        scope.startSession().current
        val event = SentryEvent().apply {
            exceptions = createNonHandledException()
        }
        fixture.getSut().updateSessionData(event, null, scope)
        scope.withSession {
            assertEquals(Session.State.Crashed, it!!.status)
        }
    }

    @Test
    fun `When event is handled, keep level as it is`() {
        val scope = Scope(fixture.sentryOptions)
        val session = scope.startSession().current
        val level = session.status
        val event = SentryEvent()
        fixture.getSut().updateSessionData(event, null, scope)
        assertEquals(level, session.status)
    }

    @Test
    fun `When event is non handled, increase errorCount`() {
        val scope = Scope(fixture.sentryOptions)
        scope.startSession().current
        val event = SentryEvent().apply {
            exceptions = createNonHandledException()
        }
        fixture.getSut().updateSessionData(event, null, scope)
        scope.withSession {
            assertEquals(1, it!!.errorCount())
        }
    }

    @Test
    fun `When event is Errored, increase errorCount`() {
        val scope = Scope(fixture.sentryOptions)
        scope.startSession().current
        val exceptions = mutableListOf<SentryException>()
        exceptions.add(SentryException())
        val event = SentryEvent().apply {
            setExceptions(exceptions)
        }
        fixture.getSut().updateSessionData(event, null, scope)
        scope.withSession {
            assertEquals(1, it!!.errorCount())
        }
    }

    @Test
    fun `When event is handled and not errored, do not increase errorsCount`() {
        val scope = Scope(fixture.sentryOptions)
        val session = scope.startSession().current
        val errorCount = session.errorCount()
        val event = SentryEvent()
        fixture.getSut().updateSessionData(event, null, scope)
        assertEquals(errorCount, session.errorCount())
    }

    @Test
    fun `When event has userAgent, set it into session`() {
        val scope = Scope(fixture.sentryOptions)
        scope.startSession().current
        val event = SentryEvent().apply {
            request = Request()
            request.headers = mutableMapOf()
            request.headers["user-agent"] = "jamesBond"
        }
        fixture.getSut().updateSessionData(event, null, scope)
        scope.withSession {
            assertEquals("jamesBond", it!!.userAgent)
        }
    }

    @Test
    fun `When event has no userAgent, keep as it is`() {
        val scope = Scope(fixture.sentryOptions)
        val session = scope.startSession().current
        val userAgent = session.userAgent
        val event = SentryEvent().apply {
            request = Request()
            request.headers = mutableMapOf()
        }
        fixture.getSut().updateSessionData(event, null, scope)
        assertEquals(userAgent, session.userAgent)
    }

    @Test
    fun `When capture an event and there's no session, do nothing`() {
        val scope = Scope(fixture.sentryOptions)
        val event = SentryEvent()
        fixture.getSut().updateSessionData(event, null, scope)
        scope.withSession {
            assertNull(it)
        }
    }

    @Test
    fun `when captureEvent with sampling, session is still updated`() {
        fixture.sentryOptions.sampleRate = 1.0
        val sut = fixture.getSut()

        val event = SentryEvent().apply {
            exceptions = createNonHandledException()
        }
        val scope = Scope(fixture.sentryOptions)
        scope.startSession().current
        sut.captureEvent(event, scope, null)
        scope.withSession {
            assertEquals(Session.State.Crashed, it!!.status)
            assertEquals(1, it.errorCount())
        }
    }

    @Test
    fun `when context property is missing on the event, property from scope contexts is applied`() {
        val sut = fixture.getSut()

        val scope = Scope(fixture.sentryOptions)
        scope.setContexts("key", "abc")
        scope.startSession().current
        sut.captureEvent(SentryEvent(), scope, null)
        verify(fixture.connection).send(check {
            val event = getEventFromData(it.items.first().data)
            val map = event.contexts["key"] as Map<*, *>
            assertEquals("abc", map["value"])
        }, anyOrNull())
    }

    @Test
    fun `when contexts property is set on the event, property from scope contexts is not applied`() {
        val sut = fixture.getSut()

        val event = SentryEvent()
        event.contexts["key"] = "event value"
        val scope = Scope(fixture.sentryOptions)
        scope.setContexts("key", "scope value")
        scope.startSession().current
        sut.captureEvent(event, scope, null)
        verify(fixture.connection).send(check {
            val eventFromData = getEventFromData(it.items.first().data)
            assertEquals("event value", eventFromData.contexts["key"])
        }, anyOrNull())
    }

    @Test
    fun `exception thrown by an event processor is handled gracefully`() {
        fixture.sentryOptions.addEventProcessor { _, _ -> throw RuntimeException() }
        val sut = fixture.getSut()
        sut.captureEvent(SentryEvent())
    }

    private fun createScope(): Scope {
        return Scope(SentryOptions()).apply {
            addBreadcrumb(Breadcrumb().apply {
                message = "message"
            })
            setExtra("extra", "extra")
            setTag("tags", "tags")
            fingerprint.add("fp")
            transaction = "transaction"
            level = SentryLevel.FATAL
            user = User().apply {
                id = "id"
            }
        }
    }

    private fun createEvent(): SentryEvent {
        return SentryEvent().apply {
            addBreadcrumb(Breadcrumb().apply {
                message = "eventMessage"
            })
            setExtra("eventExtra", "eventExtra")
            setTag("eventTag", "eventTag")
            fingerprints = listOf("eventFp")
            transaction = "eventTransaction"
            level = SentryLevel.DEBUG
            user = User().apply {
                id = "eventId"
            }
        }
    }

    private fun createSession(release: String = "rel"): Session {
        return Session("dis", User(), "env", release)
    }

    private val userFeedback: UserFeedback get()  {
        val eventId = SentryId("c2fb8fee2e2b49758bcb67cda0f713c7")
        val userFeedback = UserFeedback(eventId)
        userFeedback.apply {
            name = "John"
            email = "john@me.com"
            comments = "comment"
        }

        return userFeedback
    }

    internal class CustomTransportGate : ITransportGate {
        override fun isConnected(): Boolean = false
    }

    private fun createNonHandledException(): List<SentryException> {
        val exception = SentryException().apply {
            mechanism = Mechanism().apply {
                isHandled = false
            }
        }
        return listOf(exception)
    }

    private fun getEventFromData(data: ByteArray): SentryEvent {
        val inputStream = InputStreamReader(ByteArrayInputStream(data))
        return fixture.sentryOptions.serializer.deserializeEvent(inputStream)
    }

    internal class CustomCachedApplyScopeDataHint : Cached, ApplyScopeData

    internal class DiskFlushNotificationHint : DiskFlushNotification {
        override fun markFlushed() {}
    }
}
