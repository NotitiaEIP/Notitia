import Accelerate
import Foundation

// Séparation des locuteurs, sur l'appareil et sans modèle : « qui parle quand », pas « qui est-ce ».
//
// 1. Pendant l'enregistrement, `VoiceFeatureExtractor` résume la voix par blocs de 0,25 s (coefficients MFCC :
//    l'enveloppe du spectre, qui dépend surtout du conduit vocal). Rien n'est écrit sur le disque.
// 2. À l'arrêt, `SpeakerDiarization.label` calcule une moyenne par segment de transcription, regroupe les segments
//    dont les voix se ressemblent et les étiquette « Locuteur 1 », « Locuteur 2 »… dans l'ordre d'apparition.
//
// Confidentialité : ces mesures servent à distinguer les voix **dans un seul enregistrement**. Elles sont jetées
// après l'arrêt, ne sont jamais comparées d'un enregistrement à l'autre et n'identifient personne
// (voir PRIVACY.md). Les noms ne viennent que de l'utilisateur (`Recording.speakerNames`).

/// Résumé de la voix sur un court bloc de temps : sommes des mesures sur les trames de parole.
public struct VoiceBlock: Sendable, Equatable {
    public var start: TimeInterval
    public var end: TimeInterval
    /// Nombre de trames de 10 ms jugées assez fortes pour contenir de la parole.
    public var frames: Int
    /// Sommes des coefficients MFCC (enveloppe du spectre) et de leurs carrés.
    public var sum: [Float]
    public var sumOfSquares: [Float]
    /// Trames voisées (dont on a pu mesurer la hauteur), somme de leur hauteur en octaves et des carrés.
    public var pitchFrames: Int
    public var pitchSum: Float
    public var pitchSumOfSquares: Float

    public init(start: TimeInterval, end: TimeInterval) {
        self.start = start
        self.end = end
        self.frames = 0
        self.sum = [Float](repeating: 0, count: VoiceFeatureExtractor.coefficientCount)
        self.sumOfSquares = [Float](repeating: 0, count: VoiceFeatureExtractor.coefficientCount)
        self.pitchFrames = 0
        self.pitchSum = 0
        self.pitchSumOfSquares = 0
    }
}

/// Mesure la voix trame par trame (25 ms, pas de 10 ms) et cumule les mesures par bloc de 0,25 s :
/// - l'enveloppe du spectre (MFCC 1 à 12). Le coefficient 0 (l'énergie) est écarté : la distance au micro et le
///   gain ne doivent pas séparer une voix en deux ;
/// - la hauteur de la voix (fréquence fondamentale, par autocorrélation), en octaves.
public struct VoiceFeatureExtractor {
    public static let coefficientCount = 12
    public static let blockDuration: TimeInterval = 0.25
    /// Trame plus faible que ce niveau (≈ -50 dBFS) : silence ou bruit de fond, ignorée.
    public static let minimumLevel: Float = 0.003

    static let melCount = 26
    static let frameDuration: TimeInterval = 0.025
    static let hopDuration: TimeInterval = 0.010

    public private(set) var blocks: [VoiceBlock] = []

    private var sampleRate: Double = 0
    private var frameLength = 0
    private var hop = 0
    private var fftSize = 0
    private var dft: vDSP.DiscreteFourierTransform<Float>?
    private var window: [Float] = []
    private var melFilters: [Float] = [] // melCount × (fftSize / 2 + 1), ligne par ligne
    private var dct: [Float] = [] // coefficientCount × melCount
    private var zeros: [Float] = []

    private var pending: [Float] = []
    private var consumed = 0
    /// Instant du premier échantillon de `pending`.
    private var pendingTime: TimeInterval = 0
    private var expectedTime: TimeInterval?
    private var current: VoiceBlock?

    public init() {}

    /// Ajoute un bloc audio mono. Un trou dans l'horloge (bloc perdu) recommence le découpage en trames.
    public mutating func add(_ chunk: AudioChunk) {
        guard !chunk.samples.isEmpty, chunk.sampleRate > 0 else { return }
        if chunk.sampleRate != sampleRate { configure(sampleRate: chunk.sampleRate) }
        if let expected = expectedTime, abs(chunk.time - expected) > 0.02 {
            pending.removeAll(keepingCapacity: true)
            consumed = 0
        }
        if pending.count == consumed {
            pending.removeAll(keepingCapacity: true)
            consumed = 0
            pendingTime = chunk.time
        }
        pending += chunk.samples
        expectedTime = chunk.time + chunk.duration

        while pending.count - consumed >= frameLength {
            let time = pendingTime + Double(consumed) / sampleRate
            pending.withUnsafeBufferPointer { buffer in
                process(UnsafeBufferPointer(rebasing: buffer[consumed..<consumed + frameLength]), time: time)
            }
            consumed += hop
        }
        // Compacte le tampon de temps en temps plutôt qu'à chaque trame.
        if consumed > 8 * frameLength {
            pendingTime += Double(consumed) / sampleRate
            pending.removeFirst(consumed)
            consumed = 0
        }
    }

