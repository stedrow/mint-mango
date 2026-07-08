package com.themoon.y1.managers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;

import java.io.File;

/**
 * Downloads a launcher update APK with a live progress popup, then installs it (silent root
 * install with a manual-install fallback for non-rooted devices). Extracted verbatim from
 * MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- builds its progress popup straight out of MainActivity's Context
 * and theming helpers like every other UI-construction manager -- so it takes the MainActivity
 * instance as a parameter. MainActivity keeps thin pass-through methods for
 * downloadAndInstallApk() and installApk() since SettingsUiManager (the update checker) and
 * MusicBrowserManager (tapping a .apk file in the folder browser) call them by name.
 * MainActivity.TLSSocketFactory stays in MainActivity -- SettingsUiManager already references it
 * as MainActivity.TLSSocketFactory, so it's not part of this extraction's boundary.
 */
public class ApkInstallManager {
    private static final String TAG = "ApkInstallManager";
    private static ApkInstallManager instance;

    private ApkInstallManager() {}

    public static synchronized ApkInstallManager getInstance() {
        if (instance == null) {
            instance = new ApkInstallManager();
        }
        return instance;
    }

    // 💡 [Overhaul complete] Engine that shows download progress (%) and size (MB) in a live popup!
    public void downloadAndInstallApk(final MainActivity a, final String apkUrl) {
        // 🚀 1. Assemble the design of the 'download progress popup' shown on screen directly in Java code.
        final ProgressBar progressBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        final TextView tvProgress = new TextView(a);
        tvProgress.setGravity(android.view.Gravity.CENTER);
        tvProgress.setPadding(0, 30, 0, 0);
        tvProgress.setTextSize(16);
        tvProgress.setText(a.t("Connecting to server..."));

        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);
        layout.addView(progressBar);
        layout.addView(tvProgress);

        final AlertDialog progressDialog = new AlertDialog.Builder(a,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.t("Downloading Update"))
                .setView(layout)
                .setCancelable(false) // 💡 Lock the dialog so it can't be dismissed by tapping elsewhere during download!
                .create();

        progressDialog.show();

        // 🚀 2. Start the background download thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(apkUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                    // 🚀 [Required 1] Break through GitHub's security (TLS 1.2)
                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new MainActivity.TLSSocketFactory());
                        } catch (Exception e) {
                            Log.d(TAG, "downloadAndInstallApk failed", e);
                        }
                    }

                    conn.setInstanceFollowRedirects(false);

                    // 🚀 [Add here!!] Turn off Android's meddling automatic compression (GZIP)! (blocks fake size inflation at the source)
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setUseCaches(false);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    // 🚀 [Required 2] Chase the GitHub redirect (address forwarding) and grab the file!
                    int status = conn.getResponseCode();
                    if (status == 301 || status == 302 || status == 303) {
                        String newUrl = conn.getHeaderField("Location");
                        conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
                        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                            try {
                                ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new MainActivity.TLSSocketFactory());
                            } catch (Exception e) {
                                Log.d(TAG, "downloadAndInstallApk failed", e);
                            }
                        }

                        // 🚀 [Add here!!] Re-issue the no-compression command on the redirected real download URL too!
                        conn.setRequestProperty("Accept-Encoding", "identity");
                    }

                    conn.connect();

                    // Find out the total size of the file from the server.
                    final int fileLength = conn.getContentLength();

                    // 🚀 ⭕ [Overwrite with new code] Create an 'app-private internal vault' unaffected by the SD card.
                    File dir = a.getDir("update", Context.MODE_PRIVATE);
                    final File updateFile = new File(dir, "Y1_Launcher_Update.apk");

                    java.io.FileOutputStream fos = new java.io.FileOutputStream(updateFile);
                    java.io.InputStream is = conn.getInputStream();

                    byte[] buffer = new byte[4096]; // 💡 Quadrupled the buffer size for download speed.
                    int len;
                    long total = 0;

                    // Download the file piece by piece while pushing the percentage to the screen at the same time.
                    while ((len = is.read(buffer)) != -1) {
                        total += len;
                        fos.write(buffer, 0, len);

                        if (fileLength > 0) {
                            final int progress = (int) (total * 100 / fileLength);
                            final long downloadedMB = total / (1024 * 1024);
                            final long totalMB = fileLength / (1024 * 1024);

                            // Manipulating the UI must always be done on the main thread.
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progressBar.setProgress(progress);
                                    tvProgress.setText(progress + "%   (" + downloadedMB + "MB / " + totalMB + "MB)");
                                }
                            });
                        }
                    }
                    fos.close();
                    is.close();
                    // (earlier part omitted) Overwrite starting from the leftover-check section right after the loop ends

                    if (fileLength > 0 && total != fileLength) {
                        if (updateFile.exists())
                            updateFile.delete();
                        throw new Exception("Incomplete Download: Size Mismatch");
                    }

                    // 🚀 [Overwrite from here!!] When the download finishes, don't close the window immediately — display the size the server reported and the size we actually received on screen!
                    final String debugMessage = "Server told: " + fileLength + " bytes\nActually got: " + total
                            + " bytes";

                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 1. Fill the progress bar completely and show the key numbers we need to verify on screen.
                            progressBar.setProgress(100);
                            tvProgress.setText(a.t("Download Finished! Waiting 3 sec...\n\n") + debugMessage);
                            tvProgress.setTextColor(0xFF000000); // Eye-catching yellow!

                            // 2. Freeze the screen for exactly 3 seconds (3000ms), then close the popup and attempt installation.
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    progressDialog.dismiss();
                                    installApk(a, updateFile);
                                }
                            }, 3000);
                        }
                    });

                    // 👆 [End of overwrite here] 👆
                } catch (Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressDialog.dismiss();
                            Toast.makeText(a, a.t("Download failed. Check your internet connection."),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    public void installApk(MainActivity a, File apkFile) {
        try {
            // 1. Keep the existing permission opening (for installer access)
            try {
                Runtime.getRuntime().exec("chmod 777 " + apkFile.getParentFile().getAbsolutePath());
                Runtime.getRuntime().exec("chmod 777 " + apkFile.getAbsolutePath());
            } catch (Exception e) {
                Log.d(TAG, "installApk failed", e);
            }

            // 🚀 2. [Perfect solution: Silent Background Install]
            try {
                Process process = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(process.getOutputStream());

                // 🚀 [Core fix] Attach a redirect (> .. 2>&1) so the install result is saved to a text file (y1_update_log.txt) on the device!
                os.writeBytes("pm install -r " + apkFile.getAbsolutePath() + " > /storage/sdcard0/y1_update_log.txt 2>&1 \n");

                // 💡 Give the package manager a 3-second cooldown (rest) so it can finish installing.
                os.writeBytes("sleep 3\n");

                // Step 2: once the install completes, immediately relaunch the launcher (app) and return to the screen!
                os.writeBytes("am start -n " + a.getPackageName() + "/.MainActivity\n");

                os.writeBytes("exit\n");
                os.flush();
                os.close();
                process.waitFor();

                return;
            } catch (Exception e) {
                // Fall through to Plan B on a root-permission error
            }

            // 3. [Plan B] On non-rooted devices, show the traditional manual install screen as before
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri = Uri.parse("file://" + apkFile.getAbsolutePath());
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(intent);

        } catch (Exception e) {
            Toast.makeText(a, a.t("Install Failed."), Toast.LENGTH_SHORT).show();
        }
    }
}
