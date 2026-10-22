/*
 * Meross MSG100 Garage Door Setup
 * Namespace: Hubitat Integrations
 * Version: 2.2.0
 *
 * Logs into a Meross account once, finds MSG100 garage door openers on
 * that account (and only MSG100s - anything else Meross returns is
 * filtered out before it ever reaches the UI), locates the device's LAN
 * IP address (by scanning the local subnet or manual entry), and creates
 * a preconfigured "Meross MSG100 Garage Door" child device for the one
 * you pick.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URLEncoder
import java.security.MessageDigest

definition(
    name: 'Meross MSG100 Garage Door Setup',
    namespace: 'Hubitat Integrations',
    author: 'Gordon Thelander',
    description: 'Finds and adds Meross MSG100 garage door openers as Hubitat devices.',
    category: 'Bluetooth',
    menu: 'Integrations',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true
)

preferences {
    page(name: 'mainPage')
    page(name: 'addDeviceStep1')
    page(name: 'addDeviceStep2')
    page(name: 'addDeviceStep3')
    page(name: 'addDeviceStep4')
    page(name: 'listDevicesPage')
}

def mainPage() {
    return dynamicPage(name: 'mainPage', title: 'Meross MSG100 Garage Door Setup', install: true, uninstall: true) {
        section {
            paragraph('Adds Meross MSG100 WiFi garage door openers as Hubitat devices.')
            href(name: 'toAdd', page: 'addDeviceStep1', title: '<b>Add a Garage Door</b>', description: 'Log into Meross and add an MSG100.')
            href(name: 'toList', page: 'listDevicesPage', title: '<b>List Garage Doors</b>', description: 'Show devices already added.')
            input('debugLogging', 'bool', title: 'Enable debug logging', submitOnChange: true, defaultValue: false)
        }
    }
}

def listDevicesPage() {
    def message = getChildDevices()?.collect { "${it.label} (${it.getDeviceNetworkId()})" }?.join('\n') ?: 'No garage doors added yet.'
    return dynamicPage(name: 'listDevicesPage', title: 'Garage Doors', nextPage: 'mainPage') {
        section {
            paragraph(message)
        }
    }
}

def addDeviceStep1() {
    return dynamicPage(name: 'addDeviceStep1', title: 'Add a Garage Door (1 of 4): Account', nextPage: 'addDeviceStep2') {
        section {
            paragraph('<span style="color:red">A one-time login to your Meross account is required here to retrieve the local-control key for your device - Meross does not expose that key any other way. Your password is used only for this login and is discarded immediately after; it is never stored.</span>')
            input('merossEmail', 'string', title: 'Meross account email', required: true)
            input('merossPassword', 'password', title: 'Meross account password', required: true)
            input('merossApiBase', 'string', title: 'Meross API base URL', required: true, defaultValue: 'https://iotx-ap.meross.com',
                  description: 'Region-specific. If login fails, try https://iotx-us.meross.com or https://iotx-eu.meross.com.')
        }
    }
}

def addDeviceStep2() {
    def login = loginMeross(merossEmail, merossPassword, merossApiBase)
    app.removeSetting('merossPassword')

    if (!login.success) {
        return dynamicPage(name: 'addDeviceStep2', title: 'Login Failed', nextPage: 'mainPage') {
            section {
                paragraph(login.error)
            }
        }
    }

    state.merossKey = login.key
    def devicesResult = fetchDeviceList(login.token, merossApiBase)
    if (!devicesResult.success) {
        return dynamicPage(name: 'addDeviceStep2', title: 'Device Lookup Failed', nextPage: 'mainPage') {
            section {
                paragraph(devicesResult.error)
            }
        }
    }

    def msg100Devices = devicesResult.devices.findAll { (it.deviceType ?: '').toString().toLowerCase().contains('msg100') }
    state.data = msg100Devices

    if (msg100Devices.isEmpty()) {
        def seenTypes = devicesResult.devices.collect { it.deviceType }.unique().join(', ') ?: 'none'
        return dynamicPage(name: 'addDeviceStep2', title: 'No MSG100 Found', nextPage: 'mainPage') {
            section {
                paragraph("No MSG100 devices were found on this Meross account.")
                paragraph("Device types seen on this account: ${seenTypes}")
            }
        }
    }

    def options = msg100Devices.collectEntries { [(it.uuid): (it.devName ?: it.uuid)] }

    if (options.size() == 1) {
        String onlyUuid = options.keySet().first()
        app.updateSetting('selectedDevice', [value: onlyUuid, type: 'enum'])
        return dynamicPage(name: 'addDeviceStep2', title: 'Add a Garage Door (2 of 4): Device Found', nextPage: 'addDeviceStep3') {
            section {
                paragraph("Found 1 MSG100 device on your cloud account: '${options[onlyUuid]}'.")
                input('childDeviceLabel', 'text', title: '<span style="color:blue">Name for this device in Hubitat</span>', required: false,
                      defaultValue: options[onlyUuid])
            }
        }
    }

    return dynamicPage(name: 'addDeviceStep2', title: 'Add a Garage Door (2 of 4): Select Device', nextPage: 'addDeviceStep3') {
        section {
            input('selectedDevice', 'enum', title: "Select the garage door to add (${options.size()} MSG100 device(s) found)",
                  required: true, multiple: false, options: options)
        }
    }
}

def addDeviceStep3() {
    boolean waitingOnScan = state.scanDispatchedAt != null && !state.discoveredIp

    return dynamicPage(name: 'addDeviceStep3', title: 'Add a Garage Door (3 of 4): Find It on Your Network',
                        nextPage: 'addDeviceStep4', refreshInterval: waitingOnScan ? 3 : 0) {
        // Single-device accounts already named it on step 2, right next to
        // the "found" confirmation. Only ask again here when that didn't
        // happen (multiple devices on the account, so step 2 was a select
        // list instead of a confirmation with nowhere natural to put this).
        if (!childDeviceLabel) {
            section('Name') {
                input('childDeviceLabel', 'text', title: '<span style="color:blue">Name for this device in Hubitat</span>', required: false,
                      defaultValue: defaultChildDeviceLabel())
            }
        }

        section {
            input('scanForIp', 'bool', title: "Scan my network for this device's IP address (off = enter it manually)",
                  submitOnChange: true, defaultValue: false)
        }

        if (scanForIp) {
            section('Scan') {
                input('scanSubnetPrefix', 'text', title: 'IPv4 subnet prefix (first three octets)', required: true,
                      defaultValue: defaultSubnetPrefix())
                input('scanStartHost', 'number', title: 'First host address', required: true, defaultValue: 1, range: '1..254')
                input('scanEndHost', 'number', title: 'Last host address', required: true, defaultValue: 254, range: '1..254')
                input('scanRequestTimeoutSeconds', 'number', title: 'Probe timeout (seconds)', required: true, defaultValue: 2, range: '1..10')
                input('startScan', 'button', title: 'Start Scan')
                paragraph(scanStatusMessage())
            }
        } else {
            section('Manual entry') {
                input('manualDeviceIp', 'text', title: 'Garage door opener LAN IP address', required: true,
                      description: "Meross's cloud device list does not include this - find it in your router or the Meross app's WiFi settings.")
            }
        }
    }
}

def addDeviceStep4() {
    String resolvedIp = scanForIp ? state.discoveredIp : manualDeviceIp

    if (!resolvedIp) {
        return dynamicPage(name: 'addDeviceStep4', title: 'IP Address Needed', nextPage: 'addDeviceStep3') {
            section {
                paragraph('No IP address is available yet - go back and either finish the scan or switch to manual entry.')
            }
        }
    }

    def dni = "msg100:${selectedDevice}"
    String label = (childDeviceLabel?.toString()?.trim()) ?: defaultChildDeviceLabel()
    def message

    def existingChild = getChildDevice(dni)
    if (existingChild) {
        // Re-running the wizard for a device that already exists most
        // likely means the user wants to change its name or IP - apply
        // both rather than silently ignoring whatever was just entered.
        try {
            existingChild.setLabel(label)
            existingChild.updateSetting('deviceIp', resolvedIp)
            existingChild.updateSetting('key', [value: state.merossKey, type: 'password'])
            existingChild.initialize()
            message = "'${label}' already existed - updated its name and IP address."
        } catch (Exception e) {
            message = "Device already existed, but updating it failed: ${e}"
        }
    } else {
        try {
            def child = addChildDevice('Hubitat Integrations', 'Meross MSG100 Garage Door', dni, [label: label])
            child.updateSetting('deviceIp', resolvedIp)
            child.updateSetting('uuid', selectedDevice)
            child.updateSetting('key', [value: state.merossKey, type: 'password'])
            child.updateSetting('pollFrequencySeconds', [value: '300', type: 'enum'])
            child.updateSetting('openVerifyDelaySeconds', [value: 5, type: 'number'])
            child.updateSetting('closeVerifyDelaySeconds', [value: 20, type: 'number'])
            child.initialize()
            message = "Added '${label}' successfully using IP ${resolvedIp}."
        } catch (Exception e) {
            message = "Failed to add device: ${e}"
        }
    }

    app.removeSetting('selectedDevice')
    app.removeSetting('childDeviceLabel')
    app.removeSetting('scanForIp')
    app.removeSetting('manualDeviceIp')
    app.removeSetting('scanSubnetPrefix')
    app.removeSetting('scanStartHost')
    app.removeSetting('scanEndHost')
    app.removeSetting('scanRequestTimeoutSeconds')
    state.remove('data')
    state.remove('merossKey')
    state.remove('scanTargetUuid')
    state.remove('discoveredIp')
    state.remove('scanDispatchedAt')
    state.remove('scanProbeCount')

    return dynamicPage(name: 'addDeviceStep4', title: 'Add a Garage Door (4 of 4): Result', nextPage: 'mainPage') {
        section {
            paragraph(message)
        }
    }
}

def uninstalled() {
    getChildDevices()?.each { child ->
        try {
            deleteChildDevice(child.getDeviceNetworkId())
        } catch (Exception e) {
            log.warn("Could not delete ${child.displayName}: ${e.message}")
        }
    }
}

/*
 * Cloud login / device list
 */

