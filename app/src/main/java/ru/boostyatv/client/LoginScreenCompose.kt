package ru.boostyatv.client

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

internal interface LoginBackend {
    val api: BoostyApi
    val busy: Boolean

    fun sendCode(
        phone: String,
        pairSender: TvPairing.Sender?,
        onSuccess: (BoostyApi.Challenge) -> Unit,
        onError: (String) -> Unit,
    )

    fun confirmCode(
        challenge: BoostyApi.Challenge,
        code: String,
        pairSender: TvPairing.Sender?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )

    fun finishWebLogin(
        session: WebSession,
        pairSender: TvPairing.Sender?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )

    fun transferCurrentAccess(
        invitation: TvPairing.Sender,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )
}

@Composable
internal fun LoginScreenView(
    backend: LoginBackend,
    pairSender: TvPairing.Sender?,
    onSignedIn: () -> Unit,
    onPairFinished: () -> Unit,
    onOpenWebLogin: (TvPairing.Sender?) -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    val isTv = BoostyTheme.isTv
    val keyboard = LocalSoftwareKeyboardController.current

    // On Android TV, start directly on QR-code pairing!
    var tvPhoneMode by remember(isTv) { mutableStateOf(!isTv) }
    var challenge by remember(pairSender) { mutableStateOf<BoostyApi.Challenge?>(null) }
    var phoneInput by remember(pairSender) { mutableStateOf("") }
    var codeInput by remember(challenge) { mutableStateOf("") }
    var errorText by remember(challenge, tvPhoneMode) { mutableStateOf<String?>(null) }
    var resendAfter by remember { mutableLongStateOf(0L) }
    var showCountryPicker by remember { mutableStateOf(false) }

    val countries = remember(context) { PhoneCountry.load(context) }
    var selectedCountry by remember(context, countries) {
        val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        val region = prefs.getString("phone_region", Locale.getDefault().country)
        mutableStateOf(countries.firstOrNull { it.region() == region } ?: PhoneCountry.RUSSIA)
    }

    if (isTv && !tvPhoneMode && pairSender == null) {
        TvQrLoginScreen(
            api = backend.api,
            onSignedIn = onSignedIn,
            onSwitchToPhone = { tvPhoneMode = true },
        )
        return
    }

    val confirming = challenge != null
    val displayedCountry = remember(phoneInput, selectedCountry, countries) {
        PhoneCountry.fromInternational(phoneInput, selectedCountry, countries)
    }

    fun submitForm() {
        if (backend.busy) return
        val activeChallenge = challenge
        if (activeChallenge != null) {
            val code = codeInput.trim()
            if (!code.matches(Regex("[0-9]{6}"))) {
                errorText = context.getString(R.string.login_error_code_format)
                return
            }
            keyboard?.hide()
            errorText = null
            backend.confirmCode(
                challenge = activeChallenge,
                code = code,
                pairSender = pairSender,
                onSuccess = {
                    challenge = null
                    phoneInput = ""
                    codeInput = ""
                    if (pairSender != null) {
                        Toast.makeText(context, context.getString(R.string.login_pair_connected), Toast.LENGTH_SHORT).show()
                        onPairFinished()
                    } else {
                        onSignedIn()
                    }
                },
                onError = { errorText = it },
            )
        } else {
            val normalized = selectedCountry.normalize(phoneInput)
            if (normalized.isEmpty()) {
                errorText = context.getString(R.string.login_error_select_country)
                return
            }
            keyboard?.hide()
            errorText = null
            backend.sendCode(
                phone = normalized,
                pairSender = pairSender,
                onSuccess = { next ->
                    challenge = next
                    codeInput = ""
                    resendAfter = SystemClock.elapsedRealtime() + 60_000L
                },
                onError = { errorText = it },
            )
        }
    }

    val firstKeypadFocus = remember { FocusRequester() }
    LaunchedEffect(isTv, confirming) {
        if (isTv) runCatching { firstKeypadFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isTv) 44.dp else 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val introContent: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(ControlShape),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.accent,
                    )
                }
                Text(
                    text = when {
                        confirming -> stringResource(R.string.login_title_code)
                        pairSender != null -> stringResource(R.string.login_title_tv)
                        else -> stringResource(R.string.login_title_phone)
                    },
                    style = if (isTv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                    color = palette.ink,
                )
                Text(
                    text = if (confirming) {
                        when (challenge?.transport()) {
                            "sms" -> stringResource(R.string.login_delivery_sms)
                            "telegram" -> stringResource(R.string.login_delivery_telegram)
                            else -> stringResource(R.string.login_delivery_default)
                        }
                    } else {
                        stringResource(R.string.login_hint_phone)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.muted,
                )
            }
        }

        val formContent: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SheetShape)
                    .background(palette.surface)
                    .border(1.dp, palette.hairline, SheetShape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(if (confirming) R.string.login_label_code else R.string.login_label_phone),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )

                if (confirming) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { value ->
                            if (value.length <= 6) codeInput = value
                        },
                        singleLine = true,
                        readOnly = isTv,
                        placeholder = { Text(stringResource(R.string.login_hint_code), color = palette.muted) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = palette.ink),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitForm() }),
                        shape = ControlShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.hairline,
                            focusedContainerColor = palette.background,
                            unfocusedContainerColor = palette.background,
                            cursorColor = palette.accent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val prefixText = stringResource(
                            R.string.login_country_prefix,
                            displayedCountry.flag(),
                            displayedCountry.prefix(),
                        )
                        val prefixDesc = stringResource(
                            R.string.login_country_code_desc,
                            displayedCountry.name(),
                            displayedCountry.prefix(),
                        )
                        BoostyButton(
                            text = prefixText,
                            primary = false,
                            onClick = {
                                keyboard?.hide()
                                showCountryPicker = true
                            },
                            modifier = Modifier.semantics { contentDescription = prefixDesc },
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { value ->
                                if (value.length <= 24) phoneInput = value
                            },
                            singleLine = true,
                            readOnly = isTv,
                            placeholder = { Text(stringResource(R.string.login_hint_no_code), color = palette.muted) },
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = palette.ink),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitForm() }),
                            shape = ControlShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = palette.accent,
                                unfocusedBorderColor = palette.hairline,
                                focusedContainerColor = palette.background,
                                unfocusedContainerColor = palette.background,
                                cursorColor = palette.accent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                    )
                }

                if (isTv) {
                    TvNumericKeypad(
                        confirming = confirming,
                        firstFocus = firstKeypadFocus,
                        onKey = { key ->
                            if (key == "⌫") {
                                if (confirming) {
                                    if (codeInput.isNotEmpty()) codeInput = codeInput.dropLast(1)
                                } else if (phoneInput.isNotEmpty()) {
                                    phoneInput = phoneInput.dropLast(1)
                                }
                            } else {
                                if (confirming) {
                                    if (codeInput.length < 6) codeInput += key
                                } else if (phoneInput.length < 24) {
                                    phoneInput += key
                                }
                            }
                        },
                    )
                }

                BoostyButton(
                    text = stringResource(if (confirming) R.string.login_action_sign_in else R.string.login_action_get_code),
                    primary = true,
                    enabled = !backend.busy,
                    onClick = ::submitForm,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (confirming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BoostyButton(
                            text = stringResource(R.string.login_action_change_number),
                            primary = false,
                            enabled = !backend.busy,
                            onClick = {
                                challenge = null
                                codeInput = ""
                                errorText = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        BoostyButton(
                            text = stringResource(R.string.login_action_resend),
                            primary = false,
                            enabled = !backend.busy,
                            onClick = {
                                val remaining = (resendAfter - SystemClock.elapsedRealtime() + 999L) / 1000L
                                if (remaining > 0L) {
                                    errorText = context.getString(R.string.login_resend_in, remaining)
                                } else {
                                    val phone = challenge?.phone() ?: return@BoostyButton
                                    errorText = null
                                    backend.sendCode(
                                        phone = phone,
                                        pairSender = pairSender,
                                        onSuccess = { next ->
                                            challenge = next
                                            resendAfter = SystemClock.elapsedRealtime() + 60_000L
                                        },
                                        onError = { errorText = it },
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (pairSender != null) {
                    BoostyButton(
                        text = stringResource(R.string.login_action_cancel_pair),
                        primary = false,
                        onClick = onPairFinished,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (isTv) {
                    BoostyButton(
                        text = stringResource(R.string.login_tv_use_qr),
                        iconRes = R.drawable.ic_qr,
                        primary = false,
                        onClick = { tvPhoneMode = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BoostyButton(
                        text = stringResource(R.string.login_action_social),
                        primary = false,
                        onClick = { onOpenWebLogin(null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (isTv) {
            Row(
                modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { introContent() }
                Box(modifier = Modifier.weight(1.1f)) { formContent() }
            }
        } else {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                introContent()
                formContent()
            }
        }
    }

    if (showCountryPicker) {
        CountryPickerSheet(
            countries = countries,
            current = displayedCountry,
            onSelect = { country ->
                selectedCountry = country
                context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE).edit()
                    .putString("phone_region", country.region()).apply()
                if (country.prefix() != displayedCountry.prefix()) {
                    val national = displayedCountry.nationalPart(phoneInput)
                    if (national != phoneInput) phoneInput = national
                }
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false },
        )
    }
}

@Composable
private fun TvQrLoginScreen(
    api: BoostyApi,
    onSignedIn: () -> Unit,
    onSwitchToPhone: () -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    var sessionSeed by remember { mutableIntStateOf(0) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    val phoneButtonFocus = remember { FocusRequester() }

    DisposableEffect(sessionSeed) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        qrBitmap = null
        statusError = null

        val local = localNetworkAddress(context)
        var receiver: TvPairing.Receiver? = null
        var worker: Thread? = null

        if (local == null) {
            statusError = context.getString(R.string.login_pair_tv_not_connected)
        } else {
            try {
                receiver = TvPairing.Receiver(local, object : TvPairing.Handler {
                    private var pending: BoostyApi.Challenge? = null

                    override fun handle(action: String, input: JSONObject): JSONObject {
                        if (api.hasSession()) {
                            throw BoostyApi.Problem(R.string.login_pair_already_connected)
                        }
                        when (action) {
                            "send" -> {
                                val phone = input.getString("phone")
                                if (!phone.matches(Regex("\\+[1-9][0-9]{6,14}"))) {
                                    throw BoostyApi.Problem(R.string.login_pair_invalid_phone_format)
                                }
                                val sent = api.sendCode(phone)
                                pending = sent
                                return JSONObject().put("transport", sent.transport())
                            }
                            "confirm" -> {
                                val active = pending ?: throw BoostyApi.Problem(R.string.login_pair_request_code_first)
                                val code = input.getString("code")
                                if (!code.matches(Regex("[0-9]{6}"))) {
                                    throw BoostyApi.Problem(R.string.login_pair_invalid_code_format)
                                }
                                api.confirm(active, code)
                            }
                            "web" -> api.importWebSession(
                                WebSession(
                                    input.getString("access"),
                                    input.getString("refresh"),
                                    input.getLong("expiry"),
                                    input.getString("device"),
                                )
                            )
                            "access" -> api.importSharedAccess(
                                BoostyApi.AccessSession(
                                    input.getString("access"),
                                    input.getLong("expiry"),
                                    input.getString("device"),
                                )
                            )
                            else -> throw BoostyApi.Problem(R.string.login_pair_unknown_method)
                        }
                        return JSONObject()
                    }
                }, context.resources)

                qrBitmap = renderQrBitmap(receiver.invitation(), 480)
                val activeReceiver = receiver
                worker = AppExecutors.thread("boosty-tv-pair") {
                    val connected = activeReceiver.await()
                    activity?.runOnUiThread {
                        if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                        if (connected) {
                            onSignedIn()
                        } else {
                            statusError = context.getString(R.string.login_pair_qr_expired)
                            qrBitmap = null
                        }
                    }
                }
                worker.start()
            } catch (e: Exception) {
                Log.w("BoostyATV", "Pairing receiver failed to start", e)
                receiver?.close()
                receiver = null
                statusError = context.getString(R.string.login_pair_cannot_open)
            }
        }

        onDispose {
            worker?.interrupt()
            runCatching { receiver?.close() }
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { phoneButtonFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1.15f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_icon),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(ControlShape),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.accent,
                    )
                }

                Text(
                    text = stringResource(R.string.login_tv_qr_headline),
                    style = MaterialTheme.typography.displaySmall,
                    color = palette.ink,
                )

                Text(
                    text = stringResource(R.string.login_pair_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.muted,
                )

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (statusError != null) {
                        BoostyButton(
                            text = stringResource(R.string.login_tv_qr_refresh),
                            iconRes = R.drawable.ic_refresh,
                            primary = true,
                            onClick = { sessionSeed++ },
                        )
                    }
                    BoostyButton(
                        text = stringResource(R.string.login_tv_use_phone_number),
                        primary = statusError == null,
                        onClick = onSwitchToPhone,
                        modifier = Modifier.focusRequester(phoneButtonFocus),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(SheetShape)
                    .background(palette.surface)
                    .border(1.dp, palette.hairline, SheetShape)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qrBitmap
                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .size(252.dp)
                            .clip(CardShape)
                            .background(Color.White)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = remember(bmp) { bmp.asImageBitmap() },
                            contentDescription = stringResource(R.string.login_pair_qr_description),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.size(252.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (statusError != null) {
                            Text(
                                text = statusError.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.accent,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            ShimmerBox(modifier = Modifier.size(220.dp), shape = CardShape)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvNumericKeypad(
    confirming: Boolean,
    firstFocus: FocusRequester,
    onKey: (String) -> Unit,
) {
    val palette = BoostyTheme.palette
    val deleteDesc = stringResource(R.string.login_keypad_delete)
    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("+", "0", "⌫"),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { colIdx, key ->
                    val hidden = confirming && key == "+"
                    if (hidden) {
                        Spacer(Modifier.weight(1f).height(40.dp))
                    } else {
                        var focused by remember { mutableStateOf(false) }
                        val bg by animateColorAsState(
                            targetValue = if (focused) palette.surfaceFocused else palette.surfaceElevated,
                            animationSpec = tween(120),
                            label = "keyBg",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .then(if (rowIdx == 0 && colIdx == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .clip(ControlShape)
                                .background(bg)
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) palette.focusRing else palette.hairline,
                                    shape = ControlShape,
                                )
                                .onFocusChanged { focused = it.isFocused }
                                .semantics {
                                    if (key == "⌫") contentDescription = deleteDesc
                                }
                                .clickable(role = Role.Button) { onKey(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (focused) palette.accent else palette.ink,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryPickerSheet(
    countries: List<PhoneCountry>,
    current: PhoneCountry,
    onSelect: (PhoneCountry) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BoostyTheme.palette
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, countries, current) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            listOf(current) + countries.filter { it != current }
        } else {
            countries.filter { it.matches(q) }
        }
    }

    BoostyOverlaySheet(
        title = stringResource(R.string.login_country_picker_title),
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.login_country_search_hint), color = palette.muted) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.hairline,
                focusedContainerColor = palette.background,
                unfocusedContainerColor = palette.background,
                cursorColor = palette.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.login_country_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { "${it.region()}_${it.prefix()}" }) { country ->
                    BoostySheetOption(
                        label = "${country.flag()}  ${country.name()}",
                        trailingText = country.prefix(),
                        selected = country == current,
                        onClick = { onSelect(country) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun WebLoginScreenView(
    target: TvPairing.Sender?,
    backend: LoginBackend,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val palette = BoostyTheme.palette
    var address by remember { mutableStateOf(context.getString(R.string.login_web_title)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<BoostyWebLogin?>(null) }

    fun finishSession(session: WebSession) {
        errorText = null
        backend.finishWebLogin(
            session = session,
            pairSender = target,
            onSuccess = {
                webViewRef?.close(true)
                webViewRef = null
                if (target != null) {
                    Toast.makeText(context, context.getString(R.string.login_pair_connected), Toast.LENGTH_SHORT).show()
                }
                onFinished()
            },
            onError = { errorText = it },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.close(true)
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        ScreenToolbar(
            title = address,
            showBack = true,
            onBack = {
                if (webViewRef?.closePopup() != true) onBack()
            },
            actions = {
                BoostyButton(
                    text = stringResource(if (target == null) R.string.login_web_finish else R.string.login_web_transfer),
                    primary = true,
                    enabled = !backend.busy,
                    onClick = {
                        errorText = null
                        webViewRef?.readSession { session -> finishSession(session) }
                    },
                )
            },
        )
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        AndroidView(
            factory = { ctx ->
                BoostyWebLogin(
                    ctx,
                    { host -> address = host },
                    { err -> errorText = err },
                ).also { browser ->
                    browser.onAuthorized { session ->
                        if (target == null) {
                            finishSession(session)
                        } else {
                            browser.closePopup()
                            errorText = ctx.getString(R.string.login_web_read_session)
                        }
                    }
                    webViewRef = browser
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
internal fun PairInvitationDialog(
    signedIn: Boolean,
    invitation: TvPairing.Sender,
    backend: LoginBackend,
    onDismiss: () -> Unit,
    onStartPhonePair: (TvPairing.Sender) -> Unit,
    onStartSocialPair: (TvPairing.Sender) -> Unit,
) {
    val context = LocalContext.current
    var confirmTransfer by remember { mutableStateOf(false) }
    var transferError by remember { mutableStateOf<String?>(null) }

    if (confirmTransfer) {
        BoostyConfirmDialog(
            title = stringResource(R.string.login_pair_confirm_title),
            message = stringResource(R.string.login_pair_confirm_message),
            confirmLabel = stringResource(R.string.login_pair_confirm_grant),
            errorText = transferError,
            busy = backend.busy,
            onConfirm = {
                transferError = null
                backend.transferCurrentAccess(
                    invitation = invitation,
                    onSuccess = {
                        Toast.makeText(context, context.getString(R.string.login_pair_connected), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    onError = { err -> transferError = err },
                )
            },
            onDismiss = onDismiss,
        )
    } else {
        BoostyOverlaySheet(
            title = stringResource(R.string.login_pair_title),
            onDismiss = onDismiss,
        ) {
            if (signedIn) {
                BoostySheetOption(
                    label = stringResource(R.string.login_pair_transfer_current),
                    iconRes = R.drawable.ic_tv,
                    onClick = { confirmTransfer = true },
                )
            }
            BoostySheetOption(
                label = stringResource(R.string.login_pair_by_phone),
                iconRes = R.drawable.ic_person,
                onClick = {
                    onDismiss()
                    onStartPhonePair(invitation)
                },
            )
            BoostySheetOption(
                label = stringResource(R.string.login_pair_via_social),
                iconRes = R.drawable.ic_qr,
                onClick = {
                    onDismiss()
                    onStartSocialPair(invitation)
                },
            )
        }
    }
}

private fun renderQrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

@Suppress("DEPRECATION")
private fun localNetworkAddress(context: Context): InetAddress? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val candidates = buildList {
        manager.activeNetwork?.let(::add)
        manager.allNetworks.forEach { if (it !in this) add(it) }
    }
    for (candidate in candidates) {
        val capabilities = manager.getNetworkCapabilities(candidate) ?: continue
        val properties = manager.getLinkProperties(candidate) ?: continue
        val localTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!localTransport) continue
        for (link in properties.linkAddresses) {
            val address = link.address
            if (address is Inet4Address && address.isSiteLocalAddress) return address
        }
    }
    return null
}
