/**
 *  Lift Off
 *  Space-X Launch Schedule Integration
 *  Copyright 2021 Justin Leonard
 *
 *  Licensed Virtual the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *
 *    Date        Who            What
 *    ----        ---            ----
 */

import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.time.TimeCategory

metadata
{
    definition(name: "Lift Off", namespace: "lnjustin", author: "lnjustin", importUrl: "")
    {
        capability "Configuration"
        capability "Refresh"        
        capability "Actuator"
        capability "Switch"
        
        attribute "tile", "string" 
        
        attribute "time", "string"
        attribute "timeStr", "string"
        attribute "name", "string"
        attribute "description", "string"
        attribute "status", "string"     
    }
}

preferences
{
    section
    {
        input name: "clearWhenInactive", type: "bool", title: "Clear Tile When Inactive?", defaultValue: false
        input name: "hoursInactive", type: "number", title: "Inactivity Threshold (In Hours)", defaultValue: 24
        input name: "refreshInterval", type: "number", title: "Refresh Interval (In Minutes)", defaultValue: 120
        input name: "showName", type: "bool", title: "Show Launch Name on Tile?", defaultValue: false
        input name: "showLocality", type: "bool", title: "Show Launch Location on Tile?", defaultValue: false
        input name: "textColor", type: "text", title: "Tile Text Color (Hex)", defaultValue: "#000000"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}


def configure()
{
    state.clear()
    refresh()
}

def refresh()
{
    setState()
    updateDisplayedLaunch()
    scheduleUpdate()  
    def refreshSecs = refreshInterval ? refreshInterval * 60 : 120 * 60
    runIn(refreshSecs, refresh)
}

def setState() {
    setLatestLaunch()
    setNextLaunch()    
}

def updateDisplayedLaunch() {
    def launch = getLaunchToDisplay()
    def switchValue = getSwitchValue()  
    def tile = getTile(launch)
    updateDevice([launch: launch, switchValue: switchValue, tile: tile])  
}

def updateDevice(data) {
    sendEvent(name: "time", value: data.launch != null ? data.launch.time : "No Launch Data")
    sendEvent(name: "timeStr", value: data.launch != null ? data.launch.timeStr : "No Launch Data")
    sendEvent(name: "name", value: data.launch != null ? data.launch.name : "No Launch Data")
    sendEvent(name: "location", value: data.launch != null ? data.launch.locality : "No Launch Data")
    def description = ""
    if (data.launch == null) description = "No Launch Data"
    else if (data.launch.description == null) description = "No Description Available"
    else description = data.launch.description
    sendEvent(name: "description", value: description)
    sendEvent(name: "status", value: data.launch != null ? data.launch.status : "No Launch Data")
    
    sendEvent(name: "tile", value: data.tile)
    sendEvent(name: "switch", value: data.switchValue)    
}

def scheduleUpdate() {
    // update when time to switch to display next launch
    Date updateAtDate = getDateToSwitchFromLastToNextLaunch()   
    runOnce(updateAtDate, refresh)
    
    // update after next launch
    // TO DO: identify best time to refresh
    if (state.nextLaunch) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        def delayAfterLaunch = null
        // update launch when API likely to have new data
        use(TimeCategory ) {
           delayAfterLaunch = nextLaunchTime + 3.minutes
        }
        runOnce(delayAfterLaunch, refresh)
    }
}

def getSwitchValue()  {
    def switchValue = "off"
    if (state.latestLaunch && isToday(new Date(state.latestLaunch.time))) switchValue = "on"
    if (state.nextLaunch && isToday(new Date(state.nextLaunch.time))) switchValue = "on"
    return switchValue    
}

def getTile(launch) {
    def tile = "<div style='overflow:auto;height:90%'></div>"
    def colorStyle = ""
    if (textColor != "#000000") colorStyle = "color:" + textColor
    if (!clearWhenInactive || (clearWhenInactive && !isInactive())) {
        if (launch != null) {
            tile = "<div style='overflow:auto;height:90%;${colorStyle}'><table width='100%'>"
            tile += "<tr><td width='100%' align=center><img src='${launch.patch}' width='100%'></td>"
            if (showName) tile += "<tr style='padding-bottom: 0em'><td width='100%' align=center colspan=3>${launch.name}</td></tr>"
            tile += "<tr style='padding-bottom: 0em'><td width='100%' align=center colspan=3>${launch.timeStr}</td></tr>"
            if (showLocality) tile += "<tr style='padding-bottom: 0em'><td width='100%' align=center colspan=3>${launch.locality}</td></tr>"
            if (launch.status != "Scheduled" && launch.status != null && launch.status != "null") tile += "<tr style='padding-bottom: 0em'><td width='100%' align=center colspan=3>${launch.status}</td></tr>"
            tile += "</table></div>"  
        }
    }
    // If no launch to display, display nothing (keep dashboard clean)
    return tile    
}

