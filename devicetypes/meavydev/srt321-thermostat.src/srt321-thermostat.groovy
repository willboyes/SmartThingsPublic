/*
 * SRT321 Thermostat by MeavyDev
 * With support for an associated switch set by the SRT321 helper app
 */
metadata 
{
	definition (name: "SRT321 Thermostat", namespace: "meavydev", author: "MeavyDev") 
    {
    	capability "Actuator"
		capability "Temperature Measurement"
		capability "Thermostat"
		capability "Configuration"
		capability "Polling"
		capability "Sensor"

		command "switchMode"
        command "quickSetHeat"
		command "setTemperature"
        
		command "setupDevice" 
        		
		// fingerprint deviceId: "0x0800", inClusters: "0x25, 0x31, 0x40, 0x43, 0x70, 0x72, 0x80, 0x84, 0x85, 0x86, 0xef"
		fingerprint deviceId: "0x0800" 
        fingerprint inClusters: "0x72,0x86,0x80,0x84,0x31,0x43,0x85,0x70,0x40,0x25"
	}

	// simulator metadata
	simulator 
    {
	}

	tiles (scale: 2)
    {
        multiAttributeTile(name:"heatingSetpoint", type: "thermostat", width: 6, height: 4, canChangeIcon: true)
        {
            tileAttribute ("device.heatingSetpoint", key: "PRIMARY_CONTROL") 
            {
                attributeState("default", unit:"dC", label:'${currentValue}°')
            }
            
            tileAttribute("device.heatingSetpoint", key: "VALUE_CONTROL") 
            {
                attributeState("default", action: "setTemperature")
            }
            
            tileAttribute("device.temperature", key: "SECONDARY_CONTROL") 
            {
            	attributeState("default", label:'${currentValue}', unit:"dC")
            }
            
            tileAttribute("device.thermostatMode", key: "THERMOSTAT_MODE") 
            {
                attributeState("off", label:'${name}')
                attributeState("heat", label:'${name}')
            }
            
  			tileAttribute("device.heatingSetpoint", key: "HEATING_SETPOINT") 
            {
    			attributeState("default", label:'${currentValue}', unit:"dC")
			}
            
            tileAttribute("device.thermostatMode", key: "OPERATING_STATE") 
            {
                attributeState("off", backgroundColor:"#44b621")
                attributeState("heat", backgroundColor:"#ffa81e")
            }
        }  

        valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) 
        {
            tileAttribute ("device.battery", key: "PRIMARY_CONTROL")
            {
                state "battery", label:'${currentValue}% battery', unit:""
            }
        }
        
        standardTile("refresh", "device.thermostatMode", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
        {
			state "default", action:"polling.poll", icon:"st.secondary.refresh"
		}
        
		standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 2) 
        {
			state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
		}
	}

	main "heatingSetpoint"
    details(["heatingSetpoint", "battery", "refresh", "configure", "temperature", "mode"])

    preferences 
    {
        input "userWakeUpInterval", "number", title: "Wakeup interval...", description: "Wake Up Interval (seconds)", defaultValue: 3600, required: false, displayDuringSetup: false

        // This is the "Device Network Id" displayed in the IDE
        input "userAssociatedDevice", "string", title:"Associated z-wave switch network Id...", description:"Associated switch ZWave network Id (hex)", required: false, displayDuringSetup: false
    }
}

def parse(String description)
{
//	log.debug "Parse $description"

	def result = zwaveEvent(zwave.parse(description, [0x72:1, 0x86:1, 0x80:1, 0x84:2, 0x31:1, 0x43:1, 0x85:1, 0x70:1, 0xEF:1, 0x40:1, 0x25:1]))
	if (!result) 
    {
    	log.warn "Parse returned null"
		return null
	}
    
//	log.debug "Parse returned $result"
	result
}

def updated() 
{
	log.debug "preferences updated"

	state.configNeeded = true
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeSet cmd)
{
	def map = [:]
	switch (cmd.mode) {
		case physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeSet.MODE_OFF:
			map.value = "off"
			break
		case physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeSet.MODE_HEAT:
			map.value = "heat"
	}
	map.name = "thermostatMode"
	createEvent(map)
}

