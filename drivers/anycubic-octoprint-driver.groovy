/**
 *  Anycubic (OctoPrint) Driver for Hubitat Elevation
 *
 *  Talks to an OctoPrint instance acting as a USB bridge for an Anycubic
 *  printer (Mega, Chiron, Vyper, i3, Photon, older Kobra, etc.). Uses
 *  OctoPrint's REST API.
 *
 *  Licensed under the MIT License.
 */

metadata {
    definition(
        name: "Anycubic (OctoPrint) 3D Printer",
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
        attribute "printerState", "string"       // raw OctoPrint state string
        attribute "printProgress", "number"      // 0-100
        attribute "currentFile", "string"
        attribute "printTimeElapsed", "string"
        attribute "printTimeRemaining", "string"
        attribute "nozzleTemperature", "number"
        attribute "nozzleTarget", "number"
        attribute "bedTemperature", "number"
        attribute "bedTarget", "number"
        attribute "lastError", "string"

        command "pausePrint"
        command "resumePrint"
        command "cancelPrint"
        command "connectPrinter"
        command "disconnectPrinter"
        command "clearError"
    }

    preferences {
        input name: "host", type: "string", title: "OctoPrint host (IP or hostname)",
              description: "e.g. 192.168.1.50 or octopi.local", required: true
        input name: "port", type: "number", title: "OctoPrint port", defaultValue: 80, required: true
        input name: "useHttps", type: "bool", title: "Use HTTPS", defaultValue: false
        input name: "apiKey", type: "string", title: "OctoPrint API key", required: true,
              description: "Settings -> API in OctoPrint"
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
    log.info "Anycubic OctoPrint driver installed"
    initialize()
}

void updated() {
    log.info "Anycubic OctoPrint driver updated"
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

void on()  { if (txtEnable) log.info "${device.displayName}: 'on' is informational only";  refresh() }
void off() { if (txtEnable) log.info "${device.displayName}: 'off' is informational only"; refresh() }

void refresh() {
    queryJob()
    queryPrinter()
}

/* -------- printer commands -------- */

void pausePrint()  { jobCommand([command: "pause", action: "pause"]) }
void resumePrint() { jobCommand([command: "pause", action: "resume"]) }
void cancelPrint() { jobCommand([command: "cancel"]) }

void connectPrinter()    { connectionCommand([command: "connect"]) }
void disconnectPrinter() { connectionCommand([command: "disconnect"]) }

void clearError() {
    sendEvent(name: "lastError", value: "")
}

/* -------- polling -------- */

private void schedulePoll(boolean printing) {
    Integer interval = (printing ? (pollWhilePrintingSeconds ?: 10) : (pollSeconds ?: 30)) as Integer
    if (interval < 5) interval = 5
    state.printingPoll = printing
    unschedule("poll")
    if (interval >= 60 && interval % 60 == 0) {
        schedule("0 */${interval.intdiv(60)} * * * ?", "poll")
    } else {
        schedule("0/${interval} * * * * ?", "poll")
    }
    if (logEnable) log.debug "polling every ${interval}s (printing=${printing})"
}

void poll() {
    queryJob()
    queryPrinter()
}

/* -------- HTTP requests -------- */

private void queryJob() {
    asynchttpGet("jobHandler", [uri: baseUrl() + "/api/job", headers: authHeaders(), timeout: 10])
}

private void queryPrinter() {
    asynchttpGet("printerHandler", [uri: baseUrl() + "/api/printer", headers: authHeaders(), timeout: 10])
}

private void jobCommand(Map cmd) {
    Map params = [
        uri: baseUrl() + "/api/job",
        headers: authHeaders() + ["Content-Type": "application/json"],
        body: groovy.json.JsonOutput.toJson(cmd),
        timeout: 10
    ]
    asynchttpPost("commandHandler", params, [what: "job ${cmd.command}/${cmd.action ?: ''}"])
}

private void connectionCommand(Map cmd) {
    Map params = [
        uri: baseUrl() + "/api/connection",
        headers: authHeaders() + ["Content-Type": "application/json"],
        body: groovy.json.JsonOutput.toJson(cmd),
        timeout: 10
    ]
    asynchttpPost("commandHandler", params, [what: "connection ${cmd.command}"])
}

/* -------- handlers -------- */

void jobHandler(resp, data) {
    try {
        if (!validResponse(resp, "job")) return
        Map body = safeJson(resp)
        Map job = (body.job ?: [:]) as Map
        Map progress = (body.progress ?: [:]) as Map
        String stateStr = (body.state ?: "Offline") as String
        Map file = (job.file ?: [:]) as Map

        sendIfChanged("printerState", stateStr)
        String mapped = mapState(stateStr)
        sendIfChanged("printerStatus", mapped)

        sendIfChanged("currentFile", (file.name ?: "") as String)

        Number completion = (progress.completion ?: 0) as Number
        BigDecimal pct = (completion as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
        sendIfChanged("printProgress", pct)

        Long elapsed = ((progress.printTime ?: 0) as Number).longValue()
        Long remaining = ((progress.printTimeLeft ?: 0) as Number).longValue()
        sendIfChanged("printTimeElapsed", formatDuration(elapsed))
        sendIfChanged("printTimeRemaining", formatDuration(remaining))

        boolean isPrinting = (mapped == "printing")
        String switchVal = (switchSemantics == "online") ? "on" : (isPrinting ? "on" : "off")
        sendIfChanged("switch", switchVal)
        if (isPrinting != state.printingPoll) {
            schedulePoll(isPrinting)
        }
    } catch (Throwable t) {
        log.error "jobHandler failed: ${t.message}"
    }
}

void printerHandler(resp, data) {
    try {
        if (resp?.status == 409) {
            // Printer not operational/connected; OctoPrint returns 409.
            sendIfChanged("nozzleTemperature", null)
            return
        }
        if (!validResponse(resp, "printer")) return
        Map body = safeJson(resp)
        Map temps = (body.temperature ?: [:]) as Map
        Map tool0 = (temps.tool0 ?: [:]) as Map
        Map bed   = (temps.bed ?: [:]) as Map

        BigDecimal nozzleTemp = roundTemp(tool0.actual)
        BigDecimal nozzleTgt  = roundTemp(tool0.target)
        BigDecimal bedTemp    = roundTemp(bed.actual)
        BigDecimal bedTgt     = roundTemp(bed.target)

        sendIfChanged("nozzleTemperature", nozzleTemp)
        sendIfChanged("nozzleTarget", nozzleTgt)
        sendIfChanged("bedTemperature", bedTemp)
        sendIfChanged("bedTarget", bedTgt)

        BigDecimal primary = (primaryTemp == "bed") ? bedTemp : nozzleTemp
        if (primary != null) {
            sendIfChanged("temperature", primary, [unit: "C"])
        }
    } catch (Throwable t) {
        log.error "printerHandler failed: ${t.message}"
    }
}

void commandHandler(resp, data) {
    String what = data?.what ?: "?"
    if (resp?.status == null) {
        log.warn "command ${what}: no response"
        return
    }
    if (resp.status >= 400) {
        log.warn "command ${what} failed: HTTP ${resp.status}"
        sendEvent(name: "lastError", value: "HTTP ${resp.status} on ${what}")
        return
    }
    if (txtEnable) log.info "command ${what} ok"
    runIn(2, "poll")
}

/* -------- helpers -------- */

private boolean validResponse(resp, String label) {
    if (resp?.status == null) {
        markOffline("no response from ${label}")
        return false
    }
    if (resp.status == 401 || resp.status == 403) {
        markOffline("unauthorized (check API key)")
        return false
    }
    if (resp.status >= 400 && resp.status != 409) {
        markOffline("HTTP ${resp.status} on ${label}")
        return false
    }
    state.lastPollOk = true
    return true
}

private String baseUrl() {
    String scheme = useHttps ? "https" : "http"
    return "${scheme}://${host}:${port ?: 80}"
}

private Map authHeaders() {
    return ["Accept": "application/json", "X-Api-Key": (apiKey ?: "")]
}

private Map safeJson(resp) {
    try {
        return (resp?.json instanceof Map) ? (resp.json as Map) : [:]
    } catch (ignored) {
        try { return new groovy.json.JsonSlurper().parseText(resp?.data as String) as Map }
        catch (ignored2) { return [:] }
    }
}

private String mapState(String raw) {
    if (!raw) return "offline"
    String r = raw.toLowerCase()
    if (r.contains("printing from sd") || r == "printing") return "printing"
    if (r.contains("paused")) return "paused"
    if (r.contains("pausing")) return "paused"
    if (r.contains("cancel")) return "cancelled"
    if (r.contains("operational")) return "ready"
    if (r.contains("finishing")) return "printing"
    if (r.contains("starting")) return "printing"
    if (r.contains("offline")) return "offline"
    if (r.contains("closed")) return "offline"
    if (r.contains("error")) return "error"
    if (r.contains("complete")) return "complete"
    return r
}

private void markOffline(String reason) {
    if (logEnable) log.debug "offline: ${reason}"
    state.lastPollOk = false
    sendIfChanged("printerStatus", "offline")
    sendIfChanged("printerState", "Offline")
    sendIfChanged("lastError", reason)
    sendIfChanged("switch", "off")
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

private String formatDuration(long seconds) {
    if (seconds <= 0) return "00:00:00"
    long h = seconds.intdiv(3600)
    long m = (seconds % 3600).intdiv(60)
    long s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
