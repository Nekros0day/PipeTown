package com.pipetown.game;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

public class PipeTownView extends View {
    interface NavigationListener {
        void onReturnHome(int maxUnlocked, int latestLevel);

        void onLevelCompleted(int levelNumber, int maxUnlocked);

        void onSoundRequested(String soundKey);
    }

    private static final int GRID_W = 14;
    private static final int GRID_H = 20;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GAME = 1;
    private static final int HOME_LEVEL_BUFFER = 12;
    private static final int GLOBE_MESH_W = 28;
    private static final int GLOBE_MESH_H = 34;
    private static final float HOME_SCROLL_DRAG_SCALE = 0.42f;
    private static final long LEVEL_SEED_BASE = 0x51A7C0DEL;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final Random random = new Random(12);
    private final float[] globeMeshVerts = new float[(GLOBE_MESH_W + 1) * (GLOBE_MESH_H + 1) * 2];
    private final AssetBank assets;
    private final ArrayList<Level> levels = new ArrayList<>();
    private final HashMap<Integer, Level> levelCache = new HashMap<>();
    private final ArrayList<PointF> activePoints = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.35f);

    private float density;
    private int screen = SCREEN_HOME;
    private Level activeLevel;
    private Utility activeUtility;
    private float cell;
    private float boardLeft;
    private float boardTop;
    private float animSeconds;
    private float homeScroll;
    private boolean homeScrollReady;
    private boolean movedDuringTouch;
    private boolean draggingPipe;
    private Cell activeStartCell;
    private PointF activeStartPoint;
    private Source activeStartSource;
    private Port activeStartPort;
    private Direction activeStartDirection;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private int maxUnlocked = 1;
    private int pressedButton = -1;
    private int highlightedPortId = -1;
    private long hintUntilMs;
    private long statusUntilMs;
    private long completedAtMs;
    private String status = "Ready";
    private HintPlan hintPlan;
    private NavigationListener navigationListener;
    private volatile boolean warmingLevels;

    private final RectF homeButton = new RectF();
    private final RectF resetButton = new RectF();
    private final RectF undoButton = new RectF();
    private final RectF hintButton = new RectF();
    private final RectF solveButton = new RectF();

    public PipeTownView(Context context) {
        super(context);
        setFocusable(true);
        density = getResources().getDisplayMetrics().density;
        paint.setFilterBitmap(true);
        paint.setDither(true);
        assets = new AssetBank(context.getApplicationContext());
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStyle(Paint.Style.STROKE);
        buildLevels();
    }

    void setNavigationListener(NavigationListener navigationListener) {
        this.navigationListener = navigationListener;
    }

    void setSavedProgress(int maxUnlocked) {
        this.maxUnlocked = Math.max(1, maxUnlocked);
        ensureGeneratedLevels(Math.min(this.maxUnlocked + 2, HOME_LEVEL_BUFFER));
    }

    void warmLevelCacheAround(int focusLevel) {
        if (warmingLevels) {
            return;
        }
        final int start = Math.max(1, focusLevel - 1);
        final int end = Math.max(start, Math.min(focusLevel + 3, maxUnlocked + 2));
        warmingLevels = true;
        Thread warmup = new Thread(() -> {
            try {
                for (int level = start; level <= end; level++) {
                    levelForNumber(level);
                }
            } finally {
                warmingLevels = false;
            }
        }, "PipeTown-level-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    void startLevelFromMenu(int number) {
        startLevel(number);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        animSeconds = (now % 120_000L) / 1000f;
        updateParticles();
        if (screen == SCREEN_HOME) {
            drawHome(canvas, now);
        } else {
            drawGame(canvas, now);
        }
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (screen == SCREEN_HOME) {
            return handleHomeTouch(event);
        }
        return handleGameTouch(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawHome(Canvas canvas, long now) {
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(), 0xFF8BC7F2, 0xFF315C8B, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);

        if (!homeScrollReady && getHeight() > 0) {
            homeScroll = 0;
            homeScrollReady = true;
        }
        ensureGeneratedLevels(Math.max(maxUnlocked + HOME_LEVEL_BUFFER, homeAnchorLevel() + HOME_LEVEL_BUFFER));

        drawHomeLogo(canvas);
        RectF globe = globeRect();
        drawGlobe(canvas, globe);
        drawGlobeDecor(canvas, globe);
        drawMapPath(canvas);
        drawLevelNodes(canvas, now);
    }

    private void drawHomeLogo(Canvas canvas) {
        Bitmap logo = assets.get("art/logo/logo.png");
        float width = Math.min(getWidth() * 0.68f, dp(300));
        float height = width * 0.42f;
        float y = dp(22);
        scratch.set((getWidth() - width) * 0.5f, y, (getWidth() + width) * 0.5f, y + height);
        drawBitmap(canvas, logo, scratch, 255);
    }

    private RectF globeRect() {
        float radius = Math.max(getWidth() * 1.18f, getHeight() * 0.92f);
        float cx = getWidth() * 0.5f;
        float cy = dp(154) + radius;
        return new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    private void drawGlobe(Canvas canvas, RectF globe) {
        Path globePath = new Path();
        globePath.addOval(globe, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(globePath);
        Bitmap farm = assets.get("art/backgrounds/farm.png");
        if (farm != null) {
            float wrapHeight = globe.height() * 1.08f;
            float shift = positiveMod(homeScroll * 0.42f, wrapHeight);
            for (int i = -1; i <= 2; i++) {
                drawSphereTexture(canvas, farm, globe, globe.top - shift + i * wrapHeight, wrapHeight);
            }
        } else {
            drawCover(canvas, farm, globe.left - globe.width() * 0.08f, globe.top - globe.height() * 0.04f, globe.right + globe.width() * 0.08f, globe.bottom + globe.height() * 0.04f);
        }
        float radius = globe.width() * 0.5f;
        paint.setShader(new RadialGradient(
                globe.centerX(),
                globe.centerY(),
                radius,
                new int[]{0x00000000, 0x00000000, 0xAA06110C},
                new float[]{0f, 0.66f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawOval(globe, paint);
        paint.setShader(new RadialGradient(
                globe.centerX() - globe.width() * 0.22f,
                globe.top + globe.height() * 0.18f,
                globe.width() * 0.62f,
                new int[]{0x6CFFFFFF, 0x00000000, 0x61102417},
                new float[]{0f, 0.56f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawOval(globe, paint);
        paint.setShader(new LinearGradient(globe.left, globe.top, globe.right, Math.min(getHeight(), globe.bottom), 0x1DFFFFFF, 0x7D203A22, Shader.TileMode.CLAMP));
        canvas.drawOval(globe, paint);
        paint.setShader(null);
        paint.setColor(0x1FFFFFFF);
        float shineW = Math.min(globe.width() * 0.10f, dp(190));
        scratch.set(globe.centerX() - globe.width() * 0.26f, globe.top + globe.height() * 0.14f, globe.centerX() - globe.width() * 0.26f + shineW, globe.top + globe.height() * 0.14f + shineW * 0.48f);
        canvas.drawOval(scratch, paint);
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0xB8F8F0CF);
        canvas.drawOval(globe, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0x773A2111);
        canvas.drawOval(globe.left + dp(8), globe.top + dp(8), globe.right - dp(8), globe.bottom - dp(8), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSphereTexture(Canvas canvas, Bitmap bitmap, RectF globe, float top, float height) {
        if (bitmap == null || height <= 1f) {
            return;
        }
        float cx = globe.centerX();
        float cy = globe.centerY();
        float radius = globe.width() * 0.5f;
        int index = 0;
        for (int row = 0; row <= GLOBE_MESH_H; row++) {
            float v = row / (float) GLOBE_MESH_H;
            float y = top + height * v;
            float latitude = clamp((y - cy) / Math.max(1f, radius), -1f, 1f);
            float halfWidth = (float) Math.sqrt(Math.max(0f, 1f - latitude * latitude)) * radius;
            float bulge = 0.84f + 0.16f * (float) Math.cos(latitude * Math.PI * 0.5f);
            for (int col = 0; col <= GLOBE_MESH_W; col++) {
                float u = col / (float) GLOBE_MESH_W;
                float lon = (u - 0.5f) * 2f;
                float edgePull = 0.88f + 0.12f * (float) Math.cos(lon * Math.PI * 0.5f);
                globeMeshVerts[index++] = cx + lon * halfWidth * bulge * edgePull;
                globeMeshVerts[index++] = y;
            }
        }
        paint.setAlpha(255);
        canvas.drawBitmapMesh(bitmap, GLOBE_MESH_W, GLOBE_MESH_H, globeMeshVerts, 0, null, 0, paint);
        paint.setAlpha(255);
    }

    private void drawGlobeDecor(Canvas canvas, RectF globe) {
        String[] decor = {
                "art/blockers/tree_1x1.png",
                "art/blockers/tree_1x2.png",
                "art/blockers/tree_1x3.png",
                "art/blockers/stone_1x1.png",
                "art/blockers/stone_2x2.png",
                "art/blockers/construction_1x2.png",
                "art/blockers/pond_2x2.png",
                "art/houses/house_1x1.png",
                "art/houses/house_2x2.png",
                "art/sources/water.png",
                "art/sources/electric.png"
        };
        int base = Math.max(0, (int) (homeScroll / dp(118)) - 10);
        for (int i = base; i < base + 34; i++) {
            Random seeded = new Random(31_337L + i * 9_191L);
            float radius = globe.width() * 0.5f;
            float phase = (i * dp(118) - homeScroll) / Math.max(1f, radius * 0.56f) - 0.82f;
            float z = (float) Math.cos(phase);
            if (z < 0.18f || phase < -1.52f || phase > 0.78f) {
                continue;
            }
            float lane = -0.68f + seeded.nextFloat() * 1.36f;
            float x = globe.centerX() + lane * radius * 0.28f * z;
            float y = globe.centerY() + (float) Math.sin(phase) * radius * 0.78f;
            if (y < dp(154) || y > getHeight() + dp(70)) {
                continue;
            }
            boolean tooClose = false;
            int first = homeFirstVisibleLevel();
            int last = homeLastVisibleLevel();
            for (int levelNumber = first; levelNumber <= last; levelNumber++) {
                SurfacePoint p = levelSurfacePoint(levelNumber);
                if (p.visible && distance(x, y, p.x, p.y) < dp(54)) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            float scale = 0.30f + 0.44f * z;
            float size = dp(34 + seeded.nextInt(24)) * scale;
            scratch.set(x - size * 0.5f, y - size * 0.75f, x + size * 0.5f, y + size * 0.45f);
            paint.setColor(0x33000000);
            canvas.drawOval(scratch.left + dp(2), scratch.bottom - dp(5), scratch.right - dp(2), scratch.bottom + dp(2), paint);
            drawBitmap(canvas, assets.get(decor[Math.abs(i) % decor.length]), scratch, (int) (160 + 80 * z));
        }
    }

    private void drawMapPath(Canvas canvas) {
        RectF globe = globeRect();
        Path globePath = new Path();
        globePath.addOval(globe, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(globePath);
        int first = homeFirstVisibleLevel();
        int last = Math.min(homeLastVisibleLevel(), levels.size() - 1);
        for (int i = first; i <= last; i++) {
            SurfacePoint a = levelSurfacePoint(i);
            SurfacePoint b = levelSurfacePoint(i + 1);
            if (!a.visible || !b.visible) {
                continue;
            }
            Path path = new Path();
            path.moveTo(a.x, a.y);
            float midX = (a.x + b.x) * 0.5f;
            float midY = (a.y + b.y) * 0.5f;
            float curve = (midX - globe.centerX()) * 0.18f;
            float pathScale = Math.min(a.scale, b.scale);
            path.quadTo(midX + curve, midY - dp(12) * pathScale, b.x, b.y);
            strokePaint.setStrokeWidth(dp(16) * pathScale);
            strokePaint.setColor(0x3F4B311E);
            strokePaint.setPathEffect(null);
            canvas.drawPath(path, strokePaint);
            strokePaint.setStrokeWidth(dp(8) * pathScale);
            strokePaint.setColor(0xFFECCB86);
            strokePaint.setPathEffect(new DashPathEffect(new float[]{dp(16), dp(12)}, -animSeconds * dp(28)));
            canvas.drawPath(path, strokePaint);
            strokePaint.setPathEffect(null);
        }
        canvas.restore();
    }

    private void drawLevelNodes(Canvas canvas, long now) {
        Bitmap levelIcon = assets.get("art/icons/level.png");
        Bitmap completeIcon = assets.get("art/icons/compleate_level.png");
        RectF globe = globeRect();
        int first = homeFirstVisibleLevel();
        int last = Math.min(homeLastVisibleLevel(), levels.size());
        for (int i = first; i <= last; i++) {
            Level level = levels.get(i - 1);
            SurfacePoint p = levelSurfacePoint(level.number);
            if (!p.visible) {
                continue;
            }
            boolean unlocked = level.number <= maxUnlocked;
            float globeScale = p.scale;
            float pulse = unlocked ? 1f + 0.045f * (float) Math.sin(animSeconds * 3.1f + level.number) : 1f;
            float size = dp(unlocked ? 72 : 58) * pulse * globeScale;
            scratch.set(p.x - size * 0.5f, p.y - size * 0.5f, p.x + size * 0.5f, p.y + size * 0.5f);
            canvas.save();
            canvas.rotate((p.x - globe.centerX()) / Math.max(1f, globe.width()) * 22f, p.x, p.y);
            canvas.scale(1f, 0.78f + 0.22f * globeScale, p.x, p.y);
            drawBitmap(canvas, levelIcon, scratch, unlocked ? 255 : 105);
            canvas.restore();

            textPaint.setColor(unlocked ? Color.WHITE : 0xCCEEE7D8);
            textPaint.setTextSize(dp(22) * globeScale);
            textPaint.setFakeBoldText(true);
            textPaint.setStyle(Paint.Style.STROKE);
            textPaint.setStrokeWidth(dp(3));
            textPaint.setColor(0xFF1B1712);
            canvas.drawText(String.valueOf(level.number), p.x, p.y + dp(7) * globeScale, textPaint);
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(unlocked ? Color.WHITE : 0xCCEEE7D8);
            canvas.drawText(String.valueOf(level.number), p.x, p.y + dp(7) * globeScale, textPaint);
            textPaint.setFakeBoldText(false);

            if (level.finished) {
                float badge = dp(34) * globeScale;
                scratch.set(p.x + size * 0.18f, p.y - size * 0.56f, p.x + size * 0.18f + badge, p.y - size * 0.56f + badge);
                drawBitmap(canvas, completeIcon, scratch, 255);
            }
        }
    }

    private boolean handleHomeTouch(MotionEvent event) {
        float contentH = homeContentHeight();
        float maxScroll = Math.max(0, contentH - getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                movedDuringTouch = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastY;
                homeScroll = clamp(homeScroll + dy * HOME_SCROLL_DRAG_SCALE, 0, maxScroll);
                if (homeScroll > maxScroll - dp(900)) {
                    ensureGeneratedLevels(levels.size() + HOME_LEVEL_BUFFER);
                }
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    movedDuringTouch = true;
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!movedDuringTouch) {
                    performClick();
                    int levelNumber = hitHomeLevel(event.getX(), event.getY());
                    if (levelNumber > 0) {
                        startLevel(levelNumber);
                    }
                }
                return true;
            default:
                return true;
        }
    }

    private int hitHomeLevel(float x, float contentY) {
        int first = homeFirstVisibleLevel();
        int last = Math.min(homeLastVisibleLevel(), levels.size());
        for (int i = first; i <= last; i++) {
            if (i > maxUnlocked) {
                continue;
            }
            SurfacePoint p = levelSurfacePoint(i);
            if (p.visible && distance(x, contentY, p.x, p.y) <= dp(44) * p.scale) {
                return i;
            }
        }
        return -1;
    }

    private void startLevel(int number) {
        activeLevel = levelForNumber(number);
        activeLevel.resetForPlay();
        completedAtMs = 0L;
        highlightedPortId = -1;
        hintPlan = null;
        status = String.format(Locale.US, "Level %d", number);
        statusUntilMs = SystemClock.uptimeMillis() + 1500L;
        particles.clear();
        screen = SCREEN_GAME;
    }

    private void drawGame(Canvas canvas, long now) {
        if (activeLevel == null) {
            returnHome();
            return;
        }

        drawCover(canvas, assets.get(activeLevel.background), 0, 0, getWidth(), getHeight());
        paint.setColor(0x660F211B);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        calculateBoardMetrics();
        drawTopButtons(canvas);
        drawBoard(canvas);
        drawStrokes(canvas, activeLevel.strokes);
        drawActiveStroke(canvas);
        drawHint(canvas, now);
        drawBlockers(canvas);
        drawEndpointDocks(canvas, now);
        drawHouses(canvas);
        drawSourceProviders(canvas);
        drawParticles(canvas);
        drawStatus(canvas, now);
        if (completedAtMs > 0L) {
            drawCompletion(canvas, now);
        }
    }

    private void calculateBoardMetrics() {
        float top = dp(76);
        float bottom = dp(18);
        float availableW = getWidth() - dp(20);
        float availableH = getHeight() - top - bottom;
        cell = Math.min(availableW / GRID_W, availableH / GRID_H);
        boardLeft = (getWidth() - cell * GRID_W) * 0.5f;
        float centeredTop = top + Math.max(0f, availableH - cell * GRID_H) * 0.5f;
        float preferredTop = top + dp(10);
        boardTop = availableH - cell * GRID_H > dp(90) ? preferredTop : centeredTop;
    }

    private void drawTopButtons(Canvas canvas) {
        float button = dp(48);
        float gap = dp(9);
        float y = dp(12);
        homeButton.set(dp(12), y, dp(12) + button, y + button);
        resetButton.set(homeButton.right + gap, y, homeButton.right + gap + button, y + button);
        solveButton.set(resetButton.right + gap, y, resetButton.right + gap + button, y + button);
        undoButton.set(getWidth() - dp(12) - button * 2 - gap, y, getWidth() - dp(12) - button - gap, y + button);
        hintButton.set(getWidth() - dp(12) - button, y, getWidth() - dp(12), y + button);

        drawIconButton(canvas, homeButton, assets.get("art/icons/world_map.png"), null, pressedButton == 1);
        drawIconButton(canvas, resetButton, assets.get("art/icons/reset.png"), null, pressedButton == 2);
        drawIconButton(canvas, solveButton, assets.get("art/icons/finish_level.png"), null, pressedButton == 3);
        drawIconButton(canvas, undoButton, assets.get("art/icons/revert.png"), null, pressedButton == 4);
        drawIconButton(canvas, hintButton, assets.get("art/icons/hint.png"), null, pressedButton == 5);
    }

    private void drawIconButton(Canvas canvas, RectF rect, Bitmap icon, String fallbackText, boolean pressed) {
        if (pressed) {
            float inflate = dp(3);
            rect.inset(-inflate, -inflate);
        }
        if (icon != null) {
            float inset = pressed ? dp(1) : dp(3);
            scratch.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            drawBitmap(canvas, icon, scratch, 255);
        } else {
            textPaint.setColor(0xFF385A4A);
            textPaint.setTextSize(dp(14));
            textPaint.setFakeBoldText(true);
            canvas.drawText(fallbackText, rect.centerX(), rect.centerY() + dp(5), textPaint);
            textPaint.setFakeBoldText(false);
        }
        if (pressed) {
            float inflate = dp(3);
            rect.inset(inflate, inflate);
        }
    }

    private void drawBoard(Canvas canvas) {
        scratch.set(boardLeft - dp(5), boardTop - dp(5), boardLeft + cell * GRID_W + dp(5), boardTop + cell * GRID_H + dp(5));
        paint.setColor(0xB8FFF1D1);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(0xFFF5DFA7);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x80B9884D);
        scratch.inset(dp(5), dp(5));
        canvas.drawRoundRect(scratch, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(0x10FFFFFF);
        for (int x = 0; x <= GRID_W; x++) {
            float px = boardLeft + x * cell;
            canvas.drawLine(px, boardTop, px, boardTop + GRID_H * cell, paint);
        }
        for (int y = 0; y <= GRID_H; y++) {
            float py = boardTop + y * cell;
            canvas.drawLine(boardLeft, py, boardLeft + GRID_W * cell, py, paint);
        }
    }

    private void drawHint(Canvas canvas, long now) {
        if (now > hintUntilMs || hintPlan == null) {
            return;
        }
        if (hintPlan.removeIndex >= 0 && hintPlan.removeIndex < activeLevel.strokes.size()) {
            Stroke blocked = activeLevel.strokes.get(hintPlan.removeIndex);
            strokePaint.setStrokeWidth(dp(19));
            strokePaint.setColor(0xAAE53935);
            strokePaint.setPathEffect(new DashPathEffect(new float[]{dp(14), dp(10)}, -animSeconds * dp(46)));
            canvas.drawPath(smoothPath(blocked.points), strokePaint);
            strokePaint.setPathEffect(null);
        }
        if (hintPlan.points.size() >= 2) {
            Path path = smoothPath(hintPlan.points);
            strokePaint.setStrokeWidth(dp(7));
            strokePaint.setColor(withAlpha(hintPlan.utility.color, 190));
            strokePaint.setPathEffect(new DashPathEffect(new float[]{dp(12), dp(12)}, -animSeconds * dp(42)));
            canvas.drawPath(path, strokePaint);
            strokePaint.setPathEffect(null);
        }
    }

    private void drawStrokes(Canvas canvas, List<Stroke> strokes) {
        for (Stroke stroke : strokes) {
            drawUtilityLine(canvas, stroke.points, stroke.utility, true, 1f);
        }
        for (Stroke stroke : strokes) {
            drawNetworkJoins(canvas, stroke, strokes);
        }
    }

    private void drawActiveStroke(Canvas canvas) {
        if (activePoints.size() < 2 || activeUtility == null) {
            return;
        }
        drawUtilityLine(canvas, activePoints, activeUtility, false, 1f);
    }

    private void drawUtilityLine(Canvas canvas, List<PointF> points, Utility utility, boolean complete, float alphaScale) {
        if (points.size() < 2) {
            return;
        }
        Path path = smoothPath(points);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(width);
        strokePaint.setColor(withAlpha(Color.BLACK, (int) (62 * alphaScale)));
        canvas.drawPath(path, strokePaint);
        strokePaint.setStrokeWidth(width * 0.72f);
        strokePaint.setColor(withAlpha(utility.color, (int) (230 * alphaScale)));
        canvas.drawPath(path, strokePaint);
        strokePaint.setStrokeWidth(Math.max(dp(3), width * 0.18f));
        strokePaint.setColor(withAlpha(Color.WHITE, (int) (150 * alphaScale)));
        canvas.drawPath(path, strokePaint);
        drawFlowEffects(canvas, points, utility, alphaScale);
    }

    private void drawFlowEffects(Canvas canvas, List<PointF> points, Utility utility, float alphaScale) {
        float total = polylineLength(points);
        if (total < 2f) {
            return;
        }
        float spacing = Math.max(dp(34), cell * 0.52f);
        float phase = (animSeconds * dp(78)) % spacing;
        paint.setStyle(Paint.Style.FILL);
        for (float d = phase; d < total; d += spacing) {
            PointF p = pointAlong(points, d);
            float twinkle = 0.72f + 0.28f * (float) Math.sin(animSeconds * 8f + d * 0.03f);
            drawElementEffect(canvas, p, utility, d, twinkle, alphaScale);
        }
    }

    private void drawElementEffect(Canvas canvas, PointF p, Utility utility, float distance, float twinkle, float alphaScale) {
        float base = Math.max(dp(2), cell * 0.055f);
        switch (utility) {
            case WATER:
                paint.setColor(withAlpha(Color.WHITE, (int) (155 * twinkle * alphaScale)));
                canvas.drawCircle(p.x, p.y, base * 0.92f, paint);
                paint.setColor(withAlpha(0xFF8FE7FF, (int) (120 * alphaScale)));
                canvas.drawCircle(p.x - base * 1.8f, p.y + base * 0.7f, base * 0.55f, paint);
                break;
            case ELECTRIC:
                strokePaint.setPathEffect(null);
                strokePaint.setStrokeWidth(Math.max(dp(2), cell * 0.045f));
                strokePaint.setColor(withAlpha(0xFFFFFFFF, (int) (205 * twinkle * alphaScale)));
                Path spark = new Path();
                spark.moveTo(p.x - base * 2.2f, p.y - base * 0.5f);
                spark.lineTo(p.x, p.y - base * 1.8f);
                spark.lineTo(p.x - base * 0.3f, p.y + base * 1.4f);
                spark.lineTo(p.x + base * 2.0f, p.y + base * 0.2f);
                canvas.drawPath(spark, strokePaint);
                break;
            case INTERNET:
                paint.setColor(withAlpha(0xFFFFFFFF, (int) (170 * twinkle * alphaScale)));
                canvas.drawRoundRect(p.x - base * 1.8f, p.y - base, p.x + base * 1.8f, p.y + base, base * 0.45f, base * 0.45f, paint);
                paint.setColor(withAlpha(utility.color, (int) (125 * alphaScale)));
                canvas.drawCircle(p.x + base * 2.5f, p.y, base * 0.55f, paint);
                break;
            case HEATING:
                paint.setColor(withAlpha(0xFFFFF0A8, (int) (145 * twinkle * alphaScale)));
                canvas.drawCircle(p.x, p.y - base * 0.5f, base * 0.85f, paint);
                paint.setColor(withAlpha(0xFFFF6D2D, (int) (120 * alphaScale)));
                canvas.drawCircle(p.x + base * 1.1f, p.y + base * 0.7f, base * 0.55f, paint);
                break;
            case GAS:
                paint.setColor(withAlpha(0xFFFFE2A6, (int) (120 * twinkle * alphaScale)));
                canvas.drawCircle(p.x, p.y, base * 1.12f, paint);
                paint.setColor(withAlpha(0xFFD79B4B, (int) (95 * alphaScale)));
                canvas.drawCircle(p.x + (float) Math.sin(distance * 0.08f) * base * 1.8f, p.y - base * 0.9f, base * 0.62f, paint);
                break;
            case SEWAGE:
            default:
                paint.setColor(withAlpha(0xFFC6B08A, (int) (130 * twinkle * alphaScale)));
                canvas.drawOval(p.x - base * 1.7f, p.y - base * 0.9f, p.x + base * 1.7f, p.y + base * 0.9f, paint);
                paint.setColor(withAlpha(utility.color, (int) (105 * alphaScale)));
                canvas.drawCircle(p.x - base * 0.8f, p.y, base * 0.5f, paint);
                break;
        }
    }

    private void drawNetworkJoins(Canvas canvas, Stroke stroke, List<Stroke> strokes) {
        if (stroke.points.size() < 2) {
            return;
        }
        PointF end = stroke.points.get(stroke.points.size() - 1);
        boolean joinsNetwork = false;
        for (Stroke other : strokes) {
            if (other == stroke || other.utility != stroke.utility) {
                continue;
            }
            if (pointToPolylineDistance(end.x, end.y, other.points) <= Math.max(dp(4), cell * 0.08f)) {
                joinsNetwork = true;
                break;
            }
        }
        if (!joinsNetwork) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(stroke.utility.color, 240));
        canvas.drawCircle(end.x, end.y, Math.max(dp(8), cell * 0.16f), paint);
        paint.setColor(withAlpha(Color.WHITE, 170));
        canvas.drawCircle(end.x, end.y, Math.max(dp(3), cell * 0.055f), paint);
    }

    private void drawBlockers(Canvas canvas) {
        for (Blocker blocker : activeLevel.blockers) {
            RectF rect = visualAssetRect(assets.get(blocker.asset), cellRect(blocker.x, blocker.y, blocker.w, blocker.h), largestAssetUnit(blocker.asset, Math.max(blocker.w, blocker.h)), 1.00f, true);
            drawBitmap(canvas, assets.get(blocker.asset), rect, 255);
        }
    }

    private void drawHouses(Canvas canvas) {
        for (House house : activeLevel.houses) {
            Bitmap bitmap = assets.get(house.asset);
            RectF rect = visualAssetRect(bitmap, cellRect(house.x, house.y, house.w, house.h), largestAssetUnit(house.asset, Math.max(house.w, house.h)), 1.10f, true);
            drawBitmap(canvas, bitmap, rect, 255);
        }
    }

    private void drawEndpointDocks(Canvas canvas, long now) {
        for (Source source : activeLevel.sources) {
            drawSourceDock(canvas, source, now);
        }
        for (Port port : activeLevel.ports) {
            boolean pulse = port.id == highlightedPortId && now <= hintUntilMs;
            drawHouseDock(canvas, port, pulse, now);
        }
    }

    private void drawSourceDock(Canvas canvas, Source source, long now) {
        boolean pulse = hintPlan != null && now <= hintUntilMs && hintPlan.utility == source.utility;
        drawDockTile(canvas, source.utility, source.connectorX(), source.connectorY(), source.openDirection, false, pulse, now);
    }

    private void drawHouseDock(Canvas canvas, Port port, boolean pulse, long now) {
        drawDockTile(canvas, port.utility, port.x, port.y, port.outlet, port.connected, pulse, now);
    }

    private void drawDockTile(Canvas canvas, Utility utility, int gx, int gy, Direction imageRightDirection, boolean connected, boolean pulse, long now) {
        RectF rect = cellRect(gx, gy, 1, 1);
        PointF center = dockCenter(gx, gy, imageRightDirection);
        float centerX = center.x;
        float centerY = center.y;
        float scale = pulse ? 1f + 0.08f * (float) Math.sin(animSeconds * 7f) : 1f;
        float tile = Math.min(cell * 1.46f, dp(92)) * scale;
        scratch.set(centerX - tile * 0.5f, centerY - tile * 0.5f, centerX + tile * 0.5f, centerY + tile * 0.5f);

        paint.setColor(withAlpha(utility.color, pulse ? 92 : 24));
        canvas.drawCircle(centerX, centerY, tile * 0.52f, paint);
        drawRotatedBitmapTight(canvas, utility.baseAsset(), scratch, imageRightDirection.rotation, connected ? 244 : 255);

        float icon = tile * (utility.usesConnector ? 0.62f : 0.66f);
        scratch.set(centerX - icon * 0.5f, centerY - icon * 0.5f, centerX + icon * 0.5f, centerY + icon * 0.5f);
        drawBitmap(canvas, assets.get(utility.iconAsset()), scratch, connected ? 225 : 255);
    }

    private void drawSourceProviders(Canvas canvas) {
        for (Source source : activeLevel.sources) {
            Bitmap bitmap = assets.get(source.utility.sourceAsset());
            RectF rect = visualAssetRect(bitmap, cellRect(source.x, source.y, 2, 2), 2, 1.14f, true);
            drawBitmap(canvas, bitmap, rect, 255);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (Particle p : particles) {
            float age = p.age();
            if (age > 1f) {
                continue;
            }
            paint.setColor(withAlpha(p.color, (int) (210 * (1f - age))));
            canvas.drawCircle(p.x + p.vx * age, p.y + p.vy * age, p.radius * (1f + age), paint);
        }
    }

    private void drawStatus(Canvas canvas, long now) {
        if (now > statusUntilMs || status == null || status.length() == 0) {
            return;
        }
        float alpha = clamp((statusUntilMs - now) / 650f, 0f, 1f);
        float w = Math.min(getWidth() - dp(36), textWidth(status, dp(15)) + dp(44));
        scratch.set((getWidth() - w) * 0.5f, getHeight() - dp(58), (getWidth() + w) * 0.5f, getHeight() - dp(22));
        paint.setColor(withAlpha(0xFF21372F, (int) (210 * alpha)));
        canvas.drawRoundRect(scratch, dp(8), dp(8), paint);
        textPaint.setColor(withAlpha(Color.WHITE, (int) (255 * alpha)));
        textPaint.setTextSize(dp(15));
        textPaint.setFakeBoldText(true);
        canvas.drawText(status, scratch.centerX(), scratch.centerY() + dp(5), textPaint);
        textPaint.setFakeBoldText(false);
    }

    private void drawCompletion(Canvas canvas, long now) {
        float t = clamp((now - completedAtMs) / 650f, 0f, 1f);
        float eased = overshoot.getInterpolation(t);
        paint.setColor(0x99000000);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        Bitmap complete = assets.get("art/icons/compleate_level.png");
        float w = Math.min(getWidth() * 0.72f, dp(330)) * eased;
        float h = w * 0.75f;
        scratch.set((getWidth() - w) * 0.5f, getHeight() * 0.35f - h * 0.5f, (getWidth() + w) * 0.5f, getHeight() * 0.35f + h * 0.5f);
        drawBitmap(canvas, complete, scratch, 255);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(24));
        textPaint.setFakeBoldText(true);
        canvas.drawText("Level Complete", getWidth() * 0.5f, getHeight() * 0.56f, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private boolean handleGameTouch(MotionEvent event) {
        if (activeLevel == null) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                movedDuringTouch = false;
                pressedButton = hitButton(downX, downY);
                if (pressedButton > 0) {
                    return true;
                }
                Source source = hitSource(downX, downY);
                if (source != null && activeLevel.hasOpenPort(source.utility)) {
                    beginPipe(source.utility, sourceMouthPoint(source), sourceRouteCell(source), source, null, source.openDirection);
                    return true;
                }
                Port port = hitPort(downX, downY);
                if (port != null) {
                    if (port.connected) {
                        removeStrokeForPort(port.id);
                        port.connected = false;
                    }
                    beginPipe(port.utility, portMouthPoint(port), portRouteCell(port), null, port, port.outlet);
                    return true;
                }
                StrokeHit networkHit = hitNetwork(downX, downY);
                if (networkHit != null && activeLevel.hasOpenPort(networkHit.utility)) {
                    beginPipe(networkHit.utility, networkHit.point, networkHit.cell, null, null, null);
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingPipe) {
                    addActivePoint(event.getX(), event.getY());
                } else if (pressedButton <= 0 && Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    movedDuringTouch = true;
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (draggingPipe) {
                    finishPipe(event.getX(), event.getY());
                } else if (pressedButton > 0) {
                    int button = pressedButton;
                    pressedButton = -1;
                    if (button == hitButton(event.getX(), event.getY())) {
                        runButton(button);
                    }
                } else if (completedAtMs > 0L) {
                    returnHome();
                }
                pressedButton = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingPipe = false;
                activeUtility = null;
                activeStartCell = null;
                activeStartPoint = null;
                activeStartSource = null;
                activeStartPort = null;
                activeStartDirection = null;
                activePoints.clear();
                pressedButton = -1;
                return true;
            default:
                return true;
        }
    }

    private int hitButton(float x, float y) {
        if (homeButton.contains(x, y)) {
            return 1;
        }
        if (resetButton.contains(x, y)) {
            return 2;
        }
        if (solveButton.contains(x, y)) {
            return 3;
        }
        if (undoButton.contains(x, y)) {
            return 4;
        }
        if (hintButton.contains(x, y)) {
            return 5;
        }
        return -1;
    }

    private void runButton(int button) {
        if (button == 1) {
            returnHome();
        } else if (button == 2) {
            activeLevel.resetForPlay();
            completedAtMs = 0L;
            hintPlan = null;
            highlightedPortId = -1;
            status("Fresh pipes");
        } else if (button == 3) {
            solveLevel();
        } else if (button == 4) {
            undoLast();
        } else if (button == 5) {
            showHint();
        }
    }

    private void beginPipe(Utility utility, PointF start, Cell startCell, Source source, Port port, Direction startDirection) {
        draggingPipe = true;
        activeUtility = utility;
        activeStartCell = startCell;
        activeStartPoint = start;
        activeStartSource = source;
        activeStartPort = port;
        activeStartDirection = startDirection;
        hintPlan = null;
        activePoints.clear();
        activePoints.add(start);
        if (startDirection != null) {
            activePoints.add(new PointF(start.x + startDirection.dx * cell * 0.34f, start.y + startDirection.dy * cell * 0.34f));
        }
        status(String.format(Locale.US, "%s flowing", utility.title));
    }

    private void addActivePoint(float x, float y) {
        if (!draggingPipe) {
            return;
        }
        PointF last = activePoints.get(activePoints.size() - 1);
        float clampedX = clamp(x, boardLeft, boardLeft + GRID_W * cell);
        float clampedY = clamp(y, boardTop, boardTop + GRID_H * cell);
        PointF guided = guidedActivePoint(clampedX, clampedY);
        if (distance(last.x, last.y, guided.x, guided.y) >= Math.max(dp(4), cell * 0.08f)) {
            activePoints.add(guided);
            String error = validateActivePath();
            if (error != null) {
                rejectPipe(error);
                return;
            }
            FinishTouch finish = findFinishTouch(activePoints, false);
            if (finish != null) {
                completePipe(finish);
            }
        }
    }

    private PointF guidedActivePoint(float x, float y) {
        PointF point = new PointF(x, y);
        if (activeStartDirection != null && activeStartPoint != null) {
            float dx = point.x - activeStartPoint.x;
            float dy = point.y - activeStartPoint.y;
            float along = dx * activeStartDirection.dx + dy * activeStartDirection.dy;
            float minLead = cell * 0.36f;
            if (along < minLead) {
                float side = -dx * activeStartDirection.dy + dy * activeStartDirection.dx;
                side = clamp(side, -cell * 0.36f, cell * 0.36f);
                point.x = activeStartPoint.x + activeStartDirection.dx * minLead - activeStartDirection.dy * side;
                point.y = activeStartPoint.y + activeStartDirection.dy * minLead + activeStartDirection.dx * side;
            }
        }
        return point;
    }

    private void finishPipe(float x, float y) {
        addActivePoint(x, y);
        if (!draggingPipe) {
            return;
        }
        FinishTouch finish = findFinishTouch(activePoints, true);
        if (finish == null) {
            rejectPipe("Needs matching dock");
            return;
        }
        completePipe(finish);
    }

    private void completePipe(FinishTouch finish) {
        Port port = finish.port;
        PointF endPoint = finish.endPoint;
        ArrayList<PointF> snapped = new ArrayList<>(activePoints);
        snapped.set(0, activeStartPoint);
        replaceTailWithFinish(snapped, port, endPoint);
        Stroke stroke = new Stroke(activeUtility, port.id, simplifyStroke(snapped), new ArrayList<>(cellsTouched(snapped)));
        String error = validateStroke(stroke);
        if (error != null) {
            rejectPipe(error);
            return;
        }
        activeLevel.strokes.add(stroke);
        port.connected = true;
        highlightedPortId = -1;
        PointF burstAt = portMouthPoint(port);
        spawnBurst(burstAt.x, burstAt.y, port.utility.color, 18);
        requestSound(port.utility.usesConnector ? "electric_connect" : "liquid_connect");
        status(String.format(Locale.US, "%s connected", port.utility.title));
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
        activeStartSource = null;
        activeStartPort = null;
        activeStartDirection = null;
        activePoints.clear();
        if (activeLevel.isComplete()) {
            completeLevel();
        }
    }

    private void rejectPipe(String message) {
        PointF last = activePoints.size() > 0 ? activePoints.get(activePoints.size() - 1) : new PointF(getWidth() * 0.5f, getHeight() * 0.5f);
        spawnBurst(last.x, last.y, Color.rgb(211, 47, 47), 10);
        requestSound("fail");
        status(message);
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
        activeStartSource = null;
        activeStartPort = null;
        activeStartDirection = null;
        activePoints.clear();
    }

    private void undoLast() {
        if (activeLevel.strokes.isEmpty()) {
            status("Nothing to undo");
            return;
        }
        Stroke stroke = activeLevel.strokes.remove(activeLevel.strokes.size() - 1);
        Port port = activeLevel.findPort(stroke.portId);
        if (port != null) {
            port.connected = false;
            PointF p = portMouthPoint(port);
            spawnBurst(p.x, p.y, 0xFFECCB86, 8);
        }
        completedAtMs = 0L;
        hintPlan = null;
        highlightedPortId = -1;
        status("Pipe lifted");
    }

    private void removeStrokeForPort(int portId) {
        for (int i = activeLevel.strokes.size() - 1; i >= 0; i--) {
            if (activeLevel.strokes.get(i).portId == portId) {
                activeLevel.strokes.remove(i);
                return;
            }
        }
    }

    private void showHint() {
        HintPlan plan = findHintPlan();
        if (plan == null) {
            status(activeLevel.isComplete() ? "All connected" : "No clean route");
            return;
        }
        highlightedPortId = plan.portId;
        hintPlan = plan;
        hintUntilMs = SystemClock.uptimeMillis() + 2800L;
        if (plan.removeIndex >= 0 && plan.removeIndex < activeLevel.strokes.size()) {
            Stroke blocked = activeLevel.strokes.get(plan.removeIndex);
            status(String.format(Locale.US, "Lift %s first", blocked.utility.title));
        } else {
            status(String.format(Locale.US, "%s clean route", plan.utility.title));
        }
    }

    private HintPlan findHintPlan() {
        Port next = activeLevel.nextOpenPort();
        if (next == null) {
            return null;
        }
        HintPlan direct = hintForPort(next, -1);
        if (direct != null) {
            return direct;
        }
        for (int i = activeLevel.strokes.size() - 1; i >= 0; i--) {
            HintPlan reconnect = hintForPort(next, i);
            if (reconnect != null) {
                return reconnect;
            }
        }
        for (Port port : activeLevel.ports) {
            if (port.connected || port == next) {
                continue;
            }
            direct = hintForPort(port, -1);
            if (direct != null) {
                return direct;
            }
        }
        for (Port port : activeLevel.ports) {
            if (port.connected) {
                continue;
            }
            for (int i = activeLevel.strokes.size() - 1; i >= 0; i--) {
                HintPlan reconnect = hintForPort(port, i);
                if (reconnect != null) {
                    return reconnect;
                }
            }
        }
        return null;
    }

    private HintPlan hintForPort(Port port, int skipStrokeIndex) {
        Route route = findBestCurrentRoute(port.utility, port, skipStrokeIndex);
        if (route == null) {
            return null;
        }
        return new HintPlan(port.id, port.utility, route.points, skipStrokeIndex);
    }

    private Route findBestCurrentRoute(Utility utility, Port port, int skipStrokeIndex) {
        Route best = null;
        Source source = activeLevel.findSource(utility);
        if (source != null) {
            best = findRoute(activeLevel, utility, sourceRouteCell(source), sourceMouthPoint(source), port, activeLevel.strokes, skipStrokeIndex);
        }
        for (int i = 0; i < activeLevel.strokes.size(); i++) {
            if (i == skipStrokeIndex) {
                continue;
            }
            Stroke stroke = activeLevel.strokes.get(i);
            if (stroke.utility != utility) {
                continue;
            }
            for (Cell cell : stroke.cells) {
                Route route = findRoute(activeLevel, utility, cell, cellCenter(cell.x, cell.y), port, activeLevel.strokes, skipStrokeIndex);
                if (route != null && (best == null || route.cells.size() < best.cells.size())) {
                    best = route;
                }
            }
        }
        return best;
    }

    private void completeLevel() {
        activeLevel.finished = true;
        maxUnlocked = Math.max(maxUnlocked, activeLevel.number + 1);
        completedAtMs = SystemClock.uptimeMillis();
        status("Level complete");
        if (navigationListener != null) {
            navigationListener.onLevelCompleted(activeLevel.number, maxUnlocked);
        }
        postDelayed(() -> {
            if (screen == SCREEN_GAME && activeLevel != null && activeLevel.finished && completedAtMs > 0L) {
                returnHome();
            }
        }, 1150L);
        for (int i = 0; i < 34; i++) {
            spawnBurst(random.nextFloat() * getWidth(), boardTop + random.nextFloat() * GRID_H * cell, randomUtilityColor(), 1);
        }
    }

    private void returnHome() {
        screen = SCREEN_HOME;
        homeScrollReady = false;
        if (navigationListener != null) {
            navigationListener.onReturnHome(maxUnlocked, activeLevel == null ? 1 : activeLevel.number);
        }
    }

    private void solveLevel() {
        activeLevel.resetForPlay();
        hintPlan = null;
        highlightedPortId = -1;
        ArrayList<Stroke> solved = new ArrayList<>();
        for (Port port : activeLevel.ports) {
            Route route = findPlannedRoute(activeLevel, port, solved);
            if (route == null) {
                status("No solution route");
                requestSound("fail");
                activeLevel.strokes.clear();
                return;
            }
            Stroke stroke = new Stroke(port.utility, port.id, route.points, route.cells);
            solved.add(stroke);
            activeLevel.strokes.add(stroke);
            port.connected = true;
        }
        completeLevel();
    }

    private String validateStroke(Stroke candidate) {
        Port target = activeLevel.findPort(candidate.portId);
        Source source = activeLevel.findSource(candidate.utility);
        if (target == null || source == null) {
            return "Missing endpoint";
        }
        if (candidate.points.size() < 2) {
            return "Pipe too short";
        }
        for (Cell c : candidate.cells) {
            if (!inGrid(c.x, c.y)) {
                return "Out of town";
            }
            if (activeLevel.isBlockerCell(c.x, c.y)) {
                return "Blocked ground";
            }
            if (activeLevel.isHouseCell(c.x, c.y)) {
                return "Around houses";
            }
            if (activeLevel.isSourceProviderCell(c.x, c.y) && !isUtilitySourceProviderCell(candidate.utility, c.x, c.y)) {
                return "Source blocked";
            }
            boolean allowedEndpoint = (c.x == source.connectorX() && c.y == source.connectorY()) || (c.x == target.x && c.y == target.y);
            if (activeLevel.isEndpointCell(c.x, c.y) && !allowedEndpoint) {
                return "Endpoint crossed";
            }
        }
        if (selfIntersects(candidate.points)) {
            return "Pipe crossed itself";
        }
        for (Stroke other : activeLevel.strokes) {
            if (other.utility == candidate.utility) {
                continue;
            }
            if (sharesRouteCell(candidate, other)) {
                return "Utilities crossed";
            }
            if (strokesIntersect(candidate.points, other.points)) {
                return "Utilities crossed";
            }
        }
        return null;
    }

    private String validateActivePath() {
        if (activeUtility == null || activePoints.size() < 2) {
            return null;
        }
        Set<Cell> touched = cellsTouched(activePoints);
        for (Cell c : touched) {
            if (!inGrid(c.x, c.y)) {
                return "Out of town";
            }
            if (activeLevel.isBlockerCell(c.x, c.y)) {
                return "Blocked ground";
            }
            if (activeLevel.isHouseCell(c.x, c.y)) {
                return "Around houses";
            }
            if (activeLevel.isSourceProviderCell(c.x, c.y) && !isUtilitySourceProviderCell(activeUtility, c.x, c.y)) {
                return "Source wall";
            }
            if (activeLevel.isEndpointCell(c.x, c.y) && !activeEndpointAllowed(c)) {
                return "Wrong dock";
            }
        }
        for (Stroke other : activeLevel.strokes) {
            if (other.utility == activeUtility) {
                continue;
            }
            for (Cell c : touched) {
                if (strokeContainsCell(other, c)) {
                    return "Utilities crossed";
                }
            }
            if (strokesIntersect(activePoints, other.points)) {
                return "Utilities crossed";
            }
        }
        if (selfIntersects(activePoints)) {
            return "Looped pipe";
        }
        return null;
    }

    private boolean activeEndpointAllowed(Cell cell) {
        if (activeStartSource != null && cell.x == activeStartSource.connectorX() && cell.y == activeStartSource.connectorY()) {
            return true;
        }
        if (activeStartPort != null && cell.x == activeStartPort.x && cell.y == activeStartPort.y) {
            return true;
        }
        Source source = activeLevel.findSource(activeUtility);
        if (activeStartPort != null && source != null && cell.x == source.connectorX() && cell.y == source.connectorY()) {
            return true;
        }
        for (Port port : activeLevel.ports) {
            if (port.utility == activeUtility && !port.connected && cell.x == port.x && cell.y == port.y) {
                return true;
            }
        }
        return false;
    }

    private boolean isUtilitySourceProviderCell(Utility utility, int x, int y) {
        Source source = activeLevel.findSource(utility);
        return source != null && x >= source.x && x < source.x + 2 && y >= source.y && y < source.y + 2;
    }

    private Route findRoute(Level level, Utility utility, Cell start, PointF startPoint, Port target, List<Stroke> strokes, int skipStrokeIndex) {
        if (start == null || target == null) {
            return null;
        }
        Cell goal = portRouteCell(target);
        if (!inGrid(start.x, start.y) || !inGrid(goal.x, goal.y)) {
            return null;
        }
        if (!routeCellPassable(level, start, utility, strokes, skipStrokeIndex) || !routeCellPassable(level, goal, utility, strokes, skipStrokeIndex)) {
            return null;
        }

        PriorityQueue<SearchNode> open = new PriorityQueue<>((a, b) -> Float.compare(a.priority, b.priority));
        HashMap<Cell, Cell> cameFrom = new HashMap<>();
        HashMap<Cell, Float> costSoFar = new HashMap<>();
        open.add(new SearchNode(start, 0f));
        costSoFar.put(start, 0f);

        while (!open.isEmpty()) {
            SearchNode currentNode = open.poll();
            Cell current = currentNode.cell;
            if (current.equals(goal)) {
                break;
            }
            for (Direction direction : Direction.values()) {
                Cell next = new Cell(current.x + direction.dx, current.y + direction.dy);
                if (!routeCellPassable(level, next, utility, strokes, skipStrokeIndex)) {
                    continue;
                }
                float reuseBonus = sameUtilityOccupied(next, utility, strokes, skipStrokeIndex) ? -0.35f : 0f;
                float newCost = costSoFar.get(current) + 1f + reuseBonus;
                Float previous = costSoFar.get(next);
                if (previous == null || newCost < previous) {
                    costSoFar.put(next, newCost);
                    cameFrom.put(next, current);
                    float priority = newCost + Math.abs(next.x - goal.x) + Math.abs(next.y - goal.y);
                    open.add(new SearchNode(next, priority));
                }
            }
        }

        if (!start.equals(goal) && !cameFrom.containsKey(goal)) {
            return null;
        }

        ArrayList<Cell> cells = new ArrayList<>();
        Cell cursor = goal;
        cells.add(cursor);
        while (!cursor.equals(start)) {
            cursor = cameFrom.get(cursor);
            if (cursor == null) {
                return null;
            }
            cells.add(0, cursor);
        }

        ArrayList<PointF> points = new ArrayList<>();
        addRoutePoint(points, startPoint != null ? startPoint : cellCenter(start.x, start.y));
        for (Cell c : cells) {
            addRoutePoint(points, cellCenter(c.x, c.y));
        }
        addRoutePoint(points, portMouthPoint(target));
        return new Route(points, cells);
    }

    private boolean routeCellPassable(Level level, Cell cell, Utility utility, List<Stroke> strokes, int skipStrokeIndex) {
        if (cell == null || !inGrid(cell.x, cell.y)) {
            return false;
        }
        if (level.isHouseCell(cell.x, cell.y) || level.isSourceProviderCell(cell.x, cell.y) || level.isBlockerCell(cell.x, cell.y) || level.isEndpointCell(cell.x, cell.y)) {
            return false;
        }
        for (int i = 0; i < strokes.size(); i++) {
            if (i == skipStrokeIndex) {
                continue;
            }
            Stroke stroke = strokes.get(i);
            if (strokeContainsCell(stroke, cell) && stroke.utility != utility) {
                return false;
            }
        }
        return true;
    }

    private boolean sameUtilityOccupied(Cell cell, Utility utility, List<Stroke> strokes, int skipStrokeIndex) {
        for (int i = 0; i < strokes.size(); i++) {
            if (i == skipStrokeIndex) {
                continue;
            }
            Stroke stroke = strokes.get(i);
            if (stroke.utility == utility && strokeContainsCell(stroke, cell)) {
                return true;
            }
        }
        return false;
    }

    private boolean strokeContainsCell(Stroke stroke, Cell cell) {
        for (Cell c : stroke.cells) {
            if (c.equals(cell)) {
                return true;
            }
        }
        return false;
    }

    private boolean sharesRouteCell(Stroke a, Stroke b) {
        for (Cell ac : a.cells) {
            if (strokeContainsCell(b, ac)) {
                return true;
            }
        }
        return false;
    }

    private Cell nearestStrokeCell(Stroke stroke, float x, float y) {
        Cell best = null;
        float bestDist = Float.MAX_VALUE;
        for (Cell c : stroke.cells) {
            PointF center = cellCenter(c.x, c.y);
            float d = distance(x, y, center.x, center.y);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    private void addRoutePoint(ArrayList<PointF> points, PointF point) {
        if (!points.isEmpty()) {
            PointF last = points.get(points.size() - 1);
            if (distance(last.x, last.y, point.x, point.y) < 0.5f) {
                return;
            }
        }
        points.add(point);
    }

    private ArrayList<PointF> simplifyStroke(List<PointF> points) {
        ArrayList<PointF> simplified = new ArrayList<>();
        float minDistance = Math.max(dp(5), cell * 0.12f);
        for (PointF point : points) {
            if (simplified.isEmpty()) {
                simplified.add(point);
                continue;
            }
            PointF last = simplified.get(simplified.size() - 1);
            if (distance(last.x, last.y, point.x, point.y) >= minDistance) {
                simplified.add(point);
            }
        }
        if (!simplified.isEmpty()) {
            PointF lastInput = points.get(points.size() - 1);
            PointF last = simplified.get(simplified.size() - 1);
            if (distance(last.x, last.y, lastInput.x, lastInput.y) > 0.5f) {
                simplified.add(lastInput);
            }
        }
        return simplified;
    }

    private Set<Cell> cellsTouched(List<PointF> points) {
        HashSet<Cell> cells = new HashSet<>();
        float step = Math.max(dp(3), cell / 6f);
        for (int i = 0; i < points.size() - 1; i++) {
            PointF a = points.get(i);
            PointF b = points.get(i + 1);
            float length = distance(a.x, a.y, b.x, b.y);
            int samples = Math.max(2, (int) (length / step) + 1);
            for (int s = 0; s <= samples; s++) {
                float t = s / (float) samples;
                float px = a.x + (b.x - a.x) * t;
                float py = a.y + (b.y - a.y) * t;
                Cell c = pixelToCell(px, py);
                cells.add(c == null ? new Cell(-99, -99) : c);
            }
        }
        return cells;
    }

    private Source hitSource(float x, float y) {
        for (Source source : activeLevel.sources) {
            RectF provider = cellRect(source.x, source.y, 2, 2);
            PointF mouth = sourceMouthPoint(source);
            if (provider.contains(x, y) || sourceDockRect(source).contains(x, y) || distance(x, y, mouth.x, mouth.y) <= cell * 0.48f) {
                return source;
            }
        }
        return null;
    }

    private Port hitOpenPort(float x, float y, Utility utility) {
        Port best = null;
        float bestDist = Float.MAX_VALUE;
        for (Port port : activeLevel.ports) {
            if (port.connected || port.utility != utility) {
                continue;
            }
            if (activeStartPort != null && port.id == activeStartPort.id) {
                continue;
            }
            PointF p = portMouthPoint(port);
            float d = distance(x, y, p.x, p.y);
            if (d < bestDist) {
                bestDist = d;
                best = port;
            }
        }
        return bestDist <= cell * 0.28f ? best : null;
    }

    private Port hitPort(float x, float y) {
        Port best = null;
        float bestDist = Float.MAX_VALUE;
        for (Port port : activeLevel.ports) {
            PointF p = portMouthPoint(port);
            float d = dockRect(port.x, port.y).contains(x, y) ? 0f : distance(x, y, p.x, p.y);
            if (d < bestDist) {
                bestDist = d;
                best = port;
            }
        }
        return bestDist <= cell * 0.58f ? best : null;
    }

    private RectF sourceDockRect(Source source) {
        return dockRect(source.connectorX(), source.connectorY());
    }

    private RectF dockRect(int x, int y) {
        RectF rect = cellRect(x, y, 1, 1);
        float grow = cell * 0.08f;
        rect.inset(-grow, -grow);
        return rect;
    }

    private FinishTouch findFinishTouch(List<PointF> points, boolean release) {
        if (activeUtility == null || points.size() < 2 || !activePathClearOfStart(points)) {
            return null;
        }
        if (activeStartPort != null) {
            Source source = activeLevel.findSource(activeUtility);
            if (source != null && pathTouchesDockPixels(points, source.utility, source.connectorX(), source.connectorY(), source.openDirection, release)) {
                return new FinishTouch(activeStartPort, sourceMouthPoint(source));
            }
            StrokeHit network = sameUtilityNetworkTouch(points, activeUtility);
            if (network != null) {
                return new FinishTouch(activeStartPort, network.point);
            }
            return null;
        }

        Port best = null;
        float bestDistance = Float.MAX_VALUE;
        PointF last = points.get(points.size() - 1);
        for (Port port : activeLevel.ports) {
            if (port.connected || port.utility != activeUtility) {
                continue;
            }
            if (pathTouchesDockPixels(points, port.utility, port.x, port.y, port.outlet, release)) {
                PointF mouth = portMouthPoint(port);
                float distance = distance(last.x, last.y, mouth.x, mouth.y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = port;
                }
            }
        }
        return best == null ? null : new FinishTouch(best, portMouthPoint(best));
    }

    private boolean activePathClearOfStart(List<PointF> points) {
        if (activeStartPoint == null || points.isEmpty()) {
            return true;
        }
        PointF last = points.get(points.size() - 1);
        return distance(last.x, last.y, activeStartPoint.x, activeStartPoint.y) > cell * 0.72f;
    }

    private boolean pathTouchesDockPixels(List<PointF> points, Utility utility, int gx, int gy, Direction direction, boolean release) {
        float step = Math.max(dp(2), cell * 0.075f);
        float pipeRadius = Math.max(dp(6), cell * (release ? 0.19f : 0.16f));
        for (int i = 0; i < points.size() - 1; i++) {
            PointF a = points.get(i);
            PointF b = points.get(i + 1);
            float length = distance(a.x, a.y, b.x, b.y);
            int samples = Math.max(2, (int) (length / step) + 1);
            for (int s = 0; s <= samples; s++) {
                float t = s / (float) samples;
                float px = a.x + (b.x - a.x) * t;
                float py = a.y + (b.y - a.y) * t;
                if (activeStartPoint != null && distance(px, py, activeStartPoint.x, activeStartPoint.y) < cell * 0.62f) {
                    continue;
                }
                if (dockPixelHitWithPipeWidth(px, py, pipeRadius, utility, gx, gy, direction)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dockPixelHitWithPipeWidth(float x, float y, float radius, Utility utility, int gx, int gy, Direction direction) {
        if (dockVisiblePixelHit(x, y, utility, gx, gy, direction)) {
            return true;
        }
        float diagonal = radius * 0.70f;
        float[] offsets = {
                radius, 0f, -radius, 0f, 0f, radius, 0f, -radius,
                diagonal, diagonal, -diagonal, diagonal, diagonal, -diagonal, -diagonal, -diagonal
        };
        for (int i = 0; i < offsets.length; i += 2) {
            if (dockVisiblePixelHit(x + offsets[i], y + offsets[i + 1], utility, gx, gy, direction)) {
                return true;
            }
        }
        return false;
    }

    private boolean dockVisiblePixelHit(float x, float y, Utility utility, int gx, int gy, Direction direction) {
        Bitmap bitmap = assets.get(utility.baseAsset());
        if (bitmap == null) {
            return dockRect(gx, gy).contains(x, y);
        }
        PointF center = dockCenter(gx, gy, direction);
        float tile = Math.min(cell * 1.46f, dp(92));
        RectF dest = new RectF(center.x - tile * 0.5f, center.y - tile * 0.5f, center.x + tile * 0.5f, center.y + tile * 0.5f);
        double radians = Math.toRadians(-direction.rotation);
        float dx = x - dest.centerX();
        float dy = y - dest.centerY();
        float rotatedX = dest.centerX() + (float) (dx * Math.cos(radians) - dy * Math.sin(radians));
        float rotatedY = dest.centerY() + (float) (dx * Math.sin(radians) + dy * Math.cos(radians));
        if (!dest.contains(rotatedX, rotatedY)) {
            return false;
        }
        Rect bounds = assets.opaqueBounds(utility.baseAsset());
        float u = clamp((rotatedX - dest.left) / Math.max(1f, dest.width()), 0f, 1f);
        float v = clamp((rotatedY - dest.top) / Math.max(1f, dest.height()), 0f, 1f);
        int bx = clamp(bounds.left + Math.round(u * Math.max(0, bounds.width() - 1)), 0, bitmap.getWidth() - 1);
        int by = clamp(bounds.top + Math.round(v * Math.max(0, bounds.height() - 1)), 0, bitmap.getHeight() - 1);
        return ((bitmap.getPixel(bx, by) >>> 24) & 0xFF) > 18;
    }

    private StrokeHit sameUtilityNetworkTouch(List<PointF> points, Utility utility) {
        float threshold = Math.max(dp(5), cell * 0.075f);
        StrokeHit best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility != utility) {
                continue;
            }
            for (int i = 0; i < points.size() - 1; i++) {
                PointF a = points.get(i);
                PointF b = points.get(i + 1);
                if (activeStartPoint != null
                        && distance(b.x, b.y, activeStartPoint.x, activeStartPoint.y) < cell * 0.86f) {
                    continue;
                }
                for (int j = 0; j < stroke.points.size() - 1; j++) {
                    PointF c = stroke.points.get(j);
                    PointF d = stroke.points.get(j + 1);
                    if (segmentsIntersect(a, b, c, d)) {
                        PointF hit = nearestPointOnSegment(b.x, b.y, c, d);
                        Cell cell = nearestStrokeCell(stroke, hit.x, hit.y);
                        if (cell != null && !nearActiveStartCell(cell)) {
                            return new StrokeHit(utility, cell, hit);
                        }
                    }
                    PointF hit = nearestPointOnSegment(b.x, b.y, c, d);
                    float distance = pointToSegmentDistance(hit.x, hit.y, a, b);
                    if (distance <= threshold && distance < bestDistance) {
                        Cell cell = nearestStrokeCell(stroke, hit.x, hit.y);
                        if (cell != null && !nearActiveStartCell(cell)) {
                            bestDistance = distance;
                            best = new StrokeHit(utility, cell, hit);
                        }
                    }
                }
            }
        }
        return best;
    }

    private void replaceTailWithFinish(ArrayList<PointF> points, Port targetPort, PointF endPoint) {
        if (points.isEmpty()) {
            points.add(endPoint);
            return;
        }
        points.remove(points.size() - 1);
        Source source = activeLevel.findSource(activeUtility);
        if (source != null && distance(endPoint.x, endPoint.y, sourceMouthPoint(source).x, sourceMouthPoint(source).y) < cell * 0.1f) {
            PointF lead = new PointF(endPoint.x + source.openDirection.dx * cell * 0.42f, endPoint.y + source.openDirection.dy * cell * 0.42f);
            addRoutePoint(points, lead);
        } else if (targetPort != null && distance(endPoint.x, endPoint.y, portMouthPoint(targetPort).x, portMouthPoint(targetPort).y) < cell * 0.1f) {
            PointF lead = new PointF(endPoint.x + targetPort.outlet.dx * cell * 0.42f, endPoint.y + targetPort.outlet.dy * cell * 0.42f);
            addRoutePoint(points, lead);
        }
        addRoutePoint(points, endPoint);
    }

    private StrokeHit hitNetwork(float x, float y) {
        if (activeStartPoint != null && distance(x, y, activeStartPoint.x, activeStartPoint.y) < cell * 1.15f) {
            return null;
        }
        float threshold = activeStartPoint == null ? Math.max(dp(10), cell * 0.18f) : Math.max(dp(4), cell * 0.055f);
        StrokeHit best = null;
        float bestDist = Float.MAX_VALUE;
        for (Stroke stroke : activeLevel.strokes) {
            float d = pointToPolylineDistance(x, y, stroke.points);
            if (d <= threshold && d < bestDist) {
                bestDist = d;
                Cell cell = nearestStrokeCell(stroke, x, y);
                if (cell != null && !nearActiveStartCell(cell)) {
                    best = new StrokeHit(stroke.utility, cell, nearestPointOnPolyline(x, y, stroke.points));
                }
            }
        }
        return best;
    }

    private boolean nearActiveStartCell(Cell cell) {
        return activeStartCell != null
                && Math.abs(cell.x - activeStartCell.x) <= 1
                && Math.abs(cell.y - activeStartCell.y) <= 1;
    }

    private void spawnBurst(float x, float y, int color, int count) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < count; i++) {
            float angle = random.nextFloat() * (float) Math.PI * 2f;
            float speed = dp(18) + random.nextFloat() * dp(62);
            particles.add(new Particle(x, y, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed, dp(3) + random.nextFloat() * dp(4), color, now));
        }
    }

    private void updateParticles() {
        long now = SystemClock.uptimeMillis();
        for (int i = particles.size() - 1; i >= 0; i--) {
            if ((now - particles.get(i).bornMs) > 950L) {
                particles.remove(i);
            }
        }
    }

    private void status(String message) {
        status = message;
        statusUntilMs = SystemClock.uptimeMillis() + 1750L;
    }

    private void requestSound(String soundKey) {
        if (navigationListener != null) {
            navigationListener.onSoundRequested(soundKey);
        }
    }

    private Path smoothPath(List<PointF> points) {
        Path path = new Path();
        PointF first = points.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < points.size() - 1; i++) {
            PointF current = points.get(i);
            PointF next = points.get(i + 1);
            path.quadTo(current.x, current.y, (current.x + next.x) * 0.5f, (current.y + next.y) * 0.5f);
        }
        PointF last = points.get(points.size() - 1);
        path.lineTo(last.x, last.y);
        return path;
    }

    private void drawCover(Canvas canvas, Bitmap bitmap, float left, float top, float right, float bottom) {
        if (bitmap == null) {
            paint.setColor(0xFF5E8B62);
            canvas.drawRect(left, top, right, bottom, paint);
            return;
        }
        float viewW = right - left;
        float viewH = bottom - top;
        float scale = Math.max(viewW / bitmap.getWidth(), viewH / bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        scratch.set(left + (viewW - w) * 0.5f, top + (viewH - h) * 0.5f, left + (viewW + w) * 0.5f, top + (viewH + h) * 0.5f);
        canvas.drawBitmap(bitmap, null, scratch, null);
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, RectF dest, int alpha) {
        if (bitmap == null) {
            paint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(dest, dp(8), dp(8), paint);
            return;
        }
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, dest, paint);
        paint.setAlpha(255);
    }

    private RectF visualAssetRect(Bitmap bitmap, RectF anchor, int units, float scale, boolean bottomAligned) {
        float side = Math.max(cell * Math.max(1, units) * scale, cell * 0.8f);
        float ratio = bitmap == null || bitmap.getHeight() == 0 ? 1f : bitmap.getWidth() / (float) bitmap.getHeight();
        float width = ratio >= 1f ? side : side * ratio;
        float height = ratio >= 1f ? side / ratio : side;
        float centerX = anchor.centerX();
        float bottom = bottomAligned ? anchor.bottom + cell * 0.08f : anchor.centerY() + height * 0.5f;
        return new RectF(centerX - width * 0.5f, bottom - height, centerX + width * 0.5f, bottom);
    }

    private void drawRotatedBitmap(Canvas canvas, Bitmap bitmap, RectF dest, float degrees, int alpha) {
        canvas.save();
        canvas.rotate(degrees, dest.centerX(), dest.centerY());
        drawBitmap(canvas, bitmap, dest, alpha);
        canvas.restore();
    }

    private void drawRotatedBitmapTight(Canvas canvas, String path, RectF dest, float degrees, int alpha) {
        Bitmap bitmap = assets.get(path);
        if (bitmap == null) {
            drawBitmap(canvas, null, dest, alpha);
            return;
        }
        canvas.save();
        canvas.rotate(degrees, dest.centerX(), dest.centerY());
        Rect bounds = assets.opaqueBounds(path);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, bounds, dest, paint);
        paint.setAlpha(255);
        canvas.restore();
    }

    private RectF cellRect(int x, int y, int w, int h) {
        return new RectF(boardLeft + x * cell, boardTop + y * cell, boardLeft + (x + w) * cell, boardTop + (y + h) * cell);
    }

    private PointF cellCenter(int x, int y) {
        return new PointF(boardLeft + (x + 0.5f) * cell, boardTop + (y + 0.5f) * cell);
    }

    private PointF dockCenter(int x, int y, Direction direction) {
        PointF center = cellCenter(x, y);
        return new PointF(center.x - direction.dx * cell * 0.24f, center.y - direction.dy * cell * 0.24f);
    }

    private PointF sourceMouthPoint(Source source) {
        PointF center = dockCenter(source.connectorX(), source.connectorY(), source.openDirection);
        return new PointF(center.x + source.openDirection.dx * cell * 0.66f, center.y + source.openDirection.dy * cell * 0.66f);
    }

    private Cell sourceRouteCell(Source source) {
        return new Cell(source.connectorX() + source.openDirection.dx, source.connectorY() + source.openDirection.dy);
    }

    private PointF portMouthPoint(Port port) {
        PointF center = dockCenter(port.x, port.y, port.outlet);
        return new PointF(center.x + port.outlet.dx * cell * 0.66f, center.y + port.outlet.dy * cell * 0.66f);
    }

    private Cell portRouteCell(Port port) {
        return new Cell(port.x + port.outlet.dx, port.y + port.outlet.dy);
    }

    private Cell pixelToCell(float px, float py) {
        int x = (int) Math.floor((px - boardLeft) / cell);
        int y = (int) Math.floor((py - boardTop) / cell);
        if (!inGrid(x, y)) {
            return null;
        }
        return new Cell(x, y);
    }

    private boolean inGrid(int x, int y) {
        return x >= 0 && y >= 0 && x < GRID_W && y < GRID_H;
    }

    private float homeContentHeight() {
        return Math.max(getHeight() * 2.4f, dp(900) + levels.size() * homeLevelSpacing());
    }

    private float homeLevelSpacing() {
        return dp(146);
    }

    private int homeAnchorLevel() {
        return Math.max(1, (int) (homeScroll / Math.max(1f, homeLevelSpacing())) + 1);
    }

    private int homeFirstVisibleLevel() {
        return Math.max(1, homeAnchorLevel() - 5);
    }

    private int homeLastVisibleLevel() {
        return Math.max(homeFirstVisibleLevel(), homeAnchorLevel() + 8);
    }

    private float homeLevelPhase(int levelNumber) {
        RectF globe = globeRect();
        float radius = globe.width() * 0.5f;
        return ((levelNumber - 1) * homeLevelSpacing() - homeScroll) / Math.max(1f, radius * 0.58f) - 0.82f;
    }

    private SurfacePoint levelSurfacePoint(int levelNumber) {
        RectF globe = globeRect();
        float radius = globe.width() * 0.5f;
        float phase = homeLevelPhase(levelNumber);
        float z = (float) Math.cos(phase);
        float front = Math.max(0f, z);
        float lane = (float) Math.sin(levelNumber * 1.618f) * 0.44f;
        float y = globe.centerY() + (float) Math.sin(phase) * radius * 0.78f;
        float x = globe.centerX() + lane * radius * (0.18f + front * 0.82f) * 0.38f;
        boolean insideGlobe = distance(x, y, globe.centerX(), globe.centerY()) <= radius * 0.98f;
        boolean frontArc = phase > -1.48f && phase < 0.72f;
        boolean visible = frontArc && z > 0.22f && y >= dp(154) && y <= getHeight() + dp(46) && insideGlobe;
        float scale = clamp(0.58f + 0.42f * z, 0.48f, 1f);
        return new SurfacePoint(x, y, z, scale, visible);
    }

    private float positiveMod(float value, float modulus) {
        if (modulus <= 0f) {
            return 0f;
        }
        float result = value % modulus;
        return result < 0f ? result + modulus : result;
    }

    private float polylineLength(List<PointF> points) {
        float total = 0f;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distance(points.get(i).x, points.get(i).y, points.get(i + 1).x, points.get(i + 1).y);
        }
        return total;
    }

    private PointF pointAlong(List<PointF> points, float distance) {
        float remaining = distance;
        for (int i = 0; i < points.size() - 1; i++) {
            PointF a = points.get(i);
            PointF b = points.get(i + 1);
            float segment = distance(a.x, a.y, b.x, b.y);
            if (remaining <= segment) {
                float t = segment <= 0f ? 0f : remaining / segment;
                return new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            remaining -= segment;
        }
        return points.get(points.size() - 1);
    }

    private float pointToPolylineDistance(float x, float y, List<PointF> points) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            best = Math.min(best, pointToSegmentDistance(x, y, points.get(i), points.get(i + 1)));
        }
        return best;
    }

    private PointF nearestPointOnPolyline(float x, float y, List<PointF> points) {
        PointF best = points.isEmpty() ? new PointF(x, y) : points.get(0);
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            PointF candidate = nearestPointOnSegment(x, y, points.get(i), points.get(i + 1));
            float d = distance(x, y, candidate.x, candidate.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return new PointF(best.x, best.y);
    }

    private float pointToSegmentDistance(float px, float py, PointF a, PointF b) {
        PointF nearest = nearestPointOnSegment(px, py, a, b);
        return distance(px, py, nearest.x, nearest.y);
    }

    private PointF nearestPointOnSegment(float px, float py, PointF a, PointF b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        if (dx == 0f && dy == 0f) {
            return new PointF(a.x, a.y);
        }
        float t = ((px - a.x) * dx + (py - a.y) * dy) / (dx * dx + dy * dy);
        t = clamp(t, 0f, 1f);
        float x = a.x + dx * t;
        float y = a.y + dy * t;
        return new PointF(x, y);
    }

    private boolean selfIntersects(List<PointF> points) {
        for (int i = 0; i < points.size() - 1; i++) {
            for (int j = i + 2; j < points.size() - 1; j++) {
                if (i == 0 && j == points.size() - 2) {
                    continue;
                }
                if (segmentsIntersect(points.get(i), points.get(i + 1), points.get(j), points.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean strokesIntersect(List<PointF> a, List<PointF> b) {
        for (int i = 0; i < a.size() - 1; i++) {
            for (int j = 0; j < b.size() - 1; j++) {
                if (segmentsIntersect(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersect(PointF a, PointF b, PointF c, PointF d) {
        float o1 = orient(a, b, c);
        float o2 = orient(a, b, d);
        float o3 = orient(c, d, a);
        float o4 = orient(c, d, b);
        float eps = 0.0001f;
        if (((o1 > eps && o2 < -eps) || (o1 < -eps && o2 > eps)) && ((o3 > eps && o4 < -eps) || (o3 < -eps && o4 > eps))) {
            return true;
        }
        return Math.abs(o1) <= eps && onSegment(a, c, b)
                || Math.abs(o2) <= eps && onSegment(a, d, b)
                || Math.abs(o3) <= eps && onSegment(c, a, d)
                || Math.abs(o4) <= eps && onSegment(c, b, d);
    }

    private float orient(PointF a, PointF b, PointF c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private boolean onSegment(PointF a, PointF p, PointF b) {
        float eps = 0.0001f;
        return p.x >= Math.min(a.x, b.x) - eps && p.x <= Math.max(a.x, b.x) + eps
                && p.y >= Math.min(a.y, b.y) - eps && p.y <= Math.max(a.y, b.y) + eps
                && Math.abs(orient(a, p, b)) <= eps;
    }

    private int randomUtilityColor() {
        Utility[] utilities = Utility.values();
        return utilities[random.nextInt(utilities.length)].color;
    }

    private float textWidth(String text, float size) {
        textPaint.setTextSize(size);
        return textPaint.measureText(text);
    }

    private float dp(float value) {
        return value * density;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clamp(alpha, 0, 255) << 24);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int largestAssetUnit(String asset, int fallback) {
        int xPos = asset.lastIndexOf('x');
        if (xPos < 0) {
            return fallback;
        }
        int leftStart = xPos - 1;
        while (leftStart >= 0 && Character.isDigit(asset.charAt(leftStart))) {
            leftStart--;
        }
        int rightEnd = xPos + 1;
        while (rightEnd < asset.length() && Character.isDigit(asset.charAt(rightEnd))) {
            rightEnd++;
        }
        if (leftStart == xPos - 1 || rightEnd == xPos + 1) {
            return fallback;
        }
        int left = Integer.parseInt(asset.substring(leftStart + 1, xPos));
        int right = Integer.parseInt(asset.substring(xPos + 1, rightEnd));
        return Math.max(left, right);
    }

    private float distance(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    private void buildLevels() {
        ensureGeneratedLevels(4);
    }

    private void ensureGeneratedLevels(int count) {
        while (levels.size() < count) {
            levels.add(levelForNumber(levels.size() + 1));
        }
    }

    private Level levelForNumber(int number) {
        int safeNumber = Math.max(1, number);
        synchronized (levelCache) {
            Level cached = levelCache.get(safeNumber);
            if (cached != null) {
                return cached;
            }
        }
        Level generated = generateLevel(safeNumber);
        synchronized (levelCache) {
            Level cached = levelCache.get(safeNumber);
            if (cached != null) {
                return cached;
            }
            levelCache.put(safeNumber, generated);
        }
        return generated;
    }

    private Level generateLevel(int number) {
        DifficultyProfile profile = difficultyProfile(number);
        CandidateResult best = null;
        for (int attempt = 0; attempt < profile.maxAttempts; attempt++) {
            CandidateResult candidate = tryGenerateLevel(number, attempt, profile);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
            if (attempt >= profile.minAttempts && candidate.challengeMet && candidate.score >= profile.acceptScore) {
                return candidate.level;
            }
        }
        if (best != null && best.score >= profile.floorScore) {
            return best.level;
        }
        return fallbackLevel(number, profile);
    }

    private Level fallbackLevel(int number, DifficultyProfile profile) {
        Random rng = levelRandom(number, 9_911);
        String[] backgrounds = {"art/backgrounds/warm.png", "art/backgrounds/farm.png", "art/backgrounds/tropical.png", "art/backgrounds/desert.png"};
        ArrayList<Utility> utilities = shuffledUtilities(rng);
        for (int portTarget = profile.targetPorts; portTarget >= profile.houseCount; portTarget--) {
            Level level = new Level(number, backgrounds[Math.abs(number - 1) % backgrounds.length]);
            if (!placeFallbackSources(level, utilities, profile.sourceCount)) {
                continue;
            }
            if (!placeFallbackHouses(level, profile, rng)) {
                continue;
            }
            DifficultyProfile relaxed = profile.withTargetPorts(portTarget);
            if (!assignGeneratedPorts(level, utilities, relaxed, rng)) {
                continue;
            }
            ArrayList<Stroke> planned = plannedSolution(level);
            if (planned == null) {
                continue;
            }
            addGeneratedBlockers(level, planned, rng, Math.max(0, relaxed.blockerCount / 2));
            planned = plannedSolution(level);
            if (planned != null) {
                return level;
            }
        }
        Level emergencyNetwork = emergencyNetworkLevel(number, backgrounds[Math.abs(number - 1) % backgrounds.length], utilities, rng);
        if (emergencyNetwork != null) {
            return emergencyNetwork;
        }
        return emergencySingleRouteLevel(number, backgrounds[Math.abs(number - 1) % backgrounds.length], utilities.get(0));
    }

    private CandidateResult tryGenerateLevel(int number, int attempt, DifficultyProfile profile) {
        Random rng = levelRandom(number, attempt);
        String[] backgrounds = {"art/backgrounds/warm.png", "art/backgrounds/farm.png", "art/backgrounds/tropical.png", "art/backgrounds/desert.png"};
        Level level = new Level(number, backgrounds[Math.abs(number - 1) % backgrounds.length]);

        ArrayList<Utility> utilities = shuffledUtilities(rng);
        if (!placeSources(level, utilities, profile.sourceCount, rng)) {
            return null;
        }

        for (int i = 0; i < profile.houseCount; i++) {
            House house = randomHouse(level, rng, i + 1, profile);
            if (house == null) {
                return null;
            }
            level.houses.add(house);
        }

        if (!assignGeneratedPorts(level, utilities, profile, rng)) {
            return null;
        }

        ArrayList<Stroke> planned = plannedSolution(level);
        if (planned == null) {
            return null;
        }
        addGeneratedBlockers(level, planned, rng, profile.blockerCount);
        planned = plannedSolution(level);
        if (planned == null || !validatePlannedSolution(level, planned)) {
            return null;
        }
        int score = scoreGeneratedLevel(level, planned, profile);
        return new CandidateResult(level, score, generatedLevelMeetsChallenge(level, planned, profile));
    }

    private ArrayList<Utility> shuffledUtilities(Random rng) {
        ArrayList<Utility> utilities = new ArrayList<>();
        for (Utility utility : Utility.values()) {
            utilities.add(utility);
        }
        for (int i = utilities.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Utility temp = utilities.get(i);
            utilities.set(i, utilities.get(j));
            utilities.set(j, temp);
        }
        return utilities;
    }

    private Random levelRandom(int number, int attempt) {
        long seed = LEVEL_SEED_BASE
                ^ (number * 1_103_515_245L)
                ^ (attempt * 97_531L)
                ^ (((long) number) << 32);
        return new Random(seed);
    }

    private DifficultyProfile difficultyProfile(int number) {
        int safeNumber = Math.max(1, number);
        int tier = clamp((safeNumber - 1) / 10, 0, 5);
        int offset = (safeNumber - 1) % 10;
        int[] sources = {2, 3, 3, 4, 5, 6};
        int[] houses = {2, 2, 3, 4, 5, 6};
        int[] ports = {3, 4, 5, 7, 9, 12};
        int[] blockers = {2, 4, 6, 8, 10, 13};
        int[] bends = {2, 3, 4, 6, 8, 10};
        int[] extraLength = {2, 3, 5, 7, 9, 12};
        int intraBandPorts = offset >= 7 ? 2 : offset >= 4 ? 1 : 0;
        int intraBandBlockers = offset / 3;
        int sourceCount = clamp(sources[tier], 1, Utility.values().length);
        int houseCount = clamp(houses[tier], 1, 6);
        int targetPorts = clamp(ports[tier] + intraBandPorts, houseCount, Math.min(24, houseCount * sourceCount));
        int blockerCount = clamp(blockers[tier] + intraBandBlockers, 2, 20);
        int minSharedUtilities = clamp(tier <= 1 ? 1 : tier - 1, 1, Math.max(1, sourceCount - 1));
        int maxAttempts = 520 + tier * 150 + offset * 14;
        int minAttempts = 80 + tier * 20;
        int minBends = bends[tier] + offset / 5;
        int minExtra = extraLength[tier] + offset / 4;
        int acceptScore = 90 + tier * 28 + offset * 2;
        int floorScore = 48 + tier * 16;
        return new DifficultyProfile(tier, sourceCount, houseCount, targetPorts, blockerCount, minBends, minExtra,
                minSharedUtilities, maxAttempts, minAttempts, acceptScore, floorScore);
    }

    private Level emergencySingleRouteLevel(int number, String background, Utility utility) {
        Level level = new Level(number, background);
        level.sources.add(emergencySourceForUtility(utility, number));
        int x = 4 + Math.abs(number * 3) % Math.max(1, GRID_W - 8);
        int y = 4 + Math.abs(number * 5) % Math.max(1, GRID_H - 9);
        level.houses.add(new House(1, x, y, 1, 1, "art/houses/house_1x1.png"));
        level.ports.add(new Port(1, 1, utility, x, y + 1, Direction.DOWN));
        return level;
    }

    private Level emergencyNetworkLevel(int number, String background, ArrayList<Utility> utilities, Random rng) {
        Level level = new Level(number, background);
        DifficultyProfile profile = new DifficultyProfile(0, 2, 2, 3, 0, 0, 0, 1, 1, 0, 0, 0);
        if (!placeFallbackSources(level, utilities, profile.sourceCount)) {
            return null;
        }
        level.houses.add(new House(1, 4, 5, 1, 1, "art/houses/house_1x1.png"));
        level.houses.add(new House(2, 8, 12, 1, 1, "art/houses/house_1x1.png"));
        if (!assignGeneratedPorts(level, utilities, profile, rng)) {
            return null;
        }
        ArrayList<Stroke> planned = plannedSolution(level);
        return planned != null && validatePlannedSolution(level, planned) ? level : null;
    }

    private boolean placeFallbackSources(Level level, ArrayList<Utility> utilities, int sourceCount) {
        int right = GRID_W - 2;
        int bottom = GRID_H - 2;
        Source[] placements = {
                new Source(utilities.get(0), 0, 1, Direction.RIGHT),
                new Source(utilities.get(Math.min(1, utilities.size() - 1)), right, GRID_H - 5, Direction.LEFT),
                new Source(utilities.get(Math.min(2, utilities.size() - 1)), 0, GRID_H - 5, Direction.RIGHT),
                new Source(utilities.get(Math.min(3, utilities.size() - 1)), right, 2, Direction.LEFT),
                new Source(utilities.get(Math.min(4, utilities.size() - 1)), 4, 0, Direction.DOWN),
                new Source(utilities.get(Math.min(5, utilities.size() - 1)), 8, bottom, Direction.UP)
        };
        for (int i = 0; i < sourceCount; i++) {
            Source source = placements[i % placements.length];
            if (!canPlaceSource(level, source)) {
                return false;
            }
            level.sources.add(source);
        }
        return true;
    }

    private boolean placeFallbackHouses(Level level, DifficultyProfile profile, Random rng) {
        int[][] anchors = {{5, 4}, {8, 6}, {3, 9}, {9, 10}, {4, 15}, {9, 15}};
        for (int i = 0; i < profile.houseCount; i++) {
            String[] option = fallbackHouseOption(i, profile);
            int w = Integer.parseInt(option[0]);
            int h = Integer.parseInt(option[1]);
            boolean placed = false;
            for (int guard = 0; guard < 20 && !placed; guard++) {
                int[] anchor = anchors[(i + guard) % anchors.length];
                int x = clamp(anchor[0] + (guard == 0 ? 0 : rng.nextInt(3) - 1), 2, GRID_W - 3 - w);
                int y = clamp(anchor[1] + (guard == 0 ? 0 : rng.nextInt(3) - 1), 2, GRID_H - 3 - h);
                if (canPlaceRect(level, x, y, w, h)) {
                    level.houses.add(new House(i + 1, x, y, w, h, option[2]));
                    placed = true;
                }
            }
            if (!placed) {
                House house = randomHouse(level, rng, i + 1, profile);
                if (house == null) {
                    return false;
                }
                level.houses.add(house);
            }
        }
        return true;
    }

    private boolean assignGeneratedPorts(Level level, ArrayList<Utility> utilities, DifficultyProfile profile, Random rng) {
        int sourceCount = Math.min(profile.sourceCount, utilities.size());
        int maxPossiblePorts = Math.min(totalHouseCapacity(level), level.houses.size() * sourceCount);
        int targetPorts = clamp(profile.targetPorts, level.houses.size(), Math.min(24, maxPossiblePorts));
        if (targetPorts < level.houses.size()) {
            return false;
        }

        int[] remaining = targetUtilityCounts(sourceCount, targetPorts, rng);
        HashMap<Integer, HashSet<Utility>> plannedUtilities = new HashMap<>();
        HashMap<Integer, Integer> plannedCounts = new HashMap<>();
        ArrayList<Demand> demands = new ArrayList<>();
        ArrayList<House> firstPass = shuffledHouses(level.houses, rng);
        for (House house : firstPass) {
            int utilityIndex = chooseUtilityForHouse(level, house, utilities, remaining, plannedUtilities, sourceCount, rng);
            if (utilityIndex < 0) {
                return false;
            }
            addDemand(demands, plannedUtilities, plannedCounts, house, utilities.get(utilityIndex));
            remaining[utilityIndex]--;
        }

        int guard = 0;
        while (demands.size() < targetPorts && guard++ < 160) {
            int utilityIndex = chooseUtilityWithRemaining(remaining, rng);
            if (utilityIndex < 0) {
                break;
            }
            Utility utility = utilities.get(utilityIndex);
            House house = chooseHouseForUtility(level, utility, plannedUtilities, plannedCounts, rng);
            if (house == null) {
                remaining[utilityIndex] = 0;
                continue;
            }
            addDemand(demands, plannedUtilities, plannedCounts, house, utility);
            remaining[utilityIndex]--;
        }

        if (demands.size() < targetPorts || usedUtilityCount(demands) < sourceCount) {
            return false;
        }

        ArrayList<Demand> ordered = orderDemandsForRouting(demands, utilities, sourceCount, level, rng);
        for (Demand demand : ordered) {
            Port port = randomPortForHouse(level, demand.house, demand.utility, rng, level.findSource(demand.utility));
            if (port == null) {
                return false;
            }
            level.ports.add(port);
        }
        return level.ports.size() == targetPorts;
    }

    private int[] targetUtilityCounts(int sourceCount, int targetPorts, Random rng) {
        int[] counts = new int[sourceCount];
        for (int i = 0; i < sourceCount && i < targetPorts; i++) {
            counts[i] = 1;
        }
        int remaining = targetPorts - Math.min(sourceCount, targetPorts);
        while (remaining-- > 0) {
            int best = 0;
            int bestCount = Integer.MAX_VALUE;
            int start = rng.nextInt(Math.max(1, sourceCount));
            for (int step = 0; step < sourceCount; step++) {
                int index = (start + step) % sourceCount;
                if (counts[index] < bestCount) {
                    best = index;
                    bestCount = counts[index];
                }
            }
            counts[best]++;
        }
        return counts;
    }

    private ArrayList<House> shuffledHouses(ArrayList<House> houses, Random rng) {
        ArrayList<House> shuffled = new ArrayList<>(houses);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            House temp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, temp);
        }
        return shuffled;
    }

    private int chooseUtilityForHouse(Level level, House house, ArrayList<Utility> utilities, int[] remaining,
                                      HashMap<Integer, HashSet<Utility>> plannedUtilities, int sourceCount, Random rng) {
        int best = -1;
        float bestScore = -Float.MAX_VALUE;
        for (int i = 0; i < sourceCount; i++) {
            Utility utility = utilities.get(i);
            if (remaining[i] <= 0 || plannedHouseHasUtility(plannedUtilities, house.id, utility)) {
                continue;
            }
            Source source = level.findSource(utility);
            float distanceScore = source == null ? 0f : Math.abs(source.connectorX() - house.x) + Math.abs(source.connectorY() - house.y);
            float score = remaining[i] * 80f + distanceScore * 4f + rng.nextFloat();
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private int chooseUtilityWithRemaining(int[] remaining, Random rng) {
        int best = -1;
        int bestCount = 0;
        int start = rng.nextInt(Math.max(1, remaining.length));
        for (int step = 0; step < remaining.length; step++) {
            int index = (start + step) % remaining.length;
            if (remaining[index] > bestCount) {
                best = index;
                bestCount = remaining[index];
            }
        }
        return best;
    }

    private House chooseHouseForUtility(Level level, Utility utility, HashMap<Integer, HashSet<Utility>> plannedUtilities,
                                        HashMap<Integer, Integer> plannedCounts, Random rng) {
        House best = null;
        float bestScore = -Float.MAX_VALUE;
        Source source = level.findSource(utility);
        for (House house : level.houses) {
            int current = plannedCounts.containsKey(house.id) ? plannedCounts.get(house.id) : 0;
            if (current >= house.w * house.h + 1 || plannedHouseHasUtility(plannedUtilities, house.id, utility)) {
                continue;
            }
            float distanceScore = source == null ? 0f : Math.abs(source.connectorX() - house.x) + Math.abs(source.connectorY() - house.y);
            float score = (8 - current) * 50f + distanceScore * 3f + rng.nextFloat();
            if (score > bestScore) {
                bestScore = score;
                best = house;
            }
        }
        return best;
    }

    private boolean plannedHouseHasUtility(HashMap<Integer, HashSet<Utility>> plannedUtilities, int houseId, Utility utility) {
        HashSet<Utility> utilities = plannedUtilities.get(houseId);
        return utilities != null && utilities.contains(utility);
    }

    private void addDemand(ArrayList<Demand> demands, HashMap<Integer, HashSet<Utility>> plannedUtilities,
                           HashMap<Integer, Integer> plannedCounts, House house, Utility utility) {
        demands.add(new Demand(house, utility));
        HashSet<Utility> utilities = plannedUtilities.get(house.id);
        if (utilities == null) {
            utilities = new HashSet<>();
            plannedUtilities.put(house.id, utilities);
        }
        utilities.add(utility);
        Integer count = plannedCounts.get(house.id);
        plannedCounts.put(house.id, count == null ? 1 : count + 1);
    }

    private int usedUtilityCount(ArrayList<Demand> demands) {
        HashSet<Utility> used = new HashSet<>();
        for (Demand demand : demands) {
            used.add(demand.utility);
        }
        return used.size();
    }

    private ArrayList<Demand> orderDemandsForRouting(ArrayList<Demand> demands, ArrayList<Utility> utilities, int sourceCount,
                                                     Level level, Random rng) {
        ArrayList<Demand> pool = new ArrayList<>(demands);
        ArrayList<Demand> ordered = new ArrayList<>();
        int start = rng.nextInt(Math.max(1, sourceCount));
        for (int step = 0; step < sourceCount; step++) {
            Utility utility = utilities.get((start + step) % sourceCount);
            while (true) {
                int bestIndex = -1;
                float bestScore = -Float.MAX_VALUE;
                Source source = level.findSource(utility);
                for (int i = 0; i < pool.size(); i++) {
                    Demand demand = pool.get(i);
                    if (demand.utility != utility) {
                        continue;
                    }
                    float distanceScore = source == null ? 0f : Math.abs(source.connectorX() - demand.house.x) + Math.abs(source.connectorY() - demand.house.y);
                    float score = distanceScore + rng.nextFloat();
                    if (score > bestScore) {
                        bestScore = score;
                        bestIndex = i;
                    }
                }
                if (bestIndex < 0) {
                    break;
                }
                ordered.add(pool.remove(bestIndex));
            }
        }
        while (!pool.isEmpty()) {
            ordered.add(pool.remove(rng.nextInt(pool.size())));
        }
        return ordered;
    }

    private boolean placeSources(Level level, ArrayList<Utility> utilities, int sourceCount, Random rng) {
        int initialSize = level.sources.size();
        for (int i = 0; i < sourceCount; i++) {
            Source source = randomSourceForUtility(level, utilities.get(i), rng);
            if (source == null) {
                while (level.sources.size() > initialSize) {
                    level.sources.remove(level.sources.size() - 1);
                }
                return false;
            }
            level.sources.add(source);
        }
        return true;
    }

    private Source randomSourceForUtility(Level level, Utility utility, Random rng) {
        ArrayList<Source> candidates = new ArrayList<>();
        for (int y = 0; y <= GRID_H - 2; y++) {
            candidates.add(new Source(utility, 0, y, Direction.RIGHT));
            candidates.add(new Source(utility, GRID_W - 2, y, Direction.LEFT));
        }
        for (int x = 0; x <= GRID_W - 2; x++) {
            candidates.add(new Source(utility, x, 0, Direction.DOWN));
            candidates.add(new Source(utility, x, GRID_H - 2, Direction.UP));
        }
        for (int i = candidates.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Source temp = candidates.get(i);
            candidates.set(i, candidates.get(j));
            candidates.set(j, temp);
        }
        Source best = null;
        float bestScore = -Float.MAX_VALUE;
        for (Source candidate : candidates) {
            if (!canPlaceSource(level, candidate)) {
                continue;
            }
            float score = sourcePlacementScore(level, candidate) + rng.nextFloat() * 4f;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private float sourcePlacementScore(Level level, Source candidate) {
        if (level.sources.isEmpty()) {
            int edgeSpread = Math.min(Math.min(candidate.x + 1, GRID_W - candidate.x - 1), Math.min(candidate.y + 1, GRID_H - candidate.y - 1));
            return edgeSpread;
        }
        float minDistance = Float.MAX_VALUE;
        for (Source source : level.sources) {
            float distance = Math.abs(candidate.connectorX() - source.connectorX()) + Math.abs(candidate.connectorY() - source.connectorY());
            minDistance = Math.min(minDistance, distance);
        }
        int zoneX = clamp((candidate.x + 1) * 3 / GRID_W, 0, 2);
        int zoneY = clamp((candidate.y + 1) * 3 / GRID_H, 0, 2);
        int uniqueZoneBonus = 4;
        for (Source source : level.sources) {
            int otherX = clamp((source.x + 1) * 3 / GRID_W, 0, 2);
            int otherY = clamp((source.y + 1) * 3 / GRID_H, 0, 2);
            if (otherX == zoneX && otherY == zoneY) {
                uniqueZoneBonus = 0;
                break;
            }
        }
        return minDistance + uniqueZoneBonus;
    }

    private boolean canPlaceSource(Level level, Source source) {
        if (!canPlaceRect(level, source.x, source.y, 2, 2)) {
            return false;
        }
        int connectorX = source.connectorX();
        int connectorY = source.connectorY();
        int routeX = connectorX + source.openDirection.dx;
        int routeY = connectorY + source.openDirection.dy;
        if (!inGrid(connectorX, connectorY) || !inGrid(routeX, routeY)) {
            return false;
        }
        if (level.isSourceProviderCell(connectorX, connectorY) || level.isEndpointCell(connectorX, connectorY) || level.isSourceRouteCell(connectorX, connectorY)
                || level.isHouseCell(connectorX, connectorY) || level.isBlockerCell(connectorX, connectorY)) {
            return false;
        }
        return !level.isSourceProviderCell(routeX, routeY) && !level.isEndpointCell(routeX, routeY) && !level.isSourceRouteCell(routeX, routeY)
                && !level.isHouseCell(routeX, routeY) && !level.isBlockerCell(routeX, routeY);
    }

    private Source emergencySourceForUtility(Utility utility, int number) {
        int rightX = GRID_W - 2;
        int upper = 0;
        int middle = Math.max(0, GRID_H / 3);
        int lower = Math.max(0, GRID_H - 6);
        int offset = Math.abs(number) % 3;
        switch (utility) {
            case WATER:
                return new Source(utility, 0, Math.min(GRID_H - 2, upper + offset), Direction.RIGHT);
            case ELECTRIC:
                return new Source(utility, 0, Math.min(GRID_H - 2, middle + offset), Direction.RIGHT);
            case HEATING:
                return new Source(utility, 0, Math.min(GRID_H - 2, lower + offset), Direction.RIGHT);
            case GAS:
                return new Source(utility, rightX, Math.min(GRID_H - 2, upper + offset), Direction.LEFT);
            case SEWAGE:
                return new Source(utility, rightX, Math.min(GRID_H - 2, middle + offset), Direction.LEFT);
            case INTERNET:
            default:
                return new Source(utility, rightX, Math.min(GRID_H - 2, lower + offset), Direction.LEFT);
        }
    }

    private House randomHouse(Level level, Random rng, int id, DifficultyProfile profile) {
        String[][] options = houseOptionsForTier(profile.tier);
        for (int attempt = 0; attempt < 220; attempt++) {
            String[] option = options[rng.nextInt(options.length)];
            int w = Integer.parseInt(option[0]);
            int h = Integer.parseInt(option[1]);
            int minX = 2;
            int maxX = Math.max(minX, GRID_W - 3 - w);
            int minY = 2;
            int maxY = Math.max(minY, GRID_H - 3 - h);
            int x = minX + rng.nextInt(Math.max(1, maxX - minX + 1));
            int y = minY + rng.nextInt(Math.max(1, maxY - minY + 1));
            if (canPlaceRect(level, x, y, w, h) && (attempt > 130 || housePlacementHasBreathingRoom(level, x, y, w, h, profile))) {
                return new House(id, x, y, w, h, option[2]);
            }
        }
        return null;
    }

    private String[][] houseOptionsForTier(int tier) {
        if (tier <= 0) {
            return new String[][]{
                    {"1", "1", "art/houses/house_1x1.png"},
                    {"1", "1", "art/houses/house_1x1.png"},
                    {"2", "2", "art/houses/house_2x2.png"}
            };
        }
        if (tier == 1) {
            return new String[][]{
                    {"1", "1", "art/houses/house_1x1.png"},
                    {"2", "2", "art/houses/house_2x2.png"},
                    {"2", "2", "art/houses/house_2x2.png"}
            };
        }
        if (tier <= 3) {
            return new String[][]{
                    {"1", "1", "art/houses/house_1x1.png"},
                    {"2", "2", "art/houses/house_2x2.png"},
                    {"2", "2", "art/houses/house_2x2.png"},
                    {"4", "4", "art/houses/house 4x4.png"}
            };
        }
        return new String[][]{
                {"1", "1", "art/houses/house_1x1.png"},
                {"2", "2", "art/houses/house_2x2.png"},
                {"2", "2", "art/houses/house_2x2.png"},
                {"4", "4", "art/houses/house 4x4.png"},
                {"5", "5", "art/houses/house_5x5.png"}
        };
    }

    private String[] fallbackHouseOption(int index, DifficultyProfile profile) {
        if (profile.tier >= 4 && index == 1) {
            return new String[]{"2", "2", "art/houses/house_2x2.png"};
        }
        if (profile.tier >= 2 && index % 3 == 1) {
            return new String[]{"2", "2", "art/houses/house_2x2.png"};
        }
        return new String[]{"1", "1", "art/houses/house_1x1.png"};
    }

    private boolean housePlacementHasBreathingRoom(Level level, int x, int y, int w, int h, DifficultyProfile profile) {
        float centerX = x + w * 0.5f;
        float centerY = y + h * 0.5f;
        float minGap = profile.houseCount <= 3 ? 3.0f : 2.0f;
        for (House house : level.houses) {
            float otherX = house.x + house.w * 0.5f;
            float otherY = house.y + house.h * 0.5f;
            if (Math.abs(centerX - otherX) + Math.abs(centerY - otherY) < minGap) {
                return false;
            }
        }
        for (Source source : level.sources) {
            float sourceX = source.x + 1f;
            float sourceY = source.y + 1f;
            if (Math.abs(centerX - sourceX) + Math.abs(centerY - sourceY) < 3.0f) {
                return false;
            }
        }
        return true;
    }

    private boolean canPlaceRect(Level level, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > GRID_W || y + h > GRID_H) {
            return false;
        }
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (level.isHouseCell(xx, yy) || level.isSourceProviderCell(xx, yy) || level.isEndpointCell(xx, yy) || level.isSourceRouteCell(xx, yy) || level.isBlockerCell(xx, yy)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Port randomPortForHouse(Level level, House house, Utility utility, Random rng) {
        return randomPortForHouse(level, house, utility, rng, null);
    }

    private Port randomPortForHouse(Level level, House house, Utility utility, Random rng, Source source) {
        ArrayList<Port> candidates = new ArrayList<>();
        int id = level.ports.size() + 1;
        for (int dx = 0; dx < house.w; dx++) {
            candidates.add(new Port(id, house.id, utility, house.x + dx, house.y + house.h, Direction.DOWN));
        }
        candidates.add(new Port(id, house.id, utility, house.x - 1, house.y + Math.max(0, house.h - 1), Direction.LEFT));
        candidates.add(new Port(id, house.id, utility, house.x + house.w, house.y + Math.max(0, house.h - 1), Direction.RIGHT));
        ArrayList<Port> ordered = new ArrayList<>();
        while (!candidates.isEmpty()) {
            int selected = rng.nextInt(candidates.size());
            if (source != null) {
                float bestScore = -Float.MAX_VALUE;
                for (int i = 0; i < candidates.size(); i++) {
                    Port candidate = candidates.get(i);
                    Cell route = portRouteCell(candidate);
                    float distanceScore = Math.abs(route.x - source.connectorX()) + Math.abs(route.y - source.connectorY());
                    float score = distanceScore + rng.nextFloat();
                    if (score > bestScore) {
                        bestScore = score;
                        selected = i;
                    }
                }
            }
            ordered.add(candidates.remove(selected));
        }
        for (Port port : ordered) {
            Cell route = portRouteCell(port);
            if (inGrid(port.x, port.y) && inGrid(route.x, route.y)
                    && !level.isHouseCell(port.x, port.y)
                    && !level.isSourceProviderCell(port.x, port.y)
                    && !level.isEndpointCell(port.x, port.y)
                    && !level.isBlockerCell(port.x, port.y)
                    && !level.isHouseCell(route.x, route.y)
                    && !level.isSourceProviderCell(route.x, route.y)
                    && !level.isEndpointCell(route.x, route.y)
                    && !level.isBlockerCell(route.x, route.y)) {
                return port;
            }
        }
        return null;
    }

    private ArrayList<Stroke> plannedSolution(Level level) {
        ArrayList<Stroke> planned = new ArrayList<>();
        for (Port port : level.ports) {
            Route route = findPlannedRoute(level, port, planned);
            if (route == null) {
                return null;
            }
            planned.add(new Stroke(port.utility, port.id, route.points, route.cells));
        }
        return planned;
    }

    private boolean validatePlannedSolution(Level level, ArrayList<Stroke> planned) {
        if (planned.size() != level.ports.size()) {
            return false;
        }
        HashSet<Integer> solvedPorts = new HashSet<>();
        HashMap<Cell, Utility> owners = new HashMap<>();
        for (Stroke stroke : planned) {
            Port port = level.findPort(stroke.portId);
            if (port == null || stroke.cells.isEmpty()) {
                return false;
            }
            solvedPorts.add(port.id);
            Cell goal = portRouteCell(port);
            if (!stroke.cells.get(stroke.cells.size() - 1).equals(goal)) {
                return false;
            }
            for (int i = 0; i < stroke.cells.size(); i++) {
                Cell cell = stroke.cells.get(i);
                if (!inGrid(cell.x, cell.y) || level.isHouseCell(cell.x, cell.y) || level.isSourceProviderCell(cell.x, cell.y)
                        || level.isBlockerCell(cell.x, cell.y) || level.isEndpointCell(cell.x, cell.y)) {
                    return false;
                }
                if (i > 0) {
                    Cell prev = stroke.cells.get(i - 1);
                    if (Math.abs(prev.x - cell.x) + Math.abs(prev.y - cell.y) != 1) {
                        return false;
                    }
                }
                Utility owner = owners.get(cell);
                if (owner != null && owner != stroke.utility) {
                    return false;
                }
                owners.put(cell, stroke.utility);
            }
        }
        return solvedPorts.size() == level.ports.size();
    }

    private boolean generatedLevelMeetsChallenge(Level level, ArrayList<Stroke> planned, DifficultyProfile profile) {
        if (level.sources.size() < profile.sourceCount || level.houses.size() < profile.houseCount || level.ports.size() < profile.targetPorts) {
            return false;
        }
        if (countUtilitiesWithPorts(level) < profile.sourceCount) {
            return false;
        }
        if (countSharedUtilities(level) < profile.minSharedUtilities) {
            return false;
        }
        if (totalRouteBends(planned) < profile.minBends) {
            return false;
        }
        return extraRouteLength(level, planned) >= profile.minExtraLength;
    }

    private int scoreGeneratedLevel(Level level, ArrayList<Stroke> planned, DifficultyProfile profile) {
        int bends = totalRouteBends(planned);
        int extra = extraRouteLength(level, planned);
        int shared = countSharedUtilities(level);
        int utilityCount = countUtilitiesWithPorts(level);
        int sourceZones = sourceZoneCount(level);
        int spread = houseSpread(level);
        int score = level.sources.size() * 9
                + level.houses.size() * 10
                + level.ports.size() * 8
                + shared * 16
                + utilityCount * 9
                + bends * 7
                + extra * 5
                + sourceZones * 8
                + spread * 3
                + level.blockers.size() * 2;
        if (generatedLevelMeetsChallenge(level, planned, profile)) {
            score += 120;
        }
        return score;
    }

    private int countUtilitiesWithPorts(Level level) {
        HashSet<Utility> utilities = new HashSet<>();
        for (Port port : level.ports) {
            utilities.add(port.utility);
        }
        return utilities.size();
    }

    private int countSharedUtilities(Level level) {
        int shared = 0;
        for (Utility utility : Utility.values()) {
            int count = 0;
            for (Port port : level.ports) {
                if (port.utility == utility) {
                    count++;
                }
            }
            if (count >= 2) {
                shared++;
            }
        }
        return shared;
    }

    private int totalRouteBends(ArrayList<Stroke> planned) {
        int bends = 0;
        for (Stroke stroke : planned) {
            bends += routeBends(stroke.cells);
        }
        return bends;
    }

    private int routeBends(ArrayList<Cell> cells) {
        int bends = 0;
        int prevDx = 0;
        int prevDy = 0;
        for (int i = 1; i < cells.size(); i++) {
            Cell prev = cells.get(i - 1);
            Cell cell = cells.get(i);
            int dx = cell.x - prev.x;
            int dy = cell.y - prev.y;
            if (i > 1 && (dx != prevDx || dy != prevDy)) {
                bends++;
            }
            prevDx = dx;
            prevDy = dy;
        }
        return bends;
    }

    private int extraRouteLength(Level level, ArrayList<Stroke> planned) {
        int extra = 0;
        for (Stroke stroke : planned) {
            Port port = level.findPort(stroke.portId);
            Source source = level.findSource(stroke.utility);
            if (port == null || source == null || stroke.cells.size() < 2) {
                continue;
            }
            Cell start = sourceRouteCell(source);
            Cell goal = portRouteCell(port);
            int direct = Math.abs(start.x - goal.x) + Math.abs(start.y - goal.y);
            extra += Math.max(0, (stroke.cells.size() - 1) - direct);
        }
        return extra;
    }

    private int sourceZoneCount(Level level) {
        boolean[] zones = new boolean[9];
        int count = 0;
        for (Source source : level.sources) {
            int zx = clamp((source.x + 1) * 3 / GRID_W, 0, 2);
            int zy = clamp((source.y + 1) * 3 / GRID_H, 0, 2);
            int index = zy * 3 + zx;
            if (!zones[index]) {
                zones[index] = true;
                count++;
            }
        }
        return count;
    }

    private int houseSpread(Level level) {
        if (level.houses.isEmpty()) {
            return 0;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (House house : level.houses) {
            float x = house.x + house.w * 0.5f;
            float y = house.y + house.h * 0.5f;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return Math.round((maxX - minX) + (maxY - minY));
    }

    private void addGeneratedBlockers(Level level, ArrayList<Stroke> planned, Random rng, int target) {
        String[] assets = {
                "art/blockers/construction_1x1.png",
                "art/blockers/construction_1x2.png",
                "art/blockers/construction_1x3.png",
                "art/blockers/pond_1x1.png",
                "art/blockers/pond_2x2.png",
                "art/blockers/pond_2x3.png",
                "art/blockers/stone_1x1.png",
                "art/blockers/stone_1x3.png",
                "art/blockers/stone_2x2.png",
                "art/blockers/tree_1x1.png",
                "art/blockers/tree_1x2.png",
                "art/blockers/tree_1x3.png"
        };
        for (int placed = 0, guard = 0; placed < target && guard < 180; guard++) {
            String asset = assets[rng.nextInt(assets.length)];
            int[] size = blockerSize(asset);
            int x = rng.nextInt(Math.max(1, GRID_W - size[0] + 1));
            int y = rng.nextInt(Math.max(1, GRID_H - size[1] + 1));
            if (!canPlaceRect(level, x, y, size[0], size[1]) || routeUsesRect(planned, x, y, size[0], size[1])) {
                continue;
            }
            level.blockers.add(new Blocker(asset, x, y, size[0], size[1]));
            placed++;
        }
    }

    private int[] blockerSize(String asset) {
        int marker = asset.lastIndexOf('_');
        int xPos = asset.lastIndexOf('x');
        int dot = asset.lastIndexOf('.');
        if (marker >= 0 && xPos > marker && dot > xPos) {
            return new int[]{Integer.parseInt(asset.substring(marker + 1, xPos)), Integer.parseInt(asset.substring(xPos + 1, dot))};
        }
        return new int[]{1, 1};
    }

    private boolean routeUsesRect(ArrayList<Stroke> planned, int x, int y, int w, int h) {
        for (Stroke stroke : planned) {
            for (Cell cell : stroke.cells) {
                if (cell.x >= x && cell.x < x + w && cell.y >= y && cell.y < y + h) {
                    return true;
                }
            }
        }
        return false;
    }

    private int totalHouseCapacity(Level level) {
        int total = 0;
        for (House house : level.houses) {
            total += house.w * house.h + 1;
        }
        return total;
    }

    private int portsForHouse(Level level, int houseId) {
        int count = 0;
        for (Port port : level.ports) {
            if (port.houseId == houseId) {
                count++;
            }
        }
        return count;
    }

    private boolean houseAlreadyHasUtility(Level level, int houseId, Utility utility) {
        for (Port port : level.ports) {
            if (port.houseId == houseId && port.utility == utility) {
                return true;
            }
        }
        return false;
    }

    private Route findPlannedRoute(Level level, Port port, ArrayList<Stroke> planned) {
        Route best = null;
        Source source = level.findSource(port.utility);
        if (source != null) {
            best = findRoute(level, port.utility, sourceRouteCell(source), sourceMouthPoint(source), port, planned, -1);
        }
        for (Stroke stroke : planned) {
            if (stroke.utility != port.utility) {
                continue;
            }
            for (Cell cell : stroke.cells) {
                Route route = findRoute(level, port.utility, cell, cellCenter(cell.x, cell.y), port, planned, -1);
                if (route != null && (best == null || route.cells.size() < best.cells.size())) {
                    best = route;
                }
            }
        }
        return best;
    }

    private static final class DifficultyProfile {
        final int tier;
        final int sourceCount;
        final int houseCount;
        final int targetPorts;
        final int blockerCount;
        final int minBends;
        final int minExtraLength;
        final int minSharedUtilities;
        final int maxAttempts;
        final int minAttempts;
        final int acceptScore;
        final int floorScore;

        DifficultyProfile(int tier, int sourceCount, int houseCount, int targetPorts, int blockerCount,
                          int minBends, int minExtraLength, int minSharedUtilities, int maxAttempts,
                          int minAttempts, int acceptScore, int floorScore) {
            this.tier = tier;
            this.sourceCount = sourceCount;
            this.houseCount = houseCount;
            this.targetPorts = targetPorts;
            this.blockerCount = blockerCount;
            this.minBends = minBends;
            this.minExtraLength = minExtraLength;
            this.minSharedUtilities = minSharedUtilities;
            this.maxAttempts = maxAttempts;
            this.minAttempts = minAttempts;
            this.acceptScore = acceptScore;
            this.floorScore = floorScore;
        }

        DifficultyProfile withTargetPorts(int ports) {
            return new DifficultyProfile(tier, sourceCount, houseCount, ports, blockerCount, minBends, minExtraLength,
                    minSharedUtilities, maxAttempts, minAttempts, acceptScore, floorScore);
        }
    }

    private static final class CandidateResult {
        final Level level;
        final int score;
        final boolean challengeMet;

        CandidateResult(Level level, int score, boolean challengeMet) {
            this.level = level;
            this.score = score;
            this.challengeMet = challengeMet;
        }
    }

    private static final class Demand {
        final House house;
        final Utility utility;

        Demand(House house, Utility utility) {
            this.house = house;
            this.utility = utility;
        }
    }

    private enum Utility {
        WATER("water", "Water", Color.rgb(30, 136, 229), false),
        GAS("gas", "Gas", Color.rgb(172, 111, 31), false),
        HEATING("heating", "Heat", Color.rgb(230, 81, 0), false),
        SEWAGE("sewage", "Sewage", Color.rgb(109, 76, 65), false),
        ELECTRIC("electric", "Power", Color.rgb(255, 208, 46), true),
        INTERNET("internet", "Net", Color.rgb(126, 87, 194), true);

        final String key;
        final String title;
        final int color;
        final boolean usesConnector;

        Utility(String key, String title, int color, boolean usesConnector) {
            this.key = key;
            this.title = title;
            this.color = color;
            this.usesConnector = usesConnector;
        }

        String baseAsset() {
            return usesConnector ? "art/connectors/connector_1.png" : "art/pipes/pipe_1.png";
        }

        String iconAsset() {
            return "art/source_icons/" + key + ".png";
        }

        String sourceAsset() {
            return "art/sources/" + key + ".png";
        }
    }

    private enum Direction {
        RIGHT(1, 0, 0f),
        DOWN(0, 1, 90f),
        LEFT(-1, 0, 180f),
        UP(0, -1, -90f);

        final int dx;
        final int dy;
        final float rotation;

        Direction(int dx, int dy, float rotation) {
            this.dx = dx;
            this.dy = dy;
            this.rotation = rotation;
        }

        static Direction opposite(Direction direction) {
            switch (direction) {
                case RIGHT:
                    return LEFT;
                case LEFT:
                    return RIGHT;
                case UP:
                    return DOWN;
                case DOWN:
                default:
                    return UP;
            }
        }
    }

    private static final class Level {
        final int number;
        final String background;
        final ArrayList<House> houses = new ArrayList<>();
        final ArrayList<Source> sources = new ArrayList<>();
        final ArrayList<Port> ports = new ArrayList<>();
        final ArrayList<Blocker> blockers = new ArrayList<>();
        final ArrayList<Stroke> strokes = new ArrayList<>();
        boolean finished;

        Level(int number, String background) {
            this.number = number;
            this.background = background;
        }

        void resetForPlay() {
            strokes.clear();
            for (Port port : ports) {
                port.connected = false;
            }
        }

        boolean hasOpenPort(Utility utility) {
            for (Port port : ports) {
                if (!port.connected && port.utility == utility) {
                    return true;
                }
            }
            return false;
        }

        boolean isComplete() {
            for (Port port : ports) {
                if (!port.connected) {
                    return false;
                }
            }
            return true;
        }

        Port nextOpenPort() {
            for (Port port : ports) {
                if (!port.connected) {
                    return port;
                }
            }
            return null;
        }

        Port findPort(int id) {
            for (Port port : ports) {
                if (port.id == id) {
                    return port;
                }
            }
            return null;
        }

        Source findSource(Utility utility) {
            for (Source source : sources) {
                if (source.utility == utility) {
                    return source;
                }
            }
            return null;
        }

        boolean isHouseCell(int x, int y) {
            for (House house : houses) {
                if (x >= house.x && x < house.x + house.w && y >= house.y && y < house.y + house.h) {
                    return true;
                }
            }
            return false;
        }

        boolean isBlockerCell(int x, int y) {
            for (Blocker blocker : blockers) {
                if (x >= blocker.x && x < blocker.x + blocker.w && y >= blocker.y && y < blocker.y + blocker.h) {
                    return true;
                }
            }
            return false;
        }

        boolean isEndpointCell(int x, int y) {
            for (Source source : sources) {
                if (source.connectorX() == x && source.connectorY() == y) {
                    return true;
                }
            }
            for (Port port : ports) {
                if (port.x == x && port.y == y) {
                    return true;
                }
            }
            return false;
        }

        boolean isSourceRouteCell(int x, int y) {
            for (Source source : sources) {
                if (source.connectorX() + source.openDirection.dx == x
                        && source.connectorY() + source.openDirection.dy == y) {
                    return true;
                }
            }
            return false;
        }

        boolean isSourceProviderCell(int x, int y) {
            for (Source source : sources) {
                if (x >= source.x && x < source.x + 2 && y >= source.y && y < source.y + 2) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class House {
        final int id;
        final int x;
        final int y;
        final int w;
        final int h;
        final String asset;

        House(int id, int x, int y, int w, int h, String asset) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.asset = asset;
        }
    }

    private static final class Source {
        final Utility utility;
        final int x;
        final int y;
        final Direction openDirection;

        Source(Utility utility, int x, int y, Direction openDirection) {
            this.utility = utility;
            this.x = x;
            this.y = y;
            this.openDirection = openDirection;
        }

        int connectorX() {
            if (openDirection == Direction.RIGHT) {
                return x + 2;
            }
            if (openDirection == Direction.LEFT) {
                return x - 1;
            }
            return x + 1;
        }

        int connectorY() {
            if (openDirection == Direction.DOWN) {
                return y + 2;
            }
            if (openDirection == Direction.UP) {
                return y - 1;
            }
            return y + 1;
        }
    }

    private static final class Port {
        final int id;
        final int houseId;
        final Utility utility;
        final int x;
        final int y;
        final Direction outlet;
        boolean connected;

        Port(int id, int houseId, Utility utility, int x, int y, Direction outlet) {
            this.id = id;
            this.houseId = houseId;
            this.utility = utility;
            this.x = x;
            this.y = y;
            this.outlet = outlet;
        }
    }

    private static final class Blocker {
        final String asset;
        final int x;
        final int y;
        final int w;
        final int h;

        Blocker(String asset, int x, int y, int w, int h) {
            this.asset = asset;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final class Stroke {
        final Utility utility;
        final int portId;
        final ArrayList<PointF> points;
        final ArrayList<Cell> cells;

        Stroke(Utility utility, int portId, ArrayList<PointF> points, ArrayList<Cell> cells) {
            this.utility = utility;
            this.portId = portId;
            this.points = points;
            this.cells = cells;
        }
    }

    private static final class StrokeHit {
        final Utility utility;
        final Cell cell;
        final PointF point;

        StrokeHit(Utility utility, Cell cell, PointF point) {
            this.utility = utility;
            this.cell = cell;
            this.point = point;
        }
    }

    private static final class FinishTouch {
        final Port port;
        final PointF endPoint;

        FinishTouch(Port port, PointF endPoint) {
            this.port = port;
            this.endPoint = endPoint;
        }
    }

    private static final class Route {
        final ArrayList<PointF> points;
        final ArrayList<Cell> cells;

        Route(ArrayList<PointF> points, ArrayList<Cell> cells) {
            this.points = points;
            this.cells = cells;
        }
    }

    private static final class SearchNode {
        final Cell cell;
        final float priority;

        SearchNode(Cell cell, float priority) {
            this.cell = cell;
            this.priority = priority;
        }
    }

    private static final class HintPlan {
        final int portId;
        final Utility utility;
        final ArrayList<PointF> points;
        final int removeIndex;

        HintPlan(int portId, Utility utility, ArrayList<PointF> points, int removeIndex) {
            this.portId = portId;
            this.utility = utility;
            this.points = points;
            this.removeIndex = removeIndex;
        }
    }

    private static final class SurfacePoint {
        final float x;
        final float y;
        final float z;
        final float scale;
        final boolean visible;

        SurfacePoint(float x, float y, float z, float scale, boolean visible) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
            this.visible = visible;
        }
    }

    private static final class Cell {
        final int x;
        final int y;

        Cell(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Cell)) {
                return false;
            }
            Cell other = (Cell) obj;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return x * 31 + y;
        }
    }

    private static final class Particle {
        final float x;
        final float y;
        final float vx;
        final float vy;
        final float radius;
        final int color;
        final long bornMs;

        Particle(float x, float y, float vx, float vy, float radius, int color, long bornMs) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.color = color;
            this.bornMs = bornMs;
        }

        float age() {
            return Math.min(1f, (SystemClock.uptimeMillis() - bornMs) / 950f);
        }
    }

    private static final class AssetBank {
        private final AssetManager assetManager;
        private final Map<String, Bitmap> cache = new HashMap<>();
        private final Map<String, Rect> boundsCache = new HashMap<>();

        AssetBank(Context context) {
            assetManager = context.getAssets();
        }

        Bitmap get(String path) {
            Bitmap cached = cache.get(path);
            if (cached != null) {
                return cached;
            }
            try (InputStream stream = assetManager.open(path)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                cache.put(path, bitmap);
                return bitmap;
            } catch (IOException ignored) {
                return null;
            }
        }

        Rect opaqueBounds(String path) {
            Rect cached = boundsCache.get(path);
            if (cached != null) {
                return cached;
            }
            Bitmap bitmap = get(path);
            if (bitmap == null) {
                Rect fallback = new Rect(0, 0, 1, 1);
                boundsCache.put(path, fallback);
                return fallback;
            }
            int minX = bitmap.getWidth();
            int minY = bitmap.getHeight();
            int maxX = -1;
            int maxY = -1;
            int step = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 256);
            for (int y = 0; y < bitmap.getHeight(); y += step) {
                for (int x = 0; x < bitmap.getWidth(); x += step) {
                    if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) > 12) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            Rect bounds = maxX >= minX && maxY >= minY
                    ? new Rect(Math.max(0, minX - step), Math.max(0, minY - step), Math.min(bitmap.getWidth(), maxX + step), Math.min(bitmap.getHeight(), maxY + step))
                    : new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            boundsCache.put(path, bounds);
            return bounds;
        }
    }
}
