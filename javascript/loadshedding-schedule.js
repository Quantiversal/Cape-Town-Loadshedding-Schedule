// MIT License

// Copyright (c) 2019 Daniel Lindsay

// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:

// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.

// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// ==============================================================================



// this all makes a lot ore sense when looking at the actual tables:
// http://resource.capetown.gov.za/documentcentre/Documents/Procedures%2c%20guidelines%20and%20regulations/Load_Shedding_All_Areas_Schedule_and_Map.pdf

// indecies are 1-based (not 0-based) because we are dealing with day dates, etc so it's just easier imo :)
// there could be more error handling and validation but you can add that as needed.

// So basically these tables are generated with simple increments throughout numTimeSlots each day...

const numDayGroups = 16; // columns
const numTimeSlots = 12; // rows
const numAreaCodes = 16; // what we accumulate to before restarting from 1

const highestStage = 8;
const maxMonthDay = 31;

// but some days, they randomly skip an area for some reason (1-based)
const dayAreaExtraIncrements = [5, 9];
// and the 13th day is only skipped in stages 4 and lower. Ugh this is becoming messy...
const dayAreaExtraIncrementsStage4Lower = [13];

// I have questions for who created these loadshedding tables. Anyway...

// Each stage is just a table with a new area starting the chain.
// To get each stage's full table, you add the previous stages' table data
const stageStartingAreas = {
	1: 1,
	2: 9,
	3: 13,
	4: 5,
	5: 2,
	6: 10,
	7: 14,
	8: 6
};

// this could be better maybe
const timeSlotHours = 2;
const timeSlotMinutes = 30;

// then according to the tables (link above),
// there is this one block where in stage 4, in timeslot 4, area 4 is skipped for some reason
// it's suspicious but this whole thing has ended up a bit of a hack anyway as they do lots of illogical things

// I want to keep things simple, so this is a Time object for 1 day so we can just deal with relative values
class DayTime {
	constructor(hour = 0, minute = 0) {
		this.hour = hour;
		this.minute = minute;

		if(this.hour >= 24 || this.hour < 0) {
			this.hour = 0;
		}

		if(this.minute >= 60 || this.minute < 0) {
			this.minute = 0;
		}
	}
}

// quick helper
function addDays(date, days) {
	var result = new Date(date);
	result.setDate(result.getDate() + days);
	return result;
}

// our main class
// can also overload these functions in a proper language
class LoadSheddingSchedule {
	/**
	 * Get what areas are loadshedding on a particular day and timeslot
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} day day of the month
	 * @param {number} timeSlot id of timeslot in the table (1-12)
	 */
	getAreaCodesByTimeSlot(stage, day, timeSlot) {
		day = this._clipDayToGroup(day);
		let areaCodeAcc = this._getAreaCodeAccumulationDayStart(stage, day) + timeSlot;
		let areaCode = this._normalizeAreaCode(stage, areaCodeAcc);
		let areaCodes = [areaCode];

		// hack: this one is skipped according to the tables for some reason
		if(stage == 4 && timeSlot == 4 && day == 15) {
			areaCodes = [];
		}

		if(stage > 1) {
			areaCodes = areaCodes.concat(this.getAreaCodesByTimeSlot(stage - 1, day, timeSlot));
		}

		return areaCodes;
	}

	/**
	 * Get what areas are loadshedding on a particular day and time
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} day day of the month
	 * @param {DayTime} time time of the day
	 * @param {boolean} includeoverlap include all the areas when to loadshedding slots are overlapping by getExtraTimeslotMinutes()
	 * @param {number} previousMonthLastDay this is needed when using overlap in case you are one day 1 at and it searches back to the previous month
	 */
	getAreaCodesByTimeValue(stage, day, time, includeoverlap = false, previousMonthLastDay = 31) {
		let isOddHour = time.hour % timeSlotHours != 0;
		let timeSlot = this._getTimeslotFromHour(time.hour);

		let areaCodes = this.getAreaCodesByTimeSlot(stage, day, timeSlot);

		if(includeoverlap && !isOddHour && time.minute <= timeSlotMinutes) {
			if(timeSlot > 1) {
				timeSlot--;
			} else {
				timeSlot = numTimeSlots;
				
				if(day > 1) {
					day--;
				} else {
					day = previousMonthLastDay;
				}
			}

			areaCodes.push(this.getAreaCodesByTimeSlot(stage, day, timeSlot));
		}

		return areaCodes;
	}

	/**
	 * Get the ids of timeslots that a particular area is being loadshed for a day
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} day day of the month
	 * @param {number} areaCode the code of the area to check
	 */
	getTimeSlotsByAreaCode(stage, day, areaCode) {
		let timeSlots = [];
		for (let i = 0; i < numTimeSlots; i++) {
			let areas = this.getAreaCodesByTimeSlot(stage, day, i + 1);
			if(areas.indexOf(areaCode) > -1) {
				timeSlots.push(i + 1);
				continue;
			}
		}

		return timeSlots;
	}

