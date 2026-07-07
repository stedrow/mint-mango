package com.themoon.y1.managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;

/**
 * High-quality Gaussian blur using RenderScript, for the dynamic HD blurred background behind
 * album art. Creating and destroying a whole RenderScript context (plus the blur script) on
 * every call is very expensive -- create them once and reuse; only the per-bitmap Allocations
 * are transient. Extracted from MainActivity as the first step of an incremental God-Activity
 * breakup: this subsystem was already fully self-contained (its own fields, its own lock, one
 * public entry point).
 */
public class GaussianBlurManager {
    private static final String TAG = "GaussianBlurManager";
    private static GaussianBlurManager instance;

    private RenderScript blurRs;
    private ScriptIntrinsicBlur blurScript;
    // A single RenderScript context is not safe for concurrent use, and this is called from
    // both applyGaussianBlurAsync's thread and AudioPlayerManager's track-load worker -- so
    // serialize access. Blur is infrequent (per track / per background change), so a lock is fine.
    private final Object blurLock = new Object();

    private GaussianBlurManager() {}

    public static synchronized GaussianBlurManager getInstance() {
        if (instance == null) {
            instance = new GaussianBlurManager();
        }
        return instance;
    }

    public interface BlurResultCallback {
        void onBlurred(Bitmap blurred, Bitmap source);
    }

    public Bitmap applyGaussianBlur(Context context, Bitmap original) {
        if (original == null)
            return null;
        synchronized (blurLock) {
            Allocation inAlloc = null;
            Allocation outAlloc = null;
            try {
                if (blurRs == null) {
                    blurRs = RenderScript.create(context);
                    blurScript = ScriptIntrinsicBlur.create(blurRs, Element.U8_4(blurRs));
                    blurScript.setRadius(25f); // 💡 Blur intensity (0.0 ~ 25.0, 25 is max)
                }
                Bitmap output = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
                inAlloc = Allocation.createFromBitmap(blurRs, original, Allocation.MipmapControl.MIPMAP_NONE,
                        Allocation.USAGE_SCRIPT);
                outAlloc = Allocation.createFromBitmap(blurRs, output);

                blurScript.setInput(inAlloc);
                blurScript.forEach(outAlloc);
                outAlloc.copyTo(output);

                return output;
            } catch (Exception e) {
                Log.w(TAG, "applyGaussianBlur failed, returning source unblurred", e);
                return original;
            } finally {
                // Free the transient native allocations; keep the shared context/script alive.
                if (inAlloc != null) {
                    try {
                        inAlloc.destroy();
                    } catch (Exception e) {
                        Log.d(TAG, "inAlloc destroy failed", e);
                    }
                }
                if (outAlloc != null) {
                    try {
                        outAlloc.destroy();
                    } catch (Exception e) {
                        Log.d(TAG, "outAlloc destroy failed", e);
                    }
                }
            }
        }
    }

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // The RenderScript blur pass itself (not just decode) is real work on this hardware — running
    // it synchronously on every track change causes a visible hitch on skip/next/prev. Do the blur
    // off the caller's thread and hand the result back via callback on the main thread.
    public void applyGaussianBlurAsync(final Context context, final Bitmap source, final BlurResultCallback callback) {
        if (source == null) {
            callback.onBlurred(null, null);
            return;
        }
        new Thread(() -> {
            final Bitmap blurred = applyGaussianBlur(context, source);
            mainHandler.post(() -> callback.onBlurred(blurred, source));
        }).start();
    }

    /** Releases the shared RenderScript context. Call from the owning Activity's onDestroy(). */
    public void destroy() {
        synchronized (blurLock) {
            if (blurRs != null) {
                try {
                    blurRs.destroy();
                } catch (Exception e) {
                    Log.d(TAG, "blurRs destroy failed", e);
                }
                blurRs = null;
                blurScript = null;
            }
        }
    }
}
