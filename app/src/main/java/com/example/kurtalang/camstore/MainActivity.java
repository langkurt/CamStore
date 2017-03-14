package com.example.kurtalang.camstore;

import android.Manifest;
import android.app.Activity;
import android.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.github.clans.fab.FloatingActionButton;
import com.github.clans.fab.FloatingActionMenu;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi.DriveContentsResult;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveFolder.DriveFileResult;
import com.google.android.gms.drive.DriveFolder.DriveFolderResult;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.MetadataChangeSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        NewFolderDialogFragment.NoticeDialogListener{

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final String TAG = "DEBUG CAMERA";
    private TextureView textureView;
    private FloatingActionButton takePictureButton;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Size imageDimension;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSessions;
    private String cameraId;
    private ImageReader imageReader;
    private String picStoreLocation = Environment.getExternalStorageDirectory() + "/Pictures/";
    private String m_Text = "";
    private File requestedFile;

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private int numCaptureBtns;
    private GoogleApiClient mGoogleApiClient;
    Map<String, String> captureLocations = new HashMap<String, String>();
    protected static final int REQUEST_CODE_RESOLUTION = 0;
    private static final int TAKE_PICTURE_REQUEST_CODE = 1;
    private static int FOLDER_TO_CREATE = 0;
    private static final int LOCAL_FOLDER = 0;
    private static final int DRIVE_FOLDER = 1;

    protected CameraDevice cameraDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        // Connect to google drive
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Drive.API)
                .addScope(Drive.SCOPE_FILE)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();


        // Create a new folder for captures
        FloatingActionButton addFolderButton = (FloatingActionButton) findViewById(R.id.add_folder);
        addFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFab(0);
            }
        });

        FloatingActionButton newDriveBtn = (FloatingActionButton) findViewById(R.id.new_drive_folder);
        newDriveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FOLDER_TO_CREATE = DRIVE_FOLDER;
                askFolderName();
            }
        });

        FloatingActionButton newLocalBtn = (FloatingActionButton) findViewById(R.id.new_local_folder);
        newLocalBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FOLDER_TO_CREATE = LOCAL_FOLDER;
                askFolderName();
            }
        });

        //FloatingActionMenu addMenuBtn = (FloatingActionMenu) this.findViewById(R.id.add_menu);

        // Get saved capture buttons and the locations they point to.
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        numCaptureBtns = sharedPref.getInt("numCaptureBtns", 1); // if numCaptureBtns isnt there, defaults to 1
        String loc;

        numCaptureBtns = 0; // Debugging initial state

        // Create all our capture buttons programmatically
        for (int i = 1; i <= numCaptureBtns; ++i) {
            loc = sharedPref.getString("btnLoc" + i, picStoreLocation); // default is picStoreLocation
            captureLocations.put("btnLoc" + i, loc);
            addFab(i);
        }

        // To write to pref file us this
        //SharedPreferences.Editor editor = sharedPref.edit();
        //editor.putInt("numCaptureBtns", numCaptureBtns);
        //editor.commit();

    }

    private void askFolderName () {
        // Create an instance of the dialog fragment and show it. Wait for callback
        DialogFragment dialog = new NewFolderDialogFragment();
        dialog.show(getFragmentManager(), "NewFolderDialogFragment");
    }

    private void addFab(final int id) {

        Log.e(TAG, "Calling addFab with id: " + id);

        // Programatically create a new button. Add it to hsv
        FloatingActionButton fab = new FloatingActionButton(MainActivity.this);
        FloatingActionMenu addMenuBtn = (FloatingActionMenu) this.findViewById(R.id.add_menu);
        RelativeLayout hsv = (RelativeLayout) findViewById(R.id.innerLay);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int lastBtn = hsv.getChildCount() - 1; // minus the 'add folder' button. Doesnt count

        if (id > 1) {
            fab.setId(id);
            lay.addRule(RelativeLayout.END_OF, id - 1);
        }
        else if (id == 0) {
            fab.setId(lastBtn + 1);
            lay.addRule(RelativeLayout.END_OF, lastBtn);
        }
        else {
            fab.setId(id);
            // relative to nothing cause its first one.
        }

        int dp_fab = (int) (getResources().getDimension(R.dimen.fab_caputure_margin) / getResources().getDisplayMetrics().density);
        int fab_menu = (int) (getResources().getDimension(R.dimen.fab_menu) / getResources().getDisplayMetrics().density);
        lay.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1);
        lay.setMargins(dp_fab, 0, dp_fab, fab_menu);

        fab.setLayoutParams(lay);
        fab.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(140,240,240)));
        fab.setButtonSize(FloatingActionButton.SIZE_MINI);
        fab.setLabelText("btn" + id);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("DEBUG", "Adding FAB");
                takePicture(id);
            }
        });

        hsv.addView(fab, lastBtn);

        // Set where the 'add folder' button should be
        RelativeLayout.LayoutParams menuLay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        menuLay.addRule(RelativeLayout.END_OF, lastBtn-1);
        menuLay.addRule(RelativeLayout.ALIGN_BOTTOM, 1); // hope this resolves to true
        addMenuBtn.setLayoutParams(menuLay);

    }

    public GoogleApiClient getGoogleApiClient() {
        return mGoogleApiClient;
    }

    public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDialogPositiveClick(DialogFragment dialog, String title){

        if (FOLDER_TO_CREATE == DRIVE_FOLDER) {
            MetadataChangeSet changeSet = new MetadataChangeSet.Builder().setTitle(title).build();
            Drive.DriveApi.getRootFolder(getGoogleApiClient()).createFolder(
                    getGoogleApiClient(), changeSet).setResultCallback(createFolderCallback);
        }

        if (FOLDER_TO_CREATE == LOCAL_FOLDER) {
            picStoreLocation = Environment.getExternalStorageDirectory() + "/Pictures/" + title + "/";
            File sddir = new File(picStoreLocation);
            if (!sddir.mkdirs()) {
                if (!sddir.exists()) {
                    Toast.makeText(MainActivity.this, "Folder error", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            RelativeLayout hsv = (RelativeLayout) findViewById(R.id.innerLay);
            int lastBtn = hsv.getChildCount(); // minus the 'add folder' button. Doesnt count
            captureLocations.put("btnLoc" + lastBtn, picStoreLocation);
            addFab(lastBtn);
        }

    }

    final ResultCallback<DriveFolderResult> createFolderCallback = new ResultCallback<DriveFolderResult>() {
        @Override
        public void onResult(DriveFolderResult result) {
            if (!result.getStatus().isSuccess()) {
                showMessage("Error while trying to create the folder");
                return;
            }
            showMessage("Created a folder: " + result.getDriveFolder());

            // Now create the button that places the pictures at this folder
            RelativeLayout hsv = (RelativeLayout) findViewById(R.id.innerLay);
            int lastBtn = hsv.getChildCount(); // minus the 'add folder' button. Doesnt count
            captureLocations.put("btnLoc" + lastBtn, "" + result.getDriveFolder().getDriveId());

            addFab(lastBtn);
        }
    };

    @Override
    public void onDialogNegativeClick(DialogFragment dialog) {

    }

    /* Camera Functions */
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener(){
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };
    protected void takePicture(int id) {
        if(null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }

        Log.e(TAG, "Taking picture with camera number: " + id);
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                jpegSizes = configs.getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            width =  1920;
            height = 1080;

            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);

            // Surfaces used for camera capture session
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            outputSurfaces.add(reader.getSurface());


            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            // Orientation
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());
            if (captureLocations.containsKey("btnLoc"+id)){
                picStoreLocation = captureLocations.get("btnLoc"+id);
            }

            final File file;
            if (!picStoreLocation.toLowerCase().contains(("" + Environment.getExternalStorageDirectory()).toLowerCase())) {
                // google file
                file = new File(Environment.getExternalStorageDirectory(), "temp.jpg");
            }
            else {
                file = new File(picStoreLocation + "CamStore_" + timeStamp + ".jpg");
            }
            //final File file = new File(getExternalCacheDir(), "temp.jpg");

            if (!isExternalStorageWritable()){
                Log.e(TAG, "External Storage not writable.");
                return;
            }
            else {
                Log.e(TAG, "External Storage is writable.");
            }

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);

                        if (!picStoreLocation.toLowerCase().contains(("" + Environment.getExternalStorageDirectory()).toLowerCase())) {
                            upload(file, picStoreLocation);
                        }

                    } catch (FileNotFoundException e) {
                        Log.e(TAG, "FileNotFoundException - Trying to write pic");
                        e.printStackTrace();
                    } catch (IOException e) {
                        Log.e(TAG, "IOException - Trying to write pic");
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
                private void save(byte[] bytes) throws IOException {
                    Log.e(TAG, "Saving file");
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };

            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };

            CameraCaptureSession.StateCallback captureCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "****\n Configure failed");
                }
            };


            cameraDevice.createCaptureSession(outputSurfaces, captureCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            int perm = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (perm == PackageManager.PERMISSION_DENIED){
                Log.e(TAG, "WRITE EXTERMAL Perm Denied!!!!\n Request that shit!");
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CAMERA_PERMISSION);
            }
            else {
                Log.e(TAG, "WRITE EXTERMAL Perm GRANTED!!!!");
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }
    protected void updatePreview() {
        if(null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }
    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        //closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // TODO app crashes if no internet with IllegalStateException
        Log.i(TAG, "GoogleApiClient connection failed: " + result.toString());
        if (!result.hasResolution()) {
            // show the localized error dialog.
            GoogleApiAvailability.getInstance().getErrorDialog(this, result.getErrorCode(), 0).show();
            return;
        }
        try {
            result.startResolutionForResult(this, REQUEST_CODE_RESOLUTION);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Exception while starting resolution activity", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_RESOLUTION:
                if (resultCode == Activity.RESULT_OK) {
                    Log.v(TAG, "onActivityResult: everything ok, connecting");
                    mGoogleApiClient.connect(); // try again with resolved problem
                    return;
                } else {
                    Log.v(TAG, "onActivityResult: user cancelled?");
                }
                break;
            default:
                // let super do its thing
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {}

    @Override
    public void onConnectionSuspended(int i) {}

    protected void upload(final File file, String picStoreLocation) {
        Log.v(TAG, "uploading " + file);
        new FileUploader(mGoogleApiClient, picStoreLocation) {
            @Override
            protected void onPostExecute(DriveFile result) {
                super.onPostExecute(result);
                if (result != null) {
                    Log.v(TAG, file + " to " + result.getDriveId());
                    Toast.makeText(getApplicationContext(), "Uploaded to Google Drive", Toast.LENGTH_LONG).show();
                    file.delete();
                } else {
                    Toast.makeText(getApplicationContext(), "Upload failed", Toast.LENGTH_LONG).show();
                }
                //finish(); // TODO notify user
            }
        }.execute(file);
    }

    private static class FileUploader extends AsyncTask<File, Void, DriveFile> {
        private final GoogleApiClient client;
        private final String picStoreLocation;
        private FileUploader(GoogleApiClient client, String picStoreLocation) {
            this.client = client;
            this.picStoreLocation = picStoreLocation;
        }

        @Override
        protected DriveFile doInBackground(File... params) {
            File file = params[0];
            DriveContentsResult result = Drive.DriveApi.newDriveContents(client).await();
            if (!result.getStatus().isSuccess()) {
                Log.e(TAG, "Cannot create contents");
                return null;
            }

            DriveContents contents = result.getDriveContents();
            try {
                copy(file, contents);
            } catch (IOException ex) {
                Log.e(TAG, "Cannot upload file", ex);
                return null;
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());

            DriveId driveId = DriveId.decodeFromString(picStoreLocation);
            DriveFolder folder = driveId.asDriveFolder();

            MetadataChangeSet metadata = new MetadataChangeSet.Builder()
                    .setTitle("CamStore_" + timeStamp + ".jpg")
                    .setMimeType("image/jpeg")
                    .build();

            //DriveFolder root = Drive.DriveApi.getRootFolder(client);
            DriveFileResult createResult = folder.createFile(client, metadata, contents).await();
            if (!createResult.getStatus().isSuccess()) {
                Log.e(TAG, "Cannot create file");
                return null;
            }
            return createResult.getDriveFile();
        }

        private static void copy(File source, DriveContents target) throws IOException {
            InputStream input = null;
            OutputStream output = null;
            try {
                input = new FileInputStream(source);
                output = target.getOutputStream();
                byte[] buffer = new byte[16 * 1014];
                int read = 0;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException ex) {
                throw ex;
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ex) {
                        Log.e(TAG, "Cannot close input", ex);
                    }
                }
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException ex) {
                        Log.e(TAG, "Cannot close output", ex);
                    }
                }
            }
        }
    }
}
