package com.pipetown.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

final class CalibrationView extends View {
    interface Listener {
        void onDone(String code);

        void onExported(String code);
    }

    private static final int ASSET = 0;
    private static final int PIPE = 1;
    private static final int ICON = 2;
    private static final int TEXT = 3;
    private static final int BADGE = 4;
    private static final int SIDE_TUCK = 5;
    private static final int BOTTOM_TUCK = 6;
    private static final int MODE_LEVEL = 0;
    private static final int MODE_MENU = 1;
    private static final int MODE_PLANET = 2;
    private static final int AXIS_UNIFORM = 0;
    private static final int AXIS_WIDTH = 1;
    private static final int AXIS_HEIGHT = 2;

    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinkedHashMap<String, Bitmap> bitmaps = new LinkedHashMap<>();
    private final LinkedHashMap<String, Rect> opaqueBounds = new LinkedHashMap<>();
    private final ArrayList<Item> levelItems = new ArrayList<>();
    private final ArrayList<Item> menuItems = new ArrayList<>();
    private final ArrayList<Item> planetItems = new ArrayList<>();
    private final LinkedHashMap<String, Layer> tuning = new LinkedHashMap<>();
    private final RectF preview = new RectF();
    private final RectF[] partRects = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF[] buttons = new RectF[11];
    private final String[] labels = {"Done", "Prev", "Next", "View", "Layer", "Axis", "-", "+", "Reset", "Copy", "Defaults"};
    private final float density;

    private int mode;
    private int index;
    private int selectedPart;
    private int axisMode;
    private boolean dragging;
    private float dragX;
    private float dragY;
    private float levelReferenceCell;
    private String notice = "Done applies adjustments immediately.";
    private long noticeUntil;

