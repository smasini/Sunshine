package app.nanodegree.masini.simone.sunshine;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;

import app.nanodegree.masini.simone.sunshine.data.WeatherContract;
import app.nanodegree.masini.simone.sunshine.data.WeatherDbHelper;

public class SunshineWearService extends WearableListenerService  {

    GoogleApiClient mGoogleApiClient;

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.TABLE_NAME + "." + WeatherContract.WeatherEntry._ID,
            WeatherContract.WeatherEntry.COLUMN_DATE,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.LocationEntry.COLUMN_COORD_LAT,
            WeatherContract.LocationEntry.COLUMN_COORD_LONG
    };

    private static final String sLocationSettingWithStartDateSelection =
            WeatherContract.LocationEntry.TABLE_NAME+
                    "." + WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING + " = ? AND " +
                    WeatherContract.WeatherEntry.COLUMN_DATE + " >= ? ";

    private static final String tableName = WeatherContract.WeatherEntry.TABLE_NAME + " INNER JOIN " +
            WeatherContract.LocationEntry.TABLE_NAME +
            " ON " + WeatherContract.WeatherEntry.TABLE_NAME +
            "." + WeatherContract.WeatherEntry.COLUMN_LOC_KEY +
            " = " + WeatherContract.LocationEntry.TABLE_NAME +
            "." + WeatherContract.LocationEntry._ID;

    static final int COL_WEATHER_MAX_TEMP = 3;
    static final int COL_WEATHER_MIN_TEMP = 4;
    static final int COL_WEATHER_CONDITION_ID = 6;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if(messageEvent.getPath().equals("/path/requestInfo")){
            String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";
            String locationSetting = Utility.getPreferredLocation(this);

            long normalizedDate = Utility.normalizeDate(System.currentTimeMillis());
            String[] selectionArgs = new String[]{locationSetting, Long.toString(normalizedDate)};

            WeatherDbHelper dbHelper = new WeatherDbHelper(this);
            Cursor c = dbHelper.getReadableDatabase().query(
                    tableName,
                    FORECAST_COLUMNS,
                    sLocationSettingWithStartDateSelection,
                    selectionArgs,
                    null,
                    null,
                    sortOrder);

            if(c!=null && c.moveToFirst()){
                double max = c.getInt(COL_WEATHER_MAX_TEMP);
                double min = c.getInt(COL_WEATHER_MIN_TEMP);
                String tMax = Utility.formatTemperature(this, max);
                String tMin = Utility.formatTemperature(this, min);
                int weatherId = c.getInt(COL_WEATHER_CONDITION_ID);
                c.close();
                sendData(tMax, tMin, weatherId);
            }
        }
    }

    public void sendData(String tempMax, String tempMin, int weatherId){
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/weather-info");

        putDataMapRequest.getDataMap().putString("temp-max", tempMax);
        putDataMapRequest.getDataMap().putString("temp-min", tempMin);

        Bitmap bitmap;
        if (Utility.usingLocalGraphics(this)) {
            bitmap = BitmapFactory.decodeResource(getResources(), Utility.getIconResourceForWeatherCondition(weatherId));
        }else{
            try {
                String urlIcon = Utility.getArtUrlForWeatherCondition(this, weatherId);
                URL url = new URL(urlIcon);
                bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
                bitmap = BitmapFactory.decodeResource(getResources(), Utility.getIconResourceForWeatherCondition(weatherId));
            }
        }
        Asset asset = createAssetFromBitmap(bitmap);
        putDataMapRequest.getDataMap().putAsset("icon", asset);

        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request);
    }


    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }


}
