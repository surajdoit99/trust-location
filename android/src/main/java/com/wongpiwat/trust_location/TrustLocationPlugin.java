package com.wongpiwat.trust_location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager; // ADD THIS IMPORT
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int permission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
                result.success(permission == PackageManager.PERMISSION_GRANTED);
            } else {
                // For older versions, assume permission is granted
                result.success(true);
            }
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
        // Method 1: Check developer settings for mock location app (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                String mockLocationApp = Settings.Secure.getString(
                    context.getContentResolver(), 
                    Settings.Secure.ALLOW_MOCK_LOCATION
                );
                
                // If a mock location app is selected, mock location is enabled
                if (mockLocationApp != null && !mockLocationApp.isEmpty()) {
                    Log.i("TrustLocation", "Mock location app detected: " + mockLocationApp);
                    return true;
                }
            } catch (Exception e) {
                Log.e("TrustLocation", "Error checking mock location settings", e);
            }
        }

        // Method 2: Check for common virtual/emulator indicators
        return isDeviceSuspectForMockLocation();
    }

    private boolean isDeviceSuspectForMockLocation() {
        // Check for common emulator/virtual device indicators
        String model = Build.MODEL.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        String fingerprint = Build.FINGERPRINT.toLowerCase();

        // Extended list of virtual environment indicators
        String[] virtualIndicators = {
            "parallel", "clone", "dual", "multiple", "2face", "2account",
            "virtual", "emulator", "genymotion", "bluestacks", "nox",
            "memu", "ldplayer", "andy", "simulator", "x86", "android sdk",
            "sdk_google", "google_sdk", "droid4x", "vbox", "virtualbox",
            "vmware", "qemu", "parallel space", "multi", "island", "shelter"
        };

        for (String indicator : virtualIndicators) {
            if (model.contains(indicator) || 
                manufacturer.contains(indicator) || 
                brand.contains(indicator) ||
                fingerprint.contains(indicator)) {
                Log.i("TrustLocation", "Virtual environment detected: " + indicator);
                return true;
            }
        }

        // Check for generic test devices
        if (model.contains("test") || model.contains("generic")) {
            return true;
        }

        return false;
    }
}
