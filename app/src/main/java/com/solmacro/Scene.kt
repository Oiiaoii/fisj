===== FILE: app/src/main/java/com/solmacro/Ai.kt =====

package com.solmacro

import android.content.Context

// The "AI" of LuminFish: a small on-device learner (no internet, no cloud, no delay). It has three jobs.
//
// 1. Reel tuning. Three numbers decide how the taps are timed: where inside the green zone it aims (bias),
//    how hard the tap rate reacts to being off target (kp) and a small timing trim for the delay between a tap
//    and the game seeing it (trim). Every 3 reels it tries ONE slightly different value, keeps it only if the
//    marker stayed in the zone clearly longer, and shrinks its steps when a try does not help.
//    If the tuned values ever do much worse than the defaults, it goes back to the defaults by itself.
// 2. Physics memory. How far one tap lifts the bar and how fast it drains are measured live (see MacroService)
//    and remembered here, so the next session starts calibrated instead of from a guess.
// 3. Waiting-time ratchet. After a reel the macro waits for the result panel and for the close (X) tap.
//    Those waits can be shorter than the defaults. The AI lowers them a step after 3 clean reels and goes back up
//    the moment a wait was too short (the bar came back, or the next cast found no bar), and remembers the limit.
object Ai {
    const val BASE_DELAY = 35.0
    @Volatile var enabled = true
    @Volatile var bias = 0.0
    @Volatile var kp = 8.0
    @Volatile private var trimV = 0.0
    @Volatile var cycle = 1.0
    @Volatile var gainFrac = 0.0248
    @Volatile var drainFrac = 0.0919

    // tap-to-game delay in ms (the tuner adds a trim to it)
    fun delayMs(): Long = (BASE_DELAY + (if (enabled) trimV else 0.0)).toLong().coerceIn(5L, 130L)

    private val defaults = doubleArrayOf(0.0, 8.0, 0.0)
    private val lo = doubleArrayOf(-0.25, 3.0, -20.0)
    private val hi = doubleArrayOf(0.25, 14.0, 40.0)
    private val stepSize = doubleArrayOf(0.08, 2.0, 10.0)
    private val scale = doubleArrayOf(1.0, 1.0, 1.0)
    private var best = defaults.copyOf()
    private var cur = defaults.copyOf()
    private var testing = -1
    private var param = 0
    private var n = 0
    private var sum = 0.0
    private var bestScore = -1.0
    private var baseScore = -1.0
    private var blocks = 0
    private var reels = 0
    private var avg = 0.0
    // waiting-time ratchet
    private var cyClean = 0
    private var cyLong = 0
    private var cyFloor = 0.5
    private var cyFault = false

    private fun apply(v: DoubleArray) {
        bias = v[0]
        kp = v[1]
        trimV = v[2]
    }

    fun load(c: Context) {
        best = doubleArrayOf(Cfg.get(c, "ai_bias") / 100.0, Cfg.get(c, "ai_kp") / 10.0, Cfg.get(c, "ai_trim").toDouble())
        cur = best.copyOf()
        apply(if (Cfg.get(c, "ai") == 1) cur else defaults)
        cycle = if (Cfg.get(c, "ai") == 1) (Cfg.get(c, "ai_cycle") / 100.0).coerceIn(0.5, 1.0) else 1.0
        cyFloor = (Cfg.get(c, "ai_cyfloor") / 100.0).coerceIn(0.5, 1.0)
        gainFrac = Cfg.get(c, "ai_gain") / 10000.0
        drainFrac = Cfg.get(c, "ai_drain") / 10000.0
        if (gainFrac <= 0.0) gainFrac = 0.0248
        if (drainFrac <= 0.0) drainFrac = 0.0919
        // v2.3: an old drifted value is pulled back to a sane range
        gainFrac = gainFrac.coerceIn(0.0248 * 0.85, 0.0248 * 1.15)
        drainFrac = drainFrac.coerceIn(0.0919 * 0.85, 0.0919 * 1.15)
        testing = -1
        n = 0
        sum = 0.0
        bestScore = -1.0
        baseScore = -1.0
        blocks = 0
        cyClean = 0
        cyFault = false
        for (i in scale.indices) scale[i] = 1.0
        info()
    }

    fun reset(c: Context) {
        Cfg.put(c, "ai_bias", 0)
        Cfg.put(c, "ai_kp", 80)
        Cfg.put(c, "ai_trim", 0)
        Cfg.put(c, "ai_cycle", 100)
        Cfg.put(c, "ai_cyfloor", 50)
        Cfg.put(c, "ai_gain", 248)
        Cfg.put(c, "ai_drain", 919)
        reels = 0
        avg = 0.0
        load(c)
        Dbg.add("AI: learned values cleared")
    }

    private fun info() {
        Dbg.aiBias = Math.round(bias * 100).toString() + "%"
        Dbg.aiInfo = "aim " + Dbg.aiBias + ", kp " + String.format(java.util.Locale.US, "%.1f", kp) + ", trim " + trimV.toInt() +
            " ms, waits " + Math.round(cycle * 100) + "%, reels " + reels + ", avg " + avg.toInt() + "%"
    }

    // remember how strong one tap is and how fast the bar drains (as a share of the screen width)
    fun savePhys(c: Context, gain: Double, drain: Double, screenW: Int) {
        if (!enabled || screenW <= 0) return
        val gf = gain / screenW
        val df = drain / screenW
        if (gf in (0.0248 * 0.85)..(0.0248 * 1.15) && Math.abs(gf - gainFrac) > gainFrac * 0.02) {
            gainFrac = gf
            Cfg.put(c, "ai_gain", Math.round(gf * 10000).toInt())
        }
        if (df in (0.0919 * 0.85)..(0.0919 * 1.15) && Math.abs(df - drainFrac) > drainFrac * 0.02) {
            drainFrac = df
            Cfg.put(c, "ai_drain", Math.round(df * 10000).toInt())
        }
    }

    // pct = how long the marker stayed in the zone (0-100), caught = the reel ended with a catch
    fun report(c: Context, pct: Int, caught: Boolean) {
        if (Cfg.get(c, "ai") != 1) { apply(defaults); return }
        val score = if (caught) pct.toDouble() else pct * 0.4
        reels++
        avg = if (reels == 1) score else 0.9 * avg + 0.1 * score
        n++
        sum += score
        if (n >= 3) {
            val a = sum / n
            n = 0
            sum = 0.0
            blocks++
            if (testing < 0) {
                bestScore = if (bestScore < 0) a else 0.5 * bestScore + 0.5 * a
                if (baseScore < 0) baseScore = a
                val p = param
                param = (param + 1) % 3
                val dir = if (Math.random() < 0.5) -1.0 else 1.0
                val cand = best.copyOf()
                cand[p] = (cand[p] + dir * stepSize[p] * scale[p]).coerceIn(lo[p], hi[p])
                cur = cand
                testing = p
                apply(cur)
            } else {
                val p = testing
                if (a > bestScore + 2.0) {
                    best = cur.copyOf()
                    bestScore = a
                    Cfg.put(c, "ai_bias", Math.round(best[0] * 100).toInt())
                    Cfg.put(c, "ai_kp", Math.round(best[1] * 10).toInt())
                    Cfg.put(c, "ai_trim", Math.round(best[2]).toInt())
                    Dbg.add("AI: better timing found (in zone " + a.toInt() + "%)")
                } else {
                    scale[p] = maxOf(0.25, scale[p] * 0.85)
                }
                cur = best.copyOf()
                testing = -1
                apply(cur)
                // safety: far worse than the defaults did at the start, so go back to them
                if (blocks >= 8 && baseScore >= 0 && bestScore < baseScore - 8.0) {
                    Dbg.add("AI: tuned timing did worse than the defaults, going back to them")
                    best = defaults.copyOf()
                    cur = best.copyOf()
                    Cfg.put(c, "ai_bias", 0)
                    Cfg.put(c, "ai_kp", 80)
                    Cfg.put(c, "ai_trim", 0)
                    bestScore = -1.0
                    baseScore = -1.0
                    apply(cur)
                }
            }
        }
        info()
    }

    // ---- waiting-time ratchet ----
    // a wait was too short (the bar came back after the reel was called finished, or the cast right after a reel found no bar)
    fun cycleFault() { cyFault = true }

    // call once per finished reel
    fun cycleReelEnd(c: Context) {
        if (Cfg.get(c, "ai") != 1) return
        if (cyFault) {
            cyFault = false
            cyFloor = minOf(1.0, maxOf(cyFloor, cycle + 0.05))
            cycle = minOf(1.0, cycle + 0.15)
            cyClean = 0
            Dbg.add("AI: a wait was too short, back to " + Math.round(cycle * 100) + "% of the normal waits")
        } else {
            cyClean++
            cyLong++
            if (cyClean >= 3 && cycle - 0.1 >= cyFloor - 1e-9) {
                cycle = Math.round((cycle - 0.1) * 100) / 100.0
                cyClean = 0
                Dbg.add("AI: trying shorter waits (" + Math.round(cycle * 100) + "% of normal)")
            }
            if (cyLong >= 40) {
                cyFloor = maxOf(0.5, cyFloor - 0.05)
                cyLong = 0
            }
        }
        Cfg.put(c, "ai_cycle", Math.round(cycle * 100).toInt())
        Cfg.put(c, "ai_cyfloor", Math.round(cyFloor * 100).toInt())
        info()
    }
}


===== FILE: app/src/main/java/com/solmacro/Auth.kt =====

package com.solmacro

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// v2.3 sign-in with Discord or Roblox: the normal OAuth 2.0 "authorization code + PKCE" flow in the phone's browser.
// No password is typed in this app, no secret is stored in it, and the access token is thrown away right after the
// profile (name, picture) was read. See SIGNIN_SETUP.md for creating the two apps.
object Auth {
    const val DISCORD = "discord"
    const val ROBLOX = "roblox"
    private val main = Handler(Looper.getMainLooper())

    fun label(p: String) = if (p == ROBLOX) "Roblox" else "Discord"

    fun clientId(c: Context, p: String): String {
        val v = Cfg.gets(c, "auth_${p}_id").trim()
        if (v.isNotEmpty()) return v
        return try { c.getString(if (p == ROBLOX) R.string.roblox_client_id else R.string.discord_client_id).trim() } catch (_: Exception) { "" }
    }

    fun redirect(c: Context, p: String): String {
        val v = Cfg.gets(c, "auth_${p}_redirect").trim()
        return if (v.isEmpty()) "luminfish://auth" else v
    }

    fun isDone(c: Context): Boolean = Cfg.gets(c, "auth_state").let { it == "ok" || it == "guest" }
    fun signedIn(c: Context): Boolean = Cfg.gets(c, "auth_state") == "ok"
    fun name(c: Context): String = Cfg.gets(c, "auth_name").ifEmpty { "Guest" }
    fun provider(c: Context): String = Cfg.gets(c, "auth_provider")
    fun avatarFile(c: Context) = File(c.filesDir, "avatar.png")

    // ---- accounts kept on this phone (sign up creates one, log in uses one) ----
    class Account(val p: String, val id: String, val name: String)

    private class NoAccount(val provider: String) : Exception()

    fun accounts(c: Context): List<Account> {
        val out = ArrayList<Account>()
        try {
            val a = JSONArray(Cfg.gets(c, "auth_accounts").ifEmpty { "[]" })
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Account(o.getString("p"), o.getString("id"), o.optString("name")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveAccounts(c: Context, list: List<Account>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("p", x.p).put("id", x.id).put("name", x.name))
        Cfg.puts(c, "auth_accounts", a.toString())
    }

    fun avatarOf(c: Context, acc: Account) = File(c.filesDir, "av_" + acc.p + "_" + acc.id.filter { it.isLetterOrDigit() } + ".png")

    private fun addAccount(c: Context, acc: Account) {
        val list = accounts(c).filterNot { it.p == acc.p && it.id == acc.id }.toMutableList()
        list.add(0, acc)
        saveAccounts(c, list)
    }

    // makes a saved account the current one (no browser needed)
    fun setCurrent(c: Context, acc: Account) {
        Cfg.puts(c, "auth_state", "ok")
        Cfg.puts(c, "auth_provider", acc.p)
        Cfg.puts(c, "auth_name", acc.name)
        Cfg.puts(c, "auth_id", acc.id)
        val src = avatarOf(c, acc)
        try { if (src.exists()) src.copyTo(avatarFile(c), true) else avatarFile(c).delete() } catch (_: Exception) {}
        addAccount(c, acc)
    }

    fun removeAccount(c: Context, acc: Account) {
        saveAccounts(c, accounts(c).filterNot { it.p == acc.p && it.id == acc.id })
        avatarOf(c, acc).delete()
        if (Cfg.gets(c, "auth_id") == acc.id && provider(c) == acc.p) signOut(c)
    }

    fun current(c: Context): Account? = if (signedIn(c)) Account(provider(c), Cfg.gets(c, "auth_id"), name(c)) else null

    fun guest(c: Context) {
        Cfg.puts(c, "auth_state", "guest")
        Cfg.puts(c, "auth_provider", "")
        Cfg.puts(c, "auth_name", "Guest")
        avatarFile(c).delete()
    }

    fun signOut(c: Context) {
        // the account itself stays saved on this phone, so Log in can use it again
        for (k in listOf("auth_state", "auth_provider", "auth_name", "auth_id", "auth_pend_v", "auth_pend_s", "auth_pend_p", "auth_pend_m")) Cfg.puts(c, k, "")
        avatarFile(c).delete()
    }

    private fun rnd(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Opens the provider's sign-in page in the browser. Returns an error text, or null when the browser was opened.
    fun begin(a: Activity, p: String, mode: String = "signup"): String? {
        val id = clientId(a, p)
        if (id.isEmpty()) return "${label(p)} sign-in is not set up yet. Open \"Sign-in setup\" below and paste the client ID."
        val verifier = rnd(48)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val state = p + "." + rnd(12)
        Cfg.puts(a, "auth_pend_v", verifier)
        Cfg.puts(a, "auth_pend_s", state)
        Cfg.puts(a, "auth_pend_p", p)
        Cfg.puts(a, "auth_pend_m", mode)
        val base = if (p == ROBLOX) "https://apis.roblox.com/oauth/v1/authorize" else "https://discord.com/oauth2/authorize"
        val scope = if (p == ROBLOX) "openid profile" else "identify"
        val url = base + "?client_id=" + enc(id) + "&response_type=code&redirect_uri=" + enc(redirect(a, p)) +
            "&scope=" + enc(scope) + "&state=" + enc(state) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256"
        return try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            null
        } catch (e: ActivityNotFoundException) {
            "No browser found on this phone."
        }
    }

    private fun http(url: String, form: String?, bearer: String?): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        try {
            cn.connectTimeout = 12000
            cn.readTimeout = 15000
            cn.setRequestProperty("User-Agent", "LuminFish/2.3")
            cn.setRequestProperty("Accept", "application/json")
            if (bearer != null) cn.setRequestProperty("Authorization", "Bearer $bearer")
            if (form != null) {
                cn.requestMethod = "POST"
                cn.doOutput = true
                cn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                cn.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = cn.responseCode
            val stream = if (code in 200..299) cn.inputStream else cn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            cn.disconnect()
        }
    }

    // Called with the address the browser came back with (luminfish://auth?code=...&state=...).
    fun finish(c: Context, uri: Uri, cb: (Boolean, String) -> Unit) {
        val err = uri.getQueryParameter("error")
        if (err != null) { cb(false, if (err == "access_denied") "Sign-in was cancelled." else "Sign-in failed: $err"); return }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val p = Cfg.gets(c, "auth_pend_p")
        val verifier = Cfg.gets(c, "auth_pend_v")
        if (code == null || state == null || p.isEmpty() || verifier.isEmpty() || state != Cfg.gets(c, "auth_pend_s")) {
            cb(false, "This sign-in link is not valid any more. Please try again.")
            return
        }
        Cfg.puts(c, "auth_pend_v", "")
        Cfg.puts(c, "auth_pend_s", "")
        Thread {
            var ok = false
            var msg = ""
            try {
                val tokenUrl = if (p == ROBLOX) "https://apis.roblox.com/oauth/v1/token" else "https://discord.com/api/oauth2/token"
                val form = "client_id=" + enc(clientId(c, p)) + "&grant_type=authorization_code&code=" + enc(code) +
                    "&redirect_uri=" + enc(redirect(c, p)) + "&code_verifier=" + enc(verifier)
                val (tc, tb) = http(tokenUrl, form, null)
                if (tc !in 200..299) throw Exception("token HTTP $tc " + tb.take(120))
                val token = JSONObject(tb).getString("access_token")
                val infoUrl = if (p == ROBLOX) "https://apis.roblox.com/oauth/v1/userinfo" else "https://discord.com/api/users/@me"
                val (ic, ib) = http(infoUrl, null, token)
                if (ic !in 200..299) throw Exception("profile HTTP $ic " + ib.take(120))
                val j = JSONObject(ib)
                var name: String
                var id: String
                var pic: String? = null
                if (p == ROBLOX) {
                    id = j.optString("sub")
                    name = j.optString("nickname").ifEmpty { j.optString("name").ifEmpty { j.optString("preferred_username") } }
                    pic = j.optString("picture").ifEmpty { null }
                } else {
                    id = j.optString("id")
                    name = j.optString("global_name").let { if (it.isEmpty() || it == "null") j.optString("username") else it }
                    val h = j.optString("avatar")
                    if (h.isNotEmpty() && h != "null") pic = "https://cdn.discordapp.com/avatars/$id/$h.png?size=128"
                }
                if (name.isEmpty()) name = label(p) + " user"
                val mode = Cfg.gets(c, "auth_pend_m")
                val known = accounts(c).any { it.p == p && it.id == id }
                if (mode == "login" && !known) throw NoAccount(p)
                val acc = Account(p, id, name)
                avatarOf(c, acc).delete()
                if (pic != null) {
                    try {
                        val cn = URL(pic).openConnection() as HttpURLConnection
                        cn.connectTimeout = 8000
                        cn.readTimeout = 8000
                        cn.inputStream.use { s -> BitmapFactory.decodeStream(s) }?.let { b ->
                            avatarOf(c, acc).outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 95, o) }
                        }
                    } catch (_: Exception) {}
                }
                setCurrent(c, acc)
                ok = true
                msg = (if (known) "back:" else "new:") + name
            } catch (e: NoAccount) {
                msg = "noaccount:" + label(e.provider)
            } catch (e: Exception) {
                msg = "Could not finish sign-in (" + (e.message ?: "error") + ")"
            }
            val m = msg
            val o = ok
            main.post { cb(o, m) }
        }.start()
    }

    // round picture with the first letter, used when there is no avatar
    fun initialBitmap(c: Context, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = when (provider(c)) { DISCORD -> 0xFF5865F2.toInt(); ROBLOX -> 0xFF3A3F4B.toInt(); else -> 0xFF2A3A82.toInt() }
        cv.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = sizePx * 0.46f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val ch = name(c).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        cv.drawText(ch, sizePx / 2f, sizePx / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
}


===== FILE: app/src/main/java/com/solmacro/Biome.kt =====

package com.solmacro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

// Biome list, name matching (tolerates a letter or two read wrong), reading helpers and session stats.
object Biome {
    class Info(val key: String, val title: String, val color: Int, val rare: Boolean)

    // color = the left bar of the Discord embed (Rainy is the exact color of the reference picture)
    val all = listOf(
        Info("normal", "NORMAL", 0x8BC34A, false),
        Info("windy", "WINDY", 0x9FD8CB, false),
        Info("rainy", "RAINY", 0x829DB8, false),
        Info("snowy", "SNOWY", 0xC4E3F3, false),
        Info("sandstorm", "SAND STORM", 0xC8A25A, false),
        Info("hell", "HELL", 0xB3261E, false),
        Info("starfall", "STARFALL", 0x6E5BD6, false),
        Info("heaven", "HEAVEN", 0xF5D76E, false),
        Info("corruption", "CORRUPTION", 0x7B2D9A, false),
        Info("null", "NULL", 0x4D4D4D, true),
        Info("glitched", "GLITCHED", 0x00E5FF, true),
        Info("dreamspace", "DREAMSPACE", 0xFF8AD8, true),
        Info("cyberspace", "CYBERSPACE", 0x30C6FF, true),
        Info("bloodrain", "BLOOD RAIN", 0x8B0000, false),
        Info("aurora", "AURORA", 0x4DE0B0, false),
        Info("pumpkinmoon", "PUMPKIN MOON", 0xFF7A18, false),
        Info("graveyard", "GRAVEYARD", 0x6F8F7A, false),
        Info("limbo", "LIMBO", 0x9A9A9A, false),
        Info("eggland", "EGGLAND", 0xFFB6C1, false)
    )

    @Volatile var cur: Info? = null
    @Volatile var sinceMs = 0L
    private val counts = HashMap<String, Int>()
    private val timeMs = HashMap<String, Long>()

    fun byKey(k: String): Info? = all.firstOrNull { it.key == k }

    fun nice(b: Info): String = b.title.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    // 0 = ignore, 1 = webhook message, 2 = webhook message and ping
    fun mode(c: Context, key: String): Int = Cfg.get(c, "bio_$key")
    fun setMode(c: Context, key: String, v: Int) = Cfg.put(c, "bio_$key", v)

    @Synchronized fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
    @Synchronized fun addTime(key: String, ms: Long) { timeMs[key] = (timeMs[key] ?: 0L) + ms }
    @Synchronized fun resetStats() { counts.clear(); timeMs.clear() }

    @Synchronized fun summary(): String {
        val parts = ArrayList<String>()
        for (b in all) {
            val n = counts[b.key] ?: 0
            if (n == 0 || b.key == "normal") continue
            val m = (timeMs[b.key] ?: 0L) / 60000L
            parts.add(b.title + " x" + n + (if (m > 0) " (" + m + " min)" else ""))
        }
        return if (parts.isEmpty()) "No biomes seen yet" else parts.joinToString(", ")
    }

    // The biome whose name is closest to the text (a small number of misread letters is fine, short names must be exact).
    fun match(text: String): Info? {
        val flat = text.lowercase().filter { it in 'a'..'z' }
        if (flat.length < 4) return null
        var best: Info? = null
        var bestD = 99
        for (b in all) {
            val w = b.key
            val allowed = if (w.length <= 4) 0 else if (w.length <= 7) 1 else 2
            val d = dist(flat, w)
            if (d > allowed) continue
            if (d < bestD || (d == bestD && best != null && w.length > best.key.length)) {
                best = b
                bestD = d
            }
        }
        return best
    }

    // smallest edit distance between the word and any part of the text
    private fun dist(text: String, w: String): Int {
        val n = text.length
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..w.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (w[i - 1] == text[j - 1]) 0 else 1
                curr[j] = minOf(prev[j - 1] + cost, prev[j] + 1, curr[j - 1] + 1)
            }
            val t = prev
            prev = curr
            curr = t
        }
        var best = Int.MAX_VALUE
        for (j in 0..n) if (prev[j] < best) best = prev[j]
        return best
    }

    // Several versions of the same picture (bigger, black and white with strong contrast, inverted).
    // The caller recycles everything except the original it passed in.
    fun variants(src: Bitmap): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val sc = if (src.height < 110) minOf(110f / src.height, 4f) else 1f
        val big = if (sc > 1.01f) Bitmap.createScaledBitmap(src, minOf((src.width * sc).toInt(), 1800), (src.height * sc).toInt(), true) else src
        out.add(big)
        out.add(filtered(big, false))
        out.add(filtered(big, true))
        return out
    }

    private fun filtered(b: Bitmap, invert: Boolean): Bitmap {
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val k = 2.2f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        if (invert) cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(o).drawBitmap(b, 0f, 0f, p)
        return o
    }
}


===== FILE: app/src/main/java/com/solmacro/CaptureService.kt =====

package com.solmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.HardwareBuffer
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

// raw RGBA bytes of a box of the newest frame (for OpenCV: one bulk copy per row, no per-pixel loop)
class Rgba(val a: ByteArray, val x: Int, val y: Int, val w: Int, val h: Int, val id: Long = 0L, val ns: Long = 0L)

class Reg(val px: IntArray, val x: Int, val y: Int, val w: Int, val h: Int, val step: Int = 1, val id: Long = 0L, val ns: Long = 0L)

object Cap {
    @Volatile var active = false
    // counts every new frame taken from the screen, and the time Android stamped it (nanoseconds)
    @Volatile var frameId = 0L
    @Volatile var frameNs = 0L
    // when the app got the newest frame (uptime ms). New: frames are taken the moment they arrive.
    @Volatile var arriveMs = 0L
    var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: Image? = null
    private var w = 0
    private var h = 0
    private var capThread: HandlerThread? = null
    private var capHandler: Handler? = null
    private var rowBuf = ByteArray(0)
    private var fpsN = 0
    private var fpsT = 0L

    // frames per second the screen really delivers (shown in the panel Stats)
    private fun tickFps(now: Long) {
        fpsN++
        if (fpsT == 0L) fpsT = now
        if (now - fpsT >= 1000L) {
            Dbg.capFps = (fpsN * 1000L / (now - fpsT)).toInt()
            fpsN = 0
            fpsT = now
        }
    }

    private fun handler(): Handler {
        val c = capHandler
        if (c != null) return c
        val t = HandlerThread("brry-cap", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        capThread = t
        val n = Handler(t.looper)
        capHandler = n
        return n
    }

    // Called on the capture thread the moment Android has a new frame: take it right away so the newest
    // picture (and its time stamp) is always ready, instead of waiting for the next poll.
    private fun onAvailable(rd: ImageReader) {
        synchronized(this) {
            if (rd !== reader) return
            var n: Image? = null
            try { n = rd.acquireLatestImage() } catch (e: Exception) { n = null }
            if (n == null) return
            last?.close()
            last = n
            frameId++
            frameNs = n.timestamp
            arriveMs = SystemClock.uptimeMillis()
            tickFps(arriveMs)
        }
    }

    @Synchronized
    fun ensure(nw: Int, nh: Int, dpi: Int) {
        val p = proj ?: return
        if (reader != null && nw == w && nh == h) return
        last?.close()
        last = null
        // 3 buffers: one is held as the newest picture, the others keep the screen compositor from ever waiting for us.
        // On Android 10+ the buffers are asked for GPU-friendly memory that the CPU can still read.
        val nr = createReader(nw, nh)
        nr.setOnImageAvailableListener({ rd -> onAvailable(rd) }, handler())
        val v = vd
        if (v == null) {
            vd = p.createVirtualDisplay("solmacro", nw, nh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nr.surface, null, null)
        } else {
            v.resize(nw, nh, dpi)
            v.surface = nr.surface
        }
        val old = reader
        reader = nr
        try { old?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        old?.close()
        w = nw
        h = nh
        Dbg.add("Fast capture size ${nw}x$nh (frames arrive by event)")
    }

    private fun createReader(nw: Int, nh: Int): ImageReader {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val r = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                Dbg.add("Capture buffers: GPU-friendly hardware buffers")
                return r
            } catch (e: Throwable) {
                Dbg.add("Hardware buffers not supported here, using normal buffers")
            }
        }
        return ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3)
    }

    // Takes the newest frame (if there is one) without copying pixels. Returns the frame counter.
    @Synchronized
    fun poll(): Long {
        val rd = reader ?: return frameId
        val n = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (n != null) {
            last?.close()
            last = n
            frameId++
            frameNs = n.timestamp
            arriveMs = SystemClock.uptimeMillis()
            tickFps(arriveMs)
        }
        return frameId
    }

    @Synchronized
    fun region(x1: Int, y1: Int, rw: Int, rh: Int, step: Int = 1): Reg? {
        poll()
        val img = last ?: return null
        val pl = img.planes[0]
        val buf = pl.buffer
        val rs = pl.rowStride
        val ps = pl.pixelStride
        val cx = x1.coerceIn(0, img.width - 1)
        val cy = y1.coerceIn(0, img.height - 1)
        val cw = minOf(rw, img.width - cx)
        val ch = minOf(rh, img.height - cy)
        if (cw <= 0 || ch <= 0) return null
        val ow = cw / step
        val oh = ch / step
        if (ow <= 0 || oh <= 0) return null
        val out = IntArray(ow * oh)
        // copy one whole row at a time instead of one byte at a time (much faster)
        val rowLen = ((ow - 1) * step + 1) * ps + 3
        if (rowBuf.size < rowLen) rowBuf = ByteArray(rowLen)
        val row = rowBuf
        val lim = buf.limit()
        var o = 0
        for (yy in 0 until oh) {
            val start = (cy + yy * step) * rs + cx * ps
            val n = minOf(rowLen, lim - start)
            if (n < ps) { o += ow; continue }
            (buf as java.nio.Buffer).position(start)
            buf.get(row, 0, n)
            var idx = 0
            for (xx in 0 until ow) {
                if (idx + 2 >= n) { out[o++] = 0xFF000000.toInt(); idx += ps * step; continue }
                val r = row[idx].toInt() and 255
                val g = row[idx + 1].toInt() and 255
                val b = row[idx + 2].toInt() and 255
                out[o++] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                idx += ps * step
            }
        }
        return Reg(out, cx, cy, ow, oh, step, frameId, frameNs)
    }

    private var roiBuf = ByteArray(0)

    // The box of the newest frame as plain RGBA bytes, copied one row at a time straight from the ImageReader buffer.
    // Used by the OpenCV box scan (v2.1). Returns null when the buffer is not 4 bytes per pixel.
    @Synchronized
    fun regionRgba(x1: Int, y1: Int, rw: Int, rh: Int): Rgba? {
        poll()
        val img = last ?: return null
        val pl = img.planes[0]
        val buf = pl.buffer
        val rs = pl.rowStride
        val ps = pl.pixelStride
        if (ps != 4) return null
        val cx = x1.coerceIn(0, img.width - 1)
        val cy = y1.coerceIn(0, img.height - 1)
        val cw = minOf(rw, img.width - cx)
        val ch = minOf(rh, img.height - cy)
        if (cw <= 0 || ch <= 0) return null
        val need = cw * ch * 4
        if (roiBuf.size != need) roiBuf = ByteArray(need)
        val lim = buf.limit()
        for (yy in 0 until ch) {
            val start = (cy + yy) * rs + cx * 4
            if (start + cw * 4 > lim) return null
            (buf as java.nio.Buffer).position(start)
            buf.get(roiBuf, yy * cw * 4, cw * 4)
        }
        return Rgba(roiBuf, cx, cy, cw, ch, frameId, frameNs)
    }

    @Synchronized
    fun release() {
        active = false
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        last?.close()
        last = null
        vd?.release()
        vd = null
        reader?.close()
        reader = null
        try { proj?.stop() } catch (_: Exception) {}
        proj = null
        w = 0
        h = 0
        frameId = 0L
        frameNs = 0L
        arriveMs = 0L
        fpsN = 0
        fpsT = 0L
        Dbg.capFps = 0
    }
}

class CaptureService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val ch = "cap"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(ch, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, ch)
            .setContentTitle("LuminFish is reading the screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        val code = i?.getIntExtra("code", 0) ?: 0
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null || code == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Cap.release()
                Dbg.add("Fast capture stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        Cap.proj = p
        Cap.active = true
        Dbg.add("Fast capture ready")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Cap.release()
        super.onDestroy()
    }
}


===== FILE: app/src/main/java/com/solmacro/Cfg.kt =====

package com.solmacro

import android.content.Context

object Cfg {
    const val ROBLOX = "com.roblox.client"
    val keys = listOf("cast", "reel", "inv", "sellall", "confirm", "joy", "cam", "shift", "dismiss")
    val label = mapOf(
        "cast" to "Cast button", "reel" to "Reel area", "inv" to "Inventory button",
        "sellall" to "Sell all button", "confirm" to "Confirm button",
        "joy" to "Movement joystick center", "cam" to "Camera swipe start", "shift" to "Shift Lock button",
        "dismiss" to "Close (X) button on the caught or failed panel"
    )
    val extra = listOf("p1", "p2", "p3", "p4", "p5", "p6")
    val bioKeys = listOf("bio1", "bio2")
    val watKeys = listOf("wat1", "wat2")
    val label2: Map<String, String> = mapOf(
        "bio1" to "TOP-LEFT corner of the biome name text",
        "bio2" to "BOTTOM-RIGHT corner of the biome name text",
        "wat1" to "TOP-LEFT corner of the area to watch for words (chat, merchant, aura text)",
        "wat2" to "BOTTOM-RIGHT corner of the area to watch for words"
    ) + extra.associateWith { "extra point " + it.uppercase() + " (use it in scripts as: tap " + it + ")" }
    private val def = mapOf(
        "cast" to 900, "wait" to 4000, "taps" to 12, "gap" to 120, "mdelay" to 700,
        "every" to 10, "sell" to 1,
        "cast_x" to 2000, "cast_y" to 820, "reel_x" to 1170, "reel_y" to 330,
        "inv_x" to 120, "inv_y" to 500, "sellall_x" to 1100, "sellall_y" to 760,
        "confirm_x" to 1250, "confirm_y" to 660,
        "joy_x" to 250, "joy_y" to 800, "cam_x" to 1700, "cam_y" to 500, "shift_x" to 2150, "shift_y" to 250, "joyr" to 130,
        "icon_custom" to 0, "full" to 1, "castdelay" to 150, "postcatch" to 600, "gonems" to 500, "adapt" to 1, "detect" to 1, "debug" to 0, "cv" to 1, "afk" to 1, "afkmin" to 10, "shoton" to 0, "shotmin" to 30, "bio" to 0, "biosec" to 1, "t1min" to 30, "t2min" to 30, "t3min" to 30, "whon" to 1, "whevery" to 10, "maxcatch" to 0, "color2" to -14581335, "dismiss_x" to 1500, "dismiss_y" to 110, "tapms" to 70, "axis" to 0, "holddir" to 1, "deadband" to 15, "holdms" to 220, "tol" to 28, "minpx" to 150, "maxwait" to 30, "color" to -15039198,
        "r1_x" to 900, "r1_y" to 300, "r2_x" to 1500, "r2_y" to 500,
        "dismisswait" to 900, "resultms" to 500, "learn" to 1, "widen" to 1,
        "rejoin" to 0, "rejoinsec" to 45, "watch" to 0, "watchsec" to 4, "pingrare" to 1, "pingwatch" to 1,
        // new in 1.4: AI autopilot (learns the aim, recovers when stuck), tap press length, seconds without progress
        "ai" to 1, "ai_bias" to 0, "taphold" to 35, "stuck" to 120, "menu_open" to 1,
        // AI memory: kp x10, timing trim ms, wait factor %, lowest safe wait factor %, gain and drain as 1/10000 of screen width
        "ai_kp" to 80, "ai_trim" to 0, "ai_cycle" to 100, "ai_cyfloor" to 50, "ai_gain" to 248, "ai_drain" to 919,
        // biome alerts per biome: 0 ignore, 1 webhook message, 2 webhook message and ping; end messages; status report
        "bio_normal" to 0, "bio_windy" to 1, "bio_rainy" to 1, "bio_snowy" to 1, "bio_sandstorm" to 1, "bio_hell" to 1, "bio_starfall" to 1,
        "bio_heaven" to 1, "bio_corruption" to 1, "bio_null" to 2, "bio_glitched" to 2, "bio_dreamspace" to 2, "bio_cyberspace" to 2,
        "bio_bloodrain" to 1, "bio_aurora" to 1, "bio_pumpkinmoon" to 1, "bio_graveyard" to 1, "bio_limbo" to 1, "bio_eggland" to 1,
        "bioend" to 1, "statuson" to 0, "statusmin" to 30,
        // v1.9: smart timing, click recovery, anti-freeze, catch verification, auto-sell options
        "smart" to 1, "recover" to 1, "freeze" to 1, "freezesec" to 30, "verify" to 1,
        "sellmode" to 0, "sellmin" to 10, "sellconf" to 1,
        // v2.4: stop after N minutes (0 = never), stop below N% battery when not charging (0 = off)
        // v2.3: find Fish / Exit / X buttons on the screen, any zone colour, small circles where taps land
        // v2.0: adaptive scan speed, panel size (0 small, 1 normal, 2 big), custom panel background and how dark it is
        "anim" to 1, "scene" to 1, "anyzone" to 1, "tapdots" to 1, "edgetap" to 1, "savepics" to 1, "learnpics" to 1,
        "maxmin" to 0, "minbat" to 0, "adaptfps" to 1, "tapeng" to 1, "tap_lat" to 0, "tapmode" to 1, "tapprec" to 1, "psize" to 1, "bg_custom" to 0, "bg_dim" to 45
    )

    private fun sp(c: Context) = c.getSharedPreferences("macro", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): Int = sp(c).getInt(k, def[k] ?: 0)
    fun gets(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun puts(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun put(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
}


===== FILE: app/src/main/java/com/solmacro/Cv.kt =====

package com.solmacro

import android.os.SystemClock
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

// OpenCV reeling-bar detector (v1.8). Finds the green zone and the blue fill in HSV, so it keeps working when the
// brightness changes, and keeps a short memory of where the bar was, so a fill that covers the whole zone does not
// make the bar "disappear". If OpenCV cannot load, or finds nothing, MacroService falls back to its own scanner.
object Cv {
    @Volatile var ready = false
    @Volatile var lastUsedMemory = false
    // v2.3: the zone can change colour, so any bright coloured area of the bar that is not the blue fill counts as zone
    @Volatile var anyColor = true

    // short memory of the last bar (in scan pixels)
    private var memX = 0
    private var memY = 0
    private var memW = 0
    private var memH = 0
    private var memZl = 0
    private var memZr = 0
    private var memArea = 0.0
    private var memStep = 0
    private var memAt = 0L
    // reused between scans
    private var cacheArr: ByteArray? = null
    private var cacheMat: Mat? = null
    private var cacheHsv: Mat? = null
    private var cacheW = 0
    private var cacheH = 0

    fun init() {
        if (ready) return
        var ok = false
        try {
            val k = Class.forName("org.opencv.android.OpenCVLoader")
            ok = k.getMethod("initLocal").invoke(null) as Boolean
        } catch (e: Throwable) {
            ok = false
        }
        if (!ok) {
            try {
                System.loadLibrary("opencv_java4")
                ok = true
            } catch (e: Throwable) {
                Dbg.add("OpenCV could not load: " + e.message)
            }
        }
        ready = ok
        Dbg.add(if (ok) "OpenCV loaded" else "OpenCV is not available, using the built-in scanner")
    }

    fun forget() {
        memAt = 0L
        memW = 0
        rAt = 0L
    }

    // OpenCV style HSV (hue 0..179) of an RGB color
    private fun hsvOf(c: Int): IntArray {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        val d = mx - mn
        var hh = 0.0
        if (d > 0) {
            hh = if (mx == r) 60.0 * (((g - b).toDouble() / d + 6.0) % 6.0)
            else if (mx == g) 60.0 * ((b - r).toDouble() / d + 2.0)
            else 60.0 * ((r - g).toDouble() / d + 4.0)
        }
        val s = if (mx == 0) 0 else d * 255 / mx
        return intArrayOf((hh / 2.0).toInt().coerceIn(0, 179), s, mx)
    }

    private fun colorMask(hsv: Mat, c: Int, tol: Int): Mat {
        val z = hsvOf(c)
        val ht = 4 + tol / 3
        val lo = Scalar(maxOf(0, z[0] - ht).toDouble(), maxOf(45, (z[1] * 0.45).toInt()).toDouble(), maxOf(25, (z[2] * 0.5).toInt()).toDouble())
        val hi = Scalar(minOf(179, z[0] + ht).toDouble(), 255.0, minOf(255, (z[2] * 1.6).toInt() + 1).toDouble())
        val m = Mat()
        Core.inRange(hsv, lo, hi, m)
        return m
    }



    // v2.3: colour balance. Every colour channel is scaled so its brightest 0.3% of pixels reach the same white level: a
    // night / blue-light filter or a tinted screen protector then reads like a normal screen. Brightness itself is NOT raised
    // (that would lift the dark panel above the zone); instead the brightness floors are scaled by the picture's own white
    // level (nrmK). tests/robust_test.py: the zone is still found at 40% brightness and under a strong warm filter; with fixed
    // floors it was lost at 50% and under any warm filter. Only the any-colour paths use it, because your picked colours were
    // picked on the screen as it looks.
    private var nrmHsvScan: Mat? = null
    private var nrmHsvRoi: Mat? = null
    private var nrmK = 1.0

    private fun normHsv(src: Mat, rgbOrder: Boolean, out: Mat) {
        val chs = ArrayList<Mat>()
        val hist = Mat()
        val merged = Mat()
        try {
            Core.split(src, chs)
            val tail = src.rows() * src.cols() * 0.003
            val hi = IntArray(3)
            for (i in 0..2) {
                Imgproc.calcHist(listOf(chs[i]), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
                var acc = 0.0
                var v = 255
                for (b in 255 downTo 0) {
                    acc += hist.get(b, 0)[0]
                    if (acc >= tail) { v = b; break }
                }
                hi[i] = maxOf(v, 60)
            }
            val ref = maxOf(hi[0], maxOf(hi[1], hi[2]))
            for (i in 0..2) {
                val gain = (ref.toDouble() / hi[i]).coerceIn(1.0, 3.0)
                if (gain > 1.01) chs[i].convertTo(chs[i], -1, gain, 0.0)
            }
            nrmK = (ref / 255.0).coerceIn(0.4, 1.0)
            Core.merge(chs.subList(0, 3), merged)
            Imgproc.cvtColor(merged, out, if (rgbOrder) Imgproc.COLOR_RGB2HSV else Imgproc.COLOR_BGR2HSV)
        } finally {
            for (c in chs) c.release()
            hist.release()
            merged.release()
        }
    }

    // the blue fill in any shade (light blue to violet), so it still reads when the picked fill colour does not match
    private fun blueAny(hsv: Mat, vf: Int = 100): Mat {
        val m = Mat()
        Core.inRange(hsv, Scalar(98.0, 55.0, vf.toDouble()), Scalar(135.0, 255.0, 255.0), m)
        return m
    }

    // v2.3: every bright, coloured pixel that is not the blue fill (hue 95..140) and not white text (low saturation).
    // Covers green, teal, yellow, orange, red, pink and purple zones, and a zone made of two shades.
    private fun anyMask(hsv: Mat, blue: Mat?, vf: Int = 100): Mat {
        val a = Mat()
        val b = Mat()
        Core.inRange(hsv, Scalar(0.0, 55.0, vf.toDouble()), Scalar(94.0, 255.0, 255.0), a)
        Core.inRange(hsv, Scalar(141.0, 55.0, vf.toDouble()), Scalar(179.0, 255.0, 255.0), b)
        Core.bitwise_or(a, b, a)
        b.release()
        if (blue != null) {
            val nb = Mat()
            Core.bitwise_not(blue, nb)
            Core.bitwise_and(a, nb, a)
            nb.release()
        }
        return a
    }

    // number of set pixels in every column of rows ya..yb (xa..xb), as a plain array
    private fun colCounts(mask: Mat, ya: Int, yb: Int, xa: Int, xb: Int): IntArray {
        val band = mask.submat(ya, yb + 1, xa, xb + 1)
        val sums = Mat()
        Core.reduce(band, sums, 0, Core.REDUCE_SUM, CvType.CV_32S)
        val arr = IntArray(sums.cols())
        sums.get(0, 0, arr)
        sums.release()
        band.release()
        for (i in arr.indices) arr[i] = arr[i] / 255
        return arr
    }




    // thin lines (the bar outline, text strokes) are removed first, then small gaps are closed
    private fun openClose(src: Mat, dst: Mat, h: Int) {
        val tmp = Mat()
        val ko = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, maxOf(5, h / 150).toDouble()))
        val kc = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 3.0))
        try {
            Imgproc.morphologyEx(src, tmp, Imgproc.MORPH_OPEN, ko)
            Imgproc.morphologyEx(tmp, dst, Imgproc.MORPH_CLOSE, kc)
        } finally {
            tmp.release()
            ko.release()
            kc.release()
        }
    }

    // v2.3: how far the bar reaches left and right of a zone candidate: columns where dark track, blue fill or zone colour fill
    // the row. A real bar is several times wider than its zone. Returns [left, right] or null.
    private fun barExtent(hsv: Mat, blue: Mat, any: Mat, y: Int, ch: Int, zx: Int, zw: Int, w: Int): IntArray? {
        val ya = y + ch / 4
        val yb = maxOf(ya, y + ch - 1 - ch / 4)
        val rows = yb - ya + 1
        val dark = Mat()
        try {
            Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(179.0, 255.0, 69.0), dark)
            val cd = colCounts(dark, ya, yb, 0, w - 1)
            val cb = colCounts(blue, ya, yb, 0, w - 1)
            val ca = colCounts(any, ya, yb, 0, w - 1)
            fun ok(i: Int) = maxOf(cd[i], maxOf(cb[i], ca[i])) * 2 >= rows
            val mid = zx + zw / 2
            var l = mid
            var gap = 0
            var i = mid
            while (i >= 0) { if (ok(i)) { l = i; gap = 0 } else if (++gap > 10) break; i-- }
            var r = mid
            gap = 0
            i = mid
            while (i < w) { if (ok(i)) { r = i; gap = 0 } else if (++gap > 10) break; i++ }
            return if (r - l >= 3.5 * zw) intArrayOf(l, r) else null
        } finally {
            dark.release()
        }
    }

    // true when, along the bar's width, almost every column is dark track, blue fill or zone colour
    private fun looksLikeBar(hsv: Mat, blue: Mat, any: Mat, y: Int, ch: Int, xl: Int, xr: Int, w: Int): Boolean {
        val xa = maxOf(0, xl)
        val xb = minOf(w - 1, xr)
        if (xb - xa < 16) return false
        val ya = y + ch / 4
        val yb = maxOf(ya, y + ch - 1 - ch / 4)
        val rows = yb - ya + 1
        val dark = Mat()
        try {
            Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(179.0, 255.0, 69.0), dark)
            val cd = colCounts(dark, ya, yb, xa, xb)
            val cb = colCounts(blue, ya, yb, xa, xb)
            val ca = colCounts(any, ya, yb, xa, xb)
            var ok = 0
            for (i in cd.indices) if (maxOf(cd[i], maxOf(cb[i], ca[i])) * 2 >= rows) ok++
            return ok >= 0.7 * cd.size
        } finally {
            dark.release()
        }
    }

    // end of the first run of "filled" columns that starts near the left end of the bar (-1 if none)
    private fun runEnd(cnt: IntArray, rows: Int, xa: Int, w: Int): Int {
        var tip = -1
        var run = 0
        var rs = -1
        for (k in 0..cnt.size) {
            val ok = k < cnt.size && cnt[k] * 2 >= rows
            if (ok) {
                if (run == 0) rs = k
                run++
            } else {
                if (run >= 3 && rs <= 0.03 * w && tip < 0) tip = xa + k
                run = 0
            }
        }
        return tip
    }

    // Returns [zonePx, zoneLeft, zoneRight, blueCols, tipX(-1 if none), 1, boxX1, boxY1, boxX2, boxY2, redSamples]
    // in screen pixels, or null when no bar is found (the caller then tries the built-in scanner).
    @Synchronized
    fun scanFull(px: IntArray, w: Int, h: Int, step: Int, color: Int, color2: Int, tol: Int): DoubleArray? {
        if (!ready || w < 16 || h < 16 || px.size < w * h) return null
        lastUsedMemory = false
        val n = w * h
        // fast path: the whole picture goes into one BGRA Mat with a single bulk copy (no per-pixel loop),
        // and the Mats are kept between scans so nothing is allocated 30 times a second
        val need = n * 4
        var arr = cacheArr
        if (arr == null || arr.size != need) {
            arr = ByteArray(need)
            cacheArr = arr
        }
        var rgb = cacheMat
        if (rgb == null || cacheW != w || cacheH != h) {
            rgb?.release()
            rgb = Mat(h, w, CvType.CV_8UC4)
            cacheMat = rgb
            cacheW = w
            cacheH = h
        }
        val hsv = cacheHsv ?: Mat().also { cacheHsv = it }
        var zm: Mat? = null
        var zc: Mat? = null
        var bm: Mat? = null
        val labels = Mat()
        val stats = Mat()
        val cents = Mat()
        try {
            ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(px, 0, n)
            rgb.put(0, 0, arr)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_BGR2HSV)
            zm = colorMask(hsv, color, tol)
            zc = Mat()
            val kern = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 3.0))
            Imgproc.morphologyEx(zm, zc, Imgproc.MORPH_CLOSE, kern)
            kern.release()
            val barL = w / 2.0 - 0.386 * h
            val barR = w / 2.0 + 0.386 * h
            var bx = 0
            var by = 0
            var bw = 0
            var bh = 0
            var barea = -1
            // finds the best bar-shaped blob in a mask; verify = also check that the row looks like the bar (blue fill,
            // dark track and zone fill nearly all of its width), used for the any-colour pass where the mask is noisy
            var relXa = -1
            var relXb = -1
            fun pick(mask: Mat, verify: Boolean, blue: Mat?, any: Mat?, relaxed: Boolean = false) {
                val cnt = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents, 8, CvType.CV_32S)
                val st = IntArray(5)
                for (i in 1 until cnt) {
                    stats.get(i, 0, st)
                    val x = st[Imgproc.CC_STAT_LEFT]
                    val y = st[Imgproc.CC_STAT_TOP]
                    val cw = st[Imgproc.CC_STAT_WIDTH]
                    val ch = st[Imgproc.CC_STAT_HEIGHT]
                    val area = st[Imgproc.CC_STAT_AREA]
                    if (ch < 3 || cw < 2 * ch) continue
                    val cx = x + cw / 2.0
                    val cy = y + ch / 2.0
                    if (!relaxed) {
                        if (ch < 0.025 * h || ch > 0.06 * h) continue
                        if (cw < 0.02 * w || cw > w / 2) continue
                        if (area < cw * ch / 2) continue
                        if (cx < barL || cx > barR || cy < 0.4 * h || cy > 0.8 * h) continue
                    } else {
                        // v2.3 other layouts (portrait, tablets, a different UI size): the bar may be anywhere
                        if (ch < 0.012 * h || ch > 0.09 * h) continue
                        if (cw < 0.02 * w || cw > 0.6 * w) continue
                        if (area < cw * ch / 2) continue
                        if (cx < 0.1 * w || cx > 0.9 * w || cy < 0.1 * h || cy > 0.92 * h) continue
                    }
                    if (area <= barea) continue
                    var el = -1
                    var er = -1
                    if (verify) {
                        if (relaxed) {
                            val ext = barExtent(hsv, blue!!, any!!, y, ch, x, cw, w) ?: continue
                            el = ext[0]
                            er = ext[1]
                        } else if (!looksLikeBar(hsv, blue!!, any!!, y, ch, barL.toInt(), barR.toInt(), w)) continue
                    }
                    barea = area
                    bx = x
                    by = y
                    bw = cw
                    bh = ch
                    relXa = el
                    relXb = er
                }
            }
            pick(zc, false, null, null)
            bm = colorMask(hsv, color2, tol)
            if (anyColor) {
                val gb0 = blueAny(hsv)
                Core.bitwise_or(bm, gb0, bm)
                gb0.release()
            }
            val red = Mat()
            Core.inRange(rgb, Scalar(25.0, 24.0, 215.0, 0.0), Scalar(105.0, 104.0, 255.0, 255.0), red)
            val redN = Core.countNonZero(red).toDouble()
            red.release()
            val xa = if (relXa >= 0) maxOf(0, relXa - 1) else maxOf(0, barL.toInt() - 1)
            if (barea < 0 && anyColor) {
                val hn = nrmHsvScan ?: Mat().also { nrmHsvScan = it }
                normHsv(rgb, false, hn)
                val vf = (100 * nrmK).toInt().coerceIn(40, 100)
                val gb = blueAny(hn, vf)
                Core.bitwise_or(bm, gb, bm)
                gb.release()
                val am = anyMask(hn, bm, vf)
                val amc = Mat()
                try {
                    openClose(am, amc, h)
                    pick(amc, true, bm, am)
                } finally {
                    amc.release()
                    am.release()
                }
            }
            if (barea < 0 && anyColor) {
                // third try: the same colours, but any position and size (finds the bar on other layouts)
                val hn2 = nrmHsvScan ?: Mat().also { nrmHsvScan = it }
                val vf2 = (100 * nrmK).toInt().coerceIn(40, 100)
                val am2 = anyMask(hn2, bm, vf2)
                val amc2 = Mat()
                try {
                    openClose(am2, amc2, h)
                    pick(amc2, true, bm, am2, true)
                } finally {
                    amc2.release()
                    am2.release()
                }
            }
            if (barea < 0) return fromMemory(hsv, bm, w, h, step, redN)
            val ya = by + bh / 4
            val yb = maxOf(ya, by + bh - 1 - bh / 4)
            val xb = minOf(w - 1, bx + bw - 1 + bw)
            if (xb <= xa) return null
            val counts = colCounts(bm, ya, yb, xa, xb)
            val tip = runEnd(counts, yb - ya + 1, xa, w)
            // remember where the bar is
            memX = xa
            memY = by
            memW = if (relXb >= 0) minOf(relXb - xa + 3, w - xa) else minOf((2 * 0.386 * h).toInt() + 2, w - xa)
            memH = bh
            memZl = bx
            memZr = bx + bw
            memArea = barea.toDouble() * step * step / 2.0
            memStep = step
            memAt = SystemClock.uptimeMillis()
            val out = DoubleArray(11)
            out[0] = memArea
            out[1] = (bx * step).toDouble()
            out[2] = ((bx + bw) * step).toDouble()
            var blue = 0
            if (tip >= 0) {
                var k = tip - xa - 1
                while (k >= 0 && counts[k] * 2 >= (yb - ya + 1)) { blue++; k-- }
            }
            out[3] = blue.toDouble()
            out[4] = if (tip >= 0) (tip * step).toDouble() else -1.0
            out[5] = 1.0
            out[6] = (xa * step).toDouble()
            out[7] = ((by - 2) * step).toDouble()
            out[8] = ((xb + 1) * step).toDouble()
            out[9] = ((by + bh + 3) * step).toDouble()
            out[10] = redN
            return out
        } catch (e: Throwable) {
            Dbg.add("OpenCV scan error: " + e.message)
            return null
        } finally {
            zm?.release()
            zc?.release()
            bm?.release()
            labels.release()
            stats.release()
            cents.release()
        }
    }

    // ---- v2.1: OpenCV scan of the small box around the bar (the scan that runs on almost every frame) ----
    private var roiMat: Mat? = null
    private var roiHsv: Mat? = null
    private var roiW = 0
    private var roiH = 0
    // the zone seen last in a box scan (screen pixels), used while the fill covers it
    private var rZl = 0
    private var rZr = 0
    private var rCells = 0
    private var rAt = 0L

    // a = RGBA bytes of the box (w x h) whose top-left corner is (x0, y0) on the screen. Same output layout as
    // scanFull. The tip (right end of the blue fill) has decimals: the two columns around the edge are counted
    // partly, so it moves smoothly instead of jumping a whole pixel.
    @Synchronized
    fun scanRoi(a: ByteArray, x0: Int, y0: Int, w: Int, h: Int, color: Int, color2: Int, tol: Int, screenW: Int): DoubleArray? {
        if (!ready || w < 4 || h < 3 || a.size < w * h * 4) return null
        var m = roiMat
        if (m == null || roiW != w || roiH != h) {
            m?.release()
            m = Mat(h, w, CvType.CV_8UC4)
            roiMat = m
            roiW = w
            roiH = h
        }
        val hsv = roiHsv ?: Mat().also { roiHsv = it }
        var zm: Mat? = null
        var bm: Mat? = null
        try {
            m.put(0, 0, a)
            Imgproc.cvtColor(m, hsv, Imgproc.COLOR_RGB2HSV)
            zm = colorMask(hsv, color, tol)
            bm = colorMask(hsv, color2, tol)
            if (anyColor) {
                val hn = nrmHsvRoi ?: Mat().also { nrmHsvRoi = it }
                normHsv(m, true, hn)
                val vf = (100 * nrmK).toInt().coerceIn(40, 100)
                val gb = blueAny(hn, vf)
                Core.bitwise_or(bm, gb, bm)
                gb.release()
                val am = anyMask(hn, bm, vf)
                Core.bitwise_or(zm, am, zm)
                am.release()
            }
            val ya = h / 4
            val yb = maxOf(ya, h - 1 - h / 4)
            val rows = yb - ya + 1
            val zc = colCounts(zm, ya, yb, 0, w - 1)
            val bc = colCounts(bm, ya, yb, 0, w - 1)
            var cells = 0
            var zl = -1
            var zr = -1
            var zrun = 0
            var zStart = -1
            for (xx in 0..w) {
                val zn = if (xx < w) zc[xx] else 0
                if (xx < w && zn * 2 >= rows) {
                    if (zrun == 0) zStart = xx
                    zrun++
                    cells += zn
                } else {
                    if (zrun >= 2) {
                        if (zl < 0) zl = zStart
                        zr = xx
                    }
                    zrun = 0
                }
            }
            val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0, x0.toDouble(), y0.toDouble(), (x0 + w).toDouble(), (y0 + h).toDouble(), 0.0)
            val nowMs = SystemClock.uptimeMillis()
            var fromMem = false
            if (zl >= 0) {
                rZl = x0 + zl
                rZr = x0 + zr
                rCells = cells
                rAt = nowMs
            } else if (rAt != 0L && nowMs - rAt <= 400L) {
                // the blue fill covers the whole zone: use the zone seen a moment ago (the fill must still start at the bar's left end)
                fromMem = true
            } else {
                return out
            }
            var tip = -1.0
            var bn = 0
            var brun = 0
            var bStart = -1
            for (xx in 0..w) {
                val ok = xx < w && bc[xx] * 2 >= rows
                if (ok) {
                    if (brun == 0) bStart = xx
                    brun++
                } else {
                    if (brun >= 3 && bStart <= 0.03 * screenW) {
                        val last = bc[xx - 1].toDouble() / rows
                        val next = if (xx < w) bc[xx].toDouble() / rows else 0.0
                        tip = ((xx - 1) + last + next).coerceIn(xx - 1.0, xx + 1.0)
                        bn = brun
                    }
                    brun = 0
                }
            }
            if (fromMem) {
                // only trust it when the fill's right end is inside the box, i.e. the bar really is still there
                if (tip < 0.0 || tip >= w - 3.0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0, x0.toDouble(), y0.toDouble(), (x0 + w).toDouble(), (y0 + h).toDouble(), 0.0)
                out[0] = rCells.toDouble()
                out[1] = rZl.toDouble()
                out[2] = rZr.toDouble()
            } else {
                out[0] = cells.toDouble()
                out[1] = (x0 + zl).toDouble()
                out[2] = (x0 + zr).toDouble()
            }
            out[3] = bn.toDouble()
            out[4] = if (tip >= 0.0) x0 + tip else -1.0
            return out
        } catch (e: Throwable) {
            Dbg.add("OpenCV box scan error: " + e.message)
            return null
        } finally {
            zm?.release()
            bm?.release()
        }
    }

    // The zone is not visible (the fill covers it) but the bar was seen a moment ago: if the blue fill still starts at
    // the left end of the remembered bar and the rest of the bar is dark track, the bar is still there.
    private fun fromMemory(hsv: Mat, bm: Mat, w: Int, h: Int, step: Int, redN: Double): DoubleArray? {
        val now = SystemClock.uptimeMillis()
        if (memW <= 0 || memStep != step || now - memAt > 400L) return null
        val x0 = memX
        val bw = minOf(memW, w - x0)
        val ya = memY + memH / 4
        val yb = maxOf(ya, memY + memH - 1 - memH / 4)
        if (bw < 8 || yb >= h) return null
        val rows = yb - ya + 1
        val counts = colCounts(bm, ya, yb, x0, x0 + bw - 1)
        val tipRel = runEnd(counts, rows, 0, w)
        if (tipRel < 0) return null
        if (tipRel >= bw - 3) return null
        val track = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(179.0, 79.0, 119.0), track)
        val tc = colCounts(track, ya, yb, x0, x0 + bw - 1)
        track.release()
        var tn = 0
        var all = 0
        for (k in tipRel until tc.size) {
            all++
            if (tc[k] * 2 >= rows) tn++
        }
        if (all >= 6 && tn * 2 < all) return null
        lastUsedMemory = true
        val out = DoubleArray(11)
        out[0] = memArea
        out[1] = (memZl * step).toDouble()
        out[2] = (memZr * step).toDouble()
        out[3] = tipRel.toDouble()
        out[4] = ((x0 + tipRel) * step).toDouble()
        out[5] = 1.0
        out[6] = (x0 * step).toDouble()
        out[7] = ((memY - 2) * step).toDouble()
        out[8] = ((x0 + bw) * step).toDouble()
        out[9] = ((memY + memH + 3) * step).toDouble()
        out[10] = redN
        return out
    }
}

// Result-panel detector: a coarse picture of the screen (64 x 36 brightness samples). Comparing the screen to the
// picture taken while the bar was showing tells when the caught / failed panel slid in and when it is gone again.
object Pn {
    const val GX = 64
    const val GY = 36

    fun sig(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(GX * GY)
        if (w < 2 || h < 2 || px.size < w * h) return out
        for (gy in 0 until GY) {
            val y = (((gy + 0.5) * h) / GY).toInt().coerceIn(0, h - 1)
            for (gx in 0 until GX) {
                val x = (((gx + 0.5) * w) / GX).toInt().coerceIn(0, w - 1)
                val p = px[y * w + x]
                out[gy * GX + gx] = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
            }
        }
        return out
    }

    // fraction of samples that changed at all (used by the anti-freeze check: a frozen screen changes nothing)
    fun motion(a: IntArray, b: IntArray): Double {
        val n = minOf(a.size, b.size)
        if (n <= 0) return 0.0
        var changed = 0
        for (i in 0 until n) if (Math.abs(a[i] - b[i]) > 6) changed++
        return changed.toDouble() / n
    }

    // fraction of samples that changed a lot; ex = box to ignore as fractions of the screen [x0, y0, x1, y1]
    fun diff(a: IntArray, b: IntArray, ex: FloatArray?): Double {
        val n = minOf(a.size, b.size)
        if (n < GX * GY) return 0.0
        var changed = 0
        var total = 0
        for (gy in 0 until GY) {
            val fy = (gy + 0.5f) / GY
            for (gx in 0 until GX) {
                val fx = (gx + 0.5f) / GX
                if (ex != null && fx >= ex[0] && fx <= ex[2] && fy >= ex[1] && fy <= ex[3]) continue
                total++
                val i = gy * GX + gx
                if (Math.abs(a[i] - b[i]) > 40) changed++
            }
        }
        return if (total == 0) 0.0 else changed.toDouble() / total
    }
}

// Adaptive frame processing (v2.0). Watches how long each scan takes compared with how fast the screen delivers
// frames and how often the bar is found, then picks the scan detail on its own:
//   - scans too slow for the frame rate  -> coarser sampling (every pixel step bigger), so no frame is ever late
//   - plenty of time left and the bar is being missed -> finer sampling for a more exact marker position
// It only changes by one step at a time and at most twice a second, so it never jumps around.
object Adapt {
    private var fullStep = 0
    private var roiStep = 0
    private var fullMs = 0.0
    private var roiMs = 0.0
    private var hit = 1.0
    private var lastChange = 0L
    private var screenW = 0

    @Synchronized
    fun reset(w: Int) {
        screenW = w
        fullStep = maxOf(2, w / 500)
        roiStep = if (w >= 1600) 2 else 1
        fullMs = 0.0
        roiMs = 0.0
        hit = 1.0
        lastChange = 0L
        info()
    }

    @Synchronized
    fun full(w: Int, on: Boolean): Int {
        if (!on) return maxOf(2, w / 500)
        if (fullStep == 0 || screenW != w) reset(w)
        return fullStep
    }

    @Synchronized
    fun roi(w: Int, on: Boolean): Int {
        if (!on) return if (w >= 1600) 2 else 1
        if (roiStep == 0 || screenW != w) reset(w)
        return roiStep
    }

    // ms = time the scan itself took, found = the bar was found, inRoi = it was the small-box scan
    @Synchronized
    fun report(ms: Long, found: Boolean, inRoi: Boolean, w: Int) {
        if (screenW != w || fullStep == 0) reset(w)
        if (inRoi) roiMs = if (roiMs == 0.0) ms.toDouble() else 0.8 * roiMs + 0.2 * ms
        else fullMs = if (fullMs == 0.0) ms.toDouble() else 0.8 * fullMs + 0.2 * ms
        hit = 0.9 * hit + 0.1 * (if (found) 1.0 else 0.0)
        val now = SystemClock.uptimeMillis()
        if (now - lastChange < 500L) return
        val fps = Dbg.capFps
        // time budget for one scan: a bit less than the gap between two frames (8 - 40 ms)
        val budget = (if (fps in 5..240) 1000.0 / fps else 33.0).coerceIn(8.0, 40.0) * 0.8
        if (inRoi) {
            if (roiMs > budget && roiStep < 3) { roiStep++; lastChange = now }
            else if (roiStep > 1 && roiMs < budget * 0.35 && hit < 0.85) { roiStep--; lastChange = now }
        } else {
            val maxStep = maxOf(4, w / 300)
            if (fullMs > budget && fullStep < maxStep) { fullStep++; lastChange = now }
            else if (fullStep > 2 && fullMs < budget * 0.4 && hit < 0.85) { fullStep--; lastChange = now }
        }
        info()
    }

    private fun info() {
        Dbg.scanInfo = "full 1/$fullStep ${fullMs.toInt()} ms, box 1/$roiStep ${roiMs.toInt()} ms"
    }
}


===== FILE: app/src/main/java/com/solmacro/Dbg.kt =====

package com.solmacro

object Dbg {
    private val lines = ArrayList<String>()
    @Volatile var state = "Idle"
    @Volatile var catches = 0
    @Volatile var sells = 0
    @Volatile var startMs = 0L
    @Volatile var fps = 0
    @Volatile var latency = 0
    // v2.0: capture speed and the adaptive scan controller
    @Volatile var capFps = 0
    @Volatile var scanInfo = "-"
    @Volatile var tapInfo = "-"
    @Volatile var biteMs = 0
    @Volatile var fails = 0
    @Volatile var inZone = 0
    @Volatile var biome = ""
    // why Start was refused (shown in the panel)
    @Volatile var blocker = ""
    // tap health: Android confirms or cancels every tap it is given
    @Volatile var tapOk = 0
    @Volatile var tapCancel = 0
    @Volatile var tapRefused = 0
    @Volatile var aiBias = "0%"
    @Volatile var aiInfo = ""
    // live detection monitor (shown in the app)
    @Volatile var zonePx = 0
    @Volatile var markerPx = 0
    @Volatile var barFound = false
    @Volatile var detector = "-"
    // v1.9: statistics and recovery counters (this session)
    @Volatile var unverified = 0
    @Volatile var clickFix = 0
    @Volatile var freezes = 0
    @Volatile var biteMin = 0
    @Volatile var biteMax = 0
    private val catchTimes = ArrayList<Long>()

    @Synchronized
    fun markCatch() {
        val now = android.os.SystemClock.uptimeMillis()
        catchTimes.add(now)
        while (catchTimes.isNotEmpty() && now - catchTimes[0] > 40 * 60000L) catchTimes.removeAt(0)
    }

    @Synchronized
    fun resetCatches() { catchTimes.clear() }

    // catches in each of the last n minutes, oldest first
    @Synchronized
    fun perMinute(n: Int): IntArray {
        val now = android.os.SystemClock.uptimeMillis()
        val out = IntArray(n)
        for (t in catchTimes) {
            val back = ((now - t) / 60000L).toInt()
            if (back in 0 until n) out[n - 1 - back]++
        }
        return out
    }

    @Synchronized
    fun add(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.add("$t $s")
        if (lines.size > 150) lines.removeAt(0)
    }

    @Synchronized
    fun tail(n: Int): String = lines.takeLast(n).joinToString("\n")
}


===== FILE: app/src/main/java/com/solmacro/DebugView.kt =====

package com.solmacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

// Rebuilt file: the overlay of the TEST / debug mode. Draws the search area, the zone and the marker.
class DebugView(c: Context) : View(c) {
    val rect = Rect()
    var color: Int = Color.RED
    var label: String = ""
    var zmin: Double = -1.0
    var zmax: Double = -1.0
    var mpos: Double = -1.0
    var vert: Boolean = false

    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        box.style = Paint.Style.STROKE
        box.strokeWidth = 4f
        zone.style = Paint.Style.FILL
        zone.color = Color.argb(80, 60, 255, 120)
        mark.style = Paint.Style.STROKE
        mark.strokeWidth = 6f
        mark.color = Color.WHITE
        txt.textSize = 34f
        txt.color = Color.WHITE
        txt.setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        box.color = color
        canvas.drawRect(rect, box)
        if (zmax >= 0.0) {
            if (vert) canvas.drawRect(rect.left.toFloat(), zmin.toFloat(), rect.right.toFloat(), zmax.toFloat(), zone)
            else canvas.drawRect(zmin.toFloat(), rect.top.toFloat(), zmax.toFloat(), rect.bottom.toFloat(), zone)
        }
        if (mpos >= 0.0) {
            if (vert) canvas.drawLine(rect.left.toFloat(), mpos.toFloat(), rect.right.toFloat(), mpos.toFloat(), mark)
            else canvas.drawLine(mpos.toFloat(), rect.top.toFloat(), mpos.toFloat(), rect.bottom.toFloat(), mark)
        }
        val ty = if (rect.top > 50) rect.top - 14f else rect.bottom + 40f
        canvas.drawText(label, rect.left.toFloat(), ty, txt)
    }
}


===== FILE: app/src/main/java/com/solmacro/EdgeTapper.kt =====

package com.solmacro

// v2.3 "script-like" tapping. Rule (what the macro scripts do):
//   the blue fill falls to the LEFT EDGE of the green zone  -> exactly ONE tap
//   it falls back to the edge again                          -> one more tap
//   the fill is BELOW the zone (zone moved right / up)       -> quick taps until the fill is in the zone
//   the fill is ABOVE the zone (zone moved left / down)      -> no taps at all until it has drained back into the zone
// It does not learn the lift or the drain while reeling (that learning drifted in 2.2 and ended in tap spam).
// The tap that was just sent is counted as "on its way" until its lift has had time to show, and a new tap needs a
// pause after the last one, so it can not run away. Python mirror with results: tests/edge_sim.py.
class EdgeTapper {
    private val taps = ArrayList<Long>()
    private val zh = ArrayList<DoubleArray>()      // [time, zone centre]
    private var g = 58.0
    private var d = 215.0
    private var lat = 50.0
    private var minGap = 80L
    private var ts = 0L
    private var tip = 0.0
    private var zc = 0.0
    private var zw = 0.0
    private var zv = 0.0
    private var last = 0L
    private var haveZone = false
    private val ramp = 60.0                        // a tap's lift takes about this long to show completely

    @Volatile var edgeTaps = 0
        private set
    @Volatile var rushTaps = 0
        private set

    @Synchronized
    fun reset(gain: Double, drain: Double, latencyMs: Double, holdMs: Long, gapMs: Long) {
        taps.clear()
        zh.clear()
        g = gain.coerceAtLeast(10.0)
        d = drain.coerceAtLeast(20.0)
        lat = latencyMs.coerceIn(20.0, 110.0)
        minGap = maxOf(gapMs, holdMs + 20L, 80L)
        ts = 0L
        tip = 0.0
        zv = 0.0
        last = 0L
        haveZone = false
        edgeTaps = 0
        rushTaps = 0
    }

    // A new picture of the bar (capT = when it was taken, uptime ms). tip = right end of the blue fill.
    @Synchronized
    fun see(capT: Long, tipPx: Double, haveTip: Boolean, zoneCenter: Double, zoneWidth: Double) {
        zc = zoneCenter
        zw = zoneWidth
        haveZone = true
        zh.add(doubleArrayOf(capT.toDouble(), zoneCenter))
        while (zh.size > 0 && capT - zh[0][0] > 400.0) zh.removeAt(0)
        var old: DoubleArray? = null
        for (z in zh) if (capT - z[0] in 100.0..260.0) { old = z; break }
        if (old != null) {
            val v = (zoneCenter - old[1]) / ((capT - old[0]) / 1000.0)
            zv = if (Math.abs(v) < 25.0) 0.0 else 0.6 * zv + 0.4 * v
        }
        if (haveTip) {
            ts = capT
            tip = tipPx
        }
        var i = 0
        while (i < taps.size) { if (taps[i] < capT - 1500L) taps.removeAt(i) else i++ }
    }

    // tap delay as measured by the ledger (Tapper.delay) once it has enough samples
    @Synchronized
    fun setLatency(ms: Double) {
        lat = ms.coerceIn(20.0, 110.0)
    }

    @Synchronized
    fun usable(now: Long): Boolean = haveZone && ts != 0L && now - ts <= 300L

    @Synchronized
    fun cancelled(fire: Long) {
        taps.remove(fire)
    }

    @Synchronized
    fun commit(now: Long, rush: Boolean) {
        last = now
        taps.add(now)
        if (rush) rushTaps++ else edgeTaps++
    }

    // true when a tap should go out right now
    @Synchronized
    fun want(now: Long): Boolean = decide(now) != 0

    // 0 = no tap, 1 = the edge tap, 2 = a quick catch-up tap
    @Synchronized
    fun decide(now: Long): Int {
        if (!usable(now)) return 0
        val t = now + lat + ramp / 2.0
        var unseen = 0.0
        for (f in taps) {
            val vis = ((ts - f - lat) / ramp).coerceIn(0.0, 1.0)
            unseen += 1.0 - vis
        }
        val p = tip - d * (t - ts) / 1000.0 + g * unseen
        val zl = zc - zw / 2.0 + (zv * (t - ts) / 1000.0).coerceIn(-zw, zw)
        val e = minOf(0.12 * zw, 0.5 * maxOf(0.0, zw - g))
        // below the zone: tap fast until the predicted fill (taps already on their way included) is inside the zone
        val rush = p < zl
        val refr = if (rush) minGap else maxOf(minGap.toDouble(), 0.5 * g / d * 1000.0).toLong()
        if (p <= zl + e && now - last >= refr) return if (rush) 2 else 1
        // watchdog: clearly below the zone and 260 ms without a tap (a tap that never lifted) -> tap again
        val raw = tip - d * (t - ts) / 1000.0
        if (raw < zl - 0.5 * g && now - last >= 260L) return 2
        return 0
    }

    fun info(): String = "edge taps $edgeTaps, catch-up taps $rushTaps"
}


===== FILE: app/src/main/java/com/solmacro/Fx.kt =====

package com.solmacro

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

// v2.3 small animation toolkit used by the sign-in screens and the overlays.
// Everything goes straight to its end state when "Animations" is off in the settings or when Android's own animation
// scale is 0 (system "remove animations" / battery saver), so nothing is ever left half way.
object Fx {
    fun on(c: Context): Boolean {
        if (Cfg.get(c, "anim") != 1) return false
        return try { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (_: Exception) { true }
    }

    private fun dp(v: View, x: Int) = x * v.resources.displayMetrics.density

    // slides up a little while fading in
    fun rise(v: View, delay: Long = 0, dy: Int = 26, dur: Long = 460) {
        if (!on(v.context)) { v.alpha = 1f; v.translationY = 0f; return }
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dp(v, dy)
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    // grows from small with a little overshoot
    fun pop(v: View, delay: Long = 0, from: Float = 0.55f, dur: Long = 520) {
        if (!on(v.context)) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.animate().cancel()
        v.alpha = 0f
        v.scaleX = from
        v.scaleY = from
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(dur).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    // short side to side shake (wrong input, failed sign-in)
    fun shake(v: View) {
        if (!on(v.context)) return
        val d = dp(v, 9)
        ObjectAnimator.ofFloat(v, "translationX", 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.35f, d * 0.35f, 0f).apply { duration = 440; start() }
    }

    // quick scale tick, for a value that changed
    fun bump(v: View) {
        if (!on(v.context)) return
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    // endless soft breathing; returns the animator so the caller can cancel it
    fun breathe(v: View, to: Float = 1.05f, dur: Long = 1700): Animator? {
        if (!on(v.context)) return null
        return ValueAnimator.ofFloat(1f, to).apply {
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v.scaleX = it.animatedValue as Float; v.scaleY = it.animatedValue as Float }
            start()
        }
    }

    // endless slow up and down float
    fun float(v: View, dy: Int = 4, dur: Long = 2600): Animator? {
        if (!on(v.context)) return null
        return ObjectAnimator.ofFloat(v, "translationY", -dp(v, dy), dp(v, dy)).apply {
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ring that grows and fades again and again (glow behind a logo)
    fun ripple(v: View, from: Float = 1f, to: Float = 1.45f, dur: Long = 2200): Animator? {
        if (!on(v.context)) { v.alpha = 0f; return null }
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = from + (to - from) * t
                v.scaleX = s
                v.scaleY = s
                v.alpha = 0.55f * (1f - t)
            }
            start()
        }
    }

    // text change: old text fades out, new text fades in
    fun swapText(t: android.widget.TextView, text: String) {
        if (t.text.toString() == text) return
        if (!on(t.context)) { t.text = text; return }
        t.animate().cancel()
        t.animate().alpha(0f).translationY(-dp(t, 6)).setDuration(110).withEndAction {
            t.text = text
            t.translationY = dp(t, 6)
            t.animate().alpha(1f).translationY(0f).setDuration(190).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    fun out(v: View, dur: Long = 260, end: () -> Unit) {
        if (!on(v.context)) { end(); return }
        v.animate().cancel()
        v.animate().alpha(0f).translationY(-dp(v, 18)).scaleX(0.97f).scaleY(0.97f).setDuration(dur).setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { v.animate().setListener(null); end() }
            }).start()
    }
}


===== FILE: app/src/main/java/com/solmacro/Hook.kt =====

package com.solmacro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Hook {
    // one or more webhook URLs, separated by new lines, spaces or commas
    private fun urls(c: Context): List<String> =
        Cfg.gets(c, "webhook").split(Regex("[\\s,;]+")).map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }

    // true when the text looks like a Discord webhook link (the settings screen uses it to warn about typos)
    fun looksValid(u: String): Boolean =
        Regex("^https://(www\\.|ptb\\.|canary\\.)?discord(app)?\\.com/api(/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+(\\?.*)?$").matches(u.trim().trimEnd('/'))

    // how many usable webhook links are saved right now
    fun count(c: Context): Int = urls(c).size

    // warms the guild/channel lookup so the first biome message does not wait for it
    fun prefetch(c: Context) {
        val list = urls(c)
        if (list.isEmpty()) return
        Thread { for (u in list) webhookInfo(u) }.start()
    }

    // keeps a "?thread_id=..." part of a webhook link working when we add our own parts
    private fun addQ(url: String, extra: String): String {
        val i = url.indexOf('?')
        return if (i < 0) url + "?" + extra else url + "&" + extra
    }

    private fun msgUrl(url: String, id: String): String {
        val i = url.indexOf('?')
        return if (i < 0) url + "/messages/" + id else url.substring(0, i) + "/messages/" + id + url.substring(i)
    }

    // "<@id>" for a user, "<@&id>" for a role (type & before the number in the settings). Empty if not set.
    fun mention(c: Context): String {
        val raw = Cfg.gets(c, "pingid").trim()
        val id = raw.filter { it.isDigit() }
        if (id.isEmpty()) return ""
        return if (raw.startsWith("&")) "<@&" + id + ">" else "<@" + id + ">"
    }

    fun send(c: Context, title: String, desc: String, color: Int = 0x3EA6FF, ping: Boolean = false) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val m = if (ping) mention(c) else ""
        val content = if (m.isEmpty()) "" else "\"content\":\"" + m + "\","
        Thread {
            for (url in list) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("User-Agent", "SolMacro/1.0")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = "{\"username\":\"LuminFish\"," + content + "\"embeds\":[{\"title\":\"" + esc(title) +
                        "\",\"description\":\"" + esc(desc) + "\",\"color\":" + color + "}]}"
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    Dbg.add("Webhook replied HTTP " + conn.responseCode)
                    conn.disconnect()
                } catch (e: Exception) {
                    Dbg.add("Webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // sends a JPEG picture (periodic screenshots) to every webhook
    fun sendImage(c: Context, text: String, jpg: ByteArray) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        Thread {
            for (url in list) {
                try {
                    val b = "----solmacro" + System.currentTimeMillis()
                    val nl = "\r\n"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b)
                    conn.setRequestProperty("User-Agent", "SolMacro/1.0")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 20000
                    conn.outputStream.use { o ->
                        o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"payload_json\"" + nl +
                            "Content-Type: application/json" + nl + nl +
                            "{\"username\":\"LuminFish\",\"content\":\"" + esc(text) + "\"}" + nl).toByteArray())
                        o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"screen.jpg\"" + nl +
                            "Content-Type: image/jpeg" + nl + nl).toByteArray())
                        o.write(jpg)
                        o.write((nl + "--" + b + "--" + nl).toByteArray())
                    }
                    Dbg.add("Screenshot webhook replied HTTP " + conn.responseCode)
                    conn.disconnect()
                } catch (e: Exception) {
                    Dbg.add("Screenshot webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------------------------------
    // Biome message, built like the reference picture:
    //   ### Rainy Biome            (heading)
    //   <your private server link> (only if you set one)
    //   embed: left bar in the biome color, title "BIOME STARTED - RAINY Found", the biome icon at the right,
    //          fields Channel (channel mention), Time (Discord relative time), Message (Jump to Message link),
    //          small footer text at the bottom.
    // ---------------------------------------------------------------------------------------------
    private val infoCache = HashMap<String, Array<String>>()

    // guild and channel of a webhook (Discord returns them when the URL is opened), so Channel and Jump to Message work
    private fun webhookInfo(url: String): Array<String>? {
        synchronized(infoCache) { val k = infoCache[url]; if (k != null) return k }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            if (body.isEmpty()) null else {
                val j = JSONObject(body)
                val r = arrayOf(j.optString("guild_id", ""), j.optString("channel_id", ""))
                if (r[1].isEmpty()) null else { synchronized(infoCache) { infoCache[url] = r }; r }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun footerText(c: Context): String {
        val custom = Cfg.gets(c, "whfooter").trim()
        if (custom.isNotEmpty()) return custom
        val m = mention(c)
        return if (m.isNotEmpty()) "Message $m for any bugs" else "LuminFish - Sol's RNG macro"
    }

    private fun field(name: String, value: String, inline: Boolean = false): JSONObject =
        JSONObject().put("name", name).put("value", value).put("inline", inline)

    private fun dur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "${h}h ${m}m ${sec}s" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
    }

    private fun biomeEmbed(b: Biome.Info, started: Boolean, durMs: Long, channel: String, ts: Long, jump: String, footer: String, icon: Boolean): JSONObject {
        val e = JSONObject()
        e.put("title", if (started) "BIOME STARTED - " + b.title + " Found" else "BIOME ENDED - " + b.title + " Ended")
        e.put("color", b.color)
        if (icon) e.put("thumbnail", JSONObject().put("url", "attachment://biome.png"))
        val f = JSONArray()
        f.put(field("Channel", channel))
        f.put(field("Time", "<t:$ts:R>"))
        f.put(field("Message", "[Jump to Message]($jump)"))
        if (!started && durMs > 0) f.put(field("Duration", dur(durMs)))
        e.put("fields", f)
        e.put("footer", JSONObject().put("text", footer))
        return e
    }

    fun biome(c: Context, b: Biome.Info, started: Boolean, durMs: Long, ping: Boolean) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val link = Cfg.gets(c, "pslink").trim().let { if (it.startsWith("http")) it else "" }
        val m = if (ping) mention(c) else ""
        val content = (if (m.isEmpty()) "" else m + "\n") + "### " + Biome.nice(b) + " Biome" + (if (link.isEmpty()) "" else "\n" + link)
        val footer = footerText(c)
        val icon: ByteArray? = try { c.applicationContext.assets.open("biomes/" + b.key + ".png").use { it.readBytes() } } catch (e: Exception) { null }
        Thread {
            for (url in list) {
                try {
                    val info = webhookInfo(url)
                    val ts = System.currentTimeMillis() / 1000
                    val ch = if (info != null) "<#" + info[1] + ">" else "#biome-alerts"
                    val base = if (info != null && info[0].isNotEmpty()) "https://discord.com/channels/" + info[0] + "/" + info[1] else "https://discord.com/app"
                    val p = JSONObject()
                    p.put("username", "LuminFish")
                    p.put("content", content)
                    p.put("embeds", JSONArray().put(biomeEmbed(b, started, durMs, ch, ts, base, footer, icon != null)))
                    if (icon != null) p.put("attachments", JSONArray().put(JSONObject().put("id", 0).put("filename", "biome.png")))
                    val resp = post(addQ(url, "wait=true"), p.toString(), icon)
                    if (resp == null || info == null || info[0].isEmpty()) continue
                    // now the message exists: point "Jump to Message" at it
                    val j = JSONObject(resp)
                    val id = j.optString("id", "")
                    if (id.isEmpty()) continue
                    val p2 = JSONObject()
                    p2.put("embeds", JSONArray().put(biomeEmbed(b, started, durMs, ch, ts, "$base/$id", footer, icon != null)))
                    val att = j.optJSONArray("attachments")?.optJSONObject(0)?.optString("id", "") ?: ""
                    if (att.isNotEmpty()) p2.put("attachments", JSONArray().put(JSONObject().put("id", att)))
                    try {
                        val code = patch(msgUrl(url, id), p2.toString())
                        if (code !in 200..299) Dbg.add("Jump link edit replied HTTP $code (the link points to the channel)")
                    } catch (e: Exception) {
                        Dbg.add("Jump link edit not possible on this phone (the link points to the channel)")
                    }
                } catch (e: Exception) {
                    Dbg.add("Biome webhook failed: " + e.message)
                }
            }
        }.start()
    }

    // a small status report (used every N minutes while the macro runs)
    fun status(c: Context, title: String, color: Int, fields: List<Triple<String, String, Boolean>>, footer: String) {
        val list = urls(c)
        if (Cfg.get(c, "whon") != 1 || list.isEmpty()) return
        val e = JSONObject()
        e.put("title", title)
        e.put("color", color)
        val f = JSONArray()
        for (t in fields) f.put(field(t.first, t.second, t.third))
        e.put("fields", f)
        e.put("footer", JSONObject().put("text", footer))
        val p = JSONObject()
        p.put("username", "LuminFish")
        p.put("embeds", JSONArray().put(e))
        Thread {
            for (url in list) {
                try { post(url, p.toString(), null) } catch (ex: Exception) { Dbg.add("Status webhook failed: " + ex.message) }
            }
        }.start()
    }

    // Posts the message (with the icon picture attached when there is one). Returns the reply text when it worked.
    private fun post(url: String, json: String, png: ByteArray?): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            if (png == null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            } else {
                val b = "----solmacro" + System.currentTimeMillis()
                val nl = "\r\n"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b)
                conn.outputStream.use { o ->
                    o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"payload_json\"" + nl +
                        "Content-Type: application/json" + nl + nl + json + nl).toByteArray())
                    o.write(("--" + b + nl + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"biome.png\"" + nl +
                        "Content-Type: image/png" + nl + nl).toByteArray())
                    o.write(png)
                    o.write((nl + "--" + b + "--" + nl).toByteArray())
                }
            }
            val code = conn.responseCode
            Dbg.add("Webhook replied HTTP $code")
            return if (code in 200..299) {
                try { conn.inputStream.bufferedReader().readText() } catch (e: Exception) { null }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    // Android's HttpURLConnection may refuse the PATCH word, so there is a fallback that sets it directly.
    private fun patch(url: String, json: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            try {
                conn.requestMethod = "PATCH"
            } catch (e: java.net.ProtocolException) {
                val f = HttpURLConnection::class.java.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, "PATCH")
                try {
                    val d = conn.javaClass.getDeclaredField("delegate")
                    d.isAccessible = true
                    val inner = d.get(conn)
                    val f2 = HttpURLConnection::class.java.getDeclaredField("method")
                    f2.isAccessible = true
                    f2.set(inner, "PATCH")
                } catch (_: Exception) {}
            }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "SolMacro/1.0")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.outputStream.use { it.write(json.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // sample message so you can check the design in your channel
    fun biomeTest(c: Context) {
        val b = Biome.byKey("rainy") ?: return
        biome(c, b, true, 0L, false)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}


===== FILE: app/src/main/java/com/solmacro/LoginActivity.kt =====

package com.solmacro

import android.animation.Animator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

private const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
private const val HUG = ViewGroup.LayoutParams.WRAP_CONTENT

// slow drifting colour glow behind the sign-in screen
class AuroraView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var anim: ValueAnimator? = null
    private var g1: RadialGradient? = null
    private var g2: RadialGradient? = null
    private var g3: RadialGradient? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val r = maxOf(w, h) * 0.55f
        g1 = RadialGradient(0f, 0f, r, 0x555865F2, 0x005865F2, Shader.TileMode.CLAMP)
        g2 = RadialGradient(0f, 0f, r * 0.9f, 0x4438A0FF, 0x0038A0FF, Shader.TileMode.CLAMP)
        g3 = RadialGradient(0f, 0f, r * 0.8f, 0x33A06BFF, 0x00A06BFF, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Fx.on(context)) {
            anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                duration = 26000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF03050E.toInt())
        fun blob(g: RadialGradient?, x: Float, y: Float) {
            if (g == null) return
            c.save()
            c.translate(x, y)
            paint.shader = g
            c.drawCircle(0f, 0f, maxOf(w, h), paint)
            c.restore()
        }
        blob(g1, w * (0.2f + 0.12f * Math.sin(t.toDouble()).toFloat()), h * (0.12f + 0.06f * Math.cos(t.toDouble()).toFloat()))
        blob(g2, w * (0.9f + 0.1f * Math.cos(t * 1.0 + 1.0).toFloat()), h * (0.45f + 0.08f * Math.sin(t * 1.0 + 2.0).toFloat()))
        blob(g3, w * (0.3f + 0.1f * Math.sin(t * 1.0 + 4.0).toFloat()), h * (0.95f + 0.04f * Math.cos(t * 1.0 + 3.0).toFloat()))
    }
}

// v2.3 sign-in screen: Sign up or Log in, with Discord or Roblox (or a guest). Opened before the main screen until a choice is made.
class LoginActivity : Activity() {
    private val cBg = 0xFF03050E.toInt()
    private val cCard = 0xE60A1028.toInt()
    private val cBd = 0xFF22306B.toInt()
    private val cTx = 0xFFE6ECFF.toInt()
    private val cMut = 0xFF8E9BD0.toInt()
    private val cBad = 0xFFFF6B6B.toInt()
    private val cOk = 0xFF38D39F.toInt()
    private val cAcc = 0xFF8FB4FF.toInt()

    private lateinit var status: TextView
    private lateinit var busy: ProgressBar
    private lateinit var btnD: LinearLayout
    private lateinit var btnR: LinearLayout
    private lateinit var lblD: TextView
    private lateinit var lblR: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var card: LinearLayout
    private lateinit var savedBox: LinearLayout
    private lateinit var savedList: LinearLayout
    private lateinit var tabSignup: TextView
    private lateinit var tabLogin: TextView
    private lateinit var indicator: View
    private lateinit var tabBar: FrameLayout
    private lateinit var setup: LinearLayout
    private lateinit var idD: EditText
    private lateinit var idR: EditText
    private lateinit var reD: EditText
    private lateinit var reR: EditText
    private var mode = "signup"
    private val loops = ArrayList<Animator>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(base: Context) {
        val cfg = android.content.res.Configuration(base.resources.configuration)
        if (cfg.fontScale > 1.0f) cfg.fontScale = 1.0f
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    private fun shape(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(stroke), strokeColor)
    }

    private fun tv(t: String, size: Float, color: Int = cTx, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }

    private fun tile(letter: String, bg: Int): TextView = tv(letter, 16f, Color.WHITE, true, true).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
    }

    private fun pressFx(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    // big provider button; the label view is returned too so the text can change between Sign up and Log in
    private fun provider(name: String, letter: String, tileColor: Int, fill: Int, stroke: Int, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val lbl = tv("", 16f, Color.WHITE, true)
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(18), 0)
            background = shape(fill, 14, 1, stroke)
            isClickable = true
            elevation = dp(2).toFloat()
            setOnClickListener { onClick() }
        }
        pressFx(b)
        b.addView(tile(letter, tileColor), LinearLayout.LayoutParams(dp(34), dp(34)))
        b.addView(lbl, LinearLayout.LayoutParams(0, HUG, 1f).apply { leftMargin = dp(14) })
        b.addView(tv("›", 22f, 0xB0FFFFFF.toInt(), false))
        return Pair(b, lbl)
    }

    private fun field(hint: String, value: String, uri: Boolean = false) = EditText(this).apply {
        setHint(hint)
        setText(value)
        setHintTextColor(0xFF5E6A9C.toInt())
        setTextColor(cTx)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setSingleLine(true)
        inputType = if (uri) InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = shape(0xFF070C20.toInt(), 10, 1, cBd)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        mode = if (Auth.accounts(this).isNotEmpty()) "login" else "signup"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(44), dp(22), dp(28))
        }

        // logo with a glow ring that keeps spreading
        val logoBox = FrameLayout(this)
        val ring = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), cAcc); setColor(Color.TRANSPARENT) } }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.brry_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()); setStroke(dp(2), cAcc) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(6).toFloat()
        }
        logoBox.addView(ring, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        logoBox.addView(logo, FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        root.addView(logoBox, LinearLayout.LayoutParams(dp(150), dp(120)))
        titleTv = tv("", 28f, cTx, true, true)
        subTv = tv("", 14f, cMut, false, true)
        root.addView(titleTv, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(6) })
        root.addView(subTv, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(4) })

        // Sign up | Log in
        tabBar = FrameLayout(this).apply { background = shape(0xCC0A1028.toInt(), 14, 1, cBd) }
        indicator = View(this).apply { background = shape(0xFF2A3A82.toInt(), 11) }
        tabBar.addView(indicator, FrameLayout.LayoutParams(0, dp(38), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(4) })
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabSignup = tv("Sign up", 14f, cTx, true, true).apply { setOnClickListener { switchTab("signup") } }
        tabLogin = tv("Log in", 14f, cMut, true, true).apply { setOnClickListener { switchTab("login") } }
        tabRow.addView(tabSignup, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabRow.addView(tabLogin, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabBar.addView(tabRow, FrameLayout.LayoutParams(FILL, dp(46)))
        root.addView(tabBar, LinearLayout.LayoutParams(FILL, dp(46)).apply { topMargin = dp(22) })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cCard, 20, 1, cBd)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(260) }
        }
        savedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        savedBox.addView(tv("Your accounts on this phone", 12f, cMut, true))
        savedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        savedBox.addView(savedList, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(6) })
        savedBox.addView(tv("or use the browser", 11.5f, 0xFF5E6A9C.toInt(), false, true), LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(8); bottomMargin = dp(8) })
        card.addView(savedBox, LinearLayout.LayoutParams(FILL, HUG))
        val pd = provider("Discord", "D", 0xFF5865F2.toInt(), 0xFF5865F2.toInt(), 0xFF7A85F5.toInt()) { start(Auth.DISCORD) }
        val pr = provider("Roblox", "R", 0xFF2B2F38.toInt(), 0xFF1A1D25.toInt(), 0xFF4A505E.toInt()) { start(Auth.ROBLOX) }
        btnD = pd.first; lblD = pd.second; btnR = pr.first; lblR = pr.second
        card.addView(btnD, LinearLayout.LayoutParams(FILL, dp(56)))
        card.addView(btnR, LinearLayout.LayoutParams(FILL, dp(56)).apply { topMargin = dp(12) })
        busy = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        card.addView(busy, LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        status = tv("", 12.5f, cMut, false, true).apply { visibility = View.GONE }
        card.addView(status, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(12) })
        root.addView(card, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(16) })

        val guest = tv("Continue as guest", 14f, cAcc, true, true).apply {
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener { Auth.guest(this@LoginActivity); goMain() }
        }
        pressFx(guest)
        root.addView(guest, LinearLayout.LayoutParams(HUG, HUG).apply { topMargin = dp(8) })

        val toggle = tv("Sign-in setup", 12f, cMut, false, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { setup.visibility = if (setup.visibility == View.VISIBLE) View.GONE else View.VISIBLE; if (setup.visibility == View.VISIBLE) Fx.rise(setup, 0, 14, 300) }
        }
        root.addView(toggle, LinearLayout.LayoutParams(HUG, HUG))
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = shape(cCard, 16, 1, cBd)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        setup.addView(tv("Paste the public client IDs of your Discord and Roblox apps (see SIGNIN_SETUP.md). Redirect URLs can stay empty.", 11.5f, cMut))
        idD = field("Discord client ID", Cfg.gets(this, "auth_discord_id"))
        idR = field("Roblox client ID", Cfg.gets(this, "auth_roblox_id"))
        reD = field("Discord redirect URL (default luminfish://auth)", Cfg.gets(this, "auth_discord_redirect"), true)
        reR = field("Roblox redirect URL (default luminfish://auth)", Cfg.gets(this, "auth_roblox_redirect"), true)
        for (f in listOf(idD, reD, idR, reR)) setup.addView(f, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(8) })
        val save = tv("Save", 14f, Color.WHITE, true, true).apply {
            background = shape(0xFF2A3A82.toInt(), 12)
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener {
                Cfg.puts(this@LoginActivity, "auth_discord_id", idD.text.toString().trim())
                Cfg.puts(this@LoginActivity, "auth_roblox_id", idR.text.toString().trim())
                Cfg.puts(this@LoginActivity, "auth_discord_redirect", reD.text.toString().trim())
                Cfg.puts(this@LoginActivity, "auth_roblox_redirect", reR.text.toString().trim())
                show("Saved.", cOk)
            }
        }
        pressFx(save)
        setup.addView(save, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(12) })
        root.addView(setup, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(6) })

        val foot = tv("LuminFish only reads your public profile name and picture. No password is typed in this app and nothing is stored on a server. Accounts are kept on this phone only. Not affiliated with Discord or Roblox.", 11f, 0xFF5E6A9C.toInt(), false, true)
        root.addView(foot, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(18) })

        val frame = FrameLayout(this)
        frame.addView(AuroraView(this), FrameLayout.LayoutParams(FILL, FILL))
        frame.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(root) }, FrameLayout.LayoutParams(FILL, FILL))
        setContentView(frame)

        // start state without animation, then the entrance
        tabBar.post {
            val half = (tabBar.width - dp(8)) / 2
            indicator.layoutParams = (indicator.layoutParams as FrameLayout.LayoutParams).apply { width = half }
            applyMode(false)
            indicator.translationX = if (mode == "login") half.toFloat() else 0f
            entrance(logoBox, ring, logo, foot, guest, toggle)
        }
        handle(intent)
    }

    private fun entrance(logoBox: View, ring: View, logo: View, foot: View, guest: View, toggle: View) {
        Fx.pop(logo, 0)
        Fx.ripple(ring)?.let { loops.add(it) }
        Fx.float(logo, 3, 2800)?.let { loops.add(it) }
        Fx.rise(titleTv, 120)
        Fx.rise(subTv, 190)
        Fx.rise(tabBar, 260)
        Fx.rise(card, 330)
        Fx.rise(btnD, 430, 22, 420)
        Fx.rise(btnR, 510, 22, 420)
        Fx.rise(guest, 600, 14, 380)
        Fx.rise(toggle, 660, 10, 320)
        Fx.rise(foot, 720, 10, 380)
    }

    override fun onDestroy() {
        for (a in loops) a.cancel()
        loops.clear()
        super.onDestroy()
    }

    // ---- Sign up / Log in ----
    private fun switchTab(m: String) {
        if (m == mode) return
        mode = m
        applyMode(true)
    }

    private fun applyMode(animate: Boolean) {
        val half = (tabBar.width - dp(8)) / 2
        val x = if (mode == "login") half.toFloat() else 0f
        if (animate && Fx.on(this)) indicator.animate().translationX(x).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start() else indicator.translationX = x
        tabSignup.setTextColor(if (mode == "signup") cTx else cMut)
        tabLogin.setTextColor(if (mode == "login") cTx else cMut)
        val t = if (mode == "login") "Welcome back" else "Create your account"
        val s = if (mode == "login") "Log in to the account you already made" else "Sign up with Discord or Roblox, it takes a few seconds"
        val verb = if (mode == "login") "Log in" else "Sign up"
        if (animate) {
            Fx.swapText(titleTv, t); Fx.swapText(subTv, s); Fx.swapText(lblD, "$verb with Discord"); Fx.swapText(lblR, "$verb with Roblox")
        } else {
            titleTv.text = t; subTv.text = s; lblD.text = "$verb with Discord"; lblR.text = "$verb with Roblox"
        }
        rebuildSaved(animate)
        status.visibility = View.GONE
    }

    private fun rebuildSaved(animate: Boolean) {
        savedList.removeAllViews()
        val list = Auth.accounts(this)
        if (mode != "login" || list.isEmpty()) { savedBox.visibility = View.GONE; return }
        var i = 0
        for (acc in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(0xFF0E1638.toInt(), 14, 1, cBd)
                setPadding(dp(10), dp(8), dp(6), dp(8))
                isClickable = true
                setOnClickListener { loginSaved(acc, this) }
            }
            pressFx(row)
            val f = Auth.avatarOf(this, acc)
            val av = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()) }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                val bm = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
                setImageBitmap(bm ?: initial(acc))
            }
            row.addView(av, LinearLayout.LayoutParams(dp(40), dp(40)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(acc.name.ifEmpty { Auth.label(acc.p) + " user" }, 15f, cTx, true))
            col.addView(tv(Auth.label(acc.p), 11.5f, cMut))
            row.addView(col, LinearLayout.LayoutParams(0, HUG, 1f).apply { leftMargin = dp(12) })
            row.addView(tv("Log in ›", 13f, cAcc, true))
            val x = tv("✕", 15f, 0xFF7E89BD.toInt(), false, true).apply {
                setPadding(dp(12), dp(8), dp(10), dp(8))
                isClickable = true
                setOnClickListener { confirmRemove(acc) }
            }
            row.addView(x)
            savedList.addView(row, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = if (i == 0) 0 else dp(8) })
            if (animate) Fx.rise(row, 60L * i, 12, 300)
            i++
        }
        savedBox.visibility = View.VISIBLE
    }

    private fun initial(acc: Auth.Account): android.graphics.Bitmap {
        val sz = dp(80)
        val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (acc.p == Auth.DISCORD) 0xFF5865F2.toInt() else 0xFF3A3F4B.toInt()
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
        p.color = Color.WHITE
        p.textSize = sz * 0.46f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        cv.drawText((acc.name.firstOrNull() ?: '?').uppercaseChar().toString(), sz / 2f, sz / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    private fun confirmRemove(acc: Auth.Account) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remove account?")
            .setMessage(acc.name + " (" + Auth.label(acc.p) + ") will be removed from this phone. You can sign up again any time.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> Auth.removeAccount(this, acc); rebuildSaved(true); if (Auth.accounts(this).isEmpty()) { mode = "signup"; applyMode(true) } }
            .show()
    }

    private fun loginSaved(acc: Auth.Account, row: View) {
        Auth.setCurrent(this, acc)
        welcome("Welcome back, " + acc.name.ifEmpty { Auth.label(acc.p) + " user" })
    }

    // ---- feedback ----
    private fun show(t: String, color: Int) {
        status.setTextColor(color)
        if (status.visibility != View.VISIBLE) { status.text = t; status.visibility = View.VISIBLE; Fx.rise(status, 0, 8, 240) } else Fx.swapText(status, t)
    }

    private fun fail(t: String) {
        show(t, cBad)
        Fx.shake(card)
    }

    private fun working(on: Boolean) {
        busy.visibility = if (on) View.VISIBLE else View.GONE
        for (v in listOf(btnD, btnR)) {
            v.isEnabled = !on
            v.animate().alpha(if (on) 0.5f else 1f).setDuration(200).start()
        }
    }

    private fun welcome(msg: String) {
        show("✓  $msg", cOk)
        val c = card
        c.postDelayed({ Fx.out(c, 320) { goMain() } }, if (Fx.on(this)) 650L else 0L)
    }

    private fun start(p: String) {
        val err = Auth.begin(this, p, mode)
        if (err != null) {
            fail(err)
            if (err.contains("not set up")) { setup.visibility = View.VISIBLE; Fx.rise(setup, 0, 14, 300) }
        } else {
            show("Finish " + (if (mode == "login") "logging in" else "signing up") + " with " + Auth.label(p) + " in the browser, then you come back here.", cMut)
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "luminfish") return
        i.data = null
        working(true)
        show("One moment…", cMut)
        Auth.finish(this, d) { ok, msg ->
            working(false)
            when {
                ok -> welcome(if (msg.startsWith("back:")) "Welcome back, " + msg.substring(5) else "Account created. Welcome, " + msg.substring(4))
                msg.startsWith("noaccount:") -> {
                    fail("No LuminFish account for this " + msg.substring(10) + " yet. Create one with Sign up.")
                    card.postDelayed({ switchTab("signup") }, 900L)
                }
                else -> fail(msg)
            }
        }
    }
}


===== FILE: app/src/main/java/com/solmacro/Lumin.kt =====

package com.solmacro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// LuminFish colors (night sky blue, glowing ice-blue lines)
object Pal {
    val BG = 0xFF03050E.toInt()
    val PANEL_TOP = 0xFF060A1C.toInt()
    val PANEL_BOT = 0xFF02030A.toInt()
    val CARD = 0xB3080D24.toInt()
    val CARD_SOLID = 0xFF0A1028.toInt()
    val CARD2 = 0xFF111A3C.toInt()
    val LINE = 0x663F5FD0
    val LINE_SOLID = 0xFF2A3A82.toInt()
    val GLOW = 0xFF8FB4FF.toInt()
    val GLOW2 = 0xFFB6A8FF.toInt()
    val ICE = 0xFFDCE6FF.toInt()
    val TEXT = 0xFFE6ECFF.toInt()
    val MUTED = 0xFF8E9BD0.toInt()
    val OK = 0xFF38D39F.toInt()
    val WARN = 0xFFFFB84D.toInt()
    val BAD = 0xFFFF7A8A.toInt()
}

private fun withA(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

private fun fourStar(p: Path, cx: Float, cy: Float, r: Float, fat: Float = 0.10f) {
    p.reset()
    p.moveTo(cx, cy - r)
    p.quadTo(cx + r * fat, cy - r * fat, cx + r, cy)
    p.quadTo(cx + r * fat, cy + r * fat, cx, cy + r)
    p.quadTo(cx - r * fat, cy + r * fat, cx - r, cy)
    p.quadTo(cx - r * fat, cy - r * fat, cx, cy - r)
    p.close()
}

// Twinkling stars and slow drifting nebula glow. Cheap: about 25 frames a second.
class StarField(ctx: Context, private val count: Int = 48) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random(11)
    private val sx = FloatArray(count) { rnd.nextFloat() }
    private val sy = FloatArray(count) { rnd.nextFloat() }
    private val ss = FloatArray(count) { 0.6f + rnd.nextFloat() * 1.5f }
    private val sp = FloatArray(count) { 0.5f + rnd.nextFloat() * 1.6f }
    private val ph = FloatArray(count) { rnd.nextFloat() * 6.28f }
    private val dr = FloatArray(count) { 0.004f + rnd.nextFloat() * 0.012f }
    private val star = Path()
    private var blobs: Array<RadialGradient>? = null
    private var blobR = 0f
    private var up = false
    var strength = 1f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        blobR = maxOf(w, h) * 0.55f
        if (blobR <= 0f) return
        blobs = arrayOf(
            RadialGradient(0f, 0f, blobR, intArrayOf(0x402A44C8, 0x00000000), null, Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, blobR * 0.8f, intArrayOf(0x30604AC8, 0x00000000), null, Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, blobR * 0.7f, intArrayOf(0x2A2C7EDC, 0x00000000), null, Shader.TileMode.CLAMP)
        )
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); up = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { up = false; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = SystemClock.uptimeMillis() / 1000f
        val b = blobs
        if (b != null) {
            p.style = Paint.Style.FILL
            val pos = arrayOf(
                floatArrayOf(w * (0.25f + 0.1f * Math.sin(t * 0.21).toFloat()), h * (0.3f + 0.1f * Math.cos(t * 0.17).toFloat())),
                floatArrayOf(w * (0.8f + 0.08f * Math.cos(t * 0.19).toFloat()), h * (0.7f + 0.1f * Math.sin(t * 0.23).toFloat())),
                floatArrayOf(w * (0.55f + 0.12f * Math.sin(t * 0.13).toFloat()), h * (0.95f + 0.05f * Math.cos(t * 0.29).toFloat()))
            )
            for (i in b.indices) {
                p.shader = b[i]
                c.save()
                c.translate(pos[i][0], pos[i][1])
                p.alpha = (255 * strength).toInt()
                c.drawCircle(0f, 0f, blobR, p)
                c.restore()
            }
            p.shader = null
        }
        p.style = Paint.Style.FILL
        for (i in 0 until count) {
            val y = ((sy[i] - dr[i] * t) % 1f + 1f) % 1f
            val x = sx[i] + 0.012f * Math.sin(t * 0.6 + ph[i]).toFloat()
            val tw = 0.5f + 0.5f * Math.sin(t * sp[i] * 2.2 + ph[i]).toFloat()
            val a = (0.15f + 0.85f * tw) * strength
            val r = ss[i] * d
            p.color = withA(0xFFCFE0FF.toInt(), (255 * a).toInt())
            if (ss[i] > 1.6f) {
                fourStar(star, x * w, y * h, r * 2.6f, 0.08f)
                c.drawPath(star, p)
            } else {
                c.drawCircle(x * w, y * h, r * 0.7f, p)
            }
        }
        if (up) postInvalidateDelayed(40L)
    }
}

// The glowing ornate border: breathing glow, a light that runs around the edge, twinkling star ornaments.
class GlowFrame(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Path()
    private val seg = Path()
    private val wing = Path()
    private val star = Path()
    private val pm = PathMeasure()
    private var len = 0f
    private var up = false
    var radius = 22f
    var tint = Pal.GLOW

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val ins = 9f * d
        border.reset()
        border.addRoundRect(RectF(ins, ins, w - ins, h - ins), radius * d, radius * d, Path.Direction.CW)
        pm.setPath(border, false)
        len = pm.length
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); up = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { up = false; super.onDetachedFromWindow() }

    private fun starAt(c: Canvas, x: Float, y: Float, r: Float, tw: Float) {
        val k = 0.75f + 0.35f * tw
        fourStar(star, x, y, r * 1.9f * k, 0.12f)
        f.color = withA(tint, (70 * tw).toInt() + 25)
        c.drawPath(star, f)
        fourStar(star, x, y, r * k, 0.10f)
        f.color = withA(Pal.ICE, 200 + (55 * tw).toInt())
        c.drawPath(star, f)
    }

    private fun wings(c: Canvas, cx: Float, y: Float, dir: Float, a: Int) {
        wing.reset()
        wing.moveTo(cx + dir * 12f * d, y)
        wing.cubicTo(cx + dir * 34f * d, y - 7f * d, cx + dir * 60f * d, y + 4f * d, cx + dir * 92f * d, y + 15f * d)
        p.strokeWidth = 1.6f * d
        p.color = withA(Pal.ICE, a)
        c.drawPath(wing, p)
        wing.reset()
        wing.moveTo(cx + dir * 22f * d, y + 1f * d)
        wing.cubicTo(cx + dir * 40f * d, y + 8f * d, cx + dir * 58f * d, y + 12f * d, cx + dir * 72f * d, y + 26f * d)
        p.strokeWidth = 1.1f * d
        p.color = withA(tint, a * 3 / 4)
        c.drawPath(wing, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || len <= 0f) return
        val t = SystemClock.uptimeMillis() / 1000f
        val breath = 0.5f + 0.5f * Math.sin(t * 1.7).toFloat()
        // soft glow made of a few strokes, no blur filter (stays smooth on every phone)
        for (i in 3 downTo 0) {
            p.strokeWidth = (1.4f + i * 2.6f) * d
            p.color = withA(tint, ((0.20f - i * 0.04f) * (0.7f + 0.5f * breath) * 255).toInt())
            c.drawPath(border, p)
        }
        p.strokeWidth = 1.3f * d
        p.color = withA(Pal.ICE, 215)
        c.drawPath(border, p)
        // light running around the border
        val segLen = len * 0.14f
        val s = ((t * 0.16f) % 1f) * len
        for (pass in 0..1) {
            seg.reset()
            val ok = if (s + segLen <= len) pm.getSegment(s, s + segLen, seg, true)
            else if (pass == 0) pm.getSegment(s, len, seg, true) else pm.getSegment(0f, s + segLen - len, seg, true)
            if (ok) {
                p.strokeWidth = 5f * d
                p.color = withA(tint, 90)
                c.drawPath(seg, p)
                p.strokeWidth = 2.2f * d
                p.color = 0xFFFFFFFF.toInt()
                c.drawPath(seg, p)
            }
            if (s + segLen <= len) break
        }
        val ins = 9f * d
        val tw1 = 0.5f + 0.5f * Math.sin(t * 2.3).toFloat()
        val tw2 = 0.5f + 0.5f * Math.sin(t * 1.9 + 1.7).toFloat()
        val tw3 = 0.5f + 0.5f * Math.sin(t * 2.7 + 3.1).toFloat()
        wings(c, w / 2f, ins, -1f, 200)
        wings(c, w / 2f, ins, 1f, 200)
        starAt(c, w / 2f, ins, 15f * d, tw1)
        starAt(c, w / 2f, h - ins, 11f * d, tw2)
        starAt(c, ins, h * 0.5f, 9f * d, tw3)
        starAt(c, w - ins, h * 0.5f, 9f * d, tw1)
        starAt(c, ins + 4f * d, ins + 4f * d, 5f * d, tw2)
        starAt(c, w - ins - 4f * d, ins + 4f * d, 5f * d, tw3)
        starAt(c, ins + 4f * d, h - ins - 4f * d, 5f * d, tw1)
        starAt(c, w - ins - 4f * d, h - ins - 4f * d, 5f * d, tw2)
        if (up) postInvalidateDelayed(33L)
    }
}

// The animated halo behind the floating round icon: breathing glow, pulse rings, an orbiting spark.
class HaloView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Path()
    private var up = false
    private var angle = 0f
    private var lastT = 0L
    private var spin = 0f
    private var col = Pal.GLOW
    var state = 0   // 0 idle, 1 running, 2 paused

    override fun onAttachedToWindow() { super.onAttachedToWindow(); up = true; lastT = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { up = false; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = minOf(width, height) / 2f - 14f * d
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastT).coerceIn(0L, 100L)) / 1000f
        lastT = now
        val target = when (state) { 1 -> Pal.OK; 2 -> Pal.WARN; else -> Pal.GLOW }
        col = Color.rgb(
            Color.red(col) + ((Color.red(target) - Color.red(col)) * minOf(1f, dt * 5f)).toInt(),
            Color.green(col) + ((Color.green(target) - Color.green(col)) * minOf(1f, dt * 5f)).toInt(),
            Color.blue(col) + ((Color.blue(target) - Color.blue(col)) * minOf(1f, dt * 5f)).toInt())
        angle = (angle + (if (state == 1) 170f else 55f) * dt) % 360f
        spin = (spin + (if (state == 1) 260f else 0f) * dt) % 360f
        val t = now / 1000f
        val breath = 0.5f + 0.5f * Math.sin(t * 2.2).toFloat()
        p.style = Paint.Style.STROKE
        // glow
        for (i in 2 downTo 0) {
            p.strokeWidth = (2f + i * 3f) * d
            p.color = withA(col, ((0.30f - i * 0.08f) * (0.6f + 0.6f * breath) * 255).toInt())
            c.drawCircle(cx, cy, r0 + (1.5f + breath * 2f) * d, p)
        }
        // pulse ring every 2.4 s
        val k = (t % 2.4f) / 2.4f
        p.strokeWidth = 1.6f * d * (1f - k) + 0.5f
        p.color = withA(col, (160 * (1f - k)).toInt())
        c.drawCircle(cx, cy, r0 + 2f * d + k * 11f * d, p)
        // tilted orbit with a small planet
        c.save()
        c.rotate(-22f, cx, cy)
        val ra = r0 + 9f * d
        val rb = r0 * 0.46f
        p.strokeWidth = 1f * d
        p.color = withA(Pal.ICE, 70)
        c.drawOval(RectF(cx - ra, cy - rb, cx + ra, cy + rb), p)
        val a = Math.toRadians(angle.toDouble())
        val px = cx + (Math.cos(a) * ra).toFloat()
        val py = cy + (Math.sin(a) * rb).toFloat()
        p.style = Paint.Style.FILL
        p.color = withA(col, 110)
        c.drawCircle(px, py, 4.2f * d, p)
        p.color = Color.WHITE
        c.drawCircle(px, py, 2.1f * d, p)
        c.restore()
        // spinning arc while the macro runs
        if (state == 1) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.6f * d
            p.strokeCap = Paint.Cap.ROUND
            p.color = withA(col, 235)
            c.drawArc(RectF(cx - r0 - 3f * d, cy - r0 - 3f * d, cx + r0 + 3f * d, cy + r0 + 3f * d), spin, 70f, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        // twinkles
        p.style = Paint.Style.FILL
        val a1 = 0.5f + 0.5f * Math.sin(t * 3.1).toFloat()
        val a2 = 0.5f + 0.5f * Math.sin(t * 2.4 + 2.0).toFloat()
        fourStar(star, cx - r0 * 1.12f, cy - r0 * 0.95f, (2.5f + 3f * a1) * d, 0.1f)
        p.color = withA(Pal.ICE, (90 + 165 * a1).toInt())
        c.drawPath(star, p)
        fourStar(star, cx + r0 * 1.1f, cy + r0 * 0.9f, (2f + 3f * a2) * d, 0.1f)
        p.color = withA(Pal.ICE, (90 + 165 * a2).toInt())
        c.drawPath(star, p)
        if (up) postInvalidateDelayed(33L)
    }
}

// Floating button = halo + round icon. Bobs gently up and down.
class FloatIcon(ctx: Context, val icon: ImageView, sizeDp: Int) : FrameLayout(ctx) {
    val halo = HaloView(ctx)
    private val d = ctx.resources.displayMetrics.density

    init {
        addView(halo, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(icon, LayoutParams((sizeDp * d).toInt(), (sizeDp * d).toInt(), android.view.Gravity.CENTER))
    }

    fun setState(s: Int) { if (halo.state != s) { halo.state = s } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        icon.animate().cancel()
        val a = android.animation.ObjectAnimator.ofFloat(icon, "translationY", -2f * d, 2f * d)
        a.duration = 1700
        a.repeatCount = android.animation.ObjectAnimator.INFINITE
        a.repeatMode = android.animation.ObjectAnimator.REVERSE
        a.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        a.start()
        bob = a
    }

    private var bob: android.animation.ObjectAnimator? = null

    override fun onDetachedFromWindow() {
        bob?.cancel()
        bob = null
        super.onDetachedFromWindow()
    }
}

// Panel background chosen by the user: a photo or a GIF (animated on Android 9 and newer).
object PanelBg {
    fun file(c: Context) = File(c.filesDir, "panel_bg.bin")

    fun has(c: Context) = Cfg.get(c, "bg_custom") == 1 && file(c).exists()

    fun load(c: Context, maxPx: Int): Drawable? {
        if (!has(c)) return null
        val f = file(c)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val big = maxOf(info.size.width, info.size.height)
                    if (big > maxPx) {
                        val k = maxPx.toFloat() / big
                        dec.setTargetSize(maxOf(1, (info.size.width * k).toInt()), maxOf(1, (info.size.height * k).toInt()))
                    }
                }
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                return d
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            val o2 = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / maxPx) }
            val b = BitmapFactory.decodeFile(f.absolutePath, o2) ?: return null
            return BitmapDrawable(c.resources, b)
        } catch (e: Throwable) {
            Dbg.add("Could not load the panel background: " + e.message)
            return null
        }
    }

    // copies the picked file into the app (max 40 MB) and checks it can be shown
    fun save(c: Context, uri: Uri): Boolean {
        return try {
            val tmp = File(c.filesDir, "panel_bg.tmp")
            var total = 0L
            c.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 40L * 1024 * 1024) throw Exception("file is bigger than 40 MB")
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw Exception("cannot open the file")
            val f = file(c)
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw Exception("cannot save the file")
            Cfg.put(c, "bg_custom", 1)
            if (load(c, 512) == null) { clear(c); false } else true
        } catch (e: Throwable) {
            Dbg.add("Panel background not saved: " + e.message)
            false
        }
    }

    fun clear(c: Context) {
        Cfg.put(c, "bg_custom", 0)
        try { file(c).delete() } catch (_: Exception) {}
    }
}

// The creator's Roblox avatar, loaded from the internet (Roblox web API) and cached, shown on the Credits page.
object Online {
    const val USER = "chromeplated12301"
    @Volatile private var busy = false
    @Volatile private var doneOnce = false

    private fun cacheFile(c: Context) = File(c.filesDir, "creator_avatar.png")

    fun cached(c: Context): Bitmap? {
        val f = cacheFile(c)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun http(url: String, post: String?): String {
        val u = URL(url)
        val cn = u.openConnection() as HttpURLConnection
        cn.connectTimeout = 6000
        cn.readTimeout = 8000
        cn.setRequestProperty("Accept", "application/json")
        if (post != null) {
            cn.requestMethod = "POST"
            cn.doOutput = true
            cn.setRequestProperty("Content-Type", "application/json")
            cn.outputStream.use { it.write(post.toByteArray()) }
        }
        return cn.inputStream.bufferedReader().use { it.readText() }
    }

    // runs once per app start, calls back on a background thread with the new picture (or null when offline)
    fun refresh(c: Context, done: (Bitmap?) -> Unit) {
        if (busy || doneOnce) return
        busy = true
        Thread {
            var out: Bitmap? = null
            try {
                val j = org.json.JSONObject(http("https://users.roblox.com/v1/usernames/users",
                    "{\"usernames\":[\"" + USER + "\"],\"excludeBannedUsers\":true}"))
                val id = j.getJSONArray("data").getJSONObject(0).getLong("id")
                val t = org.json.JSONObject(http("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds=$id&size=150x150&format=Png&isCircular=false", null))
                val img = t.getJSONArray("data").getJSONObject(0).getString("imageUrl")
                val bytes = URL(img).openStream().use { it.readBytes() }
                out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (out != null) {
                    cacheFile(c).outputStream().use { it.write(bytes) }
                    doneOnce = true
                }
            } catch (e: Throwable) {
                Dbg.add("Online avatar not loaded: " + e.message)
            }
            busy = false
            done(out)
        }.start()
    }
}


===== FILE: app/src/main/java/com/solmacro/MacroService.kt =====

package com.solmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ObjectAnimator
import android.view.animation.OvershootInterpolator
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private typealias WL = WindowManager.LayoutParams

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class MacroService : AccessibilityService() {

    companion object {
        var inst: MacroService? = null
    }

    private class Step(val x: Int, val y: Int, val hold: Long, val wait: Long)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var floatIcon: FloatIcon? = null
    @Volatile private var critUntil = 0L
    private var panelSawRoblox = false
    private var panelGone = 0
    private var startBtn: TextView? = null
    private var running = false
    private var catches = 0
    private var tl: List<Step> = emptyList()
    private var idx = 0
    private var lastPkg = ""

    // detection state
    private var detectMode = false
    private var phase = 0
    private var miss = 0
    private var sellIdx = 0
    private var t0 = 0L
    private var lastShot = 0L
    private var lastMark = -1.0
    private var lastHeld = false
    private var votes = 0
    // the scan thread runs at display priority so a busy phone does not make detection late
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor(java.util.concurrent.ThreadFactory { r ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
            r.run()
        }
    })
    // screen reading (OCR) can take seconds, so it gets its own thread and never blocks the reel loop
    private val bgOcr = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var dbgView: DebugView? = null
    private var pauseBtn: TextView? = null
    private var iconView: ImageView? = null
    private var panelUi: PanelUi? = null
    private var biteAvg = 0.0
    private var wZone = 0.0
    private var nextTapAt = 0L
    private var tapCount = 0
    private var framesN = 0
    private var framesIn = 0
    private var histN = 0
    private val tipHist = DoubleArray(3)
    private var lastTap = 0L
    private var screenW = 2340
    private var biteN = 0
    private var paused = false
    private var lastSeen = 0L
    private var holdUntil = 0L
    private var failCasts = 0
    private var fpsT = 0L
    private var fpsN = 0
    private var dbgCount = 0
    private var vel = 0.0
    private var lastM = -1.0
    private var lastMT = 0L
    private var roi: IntArray? = null
    private var roiN = 0
    private var roiMiss = 0
    private var lastGesture = 0L
    private var lastShotSent = 0L
    private val taskLast = LongArray(3)
    private var curTask = 0
    private var bioCur = ""
    private var bioPend = ""
    private var bioLast = 0L
    @Volatile private var bioBusy = false
    private var bioWarned = false
    // frame timing, controller, rejoin, text watch
    private var lastFrameId = -1L
    private var lastFrameAt = 0L
    private var curFrameNs = 0L
    private var lastVelNs = 0L
    private var newFrame = true
    private var lastCtl = 0L
    private var tapAcc = 0.0
    private var drainEst = 0.0
    private var drainN = 0
    private var p7t = 0L
    private var fgAt = 0L
    private var fgVal = ""
    private var rejoinTries = 0
    private var rejoining = false
    private var lastDiscCheck = 0L
    // reel-loop heartbeat: dnext() and every finished scan stamp it, a long silence means the chain broke
    @Volatile private var loopBeat = 0L
    @Volatile private var trackGen = 0
    private var loopRestarts = 0
    private var bioPendAt = 0L
    @Volatile private var bioStartAt = 0L
    private var pulse: ObjectAnimator? = null
    // screen-detection timing of the result panel (v1.8): the macro looks at the screen instead of waiting a fixed time
    @Volatile private var lastSig: IntArray? = null
    @Volatile private var reelSig: IntArray? = null
    @Volatile private var reelBox: FloatArray? = null
    private var p7State = 0
    private var p7Settle = 0
    private var p7Gone = 0
    private var p7Taps = 0
    private var p7DismissAt = 0L
    private var p7Seen = false
    private var p7Sell = false
    private var p7Prev: IntArray? = null
    // v1.9: auto-sell options, verification, recovery, anti-freeze, stats
    private var lastSellAt = 0L
    private var catchesAtSell = 0
    private var pausedAt = 0L
    private var pausedMs = 0L
    @Volatile private var tapFailStreak = 0
    private var clickRecoveries = 0
    private var lastRecoverAt = 0L
    private var lastForce = 0L
    private var panelOpenTicks = 0
    private var verifyMiss = 0
    private var verifiedEver = false
    private var verifyOff = false
    private var fzPrev: IntArray? = null
    private var fzSince = 0L
    @Volatile private var fzBusy = false
    private var lastFreezeAt = 0L
    private var freezeStep = 0
    private var iconLp: WL? = null

    // ---- tap engine: its own thread, so tap timing never waits for the screen reader or the UI ----
    private class Obs(val capT: Long, val tip: Double, val haveTip: Boolean, val zc: Double, val wZone: Double, val at: Long, val zv: Double,
                      val lastTip: Double = 0.0, val lastTipT: Long = 0L)
    @Volatile private var obs: Obs? = null
    @Volatile private var engineOn = false
    @Volatile private var engDrain = 0.0
    @Volatile private var engGain = 0.0
    @Volatile private var engLastTap = 0L
    @Volatile private var scanT = 0L
    private val tapTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val tapper = Tapper()
    private val tapThread = android.os.HandlerThread("brry-tap", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val eh = Handler(tapThread.looper)
    private var engAcc = 0.0
    // timed tapping: the last REAL marker position seen this reel, and when the engine started
    @Volatile private var realTip = 0.0
    @Volatile private var realTipT = 0L
    @Volatile private var engStartAt = 0L
    private var engLast = 0L
    private var gapBonus = 0L
    private var chkTaps = 0
    private var chkOk = 0
    private var chkCancel = 0
    private var gainEst = 0.0
    private var gainN = 0
    private var prevTip = 0.0
    private var prevCapT = 0L
    private var progressT = 0L
    private var aiStuck = 0
    private val zhT = LongArray(16)
    private val zhZ = DoubleArray(16)
    private var zhN = 0
    private var zvEst = 0.0
    private var qT0 = 0L
    private var qTip0 = 0.0
    private var p7Back = 0
    private var p7Resumes = 0
    private var firstCast = false
    private var curCastFirst = false
    private var lastDotAt = 0L
    // v2.3: tap dots, screen recognition of the Fish / Exit / X buttons
    @Volatile private var marks: MarksView? = null
    private val edgeT = EdgeTapper()
    private var cycBite = 0L
    private var cycReel = 0L
    private var failStreak = 0
    private var castMiss = 0
    private val engine = object : Runnable {
        override fun run() {
            if (!engineOn) return
            try { engineStep() } catch (e: Exception) { Dbg.add("Tap engine error: " + e.message) }
            if (engineOn) eh.postDelayed(this, 4L)
        }
    }
    @Volatile private var watchBusy = false
    private var watchLast = 0L
    private val watchSeen = HashMap<String, Long>()
    private val th = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            try { services() } catch (e: Exception) { Dbg.add("Background tick error: " + e.message) }
            th.postDelayed(this, 2000L)
        }
    }

    // biome watch has its own fast ticker (the 2 s background tick made alerts late)
    private val bioTicker = object : Runnable {
        override fun run() {
            try { biomeTick() } catch (e: Exception) { Dbg.add("Biome tick error: " + e.message) }
            th.postDelayed(this, 400L)
        }
    }

    // v2.0: when Roblox is closed or left, the open panel closes by itself
    private val panelWatch = object : Runnable {
        override fun run() {
            try { watchPanel() } catch (e: Exception) { Dbg.add("Panel watch error: " + e.message) }
            th.postDelayed(this, 500L)
        }
    }

    private fun watchPanel() {
        if (panelUi == null) { panelSawRoblox = false; panelGone = 0; return }
        if (atRoblox()) {
            panelSawRoblox = true
            panelGone = 0
        } else if (panelSawRoblox) {
            panelGone++
            if (panelGone >= 3) {
                Dbg.add("Roblox was closed, closing the panel")
                panelSawRoblox = false
                panelGone = 0
                closePanel()
            }
        }
    }

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Cv.init()
        Ai.load(applicationContext)
        showPanel()
        th.removeCallbacks(ticker)
        th.postDelayed(ticker, 2000L)
        th.removeCallbacks(bioTicker)
        th.postDelayed(bioTicker, 1000L)
        th.removeCallbacks(panelWatch)
        th.postDelayed(panelWatch, 700L)
        Hook.prefetch(applicationContext)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != packageName && p != "com.android.systemui") { lastPkg = p; fgVal = p; fgAt = SystemClock.uptimeMillis() }
        }
    }

    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        engineOn = false
        try { tapThread.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        th.removeCallbacks(ticker)
        th.removeCallbacks(bioTicker)
        th.removeCallbacks(panelWatch)
        hideClickers(false)
        closePanel()
        stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        inst = null
        return super.onUnbind(intent)
    }

    // Which app is in front. The answer is cached for 700 ms because asking Android is slow
    // and the macro loop calls this many times a second.
    private fun foreground(): String {
        val now = SystemClock.uptimeMillis()
        if (now - fgAt < 700L && fgVal.isNotEmpty()) return fgVal
        fgAt = now
        val rp = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (rp != null && rp != packageName && rp != "com.android.systemui") lastPkg = rp
        fgVal = lastPkg
        return fgVal
    }

    private fun atRoblox(): Boolean {
        val f = foreground()
        return f == Cfg.ROBLOX || f.startsWith("com.roblox.client")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    fun toastPublic(m: String) = toast(m)

    fun togglePause() {
        if (!running) return
        if (paused) {
            paused = false
            if (pausedAt > 0L) pausedMs += SystemClock.uptimeMillis() - pausedAt
            pausedAt = 0L
            pauseBtn?.text = "PAUSE"
            label()
            Dbg.add("Resumed")
            if (phase == 6) phase = 0
            if (detectMode) dnext() else next()
        } else {
            paused = true
            pausedAt = SystemClock.uptimeMillis()
            panelOpenTicks = 0
            stopEngine()
            h.removeCallbacksAndMessages(null)
            pauseBtn?.text = "RESUME"
            Dbg.state = "Paused"
            label()
            Dbg.add("Paused")
        }
    }

    // ---- v2.3: recognise the Fish / Exit / X buttons on the live screen (Scene.kt) ----
    private fun sceneOn() = Cap.active && Cv.ready && Cfg.get(applicationContext, "scene") == 1

    // Looks at the screen: "Fish" button -> tap it where it really is; "Exit" button -> fishing is already running, wait
    // (tapping there would leave the fishing); nothing found -> look again, after 3 misses tap the saved Cast point.
    private fun castBySight() {
        val c = applicationContext
        val gen = trackGen
        bg.execute {
            var hit: Scene.Hit? = null
            try {
                val reg = Cap.region(0, 0, 100000, 100000, 2)
                if (reg != null) {
                    hit = Scene.find(c, reg, Scene.FISH or Scene.EXIT)
                    if (hit == null && castMiss >= 2) {
                        Dbg.add("Fish/Exit button not recognised (best match " + (Scene.lastBest * 100).toInt() + "%)")
                        Scene.savePicture(c, reg, "nofish")
                    }
                }
            } catch (e: Throwable) {
                Dbg.add("Screen look failed: " + e.message)
            }
            val hh = hit
            h.post {
                if (!running || gen != trackGen || phase != 0) return@post
                if (hh != null && hh.kind == Scene.EXIT) {
                    castMiss = 0
                    if (failCasts > 0) {
                        // the bar never came: leave the fishing with the real Exit button, then cast again
                        Dbg.add("Exit button: leaving the fishing to cast again")
                        tapRetry(hh.x, hh.y, 60L, 3, "exit")
                        h.postDelayed({ if (running && gen == trackGen && phase == 0) castBySight() }, 900L)
                    } else {
                        Dbg.add("Exit button showing: already fishing, waiting for the bar")
                        phase = 1
                        t0 = SystemClock.uptimeMillis()
                        h.postDelayed({ dnext() }, 150L)
                    }
                } else if (hh != null) {
                    castMiss = 0
                    Dbg.add("Fish button seen (" + (hh.score * 100).toInt() + "%), tapping it")
                    tapRetry(hh.x, hh.y, Cfg.get(c, "cast").toLong(), 3, "cast")
                    phase = 1
                    t0 = SystemClock.uptimeMillis()
                    h.postDelayed({ dnext() }, Cfg.get(c, "cast") + Cfg.get(c, "castdelay").toLong())
                } else {
                    castMiss++
                    if (castMiss < 3) {
                        h.postDelayed({ if (running && gen == trackGen && phase == 0) castBySight() }, 400L)
                    } else {
                        castMiss = 0
                        Dbg.add("No Fish button seen on the screen, tapping the saved Cast point")
                        tapRetry(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), 3, "cast")
                        phase = 1
                        t0 = SystemClock.uptimeMillis()
                        h.postDelayed({ dnext() }, Cfg.get(c, "cast") + Cfg.get(c, "castdelay").toLong())
                    }
                }
            }
        }
    }

    // The X on the "Fishing Failed" / "Fish Caught!" box: found on the screen, the saved Close point is the fallback.
    private fun dismissTap() {
        if (!sceneOn()) { tapPoint("dismiss"); return }
        val c = applicationContext
        val gen = trackGen
        bg.execute {
            var hit: Scene.Hit? = null
            try {
                val reg = Cap.region(0, 0, 100000, 100000, 2)
                if (reg != null) {
                    hit = Scene.find(c, reg, Scene.XBTN)
                    if (hit == null) {
                        Dbg.add("X button not recognised (best match " + (Scene.lastBest * 100).toInt() + "%), using the saved Close point")
                        Scene.savePicture(c, reg, "nox")
                    }
                }
            } catch (e: Throwable) {
                Dbg.add("Screen look failed: " + e.message)
            }
            val hh = hit
            h.post {
                if (!running || gen != trackGen) return@post
                if (hh != null) {
                    Dbg.add("X button seen (" + (hh.score * 100).toInt() + "%) at " + hh.x + "," + hh.y + ", tapping it")
                    tapRetry(hh.x, hh.y, 60L, 3, "dismiss")
                } else tapPoint("dismiss")
            }
        }
    }

    // ---- v2.3: small circles where taps land ----
    private fun showMarks() {
        if (!::wm.isInitialized || marks != null || Cfg.get(applicationContext, "tapdots") != 1) return
        val v = MarksView(this)
        val lp = WL(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WL.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            marks = v
        } catch (e: Exception) {
            Dbg.add("Could not show the tap dots: " + e.message)
        }
    }

    private fun hideMarks() {
        val v = marks ?: return
        marks = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun tapPoint(name: String) {
        val c = applicationContext
        tapRetry(Cfg.get(c, name + "_x"), Cfg.get(c, name + "_y"), 60L, 3, name)
    }

    // A tap that must arrive (Cast, the X on the result panel, Sell...). Android cancels a tap when another gesture
    // is running and may refuse it, so it is sent again (up to 3 times) instead of being lost.
    private fun tapRetry(x: Int, y: Int, hold: Long, left: Int, tag: String) {
        val now = SystemClock.uptimeMillis()
        marks?.add(x, y)
        lastGesture = now
        critUntil = now + hold + 200L
        try {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, maxOf(hold, 1L)))
                .build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Dbg.tapOk++
                    tapFailStreak = 0
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Dbg.tapCancel++
                    if (left > 1 && running) {
                        Dbg.add("Tap $tag cancelled, sending it again")
                        h.postDelayed({ if (running) tapRetry(x, y, hold, left - 1, tag) }, 90L)
                    } else {
                        tapFailStreak++
                        if (tapFailStreak >= 8) clickRecover("Android cancelled the last 8 taps")
                    }
                }
            }, null)
            if (!ok) {
                Dbg.tapRefused++
                if (left > 1) {
                    h.postDelayed({ tapRetry(x, y, hold, left - 1, tag) }, 90L)
                } else {
                    tapFailStreak++
                    if (tapFailStreak >= 8) h.post { clickRecover("Android refused the last 8 taps") }
                }
            }
        } catch (e: Exception) {
            Dbg.add("Tap error: " + e.message)
        }
    }

    private fun moveHold(dir: String, ms: Long) {
        val c = applicationContext
        val r = Cfg.get(c, "joyr")
        val d = when (dir) { "w" -> "up"; "s" -> "down"; "a" -> "left"; "d" -> "right"; else -> dir }
        val dx = when (d) { "left" -> -r; "right" -> r; else -> 0 }
        val dy = when (d) { "up" -> -r; "down" -> r; else -> 0 }
        val cx = Cfg.get(c, "joy_x").toFloat()
        val cy = Cfg.get(c, "joy_y").toFloat()
        val p1 = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 120L, true)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(s1).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val p2 = Path().apply { moveTo(cx + dx, cy + dy) }
                val s2 = s1.continueStroke(p2, 0L, maxOf(ms, 1L), false)
                dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), null, null)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Dbg.add("Move cancelled")
            }
        }, null)
        if (!ok) Dbg.add("Android refused the move")
    }

    private fun camSwipe(dir: String, amt: Int) {
        val c = applicationContext
        val cx = Cfg.get(c, "cam_x").toFloat()
        val cy = Cfg.get(c, "cam_y").toFloat()
        val dx = when (dir) { "left" -> -amt; "right" -> amt; else -> 0 }
        val dy = when (dir) { "up" -> -amt; "down" -> amt; else -> 0 }
        val p = Path().apply { moveTo(cx, cy); lineTo(cx + dx, cy + dy) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 250L)).build(), null, null)
    }

    // script lines: move up 1.5 | camera left 200 | shiftlock | tap inv | wait 1
    private fun runScript(text: String, done: () -> Unit) {
        val lines = text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        fun step(i: Int) {
            if (!running) return
            if (i >= lines.size) { done(); return }
            val p = lines[i].split(" ").filter { it.isNotEmpty() }
            var wait = 100L
            try {
                when (p[0].lowercase()) {
                    "wait" -> wait = (p[1].toDouble() * 1000).toLong()
                    "move" -> { val ms = (p[2].toDouble() * 1000).toLong(); moveHold(p[1].lowercase(), ms); wait = ms + 300 }
                    "camera" -> { camSwipe(p[1].lowercase(), p.getOrNull(2)?.toInt() ?: 250); wait = 450 }
                    "shiftlock" -> { tapPoint("shift"); wait = 350 }
                    "tap" -> {
                        val rx = p.getOrNull(1)?.toIntOrNull()
                        val ry = p.getOrNull(2)?.toIntOrNull()
                        if (rx != null && ry != null) tap(rx, ry, 60L) else tapPoint(p[1].lowercase())
                        wait = 350
                    }
                    else -> Dbg.add("Unknown script line: " + lines[i])
                }
            } catch (e: Exception) {
                Dbg.add("Bad script line: " + lines[i])
            }
            h.postDelayed({ step(i + 1) }, wait)
        }
        step(0)
    }

    // ---------- auto rejoin ----------
    private fun launchRoblox() {
        val c = applicationContext
        val link = Cfg.gets(c, "pslink").trim()
        try {
            val i = if (link.startsWith("http") || link.startsWith("roblox:"))
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(Cfg.ROBLOX)
            else packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } else {
                Dbg.add("Roblox is not installed")
            }
        } catch (e: Exception) {
            Dbg.add("Could not open Roblox: " + e.message)
        }
    }

    // Roblox left the screen (closed, crashed, kicked). With auto rejoin on, open it again and carry on.
    private fun lostRoblox() {
        if (!running || rejoining) return
        if (Cfg.get(applicationContext, "rejoin") != 1) {
            stop()
            toast("Roblox closed, macro stopped")
            return
        }
        beginRejoin("Roblox is not in front")
    }

    private fun beginRejoin(why: String) {
        if (!running || rejoining) return
        val c = applicationContext
        if (rejoinTries >= 5) {
            stop()
            toast("Could not get back into Roblox, stopped")
            Hook.send(c, "Macro stopped", "Roblox did not come back after 5 tries.", 0xFF6B6B, true)
            return
        }
        rejoining = true
        rejoinTries++
        Dbg.state = "Rejoining ($rejoinTries/5)"
        Dbg.add("$why, rejoining ($rejoinTries/5)")
        Hook.send(c, "Rejoining Roblox", "$why. Attempt $rejoinTries of 5.", 0xFFB84D, true)
        stopEngine()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            launchRoblox()
            h.postDelayed({ afterRejoin() }, maxOf(Cfg.get(c, "rejoinsec"), 15) * 1000L)
        }, 2500L)
    }

    private fun afterRejoin() {
        if (!running) return
        val c = applicationContext
        fgAt = 0L
        rejoining = false
        if (atRoblox()) {
            Dbg.add("Back in Roblox, continuing")
            Hook.send(c, "Back in Roblox", "The macro continues.", 0x38D39F)
            rejoining = true
            h.postDelayed({
                rejoining = false
                rejoinTries = 0
                phase = 0
                roi = null
                failCasts = 0
                runScript(Cfg.gets(c, "script_start")) { dnext() }
            }, 6000L)
        } else {
            lostRoblox()
        }
    }

    // ---------- reading words from the screen (merchant, aura, disconnect) ----------
    private fun watchBox(): IntArray {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "wat1_x"), Cfg.get(c, "wat2_x"))
        val x2 = maxOf(Cfg.get(c, "wat1_x"), Cfg.get(c, "wat2_x"))
        val y1 = minOf(Cfg.get(c, "wat1_y"), Cfg.get(c, "wat2_y"))
        val y2 = maxOf(Cfg.get(c, "wat1_y"), Cfg.get(c, "wat2_y"))
        if (x2 - x1 < 40 || y2 - y1 < 12) return intArrayOf(0, 0, 100000, 100000)
        return intArrayOf(x1, y1, x2, y2)
    }

    // forWatch = true: look for your keywords. false: look for a disconnect screen.
    private fun ocrCheck(forWatch: Boolean) {
        val c = applicationContext
        if (watchBusy || !Cap.active) return
        watchBusy = true
        bgOcr.execute {
            try {
                val a = if (forWatch) watchBox() else intArrayOf(0, 0, 100000, 100000)
                val big = (a[2] - a[0]).toLong() * (a[3] - a[1]).toLong() > 700000L
                val reg = Cap.region(a[0], a[1], a[2] - a[0], a[3] - a[1], if (big) 2 else 1)
                if (reg == null) return@execute
                val bmp = Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
                val text = Ocr.read(bmp)
                if (text == null) { bmp.recycle(); return@execute }
                val flat = text.lowercase().filter { it.isLetterOrDigit() }
                if (!forWatch) {
                    bmp.recycle()
                    if (flat.contains("disconnected") || flat.contains("lostconnection") || flat.contains("youwerekicked") || flat.contains("reconnect")) {
                        h.post { beginRejoin("Disconnect screen found") }
                    }
                    return@execute
                }
                val words = Cfg.gets(c, "watchwords").ifBlank { "mari, jester" }
                    .split(",", "\n").map { it.trim().lowercase() }.filter { it.filter { ch -> ch.isLetterOrDigit() }.length >= 3 }
                val now = SystemClock.uptimeMillis()
                for (w in words) {
                    val wf = w.filter { it.isLetterOrDigit() }
                    if (!flat.contains(wf)) continue
                    val seen = watchSeen[wf] ?: 0L
                    if (seen != 0L && now - seen < 5 * 60000L) continue
                    watchSeen[wf] = now
                    Dbg.add("Watch: saw \"" + w + "\" on the screen")
                    val ping = Cfg.get(c, "pingwatch") == 1
                    val o = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
                    val head = if (ping) Hook.mention(c) + " " else ""
                    Hook.sendImage(c, head + "Seen on screen: " + w.uppercase() + " (read from the screen, may be wrong)", o.toByteArray())
                }
                bmp.recycle()
            } catch (e: Throwable) {
                Dbg.add("Screen reading error: " + e.message)
            } finally {
                watchBusy = false
            }
        }
    }

    // ---------- timed macro loop ----------
    private fun build(): List<Step> {
        val c = applicationContext
        val l = ArrayList<Step>()
        l.add(Step(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), Cfg.get(c, "wait").toLong()))
        repeat(Cfg.get(c, "taps")) {
            l.add(Step(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), 40L, Cfg.get(c, "gap").toLong()))
        }
        val every = Cfg.get(c, "every")
        if (Cfg.get(c, "sell") == 1 && every > 0 && (catches + 1) % every == 0) {
            for (k in listOf("inv", "sellall", "confirm")) {
                l.add(Step(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L, Cfg.get(c, "mdelay").toLong()))
            }
        }
        return l
    }

    fun toggle() { if (running) stop() else start() }

    // Why Start would be refused right now, or null when everything is ready.
    fun startProblem(): String? {
        val c = applicationContext
        if (Cfg.get(c, "pts_ok") != 1) return "Tap Show auto clickers and drag the circles onto your buttons first"
        if (!Cap.active) return "Screen capture is off. Turn it on first (tap Fast capture), the macro reads the live screen"
        if (!atRoblox()) return "Open Roblox first"
        return null
    }

    // three ready checks for the panel: label, ok
    fun checklist(): List<Pair<String, Boolean>> {
        val c = applicationContext
        fgAt = 0L
        return listOf(
            "Button positions are set" to (Cfg.get(c, "pts_ok") == 1),
            (if (Cap.active) "Screen capture is on (live detection)" else "Screen capture is off, tap here to turn it on") to Cap.active,
            "Roblox is in front" to (atRoblox())
        )
    }

    fun needPoints() {
        toast("Tap Show auto clickers and drag the circles onto your buttons first")
        Dbg.add("Start blocked: button positions not set")
    }

    private fun start() {
        fgAt = 0L
        val why = startProblem()
        if (why != null) {
            Dbg.blocker = why
            Dbg.add("Start blocked: $why")
            toast(why)
            return
        }
        Dbg.blocker = ""
        if (clickersOn) hideClickers()
        // the panel swallows the taps meant for the game. It is removed right now (no fade) so the first Cast tap
        // can not land on it (this was why the first Cast sometimes did nothing)
        val hadPanel = panelUi != null
        if (hadPanel) closePanel(true)
        Cv.forget()
        lastSig = null
        reelSig = null
        reelBox = null
        Cfg.put(applicationContext, "detect", 1)
        Cfg.put(applicationContext, "full", 1)
        detectMode = true
        running = true
        castMiss = 0
        showMarks()
        rejoinTries = 0
        rejoining = false
        tapAcc = 0.0
        lastFrameId = -1L
        Dbg.resetCatches()
        idx = 0
        phase = 0
        miss = 0
        catches = 0
        Dbg.catches = 0
        Dbg.sells = 0
        Dbg.fails = 0
        Dbg.unverified = 0
        Dbg.clickFix = 0
        Dbg.freezes = 0
        Dbg.biteMin = 0
        Dbg.biteMax = 0
        Dbg.startMs = SystemClock.uptimeMillis()
        pausedMs = 0L
        pausedAt = 0L
        lastSellAt = SystemClock.uptimeMillis()
        catchesAtSell = 0
        tapFailStreak = 0
        clickRecoveries = 0
        verifyMiss = 0
        verifiedEver = false
        verifyOff = false
        fzPrev = null
        fzSince = SystemClock.uptimeMillis()
        lastFreezeAt = 0L
        freezeStep = 0
        panelOpenTicks = 0
        setAwake(true)
        lastGesture = Dbg.startMs
        lastShotSent = Dbg.startMs
        for (i in taskLast.indices) taskLast[i] = Dbg.startMs
        Dbg.state = "Starting"
        Dbg.add("Started, capture=" + (if (Cap.active) "fast" else "screenshots (slower)") + ", AI=" + (Cfg.get(applicationContext, "ai") == 1))
        Hook.send(applicationContext, "Macro started", "Capture: " + (if (Cap.active) "fast" else "screenshots") + ", AI autopilot: " + (Cfg.get(applicationContext, "ai") == 1), 0x38D39F)
        Ai.load(applicationContext)
        progressT = SystemClock.uptimeMillis()
        loopBeat = progressT
        loopRestarts = 0
        trackGen++
        aiStuck = 0
        gapBonus = 0L
        gainN = 0
        firstCast = false
        curCastFirst = false
        statusLast = 0L
        Biome.resetStats()
        paused = false
        failCasts = 0
        biteN = 0
        label()
        val go = {
            runScript(Cfg.gets(applicationContext, "script_start")) {
                if (detectMode) {
                    dnext()
                } else {
                    tl = build()
                    next()
                }
            }
        }
        if (hadPanel) h.postDelayed({ if (running) go() }, 280L) else go()
    }

    fun stop() {
        val was = running
        val sessSec = if (was) activeSeconds() else 0L
        if (was) Stats.addSeconds(applicationContext, sessSec)
        setAwake(false)
        running = false
        hideMarks()
        paused = false
        loopBeat = 0L
        trackGen++
        pauseBtn?.text = "PAUSE"
        stopEngine()
        h.removeCallbacksAndMessages(null)
        rejoining = false
        hideDebug()
        Dbg.state = "Idle"
        label()
        if (was) {
            Dbg.add("Stopped after $catches catches")
            val tries = Dbg.catches + Dbg.fails + Dbg.unverified
            val rate = if (tries > 0) (Dbg.catches * 100 / tries).toString() + "%" else "-"
            val perH = if (sessSec > 60L) Dbg.catches * 3600L / sessSec else 0L
            Hook.send(applicationContext, "Macro stopped",
                "$catches catches, ${Dbg.sells} sells\nFishing time ${Stats.hms(sessSec)}, $perH catches/hour, success $rate, failed ${Dbg.fails}" +
                (if (stopReason.isNotEmpty()) "\nReason: $stopReason" else ""), 0xFF6B6B)
            stopReason = ""
        }
    }

    private fun next() {
        if (!running) return
        if (!atRoblox()) { lostRoblox(); return }
        if (idx >= tl.size) { onCatch(); if (doneCheck()) return; tl = build(); idx = 0 }
        val s = tl[idx++]
        tap(s.x, s.y, s.hold)
        h.postDelayed({ next() }, s.hold + s.wait)
    }

    private fun tap(x: Int, y: Int, hold: Long, report: Boolean = false, onFail: (() -> Unit)? = null) {
        lastGesture = SystemClock.uptimeMillis()
        marks?.add(x, y)
        try {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, maxOf(hold, 1L)))
                .build()
            val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Dbg.tapOk++
                    tapFailStreak = 0
                    if (report) toast("Android delivered the tap at $x,$y")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Dbg.tapCancel++
                    onFail?.invoke()
                    tapFailStreak++
                    if (tapFailStreak >= 8) clickRecover("Android cancelled the last 8 taps")
                    if (report || Dbg.tapCancel % 20 == 1) Dbg.add("Tap cancelled at $x,$y (${Dbg.tapCancel} so far)")
                    if (report) toast("Android cancelled the tap")
                }
            }, null)
            if (!ok) {
                Dbg.tapRefused++
                onFail?.invoke()
                tapFailStreak++
                if (tapFailStreak >= 8) h.post { clickRecover("Android refused the last 8 taps") }
                if (report || Dbg.tapRefused % 20 == 1) Dbg.add("Android refused the tap at $x,$y")
                if (report) h.post { toast("Android refused the tap. Is the LuminFish service turned on?") }
            }
        } catch (e: Exception) {
            Dbg.add("Tap error: " + e.message)
        }
    }

    // Test tap: shows a ring where LuminFish will tap the reel area and sends one real tap there.
    fun testTap() {
        val c = applicationContext
        val x = Cfg.get(c, "reel_x")
        val y = Cfg.get(c, "reel_y")
        showTapDot(x, y)
        h.postDelayed({ tap(x, y, Cfg.get(c, "taphold").toLong(), true) }, 450L)
    }

    private fun showTapDot(x: Int, y: Int) {
        if (!::wm.isInitialized) return
        val d = resources.displayMetrics.density
        val sz = (40 * d).toInt()
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(0x55FFFFFF)
                setStroke((3 * d).toInt(), 0xFFFF8A00.toInt())
            }
        }
        val lp = WL(sz, sz, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - sz / 2
            this.y = y - sz / 2
        }
        try { wm.addView(v, lp) } catch (e: Exception) { return }
        th.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, 1200L)
    }

    private fun label() {
        startBtn?.text = if (running) "STOP ($catches)" else "START"
        val bgd = iconView?.background as? GradientDrawable
        bgd?.setStroke((2 * resources.displayMetrics.density).toInt(), if (running) 0xFF38D39F.toInt() else 0xFFDCE6FF.toInt())
        floatIcon?.setState(if (running) (if (paused) 2 else 1) else 0)
    }

    private fun biteLimit(): Long {
        val c = applicationContext
        val maxMs = Cfg.get(c, "maxwait") * 1000L
        if (Cfg.get(c, "adapt") == 1 && biteN >= 3) {
            return (biteAvg * 2.5).toLong().coerceIn(8000L, maxOf(maxMs, 8000L))
        }
        return maxMs
    }

    // Fishing time of this session in seconds (time spent paused is not counted).
    fun activeSeconds(): Long {
        if (!running || Dbg.startMs <= 0L) return 0L
        val now = SystemClock.uptimeMillis()
        val pausedNow = if (paused && pausedAt > 0L) now - pausedAt else 0L
        return maxOf(0L, (now - Dbg.startMs - pausedMs - pausedNow) / 1000L)
    }

    // Auto-sell: by catches, by time, or whichever comes first (Settings > Selling)
    private fun sellDue(): Boolean {
        val c = applicationContext
        if (Cfg.get(c, "sell") != 1) return false
        val every = Cfg.get(c, "every")
        val mins = Cfg.get(c, "sellmin")
        val byCount = every > 0 && catches - catchesAtSell >= every
        val byTime = mins > 0 && SystemClock.uptimeMillis() - lastSellAt >= mins * 60000L
        return when (Cfg.get(c, "sellmode")) {
            0 -> byCount
            1 -> byTime
            else -> byCount || byTime
        }
    }

    // Click recovery: clicking stopped working (Android cancels or refuses the taps, or the panel is in the way).
    private fun clickRecover(why: String) {
        if (!running || Cfg.get(applicationContext, "recover") != 1) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRecoverAt < 1500L) return
        lastRecoverAt = now
        clickRecoveries++
        Dbg.clickFix++
        tapFailStreak = 0
        Dbg.add("Click recovery #$clickRecoveries: $why")
        if (panelUi != null) closePanel()
        if (clickersOn) hideClickers()
        gapBonus = minOf(gapBonus + 20L, 80L)
        if (phase == 2 || engineOn) {
            stopEngine()
            h.postDelayed({ if (running && !paused && phase == 2) startEngine() }, 250L)
        }
        if (clickRecoveries % 5 == 0) {
            toast("Clicks keep failing. If this goes on, turn the LuminFish service off and on")
            Hook.send(applicationContext, "Click recovery", "Clicks failed $clickRecoveries times: $why", 0xFFB84D)
        }
    }

    // Anti-freeze: the screen has not changed at all for a long time, or nothing has progressed.
    private fun freezeCheck(now: Long) {
        val c = applicationContext
        val limit = maxOf(Cfg.get(c, "freezesec"), 10) * 1000L
        if (Cap.active && !fzBusy) {
            fzBusy = true
            bgOcr.execute {
                try {
                    val reg = Cap.region(0, 0, 100000, 100000, 8)
                    if (reg != null) {
                        val sg = Pn.sig(reg.px, reg.w, reg.h)
                        h.post {
                            val pv = fzPrev
                            if (pv == null || Pn.motion(pv, sg) > 0.003) fzSince = SystemClock.uptimeMillis()
                            fzPrev = sg
                        }
                    }
                } catch (e: Throwable) {
                    Dbg.add("Freeze check error: " + e.message)
                } finally {
                    fzBusy = false
                }
            }
        }
        if (progressT > lastFreezeAt) freezeStep = 0
        val still = Cap.active && fzSince > 0L && now - fzSince >= limit
        val stalled = now - progressT >= limit * 3
        if (!still && !stalled) return
        if (now - lastFreezeAt < limit) return
        freezeRecover(if (still) "the screen has not changed for ${(now - fzSince) / 1000} s" else "no progress for ${(now - progressT) / 1000} s")
    }

    private fun freezeRecover(why: String) {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        lastFreezeAt = now
        progressT = now
        fzSince = now
        Dbg.freezes++
        val step = freezeStep++
        Dbg.add("Anti-freeze step ${step + 1}: $why")
        Hook.send(c, "Anti-freeze", "The game looks frozen ($why). Recovery step ${step + 1}.", 0xFFB84D)
        stopEngine()
        h.removeCallbacksAndMessages(null)
        trackGen++
        if (step >= 2) {
            freezeStep = 0
            if (Cfg.get(c, "rejoin") == 1) {
                beginRejoin("Game frozen")
            } else {
                stop()
                toast("The game stayed frozen after 2 recovery tries, stopped")
                Hook.send(c, "Macro stopped", "The game stayed frozen after 2 recovery tries.", 0xFF6B6B, true)
            }
            return
        }
        tapPoint("dismiss")
        h.postDelayed({ tapPoint("dismiss") }, 350L)
        camSwipe(if (step == 0) "right" else "up", 60)
        phase = 0
        roi = null
        failCasts = 0
        Cv.forget()
        loopBeat = now
        h.postDelayed({ if (running && !paused) dnext() }, 900L)
    }

    private fun setAwake(on: Boolean) {
        val lp = iconLp ?: return
        val b = panel ?: return
        lp.flags = if (on) lp.flags or WL.FLAG_KEEP_SCREEN_ON else lp.flags and WL.FLAG_KEEP_SCREEN_ON.inv()
        try { wm.updateViewLayout(b, lp) } catch (_: Exception) {}
    }

    private fun onCatch() {
        progressT = SystemClock.uptimeMillis()
        Stats.addCatch(applicationContext)
        catches++
        Dbg.catches = catches
        Dbg.markCatch()
        label()
        val every = Cfg.get(applicationContext, "whevery")
        if (every > 0 && catches % every == 0) {
            Hook.send(applicationContext, "Progress", "$catches catches, ${Dbg.sells} sells", 0x3EA6FF)
        }
    }

    private fun showDbg(r: DoubleArray?) {
        if (!::wm.isInitialized) return
        val c = applicationContext
        var v = dbgView
        if (v == null) {
            v = DebugView(this)
            wm.addView(v, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_NOT_TOUCHABLE or WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT))
            dbgView = v
        }
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
        v.rect.set(x1, y1, x2, y2)
        val need = maxOf(Cfg.get(c, "minpx"), 1)
        if (r == null) {
            v.color = Color.RED
            v.label = "No screenshot"
            v.zmax = -1.0
            v.mpos = -1.0
        } else {
            val zn = r[0].toInt()
            val mn = r[3].toInt()
            val pct = minOf(100, zn * 100 / need)
            v.color = if (zn >= need && mn >= 3) Color.GREEN else if (zn >= need / 2) Color.YELLOW else Color.RED
            v.label = "zone match $pct%   marker ${mn}px   ${Dbg.state}"
            val auto = r.size >= 10 && r[5] >= 0
            v.vert = if (auto) r[5] == 1.0 else Cfg.get(c, "axis") == 1
            if (auto) v.rect.set(r[6].toInt(), r[7].toInt(), r[8].toInt(), r[9].toInt())
            v.zmin = r[1]
            v.zmax = r[2]
            v.mpos = if (mn > 0) r[4] / mn else -1.0
        }
        v.invalidate()
    }

    private fun hideDebug() {
        if (!::wm.isInitialized) return
        dbgView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dbgView = null
    }

    // ---------- screen detection loop ----------
    private fun dnext() {
        if (!running) return
        loopBeat = SystemClock.uptimeMillis()
        if (!atRoblox()) { lostRoblox(); return }
        val c = applicationContext
        Dbg.state = when (phase) { 0 -> "Casting"; 1 -> "Waiting for the bar"; 2 -> "Reeling"; 3 -> "Selling"; 7 -> "Checking result"; 8 -> "Running task"; else -> "Walking" }
        when (phase) {
            0 -> {
                val due = dueTask()
                if (due >= 0) {
                    curTask = due
                    phase = 8
                    dnext()
                    return
                }
                curCastFirst = firstCast
                firstCast = false
                Cv.forget()
                if (sceneOn()) {
                    castMiss = 0
                    castBySight()
                    return
                }
                tapRetry(Cfg.get(c, "cast_x"), Cfg.get(c, "cast_y"), Cfg.get(c, "cast").toLong(), 3, "cast")
                phase = 1
                t0 = SystemClock.uptimeMillis()
                h.postDelayed({ dnext() }, Cfg.get(c, "cast") + Cfg.get(c, "castdelay").toLong())
            }
            1 -> track { r ->
                if (!running) return@track
                val n = r?.get(0)?.toInt() ?: 0
                if (n >= Cfg.get(c, "minpx")) {
                    phase = 2
                    failCasts = 0
                    val bt = SystemClock.uptimeMillis() - t0
                    biteAvg = if (biteN == 0) bt.toDouble() else 0.7 * biteAvg + 0.3 * bt
                    biteN++
                    Dbg.biteMs = biteAvg.toInt()
                    cycBite = bt
                    if (Dbg.biteMin == 0 || bt < Dbg.biteMin) Dbg.biteMin = bt.toInt()
                    if (bt > Dbg.biteMax) Dbg.biteMax = bt.toInt()
                    wZone = screenW * 0.04
                    lastTap = 0L
                    nextTapAt = 0L
                    tapCount = 0
                    framesN = 0
                    framesIn = 0
                    histN = 0
                    lastMark = -1.0
                    lastHeld = false
                    votes = 0
                    roi = null
                    roiN = 0
                    roiMiss = 0
                    vel = 0.0
                    lastM = -1.0
                    lastMT = 0L
                    lastVelNs = 0L
                    lastCtl = 0L
                    tapAcc = 0.0
                    t0 = SystemClock.uptimeMillis()
                    lastSeen = t0
                    Dbg.add("Bar found (zone $n px)")
                    progressT = SystemClock.uptimeMillis()
                    prevCapT = 0L
                } else if (SystemClock.uptimeMillis() - t0 > biteLimit()) {
                    failCasts++
                    if (curCastFirst) Ai.cycleFault()
                    Dbg.add("Bar not found, cast again ($failCasts/3)")
                    if (failCasts >= 3) {
                        stop()
                        toast("Bar not found 3 times, stopped. Check COLOR and TEST.")
                        Hook.send(applicationContext, "Macro stopped", "The reeling bar was not found 3 times in a row.", 0xFF6B6B)
                        return@track
                    }
                    phase = 0
                }
                // smart timing: before the earliest bite ever seen there is nothing to catch, so scan less often (saves battery)
                val early = phase == 1 && Cfg.get(c, "smart") == 1 && biteN >= 3 && Dbg.biteMin > 0 &&
                    SystemClock.uptimeMillis() - t0 < Dbg.biteMin * 6L / 10L
                h.postDelayed({ dnext() }, if (early) 50L else 4L)
            }
            2 -> track { r ->
                if (!running) return@track
                if (Cfg.get(c, "full") == 1) { reelFull(r); return@track }
                var held = false
                if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
                    lastSeen = SystemClock.uptimeMillis()
                    if (r[3].toInt() >= 3) {
                        val zc = (r[1] + r[2]) / 2.0
                        val mc = r[4] / r[3]
                        var dir = Cfg.get(c, "holddir")
                        if (lastHeld && lastMark >= 0) {
                            val d = mc - lastMark
                            if (Math.abs(d) > 6) {
                                if ((d > 0) == (dir == 1)) votes++ else votes--
                                if (votes <= -3) {
                                    dir = 1 - dir
                                    Cfg.put(c, "holddir", dir)
                                    votes = 0
                                    toast("Hold direction flipped")
                                }
                            }
                        }
                        val db = Cfg.get(c, "deadband")
                        held = if (dir == 0) mc > zc + db else mc < zc - db
                        if (held && SystemClock.uptimeMillis() >= holdUntil - 40L) {
                            val hm = Cfg.get(c, "holdms").toLong()
                            tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hm)
                            holdUntil = SystemClock.uptimeMillis() + hm
                        }
                        lastMark = mc
                    }
                }
                lastHeld = held
                dbgCount++
                if (dbgCount % 15 == 0) Dbg.add("reel zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()} held=$held")
                if (Cfg.get(c, "debug") == 1) showDbg(r)
                if (SystemClock.uptimeMillis() - lastSeen > Cfg.get(c, "gonems").toLong() || SystemClock.uptimeMillis() - t0 > 60000L) {
                    onCatch()
                    if (doneCheck()) return@track
                    phase = if (sellDue()) 4 else 0
                    sellIdx = 0
                    h.postDelayed({ dnext() }, Cfg.get(c, "postcatch").toLong())
                } else {
                    h.postDelayed({ dnext() }, 20L)
                }
            }
            7 -> track { r ->
                if (!running) return@track
                val nowT = SystemClock.uptimeMillis()
                // the bar came back: the reel was called finished too early, so keep reeling
                val back = r != null && r[0].toInt() >= Cfg.get(c, "minpx") && r[3].toInt() >= 3
                if (back && p7State == 0) {
                    p7Back++
                    if (p7Back >= 3 && p7Resumes < 2) {
                        p7Resumes++
                        p7Back = 0
                        Dbg.add("The bar came back, reeling again")
                        Ai.cycleFault()
                        phase = 2
                        lastSeen = nowT
                        startEngine()
                        h.postDelayed({ dnext() }, 1L)
                        return@track
                    }
                } else {
                    p7Back = 0
                }
                val failed = r != null && r.size >= 11 && r[10] >= 150
                // what the screen looks like compared with the picture taken while the bar was showing
                val sg = lastSig
                val base = reelSig
                val dFrac = if (sg != null && base != null) Pn.diff(base, sg, reelBox) else -1.0
                val prev = p7Prev
                val dPrev = if (sg != null && prev != null) Pn.diff(prev, sg, null) else 1.0
                p7Prev = sg
                if (p7State == 0) {
                    // step 1: wait until the caught / failed panel has slid in and stopped moving (seen on the screen)
                    val shown = failed || dFrac >= 0.07
                    if (shown && dPrev < 0.03) p7Settle++ else if (!shown) p7Settle = 0
                    val late = nowT - p7t >= cyc(Cfg.get(c, "postcatch")) + cyc(Cfg.get(c, "resultms")) + 1200L
                    if (p7Settle < 2 && !late && !failed) {
                        h.postDelayed({ dnext() }, 15L)
                        return@track
                    }
                    p7Seen = p7Settle >= 2 || failed
                    Dbg.add(if (failed) "Result: failed panel seen, closing it" else if (p7Seen) "Result: panel seen, closing it" else "Result: no panel seen, closing anyway")
                    dismissTap()
                    p7Taps = 1
                    p7DismissAt = nowT
                    p7Gone = 0
                    Ai.report(c, Dbg.inZone, !failed)
                    Ai.cycleReelEnd(c)
                    firstCast = true
                    progressT = nowT
                    // catch verification: a catch only counts when the result panel was really seen on the screen
                    val verifyOn = Cfg.get(c, "verify") == 1 && !verifyOff
                    var counted = false
                    if (failed) {
                        Dbg.fails++
                        Stats.addFail(c)
                        verifyMiss = 0
                        failStreak++
                        if (failStreak == 5) {
                            Dbg.add("5 failed reels in a row")
                            Hook.send(applicationContext, "Many failed reels", "5 reels failed in a row. Check the bar colors, the tap spot (REEL circle) and the Debug log.", 0xFFB020)
                        }
                    } else if (!verifyOn || p7Seen) {
                        if (p7Seen) {
                            verifiedEver = true
                            verifyMiss = 0
                        }
                        counted = true
                    } else {
                        verifyMiss++
                        if (!verifiedEver && verifyMiss >= 4) {
                            verifyOff = true
                            Dbg.add("Catch verification: the result panel was never seen on this phone, counting finished reels as catches instead")
                            counted = true
                        } else {
                            Dbg.unverified++
                            Stats.addUnverified(c)
                            Dbg.add("Catch not verified (no result panel seen), not counted")
                        }
                    }
                    p7Sell = false
                    if (counted) {
                        failStreak = 0
                        onCatch()
                        if (doneCheck()) return@track
                        p7Sell = sellDue()
                    }
                    p7State = 1
                    h.postDelayed({ dnext() }, 120L)
                    return@track
                }
                // step 2: wait until the panel is really gone (seen on the screen), tap the X again if it is still there
                val since = nowT - p7DismissAt
                if (p7Seen && dFrac >= 0.0) {
                    if (dFrac <= 0.035) p7Gone++ else p7Gone = 0
                    if (p7Gone < 2 && since < 2200L) {
                        if (since > 700L * p7Taps && p7Taps < 3) {
                            dismissTap()
                            p7Taps++
                        }
                        h.postDelayed({ dnext() }, 20L)
                        return@track
                    }
                } else if (since < maxOf(cyc(Cfg.get(c, "dismisswait")), 250L)) {
                    h.postDelayed({ dnext() }, 20L)
                    return@track
                }
                Dbg.add("Cycle: bite ${cycBite} ms, reel ${cycReel} ms, result ${nowT - p7t} ms, in the zone ${Dbg.inZone}%")
                phase = if (p7Sell) 4 else 0
                sellIdx = 0
                h.postDelayed({ dnext() }, 60L)
            }
            4 -> {
                phase = 6
                lastSellAt = SystemClock.uptimeMillis()
                catchesAtSell = catches
                runScript(Cfg.gets(c, "script_pre")) { phase = 3; sellIdx = 0; dnext() }
            }
            5 -> {
                phase = 6
                runScript(Cfg.gets(c, "script_post")) { phase = 0; dnext() }
            }
            8 -> {
                phase = 6
                val ti = curTask
                Dbg.add("Running timed task " + (ti + 1))
                runScript(Cfg.gets(c, "t" + (ti + 1) + "s")) {
                    taskLast[ti] = SystemClock.uptimeMillis()
                    phase = 0
                    dnext()
                }
            }
            6 -> { }
            else -> {
                val k = listOf("inv", "sellall", "confirm")[sellIdx]
                sellIdx++
                tap(Cfg.get(c, k + "_x"), Cfg.get(c, k + "_y"), 50L)
                if (sellIdx >= (if (Cfg.get(c, "sellconf") == 1) 3 else 2)) {
                    phase = 5
                    Dbg.sells++
                    Stats.addSell(applicationContext)
                    Dbg.add("Sold (#${Dbg.sells})")
                    Hook.send(applicationContext, "Sold items", "Sell #${Dbg.sells} after $catches catches", 0x38D39F)
                }
                h.postDelayed({ dnext() }, Cfg.get(c, "mdelay").toLong())
            }
        }
    }

    private fun shot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        val wait = maxOf(0L, 380L - (SystemClock.uptimeMillis() - lastShot))
        h.postDelayed({
            lastShot = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    val hb = r.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    cb(bmp)
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        }, wait)
    }

    // Waits for a NEW screen frame while the bar is being searched or reeled, so the controller never
    // works on the same picture twice. A still screen sends no new frames, so after 60 ms it scans anyway.
    private fun track(cb: (DoubleArray?) -> Unit) {
        if (running && (phase == 1 || phase == 2 || phase == 7) && Cap.active) {
            val id = Cap.poll()
            val now = SystemClock.uptimeMillis()
            if (id == lastFrameId && now - lastFrameAt < 60L) {
                h.postDelayed({ track(cb) }, 1L)
                return
            }
            newFrame = id != lastFrameId
            if (newFrame) curFrameNs = Cap.frameNs
            lastFrameId = id
            lastFrameAt = now
        } else {
            newFrame = true
        }
        trackNow(cb)
    }

    private fun trackNow(cb: (DoubleArray?) -> Unit) {
        val t = SystemClock.uptimeMillis()
        scanT = t
        val c0 = applicationContext
        val gen = trackGen
        val after: (DoubleArray?) -> Unit = { r: DoubleArray? ->
          if (gen == trackGen) {
            val now = SystemClock.uptimeMillis()
            loopBeat = now
            Dbg.latency = (now - t).toInt()
            Dbg.zonePx = if (r == null) 0 else r[0].toInt()
            Dbg.markerPx = if (r == null) 0 else r[3].toInt()
            Dbg.barFound = r != null && r[0].toInt() >= Cfg.get(c0, "minpx")
            fpsN++
            if (now - fpsT >= 1000L) { Dbg.fps = fpsN; fpsN = 0; fpsT = now }
            // an error inside the loop must never end the loop: log it and carry on
            try {
                cb(r)
            } catch (e: Throwable) {
                Dbg.add("Reel loop error: " + e.message)
                if (running) h.postDelayed({ dnext() }, 50L)
            }
          }
        }
        val c = applicationContext
        val full = Cfg.get(c, "full") == 1
        Cv.anyColor = Cfg.get(c, "anyzone") == 1
        if (Cap.active) {
            val dm = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Cap.ensure(dm.widthPixels, dm.heightPixels, dm.densityDpi)
            screenW = dm.widthPixels
            val adaptOn = Cfg.get(c, "adaptfps") == 1
            if (full) {
                // once the bar is found, only look at a small box around it (faster and finer)
                val rb = roi
                if (phase == 2 && rb != null && roiN < 150) {
                    roiN++
                    val rs = Adapt.roi(dm.widthPixels, adaptOn)
                    bg.execute {
                        try {
                            val s0 = SystemClock.uptimeMillis()
                            // v2.1: the box goes straight from the capture buffer into OpenCV (HSV colour match, whole pixels, no
                            // per-pixel loop). The built-in scan below is only the fallback.
                            var r: DoubleArray? = null
                            if (Cv.ready && Cfg.get(c, "cv") == 1) {
                                val rgba = Cap.regionRgba(rb[0], rb[1], rb[2] - rb[0], rb[3] - rb[1])
                                if (rgba != null) {
                                    r = Cv.scanRoi(rgba.a, rgba.x, rgba.y, rgba.w, rgba.h, Cfg.get(c, "color"), Cfg.get(c, "color2"), tolNow(), dm.widthPixels)
                                    if (r != null) Dbg.detector = "OpenCV box"
                                }
                            }
                            if (r == null) {
                                val reg = Cap.region(rb[0], rb[1], rb[2] - rb[0], rb[3] - rb[1], rs)
                                r = if (reg == null) null else roiScan(reg)
                            }
                            val rr: DoubleArray? = r
                            val seen = rr != null && rr[0] >= Cfg.get(c, "minpx")
                            if (adaptOn) Adapt.report(SystemClock.uptimeMillis() - s0, seen, true, dm.widthPixels)
                            h.post {
                                if (!seen) {
                                    roiMiss++
                                    // first misses: look in a slightly bigger box before giving up on the small one
                                    if (roiMiss >= 3) roi = null else roi = growRoi(rb, dm.widthPixels, dm.heightPixels)
                                } else {
                                    roiMiss = 0
                                }
                                after(rr)
                            }
                        } catch (e: Throwable) {
                            Dbg.add("Scan error: " + e.message)
                            h.post { after(null) }
                        }
                    }
                    return
                }
                val st = Adapt.full(dm.widthPixels, adaptOn)
                bg.execute {
                    try {
                        val s0 = SystemClock.uptimeMillis()
                        val reg = Cap.region(0, 0, 100000, 100000, st)
                        val res: DoubleArray? = if (reg == null) null else {
                            val sgn = Pn.sig(reg.px, reg.w, reg.h)
                            lastSig = sgn
                            val cvr = if (Cv.ready && Cfg.get(c, "cv") == 1) {
                                Cv.scanFull(reg.px, reg.w, reg.h, reg.step, Cfg.get(c, "color"), Cfg.get(c, "color2"), tolNow())
                            } else null
                            Dbg.detector = if (cvr != null) (if (Cv.lastUsedMemory) "OpenCV (bar memory)" else "OpenCV") else "Built-in"
                            val fin = cvr ?: autoScan(reg.px, reg.w, reg.h, reg.step)
                            if (fin[0] >= Cfg.get(c, "minpx") && fin[9] > fin[7] && (phase == 1 || phase == 2)) {
                                reelSig = sgn
                                reelBox = floatArrayOf((fin[6] / dm.widthPixels - 0.01).toFloat(), (fin[7] / dm.heightPixels - 0.03).toFloat(),
                                    (fin[8] / dm.widthPixels + 0.01).toFloat(), (fin[9] / dm.heightPixels + 0.03).toFloat())
                            }
                            fin
                        }
                        if (adaptOn) Adapt.report(SystemClock.uptimeMillis() - s0, res != null && res[0] >= Cfg.get(c, "minpx"), false, dm.widthPixels)
                        h.post {
                            if (phase == 2 && res != null && res[0] >= Cfg.get(c, "minpx") && res[9] > res[7]) {
                                roi = intArrayOf(res[6].toInt(), res[7].toInt() - 6, res[8].toInt(), res[9].toInt() + 6)
                                roiN = 0
                                roiMiss = 0
                            }
                            after(res)
                        }
                    } catch (e: Throwable) {
                        Dbg.add("Scan error: " + e.message)
                        h.post { after(null) }
                    }
                }
                return
            }
            val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x"))
            val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y"))
            bg.execute {
                try {
                    val reg = Cap.region(x1, y1, x2 - x1, y2 - y1)
                    val r = if (reg == null) null else scanPx(reg.px, reg.x, reg.y, reg.w, reg.h)
                    h.post { after(r) }
                } catch (e: Throwable) {
                    Dbg.add("Scan error: " + e.message)
                    h.post { after(null) }
                }
            }
            return
        }
        // v1.9: the bar is read only from the live screen capture. Screenshots are too slow, so they are not used here.
        captureLost()
        if (running) h.post { after(null) }
    }

    // a slightly bigger box around the old one (used when the bar was missed once or twice)
    private fun growRoi(b: IntArray, sw: Int, sh: Int): IntArray {
        val dx = maxOf(8, (b[2] - b[0]) / 8)
        val dy = maxOf(10, (b[3] - b[1]) / 2)
        return intArrayOf(maxOf(0, b[0] - dx), maxOf(0, b[1] - dy), minOf(sw, b[2] + dx), minOf(sh, b[3] + dy))
    }

    // Fast scan of the small box around the bar found by the full scan (same output layout as autoScan).
    private var capLostAt = 0L
    private fun captureLost() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (now - capLostAt < 4000L) return
        capLostAt = now
        Dbg.add("Screen capture is off, stopping. Turn it on again to keep fishing")
        Hook.send(applicationContext, "Screen capture stopped", "The macro stopped because live screen capture is off.", 0xFF6B6B, true)
        stop()
        toast("Screen capture stopped. Turn it on again, then press Start")
        openApp(true)
    }

    private fun roiScan(g: Reg): DoubleArray {
        val c = applicationContext
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0,
            g.x.toDouble(), g.y.toDouble(), (g.x + g.w * g.step).toDouble(), (g.y + g.h * g.step).toDouble(), 0.0)
        val w = g.w
        val hh = g.h
        if (w < 4 || hh < 3) return out
        val ya = hh / 4
        val yb = maxOf(ya, hh - 1 - hh / 4)
        val rows = yb - ya + 1
        var cells = 0
        var zl = -1
        var zr = -1
        var zrun = 0
        var zStart = -1
        var tip = -1
        var bn = 0
        var brun = 0
        var bStart = -1
        for (xx in 0..w) {
            var zn = 0
            var bc = 0
            if (xx < w) {
                for (yy in ya..yb) {
                    val p = g.px[yy * w + xx]
                    if (near(p, zc, tol)) zn++
                    if (near(p, mk, tol)) bc++
                }
            }
            if (xx < w && zn * 2 >= rows) {
                if (zrun == 0) zStart = xx
                zrun++
                cells += zn
            } else {
                if (zrun >= 2) {
                    if (zl < 0) zl = zStart
                    zr = xx
                }
                zrun = 0
            }
            if (xx < w && bc * 2 >= rows) {
                if (brun == 0) bStart = xx
                brun++
            } else {
                if (brun >= 3 && bStart * g.step <= 0.03 * screenW) { tip = xx; bn = brun }
                brun = 0
            }
        }
        if (zl < 0) return out
        out[0] = cells.toDouble() * g.step * g.step
        out[1] = (g.x + zl * g.step).toDouble()
        out[2] = (g.x + zr * g.step).toDouble()
        out[3] = bn.toDouble()
        out[4] = if (tip >= 0) (g.x + tip * g.step).toDouble() else -1.0
        return out
    }

    private class Cand(var x0: Int, var x1: Int, var y0: Int, var y1: Int, var area: Int)

    // Whole-screen search for the reeling bar of Sol's RNG: a dark green zone (long and thin),
    // and the blue fill whose right end is the marker.
    // Returns [zonePx, zoneLeft, zoneRight, blueCols, tipX(-1 if none), 1, boxX1, boxY1, boxX2, boxY2, redSamples]
    private fun autoScan(px: IntArray, w: Int, h: Int, step: Int): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val red = 0xFFFF4041L.toInt()
        var redN = 0
        val cands = ArrayList<Cand>()
        var prev = ArrayList<Cand>()
        for (yy in 0 until h) {
            val cur = ArrayList<Cand>()
            val base = yy * w
            var xx = 0
            while (xx < w) {
                val p = px[base + xx]
                if (near(p, zc, tol)) {
                    var e = xx
                    while (e + 1 < w && near(px[base + e + 1], zc, tol)) e++
                    if (e - xx + 1 >= 3) {
                        var m: Cand? = null
                        for (q in prev) if (xx <= q.x1 && e >= q.x0) { m = q; break }
                        if (m != null) {
                            m.x0 = minOf(m.x0, xx)
                            m.x1 = maxOf(m.x1, e)
                            m.y1 = yy
                            m.area += e - xx + 1
                            if (!cur.contains(m)) cur.add(m)
                        } else {
                            val n = Cand(xx, e, yy, yy, e - xx + 1)
                            cands.add(n)
                            cur.add(n)
                        }
                    }
                    xx = e + 1
                } else {
                    if (near(p, red, 40)) redN++
                    xx++
                }
            }
            prev = cur
        }
        out[10] = redN.toDouble()
        val barL = w / 2.0 - 0.386 * h
        val barR = w / 2.0 + 0.386 * h
        var best: Cand? = null
        for (k in cands) {
            val cw = k.x1 - k.x0 + 1
            val ch = k.y1 - k.y0 + 1
            if (ch < 3 || cw < 2 * ch) continue
            if (ch < 0.025 * h || ch > 0.06 * h) continue
            if (cw < 0.02 * w || cw > w / 2) continue
            if (k.area < cw * ch / 2) continue
            val cx = (k.x0 + k.x1) / 2.0
            val cy = (k.y0 + k.y1) / 2.0
            if (cx < barL || cx > barR || cy < 0.4 * h || cy > 0.8 * h) continue
            if (best == null || k.area > best.area) best = k
        }
        val b = best ?: return out
        val wz = b.x1 - b.x0 + 1
        out[0] = b.area.toDouble() * step * step / 2.0
        out[1] = (b.x0 * step).toDouble()
        out[2] = ((b.x1 + 1) * step).toDouble()
        val ya = b.y0 + (b.y1 - b.y0) / 4
        val yb = b.y1 - (b.y1 - b.y0) / 4
        val xa = maxOf(0, barL.toInt() - 1)
        val xb = minOf(w - 1, b.x1 + wz)
        var tip = -1
        var bn = 0
        var run = 0
        var runStart = -1
        for (xx in xa..xb + 1) {
            var ok = false
            if (xx <= xb) {
                var cnt = 0
                for (yy in ya..yb) if (near(px[yy * w + xx], mk, tol)) cnt++
                ok = cnt * 2 >= (yb - ya + 1)
            }
            if (ok) {
                if (run == 0) runStart = xx
                run++
            } else {
                if (run >= 3 && (runStart - xa) <= 0.03 * w) { tip = xx; bn = run }
                run = 0
            }
        }
        out[3] = bn.toDouble()
        out[4] = if (tip >= 0) (tip * step).toDouble() else -1.0
        out[6] = (xa * step).toDouble()
        out[7] = ((b.y0 - 2) * step).toDouble()
        out[8] = ((xb + 1) * step).toDouble()
        out[9] = ((b.y1 + 3) * step).toDouble()
        return out
    }

    // ---------- tap engine ----------
    private fun startEngine() {
        tapTimes.clear()
        engAcc = 0.0
        realTip = 0.0
        realTipT = 0L
        // v2.1 tap ledger starts from what was learned before (lift, drain and the measured tap-to-game delay)
        run {
            val c = applicationContext
            val aiOn = Cfg.get(c, "ai") == 1
            val sw = if (screenW > 0) screenW else 2340
            val lat = Cfg.get(c, "tap_lat")
            tapper.reset(
                (if (aiOn) Ai.gainFrac else 0.0248) * sw, (if (aiOn) Ai.drainFrac else 0.0919) * sw,
                if (lat in 10..140) lat.toDouble() else Ai.delayMs().toDouble(),
                Cfg.get(c, "taphold").toLong(), Cfg.get(c, "tapms").toLong()
            )
        }
        run {
            val c = applicationContext
            val sw = if (screenW > 0) screenW else 2340
            val lat = Cfg.get(c, "tap_lat")
            edgeT.reset(0.0248 * sw, 0.0919 * sw, if (lat in 10..140) lat.toDouble() else 50.0, Cfg.get(c, "taphold").toLong(), Cfg.get(c, "tapms").toLong())
        }
        engStartAt = SystemClock.uptimeMillis()
        engLast = SystemClock.uptimeMillis()
        engLastTap = 0L
        obs = null
        prevCapT = 0L
        zhN = 0
        zvEst = 0.0
        qT0 = 0L
        engineOn = true
        eh.removeCallbacks(engine)
        eh.post(engine)
    }

    private fun stopEngine() {
        engineOn = false
        eh.removeCallbacks(engine)
    }

    // the waits after a reel (result panel, close tap, bar gone) shrink a little when the AI has found they can
    private fun cyc(ms: Int): Long =
        if (Cfg.get(applicationContext, "ai") == 1) (ms * Ai.cycle).toLong() else ms.toLong()

    // When was the picture taken (uptime ms)? Android stamps each fast-capture frame; screenshots are taken just after asking.
    private fun capTime(now: Long): Long {
        if (Cap.active) {
            val ms = curFrameNs / 1000000L
            if (curFrameNs > 0L && ms <= now && ms >= now - 400L) return ms
            return if (Cap.arriveMs in (now - 400L)..now) Cap.arriveMs else scanT - 25L
        }
        return lastShot + 30L
    }

    // Runs every 4 ms on its own thread. It PREDICTS where the marker is right now:
    //   last seen position - drain since the picture + lift of the taps sent since then,
    // so a slow or late picture does not make the timing late. Fast capture: tap rate follows the distance to the
    // aim point. Screenshots only: one tap whenever the predicted marker drops below the aim (bang-bang).
    private fun engineStep() {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val dt = (now - engLast).coerceIn(0L, 20L) / 1000.0
        engLast = now
        val o = obs ?: return
        val fastCap = Cap.active
        val stale = now - o.at > (if (fastCap) 300L else 1000L)
        // no taps unless the bar was seen a moment ago (v1.8: no more blind clicking after the bar is gone)
        if (stale) return
        val gain = engGain
        val drain = engDrain
        if (gain <= 0.0) return
        val aiOn = Cfg.get(c, "ai") == 1
        Ai.enabled = aiOn
        val delay = Ai.delayMs()
        var n = 0
        for (t in tapTimes) if (t > o.capT - delay) n++
        while (true) {
            val first = tapTimes.peek() ?: break
            if (first < now - 3000L) tapTimes.poll() else break
        }
        val pred = if (o.haveTip) o.tip - drain * (now - o.capT) / 1000.0 + gain * n else o.zc - 4 * gain
        // a moving zone is followed a little ahead of where it was seen
        val zcNow = o.zc + (o.zv * (now - o.capT) / 1000.0).coerceIn(-o.wZone, o.wZone)
        val target = zcNow + (if (aiOn) Ai.bias else 0.0) * o.wZone
        val kp = if (aiOn) Ai.kp else 8.0
        val hold = Cfg.get(c, "taphold").toLong()
        val minGap = maxOf(Cfg.get(c, "tapms").toLong(), hold + 20L) + gapBonus
        var fire = false
        val timed = Cfg.get(c, "tapmode") == 1
        val ledger = fastCap && Cfg.get(c, "tapeng") == 1 && tapper.usable(now)
        // v2.3: one tap at the left edge of the zone, like a script (default). The ledger below keeps measuring.
        val edgeOn = fastCap && Cfg.get(c, "edgetap") == 1 && edgeT.usable(now)
        var edgeKind = 0
        if (edgeOn) {
            edgeKind = edgeT.decide(now)
            fire = edgeKind != 0
            Dbg.tapInfo = edgeT.info()
        } else if (ledger) {
            // v2.1 PRECISION: every tap is tracked until its lift is seen in a picture (see Tapper.kt)
            fire = tapper.wantTap(now, minGap)
            Dbg.tapInfo = tapper.info()
        } else if (timed) {
            // TIMED TAPPING: a tap goes out only when the marker is about to fall below the lower trigger line.
            // One tap lifts it by 'gain', then it drains, so in steady state taps are one 'gain / drain' apart.
            // No proportional gain, no accumulator: it can not run away into spam.
            val w = maxOf(o.wZone, 1.0)
            val tMs = (gain / maxOf(drain, 1.0) * 1000.0).coerceIn(80.0, 1500.0)
            val margin = when (Cfg.get(c, "tapprec")) { 2 -> 0.0; 1 -> 0.05; else -> 0.12 } * w
            val lead = delay + hold / 2.0
            val zl = o.zc - w / 2.0
            val trig = target - 0.5 * gain - margin
            var base = 0.0
            var baseT = 0L
            var known = true
            if (o.haveTip) { base = o.tip; baseT = o.capT }
            else if (o.lastTipT > 0L && o.capT - o.lastTipT < 700L) { base = o.lastTip; baseT = o.lastTipT }
            else known = false
            var recent = 0
            for (t in tapTimes) if (t > now - 1000L) recent++
            if (known) {
                var nn = 0
                for (t in tapTimes) if (t > baseT - delay) nn++
                val p0 = base - drain * (now - baseT) / 1000.0 + gain * nn
                val at = p0 - drain * lead / 1000.0
                val emergency = at < zl - 0.25 * w
                val spacing = if (emergency) minGap else maxOf(minGap.toDouble(), 0.5 * tMs).toLong()
                val cap = (2000.0 / tMs).toInt() + 2
                fire = at < trig && now - engLastTap >= spacing && (emergency || recent < cap)
                Dbg.tapInfo = "timed, every ~${tMs.toInt()} ms, ${recent}/s"
            } else {
                // blue fill not visible: climb at twice the steady rate for the first 1.5 s, then just keep time
                val climbing = realTipT == 0L && now - engStartAt < 1500L
                val gapMs = if (climbing) 0.5 * tMs else tMs
                fire = now - engLastTap >= maxOf(minGap.toDouble(), gapMs).toLong()
                Dbg.tapInfo = "timed, marker hidden, " + (if (climbing) "climbing" else "keeping time")
            }
        } else if (fastCap) {
            val en = if (stale) 0.0 else (target - pred) / maxOf(o.wZone, 1.0)
            var rate = (drain / gain) * (1.0 + kp * en)
            if (rate < 0.0) rate = 0.0
            val maxRate = 1000.0 / minGap
            if (rate > maxRate) rate = maxRate
            engAcc += rate * dt
            if (rate < 0.5 && engAcc > 0.6) engAcc = 0.6
            if (engAcc > 2.0) engAcc = 2.0
            fire = engAcc >= 1.0
        } else {
            fire = pred < target - gain * 0.5
        }
        // v1.9 click recovery: the marker is low but no tap went out for a while, so tap again
        var force = false
        if (Cfg.get(c, "recover") == 1 && !stale && engLastTap != 0L && now - engLastTap > 1200L && pred < target && now - lastForce > 4000L) {
            force = true
            lastForce = now
            Dbg.clickFix++
            Dbg.add("Click recovery: no tap for 1.2 s while the marker is low, tapping again")
        }
        if (!(fire || force) || now - engLastTap < minGap) return
        if (fastCap) engAcc = maxOf(0.0, engAcc - 1.0)
        engLastTap = now
        tapTimes.add(now)
        tapCount++
        if (ledger || edgeOn) {
            if (ledger) tapper.commit(now)
            if (edgeOn) edgeT.commit(now, edgeKind == 2)
            // Android cancelled or refused this tap: it will not lift the marker, so it leaves the ledger
            tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hold, false) { tapper.cancelled(now); edgeT.cancelled(now) }
        } else {
            tap(Cfg.get(c, "reel_x"), Cfg.get(c, "reel_y"), hold)
        }
        // tap health: if Android keeps cancelling taps, leave more room between them
        chkTaps++
        if (chkTaps >= 20) {
            val ca = Dbg.tapCancel - chkCancel
            val ok = Dbg.tapOk - chkOk
            chkCancel = Dbg.tapCancel
            chkOk = Dbg.tapOk
            chkTaps = 0
            if (ca + ok >= 10) {
                if (ca * 4 > ca + ok && gapBonus < 60L) {
                    gapBonus += 10L
                    Dbg.add("Taps are being cancelled ($ca of ${ca + ok}), adding room between taps: +$gapBonus ms")
                } else if (ca * 20 < ca + ok && gapBonus > 0L) {
                    gapBonus -= 5L
                }
            }
        }
    }

    // The reel step only READS the screen and updates the estimate. The taps themselves come from the engine above.
    private fun reelFull(r: DoubleArray?) {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val aiOn = Cfg.get(c, "ai") == 1
        if (!engineOn) startEngine()
        if (r != null && r[0].toInt() >= Cfg.get(c, "minpx")) {
            lastSeen = now
            wZone = maxOf(wZone, r[2] - r[1])
            val zr = r[2]
            val zc = zr - wZone / 2.0
            // measured on a 2340 px wide screen: one tap lifts the fill about 58 px, it drains about 215 px/s
            val gainBase = 0.0248 * screenW
            val baseDrain = 0.0919 * screenW
            val learn = Cfg.get(c, "learn") == 1
            // start from what the AI measured in earlier sessions, then follow what is measured live
            val drain0 = if (aiOn) Ai.drainFrac * screenW else baseDrain
            val gain0 = if (aiOn) Ai.gainFrac * screenW else gainBase
            val drain = if (learn && drainN >= 4) drainEst.coerceIn(baseDrain * 0.6, baseDrain * 1.6) else drain0
            val gain = if (learn && gainN >= 8) gainEst.coerceIn(gainBase * 0.6, gainBase * 1.5) else gain0
            val haveTip = r[4] >= 0
            var tip = r[4]
            if (!haveTip) tip = zc - 4 * gain
            if (newFrame || histN == 0) {
                tipHist[histN % 3] = tip
                histN++
            }
            val m = if (histN >= 3) tipHist.copyOf().also { it.sort() }[1] else tip
            val capT = capTime(now)
            val pub = if (histN >= 3 && Math.abs(tip - m) > 0.5 * wZone) m else tip
            val delay = Ai.delayMs()
            if (newFrame) {
                // zone speed over a 100-260 ms stretch (zero when the zone is still)
                zhT[zhN % 16] = capT
                zhZ[zhN % 16] = zc
                zhN++
                var bk = -1
                for (k in 0 until minOf(zhN, 16)) {
                    val dtk = capT - zhT[k]
                    if (dtk in 100L..260L && (bk < 0 || zhT[k] < zhT[bk])) bk = k
                }
                if (bk >= 0) {
                    val v = (zc - zhZ[bk]) / ((capT - zhT[bk]) / 1000.0)
                    zvEst = if (Math.abs(v) < 25.0) 0.0 else 0.6 * zvEst + 0.4 * v
                }
            }
            if (haveTip && newFrame) {
                // gain: a picture with exactly one tap in between shows how far that tap lifted the fill
                if (prevCapT > 0L && capT > prevCapT && capT - prevCapT <= 120L) {
                    var k = 0
                    for (t in tapTimes) if (t > prevCapT - delay && t <= capT - delay) k++
                    if (k == 1) {
                        val lift = (pub - prevTip) + drain * (capT - prevCapT) / 1000.0
                        if (lift > gainBase * 0.4 && lift < gainBase * 1.8) {
                            gainEst = if (gainN == 0) lift else 0.92 * gainEst + 0.08 * lift
                            gainN++
                        }
                    }
                }
                // drain: a stretch of 120 ms or more with no tap landing shows how fast the fill falls by itself
                val lastEff = engLastTap + delay
                if (engLastTap == 0L || capT > lastEff + 10L) {
                    if (qT0 == 0L || (engLastTap != 0L && lastEff >= qT0)) {
                        qT0 = capT
                        qTip0 = pub
                    } else if (capT - qT0 >= 120L) {
                        val smp = (qTip0 - pub) / ((capT - qT0) / 1000.0)
                        if (smp > baseDrain * 0.3 && smp < baseDrain * 2.5) {
                            drainEst = if (drainN == 0) smp else 0.85 * drainEst + 0.15 * smp
                            drainN++
                        }
                        qT0 = capT
                        qTip0 = pub
                    }
                } else {
                    qT0 = 0L
                }
                prevTip = pub
                prevCapT = capT
            } else if (!haveTip) {
                prevCapT = 0L
                qT0 = 0L
            }
            engDrain = drain
            engGain = gain
            if (haveTip && newFrame) {
                realTip = pub
                realTipT = capT
            }
            obs = Obs(capT, pub, haveTip, zc, wZone, now, zvEst, realTip, realTipT)
            if (newFrame) {
                tapper.see(capT, pub, haveTip, zc, wZone)
                if (tapper.latN >= 6) edgeT.setLatency(tapper.delay)
                edgeT.see(capT, pub, haveTip, zc, wZone)
            }
            lastMT = now
            if (newFrame) {
                framesN++
                if (m >= zr - wZone && m <= zr) framesIn++
            }
            dbgCount++
            if (dbgCount % 20 == 0) {
                Dbg.add("reel tip=${m.toInt()} zone=${(zr - wZone).toInt()}-${zr.toInt()} drain=${drain.toInt()} gain=${gain.toInt()} aim=${Dbg.aiBias} taps=$tapCount")
            }
            if (Cfg.get(c, "debug") == 1) showDbg(r)
        }
        val gone = Cfg.get(c, "gonems").toLong()
        val goneNow = if (aiOn) maxOf(cyc(Cfg.get(c, "gonems")), minOf(gone, 300L)) else gone
        if (now - lastSeen > goneNow || now - t0 > 60000L) {
            stopEngine()
            val pct = 100 * framesIn / maxOf(framesN, 1)
            Dbg.inZone = pct
            cycReel = now - t0
            Dbg.add("Reel done: marker in the zone $pct% of the time, $tapCount taps")
            if (Cfg.get(c, "edgetap") == 1) Dbg.add("Edge tapper: " + edgeT.info())
            if (aiOn && gainN >= 8 && drainN >= 4) Ai.savePhys(c, gainEst, drainEst, screenW)
            if (Cfg.get(c, "tapeng") == 1) {
                // remember what the ledger measured: the real tap-to-game delay, the lift of one tap and the drain
                if (tapper.latN >= 6) Cfg.put(c, "tap_lat", tapper.delay.toInt().coerceIn(10, 140))
                if (aiOn && tapper.gN >= 8 && tapper.dN >= 4) Ai.savePhys(c, tapper.gain, tapper.drain, screenW)
                Dbg.add("Tap ledger: delay ${tapper.delay.toInt()} ms (${tapper.latN} taps measured), lift ${tapper.gain.toInt()} px, drain ${tapper.drain.toInt()} px/s")
            }
            phase = 7
            p7t = now
            p7Back = 0
            p7Resumes = 0
            p7State = 0
            p7Settle = 0
            p7Gone = 0
            p7Taps = 0
            p7Seen = false
            p7Prev = null
            h.postDelayed({ dnext() }, minOf(cyc(Cfg.get(c, "postcatch")), 120L))
        } else {
            h.postDelayed({ dnext() }, 1L)
        }
    }

    private fun scanPx(px: IntArray, x1: Int, y1: Int, w: Int, hh: Int): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    // color tolerance; it widens a little after every cast where the bar was not found
    private fun tolNow(): Int {
        val c = applicationContext
        return Cfg.get(c, "tol") + if (Cfg.get(c, "widen") == 1) minOf(failCasts, 3) * 10 else 0
    }

    private fun near(p: Int, t: Int, tol: Int): Boolean =
        Math.abs(((p shr 16) and 255) - ((t shr 16) and 255)) <= tol &&
        Math.abs(((p shr 8) and 255) - ((t shr 8) and 255)) <= tol &&
        Math.abs((p and 255) - (t and 255)) <= tol

    // returns [zoneCount, zoneMin, zoneMax, markerCount, markerSum] along the chosen axis
    private fun scan(b: Bitmap): DoubleArray {
        val c = applicationContext
        val out = doubleArrayOf(0.0, 1e9, -1.0, 0.0, 0.0)
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return out
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val zc = Cfg.get(c, "color")
        val mk = Cfg.get(c, "color2")
        val tol = tolNow()
        val vert = Cfg.get(c, "axis") == 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val pos = (if (vert) y1 + i / w else x1 + i % w).toDouble()
            if (near(p, zc, tol)) {
                out[0] += 1.0
                if (pos < out[1]) out[1] = pos
                if (pos > out[2]) out[2] = pos
            } else if (near(p, mk, tol)) {
                out[3] += 1.0
                out[4] += pos
            }
            i += 2
        }
        return out
    }

    private fun shotMain(cb: (Bitmap?) -> Unit) = shot { b -> h.post { cb(b) } }

    private fun detect(cb: (Int) -> Unit) {
        shot { b ->
            val n = if (b == null) 0 else count(b).also { b.recycle() }
            h.post { cb(n) }
        }
    }

    private var stopReason = ""
    private var lastBatCheck = 0L

    // v2.4: stop after N minutes, stop when the battery is low (checked about once a minute)
    private fun limitCheck(now: Long) {
        if (!running) return
        val c = applicationContext
        val mm = Cfg.get(c, "maxmin")
        if (mm > 0 && activeSeconds() >= mm * 60L) {
            stopReason = "time limit of $mm min reached"
            Dbg.add("Stopping: $stopReason")
            stop()
            toast("Reached $mm minutes, stopped")
            return
        }
        val lowBat = Cfg.get(c, "minbat")
        if (lowBat > 0 && now - lastBatCheck >= 60000L) {
            lastBatCheck = now
            try {
                val bm = c.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val st = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = st?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
                if (pct in 1..100 && pct <= lowBat && !charging) {
                    stopReason = "battery at $pct% (limit $lowBat%)"
                    Dbg.add("Stopping: $stopReason")
                    stop()
                    toast("Battery low ($pct%), stopped")
                }
            } catch (e: Throwable) { Dbg.add("Battery check failed: " + e.message) }
        }
    }

    private fun doneCheck(): Boolean {
        val m = Cfg.get(applicationContext, "maxcatch")
        if (m > 0 && catches >= m) { stopReason = "catch limit of $m reached"; stop(); toast("Reached $m catches, stopped"); return true }
        return false
    }

    private fun count(b: Bitmap): Int {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width - 1)
        val x2 = maxOf(Cfg.get(c, "r1_x"), Cfg.get(c, "r2_x")).coerceIn(0, b.width)
        val y1 = minOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height - 1)
        val y2 = maxOf(Cfg.get(c, "r1_y"), Cfg.get(c, "r2_y")).coerceIn(0, b.height)
        val w = x2 - x1
        val hh = y2 - y1
        if (w <= 0 || hh <= 0) return 0
        val px = IntArray(w * hh)
        b.getPixels(px, 0, w, x1, y1, w, hh)
        val tc = Cfg.get(c, "color")
        val tr = (tc shr 16) and 255
        val tg = (tc shr 8) and 255
        val tb = tc and 255
        val tol = tolNow()
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            if (Math.abs(((p shr 16) and 255) - tr) <= tol &&
                Math.abs(((p shr 8) and 255) - tg) <= tol &&
                Math.abs((p and 255) - tb) <= tol) n++
            i += 3
        }
        return n
    }

    fun test() {
        if (Build.VERSION.SDK_INT < 30) { toast("Detection needs Android 11 or newer"); return }
        track { r ->
            toast(if (r == null) "Could not capture the screen" else "Zone px: ${r[0].toInt()}, marker px: ${r[3].toInt()} (zone needs ${Cfg.get(applicationContext, "minpx")})")
            Dbg.add("TEST zone=${r?.get(0)?.toInt()} marker=${r?.get(3)?.toInt()}")
            showDbg(r)
            if (!running) h.postDelayed({ hideDebug() }, 4000L)
        }
    }

    // ---------- calibration on a frozen screenshot ----------
    private fun calibrate() {
        if (!Cap.active) { toast("Turn on screen capture first"); return }
        toast("Start fishing now. The screen is captured in 8 seconds, so have the reeling bar showing.")
        h.postDelayed({ calibrateNow() }, 8000L)
    }

    // a picture of the live screen capture (no screenshots)
    private fun liveMain(cb: (Bitmap?) -> Unit) {
        if (!Cap.active) { cb(null); return }
        bgOcr.execute {
            val bmp = try {
                val reg = Cap.region(0, 0, 100000, 100000, 1)
                if (reg == null) null else Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) { null }
            h.post { cb(bmp) }
        }
    }

    private fun calibrateNow() {
        if (!Cap.active) { toast("Turn on screen capture first"); return }
        liveMain { bmp ->
            if (bmp == null) { toast("Could not capture the screen"); return@liveMain }
            val c = applicationContext
            val box = FrameLayout(this)
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            val prompts = listOf(
                "1/4: tap the TOP-LEFT corner of the reeling bar area",
                "2/4: tap the BOTTOM-RIGHT corner of the bar area",
                "3/4: tap the GREEN zone (its color is saved)",
                "4/4: tap the filled (blue) part of the bar (its color is saved)"
            )
            val first = if (Cfg.get(c, "full") == 1) 2 else 0
            val t = pill(prompts[first], 0xDD000000.toInt())
            box.addView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 60 })
            var step = first
            box.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val x = (e.x / v.width * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = (e.y / v.height * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    when (step) {
                        0 -> { Cfg.put(c, "r1_x", x); Cfg.put(c, "r1_y", y) }
                        1 -> { Cfg.put(c, "r2_x", x); Cfg.put(c, "r2_y", y) }
                        2 -> Cfg.put(c, "color", bmp.getPixel(x, y))
                        else -> Cfg.put(c, "color2", bmp.getPixel(x, y))
                    }
                    step++
                    if (step >= 4) {
                        wm.removeView(box)
                        toast("Saved. Tap TEST while the bar is showing.")
                    } else {
                        t.text = prompts[step]
                    }
                }
                true
            }
            wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
                WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
        }
    }

    // ---------- floating panel ----------
    private fun pill(t: String, color: Int) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        setPadding(34, 22, 34, 22)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 60f }
    }

    fun refreshIcon() { loadIcon() }

    private fun loadIcon() {
        val v = iconView ?: return
        val f = java.io.File(filesDir, "float_icon.png")
        if (Cfg.get(applicationContext, "icon_custom") == 1 && f.exists()) {
            val b = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    fun isRunning() = running
    fun isPaused() = paused

    // Turn the accessibility service off from inside the app or the panel
    fun turnOff() {
        try { if (running) stop() } catch (e: Exception) { Dbg.add("Stop before turning off failed: " + e.message) }
        closePanel()
        Dbg.add("Service turned off")
        if (Build.VERSION.SDK_INT >= 24) disableSelf()
    }

    fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open the link: " + e.message) }
    }

    fun openRobloxApp() {
        val i = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
        if (i == null) { toast("Roblox is not installed"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open Roblox: " + e.message) }
    }

    fun openApp(capture: Boolean) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("capture", capture)
        try { startActivity(i) } catch (e: Exception) { Dbg.add("Could not open the app: " + e.message) }
    }

    fun openPanel() {
        if (panelUi != null) return
        if (clickersOn) hideClickers()
        val d = resources.displayMetrics.density
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        // the same shape as the LuminFish design (16:9). Small / Normal / Big is chosen in the app (Look).
        val baseDp = when (Cfg.get(this, "psize")) { 0 -> 272f; 2 -> 336f; else -> 304f }
        var pw: Float
        var ph: Float
        if (sw >= sh) {
            ph = minOf(baseDp * d, sh * 0.92f)
            pw = ph * 16f / 9f
            if (pw > sw * 0.94f) pw = sw * 0.94f
        } else {
            pw = sw * 0.95f
            ph = minOf(pw * 1.34f * (baseDp / 304f), sh * 0.82f)
        }
        val ui = PanelUi(this, pw.toInt(), ph.toInt())
        val lp = WL(pw.toInt(), ph.toInt(), WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
        }
        wm.addView(ui.root, lp)
        ui.root.alpha = 0f
        ui.root.scaleX = 0.9f
        ui.root.scaleY = 0.9f
        ui.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(1.15f)).start()
        ui.start()
        panelUi = ui
        panelSawRoblox = atRoblox()
        panelGone = 0
    }

    fun closePanel(instant: Boolean = false) {
        val ui = panelUi ?: return
        ui.stop()
        panelUi = null
        val v = ui.root
        if (instant) {
            v.animate().cancel()
            try { wm.removeView(v) } catch (_: Exception) {}
            return
        }
        v.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(170)
            .withEndAction { try { wm.removeView(v) } catch (_: Exception) {} }.start()
    }

    private fun showPanel() {
        val d = resources.displayMetrics.density
        val size = (50 * d).toInt()
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(0xFF000000.toInt())
                setStroke((2 * d).toInt(), 0xFFDCE6FF.toInt())
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        iconView = icon
        loadIcon()
        val box = FloatIcon(this, icon, 50)
        floatIcon = box
        val total = (80 * d).toInt()
        val lp = WL(total, total, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 100
        }
        drag(box, box, lp) { if (panelUi == null) openPanel() else closePanel() }
        wm.addView(box, lp)
        panel = box
        iconLp = lp
        box.scaleX = 0f
        box.scaleY = 0f
        box.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.9f)).start()
        box.setState(if (running) (if (paused) 2 else 1) else 0)
    }

    private fun drag(v: View, row: View, lp: WL, click: () -> Unit) {
        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start() }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt()
                    val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > 12 || Math.abs(my) > 12) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(row, lp) }
                }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); if (!moved) click() }
            }
            true
        }
    }

    // ---------- draggable auto clickers (v1.8) ----------
    private class Clk(val k: String, val v: View, val lp: WindowManager.LayoutParams, val sz: Int, val off: IntArray)
    private val clickers = ArrayList<Clk>()
    private var clickBar: View? = null
    private var clickersOn = false
    private val clickShort = mapOf("cast" to "CAST", "reel" to "REEL", "inv" to "INV", "sellall" to "SELL", "confirm" to "OK",
        "joy" to "MOVE", "cam" to "CAM", "shift" to "SHIFT", "dismiss" to "X")
    private val clickColor = mapOf("cast" to 0xFF38D39F.toInt(), "reel" to 0xFFFF8A00.toInt(), "inv" to 0xFF3EA6FF.toInt(),
        "sellall" to 0xFFB48CFF.toInt(), "confirm" to 0xFFFFD34D.toInt(), "joy" to 0xFFFFFFFF.toInt(),
        "cam" to 0xFF7FE0FF.toInt(), "shift" to 0xFFFF7AA8.toInt(), "dismiss" to 0xFFFF6B6B.toInt())
    // where each circle starts the first time (fractions of the screen), after that it starts where you left it
    private val clickFrac = mapOf("cast" to doubleArrayOf(0.855, 0.759), "reel" to doubleArrayOf(0.5, 0.306),
        "inv" to doubleArrayOf(0.051, 0.463), "sellall" to doubleArrayOf(0.47, 0.704), "confirm" to doubleArrayOf(0.534, 0.611),
        "joy" to doubleArrayOf(0.107, 0.741), "cam" to doubleArrayOf(0.726, 0.463), "shift" to doubleArrayOf(0.919, 0.231),
        "dismiss" to doubleArrayOf(0.641, 0.102))

    fun clickersShown() = clickersOn

    fun showClickers() {
        if (!::wm.isInitialized || clickersOn) return
        if (running) {
            toast("Stop the macro first, then show the auto clickers")
            return
        }
        closePanel()
        val c = applicationContext
        val d = resources.displayMetrics.density
        val sz = (50 * d).toInt()
        val dm = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val saved = Cfg.get(c, "pts_ok") == 1
        val flags = WL.FLAG_NOT_FOCUSABLE or WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS
        for (k in Cfg.keys) {
            val fr = clickFrac[k] ?: doubleArrayOf(0.5, 0.5)
            val cx = if (saved) Cfg.get(c, k + "_x") else (fr[0] * dm.widthPixels).toInt()
            val cy = if (saved) Cfg.get(c, k + "_y") else (fr[1] * dm.heightPixels).toInt()
            val col = clickColor[k] ?: 0xFFFFFFFF.toInt()
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val v = object : TextView(this) {
                // v2.3: a small dot in the exact middle of the circle = the spot that gets tapped
                override fun onDraw(cv: Canvas) {
                    super.onDraw(cv)
                    val cx = width / 2f
                    val cy = height / 2f
                    dotPaint.color = 0xFF000000.toInt()
                    cv.drawCircle(cx, cy, 4.2f * d, dotPaint)
                    dotPaint.color = 0xFFFFFFFF.toInt()
                    cv.drawCircle(cx, cy, 2.8f * d, dotPaint)
                }
            }.apply {
                text = clickShort[k] ?: k
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, (4 * d).toInt(), 0, 0)
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                background = GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    setColor((0x55 shl 24) or (col and 0xFFFFFF))
                    setStroke((3 * d).toInt(), col)
                }
            }
            val lp = WL(sz, sz, WL.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cx - sz / 2
                y = cy - sz / 2
                // let the circle sit anywhere on the screen, also under a camera notch
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WL.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // how far Android moved this window from the place we asked for (notch, bars). The tap uses the REAL spot.
            val off = IntArray(2)
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ox = lp.x
                        oy = lp.y
                        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ox + (e.rawX - sx).toInt()
                        lp.y = oy + (e.rawY - sy).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        Cfg.put(c, k + "_x", lp.x + off[0] + sz / 2)
                        Cfg.put(c, k + "_y", lp.y + off[1] + sz / 2)
                        Cfg.put(c, "pts_ok", 1)
                    }
                }
                true
            }
            try {
                wm.addView(v, lp)
                clickers.add(Clk(k, v, lp, sz, off))
                // v2.3: the circles pop in one after another
                Fx.pop(v, 70L * clickers.size, 0.3f, 420)
                v.post {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    off[0] = loc[0] - lp.x
                    off[1] = loc[1] - lp.y
                    if (Math.abs(off[0]) > 1 || Math.abs(off[1]) > 1) {
                        // Android put the window somewhere else: move it so the circle really is on the saved spot
                        lp.x -= off[0]
                        lp.y -= off[1]
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        Dbg.add("Clicker $k was off by ${off[0]},${off[1]} px, corrected")
                    }
                }
            } catch (e: Exception) {
                Dbg.add("Could not show the clicker " + k + ": " + e.message)
            }
        }
        val bar = pill("Drag each circle onto its button, then tap DONE", 0xEE1E8E5A.toInt())
        bar.setOnClickListener { hideClickers() }
        val blp = WL(WRAP, WRAP, WL.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (14 * d).toInt()
        }
        try {
            wm.addView(bar, blp)
            clickBar = bar
        } catch (e: Exception) {
            Dbg.add("Could not show the DONE bar: " + e.message)
        }
        clickersOn = true
        Dbg.add("Auto clickers shown")
    }

    fun hideClickers(save: Boolean = true) {
        if (!clickersOn && clickers.isEmpty()) return
        val c = applicationContext
        for (k in clickers) {
            if (save) {
                Cfg.put(c, k.k + "_x", k.lp.x + k.off[0] + k.sz / 2)
                Cfg.put(c, k.k + "_y", k.lp.y + k.off[1] + k.sz / 2)
            }
            try { wm.removeView(k.v) } catch (_: Exception) {}
        }
        clickers.clear()
        clickBar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        clickBar = null
        clickersOn = false
        if (save) {
            Cfg.put(c, "pts_ok", 1)
            toast("Positions saved")
            Dbg.add("Auto clickers saved")
        }
    }

    // ---------- tap position picker ----------
    fun setPoints() = showClickers()

    fun setExtraPoints() = pickAt(Cfg.extra, 0) { toast("Extra points saved") }

    fun setWatchRegion() = pickAt(Cfg.watKeys, 0) { toast("Watch area saved") }

    fun setBiomeRegion() = pickAt(Cfg.bioKeys, 0) {
        bioWarned = false
        toast("Biome area saved")
    }

    private fun pickAt(keys: List<String>, i: Int, done: () -> Unit) {
        if (i >= keys.size) { done(); return }
        val k = keys[i]
        val box = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val t = TextView(this).apply {
            text = "${i + 1}/${keys.size}: tap the ${Cfg.label[k] ?: Cfg.label2[k] ?: k}"
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
            gravity = Gravity.CENTER
        }
        box.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                Cfg.put(applicationContext, k + "_x", e.rawX.toInt())
                Cfg.put(applicationContext, k + "_y", e.rawY.toInt())
                wm.removeView(box)
                pickAt(keys, i + 1, done)
            }
            true
        }
        wm.addView(box, WL(MATCH, MATCH, WL.TYPE_ACCESSIBILITY_OVERLAY,
            WL.FLAG_LAYOUT_IN_SCREEN or WL.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT))
    }

    // ---------- timed tasks (auto craft, buy, biome items: your own scripts) ----------
    private fun dueTask(): Int {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        for (i in 0..2) {
            val k = "t" + (i + 1)
            if (Cfg.get(c, k + "on") != 1) continue
            val min = Cfg.get(c, k + "min")
            if (min <= 0 || Cfg.gets(c, k + "s").isBlank()) continue
            if (now - taskLast[i] >= min * 60000L) return i
        }
        return -1
    }

    // ---------- background services: anti-kick, screenshots, biome watch ----------
    private var statusLast = 0L

    private fun services() {
        val c = applicationContext
        val now = SystemClock.uptimeMillis()
        val inRoblox = atRoblox()
        // v1.9: the live screen capture is required, stop cleanly if it was turned off
        if (running && !rejoining && !Cap.active) captureLost()
        // v1.9 click recovery: an open panel swallows the taps meant for the game
        if (running && !paused) {
            if (panelUi != null) {
                panelOpenTicks++
                if (panelOpenTicks >= 4 && Cfg.get(c, "recover") == 1) {
                    panelOpenTicks = 0
                    Dbg.clickFix++
                    Dbg.add("Click recovery: the panel was blocking the taps, closing it")
                    closePanel()
                }
            } else panelOpenTicks = 0
        } else panelOpenTicks = 0
        // v1.9 anti-freeze
        if (running && !paused && !rejoining && inRoblox && Cfg.get(c, "freeze") == 1) freezeCheck(now)
        // loop watchdog (works with or without the AI): the reel loop stamps loopBeat every few ms,
        // so a silence of several seconds means the chain broke. Restart it in place.
        if (running && !paused && !rejoining && inRoblox && loopBeat > 0L &&
            (phase == 0 || phase == 1 || phase == 2 || phase == 7) && now - loopBeat > 6000L) {
            loopRestart()
        }
        // AI watchdog: nothing caught, failed or found for a long time -> clear any panel and cast again
        if (running && !paused && !rejoining && inRoblox && Cfg.get(c, "ai") == 1 && (phase <= 2 || phase == 7)) {
            val lim = maxOf(Cfg.get(c, "stuck"), 30) * 1000L
            if (now - progressT > lim) aiRecover()
        }
        // anti-kick: if nothing was tapped for a while (paused, long wait) nudge the camera a little
        if (running && inRoblox && Cfg.get(c, "afk") == 1) {
            val every = maxOf(Cfg.get(c, "afkmin"), 1) * 60000L
            if (now - lastGesture >= every && now > critUntil) {
                lastGesture = now
                Dbg.add("Anti-kick: small camera move")
                camSwipe("right", 30)
                h.postDelayed({ camSwipe("left", 30) }, 700L)
            }
        }
        // periodic screenshot to the webhooks
        if (running && Cap.active && Cfg.get(c, "shoton") == 1) {
            val every = maxOf(Cfg.get(c, "shotmin"), 1) * 60000L
            if (now - lastShotSent >= every) {
                lastShotSent = now
                bgOcr.execute {
                    val reg = Cap.region(0, 0, 100000, 100000, 2)
                    if (reg != null) {
                        val bmp = Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
                        val o = java.io.ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 70, o)
                        bmp.recycle()
                        Hook.sendImage(c, "Screenshot after " + catches + " catches", o.toByteArray())
                    }
                }
            }
        }
        // word watch (merchant, aura names, anything you list) and disconnect check
        if (inRoblox && Cap.active && Cfg.get(c, "watch") == 1 && !watchBusy &&
            now - watchLast >= maxOf(Cfg.get(c, "watchsec"), 3) * 1000L) {
            watchLast = now
            ocrCheck(true)
        }
        if (running && !rejoining && inRoblox && Cap.active && Cfg.get(c, "rejoin") == 1 && !watchBusy &&
            now - lastDiscCheck >= 20000L) {
            lastDiscCheck = now
            ocrCheck(false)
        }
        // (biome watch now runs on its own fast ticker, see biomeTick)
        // status report to the webhooks every N minutes while the macro runs
        if (running && Cfg.get(c, "statuson") == 1 && now - statusLast >= maxOf(Cfg.get(c, "statusmin"), 5) * 60000L) {
            if (statusLast != 0L) sendStatus()
            statusLast = now
        }
        limitCheck(now)
    }

    private fun loopRestart() {
        loopRestarts++
        Dbg.add("Reel loop was silent for 6 s, restarting it (" + loopRestarts + ")")
        trackGen++
        loopBeat = SystemClock.uptimeMillis()
        if (phase == 2) startEngine() else stopEngine()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ dnext() }, 20L)
    }

    private fun aiRecover() {
        val c = applicationContext
        aiStuck++
        Dbg.add("AI: no progress for a while, recovering ($aiStuck)")
        stopEngine()
        h.removeCallbacksAndMessages(null)
        if (aiStuck >= 3) {
            aiStuck = 0
            if (Cfg.get(c, "rejoin") == 1) { beginRejoin("AI: stuck for a long time"); return }
        }
        Hook.send(c, "AI recovery", "No progress, closing panels and casting again (try $aiStuck).", 0xFFB84D)
        tapPoint("dismiss")
        h.postDelayed({ tapPoint("dismiss") }, 350L)
        phase = 0
        roi = null
        failCasts = 0
        progressT = SystemClock.uptimeMillis()
        h.postDelayed({ dnext() }, 900L)
    }

    private fun sendStatus() {
        val c = applicationContext
        val min = maxOf((SystemClock.uptimeMillis() - Dbg.startMs) / 60000L, 0L)
        val perH = if (min > 0) catches * 60L / min else 0L
        Hook.status(c, "Status report", 0x3EA6FF, listOf(
            Triple("Uptime", "$min min", true),
            Triple("Catches", "$catches", true),
            Triple("Per hour", "$perH", true),
            Triple("Sells", "${Dbg.sells}", true),
            Triple("Failed reels", "${Dbg.fails}", true),
            Triple("Biome", Dbg.biome.ifEmpty { "-" }, true)
        ), Biome.summary())
    }

    // ---------- biome detection ----------
    private fun biomeBox(): IntArray? {
        val c = applicationContext
        val x1 = minOf(Cfg.get(c, "bio1_x"), Cfg.get(c, "bio2_x"))
        val x2 = maxOf(Cfg.get(c, "bio1_x"), Cfg.get(c, "bio2_x"))
        val y1 = minOf(Cfg.get(c, "bio1_y"), Cfg.get(c, "bio2_y"))
        val y2 = maxOf(Cfg.get(c, "bio1_y"), Cfg.get(c, "bio2_y"))
        if (x2 - x1 < 20 || y2 - y1 < 8) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    // A picture of the biome area: from fast capture when it is on, otherwise from one screenshot.
    // The callback always runs on the screen-reading thread (safe for text recognition).
    private fun grabBox(a: IntArray, cb: (Bitmap?) -> Unit) {
        if (Cap.active) {
            val reg = Cap.region(a[0], a[1], a[2] - a[0], a[3] - a[1], 1)
            cb(if (reg == null) null else Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888))
            return
        }
        // v1.9: no screenshots, the biome picture comes from the live capture only
        if (true) { cb(null); return }
        shot { full ->
            bgOcr.execute {
                if (full == null) { cb(null); return@execute }
                var crop: Bitmap? = null
                try {
                    val x = a[0].coerceIn(0, full.width - 2)
                    val y = a[1].coerceIn(0, full.height - 2)
                    val w = minOf(a[2] - a[0], full.width - x)
                    val hh = minOf(a[3] - a[1], full.height - y)
                    crop = if (w > 4 && hh > 4) Bitmap.createBitmap(full, x, y, w, hh) else null
                } catch (e: Exception) {
                    Dbg.add("Biome picture error: " + e.message)
                }
                val keep = crop?.copy(Bitmap.Config.ARGB_8888, false)
                if (crop != null && crop !== full) crop.recycle()
                full.recycle()
                cb(keep)
            }
        }
    }

    // Reads the biome name with several versions of the picture and returns what was read and which biome it is.
    private fun readBiome(bmp: Bitmap): Pair<String, Biome.Info?> {
        val vs = Biome.variants(bmp)
        var firstText = ""
        var hit: Biome.Info? = null
        try {
            for (v in vs) {
                val t = Ocr.read(v) ?: continue
                if (firstText.isEmpty()) firstText = t.replace("\n", " ").trim()
                val b = Biome.match(t)
                if (b != null) { hit = b; firstText = t.replace("\n", " ").trim(); break }
            }
        } finally {
            for (v in vs) if (v !== bmp) v.recycle()
        }
        return Pair(firstText, hit)
    }

    // Runs every 0.4 s. A normal read happens every N seconds, but right after a possible change the second
    // (confirming) read follows almost at once, so the alert goes out within about a second.
    private fun biomeTick() {
        val c = applicationContext
        if (Cfg.get(c, "bio") != 1) return
        val now = SystemClock.uptimeMillis()
        if (bioBusy && now - bioStartAt > 15000L) {
            Dbg.add("Biome watch: a read hung, resetting it")
            bioBusy = false
        }
        if (bioBusy) return
        if (!Cap.active) return
        val gap = if (bioPend.isNotEmpty()) 250L else maxOf(Cfg.get(c, "biosec"), 1) * 1000L
        if (now - bioLast < gap) return
        if (!atRoblox()) return
        biomeWatch()
    }

    private fun biomeWatch() {
        val c = applicationContext
        val a = biomeBox()
        if (a == null) {
            if (!bioWarned) {
                bioWarned = true
                Dbg.add("Biome watch: set the biome area first (Panel > Settings > Set biome area)")
            }
            return
        }
        bioLast = SystemClock.uptimeMillis()
        bioStartAt = bioLast
        bioBusy = true
        bgOcr.execute {
            try {
                grabBox(a) { bmp ->
                    try {
                        if (bmp != null) {
                            val res = readBiome(bmp)
                            bmp.recycle()
                            biomeResult(res.second)
                        }
                    } catch (e: Throwable) {
                        Dbg.add("Biome watch error: " + e.message)
                    } finally {
                        bioBusy = false
                    }
                }
            } catch (e: Throwable) {
                Dbg.add("Biome watch error: " + e.message)
                bioBusy = false
            }
        }
    }

    // A change must be read twice in a row before it counts (one bad read cannot send a message).
    private fun biomeResult(hit: Biome.Info?) {
        val c = applicationContext
        if (hit == null) return
        val cur = Biome.cur
        if (cur != null && hit.key == cur.key) { bioPend = ""; return }
        val nowU = SystemClock.uptimeMillis()
        if (hit.key != bioPend || nowU - bioPendAt > 8000L) { bioPend = hit.key; bioPendAt = nowU; return }
        bioPend = ""
        val now = System.currentTimeMillis()
        if (cur != null) {
            val d = now - Biome.sinceMs
            Biome.addTime(cur.key, d)
            if (Cfg.get(c, "bioend") == 1 && cur.key != "normal" && Biome.mode(c, cur.key) > 0) Hook.biome(c, cur, false, d, false)
        }
        Biome.cur = hit
        Biome.sinceMs = now
        Biome.count(hit.key)
        Dbg.biome = hit.title
        Dbg.add("Biome: " + hit.title)
        val mode = Biome.mode(c, hit.key)
        if (mode > 0) Hook.biome(c, hit, true, 0L, mode == 2)
    }

    // Test button: reads the biome area once and says what it saw, so you can check the area is right.
    fun testBiome() {
        val a = biomeBox()
        if (a == null) { toast("Set the biome area first (Panel > Settings > Set biome area)"); return }
        if (!Cap.active) { toast("Turn on screen capture first"); return }
        toast("Reading the biome area...")
        bgOcr.execute {
            grabBox(a) { bmp ->
                if (bmp == null) { h.post { toast("Could not take a picture of the screen") }; return@grabBox }
                try {
                    val res = readBiome(bmp)
                    bmp.recycle()
                    val raw = res.first.take(40).ifEmpty { "nothing" }
                    val msg = if (res.second != null) "Biome read: " + res.second!!.title + "  (text: " + raw + ")"
                        else "No biome name found. The area read: " + raw + ". Set the area again around only the biome name."
                    Dbg.add(msg)
                    h.post { toast(msg) }
                } catch (e: Throwable) {
                    Dbg.add("Biome test error: " + e.message)
                }
            }
        }
    }
}


===== FILE: app/src/main/java/com/solmacro/MainActivity.kt =====

package com.solmacro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.animation.OvershootInterpolator
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

private const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
private const val HUG = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val cBg = 0xFF03050E.toInt()
    private val cCard = 0xFF0A1028.toInt()
    private val cBd = 0xFF22306B.toInt()
    private val cTx = 0xFFE6ECFF.toInt()
    private val cMut = 0xFF8E9BD0.toInt()
    private val cAcc = 0xFFDCE6FF.toInt()
    private val cGlow = 0xFF8FB4FF.toInt()
    private val cOk = 0xFF38D39F.toInt()
    private val cBad = 0xFFFF6B6B.toInt()
    private val cWarn = 0xFFFFB84D.toInt()
    private val cTile = 0xFF101838.toInt()
    private val cSec = 0xFF111A3C.toInt()
    private val cLine = 0xFF2A3A82.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var pill: TextView
    private lateinit var pillBox: LinearLayout
    private lateinit var pillDot: ImageView
    private lateinit var svcBtn: View
    private lateinit var stateTv: TextView
    private lateinit var tCatch: TextView
    private lateinit var tFail: TextView
    private lateinit var tSell: TextView
    private lateinit var tRun: TextView
    private lateinit var kCap: TextView
    private lateinit var kTaps: TextView
    private lateinit var kChecks: TextView
    private lateinit var kLat: TextView
    private lateinit var kBite: TextView
    private lateinit var kAim: TextView
    private lateinit var statsTv: TextView
    private lateinit var posStatus: TextView
    private val posVals = HashMap<String, TextView>()
    private lateinit var logo: ImageView
    private lateinit var prevIcon: ImageView
    private var cardN = 0
    private var autoReturn = false
    private var logoAnim: ObjectAnimator? = null
    private lateinit var logTv: TextView
    private lateinit var accountChip: ImageView

    // v2.3 account: picture + name from the sign-in, tap for sign out
    private fun refreshChip() {
        val f = Auth.avatarFile(this)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        accountChip.setImageBitmap(bmp ?: Auth.initialBitmap(this, dp(72)))
    }

    private fun showAccount() {
        val who = if (Auth.signedIn(this)) Auth.name(this) + "\nSigned in with " + Auth.label(Auth.provider(this)) else "Guest (not signed in)"
        val b = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Account")
            .setMessage(who)
            .setNegativeButton("Close", null)
        b.setPositiveButton(if (Auth.signedIn(this)) "Sign out" else "Sign in") { _, _ ->
            Auth.signOut(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        if (Auth.signedIn(this)) b.setNeutralButton("Remove account") { _, _ ->
            Auth.current(this)?.let { Auth.removeAccount(this, it) }
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        b.show()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    // The text never grows with the phone's font size setting, so nothing gets cranked or cut off.
    override fun attachBaseContext(base: Context) {
        val cfg = android.content.res.Configuration(base.resources.configuration)
        if (cfg.fontScale > 1.0f) cfg.fontScale = 1.0f
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int = cTx, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    private fun ic(res: Int, sizeDp: Int, tint: Int = cTx) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun TextView.setIfChanged(s: String) {
        if (text.toString() != s) text = s
    }

    private fun add(p: LinearLayout, v: View, top: Int = 8) {
        p.addView(v, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(top) })
    }

    private fun card(title: String?, icon: Int = 0): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cCard, 18, cBd)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        add(root, c, 12)
        cardN++
        if (title != null) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != 0) {
                val chip = FrameLayout(this).apply { background = shape(0xFF111A3C.toInt(), 10) }
                chip.addView(ic(icon, 18, cGlow), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                h.addView(chip, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) })
            }
            h.addView(tv(title, 16f, cTx, true), LinearLayout.LayoutParams(0, HUG, 1f))
            c.addView(h, LinearLayout.LayoutParams(FILL, HUG))
        }
        return c
    }

    // a real button: icon + text, never cut off, 48dp tall at least
    private fun btn(t: String, color: Int, icon: Int = 0, onClick: () -> Unit): LinearLayout {
        val bg = if (color == cBd) cSec else color
        val fg = if (color == cOk) 0xFF06251C.toInt() else if (color == cAcc || color == cGlow) 0xFF0A1030.toInt() else cTx
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = shape(bg, 14, if (color == cBd) cLine else 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
        }
        if (icon != 0) b.addView(ic(icon, 18, fg), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        val label = tv(t, 14f, fg, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        b.addView(label, LinearLayout.LayoutParams(HUG, HUG))
        b.tag = label
        b.setOnClickListener { onClick() }
        press(b)
        return b
    }

    private fun row(p: LinearLayout, vararg b: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, x) in b.withIndex()) {
            r.addView(x, LinearLayout.LayoutParams(0, FILL, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        add(p, r, 12)
    }

    private fun styleEdit(e: EditText) {
        e.setTextColor(cTx)
        e.setHintTextColor(0xFF5E6AA0.toInt())
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), shape(cBg, 12, cGlow))
        sl.addState(intArrayOf(), shape(cBg, 12, cBd))
        e.background = sl
        e.setPadding(dp(14), dp(11), dp(14), dp(11))
    }

    private fun field(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Cfg.get(this@MainActivity, key).toString())
            setSelectAllOnFocus(true)
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    Cfg.put(this@MainActivity, key, s.toString().toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    // one small tile: icon, label, big number
    private fun tile(p: LinearLayout, icon: Int, tint: Int, label: String, first: Boolean): TextView {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cTile, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        t.addView(ic(icon, 22, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 11f, cMut))
        val v = tv("0", 18f, cTx, true)
        col.addView(v)
        t.addView(col, LinearLayout.LayoutParams(0, HUG, 1f))
        p.addView(t, LinearLayout.LayoutParams(0, HUG, 1f).apply { if (!first) leftMargin = dp(8) })
        return v
    }

    // small label over value, two per line
    private fun kv(p: LinearLayout, label: String, first: Boolean): TextView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 11f, cMut))
        val v = tv("-", 13f, cTx, true)
        col.addView(v)
        p.addView(col, LinearLayout.LayoutParams(0, HUG, 1f).apply { if (!first) leftMargin = dp(8) })
        return v
    }

    private fun hrow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun step(p: LinearLayout, n: Int, text: String) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val badge = tv(n.toString(), 12f, 0xFF0A0A0C.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(cAcc) }
        }
        r.addView(badge, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(1); rightMargin = dp(10) })
        r.addView(tv(text, 13f, cMut), LinearLayout.LayoutParams(0, HUG, 1f))
        add(p, r, 10)
    }

    private fun bump(t: TextView, text: String) {
        if (t.text.toString() == text) return
        t.text = text
        t.scaleX = 1.12f
        t.scaleY = 1.12f
        t.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    // text fields that edit the same saved value (webhook links, private server link) stay in step with each other,
    // and a small line under the field says whether the text looks right
    private val linked = HashMap<String, MutableList<EditText>>()

    private fun webhookCheck(s: String): String {
        val parts = s.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val bad = parts.count { !Hook.looksValid(it) }
        return if (bad == 0) "OK: " + parts.size + " webhook link" + (if (parts.size > 1) "s" else "") + " saved"
        else "$bad link(s) do not look like a Discord webhook (https://discord.com/api/webhooks/ID/TOKEN)"
    }

    private fun serverLinkCheck(s: String): String {
        if (s.isEmpty()) return ""
        return if (s.startsWith("https://www.roblox.com/") || s.startsWith("https://roblox.com/") || s.startsWith("https://ro.blox.com/"))
            "OK: link saved"
        else "This does not look like a Roblox private server link (it starts with https://www.roblox.com/)"
    }

    private fun linkField(p: LinearLayout, label: String, key: String, hintText: String, multi: Boolean, check: (String) -> String) {
        add(p, tv(label, 12f, cMut), 12)
        val status = tv("", 11f, cMut)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            styleEdit(this)
        }
        val list = linked.getOrPut(key) { ArrayList() }
        list.add(e)
        fun refresh(t: String) {
            val m = check(t)
            status.text = m
            status.setTextColor(if (m.startsWith("OK")) cOk else if (m.isEmpty()) cMut else cBad)
        }
        refresh(e.text.toString().trim())
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString().trim()
                Cfg.puts(this@MainActivity, key, t)
                refresh(t)
                for (o in list) if (o !== e && o.text.toString().trim() != t) o.setText(t)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // when you leave the webhook field, look up its channel now so the first biome alert is not delayed
        e.setOnFocusChangeListener { _, has -> if (!has && key == "webhook") Hook.prefetch(this@MainActivity) }
        add(p, e, 4)
        add(p, status, 2)
    }

    private fun textField(p: LinearLayout, label: String, key: String, hintText: String = "") {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString().trim()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun sw(p: LinearLayout, label: String, key: String) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val s = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(cGlow, 0xFF8A99D8.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x668FB4FF, 0xFF1A2350.toInt()))
            isChecked = Cfg.get(this@MainActivity, key) == 1
            setOnCheckedChangeListener { _, on -> Cfg.put(this@MainActivity, key, if (on) 1 else 0) }
        }
        r.addView(tv(label, 14f, cTx), LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(12) })
        r.addView(s, LinearLayout.LayoutParams(HUG, HUG))
        r.setOnClickListener { s.toggle() }
        add(p, r, 6)
    }

    private fun roundIcon(sizeDp: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0xFF000000.toInt()) }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(10) }
    }

    private fun applyIcon(v: ImageView) {
        val f = File(filesDir, "float_icon.png")
        if (Cfg.get(this, "icon_custom") == 1 && f.exists()) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    private fun pickIcon() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 101)
    }

    private fun resetIcon() {
        Cfg.put(this, "icon_custom", 0)
        applyIcon(prevIcon)
        applyIcon(logo)
        MacroService.inst?.refreshIcon()
    }

    private fun saveIcon(uri: android.net.Uri) {
        try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            val o2 = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(opt.outWidth, opt.outHeight) / 1024) }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: throw Exception("no image")
            val side = minOf(bmp.width, bmp.height)
            val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = android.graphics.Bitmap.createScaledBitmap(sq, 192, 192, true)
            FileOutputStream(File(filesDir, "float_icon.png")).use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Cfg.put(this, "icon_custom", 1)
            applyIcon(prevIcon)
            applyIcon(logo)
            MacroService.inst?.refreshIcon()
            Toast.makeText(this, "Icon updated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not use that image", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var bgPrev: ImageView

    private fun showBgPreview() {
        val d = PanelBg.load(this, 800)
        if (d != null) bgPrev.setImageDrawable(d) else bgPrev.setImageDrawable(null)
    }

    private fun pickBg() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 102)
    }

    // a button that steps through a list of values and saves the choice
    private fun cycleBtn(p: LinearLayout, key: String, opts: IntArray, fmt: (Int) -> String) {
        val b = btn(fmt(Cfg.get(this, key)), cBd, R.drawable.ic_repeat) {}
        b.setOnClickListener {
            val cur = Cfg.get(this, key)
            var nxt = opts[0]
            for (k in opts.indices) if (opts[k] == cur && k + 1 < opts.size) nxt = opts[k + 1]
            Cfg.put(this, key, nxt)
            (b.tag as TextView).text = fmt(nxt)
        }
        add(p, b, 8)
    }

    private fun script(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 12)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            setText(Cfg.gets(this@MainActivity, key))
            styleEdit(this)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 101 && res == RESULT_OK && data?.data != null) saveIcon(data.data!!)
        if (req == 102 && res == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            Toast.makeText(this, "Loading background…", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = PanelBg.save(this, uri)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Panel background set" else "Could not use that file", Toast.LENGTH_SHORT).show()
                    showBgPreview()
                }
            }.start()
        }
        if (req == 100) {
            if (res == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Fast capture starting", Toast.LENGTH_SHORT).show()
                if (autoReturn) {
                    autoReturn = false
                    // go back to Roblox so you can press Start right away
                    handler.postDelayed({
                        val i2 = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
                        if (i2 != null) startActivity(i2)
                    }, 900L)
                }
            } else {
                autoReturn = false
                Toast.makeText(this, "Permission not given", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun preset(cast: Int, wait: Int, taps: Int, gap: Int) {
        Cfg.put(this, "cast", cast); Cfg.put(this, "wait", wait); Cfg.put(this, "taps", taps); Cfg.put(this, "gap", gap)
        recreate()
    }

    // ---------- left menu ----------
    private val secNames = listOf("Home", "Fishing", "Detection", "Scripts", "Watchers", "Discord", "Rejoin", "Look", "Log")
    private val secIcons = listOf(R.drawable.ic_home, R.drawable.ic_fish, R.drawable.ic_robot, R.drawable.ic_compass,
        R.drawable.ic_eye, R.drawable.ic_chat, R.drawable.ic_repeat, R.drawable.ic_palette, R.drawable.ic_log)
    private val menuIcons = ArrayList<ImageView>()
    private val menuTints = IntArray(9) { 0xFFE6ECFF.toInt() }
    private val pages = ArrayList<LinearLayout>()
    private val scrolls = ArrayList<ScrollView>()
    private val menuRows = ArrayList<LinearLayout>()
    private val menuLabels = ArrayList<TextView>()
    private var curSec = -1
    private lateinit var menuScroll: ScrollView
    private lateinit var indicator: View
    private lateinit var burger: ImageView
    private lateinit var secTv: TextView
    private lateinit var monTv: TextView
    private lateinit var meter: View

    private lateinit var bioTv: TextView

    // one tappable chip per biome: off -> message -> message and ping
    private fun biomeChips(p: LinearLayout) {
        var rowL: LinearLayout? = null
        for ((i, b) in Biome.all.withIndex()) {
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                add(p, rowL, 6)
            }
            val chip = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            fun paint() {
                val md = Biome.mode(this, b.key)
                chip.text = Biome.nice(b) + "\n" + (if (md == 0) "off" else if (md == 1) "message" else "message + ping")
                if (md == 0) { chip.background = shape(cBg, 12, cBd); chip.setTextColor(cMut) }
                else if (md == 1) { chip.background = shape(cBg, 12, cGlow); chip.setTextColor(cTx) }
                else { chip.background = shape(cAcc, 12); chip.setTextColor(0xFF0A1030.toInt()) }
            }
            paint()
            chip.setOnClickListener {
                Biome.setMode(this, b.key, (Biome.mode(this, b.key) + 1) % 3)
                paint()
            }
            press(chip)
            rowL!!.addView(chip, LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(6) })
        }
    }

    private val rowH get() = dp(48)
    private val rowGap get() = dp(4)

    private fun rowTop(i: Int) = dp(4) + rowGap + i * (rowH + rowGap)

    private fun useSection(i: Int) { root = pages[i] }

    private fun press(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private var drawerOpen = false

    private fun drawerW() = minOf(dp(280), (resources.displayMetrics.widthPixels * 0.8f).toInt())

    // The top bar is centered: menu button on the left, logo + name in the middle, an empty slot on the
    // right of the same size so the middle stays exactly centered. The section list is NOT on the screen;
    // it slides in as a panel when the menu button is tapped.
    private fun buildShell(): View {
        val shellFrame = FrameLayout(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        burger = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu)
            setColorFilter(cTx)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
        }
        press(burger)
        head.addView(burger, LinearLayout.LayoutParams(dp(48), dp(48)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        logo = roundIcon(40)
        logo.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        applyIcon(logo)
        mid.addView(logo)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("LuminFish", 22f, cTx, true))
        secTv = tv("", 12f, cMut)
        titles.addView(secTv)
        mid.addView(titles, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(10) })
        head.addView(mid, LinearLayout.LayoutParams(0, HUG, 1f))
        accountChip = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()); setStroke(dp(1), 0xFF2A3A82.toInt()) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { showAccount() }
        }
        refreshChip()
        Fx.pop(accountChip, 350)
        head.addView(accountChip, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6); rightMargin = dp(6) })
        outer.addView(head, LinearLayout.LayoutParams(FILL, HUG))
        val rule = View(this).apply { setBackgroundColor(0xFF1B2552.toInt()) }
        outer.addView(rule, LinearLayout.LayoutParams(FILL, dp(1)))

        // pages (full width, nothing beside them)
        val content = FrameLayout(this)
        for (i in secNames.indices) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(40))
            }
            val sv = ScrollView(this).apply { visibility = View.GONE; isVerticalScrollBarEnabled = false; addView(page) }
            pages.add(page)
            scrolls.add(sv)
            content.addView(sv, FrameLayout.LayoutParams(FILL, FILL))
        }
        outer.addView(content, LinearLayout.LayoutParams(FILL, 0, 1f))
        shellFrame.addView(outer, FrameLayout.LayoutParams(FILL, FILL))

        // dark layer behind the panel, tap it to close
        scrim = View(this).apply {
            setBackgroundColor(0xB0000000.toInt())
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        shellFrame.addView(scrim, FrameLayout.LayoutParams(FILL, FILL))

        // the panel with the icons
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF050816.toInt())
                setStroke(dp(1), 0xFF22306B.toInt())
            }
            visibility = View.GONE
            elevation = dp(16).toFloat()
            isClickable = true
        }
        val dh = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(14), dp(14))
        }
        val dLogo = roundIcon(44)
        dLogo.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        applyIcon(dLogo)
        dh.addView(dLogo)
        val dt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dt.addView(tv("LuminFish", 20f, cTx, true))
        dt.addView(tv("Sol’s RNG Macro", 11f, cMut))
        dh.addView(dt, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(12) })
        drawer.addView(dh, LinearLayout.LayoutParams(FILL, HUG))
        drawer.addView(View(this).apply { setBackgroundColor(0xFF1B2552.toInt()) }, LinearLayout.LayoutParams(FILL, dp(1)))

        val box = FrameLayout(this)
        indicator = View(this).apply { background = shape(cAcc, 14) }
        box.addView(indicator, FrameLayout.LayoutParams(FILL, rowH).apply { leftMargin = dp(10); rightMargin = dp(10) })
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(8)) }
        for (i in secNames.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
                setOnClickListener { showSection(i); closeDrawer() }
            }
            val icon = ic(secIcons[i], 22, menuTints[i])
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val lab = tv(secNames[i], 15f, cTx, true).apply { maxLines = 1 }
            row.addView(lab, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(14) })
            press(row)
            menu.addView(row, LinearLayout.LayoutParams(FILL, rowH).apply { topMargin = rowGap })
            menuRows.add(row)
            menuLabels.add(lab)
            menuIcons.add(icon)
        }
        box.addView(menu, FrameLayout.LayoutParams(FILL, HUG))
        menuScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box, FrameLayout.LayoutParams(FILL, HUG)) }
        drawer.addView(menuScroll, LinearLayout.LayoutParams(FILL, 0, 1f))
        shellFrame.addView(drawer, FrameLayout.LayoutParams(drawerW(), FILL))
        return shellFrame
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        val w = drawerW().toFloat()
        drawer.animate().cancel(); scrim.animate().cancel()
        drawer.visibility = View.VISIBLE
        drawer.translationX = -w
        drawer.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(260).start()
        burger.animate().rotation(90f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
        moveIndicator(maxOf(curSec, 0), false)
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val w = drawerW().toFloat()
        drawer.animate().cancel(); scrim.animate().cancel()
        drawer.animate().translationX(-w).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(220).withEndAction { if (!drawerOpen) scrim.visibility = View.GONE }.start()
        burger.animate().rotation(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    private fun moveIndicator(i: Int, animate: Boolean) {
        val y = rowTop(i).toFloat()
        indicator.animate().cancel()
        if (animate) indicator.animate().translationY(y).setDuration(340).setInterpolator(OvershootInterpolator(1.5f)).start()
        else indicator.translationY = y
    }

    private fun stagger(page: LinearLayout) {
        for (j in 0 until page.childCount) {
            val v = page.getChildAt(j)
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(j * 55L).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun showSection(i: Int) {
        if (i == curSec) return
        val prev = curSec
        curSec = i
        for (k in scrolls.indices) {
            val sv = scrolls[k]
            sv.animate().cancel()
            if (k == i) {
                sv.visibility = View.VISIBLE
                sv.scrollTo(0, 0)
                if (prev >= 0) {
                    sv.alpha = 0f
                    sv.translationX = dp(if (i > prev) 30 else -30).toFloat()
                    sv.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
                } else {
                    sv.alpha = 1f
                    sv.translationX = 0f
                }
                stagger(pages[k])
            } else {
                sv.visibility = View.GONE
                sv.alpha = 1f
                sv.translationX = 0f
            }
        }
        for (k in menuLabels.indices) {
            val to = if (k == i) 0xFF0A0A0C.toInt() else cTx
            val l = menuLabels[k]
            val from = l.currentTextColor
            if (from != to) ObjectAnimator.ofArgb(l, "textColor", from, to).apply { duration = 240; start() }
            menuRows[k].animate().translationX(if (k == i) dp(2).toFloat() else 0f).setDuration(240).start()
            val ico = menuIcons[k]
            val fromT = menuTints[k]
            if (fromT != to) {
                ValueAnimator.ofArgb(fromT, to).apply {
                    duration = 240
                    addUpdateListener { ico.setColorFilter(it.animatedValue as Int) }
                    start()
                }
                menuTints[k] = to
            }
        }
        moveIndicator(i, prev >= 0)
        bump(secTv, secNames[i])
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // v2.3: sign in first (Discord / Roblox / guest)
        if (!Auth.isDone(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            return
        }
        // the bars at the top and bottom are black like the app (no blue bar)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        var flags = window.decorView.systemUiVisibility
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags

        val shell = buildShell()
        useSection(0)
        add(root, tv("Fishing and selling helper for Sol’s RNG. Not affiliated with Roblox. Macros can break game rules and get accounts banned.", 12f, cMut), 4)

        val st = card(null)
        pillBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
        }
        pillDot = ic(R.drawable.ic_dot, 14, cBad)
        pillBox.addView(pillDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(6) })
        pill = tv("", 13f, cBad, true)
        pillBox.addView(pill, LinearLayout.LayoutParams(HUG, HUG))
        st.addView(pillBox, LinearLayout.LayoutParams(HUG, HUG))
        add(st, tv("State", 11f, cMut), 12)
        stateTv = tv("", 18f, cTx, true)
        add(st, stateTv, 2)

        val r1 = hrow()
        tCatch = tile(r1, R.drawable.ic_fish, cGlow, "Catches", true)
        tFail = tile(r1, R.drawable.ic_cancel, cBad, "Failed", false)
        add(st, r1, 12)
        val r2 = hrow()
        tSell = tile(r2, R.drawable.ic_tag, cWarn, "Sells", true)
        tRun = tile(r2, R.drawable.ic_timer, cAcc, "Run time", false)
        add(st, r2, 8)

        val k1 = hrow()
        kCap = kv(k1, "Capture", true)
        kTaps = kv(k1, "Taps ok / cancelled", false)
        add(st, k1, 14)
        val k2 = hrow()
        kChecks = kv(k2, "Checks per second", true)
        kLat = kv(k2, "Latency", false)
        add(st, k2, 10)
        val k3 = hrow()
        kBite = kv(k3, "Bite time", true)
        kAim = kv(k3, "AI aim", false)
        add(st, k3, 10)

        val fs = card("Fishing statistics", R.drawable.ic_fish)
        statsTv = tv("", 12f, cTx).apply { typeface = Typeface.MONOSPACE }
        add(fs, statsTv, 8)
        add(fs, btn("Reset all-time statistics", cBd, R.drawable.ic_cancel) {
            Stats.reset(this)
            Toast.makeText(this, "All-time statistics reset", Toast.LENGTH_SHORT).show()
        }, 10)

        svcBtn = btn("Turn on service", cAcc, R.drawable.ic_power) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row(st,
            svcBtn,
            btn("Turn off service", cBd, R.drawable.ic_stop) {
                val s = MacroService.inst
                if (s != null) s.turnOff() else Toast.makeText(this, "The service is already off", Toast.LENGTH_SHORT).show()
            })
        add(st, btn("Open Roblox", cBd, R.drawable.ic_open) {
            val i = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
            if (i != null) startActivity(i) else Toast.makeText(this, "Roblox is not installed", Toast.LENGTH_SHORT).show()
        }, 8)

        val pc = card("Button positions", R.drawable.ic_target)
        posStatus = tv("", 12f, cMut)
        add(pc, posStatus, 8)
        add(pc, btn("Show auto clickers", cBd, R.drawable.ic_target) {
            val s = MacroService.inst
            if (s != null) {
                s.showClickers()
                Toast.makeText(this, "Open Roblox, drag each circle onto its button, then tap DONE", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Turn on the service first", Toast.LENGTH_SHORT).show()
            }
        }, 8)
        for (k in Cfg.keys) {
            val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pr.addView(tv(Cfg.label[k] ?: k, 12f, cMut), LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(10) })
            val v = tv("", 12f, cTx, true)
            pr.addView(v, LinearLayout.LayoutParams(HUG, HUG))
            add(pc, pr, 8)
            posVals[k] = v
        }

        val how = card("How to use", R.drawable.ic_compass)
        step(how, 1, "Turn on the service, then open Roblox. A round LuminFish button appears.")
        step(how, 2, "Tap it to open the panel and tap it again to close it.")
        step(how, 3, "Tap Show auto clickers (panel or this screen). Circles appear: drag each one onto its button (the X is the close button on the caught or failed panel), then tap DONE.")
        step(how, 4, "Optional: start fast capture (Detection page) for the best speed. Android asks for permission. You can also press Start without it.")
        step(how, 5, "Press the big play button or the Toggle to start switch under it. If Start is refused, the panel shows what is missing and you can tap the red row to fix it. Use Test detection while the reeling bar shows.")

        useSection(1)
        val fish = card("Fishing", R.drawable.ic_fish)
        field(fish, "Cast hold (ms)", "cast")
        field(fish, "Stop after N catches (0 = never)", "maxcatch")
        field(fish, "Stop after N minutes of fishing (0 = never)", "maxmin")
        field(fish, "Stop when the battery is below N% and not charging (0 = off)", "minbat")

        useSection(2)
        val mon = card("Live detection", R.drawable.ic_speed)
        monTv = tv("", 12f, cTx).apply { typeface = Typeface.MONOSPACE; background = shape(cBg, 12, cBd); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        add(mon, monTv, 8)
        add(mon, tv("Zone match", 11f, cMut), 10)
        val track = FrameLayout(this).apply { background = shape(cBg, 6, cBd) }
        meter = View(this).apply { background = shape(cOk, 6); pivotX = 0f; scaleX = 0f }
        track.addView(meter, FrameLayout.LayoutParams(FILL, dp(12)))
        add(mon, track, 4)
        add(mon, tv("Updates live while the macro runs. To test: open Roblox with the reeling bar showing and use Test detection in the LuminFish panel (Settings).", 11f, cMut), 8)
        val det = card("Detection (whole screen)", R.drawable.ic_robot)
        sw(det, "AI Autopilot (learns the best aim, recovers when stuck)", "ai")
        add(det, btn("Reset what the AI learned", cBd, R.drawable.ic_trash) {
            Ai.reset(this)
            Toast.makeText(this, "AI memory cleared", Toast.LENGTH_SHORT).show()
        }, 8)
        field(det, "AI: seconds without progress before it recovers", "stuck")
        field(det, "Tap press length (ms)", "taphold")
        sw(det, "Animations (sign-in screen, tap circles, click circles)", "anim")
        sw(det, "Find the Fish / Exit / X buttons on the screen (picture recognition, needs fast capture)", "scene")
        sw(det, "Any zone colour (the green zone can change colour)", "anyzone")
        sw(det, "Show small circles where the macro taps", "tapdots")
        sw(det, "One tap when the blue fill reaches the left edge of the zone (script-like, default)", "edgetap")
        sw(det, "Save a picture of the screen when a button is not recognised (Android/data/com.solmacro/files/debug)", "savepics")
        sw(det, "Learn the Fish / Exit buttons from my own screen", "learnpics")
        sw(det, "Debug overlay (green, yellow, red box)", "debug")
        field(det, "Minimum bar size (pixels)", "minpx")
        field(det, "Max seconds to wait for the bar", "maxwait")
        field(det, "Time between reel taps (ms)", "tapms")
        field(det, "Close button X (caught or failed panel)", "dismiss_x")
        field(det, "Close button Y (caught or failed panel)", "dismiss_y")
        sw(det, "Precision tap engine (tracks every tap until its lift is seen; needs fast capture)", "tapeng")
        sw(det, "Timed tapping, older engine (tap only when the marker is about to leave the zone)", "tapmode")
        cycleBtn(det, "tapprec", intArrayOf(0, 1, 2)) { "Tap precision: " + (if (it == 0) "Normal (safe)" else if (it == 1) "High" else "Max (tight)") }
        sw(det, "Learn the real drain speed while reeling", "learn")
        sw(det, "Widen the color match after a missed bar", "widen")
        add(det, tv("Timing", 14f, cTx, true), 14)
        field(det, "Result panel wait (ms)", "resultms")
        field(det, "Wait after closing the result (ms)", "dismisswait")
        sw(det, "Adaptive recast time (learns how long bites take)", "adapt")
        field(det, "Wait after casting before looking (ms)", "castdelay")
        field(det, "Bar counts as finished after (ms)", "gonems")
        field(det, "Pause after a catch (ms)", "postcatch")

        useSection(7)
        val fl = card("Floating button", R.drawable.ic_image)
        add(fl, tv("A round button stays on screen over Roblox. Tap it to open the control bar and tap it again to close it. Drag it to move it.", 12f, cMut), 6)
        prevIcon = roundIcon(72)
        applyIcon(prevIcon)
        fl.addView(prevIcon)
        row(fl, btn("Pick an icon", cAcc, R.drawable.ic_image) { pickIcon() }, btn("Default icon", cBd, R.drawable.ic_repeat) { resetIcon() })

        val pb = card("Panel background", R.drawable.ic_palette)
        add(pb, tv("Pick a photo or a GIF for the back of the control panel. GIFs move on Android 9 and newer.", 12f, cMut), 6)
        bgPrev = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(0xFF050818.toInt(), 14, cLine)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        pb.addView(bgPrev, LinearLayout.LayoutParams(FILL, dp(130)).apply { topMargin = dp(10) })
        showBgPreview()
        row(pb, btn("Choose photo or GIF", cAcc, R.drawable.ic_image) { pickBg() }, btn("No background", cBd, R.drawable.ic_cancel) { PanelBg.clear(this); showBgPreview() })
        cycleBtn(pb, "bg_dim", intArrayOf(15, 30, 45, 60, 75)) { "Darken background: $it%" }
        cycleBtn(pb, "psize", intArrayOf(0, 1, 2)) { "Panel size: " + (if (it == 0) "Small" else if (it == 1) "Normal" else "Big") }

        useSection(2)
        val cap = card("Fast screen capture (optional)", R.drawable.ic_play)
        add(cap, tv("Optional. Without it LuminFish uses screenshots (about 3 a second) and predicts the marker between them. With it the screen is read about 30 times a second. Android asks for permission each time you start it.", 12f, cMut), 6)
        add(cap, btn("Start fast capture", cGlow, R.drawable.ic_play) { requestCapture() }, 12)
        add(cap, btn("Stop fast capture", cBd, R.drawable.ic_stop) { Cap.release(); stopService(Intent(this, CaptureService::class.java)) }, 8)

        useSection(3)
        val mv = card("Movement, camera and Shift Lock", R.drawable.ic_compass)
        add(mv, tv("Scripts run with swipes on Roblox's on-screen joystick and camera area. One command per line:\nmove up 1.5   (also down, left, right, w, a, s, d)\ncamera left 250\nshiftlock\ntap inv\nwait 1\n# comment", 12f, cMut), 6)
        field(mv, "Joystick push distance (pixels)", "joyr")
        script(mv, "Run once when you press START", "script_start")
        script(mv, "Run before selling (walk to the seller)", "script_pre")
        script(mv, "Run after selling (walk back)", "script_post")

        useSection(1)
        val sell = card("Selling", R.drawable.ic_tag)
        sw(sell, "Auto sell", "sell")
        field(sell, "Sell every N catches", "every")
        field(sell, "Menu delay (ms)", "mdelay")
        field(sell, "Sell trigger: 0 = every N catches, 1 = every N minutes, 2 = whichever comes first", "sellmode")
        field(sell, "Sell every N minutes (used by trigger 1 and 2)", "sellmin")
        sw(sell, "Tap Confirm after Sell all", "sellconf")

        val rel = card("Reliability", R.drawable.ic_timer)
        sw(rel, "Smart timing (learns the bite time, saves battery while waiting)", "smart")
        sw(rel, "Click recovery (resumes clicking when taps stop working)", "recover")
        sw(rel, "Anti-freeze (recovers when the game stops moving)", "freeze")
        field(rel, "Freeze limit (seconds without any screen change)", "freezesec")
        sw(rel, "Verify catches (count a catch only after the result panel is seen)", "verify")

        useSection(5)
        val wh = card("Discord webhook", R.drawable.ic_send)
        sw(wh, "Send webhook messages", "whon")
        linkField(wh, "Webhook URLs (one per line, you can add several channels)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        field(wh, "Progress message every N catches (0 = off)", "whevery")
        add(wh, btn("Send test message", cAcc, R.drawable.ic_send) {
            Hook.send(this, "Test", "LuminFish webhook works", 0x38D39F)
            Toast.makeText(this, "Sent, check the log below", Toast.LENGTH_SHORT).show()
        }, 12)

        val ba = card("Biome alerts", R.drawable.ic_chat)
        add(ba, tv("Pick which biomes send a Discord message. The message copies the SolsScope biome alert: a heading, your private server link (Rejoin settings), then an embed with the biome icon, Channel, Time, Jump to Message and a small footer.", 12f, cMut), 4)
        linkField(ba, "Webhook URL for biome alerts (same list as the Discord webhook card)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        linkField(ba, "Private server link (shown under the biome heading)", "pslink",
            "https://www.roblox.com/share?code=...&type=Server", false) { serverLinkCheck(it) }
        biomeChips(ba)
        sw(ba, "Also send a message when a biome ends", "bioend")
        textField(ba, "Footer text (empty = Message <@your ID> for any bugs)", "whfooter", "")
        add(ba, btn("Send test biome message", cAcc, R.drawable.ic_send) {
            Hook.biomeTest(this)
            Toast.makeText(this, "Sent a sample RAINY message, check your channel", Toast.LENGTH_SHORT).show()
        }, 12)
        sw(ba, "Status report to the webhooks", "statuson")
        field(ba, "Status report every N minutes (minimum 5)", "statusmin")

        useSection(4)
        val ex = card("Anti-kick and screenshots", R.drawable.ic_gear)
        sw(ex, "Anti-kick (small camera move when nothing was tapped)", "afk")
        field(ex, "Anti-kick after N minutes without taps", "afkmin")
        sw(ex, "Send a screenshot to the webhooks (needs fast capture)", "shoton")
        field(ex, "Screenshot every N minutes", "shotmin")

        useSection(4)
        val bw = card("Biome watch (reads the screen text)", R.drawable.ic_eye)
        add(bw, tv("Reads the biome name from the screen and sends the alert you set up in Discord > Biome alerts when it changes. Works with or without fast capture (without it Android 11 or newer is needed). Set it up in the LuminFish panel while Roblox is open: Settings > Set biome area, tap the top-left and bottom-right corner of the biome name, then Test biome read to see what it reads.", 12f, cMut), 4)
        sw(bw, "Biome watch", "bio")
        field(bw, "Check every N seconds (minimum 1, lower = faster alerts)", "biosec")
        bioTv = tv("", 12f, cTx)
        add(bw, bioTv, 8)

        useSection(6)
        val rj = card("Auto rejoin", R.drawable.ic_repeat)
        add(rj, tv("If Roblox closes, crashes or shows a disconnect screen, the macro opens Roblox again (and your private server link if you add one) and carries on. Needs fast capture for the disconnect screen check.", 12f, cMut), 6)
        sw(rj, "Auto rejoin", "rejoin")
        linkField(rj, "Private server link (optional)", "pslink",
            "https://www.roblox.com/games/...?privateServerLinkCode=...", false) { serverLinkCheck(it) }
        field(rj, "Seconds to wait for Roblox to load", "rejoinsec")

        useSection(4)
        val ww = card("Word watch (merchant, auras, anything)", R.drawable.ic_eye)
        add(ww, tv("Reads the screen text and sends a webhook message with a picture when one of your words shows up, for example Mari, Jester or an aura name. Set the area in the panel: Settings > Set watch area (leave it unset to read the whole screen, slower). Reading can be wrong. The same word is only reported once every 5 minutes.", 12f, cMut), 6)
        sw(ww, "Word watch", "watch")
        textField(ww, "Words (separated by commas)", "watchwords", "mari, jester")
        field(ww, "Check every N seconds (minimum 3)", "watchsec")
        sw(ww, "Ping me when a word is seen", "pingwatch")
        sw(ww, "Ping me on rare biomes (needs biome watch)", "pingrare")
        textField(ww, "Discord user ID to ping (type & before a role ID)", "pingid", "123456789012345678")

        useSection(3)
        val tk = card("Timed tasks (auto craft, buy, biome items)", R.drawable.ic_timer)
        add(tk, tv("Runs your script every N minutes between casts. Use the same commands as above, plus: tap p1 (extra points P1 to P6, set in the panel: Settings > Set extra points) or tap 540 1200 (screen pixels). Example for a craft: tap p1, wait 1, tap p2, wait 1.", 12f, cMut), 6)
        for (n in 1..3) {
            sw(tk, "Task $n on", "t" + n + "on")
            field(tk, "Task $n every N minutes", "t" + n + "min")
            script(tk, "Task $n script", "t" + n + "s")
        }

        useSection(8)
        val lg = card("Log", R.drawable.ic_log)
        logTv = tv("", 11f, cMut).apply { typeface = Typeface.MONOSPACE; background = shape(cBg, 12, cBd); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        add(lg, logTv, 8)
        add(lg, btn("Copy log", cBd, R.drawable.ic_copy) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LuminFish log", Dbg.tail(150)))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }, 10)

        if (intent.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
        val frame = FrameLayout(this)
        frame.setBackgroundColor(cBg)
        frame.addView(StarField(this, 60), FrameLayout.LayoutParams(FILL, FILL))
        frame.addView(shell, FrameLayout.LayoutParams(FILL, FILL))
        setContentView(frame)
        showSection(0)
    }

    // the panel asked for fast capture while this screen was already open
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::accountChip.isInitialized) refreshChip()
        handler.post(tick)
        logoAnim?.cancel()
        logoAnim = ObjectAnimator.ofFloat(logo, "rotation", -4f, 4f).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        logoAnim?.cancel()
        logoAnim = null
    }

    private fun refresh() {
        val on = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        bump(pill, if (on) "Service ON" else "Service OFF")
        pillBox.background = shape(if (on) 0xFF10352A.toInt() else 0xFF3A1A1E.toInt(), 20, if (on) 0x6638D39F else 0x66FF6B6B)
        pill.setTextColor(if (on) cOk else cBad)
        pillDot.setColorFilter(if (on) cOk else cBad)
        bump(stateTv, Dbg.state)
        val run = if (Dbg.startMs > 0 && Dbg.state != "Idle") (SystemClock.uptimeMillis() - Dbg.startMs) / 1000 else 0L
        bump(tCatch, Dbg.catches.toString())
        bump(tFail, Dbg.fails.toString())
        bump(tSell, Dbg.sells.toString())
        tRun.setIfChanged("${run / 60}:${(run % 60).toString().padStart(2, '0')}")
        kCap.setIfChanged(if (Cap.active) "Fast capture" else "Screenshots")
        kTaps.setIfChanged("${Dbg.tapOk} / ${Dbg.tapCancel}")
        kChecks.setIfChanged(Dbg.fps.toString())
        kLat.setIfChanged("${Dbg.latency} ms")
        kBite.setIfChanged("${Dbg.biteMs} ms")
        kAim.setIfChanged(Dbg.aiBias)
        val liveSec = MacroService.inst?.activeSeconds() ?: 0L
        statsTv.setIfChanged(Stats.session(liveSec) + "\n\n" + Stats.allTime(this, liveSec))
        val posOk = Cfg.get(this, "pts_ok") == 1
        posStatus.setIfChanged(if (posOk) "All 9 positions are saved." else "Not set yet. Open Roblox, tap Show auto clickers and drag the circles onto your buttons. The numbers below are only defaults.")
        posStatus.setTextColor(if (posOk) cOk else cWarn)
        for (k in Cfg.keys) posVals[k]?.setIfChanged("${Cfg.get(this, k + "_x")}, ${Cfg.get(this, k + "_y")}")
        logTv.setIfChanged(Dbg.tail(25).ifEmpty { "No log yet" })
        if (curSec == 4) {
            bioTv.setIfChanged("Now: " + (Biome.cur?.title ?: "not read yet") + "\n" + Biome.summary())
        }
        if (curSec == 2) {
            monTv.setIfChanged("Capture   " + (if (Cap.active) "fast" else "screenshots") + "\nChecks/s  ${Dbg.fps}\nLatency   ${Dbg.latency} ms\nBar       " +
                (if (Dbg.barFound) "found" else "not seen") + "\nZone px   ${Dbg.zonePx}\nMarker px ${Dbg.markerPx}\nIn zone   ${Dbg.inZone}%\nTaps      ${Dbg.tapOk} ok, ${Dbg.tapCancel} cancelled\nAI brain  ${Dbg.aiInfo}")
            val pct = minOf(100, Dbg.zonePx * 100 / maxOf(Cfg.get(this, "minpx"), 1))
            meter.animate().scaleX(pct / 100f).setDuration(160).start()
            (meter.background as? GradientDrawable)?.setColor(if (pct >= 100) cOk else if (pct >= 50) cWarn else cBad)
        }
    }
}


===== FILE: app/src/main/java/com/solmacro/Marks.kt =====

package com.solmacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

// v2.3: small circles that show where LuminFish really taps. One see-through window that ignores touches; every tap
// adds a point that fades in under a second. Cheap enough for the 12+ taps per second of the reel.
class MarksView(ctx: Context) : View(ctx) {
    private val xs = IntArray(10)
    private val ys = IntArray(10)
    private val ts = LongArray(10)
    private var n = 0
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val loc = IntArray(2)

    fun add(x: Int, y: Int) {
        synchronized(this) {
            val i = n % xs.size
            xs[i] = x
            ys[i] = y
            ts[i] = SystemClock.uptimeMillis()
            n++
        }
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val now = SystemClock.uptimeMillis()
        var live = false
        synchronized(this) {
            for (i in xs.indices) {
                val age = now - ts[i]
                if (ts[i] == 0L || age > 800L) continue
                live = true
                val k = 1f - age / 800f
                val px = (xs[i] - loc[0]).toFloat()
                val py = (ys[i] - loc[1]).toFloat()
                fill.color = ((90 * k).toInt() shl 24) or 0xFF8A00
                ring.color = ((255 * k).toInt() shl 24) or 0xFFFFFF
                val r = (11f + 9f * (1f - k)) * d      // the circle spreads out while it fades
                c.drawCircle(px, py, r, fill)
                c.drawCircle(px, py, r, ring)
                fill.color = ((255 * k).toInt() shl 24) or 0xFFFFFF
                c.drawCircle(px, py, 2.2f * d, fill)
            }
        }
        if (live) postInvalidateOnAnimation()
    }
}


===== FILE: app/src/main/java/com/solmacro/Ocr.kt =====

package com.solmacro

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

// Rebuilt file: reads the text in a picture with ML Kit (on the device, no internet).
// Call it from a background thread. Returns null when reading failed.
object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bmp: Bitmap): String? {
        return try {
            val result = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
            result.text
        } catch (e: Throwable) {
            Dbg.add("Text reading failed: " + e.message)
            null
        }
    }
}


===== FILE: app/src/main/java/com/solmacro/Panel.kt =====

package com.solmacro

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MC = ViewGroup.LayoutParams.MATCH_PARENT

private fun alphaOf(c: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

class Glyph(ctx: Context, private val kind: Int) : View(ctx) {
    var color: Int = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.color = color
        p.style = Paint.Style.FILL
        p.strokeWidth = s * 0.1f
        p.strokeCap = Paint.Cap.ROUND
        when (kind) {
            PLAY -> {
                val t = Path()
                t.moveTo(cx - s * 0.22f, cy - s * 0.32f)
                t.lineTo(cx + s * 0.34f, cy)
                t.lineTo(cx - s * 0.22f, cy + s * 0.32f)
                t.close()
                c.drawPath(t, p)
            }
            BARS -> {
                c.drawRoundRect(RectF(cx - s * 0.38f, cy + s * 0.05f, cx - s * 0.18f, cy + s * 0.38f), 3f, 3f, p)
                c.drawRoundRect(RectF(cx - s * 0.1f, cy - s * 0.12f, cx + s * 0.1f, cy + s * 0.38f), 3f, 3f, p)
                c.drawRoundRect(RectF(cx + s * 0.18f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f), 3f, 3f, p)
            }
            GEAR -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, s * 0.2f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = cx + (Math.cos(a) * s * 0.28).toFloat()
                    val y1 = cy + (Math.sin(a) * s * 0.28).toFloat()
                    val x2 = cx + (Math.cos(a) * s * 0.42).toFloat()
                    val y2 = cy + (Math.sin(a) * s * 0.42).toFloat()
                    c.drawLine(x1, y1, x2, y2, p)
                }
            }
            BOOK -> {
                c.drawRoundRect(RectF(cx - s * 0.4f, cy - s * 0.3f, cx - s * 0.03f, cy + s * 0.32f), 3f, 3f, p)
                c.drawRoundRect(RectF(cx + s * 0.03f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.32f), 3f, 3f, p)
            }
            CLOCK -> {
                p.style = Paint.Style.STROKE
                c.drawCircle(cx, cy, s * 0.38f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.22f, p)
                c.drawLine(cx, cy, cx + s * 0.16f, cy + s * 0.08f, p)
            }
            STAR -> {
                val t = Path()
                t.moveTo(cx, cy - s * 0.45f)
                t.quadTo(cx, cy, cx + s * 0.45f, cy)
                t.quadTo(cx, cy, cx, cy + s * 0.45f)
                t.quadTo(cx, cy, cx - s * 0.45f, cy)
                t.quadTo(cx, cy, cx, cy - s * 0.45f)
                t.close()
                c.drawPath(t, p)
            }
            BOLT -> {
                val t = Path()
                t.moveTo(cx + s * 0.1f, cy - s * 0.45f)
                t.lineTo(cx - s * 0.25f, cy + s * 0.05f)
                t.lineTo(cx, cy + s * 0.05f)
                t.lineTo(cx - s * 0.1f, cy + s * 0.45f)
                t.lineTo(cx + s * 0.25f, cy - s * 0.1f)
                t.lineTo(cx, cy - s * 0.1f)
                t.close()
                c.drawPath(t, p)
            }
            CHEV -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - s * 0.12f, cy - s * 0.22f, cx + s * 0.12f, cy, p)
                c.drawLine(cx + s * 0.12f, cy, cx - s * 0.12f, cy + s * 0.22f, p)
            }
            else -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - s * 0.25f, cy - s * 0.25f, cx + s * 0.25f, cy + s * 0.25f, p)
                c.drawLine(cx + s * 0.25f, cy - s * 0.25f, cx - s * 0.25f, cy + s * 0.25f, p)
            }
        }
    }

    companion object {
        const val PLAY = 0
        const val BARS = 1
        const val GEAR = 2
        const val BOOK = 3
        const val CLOCK = 4
        const val STAR = 5
        const val BOLT = 6
        const val CHEV = 7
        const val CLOSE = 8
    }
}


// Small switch: light blue track with a dark knob when ON, like the LuminFish design.
class Tog(ctx: Context, var on: Boolean, private val change: (Boolean) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()
    private var pos = if (on) 1f else 0f
    private var anim: ValueAnimator? = null

    init {
        setOnClickListener {
            on = !on
            change(on)
            anim?.cancel()
            val a = ValueAnimator.ofFloat(pos, if (on) 1f else 0f)
            a.duration = 240
            a.interpolator = OvershootInterpolator(1.1f)
            a.addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            a.start()
            anim = a
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(3f)).start()
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = pos.coerceIn(0f, 1f)
        val g = h * 0.08f
        if (t > 0.02f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = h * 0.22f
            p.color = alphaOf(Pal.GLOW, (70 * t).toInt())
            c.drawRoundRect(RectF(g, g, w - g, h - g), h / 2, h / 2, p)
        }
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(t, 0xFF141C42.toInt(), 0xFFB4C8FF.toInt()) as Int
        c.drawRoundRect(RectF(g, g, w - g, h - g), h / 2, h / 2, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.07f
        p.color = argb.evaluate(t, 0x884A5FB0.toInt(), 0xFFE6EDFF.toInt()) as Int
        c.drawRoundRect(RectF(g, g, w - g, h - g), h / 2, h / 2, p)
        val kx = h / 2 + (w - h) * t
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(t, 0xFF8A99D8.toInt(), 0xFF0B1030.toInt()) as Int
        c.drawCircle(kx, h / 2, h * 0.34f, p)
        if (t > 0.02f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = h * 0.05f
            p.color = alphaOf(Pal.ICE, (200 * t).toInt())
            c.drawCircle(kx, h / 2, h * 0.34f, p)
        }
    }
}

// The big "Toggle to start" switch. The knob slides, the label changes, a light sweeps across it while idle.
class StartToggle(ctx: Context) : View(ctx) {
    var on = false
        set(v) {
            if (field == v) return
            field = v
            slideTo(if (v) 1f else 0f)
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()
    private val rect = RectF()
    private val tri = Path()
    private val mat = Matrix()
    private var sweep: LinearGradient? = null
    private var pos = 0f
    private var press = 0f
    private var breathe = 0f
    private var anim: ValueAnimator? = null
    private var loop: ValueAnimator? = null

    init { isClickable = true }

    private fun slideTo(t: Float) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(pos, t)
        a.duration = 330
        a.interpolator = OvershootInterpolator(1.2f)
        a.addUpdateListener {
            pos = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    // moves the knob right away when tapped; refresh() puts it back if Start was refused
    fun optimistic() { slideTo(if (on) 0f else 1f) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = LinearGradient(0f, 0f, w * 0.4f, 0f, intArrayOf(0x00FFFFFF, 0x40BCD0FF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 0f
        translationY = 24f
        animate().alpha(1f).translationY(0f).setStartDelay(160).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        loop?.cancel()
        val l = ValueAnimator.ofFloat(0f, 1f)
        l.duration = 1800
        l.repeatCount = ValueAnimator.INFINITE
        l.interpolator = LinearInterpolator()
        l.addUpdateListener {
            breathe = ((Math.sin(SystemClock.uptimeMillis() / 380.0) + 1.0) / 2.0).toFloat()
            invalidate()
        }
        l.start()
        loop = l
    }

    override fun onDetachedFromWindow() {
        loop?.cancel()
        anim?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { press = 1f; animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).setInterpolator(DecelerateInterpolator()).start() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                press = 0f
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = pos.coerceIn(0f, 1f)
        val pad = h * 0.12f
        val kr = h / 2 - pad
        val g = h * 0.04f
        // glow around the track
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.12f
        p.color = alphaOf(if (t > 0.5f) Pal.OK else Pal.GLOW, (25 + 60 * breathe).toInt())
        rect.set(g, g, w - g, h - g)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        // track
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(t, 0xFF0B1236.toInt(), 0xFF10382E.toInt()) as Int
        c.drawRoundRect(rect, h / 2, h / 2, p)
        // light sweep
        val sw = sweep
        if (sw != null && t < 0.5f) {
            val x = -w * 0.4f + ((SystemClock.uptimeMillis() % 2600L) / 2600f) * w * 1.4f
            mat.setTranslate(x, 0f)
            sw.setLocalMatrix(mat)
            sweepP.shader = sw
            c.save()
            val clip = Path()
            clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
            c.clipPath(clip)
            c.drawRect(0f, 0f, w, h, sweepP)
            c.restore()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.05f
        p.color = argb.evaluate(t, 0xCC8FB4FF.toInt(), 0xDD38D39F.toInt()) as Int
        c.drawRoundRect(rect, h / 2, h / 2, p)
        // knob
        val kx = pad + kr + (w - 2 * pad - 2 * kr) * t
        val ks = 1f - 0.08f * press
        p.style = Paint.Style.FILL
        p.color = Pal.ICE
        c.drawCircle(kx, h / 2, kr * ks, p)
        p.color = 0xFF0A1030.toInt()
        if (t < 0.999f) {
            p.alpha = (255 * (1f - t)).toInt()
            tri.reset()
            tri.moveTo(kx - kr * 0.28f, h / 2 - kr * 0.42f)
            tri.lineTo(kx + kr * 0.46f, h / 2)
            tri.lineTo(kx - kr * 0.28f, h / 2 + kr * 0.42f)
            tri.close()
            c.drawPath(tri, p)
        }
        if (t > 0.001f) {
            p.color = 0xFF0A1030.toInt()
            p.alpha = (255 * t).toInt()
            c.drawRoundRect(RectF(kx - kr * 0.32f, h / 2 - kr * 0.32f, kx + kr * 0.32f, h / 2 + kr * 0.32f), kr * 0.08f, kr * 0.08f, p)
        }
        val label = if (t >= 0.5f) "Toggle to stop" else "Toggle to start"
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = h * 0.34f
        p.textAlign = Paint.Align.CENTER
        val a = Math.abs(t - 0.5f) * 2f
        p.color = Pal.ICE
        p.alpha = (255 * a).toInt()
        val rightC = (kx + kr + w) / 2f
        val leftC = (kx - kr) / 2f
        val tx = if (t < 0.5f) rightC else leftC
        c.drawText(label, tx, h / 2 - (p.descent() + p.ascent()) / 2f, p)
        p.alpha = 255
        p.textAlign = Paint.Align.LEFT
    }
}

// Little bar chart: catches in each of the last minutes. Bars grow smoothly when new numbers arrive.
class Spark(ctx: Context) : View(ctx) {
    private var data = IntArray(20)
    private var shown = FloatArray(20)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null

    fun set(d: IntArray) {
        if (d.size != data.size || d.contentEquals(data)) return
        val from = shown.copyOf()
        data = d.copyOf()
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 500
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            for (i in shown.indices) shown[i] = from[i] + (data[i] - from[i]) * f
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val n = shown.size
        val slot = w / n
        var mx = 1f
        for (v in shown) if (v > mx) mx = v
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val bh = maxOf(3f, (h - 4f) * shown[i] / mx)
            p.color = if (i == n - 1) Pal.GLOW else 0x4D8FB4FF
            c.drawRoundRect(RectF(i * slot + slot * 0.15f, h - bh, (i + 1) * slot - slot * 0.15f, h), 4f, 4f, p)
        }
    }
}

// The big emblem in the middle of the Macro page: it is the start button too. Glow, orbiting planet,
// twinkling stars, a ring that spins while the macro runs, ripple when tapped.
class PlayBtn(ctx: Context) : View(ctx) {
    // 0 = idle, 1 = running
    var mode = 0
        set(v) {
            if (field == v) return
            field = v
            startRipple()
        }
    var paused = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spk = Path()
    private val argb = ArgbEvaluator()
    private val emblem: Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, R.drawable.lumin_emblem, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) { null }

    private var angle = 0f
    private var spd = 30f
    private var lastT = 0L
    private var tint = Pal.GLOW
    private var ripple = 0f
    private var press = 0f
    private var loop: ValueAnimator? = null
    private var rippleAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    private fun startRipple() {
        rippleAnim?.cancel()
        val a = ValueAnimator.ofFloat(0.001f, 1f)
        a.duration = 700
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            ripple = it.animatedValue as Float
            if (ripple >= 1f) ripple = 0f
            invalidate()
        }
        a.start()
        rippleAnim = a
    }

    private fun pressTo(target: Float) {
        pressAnim?.cancel()
        val a = ValueAnimator.ofFloat(press, target)
        if (target > 0f) {
            a.duration = 110
            a.interpolator = DecelerateInterpolator()
        } else {
            a.duration = 420
            a.interpolator = OvershootInterpolator(3f)
        }
        a.addUpdateListener {
            press = it.animatedValue as Float
            invalidate()
        }
        a.start()
        pressAnim = a
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressTo(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(0f)
        }
        return super.onTouchEvent(e)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 0f
        scaleX = 0.7f
        scaleY = 0.7f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
        lastT = SystemClock.uptimeMillis()
        loop?.cancel()
        val l = ValueAnimator.ofFloat(0f, 1f)
        l.duration = 1000
        l.repeatCount = ValueAnimator.INFINITE
        l.interpolator = LinearInterpolator()
        l.addUpdateListener {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastT).coerceIn(0L, 100L)) / 1000f
            lastT = now
            val targetSpd = if (mode == 1) (if (paused) 0f else 150f) else 30f
            spd += (targetSpd - spd) * minOf(1f, dt * 4f)
            angle = (angle + spd * dt) % 360f
            val targetCol = if (mode == 1) (if (paused) Pal.WARN else Pal.OK) else Pal.GLOW
            tint = argb.evaluate(minOf(1f, dt * 5f), tint, targetCol) as Int
            invalidate()
        }
        l.start()
        loop = l
    }

    override fun onDetachedFromWindow() {
        loop?.cancel()
        rippleAnim?.cancel()
        pressAnim?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun spark(c: Canvas, x: Float, y: Float, s: Float, a: Float) {
        spk.reset()
        spk.moveTo(x, y - s)
        spk.quadTo(x, y, x + s, y)
        spk.quadTo(x, y, x, y + s)
        spk.quadTo(x, y, x - s, y)
        spk.quadTo(x, y, x, y - s)
        spk.close()
        p.style = Paint.Style.FILL
        p.color = alphaOf(Color.WHITE, (255 * a).toInt())
        c.drawPath(spk, p)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.5f
        val now = SystemClock.uptimeMillis()
        val breath = ((Math.sin(now / 650.0) + 1.0) / 2.0).toFloat()
        val r = base * (1f - 0.06f * press)
        // breathing glow
        p.style = Paint.Style.FILL
        p.color = alphaOf(tint, (16 + 34 * breath).toInt())
        c.drawCircle(cx, cy, r * (0.95f + 0.1f * breath), p)
        // emblem
        val em = emblem
        if (em != null) {
            val ew = minOf(width * 0.96f, r * 2f * em.width / em.height * 0.98f)
            val eh = ew * em.height / em.width
            val sc = 1f + 0.025f * breath - 0.05f * press
            c.save()
            c.rotate(1.6f * Math.sin(now / 1100.0).toFloat(), cx, cy)
            c.scale(sc, sc, cx, cy)
            c.drawBitmap(em, null, RectF(cx - ew / 2, cy - eh / 2, cx + ew / 2, cy + eh / 2), bmpP)
            c.restore()
        }
        // orbiting planet on a tilted ring
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = 0x558FB4FF
        c.save()
        c.rotate(-18f, cx, cy)
        val ra = r * 1.55f
        val rb = r * 0.5f
        c.drawOval(RectF(cx - ra, cy - rb, cx + ra, cy + rb), p)
        val t = Math.toRadians(angle.toDouble())
        val px = cx + (Math.cos(t) * ra).toFloat()
        val py = cy + (Math.sin(t) * rb).toFloat()
        p.style = Paint.Style.FILL
        p.color = alphaOf(tint, 90)
        c.drawCircle(px, py, r * 0.12f, p)
        p.color = Color.WHITE
        c.drawCircle(px, py, r * 0.06f, p)
        c.restore()
        // twinkling sparkles
        val a1 = ((Math.sin(now / 420.0) + 1.0) / 2.0).toFloat()
        val a2 = ((Math.sin(now / 530.0 + 2.0) + 1.0) / 2.0).toFloat()
        val a3 = ((Math.sin(now / 610.0 + 4.0) + 1.0) / 2.0).toFloat()
        spark(c, cx - r * 1.45f, cy - r * 0.55f, r * (0.08f + 0.09f * a1), 0.25f + 0.75f * a1)
        spark(c, cx + r * 1.45f, cy + r * 0.5f, r * (0.07f + 0.09f * a2), 0.25f + 0.75f * a2)
        spark(c, cx + r * 0.5f, cy - r * 0.85f, r * (0.05f + 0.07f * a3), 0.25f + 0.75f * a3)
        // spinning arc while running
        if (mode == 1) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.07f
            p.strokeCap = Paint.Cap.ROUND
            p.color = alphaOf(tint, 235)
            val rr = r * 0.82f
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), angle * 2f, 80f, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        // tap ripple
        if (ripple > 0f) {
            p.style = Paint.Style.STROKE
            val rad = r * 0.5f + (r * 1.4f) * ripple
            p.strokeWidth = r * 0.06f * (1f - ripple) + 1f
            p.color = alphaOf(tint, (170 * (1f - ripple)).toInt())
            c.drawCircle(cx, cy, rad, p)
        }
    }
}

class PanelUi(private val svc: MacroService, private val widthPx: Int, private val heightPx: Int) {
    private val ctx: Context = svc
    private val d = ctx.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    val root = FrameLayout(ctx)
    private val narrow = widthPx < (500 * d)
    private val pages = ArrayList<View>()
    private val navRows = ArrayList<LinearLayout>()
    private val navGlyphs = ArrayList<Glyph>()
    private val navTexts = ArrayList<TextView>()
    private var hint: ObjectAnimator? = null
    private var dotAnim: ObjectAnimator? = null
    private var noticeUntil = 0L
    private var firstPage = true
    private var curPage = 0
    private val checkRows = ArrayList<TextView>()
    private val checkState = IntArray(3) { -1 }
    private val introViews = ArrayList<View>()
    private lateinit var checkBox: LinearLayout
    private lateinit var toggle: StartToggle
    private lateinit var spark: Spark

    private lateinit var pill: TextView
    private lateinit var dot: View
    private lateinit var play: PlayBtn
    private lateinit var tapT: TextView
    private lateinit var tapS: TextView
    private lateinit var pauseB: TextView
    private lateinit var vCatch: TextView
    private lateinit var vTime: TextView
    private lateinit var vState: TextView
    private lateinit var statsText: TextView
    private lateinit var capVal: TextView
    private lateinit var tapVal: TextView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    private val muted = Pal.MUTED
    private val line = Pal.LINE

    private fun dp(v: Int) = (v * d).toInt()

    // press squish for anything tappable (returns false so the normal click still happens)
    private fun <T : View> fx(v: T): T {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
        return v
    }

    // changes a text and gives it a little pop when it is different
    private fun bump(t: TextView, text: String) {
        if (t.text.toString() == text) return
        t.text = text
        t.scaleX = 1.22f
        t.scaleY = 1.22f
        t.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun fadeColor(t: TextView, to: Int) {
        val from = t.currentTextColor
        if (from == to) return
        ObjectAnimator.ofArgb(t, "textColor", from, to).apply {
            duration = 260
            start()
        }
    }

    private fun shake(v: View) {
        val s = dp(10).toFloat()
        ObjectAnimator.ofFloat(v, "translationX", 0f, -s, s, -s * 0.8f, s * 0.8f, -s * 0.4f, 0f).apply {
            duration = 400
            start()
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun lin(o: Int) = LinearLayout(ctx).apply { orientation = o }
    private fun flp(w: Int, h: Int) = FrameLayout.LayoutParams(w, h)

    private fun shape(fill: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, sp: Float, color: Int = Pal.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    init {
        val rr = 22f * d
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rr) }
        }
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.PANEL_TOP, Pal.PANEL_BOT))
        // the photo or GIF the user picked in the app (Look > Panel background)
        val bgd = PanelBg.load(ctx, 1280)
        if (bgd != null) {
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageDrawable(bgd)
            root.addView(iv, flp(MC, MC))
            val dim = View(ctx)
            dim.setBackgroundColor(Color.argb((Cfg.get(ctx, "bg_dim").coerceIn(0, 90) * 255) / 100, 2, 4, 14))
            root.addView(dim, flp(MC, MC))
        }
        val sf = StarField(ctx, if (bgd != null) 22 else 54)
        sf.strength = if (bgd != null) 0.7f else 1f
        root.addView(sf, flp(MC, MC))

        val col = lin(LinearLayout.VERTICAL)
        col.setPadding(dp(18), dp(15), dp(18), dp(14))
        val head = header()
        col.addView(head, lp(MC, WC))
        val body = lin(LinearLayout.HORIZONTAL)
        val side = sidebar()
        body.addView(side, lp(if (narrow) dp(46) else dp(92), MC))
        val center = FrameLayout(ctx)
        pages.add(scroll(pageMacro()))
        pages.add(scroll(pageStats()))
        pages.add(scroll(pageSettings()))
        pages.add(scroll(pageCredits()))
        for (p in pages) center.addView(p, flp(MC, MC))
        body.addView(center, lp(0, MC, 1f).apply { leftMargin = dp(7) })
        var right: View? = null
        if (!narrow) {
            right = rightColumn()
            body.addView(right, lp(dp(150), MC).apply { leftMargin = dp(7) })
        }
        col.addView(body, lp(MC, 0, 1f).apply { topMargin = dp(3) })
        val sb = statsBar()
        col.addView(sb, lp(MC, dp(40)).apply { topMargin = dp(6) })
        root.addView(col, flp(MC, MC))
        val gf = GlowFrame(ctx)
        gf.radius = 22f
        root.addView(gf, flp(MC, MC))
        introViews.add(head)
        introViews.add(side)
        introViews.add(center)
        if (right != null) introViews.add(right)
        introViews.add(sb)
        showPage(0)
        refresh()
    }

    private fun scroll(v: View): ScrollView = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(12))
        setPadding(0, 0, 0, dp(6))
        clipToPadding = false
        addView(v, ViewGroup.LayoutParams(MC, WC))
    }

    // the parts slide in one after another when the panel opens
    private fun intro() {
        for ((i, v) in introViews.withIndex()) {
            v.alpha = 0f
            v.translationY = dp(14).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 70L).setDuration(380)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun start() {
        intro()
        ui.post(tick)
        dotAnim?.cancel()
        dotAnim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    fun stop() {
        ui.removeCallbacks(tick)
        hint?.cancel()
        hint = null
        dotAnim?.cancel()
        dotAnim = null
    }

    private fun loadLogo(v: ImageView) {
        val f = java.io.File(ctx.filesDir, "float_icon.png")
        if (Cfg.get(ctx, "icon_custom") == 1 && f.exists()) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    private fun circleBtn(kind: Int, click: () -> Unit): View {
        val f = FrameLayout(ctx)
        f.background = shape(0xFF0C1330.toInt(), 15, 0x668FB4FF)
        val g = Glyph(ctx, kind)
        g.color = Pal.ICE
        f.addView(g, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        f.setOnClickListener { click() }
        return fx(f)
    }

    private fun header(): View {
        val h = lin(LinearLayout.HORIZONTAL)
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(dp(2), dp(0), dp(2), dp(4))
        val logo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(0xFF050818.toInt(), 11, 0x888FB4FF.toInt())
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadLogo(logo)
        h.addView(logo, lp(dp(38), dp(38)))
        val titles = lin(LinearLayout.VERTICAL)
        val nameRow = lin(LinearLayout.HORIZONTAL)
        nameRow.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(tv("LuminFish", 17f, Pal.ICE, true))
        val st = Glyph(ctx, Glyph.STAR)
        st.color = Pal.GLOW
        nameRow.addView(st, lp(dp(12), dp(12)).apply { leftMargin = dp(5) })
        titles.addView(nameRow)
        titles.addView(tv("Sol’s RNG Macro", 10f, muted))
        h.addView(titles, lp(0, WC, 1f).apply { leftMargin = dp(9) })
        val pb = lin(LinearLayout.HORIZONTAL)
        pb.gravity = Gravity.CENTER_VERTICAL
        pb.setPadding(dp(10), dp(5), dp(11), dp(5))
        pb.background = shape(0xFF0A1226.toInt(), 16, 0x668FB4FF)
        dot = View(ctx)
        pb.addView(dot, lp(dp(8), dp(8)))
        pill = tv("Ready", 12f, Pal.TEXT, true)
        pb.addView(pill, lp(WC, WC).apply { leftMargin = dp(6) })
        h.addView(pb)
        h.addView(circleBtn(Glyph.GEAR) { showPage(2) }, lp(dp(30), dp(30)).apply { leftMargin = dp(7) })
        h.addView(circleBtn(Glyph.CLOSE) { svc.closePanel() }, lp(dp(30), dp(30)).apply { leftMargin = dp(7) })
        return h
    }

    private fun selBg() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFF4F7FF.toInt(), 0xFFB4C8FF.toInt())).apply {
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Pal.GLOW)
    }

    private fun sidebar(): View {
        val col = lin(LinearLayout.VERTICAL)
        val names = listOf("Macro", "Stats", "Settings", "Credits")
        val kinds = listOf(Glyph.PLAY, Glyph.BARS, Glyph.GEAR, Glyph.BOOK)
        for (i in names.indices) {
            val row = lin(LinearLayout.HORIZONTAL)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(if (narrow) 0 else 9), dp(6), dp(if (narrow) 0 else 6), dp(6))
            if (narrow) row.gravity = Gravity.CENTER
            val g = Glyph(ctx, kinds[i])
            row.addView(g, lp(dp(18), dp(18)))
            val t = tv(names[i], 12.5f)
            if (!narrow) row.addView(t, lp(WC, WC).apply { leftMargin = dp(7) })
            row.setOnClickListener { showPage(i) }
            fx(row)
            col.addView(row, lp(MC, dp(32)).apply { topMargin = dp(3) })
            navRows.add(row)
            navGlyphs.add(g)
            navTexts.add(t)
        }
        col.addView(View(ctx), lp(MC, 0, 1f))
        if (!narrow) {
            val ver = lin(LinearLayout.VERTICAL)
            ver.background = shape(Pal.CARD, 12, line)
            ver.setPadding(dp(9), dp(6), dp(9), dp(6))
            val vr = lin(LinearLayout.HORIZONTAL)
            vr.gravity = Gravity.CENTER_VERTICAL
            val sg = Glyph(ctx, Glyph.STAR)
            sg.color = Pal.GLOW
            vr.addView(sg, lp(dp(11), dp(11)))
            vr.addView(tv("LuminFish v2.3", 10.5f, Pal.TEXT, true), lp(WC, WC).apply { leftMargin = dp(5) })
            ver.addView(vr)
            ver.addView(tv("Made for Sol’s RNG", 9f, muted))
            col.addView(ver, lp(MC, WC).apply { topMargin = dp(4) })
        }
        return col
    }

    private fun showPage(i: Int) {
        val prev = curPage
        curPage = i
        for (k in pages.indices) {
            val pg = pages[k]
            if (k == i) {
                val wasHidden = pg.visibility != View.VISIBLE
                pg.animate().cancel()
                pg.visibility = View.VISIBLE
                if (wasHidden && !firstPage) {
                    pg.alpha = 0f
                    pg.translationX = dp(22).toFloat()
                    pg.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                } else {
                    pg.alpha = 1f
                    pg.translationX = 0f
                }
            } else {
                pg.animate().cancel()
                pg.visibility = View.GONE
                pg.alpha = 1f
                pg.translationX = 0f
            }
        }
        for (k in navRows.indices) {
            val sel = k == i
            navRows[k].background = if (sel) selBg() else null
            val c = if (sel) 0xFF0A1030.toInt() else Pal.TEXT
            navGlyphs[k].color = c
            navGlyphs[k].invalidate()
            navTexts[k].setTextColor(c)
            if (sel && k != prev && !firstPage) {
                navRows[k].scaleX = 0.9f
                navRows[k].scaleY = 0.9f
                navRows[k].animate().scaleX(1f).scaleY(1f).setDuration(330).setInterpolator(OvershootInterpolator(2.4f)).start()
            }
        }
        firstPage = false
        if (i == 0 || i == 1) refresh()
    }

    private fun titleBar(title: String, kind: Int, both: Boolean): LinearLayout {
        val t = lin(LinearLayout.HORIZONTAL)
        t.gravity = Gravity.CENTER
        t.background = shape(0x2A5F7CFF, 11)
        t.setPadding(dp(8), dp(5), dp(8), dp(5))
        val a = Glyph(ctx, kind)
        a.color = Pal.GLOW
        t.addView(a, lp(dp(14), dp(14)))
        t.addView(tv(title, 12.5f, Pal.GLOW, true), lp(WC, WC).apply { leftMargin = dp(7); rightMargin = if (both) dp(7) else 0 })
        if (both) {
            val b = Glyph(ctx, kind)
            b.color = Pal.GLOW
            t.addView(b, lp(dp(14), dp(14)))
        }
        return t
    }

    private fun card(title: String?, kind: Int = Glyph.STAR, both: Boolean = false): LinearLayout {
        val c = lin(LinearLayout.VERTICAL)
        c.background = shape(Pal.CARD, 16, line)
        c.setPadding(dp(8), dp(7), dp(8), dp(8))
        if (title != null) c.addView(titleBar(title, kind, both), lp(MC, WC))
        return c
    }

    private fun smallBtn(t: String, click: () -> Unit): TextView {
        val b = tv(t, 11.5f, Pal.TEXT, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(12), dp(6), dp(12), dp(6))
        b.background = shape(0xFF0F1838.toInt(), 13, 0x668FB4FF)
        b.setOnClickListener { click() }
        return fx(b)
    }

    private fun pageMacro(): View {
        val c = card("Auto Fish", Glyph.STAR, true)
        c.gravity = Gravity.CENTER_HORIZONTAL
        play = PlayBtn(ctx)
        play.setOnClickListener { tryStart() }
        // the emblem takes whatever height is left so Tap to Start, the buttons and the chips are never cut off
        val availDp = heightPx / d - 120f
        val emblemDp = (availDp - 182f).coerceIn(48f, 92f).toInt()
        c.addView(play, lp(MC, dp(emblemDp)).apply { topMargin = dp(2) })
        tapT = tv("Tap to Start", 15f, Pal.ICE, true)
        c.addView(tapT, lp(WC, WC))
        tapS = tv("Let LuminFish do the work…", 10.5f, muted)
        tapS.gravity = Gravity.CENTER
        c.addView(tapS, lp(WC, WC))
        toggle = StartToggle(ctx)
        toggle.setOnClickListener { tryStart() }
        c.addView(toggle, lp(dp(200), dp(32)).apply { topMargin = dp(6) })
        val rowB = lin(LinearLayout.HORIZONTAL)
        rowB.gravity = Gravity.CENTER
        pauseB = smallBtn("Pause") { svc.togglePause() }
        pauseB.visibility = View.GONE
        rowB.addView(pauseB, lp(WC, WC).apply { rightMargin = dp(6) })
        rowB.addView(smallBtn("Show auto clickers") { svc.closePanel(); svc.showClickers() })
        c.addView(rowB, lp(WC, WC).apply { topMargin = dp(7) })

        // live "ready to start" checks. A red chip says what is stopping Start, tap it to fix it.
        checkBox = lin(LinearLayout.HORIZONTAL)
        val short = listOf("Positions", "Capture", "Roblox")
        for (k in 0..2) {
            val t = tv(short[k], 10.5f, muted, true)
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(8), dp(4), dp(8), dp(4))
            t.setOnClickListener {
                when (k) {
                    0 -> { svc.closePanel(); svc.setPoints() }
                    1 -> svc.openApp(true)
                    else -> svc.openRobloxApp()
                }
            }
            fx(t)
            checkBox.addView(t, lp(WC, WC).apply { if (k > 0) leftMargin = dp(4) })
            checkRows.add(t)
        }
        c.addView(checkBox, lp(WC, WC).apply { topMargin = dp(7) })
        return c
    }

    // Both the emblem and the toggle come here.
    private fun tryStart() {
        val running = svc.isRunning()
        val why = if (running) null else svc.startProblem()
        if (why == null) toggle.optimistic()
        svc.toggle()
        if (why != null) blocked(why)
        ui.postDelayed({ refresh() }, 160)
    }

    // Start was refused: shake, say why in the panel, jump to Settings if the positions are missing.
    private fun blocked(why: String) {
        shake(play)
        shake(toggle)
        tapS.text = why
        tapS.setTextColor(Pal.WARN)
        tapS.alpha = 1f
        noticeUntil = SystemClock.uptimeMillis() + 5000L
        checkBox.scaleX = 1.06f
        checkBox.scaleY = 1.06f
        checkBox.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(OvershootInterpolator(3f)).start()
        if (Cfg.get(ctx, "pts_ok") != 1) { svc.closePanel(); svc.showClickers() }
        else if (!Cap.active) { svc.closePanel(); svc.openApp(true) }
    }

    private fun pageStats(): View {
        val c = card("Stats", Glyph.BARS)
        statsText = tv("", 10.5f, Pal.TEXT)
        statsText.typeface = Typeface.MONOSPACE
        c.addView(statsText, lp(MC, WC).apply { topMargin = dp(6) })
        c.addView(tv("Catches per minute, last 20 minutes", 10f, muted), lp(MC, WC).apply { topMargin = dp(10) })
        spark = Spark(ctx)
        c.addView(spark, lp(MC, dp(56)).apply { topMargin = dp(5) })
        return c
    }

    // a small button that steps through a list of values and saves the choice
    private fun cycle(key: String, opts: IntArray, fmt: (Int) -> String): TextView {
        val t = tv(fmt(Cfg.get(ctx, key)), 11f, Pal.TEXT)
        t.gravity = Gravity.CENTER
        t.background = shape(0xFF0F1838.toInt(), 12, 0x668FB4FF)
        t.setPadding(dp(6), dp(4), dp(6), dp(4))
        t.setOnClickListener {
            val cur = Cfg.get(ctx, key)
            var nxt = opts[0]
            for (k in opts.indices) if (opts[k] == cur && k + 1 < opts.size) nxt = opts[k + 1]
            Cfg.put(ctx, key, nxt)
            t.text = fmt(nxt)
        }
        fx(t)
        return t
    }

    private fun actionRow(label: String, click: () -> Unit): View {
        val r = lin(LinearLayout.HORIZONTAL)
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = shape(0x99101A3E.toInt(), 11, line)
        r.setPadding(dp(10), dp(8), dp(10), dp(8))
        r.addView(tv(label, 11.5f), lp(0, WC, 1f))
        val g = Glyph(ctx, Glyph.CHEV)
        g.color = Pal.GLOW
        r.addView(g, lp(dp(16), dp(16)))
        r.setOnClickListener { click() }
        return fx(r)
    }

    private fun pageSettings(): View {
        val c = card("Settings", Glyph.GEAR)
        if (narrow) c.addView(macroSettings(false), lp(MC, WC).apply { topMargin = dp(6) })
        c.addView(actionRow("Show auto clickers (drag to place buttons)") { svc.closePanel(); svc.showClickers() }, lp(MC, WC).apply { topMargin = dp(6) })
        c.addView(actionRow("Set biome area (for biome watch)") { svc.closePanel(); svc.setBiomeRegion() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Test biome read (shows what it sees)") { svc.closePanel(); svc.testBiome() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Send test biome message") { Hook.biomeTest(ctx); svc.toastPublic("Sent a sample RAINY message") }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Set watch area (words like Mari, Jester, auras)") { svc.closePanel(); svc.setWatchRegion() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Set extra points P1-P6 (for timed tasks)") { svc.closePanel(); svc.setExtraPoints() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Test tap (shows where LuminFish taps)") { svc.closePanel(); svc.testTap() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Test detection") { svc.test() }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Turn on screen capture (needed)") { svc.openApp(true) }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Reset all-time statistics") { Stats.reset(ctx) }, lp(MC, WC).apply { topMargin = dp(5) })
        c.addView(actionRow("Open app settings") { svc.openApp(false) }, lp(MC, WC).apply { topMargin = dp(5) })
        return c
    }

    private fun pageCredits(): View {
        val c = card("Credits", Glyph.BOOK)
        val top = lin(LinearLayout.HORIZONTAL)
        top.gravity = Gravity.CENTER_VERTICAL
        // creator's Roblox avatar, loaded from the internet (shows the logo while offline)
        val av = ImageView(ctx)
        av.scaleType = ImageView.ScaleType.CENTER_CROP
        av.background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0xFF050818.toInt()); setStroke(dp(2), Pal.GLOW) }
        av.clipToOutline = true
        av.outlineProvider = ViewOutlineProvider.BACKGROUND
        val cached = Online.cached(ctx)
        if (cached != null) av.setImageBitmap(cached) else av.setImageResource(R.drawable.brry_icon)
        top.addView(av, lp(dp(46), dp(46)))
        ObjectAnimator.ofFloat(av, "scaleX", 1f, 1.06f).apply { duration = 1400; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start() }
        ObjectAnimator.ofFloat(av, "scaleY", 1f, 1.06f).apply { duration = 1400; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start() }
        Online.refresh(ctx) { bmp -> if (bmp != null) ui.post { av.setImageBitmap(bmp) } }
        val who = lin(LinearLayout.VERTICAL)
        who.addView(tv("LuminFish v2.3", 13f, Pal.ICE, true))
        who.addView(tv("Made for Sol’s RNG.", 11f, muted))
        top.addView(who, lp(WC, WC).apply { leftMargin = dp(10) })
        c.addView(top, lp(MC, WC).apply { topMargin = dp(8) })
        c.addView(tv("Not affiliated with Roblox. Macros can break game rules and get accounts banned.", 11f, muted), lp(MC, WC).apply { topMargin = dp(8) })
        val follow = tv("Follow: chromeplated12301 for more update", 12.5f, Pal.GLOW, true)
        follow.paintFlags = follow.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        follow.setPadding(0, dp(8), 0, dp(8))
        follow.setOnClickListener {
            svc.openUrl("https://www.roblox.com/share?code=9c18d3e4c933e640bdb855d8c451bef2&type=Profile&source=ProfileShare&stamp=1794479961423")
        }
        fx(follow)
        c.addView(follow, lp(WC, WC).apply { topMargin = dp(8) })
        return c
    }

    private fun row(c: LinearLayout, label: String, right: View, rightW: Int = 40) {
        val r = lin(LinearLayout.HORIZONTAL)
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(tv(label, 11.5f), lp(0, WC, 1f).apply { rightMargin = dp(4) })
        r.addView(right, lp(dp(rightW), dp(22)))
        c.addView(r, lp(MC, WC).apply { topMargin = dp(7) })
    }

    private fun tog(key: String) = Tog(ctx, Cfg.get(ctx, key) == 1) { Cfg.put(ctx, key, if (it) 1 else 0) }

    // every setting of the panel. The first seven match the design, all the old ones are still here.
    private fun fillSettings(a: LinearLayout) {
        row(a, "AI Autopilot", tog("ai"))
        row(a, "Auto Sell", tog("sell"))
        row(a, "OpenCV detect", tog("cv"))
        row(a, "Anti-AFK", tog("afk"))
        row(a, "Biome watch", tog("bio"))
        row(a, "Word watch", tog("watch"))
        row(a, "Auto rejoin", tog("rejoin"))
        row(a, "Precision taps", tog("tapeng"))
        row(a, "Timed tapping", tog("tapmode"))
        row(a, "Tap precision", cycle("tapprec", intArrayOf(0, 1, 2)) { if (it == 0) "Normal" else if (it == 1) "High" else "Max" }, 62)
        row(a, "Adaptive speed", tog("adaptfps"))
        row(a, "Animations", tog("anim"))
        row(a, "Find buttons on screen", tog("scene"))
        row(a, "Any zone colour", tog("anyzone"))
        row(a, "Show tap dots", tog("tapdots"))
        row(a, "One tap at zone edge", tog("edgetap"))
        row(a, "Save pictures when unsure", tog("savepics"))
        row(a, "Learn my buttons", tog("learnpics"))
        row(a, "Show auto clickers", Tog(ctx, svc.clickersShown()) { if (it) { svc.closePanel(); svc.showClickers() } else svc.hideClickers() })
        row(a, "Smart timing", tog("smart"))
        row(a, "Click recovery", tog("recover"))
        row(a, "Anti-freeze", tog("freeze"))
        row(a, "Verify catches", tog("verify"))
        row(a, "Sell trigger", cycle("sellmode", intArrayOf(0, 1, 2)) { if (it == 0) "Catches" else if (it == 1) "Time" else "Either" }, 74)
        row(a, "Sell every N catches", cycle("every", intArrayOf(5, 10, 20, 30, 50, 100)) { it.toString() }, 52)
        row(a, "Sell every N min", cycle("sellmin", intArrayOf(5, 10, 15, 30, 60)) { it.toString() }, 52)
        row(a, "Tap Confirm after Sell all", tog("sellconf"))
        row(a, "Screenshots", tog("shoton"))
        row(a, "Debug overlay", tog("debug"))
        tapVal = tv("", 11f, Pal.TEXT)
        tapVal.gravity = Gravity.CENTER
        tapVal.background = shape(0xFF0F1838.toInt(), 12, 0x668FB4FF)
        tapVal.setOnClickListener {
            val opts = intArrayOf(50, 70, 90, 120)
            val cur = Cfg.get(ctx, "tapms")
            var nxt = opts[0]
            for (k in opts.indices) if (opts[k] == cur && k + 1 < opts.size) nxt = opts[k + 1]
            Cfg.put(ctx, "tapms", nxt)
            refresh()
        }
        row(a, "Tap delay", tapVal, 56)
        capVal = tv("", 11f, Pal.TEXT)
        capVal.gravity = Gravity.CENTER
        capVal.background = shape(0xFF0F1838.toInt(), 12, 0x668FB4FF)
        capVal.setOnClickListener { if (!Cap.active) svc.openApp(true) }
        row(a, "Screen capture (needed)", capVal, 62)
    }

    private fun macroSettings(fixed: Boolean): View {
        val inner = lin(LinearLayout.VERTICAL)
        fillSettings(inner)
        if (!fixed) return inner
        val a = card("Macro Settings", Glyph.BOLT)
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.isVerticalFadingEdgeEnabled = true
        sv.setFadingEdgeLength(dp(16))
        sv.addView(inner, ViewGroup.LayoutParams(MC, WC))
        a.addView(sv, lp(MC, 0, 1f))
        return a
    }

    private fun rightColumn(): View = macroSettings(true)

    private fun seg(kind: Int, label: String, value: TextView): View {
        val s = lin(LinearLayout.HORIZONTAL)
        s.gravity = Gravity.CENTER_VERTICAL
        val g = Glyph(ctx, kind)
        g.color = Pal.GLOW
        s.addView(g, lp(dp(18), dp(18)))
        val t = lin(LinearLayout.VERTICAL)
        t.addView(tv(label, 9.5f, muted))
        t.addView(value)
        s.addView(t, lp(WC, WC).apply { leftMargin = dp(7) })
        return s
    }

    private fun statsBar(): View {
        val bar = lin(LinearLayout.HORIZONTAL)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = shape(Pal.CARD, 14, line)
        bar.setPadding(dp(12), dp(3), dp(10), dp(3))
        vCatch = tv("0", 14f, Pal.ICE, true)
        vTime = tv("00:00:00", 14f, Pal.ICE, true)
        vState = tv("Idle", 12f, Pal.ICE, true)
        vState.maxLines = 1
        bar.addView(seg(Glyph.BARS, "Total Catches", vCatch), lp(0, WC, 1f))
        bar.addView(seg(Glyph.CLOCK, "Runtime", vTime), lp(0, WC, 1f))
        bar.addView(seg(Glyph.STAR, "Status", vState), lp(0, WC, 1f))
        return bar
    }

    private fun refresh() {
        val running = svc.isRunning()
        val paused = svc.isPaused()
        val col = if (paused) Pal.WARN else Pal.OK
        dot.background = shape(col, 6)
        bump(pill, if (paused) "Paused" else if (running) "Running" else "Ready")
        val tt = if (running) "Tap to Stop" else "Tap to Start"
        if (tapT.text.toString() != tt) {
            tapT.text = tt
            tapT.translationY = dp(10).toFloat()
            tapT.animate().translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
        }
        if (SystemClock.uptimeMillis() >= noticeUntil) {
            if (noticeUntil != 0L) {
                noticeUntil = 0L
                tapS.setTextColor(muted)
            }
            val ts = if (running) Dbg.state else "Let LuminFish do the work…"
            if (tapS.text.toString() != ts) {
                tapS.text = ts
                tapS.alpha = 0f
                tapS.animate().alpha(1f).setDuration(220).start()
            }
        }
        play.paused = paused
        play.mode = if (running) 1 else 0
        play.invalidate()
        toggle.on = running
        // the checks only show while idle
        if (running) {
            if (checkBox.visibility != View.GONE) checkBox.visibility = View.GONE
        } else {
            if (checkBox.visibility != View.VISIBLE) checkBox.visibility = View.VISIBLE
            val items = svc.checklist()
            for (k in items.indices) {
                val ok = items[k].second
                val t = checkRows[k]
                val neutral = items[k].first.startsWith("~")
                val c2 = if (neutral) Pal.WARN else if (ok) Pal.OK else Pal.BAD
                fadeColor(t, c2)
                val st = if (neutral) 0 else if (ok) 1 else 2
                if (checkState[k] != st) {
                    checkState[k] = st
                    t.background = shape(alphaOf(c2, 0x22), 10, alphaOf(c2, 0x77))
                    val res = if (neutral) R.drawable.ic_dot else if (ok) R.drawable.ic_check else R.drawable.ic_cancel
                    val dr = ctx.getDrawable(res)?.mutate()
                    if (dr != null) {
                        dr.setTint(c2)
                        dr.setBounds(0, 0, dp(12), dp(12))
                        t.setCompoundDrawablesRelative(dr, null, null, null)
                        t.compoundDrawablePadding = dp(5)
                    }
                }
            }
        }
        pauseB.visibility = if (running) View.VISIBLE else View.GONE
        pauseB.text = if (paused) "Resume" else "Pause"
        if (!running && hint == null) {
            hint = ObjectAnimator.ofFloat(tapT, "alpha", 1f, 0.5f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        } else if (running && hint != null) {
            hint?.cancel()
            hint = null
            tapT.alpha = 1f
        }
        bump(vCatch, Dbg.catches.toString())
        val sec = if (Dbg.startMs > 0 && running) (android.os.SystemClock.uptimeMillis() - Dbg.startMs) / 1000 else 0L
        vTime.text = String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
        bump(vState, if (running) Dbg.state else "Idle")
        if (curPage == 1) {
            spark.set(Dbg.perMinute(20))
            val live = svc.activeSeconds()
            statsText.text = Stats.session(live) + "\n\n" + Stats.allTime(ctx, live) + "\n\nTECHNICAL\nIn zone   ${Dbg.inZone}%\nChecks/s  ${Dbg.fps}\nLatency   ${Dbg.latency} ms\nCapture   ${if (Cap.active) "live screen, ${Dbg.capFps} fps" else "OFF"}\nScan      ${Dbg.scanInfo}\nTapping   ${Dbg.tapInfo}\nBiome     ${Dbg.biome.ifEmpty { "-" }}\nDetect    ${Dbg.detector}\nTaps      ${Dbg.tapOk} ok, ${Dbg.tapCancel} cancelled\nAI aim    ${Dbg.aiBias}"
        }
        if (::capVal.isInitialized) capVal.text = if (Cap.active) "On" else "Off, tap"
        if (::tapVal.isInitialized) tapVal.text = "${Cfg.get(ctx, "tapms")} ms"
    }
}


===== FILE: app/src/main/java/com/solmacro/Scene.kt =====

package com.solmacro

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// v2.3 screen recognition of the fishing buttons, from the pictures in assets/scene:
//   fish.png            the "Fish" button  -> tap it
//   exit.png            the "Exit" button  -> fishing is running, do NOT tap there (it would leave the fishing)
//   x_white1/2.png      the WHITE strokes of the X in the corner of the "Fishing Failed" / "Fish Caught!" box -> tap it
// The X is matched on its white strokes only (the box is see-through, so the picture behind it changes all the time).
// Matching is done at many sizes and remembers the size that worked, so the next look is fast.
// Fish / Exit: the first strong match on YOUR phone is saved and used as an extra picture from then on.
object Scene {
    const val FISH = 1
    const val EXIT = 2
    const val XBTN = 4

    class Hit(val kind: Int, val x: Int, val y: Int, val score: Double)

    private var tFish: Mat? = null
    private var tExit: Mat? = null
    private var tFishL: Mat? = null     // learned on this phone
    private var tExitL: Mat? = null
    private var tX1: Mat? = null
    private var tX2: Mat? = null
    private var loaded = false
    private val memo = DoubleArray(8)
    @Volatile var info = ""
    @Volatile var lastBest = 0.0           // best score of the last look for each button, for the log
    private var lastPic = 0L

    private fun load(ctx: Context, name: String): Mat? = try {
        val b = ctx.assets.open("scene/$name").readBytes()
        val m = Imgcodecs.imdecode(MatOfByte(*b), Imgcodecs.IMREAD_GRAYSCALE)
        if (m.empty()) null else m
    } catch (e: Throwable) {
        Dbg.add("Scene picture $name not loaded: " + e.message)
        null
    }

    private fun loadLearned(ctx: Context, name: String): Mat? = try {
        val f = File(File(ctx.filesDir, "scene"), name)
        if (!f.exists()) null else Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_GRAYSCALE).let { if (it.empty()) null else it }
    } catch (e: Throwable) { null }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (loaded) return
        tFish = load(ctx, "fish.png")
        tExit = load(ctx, "exit.png")
        tX1 = load(ctx, "x_white1.png")
        tX2 = load(ctx, "x_white2.png")
        tFishL = loadLearned(ctx, "learn_fish.png")
        tExitL = loadLearned(ctx, "learn_exit.png")
        // sizes that worked last time: the first look after starting the app is then as fast as the rest
        for (i in memo.indices) memo[i] = Cfg.get(ctx, "scene_s$i") / 1000.0
        loaded = true
    }

    private class Best(val score: Double, val x: Int, val y: Int, val w: Int, val h: Int, val scale: Double)

    // best match of tpl in g. Tries the remembered scale first (also remembered between sessions), then a coarse sweep
    // (steps of 14%) and a fine search of +-8% in 2% steps around the best size: the coarse steps alone lost up to 20 points of
    // score on text (tests/scene_robust_test.py), the fine search brings the real match back above the limit.
    private fun best(g: Mat, tpl: Mat, slot: Int, accept: Double, from: Double = 0.05, to: Double = 1.2): Best? {
        fun at(s: Double): Best? {
            val tw = (tpl.cols() * s).toInt()
            val th = (tpl.rows() * s).toInt()
            if (th < 10 || tw < 10 || th >= g.rows() || tw >= g.cols()) return null
            val t = Mat()
            val r = Mat()
            try {
                Imgproc.resize(tpl, t, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.matchTemplate(g, t, r, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(r)
                return Best(mm.maxVal, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), tw, th, s)
            } finally {
                t.release()
                r.release()
            }
        }
        fun refine(b0: Best): Best {
            var top = b0
            var f = 0.92
            while (f <= 1.081) {
                val b = at(b0.scale * f)
                if (b != null && b.score > top.score) top = b
                f += 0.02
            }
            return top
        }
        if (memo[slot] > 0.0) {
            val b = at(memo[slot])
            if (b != null && b.score >= accept * 0.9) {
                val r = refine(b)
                if (r.score >= accept) { memo[slot] = r.scale; return r }
            }
        }
        var found: Best? = null
        var s = from
        while (s <= to) {
            val b = at(s)
            val cur = found
            if (b != null && (cur == null || b.score > cur.score)) found = b
            s *= 1.14
        }
        val first = found ?: return null
        val fin = refine(first)
        if (fin.score >= accept) memo[slot] = fin.scale
        return fin
    }

    // white strokes of the top of the screen: the colour channels are first scaled to the same white level (a night filter
    // or tinted screen then reads like a normal one) and the brightness limit follows the picture's own white level (a dim
    // screen still shows the X). Slightly blurred like the templates. tests/xbtn_test.py + scene_robust_test.py.
    private fun whiteMask(bgra: Mat, rows: Int): Mat {
        val top = bgra.submat(0, rows, 0, bgra.cols())
        val chs = ArrayList<Mat>()
        val hist = Mat()
        val merged = Mat()
        val gray = Mat()
        val m = Mat()
        try {
            Core.split(top, chs)
            val tail = top.rows() * top.cols() * 0.003
            val hi = IntArray(3)
            for (i in 0..2) {
                Imgproc.calcHist(listOf(chs[i]), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
                var acc = 0.0
                var v = 255
                for (b in 255 downTo 0) {
                    acc += hist.get(b, 0)[0]
                    if (acc >= tail) { v = b; break }
                }
                hi[i] = maxOf(v, 60)
            }
            val ref = maxOf(hi[0], maxOf(hi[1], hi[2]))
            for (i in 0..2) {
                val gain = (ref.toDouble() / hi[i]).coerceIn(1.0, 3.0)
                if (gain > 1.01) chs[i].convertTo(chs[i], -1, gain, 0.0)
            }
            Core.merge(chs.subList(0, 3), merged)
            Imgproc.cvtColor(merged, gray, Imgproc.COLOR_BGR2GRAY)
            val thr = maxOf(120.0, 214.0 * minOf(1.0, ref / 255.0))
            Imgproc.threshold(gray, m, thr, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.GaussianBlur(m, m, Size(3.0, 3.0), 0.0)
            return m
        } finally {
            for (c in chs) c.release()
            hist.release()
            merged.release()
            gray.release()
            top.release()
        }
    }

    private fun learn(ctx: Context, name: String, g: Mat, b: Best) {
        try {
            if (Cfg.get(ctx, "learnpics") != 1 || b.score < 0.85) return
            val dir = File(ctx.filesDir, "scene")
            dir.mkdirs()
            val f = File(dir, name)
            if (f.exists()) return
            val crop = g.submat(b.y, b.y + b.h, b.x, b.x + b.w)
            Imgcodecs.imwrite(f.absolutePath, crop)
            crop.release()
            Dbg.add("Saved this phone's own picture of the button ($name)")
        } catch (_: Throwable) {}
    }

    // reg = the whole screen (any sampling step). want = FISH / EXIT / XBTN (can be combined).
    // Returns the best recognised thing with the centre in real screen pixels, or null.
    @Synchronized
    fun find(ctx: Context, reg: Reg, want: Int): Hit? {
        if (!Cv.ready) return null
        ensure(ctx)
        val n = reg.w * reg.h
        if (reg.px.size < n || reg.w < 32 || reg.h < 32) return null
        val bgra = Mat(reg.h, reg.w, CvType.CV_8UC4)
        val g = Mat()
        try {
            val arr = ByteArray(n * 4)
            ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(reg.px, 0, n)
            bgra.put(0, 0, arr)
            Imgproc.cvtColor(bgra, g, Imgproc.COLOR_BGRA2GRAY)
            var out: Hit? = null
            var seen = 0.0
            fun consider(kind: Int, b: Best) {
                val cx = reg.x + (b.x + b.w / 2) * reg.step
                val cy = reg.y + (b.y + b.h / 2) * reg.step
                if (out == null || b.score > out!!.score) out = Hit(kind, cx, cy, b.score)
            }
            if (want and XBTN != 0) {
                val ph = (g.rows() * 0.6).toInt()
                val wm = whiteMask(bgra, ph)
                var bx: Best? = null
                tX1?.let { t -> best(wm, t, 2, 0.80)?.let { if (bx == null || it.score > bx!!.score) bx = it } }
                tX2?.let { t -> best(wm, t, 3, 0.80)?.let { if (bx == null || it.score > bx!!.score) bx = it } }
                val xb = bx
                if (xb != null) {
                    seen = maxOf(seen, xb.score)
                    if (xb.score >= 0.80) consider(XBTN, xb)
                }
                wm.release()
            }
            if (want and FISH != 0) {
                var bf: Best? = null
                tFish?.let { t -> best(g, t, 0, 0.82)?.let { bf = it } }
                tFishL?.let { t -> best(g, t, 4, 0.82, 0.8, 1.25)?.let { if (bf == null || it.score > bf!!.score) bf = it } }
                val b = bf
                if (b != null) {
                    seen = maxOf(seen, b.score)
                    if (b.score >= 0.82) { consider(FISH, b); learn(ctx, "learn_fish.png", g, b) }
                }
            }
            if (want and EXIT != 0) {
                var be: Best? = null
                tExit?.let { t -> best(g, t, 1, 0.82)?.let { be = it } }
                tExitL?.let { t -> best(g, t, 5, 0.82, 0.8, 1.25)?.let { if (be == null || it.score > be!!.score) be = it } }
                val b = be
                if (b != null) {
                    seen = maxOf(seen, b.score)
                    if (b.score >= 0.82) { consider(EXIT, b); learn(ctx, "learn_exit.png", g, b) }
                }
            }
            lastBest = seen
            for (i in memo.indices) { val v = (memo[i] * 1000).toInt(); if (v != Cfg.get(ctx, "scene_s$i")) Cfg.put(ctx, "scene_s$i", v) }
            info = if (out == null) "nothing (best " + (seen * 100).toInt() + "%)" else (when (out!!.kind) { FISH -> "Fish"; EXIT -> "Exit"; else -> "X" }) + " " + (out!!.score * 100).toInt() + "%"
            return out
        } catch (e: Throwable) {
            Dbg.add("Scene error: " + e.message)
            return null
        } finally {
            bgra.release()
            g.release()
        }
    }

    // Saves the screen as a picture so a button that was not recognised can be looked at (and used to improve the
    // pictures). At most one every 25 s and 15 files. Folder: Android/data/com.solmacro/files/debug
    fun savePicture(ctx: Context, reg: Reg, tag: String) {
        try {
            if (Cfg.get(ctx, "savepics") != 1) return
            val now = SystemClock.uptimeMillis()
            if (now - lastPic < 25000L) return
            lastPic = now
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "debug")
            dir.mkdirs()
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= 15) files.take(files.size - 14).forEach { it.delete() }
            val bmp = Bitmap.createBitmap(reg.px, reg.w, reg.h, Bitmap.Config.ARGB_8888)
            val f = File(dir, tag + "_" + System.currentTimeMillis() / 1000 + ".png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            Dbg.add("Saved a picture of the screen for checking: debug/" + f.name)
        } catch (e: Throwable) {
            Dbg.add("Could not save the debug picture: " + e.message)
        }
    }
}


===== FILE: app/src/main/java/com/solmacro/Stats.kt =====

package com.solmacro

import android.content.Context

// Fishing statistics (v1.9): this session (Dbg) and all time (saved in the app settings).
object Stats {
    fun addCatch(c: Context) = Cfg.put(c, "st_catch", Cfg.get(c, "st_catch") + 1)
    fun addFail(c: Context) = Cfg.put(c, "st_fail", Cfg.get(c, "st_fail") + 1)
    fun addUnverified(c: Context) = Cfg.put(c, "st_unver", Cfg.get(c, "st_unver") + 1)
    fun addSell(c: Context) = Cfg.put(c, "st_sell", Cfg.get(c, "st_sell") + 1)
    fun addSeconds(c: Context, s: Long) {
        if (s > 0L) Cfg.put(c, "st_sec", (Cfg.get(c, "st_sec").toLong() + s).coerceAtMost(2000000000L).toInt())
    }

    fun reset(c: Context) {
        for (k in listOf("st_catch", "st_fail", "st_unver", "st_sec", "st_sell")) Cfg.put(c, k, 0)
    }

    fun hms(sec: Long): String =
        String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

    private fun pct(a: Int, b: Int): String = if (b <= 0) "-" else (a * 100 / b).toString() + "%"

    // activeSec = fishing time of this session (paused time is not counted)
    fun session(activeSec: Long): String {
        val tries = Dbg.catches + Dbg.fails + Dbg.unverified
        val perHour = if (activeSec > 60L) Dbg.catches * 3600L / activeSec else 0L
        val avg = if (Dbg.catches > 0 && activeSec > 0L) (activeSec / Dbg.catches).toString() + " s" else "-"
        val bite = if (Dbg.biteMax > 0) "${Dbg.biteMin} / ${Dbg.biteMs} / ${Dbg.biteMax} ms" else "-"
        return "THIS SESSION\n" +
            "Successful catches  ${Dbg.catches}\n" +
            "Failed attempts     ${Dbg.fails}\n" +
            "Not verified        ${Dbg.unverified}\n" +
            "Success rate        ${pct(Dbg.catches, tries)}\n" +
            "Fishing time        ${hms(activeSec)}\n" +
            "Catches per hour    $perHour\n" +
            "Time per catch      $avg\n" +
            "Bite min/avg/max    $bite\n" +
            "Sells               ${Dbg.sells}\n" +
            "Click recoveries    ${Dbg.clickFix}\n" +
            "Freeze recoveries   ${Dbg.freezes}"
    }

    // liveSec = the running session, which is not saved yet
    fun allTime(c: Context, liveSec: Long): String {
        val ok = Cfg.get(c, "st_catch") + Dbg.catches
        val bad = Cfg.get(c, "st_fail") + Dbg.fails
        val un = Cfg.get(c, "st_unver") + Dbg.unverified
        val sec = Cfg.get(c, "st_sec").toLong() + liveSec
        return "ALL TIME\n" +
            "Successful catches  $ok\n" +
            "Failed attempts     $bad\n" +
            "Not verified        $un\n" +
            "Success rate        ${pct(ok, ok + bad + un)}\n" +
            "Total fishing time  ${hms(sec)}\n" +
            "Sells               ${Cfg.get(c, "st_sell") + Dbg.sells}"
    }
}


===== FILE: app/src/main/java/com/solmacro/Tapper.kt =====

package com.solmacro

// LuminFish 2.1 precision tap engine ("tap ledger").
//
// Why: the v2.0 timed engine guessed how long a tap takes to show up in the game (35 ms) and counted a tap as
// "landed" once that time had passed. If the phone is slower than the guess, the lift is still missing from the
// picture, the marker looks too low and a second, unneeded tap goes out. That is where late and doubled taps came from.
//
// How it works now:
//   1. Every tap goes into a ledger and counts as IN FLIGHT until a picture of the screen shows its lift.
//   2. The marker position at the moment a new tap would land = last seen position - drain + (taps in flight x gain).
//   3. A tap goes out when that predicted position is below (zone centre - half a tap). One tap then lifts the marker
//      to the same distance above the centre, so the marker swings evenly around the middle of the zone.
//   4. The tap-to-game delay, the lift of one tap and the drain speed are MEASURED from the pictures while reeling.
//   5. When Android reports that a tap was cancelled or refused, it leaves the ledger at once (no phantom lift).
//   6. The zone is followed ahead of where it was seen, using its speed over the last 100-260 ms.
//
// The same logic runs in tests/reel_sim.py (sim6.py) against a simulated reeling bar.
class Tapper {
    private class T(val fire: Long, var st: Int)   // st: 0 in flight, 1 lift seen, 2 lost (cancelled / never seen)

    private val taps = ArrayList<T>()
    private val zh = ArrayList<DoubleArray>()      // [time, zone centre]

    @Volatile var gain = 58.0
        private set
    @Volatile var drain = 215.0
        private set
    @Volatile var delay = 35.0
        private set
    @Volatile var gN = 0
        private set
    @Volatile var dN = 0
        private set
    @Volatile var latN = 0
        private set

    private var hold = 35.0
    // v2.3: what is learned stays close to the start values (it used to drift: the lift estimate shrank over a reel,
    // taps looked weaker and weaker, and the macro tapped more and more)
    private var gLo = 0.0
    private var gHi = 1e9
    private var dLo = 0.0
    private var dHi = 1e9
    private var minGap = 70L
    private var pts = 0L
    private var pp = 0.0
    private var acc = 0.0
    private var zc = 0.0
    private var zw = 0.0
    private var zv = 0.0
    private var haveZone = false
    private var lastFire = 0L
    private var dtEma = 25.0
    private var quietT = 0L
    private var quietP = 0.0
    private var zoneAt = 0L

    @Synchronized
    fun reset(gain0: Double, drain0: Double, delay0: Double, holdMs: Long, gapMs: Long) {
        taps.clear()
        zh.clear()
        gain = gain0.coerceAtLeast(5.0)
        drain = drain0.coerceAtLeast(10.0)
        delay = delay0.coerceIn(5.0, 140.0)
        gLo = gain * 0.8
        gHi = gain * 1.25
        dLo = drain * 0.75
        dHi = drain * 1.3
        hold = holdMs.toDouble()
        minGap = maxOf(gapMs, holdMs + 20L)
        pts = 0L
        pp = 0.0
        acc = 0.0
        zv = 0.0
        haveZone = false
        lastFire = 0L
        dtEma = 25.0
        quietT = 0L
        zoneAt = 0L
        gN = 0
        dN = 0
        latN = 0
    }

    // taps that are on their way but not yet visible in a picture
    @Synchronized
    fun inFlight(): Int {
        var n = 0
        for (t in taps) if (t.st == 0) n++
        return n
    }

    // true when there is a recent picture with a real marker position to start from
    @Synchronized
    fun usable(now: Long): Boolean = pts != 0L && haveZone && now - pts <= 300L

    // A new picture of the bar. capT = when the picture was taken (uptime ms), tip = right end of the blue fill (px,
    // may have decimals), haveTip = false when the blue fill is hidden (then only the zone is updated).
    @Synchronized
    fun see(capT: Long, tip: Double, haveTip: Boolean, zoneCenter: Double, zoneWidth: Double) {
        zc = zoneCenter
        zw = zoneWidth
        haveZone = true
        zoneAt = capT
        // zone speed from the last 100-260 ms
        zh.add(doubleArrayOf(capT.toDouble(), zoneCenter))
        while (zh.size > 0 && capT - zh[0][0] > 400.0) zh.removeAt(0)
        var old: DoubleArray? = null
        for (z in zh) if (capT - z[0] in 100.0..260.0) { old = z; break }
        if (old != null) {
            val v = (zoneCenter - old[1]) / ((capT - old[0]) / 1000.0)
            zv = if (Math.abs(v) < 25.0) 0.0 else 0.6 * zv + 0.4 * v
        }
        if (!haveTip) return
        if (pts != 0L && capT > pts) {
            val dt = (capT - pts) / 1000.0
            if (dt < 0.3) {
                dtEma = 0.9 * dtEma + 0.1 * dt * 1000.0
                val r = tip - (pp - drain * dt)            // lift since the previous picture
                acc += r
                // oldest taps first: one tap is credited for every 0.6 x gain of lift
                val fl = ArrayList<T>()
                for (t in taps) if (t.st == 0) fl.add(t)
                while (fl.isNotEmpty() && acc >= 0.6 * gain && fl[0].fire < capT) {
                    val t = fl.removeAt(0)
                    t.st = 1
                    val lat = capT - t.fire - 0.5 * dtEma
                    if (lat > 5.0 && lat < 200.0) { delay = 0.95 * delay + 0.05 * lat; latN++ }
                    if (fl.isEmpty() && acc < 1.6 * gain) {
                        gain = (0.96 * gain + 0.04 * acc.coerceIn(0.5 * gain, 1.6 * gain)).coerceIn(gLo, gHi)
                        gN++
                    }
                    acc -= gain
                }
                acc = if (fl.isNotEmpty()) acc.coerceIn(-0.5 * gain, 2.5 * gain) else 0.0
                // a tap that never showed a lift stops counting after a generous time
                val lim = maxOf(220.0, 3.0 * delay)
                for (t in fl) if (capT - t.fire > lim) t.st = 2
                // drain: a stretch with no tap in flight shows how fast the marker falls by itself
                if (inFlightNoLock() == 0) {
                    if (quietT != 0L && quietT <= capT) {
                        val qd = (capT - quietT) / 1000.0
                        if (qd >= 0.08) {
                            val d = (quietP - tip) / qd
                            if (d > 0.5 * drain && d < 1.6 * drain) { drain = (0.96 * drain + 0.04 * d).coerceIn(dLo, dHi); dN++ }
                            quietT = capT
                            quietP = tip
                        }
                    } else {
                        quietT = capT
                        quietP = tip
                    }
                } else {
                    quietT = 0L
                }
            }
        }
        pts = capT
        pp = tip
        // forget old entries
        var i = 0
        while (i < taps.size) {
            val t = taps[i]
            if (t.st != 0 && t.fire < capT - 1500L) taps.removeAt(i) else i++
        }
    }

    private fun inFlightNoLock(): Int {
        var n = 0
        for (t in taps) if (t.st == 0) n++
        return n
    }

    // Android said the tap with this fire time was cancelled or refused: it will not lift the marker
    @Synchronized
    fun cancelled(fire: Long) {
        for (t in taps) if (t.fire == fire && t.st == 0) t.st = 2
    }

    // Should a tap go out now? Pure question, nothing is recorded. gapMs = the smallest time between two taps.
    @Synchronized
    fun wantTap(now: Long, gapMs: Long): Boolean {
        if (!usable(now)) return false
        if (now - lastFire < maxOf(gapMs, minGap)) return false
        val n = inFlightNoLock()
        val horizon = now + delay + hold / 2.0
        val pT = pp - drain * (horizon - pts) / 1000.0 + gain * n
        val zT = zc + (zv * (horizon - pts) / 1000.0).coerceIn(-zw, zw)
        return pT < zT - 0.5 * gain
    }

    // The tap really goes out now: enter it in the ledger.
    @Synchronized
    fun commit(now: Long) {
        lastFire = now
        taps.add(T(now, 0))
    }

    fun info(): String =
        "ledger: delay ${delay.toInt()} ms, lift ${gain.toInt()} px, drain ${drain.toInt()} px/s, in flight ${inFlight()}"
}


===== FILE: app/build.gradle.kts =====

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.solmacro"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.solmacro"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.android.gms:play-services-tasks:18.1.0")
}


===== FILE: build.gradle.kts =====

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}


===== FILE: settings.gradle.kts =====

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SolMacro"
include(":app")


===== FILE: gradle.properties =====

org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official


===== FILE: .github/workflows/build.yml =====

name: Build APK
on:
  workflow_dispatch:
  push:
    branches: [main]
permissions:
  contents: read
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - name: Find the Android project folder
        id: find
        shell: bash
        run: |
          dir=""
          while IFS= read -r f; do
            d=$(dirname "$f")
            if [ -f "$d/build.gradle.kts" ] && [ -f "$d/app/build.gradle.kts" ]; then
              dir="$d"
              break
            fi
          done < <(find . -name settings.gradle.kts -not -path './.git/*' | sort)
          if [ -z "$dir" ]; then
            echo "::error::No Android project found (a folder with settings.gradle.kts, build.gradle.kts and app/build.gradle.kts)."
            echo "Repository files:"
            find . -maxdepth 3 -not -path './.git/*' | sort | head -80
            exit 1
          fi
          echo "Android project folder: $dir"
          echo "dir=$dir" >> "$GITHUB_OUTPUT"

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: 8.9

      - name: Build debug APK
        working-directory: ${{ steps.find.outputs.dir }}
        shell: bash
        run: |
          set -o pipefail
          gradle assembleDebug --no-daemon --console=plain 2>&1 | tee "$RUNNER_TEMP/build.log"

      - name: Show the compile errors (only when the build fails)
        if: failure()
        shell: bash
        run: |
          {
            echo "## Build failed - errors"
            echo '```'
            grep -E "^e: |error:|What went wrong|Execution failed" -A1 "$RUNNER_TEMP/build.log" | grep -v '^--$' | head -80 || true
            echo '```'
            echo "Send these lines (not the long stack trace)."
          } >> "$GITHUB_STEP_SUMMARY"
          echo "---- first errors ----"
          grep -E "^e: " "$RUNNER_TEMP/build.log" | head -40 || true

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: SolMacro-apk
          path: ${{ steps.find.outputs.dir }}/app/build/outputs/apk/debug/*.apk
          if-no-files-found: error

      - name: Upload build log (only when the build fails)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: build-log
          path: ${{ runner.temp }}/build.log
          if-no-files-found: ignore

      - name: Upload build reports (only when the build fails)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: build-reports
          path: |
            ${{ steps.find.outputs.dir }}/app/build/reports
            ${{ steps.find.outputs.dir }}/app/build/outputs/logs
          if-no-files-found: ignore


===== FILE: app/src/main/AndroidManifest.xml =====

<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <queries>
        <package android:name="com.roblox.client" />
    </queries>
    <application
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".LoginActivity" android:exported="true" android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="luminfish" android:host="auth" />
            </intent-filter>
        </activity>
        <service
            android:name=".CaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
        <service
            android:name=".MacroService"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_config" />
        </service>
    </application>
</manifest>
