package ec.travesias.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ec.travesias.app.data.local.AppSettings
import ec.travesias.app.data.social.AccountState
import ec.travesias.app.service.TravesiasBackgroundService
import ec.travesias.app.ui.auth.SplashTravesias
import ec.travesias.app.ui.i18n.t
import ec.travesias.app.ui.screens.*
import ec.travesias.app.ui.theme.AndeanBlue
import ec.travesias.app.ui.theme.AppTheme
import ec.travesias.app.ui.theme.HairLine
import ec.travesias.app.ui.theme.MutedGray
import ec.travesias.app.ui.theme.SurfaceContainerLowest
import ec.travesias.app.viewmodel.CommunityViewModel
import ec.travesias.app.viewmodel.RouteViewModel
import ec.travesias.app.viewmodel.TravelViewModel
import kotlinx.coroutines.flow.merge
import kotlin.math.roundToInt

// FragmentActivity y no ComponentActivity: BiometricPrompt (entrar con huella o patrón)
// solo funciona sobre una FragmentActivity.
class MainActivity : FragmentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBackgroundServiceIfPossible() }

    /**
     * Chat que hay que abrir porque el viajero tocó una notificación de mensaje.
     * Es un estado y no un simple extra porque la notificación puede llegar con la app ya
     * abierta: entonces entra por onNewIntent y la pantalla tiene que enterarse.
     */
    private val chatPendiente = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialPlaceId = intent?.getStringExtra(EXTRA_OPEN_PLACE_ID)
        val initialRoute = intent?.getStringExtra(EXTRA_OPEN_ROUTE)
        chatPendiente.value = intent?.getStringExtra(EXTRA_OPEN_CHAT)

        // Notificaciones (Android 13+) y arranque del servicio en segundo plano.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startBackgroundServiceIfPossible()
        }

        setContent {
            AppTheme {
                MainScreen(
                    initialPlaceId = initialPlaceId,
                    initialRoute = initialRoute,
                    abrirChat = chatPendiente.value,
                    onChatAbierto = { chatPendiente.value = null }
                )
            }
        }
    }

    /** La app ya estaba abierta y tocaron una notificación: se lleva al chat de ese mensaje. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val chat = intent.getStringExtra(EXTRA_OPEN_CHAT)
        if (!chat.isNullOrBlank()) chatPendiente.value = chat
    }

    /** Arranca el vigilante de la ruta solo si el viajero lo pidió; se apaga solo si no hay ruta. */
    private fun startBackgroundServiceIfPossible() {
        TravesiasBackgroundService.start(this)
    }

    companion object {
        const val EXTRA_OPEN_PLACE_ID = "open_place_id"
        const val EXTRA_OPEN_ROUTE = "open_route"

        /** Id del grupo/chat que hay que abrir al tocar la notificación de un mensaje. */
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}

object Routes {
    const val HOME = "home"
    const val MAP = "map"
    const val DISCOVER = "discover"
    const val ROUTE = "route"
    const val COMMUNITY = "community"
    const val KIT = "kit"
    const val CHAT = "chat"
    const val PLANNER = "planner"
    const val TRIPS = "trips"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainScreen(
    travelVm: TravelViewModel = viewModel(),
    routeVm: RouteViewModel = viewModel(),
    communityVm: CommunityViewModel = viewModel(),
    initialPlaceId: String? = null,
    initialRoute: String? = null,
    /** Chat que hay que abrir porque llegó por una notificación. */
    abrirChat: String? = null,
    onChatAbierto: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }

    // Unifica todos los mensajes en un solo collector para que no se pierda ninguno
    LaunchedEffect(Unit) {
        merge(routeVm.events, communityVm.message, travelVm.events).collect { snackbar.showSnackbar(it) }
    }

    // Apertura desde "Compartir a la app": el mapa sigue disponible aunque Inicio sea la portada.
    LaunchedEffect(initialPlaceId, initialRoute) {
        if (initialPlaceId != null) {
            routeVm.selectPlaceById(initialPlaceId)
            navController.navigateTab(Routes.MAP)
        }
        if (initialRoute != null) navController.navigateTab(initialRoute)
    }

