package com.wongpiwat.trust_location;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

/**
 * A helper class that monitors the available location info on behalf of a requesting activity or application.
 */
public class LocationAssistant {
    /**
     * Delivers relevant events required to obtain (valid) location info.
     */
    public interface Listener {
        /**
         * Called when the user needs to grant the app location permission at run time.
         * This is only necessary on newer Android systems (API level >= 23).
         * If you want to show some explanation up front, do that, then call {@link #requestLocationPermission()}.
         * Alternatively, you can call {@link #requestAndPossiblyExplainLocationPermission()}, which will request the
         * location permission right away and invoke {@link #onExplainLocationPermission()} only if the user declines.
         * Both methods will bring up the system permission dialog.
         */
        void onNeedLocationPermission();

        /**
         * Called when the user has declined the location permission and might need a better explanation as to why
         * your app really depends on it.
         * You can show some sort of dialog or info window here and then - if the user is willing - ask again for
         * permission with {@link #requestLocationPermission()}.
         */
        void onExplainLocationPermission();

        /**
         * Called when the user has declined the location permission at least twice or has declined once and checked
         * "Don't ask again" (which will cause the system to permanently decline it).
         * You can show some sort of message that explains that the user will need to go to the app settings
         * to enable the permission. You may use the preconfigured OnClickListeners to send the user to the app
         * settings page.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the app settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the app settings
         */
        void onLocationPermissionPermanentlyDeclined(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);

        /**
         * Called when a change of the location provider settings is necessary.
         * You can optionally show some informative dialog and then request the settings change with
         * {@link #changeLocationSettings()}.
         */
        void onNeedLocationSettingsChange();

        /**
         * In certain cases where the user has switched off location providers, changing the location settings from
         * within the app may not work. The LocationAssistant will attempt to detect these cases and offer a redirect to
         * the system location settings, where the user may manually enable on location providers before returning to
         * the app.
         * You can prompt the user with an appropriate message (in a view or a dialog) and use one of the provided
         * OnClickListeners to jump to the settings.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the location settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the location settings
         */
        void onFallBackToSystemSettings(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);

        /**
         * Called when a new and valid location is available.
         * If you chose to reject mock locations, this method will only be called when a real location is available.
         *
         * @param location the current user location
         */
        void onNewLocationAvailable(Location location);

        /**
         * Called when the presence of mock locations was detected and {@link #allowMockLocations} is {@code false}.
         * You can use this callback to scold the user or do whatever. The user can usually disable mock locations by
         * either switching off a running mock location app (on newer Android systems) or by disabling mock location
         * apps altogether. The latter can be done in the phone's development settings. You may show an appropriate
         * message and then use one of the provided OnClickListeners to jump to those settings.
         *
         * @param fromView   OnClickListener to use with a view (e.g. a button), jumps to the development settings
         * @param fromDialog OnClickListener to use with a dialog, jumps to the development settings
         */
        void onMockLocationsDetected(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);

        /**
         * Called when an error has occurred.
         *
         * @param type    the type of error that occurred
         * @param message a plain-text message with optional details
         */
        void onError(ErrorType type, String message);
    }

    /**
     * Possible values for the desired location accuracy.
     */
    public enum Accuracy {
        /**
         * Highest possible accuracy, typically within 30m
         */
        HIGH,
        /**
         * Medium accuracy, typically within a city block / roughly 100m
         */
        MEDIUM,
        /**
         * City-level accuracy, typically within 10km
         */
        LOW,
        /**
         * Variable accuracy, purely dependent on updates requested by other apps
         */
        PASSIVE
    }

    public enum ErrorType {
        /**
         * An error with the user's location settings
         */
        SETTINGS,
        /**
         * An error with the retrieval of location info
         */
        RETRIEVAL
    }

    private final int REQUEST_CHECK_SETTINGS = 1001;
    private final int REQUEST_LOCATION_PERMISSION = 1002;

    // Parameters
    private final Context context;
    private Activity activity;
    private Listener listener;
    private final int priority;
    private final long updateInterval;
    private final boolean allowMockLocations;
    private boolean verbose;
    private boolean quiet;

    // Internal state
    private boolean permissionGranted;
    private boolean locationRequested;
    private boolean locationStatusOk;
    private boolean changeSettings;
    private boolean updatesRequested;
    private Location bestLocation;
    private boolean mockLocationsEnabled;
    private int numTimesPermissionDeclined;

    // Google Play Services clients
    private FusedLocationProviderClient fusedLocationClient;
    private SettingsClient settingsClient;
    private LocationCallback locationCallback;

    // Mock location rejection
    private Location lastMockLocation;
    private int numGoodReadings;

