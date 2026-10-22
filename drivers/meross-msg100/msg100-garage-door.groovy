/*
 * Meross MSG100 Garage Door
 * Namespace: Hubitat Integrations
 * Version: 2.2.0
 * Parent app: Meross MSG100 Garage Door Setup
 *
 * Controls a Meross MSG100 WiFi garage door opener directly over the LAN.
 * Single-channel device: channel 0 is the only door, so there is no
 * channel selector and no channel-index handling anywhere in this file.
 *
 * Uses asynchttpPost for LAN requests rather than HubAction/sendHubCommand
 * + parse(). The latter only routes an inbound response to a device's
 * parse() method when the Device Network ID is the hex-encoded IP of the
 * device; this integration's DNI is keyed off the Meross device UUID
 * instead, so that routing never fires. asynchttpPost correlates
 * request/response through its own callback mechanism and doesn't care
 * about DNI format.
 */

import groovy.json.JsonSlurper
import java.security.MessageDigest

metadata {
    definition(name: 'Meross MSG100 Garage Door', namespace: 'Hubitat Integrations', author: 'Gordon Thelander') {
        capability 'DoorControl'
        capability 'GarageDoorControl'
        capability 'Actuator'
        capability 'ContactSensor'
        capability 'Refresh'
        capability 'Polling'
        capability 'Initialize'

        attribute 'model', 'string'
        attribute 'firmware', 'string'
        attribute 'lastRefresh', 'string'
        attribute 'commStatus', 'enum', ['online', 'offline', 'unknown']
    }

    preferences {
        section('Device Selection') {
            input('deviceIp', 'text', title: 'Device IP address', required: true)
            input('uuid', 'text', title: 'Meross device UUID', required: true)
            input('key', 'password', title: 'Meross account key', required: true)
        }
        section('Polling') {
            input('pollFrequencySeconds', 'enum', title: 'Polling frequency', required: true, defaultValue: '300',
                  options: ['0': 'Disabled', '30': '30 seconds', '60': '1 minute', '300': '5 minutes', '600': '10 minutes', '900': '15 minutes', '1800': '30 minutes'])
            input('openVerifyDelaySeconds', 'number', title: 'Status re-check delay after open (seconds)', required: true, defaultValue: 5)
            input('closeVerifyDelaySeconds', 'number', title: 'Status re-check delay after close (seconds)', required: true, defaultValue: 20)
        }
        section('Logging') {
            input('debugLogging', 'bool', title: 'Enable debug logging', defaultValue: false)
        }
    }
}

def installed() {
    log.info('Installed')
    initialize()
}

def updated() {
    log.info('Updated')
    initialize()
}

def initialize() {
    logDebug('Initializing')
    unschedule()
    sendEvent(name: 'commStatus', value: 'unknown', isStateChange: false)
    schedulePolling()
    refresh()
}

def open() {
    log.info('Opening garage door')
    sendCommand(true)
}

def close() {
    log.info('Closing garage door')
    sendCommand(false)
}

def poll() {
    refresh()
    if ((state.pollScheduleMode ?: 'runIn') == 'runIn') {
        schedulePolling()
    }
}

def refresh() {
    if (!hasMinimumConfig()) {
        warnMissingConfig()
        return
    }
    try {
        sendMerossRequest('Appliance.System.All', 'GET', [:], 'refreshCallback', [:])
    } catch (Exception e) {
        log.error("refresh() failed: ${e}")
    }
}

private void sendCommand(boolean openRequested) {
    if (!hasMinimumConfig()) {
        warnMissingConfig()
        return
    }

    sendEvent(name: 'door', value: openRequested ? 'opening' : 'closing', isStateChange: true)

    try {
        def payload = [state: [open: openRequested ? 1 : 0, channel: 0, uuid: uuid]]
        sendMerossRequest('Appliance.GarageDoor.State', 'SET', payload, 'commandCallback', [open: openRequested])

        Integer verifyDelay = (openRequested ? openVerifyDelaySeconds : closeVerifyDelaySeconds) as Integer
        if (verifyDelay != null && verifyDelay > 0) {
            runIn(verifyDelay, 'refresh', [overwrite: true])
        }
    } catch (Exception e) {
        log.error("sendCommand(open=${openRequested}) failed: ${e}")
    }
}

private void sendMerossRequest(String namespace, String method, Map payload, String callback, Map callbackData) {
    def sign = buildSign()
    def body = [
        payload: payload,
        header : [
            messageId     : sign.messageId,
            method        : method,
            from          : "http://${deviceIp}/config",
            namespace     : namespace,
            triggerSrc    : 'hubitat',
            timestamp     : sign.timestamp,
            sign          : sign.sign,
            payloadVersion: 1,
            uuid          : uuid
        ]
    ]
    def params = [
        uri        : "http://${deviceIp}",
        path       : '/config',
        contentType: 'application/json',
        body       : body,
        headers    : [Connection: 'keep-alive']
    ]
    asynchttpPost(callback, params, callbackData)
}

