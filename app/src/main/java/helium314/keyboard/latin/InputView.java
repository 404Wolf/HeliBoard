/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewKt;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.suggestions.MoreSuggestionsView;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.FloatingKeyboardUtils;
import kotlin.Unit;


public final class InputView extends FrameLayout {
    private static final float SPLIT_CARD_CORNER_RADIUS_DP = 14f;

    private final Rect mInputViewRect = new Rect();
    private MainKeyboardView mMainKeyboardView;
    private View mMainKeyboardFrame;
    // see-through gap between split keyboard halves, empty if there is none
    private final Rect mSplitGap = new Rect();
    private final Rect mSplitFrame = new Rect();
    private final Rect mTmpGap = new Rect();
    private final Path mSplitCardsPath = new Path();
    // whether the strip area above the split gap is see-through, see isStripOverSplitGapOpen
    private boolean mStripOverGapOpen;
    private SuggestionStripView mSuggestionStripView;
    private View mStripSuggestions;
    private View mStripToolbar;
    private final ViewTreeObserver.OnPreDrawListener mSplitGapUpdater = () -> {
        updateSplitCards();
        return true;
    };
    private KeyboardTopPaddingForwarder mKeyboardTopPaddingForwarder;
    private MoreSuggestionsViewCanceler mMoreSuggestionsViewCanceler;
    private MotionEventForwarder<?, ?> mActiveForwarder;

