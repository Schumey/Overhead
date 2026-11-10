package de.schumey.overhead

/** Readable names for common ICAO aircraft type codes. Unknown codes are shown as they are. */
object TypeNames {
    private val names = mapOf(
        "A318" to "Airbus A318", "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
        "A19N" to "Airbus A319neo", "A20N" to "Airbus A320neo", "A21N" to "Airbus A321neo",
        "BCS1" to "Airbus A220-100", "BCS3" to "Airbus A220-300",
        "A306" to "Airbus A300-600", "A310" to "Airbus A310",
        "A332" to "Airbus A330-200", "A333" to "Airbus A330-300", "A338" to "Airbus A330-800", "A339" to "Airbus A330-900",
        "A342" to "Airbus A340-200", "A343" to "Airbus A340-300", "A345" to "Airbus A340-500", "A346" to "Airbus A340-600",
        "A359" to "Airbus A350-900", "A35K" to "Airbus A350-1000", "A388" to "Airbus A380-800", "A3ST" to "Airbus Beluga",
        "B731" to "Boeing 737-100", "B733" to "Boeing 737-300", "B734" to "Boeing 737-400", "B735" to "Boeing 737-500",
        "B736" to "Boeing 737-600", "B737" to "Boeing 737-700", "B738" to "Boeing 737-800", "B739" to "Boeing 737-900",
        "B37M" to "Boeing 737 MAX 7", "B38M" to "Boeing 737 MAX 8", "B39M" to "Boeing 737 MAX 9", "B3XM" to "Boeing 737 MAX 10",
        "B744" to "Boeing 747-400", "B748" to "Boeing 747-8", "B752" to "Boeing 757-200", "B753" to "Boeing 757-300",
        "B762" to "Boeing 767-200", "B763" to "Boeing 767-300", "B764" to "Boeing 767-400",
        "B772" to "Boeing 777-200", "B77L" to "Boeing 777-200LR/F", "B773" to "Boeing 777-300", "B77W" to "Boeing 777-300ER",
        "B778" to "Boeing 777-8", "B779" to "Boeing 777-9",
        "B788" to "Boeing 787-8", "B789" to "Boeing 787-9", "B78X" to "Boeing 787-10",
        "MD11" to "McDonnell Douglas MD-11", "MD82" to "McDonnell Douglas MD-82", "MD83" to "McDonnell Douglas MD-83",
        "E170" to "Embraer 170", "E75S" to "Embraer 175", "E75L" to "Embraer 175", "E190" to "Embraer 190", "E195" to "Embraer 195",
        "E290" to "Embraer E190-E2", "E295" to "Embraer E195-E2", "E135" to "Embraer ERJ-135", "E145" to "Embraer ERJ-145",
        "CRJ2" to "Bombardier CRJ-200", "CRJ7" to "Bombardier CRJ-700", "CRJ9" to "Bombardier CRJ-900", "CRJX" to "Bombardier CRJ-1000",
        "DH8D" to "Dash 8 Q400", "DH8C" to "Dash 8 Q300", "AT72" to "ATR 72", "AT76" to "ATR 72-600", "AT75" to "ATR 72-500", "AT45" to "ATR 42",
        "SF34" to "Saab 340", "SU95" to "Sukhoi Superjet 100", "C919" to "COMAC C919", "AN12" to "Antonov An-12", "A124" to "Antonov An-124",
        "A225" to "Antonov An-225", "IL76" to "Ilyushin Il-76",
        "C172" to "Cessna 172", "C182" to "Cessna 182", "C208" to "Cessna Caravan", "PA28" to "Piper PA-28", "SR22" to "Cirrus SR22",
        "PC12" to "Pilatus PC-12", "PC24" to "Pilatus PC-24", "TBM9" to "TBM 900", "DA40" to "Diamond DA40", "DA42" to "Diamond DA42",
        "C25A" to "Cessna CitationJet CJ2", "C25B" to "Cessna CitationJet CJ3", "C525" to "Cessna CitationJet", "C56X" to "Cessna Citation Excel",
        "C680" to "Cessna Citation Sovereign", "C750" to "Cessna Citation X", "E55P" to "Embraer Phenom 300", "E50P" to "Embraer Phenom 100",
        "F2TH" to "Dassault Falcon 2000", "F900" to "Dassault Falcon 900", "FA7X" to "Dassault Falcon 7X", "FA8X" to "Dassault Falcon 8X",
        "GLF4" to "Gulfstream IV", "GLF5" to "Gulfstream V", "GLF6" to "Gulfstream G650", "GLEX" to "Bombardier Global Express",
        "GL5T" to "Bombardier Global 5000", "GL7T" to "Bombardier Global 7500", "CL60" to "Bombardier Challenger 600", "CL35" to "Bombardier Challenger 350",
        "LJ45" to "Learjet 45", "H25B" to "Hawker 800",
        "EC35" to "Eurocopter EC135", "EC45" to "Airbus H145", "EC30" to "Eurocopter EC130", "H160" to "Airbus H160", "A109" to "Agusta A109",
        "B06" to "Bell 206", "R44" to "Robinson R44", "R22" to "Robinson R22", "AS50" to "Eurocopter AS350", "AS65" to "Eurocopter AS365",
        "H47" to "Boeing CH-47 Chinook", "NH90" to "NH90",
    )

    fun name(code: String?): String? = code?.let { names[it.uppercase()] }
}