Boolean isInactive() {
    def isInactive = false
    def lastLaunchInactive = false
    def nextLaunchInactive = false
    Date now = new Date()
    if (state.lastLaunch && hoursInactive) {
        def lastLaunchTime = new Date(state.lastLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(lastLaunchTime)
        cal.add(Calendar.HOUR, hoursInactive)
        Date inactiveDateTime = cal.time
        if (now.after(inactiveDateTime)) lastLaunchInactive = true
    }
    if (state.nextLaunch && hoursInactive) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(nextLaunchTime)
        cal.add(Calendar.HOUR, (hoursInactive * -1 as Integer))
        Date activeDateTime = cal.time
        if (!now.after(activeDateTime)) nextLaunchInactive = true
    }    
    if (lastLaunchInactive || nextLaunchInactive) {
        isInactive = true
        logDebug("No launch activity within the past ${hoursInactive} hour(s) or within the next ${hoursInactive} hour(s). ${clearWhenInactive ? "Hiding tile." : ""}")
    }
    return isInactive
}

def setLatestLaunch() {
    def latest = httpGetExec("launches/latest")
    def unixTimestamp = (latest.date_unix as Long) * 1000
    def launchTime = new Date(unixTimestamp)
    def status = latest.success == "true" ? "Success" : "Failure"
    
    def launchPadID = latest.launchpad
    def launchPad = httpGetExec("launchpads/" + launchPadID)
    def locality = launchPad?.locality
    
    state.latestLaunch = [time: launchTime.getTime() , timeStr: getTimeStr(launchTime),  name: latest.name, description: latest.details, locality: locality, patch: latest.links.patch.large, status: status]
}

def setNextLaunch() {
    def next = httpGetExec("launches/next")
    def unixTimestamp = (next.date_unix as Long) * 1000
    def launchTime = new Date(unixTimestamp)
    
    def launchPadID = next.launchpad
    def launchPad = httpGetExec("launchpads/" + launchPadID)
    def locality = launchPad?.locality
    state.nextLaunch = [time: launchTime.getTime(), timeStr: getTimeStr(launchTime),  name: next.name, description: next.details, locality: locality, patch: next.links.patch.large, status: "Scheduled"]    
}

def getLaunchToDisplay() {
    def launch = null
    if (state.latestLaunch == null && state.nextLaunch != null) launch = state.nextLaunch
    else if (state.nextLaunch == null && state.latestLaunch != null) launch = state.latestLaunch
    else if (state.latestLaunch != null && state.nextLaunch != null) {
        def now = new Date()        
        Date updateAtDate = getDateToSwitchFromLastToNextLaunch()
        if (now.after(updateAtDate) || now.equals(updateAtDate)) launch = state.nextLaunch
        else launch = state.latestLaunch
    }
    return launch
}

def getDateToSwitchFromLastToNextLaunch() {
    if (!state.latestLaunch || !state.nextLaunch) {
        return null
        log.error "No launch in state."
    }
    def lastLaunchTime = new Date(state.latestLaunch.time)
    def nextLaunchTime = new Date(state.nextLaunch.time)
    def now = new Date()
    Date date = null
    def minsBetweenLaunches = Math.round(getSecondsBetweenDates(lastLaunchTime, nextLaunchTime) / 60)                                        
    if (minsBetweenLaunches < 1440) {
        // if less than 24 hours between launches, switch to next launch halfway between launches
        if (now.after(nextLaunchTime)) date = now // if launch is already scheduled to start, switch now
        else {
            def switchTime = Math.round(getSecondsBetweenDates(now, nextLaunchTime) / 120) as Integer // switch halfway between now and the next launch time
            Calendar cal = Calendar.getInstance()
            cal.setTimeZone(location.timeZone)
            cal.setTime(lastLaunchTime)
            cal.add(Calendar.MINUTE, switchTime)
            date = cal.time
        }
    }
    else {
        // switch to display next launch 1 day after the last launch
        date = lastLaunchTime + 1
    }
    return date   
}

def isToday(Date date) {
    def isToday = false
    def today = new Date().clearTime()
    def dateCopy = new Date(date.getTime())
    def dateObj = dateCopy.clearTime()
    if (dateObj.equals(today)) isToday = true
    return isToday
}

String getTimeStr(Date launchTime) {
    def nextWeek = new Date() + 7
    def lastWeek = new Date() - 7
    def now = new Date()
    def dateFormat = null
    def timeStrPrefix = ""
    if (launchTime.after(nextWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (isToday(launchTime)) {
        timeStrPrefix = "Today "
        dateFormat = new SimpleDateFormat("h:mm a")
    }
    else if (launchTime.before(lastWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (launchTime.before(now)) {
         timeStrPrefix = "This Past "   
        dateFormat = new SimpleDateFormat("EEE h:mm a")
    }
    else dateFormat = new SimpleDateFormat("EEE h:mm a")
    dateFormat.setTimeZone(location.timeZone)        
    def timeStr = timeStrPrefix + dateFormat.format(launchTime)    
    return timeStr
}

def getSecondsBetweenDates(Date startDate, Date endDate) {
    try {
        def difference = endDate.getTime() - startDate.getTime()
        return Math.round(difference/1000)
    } catch (ex) {
        log.error "getSecondsBetweenDates Exception: ${ex}"
        return 1000
    }
}

def updated()
{
    configure()
}

def uninstalled()
{

}

def httpGetExec(suffix)
{
    logDebug("Space-X: httpGetExec(${suffix})")
    
    try
    {
        getString = "https://api.spacexdata.com/v4/" + suffix
        httpGet(getString.replaceAll(' ', '%20'))
        { resp ->
            if (resp.data)
            {
                logDebug("resp.data = ${resp.data}")
                return resp.data
            }
        }
    }
    catch (Exception e)
    {
        log.warn "Space-X httpGetExec() failed: ${e.message}"
    }
}
    