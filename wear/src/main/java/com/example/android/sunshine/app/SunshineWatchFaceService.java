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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

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

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener, ResultCallback<DataItemBuffer> {

        final String TAG = "SunshineWatchFaceEngine";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mLowPaint;
        Paint mHighPaint;
        SimpleDateFormat dateFormat;
        private Calendar mCalendar;
        int mTapCount;

        String forecastHigh = "";
        String forecastLow = "";
        Bitmap weatherIcon;

        float timeXOffset;
        float timeYOffset;
        float dateXOffset;
        float dateYOffset;
        float tempYOffset;
        float highXOffset;
        float lowXOffset;
        float iconXOffset;
        float iconYOffset;
        float lineY;

        private GoogleApiClient mGoogleApiClient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                dateFormat.setTimeZone(mCalendar.getTimeZone());
                invalidate();
            }
        };

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
                    .setAcceptsTapEvents(true)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .build();
            Wearable.DataApi.addListener(mGoogleApiClient, this);

            PendingResult<DataItemBuffer> pendingResult
                    = Wearable.DataApi.getDataItems(mGoogleApiClient, getUriForDataItem());
            pendingResult.setResultCallback(this);

            Resources resources = SunshineWatchFaceService.this.getResources();
            timeYOffset = resources.getDimension(R.dimen.time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.date_y_offset);

            float tempTextSize = resources.getDimension(R.dimen.temp_text_size);
            tempYOffset = resources.getDimension(R.dimen.temp_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(getColor(R.color.background));
            //default weather
//            Drawable backgroundDrawable = resources.getDrawable(R.drawable.ic_clear, null);
//            weatherIcon = ((BitmapDrawable) backgroundDrawable).getBitmap();

            mTimePaint = createTextPaint(getColor(R.color.primary_text));
            mDatePaint = createTextPaint(getColor(R.color.secondary_text));

            mCalendar = Calendar.getInstance();
            dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

            mHighPaint = new Paint(mTimePaint);
            mHighPaint.setTextSize(tempTextSize);
            mLowPaint = new Paint(mDatePaint);
            mLowPaint.setTextSize(tempTextSize-2);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                dateFormat.setTimeZone(mCalendar.getTimeZone());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
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
            timeXOffset = resources.getDimension(isRound
                    ? R.dimen.time_x_offset_round : R.dimen.time_x_offset);
            dateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            lowXOffset = resources.getDimension(isRound
                    ? R.dimen.low_x_offset_round : R.dimen.low_x_offset);
            highXOffset = resources.getDimension(isRound
                    ? R.dimen.high_x_offset_round : R.dimen.high_x_offset);
            iconXOffset = resources.getDimension(isRound
                    ? R.dimen.icon_x_offset_round : R.dimen.icon_x_offset);
            lineY = resources.getDimension(isRound
                    ? R.dimen.line_y_round : R.dimen.line_y);
            iconYOffset = resources.getDimension(isRound
                    ? R.dimen.icon_y_offset_round : R.dimen.icon_y_offset);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
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
            Log.d("ambient mode: ", inAmbientMode+"");

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mTimePaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mHighPaint.setAntiAlias(antiAlias);
                mLowPaint.setAntiAlias(antiAlias);
            }

            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String time = String.format("%tR", mCalendar);
            String date = dateFormat.format(mCalendar.getTime()).toUpperCase();

            canvas.drawText(time, timeXOffset, timeYOffset, mTimePaint);
            canvas.drawText(date, dateXOffset, dateYOffset, mDatePaint);

            canvas.drawLine(dateXOffset, lineY,
                    dateXOffset + mDatePaint.measureText(date), lineY, mDatePaint);

            canvas.drawText(forecastHigh, highXOffset, tempYOffset, mHighPaint);
            canvas.drawText(forecastLow, lowXOffset, tempYOffset, mLowPaint);
            if(weatherIcon != null){
                canvas.drawBitmap(weatherIcon, iconXOffset, iconYOffset, mDatePaint);
            }
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
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged: " + dataEventBuffer);

            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather-data") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        updateForecast(dataMap);
                    }
                }
            }
        }

        @Override
        public void onResult(@NonNull DataItemBuffer dataItemBuffer) {
            if(dataItemBuffer.getStatus().isSuccess()){
                for (DataItem item : dataItemBuffer) {
                    if (item.getUri().getPath().compareTo("/weather-data") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        updateForecast(dataMap);
                    }
                }
            }
            // clean up
            dataItemBuffer.release();
        }

        private void updateForecast(DataMap dataMap){
            forecastHigh = dataMap.getString("high");
            forecastLow = dataMap.getString("low");

            int iconId = Utility
                    .getIconResourceForWeatherCondition(dataMap.getInt("art_id"));
            Resources resources = SunshineWatchFaceService.this.getResources();
            Drawable backgroundDrawable = resources.getDrawable(iconId, null);
            weatherIcon = ((BitmapDrawable) backgroundDrawable).getBitmap();
        }

        private Uri getUriForDataItem() {
            return new Uri.Builder()
                    .scheme(PutDataRequest.WEAR_URI_SCHEME)
                    .path("/weather-data")
                    .build();
        }
    }
}
