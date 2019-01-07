package com.demo.jazz.landmarkapp;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.amazonaws.amplify.generated.graphql.CreateUserLandmarkMutation;
import com.amazonaws.amplify.generated.graphql.ListUserLandmarksQuery;
import com.amazonaws.mobile.config.AWSConfiguration;
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient;
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers;
import com.apollographql.apollo.GraphQLCall;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import type.CreateUserLandmarkInput;
import type.TableStringFilterInput;
import type.TableUserLandmarkFilterInput;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, GoogleMap.OnMapLongClickListener {

    private GoogleMap mMap;
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    private boolean mLocationPermissionGranted;

    private AWSAppSyncClient mAWSAppSyncClient;

    private FusedLocationProviderClient mFusedLocationProviderClient;
    private Location mLastKnownLocation;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Initialize FusedLocationProviderClient
        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        //Initialize AppSync Client
        mAWSAppSyncClient = AWSAppSyncClient.builder()
                .context(getApplicationContext())
                .awsConfiguration(new AWSConfiguration(getApplicationContext()))
                .build();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        getLocationPermission();
        updateLocationUI();
    }

    private void updateLocationUI() {
        if(mMap == null){
            return;
        }
        try{
            if (mLocationPermissionGranted) {
                mMap.setMyLocationEnabled(true);
                mMap.getUiSettings().setMyLocationButtonEnabled(true);
                mMap.getUiSettings().setZoomControlsEnabled(true);
                mMap.setOnMapLongClickListener(this);
                showCurrentLocation();
            } else {
                mMap.setMyLocationEnabled(false);
                mMap.getUiSettings().setMyLocationButtonEnabled(false);
                mMap.getUiSettings().setZoomControlsEnabled(false);
                mLastKnownLocation = null;
                getLocationPermission();
            }
        }catch (SecurityException e){
            show(e.getMessage());
        }
    }

    private void showCurrentLocation() {
        try {
            if(mLocationPermissionGranted){
                mFusedLocationProviderClient.getLastLocation().addOnCompleteListener(this, new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        if(task.isSuccessful()){
                            mLastKnownLocation = task.getResult();
                            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                    new LatLng(mLastKnownLocation.getLatitude(),
                                            mLastKnownLocation.getLongitude()), 15));
                        }else {
                            show("Current location is null. Using defaults.");
                            mMap.getUiSettings().setMyLocationButtonEnabled(false);
                        }
                    }
                });
            }
        }catch (SecurityException e){
            show(e.getMessage());
        }
    }

    private void getLocationPermission() {
        if (ContextCompat.checkSelfPermission(this.getApplicationContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mLocationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }
    }

    private void show(final String msg) {
        Log.w(this.getClass().getName(),msg);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(),msg,Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addMarker(final LatLng loc, final String email,final String snippet) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Marker marker = mMap.addMarker(new MarkerOptions()
                        .title(email)
                        .position(loc)
                        .snippet(snippet));
                marker.showInfoWindow();
            }
        });
    }

    private String getLoginEmail() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if(account == null){
            startActivity(new Intent(this,SigninActivity.class));
        }
        return account.getEmail();
    }

    @Override
    public void onMapLongClick(LatLng latLng) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter your landmark");
        final EditText input = new EditText(this);
        input.setTag(latLng);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);
        //Configure OK Button
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                LatLng lastLoc = (LatLng) input.getTag();
                mutation(lastLoc,getLoginEmail(),input.getText().toString());
            }
        });
        //Configure Cancel Button
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    private void mutation(LatLng location, String user, String comment) {
        CreateUserLandmarkInput input = CreateUserLandmarkInput.builder()
                .note(comment)
                .lat(location.latitude)
                .lng(location.longitude)
                .user(user)
                .id(UUID.randomUUID().toString())
                .build();

        mAWSAppSyncClient.mutate(CreateUserLandmarkMutation.builder().input(input).build())
                .enqueue(mutationCallback);
    }

    private GraphQLCall.Callback<CreateUserLandmarkMutation.Data> mutationCallback = new GraphQLCall.Callback<CreateUserLandmarkMutation.Data>() {
        @Override
        public void onResponse(@Nonnull Response<CreateUserLandmarkMutation.Data> response) {
            CreateUserLandmarkMutation.CreateUserLandmark data = response.data().createUserLandmark();
            addMarker(new LatLng(data.lat(),data.lng()),data.user(),data.note());
        }

        @Override
        public void onFailure(@Nonnull ApolloException e) {
            show(e.getMessage());
        }
    };

    public void onMapSearch(View v) {
        EditText searchInput = findViewById(R.id.editText_search);
        query(searchInput.getText().toString());
    }

    private void query(String comment){
        if(comment.isEmpty()){
            show("Please fill in your search query.");
            return;
        }
        TableUserLandmarkFilterInput filterInput = TableUserLandmarkFilterInput.builder()
                .note(TableStringFilterInput.builder().contains(comment).build())
                .build();
        mAWSAppSyncClient.query(ListUserLandmarksQuery.builder().filter(filterInput).build())
                .responseFetcher(AppSyncResponseFetchers.NETWORK_FIRST)
                .enqueue(queryCallback);
    }
    private GraphQLCall.Callback<ListUserLandmarksQuery.Data> queryCallback = new GraphQLCall.Callback<ListUserLandmarksQuery.Data>() {
        @Override
        public void onResponse(@Nonnull Response<ListUserLandmarksQuery.Data> response) {
            clearMarker();
            if(!response.data().listUserLandmarks().items().isEmpty()){
                for(ListUserLandmarksQuery.Item i: response.data().listUserLandmarks().items()){
                    addMarker(new LatLng(i.lat(),i.lng()),i.user(),i.note());
                }
                moveToMarkerCenter(response.data().listUserLandmarks().items());
            }else {
                show("Sorry please try again");
            }
        }

        @Override
        public void onFailure(@Nonnull ApolloException e) {
            show(e.getMessage());
        }
    };

    private void clearMarker() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMap.clear();
            }
        });
    }

    private void moveToMarkerCenter(final List<ListUserLandmarksQuery.Item> items) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(items.size() >1 ){
                    //Move camera view to the center of the bounds
                    LatLngBounds.Builder builder = new LatLngBounds.Builder();
                    for (ListUserLandmarksQuery.Item item : items){
                        builder.include(new LatLng(item.lat(),item.lng()));
                    }
                    LatLngBounds bounds = builder.build();
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,100));
                }else if(items.size() == 1){
                    //Move camera to the particular landmark
                    final CameraPosition cameraPosition = new CameraPosition.Builder()
                            .target(new LatLng(items.get(0).lat(),items.get(0).lng()))
                            .zoom(15)
                            .bearing(0)
                            .tilt(0)
                            .build();
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                }
            }
        });
    }
}
