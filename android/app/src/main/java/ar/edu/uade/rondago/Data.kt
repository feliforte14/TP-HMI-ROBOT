package ar.edu.uade.rondago

import android.content.Context
import androidx.room.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@Entity(tableName = "records", primaryKeys = ["scope", "kind", "id"])
data class Record(val scope: String, val kind: String, val id: String, val json: String, val at: Double)
@Dao
interface Records {
    @Query("SELECT * FROM records ORDER BY at DESC") fun observe(): Flow<List<Record>>
    @Query("SELECT * FROM records WHERE scope=:scope AND kind=:kind AND id=:id LIMIT 1") suspend fun find(scope: String, kind: String, id: String): Record?
    @Query("SELECT * FROM records WHERE scope=:scope AND kind='command'") suspend fun commands(scope: String): List<Record>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)
}
@Database(entities = [Record::class], version = 1, exportSchema = false)
abstract class LocalDb : RoomDatabase() { abstract fun records(): Records }
interface Api {
    @GET("v1/state") suspend fun state(): State
    @POST("v1/control-session") suspend fun session(@Body body: Map<String, String>): Session
    @POST("v1/commands") suspend fun command(@Body order: Order): Command
    @GET("v1/commands/{id}") suspend fun result(@Path("id") id: String): Command
    @GET("v1/events") suspend fun events(@retrofit2.http.Query("after") cursor: Long): EventPage
}
private val Context.settings by preferencesDataStore("rondago_settings")
class Repository(context: Context) {
    private val settings = context.settings
    val db = Room.databaseBuilder(context, LocalDb::class.java, "rondago.db").build()
    val records = db.records()
    val gson = Gson()
    suspend fun preferences(): Triple<String, String, String> {
        val p = settings.data.first()
        var client = p[stringPreferencesKey("client")]
        if (client == null) { client = newId(); val id = client; settings.edit { it[stringPreferencesKey("client")] = id } }
        return Triple(p[stringPreferencesKey("mode")] ?: "LOCAL", p[stringPreferencesKey("url")] ?: "http://10.0.2.2:8080/", client)
    }
    suspend fun configure(mode: String, url: String) { settings.edit { it[stringPreferencesKey("mode")] = mode; it[stringPreferencesKey("url")] = url } }
    fun api(url: String, token: String): Api {
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
        return Retrofit.Builder().baseUrl(url).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java)
    }
    suspend fun saved(scope: String): State? = records.find(scope, "state", "latest")?.let { gson.fromJson(it.json, State::class.java) }
    suspend fun command(scope: String, c: Command) { records.put(Record(scope, "command", c.id, gson.toJson(c), c.updatedAt)) }
    suspend fun unresolved(scope: String) = records.commands(scope).map { gson.fromJson(it.json, Command::class.java) }.filter { it.status in listOf("REQUESTED", "ACCEPTED", "UNKNOWN") }
    suspend fun save(scope: String, s: State) {
        db.withTransaction {
            records.put(Record(scope, "state", "latest", gson.toJson(s), s.observedAt))
            s.events.forEach { records.put(Record(scope, "event", it.id, gson.toJson(it), it.observedAt)) }
            s.rounds.forEach { records.put(Record(scope, "round", it.id, gson.toJson(it), it.startedAt)) }
            s.commands.values.forEach { command(scope, it) }
        }
    }
}
