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

import android.util.SparseIntArray;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

public class LoadSheddingSchedule {
    // this all makes a lot more sense when looking at the actual tables:
    // http://resource.capetown.gov.za/documentcentre/Documents/Procedures%2c%20guidelines%20and%20regulations/Load_Shedding_All_Areas_Schedule_and_Map.pdf

    // indecies are 1-based (not 0-based) because we are dealing with day dates, etc so it's just easier imo :)
    // there could be more error handling and validation but you can add that as needed.

    // So basically these tables are generated with simple increments throughout mNumTimeSlots each day...

    private static final int mNumDayGroups = 16; // columns
    private static final int mNumTimeSlots = 12; // rows
    private static final int mNumAreaCodes = 16; // what we accumulate to before restarting from 1

    private static final int mHighestStage = 8;
    private static final int mMaxMonthDay = 31;

    // but some days, they randomly skip an area for some reason (1-based)
    private static final int mDayAreaExtraIncrements[] = {5, 9};
    // and the 13th day is only skipped in stages 4 and lower. Ugh this is becoming messy...
    private static final int mDayAreaExtraIncrementsStage4Lower[] = {13};

    // I have questions for who created these loadshedding tables. Anyway...

    // Each stage is just a table with a new area starting the chain.
    // To get each stage's full table, you add the previous stages' table data
    private static final SparseIntArray mStageStartingAreas = new SparseIntArray();
    static {
        mStageStartingAreas.append(1, 1);
        mStageStartingAreas.append(2, 9);
        mStageStartingAreas.append(3, 13);
        mStageStartingAreas.append(4, 5);
        mStageStartingAreas.append(5, 2);
        mStageStartingAreas.append(6, 10);
        mStageStartingAreas.append(7, 14);
        mStageStartingAreas.append(8, 6);
    }

    // this could be better maybe
    private static final int mTimeSlotHours = 2;
    private static final int mTimeSlotMinutes = 30;

    // then according to the tables (link above),
    // there is this one block where in stage 4, in timeslot 4, area 4 is skipped for some reason
    // it's suspicious but this whole thing has ended up a bit of a hack anyway as they do lots of illogical things

    // I want to keep things simple, so this is a Time object for 1 day so we can just deal with relative values
    final static class DayTime {
        DayTime() {
            mHour = 0;
            mMinute = 0;
        }

        DayTime(int hour, int minute) {
            mHour = hour;
            mMinute = minute;

            if(mHour >= 24 || mHour < 0) {
                mHour = 0;
            }

            if(mMinute >= 60 || mMinute < 0) {
                mMinute = 0;
            }
        }

        int getHour() {
            return mHour;
        }

        int getMinute() {
            return mMinute;
        }

        private int mHour, mMinute;
    }

    final static class NextTimeSlot {
        NextTimeSlot(int slot, int day, Date date) {
            mSlot = slot;
            mDay = day;
            mDate = date;
        }

        public int getSlot() {
            return mSlot;
        }

        public int getDay() {
            return mDay;
        }

        public Date getDate() {
            return mDate;
        }

        private int mSlot, mDay;
        private Date mDate;
    }

    class CurrentLoadshedding {
        public CurrentLoadshedding(boolean status, Date endDate) {
            mStatus = status;
            mEndDate = endDate;
        }

        public boolean getStatus() {
            return mStatus;
        }

        public Date getEndDate() {
            return mEndDate;
        }

        private boolean mStatus;
        private Date mEndDate;
    }

    /**
     * Get what areas are loadshedding on a particular day and timeslot
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param timeSlot id of timeslot in the table (1-12)
     * @return
     */
    public ArrayList<Integer> getAreaCodes(int stage, int day, int timeSlot) {
        day = _clipDayToGroup(day);
        int areaCodeAcc = _getAreaCodeAccumulationDayStart(stage, day) + timeSlot;
        int areaCode = _normalizeAreaCode(stage, areaCodeAcc);
        ArrayList<Integer> areaCodes = new ArrayList<Integer>();
        areaCodes.add(areaCode);

        // hack: this one is skipped according to the tables for some reason
        if(stage == 4 && timeSlot == 4 && day == 15) {
            areaCodes.clear();
        }

        if(stage > 1) {
            areaCodes.addAll(getAreaCodes(stage - 1, day, timeSlot));
        }

        return areaCodes;
    }