	/**
	 * Get the next timeslot that will be loadshed in a day, starting from a specified hour
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} day day of the month
	 * @param {number} areaCode the code of the area to check
	 * @param {number} fromHour bit hacky... what hour to start the search from. Value of -1 starts at beginning of the day (instead of hour 2)
	 */
	getNextTimeSlotInDay(stage, day, areaCode, fromHour = -1) {
        let slots = this.getTimeSlotsByAreaCode(stage, day, areaCode);		

        for (let i = 0; i < slots.length; i++) {
            let slot = slots[i];
            let slotHour = this.getTimeSlotHour(slot);

            if(fromHour == -1 || slotHour > fromHour) {
                return slot;
            }
        }

        return 0;
	}
	
	/**
	 * Get the next timeslot and date that will be loadshed. Will search multiple days
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} areaCode the code of the area to check
	 */
	getNextTimeSlot(stage, areaCode) {
		// since we're about to go into a loop, searching for known data,
		// better make sure the search data is valid. We can then be sure
		// we will find a result
		let result = {
			slot: null,
			day: null,
			date: null
		};

		if(stage < 1 || stage > highestStage) {
			console.log('getNextTimeSlot() stage out of bounds');
			return result;
		}

		if(areaCode < 1 || areaCode > numAreaCodes) {
			console.log('getNextTimeSlot() areaCode out of bounds');
			return result;
		}

		let d = new Date();
		let fromHour = d.getHours();
		let fromDay = d.getDate();

		let slot = null;
		let day = fromDay;
		let dayAccum = 0;
		while(!slot) {
			slot = this.getNextTimeSlotInDay(stage, day, areaCode, day == fromDay ? fromHour : -1);

			if(!slot) {
				if(day >= maxMonthDay) {
					day = 1;
				} else {
					day++;
				}

				dayAccum++;
			}
		}

		let newDate = new Date();
		newDate.setHours(this.getTimeSlotHour(slot));
		newDate.setMinutes(0);
		newDate.setSeconds(0);
		newDate.setMilliseconds(0);
		newDate = addDays(newDate, dayAccum);

		result.slot = slot;
		result.day = day;
		result.date = newDate;

		return result;
	}

	/**
	 * Return if we are currently loadshedding for a particular area and when the endtime is
	 * 
	 * @param {number} stage Eskom stage
	 * @param {number} areaCode the code of the area to check
	 */
	isLoadSheddingNow(stage, areaCode) {
		let d = new Date();
		let hour = d.getHours();
		let areaCodes = this.getAreaCodesByTimeValue(stage, d.getDate(), new DayTime(hour, d.getMinutes()));

		let result = {
			status: false,
			endDate: null
		};

		if(areaCodes.indexOf(areaCode) > -1) {
			result.status = true;
		}

		if(result.status) {
			// convert to timeslot and back to hour to get correct hour
			let slot = this._getTimeslotFromHour(hour);
			let endDate = new Date();
			endDate.setHours(this.getTimeSlotHour(slot) + timeSlotHours);
			endDate.setMinutes(timeSlotMinutes);
			endDate.setSeconds(0);
			endDate.setMilliseconds(0);
			result.endDate = endDate;
		}

		return result;
	}

	/**
	 * Get the starting hour of a timeslot
	 * 
	 * @param {number} slot timeslot id
	 */
    getTimeSlotHour(slot) {
        return (slot - 1) * timeSlotHours;
	}
	
	/**
	 * Get the extra minutes of an loadshedding timeslot period
	 */
	getExtraTimeslotMinutes() {
		return timeSlotMinutes;
	}

	// private stuff

	_getTimeslotFromHour(hour) {
		let isOddHour = hour % timeSlotHours != 0;

		let timeSlot = hour;
		if(isOddHour) {
			timeSlot--;
		}

		return timeSlot / timeSlotHours + 1;
	}

	_clipDayToGroup(day) {
		if(day > numDayGroups) {
			day -= numDayGroups;
		}

		return day;
	}

	_getAreaCodeAccumulationDayStart(stage, day) {
		if(day <= 1) {
			return 0;
		}

		let dayBefore = day - 1;
		let areaCodeAcc = dayBefore * numTimeSlots;
		
		// add the extra offsets, including the current day
		for (var i = 0; i < dayAreaExtraIncrements.length; i++) {
			if(day >= dayAreaExtraIncrements[i]) {
				areaCodeAcc++;
			}
		}

		if(stage <= 4) {
			for (var i = 0; i < dayAreaExtraIncrementsStage4Lower.length; i++) {
				if(day >= dayAreaExtraIncrementsStage4Lower[i]) {
					areaCodeAcc++;
				}
			}
		}

		return areaCodeAcc;
	}

	_normalizeAreaCode(stage, areaCodeAcc) {
		let areaCode = areaCodeAcc % numAreaCodes;
		areaCode += stageStartingAreas[stage] - 1;
		if(areaCode > numAreaCodes) {
			areaCode -= numAreaCodes;
		}

		return areaCode;
	}
}
