/**
 *  Anycubic Cloud Manager — Hubitat App
 *
 *  Parent app for monitoring and controlling stock-firmware Anycubic 3D
 *  printers via Anycubic Cloud over HTTPS. Polls Anycubic's REST API on a
 *  configurable interval and creates one child "Anycubic Cloud Printer"
 *  device per printer found on the account.
 *
 *  Auth uses the long-lived access_token from Anycubic Slicer Next's config
 *  file rather than email/password (Anycubic does not expose a credentials
 *  login endpoint to non-app clients).
 *
 *  No MQTT — Anycubic's broker requires mTLS with a client certificate that
 *  Hubitat's MQTT client cannot supply, so this driver intentionally uses
 *  HTTP polling only. Status updates lag by the poll interval.
 *
 *  Licensed under the MIT License.
 */

import java.security.MessageDigest
import groovy.transform.Field

@Field static final String BASE_URL       = "https://cloud-universe.anycubic.com"
@Field static final String API_LOGIN      = "/p/p/workbench/api/v3/public/loginWithAccessToken"
@Field static final String API_GET_PRINTERS = "/p/p/workbench/api/work/printer/getPrinters"
@Field static final String API_SEND_ORDER = "/p/p/workbench/api/work/operation/sendOrder"

@Field static final String APP_ID         = "f9b3528877c94d5c9c5af32245db46ef"
@Field static final String APP_SECRET     = "0cf75926606049a3937f56b0373b99fb"
@Field static final String APP_VERSION    = "V3.0.0"
@Field static final String APP_DEVICE_TYPE = "pcf"

definition(
    name: "Anycubic Cloud Manager",
    namespace: "anycubicforelevation",
    author: "AnycubicforElevation",
    description: "Monitor and control Anycubic 3D printers via Anycubic Cloud (HTTP polling)",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

/* -------- pages -------- */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Anycubic Cloud Manager", install: true, uninstall: true) {
        section("Anycubic account") {
            input "accessToken", "text",
                  title: "Slicer Next access_token",
                  description: "Paste the access_token from %AppData%\\AnycubicSlicerNext\\AnycubicSlicerNext.conf (Windows) or the equivalent on macOS/Linux. About 344 characters.",
                  required: true, submitOnChange: true
            input "isCn", "bool",
                  title: "China region account",
                  description: "Sets the Xx-Is-Cn header to 1.",
                  defaultValue: false
            input "testLogin", "button", title: "Test login now"
        }
        section("Polling") {
            input "pollSeconds", "number",
                  title: "Idle poll interval (seconds)",
                  defaultValue: 60, range: "15..600", required: true
            input "pollPrintingSeconds", "number",
                  title: "Active-print poll interval (seconds)",
                  defaultValue: 15, range: "5..300", required: true
        }
        section("Printers") {
            List kids = (getChildDevices() ?: []).sort { it.displayName }
            if (kids.isEmpty()) {
                paragraph "No printers discovered yet. Save preferences below; the app will log in and create devices."
            } else {
                kids.each { d ->
                    paragraph "<b>${d.displayName}</b> — status: <i>${d.currentValue('printerStatus') ?: 'unknown'}</i>" +
                              (d.currentValue('printProgress') != null ? " — ${d.currentValue('printProgress')}%" : "")
                }
            }
            input "rediscover", "button", title: "Re-fetch printers"
        }
        section("Diagnostics") {
            paragraph "Session: ${state.sessionToken ? 'authenticated' : 'not logged in'}" +
                      (state.lastError ? "<br>Last error: <code>${state.lastError}</code>" : "")
            input "dumpPayload", "button", title: "Dump next printer payload to logs"
            paragraph "<small>Click to log the full raw JSON returned by getPrinters on the next poll. Useful for debugging field-name mismatches. Logged at INFO level once, then turns itself off.</small>"
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
            input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        }
    }
}

/* -------- button handler -------- */

def appButtonHandler(String btn) {
    switch (btn) {
        case "testLogin":
            state.sessionToken = null
            if (login()) {
                log.info "Anycubic login OK"
                fetchPrinters(true)
            } else {
                log.warn "Anycubic login FAILED — see state.lastError"
            }
            break
        case "rediscover":
            fetchPrinters(true)
            break
        case "dumpPayload":
            state.dumpNextPayload = true
            log.info "next getPrinters poll will be dumped to logs"
            fetchPrinters(false)
            break
    }
}

/* -------- lifecycle -------- */

def installed() { initialize() }
def updated()   { unschedule(); initialize() }

