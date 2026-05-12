package com.r1.launcher

/**
 * Wheel / activate routing. Lives outside LauncherState so the state container
 * stays focused on "what to render"; this file handles "what button does what".
 *
 * `host` provides the side-effecting pieces (tone feedback, launching apps,
 * triggering updater/store, opening settings, rebooting). The state machine
 * itself is pure: panel + focus indices only.
 */
interface LauncherHost {
    fun launchApp(idx: Int)
    fun checkForUpdate()
    fun onOnboardingDone()
    fun setBrightness(level: Int)
    fun setVolume(level: Int)
    fun setUiVolume(level: Int)
    fun toggleWifi(enable: Boolean)
    fun toggleCellular(enable: Boolean)
    fun toggleBluetooth(enable: Boolean)
    fun factoryReset()
    fun rebootDevice()
    fun powerOffDevice()
    fun startWifiScan()
    fun connectToWifi(ssid: String, pass: String)
    fun toggleWifiShare(enable: Boolean)
    fun wifiShareSaveEdit()
    fun wifiShareCycleTimer()
    fun openAirplaneSettings()
    fun openDateSettings()
    fun lockScreen()
    fun navTone()
    fun selectTone()
    fun backTone()
    fun popTone()
    fun openClawScanned(raw: String)
    fun openClawToggleRecord()
    fun openClawRecordStart()
    fun openClawRecordStop()
    fun openClawSendText(text: String)
    fun voiceToggleEnabled()
    fun voiceCycleVoiceId()
    fun voiceSaveKey(key: String)
    fun voiceClearKey()
    fun voicePasteKeyFromClipboard()
    fun voiceSettingsRowActivate(idx: Int)
    /** Custom voice id (cloned / professional / shared). Save validates non-blank. */
    fun voiceSaveCustomVoiceId(id: String)
    fun voiceClearCustomVoiceId()
    fun voicePasteCustomVoiceIdFromClipboard()
    /** Cycle TTS model: flash → turbo → multilingual → flash. */
    fun voiceCycleModel()
    /** Tuning sliders (clamped to 0..1, speed clamped to 0.7..1.2). */
    fun voiceSetStability(value: Float)
    fun voiceSetSimilarity(value: Float)
    fun voiceSetStyle(value: Float)
    fun voiceSetSpeed(value: Float)
    fun voiceToggleSpeakerBoost()
    /** Reset model + all tuning knobs to factory defaults. */
    fun voiceResetTuning()
    /** Synthesize a fixed sample with the current settings and play it. */
    fun voiceTestSynthesize()
    /** Fetch ElevenLabs `/v1/user/subscription` and update the state cache.
     *  [force] = true bypasses the 60s in-memory cache. Runs on a background
     *  thread; UI thread reads `voiceSubLoading` / `voiceSub*` for display. */
    fun voiceFetchSubscription(force: Boolean = false)
    /** Activate handler for SETTINGS_VOICE_TUNING rows. */
    fun voiceTuningRowActivate(idx: Int)
    fun openClawScrollUp()
    fun openClawScrollDown()
    fun openClawCloseSession()
    fun openClawSettingsRowActivate(idx: Int)
    fun openClawClearHistory()
    fun openClawDisconnect()
    fun openClawSetFontSize(size: Int)
    fun openClawSwitchSession(key: String)
    fun openClawRefreshSessions()
    fun openClawSessionsRowActivate(idx: Int)
    fun openClawCompactSession()
    fun openClawClearContext()
    fun openClawOpenCameraAsk()
    fun openClawCameraCaptured(jpegBytes: ByteArray)
    fun openClawCameraRetake()
    fun openClawCameraSend(prompt: String)
    fun openClawCameraMotorNudge(delta: Int)
    fun loadSmsConversations()
    fun openSmsThread(address: String, displayName: String)
    fun toggleWebServer(enable: Boolean)
    fun setWebTerminalEnabled(enable: Boolean)
    fun terminalRun(cmd: String)
    fun terminalClear()
    fun terminalRecordStart()
    fun terminalRecordStop()
    fun terminalPasteFromClipboard()
    fun claudeSend(text: String)
    fun claudeClear()
    fun claudeRecordStart()
    fun claudeRecordStop()
    fun claudePasteFromClipboard()
    /** Kick off `claude auth login --claudeai`. Returns the OAuth URL the user
     *  must open in a browser, plus a copy of the raw script log for
     *  diagnostics. Throws if the chroot or the auth helper script is missing. */
    fun claudeAuthStart(): com.r1.launcher.claude.ClaudeAuthStartResult
    /** Feed the OAuth code back into the FIFO. Returns whether
     *  `~/.claude/.credentials.json` got written. */
    fun claudeAuthFinish(code: String): com.r1.launcher.claude.ClaudeAuthFinishResult
    /** Persist a pasted Anthropic API key to /data/local/tmp/.anthropic_key
     *  (read on every `claude --print` invocation by the r1-alpine wrapper).
     *  Returns true on success. */
    fun claudeSaveApiKey(key: String): Boolean
    /** Wipe every credential surface so a fresh login attempt starts clean:
     *  `/root/.claude/.credentials.json`, `/home/claude/.claude/.credentials.json`,
     *  the auth FIFO + log under `/tmp/`, and `/data/local/tmp/.anthropic_key`.
     *  The chroot itself is preserved. Returns true on success. */
    fun claudeAuthReset(): Boolean
    /** End-to-end auth probe — runs `claude --print 'pong'` as the
     *  unprivileged claude user and reports whether the binary actually
     *  accepts the saved credentials. Slower than [claudeAuthStatus] (issues
     *  a real LLM round-trip), but the only reliable signal that login
     *  actually worked vs. just wrote a malformed file. */
    fun claudeAuthVerify(): com.r1.launcher.claude.ClaudeAuthVerifyResult
    /** Whether ~/.claude/.credentials.json or .anthropic_key exists. Used by
     *  the web companion to decide whether to show the login form. */
    fun claudeAuthStatus(): com.r1.launcher.claude.ClaudeAuthStatus
    /** Run the alpine + claude + user bootstrap chain as a single background
     *  job. The web companion broadcasts every stdout line as a
     *  `claude.setup.progress` WS event, then `claude.setup.done` when
     *  finished. Returns true when the job kicks off (false if already
     *  running). */
    fun claudeSetupStart(): Boolean
    /** Whether the bootstrap chain is currently running. Used by the web UI
     *  to gate the start button + recover progress on reconnect. */
    fun claudeSetupRunning(): Boolean
    fun copyToClipboard(text: String, label: String = "r1-launcher")
    fun setLanguage(code: String)

