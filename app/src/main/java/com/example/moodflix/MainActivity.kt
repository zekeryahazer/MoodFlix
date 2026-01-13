package com.example.moodflix

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.moodflix.ui.theme.* import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable data class MoodResponse(val analysis: String = "", val recommendation: String = "", val films: List<String> = emptyList())
data class MovieUiModel(val title: String, val posterUrl: String?)
data class TmdbSearchResponse(val results: List<TmdbMovieResult>)
data class TmdbMovieResult(val poster_path: String?, val title: String)

interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovie(@Query("api_key") apiKey: String, @Query("query") query: String): TmdbSearchResponse
}

object RetrofitClient {
    val api: TmdbApi by lazy {
        Retrofit.Builder().baseUrl("https://api.themoviedb.org/3/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(TmdbApi::class.java)
    }
}

class MoodViewModel : ViewModel() {
    var moodResult by mutableStateOf<MoodResponse?>(null)
    var moviePosters by mutableStateOf<List<MovieUiModel>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    private val geminiApiKey = ""//Gemini Api giriniz
    private val tmdbApiKey = ""//TMDB Api giriniz
    private val generativeModel = GenerativeModel("gemini-2.5-flash", geminiApiKey)
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun analyzeMood(text: String) {
        if (text.isBlank()) return
        performAnalysis { generativeModel.generateContent("""
            Sen bir sinema terapistisin. Kullanıcı: '$text'. Duyguyu analiz et ve 3 film öner.
            SADECE JSON: { "analysis": "...", "recommendation": "...", "films": ["Film1", "Film2", "Film3"] }
        """.trimIndent()).text }
    }

    fun analyzeStyle(bitmap: Bitmap) {
        performAnalysis { generativeModel.generateContent(content {
            text("""Bu tarza uygun 3 film öner. SADECE JSON: { "analysis": "...", "recommendation": "...", "films": ["Film1", "Film2", "Film3"] }""")
            image(bitmap)
        }).text }
    }

    private fun performAnalysis(apiCall: suspend () -> String?) {
        isLoading = true; errorMessage = null
        viewModelScope.launch {
            try {
                val cleanJson = apiCall()?.replace("```json", "")?.replace("```", "")?.trim() ?: ""
                val data = jsonParser.decodeFromString<MoodResponse>(cleanJson)
                withContext(Dispatchers.Main) { moodResult = data }

                val movies = data.films.map { name ->
                    async(Dispatchers.IO) {
                        try {
                            val path = RetrofitClient.api.searchMovie(tmdbApiKey, name).results.firstOrNull()?.poster_path
                            MovieUiModel(name, if (path != null) "https://image.tmdb.org/t/p/w500$path" else null)
                        } catch (e: Exception) { MovieUiModel(name, null) }
                    }
                }.awaitAll()
                withContext(Dispatchers.Main) { moviePosters = movies }
            } catch (e: Exception) { errorMessage = "Hata: ${e.localizedMessage}" }
            finally { isLoading = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = DeepBlack, surface = DarkSurface, primary = NeonRed, onBackground = LightText)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DeepBlack, Color(0xFF180518))))) {
                    MoodFlixApp(viewModel())
                }
            }
        }
    }
}

@Composable
fun MoodFlixApp(viewModel: MoodViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color.Black.copy(0.9f)) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Face, null) }, label = { Text("Mood") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = NeonRed.copy(0.2f), selectedIconColor = NeonRed))
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.CameraAlt, null) }, label = { Text("Stil") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = BrightCyan.copy(0.2f), selectedIconColor = BrightCyan))
            }
        }
    ) { p -> Column(Modifier.padding(p)) { Header(); if (tab == 0) MoodScreen(viewModel) else StyleScreen(viewModel) } }
}

@Composable
fun Header() {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(Brush.horizontalGradient(listOf(NeonRed, ElectricPurple)), CircleShape), Alignment.Center) {
            Icon(Icons.Rounded.Movie, null, tint = Color.White)
        }
        Spacer(Modifier.width(16.dp))
        Text("MOODFLIX", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = LightText)
    }
}

@Composable
fun MoodScreen(vm: MoodViewModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Nasıl hissediyorsun?", style = MaterialTheme.typography.titleLarge, color = LightText)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().height(120.dp).shadow(8.dp, RoundedCornerShape(24.dp), spotColor = NeonRed),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonRed, unfocusedBorderColor = Color.DarkGray, focusedTextColor = LightText, unfocusedTextColor = LightText, focusedContainerColor = DarkSurface, unfocusedContainerColor = DarkSurface),
            placeholder = { Text("Örn: Yağmurlu havada kahve içiyorum...") }
        )
        Spacer(Modifier.height(24.dp))
        ModernButton("Film Öner 🔮", vm.isLoading, NeonRed) { vm.analyzeMood(text) }
        ResultSection(vm)
    }
}

@Composable
fun StyleScreen(vm: MoodViewModel) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { bitmap = context.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) } }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let { bitmap = it }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(30.dp))
            .background(DarkSurface).border(2.dp, if (bitmap == null) Color.Gray else BrightCyan, RoundedCornerShape(30.dp)),
            Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.AddPhotoAlternate, null, tint = BrightCyan, modifier = Modifier.size(60.dp))
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { cameraLauncher.launch() }, modifier = Modifier.weight(1f).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)) {
                Icon(Icons.Default.CameraAlt, null, tint = BrightCyan); Spacer(Modifier.width(8.dp)); Text("Kamera", color = BrightCyan)
            }
            Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)) {
                Icon(Icons.Default.PhotoLibrary, null, tint = BrightCyan); Spacer(Modifier.width(8.dp)); Text("Galeri", color = BrightCyan)
            }
        }
        Spacer(Modifier.height(20.dp))
        ModernButton("Analiz Et ✨", vm.isLoading, BrightCyan) { bitmap?.let { vm.analyzeStyle(it) } }
        ResultSection(vm)
    }
}

@Composable
fun ResultSection(vm: MoodViewModel) {
    val context = LocalContext.current
    AnimatedVisibility(vm.moodResult != null) {
        vm.moodResult?.let { data ->
            Column(Modifier.padding(top = 32.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(0.9f)), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("🤖 AI Analizi", color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text(data.analysis, color = LightText); Spacer(Modifier.height(8.dp))
                        Text("💡 ${data.recommendation}", color = AccentColor)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("🎬 Önerilen Filmler", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = LightText)
                vm.moviePosters.forEach { m ->
                    MovieCard(m) {
                        val query = Uri.encode("${m.title} fragman")
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query")))
                    }
                }
            }
        }
    }
    vm.errorMessage?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 16.dp)) }
}

@Composable
fun MovieCard(movie: MovieUiModel, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(120.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
        Row {
            if (movie.posterUrl != null) AsyncImage(movie.posterUrl, null, Modifier.width(80.dp).fillMaxHeight(), contentScale = ContentScale.Crop)
            else Box(Modifier.width(80.dp).fillMaxHeight().background(Color.DarkGray))
            Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                Text(movie.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LightText)
                Text("YouTube'da izle ►", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ModernButton(text: String, loading: Boolean, color: Color, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(56.dp).shadow(12.dp, RoundedCornerShape(16.dp), spotColor = color),
        colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(16.dp), enabled = !loading) {
        if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
        else Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
