package com.pipetown.game;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
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

        void onRewardedHelpRequested(boolean solve, Runnable rewardedAction);
    }

    private static final int GRID_W = 22;
    private static final int GRID_H = 34;
    private static final int LEVEL_EDGE_MARGIN = 1;
    private static final int DOCK_CLEARANCE_CELLS = 1;
    private static final int OBJECT_CLEARANCE_CELLS = 1;
    private static final float FOCUS_HALO_CELLS = 1.10f;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GAME = 1;
    private static final int MECHANICS_LAB_LEVEL = -1;
    private static final int LAB_SCENARIO_COUNT = 10;
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
    private final ArrayList<CelebrationStar> celebrationStars = new ArrayList<>();
    private final ArrayList<Stroke> autoSolvePlan = new ArrayList<>();
    private final HashSet<Integer> dynamicDamagedHouses = new HashSet<>();
    private final OvershootInterpolator overshoot = new OvershootInterpolator(1.35f);
    private final Matrix groundMatrix = new Matrix();
    private final Matrix inverseGroundMatrix = new Matrix();

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
    private boolean activeLeadReleased;
    private int touchedStrokeIndex = -1;
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
    private CalibrationProfile calibrationProfile = CalibrationProfile.fromCode(CalibrationProfile.DEFAULT_CODE);
    private volatile boolean warmingLevels;
    private float bottomReservedSpace;
    private boolean autoSolving;
    private long autoSolveStartedMs;
    private boolean groundProjectionReady;
    private long levelStartedMs;
    private long completionDurationMs;
    private long threeStarGoalMs;
    private long twoStarGoalMs;
    private int completionStars;
    private boolean assistedFinish;
    private boolean tutorialLevel;
    private boolean mechanicsLab;
    private int labScenarioIndex = -1;
    private boolean labGateOpen;
    private boolean labSecondaryState;
    private int labMistakes;
    private long labGateChangedMs;
    private long labStartedMs;
    private long labTriggerMs;
    private long labAccidentFlashUntilMs;
    private float labStoredProgress;
    private boolean dynamicGateOpen;
    private long dynamicGateChangedMs;
    private long dynamicExplosionUntilMs;
    private long dynamicHardResetAtMs;

    private final RectF homeButton = new RectF();
    private final RectF resetButton = new RectF();
    private final RectF undoButton = new RectF();
    private final RectF hintButton = new RectF();
    private final RectF solveButton = new RectF();
    private final RectF continueButton = new RectF();
    private final RectF labPrevButton = new RectF();
    private final RectF labNextButton = new RectF();

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

    void setCalibrationProfile(CalibrationProfile profile) {
        calibrationProfile = profile == null ? CalibrationProfile.fromCode(CalibrationProfile.DEFAULT_CODE) : profile;
        invalidate();
    }

    void setSavedProgress(int maxUnlocked) {
        this.maxUnlocked = Math.max(1, maxUnlocked);
        ensureGeneratedLevels(Math.min(this.maxUnlocked + 2, HOME_LEVEL_BUFFER));
    }

    void setBottomReservedSpace(float pixels) {
        bottomReservedSpace = Math.max(0f, pixels);
        invalidate();
    }

    float calibrationReferenceCell() {
        return boardCellSize();
    }

    void warmLevelCacheAround(int focusLevel) {
        if (warmingLevels) {
            return;
        }
        final int start = Math.max(1, focusLevel - 1);
        final int end = Math.max(start, Math.min(focusLevel + 1, maxUnlocked + 1));
        warmingLevels = true;
        Thread warmup = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
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

    String auditGeneratedLevels(int maxLevel) {
        int checked = Math.max(1, maxLevel);
        int invalid = 0;
        int nondeterministic = 0;
        int duplicates = 0;
        int geometryDuplicates = 0;
        int belowProfile = 0;
        int fallback = 0;
        long started = SystemClock.uptimeMillis();
        HashSet<String> signatures = new HashSet<>();
        HashSet<String> geometrySignatures = new HashSet<>();
        StringBuilder failures = new StringBuilder();
        for (int number = 1; number <= checked; number++) {
            long levelStarted = SystemClock.uptimeMillis();
            Level first = generateLevel(number);
            Level replay = generateLevel(number);
            String signature = levelSignature(first);
            if (!signature.equals(levelSignature(replay))) {
                nondeterministic++;
                appendAuditFailure(failures, number, "non-deterministic");
            }
            if (!signatures.add(signature)) {
                duplicates++;
                appendAuditFailure(failures, number, "duplicate layout");
            }
            if (!geometrySignatures.add(levelGeometrySignature(first))) {
                geometryDuplicates++;
                appendAuditFailure(failures, number, "duplicate geometry");
            }
            ArrayList<Stroke> planned = new ArrayList<>(first.hiddenSolution);
            if (planned == null || !validatePlannedSolution(first, planned)) {
                invalid++;
                appendAuditFailure(failures, number, "unsolved");
                continue;
            }
            DifficultyProfile profile = difficultyProfile(number);
            if (!generatedLevelMeetsChallenge(first, planned, profile)) {
                belowProfile++;
                appendAuditFailure(failures, number, "below tier " + profile.tier);
                Log.i("PipeTownGeneratorAudit", "below " + number + " " + challengeMetrics(first, planned, profile));
            }
            if (!"candidate".equals(first.generationMode) && !"planar".equals(first.generationMode)
                    && !"striped".equals(first.generationMode)) {
                fallback++;
                appendAuditFailure(failures, number, first.generationMode);
            }
            long levelMs = SystemClock.uptimeMillis() - levelStarted;
            if (number % 10 == 0 || levelMs > 1500L) {
                Log.i("PipeTownGeneratorAudit",
                        String.format(Locale.US, "progress level=%d ms=%d tries=%d mode=%s",
                                number, levelMs, first.generationAttempts, first.generationMode));
                if (levelMs > 1500L) {
                    Log.i("PipeTownGeneratorAudit", "metrics " + number + " " + challengeMetrics(first, planned, profile));
                }
            }
        }
        return String.format(Locale.US,
                "levels=%d ms=%d invalid=%d nondeterministic=%d duplicates=%d geometryDuplicates=%d belowProfile=%d fallback=%d%s",
                checked, SystemClock.uptimeMillis() - started, invalid, nondeterministic, duplicates, geometryDuplicates, belowProfile, fallback,
                failures.length() == 0 ? "" : " failures=" + failures);
    }

    private void appendAuditFailure(StringBuilder failures, int level, String issue) {
        if (failures.length() > 220) {
            return;
        }
        if (failures.length() > 0) {
            failures.append(", ");
        }
        failures.append(level).append(':').append(issue);
    }

    private String challengeMetrics(Level level, ArrayList<Stroke> planned, DifficultyProfile profile) {
        return String.format(Locale.US,
                "family=%d tier=%d terrain=%s objects=%d/%d/%d minNeed=%d/%d shared=%d/%d travel=%d/%d constrained=%d/%d tension=%d/%d bends=%d/%d extra=%d/%d mode=%s",
                profile.family, profile.tier, level.terrainStyle,
                level.sources.size(), level.houses.size(), level.ports.size(),
                minimumPortsOnAnyHouse(level), profile.minPortsPerHouse,
                countSharedUtilities(level), profile.minSharedUtilities,
                averageDemandTravel(level), profile.minAverageTravel,
                constrainedRouteCount(level, planned), profile.minConstrainedRoutes,
                routeTension(planned), profile.minTension,
                totalRouteBends(planned), profile.minBends,
                extraRouteLength(level, planned), profile.minExtraLength,
                level.generationMode);
    }

    private String levelSignature(Level level) {
        StringBuilder signature = new StringBuilder();
        for (Source source : level.sources) {
            signature.append('S').append(source.utility.ordinal()).append('@').append(source.x).append(',').append(source.y)
                    .append(',').append(source.openDirection.ordinal()).append(';');
        }
        for (House house : level.houses) {
            signature.append('H').append(house.x).append(',').append(house.y).append(',').append(house.w).append(',').append(house.h).append(';');
        }
        for (Port port : level.ports) {
            signature.append('P').append(port.houseId).append(',').append(port.utility.ordinal()).append(',').append(port.x).append(',').append(port.y)
                    .append(',').append(port.outlet.ordinal()).append(';');
        }
        for (Blocker blocker : level.blockers) {
            signature.append('B').append(blocker.x).append(',').append(blocker.y).append(',').append(blocker.w).append(',').append(blocker.h).append(';');
        }
        return signature.toString();
    }

    private String levelGeometrySignature(Level level) {
        StringBuilder signature = new StringBuilder();
        for (Source source : level.sources) {
            signature.append('S').append(source.x).append(',').append(source.y)
                    .append(',').append(source.openDirection.ordinal()).append(';');
        }
        for (House house : level.houses) {
            signature.append('H').append(house.x).append(',').append(house.y)
                    .append(',').append(house.w).append(',').append(house.h).append(';');
        }
        return signature.toString();
    }

    void startLevelFromMenu(int number) {
        startLevel(number);
    }

    void startMechanicsLab() {
        startLabScenario(0);
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
        tutorialLevel = activeLevel.number == 0;
        mechanicsLab = activeLevel.number <= MECHANICS_LAB_LEVEL
                && activeLevel.number > MECHANICS_LAB_LEVEL - LAB_SCENARIO_COUNT;
        labScenarioIndex = mechanicsLab ? -activeLevel.number - 1 : -1;
        resetLabExperiment();
        resetDynamicMechanics();
        autoSolvePlan.clear();
        autoSolving = false;
        completedAtMs = 0L;
        levelStartedMs = SystemClock.uptimeMillis();
        completionDurationMs = 0L;
        completionStars = 0;
        assistedFinish = false;
        highlightedPortId = -1;
        hintPlan = null;
        status = tutorialLevel ? "Tutorial" : mechanicsLab ? activeLevel.labTitle : String.format(Locale.US, "Level %d", number);
        statusUntilMs = SystemClock.uptimeMillis() + (mechanicsLab ? 3200L : 1500L);
        particles.clear();
        celebrationStars.clear();
        screen = SCREEN_GAME;
    }

    private void startLabScenario(int scenario) {
        int selected = (scenario % LAB_SCENARIO_COUNT + LAB_SCENARIO_COUNT) % LAB_SCENARIO_COUNT;
        startLevel(MECHANICS_LAB_LEVEL - selected);
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
        updateDynamicMechanics(now);
        updateAutoSolve(now);
        drawTopButtons(canvas);
        drawGroundLayer(canvas, now);
        drawStandingObjects(canvas);
        drawLabObjectEffects(canvas, now);
        drawProjectedParticles(canvas);
        drawStatus(canvas, now);
        drawTutorialGuide(canvas, now);
        drawLabGuide(canvas, now);
        if (completedAtMs > 0L) {
            drawCompletion(canvas, now);
        }
        if (mechanicsLab && now < labAccidentFlashUntilMs) {
            paint.setColor(withAlpha(0xFFFF6A32,
                    Math.round(72f * clamp((labAccidentFlashUntilMs - now) / 470f, 0f, 1f))));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
        drawDynamicExplosionOverlay(canvas, now);
    }

    private void calculateBoardMetrics() {
        float top = dp(76);
        float bottom = dp(18) + bottomReservedSpace;
        float availableW = getWidth() - dp(20);
        float availableH = getHeight() - top - bottom;
        cell = boardCellSize();
        boardLeft = (getWidth() - cell * GRID_W) * 0.5f;
        float centeredTop = top + Math.max(0f, availableH - cell * GRID_H) * 0.5f;
        float preferredTop = top + dp(10);
        boardTop = availableH - cell * GRID_H > dp(90) ? preferredTop : centeredTop;
        updateGroundProjection();
    }

    private float boardCellSize() {
        float top = dp(76);
        float bottom = dp(18) + bottomReservedSpace;
        float availableW = Math.max(dp(1), getWidth() - dp(20));
        float availableH = Math.max(dp(1), getHeight() - top - bottom);
        return Math.min(availableW / GRID_W, availableH / GRID_H);
    }

    private void updateGroundProjection() {
        float left = boardLeft - dp(5);
        float top = boardTop - dp(5);
        float right = boardLeft + cell * GRID_W + dp(5);
        float bottom = boardTop + cell * GRID_H + dp(5);
        float inset = Math.min((right - left) * 0.09f, cell * 2.15f);
        float pitch = Math.min((bottom - top) * 0.065f, cell * 2.35f);
        float[] source = {left, top, right, top, right, bottom, left, bottom};
        float[] target = {left + inset, top + pitch, right - inset, top + pitch, right, bottom, left, bottom};
        groundProjectionReady = groundMatrix.setPolyToPoly(source, 0, target, 0, 4)
                && groundMatrix.invert(inverseGroundMatrix);
        if (!groundProjectionReady) {
            groundMatrix.reset();
            inverseGroundMatrix.reset();
        }
    }

    private void drawGroundLayer(Canvas canvas, long now) {
        canvas.save();
        canvas.concat(groundMatrix);
        drawBoard(canvas);
        drawFlatBlockers(canvas);
        drawGeneratedMechanics(canvas, now);
        drawLabGroundMechanics(canvas, now);
        drawStrokes(canvas, activeLevel.strokes);
        drawAutoSolveStroke(canvas, now);
        drawActiveStroke(canvas);
        drawHint(canvas, now);
        canvas.restore();
    }

    private void drawProjectedParticles(Canvas canvas) {
        canvas.save();
        canvas.concat(groundMatrix);
        drawParticles(canvas);
        canvas.restore();
    }

    private void drawTopButtons(Canvas canvas) {
        float gap = dp(6);
        float side = dp(10);
        float button = Math.min(dp(54), (getWidth() - side * 2f - gap * 4f) / 5f);
        float y = dp(9);
        homeButton.set(side, y, side + button, y + button);
        resetButton.set(homeButton.right + gap, y, homeButton.right + gap + button, y + button);
        solveButton.set(resetButton.right + gap, y, resetButton.right + gap + button, y + button);
        undoButton.set(solveButton.right + gap, y, solveButton.right + gap + button, y + button);
        hintButton.set(getWidth() - side - button, y, getWidth() - side, y + button);

        drawIconButton(canvas, homeButton, assets.get("art/icons/world_map.png"), "map", null, pressedButton == 1);
        drawIconButton(canvas, resetButton, assets.get("art/icons/reset.png"), "reset", null, pressedButton == 2);
        drawIconButton(canvas, solveButton, assets.get("art/icons/finish_level.png"), "finish_level", null, pressedButton == 3);
        drawIconButton(canvas, undoButton, assets.get("art/icons/revert.png"), "revert", null, pressedButton == 4);
        drawIconButton(canvas, hintButton, assets.get("art/icons/hint.png"), "hint", null, pressedButton == 5);
    }

    private void drawIconButton(Canvas canvas, RectF rect, Bitmap icon, String calibrationKey, String fallbackText, boolean pressed) {
        if (pressed) {
            float inflate = dp(3);
            rect.inset(-inflate, -inflate);
        }
        if (icon != null) {
            float inset = pressed ? dp(1) : dp(3);
            scratch.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            CalibrationProfile.Layer tune = calibrationProfile.layer("M", calibrationKey, 'A');
            scaleAndOffset(scratch, tune);
            drawBitmap(canvas, icon, fitBitmapInside(icon, scratch), 255);
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
            drawUtilityOuter(canvas, stroke, true, 1f);
        }
        for (Stroke stroke : strokes) {
            drawUtilityColor(canvas, stroke, true, 1f);
        }
        drawNetworkJunctionLayer(canvas, strokes, false);
        for (Stroke stroke : strokes) {
            drawUtilityCore(canvas, stroke, true, 1f);
        }
        drawNetworkJunctionLayer(canvas, strokes, true);
        for (Stroke stroke : strokes) {
            drawFlowEffects(canvas, stroke.points, stroke.utility, 1f);
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
        drawUtilityOuter(canvas, points, complete, alphaScale);
        drawUtilityColor(canvas, points, utility, complete, alphaScale);
        drawUtilityCore(canvas, points, complete, alphaScale);
        drawFlowEffects(canvas, points, utility, alphaScale);
    }

    private void drawUtilityPath(Canvas canvas, Path path, Utility utility, boolean complete, float alphaScale) {
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
    }

    private void drawUtilityOuter(Canvas canvas, List<PointF> points, boolean complete, float alphaScale) {
        if (points.size() < 2) {
            return;
        }
        Path path = smoothPath(points);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(width);
        strokePaint.setColor(withAlpha(Color.BLACK, (int) (62 * alphaScale)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawUtilityOuter(Canvas canvas, Stroke stroke, boolean complete, float alphaScale) {
        if (stroke.points.size() < 2) {
            return;
        }
        Path path = strokePath(stroke);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setPathEffect(null);
        strokePaint.setStrokeWidth(width);
        strokePaint.setColor(withAlpha(Color.BLACK, (int) (62 * alphaScale)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawUtilityColor(Canvas canvas, List<PointF> points, Utility utility, boolean complete, float alphaScale) {
        if (points.size() < 2) {
            return;
        }
        Path path = smoothPath(points);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setStrokeWidth(width * 0.72f);
        strokePaint.setColor(withAlpha(utility.color, (int) (230 * alphaScale)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawUtilityColor(Canvas canvas, Stroke stroke, boolean complete, float alphaScale) {
        if (stroke.points.size() < 2) {
            return;
        }
        Path path = strokePath(stroke);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setStrokeWidth(width * 0.72f);
        strokePaint.setColor(withAlpha(stroke.utility.color, (int) (230 * alphaScale)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawUtilityCore(Canvas canvas, List<PointF> points, boolean complete, float alphaScale) {
        if (points.size() < 2) {
            return;
        }
        Path path = smoothPath(points);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setStrokeWidth(Math.max(dp(3), width * 0.18f));
        strokePaint.setColor(withAlpha(Color.WHITE, (int) (150 * alphaScale)));
        canvas.drawPath(path, strokePaint);
    }

    private void drawUtilityCore(Canvas canvas, Stroke stroke, boolean complete, float alphaScale) {
        if (stroke.points.size() < 2) {
            return;
        }
        Path path = strokePath(stroke);
        float width = complete ? dp(15) : dp(17);
        strokePaint.setStrokeWidth(Math.max(dp(3), width * 0.18f));
        strokePaint.setColor(withAlpha(Color.WHITE, (int) (150 * alphaScale)));
        canvas.drawPath(path, strokePaint);
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

    private void drawNetworkJunctionLayer(Canvas canvas, List<Stroke> strokes, boolean core) {
        for (Stroke stroke : strokes) {
            if (stroke.points.size() < 2) {
                continue;
            }
            PointF start = stroke.points.get(0);
            boolean joinsNetwork = false;
            for (Stroke other : strokes) {
                if (other == stroke || other.utility != stroke.utility) {
                    continue;
                }
                if (pointToPolylineDistance(start.x, start.y, other.points) <= Math.max(dp(4), cell * 0.08f)) {
                    joinsNetwork = true;
                    break;
                }
            }
            if (!joinsNetwork) {
                continue;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(core ? withAlpha(Color.WHITE, 150) : withAlpha(stroke.utility.color, 230));
            paint.setAlpha(255);
            canvas.drawCircle(start.x, start.y, core ? dp(2.2f) : dp(5.4f), paint);
        }
    }

    private void drawFlatBlockers(Canvas canvas) {
        for (Blocker blocker : activeLevel.blockers) {
            if (!isFlatGroundBlocker(blocker)) {
                continue;
            }
            RectF rect = visualAssetRect(assets.get(blocker.asset), cellRect(blocker.x, blocker.y, blocker.w, blocker.h), largestAssetUnit(blocker.asset, Math.max(blocker.w, blocker.h)), 1.00f, true);
            scaleAndOffset(rect, calibrationProfile.layer("L", blockerCalibrationKey(blocker), 'A'));
            drawBitmap(canvas, assets.get(blocker.asset), rect, 255);
        }
    }

    private boolean isFlatGroundBlocker(Blocker blocker) {
        return blocker.asset.contains("/pond_");
    }

    private void drawLabGroundMechanics(Canvas canvas, long now) {
        if (!mechanicsLab || activeLevel == null) {
            return;
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(now * 0.008f);
        switch (labScenarioIndex) {
            case 0:
                drawLabGate(canvas, labGateRect(), labGateOpen, pulse, "PUMP");
                break;
            case 1:
                drawLabPatchFlood(canvas, now);
                break;
            case 2:
                drawLabSpinner(canvas, now);
                break;
            case 3:
                drawLabOneWay(canvas, pulse);
                break;
            case 4:
                drawLabCrackField(canvas, pulse);
                break;
            case 5:
                drawLabMovingCrew(canvas, now);
                break;
            case 7:
                drawLabSignalStorm(canvas, now);
                break;
            case 8:
                drawLabRootGrowth(canvas, now);
                break;
            case 9:
                drawLabFumeWarning(canvas, pulse);
                break;
            default:
                break;
        }
    }

    private void drawGeneratedMechanics(Canvas canvas, long now) {
        if (mechanicsLab || activeLevel == null || activeLevel.mechanic == DynamicMechanic.NONE) {
            return;
        }
        switch (activeLevel.mechanic) {
            case PUMP_GATE:
                drawLabGate(canvas, dynamicGateRect(), dynamicGateOpen, 0.5f + 0.5f * (float) Math.sin(now * 0.008f), "PUMP");
                break;
            case FUME_SPLIT:
                drawGeneratedFumeMargins(canvas, now);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void drawGeneratedFumeMargins(Canvas canvas, long now) {
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility != Utility.GAS) {
                continue;
            }
            Path path = strokePath(stroke);
            float pulse = 0.5f + 0.5f * (float) Math.sin(now * 0.004f);
            strokePaint.setPathEffect(null);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setStrokeWidth(cell * (activeLevel.mechanicRadiusCells * 1.86f + 0.25f + pulse * 0.14f));
            strokePaint.setColor(withAlpha(0xFFC98E3A, 34));
            canvas.drawPath(path, strokePaint);
            strokePaint.setStrokeWidth(cell * (activeLevel.mechanicRadiusCells * 1.14f + pulse * 0.10f));
            strokePaint.setColor(withAlpha(0xFFFFD37A, 42));
            canvas.drawPath(path, strokePaint);
            drawFumePuffs(canvas, stroke, now);
        }
    }

    private void drawFumePuffs(Canvas canvas, Stroke stroke, long now) {
        float spacing = Math.max(cell * 1.1f, dp(16));
        float length = polylineLength(stroke.points);
        for (float d = positiveMod(now * 0.018f, spacing); d < length; d += spacing) {
            PointF p = pointAlong(stroke.points, d);
            float wave = 0.5f + 0.5f * (float) Math.sin(now * 0.0036f + d * 0.047f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFFE0AC59, Math.round(42f + wave * 54f)));
            canvas.drawCircle(p.x, p.y - cell * (0.18f + wave * 0.12f), cell * (0.28f + wave * 0.18f), paint);
            paint.setColor(withAlpha(0xFFFFE6A8, Math.round(24f + wave * 35f)));
            canvas.drawCircle(p.x + cell * 0.22f, p.y - cell * 0.28f, cell * (0.14f + wave * 0.10f), paint);
        }
    }

    private void drawDynamicExplosionOverlay(Canvas canvas, long now) {
        if (dynamicExplosionUntilMs <= now) {
            return;
        }
        float remaining = clamp((dynamicExplosionUntilMs - now) / 900f, 0f, 1f);
        paint.setColor(withAlpha(0xFFFFB547, Math.round(92f * remaining)));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (activeLevel == null) {
            return;
        }
        for (House house : activeLevel.houses) {
            if (!dynamicDamagedHouses.contains(house.id)) {
                continue;
            }
            PointF p = projectGroundPoint(houseConnectionPoint(house));
            float ring = dp(24) + dp(64) * (1f - remaining);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3.2f) * remaining);
            paint.setColor(withAlpha(0xFFFFE0A1, Math.round(210f * remaining)));
            canvas.drawCircle(p.x, p.y, ring, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawLabGate(Canvas canvas, RectF gate, boolean open, float pulse, String label) {
        float changed = clamp((SystemClock.uptimeMillis() - labGateChangedMs) / 420f, 0f, 1f);
        paint.setColor(withAlpha(open ? 0xFF2EC893 : 0xFFE1A13F, open ? 74 : Math.round(125f + pulse * 26f)));
        canvas.drawRoundRect(gate, cell * 0.20f, cell * 0.20f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.6f), cell * 0.07f));
        paint.setColor(open ? 0xFF3CB998 : 0xFFB8602C);
        if (open) {
            float offset = cell * (0.34f + changed * 0.22f);
            canvas.drawLine(gate.left, gate.centerY() - offset, gate.right, gate.centerY() - offset, paint);
            canvas.drawLine(gate.left, gate.centerY() + offset, gate.right, gate.centerY() + offset, paint);
        } else {
            for (int bar = 0; bar < 4; bar++) {
                float x = gate.left + gate.width() * (bar + 0.5f) / 4f;
                canvas.drawLine(x, gate.top + cell * 0.12f, x, gate.bottom - cell * 0.12f, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        drawLabZoneLabel(canvas, gate, open ? 0xFF27836C : 0xFF8D482B, label);
    }

    private void drawLabWaterZone(Canvas canvas, RectF zone, int color, float pulse, String label) {
        if (zone.height() <= 0f) {
            return;
        }
        paint.setColor(withAlpha(color, Math.round(62f + pulse * 22f)));
        canvas.drawRoundRect(zone, cell * 0.24f, cell * 0.24f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.3f), cell * 0.05f));
        paint.setColor(withAlpha(color, 205));
        for (int stripe = 0; stripe < 3; stripe++) {
            float y = zone.top + zone.height() * (stripe + 1f) / 4f;
            float shift = positiveMod(animSeconds * cell * 0.8f + stripe * cell * 0.38f, cell * 0.72f);
            canvas.drawLine(zone.left + shift * 0.35f, y, zone.right - cell * 0.12f, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawLabZoneLabel(canvas, zone, blend(color, 0xFF153A50, 0.42f), label);
    }

    private void drawLabOutlinedZone(Canvas canvas, RectF zone, int color, String label) {
        paint.setColor(withAlpha(color, 40));
        canvas.drawRoundRect(zone, cell * 0.20f, cell * 0.20f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.2f), cell * 0.045f));
        paint.setPathEffect(new DashPathEffect(new float[]{cell * 0.32f, cell * 0.20f}, -animSeconds * cell));
        paint.setColor(withAlpha(color, 180));
        canvas.drawRoundRect(zone, cell * 0.20f, cell * 0.20f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        drawLabZoneLabel(canvas, zone, color, label);
    }

    private void drawLabZoneLabel(Canvas canvas, RectF zone, int color, String label) {
        textPaint.setColor(color);
        textPaint.setTextSize(Math.max(dp(7), cell * 0.38f));
        textPaint.setFakeBoldText(true);
        canvas.drawText(label, zone.centerX(), zone.centerY() + cell * 0.13f, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private void drawLabPatchFlood(Canvas canvas, long now) {
        RectF bounds = labPatchFloodBounds();
        drawLabOutlinedZone(canvas, bounds, 0xFF45A9DC, labGateOpen ? "DRAINING" : "PATCH FLOOD");
        float progress = labPatchFloodProgress(now);
        for (int x = 3; x < 19; x++) {
            for (int y = 18; y < 32; y++) {
                float threshold = labPatchFloodThreshold(x, y);
                float near = clamp((progress + 0.10f - threshold) / 0.18f, 0f, 1f);
                if (near <= 0f) {
                    continue;
                }
                PointF center = cellCenter(x, y);
                float pulse = 0.82f + 0.12f * (float) Math.sin(animSeconds * 4.2f + x * 0.71f + y * 0.37f);
                float radius = cell * (0.18f + 0.42f * near) * pulse;
                paint.setColor(withAlpha(0xFF2EA8E6, Math.round(46f + 96f * near)));
                canvas.drawCircle(center.x, center.y, radius, paint);
                paint.setColor(withAlpha(0xFFBCEEFF, Math.round(34f + 72f * near)));
                canvas.drawCircle(center.x - radius * 0.20f, center.y - radius * 0.18f, radius * 0.38f, paint);
            }
        }
    }

    private void drawLabSpinner(Canvas canvas, long now) {
        RectF zone = labSpinnerZoneRect();
        PointF center = labSpinnerCenter();
        float radius = cell * 4.45f;
        drawLabOutlinedZone(canvas, zone, 0xFF54A6DA, "ROTATING SPRAY");
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF67CAFF, 38));
        canvas.drawCircle(center.x, center.y, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.2f), cell * 0.045f));
        paint.setColor(withAlpha(0xFF2D93CF, 170));
        canvas.drawCircle(center.x, center.y, radius, paint);

        float angle = labSpinnerAngle(now);
        scratch.set(center.x - radius, center.y - radius, center.x + radius, center.y + radius);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF69D2FF, 110));
        canvas.drawArc(scratch, (float) Math.toDegrees(angle) - 9f, 18f, true, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(dp(5), cell * 0.18f));
        paint.setColor(withAlpha(0xFFE7FBFF, 208));
        canvas.drawLine(center.x, center.y,
                center.x + (float) Math.cos(angle) * radius,
                center.y + (float) Math.sin(angle) * radius, paint);
        for (int dot = 1; dot <= 5; dot++) {
            float t = dot / 5.6f;
            float px = center.x + (float) Math.cos(angle) * radius * t;
            float py = center.y + (float) Math.sin(angle) * radius * t;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFF2FAFE8, 150 - dot * 12));
            canvas.drawCircle(px, py, cell * (0.08f + dot * 0.011f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3E9FD0);
        canvas.drawCircle(center.x, center.y, cell * 0.30f, paint);
        paint.setColor(0xFFF7FBFF);
        canvas.drawCircle(center.x - cell * 0.05f, center.y - cell * 0.06f, cell * 0.10f, paint);
    }

    private void drawLabOneWay(Canvas canvas, float pulse) {
        RectF zone = labOneWayRect();
        drawLabOutlinedZone(canvas, zone, 0xFF2E9D89, "ONE-WAY MAIN");
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(dp(2.3f), cell * 0.10f));
        paint.setColor(withAlpha(0xFF1E8B7B, Math.round(150f + pulse * 42f)));
        for (int x = 6; x < 16; x += 2) {
            float cx = boardLeft + (x + 0.5f) * cell;
            float cy = zone.centerY();
            canvas.drawLine(cx - cell * 0.28f, cy, cx + cell * 0.30f, cy, paint);
            canvas.drawLine(cx + cell * 0.30f, cy, cx + cell * 0.08f, cy - cell * 0.20f, paint);
            canvas.drawLine(cx + cell * 0.30f, cy, cx + cell * 0.08f, cy + cell * 0.20f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabCrackField(Canvas canvas, float pulse) {
        RectF zone = labCrackRect();
        drawLabOutlinedZone(canvas, zone, labSecondaryState ? 0xFF44A8DD : 0xFFA46F45,
                labSecondaryState ? "LEAKING" : "CRACKED");
        for (int x = 8; x < 14; x++) {
            for (int y = 15; y < 22; y++) {
                if (!isLabCrackCell(new Cell(x, y))) {
                    continue;
                }
                PointF c = cellCenter(x, y);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(dp(1.1f), cell * 0.045f));
                paint.setColor(withAlpha(0xFF6E4930, Math.round(128f + pulse * 38f)));
                canvas.drawLine(c.x - cell * 0.26f, c.y - cell * 0.12f, c.x, c.y + cell * 0.05f, paint);
                canvas.drawLine(c.x, c.y + cell * 0.05f, c.x + cell * 0.22f, c.y - cell * 0.18f, paint);
                paint.setStyle(Paint.Style.FILL);
                if (labSecondaryState) {
                    paint.setColor(withAlpha(0xFF39AEE8, 118));
                    canvas.drawCircle(c.x, c.y + cell * 0.18f, cell * (0.20f + pulse * 0.08f), paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabMovingCrew(Canvas canvas, long now) {
        RectF lane = labCrewLaneRect();
        drawLabOutlinedZone(canvas, lane, 0xFFE1A13F, "MOVING CREW");
        RectF crew = labCrewRect(now);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFC84A);
        canvas.drawRoundRect(crew, cell * 0.12f, cell * 0.12f, paint);
        paint.setColor(0xFF3D3A32);
        float wheel = cell * 0.11f;
        canvas.drawCircle(crew.left + crew.width() * 0.25f, crew.bottom, wheel, paint);
        canvas.drawCircle(crew.right - crew.width() * 0.25f, crew.bottom, wheel, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.2f), cell * 0.05f));
        paint.setColor(0xFF8B6422);
        canvas.drawLine(crew.left + cell * 0.12f, crew.centerY(), crew.right - cell * 0.12f, crew.centerY(), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabSignalStorm(Canvas canvas, long now) {
        RectF zone = labStormBounds();
        drawLabOutlinedZone(canvas, zone, 0xFF8D62D7, "SIGNAL STORM");
        PointF c = labStormCenter(now);
        float radius = cell * 1.55f;
        paint.setShader(new RadialGradient(c.x, c.y, radius,
                new int[]{withAlpha(0xFFE5D8FF, 160), withAlpha(0xFF8F5EE2, 92), withAlpha(0xFF533E8A, 0)},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(c.x, c.y, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.2f), cell * 0.05f));
        paint.setColor(withAlpha(0xFF7F5ACD, 180));
        canvas.drawCircle(c.x, c.y, radius * 0.72f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabRootGrowth(Canvas canvas, long now) {
        RectF zone = labRootBounds();
        drawLabOutlinedZone(canvas, zone, 0xFF6C9E39, labSecondaryState ? "ROOTS WAKING" : "DORMANT ROOTS");
        float progress = labRootProgress(now);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int x = 6; x < 17; x++) {
            for (int y = 16; y < 28; y++) {
                Cell c = new Cell(x, y);
                float grow = labRootThreshold(x, y);
                if (progress <= grow) {
                    continue;
                }
                PointF p = cellCenter(x, y);
                float fade = clamp((progress - grow) / 0.22f, 0f, 1f);
                paint.setStrokeWidth(cell * (0.04f + 0.045f * fade));
                paint.setColor(withAlpha(0xFF6D4A2C, Math.round(95f + 96f * fade)));
                canvas.drawLine(p.x - cell * 0.34f, p.y + cell * 0.20f,
                        p.x + cell * 0.34f, p.y - cell * 0.13f, paint);
                paint.setColor(withAlpha(0xFF6AA647, Math.round(62f + 72f * fade)));
                canvas.drawPoint(p.x + cell * 0.18f, p.y - cell * 0.05f, paint);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabFumeWarning(Canvas canvas, float pulse) {
        RectF zone = labFumeBounds();
        drawLabOutlinedZone(canvas, zone, 0xFFB97A2F, "GAS FUMES");
        Stroke gas = firstCompletedLabStroke(Utility.GAS);
        if (gas == null) {
            return;
        }
        for (Cell c : gas.cells) {
            PointF p = cellCenter(c.x, c.y);
            float r = cell * (0.46f + 0.12f * (float) Math.sin(animSeconds * 3.5f + c.x));
            paint.setColor(withAlpha(0xFFC78B38, Math.round(48f + pulse * 48f)));
            canvas.drawCircle(p.x, p.y, r, paint);
        }
    }

    private void drawStandingObjects(Canvas canvas) {
        for (int depth = 1; depth <= GRID_H; depth++) {
            for (Blocker blocker : activeLevel.blockers) {
                if (!isFlatGroundBlocker(blocker) && blocker.y + blocker.h == depth) {
                    drawStandingBlocker(canvas, blocker);
                }
            }
            for (House house : activeLevel.houses) {
                if (house.y + house.h == depth) {
                    drawStandingHouse(canvas, house);
                }
            }
            for (Source source : activeLevel.sources) {
                if (source.y + 2 == depth) {
                    drawStandingSource(canvas, source);
                }
            }
        }
    }

    private void drawStandingBlocker(Canvas canvas, Blocker blocker) {
        drawBitmap(canvas, assets.get(blocker.asset), standingBlockerRect(blocker), 255);
    }

    private RectF standingBlockerRect(Blocker blocker) {
        Bitmap bitmap = assets.get(blocker.asset);
        RectF rect = visualAssetRect(bitmap, cellRect(blocker.x, blocker.y, blocker.w, blocker.h),
                largestAssetUnit(blocker.asset, Math.max(blocker.w, blocker.h)), 1.00f, true);
        scaleAndOffset(rect, calibrationProfile.layer("L", blockerCalibrationKey(blocker), 'A'));
        return standingRect(rect);
    }

    private void drawStandingHouse(Canvas canvas, House house) {
        Bitmap bitmap = assets.get(house.asset);
        RectF standing = standingHouseRect(house);
        drawBitmap(canvas, bitmap, standing, 255);
        drawHouseNeeds(canvas, house, standing);
    }

    private void drawLabObjectEffects(Canvas canvas, long now) {
        if (!mechanicsLab) {
            return;
        }
        if (labScenarioIndex == 9) {
            Stroke gas = firstCompletedLabStroke(Utility.GAS);
            if (gas != null) {
                float spacing = Math.max(cell * 1.35f, dp(18));
                float length = polylineLength(gas.points);
                for (float d = spacing * 0.45f; d < length; d += spacing) {
                    PointF p = pointAlong(gas.points, d);
                    float wave = 0.5f + 0.5f * (float) Math.sin(animSeconds * 3.3f + d * 0.031f);
                    paint.setColor(withAlpha(0xFFC18B3E, Math.round(52f + wave * 42f)));
                    canvas.drawCircle(p.x, p.y - cell * 0.08f, cell * (0.46f + wave * 0.18f), paint);
                }
            }
        }
    }

    private RectF standingHouseRect(House house) {
        Bitmap bitmap = assets.get(house.asset);
        RectF rect = visualAssetRect(bitmap, cellRect(house.x, house.y, house.w, house.h),
                largestAssetUnit(house.asset, Math.max(house.w, house.h)), 1.24f, true);
        scaleAndOffset(rect, calibrationProfile.layer("L", houseCalibrationKey(house), 'A'));
        return standingRect(rect);
    }

    private void drawHouseNeeds(Canvas canvas, House house, RectF standing) {
        ArrayList<Port> needs = houseNeeds(house);
        if (needs.isEmpty()) {
            return;
        }
        float badge = houseNeedBadgeSize();
        float spread = houseNeedSpread(standing, needs.size());
        for (int i = 0; i < needs.size(); i++) {
            Port port = needs.get(i);
            PointF center = houseNeedCenter(standing, needs.size(), i, badge, spread);
            float phase = animSeconds * (3.15f + port.utility.ordinal() * 0.16f) + port.id * 1.19f;
            float x = center.x;
            float y = center.y;
            if (!port.connected) {
                y -= badge * (0.04f + 0.045f * (float) Math.sin(phase));
            }
            float pulse = port.connected ? 1f : 1f + 0.035f * (float) Math.sin(phase);
            if (port.id == highlightedPortId && SystemClock.uptimeMillis() <= hintUntilMs) {
                pulse += 0.09f * (float) Math.sin(animSeconds * 8f);
            }
            float radius = badge * 0.5f * pulse;
            int alpha = port.connected ? 60 : 255;
            if (!port.connected) {
                drawUtilityAttentionEffect(canvas, port.utility, x, y, radius, phase, false);
            }
            paint.setColor(withAlpha(0xFFF4E0AE, alpha));
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(dp(0.85f), badge * 0.035f));
            paint.setColor(withAlpha(blend(port.utility.color, 0xFF4A3827, 0.34f), alpha));
            paint.setPathEffect(new DashPathEffect(new float[]{badge * 0.18f, badge * 0.13f}, 0f));
            canvas.drawCircle(x, y, radius * 0.93f, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            scratch.set(x - radius * 0.84f, y - radius * 0.84f, x + radius * 0.84f, y + radius * 0.84f);
            drawBitmap(canvas, assets.get(port.utility.iconAsset()), scratch, alpha);
        }
    }

    private ArrayList<Port> houseNeeds(House house) {
        ArrayList<Port> needs = new ArrayList<>();
        for (Port port : activeLevel.ports) {
            if (port.houseId == house.id) {
                needs.add(port);
            }
        }
        return needs;
    }

    private float houseNeedBadgeSize() {
        return dp(31);
    }

    private float houseNeedSpread(RectF standing, int count) {
        float badge = houseNeedBadgeSize();
        return count <= 1 ? 0f : Math.min(badge * 1.08f,
                Math.max(badge * 0.78f, (standing.width() + badge * 0.55f) / Math.max(1, count - 1)));
    }

    private PointF houseNeedCenter(RectF standing, int count, int index, float badge, float spread) {
        float offset = index - (count - 1) * 0.5f;
        float curve = Math.abs(offset) * badge * 0.10f;
        return new PointF(standing.centerX() + offset * spread, standing.top - badge * 0.38f + curve);
    }

    private void drawStandingSource(Canvas canvas, Source source) {
        Bitmap bitmap = assets.get(source.utility.iconAsset());
        RectF rect = sourceMarkerRect(source);
        float phase = animSeconds * (2.45f + source.utility.ordinal() * 0.12f)
                + (source.x * 0.31f + source.y * 0.19f);
        float pulse = 1f + 0.045f * (float) Math.sin(phase);
        scaleAboutCenter(rect, pulse, pulse);
        drawUtilityAttentionEffect(canvas, source.utility, rect.centerX(), rect.centerY(),
                Math.min(rect.width(), rect.height()) * 0.47f, phase, true);
        drawBitmap(canvas, bitmap, rect, 255);
    }

    private RectF sourceMarkerRect(Source source) {
        Bitmap bitmap = assets.get(source.utility.iconAsset());
        RectF rect = visualAssetRect(bitmap, cellRect(source.x, source.y, 2, 2), 2, 0.94f, true);
        scaleAndOffset(rect, calibrationProfile.layer("G", "source_badge", 'A'));
        scaleAndOffset(rect, calibrationProfile.layer("L", "source_" + source.utility.key, 'A'));
        scaleAboutCenter(rect, 0.82f, 0.82f);
        return standingRect(rect);
    }

    private void drawUtilityAttentionEffect(Canvas canvas, Utility utility, float x, float y,
                                            float radius, float phase, boolean provider) {
        float orbit = radius * (provider ? 1.10f : 1.34f);
        int alpha = provider ? 116 : 92;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.1f), radius * 0.09f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(utility.color, alpha));
        switch (utility) {
            case WATER:
                float ripple = orbit * (0.86f + 0.12f * (0.5f + 0.5f * (float) Math.sin(phase)));
                canvas.drawCircle(x, y, ripple, paint);
                paint.setColor(withAlpha(utility.color, alpha / 2));
                canvas.drawArc(x - orbit, y - orbit * 0.72f, x + orbit, y + orbit * 0.72f,
                        15f + phase * 18f, 105f, false, paint);
                break;
            case ELECTRIC:
                Path spark = new Path();
                float jitter = radius * (0.10f + 0.04f * (float) Math.sin(phase * 2f));
                spark.moveTo(x + orbit * 0.52f, y - orbit * 0.65f);
                spark.lineTo(x + orbit * 0.17f + jitter, y - orbit * 0.16f);
                spark.lineTo(x + orbit * 0.43f, y - orbit * 0.08f);
                spark.lineTo(x + orbit * 0.04f, y + orbit * 0.49f);
                canvas.drawPath(spark, paint);
                break;
            case INTERNET:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 2; i++) {
                    float angle = phase + i * (float) Math.PI;
                    canvas.drawCircle(x + (float) Math.cos(angle) * orbit,
                            y + (float) Math.sin(angle) * orbit * 0.68f,
                            Math.max(dp(1.5f), radius * 0.13f), paint);
                }
                break;
            case HEATING:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 2; i++) {
                    float rise = (phase * 0.18f + i * 0.48f) % 1f;
                    canvas.drawCircle(x + (i == 0 ? -radius * 0.30f : radius * 0.24f),
                            y - radius * (0.85f + rise * 0.55f),
                            Math.max(dp(1.3f), radius * (0.14f - rise * 0.04f)), paint);
                }
                break;
            case GAS:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 2; i++) {
                    float wobble = (float) Math.sin(phase + i * 2.1f);
                    canvas.drawCircle(x + radius * (0.52f + i * 0.26f),
                            y - radius * (0.34f + i * 0.31f) + wobble * radius * 0.10f,
                            Math.max(dp(1.3f), radius * (0.17f - i * 0.03f)), paint);
                }
                break;
            case SEWAGE:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) {
                    float rise = (phase * 0.16f + i * 0.31f) % 1f;
                    float bubbleX = x - radius * 0.42f + i * radius * 0.42f
                            + (float) Math.sin(phase + i) * radius * 0.08f;
                    float bubbleY = y - radius * (0.82f + rise * 0.62f);
                    paint.setColor(withAlpha(utility.color, Math.max(28, alpha - (int) (rise * 54f))));
                    canvas.drawCircle(bubbleX, bubbleY,
                            Math.max(dp(1.1f), radius * (0.15f - rise * 0.045f)), paint);
                }
                break;
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void scaleAboutCenter(RectF rect, float scaleX, float scaleY) {
        float centerX = rect.centerX();
        float centerY = rect.centerY();
        float halfWidth = rect.width() * scaleX * 0.5f;
        float halfHeight = rect.height() * scaleY * 0.5f;
        rect.set(centerX - halfWidth, centerY - halfHeight,
                centerX + halfWidth, centerY + halfHeight);
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

    private void drawCelebrationStars(Canvas canvas, long now) {
        for (int i = celebrationStars.size() - 1; i >= 0; i--) {
            CelebrationStar star = celebrationStars.get(i);
            float age = (now - star.bornMs) / 1000f;
            if (age < 0f) {
                continue;
            }
            if (age > 4.4f) {
                celebrationStars.remove(i);
                continue;
            }
            float x = star.x + star.vx * age;
            float y = star.y + star.vy * age + dp(142) * age * age;
            int alpha = age < 3.2f ? 255 : Math.round(255f * clamp((4.4f - age) / 1.2f, 0f, 1f));
            drawStar(canvas, x, y, star.radius, star.rotation + star.spin * age, withAlpha(star.color, alpha));
        }
    }

    private void drawStar(Canvas canvas, float centerX, float centerY, float radius, float rotation, int color) {
        Path star = new Path();
        for (int i = 0; i < 10; i++) {
            float angle = rotation - (float) Math.PI * 0.5f + i * (float) Math.PI / 5f;
            float r = (i & 1) == 0 ? radius : radius * 0.45f;
            float x = centerX + (float) Math.cos(angle) * r;
            float y = centerY + (float) Math.sin(angle) * r;
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.close();
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(star, paint);
    }

    private void drawStatus(Canvas canvas, long now) {
        if (now > statusUntilMs || status == null || status.length() == 0) {
            return;
        }
        float alpha = clamp((statusUntilMs - now) / 650f, 0f, 1f);
        float w = Math.min(getWidth() - dp(36), textWidth(status, dp(15)) + dp(44));
        float bottom = getHeight() - bottomReservedSpace - dp(16);
        scratch.set((getWidth() - w) * 0.5f, bottom - dp(36), (getWidth() + w) * 0.5f, bottom);
        paint.setColor(withAlpha(0xFF21372F, (int) (210 * alpha)));
        canvas.drawRoundRect(scratch, dp(8), dp(8), paint);
        textPaint.setColor(withAlpha(Color.WHITE, (int) (255 * alpha)));
        textPaint.setTextSize(dp(15));
        textPaint.setFakeBoldText(true);
        canvas.drawText(status, scratch.centerX(), scratch.centerY() + dp(5), textPaint);
        textPaint.setFakeBoldText(false);
    }

    private void drawTutorialGuide(Canvas canvas, long now) {
        if (!tutorialLevel || completedAtMs > 0L || activeLevel == null || activeLevel.sources.isEmpty()) {
            return;
        }
        Port lesson = activeLevel.nextOpenPort();
        if (lesson == null) {
            return;
        }
        int stage = tutorialConnectionsComplete();
        Source source = activeLevel.findSource(lesson.utility);
        RectF marker = sourceMarkerRect(source);
        PointF outlet = new PointF(marker.centerX(), marker.centerY());
        float glow = 1f + 0.12f * (float) Math.sin(now * 0.009f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(0xCCFFF0A9);
        canvas.drawCircle(outlet.x, outlet.y, dp(22) * glow, paint);
        paint.setStyle(Paint.Style.FILL);

        float panelWidth = Math.min(getWidth() - dp(34), dp(304));
        scratch.set((getWidth() - panelWidth) * 0.5f, dp(75),
                (getWidth() + panelWidth) * 0.5f, dp(143));
        paint.setColor(0xEEFFF7E5);
        canvas.drawRoundRect(scratch, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFE4C478);
        canvas.drawRoundRect(scratch, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFF28383B);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(14));
        String title;
        String detail;
        if (draggingPipe) {
            title = "Guide the " + activeUtility.title.toLowerCase(Locale.US) + " flow into the home";
            detail = "Touch any side of the house to finish neatly";
        } else if (stage == 0) {
            title = "1 of 3  Bring water home";
            detail = "Drag from the glowing water source into the house";
        } else if (stage == 1) {
            title = "2 of 3  Power around blockers";
            detail = "Obstacles stop pipes - route around them";
        } else {
            title = "3 of 3  Keep utilities separate";
            detail = "Sewage cannot cross water or power lines";
        }
        canvas.drawText(title, scratch.centerX(), scratch.top + dp(27), textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(dp(12));
        textPaint.setColor(0xFF66757B);
        canvas.drawText(detail, scratch.centerX(), scratch.top + dp(52), textPaint);
    }

    private int tutorialConnectionsComplete() {
        int connected = 0;
        for (Port port : activeLevel.ports) {
            if (port.connected) {
                connected++;
            }
        }
        return connected;
    }

    private void drawLabGuide(Canvas canvas, long now) {
        if (!mechanicsLab || completedAtMs > 0L) {
            return;
        }
        float width = Math.min(getWidth() - dp(20), dp(396));
        scratch.set((getWidth() - width) * 0.5f, dp(68), (getWidth() + width) * 0.5f, dp(145));
        paint.setColor(0xEFFFF8E6);
        canvas.drawRoundRect(scratch, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xFFE4C47C);
        canvas.drawRoundRect(scratch, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFF24363A);
        textPaint.setTextSize(dp(13));
        textPaint.setFakeBoldText(true);
        canvas.drawText(activeLevel.labTitle, scratch.centerX(), scratch.top + dp(19), textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(dp(10.5f));
        textPaint.setColor(0xFF66757B);
        canvas.drawText(activeLevel.labRule, scratch.centerX(), scratch.top + dp(39), textPaint);
        textPaint.setColor(0xFF384950);
        textPaint.setFakeBoldText(true);
        canvas.drawText(activeLevel.labGoal, scratch.centerX(), scratch.top + dp(57), textPaint);
        textPaint.setFakeBoldText(false);

        labPrevButton.set(scratch.left + dp(8), scratch.bottom + dp(7), scratch.left + dp(72), scratch.bottom + dp(35));
        labNextButton.set(scratch.right - dp(72), scratch.bottom + dp(7), scratch.right - dp(8), scratch.bottom + dp(35));
        drawLabNavButton(canvas, labPrevButton, "Prev");
        drawLabNavButton(canvas, labNextButton, "Next");
        RectF pagePill = new RectF(scratch.centerX() - dp(38), scratch.bottom + dp(8),
                scratch.centerX() + dp(38), scratch.bottom + dp(34));
        paint.setColor(0xDFFFF8E6);
        canvas.drawRoundRect(pagePill, dp(8), dp(8), paint);
        textPaint.setColor(0xFF66757B);
        textPaint.setTextSize(dp(11));
        canvas.drawText(String.format(Locale.US, "%d / %d", labScenarioIndex + 1, LAB_SCENARIO_COUNT),
                scratch.centerX(), scratch.bottom + dp(25), textPaint);
        if (labMistakes > 0) {
            textPaint.setColor(0xFFD14B31);
            textPaint.setFakeBoldText(true);
            canvas.drawText("Setbacks: " + labMistakes + "  -  Reset for a clean run",
                    scratch.centerX(), scratch.bottom + dp(51), textPaint);
            textPaint.setFakeBoldText(false);
        }
    }

    private void drawLabNavButton(Canvas canvas, RectF button, String label) {
        paint.setColor(0xFF2F8AD4);
        canvas.drawRoundRect(button, dp(8), dp(8), paint);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(11));
        textPaint.setFakeBoldText(true);
        canvas.drawText(label, button.centerX(), button.centerY() + dp(4), textPaint);
        textPaint.setFakeBoldText(false);
    }

    private PointF projectGroundPoint(PointF point) {
        float[] values = {point.x, point.y};
        groundMatrix.mapPoints(values);
        return new PointF(values[0], values[1]);
    }

    private void drawCompletion(Canvas canvas, long now) {
        float t = clamp((now - completedAtMs) / 650f, 0f, 1f);
        float eased = overshoot.getInterpolation(t);
        paint.setColor(0x99000000);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        drawCelebrationStars(canvas, now);
        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * 0.43f;
        float panelW = Math.min(getWidth() - dp(42), dp(336));
        float panelH = dp(302);
        canvas.save();
        canvas.scale(eased, eased, centerX, centerY);
        scratch.set(centerX - panelW * 0.5f, centerY - panelH * 0.5f, centerX + panelW * 0.5f, centerY + panelH * 0.5f);
        paint.setColor(0xFFF9F6EF);
        canvas.drawRoundRect(scratch, dp(22), dp(22), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFE4CB93);
        canvas.drawRoundRect(scratch, dp(22), dp(22), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFF223236);
        textPaint.setTextSize(dp(28));
        textPaint.setFakeBoldText(true);
        String title = tutorialLevel ? "Tutorial Complete" : mechanicsLab ? "Lab Cleared" : assistedFinish ? "Solution Shown" : "Level Complete";
        canvas.drawText(title, centerX, scratch.top + dp(48), textPaint);
        textPaint.setFakeBoldText(false);
        textPaint.setTextSize(dp(15));
        textPaint.setColor(0xFF536167);
        String timeLabel = tutorialLevel ? "You brought three services home"
                : mechanicsLab ? "You handled the reactive service district"
                : assistedFinish ? "Finish the level yourself to earn stars" : "Time  " + formatDuration(completionDurationMs);
        canvas.drawText(timeLabel, centerX, scratch.top + dp(78), textPaint);

        float starY = scratch.top + dp(123);
        for (int i = 0; i < 3; i++) {
            float starX = centerX + (i - 1) * dp(54);
            boolean earned = tutorialLevel || (mechanicsLab && labMistakes == 0) || (!assistedFinish && i < completionStars);
            drawStar(canvas, starX, starY, dp(21), 0f, earned ? 0xFFFFC94E : 0xFFE1DFD9);
            drawStar(canvas, starX, starY - dp(2), dp(15), 0f, earned ? 0xFFFFE38A : 0xFFF1EFEA);
        }

        textPaint.setTextSize(dp(14));
        textPaint.setColor(0xFF68747A);
        String guidance = completionGuidance();
        canvas.drawText(guidance, centerX, scratch.top + dp(169), textPaint);
        if (!assistedFinish && completionStars < 3) {
            textPaint.setTextSize(dp(13));
            textPaint.setColor(0xFF96743B);
            long nextGoal = completionStars >= 2 ? threeStarGoalMs : twoStarGoalMs;
            canvas.drawText("Next star: " + formatDuration(nextGoal), centerX, scratch.top + dp(193), textPaint);
        }

        continueButton.set(centerX - dp(112), scratch.bottom - dp(66), centerX + dp(112), scratch.bottom - dp(18));
        paint.setColor(0xFF2F8AD4);
        canvas.drawRoundRect(continueButton, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF2272B5);
        canvas.drawRoundRect(continueButton, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(18));
        textPaint.setFakeBoldText(true);
        canvas.drawText("Continue", continueButton.centerX(), continueButton.centerY() + dp(6), textPaint);
        textPaint.setFakeBoldText(false);
        canvas.restore();
    }

    private String completionGuidance() {
        if (tutorialLevel) {
            return "Route around blockers and keep flows apart";
        }
        if (mechanicsLab) {
            return labMistakes == 0 ? "Clean run - this rule felt readable" : "Replay the experiment for a clean run";
        }
        if (assistedFinish) {
            return "No time rating for an assisted solution";
        }
        if (completionStars >= 3) {
            return "Excellent pace - three star time";
        }
        long goal = completionStars >= 2 ? threeStarGoalMs : twoStarGoalMs;
        long improvement = Math.max(1000L, completionDurationMs - goal);
        return "Go " + formatDuration(improvement) + " faster for the next star";
    }

    private String formatDuration(long durationMs) {
        long seconds = Math.max(0L, (durationMs + 500L) / 1000L);
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private boolean handleGameTouch(MotionEvent event) {
        if (activeLevel == null) {
            return true;
        }
        float rawX = event.getX();
        float rawY = event.getY();
        if (completedAtMs > 0L) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && continueButton.contains(rawX, rawY)) {
                if (mechanicsLab && labScenarioIndex < LAB_SCENARIO_COUNT - 1) {
                    startLabScenario(labScenarioIndex + 1);
                } else {
                    returnHome();
                }
            }
            return true;
        }
        if (autoSolving) {
            return true;
        }
        if (mechanicsLab && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && (labPrevButton.contains(rawX, rawY) || labNextButton.contains(rawX, rawY))) {
            return true;
        }
        if (mechanicsLab && event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (labPrevButton.contains(rawX, rawY)) {
                startLabScenario(labScenarioIndex - 1);
                return true;
            }
            if (labNextButton.contains(rawX, rawY)) {
                startLabScenario(labScenarioIndex + 1);
                return true;
            }
        }
        PointF groundTouch = unprojectGround(rawX, rawY);
        float touchX = groundTouch.x;
        float touchY = groundTouch.y;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = touchX;
                downY = lastY = touchY;
                movedDuringTouch = false;
                touchedStrokeIndex = -1;
                pressedButton = hitButton(rawX, rawY);
                if (pressedButton > 0) {
                    return true;
                }
                Source source = hitSource(rawX, rawY);
                if (source != null && activeLevel.hasOpenPort(source.utility)) {
                    if (tutorialLevel && activeLevel.nextOpenPort() != null
                            && activeLevel.nextOpenPort().utility != source.utility) {
                        status("Start with the glowing " + activeLevel.nextOpenPort().utility.title.toLowerCase(Locale.US) + " source");
                        return true;
                    }
                    beginPipe(source.utility, sourceConnectionPoint(source), sourceRouteCell(source), source, null, null);
                    return true;
                }
                StrokeHit networkHit = hitNetwork(touchX, touchY);
                if (networkHit != null) {
                    touchedStrokeIndex = networkHit.strokeIndex;
                    if (activeLevel.hasOpenPort(networkHit.utility)) {
                        beginPipe(networkHit.utility, networkHit.point, networkHit.cell, null, null, null);
                    }
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingPipe) {
                    if (Math.hypot(touchX - downX, touchY - downY) > dp(8)) {
                        movedDuringTouch = true;
                    }
                    addActivePoint(touchX, touchY, rawX, rawY, false);
                } else if (pressedButton <= 0 && Math.hypot(touchX - downX, touchY - downY) > dp(8)) {
                    movedDuringTouch = true;
                }
                lastX = touchX;
                lastY = touchY;
                return true;
            case MotionEvent.ACTION_UP:
                if (touchedStrokeIndex >= 0 && !movedDuringTouch
                        && Math.hypot(touchX - downX, touchY - downY) <= dp(10)) {
                    cancelActivePipe();
                    liftStrokeAt(touchedStrokeIndex);
                    touchedStrokeIndex = -1;
                    return true;
                }
                if (draggingPipe) {
                    finishPipe(touchX, touchY, rawX, rawY);
                } else if (pressedButton > 0) {
                    int button = pressedButton;
                    pressedButton = -1;
                    if (button == hitButton(rawX, rawY)) {
                        runButton(button);
                    }
                }
                touchedStrokeIndex = -1;
                pressedButton = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelActivePipe();
                touchedStrokeIndex = -1;
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
            if (mechanicsLab) {
                resetLabExperiment();
            }
            resetDynamicMechanics();
            autoSolvePlan.clear();
            autoSolving = false;
            completedAtMs = 0L;
            levelStartedMs = SystemClock.uptimeMillis();
            completionDurationMs = 0L;
            completionStars = 0;
            assistedFinish = false;
            celebrationStars.clear();
            hintPlan = null;
            highlightedPortId = -1;
            status("Fresh pipes");
        } else if (button == 3) {
            if (mechanicsLab) {
                status("Lab test: solve is disabled");
                return;
            }
            requestRewardedHelp(true, this::solveLevel);
        } else if (button == 4) {
            undoLast();
        } else if (button == 5) {
            if (mechanicsLab) {
                status("Try each reaction, then Reset");
                return;
            }
            requestRewardedHelp(false, this::showHint);
        }
    }

    private void requestRewardedHelp(boolean solve, Runnable action) {
        if (navigationListener == null) {
            action.run();
            return;
        }
        navigationListener.onRewardedHelpRequested(solve, action);
    }

    private void beginPipe(Utility utility, PointF start, Cell startCell, Source source, Port port, Direction startDirection) {
        draggingPipe = true;
        activeUtility = utility;
        activeStartCell = startCell;
        activeStartPoint = start;
        activeStartSource = source;
        activeStartPort = port;
        activeStartDirection = startDirection;
        activeLeadReleased = startDirection == null;
        hintPlan = null;
        activePoints.clear();
        activePoints.add(start);
        if (startDirection != null) {
            activePoints.add(new PointF(start.x + startDirection.dx * cell * 0.22f, start.y + startDirection.dy * cell * 0.22f));
        }
        status(String.format(Locale.US, "%s flowing", utility.title));
    }

    private void addActivePoint(float x, float y, float screenX, float screenY, boolean force) {
        if (!draggingPipe) {
            return;
        }
        PointF last = activePoints.get(activePoints.size() - 1);
        float clampedX = clamp(x, boardLeft, boardLeft + GRID_W * cell);
        float clampedY = clamp(y, boardTop, boardTop + GRID_H * cell);
        PointF guided = guidedActivePoint(clampedX, clampedY);
        if (force || distance(last.x, last.y, guided.x, guided.y) >= Math.max(dp(4), cell * 0.08f)) {
            activePoints.add(guided);
            if (triggerLabDynamicContact()) {
                return;
            }
            if (triggerGeneratedDynamicContact()) {
                return;
            }
            String error = validateActivePath();
            if (error != null) {
                rejectPipe(error);
                return;
            }
        }
    }

    private PointF guidedActivePoint(float x, float y) {
        PointF point = new PointF(x, y);
        if (activeStartDirection != null && activeStartPoint != null && !activeLeadReleased) {
            float dx = point.x - activeStartPoint.x;
            float dy = point.y - activeStartPoint.y;
            float along = dx * activeStartDirection.dx + dy * activeStartDirection.dy;
            float side = -dx * activeStartDirection.dy + dy * activeStartDirection.dx;
            float minLead = cell * 0.22f;
            if (along >= cell * 0.34f
                    || distance(point.x, point.y, activeStartPoint.x, activeStartPoint.y) >= cell * 0.52f
                    || Math.abs(side) >= cell * 0.38f) {
                activeLeadReleased = true;
                return point;
            }
            if (along < minLead) {
                side = clamp(side, -cell * 0.28f, cell * 0.28f);
                point.x = activeStartPoint.x + activeStartDirection.dx * minLead - activeStartDirection.dy * side;
                point.y = activeStartPoint.y + activeStartDirection.dy * minLead + activeStartDirection.dx * side;
            }
        }
        return point;
    }

    private void finishPipe(float x, float y, float screenX, float screenY) {
        addActivePoint(x, y, screenX, screenY, true);
        if (!draggingPipe) {
            return;
        }
        FinishTouch finish = findFinishTouch(activePoints, new PointF(screenX, screenY));
        if (finish == null) {
            rejectPipe("Connect to a matching home");
            return;
        }
        completePipe(finish);
    }

    private void completePipe(FinishTouch finish) {
        Port port = finish.port;
        ArrayList<PointF> cleanApproach = trimStrokeAtVisibleHouse(activePoints, finish.house);
        if (cleanApproach.size() < 2) {
            rejectPipe("Draw into the house");
            return;
        }
        cleanApproach.set(0, activeStartPoint);
        Stroke stroke = new Stroke(activeUtility, port.id, simplifyStroke(cleanApproach),
                new ArrayList<>(cellsTouched(cleanApproach)));
        String error = validateStroke(stroke);
        if (error != null) {
            rejectPipe(error);
            return;
        }
        stroke = tuckStrokeUnderHouse(stroke, finish.house);
        activeLevel.strokes.add(stroke);
        port.connected = true;
        highlightedPortId = -1;
        PointF burstAt = houseConnectionPoint(finish.house);
        spawnBurst(burstAt.x, burstAt.y, port.utility.color, 18);
        requestSound(port.utility.usesConnector ? "electric_connect" : "liquid_connect");
        String labMessage = mechanicsLab ? applyLabCompletionTransition(port, stroke) : applyGeneratedMechanicTransition(port, stroke);
        if (labMessage != null) {
            status(labMessage);
        } else {
            status(String.format(Locale.US, "%s connected", port.utility.title));
        }
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
        activeStartSource = null;
        activeStartPort = null;
        activeStartDirection = null;
        activeLeadReleased = false;
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
        cancelActivePipe();
    }

    private void cancelActivePipe() {
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
        activeStartSource = null;
        activeStartPort = null;
        activeStartDirection = null;
        activeLeadReleased = false;
        activePoints.clear();
    }

    private void resetLabExperiment() {
        labGateOpen = false;
        labSecondaryState = false;
        labMistakes = 0;
        labStartedMs = SystemClock.uptimeMillis();
        labGateChangedMs = labStartedMs;
        labTriggerMs = 0L;
        labAccidentFlashUntilMs = 0L;
        labStoredProgress = 0f;
    }

    private void resetDynamicMechanics() {
        dynamicGateOpen = false;
        dynamicGateChangedMs = SystemClock.uptimeMillis();
        dynamicExplosionUntilMs = 0L;
        dynamicHardResetAtMs = 0L;
        dynamicDamagedHouses.clear();
    }

    private String applyLabCompletionTransition(Port port, Stroke stroke) {
        long now = SystemClock.uptimeMillis();
        switch (labScenarioIndex) {
            case 0:
                if (port.id == 1 && !labGateOpen) {
                    openLabGate(now, labGateCenter());
                    return "Pump online - the service gate opened";
                }
                break;
            case 1:
                if (port.utility == Utility.SEWAGE && !labGateOpen) {
                    labStoredProgress = labPatchFloodProgress(now);
                    openLabGate(now, centerOf(labPatchFloodBounds()));
                    return "Drain online - the scattered flood patches are receding";
                }
                break;
            case 4:
                if (port.utility == Utility.WATER && strokeTouchesLabCrack(stroke)) {
                    labSecondaryState = true;
                    labTriggerMs = now;
                    spawnBurst(centerOf(labCrackRect()).x, centerOf(labCrackRect()).y, 0xFF39AEE8, 18);
                    return "Cracked ground is leaking - keep power out of the puddles";
                }
                break;
            case 8:
                if (port.utility == Utility.WATER && !labSecondaryState) {
                    labSecondaryState = true;
                    labTriggerMs = now;
                    spawnBurst(centerOf(labRootBounds()).x, centerOf(labRootBounds()).y, 0xFF6AA647, 20);
                    return "Water woke the roots - gas needs a clean path";
                }
                break;
            case 9:
                if (port.utility == Utility.GAS) {
                    return "Gas fumes are visible now - keep heat away from them";
                }
                break;
            case 2:
            case 3:
            case 5:
            case 6:
            case 7:
                break;
            default:
                break;
        }
        return null;
    }

    private String applyGeneratedMechanicTransition(Port port, Stroke stroke) {
        if (activeLevel == null || activeLevel.mechanic == DynamicMechanic.NONE) {
            return null;
        }
        if (activeLevel.mechanic == DynamicMechanic.PUMP_GATE
                && port.id == activeLevel.mechanicTriggerPortId && !dynamicGateOpen) {
            dynamicGateOpen = true;
            dynamicGateChangedMs = SystemClock.uptimeMillis();
            labGateChangedMs = dynamicGateChangedMs;
            RectF gate = dynamicGateRect();
            spawnBurst(gate.centerX(), gate.centerY(), 0xFF2EC893, 24);
            return "Pump online - the crossing opened";
        }
        if (activeLevel.mechanic == DynamicMechanic.FUME_SPLIT && stroke.utility == Utility.GAS) {
            return "Gas fumes are drifting - keep heat clear";
        }
        return null;
    }

    private void openLabGate(long now, PointF at) {
        labGateOpen = true;
        labGateChangedMs = now;
        labTriggerMs = now;
        spawnBurst(at.x, at.y, 0xFF2EC893, 22);
    }

    private PointF centerOf(RectF rect) {
        return new PointF(rect.centerX(), rect.centerY());
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
            PointF p = houseConnectionPoint(activeLevel.findHouse(port.houseId));
            spawnBurst(p.x, p.y, 0xFFECCB86, 8);
        }
        completedAtMs = 0L;
        hintPlan = null;
        highlightedPortId = -1;
        status("Pipe lifted");
    }

    private void liftStrokeAt(int strokeIndex) {
        if (strokeIndex < 0 || strokeIndex >= activeLevel.strokes.size()) {
            return;
        }
        Stroke removed = activeLevel.strokes.remove(strokeIndex);
        ArrayList<Cell> severed = new ArrayList<>(removed.cells);
        int lifted = disconnectPort(removed.portId) ? 1 : 0;
        boolean removedDependency;
        do {
            removedDependency = false;
            for (int i = activeLevel.strokes.size() - 1; i >= 0; i--) {
                Stroke candidate = activeLevel.strokes.get(i);
                if (candidate.utility != removed.utility || candidate.cells.isEmpty()
                        || !touchesAnyCell(candidate.cells.get(0), severed)) {
                    continue;
                }
                activeLevel.strokes.remove(i);
                severed.addAll(candidate.cells);
                if (disconnectPort(candidate.portId)) {
                    lifted++;
                }
                removedDependency = true;
            }
        } while (removedDependency);
        completedAtMs = 0L;
        hintPlan = null;
        highlightedPortId = -1;
        status(lifted > 1 ? "Branch lifted" : "Pipe lifted");
    }

    private boolean touchesAnyCell(Cell start, ArrayList<Cell> cells) {
        for (Cell cell : cells) {
            if (cell.equals(start)) {
                return true;
            }
        }
        return false;
    }

    private boolean disconnectPort(int portId) {
        Port port = activeLevel.findPort(portId);
        if (port == null) {
            return false;
        }
        port.connected = false;
        PointF p = houseConnectionPoint(activeLevel.findHouse(port.houseId));
        spawnBurst(p.x, p.y, 0xFFECCB86, 8);
        return true;
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
        Stroke display = displaySolutionStroke(new Stroke(port.utility, port.id, route.points, route.cells));
        return new HintPlan(port.id, port.utility, display.points, skipStrokeIndex);
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
        if (activeLevel.finished) {
            return;
        }
        activeLevel.finished = true;
        maxUnlocked = Math.max(maxUnlocked, activeLevel.number + 1);
        completedAtMs = SystemClock.uptimeMillis();
        completionDurationMs = Math.max(1000L, completedAtMs - levelStartedMs);
        threeStarGoalMs = (22L + activeLevel.ports.size() * 10L + activeLevel.sources.size() * 4L) * 1000L;
        twoStarGoalMs = Math.round(threeStarGoalMs * 1.55f);
        completionStars = tutorialLevel ? 3 : assistedFinish ? 0 : completionDurationMs <= threeStarGoalMs ? 3 : completionDurationMs <= twoStarGoalMs ? 2 : 1;
        spawnCompletionStars();
        status("Level complete");
        if (navigationListener != null) {
            navigationListener.onLevelCompleted(activeLevel.number, maxUnlocked);
        }
    }

    private void spawnCompletionStars() {
        celebrationStars.clear();
        long now = SystemClock.uptimeMillis();
        int count = assistedFinish ? 26 : 44 + completionStars * 10;
        int[] colors = {0xFFFFC94E, 0xFFFFE28A, 0xFF4BA4E9, 0xFF5FD3AE, 0xFFFF7C83};
        for (int i = 0; i < count; i++) {
            float side = i % 2 == 0 ? 1f : -1f;
            float x = getWidth() * 0.5f + side * (getWidth() * (0.18f + random.nextFloat() * 0.34f));
            float y = getHeight() * (0.78f + random.nextFloat() * 0.16f);
            float vx = -side * (dp(24) + random.nextFloat() * dp(74));
            float vy = -(dp(185) + random.nextFloat() * dp(150));
            celebrationStars.add(new CelebrationStar(x, y, vx, vy, dp(5) + random.nextFloat() * dp(8),
                    colors[random.nextInt(colors.length)], random.nextFloat() * 6.28f,
                    (random.nextFloat() - 0.5f) * 6.5f, now + random.nextInt(360)));
        }
    }

    private void returnHome() {
        autoSolving = false;
        autoSolvePlan.clear();
        screen = SCREEN_HOME;
        homeScrollReady = false;
        if (navigationListener != null) {
            navigationListener.onReturnHome(maxUnlocked, activeLevel == null ? 1 : activeLevel.number);
        }
    }

    private void solveLevel() {
        activeLevel.resetForPlay();
        resetDynamicMechanics();
        autoSolvePlan.clear();
        autoSolving = false;
        completedAtMs = 0L;
        assistedFinish = true;
        celebrationStars.clear();
        hintPlan = null;
        highlightedPortId = -1;
        ArrayList<Stroke> displayPlan = buildAutoSolveDisplayPlan();
        if (displayPlan != null) {
            autoSolvePlan.addAll(displayPlan);
            startAutoSolveAnimation();
            return;
        }
        status("No valid solution route");
        requestSound("fail");
        activeLevel.strokes.clear();
    }

    private ArrayList<Stroke> buildAutoSolveDisplayPlan() {
        ArrayList<Stroke> logicalPlan = null;
        if (activeLevel.hiddenSolution.size() == activeLevel.ports.size()
                && validatePlannedSolution(activeLevel, activeLevel.hiddenSolution)) {
            logicalPlan = new ArrayList<>(activeLevel.hiddenSolution);
        }
        if (logicalPlan == null) {
            logicalPlan = rebuildLogicalSolutionPlan();
        }
        if (logicalPlan == null || !validatePlannedSolution(activeLevel, logicalPlan)) {
            return null;
        }
        ArrayList<Stroke> displayPlan = displayPlanFromLogical(logicalPlan, true);
        if (displayPlanIsVisuallyClean(displayPlan)) {
            return displayPlan;
        }
        ArrayList<Stroke> decoratedPlan = displayPlanFromLogical(logicalPlan, false);
        if (displayPlanIsVisuallyClean(decoratedPlan)) {
            return decoratedPlan;
        }
        return displayPlan;
    }

    private ArrayList<Stroke> rebuildLogicalSolutionPlan() {
        ArrayList<Stroke> solved = new ArrayList<>();
        ArrayList<Port> order = new ArrayList<>();
        if (activeLevel.mechanic == DynamicMechanic.PUMP_GATE && activeLevel.mechanicTriggerPortId >= 0) {
            Port trigger = activeLevel.findPort(activeLevel.mechanicTriggerPortId);
            if (trigger != null) {
                order.add(trigger);
            }
        }
        for (Port port : activeLevel.ports) {
            if (!order.contains(port)) {
                order.add(port);
            }
        }
        for (Port port : order) {
            Route route = findPlannedRoute(activeLevel, port, solved);
            if (route == null) {
                return null;
            }
            Stroke stroke = new Stroke(port.utility, port.id, route.points, route.cells);
            solved.add(stroke);
        }
        return solved;
    }

    private ArrayList<Stroke> displayPlanFromLogical(ArrayList<Stroke> logicalPlan, boolean cellOnly) {
        ArrayList<Stroke> displayPlan = new ArrayList<>();
        for (Stroke stroke : logicalPlan) {
            displayPlan.add(cellOnly ? displayCellOnlySolutionStroke(stroke) : displaySolutionStroke(stroke));
        }
        return displayPlan;
    }

    private boolean displayPlanIsVisuallyClean(ArrayList<Stroke> plan) {
        for (int i = 0; i < plan.size(); i++) {
            Stroke first = plan.get(i);
            ArrayList<PointF> firstVisible = visibleSolveIntersectionPoints(first);
            if (firstVisible.size() < 2 || selfIntersectsVisible(firstVisible)) {
                return false;
            }
            for (int j = i + 1; j < plan.size(); j++) {
                Stroke second = plan.get(j);
                if (first.utility != second.utility
                        && strokesIntersectVisible(firstVisible, visibleSolveIntersectionPoints(second))) {
                    return false;
                }
            }
        }
        return true;
    }

    private ArrayList<PointF> visibleSolveIntersectionPoints(Stroke stroke) {
        ArrayList<PointF> visible = new ArrayList<>(stroke.points);
        Port port = activeLevel == null ? null : activeLevel.findPort(stroke.portId);
        House house = port == null ? null : activeLevel.findHouse(port.houseId);
        if (house != null && visible.size() > 2) {
            PointF end = visible.get(visible.size() - 1);
            PointF tucked = houseConnectionPoint(house);
            if (distance(end.x, end.y, tucked.x, tucked.y) < cell * 0.85f) {
                visible.remove(visible.size() - 1);
            }
        }
        return visible;
    }

    private Stroke displaySolutionStroke(Stroke stroke) {
        Source source = activeLevel.findSource(stroke.utility);
        Port port = activeLevel.findPort(stroke.portId);
        ArrayList<PointF> points = new ArrayList<>();
        if (!stroke.cells.isEmpty()) {
            Cell first = stroke.cells.get(0);
            if (source != null && first.equals(sourceRouteCell(source))) {
                addRoutePoint(points, sourceConnectionPoint(source));
            } else {
                addRoutePoint(points, cellCenter(first.x, first.y));
            }
            appendTurningPoints(points, stroke.cells);
        } else {
            points.addAll(stroke.points);
        }
        if (port != null) {
            addRoutePoint(points, portMouthPoint(port));
            House house = activeLevel.findHouse(port.houseId);
            if (house != null) {
                addRoutePoint(points, houseConnectionPoint(house));
            }
        }
        return new Stroke(stroke.utility, stroke.portId, points, new ArrayList<>(stroke.cells), true);
    }

    private Stroke displayCellOnlySolutionStroke(Stroke stroke) {
        ArrayList<PointF> points = new ArrayList<>();
        appendTurningPoints(points, stroke.cells);
        Port port = activeLevel.findPort(stroke.portId);
        if (port != null) {
            addRoutePoint(points, portMouthPoint(port));
            House house = activeLevel.findHouse(port.houseId);
            if (house != null) {
                addRoutePoint(points, houseConnectionPoint(house));
            }
        }
        return new Stroke(stroke.utility, stroke.portId, points, new ArrayList<>(stroke.cells), true);
    }

    private void appendTurningPoints(ArrayList<PointF> points, ArrayList<Cell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0 && i < cells.size() - 1) {
                Cell before = cells.get(i - 1);
                Cell current = cells.get(i);
                Cell after = cells.get(i + 1);
                if (current.x - before.x == after.x - current.x
                        && current.y - before.y == after.y - current.y) {
                    continue;
                }
            }
            Cell cellPoint = cells.get(i);
            addRoutePoint(points, cellCenter(cellPoint.x, cellPoint.y));
        }
    }

    private void startAutoSolveAnimation() {
        if (autoSolvePlan.isEmpty()) {
            status("No solution route");
            requestSound("fail");
            return;
        }
        autoSolving = true;
        autoSolveStartedMs = SystemClock.uptimeMillis();
        status("Solution flowing");
        invalidate();
    }

    private void updateAutoSolve(long now) {
        if (!autoSolving || activeLevel == null) {
            return;
        }
        long perStroke = 460L;
        int completedStrokes = Math.min(autoSolvePlan.size(),
                (int) ((now - autoSolveStartedMs) / perStroke));
        while (activeLevel.strokes.size() < completedStrokes) {
            Stroke stroke = autoSolvePlan.get(activeLevel.strokes.size());
            activeLevel.strokes.add(stroke);
            Port port = activeLevel.findPort(stroke.portId);
            if (port != null) {
                port.connected = true;
                if (!mechanicsLab) {
                    applyGeneratedMechanicTransition(port, stroke);
                }
                PointF mouth = houseConnectionPoint(activeLevel.findHouse(port.houseId));
                spawnBurst(mouth.x, mouth.y, port.utility.color, 10);
            }
            requestSound(stroke.utility.usesConnector ? "electric_connect" : "liquid_connect");
        }
        if (activeLevel.strokes.size() == autoSolvePlan.size()) {
            autoSolving = false;
            autoSolvePlan.clear();
            completeLevel();
        }
    }

    private void drawAutoSolveStroke(Canvas canvas, long now) {
        if (!autoSolving || activeLevel == null || activeLevel.strokes.size() >= autoSolvePlan.size()) {
            return;
        }
        int index = activeLevel.strokes.size();
        float progress = clamp((now - autoSolveStartedMs - index * 460f) / 460f, 0f, 1f);
        Stroke stroke = autoSolvePlan.get(index);
        Path fullPath = strokePath(stroke);
        PathMeasure measure = new PathMeasure(fullPath, false);
        float eased = progress * progress * (3f - 2f * progress);
        float visibleLength = measure.getLength() * eased;
        if (visibleLength <= 0f) {
            return;
        }
        Path visiblePath = new Path();
        measure.getSegment(0f, visibleLength, visiblePath, true);
        visiblePath.rLineTo(0f, 0f);
        drawUtilityPath(canvas, visiblePath, stroke.utility, true, 1f);
        float[] head = new float[2];
        if (measure.getPosTan(visibleLength, head, null)) {
            paint.setColor(withAlpha(Color.WHITE, 190));
            canvas.drawCircle(head[0], head[1], Math.max(dp(2), cell * 0.09f), paint);
        }
    }

    private boolean triggerLabDynamicContact() {
        if (!mechanicsLab || activeUtility == null || activePoints.size() < 2) {
            return false;
        }
        Set<Cell> touched = cellsTouched(activePoints);
        long now = SystemClock.uptimeMillis();
        for (Cell cell : touched) {
            switch (labScenarioIndex) {
                case 0:
                    if (!labGateOpen && isLabGateCell(cell)) {
                        return labSetback("Gate closed - power the water pump first", 0xFFE1A13F);
                    }
                    break;
                case 1:
                    if (activeUtility == Utility.ELECTRIC && isLabPatchFloodCell(cell, now)) {
                        return labSetback("Flood patch reached that lane - drain it or move faster", 0xFF36A7E7);
                    }
                    break;
                case 2:
                    if (activeUtility == Utility.ELECTRIC && isLabSpinnerCell(cell, now)) {
                        return labSetback("The spinning spray clipped the power line", 0xFF54A6DA);
                    }
                    break;
                case 3:
                    if (isLabOneWayCell(cell) && !activeSegmentMovesMostly(Direction.RIGHT)) {
                        return labSetback("That main only carries flow to the right", 0xFF2E9D89);
                    }
                    break;
                case 4:
                    if (activeUtility == Utility.ELECTRIC && isLabLeakCell(cell)) {
                        return labSetback("Power touched the leak puddle - reroute around cracked ground", 0xFF36A7E7);
                    }
                    break;
                case 5:
                    if (isLabCrewCell(cell, now)) {
                        return labSetback("The moving crew crossed your active line", 0xFFE1A13F);
                    }
                    break;
                case 7:
                    if (activeUtility == Utility.INTERNET && isLabStormCell(cell, now)) {
                        return labSetback("Signal storm scrambled the net line", 0xFF8D62D7);
                    }
                    break;
                case 8:
                    if (activeUtility == Utility.GAS && isLabRootCell(cell, now)) {
                        return labSetback("Roots grabbed the gas line - beat the growth or route wide", 0xFF6AA647);
                    }
                    break;
                case 9:
                    if (activeUtility == Utility.HEATING && isLabFumeCell(cell)) {
                        return labSetback("Heat crossed gas fumes - separate the routes", 0xFFB97A2F);
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private boolean labSetback(String message, int color) {
        PointF impact = activePoints.get(activePoints.size() - 1);
        labMistakes++;
        labAccidentFlashUntilMs = SystemClock.uptimeMillis() + 340L;
        spawnBurst(impact.x, impact.y, color, 24);
        requestSound("fail");
        status(message);
        cancelActivePipe();
        return true;
    }

    private boolean triggerGeneratedDynamicContact() {
        if (mechanicsLab || activeLevel == null || activeLevel.mechanic == DynamicMechanic.NONE
                || activeUtility == null || activePoints.size() < 2) {
            return false;
        }
        Set<Cell> touched = cellsTouched(activePoints);
        switch (activeLevel.mechanic) {
            case PUMP_GATE:
                if (!dynamicGateOpen && activeUtility != activeLevel.mechanicUtility) {
                    for (Cell cell : touched) {
                        if (isDynamicGateCell(cell)) {
                            return dynamicSetback("Pump gate closed - connect the pump service first", 0xFFE1A13F);
                        }
                    }
                }
                break;
            case FUME_SPLIT:
                if (activeUtility == Utility.HEATING && completedGasNetworkExists()
                        && activePathTouchesGasFumes(activePoints)) {
                    triggerGasExplosion();
                    return true;
                }
                break;
            case NONE:
            default:
                break;
        }
        return false;
    }

    private boolean dynamicSetback(String message, int color) {
        PointF impact = activePoints.get(activePoints.size() - 1);
        spawnBurst(impact.x, impact.y, color, 24);
        requestSound("fail");
        status(message);
        cancelActivePipe();
        return true;
    }

    private boolean completedGasNetworkExists() {
        if (activeLevel == null) {
            return false;
        }
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility == Utility.GAS) {
                return true;
            }
        }
        return false;
    }

    private boolean activePathTouchesGasFumes(List<PointF> points) {
        float radius = Math.max(cell * 0.85f, cell * activeLevel.mechanicRadiusCells);
        int startIndex = Math.max(0, points.size() - 2);
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility != Utility.GAS) {
                continue;
            }
            for (int i = startIndex; i < points.size() - 1; i++) {
                PointF a = points.get(i);
                PointF b = points.get(i + 1);
                float length = distance(a.x, a.y, b.x, b.y);
                int samples = Math.max(2, (int) Math.ceil(length / Math.max(dp(2f), cell * 0.05f)));
                for (int s = 0; s <= samples; s++) {
                    float t = s / (float) samples;
                    float x = a.x + (b.x - a.x) * t;
                    float y = a.y + (b.y - a.y) * t;
                    if (pointToPolylineDistance(x, y, stroke.points) <= radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void triggerGasExplosion() {
        long now = SystemClock.uptimeMillis();
        HashSet<Integer> damagedHouses = new HashSet<>();
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility != Utility.GAS) {
                continue;
            }
            Port port = activeLevel.findPort(stroke.portId);
            if (port != null) {
                damagedHouses.add(port.houseId);
            }
        }
        if (damagedHouses.isEmpty()) {
            dynamicSetback("Heat ignited gas fumes", 0xFFFF8A2A);
            return;
        }
        dynamicDamagedHouses.clear();
        dynamicDamagedHouses.addAll(damagedHouses);
        for (Integer houseId : damagedHouses) {
            House house = activeLevel.findHouse(houseId);
            PointF center = houseConnectionPoint(house);
            spawnBurst(center.x, center.y, 0xFFFFA12D, 42);
            spawnBurst(center.x, center.y, 0xFFFFF1B0, 18);
        }
        for (int i = activeLevel.strokes.size() - 1; i >= 0; i--) {
            Stroke stroke = activeLevel.strokes.get(i);
            Port port = activeLevel.findPort(stroke.portId);
            if (stroke.utility == Utility.GAS && port != null && damagedHouses.contains(port.houseId)) {
                activeLevel.strokes.remove(i);
            }
        }
        for (Port port : activeLevel.ports) {
            if (port.utility == Utility.GAS && damagedHouses.contains(port.houseId)) {
                port.connected = false;
            }
        }
        dynamicExplosionUntilMs = now + 900L;
        requestSound("fail");
        cancelActivePipe();
        if (damagedHouses.size() >= activeLevel.houses.size()) {
            dynamicHardResetAtMs = now + 900L;
            status("Gas blast hit every home - town reset");
        } else {
            status("Gas blast damaged connected gas homes");
        }
    }

    private void updateDynamicMechanics(long now) {
        if (dynamicHardResetAtMs > 0L && now >= dynamicHardResetAtMs && activeLevel != null) {
            activeLevel.resetForPlay();
            resetDynamicMechanics();
            status("Rebuild after the blast");
        }
    }

    private boolean isLabGateCell(Cell cell) {
        return mechanicsLab && cell != null && cell.x >= 10 && cell.x < 12 && cell.y == 27;
    }

    private Stroke firstCompletedLabStroke(Utility utility) {
        if (activeLevel == null) {
            return null;
        }
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility == utility) {
                return stroke;
            }
        }
        return null;
    }

    private boolean nearCompletedUtilityCell(Cell cell, Utility utility, int radius) {
        if (cell == null || activeLevel == null) {
            return false;
        }
        return nearUtilityCell(activeLevel.strokes, -1, cell, utility, radius);
    }

    private boolean nearUtilityCell(List<Stroke> strokes, int skipStrokeIndex, Cell cell, Utility utility, int radius) {
        if (cell == null) {
            return false;
        }
        for (int i = 0; i < strokes.size(); i++) {
            if (i == skipStrokeIndex) {
                continue;
            }
            Stroke stroke = strokes.get(i);
            if (stroke.utility != utility) {
                continue;
            }
            for (Cell other : stroke.cells) {
                if (Math.abs(cell.x - other.x) <= radius && Math.abs(cell.y - other.y) <= radius) {
                    return true;
                }
            }
        }
        return false;
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
        String visualCollision = visualObjectCollisionMessage(candidate.points, true);
        if (visualCollision != null) {
            return visualCollision;
        }
        for (Cell c : candidate.cells) {
            if (!inGrid(c.x, c.y)) {
                return "Out of town";
            }
        }
        if (selfIntersectsVisible(candidate.points)) {
            return "Pipe crossed itself";
        }
        for (Stroke other : activeLevel.strokes) {
            if (other.utility == candidate.utility) {
                continue;
            }
            if (sharesVisibleRouteCell(candidate, other)) {
                return "Utilities crossed";
            }
            if (strokesIntersectVisible(candidate.points, other.points)) {
                return "Utilities crossed";
            }
        }
        return null;
    }

    private String validateActivePath() {
        if (activeUtility == null || activePoints.size() < 2) {
            return null;
        }
        String visualCollision = visualObjectCollisionMessage(activePoints, false);
        if (visualCollision != null) {
            return visualCollision;
        }
        Set<Cell> touched = cellsTouched(activePoints);
        for (Cell c : touched) {
            if (!inGrid(c.x, c.y)) {
                return "Out of town";
            }
        }
        for (Stroke other : activeLevel.strokes) {
            if (other.utility == activeUtility) {
                continue;
            }
            for (Cell c : touched) {
                if (strokeContainsCell(other, c) && !pointUnderOpaqueHouse(cellCenter(c.x, c.y))) {
                    return "Utilities crossed";
                }
            }
            if (strokesIntersectVisible(activePoints, other.points)) {
                return "Utilities crossed";
            }
        }
        if (selfIntersectsVisible(activePoints)) {
            return "Looped pipe";
        }
        return null;
    }

    private Route findRoute(Level level, Utility utility, Cell start, PointF startPoint, Port target, List<Stroke> strokes, int skipStrokeIndex) {
        return findRoute(level, utility, start, startPoint, target, strokes, skipStrokeIndex, false);
    }

    private Route findRoute(Level level, Utility utility, Cell start, PointF startPoint, Port target, List<Stroke> strokes,
                            int skipStrokeIndex, boolean forceClosedPumpGate) {
        if (start == null || target == null) {
            return null;
        }
        Cell goal = portRouteCell(target);
        if (!inGrid(start.x, start.y) || !inGrid(goal.x, goal.y)) {
            return null;
        }
        if (!routeCellPassable(level, start, utility, strokes, skipStrokeIndex, forceClosedPumpGate)
                || !routeCellPassable(level, goal, utility, strokes, skipStrokeIndex, forceClosedPumpGate)) {
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
                if (!routeCellPassable(level, next, utility, strokes, skipStrokeIndex, forceClosedPumpGate)) {
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
        return routeCellPassable(level, cell, utility, strokes, skipStrokeIndex, false);
    }

    private boolean routeCellPassable(Level level, Cell cell, Utility utility, List<Stroke> strokes,
                                      int skipStrokeIndex, boolean forceClosedPumpGate) {
        if (cell == null || !inGrid(cell.x, cell.y)) {
            return false;
        }
        if (level.isHouseCell(cell.x, cell.y) || level.isSourceProviderCell(cell.x, cell.y) || level.isBlockerCell(cell.x, cell.y) || level.isSourceOutletCell(cell.x, cell.y)) {
            return false;
        }
        boolean closedPumpGate = forceClosedPumpGate
                || (level == activeLevel && !mechanicsLab && !dynamicGateOpen);
        if (level.mechanic == DynamicMechanic.PUMP_GATE
                && closedPumpGate && utility != level.mechanicUtility && isDynamicGateCell(level, cell)) {
            return false;
        }
        if (level == activeLevel && !mechanicsLab && level.mechanic == DynamicMechanic.FUME_SPLIT
                && utility == Utility.HEATING
                && nearUtilityCell(strokes, skipStrokeIndex, cell, Utility.GAS, Math.max(1, level.mechanicRadiusCells))) {
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

    private boolean sharesVisibleRouteCell(Stroke a, Stroke b) {
        for (Cell ac : a.cells) {
            if (strokeContainsCell(b, ac) && !pointUnderOpaqueHouse(cellCenter(ac.x, ac.y))) {
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
            RectF marker = sourceMarkerRect(source);
            marker.inset(-dp(3), -dp(3));
            if (marker.contains(x, y)) {
                return source;
            }
        }
        return null;
    }

    private FinishTouch findFinishTouch(List<PointF> points, PointF screenTail) {
        if (activeUtility == null || points.size() < 2 || !activePathClearOfStart(points)) {
            return null;
        }
        Port best = null;
        House bestHouse = null;
        float bestProgress = -1f;
        for (Port port : activeLevel.ports) {
            if (port.connected || port.utility != activeUtility) {
                continue;
            }
            House house = activeLevel.findHouse(port.houseId);
            if (house != null) {
                float progress = pathHouseTouchProgress(points, house);
                if (progress >= 0f && progress >= bestProgress) {
                    bestProgress = progress;
                    best = port;
                    bestHouse = house;
                }
            }
        }
        return best == null ? null : new FinishTouch(best, bestHouse);
    }

    private float pathHouseTouchProgress(List<PointF> points, House house) {
        if (points.size() < 2 || house == null) {
            return -1f;
        }
        Bitmap bitmap = assets.get(house.asset);
        RectF visible = standingHouseRect(house);
        float progress = 0f;
        float best = -1f;
        float step = Math.max(dp(2f), cell * 0.05f);
        for (int i = 0; i < points.size() - 1; i++) {
            PointF a = projectGroundPoint(points.get(i));
            PointF b = projectGroundPoint(points.get(i + 1));
            float length = distance(a.x, a.y, b.x, b.y);
            int samples = Math.max(1, (int) Math.ceil(length / step));
            for (int s = 0; s <= samples; s++) {
                float t = s / (float) samples;
                float x = a.x + (b.x - a.x) * t;
                float y = a.y + (b.y - a.y) * t;
                if (houseOpaqueAtScreenPoint(bitmap, visible, x, y)) {
                    best = progress + length * t;
                }
            }
            progress += length;
        }
        return best;
    }

    private boolean houseOpaqueAtScreenPoint(Bitmap bitmap, RectF rect, float x, float y) {
        return bitmapOpaqueAtPoint(bitmap, rect, x, y);
    }

    private String visualObjectCollisionMessage(List<PointF> points, boolean fullPath) {
        if (activeLevel == null || points.size() < 2) {
            return null;
        }
        int startIndex = fullPath ? 0 : Math.max(0, points.size() - 2);
        float step = Math.max(dp(2f), cell * 0.05f);
        for (int i = startIndex; i < points.size() - 1; i++) {
            PointF a = points.get(i);
            PointF b = points.get(i + 1);
            float length = distance(a.x, a.y, b.x, b.y);
            int samples = Math.max(2, (int) Math.ceil(length / step));
            for (int s = 0; s <= samples; s++) {
                float t = s / (float) samples;
                PointF ground = new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
                PointF screen = projectGroundPoint(ground);
                if (touchesOpaqueBlocker(ground, screen)) {
                    return "Blocked ground";
                }
                if (touchesOpaqueSourceMarker(screen)) {
                    return "Source wall";
                }
            }
        }
        return null;
    }

    private boolean touchesOpaqueBlocker(PointF ground, PointF screen) {
        for (Blocker blocker : activeLevel.blockers) {
            Bitmap bitmap = assets.get(blocker.asset);
            if (isFlatGroundBlocker(blocker)) {
                RectF rect = visualAssetRect(bitmap, cellRect(blocker.x, blocker.y, blocker.w, blocker.h),
                        largestAssetUnit(blocker.asset, Math.max(blocker.w, blocker.h)), 1.00f, true);
                scaleAndOffset(rect, calibrationProfile.layer("L", blockerCalibrationKey(blocker), 'A'));
                if (bitmapOpaqueAtPoint(bitmap, rect, ground.x, ground.y)) {
                    return true;
                }
            } else if (bitmapOpaqueAtPoint(bitmap, standingBlockerRect(blocker), screen.x, screen.y)) {
                return true;
            }
        }
        return false;
    }

    private boolean touchesOpaqueSourceMarker(PointF screen) {
        for (Source source : activeLevel.sources) {
            if (source == activeStartSource) {
                continue;
            }
            if (bitmapOpaqueAtPoint(assets.get(source.utility.iconAsset()), sourceMarkerRect(source), screen.x, screen.y)) {
                return true;
            }
        }
        return false;
    }

    private boolean bitmapOpaqueAtPoint(Bitmap bitmap, RectF rect, float x, float y) {
        if (bitmap == null || !rect.contains(x, y) || rect.width() <= 0f || rect.height() <= 0f) {
            return false;
        }
        int pixelX = clamp((int) ((x - rect.left) / rect.width() * bitmap.getWidth()), 0, bitmap.getWidth() - 1);
        int pixelY = clamp((int) ((y - rect.top) / rect.height() * bitmap.getHeight()), 0, bitmap.getHeight() - 1);
        return Color.alpha(bitmap.getPixel(pixelX, pixelY)) > 12;
    }

    private ArrayList<PointF> trimStrokeAtVisibleHouse(List<PointF> points, House house) {
        ArrayList<PointF> trimmed = new ArrayList<>();
        if (points.isEmpty()) {
            return trimmed;
        }
        if (points.size() < 2 || house == null) {
            trimmed.addAll(points);
            return trimmed;
        }
        Bitmap bitmap = assets.get(house.asset);
        RectF visible = standingHouseRect(house);
        float step = Math.max(dp(1.4f), cell * 0.04f);
        trimmed.add(new PointF(points.get(0).x, points.get(0).y));
        for (int segment = 0; segment < points.size() - 1; segment++) {
            PointF startGround = points.get(segment);
            PointF endGround = points.get(segment + 1);
            PointF start = projectGroundPoint(startGround);
            PointF end = projectGroundPoint(endGround);
            float length = distance(start.x, start.y, end.x, end.y);
            int samples = Math.max(2, (int) Math.ceil(length / step));
            PointF lastClear = start;
            for (int i = 1; i <= samples; i++) {
                float t = i / (float) samples;
                float x = start.x + (end.x - start.x) * t;
                float y = start.y + (end.y - start.y) * t;
                if (houseOpaqueAtScreenPoint(bitmap, visible, x, y)) {
                    addRoutePoint(trimmed, unprojectGround(lastClear.x, lastClear.y));
                    return trimmed;
                }
                lastClear = new PointF(x, y);
            }
            addRoutePoint(trimmed, new PointF(endGround.x, endGround.y));
        }
        return trimmed;
    }

    private boolean activePathClearOfStart(List<PointF> points) {
        if (activeStartPoint == null || points.isEmpty()) {
            return true;
        }
        PointF last = points.get(points.size() - 1);
        return distance(last.x, last.y, activeStartPoint.x, activeStartPoint.y) > cell * 0.72f;
    }

    private StrokeHit sameUtilityNetworkTouch(List<PointF> points, Utility utility) {
        float threshold = Math.max(dp(5), cell * 0.075f);
        StrokeHit best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Stroke stroke : activeLevel.strokes) {
            if (stroke.utility != utility) {
                continue;
            }
            for (int i = Math.max(0, points.size() - 2); i < points.size() - 1; i++) {
                PointF a = points.get(i);
                PointF b = points.get(i + 1);
                if (nearOriginExclusion(b.x, b.y)) {
                    continue;
                }
                for (int j = 0; j < stroke.points.size() - 1; j++) {
                    PointF c = stroke.points.get(j);
                    PointF d = stroke.points.get(j + 1);
                    if (segmentsIntersect(a, b, c, d)) {
                        PointF hit = nearestPointOnSegment(b.x, b.y, c, d);
                        Cell cell = nearestStrokeCell(stroke, hit.x, hit.y);
                        if (cell != null && !nearActiveStartCell(cell) && !nearOriginExclusion(hit.x, hit.y)) {
                            return new StrokeHit(utility, cell, hit);
                        }
                    }
                    PointF hit = nearestPointOnSegment(b.x, b.y, c, d);
                    float distance = pointToSegmentDistance(hit.x, hit.y, a, b);
                    if (distance <= threshold && distance < bestDistance) {
                        Cell cell = nearestStrokeCell(stroke, hit.x, hit.y);
                        if (cell != null && !nearActiveStartCell(cell) && !nearOriginExclusion(hit.x, hit.y)) {
                            bestDistance = distance;
                            best = new StrokeHit(utility, cell, hit);
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean nearOriginExclusion(float x, float y) {
        if (activeStartPoint == null) {
            return false;
        }
        if (distance(x, y, activeStartPoint.x, activeStartPoint.y) <= cell * 0.94f) {
            return true;
        }
        if (activeStartDirection == null) {
            return false;
        }
        PointF leadOut = new PointF(
                activeStartPoint.x + activeStartDirection.dx * cell * 0.43f,
                activeStartPoint.y + activeStartDirection.dy * cell * 0.43f);
        return distance(x, y, leadOut.x, leadOut.y) <= cell * 0.64f;
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
                    best = new StrokeHit(stroke.utility, cell, nearestPointOnPolyline(x, y, stroke.points), activeLevel.strokes.indexOf(stroke));
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

    private Path strokePath(Stroke stroke) {
        if (stroke.routed) {
            return angularPath(stroke.points);
        }
        return smoothPath(stroke.points);
    }

    private Path angularPath(List<PointF> points) {
        Path path = new Path();
        if (points.isEmpty()) {
            return path;
        }
        PointF first = points.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < points.size(); i++) {
            PointF point = points.get(i);
            path.lineTo(point.x, point.y);
        }
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

    private RectF fitBitmapInside(Bitmap bitmap, RectF frame) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
                || frame.width() <= 0f || frame.height() <= 0f) {
            return new RectF(frame);
        }
        float bitmapRatio = bitmap.getWidth() / (float) bitmap.getHeight();
        float frameRatio = frame.width() / frame.height();
        float width = frame.width();
        float height = frame.height();
        if (bitmapRatio > frameRatio) {
            height = width / bitmapRatio;
        } else {
            width = height * bitmapRatio;
        }
        return new RectF(frame.centerX() - width * 0.5f, frame.centerY() - height * 0.5f,
                frame.centerX() + width * 0.5f, frame.centerY() + height * 0.5f);
    }

    private RectF visualAssetRect(Bitmap bitmap, RectF anchor, int units, float scale, boolean bottomAligned) {
        float visualUnit = Math.max(cell, dp(34));
        float dimensionScale = units <= 1 ? 1f : 1f + (float) Math.sqrt(units - 1) * 0.62f;
        float side = Math.max(visualUnit * dimensionScale * scale, visualUnit * 0.8f);
        side = Math.min(side, Math.min(getWidth() * 0.42f, dp(300)));
        float ratio = bitmap == null || bitmap.getHeight() == 0 ? 1f : bitmap.getWidth() / (float) bitmap.getHeight();
        float width = ratio >= 1f ? side : side * ratio;
        float height = ratio >= 1f ? side / ratio : side;
        float centerX = anchor.centerX();
        float bottom = bottomAligned ? anchor.bottom + cell * 0.08f : anchor.centerY() + height * 0.5f;
        return new RectF(centerX - width * 0.5f, bottom - height, centerX + width * 0.5f, bottom);
    }

    private RectF standingRect(RectF groundRect) {
        float[] baseline = {groundRect.left, groundRect.bottom, groundRect.right, groundRect.bottom};
        groundMatrix.mapPoints(baseline);
        float scale = distance(baseline[0], baseline[1], baseline[2], baseline[3])
                / Math.max(1f, groundRect.width());
        float width = groundRect.width() * scale;
        float height = groundRect.height() * scale;
        float centerX = (baseline[0] + baseline[2]) * 0.5f;
        float bottom = (baseline[1] + baseline[3]) * 0.5f;
        return new RectF(centerX - width * 0.5f, bottom - height, centerX + width * 0.5f, bottom);
    }

    private PointF unprojectGround(float x, float y) {
        if (!groundProjectionReady) {
            return new PointF(x, y);
        }
        float[] point = {x, y};
        inverseGroundMatrix.mapPoints(point);
        return new PointF(point[0], point[1]);
    }

    private void drawRotatedBitmap(Canvas canvas, Bitmap bitmap, RectF dest, float degrees, int alpha) {
        canvas.save();
        canvas.rotate(degrees, dest.centerX(), dest.centerY());
        drawBitmap(canvas, bitmap, dest, alpha);
        canvas.restore();
    }

    private RectF cellRect(int x, int y, int w, int h) {
        return new RectF(boardLeft + x * cell, boardTop + y * cell, boardLeft + (x + w) * cell, boardTop + (y + h) * cell);
    }

    private RectF labGateRect() {
        return cellRect(10, 27, 2, 1);
    }

    private RectF dynamicGateRect() {
        if (activeLevel == null) {
            return new RectF();
        }
        return cellRect(activeLevel.mechanicX, activeLevel.mechanicY,
                Math.max(1, activeLevel.mechanicW), Math.max(1, activeLevel.mechanicH));
    }

    private boolean isDynamicGateCell(Cell cell) {
        return isDynamicGateCell(activeLevel, cell);
    }

    private boolean isDynamicGateCell(Level level, Cell cell) {
        return level != null && cell != null
                && cell.x >= level.mechanicX && cell.x < level.mechanicX + level.mechanicW
                && cell.y >= level.mechanicY && cell.y < level.mechanicY + level.mechanicH;
    }

    private PointF labGateCenter() {
        RectF gate = labGateRect();
        return new PointF(gate.centerX(), gate.centerY());
    }

    private RectF labPatchFloodBounds() {
        return cellRect(3, 18, 16, 14);
    }

    private float labPatchFloodProgress(long now) {
        if (labGateOpen) {
            return labStoredProgress * clamp(1f - (now - labTriggerMs) / 1350f, 0f, 1f);
        }
        return clamp((now - labStartedMs - 1400f) / 11200f, 0f, 1f);
    }

    private float labPatchFloodThreshold(int x, int y) {
        float lowGround = clamp((y - 18f) / 13f, 0f, 1f);
        float centerPull = 1f - clamp(Math.abs(x - 11f) / 8f, 0f, 1f);
        float noise = labNoise01(x, y, 71);
        return clamp(0.10f + noise * 0.72f - lowGround * 0.14f - centerPull * 0.05f, 0.04f, 0.96f);
    }

    private boolean isLabPatchFloodCell(Cell cell, long now) {
        if (cell == null || labGateOpen) {
            return false;
        }
        return cell.x >= 3 && cell.x < 19 && cell.y >= 18 && cell.y < 32
                && labPatchFloodProgress(now) > labPatchFloodThreshold(cell.x, cell.y);
    }

    private RectF labSpinnerZoneRect() {
        return cellRect(6, 12, 10, 10);
    }

    private PointF labSpinnerCenter() {
        return cellCenter(11, 17);
    }

    private float labSpinnerAngle(long now) {
        float raw = (now - labStartedMs) / 640f;
        float settle = 0.08f * (float) Math.sin((now - labStartedMs) / 155f);
        return raw + settle;
    }

    private boolean isLabSpinnerCell(Cell candidate, long now) {
        if (candidate == null || candidate.x < 6 || candidate.x >= 16 || candidate.y < 12 || candidate.y >= 22) {
            return false;
        }
        PointF center = labSpinnerCenter();
        PointF point = cellCenter(candidate.x, candidate.y);
        float dx = point.x - center.x;
        float dy = point.y - center.y;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < cell * 0.45f || distance > cell * 4.75f) {
            return false;
        }
        float angle = (float) Math.atan2(dy, dx);
        float delta = Math.abs(angleDifference(angle, labSpinnerAngle(now)));
        return delta < 0.20f;
    }

    private RectF labOneWayRect() {
        return cellRect(5, 16, 12, 3);
    }

    private boolean isLabOneWayCell(Cell cell) {
        return cell != null && cell.x >= 5 && cell.x < 17 && cell.y >= 16 && cell.y < 19;
    }

    private RectF labCrackRect() {
        return cellRect(7, 14, 8, 9);
    }

    private boolean isLabCrackCell(Cell cell) {
        if (cell == null || cell.x < 8 || cell.x >= 14 || cell.y < 15 || cell.y >= 22) {
            return false;
        }
        int pattern = Math.abs((cell.x * 19 + cell.y * 31 + 7) % 5);
        return pattern == 0 || pattern == 2;
    }

    private boolean isLabLeakCell(Cell cell) {
        if (!labSecondaryState || cell == null) {
            return false;
        }
        for (int x = 8; x < 14; x++) {
            for (int y = 15; y < 22; y++) {
                if (isLabCrackCell(new Cell(x, y))
                        && Math.abs(cell.x - x) <= 1 && Math.abs(cell.y - y) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean strokeTouchesLabCrack(Stroke stroke) {
        for (Cell cell : stroke.cells) {
            if (isLabCrackCell(cell)) {
                return true;
            }
        }
        return false;
    }

    private RectF labCrewLaneRect() {
        return cellRect(5, 15, 12, 5);
    }

    private RectF labCrewRect(long now) {
        RectF lane = labCrewLaneRect();
        float t = 0.5f + 0.5f * (float) Math.sin((now - labStartedMs) / 920f);
        float w = cell * 1.62f;
        float h = cell * 0.90f;
        float x = lane.left + cell * 0.30f + (lane.width() - w - cell * 0.60f) * t;
        float y = lane.centerY() - h * 0.48f + (float) Math.sin((now - labStartedMs) / 430f) * cell * 0.08f;
        return new RectF(x, y, x + w, y + h);
    }

    private boolean isLabCrewCell(Cell cell, long now) {
        return cell != null && RectF.intersects(cellRect(cell.x, cell.y, 1, 1), labCrewRect(now));
    }

    private RectF labStormBounds() {
        return cellRect(5, 12, 12, 11);
    }

    private PointF labStormCenter(long now) {
        RectF bounds = labStormBounds();
        float phase = (now - labStartedMs) / 1000f;
        float x = bounds.centerX() + (float) Math.sin(phase * 0.88f) * bounds.width() * 0.30f;
        float y = bounds.centerY() + (float) Math.cos(phase * 1.17f) * bounds.height() * 0.28f;
        return new PointF(x, y);
    }

    private boolean isLabStormCell(Cell cell, long now) {
        if (cell == null) {
            return false;
        }
        PointF center = labStormCenter(now);
        PointF point = cellCenter(cell.x, cell.y);
        return distance(center.x, center.y, point.x, point.y) <= this.cell * 1.55f;
    }

    private RectF labRootBounds() {
        return cellRect(6, 16, 11, 12);
    }

    private float labRootProgress(long now) {
        if (!labSecondaryState) {
            return 0f;
        }
        return clamp((now - labTriggerMs) / 3600f, 0f, 1f);
    }

    private float labRootThreshold(int x, int y) {
        float fromWater = clamp((y - 16f) / 11f, 0f, 1f);
        return clamp(0.05f + fromWater * 0.50f + labNoise01(x, y, 177) * 0.38f, 0.02f, 0.96f);
    }

    private boolean isLabRootCell(Cell cell, long now) {
        if (cell == null || cell.x < 6 || cell.x >= 17 || cell.y < 16 || cell.y >= 28) {
            return false;
        }
        return labRootProgress(now) > labRootThreshold(cell.x, cell.y);
    }

    private RectF labFumeBounds() {
        return cellRect(5, 11, 12, 12);
    }

    private boolean isLabFumeCell(Cell cell) {
        return firstCompletedLabStroke(Utility.GAS) != null && nearCompletedUtilityCell(cell, Utility.GAS, 2);
    }

    private boolean activeSegmentMovesMostly(Direction direction) {
        if (activePoints.size() < 2) {
            return true;
        }
        PointF a = activePoints.get(activePoints.size() - 2);
        PointF b = activePoints.get(activePoints.size() - 1);
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float along = dx * direction.dx + dy * direction.dy;
        float side = Math.abs(-dx * direction.dy + dy * direction.dx);
        return along > Math.max(cell * 0.015f, side * 0.42f);
    }

    private float labNoise01(int x, int y, int salt) {
        int v = x * 374761393 + y * 668265263 + salt * 1442695041;
        v = (v ^ (v >> 13)) * 1274126177;
        v ^= (v >> 16);
        return (v & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    private float angleDifference(float a, float b) {
        float diff = (a - b) % ((float) Math.PI * 2f);
        if (diff > Math.PI) {
            diff -= (float) Math.PI * 2f;
        } else if (diff < -Math.PI) {
            diff += (float) Math.PI * 2f;
        }
        return diff;
    }

    private PointF cellCenter(int x, int y) {
        return new PointF(boardLeft + (x + 0.5f) * cell, boardTop + (y + 0.5f) * cell);
    }

    private PointF sourceMouthPoint(Source source) {
        return new PointF(boardLeft + (source.x + 1f) * cell,
                boardTop + (source.y + 1.82f) * cell);
    }

    private PointF sourceConnectionPoint(Source source) {
        if (source == null) {
            return new PointF(boardLeft, boardTop);
        }
        RectF marker = sourceMarkerRect(source);
        return unprojectGround(marker.centerX(), marker.centerY());
    }

    private Cell sourceRouteCell(Source source) {
        return new Cell(source.connectorX() + source.openDirection.dx, source.connectorY() + source.openDirection.dy);
    }

    private PointF portMouthPoint(Port port) {
        House house = activeLevel == null ? null : activeLevel.findHouse(port.houseId);
        if (house == null) {
            return cellCenter(port.x, port.y);
        }
        float edgeInset = cell * 0.035f;
        float x = boardLeft + (port.x + 0.5f) * cell;
        float y = boardTop + (port.y + 0.5f) * cell;
        if (port.outlet == Direction.LEFT) {
            x = boardLeft + house.x * cell - edgeInset;
        } else if (port.outlet == Direction.RIGHT) {
            x = boardLeft + (house.x + house.w) * cell + edgeInset;
        } else if (port.outlet == Direction.UP) {
            y = boardTop + house.y * cell - edgeInset;
        } else {
            y = boardTop + (house.y + house.h) * cell + edgeInset;
        }
        return new PointF(x, y);
    }

    private Stroke tuckStrokeUnderHouse(Stroke stroke, House house) {
        if (house == null || stroke.points.isEmpty()) {
            return stroke;
        }
        ArrayList<PointF> points = new ArrayList<>(stroke.points);
        addRoutePoint(points, houseConnectionPoint(house));
        return new Stroke(stroke.utility, stroke.portId, simplifyStroke(points), new ArrayList<>(stroke.cells));
    }

    private PointF houseConnectionPoint(House house) {
        if (house == null) {
            return new PointF(boardLeft, boardTop);
        }
        RectF visible = standingHouseRect(house);
        return unprojectGround(visible.centerX(), visible.centerY());
    }

    private void scaleAndOffset(RectF rect, CalibrationProfile.Layer tune) {
        float halfWidth = rect.width() * tune.scaleX * 0.5f;
        float halfHeight = rect.height() * tune.scaleY * 0.5f;
        float centerX = rect.centerX() + dp(tune.x);
        float centerY = rect.centerY() + dp(tune.y);
        rect.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
    }

    private String houseCalibrationKey(House house) {
        if (house.asset.contains("1x1")) {
            return "house_1x1";
        }
        if (house.asset.contains("2x2")) {
            return "house_2x2";
        }
        if (house.asset.contains("4x4")) {
            return "house_4x4";
        }
        return "house_5x5";
    }

    private String blockerCalibrationKey(Blocker blocker) {
        int slash = blocker.asset.lastIndexOf('/');
        int dot = blocker.asset.lastIndexOf('.');
        return blocker.asset.substring(slash + 1, dot > slash ? dot : blocker.asset.length());
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

    private boolean inPlayableInterior(int x, int y) {
        return x >= LEVEL_EDGE_MARGIN && y >= LEVEL_EDGE_MARGIN
                && x < GRID_W - LEVEL_EDGE_MARGIN && y < GRID_H - LEVEL_EDGE_MARGIN;
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

    private boolean selfIntersectsVisible(List<PointF> points) {
        for (int i = 0; i < points.size() - 1; i++) {
            for (int j = i + 2; j < points.size() - 1; j++) {
                if (i == 0 && j == points.size() - 2) {
                    continue;
                }
                if (segmentsIntersectVisible(points.get(i), points.get(i + 1), points.get(j), points.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean strokesIntersectVisible(List<PointF> a, List<PointF> b) {
        for (int i = 0; i < a.size() - 1; i++) {
            for (int j = 0; j < b.size() - 1; j++) {
                if (segmentsIntersectVisible(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersectVisible(PointF a, PointF b, PointF c, PointF d) {
        if (!segmentsIntersect(a, b, c, d)) {
            return false;
        }
        float denominator = (a.x - b.x) * (c.y - d.y) - (a.y - b.y) * (c.x - d.x);
        if (Math.abs(denominator) > 0.0001f) {
            float px = ((a.x * b.y - a.y * b.x) * (c.x - d.x)
                    - (a.x - b.x) * (c.x * d.y - c.y * d.x)) / denominator;
            float py = ((a.x * b.y - a.y * b.x) * (c.y - d.y)
                    - (a.y - b.y) * (c.x * d.y - c.y * d.x)) / denominator;
            return !pointUnderOpaqueHouse(new PointF(px, py));
        }
        float length = distance(a.x, a.y, b.x, b.y);
        int samples = Math.max(2, (int) Math.ceil(length / Math.max(cell * 0.05f, 1f)));
        boolean foundOverlap = false;
        float threshold = Math.max(cell * 0.04f, 0.75f);
        for (int i = 0; i <= samples; i++) {
            float t = i / (float) samples;
            PointF p = new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            if (pointToSegmentDistance(p.x, p.y, c, d) <= threshold) {
                foundOverlap = true;
                if (!pointUnderOpaqueHouse(p)) {
                    return true;
                }
            }
        }
        return !foundOverlap;
    }

    private boolean pointUnderOpaqueHouse(PointF ground) {
        if (activeLevel == null || ground == null) {
            return false;
        }
        PointF screen = projectGroundPoint(ground);
        for (House house : activeLevel.houses) {
            if (houseOpaqueAtScreenPoint(assets.get(house.asset), standingHouseRect(house), screen.x, screen.y)) {
                return true;
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

    private int blend(int from, int to, float amount) {
        float t = clamp(amount, 0f, 1f);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
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
        if (number <= MECHANICS_LAB_LEVEL && number > MECHANICS_LAB_LEVEL - LAB_SCENARIO_COUNT) {
            return mechanicsLabLevel(-number - 1);
        }
        int safeNumber = Math.max(0, number);
        synchronized (levelCache) {
            Level cached = levelCache.get(safeNumber);
            if (cached != null) {
                return cached;
            }
        }
        Level generated = safeNumber == 0 ? tutorialLevel() : generateLevel(safeNumber);
        synchronized (levelCache) {
            Level cached = levelCache.get(safeNumber);
            if (cached != null) {
                return cached;
            }
            levelCache.put(safeNumber, generated);
        }
        return generated;
    }

    private Level tutorialLevel() {
        Level level = new Level(0, "art/backgrounds/farm.png");
        level.sources.add(new Source(Utility.WATER, 2, 9, Direction.RIGHT));
        level.sources.add(new Source(Utility.ELECTRIC, 17, 9, Direction.LEFT));
        level.sources.add(new Source(Utility.SEWAGE, 2, 26, Direction.RIGHT));
        House home = new House(1, 9, 16, 4, 4, "art/houses/house 4x4.png");
        level.houses.add(home);
        level.ports.add(new Port(1, home.id, Utility.WATER, home.x - 1, home.y + 1, Direction.LEFT));
        level.ports.add(new Port(2, home.id, Utility.ELECTRIC, home.x + home.w, home.y + 1, Direction.RIGHT));
        level.ports.add(new Port(3, home.id, Utility.SEWAGE, home.x + 2, home.y + home.h, Direction.DOWN));
        level.blockers.add(new Blocker("art/blockers/construction_1x2.png", 15, 13, 1, 2));
        level.blockers.add(new Blocker("art/blockers/pond_2x2.png", 6, 23, 2, 2));
        level.blockers.add(new Blocker("art/blockers/tree_1x2.png", 16, 22, 1, 2));
        ArrayList<Stroke> planned = plannedSolution(level);
        if (planned != null) {
            recordHiddenSolution(level, planned, "tutorial");
        }
        return level;
    }

    private Level mechanicsLabLevel(int scenario) {
        Level level;
        switch (scenario) {
            case 0:
                level = baseLabLevel(scenario, "1  Pump Gate", "A supplied pump opens the stitched crossing.",
                        "Connect water, then take internet through the gate.");
                addLabSource(level, Utility.WATER, 2, 7, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.WATER, 9, 7);
                addLabSource(level, Utility.INTERNET, 3, 30, Direction.UP);
                addLabHome(level, 2, 2, Utility.INTERNET, 16, 22);
                addLabBarrierRow(level, 27, 10, 2);
                return level;
            case 1:
                level = baseLabLevel(scenario, "2  Patch Flood", "Flood appears in seeded puddles instead of a straight wall.",
                        "Drain first, or race power through the gaps before patches fill.");
                addLabSource(level, Utility.SEWAGE, 2, 7, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.SEWAGE, 16, 7);
                addLabSource(level, Utility.ELECTRIC, 2, 29, Direction.RIGHT);
                addLabHome(level, 2, 2, Utility.ELECTRIC, 16, 27);
                return level;
            case 2:
                level = baseLabLevel(scenario, "3  Spinner Spray", "A rotating wet arm makes a moving danger lane.",
                        "Thread power between sweeps or take the outside curve.");
                addLabSource(level, Utility.ELECTRIC, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.ELECTRIC, 17, 17);
                return level;
            case 3:
                level = baseLabLevel(scenario, "4  One-Way Main", "The green service main only carries flow to the right.",
                        "Use it with the arrows; backtracking inside the main fails.");
                addLabSource(level, Utility.WATER, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.WATER, 17, 17);
                return level;
            case 4:
                level = baseLabLevel(scenario, "5  Leak Cracks", "Water through cracked ground creates visible puddles.",
                        "Route power before the leak, or keep both paths off the cracks.");
                addLabSource(level, Utility.WATER, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.WATER, 17, 17);
                addLabSource(level, Utility.ELECTRIC, 2, 27, Direction.RIGHT);
                addLabHome(level, 2, 2, Utility.ELECTRIC, 17, 24);
                return level;
            case 5:
                level = baseLabLevel(scenario, "6  Moving Crew", "A road crew crosses the field while you draw.",
                        "Wait, weave, or route around the moving cart.");
                addLabSource(level, Utility.GAS, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.GAS, 17, 17);
                return level;
            case 6:
                level = baseLabLevel(scenario, "7  Shared Trunk", "One water main can split into multiple homes.",
                        "Build the first branch, then split cleanly from the shared line.");
                addLabSource(level, Utility.WATER, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.WATER, 16, 12);
                addLabHome(level, 2, 2, Utility.WATER, 17, 22);
                return level;
            case 7:
                level = baseLabLevel(scenario, "8  Signal Storm", "A drifting storm scrambles internet while it passes.",
                        "Time the net line or take a calmer edge.");
                addLabSource(level, Utility.INTERNET, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.INTERNET, 17, 17);
                return level;
            case 8:
                level = baseLabLevel(scenario, "9  Root Wake", "Water wakes roots that slowly spread through the field.",
                        "Run gas first, or give it enough clearance before roots grow.");
                addLabSource(level, Utility.WATER, 2, 8, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.WATER, 17, 8);
                addLabSource(level, Utility.GAS, 2, 25, Direction.RIGHT);
                addLabHome(level, 2, 2, Utility.GAS, 17, 22);
                return level;
            case 9:
            default:
                level = baseLabLevel(9, "10  Fume Split", "A gas line leaves visible fumes that heat must avoid.",
                        "Connect heat first, or keep it well away from the gas route.");
                addLabSource(level, Utility.GAS, 2, 17, Direction.RIGHT);
                addLabHome(level, 1, 1, Utility.GAS, 17, 17);
                addLabSource(level, Utility.HEATING, 2, 19, Direction.RIGHT);
                addLabHome(level, 2, 2, Utility.HEATING, 17, 19);
                return level;
        }
    }

    private Level baseLabLevel(int scenario, String title, String rule, String goal) {
        Level level = new Level(MECHANICS_LAB_LEVEL - scenario, "art/backgrounds/desert.png");
        level.labTitle = title;
        level.labRule = rule;
        level.labGoal = goal;
        level.generationMode = "mechanics-lab";
        level.terrainStyle = "reactive";
        return level;
    }

    private void addLabSource(Level level, Utility utility, int x, int y, Direction direction) {
        level.sources.add(new Source(utility, x, y, direction));
    }

    private void addLabHome(Level level, int houseId, int portId, Utility utility, int x, int y) {
        House home = new House(houseId, x, y, 2, 2, "art/houses/house_2x2.png");
        level.houses.add(home);
        level.ports.add(new Port(portId, home.id, utility, home.x - 1, home.y + 1, Direction.LEFT));
    }

    private void addLabBarrierRow(Level level, int y, int gapX, int gapWidth) {
        for (int x = 0; x < GRID_W; x++) {
            if (x < gapX || x >= gapX + gapWidth) {
                level.blockers.add(new Blocker("art/blockers/construction_1x1.png", x, y, 1, 1));
            }
        }
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
            if (attempt >= profile.minAttempts && candidate.challengeMet) {
                candidate.level.generationAttempts = attempt + 1;
                return candidate.level;
            }
        }
        Level constructed = constructPlanarNetworkLevel(number, profile);
        if (constructed != null) {
            constructed.generationAttempts = profile.maxAttempts + 1;
            return constructed;
        }
        if (best != null && best.challengeMet) {
            return best.level;
        }
        return fallbackLevel(number, profile);
    }

    private Level constructStripedNetworkLevel(int number, DifficultyProfile profile) {
        if (profile.sourceCount > 6 || profile.houseCount != profile.sourceCount) {
            return null;
        }
        String[] backgrounds = {"art/backgrounds/warm.png", "art/backgrounds/farm.png", "art/backgrounds/tropical.png", "art/backgrounds/desert.png"};
        for (int attempt = 0; attempt < 54; attempt++) {
            Random rng = levelRandom(number, 60_000 + attempt);
            Level level = new Level(number, backgrounds[Math.abs(number - 1) % backgrounds.length]);
            ArrayList<Utility> utilities = shuffledUtilities(rng);
            int spacing = 6;
            int usedHeight = (profile.sourceCount - 1) * spacing + 2;
            int top = LEVEL_EDGE_MARGIN + rng.nextInt(Math.max(1, GRID_H - LEVEL_EDGE_MARGIN * 2 - usedHeight + 1));
            boolean reverse = rng.nextBoolean();
            boolean fromLeft = rng.nextBoolean();
            int family = Math.abs(number + attempt) % 3;
            int[] sourceRows = new int[profile.sourceCount];
            boolean usable = true;
            for (int i = 0; i < profile.sourceCount; i++) {
                int lane = reverse ? profile.sourceCount - 1 - i : i;
                int y = top + lane * spacing;
                sourceRows[i] = y;
                boolean laneFromLeft = family == 0 ? fromLeft
                        : family == 1 ? ((i & 1) == 0) == fromLeft
                        : (i < (profile.sourceCount + 1) / 2) == fromLeft;
                int inset = 1 + rng.nextInt(3);
                Source source = laneFromLeft
                        ? new Source(utilities.get(i), inset, y, Direction.RIGHT)
                        : new Source(utilities.get(i), GRID_W - 2 - inset, y, Direction.LEFT);
                if (!canPlaceSource(level, source)) {
                    usable = false;
                    break;
                }
                level.sources.add(source);
            }
            if (!usable) {
                continue;
            }

            int pairHouses = profile.sourceCount - 1;
            int duplicateGap = rng.nextInt(Math.max(1, pairHouses));
            int[] houseY = new int[pairHouses];
            int[] houseSize = new int[pairHouses];
            ArrayList<Port> createdPorts = new ArrayList<>();
            for (int gap = 0; gap < pairHouses; gap++) {
                int featuredGap = Math.abs(number * 13 + attempt) % pairHouses;
                int size = gap == featuredGap ? (profile.tier >= 8 ? 4 : 2) : rng.nextFloat() < 0.70f ? 2 : 1;
                int x = 4 + rng.nextInt(Math.max(1, GRID_W - size - 7));
                int y = Math.min(sourceRows[gap], sourceRows[gap + 1]) + (size >= 4 ? 2 : 3);
                House house = new House(level.houses.size() + 1, x, y, size, size, houseAssetForSize(size));
                if (!canPlaceRect(level, x, y, size, size)) {
                    usable = false;
                    break;
                }
                level.houses.add(house);
                houseY[gap] = y;
                houseSize[gap] = size;
                int connectorY = y + size - 1;
                createdPorts.add(new Port(createdPorts.size() + 1, house.id, utilities.get(gap), x + size, connectorY, Direction.RIGHT));
                createdPorts.add(new Port(createdPorts.size() + 1, house.id, utilities.get(gap + 1), x - 1, connectorY, Direction.LEFT));
            }
            if (!usable) {
                continue;
            }

            int extraSize = profile.tier >= 8 && houseSize[duplicateGap] == 1 ? 2 : 1;
            int extraX = 3 + rng.nextInt(Math.max(1, GRID_W - extraSize - 6));
            int extraY = houseY[duplicateGap];
            House extra = new House(level.houses.size() + 1, extraX, extraY, extraSize, extraSize, houseAssetForSize(extraSize));
            int repositionGuard = 0;
            while (!canPlaceRect(level, extra.x, extra.y, extra.w, extra.h) && repositionGuard++ < 24) {
                extraX = 3 + rng.nextInt(Math.max(1, GRID_W - extraSize - 6));
                extra = new House(level.houses.size() + 1, extraX, extraY, extraSize, extraSize, houseAssetForSize(extraSize));
            }
            if (!canPlaceRect(level, extra.x, extra.y, extra.w, extra.h)) {
                continue;
            }
            level.houses.add(extra);
            int remaining = profile.targetPorts - createdPorts.size();
            int extraConnectorY = extra.y + extra.h - 1;
            if (remaining >= 1) {
                createdPorts.add(new Port(createdPorts.size() + 1, extra.id, utilities.get(duplicateGap),
                        extra.x + extra.w, extraConnectorY, Direction.RIGHT));
            }
            if (remaining >= 2) {
                createdPorts.add(new Port(createdPorts.size() + 1, extra.id, utilities.get(duplicateGap + 1),
                        extra.x - 1, extraConnectorY, Direction.LEFT));
            }
            if (createdPorts.size() != profile.targetPorts) {
                continue;
            }
            for (Utility utility : utilities) {
                for (Port port : createdPorts) {
                    if (port.utility == utility) {
                        level.ports.add(port);
                    }
                }
            }
            ArrayList<Stroke> planned = plannedSolution(level);
            if (planned == null || !generatedLevelMeetsChallenge(level, planned, profile)) {
                continue;
            }
            addGeneratedBlockers(level, planned, rng, profile.blockerCount);
            if (!validatePlannedSolution(level, planned)) {
                continue;
            }
            recordHiddenSolution(level, planned, "striped");
            return level;
        }
        return null;
    }

    private String houseAssetForSize(int size) {
        if (size >= 5) {
            return "art/houses/house_5x5.png";
        }
        if (size >= 4) {
            return "art/houses/house 4x4.png";
        }
        if (size >= 2) {
            return "art/houses/house_2x2.png";
        }
        return "art/houses/house_1x1.png";
    }

    private Level constructPlanarNetworkLevel(int number, DifficultyProfile profile) {
        String[] backgrounds = {"art/backgrounds/warm.png", "art/backgrounds/farm.png", "art/backgrounds/tropical.png", "art/backgrounds/desert.png"};
        for (int attempt = 0; attempt < 90; attempt++) {
            Random rng = levelRandom(number, 40_000 + attempt);
            Level level = new Level(number, backgrounds[Math.abs(number - 1) % backgrounds.length]);
            addPressureTerrain(level, rng, profile);
            ArrayList<Utility> utilities = shuffledUtilities(rng);
            ArrayList<Integer> slots = planarRingSlots(profile.sourceCount, rng);
            if (slots.size() != profile.sourceCount) {
                continue;
            }
            for (int i = 0; i < profile.sourceCount; i++) {
                Source source = planarRingSource(utilities.get(i), slots.get(i));
                if (!canPlaceSource(level, source)) {
                    level = null;
                    break;
                }
                level.sources.add(source);
            }
            if (level == null) {
                continue;
            }
            ArrayList<House> ringHouses = new ArrayList<>();
            for (int i = 0; i < profile.houseCount; i++) {
                int arc = slots.get(i % slots.size());
                int[] anchor = planarHouseAnchor(arc);
                int x = clamp(anchor[0] + (attempt == 0 ? 0 : rng.nextInt(3) - 1), 2, GRID_W - 4);
                int y = clamp(anchor[1] + (attempt == 0 ? 0 : rng.nextInt(3) - 1), 2, GRID_H - 4);
                int minimumSize = profile.minPortsPerHouse >= 3 || profile.sourceCount >= 3 ? 2 : 1;
                int size = minimumSize;
                if (i == Math.abs(number + attempt) % profile.houseCount && profile.tier >= 8) {
                    size = 4;
                } else if ((attempt + i) % 4 == 3) {
                    size = Math.max(size, 2);
                }
                String asset = houseAssetForSize(size);
                if (!canPlaceRect(level, x, y, size, size)) {
                    level = null;
                    break;
                }
                House house = new House(i + 1, x, y, size, size, asset);
                level.houses.add(house);
                ringHouses.add(house);
            }
            if (level == null) {
                continue;
            }
            ArrayList<Demand> demands = new ArrayList<>();
            for (int i = 0; i < profile.houseCount; i++) {
                demands.add(new Demand(ringHouses.get(i), utilities.get(i % profile.sourceCount)));
            }
            int extras = profile.targetPorts - demands.size();
            for (int i = 0; i < extras; i++) {
                demands.add(new Demand(ringHouses.get(i % ringHouses.size()), utilities.get((i + 1) % profile.sourceCount)));
            }
            ArrayList<Demand> ordered = orderDemandsForRouting(demands, utilities, profile.sourceCount, level, rng);
            for (Demand demand : ordered) {
                Port port = randomPortForHouse(level, demand.house, demand.utility, rng);
                if (port == null) {
                    level = null;
                    break;
                }
                level.ports.add(port);
            }
            if (level == null || level.ports.size() != profile.targetPorts) {
                continue;
            }
            ArrayList<Stroke> planned = plannedSolution(level);
            if (planned == null || !generatedLevelMeetsChallenge(level, planned, profile)) {
                continue;
            }
            addGeneratedBlockers(level, planned, rng, profile.blockerCount);
            if (!validatePlannedSolution(level, planned)) {
                continue;
            }
            recordHiddenSolution(level, planned, "planar");
            return level;
        }
        return null;
    }

    private ArrayList<Integer> planarRingSlots(int sourceCount, Random rng) {
        ArrayList<Integer> slots = new ArrayList<>();
        int omitted = sourceCount == 5 ? rng.nextInt(6) : -1;
        for (int slot = 0; slot < 6; slot++) {
            if (slot != omitted && slots.size() < sourceCount) {
                slots.add(slot);
            }
        }
        if (rng.nextBoolean()) {
            for (int left = 0, right = slots.size() - 1; left < right; left++, right--) {
                Integer value = slots.get(left);
                slots.set(left, slots.get(right));
                slots.set(right, value);
            }
        }
        return slots;
    }

    private Source planarRingSource(Utility utility, int slot) {
        switch (slot) {
            case 0:
                return new Source(utility, 3, LEVEL_EDGE_MARGIN, Direction.DOWN);
            case 1:
                return new Source(utility, GRID_W - 5, LEVEL_EDGE_MARGIN, Direction.DOWN);
            case 2:
                return new Source(utility, GRID_W - 3, 5, Direction.LEFT);
            case 3:
                return new Source(utility, GRID_W - 3, GRID_H - 7, Direction.LEFT);
            case 4:
                return new Source(utility, GRID_W - 5, GRID_H - 3, Direction.UP);
            case 5:
            default:
                return new Source(utility, 3, GRID_H - 3, Direction.UP);
        }
    }

    private int[] planarHouseAnchor(int arc) {
        switch (arc) {
            case 0:
                return new int[]{GRID_W / 2 - 1, 3};
            case 1:
                return new int[]{GRID_W - 5, 4};
            case 2:
                return new int[]{GRID_W - 5, GRID_H / 2 - 1};
            case 3:
                return new int[]{GRID_W / 2, GRID_H - 5};
            case 4:
                return new int[]{3, GRID_H - 5};
            case 5:
            default:
                return new int[]{3, GRID_H / 2 - 1};
        }
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
            if (validatePlannedSolution(level, planned)) {
                recordHiddenSolution(level, planned, "fallback");
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

        addPressureTerrain(level, rng, profile);
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
        if (profile.alignmentMode != 0) {
            appendTerrainStyle(level, profile.alignmentMode == 1 ? "house-row" : "house-column");
        }

        int structuralBlockers = level.blockers.size();
        int assignmentTries = profile.tier < 3 ? 2 : 4;
        for (int assignment = 0; assignment < assignmentTries; assignment++) {
            level.ports.clear();
            Random assignmentRng = levelRandom(number, 100_000 + attempt * 7 + assignment);
            if (!assignGeneratedPorts(level, utilities, profile, assignmentRng)) {
                continue;
            }
            ArrayList<Stroke> planned = plannedSolution(level);
            if (planned == null) {
                continue;
            }
            addGeneratedBlockers(level, planned, assignmentRng, profile.blockerCount);
            if (!validatePlannedSolution(level, planned)) {
                while (level.blockers.size() > structuralBlockers) {
                    level.blockers.remove(level.blockers.size() - 1);
                }
                continue;
            }
            recordHiddenSolution(level, planned, "candidate");
            int score = scoreGeneratedLevel(level, planned, profile);
            return new CandidateResult(level, score, generatedLevelMeetsChallenge(level, planned, profile));
        }
        return null;
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
        Random profileRng = levelRandom(safeNumber, -771);
        float growth = clamp(((float) Math.sqrt(Math.min(144, safeNumber)) - 1f) / 11f, 0f, 1f);
        float variation = (profileRng.nextFloat() - 0.5f) * 0.24f
                + (float) Math.sin(safeNumber * 1.73f) * 0.055f;
        float pressure = clamp(growth + variation, 0f, 1f);
        int tier = clamp(Math.round(pressure * 9f), 0, 9);
        int family = safeNumber <= 2 ? 0 : 1 + profileRng.nextInt(3);
        int sourceCount;
        int houseCount;
        int targetPorts;
        int minPortsPerHouse;
        if (safeNumber == 1) {
            sourceCount = 2;
            houseCount = 2;
            targetPorts = 3;
            minPortsPerHouse = 1;
        } else if (safeNumber == 2) {
            sourceCount = 2;
            houseCount = 2;
            targetPorts = 4;
            minPortsPerHouse = 2;
        } else if (family == 1) {
            sourceCount = 2;
            houseCount = clamp(3 + Math.round(pressure * 3f + (profileRng.nextFloat() - 0.5f)), 3, 6);
            minPortsPerHouse = 2;
            targetPorts = houseCount * minPortsPerHouse;
        } else if (family == 2) {
            sourceCount = pressure < 0.34f ? 2 : 3;
            boolean fullService = pressure > 0.76f && sourceCount >= 3;
            houseCount = fullService ? 3
                    : clamp(3 + Math.round(pressure * 1.6f + (profileRng.nextFloat() - 0.5f)), 3, 4);
            minPortsPerHouse = fullService ? sourceCount : 2;
            targetPorts = Math.min(houseCount * sourceCount,
                    houseCount * minPortsPerHouse
                            + (minPortsPerHouse < sourceCount ? Math.max(1, Math.round(pressure * houseCount * 0.72f)) : 0));
        } else {
            sourceCount = pressure > 0.86f && profileRng.nextFloat() < 0.35f ? 4 : 3;
            houseCount = clamp(3 + Math.round(pressure * 2f + (profileRng.nextFloat() - 0.5f)), 3, 5);
            minPortsPerHouse = 2;
            targetPorts = Math.min(houseCount * sourceCount,
                    Math.max(houseCount * minPortsPerHouse,
                            Math.min(tier < 7 ? 9 : 10,
                                    houseCount * 2 + Math.max(0, Math.round(pressure * Math.min(houseCount, 3))))));
        }
        boolean fullServiceSwitchyard = family == 2 && minPortsPerHouse == sourceCount;
        int barrierLines = tier < 5 || fullServiceSwitchyard ? 0
                : pressure < 0.76f ? (profileRng.nextFloat() < 0.42f ? 1 : 0)
                : 1;
        int blockerCount = 1 + Math.round(pressure * 5f);
        int minSharedUtilities = sourceCount == 1 ? 0
                : Math.min(sourceCount, targetPorts > houseCount ? Math.max(1, sourceCount - 1) : 1);
        int minAverageTravel = safeNumber <= 2 ? 4 : 7 + Math.round(pressure * 7f);
        int minConstrainedRoutes = barrierLines == 0 ? 0 : pressure > 0.78f ? 2 : 1;
        int minTension = pressure < 0.72f ? 0 : 1;
        int maxAttempts = 52 + tier * 12 + barrierLines * 26;
        int minAttempts = 0;
        int minBends = safeNumber <= 2 ? 0 : pressure > 0.55f ? 2 : 1;
        int minExtra = pressure > 0.74f ? 2 : 0;
        int acceptScore = 115 + tier * 34 + barrierLines * 24;
        int floorScore = 64 + tier * 20;
        int alignmentMode = pressure > 0.55f && profileRng.nextFloat() < (0.18f + pressure * 0.36f)
                ? (profileRng.nextBoolean() ? 1 : 2) : 0;
        int alignmentCoord = alignmentMode == 1
                ? 5 + profileRng.nextInt(Math.max(1, GRID_H - 10))
                : 4 + profileRng.nextInt(Math.max(1, GRID_W - 8));
        return new DifficultyProfile(tier, sourceCount, houseCount, targetPorts, blockerCount, minBends, minExtra,
                minSharedUtilities, maxAttempts, minAttempts, acceptScore, floorScore, family, minPortsPerHouse,
                minAverageTravel, minConstrainedRoutes, minTension, barrierLines, alignmentMode, alignmentCoord);
    }

    private Level emergencySingleRouteLevel(int number, String background, Utility utility) {
        Level level = new Level(number, background);
        level.sources.add(emergencySourceForUtility(utility, number));
        int x = 4 + Math.abs(number * 3) % Math.max(1, GRID_W - 8);
        int y = 4 + Math.abs(number * 5) % Math.max(1, GRID_H - 9);
        level.houses.add(new House(1, x, y, 1, 1, "art/houses/house_1x1.png"));
        level.ports.add(new Port(1, 1, utility, x, y + 1, Direction.DOWN));
        ArrayList<Stroke> planned = plannedSolution(level);
        if (planned != null) {
            recordHiddenSolution(level, planned, "emergency-single");
        }
        return level;
    }

    private Level emergencyNetworkLevel(int number, String background, ArrayList<Utility> utilities, Random rng) {
        Level level = new Level(number, background);
        DifficultyProfile profile = new DifficultyProfile(0, 2, 2, 3, 0, 0, 0, 1, 1, 0, 0, 0,
                0, 1, 0, 0, 0, 0, 0, 0);
        if (!placeFallbackSources(level, utilities, profile.sourceCount)) {
            return null;
        }
        level.houses.add(new House(1, 4, 5, 1, 1, "art/houses/house_1x1.png"));
        level.houses.add(new House(2, 8, 12, 1, 1, "art/houses/house_1x1.png"));
        if (!assignGeneratedPorts(level, utilities, profile, rng)) {
            return null;
        }
        ArrayList<Stroke> planned = plannedSolution(level);
        if (planned != null && validatePlannedSolution(level, planned)) {
            recordHiddenSolution(level, planned, "emergency-network");
            return level;
        }
        return null;
    }

    private void recordHiddenSolution(Level level, ArrayList<Stroke> planned, String mode) {
        level.hiddenSolution.clear();
        level.hiddenSolution.addAll(planned);
        level.generationMode = mode;
        assignGeneratedMechanic(level, planned, mode);
    }

    private void assignGeneratedMechanic(Level level, ArrayList<Stroke> planned, String mode) {
        level.mechanic = DynamicMechanic.NONE;
        level.mechanicUtility = null;
        level.mechanicTriggerPortId = -1;
        level.mechanicX = 0;
        level.mechanicY = 0;
        level.mechanicW = 1;
        level.mechanicH = 1;
        level.mechanicRadiusCells = 2;
        if (level.number < 8 || (!"candidate".equals(mode) && !"planar".equals(mode) && !"striped".equals(mode))) {
            return;
        }
        DifficultyProfile profile = difficultyProfile(level.number);
        int roll = Math.abs((level.number * 37 + profile.family * 19 + profile.tier * 11) % 100);
        boolean assigned = false;
        if (profile.tier >= 4 && roll < 34) {
            assigned = tryAssignPumpGate(level, planned);
        }
        if (!assigned && profile.tier >= 3 && roll >= 34 && roll < 68) {
            assigned = tryAssignFumeSplit(level, planned);
        }
        if (!assigned && profile.tier >= 5) {
            assigned = tryAssignFumeSplit(level, planned) || tryAssignPumpGate(level, planned);
        }
        if (!assigned || !validateMechanicWitness(level, planned)) {
            level.mechanic = DynamicMechanic.NONE;
            level.mechanicUtility = null;
            level.mechanicTriggerPortId = -1;
            return;
        }
        appendTerrainStyle(level, level.mechanic == DynamicMechanic.PUMP_GATE ? "pump-gate" : "fume-split");
    }

    private boolean tryAssignPumpGate(Level level, ArrayList<Stroke> planned) {
        int triggerIndex = -1;
        Stroke trigger = null;
        for (int i = 0; i < planned.size(); i++) {
            if (planned.get(i).utility == Utility.WATER) {
                triggerIndex = i;
                trigger = planned.get(i);
                break;
            }
        }
        if (trigger == null || trigger.cells.size() < 4) {
            return false;
        }
        for (int i = triggerIndex + 1; i < planned.size(); i++) {
            Stroke candidate = planned.get(i);
            if (candidate.utility == Utility.WATER || candidate.cells.size() < 10) {
                continue;
            }
            for (int offset = 0; offset < 8; offset++) {
                int index = clamp(candidate.cells.size() / 2 + offset - 4, 2, candidate.cells.size() - 3);
                if (tryPlacePumpGateArea(level, planned, trigger, triggerIndex, candidate, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryPlacePumpGateArea(Level level, ArrayList<Stroke> planned, Stroke trigger, int triggerIndex,
                                         Stroke gatedStroke, int routeIndex) {
        Cell gate = gatedStroke.cells.get(routeIndex);
        Cell before = gatedStroke.cells.get(routeIndex - 1);
        Cell after = gatedStroke.cells.get(routeIndex + 1);
        boolean horizontalRoute = Math.abs(after.x - before.x) >= Math.abs(after.y - before.y);
        int[] lengths = {9, 7, 5};
        for (int length : lengths) {
            int w = horizontalRoute ? 1 : length;
            int h = horizontalRoute ? length : 1;
            int x = gate.x - w / 2;
            int y = gate.y - h / 2;
            if (!pumpGateAreaIsClear(level, trigger, x, y, w, h)) {
                continue;
            }
            level.mechanic = DynamicMechanic.PUMP_GATE;
            level.mechanicUtility = Utility.WATER;
            level.mechanicTriggerPortId = trigger.portId;
            level.mechanicX = x;
            level.mechanicY = y;
            level.mechanicW = w;
            level.mechanicH = h;
            if (pumpGateCreatesRealBlock(level, planned, triggerIndex, gatedStroke)) {
                return true;
            }
            level.mechanic = DynamicMechanic.NONE;
            level.mechanicUtility = null;
            level.mechanicTriggerPortId = -1;
        }
        return false;
    }

    private boolean pumpGateAreaIsClear(Level level, Stroke trigger, int x, int y, int w, int h) {
        if (!inPlayableInterior(x, y) || !inPlayableInterior(x + w - 1, y + h - 1)) {
            return false;
        }
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (level.isHouseCell(xx, yy) || level.isSourceProviderCell(xx, yy)
                        || level.isBlockerCell(xx, yy) || level.isEndpointCell(xx, yy)
                        || level.isSourceOutletCell(xx, yy) || level.isSourceRouteCell(xx, yy)) {
                    return false;
                }
                for (Cell cell : trigger.cells) {
                    if (cell.x == xx && cell.y == yy) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean pumpGateCreatesRealBlock(Level level, ArrayList<Stroke> planned, int triggerIndex, Stroke gatedStroke) {
        Port gatedPort = level.findPort(gatedStroke.portId);
        if (gatedPort == null || !strokeTouchesDynamicGate(level, gatedStroke)) {
            return false;
        }
        ArrayList<Stroke> beforePump = new ArrayList<>();
        for (int i = 0; i < triggerIndex; i++) {
            beforePump.add(planned.get(i));
        }
        return findBestPlannedRoute(level, gatedPort.utility, gatedPort, beforePump, true) == null;
    }

    private boolean tryAssignFumeSplit(Level level, ArrayList<Stroke> planned) {
        boolean hasGas = false;
        boolean hasHeat = false;
        for (Stroke stroke : planned) {
            hasGas |= stroke.utility == Utility.GAS;
            hasHeat |= stroke.utility == Utility.HEATING;
        }
        if (!hasGas || !hasHeat) {
            return false;
        }
        int radius = 2;
        for (Stroke gas : planned) {
            if (gas.utility != Utility.GAS) {
                continue;
            }
            for (Stroke heat : planned) {
                if (heat.utility == Utility.HEATING && minRouteDistanceCells(gas, heat) <= radius + 1) {
                    return false;
                }
            }
        }
        level.mechanic = DynamicMechanic.FUME_SPLIT;
        level.mechanicUtility = Utility.GAS;
        level.mechanicRadiusCells = radius;
        return true;
    }

    private boolean placeFallbackSources(Level level, ArrayList<Utility> utilities, int sourceCount) {
        int right = GRID_W - 3;
        int bottom = GRID_H - 3;
        Source[] placements = {
                new Source(utilities.get(0), LEVEL_EDGE_MARGIN, 2, Direction.RIGHT),
                new Source(utilities.get(Math.min(1, utilities.size() - 1)), right, GRID_H - 5, Direction.LEFT),
                new Source(utilities.get(Math.min(2, utilities.size() - 1)), LEVEL_EDGE_MARGIN, GRID_H - 5, Direction.RIGHT),
                new Source(utilities.get(Math.min(3, utilities.size() - 1)), right, 2, Direction.LEFT),
                new Source(utilities.get(Math.min(4, utilities.size() - 1)), 4, LEVEL_EDGE_MARGIN, Direction.DOWN),
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
        if (maxPossiblePorts < profile.targetPorts) {
            return false;
        }
        int targetPorts = profile.targetPorts;

        int[] remaining = targetUtilityCounts(sourceCount, targetPorts, rng);
        HashMap<Integer, HashSet<Utility>> plannedUtilities = new HashMap<>();
        HashMap<Integer, Integer> plannedCounts = new HashMap<>();
        ArrayList<Demand> demands = new ArrayList<>();
        for (int pass = 0; pass < profile.minPortsPerHouse; pass++) {
            ArrayList<House> firstPass = shuffledHouses(level.houses, rng);
            for (House house : firstPass) {
                if (demands.size() >= targetPorts) {
                    break;
                }
                int utilityIndex = chooseUtilityForHouse(level, house, utilities, remaining, plannedUtilities, sourceCount, rng);
                if (utilityIndex < 0) {
                    return false;
                }
                addDemand(demands, plannedUtilities, plannedCounts, house, utilities.get(utilityIndex));
                remaining[utilityIndex]--;
            }
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
            Port port = randomPortForHouse(level, demand.house, demand.utility, rng);
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
            float score = remaining[i] * 80f + distanceScore * 2.8f + rng.nextFloat() * 12f;
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
            if (current >= housePortCapacity(house) || plannedHouseHasUtility(plannedUtilities, house.id, utility)) {
                continue;
            }
            float distanceScore = source == null ? 0f : Math.abs(source.connectorX() - house.x) + Math.abs(source.connectorY() - house.y);
            float score = (8 - current) * 50f + distanceScore * 2.1f + rng.nextFloat() * 12f;
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

    private void addPressureTerrain(Level level, Random rng, DifficultyProfile profile) {
        if (profile.barrierLines == 0) {
            level.terrainStyle = "open";
            return;
        }
        for (int barrier = 0; barrier < profile.barrierLines; barrier++) {
            int motif = rng.nextInt(profile.tier >= 8 ? 5 : 4);
            if (motif == 0) {
                addOffsetCauseway(level, rng, profile);
                appendTerrainStyle(level, "causeway");
            } else if (motif == 1) {
                addSwitchbackTerrain(level, rng, profile);
                appendTerrainStyle(level, "switchback");
            } else if (motif == 2) {
                addBrokenDiagonalTerrain(level, rng, profile);
                appendTerrainStyle(level, "diagonal");
            } else if (motif == 3) {
                addGatePairTerrain(level, rng, profile);
                appendTerrainStyle(level, "gates");
            } else {
                addCourtyardTerrain(level, rng);
                appendTerrainStyle(level, "courtyard");
            }
        }
    }

    private void appendTerrainStyle(Level level, String style) {
        level.terrainStyle = "open".equals(level.terrainStyle) ? style : level.terrainStyle + "+" + style;
    }

    private void addOffsetCauseway(Level level, Random rng, DifficultyProfile profile) {
        boolean vertical = rng.nextBoolean();
        int fixed = vertical
                ? (rng.nextBoolean() ? 5 + rng.nextInt(3) : GRID_W - 8 + rng.nextInt(3))
                : (rng.nextBoolean() ? 9 + rng.nextInt(4) : GRID_H - 13 + rng.nextInt(4));
        int length = vertical ? GRID_H : GRID_W;
        int start = 3 + rng.nextInt(3);
        int end = length - 4 - rng.nextInt(3);
        int firstGap = start + (end - start) / 3 + rng.nextInt(3) - 1;
        int secondGap = start + (end - start) * 2 / 3 + rng.nextInt(3) - 1;
        for (int along = start; along <= end; along++) {
            boolean opening = Math.abs(along - firstGap) <= (profile.tier < 7 ? 1 : 0)
                    || Math.abs(along - secondGap) <= 1;
            if (!opening) {
                addTerrainCell(level, vertical ? fixed : along, vertical ? along : fixed, along);
            }
        }
    }

    private void addSwitchbackTerrain(Level level, Random rng, DifficultyProfile profile) {
        boolean vertical = rng.nextBoolean();
        int shift = rng.nextInt(5) - 2;
        int firstFixed = vertical ? GRID_W / 2 - 3 + shift : GRID_H / 2 - 5 + shift;
        int secondFixed = vertical ? GRID_W / 2 + 3 + shift : GRID_H / 2 + 5 + shift;
        int firstStart = 3;
        int firstEnd = (vertical ? GRID_H : GRID_W) / 2 + 3;
        int secondStart = (vertical ? GRID_H : GRID_W) / 2 - 3;
        int secondEnd = (vertical ? GRID_H : GRID_W) - 4;
        int firstGap = firstEnd - (profile.tier >= 8 ? 2 : 3);
        int secondGap = secondStart + (profile.tier >= 8 ? 2 : 3);
        for (int along = firstStart; along <= firstEnd; along++) {
            if (Math.abs(along - firstGap) > 1) {
                addTerrainCell(level, vertical ? firstFixed : along, vertical ? along : firstFixed, along);
            }
        }
        for (int along = secondStart; along <= secondEnd; along++) {
            if (Math.abs(along - secondGap) > 1) {
                addTerrainCell(level, vertical ? secondFixed : along, vertical ? along : secondFixed, along + 1);
            }
        }
    }

    private void addBrokenDiagonalTerrain(Level level, Random rng, DifficultyProfile profile) {
        boolean falling = rng.nextBoolean();
        int startX = 4 + rng.nextInt(3);
        int startY = falling ? 7 + rng.nextInt(5) : GRID_H - 8 - rng.nextInt(5);
        int count = profile.tier >= 8 ? 13 : 10;
        int gap = 3 + rng.nextInt(Math.max(1, count - 5));
        for (int step = 0; step < count; step++) {
            if (step == gap || step == gap + 1) {
                continue;
            }
            int x = startX + step;
            int y = startY + (falling ? step : -step);
            addTerrainCell(level, x, y, step);
            if (profile.tier >= 8 && step % 3 == 0) {
                addTerrainCell(level, x, y + (falling ? 1 : -1), step + 1);
            }
        }
    }

    private void addGatePairTerrain(Level level, Random rng, DifficultyProfile profile) {
        boolean vertical = rng.nextBoolean();
        int center = vertical ? GRID_W / 2 + rng.nextInt(5) - 2 : GRID_H / 2 + rng.nextInt(7) - 3;
        int start = 3;
        int end = (vertical ? GRID_H : GRID_W) - 4;
        int gateA = start + (end - start) / 3 + rng.nextInt(3) - 1;
        int gateB = start + (end - start) * 2 / 3 + rng.nextInt(3) - 1;
        for (int along = start; along <= end; along++) {
            int gapRadius = profile.tier >= 8 ? 0 : 1;
            if (Math.abs(along - gateA) <= gapRadius || Math.abs(along - gateB) <= gapRadius) {
                continue;
            }
            addTerrainCell(level, vertical ? center : along, vertical ? along : center, along);
        }
        int spur = vertical ? center + (rng.nextBoolean() ? 1 : -1) : center + (rng.nextBoolean() ? 1 : -1);
        for (int offset = -2; offset <= 2; offset++) {
            int along = gateA + offset;
            if (Math.abs(offset) != 1) {
                addTerrainCell(level, vertical ? spur : along, vertical ? along : spur, along + 2);
            }
        }
    }

    private void addCourtyardTerrain(Level level, Random rng) {
        int left = rng.nextBoolean() ? 4 + rng.nextInt(3) : GRID_W - 10 - rng.nextInt(3);
        int top = 8 + rng.nextInt(Math.max(1, GRID_H - 18));
        int width = 5 + rng.nextInt(2);
        int height = 6 + rng.nextInt(3);
        boolean openingUp = rng.nextBoolean();
        for (int x = left; x <= left + width; x++) {
            addTerrainCell(level, x, openingUp ? top + height : top, x);
        }
        for (int y = top; y <= top + height; y++) {
            addTerrainCell(level, left, y, y);
            addTerrainCell(level, left + width, y, y + 1);
        }
    }

    private void addTerrainCell(Level level, int x, int y, int variant) {
        if (!inPlayableInterior(x, y) || level.isBlockerCell(x, y)) {
            return;
        }
        String asset = Math.abs(variant) % 4 == 0
                ? "art/blockers/stone_1x1.png" : "art/blockers/construction_1x1.png";
        level.blockers.add(new Blocker(asset, x, y, 1, 1));
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
                    // Route the far service demand first so later homes can split from a meaningful shared trunk.
                    float score = distanceScore + rng.nextFloat() * 2f;
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
        Direction[] exits = aestheticSourceExits(utility);
        for (int attempt = 0; attempt < 180; attempt++) {
            int x = 1 + rng.nextInt(Math.max(1, GRID_W - 3));
            int y = 1 + rng.nextInt(Math.max(1, GRID_H - 3));
            candidates.add(new Source(utility, x, y, exits[rng.nextInt(exits.length)]));
        }
        Source best = null;
        float bestScore = -Float.MAX_VALUE;
        for (Source candidate : candidates) {
            if (!canPlaceSource(level, candidate)) {
                continue;
            }
            float innerMargin = Math.min(Math.min(candidate.x, GRID_W - candidate.x - 2),
                    Math.min(candidate.y, GRID_H - candidate.y - 2));
            float score = sourcePlacementScore(level, candidate) + Math.min(4f, innerMargin) * 0.55f + rng.nextFloat() * 6f;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private Direction[] aestheticSourceExits(Utility utility) {
        switch (utility) {
            case SEWAGE:
                return new Direction[]{Direction.LEFT, Direction.LEFT, Direction.DOWN};
            case HEATING:
                return new Direction[]{Direction.DOWN, Direction.LEFT, Direction.RIGHT};
            case WATER:
                return new Direction[]{Direction.DOWN, Direction.RIGHT, Direction.LEFT};
            case INTERNET:
                return new Direction[]{Direction.DOWN, Direction.RIGHT, Direction.LEFT};
            case ELECTRIC:
            case GAS:
            default:
                return new Direction[]{Direction.LEFT, Direction.RIGHT, Direction.DOWN};
        }
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
        if (!inPlayableInterior(connectorX, connectorY) || !inPlayableInterior(routeX, routeY)) {
            return false;
        }
        if (level.isSourceProviderCell(connectorX, connectorY) || level.isEndpointCell(connectorX, connectorY) || level.isSourceRouteCell(connectorX, connectorY)
                || level.isHouseCell(connectorX, connectorY) || level.isBlockerCell(connectorX, connectorY)) {
            return false;
        }
        return !level.isSourceProviderCell(routeX, routeY) && !level.isEndpointCell(routeX, routeY) && !level.isSourceRouteCell(routeX, routeY)
                && !level.isHouseCell(routeX, routeY) && !level.isBlockerCell(routeX, routeY)
                && exposedDockHasClearance(level, connectorX, connectorY, routeX, routeY, -1);
    }

    private Source emergencySourceForUtility(Utility utility, int number) {
        int rightX = GRID_W - 3;
        int upper = LEVEL_EDGE_MARGIN;
        int middle = Math.max(0, GRID_H / 3);
        int lower = Math.max(0, GRID_H - 6);
        int offset = Math.abs(number) % 3;
        switch (utility) {
            case WATER:
                return new Source(utility, LEVEL_EDGE_MARGIN, Math.min(GRID_H - 3, upper + offset), Direction.RIGHT);
            case ELECTRIC:
                return new Source(utility, LEVEL_EDGE_MARGIN, Math.min(GRID_H - 3, middle + offset), Direction.RIGHT);
            case HEATING:
                return new Source(utility, LEVEL_EDGE_MARGIN, Math.min(GRID_H - 3, lower + offset), Direction.RIGHT);
            case GAS:
                return new Source(utility, rightX, Math.min(GRID_H - 3, upper + offset), Direction.LEFT);
            case SEWAGE:
                return new Source(utility, rightX, Math.min(GRID_H - 3, middle + offset), Direction.LEFT);
            case INTERNET:
            default:
                return new Source(utility, rightX, Math.min(GRID_H - 3, lower + offset), Direction.LEFT);
        }
    }

    private House randomHouse(Level level, Random rng, int id, DifficultyProfile profile) {
        String[][] options = houseOptionsForTier(profile.tier);
        for (int attempt = 0; attempt < 220; attempt++) {
            String[] option = options[rng.nextInt(options.length)];
            int w = Integer.parseInt(option[0]);
            int h = Integer.parseInt(option[1]);
            int guaranteedSize = profile.tier >= 8 && id == 1 ? 4 : profile.tier >= 2 && id == 1 ? 2 : 1;
            if (Math.max(w, h) < guaranteedSize) {
                continue;
            }
            if ((profile.minPortsPerHouse >= 3 || profile.sourceCount >= 3) && Math.max(w, h) < 2) {
                continue;
            }
            int minX = 2;
            int maxX = Math.max(minX, GRID_W - 3 - w);
        int minY = 2;
            int maxY = Math.max(minY, GRID_H - 3 - h);
            int x = minX + rng.nextInt(Math.max(1, maxX - minX + 1));
            int y = minY + rng.nextInt(Math.max(1, maxY - minY + 1));
            if (profile.alignmentMode == 1 && attempt < 170) {
                y = clamp(profile.alignmentCoord + rng.nextInt(5) - 2, minY, maxY);
            } else if (profile.alignmentMode == 2 && attempt < 170) {
                x = clamp(profile.alignmentCoord + rng.nextInt(5) - 2, minX, maxX);
            }
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
                    {"4", "4", "art/houses/house 4x4.png"},
                    {"4", "4", "art/houses/house 4x4.png"}
            };
        }
        return new String[][]{
                {"1", "1", "art/houses/house_1x1.png"},
                {"2", "2", "art/houses/house_2x2.png"},
                {"2", "2", "art/houses/house_2x2.png"},
                {"4", "4", "art/houses/house 4x4.png"},
                {"4", "4", "art/houses/house 4x4.png"},
                {"5", "5", "art/houses/house_5x5.png"}
        };
    }

    private String[] fallbackHouseOption(int index, DifficultyProfile profile) {
        if (profile.tier >= 6 && index % 4 == 1) {
            return new String[]{"4", "4", "art/houses/house 4x4.png"};
        }
        if (profile.tier >= 4 && index % 3 == 1) {
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
        if (x < LEVEL_EDGE_MARGIN || y < LEVEL_EDGE_MARGIN
                || x + w > GRID_W - LEVEL_EDGE_MARGIN || y + h > GRID_H - LEVEL_EDGE_MARGIN) {
            return false;
        }
        if (rectNearExistingDock(level, x, y, w, h)) {
            return false;
        }
        if (rectInsideProtectedHalo(level, x, y, w, h)) {
            return false;
        }
        if (rectNearExistingObject(level, x, y, w, h)) {
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

    private boolean rectInsideProtectedHalo(Level level, int x, int y, int w, int h) {
        for (House house : level.houses) {
            if (circularHalosOverlap(x, y, w, h, house.x, house.y, house.w, house.h)) {
                return true;
            }
        }
        for (Source source : level.sources) {
            if (circularHalosOverlap(x, y, w, h, source.x, source.y, 2, 2)) {
                return true;
            }
        }
        return false;
    }

    private boolean circularHalosOverlap(int x, int y, int w, int h,
                                         int otherX, int otherY, int otherW, int otherH) {
        float centerX = x + w * 0.5f;
        float centerY = y + h * 0.5f;
        float otherCenterX = otherX + otherW * 0.5f;
        float otherCenterY = otherY + otherH * 0.5f;
        float radius = 0.5f * (float) Math.hypot(w, h);
        float otherRadius = 0.5f * (float) Math.hypot(otherW, otherH);
        return distance(centerX, centerY, otherCenterX, otherCenterY)
                < radius + otherRadius + FOCUS_HALO_CELLS;
    }

    private boolean rectNearExistingObject(Level level, int x, int y, int w, int h) {
        for (House house : level.houses) {
            if (rectanglesWithinClearance(x, y, w, h, house.x, house.y, house.w, house.h)) {
                return true;
            }
        }
        for (Source source : level.sources) {
            if (rectanglesWithinClearance(x, y, w, h, source.x, source.y, 2, 2)) {
                return true;
            }
        }
        for (Blocker blocker : level.blockers) {
            if (rectanglesWithinClearance(x, y, w, h, blocker.x, blocker.y, blocker.w, blocker.h)) {
                return true;
            }
        }
        return false;
    }

    private boolean rectanglesWithinClearance(int x, int y, int w, int h,
                                               int otherX, int otherY, int otherW, int otherH) {
        return x < otherX + otherW + OBJECT_CLEARANCE_CELLS
                && x + w + OBJECT_CLEARANCE_CELLS > otherX
                && y < otherY + otherH + OBJECT_CLEARANCE_CELLS
                && y + h + OBJECT_CLEARANCE_CELLS > otherY;
    }

    private boolean rectNearExistingDock(Level level, int x, int y, int w, int h) {
        for (Source source : level.sources) {
            if (rectTouchesClearance(x, y, w, h, source.connectorX(), source.connectorY())
                    || rectTouchesClearance(x, y, w, h, source.connectorX() + source.openDirection.dx,
                    source.connectorY() + source.openDirection.dy)) {
                return true;
            }
        }
        for (Port port : level.ports) {
            Cell route = portRouteCell(port);
            if (rectTouchesClearance(x, y, w, h, port.x, port.y)
                    || rectTouchesClearance(x, y, w, h, route.x, route.y)) {
                return true;
            }
        }
        return false;
    }

    private boolean rectTouchesClearance(int x, int y, int w, int h, int cellX, int cellY) {
        return cellX >= x - DOCK_CLEARANCE_CELLS && cellX < x + w + DOCK_CLEARANCE_CELLS
                && cellY >= y - DOCK_CLEARANCE_CELLS && cellY < y + h + DOCK_CLEARANCE_CELLS;
    }

    private Port randomPortForHouse(Level level, House house, Utility utility, Random rng) {
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
            ordered.add(candidates.remove(selected));
        }
        for (Port port : ordered) {
            Cell route = portRouteCell(port);
            if (inPlayableInterior(port.x, port.y) && inPlayableInterior(route.x, route.y)
                    && !level.isHouseCell(port.x, port.y)
                    && !level.isSourceProviderCell(port.x, port.y)
                    && !level.isEndpointCell(port.x, port.y)
                    && !level.isBlockerCell(port.x, port.y)
                    && !level.isHouseCell(route.x, route.y)
                    && !level.isSourceProviderCell(route.x, route.y)
                    && !level.isEndpointCell(route.x, route.y)
                    && !level.isBlockerCell(route.x, route.y)
                    && exposedDockHasClearance(level, port.x, port.y, route.x, route.y, house.id)) {
                return port;
            }
        }
        return null;
    }

    private boolean exposedDockHasClearance(Level level, int dockX, int dockY, int routeX, int routeY, int ownerHouseId) {
        for (House other : level.houses) {
            if (other.id == ownerHouseId) {
                continue;
            }
            if (rectTouchesClearance(other.x, other.y, other.w, other.h, dockX, dockY)
                    || rectTouchesClearance(other.x, other.y, other.w, other.h, routeX, routeY)) {
                return false;
            }
        }
        for (Source source : level.sources) {
            if (rectTouchesClearance(source.x, source.y, 2, 2, dockX, dockY)
                    || rectTouchesClearance(source.x, source.y, 2, 2, routeX, routeY)) {
                return false;
            }
        }
        for (Blocker blocker : level.blockers) {
            if (rectTouchesClearance(blocker.x, blocker.y, blocker.w, blocker.h, dockX, dockY)
                    || rectTouchesClearance(blocker.x, blocker.y, blocker.w, blocker.h, routeX, routeY)) {
                return false;
            }
        }
        for (Port existing : level.ports) {
            Cell existingRoute = portRouteCell(existing);
            if (Math.abs(existing.x - dockX) <= DOCK_CLEARANCE_CELLS
                    && Math.abs(existing.y - dockY) <= DOCK_CLEARANCE_CELLS) {
                return false;
            }
            if (Math.abs(existingRoute.x - routeX) <= DOCK_CLEARANCE_CELLS
                    && Math.abs(existingRoute.y - routeY) <= DOCK_CLEARANCE_CELLS) {
                return false;
            }
        }
        return true;
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
        HashMap<Utility, HashSet<Cell>> networks = new HashMap<>();
        for (Stroke stroke : planned) {
            Port port = level.findPort(stroke.portId);
            Source source = port == null ? null : level.findSource(port.utility);
            if (port == null || source == null || stroke.utility != port.utility
                    || stroke.cells.isEmpty() || solvedPorts.contains(port.id)) {
                return false;
            }
            solvedPorts.add(port.id);
            HashSet<Cell> network = networks.get(stroke.utility);
            Cell first = stroke.cells.get(0);
            boolean startsAtSource = first.equals(sourceRouteCell(source));
            boolean startsAtNetwork = network != null && network.contains(first);
            if (!startsAtSource && !startsAtNetwork) {
                return false;
            }
            Cell goal = portRouteCell(port);
            if (!stroke.cells.get(stroke.cells.size() - 1).equals(goal)) {
                return false;
            }
            for (int i = 0; i < stroke.cells.size(); i++) {
                Cell cell = stroke.cells.get(i);
                if (!inGrid(cell.x, cell.y) || level.isHouseCell(cell.x, cell.y) || level.isSourceProviderCell(cell.x, cell.y)
                        || level.isBlockerCell(cell.x, cell.y) || level.isSourceOutletCell(cell.x, cell.y)) {
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
            if (network == null) {
                network = new HashSet<>();
                networks.put(stroke.utility, network);
            }
            network.addAll(stroke.cells);
        }
        return solvedPorts.size() == level.ports.size() && validateMechanicWitness(level, planned);
    }

    private boolean validateMechanicWitness(Level level, ArrayList<Stroke> planned) {
        if (level.mechanic == DynamicMechanic.NONE) {
            return true;
        }
        switch (level.mechanic) {
            case PUMP_GATE:
                boolean triggerSeen = false;
                for (Stroke stroke : planned) {
                    if (!triggerSeen && stroke.utility != level.mechanicUtility && strokeTouchesDynamicGate(level, stroke)) {
                        return false;
                    }
                    if (stroke.portId == level.mechanicTriggerPortId) {
                        triggerSeen = true;
                    }
                }
                return triggerSeen;
            case FUME_SPLIT:
                for (Stroke gas : planned) {
                    if (gas.utility != Utility.GAS) {
                        continue;
                    }
                    for (Stroke heat : planned) {
                        if (heat.utility == Utility.HEATING
                                && minRouteDistanceCells(gas, heat) <= level.mechanicRadiusCells) {
                            return false;
                        }
                    }
                }
                return true;
            case NONE:
            default:
                return true;
        }
    }

    private boolean strokeTouchesDynamicGate(Level level, Stroke stroke) {
        for (Cell cell : stroke.cells) {
            if (cell.x >= level.mechanicX && cell.x < level.mechanicX + level.mechanicW
                    && cell.y >= level.mechanicY && cell.y < level.mechanicY + level.mechanicH) {
                return true;
            }
        }
        return false;
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
        if (minimumPortsOnAnyHouse(level) < profile.minPortsPerHouse) {
            return false;
        }
        if (averageDemandTravel(level) < profile.minAverageTravel) {
            return false;
        }
        if (constrainedRouteCount(level, planned) < profile.minConstrainedRoutes) {
            return false;
        }
        if (routeTension(planned) < profile.minTension) {
            return false;
        }
        if (profile.tier >= 2 && largestHouseUnit(level) < 2) {
            return false;
        }
        if (profile.tier >= 8 && largestHouseUnit(level) < 4) {
            return false;
        }
        if (totalRouteBends(planned) < profile.minBends) {
            return false;
        }
        return extraRouteLength(level, planned) >= profile.minExtraLength;
    }

    private int largestHouseUnit(Level level) {
        int largest = 0;
        for (House house : level.houses) {
            largest = Math.max(largest, Math.max(house.w, house.h));
        }
        return largest;
    }

    private int scoreGeneratedLevel(Level level, ArrayList<Stroke> planned, DifficultyProfile profile) {
        int bends = totalRouteBends(planned);
        int extra = extraRouteLength(level, planned);
        int shared = countSharedUtilities(level);
        int utilityCount = countUtilitiesWithPorts(level);
        int sourceZones = sourceZoneCount(level);
        int spread = houseSpread(level);
        int multiNeedHomes = multiNeedHouseCount(level);
        int routeTension = routeTension(planned);
        int demandTravel = averageDemandTravel(level);
        int constrainedRoutes = constrainedRouteCount(level, planned);
        int score = level.sources.size() * 9
                + level.houses.size() * 10
                + level.ports.size() * 8
                + shared * 16
                + utilityCount * 9
                + bends * 7
                + extra * 5
                + sourceZones * 8
                + spread * 3
                + multiNeedHomes * 18
                + routeTension * 3
                + demandTravel * 8
                + constrainedRoutes * 22
                + Math.min(level.blockers.size(), 16) * 2;
        if (generatedLevelMeetsChallenge(level, planned, profile)) {
            score += 120;
        }
        return score;
    }

    private int multiNeedHouseCount(Level level) {
        int count = 0;
        for (House house : level.houses) {
            if (portsForHouse(level, house.id) >= 2) {
                count++;
            }
        }
        return count;
    }

    private int minimumPortsOnAnyHouse(Level level) {
        int minimum = Integer.MAX_VALUE;
        for (House house : level.houses) {
            minimum = Math.min(minimum, portsForHouse(level, house.id));
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    private int averageDemandTravel(Level level) {
        if (level.ports.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Port port : level.ports) {
            Source source = level.findSource(port.utility);
            if (source == null) {
                continue;
            }
            Cell start = sourceRouteCell(source);
            Cell goal = portRouteCell(port);
            total += Math.abs(start.x - goal.x) + Math.abs(start.y - goal.y);
        }
        return Math.round(total / (float) level.ports.size());
    }

    private int constrainedRouteCount(Level level, ArrayList<Stroke> planned) {
        int count = 0;
        for (Stroke stroke : planned) {
            boolean constrained = false;
            for (Cell cell : stroke.cells) {
                for (Direction direction : Direction.values()) {
                    if (level.isBlockerCell(cell.x + direction.dx, cell.y + direction.dy)) {
                        constrained = true;
                        break;
                    }
                }
                if (constrained) {
                    break;
                }
            }
            if (constrained) {
                count++;
            }
        }
        return count;
    }

    private int routeTension(ArrayList<Stroke> planned) {
        int tension = 0;
        for (int i = 0; i < planned.size(); i++) {
            Stroke first = planned.get(i);
            for (int j = i + 1; j < planned.size(); j++) {
                Stroke second = planned.get(j);
                if (first.utility == second.utility) {
                    continue;
                }
                int nearest = Integer.MAX_VALUE;
                for (Cell a : first.cells) {
                    for (Cell b : second.cells) {
                        nearest = Math.min(nearest, Math.abs(a.x - b.x) + Math.abs(a.y - b.y));
                    }
                }
                if (nearest <= 1) {
                    tension += 3;
                } else if (nearest == 2) {
                    tension += 1;
                }
            }
        }
        return tension;
    }

    private int minRouteDistanceCells(Stroke first, Stroke second) {
        int nearest = Integer.MAX_VALUE;
        for (Cell a : first.cells) {
            for (Cell b : second.cells) {
                nearest = Math.min(nearest, Math.abs(a.x - b.x) + Math.abs(a.y - b.y));
            }
        }
        return nearest == Integer.MAX_VALUE ? 999 : nearest;
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
            total += housePortCapacity(house);
        }
        return total;
    }

    private int housePortCapacity(House house) {
        return clamp(Math.max(3, Math.max(house.w, house.h) + 1), 3, Utility.values().length);
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
        return findBestPlannedRoute(level, port.utility, port, planned, false);
    }

    private Route findBestPlannedRoute(Level level, Utility utility, Port port, ArrayList<Stroke> planned, boolean forceClosedPumpGate) {
        Route best = null;
        Source source = level.findSource(utility);
        if (source != null) {
            best = findRoute(level, utility, sourceRouteCell(source), sourceMouthPoint(source), port, planned, -1, forceClosedPumpGate);
        }
        for (Stroke stroke : planned) {
            if (stroke.utility != utility) {
                continue;
            }
            for (Cell cell : stroke.cells) {
                Route route = findRoute(level, utility, cell, cellCenter(cell.x, cell.y), port, planned, -1, forceClosedPumpGate);
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
        final int family;
        final int minPortsPerHouse;
        final int minAverageTravel;
        final int minConstrainedRoutes;
        final int minTension;
        final int barrierLines;
        final int alignmentMode;
        final int alignmentCoord;

        DifficultyProfile(int tier, int sourceCount, int houseCount, int targetPorts, int blockerCount,
                          int minBends, int minExtraLength, int minSharedUtilities, int maxAttempts,
                          int minAttempts, int acceptScore, int floorScore, int family, int minPortsPerHouse,
                          int minAverageTravel, int minConstrainedRoutes, int minTension, int barrierLines,
                          int alignmentMode, int alignmentCoord) {
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
            this.family = family;
            this.minPortsPerHouse = minPortsPerHouse;
            this.minAverageTravel = minAverageTravel;
            this.minConstrainedRoutes = minConstrainedRoutes;
            this.minTension = minTension;
            this.barrierLines = barrierLines;
            this.alignmentMode = alignmentMode;
            this.alignmentCoord = alignmentCoord;
        }

        DifficultyProfile withTargetPorts(int ports) {
            return new DifficultyProfile(tier, sourceCount, houseCount, ports, blockerCount, minBends, minExtraLength,
                    minSharedUtilities, maxAttempts, minAttempts, acceptScore, floorScore, family,
                    Math.min(minPortsPerHouse, Math.max(1, ports / Math.max(1, houseCount))), minAverageTravel,
                    minConstrainedRoutes, minTension, barrierLines, alignmentMode, alignmentCoord);
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

    private enum DynamicMechanic {
        NONE,
        PUMP_GATE,
        FUME_SPLIT
    }

    private static final class Level {
        final int number;
        final String background;
        final ArrayList<House> houses = new ArrayList<>();
        final ArrayList<Source> sources = new ArrayList<>();
        final ArrayList<Port> ports = new ArrayList<>();
        final ArrayList<Blocker> blockers = new ArrayList<>();
        final ArrayList<Stroke> strokes = new ArrayList<>();
        final ArrayList<Stroke> hiddenSolution = new ArrayList<>();
        String generationMode = "candidate";
        String terrainStyle = "open";
        String labTitle = "Mechanics Lab";
        String labRule = "";
        String labGoal = "";
        DynamicMechanic mechanic = DynamicMechanic.NONE;
        Utility mechanicUtility;
        int mechanicTriggerPortId = -1;
        int mechanicX;
        int mechanicY;
        int mechanicW;
        int mechanicH;
        int mechanicRadiusCells = 2;
        int generationAttempts;
        boolean finished;

        Level(int number, String background) {
            this.number = number;
            this.background = background;
        }

        void resetForPlay() {
            strokes.clear();
            finished = false;
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
            if (mechanic == DynamicMechanic.PUMP_GATE && mechanicTriggerPortId >= 0) {
                Port trigger = findPort(mechanicTriggerPortId);
                if (trigger != null && !trigger.connected) {
                    return trigger;
                }
            }
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

        House findHouse(int houseId) {
            for (House house : houses) {
                if (house.id == houseId) {
                    return house;
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

        boolean isSourceOutletCell(int x, int y) {
            for (Source source : sources) {
                if (source.connectorX() == x && source.connectorY() == y) {
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
        final boolean routed;

        Stroke(Utility utility, int portId, ArrayList<PointF> points, ArrayList<Cell> cells) {
            this(utility, portId, points, cells, false);
        }

        Stroke(Utility utility, int portId, ArrayList<PointF> points, ArrayList<Cell> cells, boolean routed) {
            this.utility = utility;
            this.portId = portId;
            this.points = points;
            this.cells = cells;
            this.routed = routed;
        }
    }

    private static final class StrokeHit {
        final Utility utility;
        final Cell cell;
        final PointF point;
        final int strokeIndex;

        StrokeHit(Utility utility, Cell cell, PointF point) {
            this(utility, cell, point, -1);
        }

        StrokeHit(Utility utility, Cell cell, PointF point, int strokeIndex) {
            this.utility = utility;
            this.cell = cell;
            this.point = point;
            this.strokeIndex = strokeIndex;
        }
    }

    private static final class FinishTouch {
        final Port port;
        final House house;

        FinishTouch(Port port, House house) {
            this.port = port;
            this.house = house;
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

    private static final class CelebrationStar {
        final float x;
        final float y;
        final float vx;
        final float vy;
        final float radius;
        final int color;
        final float rotation;
        final float spin;
        final long bornMs;

        CelebrationStar(float x, float y, float vx, float vy, float radius, int color,
                        float rotation, float spin, long bornMs) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.color = color;
            this.rotation = rotation;
            this.spin = spin;
            this.bornMs = bornMs;
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