    // --- meetings (transcriber) ---
    fun transcriberOpen()
    fun transcriberStartRecording()
    fun transcriberStopRecording()
    fun transcriberToggleRecording()
    fun transcriberOpenDetail(uuid: String)
    fun transcriberRetryTranscribe(uuid: String)
    fun transcriberDelete(uuid: String)
    fun transcriberPlayAudio(uuid: String)
    fun transcriberStopAudio()
    fun transcriberShareEmail(uuid: String, recipient: String)
    fun transcriberOpenSettings()
    fun transcriberSettingsRowActivate(idx: Int)
    fun transcriberSaveSmtpField(field: String, value: String)
    fun transcriberPasteSmtpField(field: String)
    fun transcriberClearSmtp()
    fun transcriberPasteRecipient()
    /** Open the ⋮ action overlay on the detail panel. Computes the action
     *  set from the current meeting's status and writes it into
     *  `state.transcriberDetailMenuActions`, then flips the menu open. */
    fun transcriberOpenDetailMenu()
    /** Dispatch a single ⋮ menu action. The activity reads the current meeting
     *  uuid from state. */
    fun transcriberDetailMenuActivate(action: com.r1.launcher.transcriber.TranscriberDetailAction)

    // --- survey call bot ---
    fun surveyOpen()
    fun surveyOpenCampaign()
    fun surveyConfirmCampaignAndDial()
    fun surveyHangup()
    fun surveyOpenDetail(callRecordId: String)
    fun surveyDeleteCallRecord(callRecordId: String)
    fun surveyEmailCallRecord(callRecordId: String)
    /** Re-run the LLM summary for a finished call record (overwrites any
     *  previous summary). Used when the first summary was empty or wrong. */
    fun surveyRetrySummary(callRecordId: String)
    /** Post-call entry point from the SIP/orchestrator path: finalize the
     *  record, generate the summary, and email it. Idempotent. */
    fun surveyHandleCallComplete(callRecordId: String)
    fun surveyPlayAudio(callRecordId: String)
    fun surveyStopAudio()
    fun surveyOpenSettings()
    fun surveySettingsRowActivate(idx: Int)
    fun surveyCampaignRowActivate(idx: Int)
    fun surveyListRowActivate(idx: Int)
    /** Persist the OpenAI gpt-realtime-2 API key. */
    fun surveySaveOpenAiKey(key: String)
    fun surveyPasteOpenAiKey()
    fun surveyClearOpenAiKey()
    fun surveyCycleVoice()
    fun surveyCycleSummarizerModel()