    /// Termine le dernier bloc et renvoie tous les blocs contenant de la parole.
    public mutating func finish() -> [VoiceBlock] {
        flushBlock()
        return blocks
    }

    // MARK: Calcul

    private mutating func configure(sampleRate: Double) {
        flushBlock()
        self.sampleRate = sampleRate
        frameLength = max(64, Int((Self.frameDuration * sampleRate).rounded()))
        hop = max(16, Int((Self.hopDuration * sampleRate).rounded()))
        fftSize = 1 << Int(ceil(log2(Double(frameLength))))
        dft = try? vDSP.DiscreteFourierTransform(count: fftSize, direction: .forward, transformType: .complexComplex, ofType: Float.self)
        window = vDSP.window(ofType: Float.self, usingSequence: .hanningDenormalized, count: frameLength, isHalfWindow: false)
        melFilters = Self.melFilterBank(sampleRate: sampleRate, fftSize: fftSize)
        dct = Self.dctMatrix()
        zeros = [Float](repeating: 0, count: fftSize)
        pending.removeAll()
        consumed = 0
        expectedTime = nil
    }

    private mutating func process(_ frame: UnsafeBufferPointer<Float>, time: TimeInterval) {
        guard let dft else { return }
        let rms = vDSP.rootMeanSquare(frame)
        let index = Int(time / Self.blockDuration)
        if current.map({ Int($0.start / Self.blockDuration) }) != index {
            flushBlock()
            let start = Double(index) * Self.blockDuration
            current = VoiceBlock(start: start, end: start + Self.blockDuration)
        }
        guard rms >= Self.minimumLevel else { return }

        var input = zeros
        input.withUnsafeMutableBufferPointer { output in
            vDSP.multiply(frame, window, result: &output[0..<frameLength])
        }
        let (real, imaginary) = dft.transform(real: input, imaginary: zeros)
        let bins = fftSize / 2 + 1
        let power = vDSP.add(vDSP.square(real[0..<bins]), vDSP.square(imaginary[0..<bins]))

        var mel = [Float](repeating: 0, count: Self.melCount)
        vDSP_mmul(melFilters, 1, power, 1, &mel, 1, vDSP_Length(Self.melCount), 1, vDSP_Length(bins))
        mel = mel.map { log(max($0, 1e-10)) }
        var coefficients = [Float](repeating: 0, count: Self.coefficientCount)
        vDSP_mmul(dct, 1, mel, 1, &coefficients, 1, vDSP_Length(Self.coefficientCount), 1, vDSP_Length(Self.melCount))

        guard var block = current else { return }
        block.frames += 1
        block.sum = vDSP.add(block.sum, coefficients)
        block.sumOfSquares = vDSP.add(block.sumOfSquares, vDSP.square(coefficients))
        if let pitch = Self.pitch(of: frame, sampleRate: sampleRate) {
            let octaves = log2(pitch)
            block.pitchFrames += 1
            block.pitchSum += octaves
            block.pitchSumOfSquares += octaves * octaves
        }
        current = block
    }

    /// Fréquence fondamentale en hertz (entre 70 et 400 Hz), ou `nil` si la trame n'est pas assez périodique.
    static func pitch(of frame: UnsafeBufferPointer<Float>, sampleRate: Double) -> Float? {
        let minimumLag = Int(sampleRate / 400)
        let maximumLag = Int(sampleRate / 70)
        let length = frame.count - maximumLag
        guard length >= minimumLag, let base = frame.baseAddress else { return nil }
        // r[k] = Σ x[n + k] · x[n], pour k de 0 à maximumLag.
        var correlation = [Float](repeating: 0, count: maximumLag + 1)
        vDSP_conv(base, 1, base, 1, &correlation, 1, vDSP_Length(maximumLag + 1), vDSP_Length(length))
        guard correlation[0] > 0 else { return nil }
        var best = minimumLag
        for lag in minimumLag...maximumLag where correlation[lag] > correlation[best] { best = lag }
        guard correlation[best] / correlation[0] >= 0.5 else { return nil }
        // Un multiple de la période ressort parfois autant que la période : on préfère la plus courte.
        var lag = best
        for divisor in [4, 3, 2] where best / divisor >= minimumLag {
            let candidate = (best / divisor - 1...best / divisor + 1).max { correlation[$0] < correlation[$1] }!
            if correlation[candidate] >= 0.9 * correlation[best] {
                lag = candidate
                break
            }
        }
        return Float(sampleRate) / Float(lag)
    }

