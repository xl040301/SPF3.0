
package com.android.server.pm;

import com.android.server.power.ShutdownThread;

import android.app.Dialog;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.ScaleAnimation;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.os.UserHandle;
import android.content.ActivityNotFoundException;
import android.view.View.OnLayoutChangeListener;
import android.view.Gravity;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.util.SparseIntArray;
import android.view.Window;

public class ShutDownDialog extends Dialog {

    private static final String TAG = "ShutDownDialog";
    private static final boolean DEBUG = true;


    private static final int BLUR_RADIUS = 3;
    private static final int SCALE_RATIO = 8;
    private static final int SCALE_DELTA = 1;
    private static final int MAX_VOLUME = 100;
    private static final int VIBRATE_MILLI = 300;
    private static final int VIBRATE_MILLI_SHORT = 100;
    private Context mDialogContext;
    private LinearLayout mRoot;
    private Button mPowerOffBtnContainer;
    private Button mRebootBtnContainer;
    private int mTextViewColorNormal;
    private int mTextViewColorPressd;
    private Bitmap mBackBitmap;
   // private AudioManager mAudioManager;
    //private Vibrator mVibrator;
    private IntentFilter mIntentFilter;
    private ScaleAnimation mScaleAnimation;
    private ScaleAnimation mShutdownAnimation;
    private ScaleAnimation mRebootAnimation;
    private ScaleAnimation mSafemodeAnimation;
    private AlphaAnimation mAlphaAnimation;

    View.OnClickListener mButtonClick = new View.OnClickListener() {
        public void onClick(View v) {
            int id = v.getId();
            switch (id) {
            case R.id.poweroff_container:
                mPowerOffBtnContainer.startAnimation(mShutdownAnimation);
                break;
            case R.id.reboot_container:
                mRebootBtnContainer.startAnimation(mRebootAnimation);
                break;
            
            default:
                mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                break;
            }
        }
    };
    
    
    View.OnLongClickListener mButtonLongClick = new View.OnLongClickListener() {
    	
		public boolean onLongClick(View v) {
			Log.i("majun","onLongClick & poweroff_container");
			int id = v.getId();
			switch (id) {
			case R.id.poweroff_container:
				mPowerOffBtnContainer.startAnimation(mSafemodeAnimation);
				return true;
			}

			return false;
		}
	};

    public ShutDownDialog(Context context) {
        this(context, com.android.internal.R.style.Theme_Translucent_NoTitleBar);
    }

    public ShutDownDialog(Context context, int theme) {
        super(context, theme);
        mDialogContext = context;
        
        Window window = getWindow();
        initAnims();

        setContentView(R.layout.shutdown_confirm_dialog);
        mRoot = (LinearLayout) this.findViewById(R.id.root);
        mPowerOffBtnContainer = (Button) this.findViewById(R.id.poweroff_container);
        mRebootBtnContainer = (Button) this.findViewById(R.id.reboot_container);
        mRoot.setOnClickListener(mButtonClick);
        mPowerOffBtnContainer.setOnClickListener(mButtonClick);
        mPowerOffBtnContainer.setOnLongClickListener(mButtonLongClick);
        mRebootBtnContainer.setOnClickListener(mButtonClick);
        mTextViewColorNormal = mDialogContext.getResources().getColor(R.color.poweroff_textview_color_normal);
        mTextViewColorPressd = mDialogContext.getResources().getColor(R.color.poweroff_textview_color_pressd);

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        mIntentFilter.addAction("com.android.homekey");
        // Dismiss self for incoming call.
        mIntentFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);

