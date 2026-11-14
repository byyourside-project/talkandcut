package com.byyourside.talkandcut.pose

import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.frame.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 웹 tests/pose.test.mjs "인원별 네 컷의 후보에 해당 인원의 PNG가 모두 들어 있다" 이식. */
class PoseCatalogTest {
    private val catalog = TestPoses.catalog

    @Test
    fun candidatesPerHeadcount() {
        for ((count, expected) in listOf(1 to 12, 2 to 14, 3 to 14, 4 to 11)) {
            val plans = PosePlanner.recommendPlan(catalog.poses(count), null)
            assertEquals(4, plans.map { it.first().id }.toSet().size)
            for (candidates in plans) assertEquals(expected, candidates.map { it.id }.toSet().size)
        }
    }

    @Test
    fun everyPoseHasImageOfCatalogSize() {
        // WebP는 tools/convert-poses.mjs로 만든다. 변환 전이면 건너뛴다.
        assumeTrue("poses/ 없음: node tools/convert-poses.mjs 실행 필요", File(TestPoses.assets, "poses").isDirectory)
        for ((count, poses) in catalog.byCount) {
            for (pose in poses) {
                val file = File(TestPoses.assets, PoseCatalog.assetPath(count, pose))
                assertTrue("missing $file", file.exists())
                assertEquals("$count/${pose.id}", pose.image[0] to pose.image[1], TestPoses.webpSize(file))
            }
        }
    }

    @Test
    fun outlinesFitInsideCellWithFaceAtUpperThird() {
        for ((count, poses) in catalog.byCount) {
            for (pose in poses) {
                val (width, height) = pose.image.map { it.toDouble() }
                val (left, top, right, bottom) = pose.bounds.map { it.toDouble() }
                for (layout in LayoutId.entries) {
                    val aspect = Layouts.cellAspect(layout)
                    val p = guidePlacement(pose, aspect)
                    val scale = p.heightPct / 100
                    val outlineTop = p.topPct / 100 + top / height * scale
                    val outlineBottom = p.topPct / 100 + bottom / height * scale
                    val face = p.topPct / 100 + pose.faceY * scale
                    val label = "$count/${pose.id} $layout"
                    assertTrue(label, (right - left) / height * scale <= aspect * 0.881)
                    assertTrue(label, outlineTop >= 0.059 && outlineBottom <= 0.941)
                    assertTrue(label, face in 0.35..0.37)
                    // 가로: 윤곽선 좌우가 칸 안에 들어간다 (shift 지점이 가운데)
                    val imgW = scale * width / height / aspect // 칸 너비 대비
                    val l = 0.5 - imgW * p.shiftPct / 100 + imgW * left / width
                    val r = 0.5 - imgW * p.shiftPct / 100 + imgW * right / width
                    assertTrue(label, l >= 0.0 && r <= 1.0)
                }
            }
        }
    }
}
