/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.nanodegree.masini.simone.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import smasini.it.wear.BuildConfig;
import smasini.it.wear.R;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();

                initFormats();
                invalidate();
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mRegisteredTimeZoneReceiver = false;

        Date mDate;
        Date mDateLastSync;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;

        AsyncTask<String, Void, Bitmap> mAsyncTask;

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mTimePaint;
        Paint mLinesPaint;
        Paint mWeatherIconPaint;
        Paint mWeatherMaxPaint;
        Paint mWeatherMinPaint;

        Bitmap mIconBitmap;
        String mTempMax;
        String mTempMin;

        int mBitmapWidth;
        int mBitmapHeight;

        boolean mAmbient;

        Time mTime;

        float mXOffset;
        float mXOffsetWeather;
        float mYOffset;
        float mLineHeight;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTimePaint = createTextPaint(resources.getColor(R.color.white), BOLD_TYPEFACE);
            mDatePaint = createTextPaint(resources.getColor(R.color.grey_200));
            mLinesPaint = createTextPaint(resources.getColor(R.color.grey_200));

            mWeatherIconPaint = new Paint();
            mWeatherIconPaint.setAntiAlias(false);
            mWeatherIconPaint.setFilterBitmap(true);
            mWeatherMaxPaint = createTextPaint(resources.getColor(R.color.white), BOLD_TYPEFACE);
            mWeatherMinPaint = createTextPaint(resources.getColor(R.color.grey_200));

            mTime = new Time();
            mDate = new Date();
            initFormats();

            mTempMax = "--";
            mTempMin = "--";
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor){
            return createTextPaint(textColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                initFormats();

                if(mDateLastSync == null){
                    requestInfo();
                }else{

                    Time time = new Time();
                    time.setToNow();
                    long currentTime = System.currentTimeMillis();
                    int julianDay = Time.getJulianDay(mDateLastSync.getTime(), time.gmtoff);
                    int currentJulianDay = Time.getJulianDay(currentTime, time.gmtoff);

                    if(julianDay != currentJulianDay){
                        requestInfo();
                    }else if(BuildConfig.DEBUG){
                        //Log.d("SunshineWatchFace", "Message send only for debug");
                        //requestInfo();
                    }
                }
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }
            invalidate();
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE", Locale.getDefault());
            mDateFormat = DateFormat.getDateFormat(SunshineWatchFaceService.this);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mXOffsetWeather = resources.getDimension(isRound ? R.dimen.digital_x_offset_round_weather : R.dimen.digital_x_offset_weather);
            float textSizeTime = resources.getDimension(isRound ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textSizeDate = resources.getDimension(isRound ? R.dimen.digital_text_size_round_date : R.dimen.digital_text_size_date);
            float textSize = resources.getDimension(isRound ? R.dimen.digital_text_size_round_other : R.dimen.digital_text_size_other);

            mTimePaint.setTextSize(textSizeTime);
            mDatePaint.setTextSize(textSizeDate);
            mWeatherMaxPaint.setTextSize(textSize);
            mWeatherMinPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
            }
            if (mLowBitAmbient) {
                mTimePaint.setAntiAlias(!inAmbientMode);
                mDatePaint.setAntiAlias(!inAmbientMode);
                mLinesPaint.setAntiAlias(!inAmbientMode);
                mWeatherMaxPaint.setAntiAlias(!inAmbientMode);
                mWeatherMinPaint.setAntiAlias(!inAmbientMode);
            }
            invalidate();
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mDate.setTime(now);
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw HH:MM in ambient mode or HH:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%02d:%02d", mTime.hour, mTime.minute)
                    : String.format("%02d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, mXOffset, mYOffset, mTimePaint);

            String day = String.format("%s, %s", mDayOfWeekFormat.format(mDate), mDateFormat.format(mDate)).toUpperCase();

            float widthDay = mDatePaint.measureText(day);
            float dayX = (bounds.width() - widthDay) / 2;
            canvas.drawText(day, dayX, mYOffset + mLineHeight, mDatePaint);

            if (getPeekCardPosition().isEmpty()) {
                float x = mXOffsetWeather;
                float yStart = mYOffset + mLineHeight;
                float y = yStart + (mBitmapHeight / 2);

                if(mIconBitmap!=null) {
                    canvas.drawBitmap(mIconBitmap, x, y, mWeatherIconPaint);
                    x += mIconBitmap.getWidth() + 10;
                    y += mIconBitmap.getHeight()/2;
                }else {
                    x+= mBitmapWidth + 10;
                    y += mBitmapHeight/2;
                }
                canvas.drawText(mTempMax, x, y, mWeatherMaxPaint);
                x += mWeatherMaxPaint.measureText(mTempMax) + 10;
                canvas.drawText(mTempMin, x, y, mWeatherMinPaint);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            mBitmapHeight = height/4;
            mBitmapWidth = width/4;
            super.onSurfaceChanged(holder, format, width, height);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        public void requestInfo(){
            Log.d("Sunshine", "requestInfo");
            new AsyncTask<Void, Void, Void>(){
                @Override
                protected Void doInBackground(Void... params) {
                    NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                    byte[] bytes = "".getBytes();
                    for(Node node : nodes.getNodes()){
                        Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(), "/path/requestInfo", bytes);
                    }
                    return null;
                }
            }.execute();
        }


        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d("SunshineWatchFace", "onConnected: " + connectionHint);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d("SunshineWatchFace", "onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.d("SunshineWatchFace", "onConnectionFailed: " + result);
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result = mGoogleApiClient.blockingConnect(10000, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d("Sunshine", "onDataChanged");
            for(DataEvent dataEvent : dataEventBuffer){
                if(dataEvent.getType() == DataEvent.TYPE_CHANGED){
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if(path.equals("/weather-info")){
                        mTempMax = dataMap.getString("temp-max");
                        mTempMin = dataMap.getString("temp-min");
                        Asset asset = dataMap.getAsset("icon");
                        new AsyncTask<Asset,Void,Void>(){
                            @Override
                            protected Void doInBackground(Asset... params) {
                                Bitmap b = loadBitmapFromAsset(params[0]);
                                Matrix m = new Matrix();
                                m.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), new RectF(0, 0, mBitmapWidth, mBitmapHeight), Matrix.ScaleToFit.CENTER);
                                mIconBitmap = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                                mDateLastSync = new Date(System.currentTimeMillis());
                                invalidate();
                                updateTimer();
                                return null;
                            }
                        }.execute(asset);
                    }
                }
            }
        }
    }



    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

}
