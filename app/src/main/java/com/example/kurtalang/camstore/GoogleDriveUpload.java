package com.example.kurtalang.camstore;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi.DriveContentsResult;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveFolder.DriveFileResult;
import com.google.android.gms.drive.MetadataChangeSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by kurtalang on 3/12/17.
 */

public class GoogleDriveUpload extends Activity implements ConnectionCallbacks, OnConnectionFailedListener {
    private static final String TAG = "Drive";

    private static final int RESOLVE_CONNECTION_REQUEST_CODE = RESULT_FIRST_USER + 0;
    private static final int TAKE_PICTURE_REQUEST_CODE = RESULT_FIRST_USER + 1;

    private GoogleApiClient mClient;
    private File requestedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TODO init UI

        mClient = new GoogleApiClient.Builder(getApplicationContext()) //
                .addApi(Drive.API) //
                .addScope(Drive.SCOPE_FILE) //
                .addConnectionCallbacks(this) //
                .addOnConnectionFailedListener(this) //
                .build();
    }

    protected void takePictureWithIntent() {
        Log.v(TAG, "takePictureWithIntent");
        requestedFile = new File(getExternalCacheDir(), "temp.jpg");
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(requestedFile));
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, TAKE_PICTURE_REQUEST_CODE);
        }
    }

    protected void upload(final File file) {
        Log.v(TAG, "uploading " + file);
        new FileUploader(mClient) {
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
                finish(); // TODO notify user
            }
        }.execute(file);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart: connecting");
        mClient.connect(); // if it's in onStart the whole activity will be connected
    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop: disconnecting");
        mClient.disconnect();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RESOLVE_CONNECTION_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    Log.v(TAG, "onActivityResult: everything ok, connecting");
                    mClient.connect(); // try again with resolved problem
                    return;
                } else {
                    Log.v(TAG, "onActivityResult: user cancelled?");
                }
                break;
            case TAKE_PICTURE_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    Log.v(TAG, "onActivityResult: got an image");
                    upload(requestedFile);
                    return;
                } else {
                    Log.v(TAG, "onActivityResult: error taking picture");
                }
                break;
            default:
                // let super do its thing
                break;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    public void onConnectionFailed(ConnectionResult result) {
        if (!result.hasResolution()) {
            Log.v(TAG, "onConnectionFailed: cannot resolve: " + result);
            GooglePlayServicesUtil.getErrorDialog(result.getErrorCode(), this, 0).show();
            return;
        }
        try {
            Log.v(TAG, "onConnectionFailed: resolving: " + result);
            result.startResolutionForResult(this, RESOLVE_CONNECTION_REQUEST_CODE);
        } catch (SendIntentException e) {
            Log.e(TAG, "Exception while trying to resolve " + result, e);
        }
    }

    public void onConnected(Bundle connectionHint) {
        Log.v(TAG, "onConnected: yaay, from here on we can use the Drive API");
        if (requestedFile == null) {
            takePictureWithIntent(); // TODO call takePicture() from an onClick button event handler
        }
    }

    public void onConnectionSuspended(int reason) {
        Log.v(TAG, "onConnectionSuspended: don't touch Drive API any more");
    }

    private static class FileUploader extends AsyncTask<File, Void, DriveFile> {
        private final GoogleApiClient client;
        private FileUploader(GoogleApiClient client) {
            this.client = client;
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
            MetadataChangeSet metadata = new MetadataChangeSet.Builder() //
                    .setTitle("picture.jpg") //
                    .setMimeType("image/jpeg") //
                    .build();
            DriveFolder root = Drive.DriveApi.getRootFolder(client);
            DriveFileResult createResult = root.createFile(client, metadata, contents).await();
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

