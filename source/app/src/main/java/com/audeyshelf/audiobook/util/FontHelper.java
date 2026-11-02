package com.audeyshelf.audiobook.util;

import android.content.Context;
import android.graphics.Typeface;
import androidx.core.content.res.ResourcesCompat;
import com.audeyshelf.audiobook.R;

public class FontHelper {
    private static Typeface montserratSemiBold;

    public static Typeface getMontserratSemiBold(Context context) {
        if (montserratSemiBold == null) {
            try {
                montserratSemiBold = ResourcesCompat.getFont(context, R.font.montserrat_semibold);
            } catch (Exception ignored) {
            }
            if (montserratSemiBold == null) {
                try {
                    montserratSemiBold = Typeface.createFromAsset(context.getAssets(), "fonts/Montserrat-SemiBold.otf");
                } catch (Exception ignored) {
                }
            }
        }
        return montserratSemiBold;
    }
}
