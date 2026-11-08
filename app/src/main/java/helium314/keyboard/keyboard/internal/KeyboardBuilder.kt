/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.res.Resources
import android.util.Xml
import androidx.annotation.XmlRes
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Key.KeyParams
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParser
import helium314.keyboard.keyboard.internal.keyboard_parser.KeyboardParser
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.sumOf
import org.xmlpull.v1.XmlPullParser

open class KeyboardBuilder<KP : KeyboardParams>(protected val mContext: Context, @JvmField val mParams: KP) {
    @JvmField
    protected val mResources: Resources
    private var mCurrentY = 0

    private var mLeftEdge = false
    private var mTopEdge = false
    private var mRightEdgeKey: Key? = null
    private lateinit var keysInRows: ArrayList<ArrayList<KeyParams>>

    init {
        val res = mContext.resources
        mResources = res
        mParams.GRID_WIDTH = res.getInteger(R.integer.config_keyboard_grid_width)
        mParams.GRID_HEIGHT = res.getInteger(R.integer.config_keyboard_grid_height)
    }

    fun load(id: KeyboardId): KeyboardBuilder<KP> {
        mParams.mId = id
        if (id.element.isEmojiLayout) {
            mParams.mAllowRedundantPopupKeys = true
            readAttributes(R.xml.kbd_emoji)
            keysInRows = EmojiParser(mParams, mContext).parse()
        } else {
            try {
                setupParams()
                keysInRows = KeyboardParser(mParams, mContext).parseLayout()
                if (keysInRows.size != 4) // that was effectively the default for OpenBoard
                    mParams.mTouchPositionCorrection.load(mContext.resources.getStringArray(R.array.touch_position_correction_data_default))
                determineAbsoluteValues()
            } catch (e: Exception) {
                Log.e(TAG, "error parsing layout $id ${id.element}", e)
                throw e
            }
        }
        return this
    }

    private fun setupParams() {
        val sv = Settings.getValues()
        mParams.mAllowRedundantPopupKeys = !sv.mRemoveRedundantPopups
        mParams.mProximityCharsCorrectionEnabled = mParams.mId.element == KeyboardElement.ALPHABET
                || (mParams.mId.element.isAlphabet && !mParams.mId.subtype.hasExtraValue(Constants.Subtype.ExtraValue.NO_SHIFT_PROXIMITY_CORRECTION))

        LocaleKeyboardInfos.addLocaleKeyTextsToParams(mContext, mParams, sv.mShowMorePopupKeys)
        mParams.mPopupKeyOrder.addAll(sv.mPopupKeyOrder)
        // add label source only if popup key type enabled
        sv.mPopupKeyHintOrder.forEach { if (it in sv.mPopupKeyOrder) mParams.mPopupKeyHintOrder.add(it) }
    }

