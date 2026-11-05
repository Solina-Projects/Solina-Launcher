package app.lawnchair.overview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.Keep
import app.lawnchair.util.RecentHelper
import app.lawnchair.util.TaskUtilLockState
import com.android.launcher3.Flags.enableRefactorTaskThumbnail
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.views.OverviewActionsView
import com.android.quickstep.views.TaskContainer
import com.android.systemui.shared.recents.model.Task

@Keep
class TaskOverlayFactoryImpl(@Suppress("UNUSED_PARAMETER") context: Context) : TaskOverlayFactory() {

    override fun createOverlay(thumbnailView: TaskContainer) = TaskOverlay(thumbnailView)

    class TaskOverlay(
        taskThumbnailView: TaskContainer,
    ) : TaskOverlayFactory.TaskOverlay<LawnchairOverviewActionsView>(taskThumbnailView) {

        override fun initOverlay(
            task: Task?,
            thumbnail: Bitmap?,
            matrix: Matrix,
            rotated: Boolean,
        ) {
            if (!enableRefactorTaskThumbnail()) {
                actionsView.updateDisabledFlags(
                    OverviewActionsView.DISABLED_NO_THUMBNAIL,
                    thumbnail == null,
                )
            }
            if (thumbnail != null || enableRefactorTaskThumbnail()) {
                if (!enableRefactorTaskThumbnail()) {
                    actionsView.updateDisabledFlags(OverviewActionsView.DISABLED_ROTATED, rotated)
                }
                val isAllowedByPolicy = isRealSnapshot
                actionsView.setCallbacks(OverlayUICallbacksImpl(isAllowedByPolicy, task))
            }
        }

        private inner class OverlayUICallbacksImpl(
            isAllowedByPolicy: Boolean,
            task: Task?,
        ) : TaskOverlayFactory.TaskOverlay<LawnchairOverviewActionsView>.OverlayUICallbacksImpl(
            isAllowedByPolicy,
            task,
        ),
            OverlayUICallbacks {
            override fun onShare() {
                if (mIsAllowedByPolicy) {
                    endLiveTileMode { mImageApi.startShareActivity(null) }
                } else {
                    showBlockedByPolicyMessage()
                }
            }

            override fun onLens() {
                if (mIsAllowedByPolicy) {
                    endLiveTileMode { mImageApi.startLensActivity() }
                } else {
                    showBlockedByPolicyMessage()
                }
            }

            override fun onLocked(context: Context, task: Task) {
                val component = task.key.component ?: return
                val isLocked = !RecentHelper.isAppLocked(task.key.packageName, context)
                TaskUtilLockState.setTaskLockState(
                    context,
                    component,
                    isLocked,
                    task.key,
                )
            }

            override fun onToggleLock() {
                val currentTask = mTask ?: return
                onLocked(mApplicationContext, currentTask)
            }
        }
    }

    sealed interface OverlayUICallbacks : TaskOverlayFactory.OverlayUICallbacks {
        fun onShare()
        fun onLens()
        fun onLocked(context: Context, task: Task)
        fun onToggleLock()
    }
}
