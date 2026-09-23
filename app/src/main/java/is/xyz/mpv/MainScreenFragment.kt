package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.*
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

    private data class StreamItem(val id: String, val name: String, val url: String)
    private data class XtreamAccount(val server: String, val username: String, val password: String)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMainScreenBinding.bind(view)
        Utils.handleInsetsAsPadding(binding.root)
        loadSaved()
        binding.loginBtn.setOnClickListener { connect() }
    }

    private fun loadSaved() {
        val p = PreferenceManager.getDefaultSharedPreferences(requireContext())
        binding.videoServerUrl.setText(p.getString(KEY_V_SERVER, "") ?: "")
        binding.videoUsername.setText(p.getString(KEY_V_USER, "") ?: "")
        binding.videoPassword.setText(p.getString(KEY_V_PASS, "") ?: "")
        binding.audioServerUrl.setText(p.getString(KEY_A_SERVER, "") ?: "")
        binding.audioUsername.setText(p.getString(KEY_A_USER, "") ?: "")
        binding.audioPassword.setText(p.getString(KEY_A_PASS, "") ?: "")
        binding.rememberLogin.isChecked = p.getBoolean(KEY_REMEMBER, false)
    }

    private fun connect() {
        val video = readAccount(binding.videoServerUrl, binding.videoUsername, binding.videoPassword)
        val audio = readAccount(binding.audioServerUrl, binding.audioUsername, binding.audioPassword)
        if (video == null || audio == null) {
            toast(R.string.xtream_fill_all)
            return
        }
        if (binding.rememberLogin.isChecked) save(video, audio) else clearSaved()
        binding.loginBtn.isEnabled = false
        binding.progress.isVisible = true
        executor.execute {
            try {
                val videoStreams = authenticateAndLoad(video)
                val audioStreams = authenticateAndLoad(audio)
                requireActivity().runOnUiThread {
                    binding.loginBtn.isEnabled = true
                    binding.progress.isVisible = false
                    if (videoStreams.isEmpty()) toast(R.string.xtream_no_video_channels)
                    else if (audioStreams.isEmpty()) toast(R.string.xtream_no_audio_channels)
                    else showPicker(videoStreams, audioStreams)
                }
            } catch (_: Exception) {
                requireActivity().runOnUiThread {
                    binding.loginBtn.isEnabled = true
                    binding.progress.isVisible = false
                    toast(R.string.xtream_connection_failed)
                }
            }
        }
    }

    private fun readAccount(s: EditText, u: EditText, p: EditText): XtreamAccount? {
        val server = normalizeServer(s.text.toString().trim()) ?: return null
        val user = u.text.toString().trim()
        val pass = p.text.toString()
        return if (user.isNotBlank() && pass.isNotBlank()) XtreamAccount(server, user, pass) else null
    }

    private fun authenticateAndLoad(a: XtreamAccount): List<StreamItem> {
        val user = URLEncoder.encode(a.username, "UTF-8")
        val pass = URLEncoder.encode(a.password, "UTF-8")
        val api = URL("${a.server}/player_api.php?username=$user&password=$pass")
        val c = api.openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val info = JSONObject(body).optJSONObject("user_info")
        if (info?.optInt("auth", 0) != 1) throw IllegalArgumentException("auth")
        val live = URL("${a.server}/player_api.php?username=$user&password=$pass&action=get_live_streams")
        val lc = live.openConnection() as HttpURLConnection
        lc.connectTimeout = 20000
        lc.readTimeout = 30000
        val liveBody = lc.inputStream.bufferedReader().use { it.readText() }
        lc.disconnect()
        val arr = JSONArray(liveBody)
        val out = ArrayList<StreamItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("stream_id").trim()
            if (id.isBlank()) continue
            val name = o.optString("stream_display_name").ifBlank { o.optString("name") }.ifBlank { "Channel $id" }
            out += StreamItem(id, name, "${a.server}/live/$user/$pass/$id.ts")
        }
        return out
    }

    private fun showPicker(video: List<StreamItem>, audio: List<StreamItem>) {
        pick(R.string.xtream_select_video, video) { v ->
            pick(R.string.xtream_select_audio, audio) { a ->
                val intent = Intent(requireContext(), MPVActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(v.url)
                    putExtra(EXTRA_EXTERNAL_AUDIO_URL, a.url)
                    putExtra(EXTRA_EXTERNAL_AUDIO_NAME, a.name)
                    putExtra(EXTRA_EXTERNAL_AUDIO_URLS, audio.map { it.url }.toTypedArray())
                    putExtra(EXTRA_EXTERNAL_AUDIO_NAMES, audio.map { it.name }.toTypedArray())
                    putExtra("title", v.name)
                }
                startActivity(intent)
            }
        }
    }

    private fun pick(title: Int, items: List<StreamItem>, done: (StreamItem) -> Unit) {
        val ctx = requireContext()
        val search = EditText(ctx).apply { hint = getString(R.string.xtream_search_channel); setSingleLine() }
        val list = ListView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle(title).setView(box).create()
        fun refresh(q: String) {
            val filtered = if (q.isBlank()) items else items.filter { it.name.contains(q, true) }
            list.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, filtered.map { it.name })
            list.setOnItemClickListener { _, _, pos, _ -> done(filtered[pos]); dialog.dismiss() }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        refresh("")
        dialog.show()
    }

    private fun save(v: XtreamAccount, a: XtreamAccount) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .putString(KEY_V_SERVER, v.server).putString(KEY_V_USER, v.username).putString(KEY_V_PASS, v.password)
            .putString(KEY_A_SERVER, a.server).putString(KEY_A_USER, a.username).putString(KEY_A_PASS, a.password)
            .putBoolean(KEY_REMEMBER, true).apply()
    }

    private fun clearSaved() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .remove(KEY_V_SERVER).remove(KEY_V_USER).remove(KEY_V_PASS)
            .remove(KEY_A_SERVER).remove(KEY_A_USER).remove(KEY_A_PASS)
            .putBoolean(KEY_REMEMBER, false).apply()
    }

    private fun normalizeServer(raw: String): String? {
        return try {
            val uri = URI(if (raw.contains("://")) raw else "http://$raw")
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            var path = uri.path.orEmpty().trimEnd('/')
            if (path.endsWith("/player_api.php", true)) path = path.removeSuffix("/player_api.php")
            if (path.endsWith("/panel_api.php", true)) path = path.removeSuffix("/panel_api.php")
            URI(scheme, uri.userInfo, uri.host, uri.port, path.ifBlank { null }, null, null).toString().trimEnd('/')
        } catch (_: Exception) {
            null
        }
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        executor.shutdownNow()
        super.onDestroyView()
    }

    companion object {
        private const val KEY_V_SERVER = "dual_video_server"
        private const val KEY_V_USER = "dual_video_user"
        private const val KEY_V_PASS = "dual_video_pass"
        private const val KEY_A_SERVER = "dual_audio_server"
        private const val KEY_A_USER = "dual_audio_user"
        private const val KEY_A_PASS = "dual_audio_pass"
        private const val KEY_REMEMBER = "dual_remember"
        const val EXTRA_EXTERNAL_AUDIO_URL = "external_audio_url"
        const val EXTRA_EXTERNAL_AUDIO_NAME = "external_audio_name"
        const val EXTRA_EXTERNAL_AUDIO_URLS = "external_audio_urls"
        const val EXTRA_EXTERNAL_AUDIO_NAMES = "external_audio_names"
    }
}
