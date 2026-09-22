package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding
    private val executor = Executors.newSingleThreadExecutor()

    private data class StreamItem(
        val id: String,
        val name: String,
        val url: String
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMainScreenBinding.bind(view)
        Utils.handleInsetsAsPadding(binding.root)
        loadSavedLogin()
        binding.loginBtn.setOnClickListener { loginXtream() }
    }

    private fun loadSavedLogin() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        binding.serverUrl.setText(prefs.getString(KEY_SERVER, "") ?: "")
        binding.username.setText(prefs.getString(KEY_USERNAME, "") ?: "")
        binding.password.setText(prefs.getString(KEY_PASSWORD, "") ?: "")
        binding.rememberLogin.isChecked =
            !prefs.getString(KEY_SERVER, "").isNullOrBlank() &&
            !prefs.getString(KEY_USERNAME, "").isNullOrBlank() &&
            !prefs.getString(KEY_PASSWORD, "").isNullOrBlank()
    }

    private fun loginXtream() {
        val server = binding.serverUrl.text.toString().trim().removeSuffix("/")
        val username = binding.username.text.toString().trim()
        val password = binding.password.text.toString()

        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(requireContext(), R.string.xtream_fill_all, Toast.LENGTH_SHORT).show()
            return
        }

        val normalizedServer = normalizeServer(server)
        if (normalizedServer == null) {
            Toast.makeText(requireContext(), R.string.xtream_invalid_server, Toast.LENGTH_SHORT).show()
            return
        }

        binding.loginBtn.isEnabled = false
        binding.progress.isVisible = true

        executor.execute {
            var success = false
            var message = R.string.xtream_login_failed
            try {
                val u = URLEncoder.encode(username, "UTF-8")
                val p = URLEncoder.encode(password, "UTF-8")
                val apiUrl = "$normalizedServer/player_api.php?username=$u&password=$p"
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AudioMixIPTV")
                val code = connection.responseCode
                if (code in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val userInfo = JSONObject(body).optJSONObject("user_info")
                    success = userInfo?.optInt("auth", 0) == 1
                    if (!success) message = R.string.xtream_credentials_rejected
                } else {
                    message = R.string.xtream_server_error
                }
                connection.disconnect()
            } catch (_: Exception) {
                message = R.string.xtream_connection_failed
            }

            requireActivity().runOnUiThread {
                binding.loginBtn.isEnabled = true
                binding.progress.isVisible = false
                if (success) {
                    if (binding.rememberLogin.isChecked) saveLogin(normalizedServer, username, password)
                    else clearLogin()
                    loadLiveStreams(normalizedServer, username, password)
                } else {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadLiveStreams(server: String, username: String, password: String) {
        binding.loginBtn.isEnabled = false
        binding.progress.isVisible = true

        executor.execute {
            try {
                val u = URLEncoder.encode(username, "UTF-8")
                val p = URLEncoder.encode(password, "UTF-8")
                val apiUrl = "$server/player_api.php?username=$u&password=$p&action=get_live_streams"
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AudioMixIPTV")
                val code = connection.responseCode
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("HTTP $code")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONArray(body)
                val streams = ArrayList<StreamItem>(json.length())

                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val id = item.optString("stream_id").trim()
                    if (id.isBlank()) continue
                    val name = item.optString("stream_display_name")
                        .ifBlank { item.optString("name") }
                        .ifBlank { "Channel $id" }
                    val url = "$server/live/$u/$p/$id.ts"
                    streams.add(StreamItem(id, name, url))
                }

                requireActivity().runOnUiThread {
                    binding.loginBtn.isEnabled = true
                    binding.progress.isVisible = false
                    if (streams.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.xtream_no_live_channels, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.xtream_login_success, Toast.LENGTH_SHORT).show()
                        showVideoPicker(streams)
                    }
                }
            } catch (_: Exception) {
                requireActivity().runOnUiThread {
                    binding.loginBtn.isEnabled = true
                    binding.progress.isVisible = false
                    Toast.makeText(requireContext(), R.string.xtream_channels_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showVideoPicker(streams: List<StreamItem>) {
        showStreamPicker(R.string.xtream_select_video, streams) { video ->
            showStreamPicker(R.string.xtream_select_audio, streams) { audio ->
                launchPlayer(video, audio)
            }
        }
    }

    private fun showStreamPicker(
        titleRes: Int,
        streams: List<StreamItem>,
        onSelected: (StreamItem) -> Unit
    ) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        val search = EditText(context).apply {
            hint = getString(R.string.xtream_search_channel)
            setSingleLine(true)
        }
        val list = ListView(context)
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(root)
            .create()

        fun updateFilter(query: String) {
            val filtered = if (query.isBlank()) streams else streams.filter {
                it.name.contains(query, ignoreCase = true)
            }
            list.adapter = ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                filtered.map { it.name }
            )
            list.setOnItemClickListener { _, _, position, _ ->
                onSelected(filtered[position])
                dialog.dismiss()
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        updateFilter("")
        dialog.show()
    }

    private fun launchPlayer(video: StreamItem, audio: StreamItem) {
        val intent = Intent(requireContext(), MPVActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(video.url)
            putExtra(EXTRA_EXTERNAL_AUDIO_URL, audio.url)
            putExtra("title", video.name)
        }
        startActivity(intent)
    }

    private fun normalizeServer(server: String): String? {
        return try {
            val uri = URI(if (server.contains("://")) server else "http://$server")
            val scheme = uri.scheme?.lowercase() ?: return null
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
            var path = uri.path.orEmpty().trimEnd('/')
            if (path.endsWith("/player_api.php", ignoreCase = true))
                path = path.removeSuffix("/player_api.php")
            if (path.endsWith("/panel_api.php", ignoreCase = true))
                path = path.removeSuffix("/panel_api.php")
            URI(scheme, uri.userInfo, uri.host, uri.port, path.ifBlank { null }, null, null)
                .toString().trimEnd('/')
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLogin(server: String, username: String, password: String) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    private fun clearLogin() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .remove(KEY_SERVER)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    override fun onDestroyView() {
        executor.shutdownNow()
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SERVER = "xtream_server"
        private const val KEY_USERNAME = "xtream_username"
        private const val KEY_PASSWORD = "xtream_password"
        const val EXTRA_EXTERNAL_AUDIO_URL = "external_audio_url"
    }
}