// Event Generation
def zwaveEvent(physicalgraph.zwave.commands.thermostatsetpointv1.ThermostatSetpointReport cmd)
{
	def map = [:]
	map.value = cmd.scaledValue.toString()
	map.unit = cmd.scale == 1 ? "F" : "C"
	map.displayed = false
	switch (cmd.setpointType) {
		case 1:
			map.name = "heatingSetpoint"
			break;
		default:
			return [:]
	}
	// So we can respond with same format
	state.size = cmd.size
	state.scale = cmd.scale
	state.precision = cmd.precision
	createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv1.SensorMultilevelReport cmd)
{
	def map = [:]
	map.value = cmd.scaledSensorValue.toString()
	map.unit = cmd.scale == 1 ? "F" : "C"
	map.name = "temperature"
	createEvent(map)
}

// Battery powered devices can be configured to periodically wake up and
// check in. They send this command and stay awake long enough to receive
// commands, or until they get a WakeUpNoMoreInformation command that
// instructs them that there are no more commands to receive and they can
// stop listening.
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
		def map = [name:"thermostatWakeUp", value: "${device.displayName} woke up", isStateChange: true]       
        def event = createEvent(map)
		def cmds = updateIfNeeded()
        
		cmds << zwave.wakeUpV2.wakeUpNoMoreInformation().format()
        
        log.debug "Wakeup $cmds"

        [event, response(delayBetween(cmds, 1000))]      
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) 
{
    def map = [ name: "battery", unit: "%" ]
    if (cmd.batteryLevel == 0xFF) 
    {  // Special value for low battery alert
            map.value = 1
            map.descriptionText = "${device.displayName} has a low battery"
            map.isStateChange = true
    } 
    else 
    {
            map.value = cmd.batteryLevel
            log.debug ("Battery: $cmd.batteryLevel")
    }
    // Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
    state.lastbatt = new Date().time
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeReport cmd) 
{
	def map = [:]
	switch (cmd.mode) {
		case physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeReport.MODE_OFF:
			map.value = "off"
			break
		case physicalgraph.zwave.commands.thermostatmodev1.ThermostatModeReport.MODE_HEAT:
			map.value = "heat"
			break
	}
	map.name = "thermostatMode"
	createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalCapabilitiesReport cmd) 
{
    def map = [ name: "defaultWakeUpInterval", unit: "seconds" ]
	map.value = cmd.defaultWakeUpIntervalSeconds
	map.displayed = false
	state.defaultWakeUpInterval = cmd.defaultWakeUpIntervalSeconds
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalReport cmd)
{
	def map = [ name: "reportedWakeUpInterval", unit: "seconds" ]
	map.value = cmd.seconds
	map.displayed = false
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) 
{
	log.debug "Zwave event received: $cmd"
}

def zwaveEvent(physicalgraph.zwave.Command cmd) 
{
	log.warn "Unexpected zwave command $cmd"
    
    delayBetween([
		zwave.sensorMultilevelV1.sensorMultilevelGet().format(), // current temperature
		zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 1).format(),
		zwave.thermostatModeV1.thermostatModeGet().format(),
		zwave.thermostatFanModeV3.thermostatFanModeGet().format(),
		zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format()
	], 1000)

}

// Command Implementations

def configure() 
{
	log.debug "configure"
	state.configNeeded = true
    
    // Normally this won't do anything as the thermostat is asleep, 
    // but do this in case it helps with the initial config
	delayBetween([
		zwave.thermostatModeV1.thermostatModeSupportedGet().format(),
		zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format(),
        // Set hub to get battery reports / warnings
        zwave.associationV1.associationSet(groupingIdentifier:3, nodeId:[zwaveHubNodeId]).format(),
        // Set hub to get set point reports
        zwave.associationV1.associationSet(groupingIdentifier:4, nodeId:[zwaveHubNodeId]).format(),
        // Set hub to get multi-level sensor reports (defaults to temperature changes of > 1C)
        zwave.associationV1.associationSet(groupingIdentifier:5, nodeId:[zwaveHubNodeId]).format(),
        // set the temperature sensor On
		zwave.configurationV1.configurationSet(configurationValue: [0xff], parameterNumber: 1, size: 1).format()
	], 1000)
}

