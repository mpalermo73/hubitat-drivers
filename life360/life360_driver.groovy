/**
 * ------------------------------------------------------------------------------------------------------------------------------
 * ** LIFE360+ Hubitat Driver **
 * 
 * - see community discussion here: https://community.hubitat.com/t/release-life360/118544
 * 
 * ------------------------------------------------------------------------------------------------------------------------------
 *  ****************  Location Tracker User Driver  ****************
 *
 *  Design Usage:
 *  This driver stores the user data to be used with Location Tracker.
 *
 *  Copyright 2020-2022 Bryan Turcotte (@bptworld)
 *
 *  This App is free.  If you like and use this app, please be sure to mention it on the Hubitat forums!  Thanks.
 *
 *  Remember...I am not a professional programmer, everything I do takes a lot of time and research (then MORE research)!
 *  Donations are never necessary but always appreciated.  Donations to support development efforts are accepted via:
 *
 *  Paypal at: https://paypal.me/bptworld
 *
 *  Unless noted in the code, ALL code contained within this app is mine. You are free to change, ripout, copy, modify or
 *  otherwise use the code in anyway you want. This is a hobby, I'm more than happy to share what I have learned and help
 *  the community grow. Have FUN with it!
 *
 * ------------------------------------------------------------------------------------------------------------------------------
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
 *  App and Driver updates can be found at https://github.com/bptworld/Hubitat
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 *  Special thanks to namespace: "tmleafs", author: "tmleafs" for his work on the Life360 ST driver
 *
 *  Changes:
 *  3.0.0 - 05/05/23 - JP: Refactor driver
 *                       - Only notify on location or battery change
 *                       - add accuracy - useful to know how accurate a given location is
 *                       - don't set fields for both miles and km - just the one the user selects
 *                       - last updated time
 *                       - add phone and email fields
 *  1.6.1 - 03/22/22 - Adustment to stop and error when someone pauses themselves in the Life360 phone app. Thanks @jpage4500!
 *  1.6.0 - 01/07/21 - Interim release 
 *  1.5.5 - 12/20/20 - Reliability Improvements + Cleaned up Logging
 *  1.5.2 - 12/17/20 - Added initialization code for additional attributes / preferences
                     - Fixed Switch capability errors
 *  1.5.1 - 12/17/20 - Adjustments to work with Life360 Tracker
 *  1.5.0 - 12/06/20 - Moved all location functionality to child driver from parent app -and-
                       Added:
                         - Minimum Transit Speed Preference - use to set a custom speed threshold
                           for inTransit to become true (follows Km or Miles unit preference)
                         - Minimum Driving Speed Prederence - use to set a custom speed threshold
                           for isDriving to become true (follows Km or Miles unit preference)
                         - memberName attribute - First Name + Last Name from Life360 member info
                         - memberFriendlyName driver preference and attribute
 *  1.3.0 - 12/04/20 - Fixed condition to trigger presence & address changes
 *  ---
 *  1.0.0 - 01/18/20 - Initial release
 */

import java.text.SimpleDateFormat

metadata {
  definition (name: "Life360+ Driver", namespace: "jpage4500", author: "Joe Page", importUrl: "https://raw.githubusercontent.com/jpage4500/hubitat-drivers/master/life360/life360_driver.groovy") {
        capability "Actuator"
        capability "Presence Sensor"
        capability "Sensor"
        capability "Refresh"
        capability "Battery"
        capability "Power Source"
        capability "Switch" 
        capability "Contact Sensor" 
        capability "Acceleration Sensor" 

        // location data
        attribute "latitude", "number"
        attribute "longitude", "number"
        attribute "accuracy", "number"
        attribute "lastUpdated", "string"

        // driving data
        attribute "inTransit", "string"
        attribute "isDriving", "string"
        attribute "speed", "number"
        attribute "since", "number"

        // device data
        attribute "battery", "number"
        attribute "charge", "bool"
        attribute "status", "string"
        attribute "wifiState", "bool"
        attribute "shareLocation", "bool"

        // user data
        attribute "avatar", "string"
        attribute "memberName", "string"
        attribute "phone", "string"
        attribute "email", "string"

        // place data
        attribute "address1", "string"
        attribute "address1prev", "string"
        attribute "savedPlaces", "map"

        // hubitat device states
        attribute "contact", "string"
        attribute "acceleration", "string"
        attribute "switch", "string"

        // HTML attributes (optional)
        attribute "avatarHtml", "string"
        attribute "html", "string"

        command "refresh"
        // Trigger to manually force subscribe to / revalidate webhook to Life360 push notifications
        command "refreshCirclePush"
  }
}