def refreshCallback(resp, data) {
    if (resp?.hasError()) {
        sendEvent(name: 'commStatus', value: 'offline', isStateChange: true)
        log.warn("Garage door did not respond to status refresh: ${resp.getErrorMessage()}")
        return
    }
    if (!resp?.data?.trim()) {
        logDebug('refreshCallback() received an empty response body, ignoring')
        return
    }

    try {
        def body = new JsonSlurper().parseText(resp.data)
        def all = body?.payload?.all
        if (!all) {
            log.error('Garage door status response missing payload.all')
            return
        }

        def doorState = all.digest?.garageDoor?.getAt(0)
        if (doorState != null) {
            boolean isOpen = doorState.open as boolean
            sendEvent(name: 'door', value: isOpen ? 'open' : 'closed', isStateChange: true)
            sendEvent(name: 'contact', value: isOpen ? 'open' : 'closed', isStateChange: true)
        }

        sendEvent(name: 'model', value: all.system?.hardware?.type, isStateChange: false)
        sendEvent(name: 'firmware', value: all.system?.firmware?.version, isStateChange: false)
        sendEvent(name: 'commStatus', value: 'online', isStateChange: true)
        sendEvent(name: 'lastRefresh', value: nowString(), isStateChange: true)
    } catch (Exception e) {
        log.error("refreshCallback() failed to parse response: ${e}")
        sendEvent(name: 'commStatus', value: 'offline', isStateChange: true)
    }
}

def commandCallback(resp, data) {
    String which = data?.open ? 'open' : 'close'

    if (resp?.hasError()) {
        failCommand(which, "transport error: ${resp.getErrorMessage()}")
        return
    }

    String body = resp?.data?.toString()
    if (!body?.trim()) {
        failCommand(which, 'empty response body')
        return
    }

    Map parsed
    try {
        parsed = new JsonSlurper().parseText(body) as Map
    } catch (Exception e) {
        failCommand(which, "unparseable response: ${e}")
        return
    }

    if (parsed?.header?.method == 'ERROR') {
        def error = parsed?.payload?.error
        failCommand(which, "device rejected the command (code=${error?.code}): ${error?.detail ?: 'no detail'}")
        return
    }

    sendEvent(name: 'commStatus', value: 'online', isStateChange: true)
    logDebug("Garage door acknowledged the ${which} command")
}

private void failCommand(String which, String reason) {
    sendEvent(name: 'commStatus', value: 'offline', isStateChange: true)
    sendEvent(name: 'door', value: 'unknown', isStateChange: true)
    log.warn("Garage door did not acknowledge the ${which} command - ${reason}")
}

private void schedulePolling() {
    Integer seconds = parseIntOrDefault(pollFrequencySeconds, 300)
    unschedule('poll')

    if (seconds <= 0) {
        state.pollScheduleMode = 'disabled'
        logDebug('Polling disabled')
        return
    }

    switch (seconds) {
        case 60:
            state.pollScheduleMode = 'recurring'
            runEvery1Minute('poll')
            break
        case 300:
            state.pollScheduleMode = 'recurring'
            runEvery5Minutes('poll')
            break
        case 600:
            state.pollScheduleMode = 'recurring'
            runEvery10Minutes('poll')
            break
        case 900:
            state.pollScheduleMode = 'recurring'
            runEvery15Minutes('poll')
            break
        case 1800:
            state.pollScheduleMode = 'recurring'
            runEvery30Minutes('poll')
            break
        default:
            state.pollScheduleMode = 'runIn'
            runIn(seconds, 'poll', [overwrite: true])
            break
    }
    logDebug("Polling scheduled every ${seconds} seconds (mode=${state.pollScheduleMode})")
}

private boolean hasMinimumConfig() {
    return settingPresent(deviceIp) && settingPresent(uuid) && settingPresent(key)
}

private void warnMissingConfig() {
    sendEvent(name: 'commStatus', value: 'unknown', isStateChange: false)
    log.warn("Missing configuration - deviceIp=${settingPresent(deviceIp)}, uuid=${settingPresent(uuid)}, key=${settingPresent(key)}")
}

private boolean settingPresent(value) {
    return value != null && value.toString().trim().length() > 0
}

private Integer parseIntOrDefault(value, Integer defaultValue) {
    try {
        return value == null ? defaultValue : value.toString().toInteger()
    } catch (Exception ignored) {
        return defaultValue
    }
}

private Map buildSign() {
    def chars = ('a'..'z') + ('0'..'9')
    def random = new Random()
    String randomString = (1..16).collect { chars[random.nextInt(chars.size())] }.join()

    long currentTime = (now() / 1000L) as long
    String messageId = md5Hex(randomString + currentTime.toString())
    String sign = md5Hex(messageId + key + currentTime.toString())

    return [messageId: messageId, sign: sign, timestamp: currentTime]
}

private String md5Hex(String value) {
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(value.bytes)
    return new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
}

private String nowString() {
    def tz = location?.timeZone ?: TimeZone.getTimeZone('UTC')
    return new Date().format('yyyy-MM-dd HH:mm:ss', tz)
}

private void logDebug(String msg) {
    if (debugLogging) {
        log.debug(msg)
    }
}
