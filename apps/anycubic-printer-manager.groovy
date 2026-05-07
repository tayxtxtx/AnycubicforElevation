/**
 *  Anycubic Printer Manager — Hubitat App
 *
 *  Discovers Anycubic-compatible printers on the local network (Moonraker
 *  and OctoPrint endpoints) and creates child devices using the
 *  "Anycubic (Moonraker) 3D Printer" / "Anycubic (OctoPrint) 3D Printer"
 *  drivers.
 *
 *  Licensed under the MIT License.
 */

definition(
    name: "Anycubic Printer Manager",
    namespace: "anycubicforelevation",
    author: "AnycubicforElevation",
    description: "Discover and manage Anycubic 3D printers on the LAN",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
}

/* -------- pages -------- */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Anycubic Printer Manager", install: true, uninstall: true) {
        section("Discover printers") {
            href(name: "goDiscover", title: "Scan my network",
                 description: "Find Moonraker and OctoPrint printers on this hub's subnet",
                 page: "discoveryPage")
        }
        section("Already added") {
            def kids = getChildDevices()
            if (!kids) {
                paragraph "No printer devices created yet. Run a scan to add some."
            } else {
                kids.each { d ->
                    paragraph "<b>${d.displayName}</b> — ${d.typeName}<br>" +
                              "Status: ${d.currentValue('printerStatus') ?: 'unknown'}"
                }
            }
        }
        section("Logging") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        }
    }
}

def discoveryPage() {
    Map scan = (state.scan ?: [:]) as Map
    boolean active = (scan.pending ?: 0) > 0

    dynamicPage(name: "discoveryPage",
                title: "Network scan",
                refreshInterval: active ? 3 : 0,
                install: false, uninstall: false) {
        section {
            paragraph "Hub IP: ${location?.hub?.localIP ?: 'unknown'}"
            paragraph "Scan range: ${scan.subnet ?: '(not started)'}"
            input "scanMoonraker", "bool",
                  title: "Scan for Moonraker printers",
                  defaultValue: true, submitOnChange: true
            if (scanMoonraker != false) {
                input "scanPortsMoonraker", "string",
                      title: "Moonraker ports to probe (comma-separated)",
                      defaultValue: "7125", submitOnChange: false
            }
            input "scanOctoprint", "bool",
                  title: "Scan for OctoPrint printers",
                  defaultValue: true, submitOnChange: true
            if (scanOctoprint != false) {
                input "scanPortsOctoprint", "string",
                      title: "OctoPrint ports to probe (comma-separated)",
                      defaultValue: "80,5000", submitOnChange: false
            }
            input "scanBatchSize", "number",
                  title: "Probes per batch (lower = gentler, slower scan)",
                  defaultValue: 25, range: "1..100", submitOnChange: false
            input "scanBatchInterval", "number",
                  title: "Seconds between batches",
                  defaultValue: 2, range: "1..30", submitOnChange: false
            input "scanGlobalTimeout", "number",
                  title: "Maximum scan duration (seconds)",
                  defaultValue: 120, range: "30..600", submitOnChange: false
            input "knownMacs", "text",
                  title: "Known printer MACs (one per line, optional)",
                  description: "e.g. AA:BB:CC:11:22:33 — full MAC or just the OUI prefix (AA:BB:CC). Used to identify and optionally filter discovery results.",
                  required: false, submitOnChange: false
            input "requireMacMatch", "bool",
                  title: "Only show printers whose MAC matches the list above",
                  defaultValue: false, submitOnChange: true
        }
        section {
            if (active) {
                Integer queued = ((state.queue ?: []) as List).size()
                paragraph "Scanning ${scan.kinds ?: ''}… ${scan.completed ?: 0} of ${scan.total ?: 0} probes done (${queued} still queued)."
                input "cancelScan", "button", title: "Cancel scan"
            } else {
                if (scanMoonraker == false && scanOctoprint == false) {
                    paragraph "<b>Both scan types are disabled.</b> Enable at least one above before scanning."
                } else {
                    input "startScan", "button", title: "Start scan"
                }
            }
        }
        section("Found printers") {
            List<Map> found = ((state.found ?: []) as List).collect { it as Map }
            List<String> filterList = parseMacList(knownMacs)
            boolean filterOn = (requireMacMatch == true) && filterList
            List<Map> shown = found.findAll { Map p ->
                if (!filterOn) return true
                String mac = (p.mac ?: "") as String
                return mac && macMatchesList(mac, filterList)
            }
            int hiddenByFilter = found.size() - shown.size()

            if (!found) {
                paragraph active ? "No results yet." : "Nothing found. Try a scan."
            } else if (!shown) {
                paragraph "Found ${found.size()} responder(s) but none match the MAC filter."
            } else {
                shown.each { Map p ->
                    int idx = found.indexOf(p)
                    String label = "${p.kind == 'moonraker' ? 'Moonraker' : 'OctoPrint'} @ ${p.ip}:${p.port}"
                    String macStr = p.mac ? " — MAC <code>${formatMac(p.mac as String)}</code>" :
                                            " — MAC pending…"
                    String existing = childForEndpoint(p.ip, p.port, p.kind)?.displayName
                    String suffix = existing ? " — already added as <i>${existing}</i>" : ""
                    paragraph "<b>${label}</b>${macStr}${suffix}"
                    if (!existing) {
                        input "add_${idx}", "button", title: "Add as device"
                    }
                }
                if (hiddenByFilter > 0) {
                    paragraph "<i>${hiddenByFilter} responder(s) hidden by MAC filter.</i>"
                }
            }
        }
    }
}

