package io.embodit.skydome

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import io.embodit.skydome.ui.sky.FULL_STAGE_ID
import io.embodit.skydome.ui.sky.MIXED_STAGE_ID
import io.embodit.skydome.ui.sky.SkyScreen
import io.embodit.skydome.ui.theme.SkyDomeTheme

/**
 * App entry (design `container`: STAGE_MIXED, skeleton C). The default Stage is Mixed (AndroidManifest
 * `pico.spatial.stage.style=1`): the dome covers the upper half of the view and the room stays visible below
 * the horizon. "全黑" re-opens the app in a second Stage with style Full; the state lives in `SkyController`,
 * so time, place, heading and the pulled-down constellation carry over (design needsCheck N2).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { SkyDomeTheme { SkyScreen(MIXED_STAGE_ID) } }
        Stage(id = FULL_STAGE_ID) { SkyDomeTheme { SkyScreen(FULL_STAGE_ID) } }
    }
