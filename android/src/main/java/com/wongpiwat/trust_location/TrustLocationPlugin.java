package com.wongpiwat.trust_location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** TrustLocationPlugin */
public class TrustLocationPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private static final String CHANNEL = "trust_location";
    private MethodChannel channel;
    private Context context;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler(this);
        context = flutterPluginBinding.getApplicationContext();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        // No activity binding needed for basic mock detection
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // No activity binding needed
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        // No activity binding needed
    }

    @Override
    public void onDetachedFromActivity() {
        // No activity binding needed
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "isMockLocation":
                handleIsMockLocation(result);
                break;
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            case "areLocationServicesEnabled":
                handleAreLocationServicesEnabled(result);
                break;
            case "hasLocationPermission":
                handleHasLocationPermission(result);
                break;
            case "requestLocationPermission":
                handleRequestLocationPermission(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleIsMockLocation(@NonNull Result result) {
        try {
            boolean isMock = detectMockLocation();
            result.success(isMock);
        } catch (Exception e) {
            Log.e("TrustLocation", "Error detecting mock location", e);
            result.success(false); // Fail-safe: assume no mock on error
        }
    }

    private void handleAreLocationServicesEnabled(@NonNull Result result) {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            boolean enabled = locationManager != null && 
                (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
                 locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            result.success(enabled);
        } catch (Exception e) {
            Log.e("TrustLocation", "Error checking location services", e);
            result.success(false);
        }
    }

    private void handleHasLocationPermission(@NonNull Result result) {
        try {
            // Use ContextCompat for better compatibility
            int permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
            result.success(permission == PackageManager.PERMISSION_GRANTED);
        } catch (Exception e) {
            Log.e("TrustLocation", "Error checking location permission", e);
            result.success(false);
        }
    }

    private void handleRequestLocationPermission(@NonNull Result result) {
        // This is a placeholder - actual permission request should be handled in Activity
        result.success(false);
    }

    private boolean detectMockLocation() {
        // Method 1: Check for common virtual/emulator indicators
        if (isDeviceSuspectForMockLocation()) {
            return true;
        }

        // Method 2: Check developer settings for mock location (Android 6.0-9.0)
        // Note: ALLOW_MOCK_LOCATION was deprecated in API 23 but still works up to Android 9
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            try {
                // This is the deprecated API causing the warning
                @SuppressWarnings("deprecation")
                String mockLocationApp = Settings.Secure.getString(
                    context.getContentResolver(), 
                    Settings.Secure.ALLOW_MOCK_LOCATION
                );
                
                // If a mock location app is selected, mock location is enabled
                if (mockLocationApp != null && !mockLocationApp.isEmpty() && !"0".equals(mockLocationApp)) {
                    Log.i("TrustLocation", "Mock location app detected: " + mockLocationApp);
                    return true;
                }
            } catch (Exception e) {
                Log.e("TrustLocation", "Error checking mock location settings", e);
            }
        }

        // Method 3: For Android 10+, we rely more on device fingerprinting
        // since ALLOW_MOCK_LOCATION is more restricted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Additional checks for Android 10+ devices
            return isAndroid10PlusMockDetected();
        }

        return false;
    }

    private boolean isAndroid10PlusMockDetected() {
        // On Android 10+, we can't reliably detect mock location via settings
        // So we rely more on device fingerprinting and behavior analysis
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                // Check if any test providers are added (less reliable but still useful)
                for (String provider : locationManager.getAllProviders()) {
                    if (provider.contains("test") || provider.contains("mock")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e("TrustLocation", "Error checking Android 10+ mock location", e);
        }
        
        return false;
    }

    private boolean isDeviceSuspectForMockLocation() {
        // Check for common emulator/virtual device indicators
        String model = Build.MODEL.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        String fingerprint = Build.FINGERPRINT.toLowerCase();
        String product = Build.PRODUCT.toLowerCase();

        // Extended list of virtual environment indicators
        String[] virtualIndicators = {
            "parallel", "clone", "dual", "multiple", "2face", "2account",
            "virtual", "emulator", "genymotion", "bluestacks", "nox",
            "memu", "ldplayer", "andy", "simulator", "x86", "android sdk",
            "sdk_google", "google_sdk", "droid4x", "vbox", "virtualbox",
            "vmware", "qemu", "parallel space", "multi", "island", "shelter",
            "test", "generic"
        };

        for (String indicator : virtualIndicators) {
            if (model.contains(indicator) || 
                manufacturer.contains(indicator) || 
                brand.contains(indicator) ||
                fingerprint.contains(indicator) ||
                product.contains(indicator)) {
                Log.i("TrustLocation", "Virtual environment detected: " + indicator);
                return true;
            }
        }

        // Check for specific emulator patterns
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.startsWith("sdk") ||
            Build.PRODUCT.startsWith("vbox")) {
            return true;
        }

        return false;
    }
}
