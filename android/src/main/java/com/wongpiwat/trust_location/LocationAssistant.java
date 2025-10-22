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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
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
        void onNeedLocationPermission();
        void onExplainLocationPermission();
        void onLocationPermissionPermanentlyDeclined(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);
        void onNeedLocationSettingsChange();
        void onFallBackToSystemSettings(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);
        void onNewLocationAvailable(Location location);
        void onMockLocationsDetected(View.OnClickListener fromView, DialogInterface.OnClickListener fromDialog);
        void onError(ErrorType type, String message);
    }

    public enum Accuracy {
        HIGH,
        MEDIUM,
        LOW,
        PASSIVE
    }

    public enum ErrorType {
        SETTINGS,
        RETRIEVAL
    }

    private final int REQUEST_CHECK_SETTINGS = 1001;
    private final int REQUEST_LOCATION_PERMISSION = 1002;

    private final Context context;
    private Activity activity;
    private Listener listener;
    private final int priority;
    private final long updateInterval;
    private final boolean allowMockLocations;
    private boolean verbose;
    private boolean quiet;

    private boolean permissionGranted;
    private boolean locationRequested;
    private boolean locationStatusOk;
    private boolean changeSettings;
    private boolean updatesRequested;
    private Location bestLocation;
    private boolean mockLocationsEnabled;
    private int numTimesPermissionDeclined;

    private FusedLocationProviderClient fusedLocationClient;
    private SettingsClient settingsClient;
    private LocationCallback locationCallback;

    private Location lastMockLocation;
    private int numGoodReadings;

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        settingsClient = LocationServices.getSettingsClient(context);
        
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
        };
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public void start() {
        checkMockLocations();
        acquireLocation();
    }

    public void stop() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        permissionGranted = false;
        locationRequested = false;
        locationStatusOk = false;
        updatesRequested = false;
    }

    public Location getBestLocation() {
        return bestLocation;
    }

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

    public void requestLocationPermission() {
        if (activity == null) {
            if (!quiet) Log.e(getClass().getSimpleName(), "Need location permission, but no activity is registered!");
            return;
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }

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
        // Simplified availability check - try to get last location
        if (!permissionGranted) return;
        
        try {
            fusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                @Override
                public void onComplete(@NonNull Task<Location> task) {
                    if (task.isSuccessful()) {
                        // If we can get last location, service is likely available
                        if (task.getResult() == null) {
                            // No last location - check providers
                            checkProviders();
                        }
                    } else {
                        // Error getting location - check providers
                        checkProviders();
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
