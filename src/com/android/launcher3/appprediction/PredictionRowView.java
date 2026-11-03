/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import app.lawnchair.preferences2.PreferenceManager2;
import app.lawnchair.theme.color.ColorOption;
import app.lawnchair.theme.color.tokens.ColorTokens;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.FloatingHeaderRow;
import com.android.launcher3.allapps.FloatingHeaderView;
import com.android.launcher3.anim.AlphaUpdateListener;
import com.android.launcher3.keyboard.FocusIndicatorHelper;
import com.android.launcher3.keyboard.FocusIndicatorHelper.SimpleFocusIndicatorHelper;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.views.ActivityContext;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PredictionRowView<T extends Context & ActivityContext>
        extends LinearLayout implements OnDeviceProfileChangeListener, FloatingHeaderRow {

    private final T mActivityContext;
    private int mNumPredictedAppsPerRow;
    // Vertical padding of the icon that contributes to the expected cell height.
    private final int mVerticalPadding;
    // Extra padding that is used in the top app rows (prediction and search) that is not used in
    // the regular A-Z list.
    private final int mTopRowExtraHeight;

    // Helper to drawing the focus indicator.
    private final FocusIndicatorHelper mFocusHelper;

    // The set of predicted apps resolved from the component names and the current set of apps
    private final List<WorkspaceItemInfo> mPredictedApps = new ArrayList<>();
    private final List<LinearLayout> mPredictionRows = new ArrayList<>();
    private final List<BubbleTextView> mPredictionIcons = new ArrayList<>();

    private FloatingHeaderView mParent;

    private boolean mPredictionsEnabled = false;

    private boolean mPredictionUiUpdatePaused = false;

    private final PreferenceManager2 prefs2 = PreferenceManager2.getInstance(getContext());
    private Runnable mStandaloneHeightChangeListener;
    // True for the legacy row inflated from floating_header_content.xml when the drawer hosts
    // its own independent suggestion component; such a row must stay empty and take no space.
    private boolean mHeaderRowDisabled = false;

    /** Permanently hides this row so it never duplicates the independent suggestion strip. */
    public void setHeaderRowDisabled(boolean disabled) {
        mHeaderRowDisabled = disabled;
        if (disabled) {
            removeAllViews();
            mPredictionRows.clear();
            mPredictionIcons.clear();
            mPredictedApps.clear();
            mPredictionsEnabled = false;
        }
        updateVisibility();
        notifyExpectedHeightChanged();
    }

    /** Receives size changes when this row is hosted outside the floating header. */
    public void setStandaloneHeightChangeListener(@Nullable Runnable listener) {
        mStandaloneHeightChangeListener = listener;
    }

    private void notifyExpectedHeightChanged() {
        if (mParent != null) {
            mParent.onHeightUpdated();
        } else {
            requestLayout();
            if (mStandaloneHeightChangeListener != null) {
                mStandaloneHeightChangeListener.run();
            }
        }
    }

    public PredictionRowView(@NonNull Context context) {
        this(context, null);
    }

    public PredictionRowView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(LinearLayout.VERTICAL);

        mFocusHelper = new SimpleFocusIndicatorHelper(this);
        mActivityContext = ActivityContext.lookupContext(context);
        mNumPredictedAppsPerRow = mActivityContext.getDeviceProfile().numShownAllAppsColumns;
        mTopRowExtraHeight = getResources().getDimensionPixelSize(
                R.dimen.all_apps_search_top_row_extra_height);
        mVerticalPadding = getResources().getDimensionPixelSize(
                R.dimen.all_apps_predicted_icon_vertical_padding);
        updateVisibility();
    }

    private void applySuggestionStyle() {
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionPadding()) * density);
        setPadding(padding, padding, padding, padding);
        if (!PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionBackgroundEnabled())) {
            setBackground(null);
            return;
        }
        ColorOption backgroundOption = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionBackgroundColor());
        int color = Color.TRANSPARENT;
        if (backgroundOption != null && backgroundOption.isSupported()) {
            color = backgroundOption.getColorPreferenceEntry()
                    .getLightColor().invoke(getContext());
        } else {
            color = ColorTokens.AllAppsScrimColor.resolveColor(getContext());
        }
        float alpha = Math.max(0f, Math.min(1f, PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionBackgroundAlpha())));
        color = Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionCornerRadius()) * density);
        setBackground(drawable);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE) {
            info.setContainerTitle(mActivityContext.getString(R.string.title_app_suggestions));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mActivityContext.addOnDeviceProfileChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mActivityContext.removeOnDeviceProfileChangeListener(this);
    }

    public void setup(FloatingHeaderView parent, FloatingHeaderRow[] rows, boolean tabsHidden) {
        mParent = parent;
        applySuggestionStyle();
    }

    private void updateVisibility() {
        boolean enabled = !mHeaderRowDisabled && mPredictionsEnabled && PreferenceCacheExtensionsKt.firstCached(prefs2.getShowSuggestedAppsInDrawer());
        setVisibility(enabled ? VISIBLE : GONE);
        if (mActivityContext.getAppsView() != null) {
            if (enabled) {
                mActivityContext.getAppsView().getAppsStore().registerIconContainer(this);
            } else {
                mActivityContext.getAppsView().getAppsStore().unregisterIconContainer(this);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(getExpectedHeight(),
                MeasureSpec.EXACTLY));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mFocusHelper.draw(canvas);
        super.dispatchDraw(canvas);
    }

    /** Height of one suggestion row, scaled by the independent row-height setting. */
    private int getSingleRowHeight() {
        DeviceProfile deviceProfile = mActivityContext.getDeviceProfile();
        int iconHeight = deviceProfile.getAllAppsProfile().getIconSizePx();
        int iconPadding = deviceProfile.getAllAppsProfile().getIconDrawablePaddingPx();
        boolean labelsVisible = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionLabelsVisible());
        float labelScale = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionLabelScale());
        int textHeight = labelsVisible ? Utilities.calculateTextHeight(
                Math.round(deviceProfile.getAllAppsProfile().getIconTextSizePx() * labelScale)) : 0;
        int totalHeight = iconHeight + iconPadding + textHeight + mVerticalPadding * 2;
        // Keep the existing prediction-row spacing, multiplied by the configured row count.
        int extraHeight = deviceProfile.inv.enableTwoLinesInAllApps
                ? (textHeight + mTopRowExtraHeight) : mTopRowExtraHeight;
        totalHeight += extraHeight;
        float heightFactor = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionRowHeightFactor());
        return Math.max(1, Math.round(totalHeight * heightFactor));
    }

    @Override
    public int getExpectedHeight() {
        int rows = mPredictionRows.size();
        return mHeaderRowDisabled || getVisibility() == GONE || rows == 0 ? 0
                : getSingleRowHeight() * rows + getPaddingTop() + getPaddingBottom();
    }

    @Override
    public boolean shouldDraw() {
        return getVisibility() != GONE;
    }

    @Override
    public boolean hasVisibleContent() {
        return mPredictionsEnabled;
    }

    @Override
    public boolean isVisible() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Returns the predicted apps.
     */
    public List<ItemInfoWithIcon> getPredictedApps() {
        return new ArrayList<>(mPredictedApps);
    }

    /**
     * Sets the current set of predicted apps.
     *
     * This can be called before we get the full set of applications, we should merge the results
     * only in onPredictionsUpdated() which is idempotent.
     *
     * If the number of predicted apps is the same as the previous list of predicted apps,
     * we can optimize by swapping them in place.
     */
    public void setPredictedApps(List<ItemInfo> items) {
        if (mHeaderRowDisabled) {
            return;
        }
        applyPredictedApps(items);
    }

    private void applyPredictedApps(List<ItemInfo> items) {
        mPredictedApps.clear();
        mPredictedApps.addAll(items.stream()
                .filter(itemInfo -> itemInfo instanceof WorkspaceItemInfo)
                .map(itemInfo -> (WorkspaceItemInfo) itemInfo).collect(Collectors.toList()));
        applyPredictionApps();
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        mNumPredictedAppsPerRow = dp.numShownAllAppsColumns;
        removeAllViews();
        mPredictionRows.clear();
        mPredictionIcons.clear();
        applyPredictionApps();
    }

    /** Pause the prediction row UI update */
    public void setPredictionUiUpdatePaused(boolean predictionUiUpdatePaused) {
        mPredictionUiUpdatePaused = predictionUiUpdatePaused;
        if (!mPredictionUiUpdatePaused) {
            applyPredictionApps();
        }
    }

    private void applyPredictionApps() {
        if (mPredictionUiUpdatePaused || mHeaderRowDisabled) {
            return;
        }
        int configuredRows = Math.max(1, Math.min(3,
                PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionRows())));
        int columns = Math.max(1, mNumPredictedAppsPerRow);
        int predictionCount = mPredictedApps.size();
        int rows = Math.min(configuredRows, (predictionCount + columns - 1) / columns);
        if (rows == 0) {
            removeAllViews();
            mPredictionRows.clear();
            mPredictionIcons.clear();
            if (mPredictionsEnabled) {
                mPredictionsEnabled = false;
                updateVisibility();
            }
            notifyExpectedHeightChanged();
            return;
        }
        if (mPredictionRows.size() != rows || mPredictionIcons.size() != rows * columns) {
            removeAllViews();
            mPredictionRows.clear();
            mPredictionIcons.clear();
            LayoutInflater inflater = mActivityContext.getAppsView().getLayoutInflater();
            for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, getSingleRowHeight()));
                mPredictionRows.add(row);
                for (int column = 0; column < columns; column++) {
                    BubbleTextView icon = (BubbleTextView) inflater.inflate(
                            R.layout.all_apps_prediction_row_icon, row, false);
                    icon.setOnClickListener(mActivityContext.getItemOnClickListener());
                    icon.setOnLongClickListener(mActivityContext.getAllAppsItemLongClickListener());
                    icon.setLongPressTimeoutFactor(1f);
                    icon.setOnFocusChangeListener(mFocusHelper);
                    icon.setIconSizeFactor(PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionIconScale()));
                    icon.setTextSizeFactor(PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionLabelScale()));
                    icon.setTextVisibility(PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionLabelsVisible()));
                    ColorOption labelColor = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionLabelColor());
                    if (labelColor != null && labelColor.isSupported()) {
                        icon.setTextColor(labelColor.getColorPreferenceEntry()
                                .getLightColor().invoke(getContext()));
                    }
                    LayoutParams lp = (LayoutParams) icon.getLayoutParams();
                    // The row owns the height (scaled by the row-height setting).
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.width = 0;
                    lp.weight = 1;
                    row.addView(icon, lp);
                    mPredictionIcons.add(icon);
                }
            }
        }

        int rowHeight = getSingleRowHeight();
        for (LinearLayout row : mPredictionRows) {
            ViewGroup.LayoutParams rowParams = row.getLayoutParams();
            if (rowParams != null && rowParams.height != rowHeight) {
                rowParams.height = rowHeight;
                row.setLayoutParams(rowParams);
            }
        }
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            for (int column = 0; column < columns; column++) {
                int iconIndex = rowIndex * columns + column;
                // SuggestionGridLayout returns rows in visual top-to-bottom order.
                int predictionIndex = rowIndex * columns + column;
                BubbleTextView icon = mPredictionIcons.get(iconIndex);
                icon.reset();
                if (predictionCount > predictionIndex) {
                    icon.setVisibility(View.VISIBLE);
                    WorkspaceItemInfo predictedItem = mPredictedApps.get(predictionIndex);
                    predictedItem.rank = predictionIndex;
                    predictedItem.cellX = column;
                    predictedItem.cellY = rowIndex;
                    icon.applyFromWorkspaceItem(predictedItem);
                } else {
                    icon.setVisibility(predictionCount == 0 ? GONE : INVISIBLE);
                }
            }
        }

        boolean predictionsEnabled = predictionCount > 0;
        if (predictionsEnabled != mPredictionsEnabled) {
            mPredictionsEnabled = predictionsEnabled;
            updateVisibility();
        }
        notifyExpectedHeightChanged();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }


    @Override
    public void setVerticalScroll(int scroll, boolean isScrolledOut) {
        if (!isScrolledOut) {
            setTranslationY(scroll);
        }
        setAlpha(isScrolledOut ? 0 : 1);
        if (getVisibility() != GONE) {
            AlphaUpdateListener.updateVisibility(this);
        }
    }

    @Override
    public Class<PredictionRowView> getTypeClass() {
        return PredictionRowView.class;
    }

    @Override
    public View getFocusedChild() {
        return mPredictionIcons.isEmpty() ? null : mPredictionIcons.get(0);
    }

    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "PredictionRowView");
        writer.println(prefix + "\tmPredictionsEnabled: " + mPredictionsEnabled);
        writer.println(prefix + "\tmPredictionUiUpdatePaused: " + mPredictionUiUpdatePaused);
        writer.println(prefix + "\tmNumPredictedAppsPerRow: " + mNumPredictedAppsPerRow);
        writer.println(prefix + "\tmSuggestionRows: " + mPredictionRows.size());
        writer.println(prefix + "\tmPredictedApps: " + mPredictedApps.size());
        for (WorkspaceItemInfo info : mPredictedApps) {
            writer.println(prefix + "\t\t" + info);
        }
    }
}
