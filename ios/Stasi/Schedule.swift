import Foundation

// MARK: - getDailySchedule wall-clock helpers (port of DailyScheduleWallClock.kt
// and the schedule helpers in OasaRepository.kt). Times are minutes-of-day in
// Europe/Athens.

let athensTimeZone = TimeZone(identifier: "Europe/Athens")!

var athensCalendar: Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = athensTimeZone
    return c
}

/// Minutes before the last origin window end for last-bus chips/banners.
let lastBusWarningThresholdMinutes = 60
private let lastBusWarningGracePastEndMinutes = 120

/// "HH:mm" → minutes of day.
func parseScheduleWallClock(_ token: String) -> Int? {
    let parts = token.split(separator: ":").map { String($0).trimmed }
    guard parts.count >= 2, let h = Int(parts[0]), let m = Int(parts[1]),
          (0...23).contains(h), (0...59).contains(m) else { return nil }
    return h * 60 + m
}

private func rangeTokens(_ range: String) -> [String] {
    let separators: Set<Character> = ["-", "–"]
    let pieces: [Substring] = range.split(whereSeparator: { separators.contains($0) })
    return pieces.map { String($0).trimmed }
}

func scheduleRangeStart(_ range: String) -> Int? {
    rangeTokens(range).first.flatMap(parseScheduleWallClock)
}

func scheduleRangeEnd(_ range: String) -> Int? {
    rangeTokens(range).last.flatMap(parseScheduleWallClock)
}

func lastServiceEnd(_ timetable: RouteDailyTimetable) -> Int? {
    timetable.originDepartures.flatMap { row in
        [scheduleRangeEnd(row.primaryRange), row.secondaryRange.flatMap(scheduleRangeEnd)].compactMap { $0 }
    }.max()
}

/// Date for `minuteOfDay` on the Athens calendar day of `day`.
private func athensDate(_ day: Date, minuteOfDay: Int, addDays: Int = 0) -> Date {
    let cal = athensCalendar
    let start = cal.startOfDay(for: day)
    let shifted = cal.date(byAdding: .day, value: addDays, to: start) ?? start
    return cal.date(bySettingHour: minuteOfDay / 60, minute: minuteOfDay % 60, second: 0, of: shifted) ?? shifted
}

func isLastBusApproaching(_ timetable: RouteDailyTimetable, now: Date = Date()) -> Bool {
    guard let lastEnd = lastServiceEnd(timetable) else { return false }
    let target = athensDate(now, minuteOfDay: lastEnd)
    let minutesUntilEnd = Int(target.timeIntervalSince(now) / 60)
    return (-lastBusWarningGracePastEndMinutes...lastBusWarningThresholdMinutes).contains(minutesUntilEnd)
}

/// OASA uses a dummy date plus wall-clock (`1900-01-01 04:15:00`) → "04:15".
func oasaScheduleTimeToHm(_ raw: String) -> String? {
    let t = raw.trimmed
    guard !t.isEmpty else { return nil }
    let tail = t.split(separator: " ").last.map(String.init) ?? t
    let noFrac = tail.split(separator: ".").first.map(String.init) ?? tail
    let parts = noFrac.split(separator: ":")
    guard parts.count >= 2, let h = Int(parts[0]), let m = Int(parts[1]) else { return nil }
    return String(format: "%02d:%02d", h, m)
}

private func formatOasaScheduleRange(_ start: String, _ end: String) -> String? {
    guard let s = oasaScheduleTimeToHm(start), let e = oasaScheduleTimeToHm(end) else { return nil }
    return "\(s)–\(e)"
}

/// Maps one `come`/`go` bucket to rows, sorted by `sdd_sort`.
func mapDailyScheduleSlots(_ slots: [JSONRow]) -> [RouteDailyTimetableRow] {
    slots
        .sorted { ($0.int("sdd_sort") ?? 0) < ($1.int("sdd_sort") ?? 0) }
        .compactMap { slot in
            let start1 = slot.s("sde_start1").nilIfBlank ?? slot.s("sdd_start1")
            let p1 = formatOasaScheduleRange(start1, slot.s("sde_end1"))
            let p2 = formatOasaScheduleRange(slot.s("sde_start2"), slot.s("sde_end2"))
            switch (p1, p2) {
            case let (a?, b?): return RouteDailyTimetableRow(primaryRange: a, secondaryRange: b)
            case let (a?, nil): return RouteDailyTimetableRow(primaryRange: a, secondaryRange: nil)
            case let (nil, b?): return RouteDailyTimetableRow(primaryRange: b, secondaryRange: nil)
            default: return nil
            }
        }
}

/// Next start of an origin (`come`) window and whether it falls on the next calendar day.
func nextOriginScheduleStart(_ timetable: RouteDailyTimetable, now: Date = Date()) -> (minuteOfDay: Int, nextDay: Bool)? {
    let starts = Set(timetable.originDepartures.flatMap { row in
        [scheduleRangeStart(row.primaryRange), row.secondaryRange.flatMap(scheduleRangeStart)].compactMap { $0 }
    }).sorted()
    guard let first = starts.first else { return nil }
    let comps = athensCalendar.dateComponents([.hour, .minute, .second], from: now)
    let nowSeconds = (comps.hour ?? 0) * 3600 + (comps.minute ?? 0) * 60 + (comps.second ?? 0)
    if let after = starts.first(where: { $0 * 60 > nowSeconds }) {
        return (after, false)
    }
    return (first, true)
}

func minutesUntilClock(now: Date = Date(), minuteOfDay: Int, nextDay: Bool) -> Int {
    var target = athensDate(now, minuteOfDay: minuteOfDay, addDays: nextDay ? 1 : 0)
    if !nextDay && target <= now {
        target = athensDate(now, minuteOfDay: minuteOfDay, addDays: 1)
    }
    return min(max(0, Int(target.timeIntervalSince(now) / 60)), arrivalMinutesUnknown - 1)
}

func formatMinuteOfDay(_ m: Int) -> String {
    String(format: "%02d:%02d", m / 60, m % 60)
}

// MARK: - Quiet hours (port of QuietHours.kt)

func isQuietNow(enabled: Bool, startMinutes: Int, endMinutes: Int, now: Date = Date()) -> Bool {
    guard enabled, startMinutes != endMinutes else { return false }
    let cal = Calendar.current
    let mins = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
    if startMinutes < endMinutes {
        return mins >= startMinutes && mins < endMinutes
    }
    return mins >= startMinutes || mins < endMinutes
}
