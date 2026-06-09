package net.pgaskin.batterytile;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.util.Objects;

public class BatteryTileService extends TileService {
    private static final int UPDATE_INTERVAL = 10000;
    private static final String DISPLAY_STATE_PREF = "display_state";
    private static final int DISPLAY_STATE_CURRENT = 0;
    private static final int DISPLAY_STATE_TEMP = 1;
    private static final int DISPLAY_STATE_TIME = 2;
    private static final int DISPLAY_STATE_PERCENT = 3;
    private static final int DISPLAY_STATE_COUNT = 4;

    private int displayState;

    private BatteryManager batteryManager;

    @Override
    public void onCreate() {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/os/BatteryManager;", "Lcom/android/internal/app/IBatteryStats;");
        handler = Handler.createAsync(getMainLooper());
        displayState = getPreferences().getInt(DISPLAY_STATE_PREF, 0);
        batteryManager = getSystemService(BatteryManager.class);
    }

    @Override
    public void onStartListening() {
        updateRunnable.run();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public void onStopListening() {
        unregisterReceiver(batteryReceiver);
        handler.removeCallbacks(updateRunnable);
    }

    @Override
    public void onClick() {
        displayState = (displayState + 1) % DISPLAY_STATE_COUNT;
        getPreferences().edit().putInt(DISPLAY_STATE_PREF, displayState).apply();
        onUpdateTile();
    }

    private SharedPreferences getPreferences() {
        return createDeviceProtectedStorageContext().getSharedPreferences("batterytile", Context.MODE_PRIVATE);
    }

    private int battDeciCelsius;
    private long battDeciCelsiusAt;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onUpdateTile();
            final long now = System.currentTimeMillis();
            battDeciCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            battDeciCelsiusAt = battDeciCelsius == Integer.MIN_VALUE ? 0 : now;
        }
    };

    private Handler handler;
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            onUpdateTile();
            if (handler != null) handler.postDelayed(updateRunnable, UPDATE_INTERVAL);
        }
    };

    private void onUpdateTile() {
        final Tile qsTile = getQsTile();
        final int pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        qsTile.setIcon(createTextIcon(pct >= 0 ? String.valueOf(pct) : "?"));
        qsTile.setState(Tile.STATE_INACTIVE);
        qsTile.setLabel("Battery");
        qsTile.setSubtitle(formatBatteryState());
        qsTile.updateTile();
    }

    private Icon createTextIcon(String text) {
        final float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(48 * density); // 48dp square canvas
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        );
        
        final float maxTextSize = size * 0.70f;
        paint.setTextSize(maxTextSize);

        // Shrink text a bit if needed so 3-digit values like "100" still fit well.
        final float width = paint.measureText(text);
        if (width > size * 0.78f) {
            paint.setTextSize(maxTextSize * (size * 0.78f / width));
        }

        final Paint.FontMetrics fm = paint.getFontMetrics();
        final float x = size / 2f;
        final float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, x, y, paint);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Icon.createWithBitmap(bitmap);
        }
        return Icon.createWithBitmap(bitmap);
    }

    private String formatBatteryState() {
        final StringBuilder state = new StringBuilder();
        switch (displayState % DISPLAY_STATE_COUNT) {
            case DISPLAY_STATE_CURRENT: {
                final int mA = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000;
                if (mA > 1000) {
                    state.append(mA / 1000);
                    state.append('.');
                    state.append(Math.abs(mA % 1000 + (100 / 2)) / 100);
                    state.append(" A");
                } else {
                    state.append(mA);
                    state.append(" mA");
                }
                break;
            }
            case DISPLAY_STATE_TEMP: {
                if (System.currentTimeMillis() - battDeciCelsiusAt < 5 * 60 * 1000) {
                    state.append(battDeciCelsius / 10);
                    state.append('.');
                    state.append(battDeciCelsius % 10);
                } else {
                    state.append('?');
                }
                state.append((char) (176));
                state.append('C');
                break;
            }
            case DISPLAY_STATE_TIME: {
                final long millis = getTimeRemaining(batteryManager);
                if (millis >= 0) {
                    final int mm = (int) ((millis / (1000 * 60)) % 60);
                    final int hh = (int) ((millis / (1000 * 60 * 60)) % 24);
                    final int dd = (int) ((millis / (1000 * 60 * 60 * 24)));
                    if (dd != 0) {
                        state.append(dd);
                        state.append('d');
                        state.append(' ');
                    }
                    if (dd != 0 || hh != 0) {
                        state.append(hh);
                        state.append('h');
                        state.append(' ');
                    }
                    if (dd != 0 || hh != 0 || mm != 0) {
                        state.append(mm);
                        state.append('m');
                    } else {
                        state.append('0');
                    }
                } else if (millis == -1) {
                    state.append("N/A");
                } else if (millis == -2) {
                    state.append("UNK");
                } else if (millis == -3) {
                    state.append("ERR");
                }
                break;
            }
            case DISPLAY_STATE_PERCENT: {
                final int pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                final int status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
                switch (status) {
                    default:
                        // fallthrough
                    case BatteryManager.BATTERY_STATUS_UNKNOWN:
                        state.append("??? ");
                        break;
                    case BatteryManager.BATTERY_STATUS_CHARGING:
                        state.append("CHR ");
                        break;
                    case BatteryManager.BATTERY_STATUS_DISCHARGING:
                        state.append("DIS ");
                        break;
                    case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                        state.append("--- ");
                        break;
                    case BatteryManager.BATTERY_STATUS_FULL:
                        state.append("FUL ");
                        break;
                }
                state.append(pct);
                state.append('%');
                break;
            }
        }
        return state.toString();
    }

    @SuppressLint("PrivateApi")
    private static long getTimeRemaining(BatteryManager bm) {
        try {
            //noinspection JavaReflectionMemberAccess
            @SuppressLint("SoonBlockedPrivateApi") final Field mBatteryStats = BatteryManager.class.getDeclaredField("mBatteryStats");
            mBatteryStats.setAccessible(true);

            final Object batteryStats = Objects.requireNonNull(mBatteryStats.get(bm), "BatteryManager.mBatteryStats is null");
            final Long batteryTimeRemaining = Objects.requireNonNull((Long) batteryStats.getClass().getMethod("computeBatteryTimeRemaining").invoke(batteryStats), "IBatteryStats.computeBatteryTimeRemaining() Long is null");
            final Long chargeTimeRemaining = Objects.requireNonNull((Long) batteryStats.getClass().getMethod("computeChargeTimeRemaining").invoke(batteryStats), "IBatteryStats.computeChargeTimeRemaining() Long is null");

            switch (bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
                case BatteryManager.BATTERY_STATUS_DISCHARGING:
                    return batteryTimeRemaining;
                case BatteryManager.BATTERY_STATUS_CHARGING:
                    return chargeTimeRemaining;
                case BatteryManager.BATTERY_STATUS_UNKNOWN:
                    return -2;
                default:
                    return -1;
            }
        } catch (Exception ex) {
            return -3;
        }
    }
}
