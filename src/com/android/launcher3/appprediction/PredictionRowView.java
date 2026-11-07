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
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.util.TypedValue;
import android.widget.TextView;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import app.lawnchair.glass.LiquidGlassStyleHelper;
import app.lawnchair.icons.shape.IconShape;
import app.lawnchair.icons.shape.SuggestionIconShapeHelper;
import app.lawnchair.preferences2.PreferenceCacheExtensionsKt;
import app.lawnchair.preferences2.PreferenceManager2;
import app.lawnchair.theme.color.ColorOption;
import app.lawnchair.theme.color.GoogleRainbowTheme;
import app.lawnchair.theme.color.tokens.ColorTokens;
import app.lawnchair.util.OutlineStyleRenderer;
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
    // The raised card holding the suggestion rows, and the marker that separates it from the
    // regular app list (below the card at the top position, above the card at the bottom).
    private final LinearLayout mCard;
    private final SectionDivider mHeader;
    private final SectionDivider mMarker;
    private final int mDividerHeight;
    private final boolean mAtBottom;

    /** Permanently hides this row so it never duplicates the independent suggestion strip. */
    public void setHeaderRowDisabled(boolean disabled) {
        mHeaderRowDisabled = disabled;
        if (disabled) {
            setDividersVisible(false);
            mCard.removeAllViews();
            mPredictionRows.clear();
            mPredictionIcons.clear();
            mPredictedApps.clear();
            mPredictionsEnabled = false;
        }
        updateVisibility();
        notifyExpectedHeightChanged();
    }

    private void setDividersVisible(boolean visible) {
        mHeader.setVisibility(visible ? VISIBLE : GONE);
        mMarker.setVisibility(visible && !mAtBottom ? VISIBLE : GONE);
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
        mNumPredictedAppsPerRow = readConfiguredColumns();
        mTopRowExtraHeight = getResources().getDimensionPixelSize(
                R.dimen.all_apps_search_top_row_extra_height);
        mVerticalPadding = getResources().getDimensionPixelSize(
                R.dimen.all_apps_predicted_icon_vertical_padding);
        float density = getResources().getDisplayMetrics().density;
        // Keep the suggestion header compact so it does not create a large fixed gap
        // between the floating search bar and the first suggestion row.
        mDividerHeight = Math.round(18 * density);
        boolean atBottom = "bottom".equals(
                PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionPosition()));
        mAtBottom = atBottom;
        mCard = new LinearLayout(context) {
            private final RectF mOutlineRect = new RectF();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionOutlineEnabled())) {
                    float d = getResources().getDisplayMetrics().density;
                    float strokeWidthDp = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionOutlineWidthDp());
                    float radiusPx = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionCornerRadius()) * d;
                    String outlineStyle = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionOutlineStyle());
                    ColorOption outlineColorOpt = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionOutlineColor());
                    int outlineColor = OutlineStyleRenderer.resolveOutlineColor(
                            getContext(), outlineColorOpt);
                    mOutlineRect.set(0f, 0f, getWidth(), getHeight());
                    OutlineStyleRenderer.drawOutline(
                            canvas,
                            mOutlineRect,
                            radiusPx,
                            outlineStyle,
                            strokeWidthDp,
                            outlineColor,
                            d,
                            1f
                    );
                }
            }
        };
        mCard.setOrientation(LinearLayout.VERTICAL);
        mHeader = new SectionDivider(context,
                context.getString(R.string.suggestion_frequently_used), SectionDivider.NO_ARROW);
        mMarker = new SectionDivider(context,
                context.getString(R.string.all_apps_label), SectionDivider.ARROW_DOWN);
        mHeader.setVisibility(GONE);
        mMarker.setVisibility(GONE);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(mHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mDividerHeight));
        addView(mCard, cardParams);
        addView(mMarker, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mDividerHeight));
        updateVisibility();
    }

    private void applySuggestionStyle() {
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionPadding()) * density);
        if (!PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionBackgroundEnabled())) {
            mCard.setPadding(padding, padding, padding, padding);
            mCard.setBackground(null);
            return;
        }
        ColorOption backgroundOption = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionBackgroundColor());
        int color;
        if (backgroundOption != null && backgroundOption.isSupported()) {
            color = backgroundOption.getColorPreferenceEntry()
                    .getLightColor().invoke(getContext());
        } else {
            // Default: an accent tint so the strip stands apart from the rest of the drawer.
            color = ColorTokens.SearchResultSmallIcon.resolveColor(getContext());
        }
        float alpha = Math.max(0f, Math.min(1f, PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionBackgroundAlpha())));
        int baseAlpha = Math.round(255 * alpha);
        float radius = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionCornerRadius()) * density;
        if (GoogleRainbowTheme.isGoogleRainbowColor(color)) {
            mCard.setPadding(padding, padding, padding, padding);
            GradientDrawable rainbowDrawable =
                    GoogleRainbowTheme.createRainbowGradientDrawable(radius, baseAlpha);
            rainbowDrawable.setStroke(
                    Math.max(1, Math.round(1.2f * density)),
                    withAlpha(Color.WHITE, Math.round(baseAlpha * 0.65f))
            );
            mCard.setBackground(rainbowDrawable);
            return;
        }
        if (LiquidGlassStyleHelper.shouldUseForSuggestions(getContext())) {
            mCard.setPadding(padding, padding, padding, padding);
            mCard.setBackground(LiquidGlassStyleHelper.createDrawable(
                    getContext(),
                    radius,
                    color,
                    Math.max(0.18f, alpha)));
            return;
        }
        int thickness = Math.round(4 * density);
        // The raised edge sits under the face, so reserve its height at the bottom.
        mCard.setPadding(padding, padding, padding, padding + thickness);
        mCard.setBackground(build3dBackground(color, baseAlpha, radius, thickness, density));
    }

    private static int withAlpha(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    /**
     * A raised, three-dimensional card: a lit gradient face, a bright rim and a darker
     * bottom edge that gives the strip visible thickness.
     */
    private static Drawable build3dBackground(int color, int alpha, float radius,
            int thickness, float density) {
        int opaque = withAlpha(color, 255);
        int faceTop = withAlpha(ColorUtils.blendARGB(opaque, Color.WHITE, 0.25f), alpha);
        int faceBottom = withAlpha(ColorUtils.blendARGB(opaque, Color.BLACK, 0.08f), alpha);
        int edge = withAlpha(ColorUtils.blendARGB(opaque, Color.BLACK, 0.38f), alpha);
        int rim = withAlpha(Color.WHITE, Math.round(alpha * 0.55f));

        GradientDrawable edgeLayer = new GradientDrawable();
        edgeLayer.setColor(edge);
        edgeLayer.setCornerRadius(radius);

        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{faceTop, faceBottom});
        face.setCornerRadius(radius);
        face.setStroke(Math.max(1, Math.round(density)), rim);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{edgeLayer, face});
        layers.setLayerInset(1, 0, 0, 0, thickness);
        return layers;
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

    /** Independent outer spacing (dp setting converted to px) around this strip. */
    public int getOuterMarginPx() {
        return Math.round(PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionMargin())
                * getResources().getDisplayMetrics().density);
    }

    // The strip owns its sizing: nothing below reads the app drawer's icon, label or grid size
    // settings, so only the suggestion settings change how large the suggestions are.
    private static final float BASE_ICON_DP = 70f;
    private static final float BASE_LABEL_SP = 12f;
    private static final float BASE_LABEL_GAP_DP = 4f;

    /** Icons per suggestion row; independent from the app drawer grid. */
    private int readConfiguredColumns() {
        Integer configured = PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionColumns());
        return Math.max(2, Math.min(8, configured == null ? 4 : configured));
    }

    private int getSuggestionIconSizePx() {
        float scale = PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionIconScale());
        int size = Math.max(1, Math.round(BASE_ICON_DP
                * getResources().getDisplayMetrics().density * scale));
        // An icon can never be drawn wider than its column without being clipped.
        int columnWidth = mActivityContext.getDeviceProfile().availableWidthPx
                / Math.max(1, readConfiguredColumns());
        return columnWidth > 0 ? Math.min(size, columnWidth) : size;
    }

    private float getSuggestionTextSizePx() {
        return Utilities.pxFromSp(BASE_LABEL_SP, getResources().getDisplayMetrics())
                * PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionLabelScale());
    }

    private int getSuggestionDrawablePaddingPx() {
        float density = getResources().getDisplayMetrics().density;
        float labelOffsetDp = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionLabelVerticalOffset());
        return Math.max(0, Math.round((BASE_LABEL_GAP_DP + labelOffsetDp) * density));
    }

    private boolean areLabelsVisible() {
        return PreferenceCacheExtensionsKt.firstCached(prefs2.getSuggestionLabelsVisible());
    }

    /** Height needed to draw an icon (and its label, when shown) without clipping. */
    private int getContentHeight() {
        int height = getSuggestionIconSizePx();
        if (areLabelsVisible()) {
            height += getSuggestionDrawablePaddingPx()
                    + Utilities.calculateTextHeight(Math.round(getSuggestionTextSizePx()));
        }
        return height;
    }

    /**
     * Height of one suggestion row, scaled by the row-height setting but never smaller than its
     * content, so enlarging icons can no longer clip them.
     */
    private int getSingleRowHeight() {
        int content = getContentHeight();
        int comfortable = content + mVerticalPadding * 2 + mTopRowExtraHeight;
        float heightFactor = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionRowHeightFactor());
        return Math.max(content, Math.round(comfortable * heightFactor));
    }

    @Override
    public int getExpectedHeight() {
        int rows = mPredictionRows.size();
        return mHeaderRowDisabled || getVisibility() == GONE || rows == 0 ? 0
                : getSingleRowHeight() * rows + mCard.getPaddingTop() + mCard.getPaddingBottom()
                        + mDividerHeight * (mAtBottom ? 1 : 2);
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
        mNumPredictedAppsPerRow = readConfiguredColumns();
        mCard.removeAllViews();
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
        mNumPredictedAppsPerRow = readConfiguredColumns();
        int columns = Math.max(1, mNumPredictedAppsPerRow);
        int predictionCount = mPredictedApps.size();
        int rows = Math.min(configuredRows, (predictionCount + columns - 1) / columns);
        if (rows == 0) {
            setDividersVisible(false);
            mCard.removeAllViews();
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
            mCard.removeAllViews();
            mPredictionRows.clear();
            mPredictionIcons.clear();
            LayoutInflater inflater = mActivityContext.getAppsView().getLayoutInflater();
            for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                mCard.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, getSingleRowHeight()));
                mPredictionRows.add(row);
                for (int column = 0; column < columns; column++) {
                    BubbleTextView icon = (BubbleTextView) inflater.inflate(
                            R.layout.all_apps_prediction_row_icon, row, false);
                    icon.setOnClickListener(mActivityContext.getItemOnClickListener());
                    icon.setOnLongClickListener(mActivityContext.getAllAppsItemLongClickListener());
                    icon.setLongPressTimeoutFactor(1f);
                    icon.setOnFocusChangeListener(mFocusHelper);
                    icon.setIndependentSizing(getSuggestionIconSizePx(),
                            getSuggestionTextSizePx(), areLabelsVisible(),
                            getSuggestionDrawablePaddingPx());
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
        IconShape dedicatedSuggestionShape = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionIconShape());
        boolean cellOutlineEnabled = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionAppCellOutlineEnabled());
        float density = getResources().getDisplayMetrics().density;
        String outlineStyle = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionOutlineStyle());
        float outlineWidthDp = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionOutlineWidthDp());
        ColorOption outlineColorOpt = PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionOutlineColor());
        int outlineColor = OutlineStyleRenderer.resolveOutlineColor(getContext(), outlineColorOpt);
        float cellRadiusPx = Math.max(8f * density, PreferenceCacheExtensionsKt.firstCached(
                prefs2.getSuggestionCornerRadius()) * density * 0.65f);
        int cellInsetPx = Math.round(3f * density);
        int iconSizePx = getSuggestionIconSizePx();

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
                    SuggestionIconShapeHelper.applySuggestionIconShape(
                            icon,
                            dedicatedSuggestionShape,
                            iconSizePx
                    );
                    ColorOption labelColor = PreferenceCacheExtensionsKt.firstCached(
                            prefs2.getSuggestionLabelColor());
                    if (labelColor != null && labelColor.isSupported()) {
                        icon.setTextColor(labelColor.getColorPreferenceEntry()
                                .getLightColor().invoke(getContext()));
                    }
                    if (cellOutlineEnabled) {
                        icon.setBackground(OutlineStyleRenderer.createOutlineDrawable(
                                cellRadiusPx,
                                outlineStyle,
                                outlineWidthDp,
                                outlineColor,
                                density,
                                cellInsetPx,
                                null
                        ));
                    } else {
                        icon.setBackground(null);
                    }
                } else {
                    icon.setBackground(null);
                    icon.setVisibility(predictionCount == 0 ? GONE : INVISIBLE);
                }
            }
        }

        setDividersVisible(true);
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

    /**
     * One shared separator design used for every section break of the drawer: fading lines, a
     * small diamond at each end and a rounded, tinted label pill (with an optional chevron).
     */
    private static final class SectionDivider extends View {
        static final int NO_ARROW = 0;
        static final int ARROW_DOWN = 1;
        static final int ARROW_UP = 2;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mRect = new RectF();
        private final String mText;
        private final int mArrow;
        private final float mDensity;
        private final int mAccent;
        private final int mTextColor;

        SectionDivider(Context context, String text, int arrow) {
            super(context);
            mText = text;
            mArrow = arrow;
            mDensity = context.getResources().getDisplayMetrics().density;
            mAccent = context.getColor(R.color.materialColorPrimary);
            mTextColor = context.getColor(R.color.materialColorOnSurface);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mPaint.setTextSize(12 * context.getResources().getDisplayMetrics().scaledDensity);
            mPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cy = h / 2f;
            float d = mDensity;
            float textW = mPaint.measureText(mText);
            float arrowW = mArrow == NO_ARROW ? 0 : 14 * d;
            float padH = 14 * d;
            float pillW = textW + arrowW + padH * 2;
            float pillH = 22 * d;
            float left = (w - pillW) / 2f;
            float right = left + pillW;
            float gap = 8 * d;
            float margin = 16 * d;

            // Fading lines on both sides of the pill.
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, 1.5f * d));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setShader(new LinearGradient(margin, 0, left - gap, 0,
                    Color.TRANSPARENT, mAccent, Shader.TileMode.CLAMP));
            canvas.drawLine(margin + 10 * d, cy, left - gap, cy, mPaint);
            mPaint.setShader(new LinearGradient(right + gap, 0, w - margin, 0,
                    mAccent, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawLine(right + gap, cy, w - margin - 10 * d, cy, mPaint);
            mPaint.setShader(null);

            // Diamonds beside the pill.
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mAccent);
            drawDiamond(canvas, left - gap, cy, 3 * d);
            drawDiamond(canvas, right + gap, cy, 3 * d);

            // Pill with a soft tint and a thin accent outline.
            mRect.set(left, cy - pillH / 2f, right, cy + pillH / 2f);
            mPaint.setColor(mAccent);
            mPaint.setAlpha(36);
            canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(d);
            mPaint.setColor(mAccent);
            mPaint.setAlpha(150);
            canvas.drawRoundRect(mRect, pillH / 2f, pillH / 2f, mPaint);

            // Label.
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTextColor);
            mPaint.setAlpha(230);
            float textX = left + padH;
            Paint.FontMetrics fm = mPaint.getFontMetrics();
            canvas.drawText(mText, textX, cy - (fm.ascent + fm.descent) / 2f, mPaint);

            // Chevron pointing toward the app list.
            if (mArrow != NO_ARROW) {
                float ax = textX + textW + 7 * d;
                float s = 3.5f * d;
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(1.6f * d);
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setColor(mAccent);
                mPaint.setAlpha(255);
                mPath.reset();
                if (mArrow == ARROW_UP) {
                    mPath.moveTo(ax - s, cy + s / 2f);
                    mPath.lineTo(ax, cy - s / 2f);
                    mPath.lineTo(ax + s, cy + s / 2f);
                } else {
                    mPath.moveTo(ax - s, cy - s / 2f);
                    mPath.lineTo(ax, cy + s / 2f);
                    mPath.lineTo(ax + s, cy - s / 2f);
                }
                canvas.drawPath(mPath, mPaint);
            }
            mPaint.setAlpha(255);
        }

        private void drawDiamond(Canvas canvas, float cx, float cy, float r) {
            mPath.reset();
            mPath.moveTo(cx, cy - r);
            mPath.lineTo(cx + r, cy);
            mPath.lineTo(cx, cy + r);
            mPath.lineTo(cx - r, cy);
            mPath.close();
            canvas.drawPath(mPath, mPaint);
        }
    }
}
