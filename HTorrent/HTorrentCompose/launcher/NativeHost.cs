using System.Diagnostics;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace HTorrent.Launcher;

/// <summary>
/// Lets the HTorrent browser extension reach the app. Browsers start this same EXE with the extension's address as the
/// first argument and talk to it over stdin/stdout (native messaging: 4-byte length, then JSON).
/// </summary>
internal static class NativeHost
{
    private const string HostName = "com.htorrent.host";
    private const string ExtensionOrigin = "chrome-extension://mkkgfjdkaddkodnijcocbnabggljjlcn/";
    private const int MaxMessageBytes = 1024 * 1024;
    private const int MaxMagnetLength = 8192;
    private const long MaxTorrentFileBytes = 32 * 1024 * 1024; // the app's own limit

    // Each browser looks in its own place. Thorium and other Chromium builds use the Chromium key.
    private static readonly string[] BrowserKeys =
    {
        @"Google\Chrome", "Chromium", @"Microsoft\Edge", @"BraveSoftware\Brave-Browser", "Vivaldi",
    };

    public static bool IsBrowserLaunch(string[] args) =>
        args.Length > 0 && args[0].StartsWith("chrome-extension://", StringComparison.OrdinalIgnoreCase);

    /// <summary>Tells every supported browser where this EXE is. Runs on each launch, so a moved install repairs itself.</summary>
    public static void Register(string exePath)
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HTorrent");
        Directory.CreateDirectory(folder);
        var manifestPath = Path.Combine(folder, HostName + ".json");
        File.WriteAllBytes(manifestPath, Manifest(exePath));
        foreach (var browser in BrowserKeys)
        {
            using var key = Registry.CurrentUser.CreateSubKey($@"Software\{browser}\NativeMessagingHosts\{HostName}");
            key.SetValue("", manifestPath);
        }
    }

    private static byte[] Manifest(string exePath)
    {
        using var buffer = new MemoryStream();
        using (var json = new Utf8JsonWriter(buffer, new JsonWriterOptions { Indented = true }))
        {
            json.WriteStartObject();
            json.WriteString("name", HostName);
            json.WriteString("description", "HTorrent");
            json.WriteString("path", exePath);
            json.WriteString("type", "stdio");
            json.WriteStartArray("allowed_origins");
            json.WriteStringValue(ExtensionOrigin);
            json.WriteEndArray();
            json.WriteEndObject();
        }
        return buffer.ToArray();
    }

    /// <summary>Answers the extension until the browser closes the connection.</summary>
    public static int Run(string origin, string appPath)
    {
        if (!origin.Equals(ExtensionOrigin, StringComparison.OrdinalIgnoreCase)) return 1;
        using var input = Console.OpenStandardInput();
        using var output = Console.OpenStandardOutput();
        while (Read(input) is { } message)
        {
            string? error;
            try
            {
                using var document = JsonDocument.Parse(message);
                error = Handle(document.RootElement, appPath);
            }
            catch (Exception e) when (e is JsonException or InvalidOperationException or IOException or System.ComponentModel.Win32Exception or UnauthorizedAccessException)
            {
                error = e.Message;
            }
            Write(output, error);
        }
        return 0;
    }

    /// <summary>Returns null when the request succeeded, otherwise what went wrong.</summary>
    private static string? Handle(JsonElement message, string appPath)
    {
        switch (Text(message, "type"))
        {
            case "ping":
                return null;
            case "open":
                StartApp(appPath, null);
                return null;
            case "add-magnet":
                var link = Text(message, "url") ?? "";
                if (!IsMagnet(link)) return "That is not a magnet link";
                StartApp(appPath, link);
                return null;
            case "add-file":
                var path = Text(message, "path") ?? "";
                if (!IsTorrentFile(path)) return "That is not a .torrent file";
                StartApp(appPath, path);
                return null;
            default:
                return "Unknown request";
        }
    }

    private static string? Text(JsonElement message, string name) =>
        message.ValueKind == JsonValueKind.Object && message.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() : null;

    private static bool IsMagnet(string link) =>
        link.Length <= MaxMagnetLength && link.StartsWith("magnet:?", StringComparison.OrdinalIgnoreCase)
        && link.Contains("xt=urn:btih:", StringComparison.OrdinalIgnoreCase)
        && !link.Any(c => c <= ' ' || c == '"');

    private static bool IsTorrentFile(string path) =>
        Path.IsPathFullyQualified(path) && path.EndsWith(".torrent", StringComparison.OrdinalIgnoreCase)
        && !path.Contains('"') && new FileInfo(path) is { Exists: true, Length: <= MaxTorrentFileBytes };

    // The app passes this on to its running window, or opens if it is closed. UseShellExecute keeps it from inheriting
    // the browser's pipes, which would stop the browser from noticing when this helper exits.
    private static void StartApp(string appPath, string? argument)
    {
        var info = new ProcessStartInfo
        {
            FileName = appPath,
            Arguments = argument == null ? "" : '"' + argument + '"',
            UseShellExecute = true,
            WorkingDirectory = Path.GetDirectoryName(appPath)!,
        };
        using var process = Process.Start(info) ?? throw new InvalidOperationException("HTorrent did not start");
    }

    private static byte[]? Read(Stream input)
    {
        var header = new byte[4];
        if (!ReadExactly(input, header)) return null;
        var length = BitConverter.ToInt32(header, 0);
        if (length <= 0 || length > MaxMessageBytes) return null;
        var body = new byte[length];
        return ReadExactly(input, body) ? body : null;
    }

    private static bool ReadExactly(Stream input, byte[] buffer)
    {
        var done = 0;
        while (done < buffer.Length)
        {
            var count = input.Read(buffer, done, buffer.Length - done);
            if (count == 0) return false;
            done += count;
        }
        return true;
    }

    private static void Write(Stream output, string? error)
    {
        using var buffer = new MemoryStream();
        using (var json = new Utf8JsonWriter(buffer))
        {
            json.WriteStartObject();
            json.WriteBoolean("ok", error == null);
            if (error != null) json.WriteString("error", error);
            json.WriteString("version", Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "");
            json.WriteEndObject();
        }
        output.Write(BitConverter.GetBytes((int)buffer.Length));
        buffer.Position = 0;
        buffer.CopyTo(output);
        output.Flush();
    }
}
