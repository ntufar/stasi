import Foundation

// MARK: - Greek-friendly search (port of Android GreekText.kt)

private let latinToGreekRough: [Character: Character] = [
    "a": "α", "b": "β", "c": "κ", "d": "δ", "e": "ε", "f": "φ",
    "g": "γ", "h": "η", "i": "ι", "j": "γ", "k": "κ", "l": "λ",
    "m": "μ", "n": "ν", "o": "ο", "p": "π", "q": "κ", "r": "ρ",
    "s": "σ", "t": "τ", "u": "υ", "v": "β", "w": "ω", "x": "ξ",
    "y": "υ", "z": "ζ",
]

/// Latin-letters-only queries (Greeklish, e.g. "syntagma") map to rough Greek.
func expandLatinQueryForGreekSearch(_ input: String) -> String {
    let t = input.trimmingCharacters(in: .whitespaces)
    guard t.count >= 2 else { return input }
    guard t.allSatisfy({ $0.isLetter && $0.isASCII }) else { return input }
    return t.map { ch -> String in
        let lower = Character(ch.lowercased())
        return String(latinToGreekRough[lower] ?? ch)
    }.joined()
}

/// Strip accents + lowercase (NFD decomposition).
func normalizeGreek(_ input: String) -> String {
    let nfd = input.trimmingCharacters(in: .whitespaces)
        .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "el"))
    return nfd.lowercased()
}

func lineSearchNorm(lineId: String, lineCode: String, descr: String) -> String {
    normalizeGreek([lineId, lineCode, descr].filter { !$0.isEmpty }.joined(separator: " "))
}

func stopSearchNorm(stopCode: String, descr: String) -> String {
    normalizeGreek([stopCode, descr].filter { !$0.isEmpty }.joined(separator: " "))
}

func matchesGreekQuery(haystackNorm: String, query: String) -> Bool {
    let expanded = normalizeGreek(expandLatinQueryForGreekSearch(query))
    guard !expanded.isEmpty else { return true }
    return expanded.split(separator: " ").allSatisfy { haystackNorm.contains($0) }
}

func parseArrivalMinutes(_ raw: String?) -> Int {
    let t = (raw ?? "").trimmingCharacters(in: .whitespaces)
    guard !t.isEmpty else { return 999 }
    let digits = t.filter { $0.isNumber }
    guard !digits.isEmpty, let v = Int(digits) else { return 999 }
    return v
}
