package com.curato.ui.enquiry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.curato.domain.DomainError
import com.curato.domain.model.Contact
import com.curato.domain.validation.FIELD_EMAIL
import com.curato.domain.validation.FIELD_MESSAGE
import com.curato.domain.validation.FIELD_NAME
import com.curato.domain.validation.FIELD_PHONE
import com.curato.presentation.enquiry.EnquirySubject
import com.curato.presentation.enquiry.EnquiryUiState
import com.curato.presentation.enquiry.SubmitLabel
import com.curato.ui.designsystem.AppBanner
import com.curato.ui.designsystem.AppTextField
import com.curato.ui.designsystem.CuratoTopBar
import com.curato.ui.designsystem.EditGlyph
import com.curato.ui.designsystem.GoneGlyph
import com.curato.ui.designsystem.MinTouchTarget
import com.curato.ui.designsystem.PanelAction
import com.curato.ui.designsystem.PrimaryButton
import com.curato.ui.designsystem.Radius
import com.curato.ui.designsystem.ShimmerBox
import com.curato.ui.designsystem.ShimmerSection
import com.curato.ui.designsystem.Spacing
import com.curato.ui.designsystem.StatePanel
import com.curato.ui.designsystem.StickyActionBar
import com.curato.ui.designsystem.TABULAR_FIGURES
import com.curato.ui.designsystem.TickGlyph
import com.curato.ui.designsystem.TraceIdRow
import com.curato.ui.designsystem.formatAcceptedAt
import com.curato.ui.designsystem.formatPrice

/** The kit's `.a-prod-head .a-thumb { width: 56px }`. */
private val SubjectThumbSize: Dp = 56.dp

/** The kit's `.a-thanks .tick { width: 56px }` and its 28px glyph. */
private val TickCircleSize: Dp = 56.dp
private val TickGlyphSize: Dp = 28.dp

/** The kit's `.a-state .glyph { width: 46px }`. */
private val StateGlyphSize: Dp = 46.dp

/** The kit's `.a-scroll { gap: 14px }` on this screen, on the 4dp scale. */
private val FormGap: Dp = Spacing.x4

/**
 * Enquiry (SPEC.md §7.6) — the only thing a customer can complete.
 *
 * A full route rather than a sheet: a sheet cannot hold a keyboard, a validation summary
 * and a sticky action without fighting all three.
 *
 * Stateless, like every screen here. The four branches are the three states that
 * genuinely replace the form — sent, gone, and a failure while resolving the subject —
 * plus the form itself, which absorbs everything else. A retryable send failure is still
 * the form, annotated, with every value where the customer left it (AC-13).
 */