preferences {
    input "isMiles", "bool", title: "Units: Miles (false for Kilometer)", required: true, defaultValue: true
    input "generateHtml", "bool", title: "HTML Fields (tile, avatar)", required: true, defaultValue: false

    input "transitThreshold", "text", title: "Minimum 'Transit' Speed", description: "Set minimum speed for inTransit to be true\n(leave as 0 to use Life360 data)", required: true, defaultValue: "0"
    input "drivingThreshold", "text", title: "Minimum 'Driving' Speed", description: "Set minimum speed for isDriving to be true\n(leave as 0 to use Life360 data)", required: true, defaultValue: "0"

    input "avatarFontSize", "text", title: "Avatar Font Size", required: true, defaultValue: "15"
    input "avatarSize", "text", title: "Avatar Size by Percentage", required: true, defaultValue: "75"

    input "logEnable", "bool", title: "Enable logging", required: true, defaultValue: false
}

def off() {
  // empty stub needed for switch capability not to throw errors
}

def on() {
  // empty stub needed for switch capability not to throw errors
}

def refresh() {
  parent.refresh()
}

def refreshCirclePush() {
    // Manually ensure that Life360 notifications subscription is current / valid
    log.info "Attempting to resubscribe to circle notifications"
    parent.createCircleSubscription()
}

def installed() {
    log.trace "Location Tracker User Driver Installed"

    if(logEnable) log.debug "Setting attributes to initial values"

    address1prev = "No Data"
    sendEvent ( name: address1prev, value: address1prev )
}

def updated() {
    log.info "Location Tracker User Driver has been Updated"
    refresh()
}

