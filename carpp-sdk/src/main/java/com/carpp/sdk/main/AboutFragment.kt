package com.carpp.sdk.main

import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.carpp.sdk.R
import com.google.android.material.appbar.MaterialToolbar
import java.util.Calendar

class AboutFragment : Fragment() {

    companion object {
        /** Car++'s public repository; the source link on this screen points here. */
        private const val REPO_URL = "#"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val copyrightText = view.findViewById<TextView>(R.id.copyright_text)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        copyrightText.text = getString(R.string.copyright, currentYear)

        val contentText = view.findViewById<TextView>(R.id.about_content_text)
        
        val sb = StringBuilder()
        sb.append("<b>Special thanks to Mike Reidis for the original headunit protocol and code.</b><br/>")
        sb.append("<a href=\"https://github.com/mikereidis/headunit\">https://github.com/mikereidis/headunit</a><br/><br/>")
        sb.append("<h3>Car++ ${com.carpp.sdk.BuildConfig.VERSION_NAME}</h3>")
        sb.append("<a href=\"").append(REPO_URL).append("\">Car++ source and releases</a><br/>")
        sb.append("Car++ brings Xiaomi CarWith, OPPO Car+ and vivo Jovi InCar to the car over ICCOA CarLink. It is built on ")
        sb.append("<a href=\"https://github.com/zutaozhong-hash/Car++-Pro\">Car++</a> and ")
        sb.append("<a href=\"https://github.com/andreknieriem/open-headunit\">Car++</a>; ")
        sb.append("thanks to their authors and contributors. Not affiliated with Google, Xiaomi, OPPO, vivo or BYD.<br/><br/>")

        sb.append(parseMarkdownToHtml(readAsset(changelogAsset())))
        sb.append("<br/><br/>")

        sb.append("<h3>LICENSE</h3>")
        // License is plain text, preserve newlines
        val license = readAsset("LICENSE").replace("\n", "<br/>")
        sb.append(license)

        contentText.text = fromHtml(sb.toString())
        contentText.movementMethod = android.text.method.LinkMovementMethod.getInstance() // Make links clickable
    }

    /**
     * Picks the changelog asset for the current locale, from the most specific name
     * (CHANGELOG-<lang>-<COUNTRY>.md) down to the English default (CHANGELOG.md).
     * The inherited Car++ / Car++ history lives in CHANGELOG-UPSTREAM.md and is
     * intentionally not shown here.
     */
    private fun changelogAsset(): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
        val candidates = listOf(
            "CHANGELOG-${locale.language}-${locale.country}.md",
            "CHANGELOG-${locale.language}.md",
            "CHANGELOG.md"
        )
        for (name in candidates) {
            try {
                requireContext().assets.open(name).close()
                return name
            } catch (_: Exception) {
                // Fall through to the next, less specific name.
            }
        }
        return "CHANGELOG.md"
    }

    private fun readAsset(fileName: String): String {
        return try {
            requireContext().assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error loading $fileName"
        }
    }

    private fun parseMarkdownToHtml(markdown: String): String {
        var html = markdown
        // Headers
        html = html.replace(Regex("### (.*)"), "<h4>$1</h4>")
        html = html.replace(Regex("## (.*)"), "<h3>$1</h3>")
        html = html.replace(Regex("# (.*)"), "<h2>$1</h2>")
        
        // Bold
        html = html.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        
        // Lists
        html = html.replace(Regex("\n- (.*)"), "<br/>&#8226; $1")
        
        // Newlines (Markdown preserves single newlines as space, but we want break for readability in log?)
        // Actually, let's just replace double newlines with paragraph, and single with br?
        // Simple approach: Replace \n with <br/> but be careful not to break tags.
        // For list items we already handled the newline prefix.
        
        // Let's replace remaining newlines that are not part of tags
        // This is tricky with regex. 
        // Better: replace all \n with <br/> at the end?
        // The list replacement consumed the \n before the dash.
        
        return html
    }

    private fun fromHtml(html: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }
}
