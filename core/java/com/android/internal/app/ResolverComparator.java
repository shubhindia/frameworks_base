/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.internal.app;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks and compares packages based on usage stats.
 */
class ResolverComparator implements Comparator<ResolvedComponentInfo> {
    private static final String TAG = "ResolverComparator";

    private static final boolean DEBUG = false;

    // One week
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 7;

    private static final long RECENCY_TIME_PERIOD = 1000 * 60 * 60 * 12;

    private static final float RECENCY_MULTIPLIER = 2.f;

    // feature names used in ranking.
    private static final String LAUNCH_SCORE = "launch";
    private static final String TIME_SPENT_SCORE = "timeSpent";
    private static final String RECENCY_SCORE = "recency";
    private static final String CHOOSER_SCORE = "chooser";

    private final Collator mCollator;
    private final boolean mHttp;
    private final PackageManager mPm;
    private final UsageStatsManager mUsm;
    private final Map<String, UsageStats> mStats;
    private final long mCurrentTime;
    private final long mSinceTime;
    private final LinkedHashMap<ComponentName, ScoredTarget> mScoredTargets = new LinkedHashMap<>();
    private final String mReferrerPackage;
    public String mContentType;
    private String mAction;
    private LogisticRegressionAppRanker mRanker;

    public ResolverComparator(Context context, Intent intent, String referrerPackage) {
        mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        String scheme = intent.getScheme();
        mHttp = "http".equals(scheme) || "https".equals(scheme);
        mReferrerPackage = referrerPackage;

        mPm = context.getPackageManager();
        mUsm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

        mCurrentTime = System.currentTimeMillis();
        mSinceTime = mCurrentTime - USAGE_STATS_PERIOD;
        mStats = mUsm.queryAndAggregateUsageStats(mSinceTime, mCurrentTime);
        mContentType = intent.getType();
        mAction = intent.getAction();
        mRanker = new LogisticRegressionAppRanker(context);
    }

    public void compute(List<ResolvedComponentInfo> targets) {
        mScoredTargets.clear();

        final long recentSinceTime = mCurrentTime - RECENCY_TIME_PERIOD;

        long mostRecentlyUsedTime = recentSinceTime + 1;
        long mostTimeSpent = 1;
        int mostLaunched = 1;
        int mostSelected = 1;

        for (ResolvedComponentInfo target : targets) {
            final ScoredTarget scoredTarget
                    = new ScoredTarget(target.getResolveInfoAt(0).activityInfo);
            mScoredTargets.put(target.name, scoredTarget);
            final UsageStats pkStats = mStats.get(target.name.getPackageName());
            if (pkStats != null) {
                // Only count recency for apps that weren't the caller
                // since the caller is always the most recent.
                // Persistent processes muck this up, so omit them too.
                if (!target.name.getPackageName().equals(mReferrerPackage)
                        && !isPersistentProcess(target)) {
                    final long lastTimeUsed = pkStats.getLastTimeUsed();
                    scoredTarget.lastTimeUsed = lastTimeUsed;
                    if (lastTimeUsed > mostRecentlyUsedTime) {
                        mostRecentlyUsedTime = lastTimeUsed;
                    }
                }
                final long timeSpent = pkStats.getTotalTimeInForeground();
                scoredTarget.timeSpent = timeSpent;
                if (timeSpent > mostTimeSpent) {
                    mostTimeSpent = timeSpent;
                }
                final int launched = pkStats.mLaunchCount;
                scoredTarget.launchCount = launched;
                if (launched > mostLaunched) {
                    mostLaunched = launched;
                }
                // TODO(kanlig): get and combine counts of categories.

                int selected = 0;
                if (pkStats.mChooserCounts != null && mAction != null
                        && pkStats.mChooserCounts.get(mAction) != null) {
                    selected = pkStats.mChooserCounts.get(mAction).getOrDefault(mContentType, 0);
                }
                if (DEBUG) {
                    if (mAction == null) {
                        Log.d(TAG, "Action type is null");
                    } else {
                        Log.d(TAG, "Chooser Count of " + mAction + ":" +
                                target.name.getPackageName() + " is " + Integer.toString(selected));
                    }
                }
                scoredTarget.chooserCount = selected;
                if (selected > mostSelected) {
                    mostSelected = selected;
                }
            }
        }


        if (DEBUG) {
            Log.d(TAG, "compute - mostRecentlyUsedTime: " + mostRecentlyUsedTime
                    + " mostTimeSpent: " + mostTimeSpent
                    + " recentSinceTime: " + recentSinceTime
                    + " mostLaunched: " + mostLaunched);
        }

        for (ScoredTarget target : mScoredTargets.values()) {
            final float recency = (float) Math.max(target.lastTimeUsed - recentSinceTime, 0)
                    / (mostRecentlyUsedTime - recentSinceTime);
            target.setFeatures((float) target.launchCount / mostLaunched,
                    (float) target.timeSpent / mostTimeSpent,
                    recency * recency * RECENCY_MULTIPLIER,
                    (float) target.chooserCount / mostSelected);
            target.selectProb = mRanker.predict(target.getFeatures());
            if (DEBUG) {
                Log.d(TAG, "Scores: " + target);
            }
        }
    }

