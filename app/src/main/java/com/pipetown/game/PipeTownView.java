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
    private static final int GRID_W = 10;
    private static final int GRID_H = 16;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GAME = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final Random random = new Random(12);
    private final AssetBank assets;
    private final ArrayList<Level> levels = new ArrayList<>();
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

    private final RectF homeButton = new RectF();
    private final RectF resetButton = new RectF();
    private final RectF undoButton = new RectF();
    private final RectF hintButton = new RectF();

    public PipeTownView(Context context) {
        super(context);
        setFocusable(true);
        density = getResources().getDisplayMetrics().density;
        assets = new AssetBank(context.getApplicationContext());
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStyle(Paint.Style.STROKE);
        buildLevels();
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
        drawCover(canvas, assets.get("art/backgrounds/farm.png"), 0, 0, getWidth(), getHeight());
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(), 0xCC173A30, 0x99285A42, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);

        float contentH = homeContentHeight();
        if (!homeScrollReady && getHeight() > 0) {
            homeScroll = clamp(contentH - getHeight(), 0, Math.max(0, contentH - getHeight()));
            homeScrollReady = true;
        }

        canvas.save();
        canvas.translate(0, -homeScroll);
        drawMapPath(canvas, contentH);
        drawHomeLogo(canvas, contentH);
        drawLevelNodes(canvas, contentH, now);
        canvas.restore();

        paint.setColor(0x44000000);
        canvas.drawRect(0, 0, getWidth(), dp(34), paint);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dp(15));
        textPaint.setColor(Color.WHITE);
        canvas.drawText("PipeTown", getWidth() * 0.5f, dp(23), textPaint);
        textPaint.setFakeBoldText(false);
    }

    private void drawHomeLogo(Canvas canvas, float contentH) {
        Bitmap logo = assets.get("art/logo/logo.png");
        float width = Math.min(getWidth() * 0.72f, dp(320));
        float height = width * 0.42f;
        float y = contentH - dp(164);
        scratch.set((getWidth() - width) * 0.5f, y, (getWidth() + width) * 0.5f, y + height);
        drawBitmap(canvas, logo, scratch, 255);
    }

    private void drawMapPath(Canvas canvas, float contentH) {
        Path path = new Path();
        for (int i = 0; i < levels.size(); i++) {
            PointF p = levelMapPoint(i + 1, contentH);
            if (i == 0) {
                path.moveTo(p.x, p.y);
            } else {
                PointF prev = levelMapPoint(i, contentH);
                float midY = (prev.y + p.y) * 0.5f;
                path.cubicTo(prev.x, midY, p.x, midY, p.x, p.y);
            }
        }
        strokePaint.setStrokeWidth(dp(18));
        strokePaint.setColor(0x554B311E);
        strokePaint.setPathEffect(null);
        canvas.drawPath(path, strokePaint);
        strokePaint.setStrokeWidth(dp(9));
        strokePaint.setColor(0xFFECCB86);
        strokePaint.setPathEffect(new DashPathEffect(new float[]{dp(18), dp(13)}, -animSeconds * dp(28)));
        canvas.drawPath(path, strokePaint);
        strokePaint.setPathEffect(null);
    }

    private void drawLevelNodes(Canvas canvas, float contentH, long now) {
        Bitmap levelIcon = assets.get("art/icons/level.png");
        Bitmap completeIcon = assets.get("art/icons/compleate_level.png");
        for (Level level : levels) {
            PointF p = levelMapPoint(level.number, contentH);
            boolean unlocked = level.number <= maxUnlocked;
            float pulse = unlocked ? 1f + 0.055f * (float) Math.sin(animSeconds * 3.1f + level.number) : 1f;
            float size = dp(unlocked ? 82 : 66) * pulse;
            scratch.set(p.x - size * 0.5f, p.y - size * 0.5f, p.x + size * 0.5f, p.y + size * 0.5f);
            paint.setColor(unlocked ? 0x55000000 : 0x33000000);
            canvas.drawOval(scratch.left + dp(4), scratch.top + dp(7), scratch.right + dp(4), scratch.bottom + dp(7), paint);
            drawBitmap(canvas, levelIcon, scratch, unlocked ? 255 : 105);

            textPaint.setColor(unlocked ? Color.WHITE : 0xCCEEE7D8);
            textPaint.setTextSize(dp(23));
            textPaint.setFakeBoldText(true);
            canvas.drawText(String.valueOf(level.number), p.x, p.y + dp(8), textPaint);
            textPaint.setFakeBoldText(false);

            if (level.finished) {
                float badge = dp(38);
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
                homeScroll = clamp(homeScroll - dy, 0, maxScroll);
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    movedDuringTouch = true;
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!movedDuringTouch) {
                    performClick();
                    int levelNumber = hitHomeLevel(event.getX(), event.getY() + homeScroll, contentH);
                    if (levelNumber > 0) {
                        startLevel(levelNumber);
                    }
                }
                return true;
            default:
                return true;
        }
    }

    private int hitHomeLevel(float x, float contentY, float contentH) {
        for (Level level : levels) {
            if (level.number > maxUnlocked) {
                continue;
            }
            PointF p = levelMapPoint(level.number, contentH);
            if (distance(x, contentY, p.x, p.y) <= dp(48)) {
                return level.number;
            }
        }
        return -1;
    }

    private void startLevel(int number) {
        activeLevel = levels.get(number - 1);
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
            screen = SCREEN_HOME;
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
        boardTop = top + (availableH - cell * GRID_H) * 0.5f;
    }

    private void drawTopButtons(Canvas canvas) {
        float button = dp(48);
        float gap = dp(10);
        float y = dp(14);
        homeButton.set(dp(12), y, dp(12) + button, y + button);
        resetButton.set(homeButton.right + gap, y, homeButton.right + gap + button, y + button);
        undoButton.set(getWidth() - dp(12) - button * 2 - gap, y, getWidth() - dp(12) - button - gap, y + button);
        hintButton.set(getWidth() - dp(12) - button, y, getWidth() - dp(12), y + button);

        drawIconButton(canvas, homeButton, null, "Map", pressedButton == 1);
        drawIconButton(canvas, resetButton, assets.get("art/icons/reset.png"), null, pressedButton == 2);
        drawIconButton(canvas, undoButton, assets.get("art/icons/revert.png"), null, pressedButton == 3);
        drawIconButton(canvas, hintButton, assets.get("art/icons/hint.png"), null, pressedButton == 4);
    }

    private void drawIconButton(Canvas canvas, RectF rect, Bitmap icon, String fallbackText, boolean pressed) {
        paint.setColor(pressed ? 0xF4FFFFFF : 0xDCFFFFFF);
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
        paint.setColor(0x33000000);
        canvas.drawRoundRect(rect.left, rect.bottom - dp(6), rect.right, rect.bottom, dp(8), dp(8), paint);
        if (icon != null) {
            float inset = dp(7);
            scratch.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            drawBitmap(canvas, icon, scratch, 255);
        } else {
            textPaint.setColor(0xFF385A4A);
            textPaint.setTextSize(dp(14));
            textPaint.setFakeBoldText(true);
            canvas.drawText(fallbackText, rect.centerX(), rect.centerY() + dp(5), textPaint);
            textPaint.setFakeBoldText(false);
        }
    }

    private void drawBoard(Canvas canvas) {
        scratch.set(boardLeft - dp(5), boardTop - dp(5), boardLeft + cell * GRID_W + dp(5), boardTop + cell * GRID_H + dp(5));
        paint.setColor(0xAAFFF1D1);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFFE7C78C);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(0x44FFFFFF);
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
        drawFlowDots(canvas, points, utility.color);
    }

    private void drawFlowDots(Canvas canvas, List<PointF> points, int color) {
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
            paint.setColor(withAlpha(Color.WHITE, (int) (170 * twinkle)));
            canvas.drawCircle(p.x, p.y, Math.max(dp(2), cell * 0.055f), paint);
            paint.setColor(withAlpha(color, 95));
            canvas.drawCircle(p.x, p.y, Math.max(dp(4), cell * 0.085f), paint);
        }
    }

    private void drawBlockers(Canvas canvas) {
        for (Blocker blocker : activeLevel.blockers) {
            RectF rect = cellRect(blocker.x, blocker.y, blocker.w, blocker.h);
            drawBitmap(canvas, assets.get(blocker.asset), rect, 255);
        }
    }

    private void drawHouses(Canvas canvas) {
        for (House house : activeLevel.houses) {
            RectF rect = cellRect(house.x, house.y, house.w, house.h);
            float bob = (float) Math.sin(animSeconds * 2f + house.id) * dp(1.4f);
            rect.offset(0, bob);
            paint.setColor(0x33000000);
            canvas.drawOval(rect.left + dp(4), rect.bottom - dp(8), rect.right - dp(4), rect.bottom + dp(4), paint);
            drawBitmap(canvas, assets.get(house.asset), rect, 255);
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
        drawDockTile(canvas, source.utility, source.connectorX(), source.connectorY(), Direction.opposite(source.openDirection), false, pulse, now);
    }

    private void drawHouseDock(Canvas canvas, Port port, boolean pulse, long now) {
        drawDockTile(canvas, port.utility, port.x, port.y, port.outlet, port.connected, pulse, now);
    }

    private void drawDockTile(Canvas canvas, Utility utility, int gx, int gy, Direction imageRightDirection, boolean connected, boolean pulse, long now) {
        RectF rect = cellRect(gx, gy, 1, 1);
        float centerX = rect.centerX();
        float centerY = rect.centerY();
        float scale = pulse ? 1f + 0.08f * (float) Math.sin(animSeconds * 7f) : 1f;
        float tile = Math.min(cell * 1.18f, dp(76)) * scale;
        scratch.set(centerX - tile * 0.5f, centerY - tile * 0.5f, centerX + tile * 0.5f, centerY + tile * 0.5f);

        paint.setColor(withAlpha(utility.color, pulse ? 120 : 54));
        canvas.drawCircle(centerX, centerY, tile * 0.57f, paint);
        drawRotatedBitmap(canvas, assets.get(utility.baseAsset()), scratch, imageRightDirection.rotation, connected ? 238 : 255);

        float icon = tile * (utility.usesConnector ? 0.54f : 0.58f);
        scratch.set(centerX - icon * 0.5f, centerY - icon * 0.5f, centerX + icon * 0.5f, centerY + icon * 0.5f);
        drawBitmap(canvas, assets.get(utility.iconAsset()), scratch, connected ? 225 : 255);
    }

    private void drawSourceProviders(Canvas canvas) {
        for (Source source : activeLevel.sources) {
            RectF rect = cellRect(source.x, source.y, 2, 2);
            float bob = (float) Math.sin(animSeconds * 2.1f + source.utility.ordinal()) * dp(1.2f);
            rect.offset(0, bob);
            paint.setColor(0x35000000);
            canvas.drawOval(rect.left + dp(7), rect.bottom - dp(11), rect.right - dp(7), rect.bottom + dp(4), paint);
            drawBitmap(canvas, assets.get(source.utility.sourceAsset()), rect, 255);
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
                    beginPipe(source.utility, sourceMouthPoint(source), sourceRouteCell(source));
                    return true;
                }
                StrokeHit networkHit = hitNetwork(downX, downY);
                if (networkHit != null && activeLevel.hasOpenPort(networkHit.utility)) {
                    beginPipe(networkHit.utility, networkHit.point, networkHit.cell);
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
                    screen = SCREEN_HOME;
                    homeScrollReady = false;
                }
                pressedButton = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingPipe = false;
                activeUtility = null;
                activeStartCell = null;
                activeStartPoint = null;
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
        if (undoButton.contains(x, y)) {
            return 3;
        }
        if (hintButton.contains(x, y)) {
            return 4;
        }
        return -1;
    }

    private void runButton(int button) {
        if (button == 1) {
            screen = SCREEN_HOME;
            homeScrollReady = false;
        } else if (button == 2) {
            activeLevel.resetForPlay();
            completedAtMs = 0L;
            hintPlan = null;
            highlightedPortId = -1;
            status("Fresh pipes");
        } else if (button == 3) {
            undoLast();
        } else if (button == 4) {
            showHint();
        }
    }

    private void beginPipe(Utility utility, PointF start, Cell startCell) {
        draggingPipe = true;
        activeUtility = utility;
        activeStartCell = startCell;
        activeStartPoint = start;
        hintPlan = null;
        activePoints.clear();
        activePoints.add(start);
        status(String.format(Locale.US, "%s flowing", utility.title));
    }

    private void addActivePoint(float x, float y) {
        PointF last = activePoints.get(activePoints.size() - 1);
        float clampedX = clamp(x, boardLeft, boardLeft + GRID_W * cell);
        float clampedY = clamp(y, boardTop, boardTop + GRID_H * cell);
        if (distance(last.x, last.y, clampedX, clampedY) >= Math.max(dp(4), cell * 0.08f)) {
            activePoints.add(new PointF(clampedX, clampedY));
        }
    }

    private void finishPipe(float x, float y) {
        addActivePoint(x, y);
        Port port = hitOpenPort(x, y, activeUtility);
        if (port == null) {
            rejectPipe("Needs matching port");
            return;
        }
        Route route = findRoute(activeLevel, activeUtility, activeStartCell, activeStartPoint, port, activeLevel.strokes, -1);
        if (route == null) {
            rejectPipe("Route is blocked");
            return;
        }
        Stroke stroke = new Stroke(activeUtility, port.id, route.points, route.cells);
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
        status(String.format(Locale.US, "%s connected", port.utility.title));
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
        activePoints.clear();
        if (activeLevel.isComplete()) {
            completeLevel();
        }
    }

    private void rejectPipe(String message) {
        PointF last = activePoints.size() > 0 ? activePoints.get(activePoints.size() - 1) : new PointF(getWidth() * 0.5f, getHeight() * 0.5f);
        spawnBurst(last.x, last.y, Color.rgb(211, 47, 47), 10);
        status(message);
        draggingPipe = false;
        activeUtility = null;
        activeStartCell = null;
        activeStartPoint = null;
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
        maxUnlocked = Math.max(maxUnlocked, Math.min(levels.size(), activeLevel.number + 1));
        completedAtMs = SystemClock.uptimeMillis();
        status("Level complete");
        for (int i = 0; i < 34; i++) {
            spawnBurst(random.nextFloat() * getWidth(), boardTop + random.nextFloat() * GRID_H * cell, randomUtilityColor(), 1);
        }
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
            if (activeLevel.isSourceProviderCell(c.x, c.y)) {
                return "Source blocked";
            }
            if (activeLevel.isEndpointCell(c.x, c.y)) {
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
            if (provider.contains(x, y) || distance(x, y, mouth.x, mouth.y) <= cell * 0.74f) {
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
            PointF p = portMouthPoint(port);
            float d = distance(x, y, p.x, p.y);
            if (d < bestDist) {
                bestDist = d;
                best = port;
            }
        }
        return bestDist <= cell * 0.72f ? best : null;
    }

    private StrokeHit hitNetwork(float x, float y) {
        float threshold = Math.max(dp(14), cell * 0.26f);
        StrokeHit best = null;
        float bestDist = Float.MAX_VALUE;
        for (Stroke stroke : activeLevel.strokes) {
            float d = pointToPolylineDistance(x, y, stroke.points);
            if (d <= threshold && d < bestDist) {
                bestDist = d;
                Cell cell = nearestStrokeCell(stroke, x, y);
                if (cell != null) {
                    best = new StrokeHit(stroke.utility, cell, cellCenter(cell.x, cell.y));
                }
            }
        }
        return best;
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

    private void drawRotatedBitmap(Canvas canvas, Bitmap bitmap, RectF dest, float degrees, int alpha) {
        canvas.save();
        canvas.rotate(degrees, dest.centerX(), dest.centerY());
        drawBitmap(canvas, bitmap, dest, alpha);
        canvas.restore();
    }

    private RectF cellRect(int x, int y, int w, int h) {
        return new RectF(boardLeft + x * cell, boardTop + y * cell, boardLeft + (x + w) * cell, boardTop + (y + h) * cell);
    }

    private PointF cellCenter(int x, int y) {
        return new PointF(boardLeft + (x + 0.5f) * cell, boardTop + (y + 0.5f) * cell);
    }

    private PointF sourceMouthPoint(Source source) {
        PointF center = cellCenter(source.connectorX(), source.connectorY());
        return new PointF(center.x + source.openDirection.dx * cell * 0.48f, center.y + source.openDirection.dy * cell * 0.48f);
    }

    private Cell sourceRouteCell(Source source) {
        return new Cell(source.connectorX() + source.openDirection.dx, source.connectorY() + source.openDirection.dy);
    }

    private PointF portMouthPoint(Port port) {
        PointF center = cellCenter(port.x, port.y);
        return new PointF(center.x + port.outlet.dx * cell * 0.48f, center.y + port.outlet.dy * cell * 0.48f);
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
        return Math.max(getHeight() * 1.85f, dp(300) + levels.size() * dp(225));
    }

    private PointF levelMapPoint(int levelNumber, float contentH) {
        float bottom = contentH - dp(238);
        float y = bottom - (levelNumber - 1) * dp(215);
        float x = getWidth() * (0.5f + 0.24f * (float) Math.sin(levelNumber * 1.38f));
        return new PointF(x, y);
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

    private float pointToSegmentDistance(float px, float py, PointF a, PointF b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        if (dx == 0f && dy == 0f) {
            return distance(px, py, a.x, a.y);
        }
        float t = ((px - a.x) * dx + (py - a.y) * dy) / (dx * dx + dy * dy);
        t = clamp(t, 0f, 1f);
        float x = a.x + dx * t;
        float y = a.y + dy * t;
        return distance(px, py, x, y);
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

    private float distance(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }

    private void buildLevels() {
        Level one = new Level(1, "art/backgrounds/warm.png");
        one.sources.add(new Source(Utility.WATER, 0, 2, Direction.RIGHT));
        one.sources.add(new Source(Utility.GAS, 0, 11, Direction.RIGHT));
        one.houses.add(new House(1, 7, 8, 1, 1, "art/houses/house_1x1.png"));
        one.ports.add(new Port(1, 1, Utility.WATER, 7, 7, Direction.UP));
        one.ports.add(new Port(2, 1, Utility.GAS, 6, 8, Direction.LEFT));
        one.blockers.add(new Blocker("art/blockers/stone_1x1.png", 4, 6, 1, 1));
        one.blockers.add(new Blocker("art/blockers/pond_1x1.png", 3, 10, 1, 1));
        levels.add(one);

        Level two = new Level(2, "art/backgrounds/farm.png");
        two.sources.add(new Source(Utility.WATER, 0, 1, Direction.RIGHT));
        two.sources.add(new Source(Utility.HEATING, 8, 12, Direction.LEFT));
        two.sources.add(new Source(Utility.INTERNET, 0, 13, Direction.RIGHT));
        two.houses.add(new House(1, 6, 4, 1, 1, "art/houses/house_1x1.png"));
        two.houses.add(new House(2, 4, 10, 1, 1, "art/houses/house_1x1.png"));
        two.ports.add(new Port(1, 1, Utility.WATER, 6, 3, Direction.UP));
        two.ports.add(new Port(2, 1, Utility.INTERNET, 5, 4, Direction.LEFT));
        two.ports.add(new Port(3, 2, Utility.HEATING, 5, 10, Direction.RIGHT));
        two.blockers.add(new Blocker("art/blockers/tree_1x2.png", 7, 7, 1, 2));
        two.blockers.add(new Blocker("art/blockers/construction_1x1.png", 2, 6, 1, 1));
        levels.add(two);

        Level three = new Level(3, "art/backgrounds/tropical.png");
        three.sources.add(new Source(Utility.WATER, 0, 1, Direction.RIGHT));
        three.sources.add(new Source(Utility.SEWAGE, 8, 1, Direction.LEFT));
        three.sources.add(new Source(Utility.ELECTRIC, 0, 13, Direction.RIGHT));
        three.sources.add(new Source(Utility.GAS, 8, 13, Direction.LEFT));
        three.houses.add(new House(1, 5, 6, 2, 2, "art/houses/house_2x2.png"));
        three.houses.add(new House(2, 3, 11, 1, 1, "art/houses/house_1x1.png"));
        three.ports.add(new Port(1, 1, Utility.WATER, 5, 5, Direction.UP));
        three.ports.add(new Port(2, 1, Utility.SEWAGE, 7, 6, Direction.RIGHT));
        three.ports.add(new Port(3, 1, Utility.ELECTRIC, 4, 7, Direction.LEFT));
        three.ports.add(new Port(4, 1, Utility.GAS, 6, 8, Direction.DOWN));
        three.ports.add(new Port(5, 2, Utility.GAS, 4, 11, Direction.RIGHT));
        three.blockers.add(new Blocker("art/blockers/tree_1x2.png", 4, 2, 1, 2));
        three.blockers.add(new Blocker("art/blockers/construction_1x1.png", 6, 11, 1, 1));
        three.blockers.add(new Blocker("art/blockers/pond_1x1.png", 3, 5, 1, 1));
        levels.add(three);

        Level four = new Level(4, "art/backgrounds/desert.png");
        four.sources.add(new Source(Utility.WATER, 0, 0, Direction.RIGHT));
        four.sources.add(new Source(Utility.HEATING, 8, 0, Direction.LEFT));
        four.sources.add(new Source(Utility.ELECTRIC, 4, 0, Direction.DOWN));
        four.sources.add(new Source(Utility.INTERNET, 0, 14, Direction.RIGHT));
        four.sources.add(new Source(Utility.SEWAGE, 8, 14, Direction.LEFT));
        four.houses.add(new House(1, 4, 5, 2, 2, "art/houses/house_2x2.png"));
        four.houses.add(new House(2, 7, 8, 1, 1, "art/houses/house_1x1.png"));
        four.houses.add(new House(3, 2, 10, 1, 1, "art/houses/house_1x1.png"));
        four.ports.add(new Port(1, 1, Utility.WATER, 4, 4, Direction.UP));
        four.ports.add(new Port(2, 1, Utility.HEATING, 6, 5, Direction.RIGHT));
        four.ports.add(new Port(3, 1, Utility.ELECTRIC, 5, 4, Direction.UP));
        four.ports.add(new Port(4, 3, Utility.INTERNET, 1, 10, Direction.LEFT));
        four.ports.add(new Port(5, 2, Utility.SEWAGE, 8, 8, Direction.RIGHT));
        four.blockers.add(new Blocker("art/blockers/stone_1x1.png", 8, 5, 1, 1));
        four.blockers.add(new Blocker("art/blockers/tree_1x2.png", 3, 12, 1, 2));
        four.blockers.add(new Blocker("art/blockers/pond_1x1.png", 6, 10, 1, 1));
        levels.add(four);

        Level five = new Level(5, "art/backgrounds/farm.png");
        five.sources.add(new Source(Utility.WATER, 0, 0, Direction.RIGHT));
        five.sources.add(new Source(Utility.GAS, 8, 0, Direction.LEFT));
        five.sources.add(new Source(Utility.ELECTRIC, 4, 0, Direction.DOWN));
        five.sources.add(new Source(Utility.HEATING, 0, 14, Direction.RIGHT));
        five.sources.add(new Source(Utility.INTERNET, 4, 14, Direction.UP));
        five.sources.add(new Source(Utility.SEWAGE, 8, 14, Direction.LEFT));
        five.houses.add(new House(1, 3, 5, 4, 4, "art/houses/house 4x4.png"));
        five.houses.add(new House(2, 1, 10, 2, 2, "art/houses/house_2x2.png"));
        five.houses.add(new House(3, 8, 10, 1, 1, "art/houses/house_1x1.png"));
        five.ports.add(new Port(1, 1, Utility.WATER, 3, 4, Direction.UP));
        five.ports.add(new Port(2, 1, Utility.GAS, 7, 5, Direction.RIGHT));
        five.ports.add(new Port(3, 1, Utility.HEATING, 2, 6, Direction.LEFT));
        five.ports.add(new Port(4, 2, Utility.HEATING, 1, 12, Direction.DOWN));
        five.ports.add(new Port(5, 1, Utility.ELECTRIC, 6, 4, Direction.UP));
        five.ports.add(new Port(6, 2, Utility.INTERNET, 3, 11, Direction.RIGHT));
        five.ports.add(new Port(7, 3, Utility.SEWAGE, 8, 11, Direction.DOWN));
        five.blockers.add(new Blocker("art/blockers/tree_1x2.png", 9, 7, 1, 2));
        five.blockers.add(new Blocker("art/blockers/pond_1x1.png", 8, 6, 1, 1));
        five.blockers.add(new Blocker("art/blockers/stone_1x1.png", 1, 5, 1, 1));
        five.blockers.add(new Blocker("art/blockers/construction_1x1.png", 5, 11, 1, 1));
        levels.add(five);

        ensureLevelsSolvable();
    }

    private void ensureLevelsSolvable() {
        for (Level level : levels) {
            ArrayList<Stroke> planned = new ArrayList<>();
            for (Port port : level.ports) {
                Route route = findPlannedRoute(level, port, planned);
                if (route == null) {
                    throw new IllegalStateException("Level " + level.number + " is not solvable at port " + port.id + " (" + port.utility.title + ")");
                }
                planned.add(new Stroke(port.utility, port.id, route.points, route.cells));
            }
        }
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
    }
}
