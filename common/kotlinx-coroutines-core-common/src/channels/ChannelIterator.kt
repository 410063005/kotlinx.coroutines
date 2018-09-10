package kotlinx.coroutines.experimental.channels

import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.yield

/**
 * Iterator for [ReceiveChannel]. Instances of this interface are *not thread-safe* and shall not be used
 * from concurrent coroutines.
 */
interface ChannelIterator<out E> {
    /**
     * Returns `true` if the channel has more elements suspending the caller while this channel
     * [isEmpty][ReceiveChannel.isEmpty] or returns `false` if the channel
     * [isClosedForReceive][ReceiveChannel.isClosedForReceive] without cause.
     * It throws the original [close][SendChannel.close] cause exception if the channel has _failed_.
     *
     * This function retrieves and removes the element from this channel for the subsequent invocation
     * of [next].
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     *
     * *Cancellation of suspended receive is atomic* -- when this function
     * throws [CancellationException] it means that the element was not retrieved from this channel.
     * As a side-effect of atomic cancellation, a thread-bound coroutine (to some UI thread, for example) may
     * continue to execute even after it was cancelled from the same thread in the case when this receive operation
     * was already resumed and the continuation was posted for execution to the thread's queue.
     *
     * Note, that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     */
    suspend operator fun hasNext(): Boolean

    /**
     * Retrieves and removes the element from this channel suspending the caller while this channel
     * [isEmpty][ReceiveChannel.isEmpty] or throws [ClosedReceiveChannelException] if the channel
     * [isClosedForReceive][ReceiveChannel.isClosedForReceive] without cause.
     * It throws the original [close][SendChannel.close] cause exception if the channel has _failed_.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     *
     * *Cancellation of suspended receive is atomic* -- when this function
     * throws [CancellationException] it means that the element was not retrieved from this channel.
     * As a side-effect of atomic cancellation, a thread-bound coroutine (to some UI thread, for example) may
     * continue to execute even after it was cancelled from the same thread in the case when this receive operation
     * was already resumed and the continuation was posted for execution to the thread's queue.
     *
     * Note, that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     */
    suspend operator fun next(): E
}

/**
 * Returns new iterator to receive elements from this channels using `for` loop.
 * Iteration completes normally when the channel is [isClosedForReceive] without cause and
 * throws the original [close][SendChannel.close] cause exception if the channel has _failed_.
 */
operator fun <E> ReceiveChannel<E>.iterator(): ChannelIterator<E> {
    val c = this
    return object: ChannelIterator<E> {
        var result: Any? = NO_RESULT // E | NO_RESULT | ClosedResult

        override suspend fun hasNext(): Boolean {
            if (result != NO_RESULT) return hasNext(result)
            // Try to receive an element. Store the result even if
            // receiving fails in order to process further [hasNext]
            // and [next] invocations properly.
            try {
                result = c.receive()
                return true
            } catch (closedException: ClosedReceiveChannelException) {
                // Channel is closed, cannot iterate further.
                result = ClosedResult(null)
                return false
            } catch (closingCause: Throwable) {
                // Channel is _failed_, throw the cause exception.
                result = ClosedResult(closingCause)
                throw closingCause
            }
        }

        private fun hasNext(result: Any?): Boolean {
            if (result is ClosedResult) {
                if (result.cause != null) throw result.cause
                return false
            }
            return true
        }

        override suspend fun next(): E {
            // Read the already received result or NO_RESULT
            // if [hasNext] was not invoked before this call.
            val result = this.result
            when (result) {
                 // Rare case -- [hasNext] was not invoked, invoke [receive] directly.
                NO_RESULT -> return c.receive()
                // Channel is closed, throw the cause exception.
                is ClosedResult -> throw result.receiveException
                // An element was received successfully
                else -> {
                    // Reset the [result] field and return the element
                    this.result = NO_RESULT
                    return result as E
                }
            }
        }
    }
}

private val NO_RESULT = Symbol("NO_RESULT")
private class ClosedResult(val cause: Throwable?) {
    val receiveException: Throwable get() = cause ?: ClosedReceiveChannelException(DEFAULT_CLOSE_MESSAGE)
}