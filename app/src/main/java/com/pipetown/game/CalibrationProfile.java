package com.pipetown.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

final class CalibrationProfile {
    static final String VERSION = "PTCAL3";
    static final String DEFAULT_CODE = VERSION
            + "|G:pipe:A=1.00,1.00,0.0,0.0"
            + "|G:pipe_side:A=1.00,1.00,0.0,0.0"
            + "|G:pipe_bottom:A=1.00,1.00,0.0,0.0"
            + "|G:pipe_icon:A=1.00,1.00,0.0,0.0"
            + "|G:source_badge:A=0.88,0.88,0.0,0.0"
            + "|L:house_1x1:A=1.84,1.84,0.0,0.0"
            + "|L:house_2x2:A=1.28,1.28,0.0,0.0"
            + "|L:house_4x4:A=1.40,1.40,0.0,0.0"
            + "|L:house_5x5:A=1.40,1.40,0.0,0.0"
            + "|L:source_water:A=1.00,1.00,0.0,0.0"
            + "|L:source_electric:A=1.00,1.00,0.0,0.0"
            + "|L:source_gas:A=1.00,1.00,0.0,0.0"
            + "|L:source_heating:A=1.00,1.00,0.0,0.0"
            + "|L:source_internet:A=1.00,1.00,0.0,0.0"
            + "|L:source_sewage:A=1.00,1.00,0.0,0.0"
            + "|L:construction_1x1:A=1.76,1.76,0.0,0.0"
            + "|L:construction_1x2:A=1.64,1.64,0.0,0.0"
            + "|L:construction_1x3:A=1.52,1.52,0.0,0.0"
            + "|L:pond_1x1:A=1.68,1.68,0.0,0.0"
            + "|L:pond_2x2:A=1.68,1.68,0.0,0.0"
            + "|L:pond_2x3:A=1.52,1.52,0.0,0.0"
            + "|L:stone_1x1:A=1.96,1.96,0.0,0.0"
            + "|L:stone_1x3:A=1.40,1.40,0.0,0.0"
            + "|L:stone_2x2:A=1.44,1.44,0.0,0.0"
            + "|L:tree_1x1:A=2.20,2.20,0.0,0.0"
            + "|L:tree_1x2:A=1.52,1.52,0.0,0.0"
            + "|L:tree_1x3:A=1.40,1.40,0.0,0.0"
            + "|M:logo:A=1.00,1.00,0.0,0.0"
            + "|M:sound_on:A=1.28,1.28,0.0,0.0"
            + "|M:sound_off:A=1.28,1.28,0.0,0.0"
            + "|M:go_level_1:A=1.24,1.24,0.0,0.0"
            + "|M:go_unlocked:A=1.24,1.24,0.0,0.0"
            + "|M:map:A=1.64,1.64,0.0,0.0"
            + "|M:reset:A=1.44,1.44,0.0,0.0"
            + "|M:finish_level:A=1.36,1.36,0.0,0.0"
            + "|M:revert:A=1.28,1.28,0.0,0.0"
            + "|M:hint:A=1.68,1.68,0.0,0.0"
            + "|M:settings:A=1.40,1.40,0.0,0.0";

    private static final String PREFS = "pipetown_calibration";
    private static final String PROFILE_CODE = "profile_code";

    static final class Layer {
        static final Layer IDENTITY = new Layer(1f, 1f, 0f, 0f);

        final float scaleX;
        final float scaleY;
        final float x;
        final float y;

        Layer(float scaleX, float scaleY, float x, float y) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.x = x;
            this.y = y;
        }
    }

    private final String code;
    private final HashMap<String, Layer> layers = new HashMap<>();

    private CalibrationProfile(String code) {
        this.code = code;
        parseCode(code);
    }

    static CalibrationProfile load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(PROFILE_CODE, DEFAULT_CODE);
        if (stored == null || !stored.startsWith(VERSION) || isLegacyDefault(stored)) {
            prefs.edit().putString(PROFILE_CODE, DEFAULT_CODE).apply();
            stored = DEFAULT_CODE;
        }
        return new CalibrationProfile(stored);
    }

    static CalibrationProfile fromCode(String code) {
        return new CalibrationProfile(code != null && code.startsWith(VERSION) ? code : DEFAULT_CODE);
    }

    static void save(Context context, String code) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PROFILE_CODE, code != null && code.startsWith(VERSION) ? code : DEFAULT_CODE)
                .apply();
    }

    Layer layer(String scope, String key, char part) {
        Layer value = layers.get(scope + ":" + canonicalKey(scope, key) + ":" + part);
        return value == null ? Layer.IDENTITY : value;
    }

    private void parseCode(String value) {
        String[] items = value.split("\\|");
        for (int i = 1; i < items.length; i++) {
            String[] parts = items[i].split(":");
            if (parts.length < 3) {
                continue;
            }
            String scope = parts[0];
            String key = canonicalKey(scope, parts[1]);
            for (int layerIndex = 2; layerIndex < parts.length; layerIndex++) {
                String entry = parts[layerIndex];
                int equals = entry.indexOf('=');
                if (equals != 1 || entry.length() <= equals + 1) {
                    continue;
                }
                String[] numbers = entry.substring(equals + 1).split(",");
                try {
                    if (numbers.length == 4) {
                        layers.put(scope + ":" + key + ":" + entry.charAt(0),
                                new Layer(Float.parseFloat(numbers[0]), Float.parseFloat(numbers[1]),
                                        Float.parseFloat(numbers[2]), Float.parseFloat(numbers[3])));
                    }
                } catch (NumberFormatException ignored) {
                    // Keep the runtime baseline for malformed values.
                }
            }
        }
    }

    private static String canonicalKey(String scope, String key) {
        if ("M".equals(scope) && "world_map".equals(key)) {
            return "map";
        }
        return key;
    }

    private static boolean isLegacyDefault(String code) {
        return code.startsWith(VERSION) && !code.contains("|L:house_1x1:")
                && !code.contains("|M:sound_on:");
    }
}
