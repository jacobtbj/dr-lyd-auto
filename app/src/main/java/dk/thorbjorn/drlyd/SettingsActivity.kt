package dk.thorbjorn.drlyd

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Indstillinger i bilen. Bilen åbner den via tandhjulet, og kun når den holder stille.
 * Bygget med store knapper og tekst, så den er nem at ramme på bilskærmen.
 */
class SettingsActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var bg: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bg = Executors.newSingleThreadExecutor()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), dp(40), dp(64))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
        // Hent podcast-navne i baggrunden, så listen viser rigtige titler
        bg.execute {
            Feeds.shows(this, Settings.podcasts(this))
            runOnUiThread { if (!isFinishing) render() }
        }
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        // Bilen har ikke altid en tilbage-knap, så vi laver vores egen
        row {
            addView(smallButton("‹ Tilbage") { finish() })
        }
        title("Indstillinger")
        note("Ændringer slår igennem med det samme i DR Lyd.")

        // ---- Live-kanaler ----
        section("Live-kanaler")
        note("Vælg hvilke kanaler der vises under Live, og flyt dem med pilene.")
        val cfg = ConfigRepo.get(this)
        val selected = Settings.liveIds(this).toMutableList()
        val ordered = selected.mapNotNull { cfg.channel(it) } + cfg.channels.filter { it.id !in selected }
        ordered.forEach { ch ->
            val on = ch.id in selected
            row {
                addView(CheckBox(context).apply {
                    text = if (ch.subtitle.isNotBlank()) "${ch.title}  ·  ${ch.subtitle}" else ch.title
                    textSize = 22f
                    isChecked = on
                    minHeight = dp(64)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(ch.id) else selected.remove(ch.id)
                        Settings.setLiveIds(context, selected)
                        changed()
                        render()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (on) {
                    addView(smallButton("▲") { move(selected, ch.id, -1) })
                    addView(smallButton("▼") { move(selected, ch.id, 1) })
                }
            }
        }

        // ---- Podcasts ----
        section("Mine podcasts")
        note("Vises under Hjem og Din lyd.")
        val podcasts = Settings.podcasts(this)
        podcasts.forEach { slug ->
            row {
                addView(label(Feeds.cached(slug)?.title ?: Slugs.pretty(slug)),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(smallButton("Fjern") {
                    Settings.setPodcasts(context, podcasts - slug)
                    changed()
                    render()
                })
            }
        }
        val input = EditText(this).apply {
            hint = "dr.dk-link eller programnavn"
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minHeight = dp(64)
        }
        row {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallButton("Tilføj") { addPodcast(input.text.toString()) })
        }

        // ---- Opsætning ----
        section("Kanal-opsætning")
        note("Stream-adresser, kategorier og Fremhævet hentes automatisk fra denne fil. Ret filen, så opdateres bilen uden ny app.")
        note(Settings.configStatus(this))
        val url = EditText(this).apply {
            setText(Settings.configUrl(this@SettingsActivity))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minHeight = dp(64)
        }
        root.addView(url)
        row {
            addView(smallButton("Gem adresse") {
                Settings.setConfigUrl(context, url.text.toString())
                refreshConfig()
            })
            addView(smallButton("Hent nu") { refreshConfig() })
        }
    }

    private fun move(list: MutableList<String>, id: String, delta: Int) {
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        Settings.setLiveIds(this, list)
        changed()
        render()
    }

    private fun addPodcast(text: String) {
        if (text.isBlank()) {
            toast("Skriv et link eller et navn først")
            return
        }
        val slug = Slugs.fromInput(text)
        toast("Leder efter \"$slug\" …")
        bg.execute {
            val show = Feeds.show(this, slug, force = true)
            runOnUiThread {
                if (show == null || show.episodes.isEmpty()) {
                    toast("Fandt ikke en DR-podcast ved navn \"$slug\"")
                } else {
                    Settings.setPodcasts(this, Settings.podcasts(this) + slug)
                    changed()
                    render()
                    toast("${show.title} er tilføjet")
                }
            }
        }
    }

    private fun refreshConfig() {
        toast("Henter opsætning …")
        bg.execute {
            ConfigRepo.refresh(this, force = true)
            runOnUiThread {
                changed()
                render()
                toast(Settings.configStatus(this))
            }
        }
    }

    private fun changed() = LibraryEvents.changed()

    // ---------- Små byggeklodser ----------

    private fun row(block: LinearLayout.() -> Unit) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            block()
        })
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun section(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, dp(32), 0, dp(4))
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        minHeight = dp(64)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 20f
        minHeight = dp(64)
        minWidth = dp(88)
        setOnClickListener { onClick() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
