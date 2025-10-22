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

        // Add activity result listener for location settings
        binding.addActivityResultListener((requestCode, resultCode, data) -> {
            if (locationAssistantListener != null && locationAssistantListener.getAssistant() != null) {
                locationAssistantListener.getAssistant().onActivityResult(requestCode, resultCode);
                return true;
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
            activityBinding.removeActivityResultListener((requestCode, resultCode, data) -> false);
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
                handleIsMockLocation(result);
                break;
            case "getLatitude":
                handleGetLatitude(result);
                break;
            case "getLongitude":
                handleGetLongitude(result);
                break;
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            case "start":
                handleStart(result);
                break;
            case "stop":
                handleStop(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleIsMockLocation(@NonNull Result result) {
        if (locationAssistantListener.isMockLocationsDetected()) {
            result.success(true);
        } else if (locationAssistantListener.getLatitude() != null && locationAssistantListener.getLongitude() != null) {
            result.success(false);
        } else {
            result.success(true);
        }
    }

    private void handleGetLatitude(@NonNull Result result) {
        String latitude = locationAssistantListener.getLatitude();
        if (latitude != null) {
            result.success(latitude);
        } else {
            result.success(null);
        }
    }

    private void handleGetLongitude(@NonNull Result result) {
        String longitude = locationAssistantListener.getLongitude();
        if (longitude != null) {
            result.success(longitude);
        } else {
            result.success(null);
        }
    }

    private void handleStart(@NonNull Result result) {
        if (locationAssistantListener.getAssistant() != null) {
            locationAssistantListener.getAssistant().start();
            result.success(true);
        } else {
            result.error("UNAVAILABLE", "Location assistant not available", null);
        }
    }

    private void handleStop(@NonNull Result result) {
        if (locationAssistantListener.getAssistant() != null) {
            locationAssistantListener.getAssistant().stop();
            result.success(true);
        } else {
            result.error("UNAVAILABLE", "Location assistant not available", null);
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
    }

    @Override
    public void onNeedLocationPermission() {
        if (assistant != null) {
            assistant.requestAndPossiblyExplainLocationPermission();
        }
        android.util.Log.i("TrustLocation", "onNeedLocationPermission: Permission required");
    }

    @Override
    public void onExplainLocationPermission() {
        android.util.Log.i("TrustLocation", "onExplainLocationPermission: Explain why permission is needed");
    }

    @Override
    public void onLocationPermissionPermanentlyDeclined(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        android.util.Log.i("TrustLocation", "onLocationPermissionPermanentlyDeclined: Permission permanently denied");
        // You could show a dialog here to guide user to app settings
        if (fromDialog != null) {
            fromDialog.onClick(null, DialogInterface.BUTTON_POSITIVE);
        }
    }

    @Override
    public void onNeedLocationSettingsChange() {
        android.util.Log.i("TrustLocation", "onNeedLocationSettingsChange: Location settings need to be changed");
        if (assistant != null) {
            assistant.changeLocationSettings();
        }
    }

    @Override
    public void onFallBackToSystemSettings(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        android.util.Log.i("TrustLocation", "onFallBackToSystemSettings: Need to open system settings");
        // Open system location settings
        if (fromView != null) {
            fromView.onClick(null);
        }
    }

    @Override
    public void onNewLocationAvailable(android.location.Location location) {
        if (location == null) return;
        latitude = String.valueOf(location.getLatitude());
        longitude = String.valueOf(location.getLongitude());
        isMockLocationsDetected = false;
        android.util.Log.i("TrustLocation", "onNewLocationAvailable: " + latitude + ", " + longitude);
    }

    @Override
    public void onMockLocationsDetected(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog) {
        isMockLocationsDetected = true;
        android.util.Log.w("TrustLocation", "onMockLocationsDetected: Mock location detected");
        // You could show a warning dialog here
        if (fromDialog != null) {
            fromDialog.onClick(null, DialogInterface.BUTTON_POSITIVE);
        }
    }

    @Override
    public void onError(LocationAssistant.ErrorType type, String message) {
        android.util.Log.e("TrustLocation", "onError: " + type + " - " + message);
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