    /**
     * Get what areas are loadshedding on a particular day and time
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param time time of the day
     * @return
     */
    public ArrayList<Integer> getAreaCodes(int stage, int day, DayTime time) {
        return getAreaCodes(stage, day, time, false, 31);
    }

    /**
     * Get what areas are loadshedding on a particular day and time
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param time time of the day
     * @param includeoverlap include all the areas when to loadshedding slots are overlapping by getExtraTimeslotMinutes()
     * @return
     */
    public ArrayList<Integer> getAreaCodes(int stage, int day, DayTime time, boolean includeoverlap) {
        return getAreaCodes(stage, day, time, includeoverlap, 31);
    }

    /**
     * Get what areas are loadshedding on a particular day and time
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param time time of the day
     * @param includeoverlap include all the areas when to loadshedding slots are overlapping by getExtraTimeslotMinutes()
     * @param previousMonthLastDay this is needed when using overlap in case you are one day 1 at and it searches back to the previous month
     * @return
     */
    public ArrayList<Integer> getAreaCodes(int stage, int day, DayTime time, boolean includeoverlap, int previousMonthLastDay) {
        boolean isOddHour = time.getHour() % mTimeSlotHours != 0;
        int timeSlot = _getTimeslotFromHour(time.getHour());

        ArrayList<Integer> areaCodes = getAreaCodes(stage, day, timeSlot);

        if(includeoverlap && !isOddHour && time.getMinute() <= mTimeSlotMinutes) {
            if(timeSlot > 1) {
                timeSlot--;
            } else {
                timeSlot = mNumTimeSlots;

                if(day > 1) {
                    day--;
                } else {
                    day = previousMonthLastDay;
                }
            }

            areaCodes.addAll(getAreaCodes(stage, day, timeSlot));
        }

        return areaCodes;
    }

    /**
     * Get the ids of timeslots that a particular area is being loadshed for a day
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param areaCode the code of the area to check
     * @return
     */
    public ArrayList<Integer> getTimeSlots(int stage, int day, int areaCode) {
        ArrayList<Integer> timeSlots = new ArrayList<Integer>();
        for (int i = 0; i < mNumTimeSlots; i++) {
            ArrayList<Integer> areas = getAreaCodes(stage, day, i + 1);
            if(areas.contains(areaCode)) {
                timeSlots.add(i + 1);
                continue;
            }
        }

        return timeSlots;
    }

    /**
     * Get the next timeslot that will be loadshed in a day
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param areaCode the code of the area to check
     * @return
     */
    public int getNextTimeSlotInDay(int stage, int day, int areaCode) {
        return getNextTimeSlotInDay(stage, day, areaCode, -1);
    }

    /**
     * Get the next timeslot that will be loadshed in a day, starting from a specified hour
     *
     * @param stage Eskom stage
     * @param day day of the month
     * @param areaCode the code of the area to check
     * @param fromHour bit hacky... what hour to start the search from. Value of -1 starts at beginning of the day (instead of hour 2)
     * @return
     */
    public int getNextTimeSlotInDay(int stage, int day, int areaCode, int fromHour) {
        ArrayList<Integer> slots = getTimeSlots(stage, day, areaCode);

        for (int i = 0; i < slots.size(); i++) {
            Integer slot = slots.get(i);
            int slotHour = getTimeSlotHour(slot);

            if(fromHour == -1 || slotHour > fromHour) {
                return slot;
            }
        }

        return 0;
    }

