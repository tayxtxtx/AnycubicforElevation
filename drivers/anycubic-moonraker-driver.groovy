/**
 *  Anycubic (Moonraker) Driver for Hubitat Elevation
 *
 *  Talks to a Klipper/Moonraker HTTP API on the local network. Works with any
 *  Anycubic printer running Klipper + Moonraker (e.g. Kobra 3, modded Kobra 2
 *  series), and with any other Klipper printer for that matter.
 *
 *  Licensed under the MIT License.
 */

metadata {
    definition(
        name: "Anycubic (Moonraker) 3D Printer",
        namespace: "anycubicforelevation",
        author: "AnycubicforElevation",
        importUrl: ""
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "Initialize"

        attribute "printerStatus", "string"      // ready, printing, paused, complete, cancelled, error, offline
        attribute "printState", "string"         // raw state from Moonraker print_stats
        attribute "printProgress", "number"      // 0-100
        attribute "currentFile", "string"
        attribute "printTimeElapsed", "string"   // hh:mm:ss
        attribute "printTimeRemaining", "string" // hh:mm:ss (estimate)
        attribute "totalDuration", "number"      // seconds
        attribute "filamentUsed", "number"       // millimeters
        attribute "nozzleTemperature", "number"
        attribute "nozzleTarget", "number"
        attribute "bedTemperature", "number"
        attribute "bedTarget", "number"
        attribute "klippyState", "string"        // ready, error, shutdown, startup
        attribute "lastError", "string"

        command "pausePrint"
        command "resumePrint"
        command "cancelPrint"
        command "emergencyStop"
        command "firmwareRestart"
        command "clearError"
    }

    preferences {
        input name: "ipAddress", type: "string", title: "Printer IP address",
              description: "e.g. 192.168.1.50", required: true
        input name: "port", type: "number", title: "Moonraker port",
              defaultValue: 7125, required: true
        input name: "apiKey", type: "string", title: "Moonraker API key (optional)",
              description: "Leave blank if Moonraker trusts this host", required: false
        input name: "pollSeconds", type: "number", title: "Poll interval (seconds)",
              defaultValue: 30, required: true
        input name: "pollWhilePrintingSeconds", type: "number",
              title: "Poll interval while printing (seconds)", defaultValue: 10, required: true
        input name: "primaryTemp", type: "enum", title: "Temperature reported as device temperature",
              options: ["nozzle", "bed"], defaultValue: "nozzle", required: true
        input name: "switchSemantics", type: "enum",
              title: "Switch state reflects",
              options: ["printing", "online"],
              defaultValue: "printing", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
    }
}

/* -------- lifecycle -------- */

void installed() {
    log.info "Anycubic Moonraker driver installed"
    initialize()
}

void updated() {
    log.info "Anycubic Moonraker driver updated"
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    state.lastPollOk = false
    state.printingPoll = false
    schedulePoll(false)
    runIn(2, "refresh")
}

