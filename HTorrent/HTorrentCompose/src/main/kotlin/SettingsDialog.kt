import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

internal fun showSettingsDialog(current: AppSettings, onSave: (AppSettings) -> Unit) {
    val path = JTextField(current.downloadPath, 28)
    val browse = JButton("Browse...").apply {
        addActionListener {
            val chooser = JFileChooser(path.text).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) path.text = chooser.selectedFile.absolutePath
        }
    }
    val pathRow = JPanel(BorderLayout(6, 0)).apply { add(path, BorderLayout.CENTER); add(browse, BorderLayout.EAST) }
    val down = JTextField(current.downloadLimitKiB.toString(), 10)
    val up = JTextField(current.uploadLimitKiB.toString(), 10)
    val fields = JPanel(GridLayout(3, 2, 8, 8)).apply {
        add(JLabel("Download folder")); add(pathRow)
        add(JLabel("Download limit (KiB/s, 0 = unlimited)")); add(down)
        add(JLabel("Upload limit (KiB/s, 0 = unlimited)")); add(up)
    }
    val startNew = JCheckBox("Start new torrents automatically", current.startNewTorrents)
    val resume = JCheckBox("Resume active torrents when HTorrent opens", current.resumeOnLaunch)
    val choices = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(startNew); add(resume)
    }
    val panel = JPanel(BorderLayout(0, 12)).apply { add(fields, BorderLayout.CENTER); add(choices, BorderLayout.SOUTH) }
    if (JOptionPane.showConfirmDialog(null, panel, "HTorrent Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
    val download = down.text.trim().toLongOrNull()
    val upload = up.text.trim().toLongOrNull()
    if (download == null || upload == null || download !in 0..1_000_000_000 || upload !in 0..1_000_000_000 || path.text.isBlank()) {
        JOptionPane.showMessageDialog(null, "Enter a download folder and nonnegative whole-number limits.", "HTorrent Settings", JOptionPane.ERROR_MESSAGE)
        return
    }
    val normalized = runCatching { Path.of(path.text.trim()).toAbsolutePath().normalize().toString() }.getOrElse {
        JOptionPane.showMessageDialog(null, "Enter a valid download folder.", "HTorrent Settings", JOptionPane.ERROR_MESSAGE)
        return
    }
    onSave(AppSettings(normalized, download, upload, startNew.isSelected, resume.isSelected))
}
