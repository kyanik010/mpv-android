package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class XtreamBrowserFragment : Fragment(R.layout.fragment_xtream_browser) {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var categoriesBox: LinearLayout
    private lateinit var channelsList: ListView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var server = ""
    private var username = ""
    private var password = ""
    private var categories = mutableListOf<Category>()
    private var allChannels = mutableListOf<Channel>()
    private var visibleChannels = mutableListOf<Channel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        categoriesBox = view.findViewById(R.id.xtream_categories)
        channelsList = view.findViewById(R.id.xtream_channels)
        status = view.findViewById(R.id.xtream_status)
        progress = view.findViewById(R.id.xtream_progress)
        server = requireArguments().getString(ARG_SERVER).orEmpty()
        username = requireArguments().getString(ARG_USERNAME).orEmpty()
        password = requireArguments().getString(ARG_PASSWORD).orEmpty()
        channelsList.setOnItemClickListener { _, _, position, _ ->
            if (position in visibleChannels.indices) play(visibleChannels[position])
        }
        loadData()
    }

    private fun loadData() {
        setLoading(true)
        executor.execute {
            try {
                val cats = requestArray("get_live_categories")
                val streams = requestArray("get_live_streams")
                val parsedCats = mutableListOf<Category>()
                parsedCats.add(Category("", "All Channels"))
                for (n in 0 until cats.length()) {
                    val o = cats.optJSONObject(n) ?: continue
                    parsedCats.add(Category(o.optString("category_id"), o.optString("category_name")))
                }
                val parsedChannels = mutableListOf<Channel>()
                for (n in 0 until streams.length()) {
                    val o = streams.optJSONObject(n) ?: continue
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) parsedChannels.add(
                        Channel(id, o.optString("name").ifBlank { "Channel " + id }, o.optString("category_id"))
                    )
                }
                requireActivity().runOnUiThread {
                    categories = parsedCats
                    allChannels = parsedChannels
                    setLoading(false)
                    renderCategories()
                    filter(null)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    setLoading(false)
                    status.text = "Failed to load IPTV: " + (e.message ?: "unknown error")
                    Toast.makeText(requireContext(), status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestArray(action: String): JSONArray {
        val u = URLEncoder.encode(username, "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        val a = URLEncoder.encode(action, "UTF-8")
        val url = URL(server + "/player_api.php?username=" + u + "&password=" + p + "&action=" + a)
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) mpv-android")
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP " + code)
            JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun renderCategories() {
        categoriesBox.removeAllViews()
        categories.forEach { category ->
            val b = Button(requireContext()).apply {
                text = category.name
                isAllCaps = false
                setOnClickListener { filter(category.id.ifBlank { null }) }
            }
            categoriesBox.addView(b, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(6) })
        }
    }

    private fun filter(categoryId: String?) {
        visibleChannels = if (categoryId == null) allChannels.toMutableList()
        else allChannels.filter { it.categoryId == categoryId }.toMutableList()
        channelsList.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            visibleChannels.map { it.name })
        status.text = visibleChannels.size.toString() + " channels"
    }

    private fun play(channel: Channel) {
        val url = server + "/live/" + Uri.encode(username) + "/" + Uri.encode(password) + "/" + channel.id + ".ts"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setClass(requireContext(), MPVActivity::class.java)
        })
    }

    private fun setLoading(value: Boolean) {
        progress.isVisible = value
        if (value) status.text = "Loading IPTV..."
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        executor.shutdownNow()
        super.onDestroyView()
    }

    data class Category(val id: String, val name: String)
    data class Channel(val id: String, val name: String, val categoryId: String)

    companion object {
        private const val ARG_SERVER = "server"
        private const val ARG_USERNAME = "username"
        private const val ARG_PASSWORD = "password"
        fun newInstance(server: String, username: String, password: String) =
            XtreamBrowserFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER, server)
                    putString(ARG_USERNAME, username)
                    putString(ARG_PASSWORD, password)
                }
            }
    }
}