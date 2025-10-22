package com.wongpiwat.trust_location;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

/** TrustLocationPlugin */
public class TrustLocationPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private static final String CHANNEL = "trust_location";
    private LocationAssistantListener locationAssistantListener;
    private MethodChannel channel;
    private ActivityPluginBinding activityBinding;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler(this);
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects.
    @SuppressWarnings("deprecation")
    public static void registerWith(PluginRegistry.Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), CHANNEL);
        TrustLocationPlugin plugin = new TrustLocationPlugin();
        channel.setMethodCallHandler(plugin);
        
        if (registrar.activity() != null) {
            plugin.locationAssistantListener = new LocationAssistantListener(registrar.activity(), registrar.activeContext());
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activityBinding = binding;
        this.locationAssistantListener = new LocationAssistantListener(binding.getActivity(), binding.getActivity().getApplicationContext());
        
        // Add permission result listener
        binding.addRequestPermissionsResultListener((requestCode, permissions, grantResults) -> {
            if (locationAssistantListener != null && locationAssistantListener.getAssistant() != null) {
                return locationAssistantListener.getAssistant().onPermissionsUpdated(requestCode, grantResults);
            }
            return false;
        });
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (locationAssistantListener != null && locationAssistantListener.getAssistant() != null) {
            locationAssistantListener.getAssistant().stop();
        }
        if (activityBinding != null) {
            activityBinding.removeRequestPermissionsResultListener((requestCode, permissions, grantResults) -> false);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (locationAssistantListener == null) {
            result.error("UNAVAILABLE", "Location assistant not initialized", null);
            return;
        }

        switch (call.method) {
            case "isMockLocation":
                if (locationAssistantListener.isMockLocationsDetected()) {
                    result.success(true);
                } else if (locationAssistantListener.getLatitude() != null && locationAssistantListener.getLongitude() != null) {
                    result.success(false);
                } else {
                    result.success(true);
                }
                break;
            case "getLatitude":
                if (locationAssistantListener.getLatitude() != null) {
                    result.success(locationAssistantListener.getLatitude());
                } else {
                    result.success(null);
                }
                break;
            case "getLongitude":
                if (locationAssistantListener.getLongitude() != null) {
                    result.success(locationAssistantListener.getLongitude());
                } else {
                    result.success(null);
                }
                break;
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            default:
                result.notImplemented();
                break;
        }
    }
}

class LocationAssistantListener implements LocationAssistant.Listener {
    private final LocationAssistant assistant;
    private boolean isMockLocationsDetected = false;
    private String latitude;
    private String longitude;

    public LocationAssistantListener(android.app.Activity activity, Context context) {
        assistant = new LocationAssistant(activity, context, this, LocationAssistant.Accuracy.HIGH, 5000, false);
        assistant.setVerbose(true);
        assistant.start();
    }

    @Override
    public void onNeedLocationPermission() {
        if (assistant != null) {
            assistant.requestAndPossiblyExplainLocationPermission();
        }
    }

    @Override
    public void onExplainLocationPermission() {
        android.util.Log.i("TrustLocation", "onExplainLocationPermission: ");
    }

    @Override
    public void onLocationPermissionPermanentlyDeclined(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        android.util.Log.i("TrustLocation", "onLocationPermissionPermanentlyDeclined: ");
    }

    @Override
    public void onNeedLocationSettingsChange() {
        android.util.Log.i("TrustLocation", "LocationSettingsStatusCodes.RESOLUTION_REQUIRED: Please Turn on GPS location service.");
    }

    @Override
    public void onFallBackToSystemSettings(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        android.util.Log.i("TrustLocation", "onFallBackToSystemSettings: ");
    }

    @Override
    public void onNewLocationAvailable(android.location.Location location) {
        if (location == null) return;
        latitude = String.valueOf(location.getLatitude());
        longitude = String.valueOf(location.getLongitude());
        isMockLocationsDetected = false;
    }

    @Override
    public void onMockLocationsDetected(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        isMockLocationsDetected = true;
    }

    @Override
    public void onError(LocationAssistant.ErrorType type, String message) {
        android.util.Log.i("TrustLocation", "Error: " + message);
    }

    public String getLatitude() {
        return latitude;
    }

    public String getLongitude() {
        return longitude;
    }

    public boolean isMockLocationsDetected() {
        return isMockLocationsDetected;
    }

    public LocationAssistant getAssistant() {
        return assistant;
    }
}
