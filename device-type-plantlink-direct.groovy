/**
 *	PlantLink Direct Sensor
 *     This is a non-API implementation of OsoTech's PlantLink moisture sensor.  It converts the voltage
 *    reading on the device to a resistance reading.  The resistance is then used to calculate a moisture 
 *    estimate percentage given a soil type.  The device also has a status for triggering alerts
 *
 *	Author: Kristopher Kubicki, d8adrvn, OsoTech, SmartThings
 *	Date: 2015-04-15
 */
metadata {

	definition (name: "PlantLink Direct Sensor", namespace: "KristopherKubicki", author: "kristopher@acm.org") {
		capability "Relative Humidity Measurement"
		capability "Battery"
		capability "Sensor"
        capability "Water Sensor"
        
        attribute "soilType","string"
        attribute "battery","number"
        attribute "voltage","number"
        attribute "humidity","number"
        attribute "resistivity","number"
        attribute "status","string"

		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0B04"
	}
    
    preferences {
		input "soilType", "enum", title: "Soil Type", options: ["Sand","Loamy Sand","Sandy Loam","Loam","Silty Loam","Silt","Sandy Clay Loam","Clay Loam","Silty Clay Loam","Silty Clay","Sandy Clay"], required: true, displayDuringSetup: true, defaultValue: "Loam"
    }
    
    simulator {
        status "moisture": "read attr - raw: C072010B040A0001290000, dni: D00F, endpoint: 01, cluster: 0B04, size: 0A, attrId: 0100, encoding: 29, value: 2100"
        status "battery": "read attr - raw: C0720100010A000021340A, dni: D00F, endpoint: 01, cluster: 0001, size: 0A, attrId: 0000, encoding: 21, value: 0a34"
    }

	tiles {
        valueTile("status", "device.status", inactiveLabel: True, decoration: "flat") {
            state "status", label:'${currentValue}'
        }
        
		valueTile("humidity", "device.humidity", width: 2, height: 2) {
			state("humidity", label:'${currentValue}%', unit:"",
				backgroundColors:[
					[value: 2, color: "#FF0000"],
					[value: 10, color: "#CC9900"],
					[value: 20, color: "#996600"],
					[value: 30, color: "#996633"],
					[value: 40, color: "#663300"],
					[value: 50, color: "#000099"]
				]
			)
		}
        valueTile("soilType", "device.soilType", inactiveLabel: false, decoration: "flat") {
            state "soilType", label:'${currentValue}', unit:""
        }
   		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") {
            state "battery", label:'${currentValue}% battery', unit:""
        }
        valueTile("voltage", "device.voltage", inactiveLabel: false, decoration: "flat") {
            state "voltage", label:'${currentValue}V', unit:""
        }
        valueTile("resistivity", "device.resistivity", inactiveLabel: false, decoration: "flat") {
            state "resistivity", label:'${currentValue}µΩ', unit:""
        }
        standardTile("water", "device.water", width: 1, height: 1) {
			state "dry", icon:"st.alarm.water.wet", backgroundColor:"#FF0000"
            state "ok", icon:"st.alarm.water.dry", backgroundColor:"#FFFFFF"
			state "wet", icon:"st.alarm.water.wet", backgroundColor:"#0066FF"
		}

        main "humidity"
        details(["humidity","soilType","battery", "water","status","voltage","resistivity"])
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	log.debug "Parse description $description"
	def map = [:]
	if (description?.startsWith("read attr -")) {
		def descMap = parseDescriptionAsMap(description)
		if(descMap.cluster == "0B04" && descMap.attrId == "0100") {
			map.name = "humidity"
			map.value = calculateHumidity(descMap.value) as Integer
		} else if (descMap.cluster == "0001" && descMap.attrId == "0000") {
			map.name = "battery"
			map.value = calculateBattery(descMap.value) as Integer
		}
	}
    log.debug "Processed description $description"
    
    sendEvent(name: "soilType", value: settings.soilType)

	def result = null
	if (map) {
		result = createEvent(map)
	}
	return result
}

