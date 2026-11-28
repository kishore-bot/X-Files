package com.xfiles.feature.reader

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.xfiles.design.EzControlSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.design.EzColors
import com.xfiles.design.EzIcons
import com.xfiles.design.EzMotion
import com.xfiles.design.LocalReducedMotion
import com.xfiles.design.EzShapes
import com.xfiles.design.glass
import com.xfiles.design.glassCard
import com.xfiles.design.hapticClickable
import com.xfiles.documentengine.PdfSignatureInfo
import com.xfiles.feature.reader.domain.ReadPdfSignaturesUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PdfSignaturesEntryPoint {
    fun readPdfSignatures(): ReadPdfSignaturesUseCase
}

/** What the signatures say as a whole, for the pill's tone. */
private enum class SignatureTone(val color: Color?, val label: String) {
    Present(null, "Signature present · unverified"),
    Changed(Color(0xFFF0A020), "Unverified · signature range ends before the file"),
    Damaged(EzColors.Primary, "Unverified · invalid signature range"),
}

private fun toneOf(signatures: List<PdfSignatureInfo>): SignatureTone = when {
    signatures.any { !it.byteRangeOk } -> SignatureTone.Damaged
    // The offsets only describe the file revision that the signature claims to cover.
    signatures.none { it.coversWholeFile } -> SignatureTone.Changed
    else -> SignatureTone.Present
}

/** A pill over the page when the PDF is digitally signed; tapping it opens the details. */
@Composable
internal fun PdfSignatureBadge(uri: Uri, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val useCase = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, PdfSignaturesEntryPoint::class.java).readPdfSignatures()
    }
    val signatures by produceState(emptyList<PdfSignatureInfo>(), uri) { value = useCase(uri) }
    var open by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        signatures.isNotEmpty(),
        modifier,
        enter = EzMotion.edgeEnter(reduced, fromTop = true),
        exit = EzMotion.edgeExit(reduced, toTop = true),
    ) {
        val tone = toneOf(signatures)
        Row(
            Modifier
                .glass(EzShapes.Bar)
                .hapticClickable { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(EzIcons.Lock, null, tint = tone.color ?: EzColors.TextSecondary, modifier = Modifier.size(18.dp))
            Text(
                tone.label,
                color = EzColors.Text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (signatures.size > 1) Text("+${signatures.size - 1}", color = EzColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) PdfSignatureSheet(signatures) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfSignatureSheet(signatures: List<PdfSignatureInfo>, onDismiss: () -> Unit) {
    EzControlSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Digital signatures", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Xfiles checks the layout of the declared byte ranges. The signature and signer's certificate are unverified.",
                color = EzColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            signatures.forEach { SignatureCard(it) }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SignatureCard(s: PdfSignatureInfo) {
    val tone = toneOf(listOf(s))
    Column(Modifier.fillMaxWidth().glassCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(EzIcons.Lock, null, tint = tone.color ?: EzColors.TextSecondary, modifier = Modifier.size(20.dp))
            Text(s.signer?.let { "Declared signer: $it" } ?: "No signer declared", style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        }
        Detail("Status", tone.label)
        Detail("Signed", s.signedAt)
        Detail("Reason", s.reason)
        Detail("Location", s.location)
        Detail("Contact", s.contact)
        Detail("Declared range ends at file end", if (s.coversWholeFile) "Yes" else "No")
        Detail("Field", s.field.ifBlank { null })
        Detail("Format", s.format)
    }
}

@Composable
private fun Detail(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = EzColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        Text(value, color = EzColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