private Map loginMeross(String email, String password, String apiBase) {
    def sign = buildSign()
    def params = JsonOutput.toJson([email: email, password: password]).bytes.encodeBase64().toString()
    def signature = md5Hex('23x17ahWarFH6w29' + sign.timestamp + sign.nonce + params)

    def body = "params=${urlEncode(params)}&sign=${signature}&timestamp=${sign.timestamp}&nonce=${sign.nonce}"
    def result = [success: false, error: 'Unknown login error']

    try {
        httpPost([uri: "${apiBase}/v1/Auth/signIn", contentType: 'application/x-www-form-urlencoded', body: body]) { resp ->
            logDebug("Meross signIn raw response: ${redactSecrets(resp.data)}")
            if (resp.status != 200) {
                result = [success: false, error: "HTTP error ${resp.status} logging into Meross."]
                return
            }

            def parsed = asMap(resp.data)
            if (parsed.containsKey('apiStatus') && parsed.apiStatus != 0) {
                result = [success: false, error: "Meross login failed (apiStatus=${parsed.apiStatus}): ${parsed.info ?: 'no details returned'}"]
                return
            }

            def data = (parsed.data instanceof Map) ? parsed.data : parsed
            String token = (data?.token ?: '').toString()
            String key = (data?.key ?: '').toString()
            if (!token) token = extractFieldFromRaw(resp.data, 'token')
            if (!key) key = extractFieldFromRaw(resp.data, 'key')

            if (!token || !key) {
                result = [success: false, error: "Meross login response did not contain a token/key. Enable debug logging on this app and check Live Logs for the raw response."]
                return
            }
            result = [success: true, token: token, key: key]
        }
    } catch (Exception e) {
        result = [success: false, error: "Error contacting Meross: ${e}"]
    }
    return result
}