@Composable
fun EnquiryScreen(
    state: EnquiryUiState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onFieldBlur: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onEditContact: () -> Unit,
    onSubmit: () -> Unit,
    onRetryLoad: () -> Unit,
    onBackToProduct: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Titled, unlike Product: the form below has no headline of its own, so
            // without this the screen would never say what it is. Elevated, as the kit
            // draws it, because the content starts flush against the bar.
            //
            // Back stays live while sending. The kit dims it to 38%, but a customer who
            // wants out of an in-flight request must not be trapped by it, and leaving
            // costs at most an email that was already on its way.
            CuratoTopBar(title = "Enquire", onBack = onBack)

            when (state) {
                is EnquiryUiState.Sent -> ThankYou(state, onBackToProduct)

                is EnquiryUiState.Unavailable ->
                    ProductGone(state.afterSubmit, onBackToProduct)

                is EnquiryUiState.SubjectFailed ->
                    SubjectError(state.error, onRetryLoad, onBack)

                is EnquiryUiState.Form -> FormContent(
                    state = state,
                    onNameChange = onNameChange,
                    onEmailChange = onEmailChange,
                    onPhoneChange = onPhoneChange,
                    onMessageChange = onMessageChange,
                    onFieldBlur = onFieldBlur,
                    onConsentChange = onConsentChange,
                    onEditContact = onEditContact,
                    onSubmit = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun FormContent(
    state: EnquiryUiState.Form,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onFieldBlur: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onEditContact: () -> Unit,
    onSubmit: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // **A banner the customer cannot see is not a banner.** It is composed at the top of
    // the form, and a failure almost always arrives from the bottom of it — the Submit
    // button is in the sticky bar, so the scroll is at its end when the error lands.
    // Without this the field outlines turn red and the sentence explaining why is parked
    // off-screen above. Keyed on the banner itself, so clearing one does not yank the
    // view and two identical failures in a row still scroll back.
    LaunchedEffect(state.banner) {
        if (state.banner != null) scrollState.animateScrollTo(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    top = Spacing.x4,
                    bottom = Spacing.x4,
                ),
            verticalArrangement = Arrangement.spacedBy(FormGap),
        ) {
            // Above the subject, per the kit: the thing that went wrong is read before
            // the thing it went wrong about.
            state.banner?.let { banner ->
                AppBanner(message = banner.message, kind = banner.kind)
                // The one diagnostic ever shown, and only when the error carried one —
                // which `DomainError` restricts to `INTERNAL_ERROR` at the API boundary.
                banner.traceId?.let { TraceIdRow(traceId = it) }
            }

            SubjectRow(state.subject)

            if (state.collapsed && state.savedContact != null) {
                ContactSummaryRow(
                    contact = state.savedContact,
                    // Absent mid-send, not disabled: editing the address an in-flight
                    // enquiry is going to would be editing the past.
                    onEdit = if (state.submitting) null else onEditContact,
                )
            } else {
                ContactFields(
                    state = state,
                    onNameChange = onNameChange,
                    onEmailChange = onEmailChange,
                    onPhoneChange = onPhoneChange,
                    onFieldBlur = onFieldBlur,
                )
            }

            AppTextField(
                label = "Message",
                value = state.values.message,
                onValueChange = onMessageChange,
                placeholder = "Optional",
                errorMessage = state.fieldErrors[FIELD_MESSAGE],
                enabled = !state.submitting,
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    // No Done action: this is the last field and a multiline box uses the
                    // action key for a newline, which is what a message wants.
                    imeAction = ImeAction.Default,
                ),
                onFocusLost = { onFieldBlur(FIELD_MESSAGE) },
            )
        }

        // The kit puts consent in the bar beside the action it governs — "default on and
        // never hidden behind a disclosure". `StickyActionBar` carries `imePadding()` and
        // `navigationBarsPadding()` itself, which is what keeps both the checkbox and the
        // button above the keyboard.
        StickyActionBar {
            if (state.showConsent) {
                ConsentRow(
                    checked = state.consent,
                    onCheckedChange = onConsentChange,
                    enabled = !state.submitting,
                )
            }

            PrimaryButton(
                // The label names what a tap does *now*, and the banner knows: a failed
                // request makes the next tap a retry, while the client's own validation
                // rejecting the form means nothing was ever attempted.
                label = state.banner?.actionLabel ?: SubmitLabel,
                onClick = onSubmit,
                enabled = state.canSubmit,
                loading = state.submitting,
            )

            if (state.submitting) {
                Text(
                    // Says what is being waited for, which is the point of §4.6: the 2xx
                    // arrives only once SMTP has accepted the message.
                    text = "Waiting for confirmation that your enquiry was accepted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Name, email and phone — the full form.
 *
 * `Required` and `Optional` are marked in the field itself rather than with an asterisk
 * and a legend, which is the kit's call and the better one: a legend is read once and then
 * has to be remembered against four inputs.
 */
@Composable
private fun ContactFields(
    state: EnquiryUiState.Form,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onFieldBlur: (String) -> Unit,
) {
    AppTextField(
        label = "Your name",
        value = state.values.name,
        onValueChange = onNameChange,
        placeholder = "Required",
        errorMessage = state.fieldErrors[FIELD_NAME],
        enabled = !state.submitting,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
        ),
        onFocusLost = { onFieldBlur(FIELD_NAME) },
    )

    AppTextField(
        label = "Email",
        value = state.values.email,
        onValueChange = onEmailChange,
        placeholder = "Required",
        supportingText = "We will reply to this address.",
        errorMessage = state.fieldErrors[FIELD_EMAIL],
        enabled = !state.submitting,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            // An address is not prose: autocorrect and an initial capital are both
            // actively wrong here, and both are on by default.
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Next,
        ),
        onFocusLost = { onFieldBlur(FIELD_EMAIL) },
    )

    AppTextField(
        label = "Phone",
        value = state.values.phone,
        onValueChange = onPhoneChange,
        placeholder = "Optional",
        errorMessage = state.fieldErrors[FIELD_PHONE],
        enabled = !state.submitting,
        keyboardOptions = KeyboardOptions(
            // `Phone`, not `Number`: §6 accepts `+`, parentheses, hyphens and spaces, and
            // a bare numeric pad cannot type `+91` or `(020)`.
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
        ),
        onFocusLost = { onFieldBlur(FIELD_PHONE) },
    )
}

/**
 * The kit's `.a-prod-head` — what this enquiry is about.
 *
 * Drawn because "an enquiry with no visible subject is a form a customer cannot check
 * before sending". It shimmers rather than disappearing while the product resolves, so
 * the form does not reflow downward the moment it lands; the kit draws no loading frame
 * for this screen, and the shape of the row is the honest placeholder for one.
 */
@Composable
private fun SubjectRow(subject: EnquirySubject?) {
    if (subject == null) {
        ShimmerSection { SubjectPlaceholder() }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(SubjectThumbSize)
                .clip(RoundedCornerShape(Radius.field))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = subject.imageUrl,
                // Decorative: the title sits beside it and names the same product, so
                // describing the image too would read the name twice.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(Spacing.x1),
            )
        }

        Column(modifier = Modifier.padding(start = Spacing.x3).weight(1f)) {
            Text(
                text = subject.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                // Two lines, as the kit clamps it. A 90-character upstream title would
                // otherwise push the first input off the screen.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatPrice(subject.price, subject.currency)} ${subject.currency}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubjectPlaceholder() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerBox(
            modifier = Modifier.size(SubjectThumbSize),
            shape = RoundedCornerShape(Radius.field),
        )
        Column(
            modifier = Modifier.padding(start = Spacing.x3).weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(0.8f).height(Spacing.x4),
                shape = MaterialTheme.shapes.extraSmall,
            )
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(0.35f).height(Spacing.x3),
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
    }
}

/**
 * The kit's `.a-summary` — AC-16's one-tap repeat.
 *
 * [onEdit] is nullable so the control is **absent** rather than disabled while sending;
 * the kit drops it from its own sending frame, and a dimmed button beside a spinner is
 * one more thing to interpret at the one moment nothing can be done about it.
 */
@Composable
private fun ContactSummaryRow(contact: Contact, onEdit: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x2,
                    top = Spacing.x3,
                    bottom = Spacing.x3,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The kit's "ada@example.com · +91 9876543210", with the separator
                    // dropped rather than left dangling when there is no phone number.
                    text = listOfNotNull(contact.email, contact.phone).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onEdit != null) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics { contentDescription = "Edit your details" },
                ) {
                    EditGlyph(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.x6),
                    )
                }
            }
        }
    }
}

