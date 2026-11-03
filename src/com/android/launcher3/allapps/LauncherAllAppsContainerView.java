/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps;

import android.content.Context;
import android.util.AttributeSet;
import android.view.WindowInsets;
import android.widget.RelativeLayout;

import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import app.lawnchair.preferences2.PreferenceManager2;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.Utilities;
import com.android.launcher3.appprediction.AppDrawerSuggestionRow;
import com.android.launcher3.appprediction.PredictionRowView;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.statemanager.StateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * AllAppsContainerView with launcher specific callbacks
 */
public class LauncherAllAppsContainerView extends ActivityAllAppsContainerView<Launcher> {

    public LauncherAllAppsContainerView(Context context) {
        this(context, null);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // This view can be queried by the superclass constructor before subclass field
    // initializers run. Keep the row lazily initialized and do not assign it at declaration.
    private AppDrawerSuggestionRow mSuggestionRow;
    private final List<ItemInfo> mLatestSuggestedApps = new ArrayList<>();
    private PredictionRowView<Launcher> mSuggestionFooterView;

    private AppDrawerSuggestionRow getSuggestionRow() {
        if (mSuggestionRow == null) {
            mSuggestionRow = new AppDrawerSuggestionRow();
        }
        return mSuggestionRow;
    }

    private String getSuggestionPosition() {
        return PreferenceCacheExtensionsKt.firstCached(
                PreferenceManager2.getInstance(getContext()).getSuggestionPosition());
    }

    private boolean isSuggestionPositionBottom() {
        return "bottom".equals(getSuggestionPosition());
    }

    @Override
    public List<com.android.systemui.plugins.AllAppsRow> getAdditionalHeaderRows() {
        return isSuggestionPositionBottom() ? List.of() : List.of(getSuggestionRow());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // floating_header_content.xml still inflates a legacy prediction row. The independent
        // suggestion component below replaces it, so keep the legacy row empty to avoid a
        // second, duplicated strip.
        PredictionRowView<?> legacyRow = getFloatingHeaderView()
                .findFixedRowByType(PredictionRowView.class);
        if (legacyRow != null) {
            legacyRow.setHeaderRowDisabled(true);
        }
        if (!isSuggestionPositionBottom()) {
            return;
        }
        mSuggestionFooterView = new PredictionRowView<>((Launcher) getContext());
        mSuggestionFooterView.setup(null, FloatingHeaderRow.NO_ROWS, false);
        attachSuggestionFooterView(mSuggestionFooterView);
        RelativeLayout.LayoutParams layoutParams =
                (RelativeLayout.LayoutParams) mSuggestionFooterView.getLayoutParams();
        int margin = Math.round(PreferenceCacheExtensionsKt.firstCached(
                PreferenceManager2.getInstance(getContext()).getSuggestionMargin())
                * getResources().getDisplayMetrics().density);
        layoutParams.topMargin = margin;
        mSuggestionFooterView.setLayoutParams(layoutParams);
        mSuggestionFooterView.setStandaloneHeightChangeListener(this::updateSuggestionFooterHeight);
        mSuggestionFooterView.setPredictedApps(mLatestSuggestedApps);
        updateSuggestionFooterHeight();
    }

    /** Returns the independent suggestion view for the active position, or null if not ready. */
    @androidx.annotation.Nullable
    public PredictionRowView<Launcher> getSuggestionView() {
        return isSuggestionPositionBottom() ? mSuggestionFooterView : getSuggestionRow().getView();
    }

    /** Updates the independent suggestion component with the latest model prediction items. */
    public void setSuggestedApps(List<ItemInfo> items) {
        mLatestSuggestedApps.clear();
        mLatestSuggestedApps.addAll(items);
        if (isSuggestionPositionBottom()) {
            if (mSuggestionFooterView != null) {
                mSuggestionFooterView.setPredictedApps(mLatestSuggestedApps);
                updateSuggestionFooterHeight();
            }
        } else {
            getSuggestionRow().setPredictedApps(mLatestSuggestedApps);
        }
    }

    private void updateSuggestionFooterHeight() {
        if (mSuggestionFooterView == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int rowHeight = mSuggestionFooterView.getExpectedHeight();
        int margin = rowHeight == 0 ? 0 : Math.round(PreferenceCacheExtensionsKt.firstCached(
                PreferenceManager2.getInstance(getContext()).getSuggestionMargin()) * density);
        setSuggestionFooterOuterMargin(margin);
        setSuggestionFooterHeight(rowHeight + margin);
    }

    @Override
    protected int computeNavBarScrimHeight(WindowInsets insets) {
        return Utilities.ATLEAST_Q ? insets.getTappableElementInsets().bottom : insets.getStableInsetBottom();
    }

    @Override
    public boolean isInAllApps() {
        return mActivityContext.getStateManager().isInStableState(LauncherState.ALL_APPS);
    }

    @Override
    public boolean shouldFloatingSearchBarBePillWhenUnfocused() {
        if (!isSearchBarFloating()) {
            return false;
        }
        Launcher launcher = mActivityContext;
        StateManager<LauncherState, Launcher> manager = launcher.getStateManager();
        if (manager.isInTransition() && manager.getTargetState() != null) {
            return manager.getTargetState().shouldFloatingSearchBarUsePillWhenUnfocused(launcher);
        }
        return manager.getCurrentStableState()
                .shouldFloatingSearchBarUsePillWhenUnfocused(launcher);
    }

    @Override
    public int getFloatingSearchBarRestingMarginBottom() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginBottom();
        }
        Launcher launcher = mActivityContext;
        StateManager<LauncherState, Launcher> stateManager = launcher.getStateManager();

        // We want to rest at the current state's resting position, unless we are in transition and
        // the target state's resting position is higher (that way if we are closing the keyboard,
        // we can stop translating at that point).
        int currentStateMarginBottom = stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginBottom(launcher);
        int targetStateMarginBottom = -1;
        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            targetStateMarginBottom = stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginBottom(launcher);
            if (targetStateMarginBottom < 0) {
                // Go ahead and move offscreen.
                return targetStateMarginBottom;
            }
        }
        return Math.max(targetStateMarginBottom, currentStateMarginBottom);
    }

    @Override
    public int getFloatingSearchBarRestingMarginStart() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginStart();
        }

        StateManager<LauncherState, Launcher> stateManager = mActivityContext.getStateManager();

        // Special case to not expand the search bar when exiting All Apps on phones.
        if (stateManager.getCurrentStableState() == LauncherState.ALL_APPS
                && mActivityContext.getDeviceProfile().getDeviceProperties().isPhone()) {
            return LauncherState.ALL_APPS.getFloatingSearchBarRestingMarginStart(mActivityContext);
        }

        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            return stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginStart(mActivityContext);
        }
        return stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginStart(mActivityContext);
    }

    @Override
    public int getFloatingSearchBarRestingMarginEnd() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginEnd();
        }

        StateManager<LauncherState, Launcher> stateManager = mActivityContext.getStateManager();

        // Special case to not expand the search bar when exiting All Apps on phones.
        if (stateManager.getCurrentStableState() == LauncherState.ALL_APPS
                && mActivityContext.getDeviceProfile().getDeviceProperties().isPhone()) {
            return LauncherState.ALL_APPS.getFloatingSearchBarRestingMarginEnd(mActivityContext);
        }

        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            return stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginEnd(mActivityContext);
        }
        return stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginEnd(mActivityContext);
    }
}