    private mutating func flushBlock() {
        if let block = current, block.frames > 0 { blocks.append(block) }
        current = nil
    }

    /// Filtres triangulaires répartis sur l'échelle mel, de 100 Hz à 8 kHz (ou la moitié de la fréquence).
    static func melFilterBank(sampleRate: Double, fftSize: Int) -> [Float] {
        func mel(_ hertz: Double) -> Double { 2595 * log10(1 + hertz / 700) }
        func hertz(_ mel: Double) -> Double { 700 * (pow(10, mel / 2595) - 1) }
        let bins = fftSize / 2 + 1
        let low = mel(100), high = mel(min(8000, sampleRate / 2))
        let edges = (0...melCount + 1).map { hertz(low + (high - low) * Double($0) / Double(melCount + 1)) }
        var filters = [Float](repeating: 0, count: melCount * bins)
        for m in 0..<melCount {
            let (left, center, right) = (edges[m], edges[m + 1], edges[m + 2])
            for bin in 0..<bins {
                let frequency = Double(bin) * sampleRate / Double(fftSize)
                let weight = frequency <= center ? (frequency - left) / (center - left) : (right - frequency) / (right - center)
                if weight > 0 { filters[m * bins + bin] = Float(weight) }
            }
        }
        return filters
    }

    /// DCT-II, coefficients 1 à 12.
    static func dctMatrix() -> [Float] {
        (1...coefficientCount).flatMap { n in
            (0..<melCount).map { m in Float(cos(Double.pi * Double(n) * (Double(m) + 0.5) / Double(melCount))) }
        }
    }
}

/// Cumule les blocs de voix d'un flux audio (un des flux de l'enregistrement, comme les détecteurs de sons).
public enum VoiceFeatureCollector {
    public static func collect(_ audio: AsyncStream<AudioChunk>) async -> [VoiceBlock] {
        await Task.detached(priority: .utility) {
            var extractor = VoiceFeatureExtractor()
            for await chunk in audio { extractor.add(chunk) }
            return extractor.finish()
        }.value
    }
}

// MARK: - Regroupement

public enum SpeakerDiarization {
    public struct Options: Sendable {
        /// Nombre de participants indiqué par l'utilisateur ; `nil` : estimé.
        public var expectedSpeakers: Int?
        public var maximumSpeakers = 8
        /// Distance moyenne au-delà de laquelle deux groupes ne sont plus fusionnés (estimation automatique).
        public var threshold: Float = 1.5
        /// Parole minimale d'un segment (en trames de 10 ms) pour participer au regroupement.
        public var minimumFrames = 60
        /// Un groupe plus petit que cette part de la parole est rattaché au groupe le plus proche.
        public var minimumShare: Float = 0.05

        public init(expectedSpeakers: Int? = nil) {
            self.expectedSpeakers = expectedSpeakers
        }
    }

    /// Libellé neutre d'un locuteur. Il est scellé dans la transcription ; le nom choisi par l'utilisateur ne l'est pas.
    public static func label(_ number: Int) -> String { "Locuteur \(number)" }

    /// Renvoie les segments avec un locuteur. S'il n'y a qu'une voix (ou trop peu de parole), ils restent sans locuteur.
    public static func label(_ segments: [TranscriptSegment], voice: [VoiceBlock], options: Options = Options()) -> [TranscriptSegment] {
        let profiles = segments.map { profile(of: $0, in: voice) }
        guard let groups = cluster(profiles, options: options) else { return segments }

        // Numérotation dans l'ordre d'apparition.
        var numbers: [Int: Int] = [:]
        var result = segments
        for index in result.indices {
            guard let group = groups[index] else { continue }
            if numbers[group] == nil { numbers[group] = numbers.count + 1 }
            result[index].speaker = label(numbers[group]!)
        }
        return numbers.count > 1 ? result : segments
    }