    /**
     * Get the next timeslot and date that will be loadshed. Will search multiple days
     *
     * @param stage Eskom stage
     * @param areaCode the code of the area to check
     * @return
     * @throws Exception
     */
    public NextTimeSlot getNextTimeSlot(int stage, int areaCode) throws Exception {
        // since we're about to go into a loop, searching for known data,
        // better make sure the search data is valid. We can then be sure
        // we will find a result
        if(stage < 1 || stage > mHighestStage) {
            // just throw for now. Feel free to change your error handling
            throw new Exception("getNextTimeSlot() stage out of bounds");
        }

        if(areaCode < 1 || areaCode > mNumAreaCodes) {
            // just throw for now. Feel free to change your error handling
            throw new Exception("getNextTimeSlot() areaCode out of bounds");
        }

        Calendar calendar = Calendar.getInstance();
        int fromHour = calendar.get(Calendar.HOUR_OF_DAY);
        int fromDay = calendar.get(Calendar.DAY_OF_MONTH);

        int slot = 0;
        int day = fromDay;
        int dayAccum = 0;
        while(slot == 0) {
            slot = getNextTimeSlotInDay(stage, day, areaCode, day == fromDay ? fromHour : -1);

            if(slot == 0) {
                if(day >= mMaxMonthDay) {
                    day = 1;
                } else {
                    day++;
                }

                dayAccum++;
            }
        }

        calendar.set(Calendar.HOUR_OF_DAY, getTimeSlotHour(slot));
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, dayAccum);

        return new NextTimeSlot(slot, day, calendar.getTime());
    }

    /**
     * Return if we are currently loadshedding for a particular area and when the endtime is
     *
     * @param stage Eskom stage
     * @param areaCode the code of the area to check
     * @return
     */
    public CurrentLoadshedding isLoadSheddingNow(int stage, int areaCode) {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        ArrayList<Integer> areaCodes = getAreaCodes(stage, calendar.get(Calendar.DAY_OF_MONTH), new DayTime(hour, calendar.get(Calendar.MINUTE)));

        boolean status = false;
        Date endDate = null;

        if(areaCodes.indexOf(areaCode) > -1) {
            status = true;
        }

        if(status) {
            // convert to timeslot and back to hour to get correct hour
            int slot = _getTimeslotFromHour(hour);

            calendar.set(Calendar.HOUR_OF_DAY, getTimeSlotHour(slot) + mTimeSlotHours);
            calendar.set(Calendar.MINUTE, mTimeSlotMinutes);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            endDate = calendar.getTime();
        }

        return new CurrentLoadshedding(status, endDate);
    }

    /**
     * Get the starting hour of a timeslot
     *
     * @param slot timeslot id
     * @return
     */
    public int getTimeSlotHour(int slot) {
        return (slot - 1) * mTimeSlotHours;
    }

    /**
     * Get the extra minutes of an loadshedding timeslot period
     *
     * @return
     */
    public int getExtraTimeslotMinutes() {
        return mTimeSlotMinutes;
    }

    // private stuff

    private int _getTimeslotFromHour(int hour) {
        boolean isOddHour = hour % mTimeSlotHours != 0;

        int timeSlot = hour;
        if(isOddHour) {
            timeSlot--;
        }

        return timeSlot / mTimeSlotHours + 1;
    }

    private int _clipDayToGroup(int day) {
        if(day > mNumDayGroups) {
            day -= mNumDayGroups;
        }

        return day;
    }

    private int _getAreaCodeAccumulationDayStart(int stage, int day) {
        if(day <= 1) {
            return 0;
        }

        int dayBefore = day - 1;
        int areaCodeAcc = dayBefore * mNumTimeSlots;

        // add the extra offsets, including the current day
        for (int i = 0; i < mDayAreaExtraIncrements.length; i++) {
            if(day >= mDayAreaExtraIncrements[i]) {
                areaCodeAcc++;
            }
        }

        if(stage <= 4) {
            for (int i = 0; i < mDayAreaExtraIncrementsStage4Lower.length; i++) {
                if(day >= mDayAreaExtraIncrementsStage4Lower[i]) {
                    areaCodeAcc++;
                }
            }
        }

        return areaCodeAcc;
    }

    private int _normalizeAreaCode(int stage, int areaCodeAcc) {
        int areaCode = areaCodeAcc % mNumAreaCodes + (mStageStartingAreas.get(stage) - 1);
        if(areaCode > mNumAreaCodes) areaCode -= mNumAreaCodes;
        return areaCode;
    }
}
