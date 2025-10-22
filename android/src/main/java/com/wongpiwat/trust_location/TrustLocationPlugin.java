package com.wongpiwat.trust_location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.List;

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
            Log.i("TrustLocation", "Mock location detection result: " + isMock);
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
        Log.i("TrustLocation", "Starting mock location detection...");
        
        // Method 1: Check if mock location is enabled in developer options
        if (isMockLocationEnabledInSettings()) {
            Log.i("TrustLocation", "Mock location enabled in developer settings");
            return true;
        }

        // Method 2: Check for test providers (more reliable indicator)
        if (hasTestProviders()) {
            Log.i("TrustLocation", "Test providers detected");
            return true;
        }

        // Method 3: Check last known locations for mock indicators
        if (hasMockLocationsFromProviders()) {
            Log.i("TrustLocation", "Mock locations detected from providers");
            return true;
        }

        // Method 4: Check for virtual/emulator environment
        if (isDeviceSuspectForMockLocation()) {
            Log.i("TrustLocation", "Device is suspect for mock location");
            return true;
        }

        Log.i("TrustLocation", "No mock location detected");
        return false;
    }

    private boolean isMockLocationEnabledInSettings() {
    try {
        // Use AppOpsManager (valid from Android 6+)
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;

        List<ApplicationInfo> apps = context.getPackageManager().getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            if (app.packageName.equals(context.getPackageName())) continue; // Skip self

            try {
                int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, app.uid, app.packageName);
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    Log.i("TrustLocation", "Mock location app detected: " + app.packageName);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
    } catch (Exception e) {
        Log.e("TrustLocation", "Error checking mock location apps via AppOpsManager", e);
    }

    return false;
}


    private boolean hasTestProviders() {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return false;
            }

            // Check for test providers in the list of all providers
            List<String> allProviders = locationManager.getAllProviders();
            for (String provider : allProviders) {
                if (provider.toLowerCase().contains("test") || 
                    provider.toLowerCase().contains("mock") ||
                    provider.equals("fused") || // Some mock apps use "fused" provider
                    provider.contains("gps") && provider.length() > 3) { // Modified GPS providers
                    Log.i("TrustLocation", "Suspicious provider found: " + provider);
                    return true;
                }
            }

            // Check if test provider can be added (indicates mock location capability)
            try {
                // This will throw SecurityException if mock locations are not allowed
                locationManager.addTestProvider("test_provider_check", false, false, false, false, true, true, true, 0, 5);
                locationManager.removeTestProvider("test_provider_check");
                Log.i("TrustLocation", "Test provider can be added - mock location likely enabled");
                return true;
            } catch (SecurityException e) {
                // SecurityException means mock locations are not allowed - this is good
                Log.i("TrustLocation", "Cannot add test provider - mock location likely disabled");
            } catch (Exception e) {
                Log.e("TrustLocation", "Error testing provider addition", e);
            }

        } catch (Exception e) {
            Log.e("TrustLocation", "Error checking test providers", e);
        }
        return false;
    }

    private boolean hasMockLocationsFromProviders() {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i("TrustLocation", "No location permission, skipping location-based mock detection");
                return false;
            }

            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return false;
            }

            // Check last known locations from different providers
            String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            
            for (String provider : providers) {
                try {
                    Location location = locationManager.getLastKnownLocation(provider);
                    if (location != null) {
                        // Check if location is from mock provider (API 18+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            if (location.isFromMockProvider()) {
                                Log.i("TrustLocation", "Mock location detected from provider: " + provider);
                                return true;
                            }
                        }
                        
                        // Additional checks for suspicious locations
                        if (isSuspiciousLocation(location)) {
                            Log.i("TrustLocation", "Suspicious location from provider: " + provider);
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    Log.w("TrustLocation", "No permission to access location from provider: " + provider);
                } catch (Exception e) {
                    Log.e("TrustLocation", "Error checking location from provider: " + provider, e);
                }
            }

        } catch (Exception e) {
            Log.e("TrustLocation", "Error checking locations from providers", e);
        }
        return false;
    }

    private boolean isSuspiciousLocation(Location location) {
        if (location == null) return false;

        // Check for 0,0 coordinates (common in mock locations)
        if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) {
            return true;
        }

        // Check for impossible accuracy values
        if (location.hasAccuracy() && (location.getAccuracy() < 0 || location.getAccuracy() > 100000)) {
            return true;
        }

        // Check for very old timestamps (older than 1 hour)
        long locationAge = System.currentTimeMillis() - location.getTime();
        if (locationAge > 3600000) { // 1 hour in milliseconds
            return true;
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
        String device = Build.DEVICE.toLowerCase();

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
                fingerprint.contains(indicator) ||
                product.contains(indicator) ||
                device.contains(indicator)) {
                Log.i("TrustLocation", "Virtual environment indicator: " + indicator);
                return true;
            }
        }

        // Check for specific emulator patterns
        if (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox") ||
            Build.FINGERPRINT.contains("test-keys") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.startsWith("sdk") ||
            Build.PRODUCT.startsWith("vbox86t") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("vbox") ||
            Build.HARDWARE.contains("ranchu")) {
            Log.i("TrustLocation", "Emulator pattern detected");
            return true;
        }

        return false;
    }
}
