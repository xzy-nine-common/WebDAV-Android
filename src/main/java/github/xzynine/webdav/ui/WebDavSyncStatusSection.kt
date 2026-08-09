package github.xzynine.webdav.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 同步界面状态
 *
 * @param isBackupInProgress 备份是否进行中
 * @param isRestoreInProgress 恢复是否进行中
 * @param backupStatus 最近一次同步状态文案
 * @param backupProgress 备份进度百分比
 * @param restoreProgress 恢复进度百分比
 * @param isCloudBound 当前库是否已绑定云端
 * @param cloudBindingSummary 当前库云端绑定摘要
 */
data class WebDavSyncUiState(
    val isBackupInProgress: Boolean = false,
    val isRestoreInProgress: Boolean = false,
    val backupStatus: String = "",
    val backupProgress: Int = 0,
    val restoreProgress: Int = 0,
    val isCloudBound: Boolean = false,
    val cloudBindingSummary: String = ""
)

/**
 * WebDAV 同步状态区块
 *
 * 展示备份状态、备份与手动恢复三个条目，含进行中进度与按钮联动。
 * 由调用方组合到自身页面布局中（如"备份与恢复"分组）。
 *
 * @param state 同步界面状态
 * @param onCloudBindingClick 备份状态条目点击回调（通常打开云端绑定设置）
 * @param onBackupClick 备份按钮点击回调
 * @param onRestoreClick 手动恢复按钮点击回调
 */
@Composable
fun WebDavSyncStatusSection(
    state: WebDavSyncUiState,
    onCloudBindingClick: () -> Unit,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    // 备份状态显示（包含当前库云端摘要）
    val combinedBackupSummary = run {
        val statusText = state.backupStatus
        if (statusText.isBlank()) {
            state.cloudBindingSummary
        } else if (state.cloudBindingSummary.isBlank()) {
            statusText
        } else {
            "$statusText | ${state.cloudBindingSummary}"
        }
    }
    ArrowPreference(
        title = "备份状态",
        summary = combinedBackupSummary,
        startAction = {
            Icon(
                modifier = Modifier.padding(end = 16.dp),
                imageVector = MiuixIcons.Backup,
                contentDescription = "备份状态",
            )
        },
        onClick = onCloudBindingClick,
        modifier = Modifier
            .fillMaxWidth()
    )

    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

    // 备份按钮
    ArrowPreference(
        title = if (state.isBackupInProgress) {
            "备份中..."
        } else if (state.isCloudBound) {
            "备份令牌"
        } else {
            "绑定后可备份"
        },
        summary = if (state.isBackupInProgress) {
            "正在备份到WebDAV服务器... ${state.backupProgress}%"
        } else if (state.isCloudBound) {
            "点击开始备份"
        } else {
            "当前库未绑定云端 .kdbx"
        },
        startAction = {
            if (state.isBackupInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 16.dp)
                )
            } else {
                Icon(
                    modifier = Modifier.padding(end = 16.dp),
                    imageVector = MiuixIcons.UploadCloud,
                    contentDescription = "备份令牌",
                )
            }
        },
        onClick = {
            if (!state.isBackupInProgress && state.isCloudBound) {
                onBackupClick()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
    )

    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

    // 手动恢复按钮
    ArrowPreference(
        title = if (state.isRestoreInProgress) {
            "恢复中..."
        } else if (state.isCloudBound) {
            "手动恢复"
        } else {
            "绑定后可恢复"
        },
        summary = if (state.isRestoreInProgress) {
            "正在从WebDAV服务器恢复... ${state.restoreProgress}%"
        } else if (state.isCloudBound) {
            "点击开始手动恢复"
        } else {
            "当前库未绑定云端 .kdbx"
        },
        startAction = {
            if (state.isRestoreInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 16.dp)
                )
            } else {
                Icon(
                    modifier = Modifier.padding(end = 16.dp),
                    imageVector = MiuixIcons.Download,
                    contentDescription = "手动恢复",
                )
            }
        },
        onClick = {
            if (!state.isRestoreInProgress && state.isCloudBound) {
                onRestoreClick()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
    )
}
