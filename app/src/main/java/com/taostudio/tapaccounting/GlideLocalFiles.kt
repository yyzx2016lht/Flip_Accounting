package com.taostudio.tapaccounting

import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.signature.ObjectKey
import java.io.File

object GlideLocalFiles {
    fun clear(target: ImageView) {
        Glide.with(target).clear(target)
    }

    fun load(
        target: ImageView,
        file: File?,
        @DrawableRes placeholderRes: Int? = null,
        @DrawableRes errorRes: Int? = placeholderRes,
        circleCrop: Boolean = false,
        centerCrop: Boolean = false,
        signatureKey: String? = file?.let { "${it.absolutePath}:${it.lastModified()}" },
        diskCacheStrategy: DiskCacheStrategy = if (circleCrop || centerCrop) {
            DiskCacheStrategy.DATA
        } else {
            DiskCacheStrategy.NONE
        },
        skipMemoryCache: Boolean = diskCacheStrategy == DiskCacheStrategy.NONE,
        overrideSize: Int = 0
    ) {
        if (file == null || !file.exists()) {
            clear(target)
            when {
                placeholderRes != null -> target.setImageResource(placeholderRes)
                else -> target.setImageDrawable(null)
            }
            return
        }

        var request = Glide.with(target)
            .load(file)
            .diskCacheStrategy(diskCacheStrategy)
            .skipMemoryCache(skipMemoryCache)
            .apply { if (overrideSize > 0) override(overrideSize) }

        if (!signatureKey.isNullOrBlank()) {
            request = request.signature(ObjectKey(signatureKey))
        }
        if (circleCrop) {
            request = request.transform(CircleCrop())
        } else if (centerCrop) {
            request = request.transform(CenterCrop())
        }
        if (placeholderRes != null) {
            request = request.placeholder(placeholderRes)
        }
        if (errorRes != null) {
            request = request.error(errorRes)
        }
        request.into(target)
    }
}