    static boolean isPersistentProcess(ResolvedComponentInfo rci) {
        if (rci != null && rci.getCount() > 0) {
            return (rci.getResolveInfoAt(0).activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_PERSISTENT) != 0;
        }
        return false;
    }

    @Override
    public int compare(ResolvedComponentInfo lhsp, ResolvedComponentInfo rhsp) {
        final ResolveInfo lhs = lhsp.getResolveInfoAt(0);
        final ResolveInfo rhs = rhsp.getResolveInfoAt(0);

        // We want to put the one targeted to another user at the end of the dialog.
        if (lhs.targetUserId != UserHandle.USER_CURRENT) {
            return rhs.targetUserId != UserHandle.USER_CURRENT ? 0 : 1;
        }
        if (rhs.targetUserId != UserHandle.USER_CURRENT) {
            return -1;
        }

        if (mHttp) {
            // Special case: we want filters that match URI paths/schemes to be
            // ordered before others.  This is for the case when opening URIs,
            // to make native apps go above browsers.
            final boolean lhsSpecific = ResolverActivity.isSpecificUriMatch(lhs.match);
            final boolean rhsSpecific = ResolverActivity.isSpecificUriMatch(rhs.match);
            if (lhsSpecific != rhsSpecific) {
                return lhsSpecific ? -1 : 1;
            }
        }

        final boolean lPinned = lhsp.isPinned();
        final boolean rPinned = rhsp.isPinned();

        if (lPinned && !rPinned) {
            return -1;
        } else if (!lPinned && rPinned) {
            return 1;
        }

        // Pinned items stay stable within a normal lexical sort and ignore scoring.
        if (!lPinned && !rPinned) {
            if (mStats != null) {
                final ScoredTarget lhsTarget = mScoredTargets.get(new ComponentName(
                        lhs.activityInfo.packageName, lhs.activityInfo.name));
                final ScoredTarget rhsTarget = mScoredTargets.get(new ComponentName(
                        rhs.activityInfo.packageName, rhs.activityInfo.name));

                final int selectProbDiff = Float.compare(
                        rhsTarget.selectProb, lhsTarget.selectProb);

                if (selectProbDiff != 0) {
                    return selectProbDiff > 0 ? 1 : -1;
                }
            }
        }

        CharSequence  sa = lhs.loadLabel(mPm);
        if (sa == null) sa = lhs.activityInfo.name;
        CharSequence  sb = rhs.loadLabel(mPm);
        if (sb == null) sb = rhs.activityInfo.name;

        return mCollator.compare(sa.toString().trim(), sb.toString().trim());
    }

    public float getScore(ComponentName name) {
        final ScoredTarget target = mScoredTargets.get(name);
        if (target != null) {
            return target.selectProb;
        }
        return 0;
    }

    static class ScoredTarget {
        public final ComponentInfo componentInfo;
        public long lastTimeUsed;
        public long timeSpent;
        public long launchCount;
        public long chooserCount;
        public ArrayMap<String, Float> features;
        public float selectProb;

        public ScoredTarget(ComponentInfo ci) {
            componentInfo = ci;
            features = new ArrayMap<>(5);
        }

        @Override
        public String toString() {
            return "ScoredTarget{" + componentInfo
                    + " lastTimeUsed: " + lastTimeUsed
                    + " timeSpent: " + timeSpent
                    + " launchCount: " + launchCount
                    + " chooserCount: " + chooserCount
                    + " selectProb: " + selectProb
                    + "}";
        }

        public void setFeatures(float launchCountScore, float usageTimeScore, float recencyScore,
                                float chooserCountScore) {
            features.put(LAUNCH_SCORE, launchCountScore);
            features.put(TIME_SPENT_SCORE, usageTimeScore);
            features.put(RECENCY_SCORE, recencyScore);
            features.put(CHOOSER_SCORE, chooserCountScore);
        }

        public ArrayMap<String, Float> getFeatures() {
            return features;
        }
    }