def generatePresenceEvent(member, thePlaces, home) {
    if (member.location == null) {
        // log.info "no location set for $member"
        return
    }

    // NOTE: only interested in sending updates device when location or battery changes
    // -- current values --
    def latitude = member.location.latitude.toFloat()               // current latitude
    def longitude = member.location.longitude.toFloat()             // current longitude
    def accuracy = member.location.accuracy.toFloat()               // current accuracy
    def battery = Math.round(member.location.battery.toDouble())    // current battery level
    // -- previous values (could be null) --
    def prevLatitude = device.currentValue('latitude')
    def prevLongitude = device.currentValue('longitude')
    def prevAccuracy = device.currentValue('accuracy')
    def prevBattery = device.currentValue('battery')
    if (prevLatitude != null && prevLatitude.toFloat() == latitude && prevLongitude != null && prevLongitude.toFloat() == longitude 
        && prevAccuracy != null && prevAccuracy.toFloat() == accuracy 
        && prevBattery != null && Math.round(prevBattery.toDouble()) == battery) {
        if(logEnable) log.trace "No change: lat:$latitude, long:$longitude, accuracy:$accuracy, battery:$battery"
        return
    }
    // location changed -- fetch any other useful values
    def lastUpdated = new Date()
    def isDriving = (member.location.isDriving == "0") ? "false" : "true"
    def speed = member.location.speed.toFloat()
    def inTransit = (member.location.inTransit == "0") ? "false" : "true"
    log.debug "changed: lat:$latitude, long:$longitude, acc:$accuracy, bat:$battery, charge:$charge, wifi:$wifiState"
    if (logEnable && (isDriving == "true" || inTransit == "true")) log.trace "isDriving: $isDriving, inTransit: $inTransit, speed: $speed"

    def charge = (member.location.charge == "0") ? "false" : "true"
    def wifiState = ( member.location.wifiState == "0" ) ? "false" : "true"

    // *** Member Name ***
    def memberFirstName = (member.firstName) ? member.firstName : ""
    def memberLastName = (member.lastName) ? member.lastName : ""
    def memberFullName = memberFirstName + " " + memberLastName
    sendEvent( name: "memberName", value: memberFullName )

    // *** Places List ***
    sendEvent( name: "savedPlaces", value: thePlaces )

    // *** Avatar ***
    def avatar
    def avatarHtml
    if (member.avatar != null){
        avatar = member.avatar
        avatarHtml =  "<img src= \"${avatar}\">"
    } else {
        avatar = "not set"
        avatarHtml = "not set"
    }
    sendEvent( name: "avatar", value: avatar )
    // send HTML avatar if generateHTML is enabled; otherwise clear it (only if previously set)
    if (generateHtml) sendEvent( name: "avatarHtml", value: generateHtml)
    else if (device.currentValue('avatarHtml') != null) sendEvent( name: "avatarHtml", value: null)

    // *** Location ***
    def homeLatitude = home.latitude.toFloat() // home latitude
    def homeLongitude = home.longitude.toFloat() // home longitude
    def homeRadius = home.radius.toFloat() // home radius
    def distanceAway = haversine(latitude, longitude, homeLatitude, homeLongitude) * 1000 // in meters
    // It is safe to assume that if we are within home radius then we are
    // both present and at home (to address any potential radius jitter)
    def memberPresence = (distanceAway <= homeRadius) ? "present" : "not present"

    // Where we think we are now is either at a named place or at address1
    def address1 = (member.location.name) ? member.location.name : member.location.address1
    // or perhaps we are on the free version of Life360 (address1  = null)
    if (address1 == null || address1 == "") address1 = "No Data"

    def address2 = (member.location.address2) ? member.location.address2 : member.location.shortaddress
    if (address2 == null || address2 == "") address2 = "No Data"

    // *** Address ***
    // If we are present then we are Home...
    address1 = (memberPresence == "present") ? "Home" : address1

    def prevAddress = device.currentValue('address1')

    if (address1 != prevAddress) {
        if(logEnable) log.trace "address1:$address1, prevAddress = $prevAddress"
        // Update old and current address information and trigger events
        sendEvent( name: "address1prev", value: prevAddress)
        sendEvent( name: "address1", value: address1 )
        sendEvent( name: "lastLocationUpdate", value: lastUpdated )
        sendEvent( name: "since", value: member.location.since )
    }

    // *** Presence ***
    def descriptionText = device.displayName + " has " + ( memberPresence == "present" ) ? "arrived" : "left"
    sendEvent (name: "presence", value: memberPresence, descriptionText: descriptionText)
    state.presence = memberPresence

    // if(logEnable) log.debug "results = $results"

    // *** Coordinates ***
    sendEvent( name: "longitude", value: longitude )
    sendEvent( name: "latitude", value: latitude )
    sendEvent( name: "accuracy", value: accuracy )

    // *** Speed ***
    // Below includes a check for iPhone sometime reporting speed of -1 and set to 0
    def speedMetric = (speed == -1) ? new Double (0) : speed.toDouble().round(2)

    // Update status attribute with appropriate distance units
    // and update temperature attribute with appropriate speed units
    // as chosen by users in device preferences
    def sStatus
    if (transitThreshold == null) transitThreshold = "0"
    def movethreshold = transitThreshold.toDouble().round(2)

    if (drivingThreshold == null) drivingThreshold = "0"
    def drivethreshold = drivingThreshold.toDouble().round(2)
    def speedUnits
    def distanceUnits
    if (isMiles) {
        // Speed in miles
        speedUnits = (speedMetric * 2.23694).toDouble().round(2)
        // Distance in miles
        distanceUnits = ((distanceAway / 1000) / 1.609344).toDouble().round(2)
        sStatus = sprintf("%.2f", speedUnits) + " miles from Home"
    } else {
        // Speed in km
        speedUnits = (speedMetric * 3.6).toDouble().round(2)
        // Distance in km
        distanceUnits = (distanceAway / 1000).toDouble().round(2)
        sStatus = sprintf("%.2f", speedUnits) + "  km from Home"
    }
    // if transit threshold specified in preferences then use it; else, use info provided by Life360
    if (movethreshold > 0) inTransit = (speedUnits > movethreshold) ? "true" : "false"
    // if driving threshold specified in preferences then use it; else, use info provided by Life360
    if (drivethreshold > 0) isDriving = (speedUnits > drivethreshold) ? "true" : "false"
    if (logEnable && (isDriving == "true" || inTransit == "true")) {
        // *** On the move ***
        if(logEnable) log.debug "speed: $speedUnits, distance: $distanceUnits, moverthreshold: $movethreshold, inTransit: $inTransit, drivethreshold: $drivethreshold, isDriving: $isDriving"
    }
    sendEvent( name: "inTransit", value: inTransit )
    sendEvent( name: "isDriving", value: isDriving )
    sendEvent( name: "speed", value: speedUnits )
    sendEvent( name: "distance", value: distanceUnits )
    sendEvent( name: "status", value: sStatus )
    state.status = sStatus

    // Set acceleration to active state if we are either moving or if we are anywhere outside home radius
    def sAcceleration = (inTransit == "true" || isDriving == "true" || memberPresence == "not present" ) ? "active" : "inactive"
    sendEvent( name: "acceleration", value: sAcceleration )

    // *** Battery Level ***
    sendEvent( name: "battery", value: battery )

    // *** Charging State ***
    sendEvent( name: "charge", value: charge )
    sendEvent( name: "powerSource", value: (charge == "true"? "DC" : "BTRY"))

    def cContact = (charge == "true" ) ? "open" : "closed"
    sendEvent( name: "contact", value: cContact )

    // *** Wifi ***
    def sSwitch = ( wifiState == "true" ) ? "on" : "off"
    sendEvent( name: "wifiState", value: wifiState )
    sendEvent( name: "switch", value: sSwitch )

    // ** Member Features **
    if (member.features != null) {
        def shareLocation = member.features.shareLocation == "0" ? "false" : "true"
        sendEvent ( name: "shareLocation", value: shareLocation )
    }

    // ** Member Communications **
    if (member.communications != null) {
        member.communications.each { comm ->
            def commType = comm.get('channel')
            if (commType != null && commType == "Voice") {
                sendEvent ( name: "phone", value: comm.value )
            } else if (commType != null && commType == "Email") {
                sendEvent ( name: "email", value: comm.value )
            }
        }
    }

    // *** Timestamp ***
    sendEvent ( name: "lastUpdated", value: lastUpdated )
    state.update = true

    // Lastly update the status tile
    if (generateHtml) sendStatusTile1()
    else if (device.currentValue('html') != null) sendEvent( name: "html", value: null)
}