/* -------- button handler -------- */

def appButtonHandler(String btn) {
    if (btn == "startScan") {
        startScan()
        return
    }
    if (btn == "cancelScan") {
        cancelScan("user cancelled")
        return
    }
    if (btn?.startsWith("add_")) {
        Integer idx = btn.substring(4) as Integer
        List found = (state.found ?: []) as List
        if (idx >= 0 && idx < found.size()) {
            createChildFor(found[idx] as Map)
        }
    }
}

/* -------- lifecycle -------- */

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    state.scan = state.scan ?: [:]
    state.found = state.found ?: []
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

/* -------- scan -------- */

private void startScan() {
    String hubIp = location?.hub?.localIP
    if (!hubIp) {
        log.warn "no hub IP available"
        return
    }
    boolean doMoonraker = (scanMoonraker != false)
    boolean doOctoprint = (scanOctoprint != false)
    if (!doMoonraker && !doOctoprint) {
        log.warn "both Moonraker and OctoPrint scanning are disabled — nothing to do"
        state.scan = [subnet: "(disabled)", total: 0, completed: 0, pending: 0]
        state.queue = []
        return
    }

    String prefix = hubIp.replaceAll(/\.\d+$/, "")
    List<Integer> mPorts = doMoonraker ? parsePorts(scanPortsMoonraker, [7125]) : []
    List<Integer> oPorts = doOctoprint ? parsePorts(scanPortsOctoprint, [80, 5000]) : []

    List<Map> queue = []
    (1..254).each { Integer host ->
        String ip = "${prefix}.${host}"
        mPorts.each { Integer p -> queue << [ip: ip, port: p, kind: "moonraker"] }
        oPorts.each { Integer p -> queue << [ip: ip, port: p, kind: "octoprint"] }
    }

    state.found = []
    state.queue = queue
    state.scan = [
        subnet: "${prefix}.1-254",
        kinds: [doMoonraker ? "Moonraker" : null, doOctoprint ? "OctoPrint" : null].findAll().join(", "),
        total: queue.size(),
        completed: 0,
        pending: queue.size(),
        startedAt: now()
    ]
    if (logEnable) log.debug "scan starting: ${state.scan}"

    unschedule("scanTick")
    unschedule("scanTimeout")
    Integer timeout = (scanGlobalTimeout ?: 120) as Integer
    runIn(timeout, "scanTimeout")
    scanTick()
}

