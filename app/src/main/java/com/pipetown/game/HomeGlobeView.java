package com.pipetown.game;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class HomeGlobeView extends GLSurfaceView {
    interface Listener {
        void onLevelSelected(int levelNumber);

        void onPlanetScrolled();
    }

    private final GlobeRenderer renderer;
    private Listener listener;
    private float downX;
    private float downY;
    private float lastY;
    private boolean moved;
    private boolean selectingLevel;
    private long lastScrollSoundMs;

    public HomeGlobeView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new GlobeRenderer(context.getApplicationContext());
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setMaxUnlocked(int maxUnlocked) {
        renderer.setMaxUnlocked(maxUnlocked);
    }

    void setProgress(int maxUnlocked, int focusLevel) {
        renderer.setMaxUnlocked(maxUnlocked);
        renderer.focusLevel(focusLevel);
    }

    void celebrateLevel(int level) {
        queueEvent(() -> renderer.animateToLevel(level, true));
    }

    void rotateToLevelOne() {
        queueEvent(() -> renderer.animateToLevel(1, false));
    }

    void rotateToLatestUnlocked() {
        queueEvent(renderer::animateToLatestUnlocked);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (selectingLevel) {
                    return true;
                }
                downX = event.getX();
                downY = lastY = event.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selectingLevel) {
                    return true;
                }
                float dy = event.getY() - lastY;
                renderer.drag(dy);
                long now = event.getEventTime();
                if (listener != null && Math.abs(dy) > dp(2) && now - lastScrollSoundMs > 280L) {
                    listener.onPlanetScrolled();
                    lastScrollSoundMs = now;
                }
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    moved = true;
                }
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                if (!moved && listener != null) {
                    int level = renderer.hitLevel(event.getX(), event.getY());
                    if (level > 0) {
                        selectingLevel = true;
                        queueEvent(() -> renderer.playSelectLevel(level));
                        postDelayed(() -> selectingLevel = false, 1200L);
                        listener.onLevelSelected(level);
                    }
                }
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class GlobeRenderer implements Renderer {
        private static final float RADIUS = 2.02f;
        private static final float CENTER_Y = -1.78f;
        private static final float CAMERA_Z = 5.05f;
        private static final float LEVEL_SPACING = 0.44f;
        private static final float LEVEL_BASE_THETA = 0.94f;
        private static final float LEVEL_FOCUS_THETA = 0.58f;
        private static final float DRAG_TO_RADIANS = 0.00055f;
        private static final float DECOR_STEP = 0.075f;
        private static final long LEVEL_LANE_SEED = 0x51A7C0DEL;
        private static final int MAX_LEVELS_AHEAD = 20;
        private static final long FOCUS_ANIMATION_MS = 1120L;
        private static final long FOCUS_END_BUFFER_MS = 180L;
        private static final long CELEBRATION_GLOW_MS = 2800L;
        private static final long SELECT_ANIMATION_MS = 920L;
        private static final int SPHERE_SLICES = 64;
        private static final int SPHERE_STACKS = 42;
        private static final int PATH_SAMPLES = 18;
        private static final DecorSpec[] DECOR = {
                new DecorSpec("art/blockers/tree_1x1.png", 0.15f, 1.00f, 1.04f, true),
                new DecorSpec("art/blockers/tree_1x2.png", 0.18f, 1.00f, 1.08f, true),
                new DecorSpec("art/blockers/tree_1x3.png", 0.21f, 0.96f, 1.16f, true),
                new DecorSpec("art/blockers/tree_1x3.png", 0.21f, 0.96f, 1.16f, true),
                new DecorSpec("art/blockers/stone_1x1.png", 0.13f, 1.02f, 0.96f, true),
                new DecorSpec("art/blockers/stone_1x3.png", 0.18f, 0.98f, 1.12f, true),
                new DecorSpec("art/blockers/stone_2x2.png", 0.21f, 1.06f, 1.04f, true),
                new DecorSpec("art/blockers/stone_2x2.png", 0.21f, 1.06f, 1.04f, true),
                new DecorSpec("art/blockers/construction_1x1.png", 0.15f, 1.02f, 1.02f, true),
                new DecorSpec("art/blockers/construction_1x2.png", 0.18f, 1.00f, 1.10f, true),
                new DecorSpec("art/blockers/construction_1x3.png", 0.21f, 0.98f, 1.16f, true),
                new DecorSpec("art/blockers/construction_1x3.png", 0.21f, 0.98f, 1.16f, true),
                new DecorSpec("art/blockers/pond_1x1.png", 0.15f, 1.08f, 0.92f, false),
                new DecorSpec("art/blockers/pond_2x2.png", 0.22f, 1.10f, 0.94f, false),
                new DecorSpec("art/blockers/pond_2x3.png", 0.25f, 1.12f, 1.02f, false),
                new DecorSpec("art/houses/house_1x1.png", 0.18f, 1.00f, 1.08f, true),
                new DecorSpec("art/houses/house_2x2.png", 0.23f, 1.04f, 1.10f, true),
                new DecorSpec("art/houses/house 4x4.png", 0.30f, 1.08f, 1.12f, true),
                new DecorSpec("art/houses/house_5x5.png", 0.34f, 1.10f, 1.14f, true),
                new DecorSpec("art/sources/water.png", 0.22f, 1.02f, 1.08f, true),
                new DecorSpec("art/sources/electric.png", 0.22f, 1.02f, 1.08f, true),
                new DecorSpec("art/sources/gas.png", 0.22f, 1.02f, 1.08f, true),
                new DecorSpec("art/sources/internet.png", 0.22f, 1.02f, 1.08f, true)
        };

        private final Context context;
        private final AssetManager assets;
        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] viewProjection = new float[16];
        private final float[] model = new float[16];
        private final float[] mvp = new float[16];
        private final float[] temp = new float[16];
        private final float[] clip = new float[4];
        private final float[] point = new float[4];
        private final float[] quad = new float[20];
        private final float[] ribbon = new float[(PATH_SAMPLES + 1) * 2 * 3];
        private final Vec3 globeCenter = new Vec3(0f, CENTER_Y, 0f);
        private final Vec3 upAxis = new Vec3(0f, 1f, 0f);
        private final FloatBuffer quadBuffer = directFloatBuffer(20);
        private final ShortBuffer quadIndices = directShortBuffer(new short[]{0, 1, 2, 0, 2, 3});
        private final FloatBuffer ribbonBuffer = directFloatBuffer((PATH_SAMPLES + 1) * 2 * 3);
        private final FloatBuffer skyBuffer = directFloatBuffer(12);
        private final Map<String, Integer> textureCache = new HashMap<>();
        private final Map<Integer, Integer> levelTextureCache = new HashMap<>();
        private final Map<Integer, Float> levelLaneCache = new HashMap<>();
        private final ArrayList<HitLevel> hitLevels = new ArrayList<>();

        private FloatBuffer sphereBuffer;
        private ShortBuffer sphereIndices;
        private int sphereIndexCount;
        private int sphereTexture;
        private int sphereProgram;
        private int spriteProgram;
        private int colorProgram;
        private int skyProgram;
        private int glowTexture;
        private int width;
        private int height;
        private volatile float scrollRadians;
        private volatile int maxUnlocked = 1;
        private boolean autoAnimating;
        private float animationStartScroll;
        private float animationTargetScroll;
        private long animationStartMs;
        private int celebrationLevel = -1;
        private long celebrationStartMs;
        private int selectedLevel = -1;
        private long selectedStartMs;

        GlobeRenderer(Context context) {
            this.context = context;
            this.assets = context.getAssets();
        }

        void setMaxUnlocked(int maxUnlocked) {
            this.maxUnlocked = Math.max(1, maxUnlocked);
            scrollRadians = clampScroll(scrollRadians);
            animationTargetScroll = clampScroll(animationTargetScroll);
        }

        void focusLevel(int level) {
            scrollRadians = targetScrollForLevel(level);
            autoAnimating = false;
        }

        void animateToLatestUnlocked() {
            animateToLevel(maxUnlocked, false);
        }

        void animateToLevel(int level, boolean celebrate) {
            int targetLevel = Math.max(1, level);
            animationStartScroll = scrollRadians;
            animationTargetScroll = targetScrollForLevel(targetLevel);
            animationStartMs = SystemClock.uptimeMillis();
            celebrationLevel = celebrate ? targetLevel : -1;
            celebrationStartMs = celebrate ? animationStartMs : 0L;
            autoAnimating = true;
        }

        void playSelectLevel(int level) {
            selectedLevel = Math.max(1, level);
            selectedStartMs = SystemClock.uptimeMillis();
        }

        void drag(float dy) {
            autoAnimating = false;
            scrollRadians += dy * DRAG_TO_RADIANS;
            scrollRadians = clampScroll(scrollRadians);
        }

        int hitLevel(float x, float y) {
            synchronized (hitLevels) {
                for (int i = hitLevels.size() - 1; i >= 0; i--) {
                    HitLevel level = hitLevels.get(i);
                    if (level.number <= maxUnlocked && distance(x, y, level.x, level.y) <= level.radius) {
                        return level.number;
                    }
                }
            }
            return -1;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            textureCache.clear();
            levelTextureCache.clear();
            GLES20.glClearColor(0.38f, 0.70f, 0.92f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            sphereProgram = makeProgram(SPHERE_VERTEX, SPHERE_FRAGMENT);
            spriteProgram = makeProgram(SPRITE_VERTEX, SPRITE_FRAGMENT);
            colorProgram = makeProgram(COLOR_VERTEX, COLOR_FRAGMENT);
            skyProgram = makeProgram(SKY_VERTEX, SKY_FRAGMENT);
            buildSphere();
            sphereTexture = loadSphereTexture();
            preloadDecorTextures();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            GLES20.glViewport(0, 0, this.width, this.height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            float scroll = currentScroll();
            float selectionT = selectionProgress();
            updateCamera(scroll, selectionT);
            drawSkyGradient();
            drawSphere(scroll);
            ArrayList<HitLevel> frameHits = new ArrayList<>();
            drawPaths(scroll);
            drawDecor(scroll);
            drawLevels(scroll, frameHits, selectionT);
            synchronized (hitLevels) {
                hitLevels.clear();
                hitLevels.addAll(frameHits);
            }
        }

        private void updateCamera(float scroll, float selectionT) {
            float aspect = width / (float) height;
            float eased = selectionT * selectionT * (3f - 2f * selectionT);
            Matrix.perspectiveM(projection, 0, lerp(42f, 27f, eased), aspect, 0.1f, 30f);
            float eyeX = 0f;
            float eyeY = 0f;
            float eyeZ = CAMERA_Z;
            float targetX = 0f;
            float targetY = -1.15f;
            float targetZ = 0f;
            if (selectedLevel > 0 && selectionT > 0f) {
                SurfacePoint selected = surfacePoint(selectedLevel, scroll);
                float focus = Math.min(1f, eased * 1.18f);
                eyeX = lerp(0f, selected.world.x * 0.52f, focus);
                eyeY = lerp(0f, selected.world.y + 0.28f, focus);
                eyeZ = lerp(CAMERA_Z, CAMERA_Z - 1.55f, focus);
                targetX = lerp(0f, selected.world.x, focus);
                targetY = lerp(-1.15f, selected.world.y, focus);
                targetZ = lerp(0f, selected.world.z, focus);
            }
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0f, 1f, 0f);
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0);
        }

        private void drawSkyGradient() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(skyProgram);
            int aPosition = GLES20.glGetAttribLocation(skyProgram, "aPosition");
            GLES20.glEnableVertexAttribArray(aPosition);
            skyBuffer.clear();
            skyBuffer.put(new float[]{-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f});
            skyBuffer.position(0);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, skyBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private float currentScroll() {
            if (!autoAnimating) {
                return scrollRadians;
            }
            long elapsed = SystemClock.uptimeMillis() - animationStartMs;
            if (elapsed >= FOCUS_ANIMATION_MS + FOCUS_END_BUFFER_MS) {
                scrollRadians = animationTargetScroll;
                autoAnimating = false;
                return scrollRadians;
            }
            if (elapsed >= FOCUS_ANIMATION_MS) {
                scrollRadians = animationTargetScroll;
                return scrollRadians;
            }
            float t = clamp(elapsed / (float) FOCUS_ANIMATION_MS, 0f, 1f);
            float eased = t * t * t * (t * (t * 6f - 15f) + 10f);
            scrollRadians = lerp(animationStartScroll, animationTargetScroll, eased);
            return scrollRadians;
        }

        private void drawSphere(float scroll) {
            GLES20.glUseProgram(sphereProgram);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, 0f, CENTER_Y, 0f);
            Matrix.rotateM(model, 0, (float) Math.toDegrees(scroll), 1f, 0f, 0f);
            Matrix.scaleM(model, 0, RADIUS, RADIUS, RADIUS);
            Matrix.multiplyMM(temp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);
            if (sphereTexture == 0) {
                sphereTexture = loadSphereTexture();
            }

            int aPosition = GLES20.glGetAttribLocation(sphereProgram, "aPosition");
            int aTex = GLES20.glGetAttribLocation(sphereProgram, "aTexCoord");
            int uMvp = GLES20.glGetUniformLocation(sphereProgram, "uMvp");
            int uModel = GLES20.glGetUniformLocation(sphereProgram, "uModel");
            int uTexture = GLES20.glGetUniformLocation(sphereProgram, "uTexture");
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sphereTexture);
            GLES20.glUniform1i(uTexture, 0);
            sphereBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, sphereBuffer);
            sphereBuffer.position(3);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, sphereBuffer);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount, GLES20.GL_UNSIGNED_SHORT, sphereIndices);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTex);
        }

        private void drawPaths(float scroll) {
            int anchor = anchorLevel(scroll);
            int first = Math.max(1, anchor - 2);
            int last = Math.min(anchor + 8, maxScrollableLevel());
            float celebrationT = celebrationProgress();
            for (int level = first; level < last; level++) {
                SurfacePoint a = surfacePoint(level, scroll);
                SurfacePoint b = surfacePoint(level + 1, scroll);
                if (!a.visible && !b.visible) {
                    continue;
                }
                boolean unlockedSegment = level + 1 <= maxUnlocked;
                int shadow = 0x70442C18;
                int bright = 0xFFE8C573;
                int lockedShadow = 0x44302517;
                int lockedBright = 0x8A8B8067;
                boolean revealing = celebrationLevel == level + 1 && celebrationT < 1f;
                drawRibbon(level, level + 1, scroll, 0.034f, revealing ? lockedShadow : (unlockedSegment ? shadow : lockedShadow), 1f, 0.025f);
                drawRibbon(level, level + 1, scroll, 0.018f, revealing ? lockedBright : (unlockedSegment ? bright : lockedBright), 1f, 0.038f);
                if (revealing) {
                    drawRibbon(level, level + 1, scroll, 0.040f, 0x9CFFF3A6, celebrationT, 0.050f);
                    drawRibbon(level, level + 1, scroll, 0.020f, bright, celebrationT, 0.062f);
                }
            }
        }

        private void drawLevels(float scroll, ArrayList<HitLevel> frameHits, float selectionT) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            int anchor = anchorLevel(scroll);
            int first = Math.max(1, anchor - 3);
            int last = Math.min(anchor + 9, maxScrollableLevel());
            for (int level = last; level >= first; level--) {
                SurfacePoint p = surfacePoint(level, scroll);
                if (!p.visible) {
                    continue;
                }
                int texture = levelTexture(level);
                float scale = 0.72f + 0.35f * p.normal.z;
                float size = 0.24f * scale;
                float alpha = level <= maxUnlocked ? 1f : 0.50f;
                float selectT = level == selectedLevel ? selectionT : 0f;
                if (selectT > 0f && selectT < 1f) {
                    size *= 1f + 0.05f * selectT;
                    alpha = 1f;
                }
                float glow = celebrationGlow(level);
                if (selectT > 0f && selectT < 1f) {
                    float glowSize = size * (2.40f + selectT * 0.40f);
                    drawSurfaceSprite(glowTexture(), p.normal, glowSize, glowSize, 0.052f, 0f, 0f, new float[]{1f, 1f, 1f, 0.34f * (1f - selectT * 0.25f)});
                } else if (glow > 0f) {
                    float pulse = 1f + 0.08f * (float) Math.sin(SystemClock.uptimeMillis() * 0.0065f);
                    drawSurfaceSprite(glowTexture(), p.normal, size * 2.55f * pulse, size * 2.55f * pulse, 0.045f, 0f, 0f, new float[]{1f, 1f, 1f, glow});
                }
                drawSurfaceSprite(texture, p.normal, size, size, 0.055f, 0f, 0f, new float[]{1f, 1f, 1f, alpha});
                float[] screen = project(p.world.x, p.world.y, p.world.z);
                if (screen != null) {
                    frameHits.add(new HitLevel(level, screen[0], screen[1], Math.max(82f, width * 0.085f * scale)));
                }
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private void drawDecor(float scroll) {
            int base = Math.max(0, (int) ((scroll - 2.95f) / DECOR_STEP));
            for (int i = base; i < base + 176; i++) {
                Random seeded = new Random(91_731L + i * 13_337L);
                float theta = -0.35f + i * DECOR_STEP;
                float lon = -0.72f + seeded.nextFloat() * 1.44f;
                Vec3 normal = mapNormal(theta, lon, scroll);
                if (normal.z < -0.28f) {
                    continue;
                }
                if (nearVisibleLevelOrPath(theta, lon, scroll)) {
                    continue;
                }
                DecorSpec spec = DECOR[Math.abs(i) % DECOR.length];
                int texture = loadTexture(spec.asset);
                float scale = decorVisualSize(spec) * (0.90f + seeded.nextFloat() * 0.20f);
                if (spec.standing) {
                    drawStandingSprite(texture, normal, scale, scale, 0.016f, 0f, new float[]{1f, 1f, 1f, 0.92f});
                } else {
                    drawSurfaceSprite(texture, normal, scale, scale, 0.026f, 0f, 0f, new float[]{1f, 1f, 1f, 0.90f});
                }
            }
        }

        private boolean nearVisibleLevelOrPath(float theta, float lon, float scroll) {
            int anchor = anchorLevel(scroll);
            for (int level = Math.max(1, anchor - 3); level <= Math.min(anchor + 8, maxScrollableLevel()); level++) {
                float levelTheta = levelTheta(level);
                float levelLon = levelLon(level);
                if (Math.abs(theta - levelTheta) < 0.31f && Math.abs(lon - levelLon) < 0.34f) {
                    return true;
                }
            }
            for (int level = Math.max(1, anchor - 4); level < Math.min(anchor + 9, maxScrollableLevel()); level++) {
                for (int i = 0; i <= 8; i++) {
                    float t = i / 8f;
                    float pathTheta = lerp(levelTheta(level), levelTheta(level + 1), t);
                    float pathLon = pathLon(level, level + 1, t);
                    if (Math.abs(theta - pathTheta) < 0.14f && Math.abs(lon - pathLon) < 0.16f) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void drawRibbon(int from, int to, float scroll, float width, int color, float progress, float altitude) {
            progress = clamp(progress, 0f, 1f);
            if (progress <= 0.01f) {
                return;
            }
            int index = 0;
            int samples = Math.max(1, (int) Math.ceil(PATH_SAMPLES * progress));
            for (int i = 0; i <= samples; i++) {
                float t = progress * i / (float) samples;
                float theta = lerp(levelTheta(from), levelTheta(to), t);
                float lon = pathLon(from, to, t);
                Vec3 center = pointAt(theta, lon, RADIUS + altitude, scroll);
                Vec3 normal = mapNormal(theta, lon, scroll);
                Vec3 before = pointAt(theta - 0.01f, pathLon(from, to, clamp(t - 0.025f, 0f, 1f)), RADIUS + altitude, scroll);
                Vec3 after = pointAt(theta + 0.01f, pathLon(from, to, clamp(t + 0.025f, 0f, 1f)), RADIUS + altitude, scroll);
                Vec3 tangent = after.minus(before).normalize();
                Vec3 side = normal.cross(tangent).normalize();
                Vec3 left = center.plus(side.times(width));
                Vec3 right = center.minus(side.times(width));
                ribbon[index++] = left.x;
                ribbon[index++] = left.y;
                ribbon[index++] = left.z;
                ribbon[index++] = right.x;
                ribbon[index++] = right.y;
                ribbon[index++] = right.z;
            }

            GLES20.glUseProgram(colorProgram);
            int aPosition = GLES20.glGetAttribLocation(colorProgram, "aPosition");
            int uMvp = GLES20.glGetUniformLocation(colorProgram, "uMvp");
            int uColor = GLES20.glGetUniformLocation(colorProgram, "uColor");
            GLES20.glUniformMatrix4fv(uMvp, 1, false, viewProjection, 0);
            setColorUniform(uColor, color);
            ribbonBuffer.clear();
            ribbonBuffer.put(ribbon, 0, index);
            ribbonBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, ribbonBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, index / 3);
            GLES20.glDisableVertexAttribArray(aPosition);
        }

        private float pathLon(int from, int to, float t) {
            float base = lerp(levelLon(from), levelLon(to), t);
            float seed = (from * 37.11f + to * 9.73f);
            return base
                    + (float) Math.sin(t * Math.PI * 2f + seed) * 0.036f
                    + (float) Math.sin(t * Math.PI * 5f + seed * 0.37f) * 0.012f;
        }

        private void drawStandingSprite(int texture, Vec3 normal, float width, float height, float altitude, float rightShift, float[] tint) {
            if (texture == 0 || normal.z < -0.28f) {
                return;
            }
            Vec3 right = upAxis.cross(normal).normalize();
            if (right.length() < 0.001f) {
                right = new Vec3(1f, 0f, 0f);
            }
            Vec3 surfaceUp = normal.cross(right).normalize();
            Vec3 standUp = surfaceUp.times(0.70f).plus(normal.times(0.70f)).normalize();
            Vec3 base = globeCenter.plus(normal.times(RADIUS + altitude)).plus(right.times(rightShift));
            Vec3 a = base.minus(right.times(width * 0.5f));
            Vec3 b = base.plus(right.times(width * 0.5f));
            Vec3 c = b.plus(standUp.times(height));
            Vec3 d = a.plus(standUp.times(height));
            putQuad(a, b, c, d);
            drawPreparedSprite(texture, tint);
        }

        private void drawSurfaceSprite(int texture, Vec3 normal, float width, float height, float altitude, float rightShift, float upShift, float[] tint) {
            if (texture == 0 || normal.z < -0.28f) {
                return;
            }
            Vec3 right = upAxis.cross(normal).normalize();
            if (right.length() < 0.001f) {
                right = new Vec3(1f, 0f, 0f);
            }
            Vec3 up = normal.cross(right).normalize();
            Vec3 center = globeCenter.plus(normal.times(RADIUS + altitude)).plus(right.times(rightShift)).plus(up.times(upShift));
            Vec3 a = center.minus(right.times(width * 0.5f)).minus(up.times(height * 0.5f));
            Vec3 b = center.plus(right.times(width * 0.5f)).minus(up.times(height * 0.5f));
            Vec3 c = center.plus(right.times(width * 0.5f)).plus(up.times(height * 0.5f));
            Vec3 d = center.minus(right.times(width * 0.5f)).plus(up.times(height * 0.5f));
            putQuad(a, b, c, d);
            drawPreparedSprite(texture, tint);
        }

        private void drawPreparedSprite(int texture, float[] tint) {
            GLES20.glUseProgram(spriteProgram);
            int aPosition = GLES20.glGetAttribLocation(spriteProgram, "aPosition");
            int aTex = GLES20.glGetAttribLocation(spriteProgram, "aTexCoord");
            int uMvp = GLES20.glGetUniformLocation(spriteProgram, "uMvp");
            int uTexture = GLES20.glGetUniformLocation(spriteProgram, "uTexture");
            int uTint = GLES20.glGetUniformLocation(spriteProgram, "uTint");
            GLES20.glUniformMatrix4fv(uMvp, 1, false, viewProjection, 0);
            GLES20.glUniform4fv(uTint, 1, tint, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(uTexture, 0);
            quadBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, quadBuffer);
            quadBuffer.position(3);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, quadBuffer);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, quadIndices);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTex);
        }

        private void putQuad(Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
            putVertex(0, a, 0f, 1f);
            putVertex(5, b, 1f, 1f);
            putVertex(10, c, 1f, 0f);
            putVertex(15, d, 0f, 0f);
            quadBuffer.clear();
            quadBuffer.put(quad);
            quadBuffer.position(0);
            quadIndices.position(0);
        }

        private void putVertex(int index, Vec3 point, float u, float v) {
            quad[index] = point.x;
            quad[index + 1] = point.y;
            quad[index + 2] = point.z;
            quad[index + 3] = u;
            quad[index + 4] = v;
        }

        private SurfacePoint surfacePoint(int level, float scroll) {
            float theta = levelTheta(level);
            float lon = levelLon(level);
            Vec3 normal = mapNormal(theta, lon, scroll);
            Vec3 world = globeCenter.plus(normal.times(RADIUS + 0.09f));
            boolean visible = normal.z > 0.18f;
            return new SurfacePoint(normal, world, visible);
        }

        private Vec3 mapNormal(float theta, float lon, float scroll) {
            return rotateX(normalAt(theta, lon), scroll);
        }

        private Vec3 normalAt(float theta, float lon) {
            float cosTheta = (float) Math.cos(theta);
            return new Vec3(
                    (float) Math.sin(lon) * cosTheta,
                    (float) Math.sin(theta),
                    (float) Math.cos(lon) * cosTheta
            ).normalize();
        }

        private Vec3 rotateX(Vec3 v, float radians) {
            float c = (float) Math.cos(radians);
            float s = (float) Math.sin(radians);
            return new Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c).normalize();
        }

        private Vec3 pointAt(float theta, float lon, float radius, float scroll) {
            return globeCenter.plus(mapNormal(theta, lon, scroll).times(radius));
        }

        private float levelTheta(int level) {
            return LEVEL_BASE_THETA + (level - 1) * LEVEL_SPACING;
        }

        private float levelLon(int level) {
            Float cached = levelLaneCache.get(level);
            if (cached != null) {
                return cached;
            }
            int start = level - 1;
            while (start > 1 && !levelLaneCache.containsKey(start)) {
                start--;
            }
            float lane = levelLaneCache.containsKey(start) ? levelLaneCache.get(start) : 0f;
            for (int i = Math.max(1, start + 1); i <= level; i++) {
                Random seeded = new Random(LEVEL_LANE_SEED + i * 48_271L);
                float proposed = (seeded.nextFloat() * 2f - 1f) * 0.24f;
                float step = clamp(proposed - lane, -0.16f, 0.16f);
                lane = clamp(lane * 0.42f + step, -0.26f, 0.26f);
                levelLaneCache.put(i, lane);
            }
            return lane;
        }

        private int anchorLevel(float scroll) {
            return Math.max(1, (int) Math.floor((scroll - LEVEL_BASE_THETA + LEVEL_FOCUS_THETA + 0.18f) / LEVEL_SPACING) + 1);
        }

        private float targetScrollForLevel(int level) {
            return clampScroll(rawTargetScrollForLevel(level));
        }

        private float rawTargetScrollForLevel(int level) {
            return Math.max(0f, levelTheta(Math.max(1, level)) - LEVEL_FOCUS_THETA);
        }

        private float clampScroll(float scroll) {
            return clamp(scroll, 0f, rawTargetScrollForLevel(maxScrollableLevel()));
        }

        private int maxScrollableLevel() {
            return Math.max(1, maxUnlocked + MAX_LEVELS_AHEAD);
        }

        private float decorVisualSize(DecorSpec spec) {
            int units = largestAssetUnit(spec.asset, 0);
            if (units <= 0) {
                return spec.size;
            }
            return 0.115f + units * 0.052f;
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

        private float celebrationProgress() {
            if (celebrationLevel <= 1 || celebrationStartMs <= 0L) {
                return 1f;
            }
            return clamp((SystemClock.uptimeMillis() - celebrationStartMs) / (float) FOCUS_ANIMATION_MS, 0f, 1f);
        }

        private float celebrationGlow(int level) {
            if (level != celebrationLevel || celebrationStartMs <= 0L) {
                return 0f;
            }
            float age = (SystemClock.uptimeMillis() - celebrationStartMs) / (float) CELEBRATION_GLOW_MS;
            if (age < 0f || age > 1f) {
                return 0f;
            }
            return (float) Math.sin(age * Math.PI) * 0.92f;
        }

        private float selectionProgress() {
            if (selectedLevel <= 0 || selectedStartMs <= 0L) {
                return 0f;
            }
            float progress = (SystemClock.uptimeMillis() - selectedStartMs) / (float) SELECT_ANIMATION_MS;
            if (progress >= 1f) {
                selectedLevel = -1;
                selectedStartMs = 0L;
                return 0f;
            }
            return clamp(progress, 0f, 1f);
        }

        private float[] project(float x, float y, float z) {
            point[0] = x;
            point[1] = y;
            point[2] = z;
            point[3] = 1f;
            Matrix.multiplyMV(clip, 0, viewProjection, 0, point, 0);
            if (clip[3] <= 0.0001f) {
                return null;
            }
            float ndcX = clip[0] / clip[3];
            float ndcY = clip[1] / clip[3];
            if (ndcX < -1.2f || ndcX > 1.2f || ndcY < -1.2f || ndcY > 1.2f) {
                return null;
            }
            return new float[]{(ndcX * 0.5f + 0.5f) * width, (0.5f - ndcY * 0.5f) * height};
        }

        private void buildSphere() {
            int vertexCount = (SPHERE_STACKS + 1) * (SPHERE_SLICES + 1);
            float[] vertices = new float[vertexCount * 5];
            int vi = 0;
            for (int stack = 0; stack <= SPHERE_STACKS; stack++) {
                float v = stack / (float) SPHERE_STACKS;
                float theta = (float) (-Math.PI * 0.5 + Math.PI * v);
                float cosTheta = (float) Math.cos(theta);
                float sinTheta = (float) Math.sin(theta);
                for (int slice = 0; slice <= SPHERE_SLICES; slice++) {
                    float u = slice / (float) SPHERE_SLICES;
                    float lon = (float) (-Math.PI + Math.PI * 2.0 * u);
                    vertices[vi++] = (float) Math.sin(lon) * cosTheta;
                    vertices[vi++] = sinTheta;
                    vertices[vi++] = (float) Math.cos(lon) * cosTheta;
                    vertices[vi++] = u;
                    vertices[vi++] = 1f - v;
                }
            }
            short[] indices = new short[SPHERE_STACKS * SPHERE_SLICES * 6];
            int ii = 0;
            int row = SPHERE_SLICES + 1;
            for (int stack = 0; stack < SPHERE_STACKS; stack++) {
                for (int slice = 0; slice < SPHERE_SLICES; slice++) {
                    short a = (short) (stack * row + slice);
                    short b = (short) ((stack + 1) * row + slice);
                    short c = (short) ((stack + 1) * row + slice + 1);
                    short d = (short) (stack * row + slice + 1);
                    indices[ii++] = a;
                    indices[ii++] = b;
                    indices[ii++] = c;
                    indices[ii++] = a;
                    indices[ii++] = c;
                    indices[ii++] = d;
                }
            }
            sphereBuffer = directFloatBuffer(vertices);
            sphereIndices = directShortBuffer(indices);
            sphereIndexCount = indices.length;
        }

        private void preloadDecorTextures() {
            loadTexture("art/icons/level.png");
            for (DecorSpec spec : DECOR) {
                loadTexture(spec.asset);
            }
        }

        private int levelTexture(int level) {
            Integer cached = levelTextureCache.get(level);
            if (cached != null) {
                return cached;
            }
            Bitmap base = bitmapFromAssets("art/icons/level.png");
            if (base == null) {
                return 0;
            }
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            RectF dest = new RectF(0, 0, 256, 256);
            canvas.drawBitmap(base, null, dest, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            p.setTextSize(level >= 100 ? 70f : level >= 10 ? 82f : 92f);
            String text = String.format(Locale.US, "%d", level);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setColor(Color.BLACK);
            canvas.drawText(text, 128f, 148f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawText(text, 128f, 148f, p);
            int texture = textureFromBitmap(bitmap, false);
            bitmap.recycle();
            levelTextureCache.put(level, texture);
            return texture;
        }

        private int loadSphereTexture() {
            Integer cached = textureCache.get("__sphere_planet");
            if (cached != null) {
                return cached;
            }
            Bitmap tile = bitmapFromAssets("art/planet/farm.png");
            if (tile == null) {
                return loadTexture("art/backgrounds/farm.png");
            }
            int texture = textureFromBitmap(tile, false);
            tile.recycle();
            textureCache.put("__sphere_planet", texture);
            return texture;
        }

        private int loadTexture(String path) {
            Integer cached = textureCache.get(path);
            if (cached != null) {
                return cached;
            }
            Bitmap bitmap = bitmapFromAssets(path);
            if (bitmap == null) {
                textureCache.put(path, 0);
                return 0;
            }
            int texture = textureFromBitmap(bitmap, false);
            bitmap.recycle();
            textureCache.put(path, texture);
            return texture;
        }

        private int glowTexture() {
            if (glowTexture != 0) {
                return glowTexture;
            }
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new RadialGradient(128f, 128f, 122f,
                    new int[]{0xF8FFF7A8, 0xB6FFD75A, 0x32FFB13B, 0x00FFB13B},
                    new float[]{0f, 0.32f, 0.70f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(128f, 128f, 122f, p);
            p.setShader(null);
            glowTexture = textureFromBitmap(bitmap, false);
            bitmap.recycle();
            return glowTexture;
        }

        private Bitmap bitmapFromAssets(String path) {
            try (InputStream stream = assets.open(path)) {
                return BitmapFactory.decodeStream(stream);
            } catch (IOException ignored) {
                return null;
            }
        }

        private int textureFromBitmap(Bitmap bitmap, boolean repeat) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            return texture;
        }

        private int makeProgram(String vertex, String fragment) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            }
            return program;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private void setColorUniform(int location, int color) {
            float a = ((color >> 24) & 0xFF) / 255f;
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            GLES20.glUniform4f(location, r, g, b, a);
        }
    }

    private static FloatBuffer directFloatBuffer(int count) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());
        return buffer.asFloatBuffer();
    }

    private static FloatBuffer directFloatBuffer(float[] values) {
        FloatBuffer buffer = directFloatBuffer(values.length);
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static ShortBuffer directShortBuffer(short[] values) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 2).order(ByteOrder.nativeOrder());
        ShortBuffer shortBuffer = buffer.asShortBuffer();
        shortBuffer.put(values);
        shortBuffer.position(0);
        return shortBuffer;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int lerpColor(int from, int to, float t) {
        t = clamp(t, 0f, 1f);
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int g = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float distance(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static final class SurfacePoint {
        final Vec3 normal;
        final Vec3 world;
        final boolean visible;

        SurfacePoint(Vec3 normal, Vec3 world, boolean visible) {
            this.normal = normal;
            this.world = world;
            this.visible = visible;
        }
    }

    private static final class HitLevel {
        final int number;
        final float x;
        final float y;
        final float radius;

        HitLevel(int number, float x, float y, float radius) {
            this.number = number;
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    private static final class DecorSpec {
        final String asset;
        final float size;
        final float widthBias;
        final float heightBias;
        final boolean standing;

        DecorSpec(String asset, float size, float widthBias, float heightBias, boolean standing) {
            this.asset = asset;
            this.size = size;
            this.widthBias = widthBias;
            this.heightBias = heightBias;
            this.standing = standing;
        }
    }

    private static final class Vec3 {
        final float x;
        final float y;
        final float z;

        Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vec3 plus(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        Vec3 minus(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        Vec3 times(float scalar) {
            return new Vec3(x * scalar, y * scalar, z * scalar);
        }

        Vec3 cross(Vec3 other) {
            return new Vec3(
                    y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x
            );
        }

        float length() {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        Vec3 normalize() {
            float length = length();
            if (length < 0.0001f) {
                return new Vec3(0f, 0f, 0f);
            }
            return new Vec3(x / length, y / length, z / length);
        }
    }

    private static final String SPHERE_VERTEX =
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uModel;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec3 vObjectPos;\n" +
            "varying vec3 vObjectNormal;\n" +
            "varying float vLight;\n" +
            "void main() {\n" +
            "  vec3 normal = normalize((uModel * vec4(aPosition, 0.0)).xyz);\n" +
            "  vec3 light = normalize(vec3(-0.28, 0.66, 0.70));\n" +
            "  float diffuse = clamp(dot(normal, light) * 0.60 + 0.56, 0.30, 1.12);\n" +
            "  float rim = smoothstep(0.05, 0.72, normal.z);\n" +
            "  vLight = diffuse * (0.50 + 0.50 * rim);\n" +
            "  vTexCoord = aTexCoord;\n" +
            "  vObjectPos = aPosition;\n" +
            "  vObjectNormal = normalize(aPosition);\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String SPHERE_FRAGMENT =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec3 vObjectPos;\n" +
            "varying vec3 vObjectNormal;\n" +
            "varying float vLight;\n" +
            "void main() {\n" +
            "  vec3 weights = abs(vObjectNormal);\n" +
            "  weights = weights * weights;\n" +
            "  weights = weights / max(0.001, weights.x + weights.y + weights.z);\n" +
            "  vec2 uvX = vObjectPos.yz * 2.42 + vec2(0.17, 0.53);\n" +
            "  vec2 uvY = vObjectPos.xz * 2.42 + vec2(0.61, 0.29);\n" +
            "  vec2 uvZ = vObjectPos.xy * 2.42 + vec2(0.37, 0.73);\n" +
            "  vec4 color = texture2D(uTexture, fract(uvX)) * weights.x\n" +
            "      + texture2D(uTexture, fract(uvY)) * weights.y\n" +
            "      + texture2D(uTexture, fract(uvZ)) * weights.z;\n" +
            "  gl_FragColor = vec4(color.rgb * vLight, 1.0);\n" +
            "}\n";

    private static final String SPRITE_VERTEX =
            "attribute vec3 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMvp;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  vTexCoord = aTexCoord;\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String SPRITE_FRAGMENT =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec4 uTint;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  vec4 color = texture2D(uTexture, vTexCoord) * uTint;\n" +
            "  if (color.a < 0.02) discard;\n" +
            "  gl_FragColor = color;\n" +
            "}\n";

    private static final String COLOR_VERTEX =
            "attribute vec3 aPosition;\n" +
            "uniform mat4 uMvp;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String COLOR_FRAGMENT =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main() {\n" +
            "  gl_FragColor = uColor;\n" +
            "}\n";

    private static final String SKY_VERTEX =
            "attribute vec3 aPosition;\n" +
            "varying float vY;\n" +
            "void main() {\n" +
            "  vY = aPosition.y * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String SKY_FRAGMENT =
            "precision mediump float;\n" +
            "varying float vY;\n" +
            "void main() {\n" +
            "  vec3 lower = vec3(0.69, 0.88, 0.98);\n" +
            "  vec3 horizon = vec3(0.98, 0.995, 1.0);\n" +
            "  vec3 upper = vec3(0.28, 0.65, 0.90);\n" +
            "  float toHorizon = smoothstep(0.00, 0.46, vY);\n" +
            "  float toUpper = smoothstep(0.50, 1.00, vY);\n" +
            "  vec3 color = mix(lower, horizon, toHorizon);\n" +
            "  color = mix(color, upper, toUpper);\n" +
            "  float glow = 0.035 * (1.0 - abs(vY - 0.48) * 2.0);\n" +
            "  gl_FragColor = vec4(color + glow, 1.0);\n" +
            "}\n";
}
