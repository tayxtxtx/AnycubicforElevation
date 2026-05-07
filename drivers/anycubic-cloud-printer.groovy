/**
 *  Anycubic Cloud Printer — Hubitat Driver
 *
 *  Child driver paired with the "Anycubic Cloud Manager" app. One instance
 *  per printer on your Anycubic Cloud account. State is pushed by the parent
 *  app on every poll; commands are sent through the parent which signs and
 *  POSTs them to Anycubic's REST API.
 *
 *  Licensed under the MIT License.
 */

metadata {
    definition(
        name: "Anycubic Cloud Printer",
        namespace: "anycubicforelevation",
        author: "AnycubicforElevation"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "Initialize"

        attribute "printerStatus", "string"
        attribute "printProgress", "number"
        attribute "currentFile", "string"
        attribute "printTimeElapsed", "string"
        attribute "printTimeRemaining", "string"
        attribute "nozzleTemperature", "number"
        attribute "nozzleTarget", "number"
        attribute "bedTemperature", "number"
        attribute "bedTarget", "number"
        attribute "online", "string"
        attribute "machineType", "string"
        attribute "lastError", "string"

        command "pausePrint"
        command "resumePrint"
        command "cancelPrint"
        command "stopPrint"
        command "clearError"
    }

    preferences {
        input name: "primaryTemp", type: "enum",
              title: "Temperature reported as device temperature",
              options: ["nozzle", "bed"], defaultValue: "nozzle"
        input name: "switchSemantics", type: "enum",
              title: "Switch state reflects",
              options: ["printing", "online"], defaultValue: "printing"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Description text logging", defaultValue: true
    }
}

/* -------- lifecycle -------- */

void installed()  { log.info "${device.displayName} installed" }
void updated()    { log.info "${device.displayName} updated" }
void initialize() { refresh() }

/* -------- capability commands -------- */

void on()  { if (txtEnable) log.info "${device.displayName}: 'on' is informational only" }
void off() { if (txtEnable) log.info "${device.displayName}: 'off' is informational only" }

void refresh() {
    try { parent?.pollNow() }
    catch (Throwable t) { log.warn "refresh: parent.pollNow() failed: ${t.message}" }
}

void clearError() { sendEvent(name: "lastError", value: "") }

/* -------- printer commands -------- */

void pausePrint()  { dispatchOrder(2, "pause") }
void resumePrint() { dispatchOrder(3, "resume") }
void cancelPrint() { dispatchOrder(4, "cancel") }
void stopPrint()   { dispatchOrder(44, "stop (force)") }

private void dispatchOrder(Integer orderId, String label) {
    String printerId = getDataValue("printerId")
    if (!printerId) {
        sendEvent(name: "lastError", value: "no printerId set on device")
        log.warn "${device.displayName}: cannot ${label} — no printerId on device data"
        return
    }
    String projectId = state.currentProjectId ?: getDataValue("currentProjectId")
    if (!projectId && orderId in [2, 3, 4, 44]) {
        sendEvent(name: "lastError", value: "no active project to ${label}")
        log.warn "${device.displayName}: cannot ${label} — no active project_id known"
        return
    }
    Map result
    try {
        result = parent.sendOrder(printerId, projectId, orderId) as Map
    } catch (Throwable t) {
        result = [success: false, error: t.message]
    }
    if (result?.success) {
        if (txtEnable) log.info "${device.displayName}: ${label} sent"
    } else {
        String err = result?.error ?: "unknown error"
        sendEvent(name: "lastError", value: err)
        log.warn "${device.displayName}: ${label} failed — ${err}"
    }
}

/* -------- state ingestion (called by parent app) -------- */

void updateFromCloud(Map p) {
    if (!p) return
    try {
        if (p.machine_type != null) sendIfChanged("machineType", p.machine_type.toString())

        String status = mapStatus(p)
        sendIfChanged("printerStatus", status)

        String onlineVal = onlineString(p)
        sendIfChanged("online", onlineVal)

        Number progress = pickNumber(p,
            ["progress", "print_progress", "printProgress"], 0)
        sendIfChanged("printProgress", progress)

        String filename = pickString(p,
            ["filename", "file_name", "print_filename", "current_file_name", "task_name"])
        sendIfChanged("currentFile", filename)

        BigDecimal nozzleTemp = roundTemp(pickValue(p,
            ["curr_nozzle_temp", "nozzle_temp", "current_nozzle_temp", "extruder_temp"]))
        BigDecimal nozzleTarget = roundTemp(pickValue(p,
            ["target_nozzle_temp", "nozzle_target", "extruder_target"]))
        BigDecimal bedTemp = roundTemp(pickValue(p,
            ["curr_hotbed_temp", "hotbed_temp", "bed_temp", "current_bed_temp"]))
        BigDecimal bedTarget = roundTemp(pickValue(p,
            ["target_hotbed_temp", "hotbed_target", "bed_target"]))
        sendIfChanged("nozzleTemperature", nozzleTemp)
        sendIfChanged("nozzleTarget", nozzleTarget)
        sendIfChanged("bedTemperature", bedTemp)
        sendIfChanged("bedTarget", bedTarget)

        BigDecimal primary = (primaryTemp == "bed") ? bedTemp : nozzleTemp
        if (primary != null) sendIfChanged("temperature", primary, [unit: "C"])

        Long elapsed = pickLong(p,
            ["elapsed_time", "print_time", "printed_time", "used_time"])
        Long remaining = pickLong(p,
            ["remaining_time", "left_time", "eta_seconds", "remain_time"])
        sendIfChanged("printTimeElapsed", formatDuration(elapsed))
        sendIfChanged("printTimeRemaining", formatDuration(remaining))

        def proj = p.current_project_id ?: p.project_id ?: p.taskid ?: p.task_id
        if (proj != null && proj.toString() != "0" && proj.toString() != "") {
            String s = proj.toString()
            if (state.currentProjectId != s) state.currentProjectId = s
        } else if (status in ["ready", "complete", "cancelled"]) {
            state.currentProjectId = null
        }

        boolean isPrinting = (status == "printing")
        String sw
        if (switchSemantics == "online") {
            sw = (onlineVal == "online") ? "on" : "off"
        } else {
            sw = isPrinting ? "on" : "off"
        }
        sendIfChanged("switch", sw)
    } catch (Throwable t) {
        log.error "${device.displayName} updateFromCloud: ${t.message}"
        if (logEnable) log.debug "payload was: ${p}"
    }
}