private Map fetchDeviceList(String token, String apiBase) {
    def sign = buildSign()
    def emptyParams = JsonOutput.toJson([:]).bytes.encodeBase64().toString()
    def signature = md5Hex('23x17ahWarFH6w29' + sign.timestamp + sign.nonce + emptyParams)

    def result = [success: false, error: 'Unknown device lookup error']
    try {
        httpPostJson([
            uri    : "${apiBase}/v1/Device/devList",
            headers: ['Authorization': "Basic ${token}"],
            body   : [params: emptyParams, sign: signature, timestamp: sign.timestamp, nonce: sign.nonce]
        ]) { resp ->
            logDebug("Meross devList raw response: ${redactSecrets(resp.data)}")
            if (resp.status != 200) {
                result = [success: false, error: "HTTP error ${resp.status} fetching device list."]
                return
            }
            def parsed = asMap(resp.data)
            if (parsed.containsKey('apiStatus') && parsed.apiStatus != 0) {
                result = [success: false, error: "Meross device lookup failed (apiStatus=${parsed.apiStatus}): ${parsed.info ?: 'no details returned'}"]
                return
            }
            if (!(parsed.data instanceof List)) {
                result = [success: false, error: "Meross device list response was not in the expected shape. Enable debug logging on this app and check Live Logs for the raw response."]
                return
            }
            result = [success: true, devices: parsed.data]
        }
    } catch (Exception e) {
        result = [success: false, error: "Error contacting Meross: ${e}"]
    }
    return result
}