def initialize() {
    if (logEnable) runIn(1800, "logsOff")
    state.printingNow = false
    if (!accessToken) {
        log.warn "no access_token configured"
        return
    }
    runIn(2, "loginAndDiscover")
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void logsOff() {
    log.warn "debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* -------- top-level orchestration -------- */

void loginAndDiscover() {
    if (login()) {
        fetchPrinters(true)
        schedulePoll(false)
    }
}

private void schedulePoll(boolean printing) {
    Integer interval = (printing ? (pollPrintingSeconds ?: 15) : (pollSeconds ?: 60)) as Integer
    if (interval < 5) interval = 5
    unschedule("pollNow")
    if (interval >= 60 && interval % 60 == 0) {
        schedule("0 */${interval.intdiv(60)} * * * ?", "pollNow")
    } else {
        schedule("0/${interval} * * * * ?", "pollNow")
    }
    if (logEnable) log.debug "polling every ${interval}s (printing=${printing})"
}

void pollNow() {
    fetchPrinters(false)
}

/* -------- auth -------- */

private boolean login() {
    if (!accessToken) {
        state.lastError = "no access_token configured"
        return false
    }
    String body = groovy.json.JsonOutput.toJson([
        device_type: APP_DEVICE_TYPE,
        access_token: accessToken
    ])
    Map params = [
        uri: BASE_URL,
        path: API_LOGIN,
        headers: signedHeaders(false),
        contentType: "application/json",
        requestContentType: "application/json",
        body: body,
        timeout: 15
    ]
    boolean ok = false
    try {
        httpPost(params) { resp ->
            Map data = (resp?.data instanceof Map) ? (resp.data as Map) : [:]
            String token = data?.data?.token as String
            if (resp?.status == 200 && token) {
                state.sessionToken = token
                state.sessionAcquiredAt = now()
                state.lastError = null
                ok = true
                if (logEnable) log.debug "login OK; session token cached"
            } else {
                state.lastError = "login failed: status=${resp?.status} msg=${data?.msg ?: data}"
                log.warn state.lastError
            }
        }
    } catch (Throwable t) {
        state.lastError = "login error: ${t.message}"
        log.error state.lastError
    }
    return ok
}

private Map signedHeaders(boolean includeToken = true) {
    long ts = now()
    String n = nonce()
    String sig = md5("${APP_ID}${ts}${APP_VERSION}${APP_SECRET}${n}${APP_ID}")
    Map h = [
        "Xx-Device-Type": APP_DEVICE_TYPE,
        "Xx-Is-Cn": (isCn ? "1" : "0"),
        "Xx-Nonce": n,
        "Xx-Signature": sig,
        "Xx-Timestamp": ts.toString(),
        "Xx-Version": APP_VERSION,
        "XX-LANGUAGE": "US",
        "Accept": "application/json"
    ]
    if (includeToken && state.sessionToken) {
        h["XX-Token"] = state.sessionToken
    }
    return h
}

private String md5(String s) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] dig = md.digest(s.getBytes("UTF-8"))
    return dig.encodeHex().toString()
}

private String nonce() {
    String chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    StringBuilder sb = new StringBuilder()
    Random r = new Random()
    16.times { sb.append(chars.charAt(r.nextInt(chars.length()))) }
    return sb.toString()
}

/* -------- printer fetch / state distribution -------- */

private void fetchPrinters(boolean createIfMissing) {
    if (!state.sessionToken) {
        if (!login()) return
    }
    asynchttpGet("printersHandler",
        [uri: "${BASE_URL}${API_GET_PRINTERS}",
         headers: signedHeaders(true),
         timeout: 15],
        [createIfMissing: createIfMissing])
}

void printersHandler(resp, data) {
    try {
        if (resp?.status == null) {
            state.lastError = "no response from getPrinters"
            log.warn state.lastError
            return
        }
        if (resp.status == 401 || resp.status == 403) {
            log.warn "getPrinters auth failed (${resp.status}); re-logging in"
            state.sessionToken = null
            runIn(2, "loginAndDiscover")
            return
        }
        if (resp.status >= 400) {
            state.lastError = "getPrinters HTTP ${resp.status}"
            log.warn state.lastError
            return
        }

        Map body = safeJson(resp)

        if (state.dumpNextPayload) {
            try {
                log.info "RAW getPrinters body: ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(body))}"
            } catch (Throwable t) {
                log.info "RAW getPrinters body (toString): ${body}"
            }
            state.dumpNextPayload = false
        }

        // Empty or null data is the documented "session expired" signal.
        def dataField = body?.data
        if (dataField == null || (dataField instanceof Map && dataField.isEmpty())) {
            log.warn "getPrinters returned empty data; treating as session expired"
            state.sessionToken = null
            runIn(2, "loginAndDiscover")
            return
        }

        List printers = []
        if (dataField instanceof List) {
            printers = dataField as List
        } else if (dataField instanceof Map) {
            // Some endpoints wrap as {data: {list: [...]}} or {data: {printers: [...]}}.
            def wrapped = dataField.list ?: dataField.printers ?: dataField.records
            if (wrapped instanceof List) printers = wrapped as List
        }

        boolean printingNow = false
        printers.each { def p ->
            if (p instanceof Map) {
                updatePrinter(p as Map, (data?.createIfMissing as Boolean) ?: false)
                if (mapStatus(p as Map) == "printing") printingNow = true
            }
        }

        if (printingNow != (state.printingNow as Boolean)) {
            state.printingNow = printingNow
            schedulePoll(printingNow)
        }
        state.lastError = null
    } catch (Throwable t) {
        state.lastError = "printersHandler: ${t.message}"
        log.error state.lastError
    }
}