/* -------- helpers -------- */

private String onlineString(Map p) {
    def v = pickValue(p, [
        "online", "is_online", "isOnline",
        "device_status", "deviceStatus",
        "connect_status", "connection_status", "connectStatus", "connectionStatus",
        "mqtt_status", "mqttStatus",
        "machine_status", "machineStatus",
        "active", "is_active",
        "status"   // tried last; on stock Anycubic responses this commonly holds online state
    ])
    if (v == null) return "unknown"
    String s = v.toString().toLowerCase().trim()
    if (s in ["1", "true", "online", "y", "yes", "active", "connected", "ready"]) return "online"
    if (s in ["0", "false", "offline", "n", "no", "inactive", "disconnected"]) return "offline"
    return s
}

private String mapStatus(Map p) {
    // Use only true print-state fields here. Do NOT consume the generic "status"
    // field — on stock Anycubic responses that's the online flag, and reading
    // "1" as "printing" was the cause of idle printers showing "printing".
    String s = (p.print_status ?: p.printStatus ?: p.print_state ?: p.printState ?:
                p.task_status ?: p.taskStatus ?: p.printing_status ?: "")?.toString()?.toLowerCase()?.trim()
    if (s) {
        if (s in ["printing", "running", "1"]) return "printing"
        if (s in ["paused", "pause", "2"]) return "paused"
        if (s in ["complete", "completed", "finish", "finished", "3"]) return "complete"
        if (s in ["cancelled", "canceled", "stopped", "stop", "4"]) return "cancelled"
        if (s in ["error", "fault", "failed", "5"]) return "error"
        if (s in ["idle", "ready", "standby", "free", "0"]) return "ready"
        if (s == "offline") return "offline"
        return s
    }
    // No explicit print-state field — infer from current project / progress.
    def proj = p.current_project_id ?: p.project_id ?: p.taskid ?: p.task_id ?: p.currentProjectId
    boolean hasActiveProject = (proj != null) && !(proj.toString() in ["0", "", "null"])
    Number prog = pickNumber(p, ["progress", "print_progress", "printProgress"], -1)
    if (hasActiveProject || (prog != null && prog.intValue() > 0 && prog.intValue() < 100)) return "printing"
    return "ready"
}

private def pickValue(Map p, List<String> keys) {
    for (String k : keys) {
        if (p.containsKey(k) && p[k] != null) return p[k]
    }
    return null
}

private String pickString(Map p, List<String> keys) {
    def v = pickValue(p, keys)
    return (v == null) ? "" : v.toString()
}

private Number pickNumber(Map p, List<String> keys, Number dflt) {
    def v = pickValue(p, keys)
    if (v == null) return dflt
    try { return (v as BigDecimal) } catch (ignored) { return dflt }
}

private Long pickLong(Map p, List<String> keys) {
    def v = pickValue(p, keys)
    if (v == null) return 0L
    try { return (v as Number).longValue() } catch (ignored) { return 0L }
}

private BigDecimal roundTemp(def v) {
    if (v == null) return null
    try { return (v as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP) }
    catch (ignored) { return null }
}

private String formatDuration(long seconds) {
    if (seconds <= 0) return "00:00:00"
    long h = seconds.intdiv(3600)
    long m = (seconds % 3600).intdiv(60)
    long s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

private void sendIfChanged(String name, def value, Map extra = [:]) {
    if (value == null) return
    def cur = device.currentValue(name)
    if (cur?.toString() == value?.toString()) return
    Map evt = [name: name, value: value]
    if (extra) evt.putAll(extra)
    if (txtEnable && name in ["printerStatus", "currentFile", "printProgress", "online"]) {
        evt.descriptionText = "${device.displayName} ${name} is ${value}"
        log.info evt.descriptionText
    }
    sendEvent(evt)
}