def poll() 
{
	log.debug "poll"

	// Normally this won't do anything as the thermostat is asleep, 
    // but do this in case it helps with the initial config
	delayBetween([
		zwave.sensorMultilevelV1.sensorMultilevelGet().format(), // current temperature
		zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: physicalgraph.zwave.commands.thermostatsetpointv1.ThermostatSetpointSet.SETPOINT_TYPE_HEATING_1).format(),
		zwave.thermostatModeV1.thermostatModeGet().format(),
		zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format()
	], 1000)
}

def refresh()
{
	log.debug "refresh"

	state.refreshNeeded = true
    
    // Normally this won't do anything as the thermostat is asleep, 
    // but do this in case it helps with the initial config
	delayBetween([
		zwave.sensorMultilevelV1.sensorMultilevelGet().format(), // current temperature
		zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: physicalgraph.zwave.commands.thermostatsetpointv1.ThermostatSetpointSet.SETPOINT_TYPE_HEATING_1).format(),
		zwave.thermostatModeV1.thermostatModeGet().format(),
		zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format()
	], 1000)
}

def quickSetHeat(degrees) 
{
	setHeatingSetpoint(degrees, 1000)
	log.debug("Degrees at quicksetheat: $degrees")
}

def setTempUp() 
{ 
    def newtemp = device.currentValue("heatingSetpoint").toInteger() + 1
    log.debug "Setting temp up: $newtemp"
    sendEvent(name: 'heatingSetpoint', value: newtemp)
    quickSetHeat(newtemp)
}

def setTempDown() 
{ 
    def newtemp = device.currentValue("heatingSetpoint").toInteger() - 1
    log.debug "Setting temp down: $newtemp"
    sendEvent(name: 'heatingSetpoint', value: newtemp)
    quickSetHeat(newtemp)
}

def setTemperature(temp)
{
	log.debug "setTemperature $temp"
    sendEvent(name: 'heatingSetpoint', value: temp)

    quickSetHeat(temp)
}

def setHeatingSetpoint(degrees, delay = 30000) 
{
	setHeatingSetpoint(degrees.toDouble(), delay)
	log.debug("Degrees at setheatpoint: $degrees")
}

def setHeatingSetpoint(Double degrees, Integer delay = 30000) 
{
	log.trace "setHeatingSetpoint($degrees, $delay)"
	def deviceScale = state.scale ?: 1
	def deviceScaleString = deviceScale == 2 ? "C" : "F"
    def locationScale = getTemperatureScale()
    def p = (state.precision == null) ? 1 : state.precision

    def convertedDegrees
    if (locationScale == "C" && deviceScaleString == "F") 
    {
        convertedDegrees = celsiusToFahrenheit(degrees)
    } 
    else if (locationScale == "F" && deviceScaleString == "C") 
    {
        convertedDegrees = fahrenheitToCelsius(degrees)
    } 
    else 
    {
        convertedDegrees = degrees
    }

	log.trace "setHeatingSetpoint scale: $deviceScale precision: $p setpoint: $convertedDegrees"
	state.deviceScale = deviceScale
    state.p = p
    state.convertedDegrees = convertedDegrees
    state.updateNeeded = true
    
    thermostatMode
}

private getStandardDelay() 
{
	1000
}

