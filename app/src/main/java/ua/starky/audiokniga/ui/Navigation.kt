package ua.starky.audiokniga.ui

import android.app.Application
import android.util.Base64
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.starky.audiokniga.ui.library.LibraryScreen
import ua.starky.audiokniga.ui.library.LibraryViewModel
import ua.starky.audiokniga.ui.player.PlayerScreen
import ua.starky.audiokniga.ui.player.PlayerViewModel
import ua.starky.audiokniga.ui.search.SearchScreen
import ua.starky.audiokniga.ui.search.SearchViewModel
import ua.starky.audiokniga.ui.settings.SettingsScreen
import ua.starky.audiokniga.ui.settings.SettingsViewModel
import ua.starky.audiokniga.ui.settings.SourcesScreen

object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val PLAYER = "player/{bookId}"

    /**
     * Идентификатор книги едет в адресе экрана, а он же адрес URI — и это ловушка.
     *
     * У книги с устройства id выглядит как `local:content://…/tree/primary%3AKnigi%2FАвтор`,
     * и процентные последовательности внутри него значимы. Обычное кодирование не спасает:
     * Navigation декодирует аргумент сам, наш код декодировал бы второй раз, и `%3A`
     * превратилось бы в живое двоеточие — книга по такому id уже не находится. У лент и
     * подкастов id тоже со слэшами, так что беда общая.
     *
     * Base64 в url-safe виде решает это начисто: в нём нет ни слэшей, ни процентов,
     * ни плюсов, поэтому декодировать его может только тот, кто знает, что это Base64.
     */
    fun player(bookId: String): String = "player/" + encodeArgument(bookId)

    fun encodeArgument(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    fun decodeArgument(value: String): String = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrDefault(value)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    // Одно состояние меню на все разделы: свайп открывает его на любом из них.
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {

        composable(Routes.LIBRARY) {
            Shell(AppSection.SHELF, navController, drawerState) { openDrawer ->
                LibraryScreen(
                    viewModel = viewModel<LibraryViewModel>(),
                    onOpenDrawer = openDrawer,
                    onOpenSearch = { navController.navigateToSection(AppSection.SEARCH) },
                    onOpenBook = { bookId -> navController.navigate(Routes.player(bookId)) },
                )
            }
        }

        composable(Routes.SEARCH) {
            Shell(AppSection.SEARCH, navController, drawerState) { openDrawer ->
                SearchScreen(
                    viewModel = viewModel<SearchViewModel>(),
                    onOpenDrawer = openDrawer,
                    onOpenBook = { bookId -> navController.navigate(Routes.player(bookId)) },
                )
            }
        }

        composable(Routes.SOURCES) {
            Shell(AppSection.SOURCES, navController, drawerState) { openDrawer ->
                SourcesScreen(
                    viewModel = viewModel<SettingsViewModel>(),
                    onOpenDrawer = openDrawer,
                    onOpenBook = { bookId -> navController.navigate(Routes.player(bookId)) },
                )
            }
        }

        composable(Routes.SETTINGS) {
            Shell(AppSection.SETTINGS, navController, drawerState) { openDrawer ->
                SettingsScreen(
                    viewModel = viewModel<SettingsViewModel>(),
                    onOpenDrawer = openDrawer,
                )
            }
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = Routes.decodeArgument(entry.arguments?.getString("bookId").orEmpty())
            val vm: PlayerViewModel = viewModel(
                key = bookId,
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        PlayerViewModel(application, bookId) as T
                },
            )
            PlayerScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun Shell(
    section: AppSection,
    navController: NavHostController,
    drawerState: androidx.compose.material3.DrawerState,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    AppShell(
        section = section,
        drawerState = drawerState,
        onNavigate = { navController.navigateToSection(it) },
        content = content,
    )
}

/**
 * Переход между разделами меню не должен копить историю: возврат с любого из них
 * ведёт на полку, а не по цепочке открытых вкладок.
 */
private fun NavHostController.navigateToSection(section: AppSection) {
    navigate(section.route) {
        popUpTo(Routes.LIBRARY) { inclusive = section.route == Routes.LIBRARY }
        launchSingleTop = true
    }
}
