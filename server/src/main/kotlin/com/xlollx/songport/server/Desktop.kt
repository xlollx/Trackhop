package com.xlollx.songport.server

import java.awt.CheckboxMenuItem
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.net.BindException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Trackhop Desktop (beta): the server's engine and pages as an app on the user's own computer
 * (Windows, Linux), packaged with its own Java runtime (.github/workflows/desktop.yml). No Docker,
 * no password: it listens on 127.0.0.1 only. An icon in the system tray opens the pages, starts it
 * with the computer, or quits; where there is no tray (GNOME without an extension) the browser
 * opens and the page's footer has "Quit". Started again while it runs, it opens the browser on the
 * running one. `--background` (the start with the computer) does not open the browser.
 */
object DesktopApp {
    /** A fixed port, so the redirect a user registers with Spotify & co. stays valid. */
    const val PORT = 8642

    /**
     * Where the data lives: the platform's per-user application data folder. On Windows the roaming
     * one: the MSI installs the program itself under %LOCALAPPDATA%\Trackhop.
     */
    fun dataDir(env: Map<String, String>, os: String = System.getProperty("os.name"), home: String = System.getProperty("user.home")): File = when {
        os.startsWith("Windows", ignoreCase = true) ->
            File(env["APPDATA"]?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming", "Trackhop")
        os.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Application Support/Trackhop")
        else -> File(env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "trackhop")
    }

    fun config(env: Map<String, String>): Config {
        // The system's language unless SONGPORT_LOCALE says otherwise.
        val withLocale = if (env["SONGPORT_LOCALE"].isNullOrBlank()) env + ("SONGPORT_LOCALE" to Locale.getDefault().toLanguageTag()) else env
        // 127.0.0.1 rather than localhost: Spotify accepts plain http only for the loopback address.
        return Config(withLocale - "SONGPORT_PASSWORD", dataDir(env).path, PORT, "127.0.0.1")
    }

    /** True when a Trackhop already answers on [baseUrl]. */
    fun isRunning(baseUrl: String): Boolean = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val res = client.send(HttpRequest.newBuilder(URI("$baseUrl/health")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        res.statusCode() == 200 && res.body().trim() == "ok"
    }.getOrDefault(false)

    fun openBrowser(url: String) {
        val viaAwt = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url)); true
            } else false
        }.getOrDefault(false)
        if (viaAwt) return
        val os = System.getProperty("os.name")
        val cmd = when {
            os.startsWith("Windows", ignoreCase = true) -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.startsWith("Mac", ignoreCase = true) -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(cmd).start() }.onFailure { Log.add("desktop", "could not open the browser: ${it.message}; open $url") }
    }

    /** True when this build carries SWT (Desktop's packages do; Docker and tests do not). */
    val swtAvailable: Boolean get() = runCatching { Class.forName("org.eclipse.swt.widgets.Display") }.isSuccess

    /** Asks the Trackhop already running on [baseUrl] to show its window; false if it did not answer. */
    fun askToShow(baseUrl: String): Boolean = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val req = HttpRequest.newBuilder(URI("$baseUrl/show")).timeout(Duration.ofSeconds(3)).header("Origin", baseUrl).POST(HttpRequest.BodyPublishers.noBody()).build()
        client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399
    }.getOrDefault(false)

    fun error(message: String) {
        Log.add("desktop", message)
        if (!GraphicsEnvironment.isHeadless()) runCatching { JOptionPane.showMessageDialog(null, message, "Trackhop", JOptionPane.ERROR_MESSAGE) }
    }
}

/**
 * Starting with the computer: the launcher that jpackage made (its path is in `jpackage.app-path`),
 * registered in the user's Run key on Windows or as an XDG autostart entry on Linux. Not offered
 * when running from a plain build (no launcher to register), nor from the Microsoft Store's package
 * (under WindowsApps), which only sees its own copy of the Run key: there Windows' own switch for
 * the package's startup task does it (Settings › Apps › Startup).
 */
