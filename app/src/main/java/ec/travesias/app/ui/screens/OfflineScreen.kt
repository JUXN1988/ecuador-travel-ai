package ec.travesias.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ec.travesias.app.data.local.OfflinePackageEntity
import ec.travesias.app.data.maps.OfflineMapManager
import ec.travesias.app.ui.theme.*
import ec.travesias.app.viewmodel.TravelViewModel

/**
 * Mapas offline: descarga real de teselas de OpenStreetMap por región (osmdroid CacheManager).
 * Los lugares, rutas y tarjetas ya están siempre offline (Room).
 */
@Composable
fun OfflineScreen(viewModel: TravelViewModel) {
    val packages by viewModel.offlinePackages.collectAsState()
    val downloads by viewModel.mapDownloads.collectAsState()
    val cacheMb by viewModel.cacheSizeMb.collectAsState()

    val downloadedCount = packages.count { it.isDownloaded }

    LazyColumn(modifier = Modifier.fillMaxSize().background(SurfaceLight), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Mapas sin señal", style = MaterialTheme.typography.headlineSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
            Text(
                "Tus lugares, rutas y tarjetas ya funcionan sin internet. Aquí descargas además el mapa de cada región para navegar en páramos, selva o islas sin cobertura.",
                style = MaterialTheme.typography.bodySmall, color = VolcanicGray
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Almacenamiento de mapas", style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            Text("$downloadedCount región(es) descargada(s)", style = MaterialTheme.typography.bodySmall, color = VolcanicGray)
                        }
                        Surface(color = SurfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SdStorage, contentDescription = null, tint = AndeanBlue, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("$cacheMb MB", style = MaterialTheme.typography.labelSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { (cacheMb / 1024f).coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = AndeanBlue, trackColor = SurfaceContainerHighest)
                    Text("Límite de caché: 1 GB · Las teselas descargadas se conservan 1 año", style = MaterialTheme.typography.labelSmall, color = VolcanicGray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item { Text("Regiones de Ecuador (${packages.size})", style = MaterialTheme.typography.titleMedium, color = AndeanBlue, fontWeight = FontWeight.Bold) }
        items(packages, key = { it.id }) { pkg ->
            OfflinePackageCard(
                pkg = pkg,
                download = downloads[pkg.id],
                onDownload = { viewModel.downloadOfflinePackage(pkg.id) },
                onDelete = { viewModel.deleteOfflinePackage(pkg.id) }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun OfflinePackageCard(
    pkg: OfflinePackageEntity,
    download: OfflineMapManager.DownloadState?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val running = download?.isRunning == true
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainerLowest), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(color = if (pkg.isDownloaded) BrandFill else SurfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
                        Icon(
                            when { running -> Icons.Default.Downloading; pkg.isDownloaded -> Icons.Default.CloudDone; else -> Icons.Default.CloudQueue },
                            contentDescription = null, tint = if (pkg.isDownloaded) Color.White else AndeanBlue, modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(pkg.regionName, style = MaterialTheme.typography.titleSmall, color = AndeanBlue, fontWeight = FontWeight.Bold)
                        Text("${pkg.province} • ${pkg.lastUpdated}", style = MaterialTheme.typography.labelSmall, color = VolcanicGray)
                    }
                }
                if (pkg.isDownloaded) Icon(Icons.Default.CheckCircle, contentDescription = "Descargado", tint = StatusNormal)
            }
            if (running && download != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth(), color = AndeanBlue, trackColor = SurfaceContainerHighest)
                Text("${(download.progress * 100).toInt()} % · ${download.tilesDone}/${download.tilesTotal} teselas", style = MaterialTheme.typography.labelSmall, color = VolcanicGray, modifier = Modifier.padding(top = 4.dp))
            }
            download?.error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = StatusClosed) }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("~${pkg.sizeMb} MB", style = MaterialTheme.typography.bodyMedium, color = VolcanicGray, fontWeight = FontWeight.SemiBold)
                when {
                    running -> Text("Descargando…", color = AndeanBlue, style = MaterialTheme.typography.labelMedium)
                    pkg.isDownloaded -> Row {
                        TextButton(onClick = onDownload) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Actualizar") }
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StatusClosed, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text("Eliminar", color = StatusClosed, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    else -> Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = BrandFill), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Descargar mapa", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