// Hubitat's httpPost/httpPostJson have been observed returning Meross's JSON
// response in more than one shape: already parsed, as a raw string, as a
// List wrapping a single Map, or as a Map whose one key is the actual JSON
// (string or nested Map) with a null value. Normalise all of those to a
// plain Map here.
private Map asMap(raw) {
    def parsed = raw
    if (parsed instanceof String) {
        try {
            parsed = new JsonSlurper().parseText(parsed)
        } catch (Exception ignored) {
            return [:]
        }
    }

    if (parsed instanceof List) {
        parsed = parsed.find { it instanceof Map } ?: [:]
    }

    if (parsed instanceof Map && !parsed.containsKey('data') && parsed.size() == 1) {
        def onlyKey = parsed.keySet().iterator().next()
        if (onlyKey instanceof Map) {
            parsed = onlyKey
        } else if (onlyKey instanceof String) {
            try {
                def reparsed = new JsonSlurper().parseText(onlyKey)
                if (reparsed instanceof Map) {
                    parsed = reparsed
                }
            } catch (Exception ignored) {
                // Leave parsed as-is - not the map-as-key shape after all.
            }
        }
    }

    return (parsed instanceof Map) ? parsed : [:]
}

// Last-resort fallback if Map access above still comes up empty - pull the
// field straight out of the raw response text with a regex.
private String extractFieldFromRaw(raw, String fieldName) {
    def text = raw?.toString() ?: ''
    def pattern = java.util.regex.Pattern.compile('"' + fieldName + '"\\s*:\\s*"([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"')
    def matcher = pattern.matcher(text)
    return matcher.find() ? matcher.group(1) : ''
}

// Debug logs print the raw Meross response for troubleshooting, but that
// response can contain the account token/key - mask those specific fields
// before logging rather than skipping the log entirely.
private String redactSecrets(raw) {
    String text = raw?.toString() ?: ''
    text = text.replaceAll(/"token"\s*:\s*"[^"]*"/, '"token":"[redacted]"')
    text = text.replaceAll(/"key"\s*:\s*"[^"]*"/, '"key":"[redacted]"')
    return text
}

private Map buildSign() {
    def chars = ('A'..'Z') + ('0'..'9')
    def random = new Random()
    String nonce = (1..16).collect { chars[random.nextInt(chars.size())] }.join()
    long timestamp = (now() / 1000L) as long
    return [nonce: nonce, timestamp: timestamp]
}

private String md5Hex(String value) {
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(value.bytes)
    return new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
}

private String urlEncode(String value) {
    return URLEncoder.encode(value, 'UTF-8')
}

private void logDebug(String msg) {
    if (debugLogging) {
        log.debug(msg)
    }
}