def sendStatusTile1() {
    //if(logEnable) log.trace "In sendStatusTile1 - Making the Avatar Tile"
    def avat = device.currentValue("avatar")
    if(avat == null || avat == "") avat = avatarURL
    def add1 = device.currentValue('address1')
    def bLevel = device.currentValue('battery')
    def bCharge = device.currentValue('powerSource')
    def bSpeed = device.currentValue('speed')

    if(add1 == "No Data") add1 = "Between Places"

    def binTransit = device.currentValue('inTransit')
    if(binTransit == "true") {
        binTransita = "Moving"
    } else {
        binTransita = "Not Moving"
    }

    def bWifi = device.currentValue('wifiState')
    if(bWifi == "true") {
        bWifiS = "Wifi"
    } else {
        bWifiS = "No Wifi"
    }

    int sEpoch = device.currentValue('since')
    if(sEpoch == null) {
        theDate = use( groovy.time.TimeCategory ) {
            new Date( 0 )
        }
    } else {
        theDate = use( groovy.time.TimeCategory ) {
            new Date( 0 ) + sEpoch.seconds
        }
    }
    lUpdated = device.currentValue('lastUpdated')
    sFont = avatarFontSize.toInteger() / 1.25
    SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("E hh:mm a")
    String dateSince = DATE_FORMAT.format(theDate)

    theMap = "https://www.google.com/maps/search/?api=1&query=${device.currentValue('latitude')},${device.currentValue('longitude')}"

    tileMap = "<div style='overflow:auto;height:90%'><table width='100%'>"
    tileMap += "<tr><td width='25%' align=center><img src='${avat}' height='${avatarSize}%'>"
    tileMap += "<td width='75%'><p style='font-size:${avatarFontSize}px'>"
    tileMap += "At: <a href='${theMap}' target='_blank'>${add1}</a><br>"
    tileMap += "Since: ${dateSince}<br>${device.currentValue('status')}<br>"
    tileMap += "${binTransita} - ${bSpeed} ${isMiles?"MPH":"KMH"}<br>"

    tileMap += "Phone Lvl: ${bLevel} - ${bCharge} - ${bWifiS}</p>"
    tileMap += "<p style='width:100%'>${lUpdated}</p>" //Avi - cleaned up formatting (cosmetic / personal preference only)
    tileMap += "</table></div>"

    tileDevice1Count = tileMap.length()
    if(tileDevice1Count <= 1000) {
        if(logEnable) log.debug "tileMap - has ${tileDevice1Count} Characters<br>${tileMap}"
    } else {
        log.warn "In sendStatusTile1 - Too many characters to display on Dashboard (${tileDevice1Count})"
    }
    sendEvent(name: "html", value: tileMap, displayed: true)
}

def setMemberId(String memberId) {
   if(logEnable) log.debug "MemberId = ${memberId}"
   state.life360MemberId = memberId
}

def getMemberId() {
  if(logEnable) log.debug "MemberId = ${state.life360MemberId}"
    return(state.life360MemberId)
}

def haversine(lat1, lon1, lat2, lon2) {
    def R = 6372.8
    // In kilometers
    def dLat = Math.toRadians(lat2 - lat1)
    def dLon = Math.toRadians(lon2 - lon1)
    lat1 = Math.toRadians(lat1)
    lat2 = Math.toRadians(lat2)

    def a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
    def c = 2 * Math.asin(Math.sqrt(a))
    def d = R * c
    return(d)
}

private arriving() {
  if (device.currentValue('presence') == "not present" && memberPresence == "present")
    return true
  else
    return false
}

private departing() {
  if (device.currentValue('presence') == "present" && memberPresence == "not present")
    return true
  else
    return false
}

private formatLocalTime(format = "EEE, MMM d yyyy @ h:mm:ss a z", time = now()) {
  def formatter = new java.text.SimpleDateFormat(format)
  formatter.setTimeZone(location.timeZone)
  return formatter.format(time)
}