def parseDescriptionAsMap(description) {
	(description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
}


private calculateHumidity(value) {
	
    log.debug "HUMID: $value "
    
    // exponential curve fit to PlantLink data.  Potentially more accurate than Oso's interpretation
    def resistivity = (1500000 * 2.71828 ** (-0.000888 * Integer.parseInt(value, 16))) as Integer
    if(resistivity > 100000) { 
    	sendEvent(name: "resistivity", value: "∞")
    }
    else { 
	    sendEvent(name: "resistivity", value: resistivity)
    }
    def percent = resistivityToPercent(resistivity)
    
    log.debug "PERCENT1: $percent "
    
    percentToWarn(percent * 100)
    log.debug "PERCENT2: $percent "
    percent = (percent * 100) as Integer
    
    log.debug "PERCENT3: $percent "
	percent
}

// Originally it seemed the battery voltage was a big component of the 
//  resistivity calculation.  It seems like its probably less than 5% after 
// looking at it more 
private calculateBattery(value) {
	def min = 2300
	def percent = (Integer.parseInt(value, 16) - min) / 10
   
	percent = Math.max(0.0, Math.min(percent, 100.0))
    if(percent < 5) {
    	sendEvent(name: "status", value: "Change Battery!")
    }
    def voltage = sprintf("%.2f",(4.167 + (percent / 100))/1.67)
    sendEvent(name: "voltage", value: voltage)
    
	percent
}

// Calculated by hand.  This was real hard
private resistivityToPercent(resistivity) { 

log.debug "RESIST: $resistivity "


	def percent = 0.00
    if(resistivity == 0) { 
    	return percent
    }
	else if(soilType == "Loamy Sand" || soilType == "Sand" || soilType == "Sandy Loam") {
		percent = (3.1916 * resistivity ** -0.412)
    }
    else if(soilType == "Clay Loam" || soilType == "Silty Clay Loam" || soilType == "Sandy Clay" || soilType == "Clay") { 
    	percent = (1.6356 * resistivity ** -0.297)
    }
    else {
    	percent = (5.6398 * resistivity ** -0.504)
    }
    
    if(percent < 0) { 
    	percent = 0
    }
    if(percent > 0.5) { 
    	percent = 0.5
    }
    
	return percent
}

// https://www.midwestlabs.com/wp-content/uploads/2012/09/137_estimating_soil_textures_by_cation_exch_cap_det.pdf
private percentToWarn(percent) { 
	if(soilType == "Clay") { 
        if(percent > 22.3) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 10.3) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        }
    }
    else if(soilType == "Silty Clay") {
        if(percent > 39.4) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 21.4) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
		else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        }
    }
    else if(soilType == "Sandy Clay") { 
    	if(percent > 30.2) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 18.4) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        }        
    }
    else if(soilType == "Silty Clay Loam") {
        if(percent > 25.3) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 12.4) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Clay Loam") { 
        if(percent > 12.6) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 5.9) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Sandy Clay Loam") { 
        if(percent > 14.4) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 7.5) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Silty Loam") { 
        if(percent > 24.2) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 11.2) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Loam") { 
        if(percent > 22.2) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 9.2) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Silt") { 
        if(percent > 24.3) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
		else if(percent < 11.2) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Sandy Loam") {
        if(percent > 17.2) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 6.9) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Loamy Sand") {
        if(percent > 9) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 4) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }
    else if(soilType == "Sand") { 
        if(percent > 5.2) { 
        	sendEvent(name: "status", value: "Too Wet")
            sendEvent(name: "water", value: "wet")
        }
        else if(percent < 2.5) { 
        	sendEvent(name: "status", value: "Too Dry")
            sendEvent(name: "water", value: "dry")
        }
        else {
        	sendEvent(name: "status", value: "OK")
        	sendEvent(name: "water", value: "ok")
        } 
    }

    if(percent <= 2.0) { 
    	sendEvent(name: "status", value: "No Soil!")
    } 
    else if(percent > 60) { 
    	sendEvent(name: "status", value: "Waterlogged!")
    } 


}