    public InputView(final Context context, final AttributeSet attrs) {
        super(context, attrs, 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final SuggestionStripView suggestionStripView =
                findViewById(R.id.suggestion_strip_view);
        mMainKeyboardView = findViewById(R.id.keyboard_view);
        mMainKeyboardFrame = findViewById(R.id.main_keyboard_frame);
        mSuggestionStripView = suggestionStripView;
        mStripSuggestions = suggestionStripView.findViewById(R.id.suggestions_strip);
        mStripToolbar = suggestionStripView.findViewById(R.id.toolbar_container);
        mKeyboardTopPaddingForwarder = new KeyboardTopPaddingForwarder(
                mMainKeyboardView, suggestionStripView);
        mMoreSuggestionsViewCanceler = new MoreSuggestionsViewCanceler(
                mMainKeyboardView, suggestionStripView);
        ViewKt.doOnNextLayout(this, this::onNextLayout);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mSplitGapUpdater);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mSplitGapUpdater);
        super.onDetachedFromWindow();
    }

    /**
     * Computes the see-through gap between the halves of a split keyboard in this view's
     * coordinates. It reaches from the top of the keys down to the bottom of this view, or to
     * the top of the first row that spans both halves.
     * @return false if there is no such gap, e.g. because the main keyboard is not shown or not split
     */
    public boolean getSplitGap(@NonNull final Rect outRect) {
        if (Settings.getValues().mIsFloatingKeyboard || !mMainKeyboardView.isShown())
            return false;
        final Keyboard keyboard = mMainKeyboardView.getKeyboard();
        if (keyboard == null || keyboard.mSplitGapLeft < 0)
            return false;
        outRect.set(0, 0, mMainKeyboardView.getWidth(), mMainKeyboardView.getHeight());
        offsetDescendantRectToMyCoords(mMainKeyboardView, outRect);
        final int keyboardLeft = outRect.left + mMainKeyboardView.getPaddingLeft();
        final int top = outRect.top;
        final int bottom = keyboard.mSplitGapBottom >= keyboard.mOccupiedHeight
                ? getHeight()
                : top + mMainKeyboardView.getPaddingTop() + keyboard.mSplitGapBottom;
        outRect.set(keyboardLeft + keyboard.mSplitGapLeft, top, keyboardLeft + keyboard.mSplitGapRight, bottom);
        return !outRect.isEmpty();
    }

    /**
     * Whether the strip area above the split gap is see-through, so the halves are two separate
     * cards: true while the strip shows nothing that spans the gap (no suggestions, no expanded
     * toolbar, nothing drawn over the strip). Only meaningful while {@link #getSplitGap} is true.
     */
    public boolean isStripOverSplitGapOpen() {
        return mStripOverGapOpen;
    }

    private boolean isStripIdle() {
        final SuggestionStripView strip = mSuggestionStripView;
        if (strip == null || !strip.isShown())
            return true;
        if (mStripSuggestions != null && mStripSuggestions.isShown()
                && mStripSuggestions instanceof ViewGroup group && hasStripContent(group))
            return false;
        if (mStripToolbar != null && mStripToolbar.isShown())
            return false;
        // anything else shown in the strip (e.g. an indicator added over it) may span the gap
        for (int i = 0; i < strip.getChildCount(); i++) {
            final View child = strip.getChildAt(i);
            if (child.getId() != R.id.suggestions_strip_wrapper && child.getVisibility() == View.VISIBLE)
                return false;
        }
        return true;
    }

    /** Words with text, or other views such as inline autofill suggestions; empty word slots and dividers don't count. */
    private static boolean hasStripContent(final ViewGroup suggestions) {
        for (int i = 0; i < suggestions.getChildCount(); i++) {
            final View child = suggestions.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE)
                continue;
            if (child instanceof TextView text) {
                if (text.getText().length() > 0)
                    return true;
            } else if (!(child instanceof ImageView)) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the clip path for the keyboard frame if the split gap, the strip or the frame changed. */
    private void updateSplitCards() {
        if (!getSplitGap(mTmpGap))
            mTmpGap.setEmpty();
        final boolean stripOpen = !mTmpGap.isEmpty() && isStripIdle();
        final View frame = mMainKeyboardFrame;
        if (mTmpGap.equals(mSplitGap) && stripOpen == mStripOverGapOpen
                && frame.getLeft() == mSplitFrame.left && frame.getTop() == mSplitFrame.top
                && frame.getRight() == mSplitFrame.right && frame.getBottom() == mSplitFrame.bottom)
            return;
        final boolean openChanged = stripOpen != mStripOverGapOpen;
        mSplitGap.set(mTmpGap);
        mStripOverGapOpen = stripOpen;
        mSplitFrame.set(frame.getLeft(), frame.getTop(), frame.getRight(), frame.getBottom());
        mSplitCardsPath.reset();
        if (!mSplitGap.isEmpty())
            buildSplitCardsPath(mSplitCardsPath, mSplitFrame, mSplitGap, stripOpen,
                    SPLIT_CARD_CORNER_RADIUS_DP * getResources().getDisplayMetrics().density);
        invalidate();
        if (openChanged)
            requestLayout(); // the touchable region (LatinIME.onComputeInsets) depends on it
    }

    /**
     * Each keyboard half is a rounded card, the gap between them stays see-through. While the
     * strip (if any) shows something, it bridges the cards above the gap, with rounded inner
     * corners where it meets the gap; while it is idle the gap reaches up to the top.
     */
    private static void buildSplitCardsPath(final Path out, final Rect f, final Rect gap,
            final boolean stripOpen, final float r) {
        final int keysTop = gap.top;
        final boolean bridge = keysTop > f.top && !stripOpen;
        out.addRoundRect(new RectF(f.left, f.top, gap.left, f.bottom), r, r, Path.Direction.CW);
        out.addRoundRect(new RectF(gap.right, f.top, f.right, f.bottom), r, r, Path.Direction.CW);
        if (gap.bottom < f.bottom) // rows below the gap span the whole width
            out.addRect(f.left, gap.bottom, f.right, f.bottom, Path.Direction.CW);
        if (!bridge)
            return;
        final Path bridgePath = new Path();
        // covers the inner top corners of both cards, plus concave fillets below it
        bridgePath.addRect(gap.left - r, f.top, gap.right + r, keysTop + r, Path.Direction.CW);
        final Path hole = new Path();
        hole.addRoundRect(new RectF(gap.left, keysTop, gap.right, Math.max(gap.bottom, keysTop + 2 * r)),
                new float[] { r, r, r, r, 0, 0, 0, 0 }, Path.Direction.CW);
        bridgePath.op(hole, Path.Op.DIFFERENCE);
        out.op(bridgePath, Path.Op.UNION);
    }

    @Override
    protected boolean drawChild(@NonNull final Canvas canvas, final View child, final long drawingTime) {
        if (child != mMainKeyboardFrame || mSplitGap.isEmpty())
            return super.drawChild(canvas, child, drawingTime);
        // don't let the keyboard background (or anything else in the frame) paint the split gap
        canvas.save();
        canvas.clipPath(mSplitCardsPath);
        final boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restore();
        return result;
    }

    public void setKeyboardTopPadding(final int keyboardTopPadding) {
        mKeyboardTopPaddingForwarder.setKeyboardTopPadding(keyboardTopPadding);
    }

    @Override
    protected boolean dispatchHoverEvent(final MotionEvent event) {
        if (AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled()
                && mMainKeyboardView.isShowingPopupKeysPanel()) {
            // With accessibility mode on, discard hover events while a popup keys keyboard is shown.
            // The {@link PopupKeysKeyboard} receives hover events directly from the platform.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent me) {
        final Rect rect = mInputViewRect;
        getGlobalVisibleRect(rect);
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index) + rect.left;
        final int y = (int)me.getY(index) + rect.top;

        // The touch events that hit the top padding of keyboard should be forwarded to
        // {@link SuggestionStripView}.
        if (mKeyboardTopPaddingForwarder.onInterceptTouchEvent(x, y, me)) {
            mActiveForwarder = mKeyboardTopPaddingForwarder;
            return true;
        }

        // To cancel {@link MoreSuggestionsView}, we should intercept a touch event to
        // {@link MainKeyboardView} and dismiss the {@link MoreSuggestionsView}.
        if (mMoreSuggestionsViewCanceler.onInterceptTouchEvent(x, y, me)) {
            mActiveForwarder = mMoreSuggestionsViewCanceler;
            return true;
        }

        mActiveForwarder = null;
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        if (mActiveForwarder == null) {
            return super.onTouchEvent(me);
        }

        final Rect rect = mInputViewRect;
        getGlobalVisibleRect(rect);
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index) + rect.left;
        final int y = (int)me.getY(index) + rect.top;
        return mActiveForwarder.onTouchEvent(x, y, me);
    }

    private Unit onNextLayout(View v) {
        Settings.getValues().mColors.setBackground(findViewById(R.id.main_keyboard_frame), ColorType.MAIN_BACKGROUND);

        // Work around inset application being unreliable
        requestApplyInsets();

        // need to update the floating keyboard position after applying insets
        if (Settings.getValues().mIsFloatingKeyboard)
            FloatingKeyboardUtils.setFloating(this);
        return null;
    }

    /**
     * This class forwards series of {@link MotionEvent}s from <code>SenderView</code> to
     * <code>ReceiverView</code>.
     *
     * @param <SenderView> a {@link View} that may send a {@link MotionEvent} to <ReceiverView>.
     * @param <ReceiverView> a {@link View} that receives forwarded {@link MotionEvent} from
     *     <SenderView>.
     */
    private static abstract class
            MotionEventForwarder<SenderView extends View, ReceiverView extends View> {
        protected final SenderView mSenderView;
        protected final ReceiverView mReceiverView;

        protected final Rect mEventSendingRect = new Rect();
        protected final Rect mEventReceivingRect = new Rect();

        public MotionEventForwarder(final SenderView senderView, final ReceiverView receiverView) {
            mSenderView = senderView;
            mReceiverView = receiverView;
        }

        // Return true if a touch event of global coordinate x, y needs to be forwarded.
        protected abstract boolean needsToForward(final int x, final int y);

        // Translate global x-coordinate to <code>ReceiverView</code> local coordinate.
        protected int translateX(final int x) {
            return x - mEventReceivingRect.left;
        }

        // Translate global y-coordinate to <code>ReceiverView</code> local coordinate.
        protected int translateY(final int y) {
            return y - mEventReceivingRect.top;
        }

        /**
         * Callback when a {@link MotionEvent} is forwarded.
         * @param me the motion event to be forwarded.
         */
        protected void onForwardingEvent(final MotionEvent me) {}

        // Returns true if a {@link MotionEvent} is needed to be forwarded to
        // <code>ReceiverView</code>. Otherwise returns false.
        public boolean onInterceptTouchEvent(final int x, final int y, final MotionEvent me) {
            // Forwards a {link MotionEvent} only if both <code>SenderView</code> and
            // <code>ReceiverView</code> are visible.
            if (mSenderView.getVisibility() != View.VISIBLE ||
                    mReceiverView.getVisibility() != View.VISIBLE) {
                return false;
            }
            mSenderView.getGlobalVisibleRect(mEventSendingRect);
            if (!mEventSendingRect.contains(x, y)) {
                return false;
            }

            if (me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // If the down event happens in the forwarding area, successive
                // {@link MotionEvent}s should be forwarded to <code>ReceiverView</code>.
                return needsToForward(x, y);
            }

            return false;
        }

        // Returns true if a {@link MotionEvent} is forwarded to <code>ReceiverView</code>.
        // Otherwise returns false.
        public boolean onTouchEvent(final int x, final int y, final MotionEvent me) {
            mReceiverView.getGlobalVisibleRect(mEventReceivingRect);
            // Translate global coordinates to <code>ReceiverView</code> local coordinates.
            me.setLocation(translateX(x), translateY(y));
            mReceiverView.dispatchTouchEvent(me);
            onForwardingEvent(me);
            return true;
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the top padding of
     * {@link MainKeyboardView} to {@link SuggestionStripView}.
     */
    private static class KeyboardTopPaddingForwarder
            extends MotionEventForwarder<MainKeyboardView, SuggestionStripView> {
        private int mKeyboardTopPadding;

        public KeyboardTopPaddingForwarder(final MainKeyboardView mainKeyboardView,
                final SuggestionStripView suggestionStripView) {
            super(mainKeyboardView, suggestionStripView);
        }

        public void setKeyboardTopPadding(final int keyboardTopPadding) {
            mKeyboardTopPadding = keyboardTopPadding;
        }

        private boolean isInKeyboardTopPadding(final int y) {
            return y < mEventSendingRect.top + mKeyboardTopPadding;
        }

        @Override
        protected boolean needsToForward(final int x, final int y) {
            // Forwarding an event only when {@link MainKeyboardView} is visible.
            // Because the visibility of {@link MainKeyboardView} is controlled by its parent
            // view in {@link KeyboardSwitcher#setMainKeyboardFrame()}, we should check the
            // visibility of the parent view.
            final View mainKeyboardFrame = (View)mSenderView.getParent();
            return mainKeyboardFrame.getVisibility() == View.VISIBLE && isInKeyboardTopPadding(y);
        }

        @Override
        protected int translateY(final int y) {
            final int translatedY = super.translateY(y);
            if (isInKeyboardTopPadding(y)) {
                // The forwarded event should have coordinates that are inside of the target.
                return Math.min(translatedY, mEventReceivingRect.height() - 1);
            }
            return translatedY;
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the {@link MainKeyboardView} to
     * {@link SuggestionStripView} when the {@link MoreSuggestionsView} is showing.
     * {@link SuggestionStripView} dismisses {@link MoreSuggestionsView} when it receives any event
     * outside of it.
     */
    private static class MoreSuggestionsViewCanceler
            extends MotionEventForwarder<MainKeyboardView, SuggestionStripView> {
        public MoreSuggestionsViewCanceler(final MainKeyboardView mainKeyboardView,
                final SuggestionStripView suggestionStripView) {
            super(mainKeyboardView, suggestionStripView);
        }

        @Override
        protected boolean needsToForward(final int x, final int y) {
            return mReceiverView.isShowingMoreSuggestionPanel() && mEventSendingRect.contains(x, y);
        }

        @Override
        protected void onForwardingEvent(final MotionEvent me) {
            if (me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mReceiverView.dismissMoreSuggestionsPanel();
            }
        }
    }
}