        mBackBitmap = getScreenshot(context, BLUR_RADIUS);
       // mBackBitmap = null;
        Drawable backgroundDrawable = null;
        if (mBackBitmap != null) {
            backgroundDrawable = new BitmapDrawable(mBackBitmap);
        } else {
            Log.i(TAG, "mBackBitmap is null.");
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(mDialogContext);
            backgroundDrawable = wallpaperManager.getDrawable();
        }
        if (backgroundDrawable != null) {
            window.getDecorView().setBackground(new LayerDrawable(new Drawable[]{backgroundDrawable,
                context.getResources().getDrawable(R.drawable.poweroff_background)}));
        }
        window.setWindowAnimations(R.style.Animation_GlobalActions);
    }

    @Override
    protected void onStart() {
        Log.i(TAG, "onStart()");
        mDialogContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "onStop()");
        mDialogContext.unregisterReceiver(mBroadcastReceiver);
    }
    
    private void initAnims() {
        mScaleAnimation = new ScaleAnimation(1.0f, 1.1f, 1.0f, 1.1f, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mScaleAnimation.setDuration(100);
        mScaleAnimation.setAnimationListener(animationListener);

        mShutdownAnimation = new ScaleAnimation(1.0f, 1.1f, 1.0f, 1.1f, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mShutdownAnimation.setDuration(100);
        mShutdownAnimation.setAnimationListener(animationListener);

        mRebootAnimation = new ScaleAnimation(1.0f, 1.1f, 1.0f, 1.1f, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mRebootAnimation.setDuration(100);
        mRebootAnimation.setAnimationListener(animationListener);
        
        mSafemodeAnimation = new ScaleAnimation(1.0f, 1.1f, 1.0f, 1.1f, Animation.RELATIVE_TO_SELF,
                0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mSafemodeAnimation.setDuration(100);
        mSafemodeAnimation.setAnimationListener(animationListener);
    }

    final AnimationListener animationListener = new AnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            Message msg = mHandler.obtainMessage();
            if (animation == mShutdownAnimation) {
                msg.what = MESSAGE_SHUTDOWN;
                mHandler.sendMessageDelayed(msg, MESSAGE_DELAY_TIME);
            } else if (animation == mRebootAnimation) {
                msg.what = MESSAGE_REBOOT;
                mHandler.sendMessageDelayed(msg, MESSAGE_DELAY_TIME);
            } else if (animation == mScaleAnimation) {
                msg.what = MESSAGE_DISMISS;
                mHandler.sendMessageDelayed(msg, MESSAGE_DELAY_TIME);
            } else if (animation == mSafemodeAnimation) {
            	msg.what = MESSAGE_SAFEMODE;
            	mHandler.sendMessageDelayed(msg, MESSAGE_DELAY_TIME);
            }
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onAnimationStart(Animation animation) {
            // TODO Auto-generated method stub
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action) || action.equals("com.android.homekey")) {
                // check it later ....
                /*
                 * String reason =
                 * intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY
                 * ); if (!
                 * PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS
                 * .equals(reason)) {
                 */
                mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                // }
            } /**else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
                    || Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MESSAGE_UPDATE_AUDIO_BUTTON);
            }**/ else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                // Dismiss self for incoming call.
                TelephonyManager tm = (TelephonyManager)mDialogContext.
                        getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) {
                    return;
                }
                int state = tm.getCallState();
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            }
        }
    };

    private static final int MESSAGE_DELAY_TIME = 200;
    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_SHUTDOWN= 1;
    private static final int MESSAGE_REBOOT = 2;
    private static final int MESSAGE_UPDATE_AUDIO_BUTTON = 3;
    private static final int MESSAGE_SAFEMODE = 4;
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_DISMISS) {
                dismiss();
            } else if (msg.what == MESSAGE_SHUTDOWN) {
               final boolean quickbootEnabled = Settings.System.getInt(
                    mDialogContext.getContentResolver(), "enable_quickboot", 0) == 1;
                // go to quickboot mode if enabled
               if (quickbootEnabled) {
                   startQuickBoot();
                } else {
                	ShutdownThread.shutdown(mDialogContext,"", false);
                	dismiss();
                }
            } else if (msg.what == MESSAGE_REBOOT) {
                ShutdownThread.reboot(mDialogContext, null, false);
            } else if (msg.what == MESSAGE_UPDATE_AUDIO_BUTTON) {
            } else if (msg.what == MESSAGE_SAFEMODE) {
              ShutdownThread.rebootSafeMode(mDialogContext, false);
            }
        }
    };

    private void playSound() {
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_RING, MAX_VOLUME);
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP);
    }

    public void onDetachedFromWindow() {
            Log.i(TAG, "onDetachedFromWindow().");
            if (mBackBitmap != null) {
                mBackBitmap.recycle();
                mBackBitmap = null;
            }
    }

    /**
     * @return the current display rotation in degrees
     */
    private float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 360f - 90f;
        case Surface.ROTATION_180:
            return 360f - 180f;
        case Surface.ROTATION_270:
            return 360f - 270f;
        }
        return 0f;
    }
    private Bitmap getScreenshot(Context context, int radius) {
        long start = System.currentTimeMillis();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        Matrix displayMatrix = new Matrix();
        display.getRealMetrics(displayMetrics);
        final int w = displayMetrics.widthPixels;
        final int h = displayMetrics.heightPixels;
        float[] dims = {w, h};
        float degrees = getDegreesForRotation(display.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            displayMatrix.reset();
            displayMatrix.preRotate(-degrees);
            displayMatrix.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }

        Bitmap bp = SurfaceControl.screenshot((int) dims[0], (int) dims[1]);
        if (bp == null) return null;

        //clip bitmap
        Bitmap ss = Bitmap.createBitmap((int) dims[0]/SCALE_RATIO, (int) dims[1]/SCALE_RATIO, Bitmap.Config.ARGB_8888);
        if (ss == null) return null;
        Canvas c = new Canvas(ss);
        c.clipRect(0, 0, dims[0] / SCALE_RATIO, dims[1] / SCALE_RATIO);
        Rect srcRect = new Rect(0, 0, bp.getWidth(), bp.getHeight());
        Rect dstRect = new Rect(0, 0, ss.getWidth() - SCALE_DELTA, ss.getHeight());
        c.drawBitmap(bp, srcRect, dstRect, null);
        c.setBitmap(null);
        bp.recycle();
        bp = ss;

        //rotate bitmap
        if (requiresRotation) {
            ss = Bitmap.createBitmap(w / SCALE_RATIO, h / SCALE_RATIO, Bitmap.Config.ARGB_8888);
            if (ss == null) return null;
            c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(degrees);
            c.translate(-dims[0] / (2 * SCALE_RATIO), -dims[1] / (2 * SCALE_RATIO));
            c.drawBitmap(bp, 0, 0, null);
            c.setBitmap(null);
            bp.recycle();
            bp = ss;
        }

        //blur bitmap
        Bitmap bitmap = bp.copy(bp.getConfig(), true);
        final RenderScript rs = RenderScript.create(context);
        final Allocation input = Allocation.createFromBitmap(rs, bp, Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT);
        final Allocation output = Allocation.createTyped(rs, input.getType());
        final ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setRadius(radius);
        script.setInput(input);
        script.forEach(output);
        output.copyTo(bitmap);

        long end = System.currentTimeMillis();
        Log.d(TAG, "Take time " + (end - start));
        return bitmap;
    }
    
     private void startQuickBoot() {

        Intent intent = new Intent("org.codeaurora.action.QUICKBOOT");
        intent.putExtra("mode", 0);
        try {
            mDialogContext.startActivityAsUser(intent,UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
        }
    }
    
    @Override
    public void show() {
        super.show();
        //majun fix:When the dialog  is displayed horizontally,
        // but the dialog  cannot display  full-screen
        Window window = getWindow();
        
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);//Integer.MIN_VALUE
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = WindowManager.LayoutParams.FILL_PARENT;
        lp.height = WindowManager.LayoutParams.FILL_PARENT;
        window.setAttributes(lp);
        //add end
    }

}
