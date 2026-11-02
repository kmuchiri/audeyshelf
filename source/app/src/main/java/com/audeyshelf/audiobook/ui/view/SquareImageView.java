package com.audeyshelf.audiobook.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * SquareImageView forces a 1:1 aspect ratio based on width.
 * Ideal for cover art in GridView on all screen densities.
 */
public class SquareImageView extends ImageView {

    public SquareImageView(Context context) {
        super(context);
    }

    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SquareImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Measure with width as height for 1:1 aspect ratio
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