    /// Cumul des blocs dont le centre tombe dans le segment.
    static func profile(of segment: TranscriptSegment, in voice: [VoiceBlock]) -> VoiceBlock {
        var profile = VoiceBlock(start: segment.start, end: segment.end)
        for block in voice {
            let center = (block.start + block.end) / 2
            guard center >= segment.start, center <= segment.end else { continue }
            profile.add(block)
        }
        return profile
    }

    /// Groupe de chaque segment (`nil` : pas de parole mesurée), ou `nil` si l'on ne peut rien conclure.
    static func cluster(_ profiles: [VoiceBlock], options: Options) -> [Int?]? {
        let reliable = profiles.indices.filter { profiles[$0].frames >= options.minimumFrames }
        guard reliable.count >= 2 else { return nil }

        // Hauteur moyenne de tout l'enregistrement : elle remplace celle d'un segment sans trame voisée.
        let pitched = reliable.filter { profiles[$0].pitchFrames > 0 }
        let pitchFrames = Float(pitched.reduce(0) { $0 + profiles[$1].pitchFrames })
        let averagePitch = pitchFrames > 0 ? pitched.reduce(Float(0)) { $0 + profiles[$1].pitchSum } / pitchFrames : 0
        func features(_ profile: VoiceBlock) -> [Float] {
            profile.meanCoefficients + [profile.pitchFrames > 0 ? profile.pitchSum / Float(profile.pitchFrames) : averagePitch]
        }

        // Échelle de chaque mesure : sa dispersion moyenne à l'intérieur des segments. Ce qui varie peu chez une
        // même personne compte davantage ; le gain du micro n'intervient pas.
        let frames = Float(reliable.reduce(0) { $0 + profiles[$1].frames })
        var variance = [Float](repeating: 0, count: VoiceFeatureExtractor.coefficientCount)
        for index in reliable {
            variance = vDSP.add(variance, vDSP.multiply(Float(profiles[index].frames) / frames, profiles[index].coefficientVariance))
        }
        let pitchVariance = pitched.reduce(Float(0)) { $0 + Float(profiles[$1].pitchFrames) / pitchFrames * profiles[$1].pitchVariance }
        let scale = (variance + [pitchVariance]).map { 1 / max($0.squareRoot(), 1e-3) }
        let points = reliable.map { vDSP.multiply(features(profiles[$0]), scale) }
        let weights = reliable.map { Float(profiles[$0].frames) }

        var groups = AgglomerativeClustering.run(points: points, weights: weights,
                                                 targetCount: options.expectedSpeakers,
                                                 threshold: options.threshold,
                                                 maximumCount: options.maximumSpeakers)
        if options.expectedSpeakers == nil {
            groups = AgglomerativeClustering.absorbSmallGroups(groups, points: points, weights: weights, minimumShare: options.minimumShare)
        }

        // Centres des groupes, pour rattacher les segments trop courts.
        let centroids = Dictionary(grouping: groups.indices, by: { groups[$0] }).mapValues { members -> [Float] in
            let weight = members.reduce(Float(0)) { $0 + weights[$1] }
            return members.reduce([Float](repeating: 0, count: scale.count)) { vDSP.add($0, vDSP.multiply(weights[$1] / weight, points[$1])) }
        }

        var result = [Int?](repeating: nil, count: profiles.count)
        for (position, index) in reliable.enumerated() { result[index] = groups[position] }
        for index in profiles.indices where result[index] == nil && profiles[index].frames > 0 {
            let point = vDSP.multiply(features(profiles[index]), scale)
            result[index] = centroids.min { vDSP.distanceSquared($0.value, point) < vDSP.distanceSquared($1.value, point) }?.key
        }
        // Aucun son mesuré (segment très court) : même locuteur que le segment précédent.
        for index in profiles.indices where result[index] == nil {
            result[index] = index > 0 ? result[index - 1] : result.first { $0 != nil } ?? nil
        }
        return result
    }
}

extension VoiceBlock {
    mutating func add(_ other: VoiceBlock) {
        frames += other.frames
        sum = vDSP.add(sum, other.sum)
        sumOfSquares = vDSP.add(sumOfSquares, other.sumOfSquares)
        pitchFrames += other.pitchFrames
        pitchSum += other.pitchSum
        pitchSumOfSquares += other.pitchSumOfSquares
    }

    var meanCoefficients: [Float] { frames > 0 ? vDSP.divide(sum, Float(frames)) : sum }

    var coefficientVariance: [Float] {
        guard frames > 1 else { return [Float](repeating: 0, count: sum.count) }
        return vDSP.subtract(vDSP.divide(sumOfSquares, Float(frames)), vDSP.square(meanCoefficients)).map { max($0, 0) }
    }