void scanTick() {
    List<Map> queue = ((state.queue ?: []) as List).collect { it as Map }
    if (!queue) {
        // Nothing left to send. If callbacks are still pending, the global
        // timeout will finalize; otherwise mark done now.
        Map scan = (state.scan ?: [:]) as Map
        if (((scan.pending ?: 0) as Integer) <= 0) {
            finalizeScan("complete")
        }
        return
    }
    Integer batchSize = (scanBatchSize ?: 25) as Integer
    if (batchSize < 1) batchSize = 1
    Integer interval = (scanBatchInterval ?: 2) as Integer
    if (interval < 1) interval = 1

    int n = Math.min(batchSize, queue.size())
    List<Map> batch = queue.take(n)
    state.queue = queue.drop(n)

    batch.each { Map p ->
        if (p.kind == "moonraker") probeMoonraker(p.ip as String, p.port as Integer)
        else probeOctoprint(p.ip as String, p.port as Integer)
    }
    if (logEnable) log.debug "fired batch of ${n}, ${((state.queue ?: []) as List).size()} queued"

    if (((state.queue ?: []) as List).size() > 0) {
        runIn(interval, "scanTick")
    } else {
        // Last batch dispatched. Give callbacks a brief grace window before
        // the global timeout cleans up anything stuck.
        runIn(10, "scanFinalizeIfQuiet")
    }
}

void scanFinalizeIfQuiet() {
    Map scan = (state.scan ?: [:]) as Map
    if (((scan.pending ?: 0) as Integer) <= 0) {
        finalizeScan("complete")
    }
}

void scanTimeout() {
    Map scan = (state.scan ?: [:]) as Map
    Integer pending = (scan.pending ?: 0) as Integer
    Integer queued = ((state.queue ?: []) as List).size()
    if (pending > 0 || queued > 0) {
        log.warn "scan timed out: ${queued} queued, ${pending} pending callbacks dropped"
        finalizeScan("timed out (${pending} probes had no response)")
    }
}

private void cancelScan(String reason) {
    unschedule("scanTick")
    unschedule("scanFinalizeIfQuiet")
    unschedule("scanTimeout")
    state.queue = []
    finalizeScan(reason)
}

private void finalizeScan(String reason) {
    unschedule("scanTick")
    unschedule("scanFinalizeIfQuiet")
    unschedule("scanTimeout")
    Map scan = (state.scan ?: [:]) as Map
    scan.pending = 0
    scan.queue = 0
    scan.finishedAt = now()
    scan.result = reason
    state.scan = scan
    state.queue = []
    if (logEnable) log.debug "scan finalized: ${reason}; found ${(state.found ?: []).size()}"
}

private List<Integer> parsePorts(String s, List<Integer> dflt) {
    if (!s) return dflt
    try {
        List<Integer> ports = s.split(",").collect { (it.trim() as Integer) }.findAll { it > 0 && it < 65536 }
        return ports ?: dflt
    } catch (ignored) { return dflt }
}

private void probeMoonraker(String ip, Integer port) {
    asynchttpGet("scanResult",
        [uri: "http://${ip}:${port}/server/info", timeout: 3, ignoreSSLIssues: true],
        [ip: ip, port: port, kind: "moonraker"])
}

private void probeOctoprint(String ip, Integer port) {
    asynchttpGet("scanResult",
        [uri: "http://${ip}:${port}/api/version", timeout: 3, ignoreSSLIssues: true],
        [ip: ip, port: port, kind: "octoprint"])
}

