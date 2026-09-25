namespace HTorrentPlayer

open System
open System.IO
open System.Runtime.InteropServices

/// HTorrentPlayer [--title <name>] [<http(s) stream URL | media file path>]
/// HTorrent starts it with a torrent file's stream URL from its local HTTP API.
module Program =
    [<DllImport("user32.dll", CharSet = CharSet.Unicode)>]
    extern int MessageBox(nativeint hWnd, string text, string caption, uint32 flags)

    let parseArgs (argv: string[]) =
        let rec loop args source title =
            match args with
            | "--title" :: value :: rest -> loop rest source (Some value)
            | value :: rest when source = None -> loop rest (Some value) title
            | _ :: rest -> loop rest source title
            | [] -> source, title
        let source, title = loop (List.ofArray argv) None None
        let source =
            source |> Option.bind (fun value ->
                if File.Exists(value) then Some(Uri(Path.GetFullPath(value)).AbsoluteUri)
                else
                    match Uri.TryCreate(value, UriKind.Absolute) with
                    | true, uri when uri.Scheme = Uri.UriSchemeHttp || uri.Scheme = Uri.UriSchemeHttps -> Some(uri.AbsoluteUri)
                    | _ -> None)
        let title =
            match title, source with
            | Some _, _ -> title
            | None, Some s -> Some(Path.GetFileName(Uri(s).LocalPath))
            | None, None -> None
        source, title

    [<EntryPoint>]
    [<STAThread>]
    let main argv =
        try
            let source, title = parseArgs argv
            let window = PlayerWindow.create source title
            window.WaitForClose()
            0
        with ex ->
            MessageBox(0n, sprintf "HTorrentPlayer could not start:\n\n%s\n\nMake sure the Microsoft Edge WebView2 Runtime is installed." ex.Message, "HTorrentPlayer", 0x10u) |> ignore
            1