private Map safeJson(resp) {
    try {
        return (resp?.json instanceof Map) ? (resp.json as Map) : [:]
    } catch (ignored) {
        try { return new groovy.json.JsonSlurper().parseText(resp?.data as String) as Map }
        catch (ignored2) { return [:] }
    }
}

private void updatePrinter(Map p, boolean createIfMissing) {
    String pid = (p.id ?: p.printer_id ?: "")?.toString()
    if (!pid) {
        if (logEnable) log.debug "skipping printer entry without id: ${p}"
        return
    }
    String dni = "anycubic-cloud-${pid}"
    def child = getChildDevice(dni)
    if (!child && createIfMissing) {
        String name = (p.name ?: p.machine_name ?: p.printer_name ?: "Anycubic ${pid}") as String
        try {
            child = addChildDevice("anycubicforelevation", "Anycubic Cloud Printer", dni,
                [name: "Anycubic Cloud Printer", label: name, isComponent: false])
            try { child.updateDataValue("printerId", pid) } catch (ignored) { }
            if (p.machine_type != null) try { child.updateDataValue("machineType", p.machine_type.toString()) } catch (ignored) { }
            if (p.key != null) try { child.updateDataValue("key", p.key.toString()) } catch (ignored) { }
            log.info "created child device for printer ${name} (id=${pid})"
        } catch (Throwable t) {
            log.error "failed to create child for printer ${pid}: ${t.message}"
            return
        }
    }
    if (child) {
        try { child.updateFromCloud(p) }
        catch (Throwable t) { log.error "child.updateFromCloud(${pid}) failed: ${t.message}" }
    }
}

private String mapStatus(Map p) {
    String s = (p.print_status ?: p.status ?: p.state ?: p.print_state ?: "")?.toString()?.toLowerCase()
    if (!s) return "unknown"
    if (s in ["printing", "1"]) return "printing"
    if (s in ["paused", "pause", "2"]) return "paused"
    if (s in ["complete", "completed", "finish", "finished", "3"]) return "complete"
    if (s in ["cancelled", "canceled", "stopped", "stop", "4"]) return "cancelled"
    if (s in ["error", "fault", "failed", "5"]) return "error"
    if (s in ["idle", "ready", "standby", "free", "0"]) return "ready"
    if (s in ["offline"]) return "offline"
    return s
}

/* -------- commands (called from child drivers) -------- */

Map sendOrder(String printerId, String projectId, Integer orderId) {
    Map result = [success: false]
    if (!printerId) {
        result.error = "missing printerId"
        return result
    }
    if (!state.sessionToken && !login()) {
        result.error = state.lastError ?: "not authenticated"
        return result
    }

    Map payload = [
        order_id: orderId,
        printer_id: toLong(printerId),
        project_id: (projectId ? toLong(projectId) : null),
        data: null,
        ams_info: null,
        settings: null
    ]
    Map params = [
        uri: BASE_URL,
        path: API_SEND_ORDER,
        headers: signedHeaders(true),
        contentType: "application/json",
        requestContentType: "application/json",
        body: groovy.json.JsonOutput.toJson(payload),
        timeout: 15
    ]
    try {
        httpPost(params) { resp ->
            Map data = (resp?.data instanceof Map) ? (resp.data as Map) : [:]
            Integer code = (data?.code != null) ? (data.code as Integer) : null
            if (resp?.status == 200 && (code == null || code == 0 || code == 200)) {
                result.success = true
            } else {
                result.error = "status=${resp?.status} code=${code} msg=${data?.msg ?: data}"
            }
        }
    } catch (Throwable t) {
        result.error = t.message
    }
    if (logEnable) log.debug "sendOrder order_id=${orderId} printer=${printerId} project=${projectId} → ${result}"
    runIn(3, "pollNow")
    return result
}

private Long toLong(def v) {
    if (v == null) return null
    if (v instanceof Number) return (v as Long)
    String s = v.toString().trim()
    return s.isLong() ? (s as Long) : null
}