    /**
     * Constructs a LocationAssistant instance that will listen for valid location updates.
     */
    public LocationAssistant(Activity activity, Context context, Listener listener, Accuracy accuracy, 
                           long updateInterval, boolean allowMockLocations) {
        this.activity = activity;
        this.context = context;
        this.listener = listener;
        
        switch (accuracy) {
            case HIGH:
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY;
                break;
            case MEDIUM:
                priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
                break;
            case LOW:
                priority = LocationRequest.PRIORITY_LOW_POWER;
                break;
            case PASSIVE:
            default:
                priority = LocationRequest.PRIORITY_NO_POWER;
        }
        
        this.updateInterval = updateInterval;
        this.allowMockLocations = allowMockLocations;

        // Initialize Google Play Services clients
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        settingsClient = LocationServices.getSettingsClient(context);
        
        // Initialize location callback
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    onLocationChanged(location);
                }
            }
            
            @Override
            public void onLocationAvailability(LocationAvailability locationAvailability) {
                if (locationAvailability != null && !locationAvailability.isLocationAvailable()) {
                    if (listener != null) {
                        listener.onError(ErrorType.RETRIEVAL, "Location services became unavailable");
                    }
                }
            }
        };
    }

    /**
     * Makes the LocationAssistant print info log messages.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Mutes/unmutes all log output.
     */
    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Starts the LocationAssistant and makes it subscribe to valid location updates.
     */
    public void start() {
        checkMockLocations();
        acquireLocation();
    }

    /**
     * Stops the LocationAssistant and makes it unsubscribe from any location updates.
     */
    public void stop() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        permissionGranted = false;
        locationRequested = false;
        locationStatusOk = false;
        updatesRequested = false;
    }

    /**
     * Returns the best valid location currently available.
     */
    public Location getBestLocation() {
        return bestLocation;
    }

    /**
     * Request location permission with explanation if needed.
     */
    public void requestAndPossiblyExplainLocationPermission() {
        if (permissionGranted) return;
        if (activity == null) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Need location permission, but no activity is registered!");
            return;
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                && listener != null) {
            listener.onExplainLocationPermission();
        } else {
            requestLocationPermission();
        }
    }

    /**
     * Brings up a system dialog asking the user to give location permission to the app.
     */
    public void requestLocationPermission() {
        if (activity == null) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Need location permission, but no activity is registered!");
            return;
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }

    /**
     * Handle permission results.
     */
    public boolean onPermissionsUpdated(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_LOCATION_PERMISSION) return false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true;
            acquireLocation();
            return true;
        } else {
            numTimesPermissionDeclined++;
            if (!quiet) Log.i(getClass().getSimpleName(), "Location permission request denied.");
            if (numTimesPermissionDeclined >= 2 && listener != null) {
                listener.onLocationPermissionPermanentlyDeclined(onGoToAppSettingsFromView, onGoToAppSettingsFromDialog);
            }
            return false;
        }
    }

    /**
     * Handle activity results for location settings.
     */
    public void onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CHECK_SETTINGS) return;
        if (resultCode == Activity.RESULT_OK) {
            changeSettings = false;
            locationStatusOk = true;
            acquireLocation();
        } else {
            if (listener != null) {
                listener.onError(ErrorType.SETTINGS, "Location settings were not changed");
            }
        }
    }

    /**
     * Request change in location settings.
     */
    public void changeLocationSettings() {
        LocationRequest locationRequest = createLocationRequest();
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true);

        Task<LocationSettingsResponse> task = settingsClient.checkLocationSettings(builder.build());

        task.addOnSuccessListener(activity, new OnSuccessListener<LocationSettingsResponse>() {
            @Override
            public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                locationStatusOk = true;
                acquireLocation();
            }
        });

        task.addOnFailureListener(activity, new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                if (e instanceof ResolvableApiException) {
                    try {
                        ResolvableApiException resolvable = (ResolvableApiException) e;
                        resolvable.startResolutionForResult(activity, REQUEST_CHECK_SETTINGS);
                    } catch (IntentSender.SendIntentException sendEx) {
                        if (!quiet) Log.e(getClass().getSimpleName(), "Error starting resolution for location settings", sendEx);
                        if (listener != null) {
                            listener.onError(ErrorType.SETTINGS, "Could not resolve location settings");
                        }
                    }
                } else {
                    if (!quiet) Log.e(getClass().getSimpleName(), "Location settings check failed", e);
                    if (listener != null) {
                        listener.onError(ErrorType.SETTINGS, "Location settings check failed");
                    }
                }
            }
        });
    }

    private void acquireLocation() {
        if (!permissionGranted) checkLocationPermission();
        if (!permissionGranted) {
            if (numTimesPermissionDeclined >= 2) return;
            if (listener != null) {
                listener.onNeedLocationPermission();
            } else if (!quiet) {
                Log.e(getClass().getSimpleName(), "Need location permission, but no listener is registered!");
            }
            return;
        }

        if (!locationRequested) {
            requestLocation();
            return;
        }

        if (!locationStatusOk) {
            if (changeSettings) {
                if (listener != null) {
                    listener.onNeedLocationSettingsChange();
                }
            } else {
                checkProviders();
            }
            return;
        }

        if (!updatesRequested) {
            requestLocationUpdates();
            return;
        }

        checkLocationAvailability();
    }

    private void checkInitialLocation() {
        if (!permissionGranted) return;
        
        try {
            fusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                @Override
                public void onComplete(@NonNull Task<Location> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        onLocationChanged(task.getResult());
                    }
                }
            });
        } catch (SecurityException e) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Error while requesting last location", e);
            if (listener != null) {
                listener.onError(ErrorType.RETRIEVAL, "Could not retrieve initial location");
            }
        }
    }

    private void checkMockLocations() {
        mockLocationsEnabled = false;
    }

    private void checkLocationPermission() {
        permissionGranted = Build.VERSION.SDK_INT < 23 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }

    private LocationRequest createLocationRequest() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(priority);
        locationRequest.setInterval(updateInterval);
        locationRequest.setFastestInterval(updateInterval / 2);
        return locationRequest;
    }

    private void requestLocation() {
        LocationRequest locationRequest = createLocationRequest();
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true);

        settingsClient.checkLocationSettings(builder.build())
                .addOnSuccessListener(activity, new OnSuccessListener<LocationSettingsResponse>() {
                    @Override
                    public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                        locationRequested = true;
                        locationStatusOk = true;
                        checkInitialLocation();
                        acquireLocation();
                    }
                })
                .addOnFailureListener(activity, new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        locationRequested = true;
                        if (e instanceof ResolvableApiException) {
                            changeSettings = true;
                            locationStatusOk = false;
                        } else {
                            locationStatusOk = false;
                        }
                        acquireLocation();
                    }
                });
    }

    private void checkLocationAvailability() {
        if (!permissionGranted) return;
        
        try {
            fusedLocationClient.getLocationAvailability().addOnCompleteListener(new OnCompleteListener<LocationAvailability>() {
                @Override
                public void onComplete(@NonNull Task<LocationAvailability> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        boolean isAvailable = task.getResult().isLocationAvailable();
                        if (!isAvailable) {
                            checkProviders();
                        }
                    }
                }
            });
        } catch (SecurityException e) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Error checking location availability", e);
        }
    }

    private void checkProviders() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        boolean gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!gps && !network && listener != null) {
            listener.onFallBackToSystemSettings(onGoToLocationSettingsFromView, onGoToLocationSettingsFromDialog);
        }
    }

    private void requestLocationUpdates() {
        if (!permissionGranted || !locationRequested) return;
        
        try {
            LocationRequest locationRequest = createLocationRequest();
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            updatesRequested = true;
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            if (!quiet) Log.e(getClass().getSimpleName(), "Error requesting location updates", e);
                            if (listener != null) {
                                listener.onError(ErrorType.RETRIEVAL, "Could not request location updates");
                            }
                        }
                    });
        } catch (SecurityException e) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Security exception requesting location updates", e);
        }
    }

    private final DialogInterface.OnClickListener onGoToLocationSettingsFromDialog = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                activity.startActivity(intent);
            }
        }
    };

    private final View.OnClickListener onGoToLocationSettingsFromView = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                activity.startActivity(intent);
            }
        }
    };

    private final DialogInterface.OnClickListener onGoToDevSettingsFromDialog = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                activity.startActivity(intent);
            }
        }
    };

    private final View.OnClickListener onGoToDevSettingsFromView = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                activity.startActivity(intent);
            }
        }
    };

    private final DialogInterface.OnClickListener onGoToAppSettingsFromDialog = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                intent.setData(uri);
                activity.startActivity(intent);
            }
        }
    };

    private final View.OnClickListener onGoToAppSettingsFromView = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                intent.setData(uri);
                activity.startActivity(intent);
            }
        }
    };

    private boolean isLocationPlausible(Location location) {
        if (location == null) return false;

        boolean isMock = mockLocationsEnabled || (Build.VERSION.SDK_INT >= 18 && location.isFromMockProvider());
        if (isMock) {
            lastMockLocation = location;
            numGoodReadings = 0;
        } else {
            numGoodReadings = Math.min(numGoodReadings + 1, 1000000);
        }

        if (numGoodReadings >= 20) lastMockLocation = null;
        if (lastMockLocation == null) return true;

        double d = location.distanceTo(lastMockLocation);
        return (d > 1000);
    }

    private void onLocationChanged(Location location) {
        if (location == null) return;
        boolean plausible = isLocationPlausible(location);

        if (verbose && !quiet) {
            Log.i(getClass().getSimpleName(), location.toString() + (plausible ? " -> plausible" : " -> not plausible"));
        }

        if (!allowMockLocations && !plausible) {
            if (listener != null) {
                listener.onMockLocationsDetected(onGoToDevSettingsFromView, onGoToDevSettingsFromDialog);
            }
            return;
        }

        bestLocation = location;
        if (listener != null) {
            listener.onNewLocationAvailable(location);
        } else if (!quiet) {
            Log.w(getClass().getSimpleName(), "New location available but no listener registered");
        }
    }
}