    // Tocaron la notificación de un mensaje: se entra a Viajeros y se abre ESE chat, no la
    // pantalla por la que se andaba.
    LaunchedEffect(abrirChat) {
        val chat = abrirChat
        if (!chat.isNullOrBlank()) {
            communityVm.openGroup(chat)
            navController.navigateTab(Routes.COMMUNITY)
            onChatAbierto()
        }
    }

    val lang by AppSettings.language.collectAsState()
    val cuenta by communityVm.accountState.collectAsState()
    val navItems = listOf(
        NavItem(Routes.HOME, t(lang, "Inicio", "Home"), Icons.Default.Home),
        NavItem(Routes.DISCOVER, t(lang, "Descubrir", "Discover"), Icons.Default.Radar),
        NavItem(Routes.ROUTE, t(lang, "Mi Ruta", "My Route"), Icons.Default.Route),
        NavItem(Routes.COMMUNITY, t(lang, "Viajeros", "Travelers"), Icons.Default.Groups),
        NavItem(Routes.CHAT, t(lang, "Guía IA", "AI Guide"), Icons.Default.SmartToy),
        NavItem(Routes.KIT, t(lang, "Kit", "Kit"), Icons.Default.HealthAndSafety)
    )

    // Solo mientras se restaura la sesión guardada en ESTE teléfono. Después se entra
    // siempre, con cuenta o sin ella: la cuenta se pide dentro de Viajeros y en ningún
    // otro sitio. Antes esta pantalla era una puerta y nadie llegaba a ver la app.
    if (cuenta is AccountState.Loading) {
        SplashTravesias()
        return
    }

    // La portada NO lleva barra: su menú es el del panel de vidrio, y así se ve el paisaje entero.
    // Comunidad tampoco: tiene su propia barra dentro.
    val conBarra = currentRoute != Routes.HOME && currentRoute != Routes.COMMUNITY

