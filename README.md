# Cape-Town-Loadshedding-Schedule
A class that can be used to fetch loadshedding times for Cape Town, South Africa based on Eskom stages 1-8. It does this all with maths instead of storing it all in tables. So the LoadSheddingSchedule class and the current Eskom loadshedding stage is all you need.
Based on these tables: http://resource.capetown.gov.za/documentcentre/Documents/Procedures%2c%20guidelines%20and%20regulations/Load_Shedding_All_Areas_Schedule_and_Map.pdf

There is a javascript class with a demo on how to use the functionality and a java class for Android.

## The simplest way to use it:
1. scrape the current Eskom stage integer from their website (or elsewhere like EWN's little API https://ewn.co.za/assets/loadshedding/api/status)
2. check if you are currently in loadshedding using isLoadSheddingNow()
3. You can also check when the next loadshedding time is using getNextTimeSlot()

## Why does this exist?
I was creating a "Kiosk" Android app for a tablet in my house to display the loadshedding status in a more custom way than existing apps. I needed the schedule but when I looked at the tables, it seemed like they were generated with simple increments. So I decided I wanted to be overly efficient for some reason and just generated what info I need on the fly instead of storing tables (and painstakingly adding all that data). It may be overboard, but it feels nice just adding a class instead of extra bloat (tables) to an app. And it was an interesting exercise in the end!

Hope it can be useful for others too :)