    // todo: remnant of old parser, replace it if reasonably simple
    protected fun readAttributes(@XmlRes xmlId: Int) {
        val parser = mResources.getXml(xmlId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val event = parser.next()
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name
                if ("Keyboard" == tag) {
                    mParams.readAttributes(mContext, Xml.asAttributeSet(parser))
                    return
                }
            }
        }
        mParams.readAttributes(mContext, null)
    }

    fun disableTouchPositionCorrectionDataForTest() {
        mParams.mTouchPositionCorrection.setEnabled(false)
    }

    fun setProximityCharsCorrectionEnabled(enabled: Boolean) {
        mParams.mProximityCharsCorrectionEnabled = enabled
    }

    open fun build(): Keyboard {
        if (mParams.mId.isSplitLayout
                && mParams.mId.element in KeyboardElement.ALPHABET..KeyboardElement.SYMBOLS_SHIFTED) {
            addSplit()
        }
        addKeysToParams()
        return Keyboard(mParams)
    }

    // determine key size and positions using relative width and height
    private fun determineAbsoluteValues() {
        var currentY = mParams.mTopPadding.toFloat()
        for (row in keysInRows) {
            if (row.isEmpty()) continue
            var currentX = mParams.mLeftPadding.toFloat()
            row.forEach {
                it.setAbsoluteDimensions(currentX, currentY)
                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "setting size and position for ${it.mLabel ?: it.mIconName}, ${it.mCode}: x ${currentX.toInt()}, w ${it.mAbsoluteWidth.toInt()}")
                currentX += it.mAbsoluteWidth
            }
            currentY += row.first().mAbsoluteHeight
        }
    }

    private fun addSplit() {
        val spacerRelativeWidth = Settings.getValues().mSplitKeyboardSpacerRelativeWidth
        // adjust gaps for the whole keyboard, so it's the same for all rows
        mParams.mRelativeHorizontalGap *= 1f / (1f + spacerRelativeWidth)
        mParams.mHorizontalGap = (mParams.mRelativeHorizontalGap * mParams.mId.width).toInt()
        var maxWidthBeforeSpacer = 0f
        var maxWidthAfterSpacer = 0f
        val rowSpacers = ArrayList<KeyParams?>(keysInRows.size)
        for (row in keysInRows) {
            val y = row.first().yPos // all have the same y, so this is fine
            val relativeWidthSum = row.sumOf { it.mWidth } // sum up relative widths
            val spacer = KeyParams.newSpacer(mParams, spacerRelativeWidth)
            // insert spacer before first key that starts right of the center (also consider gap)
            var insertIndex = row.indexOfFirst { it.xPos + it.mAbsoluteWidth / 3 > mParams.mOccupiedWidth / 2 }
                .takeIf { it > -1 } ?: (row.size / 2) // fallback should never be needed, but better than having an error
            val indexOfProperSpace = row.indexOfFirst { key ->
                // should work reasonably with customizable layouts, where space key might be completely different:
                // "normal" width space keys are ignored, and the possibility of space being first in row is considered
                key.mCode == Constants.CODE_SPACE && key.mWidth > row.first { !it.isSpacer && it.mCode != Constants.CODE_SPACE }.mWidth * 1.5f
            }
            if (indexOfProperSpace >= 0) {
                val spaceLeft = row[indexOfProperSpace]
                reduceSymbolAndActionKeyWidth(row)
                insertIndex = row.indexOf(spaceLeft) + 1
                val widthBeforeSpace = row.subList(0, insertIndex - 1).sumOf { it.mWidth }
                val widthAfterSpace = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                val spaceLeftWidth = (maxWidthBeforeSpacer - widthBeforeSpace).coerceAtLeast(mParams.mDefaultKeyWidth)
                val spaceRightWidth = (maxWidthAfterSpacer - widthAfterSpace).coerceAtLeast(mParams.mDefaultKeyWidth)
                val spacerWidth = spaceLeft.mWidth + spacerRelativeWidth - spaceLeftWidth - spaceRightWidth
                if (spacerWidth > 0.05f) {
                    // only insert if the spacer has a reasonable width
                    val spaceRight = KeyParams(spaceLeft)
                    spaceLeft.mWidth = spaceLeftWidth
                    spaceRight.mWidth = spaceRightWidth
                    spacer.mWidth = spacerWidth
                    row.add(insertIndex, spaceRight)
                    row.add(insertIndex, spacer)
                    rowSpacers.add(spacer)
                } else {
                    // otherwise increase space width, so other keys are resized properly
                    spaceLeft.mWidth += spacerWidth
                    rowSpacers.add(null)
                }
            } else {
                val widthBeforeSpacer = row.subList(0, insertIndex).sumOf { it.mWidth }
                val widthAfterSpacer = row.subList(insertIndex, row.size).sumOf { it.mWidth }
                maxWidthBeforeSpacer = maxWidthBeforeSpacer.coerceAtLeast(widthBeforeSpacer)
                maxWidthAfterSpacer = maxWidthAfterSpacer.coerceAtLeast(widthAfterSpacer)
                row.add(insertIndex, spacer)
                rowSpacers.add(spacer)
            }
            widenInnerKeys(row, row.indexOf(spacer))
            if (Settings.getValues().mSplitHiddenDuplicates)
                addHiddenDuplicates(row, spacer)
            // re-calculate relative widths
            val relativeWidthSumNew = row.sumOf { it.mWidth }
            val widthFactor = relativeWidthSum / relativeWidthSumNew
            // re-calculate absolute sizes and positions
            var currentX = mParams.mLeftPadding.toFloat()
            row.forEach {
                it.mWidth *= widthFactor
                it.setAbsoluteDimensions(currentX, y)
                currentX += it.mAbsoluteWidth
            }
        }
        setSplitGap(rowSpacers)
    }

    /**
     *  Make the letter keys next to the split gap wider (split_inner_key_scale), taking the width
     *  from the other keys of the same half. The inner columns are the farthest reach for the
     *  thumbs, where touches spread most, so they get the bigger targets. Half widths stay the same.
     */
    private fun widenInnerKeys(row: List<KeyParams>, spacerIndex: Int) {
        val scale = Settings.getValues().mSplitInnerKeyScale
        if (scale <= 1f || spacerIndex <= 0 || spacerIndex >= row.lastIndex) return
        for (left in listOf(true, false)) {
            val half = if (left) row.subList(0, spacerIndex) else row.subList(spacerIndex + 1, row.size)
            val keys = half.filter { !it.isSpacer }
            val inner = (if (left) keys.lastOrNull() else keys.firstOrNull()) ?: continue
            if (inner.mBackgroundType != Key.BACKGROUND_TYPE_NORMAL) continue
            val others = keys.filter { it !== inner }
            val othersWidth = others.sumOf { it.mWidth }
            val extra = inner.mWidth * (scale - 1f)
            if (others.isEmpty() || othersWidth <= extra * 2) continue
            inner.mWidth += extra
            others.forEach { it.mWidth *= (othersWidth - extra) / othersWidth }
        }
    }

    /**
     *  Like the iPad's split keyboard: a narrow invisible key at the inner edge of each half that
     *  types the other half's innermost letter (Y H B at the left half, T G V at the right), so a
     *  thumb that overshoots the edge still gets the letter it was reaching for. Taken from the
     *  gap, so the cards (and the touchable area) grow over these zones. Letter rows only.
     */
    private fun addHiddenDuplicates(row: ArrayList<KeyParams>, spacer: KeyParams) {
        val i = row.indexOf(spacer)
        if (i <= 0 || i >= row.lastIndex) return
        val left = row[i - 1]
        val right = row[i + 1]
        if (left.isSpacer || right.isSpacer || left.mBackgroundType != Key.BACKGROUND_TYPE_NORMAL
                || right.mBackgroundType != Key.BACKGROUND_TYPE_NORMAL)
            return
        val width = minOf(left.mWidth, right.mWidth) * HIDDEN_DUPLICATE_WIDTH
        if (spacer.mWidth < 4 * width) return // keep a real gap
        spacer.mWidth -= 2 * width
        val ofRight = KeyParams(right).apply { mWidth = width; mHiddenDuplicate = true }
        val ofLeft = KeyParams(left).apply { mWidth = width; mHiddenDuplicate = true }
        row.add(i, ofRight) // left half: ..., t, [y], gap, [t], y, ...
        row.add(i + 2, ofLeft)
    }

    /**
     *  Store the area that is empty in all rows from the top down, so views can leave it transparent.
     *  The gap ends above the first row without spacer (e.g. a space bar spanning both halves).
     */
    private fun setSplitGap(rowSpacers: List<KeyParams?>) {
        var left = 0f
        var right = Float.MAX_VALUE
        var bottom = 0f
        for ((i, spacer) in rowSpacers.withIndex()) {
            if (spacer == null) break
            left = left.coerceAtLeast(spacer.xPos)
            right = right.coerceAtMost(spacer.xPos + spacer.mAbsoluteWidth)
            val rowKey = keysInRows[i].first() // spacer height may differ from row height
            bottom = rowKey.yPos + rowKey.mAbsoluteHeight
        }
        if (bottom <= 0f || right - left < 1f) return
        mParams.mSplitGapLeft = left.toInt()
        mParams.mSplitGapRight = right.toInt()
        mParams.mSplitGapBottom = if (rowSpacers.all { it != null }) mParams.mOccupiedHeight else bottom.toInt()
    }

    // reduce width of symbol and action key if in the (to be split) row, and add this width to space to keep other key size constant
    private fun reduceSymbolAndActionKeyWidth(row: ArrayList<KeyParams>) {
        val spaceKey = row.first { it.mCode == Constants.CODE_SPACE }
        if (spaceKey.mWidth >= 0.5) return // only if space key is small
        val symbolKey = row.firstOrNull { it.mCode == KeyCode.SYMBOL_ALPHA }
        val symbolKeyWidth = symbolKey?.mWidth ?: 0f
        if (symbolKeyWidth > mParams.mDefaultKeyWidth) {
            val widthToChange = symbolKey!!.mWidth - mParams.mDefaultKeyWidth
            symbolKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
        val actionKey = row.firstOrNull { it.mBackgroundType == Key.BACKGROUND_TYPE_ACTION }
        val actionKeyWidth = actionKey?.mWidth ?: 0f
        if (actionKeyWidth > mParams.mDefaultKeyWidth * 1.1f) { // allow it to stay a little wider
            val widthToChange = actionKey!!.mWidth - mParams.mDefaultKeyWidth * 1.1f
            actionKey.mWidth -= widthToChange
            spaceKey.mWidth += widthToChange
        }
    }

    private fun startKeyboard() {
        mCurrentY += mParams.mTopPadding
        mTopEdge = true
    }

    private fun startRow() {
        mLeftEdge = true
        mRightEdgeKey = null
    }

    private fun endRow() {
        val rightEdgeKey = mRightEdgeKey
        if (rightEdgeKey != null) {
            rightEdgeKey.markAsRightEdge(mParams)
            mCurrentY += rightEdgeKey.height + rightEdgeKey.verticalGap
            mRightEdgeKey = null
        }
        mLeftEdge = false
        mTopEdge = false
    }

    private fun endKey(key: Key) {
        mParams.onAddKey(key)
        if (mLeftEdge && !key.isSpacer) {
            key.markAsLeftEdge(mParams)
            mLeftEdge = false
        }
        if (mTopEdge) {
            key.markAsTopEdge(mParams)
        }
        if (!key.isSpacer) {
            mRightEdgeKey = key
        }
    }

    private fun endKeyboard() {
        mParams.removeRedundantPopupKeys()
        if (!mParams.mId.element.isEmojiLayout) return
        val actualHeight = mCurrentY - mParams.mVerticalGap + mParams.mBottomPadding
        mParams.mOccupiedHeight = mParams.mOccupiedHeight.coerceAtLeast(actualHeight)
    }

    private fun addKeysToParams() {
        // need to reset it, we need to sum it up to get the height nicely
        // (though in the end we could just not touch it at all, final used value is the same as the one before resetting)
        mCurrentY = 0
        startKeyboard()
        for (row in keysInRows) {
            startRow()
            for (keyParams in row) {
                endKey(keyParams.createKey())
            }
            endRow()
        }
        endKeyboard()
    }

    companion object {
        /** width of a hidden duplicate key, relative to the key it copies */
        private const val HIDDEN_DUPLICATE_WIDTH = 0.5f
        private const val TAG = "Keyboard.Builder"
    }
}