/*
 * LAN subnet scan for the device's IP address.
 *
 * Sends a deliberately-unsigned Appliance.System.All request to each
 * candidate host's /config endpoint. A genuine Meross device answers even
 * an invalid signature with a structured "5001 sign error" response (or,
 * occasionally, a real GETACK) that reveals its UUID via header.from -
 * this is enough to identify the specific device already selected from
 * the Meross account's device list without needing the key yet.
 *
 * All probes are dispatched together from a single button click rather
 * than paced over time: asynchttpPost is fire-and-forget, so this loop
 * only enqueues the requests and returns immediately, without depending
 * on the job scheduler at all.
 */

void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case 'startScan':
            beginTargetedScan()
            break
        default:
            log.warn("Unknown button: ${buttonName}")
            break
    }
}

private void beginTargetedScan() {
    String prefix = (scanSubnetPrefix?.toString()?.trim()) ?: defaultSubnetPrefix()
    Integer first = safeInteger(scanStartHost) ?: 1
    Integer last = safeInteger(scanEndHost) ?: 254

    if (!isValidSubnetPrefix(prefix) || first < 1 || last > 254 || first > last) {
        log.warn("Invalid scan range: ${prefix}.${first}-${last}")
        return
    }

    state.scanTargetUuid = selectedDevice
    state.discoveredIp = null
    state.scanDispatchedAt = now()
    state.scanProbeCount = 0

    Integer count = last - first + 1
    log.info("Scanning ${prefix}.${first}-${last} (${count} addresses) for MSG100 uuid ${selectedDevice}")

    for (int host = first; host <= last; host++) {
        sendScanProbe("${prefix}.${host}")
        state.scanProbeCount = (state.scanProbeCount as Integer) + 1
    }

    log.info("Dispatched ${state.scanProbeCount} scan probes")
}

private void sendScanProbe(String ip) {
    Integer timeoutSeconds = safeInteger(scanRequestTimeoutSeconds) ?: 2
    Map requestBody = buildUnsignedSystemAllRequest()

    Map params = [
        uri               : "http://${ip}:80/config",
        timeout           : timeoutSeconds,
        contentType       : 'application/json',
        requestContentType: 'application/json',
        headers           : ['Connection': 'close'],
        body              : requestBody
    ]

    try {
        asynchttpPost('scanResponseHandler', params, [ip: ip])
    } catch (Exception e) {
        logDebug("Unable to dispatch scan probe to ${ip}: ${e}")
    }
}

void scanResponseHandler(response, Map data) {
    if (state.discoveredIp) {
        // Already found the target device from an earlier response.
        return
    }

    String ip = data?.ip
    Integer status = null
    try {
        status = response.getStatus()
    } catch (Exception ignored) {
        // Leave status null - treated as no usable response below.
    }
    if (status != 200) {
        return
    }

    String body
    try {
        body = response.getData()?.toString()
    } catch (Exception ignored) {
        return
    }
    if (!body) {
        return
    }

    Map json = parseJsonMap(body)
    if (!json) {
        return
    }

    Map classification = classifyMerossResponse(json)
    if (classification.isMeross != true) {
        return
    }
    if (classification.uuid != state.scanTargetUuid) {
        logDebug("${ip} is a Meross endpoint but uuid ${classification.uuid} does not match target ${state.scanTargetUuid}")
        return
    }

    state.discoveredIp = ip
    log.info("Found the target MSG100 (uuid ${classification.uuid}) at ${ip}")
}

private String scanStatusMessage() {
    if (state.discoveredIp) {
        return "Found the device at ${state.discoveredIp}. Click Next to continue."
    }
    if (state.scanDispatchedAt) {
        Integer probeCount = (state.scanProbeCount ?: 0) as Integer
        long elapsedMs = now() - (state.scanDispatchedAt as long)
        long elapsedSeconds = elapsedMs / 1000L

        // A short elapsed time isn't proof nothing will be found - Hubitat
        // needs time to work through the whole dispatched batch, and
        // scanResponseHandler() has no time cutoff of its own, so a match
        // can still arrive after this message stops saying "waiting."
        long stillLikelyMs = Math.max(15000L, probeCount * 200L)
        if (elapsedMs < stillLikelyMs) {
            return "Dispatched ${probeCount} probes ${elapsedSeconds}s ago - still waiting for responses."
        }
        return "No matching response after ${elapsedSeconds}s. A response can still arrive late - wait a bit longer, click Start Scan again, narrow the address range, or switch to manual entry."
    }
    return 'Click Start Scan to search your network for this device.'
}

