package io.github.ntufar.stasi.util

import android.content.Context
import android.util.Log
import io.github.ntufar.stasi.ui.map.STYLE_URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

private const val TAG = "OfflineMap"
private const val MIN_ZOOM = 10.0
private const val MAX_ZOOM = 15.0

private val ATHENS_BOUNDS = LatLngBounds.Builder()
    .include(org.maplibre.android.geometry.LatLng(37.85, 23.55))
    .include(org.maplibre.android.geometry.LatLng(38.10, 23.85))
    .build()

sealed class OfflineMapState {
    data object Unavailable : OfflineMapState()
    data object NotDownloaded : OfflineMapState()
    data class Downloading(val progress: Float) : OfflineMapState()
    data object Downloaded : OfflineMapState()
    data class Error(val message: String) : OfflineMapState()
}

class OfflineMapManager(context: Context) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<OfflineMapState>(OfflineMapState.Unavailable)
    val state: StateFlow<OfflineMapState> = _state.asStateFlow()

    private var currentRegion: OfflineRegion? = null
    private var offlineManager: OfflineManager? = null

    fun init() {
        try {
            offlineManager = OfflineManager.getInstance(appContext)
            _state.value = OfflineMapState.NotDownloaded
        } catch (e: Exception) {
            Log.w(TAG, "OfflineManager not available", e)
            _state.value = OfflineMapState.Unavailable
        }
    }

    fun refreshStatus() {
        val mgr = offlineManager ?: return
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList() ?: emptyList<OfflineRegion>()
                val region = regions.firstOrNull { it.definition is OfflineTilePyramidRegionDefinition }
                if (region == null) {
                    _state.value = OfflineMapState.NotDownloaded
                    currentRegion = null
                    return
                }
                currentRegion = region
                region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        if (status != null) {
                            updateStateFromStatus(status)
                        }
                    }

                    override fun onError(error: String?) {
                        Log.w(TAG, "getStatus error: $error")
                    }
                })
            }

            override fun onError(error: String) {
                Log.w(TAG, "listOfflineRegions error: $error")
            }
        })
    }

    fun startDownload() {
        val mgr = offlineManager ?: return
        _state.value = OfflineMapState.Downloading(0f)
        try {
            val density = appContext.resources.displayMetrics.density
            val definition = OfflineTilePyramidRegionDefinition(
                STYLE_URI, ATHENS_BOUNDS, MIN_ZOOM, MAX_ZOOM, density,
            )
            val metadata = REGION_NAME
            mgr.createOfflineRegion(
                definition, metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        currentRegion = offlineRegion
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                updateStateFromStatus(status)
                            }

                            override fun onError(error: OfflineRegionError) {
                                val msg = error.message
                                Log.e(TAG, "Offline region error: $msg")
                                _state.value = OfflineMapState.Error(msg)
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                Log.w(TAG, "Tile count limit exceeded: $limit")
                            }
                        })
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "createOfflineRegion error: $error")
                        _state.value = OfflineMapState.Error(error)
                    }
                },
            )
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to start offline download."
            Log.e(TAG, "Download start failed", e)
            _state.value = OfflineMapState.Error(msg)
        }
    }

    fun deleteRegion() {
        val region = currentRegion ?: return
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                currentRegion = null
                _state.value = OfflineMapState.NotDownloaded
            }

            override fun onError(error: String) {
                Log.w(TAG, "delete region error: $error")
                currentRegion = null
                _state.value = OfflineMapState.NotDownloaded
            }
        })
    }

    private fun updateStateFromStatus(status: OfflineRegionStatus) {
        _state.value = when {
            status.isComplete -> OfflineMapState.Downloaded
            status.requiredResourceCount > 0 -> {
                val p = (status.completedResourceCount.toFloat() / status.requiredResourceCount.toFloat())
                    .coerceIn(0f, 1f)
                OfflineMapState.Downloading(p)
            }
            else -> OfflineMapState.Downloading(0f)
        }
    }

    companion object {
        private val REGION_NAME = "athens".toByteArray()
    }
}
