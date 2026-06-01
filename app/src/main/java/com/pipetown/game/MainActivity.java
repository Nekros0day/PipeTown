package com.pipetown.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends Activity {
    private static final long HEART_REGEN_MS = 30L * 60L * 1000L;
    private static final int MAX_HEARTS = 3;
    private static final int SKY_TOP_COLOR = 0xFF47A6E5;
    private static final String TEST_BANNER_AD_ID = "ca-app-pub-3940256099942544/6300978111";
    private static final String TEST_INTERSTITIAL_AD_ID = "ca-app-pub-3940256099942544/1033173712";
    private static final String TEST_REWARDED_AD_ID = "ca-app-pub-3940256099942544/5224354917";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private FrameLayout homeLayer;
    private HomeGlobeView homeGlobeView;
    private PipeTownView gameView;
    private CalibrationView calibrationView;
    private CalibrationProfile calibrationProfile;
    private TextView soundButton;
    private FrameLayout.LayoutParams soundParams;
    private TextView firstLevelButton;
    private TextView latestLevelButton;
    private FrameLayout.LayoutParams firstLevelParams;
    private FrameLayout.LayoutParams latestLevelParams;
    private FrameLayout adContainer;
    private LinearLayout debugBar;
    private FrameLayout.LayoutParams debugBarParams;
    private TextView adsToggleButton;
    private FrameLayout onboardingLayer;
    private AdView bannerAd;
    private InterstitialAd interstitialAd;
    private RewardedAd rewardedAd;
    private View whiteTransition;
    private ProgressStore progress;
    private AudioController audio;
    private int pendingCelebrationLevel = -1;
    private boolean levelTransitioning;
    private boolean adsInitialized;
    private boolean pendingInterstitial;
    private boolean calibrationOpenedFromGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(SKY_TOP_COLOR);
        getWindow().setNavigationBarColor(0xFFCDECF6);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(SKY_TOP_COLOR));
        progress = new ProgressStore(this);
        calibrationProfile = CalibrationProfile.load(this);
        audio = new AudioController(this);
        audio.setMuted(progress.soundMuted());

        root = new FrameLayout(this);
        root.setBackgroundColor(SKY_TOP_COLOR);
        homeLayer = new FrameLayout(this);
        homeLayer.setBackgroundColor(SKY_TOP_COLOR);
        homeGlobeView = new HomeGlobeView(this);
        homeGlobeView.setCalibrationProfile(calibrationProfile);
        homeGlobeView.setProgress(progress.maxUnlocked(), progress.focusLevel());
        homeLayer.addView(homeGlobeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

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
        soundParams = new FrameLayout.LayoutParams(dp(54), dp(54));
        soundParams.gravity = Gravity.TOP | Gravity.END;
        soundParams.rightMargin = dp(14);
        soundParams.topMargin = dp(12);
        homeLayer.addView(soundButton, soundParams);

        firstLevelButton = homeNavButton("art/icons/go_level_1.png");
        firstLevelButton.setOnClickListener(v -> {
            audio.play("scroll_planet");
            homeGlobeView.rotateToLevelOne();
        });
        firstLevelParams = new FrameLayout.LayoutParams(dp(72), dp(72));
        firstLevelParams.gravity = Gravity.BOTTOM | Gravity.START;
        firstLevelParams.leftMargin = dp(22);
        homeLayer.addView(firstLevelButton, firstLevelParams);

        latestLevelButton = homeNavButton("art/icons/go_level_unlocked.png");
        latestLevelButton.setOnClickListener(v -> {
            audio.play("scroll_planet");
            homeGlobeView.rotateToLatestUnlocked();
        });
        latestLevelParams = new FrameLayout.LayoutParams(dp(72), dp(72));
        latestLevelParams.gravity = Gravity.BOTTOM | Gravity.END;
        latestLevelParams.rightMargin = dp(22);
        homeLayer.addView(latestLevelButton, latestLevelParams);
        applyHomeCalibration();

        gameView = new PipeTownView(this);
        gameView.setCalibrationProfile(calibrationProfile);
        gameView.setSavedProgress(progress.maxUnlocked());
        gameView.setVisibility(View.GONE);
        gameView.setNavigationListener(new PipeTownView.NavigationListener() {
            @Override
            public void onReturnHome(int maxUnlocked, int latestLevel) {
                progress.setUnlocked(maxUnlocked);
                if (latestLevel > 0) {
                    progress.setLatestLevel(latestLevel);
                }
                showHome();
            }

            @Override
            public void onLevelCompleted(int levelNumber, int maxUnlocked) {
                if (levelNumber < 0) {
                    audio.play("passed_level");
                    return;
                }
                if (levelNumber == 0) {
                    progress.setTutorialCompleted(true);
                    audio.play("passed_level");
                    return;
                }
                progress.completeLevel(levelNumber, maxUnlocked);
                pendingCelebrationLevel = Math.max(1, maxUnlocked);
                if (!progress.adsDisabled() && progress.countCompletionTowardAd()) {
                    pendingInterstitial = true;
                }
                audio.play("passed_level");
                updateHud();
            }

            @Override
            public void onSoundRequested(String soundKey) {
                audio.play(soundKey);
            }

            @Override
            public void onRewardedHelpRequested(boolean solve, Runnable rewardedAction) {
                showRewardedHelp(solve, rewardedAction);
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
        if (getIntent().getBooleanExtra("generator_audit", false)) {
            int auditLevels = Math.max(1, getIntent().getIntExtra("audit_levels", 120));
            Thread auditThread = new Thread(() ->
                    Log.i("PipeTownGeneratorAudit", gameView.auditGeneratedLevels(auditLevels)),
                    "PipeTown-generator-audit");
            auditThread.setDaemon(true);
            auditThread.start();
        }

        root.addView(homeLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        calibrationView = new CalibrationView(this, calibrationProfile, new CalibrationView.Listener() {
            @Override
            public void onDone(String code) {
                applyCalibration(code);
                closeCalibration();
            }

            @Override
            public void onExported(String code) {
                Toast.makeText(MainActivity.this, "Calibration code copied", Toast.LENGTH_SHORT).show();
            }
        });
        calibrationView.setVisibility(View.GONE);
        root.addView(calibrationView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        createBottomTools();
        whiteTransition = new View(this);
        whiteTransition.setBackgroundColor(0xFFFFFFFF);
        whiteTransition.setAlpha(0f);
        whiteTransition.setVisibility(View.GONE);
        whiteTransition.setElevation(dp(20));
        root.addView(whiteTransition, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        applySafeInsets();
        setContentView(root);
        updateAdVisibility();
        updateHud();
        showFirstRunGuide();
    }

    private void applySafeInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (audio != null) {
            audio.pause();
        }
        if (bannerAd != null) {
            bannerAd.pause();
        }
        if (homeGlobeView != null && homeLayer != null && homeLayer.getVisibility() == View.VISIBLE) {
            homeGlobeView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (progress != null) {
            updateHud();
        }
        if (audio != null) {
            audio.resume();
        }
        if (bannerAd != null) {
            bannerAd.resume();
        }
        if (homeGlobeView != null && homeLayer != null && homeLayer.getVisibility() == View.VISIBLE) {
            homeGlobeView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (audio != null) {
            audio.release();
        }
        if (bannerAd != null) {
            bannerAd.destroy();
        }
        super.onDestroy();
    }

    private void showHome() {
        levelTransitioning = false;
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
        handler.postDelayed(this::maybeShowInterstitial, 500L);
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

    private void createBottomTools() {
        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(0xD9F7FCFF);
        adContainer.setElevation(dp(28));
        FrameLayout.LayoutParams adParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(54));
        adParams.gravity = Gravity.BOTTOM;
        root.addView(adContainer, adParams);

        debugBar = new LinearLayout(this);
        debugBar.setGravity(Gravity.CENTER_VERTICAL);
        debugBar.setOrientation(LinearLayout.HORIZONTAL);
        debugBar.setPadding(dp(7), dp(5), dp(7), dp(5));
        debugBar.setBackgroundColor(0xE928313D);
        debugBar.setElevation(dp(29));

        TextView reset = debugButton("Reset Guide");
        reset.setOnClickListener(v -> {
            progress.debugResetLevels();
            progress.debugResetGuide();
            pendingCelebrationLevel = -1;
            pendingInterstitial = false;
            gameView.setSavedProgress(1);
            homeGlobeView.setProgress(1, 1);
            showHome();
            homeGlobeView.rotateToLevelOne();
            updateHud();
            showFirstRunGuide();
        });
        debugBar.addView(reset, weightedDebugParams());

        TextView unlock = debugButton("Solve +10");
        unlock.setOnClickListener(v -> {
            progress.debugUnlockTen();
            pendingCelebrationLevel = -1;
            gameView.setSavedProgress(progress.maxUnlocked());
            homeGlobeView.setProgress(progress.maxUnlocked(), progress.maxUnlocked());
            showHome();
            homeGlobeView.rotateToLatestUnlocked();
            updateHud();
        });
        debugBar.addView(unlock, weightedDebugParams());

        TextView calibrate = debugButton("Calibrate");
        calibrate.setOnClickListener(v -> showCalibration());
        debugBar.addView(calibrate, weightedDebugParams());

        TextView mechanicsLab = debugButton("Lab");
        mechanicsLab.setOnClickListener(v -> showMechanicsLab());
        debugBar.addView(mechanicsLab, weightedDebugParams());

        adsToggleButton = debugButton("");
        adsToggleButton.setOnClickListener(v -> {
            progress.setAdsDisabled(!progress.adsDisabled());
            pendingInterstitial = false;
            updateAdVisibility();
        });
        debugBar.addView(adsToggleButton, weightedDebugParams());

        debugBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(49));
        debugBarParams.gravity = Gravity.BOTTOM;
        root.addView(debugBar, debugBarParams);
    }

    private void showMechanicsLab() {
        dismissFirstRunGuide();
        pendingInterstitial = false;
        homeGlobeView.onPause();
        homeLayer.setVisibility(View.GONE);
        calibrationView.setVisibility(View.GONE);
        gameView.setVisibility(View.VISIBLE);
        gameView.startMechanicsLab();
        audio.play("select_level");
        updateHud();
    }

    private LinearLayout.LayoutParams weightedDebugParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private TextView debugButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF3E5665);
        shape.setStroke(dp(1), 0xFF8DB0BD);
        shape.setCornerRadius(dp(6));
        button.setBackground(shape);
        return button;
    }

    private void showFirstRunGuide() {
        if (progress == null || progress.homeGuideSeen() || onboardingLayer != null) {
            return;
        }
        onboardingLayer = new FrameLayout(this);
        onboardingLayer.setBackgroundColor(0x990E1D28);
        onboardingLayer.setElevation(dp(60));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFF8E8);
        background.setStroke(dp(2), 0xFFE6C77F);
        background.setCornerRadius(dp(20));
        card.setBackground(background);

        TextView title = guideText("Welcome to PipeTown", 25, 0xFF25343B, true);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView guide = guideText("Swipe the globe to browse stitched level patches.\nTap a patch to play. Start with one quick lesson.", 14, 0xFF5E6C70, false);
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        guideParams.topMargin = dp(12);
        guideParams.bottomMargin = dp(20);
        card.addView(guide, guideParams);

        TextView start = guideButton("Play Tutorial", 0xFF2F8AD4, Color.WHITE);
        start.setOnClickListener(v -> {
            progress.setHomeGuideSeen(true);
            dismissFirstRunGuide();
            beginLevelSelection(0);
        });
        card.addView(start, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        TextView explore = guideButton("Explore Map", 0xFFFFF8E8, 0xFF37718F);
        explore.setOnClickListener(v -> {
            progress.setHomeGuideSeen(true);
            dismissFirstRunGuide();
        });
        LinearLayout.LayoutParams exploreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        exploreParams.topMargin = dp(8);
        card.addView(explore, exploreParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(34)),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        onboardingLayer.addView(card, cardParams);
        root.addView(onboardingLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void dismissFirstRunGuide() {
        if (onboardingLayer != null) {
            root.removeView(onboardingLayer);
            onboardingLayer = null;
        }
    }

    private TextView guideText(String label, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(0f, 1.16f);
        return view;
    }

    private TextView guideButton(String label, int fill, int textColor) {
        TextView button = guideText(label, 16, textColor, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setStroke(dp(2), fill == 0xFF2F8AD4 ? 0xFF2472B4 : 0xFFD8BC79);
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        return button;
    }

    private void updateAdVisibility() {
        if (adContainer == null || debugBarParams == null) {
            return;
        }
        boolean disabled = progress.adsDisabled();
        if (adsToggleButton != null) {
            adsToggleButton.setText(disabled ? "Ads: OFF" : "Ads: ON");
        }
        int bannerSpace = disabled ? 0 : dp(54);
        adContainer.setVisibility(disabled ? View.GONE : View.VISIBLE);
        debugBarParams.bottomMargin = bannerSpace;
        debugBar.setLayoutParams(debugBarParams);
        int controlsBottom = bannerSpace + dp(61);
        if (firstLevelParams != null) {
            firstLevelParams.bottomMargin = controlsBottom;
            firstLevelButton.setLayoutParams(firstLevelParams);
        }
        if (latestLevelParams != null) {
            latestLevelParams.bottomMargin = controlsBottom;
            latestLevelButton.setLayoutParams(latestLevelParams);
        }
        if (gameView != null) {
            gameView.setBottomReservedSpace(bannerSpace + dp(53));
        }
        if (disabled) {
            if (bannerAd != null) {
                bannerAd.pause();
            }
            return;
        }
        ensureAdsInitialized();
    }

    private void showCalibration() {
        if (calibrationView == null) {
            return;
        }
        calibrationOpenedFromGame = gameView != null && gameView.getVisibility() == View.VISIBLE;
        calibrationView.setLevelReferenceCell(gameView.calibrationReferenceCell());
        homeGlobeView.onPause();
        homeLayer.setVisibility(View.GONE);
        gameView.setVisibility(View.GONE);
        debugBar.setVisibility(View.GONE);
        adContainer.setVisibility(View.GONE);
        calibrationView.setVisibility(View.VISIBLE);
        calibrationView.loadProfile(calibrationProfile);
        calibrationView.resetSessionView();
    }

    private void closeCalibration() {
        calibrationView.setVisibility(View.GONE);
        debugBar.setVisibility(View.VISIBLE);
        if (calibrationOpenedFromGame) {
            homeLayer.setVisibility(View.GONE);
            gameView.setVisibility(View.VISIBLE);
            updateHud();
        } else {
            showHome();
        }
        calibrationOpenedFromGame = false;
        updateAdVisibility();
    }

    private void applyCalibration(String code) {
        CalibrationProfile.save(this, code);
        calibrationProfile = CalibrationProfile.fromCode(code);
        homeGlobeView.setCalibrationProfile(calibrationProfile);
        gameView.setCalibrationProfile(calibrationProfile);
        applyHomeCalibration();
        Toast.makeText(this, "Calibration applied", Toast.LENGTH_SHORT).show();
    }

    private void ensureAdsInitialized() {
        if (adsInitialized || progress.adsDisabled()) {
            return;
        }
        adsInitialized = true;
        MobileAds.initialize(this, ignored -> {
            loadBannerAd();
            loadInterstitialAd();
            loadRewardedAd();
        });
    }

    private void loadBannerAd() {
        if (progress.adsDisabled() || adContainer == null) {
            return;
        }
        if (bannerAd == null) {
            bannerAd = new AdView(this);
            bannerAd.setAdUnitId(TEST_BANNER_AD_ID);
            bannerAd.setAdSize(AdSize.BANNER);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER;
            adContainer.addView(bannerAd, params);
        }
        bannerAd.resume();
        bannerAd.loadAd(new AdRequest.Builder().build());
    }

    private void loadInterstitialAd() {
        if (progress.adsDisabled() || interstitialAd != null) {
            return;
        }
        InterstitialAd.load(this, TEST_INTERSTITIAL_AD_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitialAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        interstitialAd = null;
                    }
                });
    }

    private void maybeShowInterstitial() {
        if (!pendingInterstitial || progress.adsDisabled()
                || homeLayer == null || homeLayer.getVisibility() != View.VISIBLE) {
            return;
        }
        if (interstitialAd == null) {
            loadInterstitialAd();
            return;
        }
        pendingInterstitial = false;
        InterstitialAd ad = interstitialAd;
        interstitialAd = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                loadInterstitialAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError adError) {
                loadInterstitialAd();
            }
        });
        ad.show(this);
    }

    private void loadRewardedAd() {
        if (progress.adsDisabled() || rewardedAd != null) {
            return;
        }
        RewardedAd.load(this, TEST_REWARDED_AD_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(RewardedAd ad) {
                        rewardedAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        rewardedAd = null;
                    }
                });
    }

    private void showRewardedHelp(boolean solve, Runnable rewardedAction) {
        if (!solve) {
            showHintInterstitial(rewardedAction);
            return;
        }
        if (progress.adsDisabled()) {
            rewardedAction.run();
            return;
        }
        if (rewardedAd == null) {
            Toast.makeText(this, "Solve video is loading. Try again shortly.", Toast.LENGTH_SHORT).show();
            loadRewardedAd();
            return;
        }
        RewardedAd ad = rewardedAd;
        rewardedAd = null;
        final boolean[] earnedReward = {false};
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                loadRewardedAd();
                if (earnedReward[0]) {
                    rewardedAction.run();
                }
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError adError) {
                loadRewardedAd();
            }
        });
        ad.show(this, rewardItem -> earnedReward[0] = true);
    }

    private void showHintInterstitial(Runnable hintAction) {
        if (progress.adsDisabled()) {
            hintAction.run();
            return;
        }
        if (interstitialAd == null) {
            Toast.makeText(this, "Hint ad is loading. Try again shortly.", Toast.LENGTH_SHORT).show();
            loadInterstitialAd();
            return;
        }
        InterstitialAd ad = interstitialAd;
        interstitialAd = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                loadInterstitialAd();
                hintAction.run();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError adError) {
                loadInterstitialAd();
            }
        });
        ad.show(this);
    }

    private void updateHud() {
        if (soundButton == null || progress == null) {
            return;
        }
        soundButton.setBackground(bitmapBackground(progress.soundMuted() ? "art/icons/sound_off.png" : "art/icons/sound_on.png"));
        applyHomeCalibration();
        if (latestLevelButton != null) {
            latestLevelButton.setText("");
        }
    }

    private void applyHomeCalibration() {
        if (calibrationProfile == null) {
            return;
        }

        CalibrationProfile.Layer soundTune = calibrationProfile.layer("M",
                progress != null && progress.soundMuted() ? "sound_off" : "sound_on", 'A');
        soundParams.width = scaled(dp(54), soundTune.scaleX);
        soundParams.height = scaled(dp(54), soundTune.scaleY);
        soundParams.rightMargin = dp(14 - soundTune.x);
        soundParams.topMargin = dp(12 + soundTune.y);
        soundButton.setLayoutParams(soundParams);

        applyHomeNavCalibration(firstLevelButton, firstLevelParams, "go_level_1", true);
        applyHomeNavCalibration(latestLevelButton, latestLevelParams, "go_unlocked", false);
    }

    private void applyHomeNavCalibration(TextView button, FrameLayout.LayoutParams params, String key, boolean start) {
        if (button == null || params == null) {
            return;
        }
        CalibrationProfile.Layer tune = calibrationProfile.layer("M", key, 'A');
        params.width = scaled(dp(72), tune.scaleX);
        params.height = scaled(dp(72), tune.scaleY);
        if (start) {
            params.leftMargin = dp(22 + tune.x);
        } else {
            params.rightMargin = dp(22 - tune.x);
        }
        button.setTranslationY(dp(tune.y));
        button.setLayoutParams(params);
    }

    private int scaled(int base, float scale) {
        return Math.max(dp(22), Math.round(base * scale));
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
        private static final String ADS_DISABLED = "ads_disabled";
        private static final String LEVELS_TO_INTERSTITIAL = "levels_to_interstitial";
        private static final String HOME_GUIDE_SEEN = "home_guide_seen";
        private static final String TUTORIAL_COMPLETED = "tutorial_completed";

        private final SharedPreferences prefs;
        private final Random random = new Random();

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
                        .putBoolean(ADS_DISABLED, false)
                        .putInt(LEVELS_TO_INTERSTITIAL, nextAdGap())
                        .apply();
            }
            if (!prefs.contains(LEVELS_TO_INTERSTITIAL)) {
                prefs.edit().putInt(LEVELS_TO_INTERSTITIAL, nextAdGap()).apply();
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

        boolean adsDisabled() {
            return prefs.getBoolean(ADS_DISABLED, false);
        }

        boolean homeGuideSeen() {
            return prefs.getBoolean(HOME_GUIDE_SEEN, false);
        }

        void setHomeGuideSeen(boolean seen) {
            prefs.edit().putBoolean(HOME_GUIDE_SEEN, seen).apply();
        }

        void setTutorialCompleted(boolean completed) {
            prefs.edit().putBoolean(TUTORIAL_COMPLETED, completed).apply();
        }

        void setAdsDisabled(boolean disabled) {
            prefs.edit().putBoolean(ADS_DISABLED, disabled).apply();
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
            }
            editor.apply();
        }

        void debugResetLevels() {
            prefs.edit()
                    .putInt(MAX_UNLOCKED, 1)
                    .putInt(HIGHEST_COMPLETED, 0)
                    .putInt(LATEST_LEVEL, 1)
                    .putInt(HEARTS, MAX_HEARTS)
                    .putInt(POINTS, 0)
                    .putLong(LAST_HEART_MS, System.currentTimeMillis())
                    .putInt(LEVELS_TO_INTERSTITIAL, nextAdGap())
                    .apply();
        }

        void debugResetGuide() {
            prefs.edit()
                    .putBoolean(HOME_GUIDE_SEEN, false)
                    .putBoolean(TUTORIAL_COMPLETED, false)
                    .apply();
        }

        void debugUnlockTen() {
            int unlocked = maxUnlocked() + 10;
            prefs.edit()
                    .putInt(MAX_UNLOCKED, unlocked)
                    .putInt(HIGHEST_COMPLETED, Math.max(0, unlocked - 1))
                    .putInt(LATEST_LEVEL, unlocked)
                    .apply();
        }

        boolean countCompletionTowardAd() {
            int remaining = prefs.getInt(LEVELS_TO_INTERSTITIAL, nextAdGap()) - 1;
            if (remaining <= 0) {
                prefs.edit().putInt(LEVELS_TO_INTERSTITIAL, nextAdGap()).apply();
                return true;
            }
            prefs.edit().putInt(LEVELS_TO_INTERSTITIAL, remaining).apply();
            return false;
        }

        private int nextAdGap() {
            return 3 + random.nextInt(3);
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
