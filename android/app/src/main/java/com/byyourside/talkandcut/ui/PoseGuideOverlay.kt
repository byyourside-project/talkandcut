package com.byyourside.talkandcut.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.byyourside.talkandcut.pose.Pose
import com.byyourside.talkandcut.pose.PoseCatalog
import com.byyourside.talkandcut.pose.guidePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 현재 컷의 포즈 윤곽선. 웹 .pose-image img와 같은 배치:
 * 높이 = 뷰파인더 높이 × heightPct, 위 = 뷰파인더 높이 × topPct, 이미지의 shift 지점이 가로 중앙.
 * 흰색 실루엣(opacity .84) + 어두운 그림자. 미러링하지 않는다.
 */
@Composable
fun PoseGuideOverlay(count: Int, pose: Pose, aspect: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path = PoseCatalog.assetPath(count, pose)
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap ?: return

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val p = guidePlacement(pose, aspect)
        val imgH = maxHeight * (p.heightPct / 100).toFloat()
        val imgW = imgH * (pose.image[0].toFloat() / pose.image[1])
        val top = maxHeight * (p.topPct / 100).toFloat()
        val left = maxWidth / 2 - imgW * (p.shiftPct / 100).toFloat()

        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color(0xFF160E23), BlendMode.SrcIn),
            alpha = 0.7f,
            modifier = Modifier.offset(left, top + 1.dp).size(imgW, imgH),
        )
        Image(
            bitmap = image,
            contentDescription = pose.name,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color.White, BlendMode.SrcIn),
            alpha = 0.84f,
            modifier = Modifier.offset(left, top).size(imgW, imgH),
        )
    }
}