    public void updateModel(ComponentName componentName) {
        if (mScoredTargets == null || componentName == null ||
                !mScoredTargets.containsKey(componentName)) {
            return;
        }
        ScoredTarget selected = mScoredTargets.get(componentName);
        for (ComponentName targetComponent : mScoredTargets.keySet()) {
            if (targetComponent.equals(componentName)) {
                continue;
            }
            ScoredTarget target = mScoredTargets.get(targetComponent);
            // A potential point of optimization. Save updates or derive a closed form for the
            // positive case, to avoid calculating them repeatedly.
            if (target.selectProb >= selected.selectProb) {
                mRanker.update(target.getFeatures(), target.selectProb, false);
                mRanker.update(selected.getFeatures(), selected.selectProb, true);
            }
        }
        mRanker.commitUpdate();
    }

    class LogisticRegressionAppRanker {
        private static final String PARAM_SHARED_PREF_NAME = "resolver_ranker_params";
        private static final String BIAS_PREF_KEY = "bias";
        private static final float LEARNING_RATE = 0.02f;
        private static final float REGULARIZER_PARAM = 0.1f;
        private SharedPreferences mParamSharedPref;
        private ArrayMap<String, Float> mFeatureWeights;
        private float mBias;

        public LogisticRegressionAppRanker(Context context) {
            mParamSharedPref = getParamSharedPref(context);
        }

        public float predict(ArrayMap<String, Float> target) {
            if (target == null || mParamSharedPref == null) {
                return 0.0f;
            }
            final int featureSize = target.size();
            if (featureSize == 0) {
                return 0.0f;
            }
            float sum = 0.0f;
            if (mFeatureWeights == null) {
                mBias = mParamSharedPref.getFloat(BIAS_PREF_KEY, 0.0f);
                mFeatureWeights = new ArrayMap<>(featureSize);
                for (int i = 0; i < featureSize; i++) {
                    String featureName = target.keyAt(i);
                    float weight = mParamSharedPref.getFloat(featureName, 0.0f);
                    sum += weight * target.valueAt(i);
                    mFeatureWeights.put(featureName, weight);
                }
            } else {
                for (int i = 0; i < featureSize; i++) {
                    String featureName = target.keyAt(i);
                    float weight = mFeatureWeights.getOrDefault(featureName, 0.0f);
                    sum += weight * target.valueAt(i);
                }
            }
            return (float) (1.0 / (1.0 + Math.exp(-mBias - sum)));
        }

        public void update(ArrayMap<String, Float> target, float predict, boolean isSelected) {
            if (target == null || target.size() == 0) {
                return;
            }
            final int featureSize = target.size();
            if (mFeatureWeights == null) {
                mBias = 0.0f;
                mFeatureWeights = new ArrayMap<>(featureSize);
            }
            float error = isSelected ? 1.0f - predict : -predict;
            for (int i = 0; i < featureSize; i++) {
                String featureName = target.keyAt(i);
                float currentWeight = mFeatureWeights.getOrDefault(featureName, 0.0f);
                mBias += LEARNING_RATE * error;
                currentWeight = currentWeight - LEARNING_RATE * REGULARIZER_PARAM * currentWeight +
                        LEARNING_RATE * error * target.valueAt(i);
                mFeatureWeights.put(featureName, currentWeight);
            }
            if (DEBUG) {
                Log.d(TAG, "Weights: " + mFeatureWeights + " Bias: " + mBias);
            }
        }

        public void commitUpdate() {
            if (mFeatureWeights == null || mFeatureWeights.size() == 0) {
                return;
            }
            SharedPreferences.Editor editor = mParamSharedPref.edit();
            editor.putFloat(BIAS_PREF_KEY, mBias);
            final int size = mFeatureWeights.size();
            for (int i = 0; i < size; i++) {
                editor.putFloat(mFeatureWeights.keyAt(i), mFeatureWeights.valueAt(i));
            }
            editor.apply();
        }

        private SharedPreferences getParamSharedPref(Context context) {
            // The package info in the context isn't initialized in the way it is for normal apps,
            // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
            // build the path manually below using the same policy that appears in ContextImpl.
            if (DEBUG) {
                Log.d(TAG, "Context Package Name: " + context.getPackageName());
            }
            final File prefsFile = new File(new File(
                    Environment.getDataUserCePackageDirectory(StorageManager.UUID_PRIVATE_INTERNAL,
                            context.getUserId(), context.getPackageName()),
                    "shared_prefs"),
                    PARAM_SHARED_PREF_NAME + ".xml");
            return context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
        }
    }
}
