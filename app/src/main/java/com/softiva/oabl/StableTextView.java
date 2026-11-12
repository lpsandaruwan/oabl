package com.softiva.oabl;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Home's TextView. Setting the text it already shows is a no-op: a plain TextView re-lays out and
 * redraws anyway, and Home re-sets its readings on every tick. On the car screen each redrawn frame
 * is H.264-encoded and sent over the USB link that also carries the audio, so unchanged readings
 * kept that video busy several frames a second.
 */
public final class StableTextView extends TextView {
    public StableTextView(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (sameText(getText(), text)) return;
        super.setText(text, type);
    }

    /** Equal characters, and equal styling when either side carries spans. */
    static boolean sameText(CharSequence shown, CharSequence next) {
        if (shown == null || next == null) return false;
        if (!TextUtils.equals(shown, next)) return false;
        boolean shownStyled = shown instanceof android.text.Spanned, nextStyled = next instanceof android.text.Spanned;
        if (!shownStyled && !nextStyled) return true;
        // Styled text (a smaller unit, say) is redrawn unless both carry the same spans in the same places.
        if (!shownStyled || !nextStyled) return false;
        android.text.Spanned a = (android.text.Spanned) shown, b = (android.text.Spanned) next;
        Object[] as = a.getSpans(0, a.length(), Object.class), bs = b.getSpans(0, b.length(), Object.class);
        if (as.length != bs.length) return false;
        for (int i = 0; i < as.length; i++) {
            if (as[i].getClass() != bs[i].getClass() || a.getSpanStart(as[i]) != b.getSpanStart(bs[i])
                    || a.getSpanEnd(as[i]) != b.getSpanEnd(bs[i])) return false;
            if (as[i] instanceof android.text.style.RelativeSizeSpan) {
                if (((android.text.style.RelativeSizeSpan) as[i]).getSizeChange()
                        != ((android.text.style.RelativeSizeSpan) bs[i]).getSizeChange()) return false;
            } else if (as[i] instanceof android.text.style.ForegroundColorSpan) {
                if (((android.text.style.ForegroundColorSpan) as[i]).getForegroundColor()
                        != ((android.text.style.ForegroundColorSpan) bs[i]).getForegroundColor()) return false;
            } else return false; // a span this cannot compare: redraw to be safe
        }
        return true;
    }
}
