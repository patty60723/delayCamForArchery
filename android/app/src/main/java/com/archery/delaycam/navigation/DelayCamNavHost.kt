package com.archery.delaycam.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.archery.delaycam.ui.components.RecordingControls
import com.archery.delaycam.ui.components.RecordingScreenLayout
import androidx.navigation.navArgument
import com.archery.delaycam.gallery.ClipEntry
import com.archery.delaycam.state.AppState
import com.archery.delaycam.state.Routes
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.gallery.GalleryScreen
import com.archery.delaycam.ui.gallery.PlayerScreen
import com.archery.delaycam.ui.live.LiveScreen
import com.archery.delaycam.ui.live.PracticeCameraLayout
import androidx.compose.ui.geometry.Rect
import com.archery.delaycam.ui.replay.ReplayScreen
import com.archery.delaycam.ui.settings.SettingsScreen
import com.archery.delaycam.ui.viewer.ViewerScreen
import com.archery.delaycam.BuildConfig
import java.net.URLDecoder

/**
 * Top-level screen router (spec 3.1-3.4). Live/Settings/Gallery/Player are
 * ordinary user-navigated destinations; Replay is instead driven by
 * viewModel.appState - it appears automatically the moment a save is
 * triggered and pops itself once playback finishes or is skipped, mirroring
 * the web prototype's showScreen(REPLAY) calls in triggerSave().
 */
@Composable
fun DelayCamNavHost(viewModel: DelayCamViewModel) {
    val navController = rememberNavController()
    val approval by viewModel.deleteApproval.collectAsState()
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onDeleteApprovalResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(approval) {
        approval?.let { sender ->
            try { deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()); viewModel.onDeleteApprovalLaunched() }
            catch (_: Exception) { viewModel.onDeleteApprovalResult(false) }
        }
    }
    val appState by viewModel.appState.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val previewSource by viewModel.previewSource.collectAsState()
    val recordingPhase by viewModel.recordingPhase.collectAsState()
    val recordingElapsed by viewModel.recordingElapsedSec.collectAsState()
    val recordingSave by viewModel.recordingSave.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    LaunchedEffect(route) {
        viewModel.setPracticeVisible(route == Routes.LIVE || route == Routes.REPLAY)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.setPracticeVisible(false) }
    }
    val openSaved: (android.net.Uri, String) -> Unit = { uri, name ->
        if (route == Routes.REPLAY) {
            viewModel.skipReplay()
            navController.navigate(Routes.player(uri.toString(), name)) {
                popUpTo(Routes.REPLAY) { inclusive = true }
            }
        } else navController.navigate(Routes.player(uri.toString(), name))
    }

    LaunchedEffect(route, appState) {
        if (route == Routes.REPLAY && appState == AppState.LIVE) navController.popBackStack()
    }

    LaunchedEffect(appState) {
        val onReplayRoute = navController.currentBackStackEntry?.destination?.route == Routes.REPLAY
        when (appState) {
            AppState.REPLAY -> if (!onReplayRoute) navController.navigate(Routes.REPLAY)
            AppState.LIVE -> if (onReplayRoute) navController.popBackStack()
            AppState.SAVING -> Unit
        }
    }

    // Hoisted above the NavHost (rather than living in LiveScreen) so a
    // toast fired by the background clip export - which can land while the
    // user is on Replay, Settings or Gallery - is never silently dropped.
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PracticeCameraLayout(previewSource, live = route == Routes.LIVE) { previewBounds ->
        RecordingScreenLayout(
            practice = route == Routes.LIVE || route == Routes.REPLAY || route == Routes.PLAYER,
            recording = { compact, modifier ->
                RecordingControls(recordingPhase, recordingElapsed, recordingSave,
                    viewModel::toggleContinuousRecording, viewModel::retryRecordingSave, openSaved,
                    compact = compact, modifier = modifier, allowStart = route == Routes.LIVE)
            },
            content = { modifier -> AppNavHost(navController, viewModel, modifier, openSaved, previewBounds) },
        )
        }

        toast?.let {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF14171C),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: DelayCamViewModel,
    modifier: Modifier,
    onOpenSaved: (android.net.Uri, String) -> Unit,
    onPreviewBounds: (Rect?) -> Unit,
) {
    NavHost(navController = navController, startDestination = Routes.LIVE, modifier = modifier) {
        composable(Routes.LIVE) {
            LiveScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenGallery = { navController.navigate(Routes.GALLERY) },
                onOpenViewer = { navController.navigate(Routes.VIEWER) { launchSingleTop = true } },
                onOpenSavedClip = onOpenSaved,
                onPreviewBounds = onPreviewBounds,
            )
        }
        composable(Routes.REPLAY) {
            ReplayScreen(viewModel = viewModel, onOpenSavedClip = onOpenSaved)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() },
                onOpenViewer = { navController.navigate(Routes.VIEWER) { launchSingleTop = true } })
        }
        if (BuildConfig.LAN_VIEWER_TRIAL) composable(Routes.VIEWER) {
            ViewerScreen(viewModel, onBack = { navController.popBackStack() },
                onReturnToPractice = { navController.popBackStack(Routes.LIVE, false) })
        }
        composable(Routes.GALLERY) {
            GalleryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenClip = { clip: ClipEntry ->
                    navController.navigate(Routes.player(clip.uri.toString(), clip.displayName))
                },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("clipUri") { type = NavType.StringType },
                navArgument("clipName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val clipUri = URLDecoder.decode(backStackEntry.arguments?.getString("clipUri").orEmpty(), "UTF-8")
            val clipName = URLDecoder.decode(backStackEntry.arguments?.getString("clipName").orEmpty(), "UTF-8")
            PlayerScreen(
                viewModel = viewModel,
                clipUri = clipUri,
                clipName = clipName,
                onBack = { if (navController.currentBackStackEntry == backStackEntry) navController.popBackStack() },
            )
        }
    }
}