void scanResult(resp, data) {
    try {
        Map scan = (state.scan ?: [:]) as Map
        scan.completed = ((scan.completed ?: 0) as Integer) + 1
        scan.pending = ((scan.pending ?: 0) as Integer) - 1
        state.scan = scan

        Integer status = (resp?.status ?: 0) as Integer
        if (status < 200 || status >= 500) return

        String kind = data.kind
        String body = ""
        try { body = (resp?.data ?: "") as String } catch (ignored) { body = "" }

        boolean match = false
        if (kind == "moonraker") {
            // /server/info returns 200 with JSON containing "klippy_state" or "moonraker_version"
            match = (status == 200 && (body?.contains("klippy_state") || body?.contains("moonraker_version")))
        } else if (kind == "octoprint") {
            // /api/version returns 200 with {"server":"x.y.z","api":"0.1","text":"OctoPrint x.y.z"}
            // or 403 with body mentioning "OctoPrint" if API key is required
            match = (status == 200 && body?.contains("OctoPrint")) ||
                    (status in [401, 403] && body?.toLowerCase()?.contains("octoprint"))
        }

        if (match) {
            List<Map> found = ((state.found ?: []) as List).collect { it as Map }
            boolean dup = found.any { it.ip == data.ip && it.port == data.port && it.kind == kind }
            if (!dup) {
                found << [ip: data.ip, port: data.port, kind: kind, mac: null]
                state.found = found
                if (logEnable) log.debug "discovered ${kind} at ${data.ip}:${data.port} — capturing MAC"
                captureMac(data.ip as String, data.port as Integer, kind)
            }
        }
    } catch (Throwable t) {
        log.error "scanResult error: ${t.message}"
    }
}

/* -------- MAC capture (HubAction round-trip) -------- */

private void captureMac(String ip, Integer port, String kind) {
    try {
        String hexIp = ip.tokenize(".").collect { String.format("%02X", (it as Integer)) }.join("")
        String hexPort = String.format("%04X", port)
        String dni = "${hexIp}:${hexPort}"
        String path = (kind == "moonraker") ? "/server/info" : "/api/version"

        def action = new hubitat.device.HubAction(
            method: "GET",
            path: path,
            headers: [HOST: "${ip}:${port}", Accept: "application/json"]
        )
        action.dni = dni
        action.options = [callback: "macCaptureResult", type: hubitat.device.Protocol.LAN]
        sendHubCommand(action)
    } catch (Throwable t) {
        if (logEnable) log.debug "MAC capture skipped for ${ip}:${port}: ${t.message}"
    }
}

void macCaptureResult(response) {
    try {
        String mac = response?.mac
        String ipHex = null
        String portHex = null
        if (response?.headers) {
            // Hubitat puts the source MAC in response.mac directly. The DNI
            // form ipHex:portHex is also available via the description but
            // we don't need it.
        }
        if (!mac) {
            // Some hub versions surface MAC via response?.description as
            // "mac:XXXXXXXXXXXX, ip:..., ..."
            String desc = (response?.description ?: "") as String
            def m = desc =~ /mac:\s*([0-9A-Fa-f:]+)/
            if (m.find()) mac = m.group(1)
        }
        if (!mac) {
            if (logEnable) log.debug "MAC capture: response had no MAC"
            return
        }
        String ip = inferIpFromResponse(response)
        Integer port = inferPortFromResponse(response)
        if (logEnable) log.debug "MAC ${mac} for ${ip}:${port}"

        List<Map> found = ((state.found ?: []) as List).collect { it as Map }
        boolean updated = false
        found.each { Map p ->
            if ((ip == null || p.ip == ip) && (port == null || (p.port as Integer) == port)) {
                if (!p.mac) {
                    p.mac = normalizeMac(mac)
                    updated = true
                }
            }
        }
        // Fallback: if we couldn't pin down ip/port, attach the MAC to the
        // most recent entry that's still missing one.
        if (!updated) {
            Map last = found.reverse().find { it.mac == null }
            if (last) {
                last.mac = normalizeMac(mac)
                updated = true
            }
        }
        if (updated) state.found = found
    } catch (Throwable t) {
        log.error "macCaptureResult error: ${t.message}"
    }
}

private String inferIpFromResponse(response) {
    try {
        // Hubitat HubResponse exposes neither ip nor port directly across all
        // versions. The DNI in response.deviceNetworkId / response.dni is
        // ipHex:portHex when the request was sent with such a DNI.
        String dni = (response?.deviceNetworkId ?: response?.dni ?: "") as String
        if (dni && dni.contains(":")) {
            String ipHex = dni.tokenize(":")[0]
            if (ipHex?.length() == 8) {
                return [
                    Integer.parseInt(ipHex.substring(0, 2), 16),
                    Integer.parseInt(ipHex.substring(2, 4), 16),
                    Integer.parseInt(ipHex.substring(4, 6), 16),
                    Integer.parseInt(ipHex.substring(6, 8), 16)
                ].join(".")
            }
        }
    } catch (ignored) { }
    return null
}

