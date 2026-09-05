package ua.starky.audiohunter.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.app.Application
import androidx.compose.ui.platform.LocalContext
import java.net.URLDecoder
import java.net.URLEncoder
import ua.starky.audiohunter.ui.library.LibraryScreen
import ua.starky.audiohunter.ui.library.LibraryViewModel
import ua.starky.audiohunter.ui.player.PlayerScreen
import ua.starky.audiohunter.ui.player.PlayerViewModel
import ua.starky.audiohunter.ui.search.SearchScreen
import ua.starky.audiohunter.ui.search.SearchViewModel

object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val PLAYER = "player/{bookId}"
    fun player(bookId: String): String = "player/" + URLEncoder.encode(bookId, "UTF-8")
}

@Composable
fun AppNavigation(themeMode: Int, onToggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {

        composable(Routes.LIBRARY) {
            LibraryScreen(
                viewModel = viewModel(),
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenBook = { bookId -> navController.navigate(Routes.player(bookId)) },
            )
        }

        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel()
            SearchScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenBook = { bookId ->
                    navController.navigate(Routes.player(bookId)) {
                        popUpTo(Routes.SEARCH) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = URLDecoder.decode(entry.arguments?.getString("bookId").orEmpty(), "UTF-8")
            val vm: PlayerViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        PlayerViewModel(application, bookId) as T
                }
            )
            PlayerScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
