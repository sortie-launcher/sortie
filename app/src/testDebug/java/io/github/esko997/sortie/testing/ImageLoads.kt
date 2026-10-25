package io.github.esko997.sortie.testing

import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.esko997.sortie.SortieApp
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers

/**
 * Covers and figures load on Coil's background dispatchers, which Compose's idling does not see. This installs the app's own
 * image loader ([SortieApp.newImageLoader]) with a listener that counts requests in flight, so a test can wait until every
 * picture on screen has arrived before it compares pixels.
 */
object ImageLoads {
    private val inFlight: MutableSet<ImageRequest> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    val idle: Boolean get() = inFlight.isEmpty()

    /** What is still loading, for a test's failure message. */
    fun pending(): List<String> = synchronized(inFlight) { inFlight.map { it.data.toString() } }

    @OptIn(DelicateCoilApi::class)
    fun install(app: SortieApp) {
        inFlight.clear()
        SingletonImageLoader.setUnsafe { context ->
            app.newImageLoader(context).newBuilder()
                // Robolectric has no ImageDecoder, which Coil prefers on API 29+; BitmapFactory decodes the same files
                .components { add(BitmapFactoryDecoder.Factory()) }
                // load on the thread that asks (the main thread, inside Compose's frame), not on background workers whose
                // results race the test's idling
                .coroutineContext(Dispatchers.Unconfined)
                .eventListener(Counter)
                .build()
        }
    }

    private object Counter : EventListener() {
        override fun onStart(request: ImageRequest) {
            inFlight += request
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            inFlight -= request
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            inFlight -= request
        }

        override fun onCancel(request: ImageRequest) {
            inFlight -= request
        }
    }
}
