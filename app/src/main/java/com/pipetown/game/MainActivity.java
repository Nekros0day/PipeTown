package com.pipetown.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final long HEART_REGEN_MS = 30L * 60L * 1000L;
    private static final int MAX_HEARTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hudTicker = new Runnable() {
        @Override
        public void run() {
            if (progress != null) {
                progress.regenerateHearts();
                updateHud();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private FrameLayout homeLayer;
    private HomeGlobeView homeGlobeView;
    private PipeTownView gameView;
    private TextView hudText;
    private TextView soundButton;
    private TextView firstLevelButton;
    private TextView latestLevelButton;
    private View whiteTransition;
    private ProgressStore progress;
    private AudioController audio;
    private int pendingCelebrationLevel = -1;
    private boolean levelTransitioning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF83C7EC));
        progress = new ProgressStore(this);
        progress.regenerateHearts();
        audio = new AudioController(this);
        audio.setMuted(progress.soundMuted());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF83C7EC);
        homeLayer = new FrameLayout(this);
        homeLayer.setBackgroundColor(0xFF83C7EC);
        homeGlobeView = new HomeGlobeView(this);
        homeGlobeView.setProgress(progress.maxUnlocked(), progress.focusLevel());
        homeLayer.addView(homeGlobeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap logoBitmap = loadAssetBitmap("art/logo/logo.png");
        if (logoBitmap != null) {
            logo.setImageBitmap(logoBitmap);
        }
        logo.setElevation(dp(8));
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(dp(218), dp(96));
        logoParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        logoParams.topMargin = dp(76);
        homeLayer.addView(logo, logoParams);

        hudText = new TextView(this);
        hudText.setTextColor(0xFF5C371F);
        hudText.setTextSize(14f);
        hudText.setTypeface(Typeface.DEFAULT_BOLD);
        hudText.setGravity(Gravity.CENTER_VERTICAL);
        hudText.setPadding(dp(64), 0, dp(15), dp(2));
        hudText.setShadowLayer(dp(1), 0, dp(1), 0x66FFFFFF);
        hudText.setBackground(bitmapBackground("art/icons/hearts.png"));
        hudText.setElevation(dp(10));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(dp(224), dp(60));
        hudParams.gravity = Gravity.TOP | Gravity.START;
        hudParams.leftMargin = dp(8);
        hudParams.topMargin = dp(10);
        homeLayer.addView(hudText, hudParams);

        soundButton = new TextView(this);
        soundButton.setGravity(Gravity.CENTER);
        soundButton.setText("");
        soundButton.setBackground(bitmapBackground("art/icons/sound_on.png"));
        soundButton.setElevation(dp(10));
        soundButton.setOnClickListener(v -> {
            progress.setSoundMuted(!progress.soundMuted());
            audio.setMuted(progress.soundMuted());
            updateHud();
        });
        FrameLayout.LayoutParams soundParams = new FrameLayout.LayoutParams(dp(54), dp(54));
        soundParams.gravity = Gravity.TOP | Gravity.END;
        soundParams.rightMargin = dp(14);
        soundParams.topMargin = dp(12);
        homeLayer.addView(soundButton, soundParams);

        firstLevelButton = homeNavButton("art/icons/go_level_1.png");
        firstLevelButton.setOnClickListener(v -> {
            audio.play("scroll_planet");
            homeGlobeView.rotateToLevelOne();
        });
        FrameLayout.LayoutParams firstParams = new FrameLayout.LayoutParams(dp(72), dp(72));
        firstParams.gravity = Gravity.BOTTOM | Gravity.START;
        firstParams.leftMargin = dp(22);
        firstParams.bottomMargin = dp(72);
        homeLayer.addView(firstLevelButton, firstParams);

        latestLevelButton = homeNavButton("art/icons/go_level_unlocked.png");
        latestLevelButton.setOnClickListener(v -> {
            audio.play("scroll_planet");
            homeGlobeView.rotateToLatestUnlocked();
        });
        FrameLayout.LayoutParams latestParams = new FrameLayout.LayoutParams(dp(72), dp(72));
        latestParams.gravity = Gravity.BOTTOM | Gravity.END;
        latestParams.rightMargin = dp(22);
        latestParams.bottomMargin = dp(72);
        homeLayer.addView(latestLevelButton, latestParams);

        gameView = new PipeTownView(this);
        gameView.setSavedProgress(progress.maxUnlocked());
        gameView.setVisibility(View.GONE);
        gameView.setNavigationListener(new PipeTownView.NavigationListener() {
            @Override
            public void onReturnHome(int maxUnlocked, int latestLevel) {
                progress.setUnlocked(maxUnlocked);
                progress.setLatestLevel(latestLevel);
                showHome();
            }

            @Override
            public void onLevelCompleted(int levelNumber, int maxUnlocked) {
                progress.completeLevel(levelNumber, maxUnlocked);
                pendingCelebrationLevel = Math.max(1, maxUnlocked);
                audio.play("passed_level");
                updateHud();
            }

            @Override
            public void onSoundRequested(String soundKey) {
                audio.play(soundKey);
            }
        });
        homeGlobeView.setListener(new HomeGlobeView.Listener() {
            @Override
            public void onLevelSelected(int levelNumber) {
                beginLevelSelection(levelNumber);
            }

            @Override
            public void onPlanetScrolled() {
                audio.play("scroll_planet");
            }
        });

        root.addView(homeLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        whiteTransition = new View(this);
        whiteTransition.setBackgroundColor(0xFFFFFFFF);
        whiteTransition.setAlpha(0f);
        whiteTransition.setVisibility(View.GONE);
        whiteTransition.setElevation(dp(20));
        root.addView(whiteTransition, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
        updateHud();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(hudTicker);
        if (audio != null) {
            audio.pause();
        }
        if (homeGlobeView != null && homeLayer != null && homeLayer.getVisibility() == View.VISIBLE) {
            homeGlobeView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (progress != null) {
            progress.regenerateHearts();
            updateHud();
        }
        handler.removeCallbacks(hudTicker);
        handler.post(hudTicker);
        if (audio != null) {
            audio.resume();
        }
        if (homeGlobeView != null && homeLayer != null && homeLayer.getVisibility() == View.VISIBLE) {
            homeGlobeView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hudTicker);
        if (audio != null) {
            audio.release();
        }
        super.onDestroy();
    }

    private void showHome() {
        levelTransitioning = false;
        progress.regenerateHearts();
        int celebrationLevel = pendingCelebrationLevel;
        int focusLevel = celebrationLevel > 1 ? celebrationLevel - 1 : progress.focusLevel();
        homeGlobeView.setProgress(progress.maxUnlocked(), focusLevel);
        gameView.setSavedProgress(progress.maxUnlocked());
        gameView.warmLevelCacheAround(focusLevel);
        gameView.setVisibility(View.GONE);
        homeLayer.setVisibility(View.VISIBLE);
        homeGlobeView.onResume();
        if (celebrationLevel > 0) {
            homeGlobeView.celebrateLevel(celebrationLevel);
            pendingCelebrationLevel = -1;
        }
        updateHud();
    }

    private void beginLevelSelection(int levelNumber) {
        if (levelTransitioning) {
            return;
        }
        levelTransitioning = true;
        audio.play("select_level");
        progress.setLatestLevel(levelNumber);
        updateHud();
        whiteTransition.animate().cancel();
        whiteTransition.setVisibility(View.VISIBLE);
        whiteTransition.setAlpha(0f);
        whiteTransition.animate()
                .alpha(1f)
                .setStartDelay(540L)
                .setDuration(260L)
                .withEndAction(() -> revealSelectedLevel(levelNumber))
                .start();
    }

    private void revealSelectedLevel(int levelNumber) {
        homeGlobeView.onPause();
        homeLayer.setVisibility(View.GONE);
        gameView.setVisibility(View.VISIBLE);
        gameView.setSavedProgress(progress.maxUnlocked());
        gameView.startLevelFromMenu(levelNumber);
        whiteTransition.setAlpha(1f);
        whiteTransition.animate()
                .alpha(0f)
                .setStartDelay(90L)
                .setDuration(360L)
                .withEndAction(() -> {
                    whiteTransition.setVisibility(View.GONE);
                    levelTransitioning = false;
                })
                .start();
    }

    private void updateHud() {
        if (hudText == null || soundButton == null || progress == null) {
            return;
        }
        int hearts = progress.hearts();
        StringBuilder text = new StringBuilder();
        text.append(hearts);
        if (hearts < MAX_HEARTS) {
            text.append("  ").append(formatTime(progress.nextHeartRemainingMs()));
        }
        text.append("      Pts ").append(progress.points());
        hudText.setText(text.toString());
        soundButton.setBackground(bitmapBackground(progress.soundMuted() ? "art/icons/sound_off.png" : "art/icons/sound_on.png"));
        if (latestLevelButton != null) {
            latestLevelButton.setText("");
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0L, (ms + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private Bitmap loadAssetBitmap(String path) {
        try (InputStream stream = getAssets().open(path)) {
            return BitmapFactory.decodeStream(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView homeNavButton(String iconPath) {
        TextView button = new TextView(this);
        button.setText("");
        button.setGravity(Gravity.CENTER);
        button.setBackground(bitmapBackground(iconPath));
        button.setElevation(dp(10));
        return button;
    }

    private BitmapDrawable bitmapBackground(String path) {
        Bitmap bitmap = loadAssetBitmap(path);
        if (bitmap == null) {
            BitmapDrawable fallback = new BitmapDrawable(getResources());
            fallback.setGravity(Gravity.CENTER);
            return fallback;
        }
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setGravity(Gravity.FILL);
        drawable.setDither(true);
        drawable.setAntiAlias(true);
        return drawable;
    }

    private static final class ProgressStore {
        private static final String PREFS = "pipetown_progress";
        private static final String MAX_UNLOCKED = "max_unlocked";
        private static final String HIGHEST_COMPLETED = "highest_completed";
        private static final String LATEST_LEVEL = "latest_level";
        private static final String HEARTS = "hearts";
        private static final String POINTS = "points";
        private static final String LAST_HEART_MS = "last_heart_ms";
        private static final String SOUND_MUTED = "sound_muted";

        private final SharedPreferences prefs;

        ProgressStore(Context context) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.contains(HEARTS)) {
                prefs.edit()
                        .putInt(MAX_UNLOCKED, 1)
                        .putInt(HIGHEST_COMPLETED, 0)
                        .putInt(LATEST_LEVEL, 1)
                        .putInt(HEARTS, MAX_HEARTS)
                        .putInt(POINTS, 0)
                        .putLong(LAST_HEART_MS, System.currentTimeMillis())
                        .putBoolean(SOUND_MUTED, false)
                        .apply();
            }
        }

        int maxUnlocked() {
            return Math.max(1, prefs.getInt(MAX_UNLOCKED, 1));
        }

        int focusLevel() {
            return Math.max(1, maxUnlocked());
        }

        int hearts() {
            return Math.max(0, Math.min(MAX_HEARTS, prefs.getInt(HEARTS, MAX_HEARTS)));
        }

        int points() {
            return Math.max(0, prefs.getInt(POINTS, 0));
        }

        boolean soundMuted() {
            return prefs.getBoolean(SOUND_MUTED, false);
        }

        void setSoundMuted(boolean muted) {
            prefs.edit().putBoolean(SOUND_MUTED, muted).apply();
        }

        void setUnlocked(int maxUnlocked) {
            prefs.edit().putInt(MAX_UNLOCKED, Math.max(1, maxUnlocked)).apply();
        }

        void setLatestLevel(int level) {
            prefs.edit().putInt(LATEST_LEVEL, Math.max(1, level)).apply();
        }

        void completeLevel(int levelNumber, int maxUnlocked) {
            int previousHighest = prefs.getInt(HIGHEST_COMPLETED, 0);
            SharedPreferences.Editor editor = prefs.edit()
                    .putInt(MAX_UNLOCKED, Math.max(maxUnlocked(), maxUnlocked))
                    .putInt(LATEST_LEVEL, Math.max(1, maxUnlocked));
            if (levelNumber > previousHighest) {
                editor.putInt(HIGHEST_COMPLETED, levelNumber);
                editor.putInt(POINTS, points() + 100);
            }
            editor.apply();
        }

        void addHearts(int amount) {
            int hearts = Math.min(MAX_HEARTS, hearts() + Math.max(0, amount));
            prefs.edit()
                    .putInt(HEARTS, hearts)
                    .putLong(LAST_HEART_MS, System.currentTimeMillis())
                    .apply();
        }

        boolean removeHeart() {
            if (hearts() <= 0) {
                return false;
            }
            int newHearts = hearts() - 1;
            SharedPreferences.Editor editor = prefs.edit().putInt(HEARTS, newHearts);
            if (newHearts < MAX_HEARTS) {
                editor.putLong(LAST_HEART_MS, System.currentTimeMillis());
            }
            editor.apply();
            return true;
        }

        void addPoints(int amount) {
            prefs.edit().putInt(POINTS, points() + Math.max(0, amount)).apply();
        }

        void removePoints(int amount) {
            prefs.edit().putInt(POINTS, Math.max(0, points() - Math.max(0, amount))).apply();
        }

        void regenerateHearts() {
            int hearts = hearts();
            long now = System.currentTimeMillis();
            long last = prefs.getLong(LAST_HEART_MS, now);
            if (hearts >= MAX_HEARTS) {
                prefs.edit().putInt(HEARTS, MAX_HEARTS).putLong(LAST_HEART_MS, now).apply();
                return;
            }
            long elapsed = Math.max(0L, now - last);
            int gained = (int) (elapsed / HEART_REGEN_MS);
            if (gained <= 0) {
                return;
            }
            int newHearts = Math.min(MAX_HEARTS, hearts + gained);
            long newLast = newHearts >= MAX_HEARTS ? now : last + gained * HEART_REGEN_MS;
            prefs.edit().putInt(HEARTS, newHearts).putLong(LAST_HEART_MS, newLast).apply();
        }

        long nextHeartRemainingMs() {
            if (hearts() >= MAX_HEARTS) {
                return 0L;
            }
            long last = prefs.getLong(LAST_HEART_MS, System.currentTimeMillis());
            return Math.max(0L, HEART_REGEN_MS - (System.currentTimeMillis() - last));
        }
    }

    private static final class AudioController {
        private final Context context;
        private final SoundPool soundPool;
        private final Map<String, Integer> sounds = new HashMap<>();
        private MediaPlayer music;
        private boolean muted;

        AudioController(Context context) {
            this.context = context.getApplicationContext();
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(attributes)
                    .build();
            loadSound("select_level", "sounds/select_level.mp3");
            loadSound("scroll_planet", "sounds/scroll_planet.mp3");
            loadSound("passed_level", "sounds/passed_level.mp3");
            loadSound("liquid_connect", "sounds/liquid_connect.mp3");
            loadSound("electric_connect", "sounds/electric_connect.mp3");
            loadSound("fail", "sounds/fail.mp3");
            music = createLoopingMusic("sounds/background_music.mp3");
        }

        void setMuted(boolean muted) {
            this.muted = muted;
            if (muted) {
                pause();
            } else {
                resume();
            }
        }

        void play(String key) {
            if (muted) {
                return;
            }
            Integer soundId = sounds.get(key);
            if (soundId == null || soundId == 0) {
                return;
            }
            soundPool.play(soundId, 0.62f, 0.62f, 1, 0, 1f);
        }

        void resume() {
            if (muted || music == null) {
                return;
            }
            try {
                if (!music.isPlaying()) {
                    music.start();
                }
            } catch (IllegalStateException ignored) {
            }
        }

        void pause() {
            if (music == null) {
                return;
            }
            try {
                if (music.isPlaying()) {
                    music.pause();
                }
            } catch (IllegalStateException ignored) {
            }
        }

        void release() {
            soundPool.release();
            if (music != null) {
                music.release();
                music = null;
            }
        }

        private void loadSound(String key, String path) {
            try (AssetFileDescriptor descriptor = context.getAssets().openFd(path)) {
                sounds.put(key, soundPool.load(descriptor, 1));
            } catch (IOException ignored) {
                sounds.put(key, 0);
            }
        }

        private MediaPlayer createLoopingMusic(String path) {
            MediaPlayer player = new MediaPlayer();
            try (AssetFileDescriptor descriptor = context.getAssets().openFd(path)) {
                player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
                player.setLooping(true);
                player.setVolume(0.34f, 0.34f);
                player.prepare();
                return player;
            } catch (IOException | IllegalStateException ignored) {
                player.release();
                return null;
            }
        }
    }
}
