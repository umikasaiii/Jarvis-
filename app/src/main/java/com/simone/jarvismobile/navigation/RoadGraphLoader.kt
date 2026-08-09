package com.simone.jarvismobile.navigation

import android.util.Log
import com.simone.jarvismobile.core.navigation.LatLng
import com.simone.jarvismobile.core.navigation.RoadGraph
import com.simone.jarvismobile.core.navigation.RoadNode
import org.json.JSONObject
import java.io.File

/**
 * Loads a region's routing graph from its local routing file into the pure
 * [RoadGraph] the A* router consumes. The file is a compact JSON exported from a
 * routing tool (or a BRouter conversion): a list of nodes and undirected links
 * with road attributes. Parsing is the only Android-specific bit; the routing
 * itself stays in `:core` and is unit-tested.
 *
 * Format (navigation/maps/<id>/routing.json):
 *   {
 *     "nodes": [ { "id": 1, "lat": 41.9, "lon": 12.5 }, … ],
 *     "links": [ { "from":1,"to":2,"name":"Via …","class":"primary",
 *                  "speed":50,"oneway":false,"toll":false,"ferry":false,
 *                  "geometry":[[lon,lat],…] }, … ]
 *   }
 */
object RoadGraphLoader {
    private const val TAG = "JarvisRoadGraph"
    const val ROUTING_FILE = "routing.json"

    fun load(regionDir: File): RoadGraph? {
        val file = File(regionDir, ROUTING_FILE)
        if (!file.exists()) return null
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "graph_load_failed ${it.javaClass.simpleName}")
            null
        }
    }

    fun parse(text: String): RoadGraph {
        val root = JSONObject(text)
        val nodesJson = root.getJSONArray("nodes")
        val nodes = ArrayList<RoadNode>(nodesJson.length())
        for (i in 0 until nodesJson.length()) {
            val n = nodesJson.getJSONObject(i)
            nodes += RoadNode(n.getLong("id"), LatLng(n.getDouble("lat"), n.getDouble("lon")))
        }
        val linksJson = root.getJSONArray("links")
        val links = ArrayList<RoadGraph.Link>(linksJson.length())
        for (i in 0 until linksJson.length()) {
            val l = linksJson.getJSONObject(i)
            val geom = ArrayList<LatLng>()
            l.optJSONArray("geometry")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val c = arr.getJSONArray(j)
                    geom += LatLng(c.getDouble(1), c.getDouble(0)) // [lon, lat]
                }
            }
            links += RoadGraph.Link(
                from = l.getLong("from"),
                to = l.getLong("to"),
                geometry = geom,
                roadName = l.optString("name", ""),
                roadClass = l.optString("class", "residential"),
                speedKmh = l.optDouble("speed", 50.0),
                oneway = l.optBoolean("oneway", false),
                toll = l.optBoolean("toll", false),
                ferry = l.optBoolean("ferry", false),
            )
        }
        return RoadGraph.fromLinks(nodes, links)
    }
}