    CalibrationView(Context context, CalibrationProfile profile, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        paint.setFilterBitmap(true);
        paint.setDither(true);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        buildItems();
        loadProfile(profile);
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = new RectF();
        }
    }

    void loadProfile(CalibrationProfile profile) {
        tuning.clear();
        putProfileLayer(profile, "G", "pipe", 'A');
        putProfileLayer(profile, "G", "pipe_side", 'A');
        putProfileLayer(profile, "G", "pipe_bottom", 'A');
        putProfileLayer(profile, "G", "pipe_icon", 'A');
        putProfileLayer(profile, "G", "source_badge", 'A');
        for (Item item : levelItems) {
            putProfileLayer(profile, "L", item.key, 'A');
        }
        for (Item item : menuItems) {
            putProfileLayer(profile, "M", item.key, 'A');
            if (item.text != null) {
                putProfileLayer(profile, "M", item.textKey, 'T');
            }
        }
        invalidate();
    }

    void resetSessionView() {
        mode = MODE_LEVEL;
        index = 0;
        selectedPart = ASSET;
        axisMode = AXIS_UNIFORM;
        notice = "1.00 x 1.00 is the current live runtime size.";
        noticeUntil = System.currentTimeMillis() + 4200L;
        invalidate();
    }

    void setLevelReferenceCell(float cell) {
        levelReferenceCell = cell >= dp(4) ? cell : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        Item item = currentItem();
        drawHeader(canvas, item);
        if (mode == MODE_LEVEL) {
            drawLevelPreview(canvas, item);
        } else if (mode == MODE_MENU) {
            drawMenuPreview(canvas, item);
        } else {
            drawPlanetPreview(canvas, item);
        }
        drawSelection(canvas);
        drawControls(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = 0; i < buttons.length; i++) {
                    if (buttons[i].contains(x, y)) {
                        runButton(i);
                        return true;
                    }
                }
                if (preview.contains(x, y)) {
                    int[] directSelection = {ICON, BADGE, TEXT, PIPE, ASSET};
                    for (int part : directSelection) {
                        if (partAvailable(currentItem(), part) && partRects[part].contains(x, y)) {
                            selectedPart = part;
                            break;
                        }
                    }
                    dragging = true;
                    dragX = x;
                    dragY = y;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    Layer layer = selectedLayer();
                    if (selectedPart == SIDE_TUCK) {
                        layer.x += (x - dragX) / density;
                    } else if (selectedPart == BOTTOM_TUCK) {
                        layer.y += (y - dragY) / density;
                    } else {
                        layer.x += (x - dragX) / density;
                        layer.y += (y - dragY) / density;
                    }
                    dragX = x;
                    dragY = y;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void runButton(int button) {
        switch (button) {
            case 0:
                listener.onDone(calibrationCode());
                return;
            case 1:
                index = (index - 1 + items().size()) % items().size();
                selectedPart = ASSET;
                break;
            case 2:
                index = (index + 1) % items().size();
                selectedPart = ASSET;
                break;
            case 3:
                mode = (mode + 1) % 2;
                index = 0;
                selectedPart = ASSET;
                break;
            case 4:
                selectedPart = nextPart(currentItem(), selectedPart);
                break;
            case 5:
                axisMode = (axisMode + 1) % 3;
                break;
            case 6:
                adjustScale(-0.04f);
                break;
            case 7:
                adjustScale(0.04f);
                break;
            case 8:
                tuning.put(storageKey(currentItem(), selectedPart), new Layer());
                break;
            case 9:
                copyCalibration();
                return;
            case 10:
                loadProfile(CalibrationProfile.fromCode(CalibrationProfile.DEFAULT_CODE));
                notice = "Restored current runtime baseline.";
                noticeUntil = System.currentTimeMillis() + 2800L;
                break;
            default:
                return;
        }
        invalidate();
    }

    private void adjustScale(float amount) {
        if (selectedPart == SIDE_TUCK || selectedPart == BOTTOM_TUCK) {
            notice = "Drag depth guides: side uses X, bottom uses Y.";
            noticeUntil = System.currentTimeMillis() + 2200L;
            return;
        }
        Layer layer = selectedLayer();
        if (axisMode == AXIS_UNIFORM || axisMode == AXIS_WIDTH) {
            layer.scaleX = clamp(layer.scaleX + amount, 0.20f, 3.00f);
        }
        if (axisMode == AXIS_UNIFORM || axisMode == AXIS_HEIGHT) {
            layer.scaleY = clamp(layer.scaleY + amount, 0.20f, 3.00f);
        }
    }

    private void drawBackground(Canvas canvas) {
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                new int[]{0xFF48A7E6, 0xFFE9F7FA, 0xFFF6EDD2},
                new float[]{0f, 0.37f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private void drawHeader(Canvas canvas, Item item) {
        textPaint.setColor(0xFF173D4B);
        textPaint.setTextSize(dp(20));
        canvas.drawText("PipeTown Calibration", getWidth() * 0.5f, dp(32), textPaint);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(0xFF365764);
        String screen = mode == MODE_LEVEL ? "LEVEL" : mode == MODE_MENU ? "MENU" : "PLANET";
        canvas.drawText(screen + "  " + (index + 1) + "/" + items().size() + "  " + item.label,
                getWidth() * 0.5f, dp(55), textPaint);
        Layer layer = selectedLayer();
        textPaint.setColor(0xFF8A4B2A);
        canvas.drawText(layerName(item, selectedPart) + "  W " + fmt(layer.scaleX) + "  H " + fmt(layer.scaleY)
                        + "  X " + fmt(layer.x) + "  Y " + fmt(layer.y),
                getWidth() * 0.5f, dp(76), textPaint);
    }

    private void drawLevelPreview(Canvas canvas, Item item) {
        preview.set(dp(12), dp(92), getWidth() - dp(12), getHeight() - dp(192));
        paint.setColor(0xD9FFF6D9);
        canvas.drawRoundRect(preview, dp(12), dp(12), paint);
        float cell = liveLevelCell();
        float gridLeft = preview.centerX() - cell * 10f;
        float gridTop = preview.centerY() - cell * 11f;
        paint.setColor(0x248C7758);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        for (float x = gridLeft; x < preview.right; x += cell) {
            canvas.drawLine(x, preview.top, x, preview.bottom, paint);
        }
        for (float y = gridTop; y < preview.bottom; y += cell) {
            canvas.drawLine(preview.left, y, preview.right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        int units = levelAssetUnits(item);
        RectF anchor = centered(preview.centerX(), preview.centerY() - dp(14), cell * units, cell * units);
        RectF assetBase = runtimeLevelAssetRect(item, anchor, cell, units);
        Layer assetTune = item.key.startsWith("source_")
                ? layer("G", "source_badge", 'A') : layer("L", item.key, 'A');
        RectF asset = drawPlacedLayerBitmap(canvas, item.asset, assetBase, assetTune, 255);
        partRects[ASSET].set(asset);
        if (item.dock != null) {
            drawCalibrationDock(canvas, item, anchor, cell, false);
            drawCalibrationDock(canvas, item, anchor, cell, true);
        } else {
            partRects[PIPE].setEmpty();
            partRects[ICON].setEmpty();
            partRects[SIDE_TUCK].setEmpty();
            partRects[BOTTOM_TUCK].setEmpty();
        }
        if (item.badge != null) {
            float badgeBaseSize = Math.min(asset.width(), asset.height()) * 0.34f;
            RectF badgeBase = centered(asset.centerX(), asset.centerY() - asset.height() * 0.10f, badgeBaseSize, badgeBaseSize);
            RectF badge = drawPlacedLayerBitmap(canvas, item.badge, badgeBase, layer("G", "source_badge", 'A'), 255);
            partRects[BADGE].set(badge);
        } else {
            partRects[BADGE].setEmpty();
        }
        partRects[TEXT].setEmpty();
    }

    private void drawCalibrationDock(Canvas canvas, Item item, RectF anchor, float cell, boolean bottom) {
        Layer pipeTune = layer("G", "pipe", 'A');
        Layer depth = layer("G", bottom ? "pipe_bottom" : "pipe_side", 'A');
        float tile = Math.min(Math.max(cell * 1.56f, dp(44)), dp(98));
        float inset = Math.max(cell * 0.24f, tile * 0.16f);
        float centerX = bottom ? anchor.centerX() : anchor.right + cell * 0.5f - inset + dp(depth.x);
        float centerY = bottom ? anchor.bottom + cell * 0.5f - inset + dp(depth.y) : anchor.bottom - cell * 0.5f;
        RectF pipe = runtimePipeRect(item.dock, centerX, centerY, tile, pipeTune);
        drawRotatedTrimmedBitmap(canvas, item.dock, pipe, bottom ? 90f : 0f, 255);
        if (!bottom) {
            partRects[PIPE].set(pipe);
            partRects[SIDE_TUCK].set(pipe);
        } else {
            partRects[BOTTOM_TUCK].set(pipe);
        }
        Layer iconTune = layer("G", "pipe_icon", 'A');
        float iconSide = tile * (item.dock.contains("connector") ? 0.62f : 0.66f);
        RectF iconBase = centered(centerX, centerY, iconSide, iconSide);
        float iconX = bottom ? -iconTune.y : iconTune.x;
        float iconY = bottom ? iconTune.x : iconTune.y;
        iconBase.offset(dp(iconX), dp(iconY));
        RectF icon = drawPlacedLayerBitmap(canvas, item.icon, iconBase,
                new Layer(iconTune.scaleX, iconTune.scaleY, 0f, 0f), 255);
        if (!bottom) {
            partRects[ICON].set(icon);
        }
    }

    private void drawMenuPreview(Canvas canvas, Item item) {
        preview.set(dp(12), dp(92), getWidth() - dp(12), getHeight() - dp(192));
        paint.setColor(0xFF82C7EB);
        canvas.drawRoundRect(preview, dp(12), dp(12), paint);
        paint.setColor(0xFF6EA84D);
        canvas.drawOval(preview.left - dp(35), preview.centerY() + dp(52), preview.right + dp(35), preview.bottom + dp(155), paint);
        RectF assetBase = runtimeMenuAssetRect(item);
        RectF asset = drawPlacedLayerBitmap(canvas, item.asset, assetBase, layer("M", item.key, 'A'), 255);
        partRects[ASSET].set(asset);
        clearLevelExtraRects();
        if (item.text != null) {
            Layer textLayer = layer("M", item.textKey, 'T');
            float baseX = asset.centerX();
            float baseY = asset.centerY() + dp(7);
            if ("heart_count".equals(item.textKey)) {
                baseX = asset.left + asset.width() * 0.13f;
            } else if ("hearts_text".equals(item.textKey)) {
                baseX = asset.left + asset.width() * 0.60f;
            }
            float x = baseX + dp(textLayer.x);
            float y = baseY + dp(textLayer.y);
            textPaint.setTextSize(dp(item.textSize) * textLayer.scaleY);
            textPaint.setColor(Color.WHITE);
            textPaint.setShadowLayer(dp(1), 0, dp(1), 0xFF42271B);
            canvas.drawText(item.text, x, y, textPaint);
            textPaint.clearShadowLayer();
            float width = textPaint.measureText(item.text) * textLayer.scaleX / Math.max(0.01f, textLayer.scaleY);
            partRects[TEXT].set(x - width * 0.55f, y - dp(item.textSize + 5) * textLayer.scaleY,
                    x + width * 0.55f, y + dp(7));
        } else {
            partRects[TEXT].setEmpty();
        }
    }

    private void drawPlanetPreview(Canvas canvas, Item item) {
        preview.set(dp(12), dp(92), getWidth() - dp(12), getHeight() - dp(192));
        paint.setColor(0xFF80C5EE);
        canvas.drawRoundRect(preview, dp(12), dp(12), paint);
        RectF globe = new RectF(preview.left - dp(45), preview.centerY() - dp(25),
                preview.right + dp(45), preview.bottom + dp(150));
        Layer hills = layer("P", "hills", 'A');
        paint.setShader(new RadialGradient(globe.centerX() - dp(44), globe.top + dp(55), globe.width() * 0.72f,
                new int[]{0xFF86CC55, 0xFF61A13E, 0xFF355D2C}, null, Shader.TileMode.CLAMP));
        canvas.drawOval(globe, paint);
        paint.setShader(null);
        float rise = dp(26) * hills.scaleX;
        float spread = dp(58) * hills.scaleY;
        paint.setColor(0x33519B39);
        for (int i = -1; i <= 2; i++) {
            float cx = globe.centerX() + i * (spread + dp(14)) + dp(hills.x * 0.25f);
            canvas.drawOval(cx - spread, globe.top + dp(50) - rise, cx + spread, globe.top + dp(50) + rise, paint);
        }
        clearLevelExtraRects();
        partRects[ASSET].set(globe);
        textPaint.setTextSize(dp(13));
        textPaint.setColor(0xFF234454);
        canvas.drawText("W height  H hill width  drag: spacing / rotation", preview.centerX(), preview.bottom - dp(18), textPaint);
    }

    private void drawSelection(Canvas canvas) {
        RectF rect = partRects[selectedPart];
        if (rect.isEmpty()) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFE34E68);
        RectF highlight = new RectF(rect);
        highlight.inset(-dp(5), -dp(5));
        canvas.drawRoundRect(highlight, dp(7), dp(7), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawControls(Canvas canvas) {
        float top = getHeight() - dp(176);
        paint.setColor(0xEE213745);
        canvas.drawRoundRect(dp(8), top, getWidth() - dp(8), getHeight() - dp(8), dp(10), dp(10), paint);
        int columns = 6;
        float gap = dp(5);
        float left = dp(13);
        float width = (getWidth() - dp(26) - gap * (columns - 1)) / columns;
        float height = dp(43);
        for (int i = 0; i < buttons.length; i++) {
            int row = i / columns;
            int col = i % columns;
            float x = left + col * (width + gap);
            float y = top + dp(8) + row * (height + gap);
            buttons[i].set(x, y, x + width, y + height);
            paint.setColor(i == 3 || i == 5 ? 0xFF426C6E : 0xFF385061);
            canvas.drawRoundRect(buttons[i], dp(6), dp(6), paint);
            textPaint.setTextSize(dp(11));
            textPaint.setColor(Color.WHITE);
            String label = labels[i];
            if (i == 3) {
                label = mode == MODE_LEVEL ? "Menu" : mode == MODE_MENU ? "Planet" : "Level";
            } else if (i == 4) {
                label = layerShortName(currentItem(), selectedPart);
            } else if (i == 5) {
                label = axisMode == AXIS_UNIFORM ? "Both" : axisMode == AXIS_WIDTH ? "Width" : "Height";
            }
            canvas.drawText(label, buttons[i].centerX(), buttons[i].centerY() + dp(4), textPaint);
        }
        textPaint.setTextSize(dp(11));
        textPaint.setColor(0xFFE4F0F3);
        String message = noticeUntil > System.currentTimeMillis() ? notice : "Drag to place. Axis chooses width, height, or both for -/+.";
        canvas.drawText(message, getWidth() * 0.5f, getHeight() - dp(16), textPaint);
    }

    private float liveLevelCell() {
        if (levelReferenceCell > 0f) {
            return levelReferenceCell;
        }
        float availableW = Math.max(dp(1), getWidth() - dp(20));
        float availableH = Math.max(dp(1), getHeight() - dp(76) - dp(18 + 53));
        return Math.min(availableW / 22f, availableH / 34f);
    }

    private int levelAssetUnits(Item item) {
        if (item.key.startsWith("source_")) {
            return 2;
        }
        int marker = item.key.lastIndexOf('x');
        if (marker > 0 && marker + 1 < item.key.length()) {
            int left = Character.digit(item.key.charAt(marker - 1), 10);
            int right = Character.digit(item.key.charAt(marker + 1), 10);
            if (left > 0 && right > 0) {
                return Math.max(left, right);
            }
        }
        return 1;
    }

    private RectF runtimeLevelAssetRect(Item item, RectF anchor, float cell, int units) {
        float visualUnit = Math.max(cell, dp(34));
        float dimensionScale = units <= 1 ? 1f : 1f + (float) Math.sqrt(units - 1) * 0.62f;
        float runtimeScale = item.key.startsWith("house_") ? 1.24f : item.key.startsWith("source_") ? 0.94f : 1f;
        float side = Math.max(visualUnit * dimensionScale * runtimeScale, visualUnit * 0.8f);
        side = Math.min(side, Math.min(getWidth() * 0.42f, dp(300)));
        Bitmap bitmap = bitmap(item.asset);
        float ratio = bitmap == null || bitmap.getHeight() == 0 ? 1f : bitmap.getWidth() / (float) bitmap.getHeight();
        float width = ratio >= 1f ? side : side * ratio;
        float height = ratio >= 1f ? side / ratio : side;
        float bottom = anchor.bottom + cell * 0.08f;
        return new RectF(anchor.centerX() - width * 0.5f, bottom - height,
                anchor.centerX() + width * 0.5f, bottom);
    }

    private RectF runtimePipeRect(String asset, float centerX, float centerY, float tile, Layer tune) {
        Bitmap bitmap = bitmap(asset);
        Rect visible = bitmap == null ? null : opaqueBounds(asset, bitmap);
        float ratio = visible == null || visible.height() == 0 ? 1f : visible.width() / (float) visible.height();
        float width = ratio >= 1f ? tile : tile * ratio;
        float height = ratio >= 1f ? tile / ratio : tile;
        return centered(centerX, centerY, width * tune.scaleX, height * tune.scaleY);
    }

    private RectF runtimeMenuAssetRect(Item item) {
        float width;
        float height;
        switch (item.key) {
            case "logo":
                width = dp(186);
                height = dp(82);
                break;
            case "hearts":
                width = dp(236);
                height = dp(62);
                break;
            case "sound_on":
            case "sound_off":
                width = height = dp(54);
                break;
            case "go_level_1":
            case "go_unlocked":
                width = height = dp(72);
                break;
            case "complete":
                width = Math.min(getWidth() * 0.72f, dp(330));
                height = width * 0.75f;
                break;
            case "level":
                width = height = dp(82);
                break;
            default:
                float button = Math.min(dp(54), (getWidth() - dp(20) - dp(24)) / 5f);
                width = height = button - dp(6);
                break;
        }
        return centered(preview.centerX(), preview.centerY(), width, height);
    }

    private RectF drawPlacedLayerBitmap(Canvas canvas, String asset, RectF base, Layer layer, int alpha) {
        Bitmap bitmap = bitmap(asset);
        RectF output = centered(base.centerX() + dp(layer.x), base.centerY() + dp(layer.y),
                base.width() * layer.scaleX, base.height() * layer.scaleY);
        if (bitmap != null) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, null, output, paint);
            paint.setAlpha(255);
        }
        return output;
    }

    private void drawRotatedTrimmedBitmap(Canvas canvas, String asset, RectF dest, float degrees, int alpha) {
        Bitmap bitmap = bitmap(asset);
        if (bitmap == null) {
            return;
        }
        canvas.save();
        canvas.rotate(degrees, dest.centerX(), dest.centerY());
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, opaqueBounds(asset, bitmap), dest, paint);
        paint.setAlpha(255);
        canvas.restore();
    }

    private RectF drawLayerBitmap(Canvas canvas, String asset, RectF base, Layer layer, int alpha) {
        Bitmap bitmap = bitmap(asset);
        float baseWidth = base.width();
        float baseHeight = base.height();
        Rect visible = bitmap == null ? null : opaqueBounds(asset, bitmap);
        if (visible != null && visible.height() > 0) {
            float ratio = visible.width() / (float) visible.height();
            if (ratio > 1f) {
                baseHeight = baseWidth / ratio;
            } else {
                baseWidth = baseHeight * ratio;
            }
        }
        float width = baseWidth * layer.scaleX;
        float height = baseHeight * layer.scaleY;
        RectF output = centered(base.centerX() + dp(layer.x), base.centerY() + dp(layer.y), width, height);
        if (bitmap != null) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, visible, output, paint);
            paint.setAlpha(255);
        }
        return output;
    }

    private void copyCalibration() {
        String code = calibrationCode();
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PipeTown calibration", code));
        }
        notice = "PTCAL3 code copied.";
        noticeUntil = System.currentTimeMillis() + 3200L;
        listener.onExported(code);
        invalidate();
    }

    private String calibrationCode() {
        StringBuilder code = new StringBuilder(CalibrationProfile.VERSION);
        for (String key : tuning.keySet()) {
            Layer layer = tuning.get(key);
            code.append('|').append(key).append('=')
                    .append(String.format(Locale.US, "%.2f,%.2f,%.1f,%.1f",
                            layer.scaleX, layer.scaleY, layer.x, layer.y));
        }
        return code.toString();
    }

    private void buildItems() {
        addLevel("house_1x1", "house_1x1", "art/houses/house_1x1.png", null, null, null, 94, false);
        addLevel("house_2x2", "house_2x2", "art/houses/house_2x2.png", null, null, null, 130, false);
        addLevel("house_4x4", "house_4x4", "art/houses/house 4x4.png", null, null, null, 184, false);
        addLevel("house_5x5", "house_5x5", "art/houses/house_5x5.png", null, null, null, 208, false);
        addSource("water");
        addSource("electric");
        addSource("gas");
        addSource("heating");
        addSource("internet");
        addSource("sewage");
        String[] blockers = {"construction_1x1", "construction_1x2", "construction_1x3",
                "pond_1x1", "pond_2x2", "pond_2x3", "stone_1x1", "stone_1x3",
                "stone_2x2", "tree_1x1", "tree_1x2", "tree_1x3"};
        for (String blocker : blockers) {
            addLevel(blocker, blocker, "art/blockers/" + blocker + ".png", null, null, null, 138, false);
        }
        addMenu("logo", "logo", "art/logo/logo.png", null, null, 220, 24);
        addMenu("sound_on", "sound on", "art/icons/sound_on.png", null, null, 116, 24);
        addMenu("sound_off", "sound off", "art/icons/sound_off.png", null, null, 116, 24);
        addMenu("go_level_1", "go level 1", "art/icons/go_level_1.png", null, null, 132, 24);
        addMenu("go_unlocked", "go unlocked", "art/icons/go_level_unlocked.png", null, null, 132, 24);
        String[] tools = {"map", "reset", "finish_level", "revert", "hint", "settings"};
        for (String tool : tools) {
            addMenu(tool, tool, "art/icons/" + ("map".equals(tool) ? "world_map" : tool) + ".png", null, null, 112, 24);
        }
    }

    private void addSource(String utility) {
        addLevel("source_" + utility, "source_" + utility, "art/source_icons/" + utility + ".png",
                null, null, null, 132, false);
    }

    private void addLevel(String key, String label, String asset, String dock, String icon, String badge, float size, boolean bottom) {
        levelItems.add(new Item("L", key, label, asset, dock, icon, badge, size, 0, bottom));
    }

    private void addMenu(String key, String label, String asset, String textKey, String text, float size, float textSize) {
        menuItems.add(new Item("M", key, label, asset, null, null, text, size, textSize, false, textKey));
    }

    private ArrayList<Item> items() {
        return mode == MODE_LEVEL ? levelItems : mode == MODE_MENU ? menuItems : planetItems;
    }

    private Item currentItem() {
        ArrayList<Item> current = items();
        return current.get(Math.min(index, current.size() - 1));
    }

    private Layer selectedLayer() {
        return layerForKey(storageKey(currentItem(), selectedPart));
    }

    private Layer layer(String scope, String key, char part) {
        return layerForKey(scope + ":" + key + ":" + part);
    }

    private Layer layerForKey(String key) {
        Layer value = tuning.get(key);
        if (value == null) {
            value = new Layer();
            tuning.put(key, value);
        }
        return value;
    }

    private void putProfileLayer(CalibrationProfile profile, String scope, String key, char part) {
        CalibrationProfile.Layer source = profile.layer(scope, key, part);
        tuning.put(scope + ":" + key + ":" + part,
                new Layer(source.scaleX, source.scaleY, source.x, source.y));
    }

    private String storageKey(Item item, int part) {
        if (mode == MODE_PLANET) {
            return "P:" + item.key + ":A";
        }
        if (part == PIPE) {
            return "G:pipe:A";
        }
        if (part == ICON) {
            return "G:pipe_icon:A";
        }
        if (part == BADGE) {
            return "G:source_badge:A";
        }
        if (part == SIDE_TUCK) {
            return "G:pipe_side:A";
        }
        if (part == BOTTOM_TUCK) {
            return "G:pipe_bottom:A";
        }
        if (part == TEXT) {
            return "M:" + item.textKey + ":T";
        }
        if ("L".equals(item.scope) && item.key.startsWith("source_")) {
            return "G:source_badge:A";
        }
        return item.scope + ":" + item.key + ":A";
    }

    private boolean partAvailable(Item item, int part) {
        if (mode == MODE_PLANET) {
            return part == ASSET;
        }
        return part == ASSET || (part == PIPE && item.dock != null)
                || (part == ICON && item.icon != null) || (part == BADGE && item.badge != null)
                || (part == SIDE_TUCK && item.dock != null) || (part == BOTTOM_TUCK && item.dock != null)
                || (part == TEXT && item.text != null);
    }

    private int nextPart(Item item, int part) {
        for (int step = 1; step <= partRects.length; step++) {
            int next = (part + step) % partRects.length;
            if (partAvailable(item, next)) {
                return next;
            }
        }
        return ASSET;
    }

    private String layerName(Item item, int part) {
        if (mode == MODE_PLANET) {
            return "decor".equals(item.key) ? "Decor controls" : "Hill controls";
        }
        if (part == PIPE) {
            return "Shared pipe size";
        }
        if (part == ICON) {
            return "Icon on pipe";
        }
        if (part == BADGE) {
            return "Icon on source";
        }
        if (part == SIDE_TUCK) {
            return "Side depth (X)";
        }
        if (part == BOTTOM_TUCK) {
            return "Bottom depth (Y)";
        }
        if (part == TEXT) {
            return "Text position";
        }
        return "Asset";
    }

    private String layerShortName(Item item, int part) {
        if (part == PIPE) {
            return "Pipe";
        }
        if (part == ICON) {
            return "Icon";
        }
        if (part == BADGE) {
            return "Badge";
        }
        if (part == SIDE_TUCK) {
            return "Side X";
        }
        if (part == BOTTOM_TUCK) {
            return "Bottom Y";
        }
        if (part == TEXT) {
            return "Text";
        }
        return "Asset";
    }

    private void clearLevelExtraRects() {
        for (int i = PIPE; i < partRects.length; i++) {
            partRects[i].setEmpty();
        }
    }

    private Bitmap bitmap(String path) {
        if (path == null) {
            return null;
        }
        Bitmap cached = bitmaps.get(path);
        if (cached != null) {
            return cached;
        }
        try (InputStream stream = getContext().getAssets().open(path)) {
            Bitmap loaded = BitmapFactory.decodeStream(stream);
            if (loaded != null) {
                bitmaps.put(path, loaded);
            }
            return loaded;
        } catch (IOException ignored) {
            return null;
        }
    }

    private Rect opaqueBounds(String path, Bitmap bitmap) {
        Rect cached = opaqueBounds.get(path);
        if (cached != null) {
            return cached;
        }
        int minX = bitmap.getWidth();
        int minY = bitmap.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) > 18) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        Rect bounds = maxX >= minX && maxY >= minY
                ? new Rect(minX, minY, maxX + 1, maxY + 1)
                : new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        opaqueBounds.put(path, bounds);
        return bounds;
    }

    private RectF centered(float x, float y, float width, float height) {
        return new RectF(x - width * 0.5f, y - height * 0.5f, x + width * 0.5f, y + height * 0.5f);
    }

    private String fmt(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private float dp(float value) {
        return value * density;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Item {
        final String scope;
        final String key;
        final String label;
        final String asset;
        final String dock;
        final String icon;
        final String badge;
        final float baseSize;
        final float textSize;
        final boolean bottomDock;
        final String textKey;
        final String text;

        Item(String scope, String key, String label, String asset, String dock, String icon, String badge,
             float baseSize, float textSize, boolean bottomDock) {
            this(scope, key, label, asset, dock, icon, badge, baseSize, textSize, bottomDock, null);
        }

        Item(String scope, String key, String label, String asset, String dock, String icon, String text,
             float baseSize, float textSize, boolean bottomDock, String textKey) {
            this.scope = scope;
            this.key = key;
            this.label = label;
            this.asset = asset;
            this.dock = dock;
            this.icon = icon;
            this.badge = scope.equals("L") ? text : null;
            this.text = scope.equals("M") ? text : null;
            this.baseSize = baseSize;
            this.textSize = textSize;
            this.bottomDock = bottomDock;
            this.textKey = textKey == null ? key : textKey;
        }
    }

    private static final class Layer {
        float scaleX = 1f;
        float scaleY = 1f;
        float x;
        float y;

        Layer() {
        }

        Layer(float scaleX, float scaleY, float x, float y) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.x = x;
            this.y = y;
        }
    }
}