object Autostart {
    private val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }
    private val windows get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val linux get() = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val desktopFile get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "autostart/trackhop.desktop")

    val available: Boolean get() = launcher.let { it != null && !storePackage(it) } && (windows || linux)

    /** The launcher's path is inside an installed MSIX package (`…\WindowsApps\<package>\…`). */
    internal fun storePackage(path: String): Boolean = path.replace('/', '\\').contains("\\WindowsApps\\", ignoreCase = true)

    fun enabled(): Boolean = when {
        windows -> run("reg", "query", RUN_KEY, "/v", "Trackhop") == 0
        linux -> desktopFile.exists()
        else -> false
    }

    fun set(on: Boolean) {
        val exe = launcher ?: return
        when {
            windows -> if (on) run("reg", "add", RUN_KEY, "/v", "Trackhop", "/t", "REG_SZ", "/d", "\"$exe\" --background", "/f")
                else run("reg", "delete", RUN_KEY, "/v", "Trackhop", "/f")
            linux -> if (on) {
                desktopFile.parentFile.mkdirs()
                desktopFile.writeText("[Desktop Entry]\nType=Application\nName=Trackhop\nExec=\"$exe\" --background\nIcon=trackhop\nX-GNOME-Autostart-enabled=true\n")
            } else desktopFile.delete()
        }
    }

    private fun run(vararg cmd: String): Int = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }.getOrDefault(-1)
}

fun main(args: Array<String>) {
    val background = "--background" in args
    val config = DesktopApp.config(System.getenv())
    // Already running (a second click on the icon): show it and leave.
    if (DesktopApp.isRunning(config.baseUrl)) {
        if (!background && !DesktopApp.askToShow(config.baseUrl)) DesktopApp.openBrowser(config.baseUrl)
        return
    }
    var tray: TrayIcon? = null
    var window: DesktopWindow? = null
    val quit = {
        window?.quit()
        tray?.let { t -> runCatching { SystemTray.getSystemTray().remove(t) } }
        exitProcess(0)
    }
    val show = { window?.showLater() ?: DesktopApp.openBrowser(config.baseUrl) }
    // The Microsoft Store's package offers only the official connections, as the app's Play build does.
    val store = Autostart.storePackage(System.getProperty("jpackage.app-path").orEmpty())
    Updates.configure(
        config.dataDir, serverVersion(), Html.Edition.DESKTOP, System.getenv(),
        Updates.desktopUpdater(System.getProperty("jpackage.app-path").orEmpty(), System.getProperty("java.home").orEmpty(), System.getenv(), store),
    )
    val app = launch(config, Html.Edition.DESKTOP, onQuit = quit, onShow = show, officialOnly = store)
    try {
        app.web.start(config.port, loopbackOnly = true)
    } catch (e: BindException) {
        DesktopApp.error("Trackhop could not start: port ${config.port} is used by another program. Close it, or set SONGPORT_PORT to another port.")
        exitProcess(1)
    }
    app.started(warnNoPassword = false)
    // Its own window where the package carries SWT and the system has a web engine; else the browser.
    if (DesktopApp.swtAvailable && !GraphicsEnvironment.isHeadless()) {
        val w = runCatching { DesktopWindow(config.baseUrl) }.onFailure { Log.add("desktop", "no window: ${it.message}") }.getOrNull()
        if (w != null) {
            window = w
            val hasTray = w.installTray()
            if ((!background || !hasTray) && !w.show()) DesktopApp.openBrowser(config.baseUrl)
            w.run()
            return
        }
    }
    tray = installTray(config.baseUrl, quit)
    if (!background || tray == null) DesktopApp.openBrowser(config.baseUrl)
}

/** The tray icon and its menu, or null where the desktop has no tray. */
private fun installTray(url: String, quit: () -> Unit): TrayIcon? {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return null
    return runCatching {
        val image = DesktopApp::class.java.getResourceAsStream("/desktop/tray.png")!!.use { ImageIO.read(it) }
        val menu = PopupMenu()
        menu.add(MenuItem("Open Trackhop").apply { addActionListener { DesktopApp.openBrowser(url) } })
        if (Autostart.available) {
            menu.add(CheckboxMenuItem("Start with the computer", Autostart.enabled()).apply {
                addItemListener { Autostart.set(state) }
            })
        }
        menu.addSeparator()
        menu.add(MenuItem("Quit Trackhop").apply { addActionListener { quit() } })
        TrayIcon(image, "Trackhop", menu).apply {
            isImageAutoSize = true
            addActionListener { DesktopApp.openBrowser(url) }
            SystemTray.getSystemTray().add(this)
        }
    }.onFailure { Log.add("desktop", "no tray icon: ${it.message}") }.getOrNull()
}
