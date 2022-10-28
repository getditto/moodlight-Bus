package live.ditto.moodlight

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.core.content.ContextCompat
import com.couchbase.lite.*
//import com.google.gson.GsonBuilder
import live.ditto.Ditto
import live.ditto.DittoDocumentID
import live.ditto.DittoPendingCursorOperation

interface TaskManagerRepository {
    fun updateColors(id: String, red: Double, green: Double, blue: Double)
    fun updateIsOFF(id: String, isOff: Boolean)
    fun getInitialColors(id: String): Int
    fun findAllTask(): Any
    fun getDatabaseLights(): Database

}

open class CBTaskManagerLocalRepositoryImpl(private val dbPath: String) : TaskManagerRepository {
    val database: Database = Database(
        "lights",
        DatabaseConfigurationFactory.create(dbPath)
    )


    override fun updateColors(id: String, red: Double, green: Double, blue: Double) {
        val doc = database.getDocument(id)
        val mutableDoc = doc?.toMutable()
        if (mutableDoc != null) {
            mutableDoc.setDouble("red", red)
            mutableDoc.setDouble("green", green)
            mutableDoc.setDouble("blue", blue)
            database.save(mutableDoc)
        }
        else {
            println("Error in updating document")
        }
    }

    override fun updateIsOFF(id: String, isOff: Boolean) {
        val doc = database.getDocument(id)
        val mutableDoc = doc?.toMutable()
        if (mutableDoc != null) {
            mutableDoc.setBoolean("isOff", isOff)
            database.save(mutableDoc)
        }
        else {
            println("Error in updating document")
        }
    }

   override fun getInitialColors(id: String): Int {
        val doc = database.getDocument(id)
       return if (doc != null) {
           val red = doc.getDouble("red")
           val green = doc.getDouble("green")
           val blue = doc.getDouble("blue")
           Color.rgb(red.toInt(), green.toInt(), blue.toInt())
       } else {
           val doc = MutableDocument("5")
           doc.setDouble("red", 100.0)
           doc.setDouble("green", 100.0)
           doc.setDouble("blue", 100.0)
           database.save(doc)
           Color.rgb(100, 100, 100)
       }
    }

    override fun findAllTask(): Any {
        return QueryBuilder
            .select(SelectResult.expression(Meta.id), SelectResult.all())
            .from(DataSource.database(database))    }

    override fun getDatabaseLights(): Database {
        return database
    }

}