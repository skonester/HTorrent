namespace HTorrentPlayer

open System
open System.IO
open System.Text.Json
open System.Drawing
open InfiniFrame

module PlayerWindow =
    let appName = "HTorrentPlayer"

    let windowTitle (mediaTitle: string option) =
        match mediaTitle with
        | Some title when not (String.IsNullOrWhiteSpace title) -> sprintf "%s - %s" title appName
        | _ -> appName

    // The page reads ?src=&title= on load and starts playback (see web/index.html).
    let startUrl (indexPath: string) (source: string option) (mediaTitle: string option) =
        let query =
            [ source |> Option.map (fun s -> "src=" + Uri.EscapeDataString s)
              mediaTitle |> Option.map (fun t -> "title=" + Uri.EscapeDataString t) ]
            |> List.choose id
        let pageUri = Uri(indexPath).AbsoluteUri
        if query.IsEmpty then pageUri else pageUri + "?" + String.Join("&", query)

    let create (source: string option) (mediaTitle: string option) =
        let baseDir = AppContext.BaseDirectory
        let iconPath = Path.Combine(baseDir, "web", "icon.ico")
        let indexPath = Path.Combine(baseDir, "web", "index.html")
        // WebView2 needs a writable profile folder; the install directory is read-only under Program Files.
        let profileDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HTorrent", appName)
        Directory.CreateDirectory(profileDir) |> ignore
        let mutable isDarkMode = false

        let getWindowInfo (window: IInfiniFrameWindow) =
            try
                dict [
                    "x", box window.Left
                    "y", box window.Top
                    "width", box window.Width
                    "height", box window.Height
                    "isMaximized", box window.Maximized
                    "isMinimized", box window.Minimized
                    "isFocused", box window.Focused
                    "isDarkMode", box isDarkMode
                ] |> JsonSerializer.Serialize
            with _ -> "{}"

        let dispatchWindowEvent (window: IInfiniFrameWindow) eventName =
            try
                let info = getWindowInfo window
                let payload = dict [ "type", box eventName; "detail", box info ]
                window.SendWebMessage(JsonSerializer.Serialize(payload)) |> ignore
            with _ -> ()

        let tryReadAction (message: string) =
            try
                if String.IsNullOrWhiteSpace(message) then
                    None
                else
                    let trimmed = message.Trim()
                    if trimmed.StartsWith("{") then
                        use data = JsonDocument.Parse(trimmed)
                        match data.RootElement.TryGetProperty("action") with
                        | true, action when action.ValueKind = JsonValueKind.String -> Some(action.GetString())
                        | _ -> None
                    else
                        Some(trimmed.Trim('"'))
            with _ -> None

        let builder =
            InfiniFrameWindowBuilder.Create()
                .SetTitle(windowTitle mediaTitle)
                .SetUseOsDefaultSize(false)
                .SetSize(1280, 720)
                .Center()
                .SetResizable(true)
                .SetDevToolsEnabled(false)
                .SetContextMenuEnabled(false)
                .SetMediaAutoplayEnabled(true)
                .SetTemporaryFilesPath(profileDir)
                .SetFileSystemAccessEnabled(true)
                .SetWebSecurityEnabled(false)
                .SetAllowedNavigationSchemes([| "about"; "file"; "http"; "https"; "blob"; "data" |])
                .SetAllowedExternalSchemes([||])

        if File.Exists(iconPath) then
            builder.SetIconFile(iconPath) |> ignore

        builder.SetStartUrl(if File.Exists(indexPath) then startUrl indexPath source mediaTitle else "about:blank") |> ignore

        // Build the window
        let window = builder.Build()

        // Register handlers AFTER building to ensure the native handle is valid
        window.RegisterWebMessageReceivedHandler(fun window message ->
            try
                match tryReadAction message with
                | Some "close" -> window.Close()
                | Some "minimize" ->
                    window.SetMinimized(true) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "maximize" ->
                    window.SetMaximized(not window.Maximized) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "restore" ->
                    window.SetMaximized(false) |> ignore
                    window.SetMinimized(false) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "dragMove" -> ()
                | Some "setTitle" ->
                    use data = JsonDocument.Parse(message)
                    let title =
                        match data.RootElement.TryGetProperty("title") with
                        | true, t when t.ValueKind = JsonValueKind.String -> Some(t.GetString())
                        | _ -> None
                    window.SetTitle(windowTitle title) |> ignore
                | Some "setSize" ->
                    use data = JsonDocument.Parse(message)
                    let w = data.RootElement.GetProperty("width").GetInt32()
                    let h = data.RootElement.GetProperty("height").GetInt32()
                    window.SetSize(w, h) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "move" ->
                    use data = JsonDocument.Parse(message)
                    let x = data.RootElement.GetProperty("x").GetInt32()
                    let y = data.RootElement.GetProperty("y").GetInt32()
                    window.SetLocation(Point(x, y)) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "setAlwaysOnTop" ->
                    use data = JsonDocument.Parse(message)
                    window.SetTopMost(data.RootElement.GetProperty("top").GetBoolean()) |> ignore
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "setDarkMode" ->
                    use data = JsonDocument.Parse(message)
                    isDarkMode <- data.RootElement.GetProperty("dark").GetBoolean()
                    dispatchWindowEvent window "windowInfoChanged"
                | Some "getWindowInfo" ->
                    let info = getWindowInfo window
                    let payload = dict [ "type", "windowInfoResult"; "detail", info ]
                    window.SendWebMessage(JsonSerializer.Serialize(payload)) |> ignore
                | _ -> ()
            with _ -> ()
        ) |> ignore

        window.RegisterSizeChangedHandler(fun window _ -> dispatchWindowEvent window "windowInfoChanged") |> ignore
        window.RegisterLocationChangedHandler(fun window _ -> dispatchWindowEvent window "windowInfoChanged") |> ignore

        window
