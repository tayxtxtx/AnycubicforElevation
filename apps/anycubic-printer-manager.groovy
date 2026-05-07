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
        }
        section {
            if (active) {
                paragraph "Scanning ${scan.kinds ?: ''}… ${scan.completed ?: 0} of ${scan.total ?: 0} probes done."
            } else {
                if (scanMoonraker == false && scanOctoprint == false) {
                    paragraph "<b>Both scan types are disabled.</b> Enable at least one above before scanning."
                } else {
                    input "startScan", "button", title: "Start scan"
                }
            }
        }
        section("Found printers") {
            List found = (state.found ?: []) as List
            if (!found) {
                paragraph active ? "No results yet." : "Nothing found. Try a scan."
            } else {
                found.eachWithIndex { Map p, int idx ->
                    String label = "${p.kind == 'moonraker' ? 'Moonraker' : 'OctoPrint'} @ ${p.ip}:${p.port}"
                    String existing = childForEndpoint(p.ip, p.port, p.kind)?.displayName
                    paragraph "<b>${label}</b>" + (existing ? " — already added as <i>${existing}</i>" : "")
                    if (!existing) {
                        input "add_${idx}", "button", title: "Add as device"
                    }
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
        return
    }

    String prefix = hubIp.replaceAll(/\.\d+$/, "")
    List<Integer> mPorts = doMoonraker ? parsePorts(scanPortsMoonraker, [7125]) : []
    List<Integer> oPorts = doOctoprint ? parsePorts(scanPortsOctoprint, [80, 5000]) : []
    Integer total = 254 * (mPorts.size() + oPorts.size())

    state.found = []
    state.scan = [
        subnet: "${prefix}.1-254",
        kinds: [doMoonraker ? "Moonraker" : null, doOctoprint ? "OctoPrint" : null].findAll().join(", "),
        total: total,
        completed: 0,
        pending: total,
        startedAt: now()
    ]
    if (logEnable) log.debug "scan starting: ${state.scan}"

    (1..254).each { Integer host ->
        String ip = "${prefix}.${host}"
        mPorts.each { Integer p -> probeMoonraker(ip, p) }
        oPorts.each { Integer p -> probeOctoprint(ip, p) }
    }
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
            List found = ((state.found ?: []) as List).collect { it as Map }
            boolean dup = found.any { it.ip == data.ip && it.port == data.port && it.kind == kind }
            if (!dup) {
                found << [ip: data.ip, port: data.port, kind: kind]
                state.found = found
                if (logEnable) log.debug "discovered ${kind} at ${data.ip}:${data.port}"
            }
        }
    } catch (Throwable t) {
        log.error "scanResult error: ${t.message}"
    }
}

/* -------- child management -------- */

private def childForEndpoint(String ip, Integer port, String kind) {
    String dni = childDni(ip, port, kind)
    return getChildDevice(dni)
}

private String childDni(String ip, Integer port, String kind) {
    return "anycubic-${kind}-${ip}-${port}".replaceAll(/[^A-Za-z0-9-]/, "-")
}

private void createChildFor(Map p) {
    String kind = p.kind
    String typeName = (kind == "moonraker")
        ? "Anycubic (Moonraker) 3D Printer"
        : "Anycubic (OctoPrint) 3D Printer"
    String label = "Anycubic ${kind.capitalize()} ${p.ip}"
    String dni = childDni(p.ip, p.port as Integer, kind)
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
        // Drivers schedule polling on updated() — call it so the new prefs take effect.
        try { child.updated() } catch (ignored) { }
        log.info "added child device ${label} (${dni})"
    } catch (Throwable t) {
        log.error "failed to add child ${dni}: ${t.message}"
    }
}