    // La barra se va hacia abajo cuando el viajero baja por la pantalla y vuelve en cuanto sube.
    // Con esto las listas largas (Descubrir, Mi Ruta, Kit) ganan una barra entera de alto.
    val altoBarra = with(LocalDensity.current) { 86.dp.toPx() }
    // Se guarda solo "visible sí o no", no el pixel exacto: así la pantalla no se recompone
    // en cada dedo de scroll, que es lo que pone la app a tirones.
    var barraVisible by remember { mutableStateOf(true) }
    // Al cambiar de pestaña la barra siempre reaparece: si no, se queda escondida sin motivo.
    LaunchedEffect(currentRoute) { barraVisible = true }
    val escondeBarra = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // El umbral evita que un temblor del dedo la haga parpadear.
                if (available.y < -4f) barraVisible = false
                else if (available.y > 4f) barraVisible = true
                return Offset.Zero
            }
        }
    }
    val corrimiento = animateFloatAsState(
        targetValue = if (barraVisible) 0f else altoBarra,
        animationSpec = tween(220),
        label = "barra"
    )
    // Cuando la barra se va, el contenido tiene que OCUPAR ese alto. Si no, queda una
    // franja vacía abajo que sigue tapando la pantalla y no se gana nada.
    val altoBarraDp = with(LocalDensity.current) { altoBarra.toDp() }
    val huecoQueSobra by animateDpAsState(
        targetValue = if (barraVisible) 0.dp else altoBarraDp,
        animationSpec = tween(220),
        label = "hueco"
    )

    Scaffold(
        modifier = if (conBarra) Modifier.nestedScroll(escondeBarra) else Modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (conBarra) {
                Column(Modifier.offset { IntOffset(0, corrimiento.value.roundToInt()) }) {
                    // Línea de pelo en vez de sombra: es lo que hace que se vea limpio.
                    HorizontalDivider(thickness = 1.dp, color = HairLine)
                    NavigationBar(containerColor = SurfaceContainerLowest, tonalElevation = 0.dp) {
                        navItems.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(22.dp)) },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                selected = selected,
                                onClick = { navController.navigateTab(item.route) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AndeanBlue,
                                    selectedTextColor = AndeanBlue,
                                    unselectedIconColor = MutedGray,
                                    unselectedTextColor = MutedGray,
                                    indicatorColor = AndeanBlue.copy(alpha = 0.10f)
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // La portada va a pantalla completa (el paisaje pasa por detrás de las barras);
        // las demás pantallas se meten dentro de los márgenes del Scaffold.
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Routes.HOME) {
                HomeHubScreen(
                    espacioAbajo = innerPadding.calculateBottomPadding(),
                    routeVm = routeVm,
                    onOpenDiscover = { navController.navigateTab(Routes.DISCOVER) },
                    onOpenRoute = { navController.navigateTab(Routes.ROUTE) },
                    onOpenMap = { navController.navigateTab(Routes.MAP) },
                    onOpenChat = { navController.navigateTab(Routes.CHAT) },
                    onOpenCommunity = { navController.navigateTab(Routes.COMMUNITY) },
                    onOpenKit = { navController.navigateTab(Routes.KIT) }
                )
            }
            composable(Routes.MAP) {
              Pantalla(innerPadding, huecoQueSobra) {
                MapScreen(
                    viewModel = routeVm,
                    onOpenDiscover = { navController.navigateTab(Routes.DISCOVER) },
                    onOpenRoute = { navController.navigateTab(Routes.ROUTE) }
                )
              }
            }
            composable(Routes.DISCOVER) {
              Pantalla(innerPadding, huecoQueSobra) {
                DiscoverScreen(
                    viewModel = routeVm,
                    onOpenMap = { navController.navigateTab(Routes.MAP) },
                    onOpenCommunityShare = { placeId ->
                        communityVm.sharePlaceById(placeId)
                        navController.navigateTab(Routes.COMMUNITY)
                    }
                )
              }
            }
            composable(Routes.ROUTE) {
              Pantalla(innerPadding, huecoQueSobra) {
                RouteScreen(
                    viewModel = routeVm,
                    onOpenMap = { navController.navigateTab(Routes.MAP) },
                    onOpenDiscover = { navController.navigateTab(Routes.DISCOVER) },
                    onOpenPlanner = { navController.navigate(Routes.PLANNER) },
                    onShareRouteToGroup = { communityVm.shareActiveRoute(); navController.navigateTab(Routes.COMMUNITY) },
                    // "Que la Guía IA lo arregle": manda el pedido al chat y lo abre.
                    onAskGuide = { pedido -> travelVm.sendChatMessage(pedido); navController.navigateTab(Routes.CHAT) }
                )
              }
            }
            composable(Routes.COMMUNITY) {
              Pantalla(innerPadding, huecoQueSobra) {
                CommunityScreen(
                    community = communityVm,
                    routeVm = routeVm,
                    onOpenMap = { navController.navigateTab(Routes.MAP) },
                    // Atrás desde Viajeros vuelve a la portada, que es de donde se llega
                    // cuando alguien todavía no tiene cuenta.
                    onBackToRoute = { navController.navigateTab(Routes.HOME) }
                )
              }
            }
            composable(Routes.CHAT) {
              Pantalla(innerPadding, huecoQueSobra) {
                AiChatScreen(viewModel = travelVm, onOpenRoute = { navController.navigateTab(Routes.ROUTE) })
              }
            }
            composable(Routes.KIT) { Pantalla(innerPadding, huecoQueSobra) { ToolsScreen(viewModel = travelVm) } }
            composable(Routes.PLANNER) {
              Pantalla(innerPadding, huecoQueSobra) {
                PlannerScreen(viewModel = travelVm, onNavigateToTrips = { navController.navigate(Routes.TRIPS) })
              }
            }
            composable(Routes.TRIPS) {
              Pantalla(innerPadding, huecoQueSobra) {
                TripsScreen(viewModel = travelVm, onNavigateToPlanner = { navController.navigate(Routes.PLANNER) })
              }
            }
        }
    }
}

/** Mete una pantalla dentro de los márgenes del Scaffold (barra de estado y barra inferior). */
@Composable
private fun Pantalla(margenes: PaddingValues, quitarAbajo: Dp = 0.dp, contenido: @Composable () -> Unit) {
    val abajo = (margenes.calculateBottomPadding() - quitarAbajo).coerceAtLeast(0.dp)
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = margenes.calculateTopPadding(), bottom = abajo)
    ) { contenido() }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
