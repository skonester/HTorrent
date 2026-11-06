#include "nexonic_torrent.h"
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/bdecode.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/write_resume_data.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/ip_filter.hpp>
#include <libtorrent/hex.hpp>
#include <libtorrent/peer_info.hpp>
#include <libtorrent/announce_entry.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <mutex>
#include <map>
#include <set>
#include <chrono>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <functional>
#include <cstdlib>
#ifdef _WIN32
#include <windows.h>
#endif
#include <cstring>
#include <memory>
#include <stdexcept>
#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif
namespace lt = libtorrent;
namespace fs = std::filesystem;
using json = nlohmann::json;
using Clock = std::chrono::steady_clock;
namespace {
constexpr std::size_t MAX_META = 8 * 1024 * 1024;
constexpr std::size_t MAX_REQUEST = 24 * 1024 * 1024;
constexpr int MAX_FILES = 10000;
std::string text(lt::string_view s) { return {s.data(), s.size()}; }
std::string encode(std::vector<char> const& bytes) {
    std::string out(4 * ((bytes.size() + 2) / 3), '\0');
    EVP_EncodeBlock(reinterpret_cast<unsigned char*>(out.data()), reinterpret_cast<unsigned char const*>(bytes.data()), int(bytes.size()));
    return out;
}
std::vector<char> decode(std::string const& value) {
    if (value.empty() || value.size() % 4 || value.size() > 4 * ((MAX_META + 2) / 3)) throw std::runtime_error("Invalid or excessive torrent metadata");
    for (std::size_t i = 0; i < value.size(); ++i) {
        unsigned char c = value[i];
        if (!(std::isalnum(c) || c == '+' || c == '/' || (c == '=' && i >= value.size() - 2))) throw std::runtime_error("Invalid metadata encoding");
    }
    std::vector<char> out(value.size() / 4 * 3);
    int count = EVP_DecodeBlock(reinterpret_cast<unsigned char*>(out.data()), reinterpret_cast<unsigned char const*>(value.data()), int(value.size()));
    if (count < 0) throw std::runtime_error("Invalid metadata encoding");
    if (value.back() == '=') --count;
    if (value[value.size() - 2] == '=') --count;
    out.resize(std::size_t(count));
    return out;
}
std::vector<char> read_file(fs::path const& p, std::size_t limit = MAX_META) {
    if (!fs::is_regular_file(p) || fs::file_size(p) > limit) throw std::runtime_error("Missing or excessive torrent file");
    std::ifstream in(p, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(in)), {});
    if (!in.eof() && !in.good()) throw std::runtime_error("Could not read torrent file");
    if (bytes.size() > limit) throw std::runtime_error("Torrent file exceeds the size limit");
    return bytes;
}
void private_directory(fs::path const& p) {
    if (!p.is_absolute()) throw std::runtime_error("Choose an absolute storage location");
    fs::path current;
    for (auto const& part : p) {
        current /= part;
        if (fs::is_symlink(fs::symlink_status(current))) throw std::runtime_error("Storage locations cannot contain symbolic links");
    }
    fs::create_directories(p);
#ifndef _WIN32
    fs::permissions(p, fs::perms::owner_all, fs::perm_options::replace);
#endif
}
void atomic_write(fs::path const& p, std::vector<char> const& bytes) {
    private_directory(p.parent_path());
    auto temp = p; temp += ".tmp";
#ifndef _WIN32
    int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (fd < 0) throw std::runtime_error("Cannot save torrent state");
    std::size_t done = 0;
    while (done < bytes.size()) {
        auto n = ::write(fd, bytes.data() + done, bytes.size() - done);
        if (n <= 0) { ::close(fd); throw std::runtime_error("Cannot save torrent state"); }
        done += std::size_t(n);
    }
    ::fsync(fd); ::close(fd);
    fs::rename(temp, p);
#else
    { std::ofstream out(temp, std::ios::binary | std::ios::trunc); out.write(bytes.data(), std::streamsize(bytes.size())); if (!out.good()) throw std::runtime_error("Cannot save torrent state"); }
    if (!MoveFileExW(temp.c_str(), p.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
        throw std::runtime_error("Cannot replace torrent checkpoint");
#endif
}
void atomic_json(fs::path const& p, json const& value) { auto s = value.dump(); atomic_write(p, {s.begin(), s.end()}); }
void component(std::string const& name) {
    if (name.empty() || name == "." || name == ".." || name.size() > 255 || name.back() == '.' || name.back() == ' ') throw std::runtime_error("Unsafe torrent filename");
    for (unsigned char c : name) if (c < 32 || c == 127 || std::string("/\\:*?\"<>|").find(char(c)) != std::string::npos) throw std::runtime_error("Unsafe torrent filename");
    auto stem = name.substr(0, name.find('.')); std::transform(stem.begin(), stem.end(), stem.begin(), [](unsigned char c) { return char(std::toupper(c)); });
    if (stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL" || (stem.size() == 4 && (stem.substr(0, 3) == "COM" || stem.substr(0, 3) == "LPT") && stem[3] >= '0' && stem[3] <= '9')) throw std::runtime_error("Reserved torrent filename");
}
void entry_flags(lt::bdecode_node const& entry) {
    if (text(entry.dict_find_string_value("attr")).find('l') != std::string::npos || entry.dict_find("symlink path")) throw std::runtime_error("Torrent symlinks are not supported");
}
void validate_info(lt::bdecode_node const& info) {
    if (info.type() != lt::bdecode_node::dict_t) throw std::runtime_error("Invalid torrent info");
    component(text(info.dict_find_string_value("name")));
    if (auto utf = info.dict_find("name.utf-8")) component(text(utf.string_value()));
    entry_flags(info);
    auto files = info.dict_find_list("files");
    if (files) {
        if (files.list_size() < 1 || files.list_size() > MAX_FILES) throw std::runtime_error("Torrent has too many files");
        for (int i = 0; i < files.list_size(); ++i) {
            auto f = files.list_at(i); entry_flags(f);
            for (auto key : {"path", "path.utf-8"}) {
                auto path = f.dict_find_list(key);
                if (!path && std::string(key) == "path.utf-8") continue;
                if (!path || path.list_size() == 0 || path.list_size() > 64) throw std::runtime_error("Invalid torrent path");
                for (int k = 0; k < path.list_size(); ++k) component(text(path.list_at(k).string_value()));
            }
        }
    }
    auto tree = info.dict_find_dict("file tree");
    if (tree) {
        int count = 0;
        std::function<void(lt::bdecode_node const&, int)> visit = [&](lt::bdecode_node const& node, int depth) {
            if (depth > 64 || node.type() != lt::bdecode_node::dict_t) throw std::runtime_error("Invalid v2 torrent tree");
            for (int i = 0; i < node.dict_size(); ++i) {
                auto item = node.dict_at(i); auto key = text(item.first);
                if (key.empty()) { entry_flags(item.second); if (++count > MAX_FILES) throw std::runtime_error("Torrent has too many files"); }
                else { component(key); visit(item.second, depth + 1); }
            }
        };
        visit(tree, 0);
    }
}
lt::bdecode_node bdecode(std::vector<char> const& bytes) {
    lt::error_code ec;
    auto node = lt::bdecode(lt::span<char const>(bytes.data(), bytes.size()), ec);
    if (ec) throw std::runtime_error("Invalid torrent metadata: " + ec.message());
    return node;
}
std::shared_ptr<lt::torrent_info> torrent_info(std::vector<char> const& bytes) {
    validate_info(bdecode(bytes).dict_find_dict("info"));
    lt::error_code ec;
    auto ti = std::make_shared<lt::torrent_info>(bytes.data(), int(bytes.size()), ec);
    if (ec) throw std::runtime_error(ec.message());
    if (ti->num_files() < 1 || ti->num_files() > MAX_FILES) throw std::runtime_error("Invalid torrent file count");
    std::set<std::string> seen;
    for (auto index : ti->files().file_range()) {
        auto path = ti->files().file_path(index);
        if (fs::u8path(path).is_absolute()) throw std::runtime_error("Unsafe torrent path");
        for (auto const& part : fs::u8path(path)) component(part.u8string());
        std::transform(path.begin(), path.end(), path.begin(), [](unsigned char c) { return char(std::tolower(c)); });
        if (!seen.insert(path).second || bool(ti->files().file_flags(index) & lt::file_storage::flag_symlink)) throw std::runtime_error("Duplicate path or symlink in torrent");
    }
    return ti;
}
void safe_payload(fs::path const& root, lt::torrent_info const& ti) {
    private_directory(root);
    for (auto index : ti.files().file_range()) {
        auto path = root / fs::u8path(ti.files().file_path(index));
        for (auto p = path; p != root && p != p.parent_path(); p = p.parent_path()) {
            if (fs::is_symlink(fs::symlink_status(p))) throw std::runtime_error("Torrent data path contains a symbolic link");
        }
        if (fs::exists(path) && (!fs::is_regular_file(path) || fs::hard_link_count(path) > 1)) throw std::runtime_error("Torrent data conflicts with an existing path");
    }
}
std::string digest_file(fs::path const& file) {
    std::ifstream in(file, std::ios::binary);
    if (!in) throw std::runtime_error("A selected torrent file is missing");
    auto ctx = std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)>(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    EVP_DigestInit_ex(ctx.get(), EVP_sha256(), nullptr);
    char buffer[65536];
    while (in) { in.read(buffer, sizeof(buffer)); auto n = in.gcount(); if (n > 0) EVP_DigestUpdate(ctx.get(), buffer, std::size_t(n)); }
    if (!in.eof()) throw std::runtime_error("Cannot verify downloaded file");
    unsigned char digest[EVP_MAX_MD_SIZE]; unsigned length = 0; EVP_DigestFinal_ex(ctx.get(), digest, &length);
    static char const* hex = "0123456789abcdef"; std::string out;
    for (unsigned i = 0; i < length; ++i) { out += hex[digest[i] >> 4]; out += hex[digest[i] & 15]; }
    return out;
}
std::vector<lt::download_priority_t> priorities(json const& input, int count) {
    std::vector<lt::download_priority_t> result(count, lt::default_priority);
    if (!input.is_null()) {
        if (!input.is_array() || int(input.size()) != count) throw std::runtime_error("Select priorities for every torrent file");
        for (int i = 0; i < count; ++i) {
            if (!input[i].is_number_integer()) throw std::runtime_error("Invalid file priority");
            int p = input[i].get<int>(); if (p < 0 || p > 7) throw std::runtime_error("File priorities range from 0 (skip) to 7");
            result[i] = lt::download_priority_t(p);
        }
    }
    if (std::none_of(result.begin(), result.end(), [](auto p) { return p != lt::dont_download; })) throw std::runtime_error("Select at least one file");
    return result;
}
struct Job {
    std::string id, source, error;
    fs::path destination;
    lt::torrent_handle handle;
    std::vector<char> metainfo;
    std::vector<lt::download_priority_t> selection;
    bool preview = true, running = false, verified = false, verifying = false, notified_check = false;
    bool seed = false, checkpoint_pending = false;
    double ratio_limit = 1.0;
    int download_limit = 0, upload_limit = 0, seed_seconds = 0, max_connections = -1, max_uploads = -1;
    bool sequential = false;
    json hashes = json::object();
    Clock::time_point last_save = Clock::now();
};
struct Engine {
    std::mutex mutex;
    fs::path state;
    bool allow_local;
    // "0.0.0.0:0,[::]:0" opens one socket per network adapter, each with its own ephemeral port.
    // Report the IPv4 loopback port (what same-machine peers reach), else the first IPv4 port.
    int listen_v4 = 0, listen_loopback = 0;
    lt::session session;
    std::map<std::string, Job> jobs;
    static lt::session_params settings(json const& config) {
        lt::settings_pack settings;
        settings.set_str(lt::settings_pack::listen_interfaces, config.value("listen", "0.0.0.0:0,[::]:0"));
        settings.set_bool(lt::settings_pack::enable_dht, config.value("dht", true));
        settings.set_bool(lt::settings_pack::enable_lsd, false);
        // Port forwarding on the router (UPnP and NAT-PMP) is off unless the user turns it on.
        settings.set_bool(lt::settings_pack::enable_upnp, config.value("port_forwarding", false));
        settings.set_bool(lt::settings_pack::enable_natpmp, config.value("port_forwarding", false));
        auto encryption = config.value("encryption", std::string("prefer"));
        int policy = encryption == "require" ? lt::settings_pack::pe_forced : encryption == "off" ? lt::settings_pack::pe_disabled : lt::settings_pack::pe_enabled;
        settings.set_int(lt::settings_pack::out_enc_policy, policy); settings.set_int(lt::settings_pack::in_enc_policy, policy);
        if (encryption == "require") settings.set_int(lt::settings_pack::allowed_enc_level, lt::settings_pack::pe_rc4);
        // An HTTP or SOCKS5 proxy carries peer, tracker and DHT traffic, so torrents follow the user's proxy setting.
        if (config.contains("proxy") && config["proxy"].is_object()) {
            auto const& proxy = config["proxy"]; auto type = proxy.value("type", std::string()); auto host = proxy.value("host", std::string());
            int port = proxy.value("port", 0); auto user = proxy.value("username", std::string());
            if ((type != "http" && type != "socks5") || host.empty() || host.size() > 255 || port < 1 || port > 65535) throw std::runtime_error("Invalid torrent proxy");
            settings.set_int(lt::settings_pack::proxy_type, type == "http" ? (user.empty() ? lt::settings_pack::http : lt::settings_pack::http_pw)
                : (user.empty() ? lt::settings_pack::socks5 : lt::settings_pack::socks5_pw));
            settings.set_str(lt::settings_pack::proxy_hostname, host); settings.set_int(lt::settings_pack::proxy_port, port);
            if (!user.empty()) { settings.set_str(lt::settings_pack::proxy_username, user); settings.set_str(lt::settings_pack::proxy_password, proxy.value("password", std::string())); }
            settings.set_bool(lt::settings_pack::proxy_hostnames, true);
            settings.set_bool(lt::settings_pack::proxy_peer_connections, true);
            settings.set_bool(lt::settings_pack::proxy_tracker_connections, true);
        }
        settings.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, config.value("allow_local_peers", false));
        settings.set_bool(lt::settings_pack::apply_ip_filter_to_trackers, true);
        settings.set_int(lt::settings_pack::max_metadata_size, int(MAX_META));
        settings.set_int(lt::settings_pack::alert_mask, int(static_cast<std::uint32_t>(lt::alert_category::error | lt::alert_category::status | lt::alert_category::storage)));
        return lt::session_params(settings);
    }
    explicit Engine(json const& config) : state(fs::u8path(config.at("state_dir").get<std::string>())), allow_local(config.value("allow_local_peers", false)), session(settings(config)) {
        private_directory(state);
        if (!allow_local) {
            lt::ip_filter filter;
            for (auto const& range : std::vector<std::pair<std::string, std::string>>{
                {"0.0.0.0","0.255.255.255"},{"10.0.0.0","10.255.255.255"},{"100.64.0.0","100.127.255.255"},
                {"127.0.0.0","127.255.255.255"},{"169.254.0.0","169.254.255.255"},{"172.16.0.0","172.31.255.255"},
                {"192.168.0.0","192.168.255.255"},{"224.0.0.0","255.255.255.255"},{"::","::1"},
                {"fc00::","fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"},{"fe80::","febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff"},
                {"ff00::","ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"}})
                filter.add_rule(lt::make_address(range.first), lt::make_address(range.second), lt::ip_filter::blocked);
            session.set_ip_filter(filter);
        }
    }
    fs::path manifest(std::string const& id) const { return state / ("torrent-" + id + ".json"); }
    fs::path checkpoint(std::string const& id) const { return state / ("torrent-" + id + ".resume"); }
    void persist(Job const& j) {
        if (j.preview) return;
        json p = json::array(); for (auto value : j.selection) p.push_back(int(static_cast<std::uint8_t>(value)));
        atomic_json(manifest(j.id), {{"id",j.id},{"source",j.source},{"destination",j.destination.u8string()}, {"metainfo",j.metainfo.empty() ? "" : encode(j.metainfo)},
            {"priorities",p},{"seed",j.seed},{"ratio_limit",j.ratio_limit},{"seed_seconds",j.seed_seconds}, {"download_limit",j.download_limit},{"upload_limit",j.upload_limit}});
    }
    Job& job(json const& request) {
        auto id = request.at("id").get<std::string>(); auto found = jobs.find(id);
        if (found == jobs.end()) throw std::runtime_error("Torrent task not found");
        return found->second;
    }
    void save(Job& j) {
        if (!j.preview && j.handle.is_valid() && j.handle.status().has_metadata && !j.checkpoint_pending) {
            j.handle.save_resume_data(lt::torrent_handle::flush_disk_cache | lt::torrent_handle::save_info_dict);
            j.checkpoint_pending = true; j.last_save = Clock::now();
        }
    }
    void metadata(Job& j) {
        auto ti = j.handle.torrent_file();
        if (!ti) return;
        lt::add_torrent_params p; p.ti = std::const_pointer_cast<lt::torrent_info>(ti);
        for (auto const& tracker : j.handle.trackers()) { p.trackers.push_back(tracker.url); p.tracker_tiers.push_back(tracker.tier); }
        auto bytes = lt::write_torrent_file_buf(p, lt::write_flags::allow_missing_piece_layer | lt::write_flags::no_http_seeds);
        auto checked = torrent_info(bytes); // Raw v1/v2/hybrid names and native paths must both pass.
        j.metainfo = std::move(bytes);
        if (j.selection.empty()) j.selection = priorities(nullptr, checked->num_files());
    }
    void pump() {
        std::vector<lt::alert*> alerts; session.pop_alerts(&alerts);
        for (auto* alert : alerts)
            if (auto* ls = lt::alert_cast<lt::listen_succeeded_alert>(alert))
                if (ls->socket_type == lt::socket_type_t::tcp && ls->address.is_v4())
                {
                    if (ls->address.is_loopback()) listen_loopback = ls->port;
                    else if (!listen_v4) listen_v4 = ls->port;
                }
        for (auto* alert : alerts) for (auto& [id,j] : jobs) {
            auto* ta = dynamic_cast<lt::torrent_alert*>(alert); if (!ta || ta->handle != j.handle) continue;
            if (auto* saved = lt::alert_cast<lt::save_resume_data_alert>(alert)) {
                j.checkpoint_pending = false;
                auto data = lt::write_resume_data_buf(saved->params);
                if (data.size() <= MAX_META) atomic_write(checkpoint(id), data);
            }
            if (lt::alert_cast<lt::save_resume_data_failed_alert>(alert)) j.checkpoint_pending = false;
            if (lt::alert_cast<lt::torrent_checked_alert>(alert) && j.verifying) j.notified_check = true;
        }
        for (auto& [id,j] : jobs) {
            try {
                auto s = j.handle.status();
                if (s.errc) { j.error = s.errc.message(); j.running = false; }
                if (s.has_metadata && j.metainfo.empty()) metadata(j);
                if (j.preview || (!j.running && !j.verifying) || !s.has_metadata) continue;
                if (j.verifying && j.notified_check) {
                    j.verifying = false; j.notified_check = false;
                    if (s.is_finished) {
                        auto ti = j.handle.torrent_file(); safe_payload(j.destination, *ti); j.hashes = json::object();
                        for (auto index : ti->files().file_range()) if (j.selection[static_cast<std::size_t>(static_cast<int>(index))] != lt::dont_download && !bool(ti->files().file_flags(index) & lt::file_storage::flag_pad_file)) {
                            auto relative = ti->files().file_path(index); auto path = j.destination / fs::u8path(relative);
                            if (ti->files().file_size(index) == 0 && !fs::exists(path)) { fs::create_directories(path.parent_path()); std::ofstream empty(path); }
                            j.hashes[fs::u8path(relative).generic_u8string()] = digest_file(path);
                        }
                        j.verified = true; save(j);
                    }
                }
                if (s.is_finished && !j.verified && !j.verifying) {
                    j.verifying = true; j.handle.force_recheck();
                }
                if (j.verified && (!j.seed || (j.ratio_limit > 0 && double(s.all_time_upload) / std::max<std::int64_t>(1,s.total_wanted) >= j.ratio_limit)
                    || (j.seed_seconds > 0 && s.seeding_duration.count() >= j.seed_seconds))) {
                    j.running = false; j.handle.pause(); save(j);
                }
                if (Clock::now() - j.last_save >= std::chrono::seconds(5)) { persist(j); save(j); }
            } catch (std::exception const& e) { j.error = e.what(); j.running = false; j.handle.pause(); }
        }
    }
    json snapshot(Job& j) {
        auto s = j.handle.status(); auto ti = j.handle.torrent_file();
        std::string status = !j.error.empty() ? "failed" : j.preview ? (s.has_metadata ? "preview" : "metadata")
            : j.verified ? (j.running ? "seeding" : "complete") : j.verifying ? "verifying" : !j.running ? "paused"
            : s.state == lt::torrent_status::checking_files || s.state == lt::torrent_status::checking_resume_data ? "checking" : "downloading";
        json result = {{"id",j.id},{"state",status},{"source",j.source},{"destination",j.destination.u8string()}, {"error",j.error},
            {"payload_complete",j.verified},{"received",s.total_wanted_done},{"wanted_size",s.total_wanted}, {"download_speed",s.download_payload_rate},{"upload_speed",s.upload_payload_rate},
            {"uploaded",s.all_time_upload},{"peers",s.num_peers},{"seeds",s.num_seeds},{"eta",s.download_payload_rate > 0 ? (s.total_wanted - s.total_wanted_done) / s.download_payload_rate : -1},
            {"ratio",double(s.all_time_upload) / std::max<std::int64_t>(1,s.total_wanted)}, {"seed",j.seed},{"ratio_limit",j.ratio_limit},{"seed_seconds",j.seed_seconds},
            {"download_limit",j.download_limit},{"upload_limit",j.upload_limit},{"sha256",j.hashes}};
        if (ti) {
            result["name"] = ti->name(); result["size"] = ti->total_size(); result["private"] = ti->priv(); result["magnet"] = lt::make_magnet_uri(*ti);
            result["info_hash_v1"] = ti->info_hashes().has_v1() ? lt::aux::to_hex(ti->info_hashes().v1.to_string()) : "";
            result["info_hash_v2"] = ti->info_hashes().has_v2() ? lt::aux::to_hex(ti->info_hashes().v2.to_string()) : "";
            result["metainfo"] = j.metainfo.empty() ? "" : encode(j.metainfo);
            result["files"] = json::array(); auto progress = j.handle.file_progress();
            for (auto index : ti->files().file_range()) result["files"].push_back({{"index",int(index)}, {"path",fs::u8path(ti->files().file_path(index)).generic_u8string()}, {"size",ti->files().file_size(index)},
                {"received",progress[static_cast<std::size_t>(static_cast<int>(index))]},{"priority",j.selection.empty() ? 4 : int(static_cast<std::uint8_t>(j.selection[static_cast<std::size_t>(static_cast<int>(index))]))}, {"pad",bool(ti->files().file_flags(index) & lt::file_storage::flag_pad_file)}});
        }
        result["trackers"] = json::array(); result["tracker_status"] = json::array();
        for (auto const& t : j.handle.trackers()) {
            result["trackers"].push_back(t.url);
            std::string message; int seeds = -1, peers = -1; bool working = false, updating = false;
            for (auto const& endpoint : t.endpoints) for (auto version : {lt::protocol_version::V1, lt::protocol_version::V2}) {
                auto const& hash = endpoint.info_hashes[version];
                if (hash.last_error) message = hash.last_error.message(); else if (!hash.message.empty() && message.empty()) message = hash.message;
                seeds = std::max(seeds, hash.scrape_complete); peers = std::max(peers, hash.scrape_incomplete);
                working = working || (hash.start_sent && hash.fails == 0 && !hash.last_error); updating = updating || hash.updating;
            }
            result["tracker_status"].push_back({{"url",t.url},{"tier",int(t.tier)},{"working",working},{"updating",updating},{"message",message},{"seeds",seeds},{"peers",peers}});
        }
        // What a BitTorrent client shows for a torrent: swarm size, connections, availability and the pieces it has.
        result["connections"] = s.num_connections; result["seeds_total"] = s.num_complete; result["peers_total"] = s.num_incomplete;
        result["seeds_connected"] = s.num_seeds; result["peers_connected"] = s.num_peers - s.num_seeds;
        result["availability"] = s.distributed_copies; result["downloaded"] = s.all_time_download;
        result["pieces_done"] = s.num_pieces; result["piece_count"] = ti ? ti->num_pieces() : 0; result["piece_size"] = ti ? ti->piece_length() : 0;
        result["active_seconds"] = std::int64_t(s.active_duration.count()); result["seeding_seconds"] = std::int64_t(s.seeding_duration.count());
        result["max_connections"] = j.max_connections; result["max_uploads"] = j.max_uploads; result["sequential"] = j.sequential;
        json map = json::array(); int count = s.pieces.size();
        if (count > 0) {
            int buckets = std::min(count, 100);
            for (int b = 0; b < buckets; ++b) {
                int from = int(std::int64_t(b) * count / buckets), to = int(std::int64_t(b + 1) * count / buckets), have = 0;
                for (int i = from; i < to; ++i) if (s.pieces.get_bit(lt::piece_index_t(i))) ++have;
                map.push_back(to > from ? have * 100 / (to - from) : 0);
            }
        }
        result["piece_map"] = map;
        return result;
    }
    void add(json const& request) {
        auto id = request.at("id").get<std::string>();
        if (id.empty() || id.size() > 80 || !std::all_of(id.begin(),id.end(),[](unsigned char c){return std::isalnum(c) || c == '-' || c == '_';})) throw std::runtime_error("Invalid torrent task ID");
        if (jobs.count(id)) throw std::runtime_error("Torrent task already exists");
        Job j; j.id = id; j.source = request.value("source", ""); j.preview = request.value("preview", true);
        j.destination = j.preview ? state / ("preview-" + id) : fs::u8path(request.at("destination").get<std::string>());
        lt::add_torrent_params p;
        if (request.contains("metainfo") && !request["metainfo"].get<std::string>().empty()) { j.metainfo = decode(request["metainfo"]); p.ti = torrent_info(j.metainfo); }
        else if (j.source.rfind("magnet:?",0) == 0) { if (j.source.size() > 32768) throw std::runtime_error("Magnet link is too long"); lt::error_code ec; p = lt::parse_magnet_uri(j.source, ec); if (ec) throw std::runtime_error(ec.message()); }
        else { j.metainfo = read_file(fs::u8path(j.source)); p.ti = torrent_info(j.metainfo); }
        if (!j.preview && !p.ti) throw std::runtime_error("Preview torrent metadata before downloading");
        if (p.ti) { j.selection = priorities(request.value("priorities",json()),p.ti->num_files()); safe_payload(j.destination,*p.ti); }
        if (!j.preview && fs::is_regular_file(checkpoint(id))) {
            try {
                auto bytes = read_file(checkpoint(id)); auto node = bdecode(bytes); if (auto info = node.dict_find_dict("info")) validate_info(info);
                auto restored = lt::read_resume_data(lt::span<char const>(bytes.data(),bytes.size()));
                if (restored.info_hashes != p.ti->info_hashes()) throw std::runtime_error("Resume hash mismatch");
                restored.ti = p.ti; restored.flags = p.flags; restored.renamed_files.clear(); restored.trackers = p.trackers;
                restored.tracker_tiers = p.tracker_tiers; restored.piece_priorities.clear(); p = std::move(restored);
            } catch (...) { /* Missing/corrupt/incompatible checkpoints cause native piece checking. */ }
        }
        p.save_path = j.destination.u8string(); p.peers.clear(); p.banned_peers.clear(); p.dht_nodes.clear(); p.url_seeds.clear(); p.http_seeds.clear();
        if (p.ti) p.ti->set_web_seeds({});
        p.flags &= ~lt::torrent_flags::auto_managed;
        p.flags |= lt::torrent_flags::duplicate_is_error;
        if (p.ti && p.ti->priv()) p.flags |= lt::torrent_flags::disable_dht | lt::torrent_flags::disable_pex | lt::torrent_flags::disable_lsd;
        if (j.preview) { p.flags |= lt::torrent_flags::upload_mode | lt::torrent_flags::default_dont_download; p.flags &= ~lt::torrent_flags::paused; }
        else { p.flags |= lt::torrent_flags::paused; p.file_priorities = j.selection; }
        lt::error_code ec; j.handle = session.add_torrent(p,ec); if (ec) throw std::runtime_error(ec.message());
        jobs.emplace(id,std::move(j));
        if (!request.value("preview",true)) configure(jobs.at(id),request);
    }
    void configure(Job& j, json const& r) {
        if (j.preview) throw std::runtime_error("Metadata previews cannot download content");
        auto ti = j.handle.torrent_file(); if (!ti) throw std::runtime_error("Torrent metadata is missing");
        if (r.contains("priorities")) { j.selection = priorities(r["priorities"],ti->num_files()); j.verified = false; j.hashes = json::object(); }
        j.download_limit = r.value("download_limit",j.download_limit); j.upload_limit = r.value("upload_limit",j.upload_limit);
        j.seed = r.value("seed",j.seed); j.ratio_limit = r.value("ratio_limit",j.ratio_limit); j.seed_seconds = r.value("seed_seconds",j.seed_seconds);
        if (j.download_limit < 0 || j.upload_limit < 0 || !std::isfinite(j.ratio_limit) || j.ratio_limit < 0 || j.seed_seconds < 0) throw std::runtime_error("Invalid torrent limits");
        // -1 means no limit; libtorrent needs at least 2 connections and 1 upload slot.
        int connections = r.value("max_connections",j.max_connections), uploads = r.value("max_uploads",j.max_uploads);
        if (connections != -1 && (connections < 2 || connections > 10000)) throw std::runtime_error("Connections per torrent must be between 2 and 10,000");
        if (uploads != -1 && (uploads < 1 || uploads > 10000)) throw std::runtime_error("Upload slots must be between 1 and 10,000");
        j.max_connections = connections; j.max_uploads = uploads; j.sequential = r.value("sequential",j.sequential);
        j.handle.set_download_limit(j.download_limit); j.handle.set_upload_limit(j.upload_limit); j.handle.prioritize_files(j.selection);
        j.handle.set_max_connections(j.max_connections); j.handle.set_max_uploads(j.max_uploads);
        if (j.sequential) j.handle.set_flags(lt::torrent_flags::sequential_download); else j.handle.unset_flags(lt::torrent_flags::sequential_download);
        if (r.contains("trackers")) {
            std::vector<lt::announce_entry> trackers;
            for (auto const& url : r["trackers"]) { auto value = url.get<std::string>(); if (value.size() > 4096 || !(value.rfind("https://",0)==0 || value.rfind("http://",0)==0 || value.rfind("udp://",0)==0)) throw std::runtime_error("Tracker must use HTTP, HTTPS or UDP"); trackers.emplace_back(value); }
            if (trackers.size() > 128) throw std::runtime_error("Too many trackers");
            j.handle.replace_trackers(trackers); j.handle.force_reannounce();
        }
        persist(j);
    }
    json call(json const& r) {
        pump(); auto action = r.at("action").get<std::string>();
        if (action == "add") { add(r); return snapshot(job(r)); }
        if (action == "restore") {
            auto value = read_file(manifest(r.at("id").get<std::string>()),MAX_REQUEST); auto saved = json::parse(value.begin(),value.end()); saved["preview"] = false; add(saved); return snapshot(job(saved));
        }
        if (action == "list") { json list = json::array(); for (auto& [id,j] : jobs) list.push_back(snapshot(j)); return {{"jobs",list},{"listen_port",listen_loopback ? listen_loopback : listen_v4 ? listen_v4 : session.listen_port()}}; }
        if (action == "status") return snapshot(job(r));
        if (action == "configure") { auto& j = job(r); configure(j,r); return snapshot(j); }
        if (action == "resume") { auto& j = job(r); if (j.preview) throw std::runtime_error("Metadata-only task cannot be resumed as a download"); j.error.clear(); j.running = true; j.handle.unset_flags(lt::torrent_flags::upload_mode); j.handle.resume(); persist(j); return snapshot(j); }
        if (action == "pause") { auto& j = job(r); j.running = false; j.handle.pause(); persist(j); save(j); return snapshot(j); }
        if (action == "recheck") { auto& j = job(r); j.verified = false; j.verifying = true; j.notified_check = false; j.hashes = json::object(); j.handle.force_recheck(); return snapshot(j); }
        if (action == "remove") {
            auto& j = job(r); session.remove_torrent(j.handle); std::error_code ignored;
            fs::remove(manifest(j.id),ignored); fs::remove(checkpoint(j.id),ignored); jobs.erase(j.id); return {{"removed",true}};
        }
        if (action == "connect_peer") { if (!allow_local) throw std::runtime_error("Direct peers require explicit local-peer opt-in"); auto& j = job(r); j.handle.connect_peer({lt::make_address(r.value("address","127.0.0.1")),static_cast<unsigned short>(r.at("port").get<int>())}); return snapshot(j); }
        if (action == "create_torrent") {
            fs::path path = fs::u8path(r.at("path").get<std::string>()); if (!path.is_absolute() || fs::is_symlink(fs::symlink_status(path))) throw std::runtime_error("Choose a regular local file or folder");
            lt::file_storage files;
            lt::add_files(files,path.u8string(), [](std::string const& candidate) { if (fs::is_symlink(fs::symlink_status(fs::u8path(candidate)))) throw std::runtime_error("Do not create torrents from symbolic links"); return true; });
            if (files.num_files() < 1 || files.num_files() > MAX_FILES) throw std::runtime_error("Invalid torrent file count");
            auto format = r.value("format","v1");
            auto flags = format == "v1" ? lt::create_torrent::v1_only : format == "v2" ? lt::create_torrent::v2_only : lt::create_flags_t{};
            if (format != "v1" && format != "v2" && format != "hybrid") throw std::runtime_error("Choose v1, v2 or hybrid torrent format");
            lt::create_torrent creator(files,0,flags);
            if (r.contains("trackers")) for (auto const& tracker : r["trackers"]) creator.add_tracker(tracker.get<std::string>());
            lt::error_code ec; lt::set_piece_hashes(creator,path.parent_path().u8string(),ec); if (ec) throw std::runtime_error(ec.message());
            std::vector<char> bytes; lt::bencode(std::back_inserter(bytes),creator.generate()); torrent_info(bytes);
            return {{"metainfo",encode(bytes)}};
        }
        if (action == "flush") {
            for (auto& [id,j] : jobs) { persist(j); save(j); }
            auto until = Clock::now() + std::chrono::seconds(5);
            while (Clock::now() < until && std::any_of(jobs.begin(),jobs.end(),[](auto const& item) { return item.second.checkpoint_pending; })) {
                pump(); session.wait_for_alert(std::chrono::milliseconds(100));
            }
            return {{"flushed",true}};
        }
        if (action == "peers") {
            auto& j = job(r); std::vector<lt::peer_info> info; j.handle.get_peer_info(info); json list = json::array();
            for (auto const& p : info) {
                if (list.size() >= 300) break;
                list.push_back({{"address",p.ip.address().to_string()},{"port",int(p.ip.port())},{"client",p.client},{"progress",double(p.progress)},
                    {"download_speed",p.payload_down_speed},{"upload_speed",p.payload_up_speed},{"downloaded",p.total_download},{"uploaded",p.total_upload},
                    {"seed",bool(p.flags & lt::peer_info::seed)},{"encrypted",bool(p.flags & (lt::peer_info::rc4_encrypted | lt::peer_info::plaintext_encrypted))},
                    {"incoming",!(p.flags & lt::peer_info::local_connection)},{"utp",bool(p.flags & lt::peer_info::utp_socket)}});
            }
            return {{"peers",list}};
        }
        if (action == "reannounce") { auto& j = job(r); j.handle.force_reannounce(); if (j.handle.status().has_metadata) j.handle.force_dht_announce(); return snapshot(j); }
        if (action == "settings") {
            lt::settings_pack settings; settings.set_bool(lt::settings_pack::enable_dht,r.value("dht",true));
            settings.set_int(lt::settings_pack::download_rate_limit,std::max(0,r.value("download_limit",0)));
            settings.set_int(lt::settings_pack::upload_rate_limit,std::max(0,r.value("upload_limit",0))); session.apply_settings(settings); return {{"configured",true}};
        }
        throw std::runtime_error("Unknown native torrent action");
    }
    ~Engine() {
        try {
            for (auto& [id,j] : jobs) { j.running = false; j.handle.pause(); persist(j); save(j); }
            auto until = Clock::now() + std::chrono::seconds(5);
            while (Clock::now() < until && std::any_of(jobs.begin(),jobs.end(),[](auto const& p) { return p.second.checkpoint_pending; })) { pump(); session.wait_for_alert(std::chrono::milliseconds(100)); }
        } catch (...) { }
    }
};
char* owned(json const& value) { auto data = value.dump(-1,' ',false,json::error_handler_t::replace); auto out = static_cast<char*>(std::malloc(data.size()+1)); if (!out) return nullptr; std::memcpy(out,data.c_str(),data.size()+1); return out; }
thread_local std::string last_error;
}
extern "C" {
void* ndm_torrent_create(char const* config) { try { return new Engine(json::parse(config)); } catch (std::exception const& e) { last_error=e.what(); return nullptr; } catch (...) { last_error="Native torrent initialization failed"; return nullptr; } }
char* ndm_torrent_call(void* context, char const* request) {
    try {
        if (!context) throw std::runtime_error(last_error.empty()?"Torrent engine is unavailable":last_error);
        if (!request || std::strlen(request) > MAX_REQUEST) throw std::runtime_error("Torrent request is too large");
        auto& engine=*static_cast<Engine*>(context); std::lock_guard<std::mutex> lock(engine.mutex);
        auto result=engine.call(json::parse(request)); result["ok"]=true; return owned(result);
    } catch (std::exception const& e) { return owned({{"ok",false},{"error",e.what()}}); } catch (...) { return owned({{"ok",false},{"error","Native torrent operation failed"}}); }
}
void ndm_torrent_free(char* data) { std::free(data); }
void ndm_torrent_destroy(void* context) { delete static_cast<Engine*>(context); }
char const* ndm_torrent_version() { return LIBTORRENT_VERSION; }
}