    // --- web companion CRUD ---
    /** Persist a Survey (new if [com.r1.launcher.survey.Survey.id] is blank,
     *  update otherwise). Returns the surveyId. */
    fun surveyUpsertSurvey(survey: com.r1.launcher.survey.Survey): String
    fun surveyDeleteSurveyById(surveyId: String)
    /** Create a campaign, ready to start. Returns the new campaign id. */
    fun surveyCreateCampaign(surveyId: String, contacts: List<com.r1.launcher.survey.Contact>): String
    fun surveyCancelCampaignById(campaignId: String)
    /** Kick off (or resume) calling through a campaign. Calls into the SIP
     *  dialer when task #10 is wired; for now toasts a placeholder. */
    fun surveyStartCampaignById(campaignId: String)
    /** Persist a single setting from the web companion. Field names:
     *   - `openai_key`, `claude_key`
     *   - `sip_host`, `sip_user`, `sip_password`, `sip_from`
     *   - `consent_text`, `voice`, `summarizer_model`, `email_recipient`
     *  Empty string clears the field for nullable backings. */
    fun surveySaveSettingsField(field: String, value: String)
}

fun LauncherState.wheelUp(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            if (appsFocus <= 0) {
                back(); host.backTone()
            } else {
                appsFocus--; host.navTone()
            }
        }
        Panel.ONBOARDING -> {
            if (onboardingFocus <= 0) {
                if (onboardingStep > 0) {
                    onboardingStep--
                    onboardingFocus = 0
                    host.backTone()
                }
                // step 0: no-op (welcome has no back)
            } else {
                onboardingFocus--; host.navTone()
            }
        }
        Panel.SETTINGS -> {
            if (settingsFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_DISPLAY -> {
            if (settingsDisplayFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsDisplayFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_SOUND -> {
            if (settingsSoundFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsSoundFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_DEVICE -> {
            if (settingsDeviceFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsDeviceFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_ABOUT -> {
            back(); host.backTone()
        }
        Panel.SETTINGS_VOICE -> {
            if (voiceFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_VOICE_TUNING -> {
            if (voiceTuningFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceTuningFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> {
            if (voiceSubFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceSubFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_LANGUAGE -> {
            if (settingsLanguageFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsLanguageFocus--; host.navTone()
            }
        }
        Panel.NETWORK -> {
            if (networkFocus <= 0) {
                back(); host.backTone()
            } else {
                networkFocus--; host.navTone()
            }
        }
        Panel.FACTORY_CONFIRM -> {
            if (factoryConfirmFocus <= 0) {
                back(); host.backTone()
            } else {
                factoryConfirmFocus--; host.navTone()
            }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiScanFocus--; host.navTone()
            }
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
        Panel.WIFI_SHARE -> {
            if (wifiShareFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiShareFocus--; host.navTone()
            }
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel - 16).coerceAtLeast(1)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel - 1).coerceAtLeast(0)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.UI_VOLUME -> {
            val prev = uiVolumeLevel
            uiVolumeLevel = (uiVolumeLevel - 1).coerceAtLeast(0)
            if (prev != uiVolumeLevel) {
                host.setUiVolume(uiVolumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollUp(); host.navTone() }
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(-15) }
        Panel.OPENCLAW_SETTINGS -> {
            if (openClawSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSettingsFocus--; host.navTone()
            }
        }
        Panel.OPENCLAW_SESSIONS -> {
            if (openClawSessionsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSessionsFocus--; host.navTone()
            }
        }
        Panel.MESSAGES -> {
            if (messagesFocus <= 0) {
                back(); host.backTone()
            } else {
                messagesFocus--; host.navTone()
            }
        }
        Panel.MESSAGES_THREAD -> {
            if (smsThreadFocus <= 0) {
                back(); host.backTone()
            } else {
                smsThreadFocus--; host.navTone()
            }
        }
        Panel.TERMINAL -> { terminalScrollIndex++; host.navTone() }
        Panel.CLAUDE -> { claudeScrollIndex++; host.navTone() }
        Panel.TRANSCRIBER_LIST -> {
            if (transcriberListFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberListFocus--; host.navTone()
            }
        }
        // Recording panel: wheel-up cancels back to list (recording continues
        // in the FGS — user can stop it from the list row by re-entering).
        // Activate (side tap) is the canonical stop gesture.
        Panel.TRANSCRIBER_RECORDING -> { /* no scroll target */ }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                if (transcriberDetailMenuFocus <= 0) {
                    transcriberDetailMenuFocus = 0
                    host.backTone()
                } else {
                    transcriberDetailMenuFocus--; host.navTone()
                }
            } else if (transcriberDetailFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberDetailFocus--; host.navTone()
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            if (transcriberSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberSettingsFocus--; host.navTone()
            }
        }
        Panel.SURVEY_LIST -> {
            if (surveyListFocus <= 0) {
                back(); host.backTone()
            } else {
                surveyListFocus--; host.navTone()
            }
        }
        Panel.SURVEY_CAMPAIGN -> {
            if (surveyCampaignFocus <= 0) {
                back(); host.backTone()
            } else {
                surveyCampaignFocus--; host.navTone()
            }
        }
        Panel.SURVEY_CONSENT -> {
            if (surveyConsentFocus <= 0) {
                back(); host.backTone()
            } else {
                surveyConsentFocus--; host.navTone()
            }
        }
        Panel.SURVEY_LIVE -> { /* side-button hangs up; no scroll target */ }
        Panel.SURVEY_DETAIL -> {
            if (surveyDetailFocus <= 0) {
                back(); host.backTone()
            } else {
                surveyDetailFocus--; host.navTone()
            }
        }
        Panel.SURVEY_SETTINGS -> {
            if (surveySettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                surveySettingsFocus--; host.navTone()
            }
        }
        Panel.HOME -> { /* clock screen — no list to scroll */ }
    }
}

fun LauncherState.wheelDown(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            val max = (apps.size - 1).coerceAtLeast(0)
            val prev = appsFocus
            appsFocus = (appsFocus + 1).coerceAtMost(max)
            if (prev != appsFocus) host.navTone()
        }
        Panel.ONBOARDING -> {
            val max = when (onboardingStep) {
                0 -> com.r1.launcher.locale.LocalePrefs.SUPPORTED.size - 1 // language picker
                1 -> 0          // welcome: only continue
                2 -> 2          // network: connect wi-fi, use cellular, skip
                3 -> 2          // updates: check now, skip, continue
                4 -> 0          // done: only finish
                else -> 0
            }
            val prev = onboardingFocus
            onboardingFocus = (onboardingFocus + 1).coerceAtMost(max)
            if (prev != onboardingFocus) host.navTone()
        }
        Panel.SETTINGS -> {
            val prev = settingsFocus
            settingsFocus = (settingsFocus + 1).coerceAtMost(6) // back, network, display, sound, voice, device, about
            if (prev != settingsFocus) host.navTone()
        }
        Panel.SETTINGS_DISPLAY -> {
            val prev = settingsDisplayFocus
            settingsDisplayFocus = (settingsDisplayFocus + 1).coerceAtMost(1) // back, brightness
            if (prev != settingsDisplayFocus) host.navTone()
        }
        Panel.SETTINGS_SOUND -> {
            val prev = settingsSoundFocus
            settingsSoundFocus = (settingsSoundFocus + 1).coerceAtMost(2) // back, ui sound, speaker
            if (prev != settingsSoundFocus) host.navTone()
        }
        Panel.SETTINGS_DEVICE -> {
            val prev = settingsDeviceFocus
            // back, updates, language, reboot, power off, factory reset
            settingsDeviceFocus = (settingsDeviceFocus + 1).coerceAtMost(5)
            if (prev != settingsDeviceFocus) host.navTone()
        }
        Panel.SETTINGS_ABOUT -> {
            // Single info row, no scroll needed
        }
        Panel.SETTINGS_VOICE -> {
            val prev = voiceFocus
            // back, on/off, key, scan-qr, subscription, voice picker, custom-id, test, tuning, clear
            voiceFocus = (voiceFocus + 1).coerceAtMost(9)
            if (prev != voiceFocus) host.navTone()
        }
        Panel.SETTINGS_VOICE_TUNING -> {
            val prev = voiceTuningFocus
            // back, model, stability, similarity, style, speed, speaker-boost, test, reset
            voiceTuningFocus = (voiceTuningFocus + 1).coerceAtMost(8)
            if (prev != voiceTuningFocus) host.navTone()
        }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> {
            val prev = voiceSubFocus
            // back, refresh
            voiceSubFocus = (voiceSubFocus + 1).coerceAtMost(1)
            if (prev != voiceSubFocus) host.navTone()
        }
        Panel.SETTINGS_LANGUAGE -> {
            // back + N supported languages → max idx = N
            val max = com.r1.launcher.locale.LocalePrefs.SUPPORTED.size
            val prev = settingsLanguageFocus
            settingsLanguageFocus = (settingsLanguageFocus + 1).coerceAtMost(max)
            if (prev != settingsLanguageFocus) host.navTone()
        }
        Panel.NETWORK -> {
            val prev = networkFocus
            networkFocus = (networkFocus + 1).coerceAtMost(7) // back, wifi, cellular, bluetooth, share, remote, terminal, scan
            if (prev != networkFocus) host.navTone()
        }
        Panel.FACTORY_CONFIRM -> {
            val prev = factoryConfirmFocus
            factoryConfirmFocus = (factoryConfirmFocus + 1).coerceAtMost(1)
            if (prev != factoryConfirmFocus) host.navTone()
        }
        Panel.WIFI_SCAN -> {
            val max = (wifiScanResults.size).coerceAtLeast(0) // back + N items
            val prev = wifiScanFocus
            wifiScanFocus = (wifiScanFocus + 1).coerceAtMost(max)
            if (prev != wifiScanFocus) host.navTone()
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
        Panel.WIFI_SHARE -> {
            val prev = wifiShareFocus
            wifiShareFocus = (wifiShareFocus + 1).coerceAtMost(5) // back, enable, name, password, connected, auto-off
            if (prev != wifiShareFocus) host.navTone()
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel + 16).coerceAtMost(255)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel + 1).coerceAtMost(volumeMax)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.UI_VOLUME -> {
            val prev = uiVolumeLevel
            uiVolumeLevel = (uiVolumeLevel + 1).coerceAtMost(uiVolumeMax)
            if (prev != uiVolumeLevel) {
                host.setUiVolume(uiVolumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollDown(); host.navTone() }
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(+15) }
        Panel.OPENCLAW_SETTINGS -> {
            val prev = openClawSettingsFocus
            openClawSettingsFocus = (openClawSettingsFocus + 1).coerceAtMost(5)
            if (prev != openClawSettingsFocus) host.navTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            // Row layout matches OpenClawSessionsPanel:
            //   0             "< back"
            //   1             "+ new thread"
            //   2..choices+1  one row per resolveSessionChoices entry
            //                (or a single placeholder row when choices is empty)
            //   choices+2     "refresh"
            val choiceCount = com.r1.launcher.openclaw.resolveSessionChoices(
                currentSessionKey = selectedSessionKey,
                sessions = chatSessions.toList(),
                mainSessionKey = mainSessionKey,
            ).size.coerceAtLeast(1)
            val max = 2 + choiceCount // back + new + choices + refresh; last index = 2+choices
            val prev = openClawSessionsFocus
            openClawSessionsFocus = (openClawSessionsFocus + 1).coerceAtMost(max)
            if (prev != openClawSessionsFocus) host.navTone()
        }
        Panel.MESSAGES -> {
            // Row 0 = back, then one row per conversation. Empty list still has
            // a single "no messages" row, but it isn't selectable — keep focus on back.
            val maxRow = smsConversations.size // back + N convs; last index = N
            val prev = messagesFocus
            messagesFocus = (messagesFocus + 1).coerceAtMost(maxRow)
            if (prev != messagesFocus) host.navTone()
        }
        Panel.MESSAGES_THREAD -> {
            val maxRow = smsThreadMessages.size // back + N items; last index = N
            val prev = smsThreadFocus
            smsThreadFocus = (smsThreadFocus + 1).coerceAtMost(maxRow)
            if (prev != smsThreadFocus) host.navTone()
        }
        Panel.TERMINAL -> {
            val prev = terminalScrollIndex
            terminalScrollIndex = (terminalScrollIndex - 1).coerceAtLeast(0)
            if (prev != terminalScrollIndex) host.navTone()
        }
        Panel.CLAUDE -> {
            val prev = claudeScrollIndex
            claudeScrollIndex = (claudeScrollIndex - 1).coerceAtLeast(0)
            if (prev != claudeScrollIndex) host.navTone()
        }
        Panel.TRANSCRIBER_LIST -> {
            // 0=back, 1="+ new recording", 2..=meetings ; +1 for settings shortcut at the end
            val max = 2 + meetings.size  // back + new + N meetings + settings = last idx N+2
            val prev = transcriberListFocus
            transcriberListFocus = (transcriberListFocus + 1).coerceAtMost(max)
            if (prev != transcriberListFocus) host.navTone()
        }
        Panel.TRANSCRIBER_RECORDING -> { /* no scroll target */ }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                val max = (transcriberDetailMenuActions.size - 1).coerceAtLeast(0)
                val prev = transcriberDetailMenuFocus
                transcriberDetailMenuFocus = (transcriberDetailMenuFocus + 1).coerceAtMost(max)
                if (prev != transcriberDetailMenuFocus) host.navTone()
            } else {
                val prev = transcriberDetailFocus
                // back, ⋮ — actions are now hidden behind the menu
                transcriberDetailFocus = (transcriberDetailFocus + 1).coerceAtMost(1)
                if (prev != transcriberDetailFocus) host.navTone()
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            val prev = transcriberSettingsFocus
            // back, host, port, user, password, recipient, clear
            transcriberSettingsFocus = (transcriberSettingsFocus + 1).coerceAtMost(6)
            if (prev != transcriberSettingsFocus) host.navTone()
        }
        Panel.SURVEY_LIST -> {
            // 0=back, 1=settings, 2="+ new campaign", 3..=campaigns rows
            val max = 2 + campaigns.size
            val prev = surveyListFocus
            surveyListFocus = (surveyListFocus + 1).coerceAtMost(max)
            if (prev != surveyListFocus) host.navTone()
        }
        Panel.SURVEY_CAMPAIGN -> {
            // 0=back, 1=pick survey, 2=pick contacts, 3=start
            val prev = surveyCampaignFocus
            surveyCampaignFocus = (surveyCampaignFocus + 1).coerceAtMost(3)
            if (prev != surveyCampaignFocus) host.navTone()
        }
        Panel.SURVEY_CONSENT -> {
            // 0=cancel, 1=confirm + dial
            val prev = surveyConsentFocus
            surveyConsentFocus = (surveyConsentFocus + 1).coerceAtMost(1)
            if (prev != surveyConsentFocus) host.navTone()
        }
        Panel.SURVEY_LIVE -> { /* side-button hangs up; no scroll target */ }
        Panel.SURVEY_DETAIL -> {
            // 0=back, 1=⋮
            val prev = surveyDetailFocus
            surveyDetailFocus = (surveyDetailFocus + 1).coerceAtMost(1)
            if (prev != surveyDetailFocus) host.navTone()
        }
        Panel.SURVEY_SETTINGS -> {
            // back, openai-key, sip-host, sip-user, sip-password, sip-from,
            // consent text, voice cycle, summarizer model, email recipient, clear
            val prev = surveySettingsFocus
            surveySettingsFocus = (surveySettingsFocus + 1).coerceAtMost(10)
            if (prev != surveySettingsFocus) host.navTone()
        }
        Panel.HOME -> {
            openApps()
            host.selectTone()
        }
    }
}

fun LauncherState.activate(host: LauncherHost) {
    when (panel) {
        // Don't launch here — bump the press trigger so the focused AppCard
        // runs its dip-and-bounce animation, then the AppCard's LaunchedEffect
        // calls launchApp() at the tail. Matches the touch-tap experience.
        Panel.APPS -> appsPressTrigger++
        Panel.ONBOARDING -> when (onboardingStep) {
            0 -> {
                // Language picker. Picking sets pref + recreate(); after recreate
                // onCreate jumps to step 1 (welcome) automatically. Network requests
                // remain blocked because isOnboarding stays true.
                val lang = com.r1.launcher.locale.LocalePrefs.SUPPORTED.getOrNull(onboardingFocus)
                if (lang != null) {
                    host.setLanguage(lang.code)
                    host.selectTone()
                }
            }
            1 -> { advanceOnboarding(); host.selectTone() }
            2 -> when (onboardingFocus) {
                0 -> { openWifiScan(); host.startWifiScan(); host.selectTone() }
                1 -> if (simPresent) { advanceOnboarding(); host.selectTone() } else host.backTone()
                2 -> { advanceOnboarding(); host.selectTone() }
            }
            3 -> when (onboardingFocus) {
                0 -> { host.checkForUpdate(); host.selectTone() }
                1 -> { advanceOnboarding(); host.selectTone() }
                2 -> { advanceOnboarding(); host.selectTone() }
            }
            4 -> { host.onOnboardingDone(); host.selectTone() }
        }
        Panel.SETTINGS -> when (settingsFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openNetwork(); host.selectTone() }
            2 -> { openSettingsDisplay(); host.selectTone() }
            3 -> { openSettingsSound(); host.selectTone() }
            4 -> { openSettingsVoice(); host.selectTone() }
            5 -> { openSettingsDevice(); host.selectTone() }
            6 -> { openSettingsAbout(); host.selectTone() }
        }
        Panel.SETTINGS_LANGUAGE -> {
            if (settingsLanguageFocus == 0) {
                back(); host.backTone()
            } else {
                val lang = com.r1.launcher.locale.LocalePrefs.SUPPORTED
                    .getOrNull(settingsLanguageFocus - 1)
                if (lang != null) {
                    host.setLanguage(lang.code) // triggers recreate(); state.back() not needed
                    host.selectTone()
                }
            }
        }
        Panel.SETTINGS_VOICE -> { host.voiceSettingsRowActivate(voiceFocus); host.selectTone() }
        Panel.SETTINGS_VOICE_TUNING -> { host.voiceTuningRowActivate(voiceTuningFocus); host.selectTone() }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> when (voiceSubFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.voiceFetchSubscription(force = true); host.popTone() }
        }
        Panel.SETTINGS_DISPLAY -> when (settingsDisplayFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openBrightness(); host.selectTone() }
        }
        Panel.SETTINGS_SOUND -> when (settingsSoundFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openUiVolume(); host.selectTone() }
            2 -> { openVolume(); host.selectTone() }
        }
        Panel.SETTINGS_DEVICE -> when (settingsDeviceFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.checkForUpdate(); host.selectTone() }
            2 -> { openSettingsLanguage(); host.selectTone() }
            3 -> { host.rebootDevice(); host.selectTone() }
            4 -> { host.powerOffDevice(); host.selectTone() }
            5 -> { openFactoryConfirm(); host.selectTone() }
        }
        Panel.SETTINGS_ABOUT -> { back(); host.backTone() }
        Panel.NETWORK -> when (networkFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifi(!wifiEnabled); host.popTone() }
            2 -> { host.toggleCellular(!cellularOn); host.popTone() }
            3 -> { host.toggleBluetooth(!btOn); host.popTone() }
            4 -> { openWifiShare(); host.selectTone() }
            5 -> { host.toggleWebServer(!webServerEnabled); host.popTone() }
            6 -> { host.setWebTerminalEnabled(!webTerminalEnabled); host.popTone() }
            7 -> { host.startWifiScan(); openWifiScan(); host.selectTone() }
        }
        Panel.WIFI_SHARE -> when (wifiShareFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifiShare(!wifiShareEnabled); host.popTone() }
            2 -> { openWifiShareEdit(WifiShareEditTarget.SSID); host.selectTone() }
            3 -> { openWifiShareEdit(WifiShareEditTarget.PASSWORD); host.selectTone() }
            4 -> {
                if (wifiShareConnectedClients.isNotEmpty()) {
                    wifiShareClientsExpanded = !wifiShareClientsExpanded
                    host.popTone()
                }
            }
            5 -> { host.wifiShareCycleTimer(); host.popTone() }
        }
        Panel.WIFI_SHARE_EDIT -> { /* RetroKeyboard handles input */ }
        Panel.FACTORY_CONFIRM -> when (factoryConfirmFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.factoryReset(); host.selectTone() }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus == 0) {
                back(); host.backTone()
            } else {
                val ssid = wifiScanResults.getOrNull(wifiScanFocus - 1)
                if (ssid != null) {
                    openWifiPassword(ssid)
                    host.selectTone()
                }
            }
        }
        Panel.WIFI_PASSWORD -> { /* RetroKeyboard handles input */ }
        Panel.BRIGHTNESS, Panel.VOLUME, Panel.UI_VOLUME -> { back(); host.selectTone() }
        Panel.OPENCLAW_QR -> { /* camera scan auto-completes; activate is no-op */ }
        Panel.OPENCLAW_CHAT -> { host.openClawToggleRecord(); host.popTone() }
        Panel.OPENCLAW_CAMERA -> { /* touch-first capture/ask surface */ }
        Panel.OPENCLAW_SETTINGS -> {
            host.openClawSettingsRowActivate(openClawSettingsFocus)
            host.selectTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            host.openClawSessionsRowActivate(openClawSessionsFocus)
            host.selectTone()
        }
        Panel.MESSAGES -> {
            if (messagesFocus == 0) {
                back(); host.backTone()
            } else {
                val conv = smsConversations.getOrNull(messagesFocus - 1)
                if (conv != null) {
                    host.openSmsThread(conv.address, conv.displayName)
                    host.selectTone()
                }
            }
        }
        Panel.MESSAGES_THREAD -> {
            // Only the back row at idx 0 is actionable; bubbles are read-only.
            if (smsThreadFocus == 0) { back(); host.backTone() }
        }
        Panel.TERMINAL -> {
            val cmd = terminalInput.trim()
            if (cmd.isNotEmpty()) {
                host.terminalRun(cmd)
                host.popTone()
            }
        }
        Panel.CLAUDE -> {
            // Redirect screen: wheel-press is the primary "open anyway"
            // action so users can dismiss the hint without reaching the
            // touch target on a 480x480 round screen. Mirror the visibility
            // gate from ClaudePanel — only logged-out users see the hint,
            // so only they need the dismiss shortcut.
            if (!claudeAuthed && claudeShowWebHint) {
                claudeShowWebHint = false
                host.selectTone()
            } else {
                val text = claudeInput.trim()
                if (text.isNotEmpty()) {
                    host.claudeSend(text)
                    host.popTone()
                }
            }
        }
        // Wheel press on the clock screen jumps straight to the apps grid.
        Panel.HOME -> { openApps(); host.selectTone() }
        Panel.TRANSCRIBER_LIST -> when {
            // 0=back, 1=settings, 2=record, 3..N+2=meetings
            transcriberListFocus == 0 -> { back(); host.backTone() }
            transcriberListFocus == 1 -> { host.transcriberOpenSettings(); host.selectTone() }
            transcriberListFocus == 2 -> { host.transcriberStartRecording(); host.popTone() }
            transcriberListFocus - 3 in meetings.indices -> {
                val m = meetings[transcriberListFocus - 3]
                host.transcriberOpenDetail(m.uuid)
                host.selectTone()
            }
        }
        Panel.TRANSCRIBER_RECORDING -> { host.transcriberStopRecording(); host.popTone() }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                val action = transcriberDetailMenuActions.getOrNull(transcriberDetailMenuFocus)
                if (action != null) {
                    host.transcriberDetailMenuActivate(action)
                    host.popTone()
                }
            } else when (transcriberDetailFocus) {
                0 -> { back(); host.backTone() }
                1 -> { host.transcriberOpenDetailMenu(); host.selectTone() }
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            host.transcriberSettingsRowActivate(transcriberSettingsFocus)
            host.selectTone()
        }
        Panel.SURVEY_LIST -> {
            host.surveyListRowActivate(surveyListFocus)
            host.selectTone()
        }
        Panel.SURVEY_CAMPAIGN -> {
            host.surveyCampaignRowActivate(surveyCampaignFocus)
            host.selectTone()
        }
        Panel.SURVEY_CONSENT -> when (surveyConsentFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.surveyConfirmCampaignAndDial(); host.selectTone() }
        }
        // Side-button activate while on the live call panel = hang up.
        Panel.SURVEY_LIVE -> { host.surveyHangup(); host.popTone() }
        Panel.SURVEY_DETAIL -> when (surveyDetailFocus) {
            0 -> { back(); host.backTone() }
            // No multi-action menu yet — single action is "email this call".
            // Re-summary + delete + playback live on the web companion for now.
            1 -> currentCallRecordId?.let { host.surveyEmailCallRecord(it); host.selectTone() }
        }
        Panel.SURVEY_SETTINGS -> {
            host.surveySettingsRowActivate(surveySettingsFocus)
            host.selectTone()
        }
    }
}

fun LauncherState.backPressed(host: LauncherHost) {
    if (panel == Panel.OPENCLAW_CHAT || panel == Panel.OPENCLAW_QR) {
        host.openClawCloseSession()
        back(); host.backTone()
        return
    }
    if (panel == Panel.OPENCLAW_CAMERA) {
        back(); host.backTone()
        return
    }
    // Back from the recording panel always stops the FGS — leaving a recording
    // running while the user navigates away is almost never what they want
    // (and would chew battery indefinitely).
    if (panel == Panel.TRANSCRIBER_RECORDING) {
        host.transcriberStopRecording()
        back(); host.backTone()
        return
    }
    // Back from a live survey call hangs up first — same rationale.
    if (panel == Panel.SURVEY_LIVE) {
        host.surveyHangup()
        back(); host.backTone()
        return
    }
    // On the detail page, back first closes the ⋮ overlay if it's open. Only
    // a second back unwinds to the list.
    if (panel == Panel.TRANSCRIBER_DETAIL && transcriberDetailMenuOpen) {
        transcriberDetailMenuOpen = false
        transcriberDetailMenuFocus = 0
        host.backTone()
        return
    }
    if (panel != Panel.HOME) {
        back(); host.backTone()
    }
}
