package com.xfiles.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xfiles.documents.structure.DocumentParts
import com.xfiles.documents.structure.SlideShape
import com.xfiles.documents.structure.StructuredDocument
import com.xfiles.feature.reader.domain.LoadSlidePictureUseCase
import com.xfiles.feature.reader.domain.ResolveSlideLinkUseCase
import com.xfiles.feature.reader.domain.ResolveSlideMediaUseCase
import com.xfiles.feature.reader.domain.SlideLinkTarget
import com.xfiles.feature.reader.domain.SlideMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A picture open in the zoom viewer; [bytes] is null while it loads. */
internal class SlidePictureView(val bytes: ByteArray?)

internal data class SlideUiState(
    /** A web link waiting for the reader's confirmation. */
    val pendingUrl: String? = null,
    val preparingMedia: Boolean = false,
    val media: SlideMedia? = null,
    val picture: SlidePictureView? = null,
    val message: String? = null,
)

internal sealed interface SlideEvent {
    /** A tap on a link; [current] and [count] place `next`, `previous` and jumps. */
    data class ActivateLink(val link: String, val current: Int, val count: Int) : SlideEvent

    data class PlayMedia(val shape: SlideShape) : SlideEvent

    data class ViewPicture(val path: String) : SlideEvent

    data object ConfirmWebLink : SlideEvent

    data object DismissWebLink : SlideEvent

    data object CloseMedia : SlideEvent

    data object ClosePicture : SlideEvent

    data object DismissMessage : SlideEvent
}

/** One-off instructions for the screen: they must not repeat on recomposition. */
internal sealed interface SlideEffect {
    data class GoTo(val index: Int) : SlideEffect

    data class Open(val url: String) : SlideEffect
}

/**
 * Slide interactivity: links, media and pictures. The screen forwards taps as [SlideEvent]s, reads
 * [state], and collects [effects]. The document's parts are bound by [bind] while it is shown.
 */
@HiltViewModel
internal class SlidePresentationViewModel @Inject constructor(
    private val links: ResolveSlideLinkUseCase,
    private val resolveMedia: ResolveSlideMediaUseCase,
    private val loadPicture: LoadSlidePictureUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SlideUiState())
    val state: StateFlow<SlideUiState> = mutableState.asStateFlow()

    private val mutableEffects = Channel<SlideEffect>(Channel.BUFFERED)
    val effects: Flow<SlideEffect> = mutableEffects.receiveAsFlow()

    private var parts: DocumentParts? = null
    private var preloaded: Map<String, ByteArray> = emptyMap()
    private var mediaJob: Job? = null
    private var pictureJob: Job? = null

    fun bind(document: StructuredDocument) {
        parts = document.parts
        preloaded = document.images
    }

    fun onEvent(event: SlideEvent) {
        when (event) {
            is SlideEvent.ActivateLink -> activate(event)
            is SlideEvent.PlayMedia -> playMedia(event.shape)
            is SlideEvent.ViewPicture -> viewPicture(event.path)
            SlideEvent.ConfirmWebLink -> {
                val url = state.value.pendingUrl
                mutableState.update { it.copy(pendingUrl = null) }
                if (url != null) mutableEffects.trySend(SlideEffect.Open(url))
            }
            SlideEvent.DismissWebLink -> mutableState.update { it.copy(pendingUrl = null) }
            SlideEvent.CloseMedia -> {
                mediaJob?.cancel()
                mutableState.update { it.copy(media = null, preparingMedia = false) }
            }
            SlideEvent.ClosePicture -> {
                pictureJob?.cancel()
                mutableState.update { it.copy(picture = null) }
            }
            SlideEvent.DismissMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun activate(event: SlideEvent.ActivateLink) {
        when (val target = links(event.link, event.current, event.count)) {
            is SlideLinkTarget.Slide -> mutableEffects.trySend(SlideEffect.GoTo(target.index))
            is SlideLinkTarget.Web -> mutableState.update { it.copy(pendingUrl = target.url) }
            SlideLinkTarget.Nowhere -> Unit
        }
    }

    private fun playMedia(shape: SlideShape) {
        if (state.value.preparingMedia) return
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            mutableState.update { it.copy(preparingMedia = true, message = null) }
            try {
                val media = resolveMedia(shape, parts)
                mutableState.update { it.copy(preparingMedia = false, media = media) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableState.update { it.copy(preparingMedia = false, message = failure.message ?: "This media can't be played") }
            }
        }
    }

    private fun viewPicture(path: String) {
        pictureJob?.cancel()
        mutableState.update { it.copy(picture = SlidePictureView(null)) }
        pictureJob = viewModelScope.launch {
            try {
                val bytes = loadPicture(path, preloaded, parts)
                mutableState.update { it.copy(picture = SlidePictureView(bytes)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableState.update { it.copy(picture = null, message = failure.message ?: "This picture can't be shown") }
            }
        }
    }
}
