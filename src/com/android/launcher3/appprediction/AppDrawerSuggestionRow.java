/*
 * Copyright (C) 2026 Solina-Projects
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
package com.android.launcher3.appprediction;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.launcher3.Launcher;
import com.android.launcher3.allapps.FloatingHeaderRow;
import com.android.launcher3.allapps.FloatingHeaderView;
import com.android.launcher3.model.data.ItemInfo;
import com.android.systemui.plugins.AllAppsRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the App Drawer suggestion strip as an independent floating-header component.
 *
 * Prediction data is supplied by Launcher.bindPredictedContainerInfo; this class only adapts
 * the existing suggestion view to the all-apps row lifecycle.
 */
public final class AppDrawerSuggestionRow implements AllAppsRow {

    private PredictionRowView<Launcher> mView;
    private OnHeightUpdatedListener mHeightUpdatedListener;
    private final List<ItemInfo> mPendingItems = new ArrayList<>();
    private boolean mHasPendingUpdate;

    @Override
    public View setup(ViewGroup parent) {
        if (mView == null) {
            Launcher launcher = (Launcher) parent.getContext();
            mView = new PredictionRowView<>(launcher);
            float density = parent.getResources().getDisplayMetrics().density;
            int margin = Math.round(app.lawnchair.preferences2.PreferenceCacheExtensionsKt.firstCached(
                    app.lawnchair.preferences2.PreferenceManager2.getInstance(launcher)
                            .getSuggestionMargin()) * density);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.setMargins(0, margin, 0, margin);
            mView.setLayoutParams(layoutParams);
            mView.setup((FloatingHeaderView) parent, FloatingHeaderRow.NO_ROWS, false);
        }
        return mView;
    }

    @Override
    public int getExpectedHeight() {
        if (mView == null || mView.getVisibility() == View.GONE) {
            return 0;
        }
        float density = mView.getResources().getDisplayMetrics().density;
        int margin = Math.round(app.lawnchair.preferences2.PreferenceCacheExtensionsKt.firstCached(
                app.lawnchair.preferences2.PreferenceManager2.getInstance(mView.getContext())
                        .getSuggestionMargin()) * density);
        return mView.getExpectedHeight() + margin * 2;
    }

    @Override
    public void setOnHeightUpdatedListener(OnHeightUpdatedListener listener) {
        mHeightUpdatedListener = listener;
        if (mView != null && mHasPendingUpdate) {
            mHasPendingUpdate = false;
            mView.setPredictedApps(new ArrayList<>(mPendingItems));
            mPendingItems.clear();
        }
    }

    public void setPredictedApps(List<ItemInfo> items) {
        if (mView == null) {
            mPendingItems.clear();
            mPendingItems.addAll(items);
            mHasPendingUpdate = true;
            return;
        }
        mView.setPredictedApps(items);
        if (mHeightUpdatedListener != null) {
            mHeightUpdatedListener.onHeightUpdated();
        }
    }
}
