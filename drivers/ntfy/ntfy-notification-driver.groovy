/**
 * Ntfy Notification Driver for Hubitat
 *
 * This driver enables sending notifications through the ntfy.sh service
 * or self-hosted ntfy instances.
 *
 * Author: themarkwilliams
 * Date: April 25, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0
 */

// Emoji REference - https://docs.ntfy.sh/emojis/

metadata {
    definition(
        name: "Ntfy Notification",
        namespace: "themarkwilliams",
        author: "themarkwilliams",
        description: "Send notifications through ntfy service",
        iconUrl: "",
        iconX2Url: ""
    ) {
        capability "Notification"
        capability "Speech Synthesis"
        
        attribute "lastMessageSent", "STRING"
        
        command "testMessage", [[name: "Test Message*", type: "STRING", description: "Send a test message"]]
        command "clearLastMessage", []
        command "setPriority", [[name:"priority*", type: "ENUM", constraints: ["min", "low", "default", "high", "max"], description: "Set the priority for notifications"]]
        command "setTitle", [[name:"title*", type: "STRING", description: "Set a default title for notifications"]]
        command "setTags", [[name:"tags*", type: "STRING", description: "Set tags for notifications (comma-separated)"]]
    }
    
    preferences {
        input name: "ntfyServer", type: "text", title: "Ntfy Server", description: "Your ntfy server URL (without https://)", defaultValue: "ntfy.sh", required: true
        input name: "ntfyTopic", type: "text", title: "Topic Name", description: "Topic to publish to", required: true
        input name: "ntfyAuthToken", type: "password", title: "Authentication Token", description: "Your ntfy authentication token", required: true
        input name: "defaultPriority", type: "enum", title: "Default Priority", options: ["min", "low", "default", "high", "max"], defaultValue: "default", required: true
        input name: "defaultTitle", type: "text", title: "Default Title", description: "Default title for notifications", defaultValue: "Hubitat Alert"
        input name: "defaultTags", type: "text", title: "Default Tags", description: "Default tags for notifications (comma-separated, e.g. warning,home)", defaultValue: "hubitat"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    log.info "Ntfy Notification Driver Installed"
    initialize()
}

def updated() {
    log.info "Ntfy Notification Driver Updated"
    initialize()
}

def initialize() {
    state.priority = settings.defaultPriority ?: "default"
    state.title = settings.defaultTitle ?: "Hubitat Alert"
    state.tags = settings.defaultTags ?: "hubitat"
    if (!state.lastMessage) state.lastMessage = ""
    if (logEnable) runIn(1800, logsOff)
}

def logsOff() {
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// Set notification priority
def setPriority(priority) {
    state.priority = priority
    if (logEnable) log.debug "Priority set to ${priority}"
}

// Set notification title
def setTitle(title) {
    state.title = title
    if (logEnable) log.debug "Title set to ${title}"
}

// Set notification tags
def setTags(tags) {
    state.tags = tags
    if (logEnable) log.debug "Tags set to ${tags}"
}

// Clear last message
def clearLastMessage() {
    state.lastMessage = ""
    sendEvent(name: "lastMessageSent", value: "")
    if (logEnable) log.debug "Last message cleared"
}

// Send a test message
def testMessage(message) {
    deviceNotification(message)
}

// Implementation of "Notification" capability
def deviceNotification(message) {
    if (logEnable) log.debug "Sending notification: ${message}"
    
    // Save last message
    state.lastMessage = message
    sendEvent(name: "lastMessageSent", value: message)
    
    // Send to ntfy
    sendNtfyMessage(message)
}

// Implementation of "Speech Synthesis" capability
def speak(String text, BigDecimal volume = null, String voice = null) {
    deviceNotification(text)
}

// Send message to ntfy
private sendNtfyMessage(message) {
    if (!ntfyServer || !ntfyTopic || !ntfyAuthToken) {
        log.error "Missing configuration for ntfy service"
        return
    }
    
    def topic = ntfyTopic.trim()
    def title = state.title ?: settings.defaultTitle
    def priority = state.priority ?: settings.defaultPriority
    def tags = state.tags ?: settings.defaultTags
    
    def uri = "https://${ntfyServer}/${topic}"
    
    if (logEnable) log.debug "Sending to ntfy: URI=${uri}, Title=${title}, Priority=${priority}, Tags=${tags}"
    
    try {
        def postParams = [
            uri: uri,
            requestContentType: "text/plain",
            headers: [
                "Authorization": "Bearer ${ntfyAuthToken}",
                "Title": title,
                "Priority": priority,
                "Tags": tags
            ],
            body: message
        ]
        
        httpPost(postParams) { response ->
            if (logEnable) log.debug "ntfy message sent successfully (HTTP ${response.status})"
        }
    } catch (Exception e) {
        log.error "Error sending ntfy message: ${e.message}"
    }
}