void logsOff() {
    log.warn "debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* -------- capability commands -------- */

void on() {
    // Switch capability is informational only; we don't power-cycle the printer.
    if (txtEnable) log.info "${device.displayName}: 'on' has no effect (status reflects printer state)"
    refresh()
}

void off() {
    if (txtEnable) log.info "${device.displayName}: 'off' has no effect (status reflects printer state)"
    refresh()
}

void refresh() {
    queryStatus()
}

/* -------- printer commands -------- */

void pausePrint()     { postEndpoint("/printer/print/pause") }
void resumePrint()    { postEndpoint("/printer/print/resume") }
void cancelPrint()    { postEndpoint("/printer/print/cancel") }
void emergencyStop()  { postEndpoint("/printer/emergency_stop") }
void firmwareRestart() { postEndpoint("/printer/firmware_restart") }

void clearError() {
    sendEvent(name: "lastError", value: "")
}

/* -------- polling -------- */

private void schedulePoll(boolean printing) {
    Integer interval = (printing ? (pollWhilePrintingSeconds ?: 10) : (pollSeconds ?: 30)) as Integer
    if (interval < 5) interval = 5
    state.printingPoll = printing
    unschedule("queryStatus")
    if (interval >= 60 && interval % 60 == 0) {
        schedule("0 */${interval.intdiv(60)} * * * ?", "queryStatus")
    } else {
        schedule("0/${interval} * * * * ?", "queryStatus")
    }
    if (logEnable) log.debug "polling every ${interval}s (printing=${printing})"
}

void queryStatus() {
    if (!ipAddress) {
        log.warn "no IP address configured"
        return
    }
    String objects = [
        "print_stats",
        "heater_bed",
        "extruder",
        "virtual_sdcard",
        "display_status",
        "toolhead",
        "webhooks"
    ].collect { "${it}" }.join("&")
    String url = "http://${ipAddress}:${port ?: 7125}/printer/objects/query?${objects}"
    Map params = [uri: url, headers: authHeaders(), timeout: 10]
    asynchttpGet("statusHandler", params)
}

/* -------- HTTP handlers -------- */

void statusHandler(resp, data) {
    try {
        if (resp == null || resp.status == null) {
            markOffline("no response")
            return
        }
        if (resp.status == 401 || resp.status == 403) {
            markOffline("unauthorized (check API key)")
            return
        }
        if (resp.status >= 400) {
            // Moonraker returns 503 with klippy_state when Klipper is down
            if (resp.status == 503) {
                Map body = safeJson(resp)
                String klippy = body?.error?.message ?: "klippy not ready"
                sendIfChanged("klippyState", "shutdown")
                sendIfChanged("printerStatus", "error")
                sendIfChanged("lastError", klippy)
                return
            }
            markOffline("HTTP ${resp.status}")
            return
        }

        Map body = safeJson(resp)
        Map status = body?.result?.status ?: [:]
        if (!status) {
            markOffline("empty status payload")
            return
        }

        state.lastPollOk = true

        Map printStats = (status.print_stats ?: [:]) as Map
        Map bed = (status.heater_bed ?: [:]) as Map
        Map ext = (status.extruder ?: [:]) as Map
        Map sd  = (status.virtual_sdcard ?: [:]) as Map
        Map disp = (status.display_status ?: [:]) as Map
        Map hooks = (status.webhooks ?: [:]) as Map

        String klippy = (hooks.state ?: "ready") as String
        sendIfChanged("klippyState", klippy)

        String rawState = (printStats.state ?: "standby") as String
        sendIfChanged("printState", rawState)

        String mapped = mapPrinterStatus(rawState, klippy)
        sendIfChanged("printerStatus", mapped)

        String filename = (printStats.filename ?: "") as String
        sendIfChanged("currentFile", filename)

        BigDecimal progressPct = 0
        if (disp?.progress != null) {
            progressPct = ((disp.progress as BigDecimal) * 100).setScale(1, BigDecimal.ROUND_HALF_UP)
        } else if (sd?.progress != null) {
            progressPct = ((sd.progress as BigDecimal) * 100).setScale(1, BigDecimal.ROUND_HALF_UP)
        }
        sendIfChanged("printProgress", progressPct)

        Number printDuration = (printStats.print_duration ?: 0) as Number
        Number totalDuration = (printStats.total_duration ?: 0) as Number
        sendIfChanged("totalDuration", totalDuration)
        sendIfChanged("printTimeElapsed", formatDuration(printDuration?.longValue() ?: 0L))

        Long remaining = estimateRemaining(printDuration?.doubleValue() ?: 0d,
                                           progressPct?.doubleValue() ?: 0d)
        sendIfChanged("printTimeRemaining", formatDuration(remaining))

        Number filamentUsed = (printStats.filament_used ?: 0) as Number
        sendIfChanged("filamentUsed", filamentUsed)

        BigDecimal nozzleTemp = roundTemp(ext?.temperature)
        BigDecimal nozzleTgt  = roundTemp(ext?.target)
        BigDecimal bedTemp    = roundTemp(bed?.temperature)
        BigDecimal bedTgt     = roundTemp(bed?.target)
        sendIfChanged("nozzleTemperature", nozzleTemp)
        sendIfChanged("nozzleTarget", nozzleTgt)
        sendIfChanged("bedTemperature", bedTemp)
        sendIfChanged("bedTarget", bedTgt)

        BigDecimal primary = (primaryTemp == "bed") ? bedTemp : nozzleTemp
        if (primary != null) {
            sendIfChanged("temperature", primary, [unit: "C"])
        }

        String message = (printStats.message ?: "") as String
        if (rawState == "error" && message) {
            sendIfChanged("lastError", message)
        }

        boolean isPrinting = (mapped == "printing")
        String switchVal
        if (switchSemantics == "online") {
            switchVal = "on"
        } else {
            switchVal = isPrinting ? "on" : "off"
        }
        sendIfChanged("switch", switchVal)

        if (isPrinting != state.printingPoll) {
            schedulePoll(isPrinting)
        }
    } catch (Throwable t) {
        log.error "statusHandler failed: ${t.message}"
        if (logEnable) log.debug "stack: ${t}"
    }
}

void postHandler(resp, data) {
    String endpoint = data?.endpoint ?: "?"
    if (resp?.status == null) {
        log.warn "command ${endpoint}: no response"
        return
    }
    if (resp.status >= 400) {
        Map body = safeJson(resp)
        String err = body?.error?.message ?: "HTTP ${resp.status}"
        log.warn "command ${endpoint} failed: ${err}"
        sendEvent(name: "lastError", value: err)
        return
    }
    if (txtEnable) log.info "command ${endpoint} ok"
    runIn(2, "queryStatus")
}

/* -------- helpers -------- */

private void postEndpoint(String path) {
    if (!ipAddress) {
        log.warn "no IP address configured"
        return
    }
    String url = "http://${ipAddress}:${port ?: 7125}${path}"
    Map params = [uri: url, headers: authHeaders(), timeout: 10]
    asynchttpPost("postHandler", params, [endpoint: path])
}

private Map authHeaders() {
    Map h = ["Accept": "application/json"]
    if (apiKey) h["X-Api-Key"] = apiKey
    return h
}

private Map safeJson(resp) {
    try {
        return (resp?.json instanceof Map) ? (resp.json as Map) : [:]
    } catch (ignored) {
        try { return new groovy.json.JsonSlurper().parseText(resp?.data as String) as Map }
        catch (ignored2) { return [:] }
    }
}

private String mapPrinterStatus(String rawState, String klippy) {
    if (klippy && klippy != "ready") {
        if (klippy == "shutdown" || klippy == "error") return "error"
        if (klippy == "startup") return "offline"
    }
    switch (rawState) {
        case "printing": return "printing"
        case "paused":   return "paused"
        case "complete": return "complete"
        case "cancelled":
        case "canceled": return "cancelled"
        case "error":    return "error"
        case "standby":
        default:         return "ready"
    }
}

private void markOffline(String reason) {
    if (logEnable) log.debug "offline: ${reason}"
    state.lastPollOk = false
    sendIfChanged("printerStatus", "offline")
    sendIfChanged("klippyState", "offline")
    sendIfChanged("lastError", reason)
    if (switchSemantics == "online") {
        sendIfChanged("switch", "off")
    } else {
        sendIfChanged("switch", "off")
    }
}

private void sendIfChanged(String name, def value, Map extra = [:]) {
    if (value == null) return
    def current = device.currentValue(name)
    if (current?.toString() == value?.toString()) return
    Map evt = [name: name, value: value]
    if (extra) evt.putAll(extra)
    if (txtEnable && name in ["printerStatus", "currentFile", "printProgress"]) {
        evt.descriptionText = "${device.displayName} ${name} is ${value}"
        log.info evt.descriptionText
    }
    sendEvent(evt)
}

private BigDecimal roundTemp(def v) {
    if (v == null) return null
    return (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
}

private Long estimateRemaining(double elapsedSec, double progressPct) {
    if (progressPct <= 0.1d || elapsedSec <= 0d) return 0L
    double total = elapsedSec * (100d / progressPct)
    long remaining = Math.max(0L, Math.round(total - elapsedSec))
    return remaining
}

private String formatDuration(long seconds) {
    if (seconds <= 0) return "00:00:00"
    long h = seconds.intdiv(3600)
    long m = (seconds % 3600).intdiv(60)
    long s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
