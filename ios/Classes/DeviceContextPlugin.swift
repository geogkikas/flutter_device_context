import Flutter
import UIKit
import CoreLocation
import CoreMotion

public class DeviceContextPlugin: NSObject, FlutterPlugin {
  private let motionManager = CMMotionManager()

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "max.device.collector/context", binaryMessenger: registrar.messenger())
    let instance = DeviceContextPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard call.method == "getSensorData" else {
      result(FlutterMethodNotImplemented)
      return
    }

    var sensorData = [String: Any]()
    let arguments = call.arguments as? [String: Any] ?? [:]

    // 1. Process Synchronous One-Time Reads
    if arguments["fetchDeviceInfo"] as? Bool ?? true { fetchDeviceInfo(into: &sensorData) }
    if arguments["fetchBasic"] as? Bool ?? true { fetchBasicInfo(into: &sensorData) }
    if arguments["fetchThermal"] as? Bool ?? true { fetchThermalInfo(into: &sensorData) }
    if arguments["fetchLocation"] as? Bool ?? false { fetchLocationInfo(into: &sensorData) }

    // 2. Extract Sampling Parameters
    let samplingWindowSeconds = arguments["samplingWindowSeconds"] as? Int ?? 0
    let samplingHz = arguments["samplingHz"] as? Int ?? 10
    let fetchMeanMotion = arguments["fetchMeanMotion"] as? Bool ?? false
    let fetchInstantMotion = arguments["fetchMotion"] as? Bool ?? false

    // 3. Route to the correct execution engine
    if samplingWindowSeconds > 0 && fetchMeanMotion {
      // FIX: Grab the instant snapshot immediately before starting the 5-second loop
      if fetchInstantMotion { fetchInstantMotionInfo(into: &sensorData) }

      executeContinuousSampling(
          dataMap: sensorData,
          windowSeconds: samplingWindowSeconds,
          hz: samplingHz,
          result: result
      )
    } else {
      if fetchInstantMotion { fetchInstantMotionInfo(into: &sensorData) }
      result(sensorData)
    }
  }

  // MARK: - Continuous Sampling Engine

  /// Gathers high-frequency data over a specified time window and calculates mean values.
  private func executeContinuousSampling(dataMap: [String: Any], windowSeconds: Int, hz: Int, result: @escaping FlutterResult) {
    var finalData = dataMap
    var hasResponded = false

    var accelerationXSamples = [Double]()
    var accelerationYSamples = [Double]()
    var accelerationZSamples = [Double]()
    var movingSampleCount = 0

    // Calculate update interval (e.g., 20Hz = 0.05s)
    let updateInterval = hz > 0 ? (1.0 / Double(hz)) : 0.1

    if motionManager.isAccelerometerAvailable {
      motionManager.accelerometerUpdateInterval = updateInterval
      motionManager.startAccelerometerUpdates(to: .main) { (accelerometerData, error) in
        guard !hasResponded, let acceleration = accelerometerData?.acceleration else { return }

        accelerationXSamples.append(acceleration.x)
        accelerationYSamples.append(acceleration.y)
        accelerationZSamples.append(acceleration.z)

        // Motion heuristic: iOS returns acceleration in Gs (1.0 = resting state due to gravity).
        // A deviation > 0.1G indicates movement.
        let magnitude = sqrt(pow(acceleration.x, 2) + pow(acceleration.y, 2) + pow(acceleration.z, 2))
        if abs(magnitude - 1.0) > 0.1 {
          movingSampleCount += 1
        }
      }
    }

    // Time-Bound Shutdown
    DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(windowSeconds)) { [weak self] in
      guard !hasResponded else { return }
      hasResponded = true

      self?.motionManager.stopAccelerometerUpdates()

      if !accelerationXSamples.isEmpty {
        finalData["mean_accelX"] = accelerationXSamples.reduce(0, +) / Double(accelerationXSamples.count)
        finalData["mean_accelY"] = accelerationYSamples.reduce(0, +) / Double(accelerationYSamples.count)
        finalData["mean_accelZ"] = accelerationZSamples.reduce(0, +) / Double(accelerationZSamples.count)

        // If >5% of the samples registered a motion spike, classify the state as "Moving"
        let percentageMoving = Double(movingSampleCount) / Double(accelerationXSamples.count)
        finalData["mean_motionState"] = percentageMoving > 0.05 ? "Moving" : "Still"
      }

      result(finalData)
    }
  }

  // MARK: - Synchronous Fetchers

  private func fetchDeviceInfo(into data: inout [String: Any]) {
    data["manufacturer"] = "Apple"
    data["osName"] = UIDevice.current.systemName
    data["osVersion"] = UIDevice.current.systemVersion
    data["deviceId"] = UIDevice.current.identifierForVendor?.uuidString

    // Extract exact hardware identifier (e.g., "iPhone14,2")
    var systemInfo = utsname()
    uname(&systemInfo)
    let machineMirror = Mirror(reflecting: systemInfo.machine)
    let modelIdentifier = machineMirror.children.reduce("") { identifier, element in
      guard let value = element.value as? Int8, value != 0 else { return identifier }
      return identifier + String(UnicodeScalar(UInt8(value)))
    }
    data["model"] = modelIdentifier
  }

  private func fetchBasicInfo(into data: inout [String: Any]) {
    UIDevice.current.isBatteryMonitoringEnabled = true
    data["batteryLevel"] = Int(UIDevice.current.batteryLevel * 100)

    switch UIDevice.current.batteryState {
    case .charging: data["status"] = 2
    case .unplugged: data["status"] = 3
    case .full: data["status"] = 5
    case .unknown: break
    @unknown default: break
    }
  }

  private func fetchThermalInfo(into data: inout [String: Any]) {
    if #available(iOS 11.0, *) {
      switch ProcessInfo.processInfo.thermalState {
      case .nominal: data["thermalStatus"] = 0
      case .fair: data["thermalStatus"] = 1
      case .serious: data["thermalStatus"] = 3
      case .critical: data["thermalStatus"] = 4
      @unknown default: break
      }
    }
  }

  private func fetchLocationInfo(into data: inout [String: Any]) {
    guard let location = CLLocationManager().location else { return }
    let coordinate = location.coordinate
    let hasInvalidFix = location.horizontalAccuracy < 0
    let isStale = Date().timeIntervalSince(location.timestamp) > 300
    let isNoFixDefault =
        abs(coordinate.latitude - 37.33233141) < 0.0001 &&
        abs(coordinate.longitude + 122.0312186) < 0.0001
    let isNullIsland = coordinate.latitude == 0 && coordinate.longitude == 0
    if hasInvalidFix || isStale || isNoFixDefault || isNullIsland { return }
    data["latitude"] = coordinate.latitude
    data["longitude"] = coordinate.longitude
    data["altitude"] = location.altitude
  }

  private func fetchInstantMotionInfo(into data: inout [String: Any]) {
    // 1. Proximity
    UIDevice.current.isProximityMonitoringEnabled = true
    data["isCovered"] = UIDevice.current.proximityState
    UIDevice.current.isProximityMonitoringEnabled = false

    // 2. Posture
    data["posture"] = {
      switch UIDevice.current.orientation {
      case .faceUp: return "Face Up"
      case .faceDown: return "Face Down"
      case .portrait, .portraitUpsideDown: return "Portrait"
      case .landscapeLeft, .landscapeRight: return "Landscape"
      default: return "Other"
      }
    }()

    // 3. Accelerometer (Instant single-shot)
    if motionManager.isAccelerometerAvailable {
      motionManager.startAccelerometerUpdates()
      if let accel = motionManager.accelerometerData {
        let x = accel.acceleration.x
        let y = accel.acceleration.y
        let z = accel.acceleration.z

        data["accelX"] = x
        data["accelY"] = y
        data["accelZ"] = z

        // Manual Heuristic for "Motion"
        let magnitude = sqrt(pow(x, 2) + pow(y, 2) + pow(z, 2))
        data["motionState"] = abs(magnitude - 1.0) > 0.1 ? "Moving" : "Still"
      }
      motionManager.stopAccelerometerUpdates()
    }
  }
}