def updateIfNeeded()
{
	def cmds = []
    
    // Only ask for battery if we haven't had a BatteryReport in a while
    if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) 
    {
    	log.debug "Getting battery state"
    	cmds << zwave.batteryV1.batteryGet().format()
    }
        
	if (state.refreshNeeded)
    {
        log.debug "Refresh"
        cmds << zwave.sensorMultilevelV1.sensorMultilevelGet().format() // current temperature
		cmds << zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: physicalgraph.zwave.commands.thermostatsetpointv1.ThermostatSetpointSet.SETPOINT_TYPE_HEATING_1).format()

		cmds << zwave.thermostatModeV1.thermostatModeGet().format()
		cmds << zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format()
        cmds << zwave.thermostatModeV1.thermostatModeSupportedGet().format()
       	state.refreshNeeded = false
    }
    
    if (state.updateNeeded)
    {
        log.debug "Update"

		cmds << zwave.thermostatSetpointV1.thermostatSetpointSet(setpointType: physicalgraph.zwave.commands.thermostatsetpointv1.ThermostatSetpointSet.SETPOINT_TYPE_HEATING_1, scale: state.deviceScale, precision: state.p, scaledValue: state.convertedDegrees).format()
        state.updateNeeded = false
    }
    
    if (state.configNeeded)
    {
        log.debug "Config"
    	state.configNeeded = false
        
        // Nodes controlled by Thermostat Mode Set - not sure this is needed?
        cmds << zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format()
        
         // Set hub to get battery reports / warnings
        cmds << zwave.associationV1.associationSet(groupingIdentifier:3, nodeId:[zwaveHubNodeId]).format()
        
         // Set hub to get set point reports
        cmds << zwave.associationV1.associationSet(groupingIdentifier:4, nodeId:[zwaveHubNodeId]).format()
        
         // Set hub to get multi-level sensor reports (defaults to temperature changes of > 1C)
        cmds << zwave.associationV1.associationSet(groupingIdentifier:5, nodeId:[zwaveHubNodeId]).format()
        
        // set the temperature sensor On
		cmds << zwave.configurationV1.configurationSet(configurationValue: [0xff], parameterNumber: 1, size: 1).format()

		log.debug "association $state.association user: $userAssociatedDevice"
		int nodeID = getAssociatedId(state.association)
        // If user has changed the switch association, send the new assocation to the device 
    	if (nodeID != -1)
        {
            log.debug "Setting associated device $nodeID"
            cmds << zwave.associationV1.associationSet(groupingIdentifier: 2, nodeId: nodeID).format()
        }
        
        def userWake = getUserWakeUp(userWakeUpInterval)
        // If user has changed userWakeUpInterval, send the new interval to the device 
    	if (state.wakeUpInterval != userWake)
        {
       		state.wakeUpInterval = userWake
            log.debug "Setting New WakeUp Interval to: " + state.wakeUpInterval
        	cmds << zwave.wakeUpV2.wakeUpIntervalSet(seconds:state.wakeUpInterval, nodeid:zwaveHubNodeId).format()
       		cmds << zwave.wakeUpV2.wakeUpIntervalGet().format()
    	}  
		cmds << zwave.thermostatModeV1.thermostatModeSupportedGet().format()
    }
    
    if (cmds.size() > 0)
    {
    	cmds << "delay 2000"
    }
    cmds
}


private getUserWakeUp(userWake) 
{
    if (!userWake)  
    { 
    	userWake = '3600' // set default 1 hr if no user preference 
    } 
    // make sure user setting is within valid range for device 
    if (userWake.toInteger() < 60)
    { 
    	userWake = '600'   // 10 minutes - Mininum
    }
    if (userWake.toInteger() > 36000)
    {
    	userWake = '36000' // 10 hours - Maximum
    }  
    return userWake.toInteger()
}

// Get the Z-Wave Id of the binary switch the user wants the thermostat to control
// -1 if no association set
int getAssociatedId(association)
{
	int associatedState = -1
	int associatedUser = -1
    log.debug "getAssociatedId $association"
	if (association != null && association != "")
    {
    	associatedState = association.toInteger()
        log.debug "State $association $associatedState"
    }
    if (userAssociatedDevice != null && userAssociatedDevice != "")
    {
    	try
        {
       		associatedUser = Integer.parseInt(userAssociatedDevice, 16)
        }
        catch (Exception e)
        {
        }
        log.debug "userDev $userAssociatedDevice $associatedUser"
    }
    
    // Use the app associated switch id if it exists, otherwise the device preference  
    return associatedState != -1 ? associatedState : associatedUser
}

// Called from the SRT321 App with the Z-Wave switch network ID
// How long before SmartThings realises that having device preferences 
// with input "*" "capability.switch" is reasonable????
void setupDevice(value)
{
	state.association = "$value"
    int val = Integer.parseInt(value)
    String hex = Integer.toHexString(val)
    log.debug "Setting associated device Id $value Hex $hex"
    settings.userAssociatedDevice = hex
    state.configNeeded = true
}