private Integer inferPortFromResponse(response) {
    try {
        String dni = (response?.deviceNetworkId ?: response?.dni ?: "") as String
        if (dni && dni.contains(":")) {
            String portHex = dni.tokenize(":")[1]
            if (portHex) return Integer.parseInt(portHex, 16)
        }
    } catch (ignored) { }
    return null
}

/* -------- MAC helpers -------- */

private String normalizeMac(String mac) {
    if (!mac) return ""
    return mac.replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase()
}

private String formatMac(String mac) {
    String n = normalizeMac(mac)
    if (n.length() != 12) return mac
    return (0..5).collect { n.substring(it * 2, it * 2 + 2) }.join(":")
}

private List<String> parseMacList(String text) {
    if (!text) return []
    return text.split(/[\s,;]+/)
               .collect { normalizeMac(it as String) }
               .findAll { it && it.length() in 6..12 }
}

private boolean macMatchesList(String mac, List<String> list) {
    String n = normalizeMac(mac)
    if (!n || !list) return false
    return list.any { String entry ->
        // Match if entry is a prefix (OUI = 6 hex chars) or full equality.
        n == entry || (entry.length() < 12 && n.startsWith(entry))
    }
}

/* -------- child management -------- */

private def childForEndpoint(String ip, Integer port, String kind) {
    // Try IP-based DNI (legacy); also look up by MAC if any found entry has one.
    String legacyDni = "anycubic-${kind}-${ip}-${port}".replaceAll(/[^A-Za-z0-9-]/, "-")
    def existing = getChildDevice(legacyDni)
    if (existing) return existing
    Map p = ((state.found ?: []) as List).find {
        Map m = it as Map
        m.ip == ip && (m.port as Integer) == port && m.kind == kind
    } as Map
    if (p?.mac) {
        return getChildDevice("anycubic-${kind}-${normalizeMac(p.mac as String)}")
    }
    return null
}

private String childDni(Map p) {
    String mac = normalizeMac((p.mac ?: "") as String)
    if (mac && mac.length() == 12) {
        // MAC-based DNI is stable across DHCP changes.
        return "anycubic-${p.kind}-${mac}"
    }
    return "anycubic-${p.kind}-${p.ip}-${p.port}".replaceAll(/[^A-Za-z0-9-]/, "-")
}

private void createChildFor(Map p) {
    String kind = p.kind
    String typeName = (kind == "moonraker")
        ? "Anycubic (Moonraker) 3D Printer"
        : "Anycubic (OctoPrint) 3D Printer"
    String label = "Anycubic ${kind.capitalize()} ${p.ip}"
    String dni = childDni(p)
    if (getChildDevice(dni)) {
        log.info "child already exists for ${dni}"
        return
    }
    try {
        def child = addChildDevice("anycubicforelevation", typeName, dni,
            [name: typeName, label: label, isComponent: false])
        if (kind == "moonraker") {
            child.updateSetting("ipAddress", [type: "string", value: p.ip])
            child.updateSetting("port", [type: "number", value: p.port])
        } else {
            child.updateSetting("host", [type: "string", value: p.ip])
            child.updateSetting("port", [type: "number", value: p.port])
            child.updateSetting("useHttps", [type: "bool", value: false])
        }
        if (p.mac) {
            try { child.updateDataValue("mac", formatMac(p.mac as String)) } catch (ignored) { }
        }
        // Drivers schedule polling on updated() — call it so the new prefs take effect.
        try { child.updated() } catch (ignored) { }
        log.info "added child device ${label} (${dni})"
    } catch (Throwable t) {
        log.error "failed to add child ${dni}: ${t.message}"
    }
}
