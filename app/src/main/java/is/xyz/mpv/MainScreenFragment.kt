package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding
    private val executor = Executors.newSingleThreadExecutor()

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
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
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
                    Toast.makeText(requireContext(), R.string.xtream_login_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun normalizeServer(server: String): String? {
        return try {
            val uri = URI(if (server.contains("://")) server else "http://$server")
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https" || uri.host.isNullOrBlank()) return null
            URI(scheme, uri.userInfo, uri.host, uri.port, uri.path?.trimEnd('/'), null, null).toString().trimEnd('/')
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLogin(server: String, username: String, password: String) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .putString(KEY_SERVER, server).putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password).apply()
    }

    private fun clearLogin() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .remove(KEY_SERVER).remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    override fun onDestroyView() {
        executor.shutdownNow()
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SERVER = "xtream_server"
        private const val KEY_USERNAME = "xtream_username"
        private const val KEY_PASSWORD = "xtream_password"
    }
}
