/**
 *  ****************  Device Watchdog Parent App  ****************
 *
 *  Design Usage:
 *  Keep an eye on your devices and see how long it's been since they checked in.
 *
 *  Copyright 2018-2022 Bryan Turcotte (@bptworld)
 *  
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Remember...I am not a professional programmer, everything I do takes a lot of time and research!
 *  Donations are never necessary but always appreciated.  Donations to support development efforts are accepted via: 
 *
 *  Paypal at: https://paypal.me/bptworld
 *
 *-------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  If modifying this project, please keep the above header intact and add your comments/credits below - Thank you! -  @BPTWorld
 *
 *  App and Driver updates can be found at https://github.com/bptworld/Hubitat/
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Changes:
 *
 *  2.0.3 - 05/06/22 - Bundle Manager
 *  2.0.2 - 05/14/20 - Added color coding to status reports
 *  2.0.1 - 04/27/20 - Cosmetic changes
 *  2.0.0 - 08/18/19 - Now App Watchdog compliant
 *  --
 *  1.0.0 - 12/21/18 - Initial release.
 *
 */

#include BPTWorld.bpt-normalStuff

def setVersion(){
	state.version = null
}

definition(
    name:"Device Watchdog",
    namespace: "BPTWorld",
    author: "Bryan Turcotte",
    description: "Keep an eye on your devices and see how long it's been since they checked in.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/bptworld/Hubitat/master/Apps/Device%20Watchdog/DW-parent.groovy"
    )

preferences {
    page name: "mainPage", title: "", install: true, uninstall: true
    page name: "colorCodingConfig", title: "", install: false, uninstall: false, nextPage: "mainPage"
} 

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    unsubscribe()
    initialize()
}

def initialize() {
    log.info "There are ${childApps.size()} child apps"
    childApps.each {child ->
    log.info "Child app: ${child.label}"
    }
}

def mainPage() {
    dynamicPage(name: "mainPage") {
    	installCheck()
		if(state.appInstalled == 'COMPLETE'){
			section("Instructions:", hideable: true, hidden: true) {
				paragraph "<b>Notes:</b>"
				paragraph "- Devices may show up in multiple lists but each device only needs to be selected once.<br>- Be sure to generate a new report before trying to view the 'Last Device Status Report'.<br>- All changes are saved right away, no need to exit out and back in before generating a new report."
			}
  			section(getFormat("header-green", "${getImage("Blank")}"+" Child Apps")) {
				app(name: "anyOpenApp", appName: "Device Watchdog Child", namespace: "BPTWorld", title: "<b>Add a new 'Device Watchdog' child</b>", multiple: true)
  			}
                    
            section(getFormat("header-green", "${getImage("Blank")}"+" Device Attribute Color Options")) {
                if(colorActive || colorClear || colorLocked || colorOn || colorPresent || colorWet) {
                    href "colorCodingConfig", title:"${getImage("optionsGreen")} Device Attribute Color Options", description:"Click here for Options"
                } else {
                    href "colorCodingConfig", title:"${getImage("optionsRed")} Device Attribute Color Options", description:"Click here for Options"
                }
            }
            
 			section(getFormat("header-green", "${getImage("Blank")}"+" General")) {
       				label title: "Enter a name for parent app (optional)", required: false
 			}
			display2()
		}
	}
}

def colorCodingConfig() {
	dynamicPage(name: "colorCodingConfig", title: "", install:false, uninstall:false) {
        display()
        section() {
            paragraph "Assign colors to your device attributes. For use with Status Reports."
            
            input "colorActive", "text", title: "Active", width: 6, defaultValue: "red"
            input "colorInactive", "text", title: "Inactive", width: 6, defaultValue: "green"
            
            input "colorClear", "text", title: "Clear", width: 6, defaultValue: "green"
            input "colorDetected", "text", title: "Detected", width: 6, defaultValue: "red"
            
            input "colorLocked", "text", title: "Locked", width: 6, defaultValue: "green"
            input "colorUnlocked", "text", title: "Unlocked", width: 6, defaultValue: "red"
            
            input "colorOn", "text", title: "On", width: 6, defaultValue: "red"
            input "colorOff", "text", title: "Off", width: 6, defaultValue: "green"
            
            input "colorOpen", "text", title: "Open", width: 6, defaultValue: "red"
            input "colorClosed", "text", title: "Closed", width: 6, defaultValue: "green"
            
            input "colorPresent", "text", title: "Present", width: 6, defaultValue: "green"
            input "colorNotPresent", "text", title: "Not Present", width: 6, defaultValue: "red"
            
            input "colorWet", "text", title: "Wet", width: 6, defaultValue: "red"
            input "colorDry", "text", title: "Dry", width: 6, defaultValue: "green"
            
            input "colorSiren", "text", title: "Siren", width: 4, defaultValue: "red"
            input "colorStrobe", "text", title: "Strobe", width: 4, defaultValue: "red"
            input "colorBoth", "text", title: "Both", width: 4, defaultValue: "red"

            paragraph "<hr>"
        }
    }
}

def installCheck(){  
    display()
	state.appInstalled = app.getInstallationState() 
	if(state.appInstalled != 'COMPLETE'){
		section{paragraph "Please hit 'Done' to install '${app.label}' parent app "}
  	}
  	else{
    	log.info "Parent Installed OK"
  	}
}
