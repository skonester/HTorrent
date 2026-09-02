using System.Diagnostics;
using System.Runtime.InteropServices;

namespace HTorrent.Launcher;

/// <summary>
/// Minimal native launcher stub for HTorrent.
/// Resolves the jpackage-generated app launcher inside the runtime/ directory
/// and starts it, forwarding all command-line arguments.
/// </summary>
internal static class Program
{
    // Hide the console window allocation for WinExe fallback
    [DllImport("kernel32.dll")]
    private static extern IntPtr GetConsoleWindow();

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [STAThread]
    static int Main(string[] args)
    {
        // Ensure no console flash
        var consoleWindow = GetConsoleWindow();
        if (consoleWindow != IntPtr.Zero)
            ShowWindow(consoleWindow, 0 /* SW_HIDE */);

        // Environment.ProcessPath gives the real on-disk EXE location,
        // not the temp extraction dir that AppContext.BaseDirectory returns
        // for single-file published apps.
        var exeDir = Path.GetDirectoryName(Environment.ProcessPath)!;
        var runtimeLauncher = Path.Combine(exeDir, "runtime", "HTorrent.exe");

        if (!File.Exists(runtimeLauncher))
        {
            // Try flat layout (launcher sitting next to the app/ folder directly)
            runtimeLauncher = Path.Combine(exeDir, "HTorrent", "HTorrent.exe");
        }

        if (!File.Exists(runtimeLauncher))
        {
            ShowError(
                "HTorrent runtime not found.\n\n" +
                $"Expected at:\n{Path.Combine(exeDir, "runtime", "HTorrent.exe")}\n\n" +
                "Please ensure the runtime folder is in the same directory as this executable.");
            return 1;
        }

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = runtimeLauncher,
                UseShellExecute = false,
                CreateNoWindow = false,
                WorkingDirectory = Path.GetDirectoryName(runtimeLauncher)!
            };

            // Forward all arguments
            foreach (var arg in args)
                psi.ArgumentList.Add(arg);

            using var process = Process.Start(psi);
            if (process == null)
            {
                ShowError("Failed to start HTorrent runtime process.");
                return 1;
            }

            process.WaitForExit();
            return process.ExitCode;
        }
        catch (Exception ex)
        {
            ShowError($"Failed to launch HTorrent:\n\n{ex.Message}");
            return 1;
        }
    }

    private static void ShowError(string message)
    {
        // Use Windows MessageBox via P/Invoke to avoid WinForms dependency
        MessageBox(IntPtr.Zero, message, "HTorrent - Launch Error", 0x10 /* MB_ICONERROR */);
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBox(IntPtr hWnd, string text, string caption, uint type);
}