private Map buildUnsignedSystemAllRequest() {
    String messageId = UUID.randomUUID().toString().replace('-', '').toLowerCase()
    long timestamp = now().intdiv(1000)

    // Blank/missing key is intentional - a cloud-paired Meross device
    // should still answer with a "5001 sign error", which is the
    // discovery fingerprint we're looking for.
    String signature = md5Hex("${messageId}${timestamp}")

    return [
        header : [
            messageId     : messageId,
            namespace     : 'Appliance.System.All',
            method        : 'GET',
            payloadVersion: 1,
            from          : "/app/${messageId}/subscribe",
            timestamp     : timestamp,
            timestampMs   : 0,
            sign          : signature
        ],
        payload: [:]
    ]
}

private Map classifyMerossResponse(Map json) {
    Map header = (json.header instanceof Map) ? json.header as Map : [:]
    Map payload = (json.payload instanceof Map) ? json.payload as Map : [:]
    Map error = (payload.error instanceof Map) ? payload.error as Map : [:]

    String namespace = header.namespace?.toString()
    String method = header.method?.toString()
    String from = header.from?.toString()
    Integer errorCode = safeInteger(error.code)

    boolean signatureFingerprint = method == 'ERROR' && namespace == 'Appliance.System.All' &&
                                    errorCode == 5001 && from?.startsWith('/appliance/')

    Map all = (payload.all instanceof Map) ? payload.all as Map : [:]
    Map system = (all.system instanceof Map) ? all.system as Map : [:]
    Map hardware = (system.hardware instanceof Map) ? system.hardware as Map : [:]

    boolean validSystemAll = namespace == 'Appliance.System.All' && method == 'GETACK' && !hardware.isEmpty()

    if (!signatureFingerprint && !validSystemAll) {
        return [isMeross: false]
    }

    String uuid = hardware.uuid?.toString() ?: extractUuidFromHeader(from)
    return [isMeross: true, uuid: uuid]
}

// header.from's shape is /appliance/<uuid>/publish. Groovy's tokenize()
// drops the leading empty token from the leading slash, so this locates
// the "appliance" segment and reads the next one rather than indexing by
// a fixed position.
private String extractUuidFromHeader(String from) {
    if (!from) {
        return null
    }
    List<String> parts = from.tokenize('/')
    Integer idx = parts.indexOf('appliance')
    if (idx >= 0 && parts.size() > idx + 1) {
        return parts[idx + 1]
    }
    return null
}

private Map parseJsonMap(String body) {
    try {
        Object parsed = new JsonSlurper().parseText(body)
        return (parsed instanceof Map) ? parsed as Map : null
    } catch (Exception ignored) {
        return null
    }
}

private String defaultChildDeviceLabel() {
    def device = state.data?.find { it.uuid == selectedDevice }
    return device?.devName ?: 'Meross MSG100 Garage Door'
}

private String defaultSubnetPrefix() {
    try {
        String ip = location?.hub?.localIP?.toString()
        if (ip) {
            List<String> parts = ip.tokenize('.')
            if (parts.size() == 4) {
                return parts[0..2].join('.')
            }
        }
    } catch (Exception ignored) {
        // Fall through to the generic default below.
    }
    return '10.0.0'
}

private Boolean isValidSubnetPrefix(String prefix) {
    if (!prefix) {
        return false
    }
    List<String> parts = prefix.tokenize('.')
    if (parts.size() != 3) {
        return false
    }
    return parts.every { String part -> part ==~ /\d{1,3}/ && part.toInteger() >= 0 && part.toInteger() <= 255 }
}

private Integer safeInteger(Object value) {
    if (value == null) {
        return null
    }
    try {
        return value.toString().toInteger()
    } catch (Exception ignored) {
        return null
    }
}
