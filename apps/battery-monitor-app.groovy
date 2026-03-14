/**
 * Battery Monitor App for Hubitat
 *
 * Monitors battery levels on selected devices and sends notifications
 * when they drop below a specified threshold using the ntfy driver.
 * Includes daily scheduled checks and deduplication to avoid repeated alerts.
 *
 * Author: themarkwilliams
 * Date: March 14, 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0
 */

definition(
    name: "Battery Monitor",
    namespace: "themarkwilliams",
    author: "themarkwilliams",
    description: "Monitor battery levels and notify when low",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage", title: "Battery Monitor Settings", install: true, uninstall: true) {
        section("Devices to Monitor") {
            input name: "batteryDevices", type: "capability.battery", title: "Battery Devices", multiple: true, required: true
        }
        section("Alert Threshold") {
            input name: "batteryThreshold", type: "number", title: "Low Battery Threshold (%)", defaultValue: 20, required: true, range: "1..100"
        }
        section("Notification Device") {
            input name: "notificationDevice", type: "capability.notification", title: "Notification Device", required: true
        }
        section("Scheduled Check") {
            input name: "checkTime", type: "time", title: "Daily check time", defaultValue: "08:00", required: true
            input name: "notifyOnRecovery", type: "bool", title: "Notify when battery recovers above threshold", defaultValue: false
        }
        section("Options") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def installed() {
    log.info "Battery Monitor App Installed"
    initialize()
}

def updated() {
    log.info "Battery Monitor App Updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (logEnable) log.debug "Initializing Battery Monitor"
    if (!state.lowDevices) state.lowDevices = [:]
    subscribe(batteryDevices, "battery", batteryHandler)
    schedule(checkTime, dailyCheck)
    if (logEnable) runIn(1800, logsOff)
    runIn(5, checkAllDevices)  // catch already-low devices on install/update
}

def logsOff() {
    log.warn "Debug logging disabled."
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

def dailyCheck() {
    if (logEnable) log.debug "Running daily battery check"
    checkAllDevices()
}

def checkAllDevices() {
    batteryDevices.each { device ->
        def rawValue = device.currentValue("battery")
        if (rawValue == null) {
            log.warn "No battery value available for ${device.displayName}"
            return
        }
        def level = parseLevel(rawValue)
        if (level == null) {
            log.warn "Invalid battery value '${rawValue}' from ${device.displayName}"
            return
        }
        evaluateLevel(device, level)
    }
}

def batteryHandler(evt) {
    def level = parseLevel(evt.value)
    if (level == null) {
        log.warn "Invalid battery event value '${evt.value}' from ${evt.device.displayName}"
        return
    }
    if (logEnable) log.debug "Battery event: ${evt.device.displayName} = ${level}% (threshold: ${batteryThreshold}%)"
    evaluateLevel(evt.device, level)
}

private Integer parseLevel(value) {
    if (value == null) return null
    def str = value.toString().trim()
    if (!str.isNumber()) return null
    def num = str.toFloat().toInt()
    if (num < 0 || num > 100) return null
    return num
}

private void evaluateLevel(device, int level) {
    def deviceId = device.id.toString()
    def threshold = batteryThreshold as int
    def wasLow = state.lowDevices.containsKey(deviceId)

    if (level < threshold) {
        if (!wasLow) {
            def message = "Battery low on ${device.displayName}: ${level}% (below ${threshold}%)"
            if (logEnable) log.debug "Sending low notification: ${message}"
            notificationDevice.deviceNotification(message)
            state.lowDevices[deviceId] = level
        } else {
            if (logEnable) log.debug "Skipping repeat notification for ${device.displayName}: still at ${level}%"
        }
    } else {
        if (wasLow) {
            state.lowDevices.remove(deviceId)
            if (notifyOnRecovery) {
                def message = "Battery recovered on ${device.displayName}: ${level}%"
                if (logEnable) log.debug "Sending recovery notification: ${message}"
                notificationDevice.deviceNotification(message)
            }
        }
    }
}