/**
 * The kit's `.a-check` — consent, in the bar beside the action it governs.
 *
 * One toggle target covering the box and both lines, rather than a checkbox with a label
 * beside it: the second line explains the consequence, so it should be part of what you
 * tap to agree to it. Unchecking clears the stored details immediately (SPEC.md §9.2),
 * which is the caller's job and is why this component only reports the change.
 */
@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            // Null, so the row owns the one click: a screen reader is offered one target
            // rather than two that do the same thing.
            onCheckedChange = null,
            enabled = enabled,
        )
        Column(modifier = Modifier.padding(start = Spacing.x2).weight(1f)) {
            Text(
                text = "Remember my details",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // Says where they go, because that is the thing being consented to.
                text = "Kept on this device only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The thank-you (AC-12), a state of this route rather than a destination.
 *
 * Every string is optional and the screen degrades rather than disappearing: a draft from
 * an older build still gets the tick and the headline. The enquiry *was* accepted, and
 * that must not look conditional on a string being present.
 */
@Composable
private fun ThankYou(state: EnquiryUiState.Sent, onBackToProduct: () -> Unit) {
    val stamp = formatAcceptedAt(state.acceptedAt)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.x6)
            // No keyboard can be open here — the form is gone — but the IME can still be
            // closing as this composes, and a button that slides into place afterwards is
            // the one control on this screen nobody should have to wait for.
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(TickCircleSize)
                .clip(RoundedCornerShape(Radius.full))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            TickGlyph(
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(TickGlyphSize),
            )
        }

        Text(
            text = "Enquiry sent",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Text(
            text = thankYouBody(state.productTitle, state.email),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (stamp != null) {
            Text(
                // Monospaced, like the trace row: a timestamp is a record to be quoted
                // back, not prose.
                text = "Accepted $stamp",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onBackToProduct,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.x2)
                .defaultMinSize(minHeight = MinTouchTarget),
            shape = RoundedCornerShape(Radius.full),
        ) {
            Text("Back to product", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The confirmation, as specific as the draft allows.
 *
 * Both halves can be missing — a draft written by an older build — so the copy degrades
 * in two steps rather than collapsing to one bland sentence the moment either is absent.
 */
internal fun thankYouBody(productTitle: String?, email: String?): String = when {
    productTitle != null && email != null ->
        "We have your message about $productTitle and will reply to $email."

    email != null -> "We have your message and will reply to $email."

    else -> "We have your message and will reply shortly."
}

/**
 * `NOT_FOUND` (AC-19) — the one failure that replaces the form rather than annotating it.
 *
 * After a send, "Your enquiry was not sent" is stated rather than implied: the customer
 * filled in a form and pressed a button, so silence about the outcome would be read as
 * success. On arrival nothing was attempted, so that sentence would describe an event
 * that never happened. One route out either way, and no Retry — nothing the customer can
 * retype makes a removed product exist.
 */
@Composable
private fun ProductGone(afterSubmit: Boolean, onBackToProduct: () -> Unit) {
    StatePanel(
        glyphContent = {
            GoneGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(StateGlyphSize),
            )
        },
        title = "This product is no longer available",
        body = if (afterSubmit) {
            // The one question a customer has after tapping Send, answered first.
            "Your enquiry was not sent."
        } else {
            // Nothing was attempted — the same sentence the product screen uses for the
            // same condition, so the two do not describe one event differently.
            "It may have sold out or been removed from the catalogue."
        },
        actions = listOf(PanelAction("Back to product", onBackToProduct)),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * A retryable failure while resolving the subject — the form is never composed.
 *
 * §7.6 is explicit that this one is full-screen rather than inline: there is no product,
 * so there is nothing to enquire about and no typed values to preserve.
 */
@Composable
private fun SubjectError(error: DomainError, onRetry: () -> Unit, onBack: () -> Unit) {
    StatePanel(
        glyph = "⚠",
        title = "Could not load this product",
        body = error.userMessage,
        actions = if (error.isRetryable) {
            listOf(
                PanelAction("Retry", onRetry),
                PanelAction("Go back", onBack, primary = false),
            )
        } else {
            listOf(PanelAction("Go back", onBack))
        },
        modifier = Modifier.fillMaxSize(),
    )
}
