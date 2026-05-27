(function () {
  "use strict";

  window.VoltDashboardDemoData = function voltDashboardDemoData() {
    return {
      trips: [
        { id: 8421, label: "Home -> Office", date: "Apr 30 - 08:14", miles: 18.4, mins: 28, efficiency: "4.1 mi/kWh", mode: "ev", wh: 241 },
        { id: 8420, label: "Office -> Trader Joe's", date: "Apr 29 - 17:42", miles: 6.1, mins: 14, efficiency: "3.9 mi/kWh", mode: "ev", wh: 256 },
        { id: 8419, label: "Trader Joe's -> Home", date: "Apr 29 - 19:08", miles: 7.3, mins: 14, efficiency: "4.0 mi/kWh", mode: "ev", wh: 250 },
        { id: 8418, label: "Home -> Tahoe", date: "Apr 28 - 07:22", miles: 184.2, mins: 173, efficiency: "41.7 MPG", mode: "mixed", wh: 0 },
        { id: 8417, label: "Tahoe Loop", date: "Apr 27 - 10:00", miles: 22.0, mins: 47, efficiency: "3.6 mi/kWh", mode: "ev", wh: 278 },
        { id: 8416, label: "Tahoe -> Home", date: "Apr 26 - 16:48", miles: 178.5, mins: 160, efficiency: "43.2 MPG", mode: "gas", wh: 0 }
      ],
      sessions: [
        { date: "Apr 30 - 21:18", type: "L2", kwh: 11.8, soc: "24->91", location: "Home", cost: "$1.41" },
        { date: "Apr 29 - 22:04", type: "L2", kwh: 9.6, soc: "36->90", location: "Home", cost: "$1.15" },
        { date: "Apr 28 - 18:12", type: "L1", kwh: 5.2, soc: "58->88", location: "Office", cost: "$0.62" },
        { date: "Apr 27 - 20:48", type: "L2", kwh: 10.4, soc: "32->90", location: "Home", cost: "$1.25" }
      ],
      hourly: [8, 12, 18, 24, 16, 10, 4, 0, 0, 2, 4, 8, 12, 10, 6, 4, 8, 14, 22, 30, 42, 68, 82, 54],
      insights: [
        { kind: "good", icon: "+", title: "Best month yet for EV ratio", body: "April hit 78% electric, up from 64% in March. Projected annual savings rises about $45." },
        { kind: "good", icon: "OK", title: "Battery degrading below average", body: "8.7% capacity loss across 38k miles vs about 12% expected for 2017 Volts." },
        { kind: "warn", icon: "!", title: "Cell 47 trending low", body: "Cell 47 has drifted 18 mV below the pack mean over the past two weeks." },
        { kind: "info", icon: "i", title: "Cheaper to charge after 21:00", body: "Shifting two L2 sessions per week saves roughly $8 per month." },
        { kind: "good", icon: "EV", title: "Tahoe trip MPG within 4% of route avg", body: "Apr 28's 184 mile roundtrip hit 41.7 MPG for that elevation profile." }
      ]
    };
  };
})();