    var pitchVariance: Float {
        guard pitchFrames > 1 else { return 0 }
        let mean = pitchSum / Float(pitchFrames)
        return max(pitchSumOfSquares / Float(pitchFrames) - mean * mean, 0)
    }
}

/// Regroupement hiérarchique ascendant, liaison moyenne pondérée (UPGMA), distance euclidienne.
enum AgglomerativeClustering {
    /// Renvoie un numéro de groupe par point.
    /// - `targetCount` : nombre de groupes voulu ; sinon on fusionne tant que la distance reste sous `threshold`.
    static func run(points: [[Float]], weights: [Float], targetCount: Int?, threshold: Float, maximumCount: Int) -> [Int] {
        let n = points.count
        guard n > 1 else { return [Int](repeating: 0, count: n) }
        var distance = [Float](repeating: 0, count: n * n)
        for i in 0..<n {
            for j in (i + 1)..<n {
                let d = vDSP.distanceSquared(points[i], points[j]).squareRoot()
                distance[i * n + j] = d
                distance[j * n + i] = d
            }
        }
        var active = [Bool](repeating: true, count: n)
        var weight = weights
        var parent = Array(0..<n)
        var count = n
        let target = targetCount.map { max(1, $0) }

        // Plus proche voisin de chaque groupe, mis à jour seulement quand il change : O(n²) au lieu de O(n³).
        var neighbor = [Int](repeating: -1, count: n)
        var neighborDistance = [Float](repeating: .infinity, count: n)
        func updateNeighbor(_ i: Int) {
            neighbor[i] = -1
            neighborDistance[i] = .infinity
            for k in 0..<n where active[k] && k != i && distance[i * n + k] < neighborDistance[i] {
                neighbor[i] = k
                neighborDistance[i] = distance[i * n + k]
            }
        }
        for i in 0..<n { updateNeighbor(i) }

        while count > 1 {
            var i = -1
            for k in 0..<n where active[k] && (i < 0 || neighborDistance[k] < neighborDistance[i]) { i = k }
            let j = neighbor[i]
            if let target {
                if count <= target { break }
            } else if neighborDistance[i] > threshold && count <= maximumCount {
                break
            }
            // Fusionne j dans i (formule de Lance-Williams pour la liaison moyenne).
            for k in 0..<n where active[k] && k != i && k != j {
                let d = (weight[i] * distance[i * n + k] + weight[j] * distance[j * n + k]) / (weight[i] + weight[j])
                distance[i * n + k] = d
                distance[k * n + i] = d
            }
            weight[i] += weight[j]
            active[j] = false
            parent[j] = i
            count -= 1
            updateNeighbor(i)
            for k in 0..<n where active[k] && k != i {
                if neighbor[k] == i || neighbor[k] == j {
                    updateNeighbor(k)
                } else if distance[k * n + i] < neighborDistance[k] {
                    neighbor[k] = i
                    neighborDistance[k] = distance[k * n + i]
                }
            }
        }

        func root(_ index: Int) -> Int {
            var index = index
            while parent[index] != index { index = parent[index] }
            return index
        }
        return (0..<n).map(root)
    }

    /// Rattache au groupe le plus proche ceux qui pèsent moins de `minimumShare` du total (souvent du bruit, un rire).
    static func absorbSmallGroups(_ groups: [Int], points: [[Float]], weights: [Float], minimumShare: Float) -> [Int] {
        var groups = groups
        let total = weights.reduce(0, +)
        while true {
            let sizes = Dictionary(grouping: groups.indices, by: { groups[$0] }).mapValues { $0.reduce(Float(0)) { $0 + weights[$1] } }
            guard sizes.count > 1, let smallest = sizes.min(by: { $0.value < $1.value }), smallest.value < minimumShare * total else {
                return groups
            }
            let members = groups.indices.filter { groups[$0] == smallest.key }
            let others = groups.indices.filter { groups[$0] != smallest.key }
            // Groupe dont un membre est le plus proche, en moyenne sur les membres du petit groupe.
            var score: [Int: Float] = [:]
            for other in others {
                let d = members.reduce(Float(0)) { $0 + vDSP.distanceSquared(points[$1], points[other]).squareRoot() } / Float(members.count)
                score[groups[other]] = min(score[groups[other]] ?? .infinity, d)
            }
            guard let nearest = score.min(by: { $0.value < $1.value })?.key else { return groups }
            for member in members { groups[member] = nearest }
        }
    }
}
