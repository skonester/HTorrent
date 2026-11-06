#include "nexonic_torrent.h"
#include <nlohmann/json.hpp>
#include <openssl/evp.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>
#include <chrono>
#include <stdexcept>
#include <memory>
using json = nlohmann::json;
namespace fs = std::filesystem;
using namespace std::chrono_literals;
struct Context {
    void* p;
    explicit Context(fs::path const& state) {
        auto value = json{{"state_dir",state.u8string()},{"dht",false},{"allow_local_peers",true},{"listen","127.0.0.1:0"}}.dump();
        p=ndm_torrent_create(value.c_str()); if (!p) throw std::runtime_error("Native context initialization failed");
    }
    ~Context() { ndm_torrent_destroy(p); }
    json call(json value, bool success=true) {
        auto text=value.dump(); char* data=ndm_torrent_call(p,text.c_str()); if (!data) throw std::runtime_error("No native response");
        auto result=json::parse(data); ndm_torrent_free(data);
        if (success && !result.value("ok",false)) throw std::runtime_error(result.dump());
        if (!success && result.value("ok",false)) throw std::runtime_error("Unsafe action unexpectedly succeeded");
        return result;
    }
};
void require(bool condition, std::string const& message) { if (!condition) throw std::runtime_error(message); }
template<class P> json until(Context& c, std::string const& id, P predicate, int seconds=35) {
    auto end=std::chrono::steady_clock::now()+std::chrono::seconds(seconds); json last;
    do {
        last=c.call({{"action","status"},{"id",id}});
        if (last.value("state","")=="failed") throw std::runtime_error(last.dump());
        if (predicate(last)) return last;
        std::this_thread::sleep_for(50ms);
    } while(std::chrono::steady_clock::now()<end);
    last.erase("metainfo");
    throw std::runtime_error("Native transfer timeout: "+last.dump());
}
std::string hash(fs::path const& p) {
    std::ifstream in(p,std::ios::binary); auto context=EVP_MD_CTX_new(); EVP_DigestInit_ex(context,EVP_sha256(),nullptr);
    char buffer[65536]; while(in) { in.read(buffer,sizeof(buffer)); if(in.gcount()) EVP_DigestUpdate(context,buffer,std::size_t(in.gcount())); }
    unsigned char bytes[32]; unsigned n=0; EVP_DigestFinal_ex(context,bytes,&n); EVP_MD_CTX_free(context);
    static char const* hex="0123456789abcdef"; std::string out; for(unsigned i=0;i<n;++i) {out+=hex[bytes[i]>>4];out+=hex[bytes[i]&15];} return out;
}
int main() {
    auto base=fs::canonical(fs::temp_directory_path())/fs::path("nexonic-torrent-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    auto torrent_name = std::string(u8"Legal-\u8a66\u9a13");
    auto seed_tree = base/"seed"/fs::u8path(torrent_name);
    try {
        fs::create_directories(seed_tree);
        for(auto [name,size]:std::vector<std::pair<std::string,int>>{{"selected.bin",4*1024*1024},{"skipped.bin",16384}}) {
            std::ofstream out(seed_tree/name,std::ios::binary); for(int i=0;i<size;++i) out.put(char(i%251));
        }
        {
        Context seed(base/"seed-state");
        auto created=seed.call({{"action","create_torrent"},{"path",seed_tree.u8string()}});
        auto meta=created.at("metainfo");
        seed.call({{"action","add"},{"id","seed"},{"preview",false},{"metainfo",meta},{"destination",(base/"seed").u8string()},{"seed",true},{"ratio_limit",0},{"upload_limit",0}});
        seed.call({{"action","resume"},{"id","seed"}});
        auto seeding=until(seed,"seed",[](json const& x){return x["state"]=="seeding";});
        int port=seed.call({{"action","list"}}).at("listen_port");
        require(port>0,"No peer port");
        auto context=std::make_unique<Context>(base/"download-state");
        auto preview=context->call({{"action","add"},{"id","preview"},{"preview",true},{"metainfo",meta}});
        require(!preview.at("payload_complete").get<bool>(),"Metadata was incorrectly completed as content");
        require(preview.at("files").size()==2,"File preview is incomplete");
        context->call({{"action","resume"},{"id","preview"}},false);
        context->call({{"action","remove"},{"id","preview"}});
        context->call({{"action","add"},{"id","magnet"},{"preview",true},{"source",seeding.at("magnet")}});
        context->call({{"action","connect_peer"},{"id","magnet"},{"port",port}});
        auto magnet=until(*context,"magnet",[](json const& x){return x["state"]=="preview";});
        require(magnet.at("files").size()==2 && !magnet.at("payload_complete").get<bool>(),"Magnet metadata preview failed");
        context->call({{"action","remove"},{"id","magnet"}});
        context->call({{"action","add"},{"id","payload"},{"preview",false},{"metainfo",meta},{"destination",(base/"download").u8string()}, {"priorities",{7,0}},{"download_limit",256*1024}});
        context->call({{"action","resume"},{"id","payload"}});
        context->call({{"action","connect_peer"},{"id","payload"},{"port",port}});
        auto partial=until(*context,"payload",[](json const& x){return x.at("received").get<long long>()>65536;});
        require(!partial.at("payload_complete").get<bool>(),"Partial torrent was completed early");
        context->call({{"action","pause"},{"id","payload"}});
        context.reset(); // Flush and persist, then create a fresh native session (actual application restart).
        require(fs::exists(base/"download-state"/"torrent-payload.resume"),"Native checkpoint missing");
        context=std::make_unique<Context>(base/"download-state");
        auto restored=context->call({{"action","restore"},{"id","payload"}});
        require(restored["files"][0]["priority"]==7 && restored["files"][1]["priority"]==0,"Priorities lost after restart");
        auto tuned=context->call({{"action","configure"},{"id","payload"},{"download_limit",0},{"trackers",{"http://127.0.0.1:9/announce"}},{"max_connections",40},{"max_uploads",4},{"sequential",true}});
        require(tuned["max_connections"]==40 && tuned["max_uploads"]==4 && tuned["sequential"]==true,"Connection settings were not applied");
        require(tuned["tracker_status"].size()==1 && tuned["piece_count"].get<int>()>0 && tuned.contains("connections") && tuned["piece_map"].is_array(),"Torrent details are missing");
        context->call({{"action","configure"},{"id","payload"},{"max_connections",0}},false);
        require(context->call({{"action","peers"},{"id","payload"}})["peers"].is_array(),"Peer list is missing");
        context->call({{"action","resume"},{"id","payload"}});
        context->call({{"action","connect_peer"},{"id","payload"},{"port",port}});
        auto done=until(*context,"payload",[](json const& x){return x["state"]=="complete";});
        auto downloaded=base/"download"/fs::u8path(torrent_name)/"selected.bin";
        auto expected=hash(seed_tree/"selected.bin");
        require(done.at("payload_complete").get<bool>() && hash(downloaded)==expected,"Final torrent payload hash mismatch");
        require(done["sha256"][torrent_name+"/selected.bin"]==expected,"Native final integrity report mismatch");
        require(done["received"]==4*1024*1024,"Wrong selected payload progress");
        require(done["trackers"].size()==1,"Tracker changes were lost");
        require(done["pieces_done"].get<int>()>0 && done["max_connections"]==40,"Piece counts or connection settings are missing");
        context->call({{"action","reannounce"},{"id","payload"}});
        context->call({{"action","remove"},{"id","payload"}});
        require(fs::exists(downloaded),"Removing a torrent deleted user payload data");
        {
            auto good=json{{"state_dir",(base/"proxy-state").u8string()},{"dht",false},{"proxy",{{"type","socks5"},{"host","127.0.0.1"},{"port",9},{"username","u"},{"password","p"}}}}.dump();
            void* proxied=ndm_torrent_create(good.c_str()); require(proxied!=nullptr,"Proxy session failed to start"); ndm_torrent_destroy(proxied);
            auto bad=json{{"state_dir",(base/"proxy-bad").u8string()},{"proxy",{{"type","ftp"},{"host","x"},{"port",1}}}}.dump();
            void* refused=ndm_torrent_create(bad.c_str()); require(refused==nullptr,"Invalid proxy accepted"); if (refused) ndm_torrent_destroy(refused);
        }
        context->call({{"action","add"},{"id","../escape"},{"preview",true},{"metainfo",meta}},false);
        std::cout<<"PASS native libtorrent "<<ndm_torrent_version()<<": metadata, magnet, real peer transfer, pause/restart, file priorities, trackers, piece recheck and SHA-256 "<<expected<<"\n";
        context.reset();
        } // All native contexts release files before cleanup.
        fs::remove_all(base);
        return 0;
    } catch(std::exception const& e) { std::cerr<<"FAIL "<<e.what()<<"\n"; std::error_code ignored; fs::remove_all(base,ignored); return 1; }
}
