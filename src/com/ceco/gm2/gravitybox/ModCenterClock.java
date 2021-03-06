package com.ceco.gm2.gravitybox;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LayoutInflated;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ModCenterClock {
    public static final String PACKAGE_NAME = "com.android.systemui";
    private static final String TAG = "ModCenterClock";
    private static final String CLASS_PHONE_STATUSBAR = "com.android.systemui.statusbar.phone.PhoneStatusBar";
    private static final String CLASS_TICKER = "com.android.systemui.statusbar.phone.PhoneStatusBar$MyTicker";

    private static ViewGroup mIconArea;
    private static ViewGroup mRootView;
    private static LinearLayout mLayoutClock;
    private static TextView mClock;
    private static Object mPhoneStatusBar;
    private static Context mContext;
    private static int mAnimPushUpOut;
    private static int mAnimPushDownIn;
    private static int mAnimFadeIn;
    private static boolean mClockCentered = false;
    private static int mClockOriginalPaddingLeft;
    private static boolean mClockShowDow = false;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            log("Broadcast received: " + intent.toString());
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_CENTER_CLOCK_CHANGED)) {
                setClockPosition(intent.getBooleanExtra(GravityBoxSettings.EXTRA_CENTER_CLOCK, false));
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_CLOCK_DOW)) {
                mClockShowDow = intent.getBooleanExtra(GravityBoxSettings.EXTRA_CLOCK_DOW, false);
                if (mClock != null) {
                    XposedHelpers.callMethod(mClock, "updateClock");
                }
            }
        }
    };

    public static void initResources(final XSharedPreferences prefs, final InitPackageResourcesParam resparam) {
        try {
            String layout = Utils.isMtkDevice() ? "gemini_super_status_bar" : "super_status_bar";
            resparam.res.hookLayout(PACKAGE_NAME, "layout", layout, new XC_LayoutInflated() {

                @Override
                public void handleLayoutInflated(LayoutInflatedParam liparam) throws Throwable {
                    prefs.reload();
                    mClockShowDow = prefs.getBoolean(GravityBoxSettings.PREF_KEY_STATUSBAR_CLOCK_DOW, false);
                    
                    mIconArea = (ViewGroup) liparam.view.findViewById(
                            liparam.res.getIdentifier("system_icon_area", "id", PACKAGE_NAME));
                    if (mIconArea == null) return;

                    mRootView = (ViewGroup) mIconArea.getParent().getParent();
                    if (mRootView == null) return;

                    mClock = (TextView) mIconArea.findViewById(
                            liparam.res.getIdentifier("clock", "id", PACKAGE_NAME));
                    if (mClock == null) return;
                    ModStatusbarColor.setClock(mClock);
                    // use this additional field to identify the instance of Clock that resides in status bar
                    XposedHelpers.setAdditionalInstanceField(mClock, "sbClock", true);
                    mClockOriginalPaddingLeft = mClock.getPaddingLeft();

                    // inject new clock layout
                    mLayoutClock = new LinearLayout(liparam.view.getContext());
                    mLayoutClock.setLayoutParams(new LinearLayout.LayoutParams(
                                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                    mLayoutClock.setGravity(Gravity.CENTER);
                    mLayoutClock.setVisibility(View.GONE);
                    mRootView.addView(mLayoutClock);
                    log("mLayoutClock injected");

                    XposedHelpers.findAndHookMethod(mClock.getClass(), "getSmallTime", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // is this a status bar Clock instance?
                            // yes, if it contains our additional sbClock field
                            Object sbClock = XposedHelpers.getAdditionalInstanceField(param.thisObject, "sbClock");
                            if (sbClock != null) {
                                Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
                                String clockText = param.getResult().toString();
                                String amPm = calendar.getDisplayName(
                                        Calendar.AM_PM, Calendar.SHORT, Locale.getDefault());
                                int amPmIndex = clockText.indexOf(amPm);
                                // insert AM/PM if missing
                                if (!DateFormat.is24HourFormat(mClock.getContext()) && amPmIndex == -1) {
                                    clockText += " " + amPm;
                                    amPmIndex = clockText.indexOf(amPm);
                                }
                                CharSequence dow = "";
                                if (mClockShowDow) {
                                    dow = calendar.getDisplayName(
                                            Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()) + " ";
                                }
                                clockText = dow + clockText;
                                SpannableStringBuilder sb = new SpannableStringBuilder(clockText);
                                sb.setSpan(new RelativeSizeSpan(0.7f), 0, dow.length(), 
                                        Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                                if (amPmIndex > -1) {
                                    int offset = Character.isWhitespace(clockText.charAt(dow.length() + amPmIndex - 1)) ?
                                            1 : 0;
                                    sb.setSpan(new RelativeSizeSpan(0.7f), dow.length() + amPmIndex - offset, 
                                            dow.length() + amPmIndex + amPm.length(), 
                                            Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                                }
                                param.setResult(sb);
                            }
                        }
                    });

                    setClockPosition(prefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_STATUSBAR_CENTER_CLOCK, false));
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            final Class<?> phoneStatusBarClass =
                    XposedHelpers.findClass(CLASS_PHONE_STATUSBAR, classLoader);
            final Class<?> tickerClass =
                    XposedHelpers.findClass(CLASS_TICKER, classLoader);

            final Class<?>[] loadAnimParamArgs = new Class<?>[2];
            loadAnimParamArgs[0] = int.class;
            loadAnimParamArgs[1] = Animation.AnimationListener.class;

            XposedHelpers.findAndHookMethod(phoneStatusBarClass, "makeStatusBarView", new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mPhoneStatusBar = param.thisObject;
                    mContext = (Context) XposedHelpers.getObjectField(mPhoneStatusBar, "mContext");
                    Resources res = mContext.getResources();
                    mAnimPushUpOut = res.getIdentifier("push_up_out", "anim", "android");
                    mAnimPushDownIn = res.getIdentifier("push_down_in", "anim", "android");
                    mAnimFadeIn = res.getIdentifier("fade_in", "anim", "android");

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_CENTER_CLOCK_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_CLOCK_DOW);
                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);
                }
            });

            XposedHelpers.findAndHookMethod(tickerClass, "tickerStarting", new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mLayoutClock == null || !mClockCentered) return;

                    mLayoutClock.setVisibility(View.GONE);
                    Animation anim = (Animation) XposedHelpers.callMethod(
                            mPhoneStatusBar, "loadAnim", loadAnimParamArgs, mAnimPushUpOut, null);
                    mLayoutClock.startAnimation(anim);
                }
            });

            XposedHelpers.findAndHookMethod(tickerClass, "tickerDone", new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mLayoutClock == null || !mClockCentered) return;

                    mLayoutClock.setVisibility(View.VISIBLE);
                    Animation anim = (Animation) XposedHelpers.callMethod(
                            mPhoneStatusBar, "loadAnim", loadAnimParamArgs, mAnimPushDownIn, null);
                    mLayoutClock.startAnimation(anim);
                }
            });

            XposedHelpers.findAndHookMethod(tickerClass, "tickerHalting", new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mLayoutClock == null || !mClockCentered) return;

                    mLayoutClock.setVisibility(View.VISIBLE);
                    Animation anim = (Animation) XposedHelpers.callMethod(
                            mPhoneStatusBar, "loadAnim", loadAnimParamArgs, mAnimFadeIn, null);
                    mLayoutClock.startAnimation(anim);
                }
            });
        }
        catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private static void setClockPosition(boolean center) {
        if (mClockCentered == center || mClock == null || 
                mIconArea == null || mLayoutClock == null) {
            return;
        }

        if (center) {
            mClock.setGravity(Gravity.CENTER);
            mClock.setLayoutParams(new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mClock.setPadding(0, 0, 0, 0);
            mIconArea.removeView(mClock);
            mLayoutClock.addView(mClock);
            mLayoutClock.setVisibility(View.VISIBLE);
            log("Clock set to center position");
        } else {
            mClock.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            mClock.setLayoutParams(new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            mClock.setPadding(mClockOriginalPaddingLeft, 0, 0, 0);
            mLayoutClock.removeView(mClock);
            mIconArea.addView(mClock);
            mLayoutClock.setVisibility(View.GONE);
            log("Clock set to normal position");
        }

        mClockCentered = center;
    }
}