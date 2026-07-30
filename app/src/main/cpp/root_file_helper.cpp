#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

namespace {
constexpr uint32_t kMagic = 0x57554944;
constexpr uint16_t kVersion = 1;
constexpr uint32_t kMaxField = 1024 * 1024;
constexpr uint32_t kMaxFrame = 8 * 1024 * 1024;
constexpr uint32_t kMaxPayload = 16 * 1024 * 1024;
constexpr std::string_view kPackage = "com.kurogame.wutheringwaves.global";
#ifndef WUWA_APP_PACKAGE
#define WUWA_APP_PACKAGE "com.titotfp.wuwaid"
#endif
constexpr std::string_view kAppPackage = WUWA_APP_PACKAGE;

enum class Op : uint16_t { Ping, Copy, Replace, Delete, Exists, Mkdirs, List, Read, WriteAtomic, Sha1, Sha256 };

bool read_full(int fd, void* data, size_t size) {
    auto* out = static_cast<unsigned char*>(data);
    while (size) { ssize_t n = read(fd, out, size); if (n < 0 && errno == EINTR) continue; if (n <= 0) return false; out += n; size -= static_cast<size_t>(n); }
    return true;
}
bool write_full(int fd, const void* data, size_t size) {
    const auto* in = static_cast<const unsigned char*>(data);
    while (size) { ssize_t n = write(fd, in, size); if (n < 0 && errno == EINTR) continue; if (n <= 0) return false; in += n; size -= static_cast<size_t>(n); }
    return true;
}
uint16_t be16(const unsigned char* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
uint32_t be32(const unsigned char* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
void put16(std::vector<unsigned char>& b, uint16_t v) { b.push_back(v >> 8); b.push_back(v); }
void put32(std::vector<unsigned char>& b, uint32_t v) { b.push_back(v >> 24); b.push_back(v >> 16); b.push_back(v >> 8); b.push_back(v); }

struct Request { Op op; std::vector<std::string> fields; };

bool valid_relative(std::string_view path) {
    if (path.empty()) return true;
    if (path.size() > 4096 || path.front() == '/' || path.back() == '/' || path.find('\0') != path.npos) return false;
    size_t start = 0;
    while (start < path.size()) { size_t end = path.find('/', start); if (end == path.npos) end = path.size(); auto part = path.substr(start, end - start); if (part.empty() || part == "." || part == "..") return false; start = end + 1; }
    return true;
}

bool read_request(Request& req) {
    std::array<unsigned char, 14> h{};
    if (!read_full(STDIN_FILENO, h.data(), h.size()) || be32(h.data()) != kMagic || be16(h.data() + 4) != kVersion) return false;
    uint16_t op = be16(h.data() + 6), count = be16(h.data() + 12); uint32_t body = be32(h.data() + 8);
    if (op > static_cast<uint16_t>(Op::Sha256) || count > 2 || body > kMaxFrame) return false;
    size_t expected = 2; req.op = static_cast<Op>(op);
    for (uint16_t i = 0; i < count; ++i) { unsigned char l[4]; if (!read_full(0, l, 4)) return false; uint32_t n = be32(l); expected += 4 + n; if (n > kMaxField || expected > body) return false; std::string value(n, '\0'); if (n && !read_full(0, value.data(), n)) return false; if (value.find('\0') != value.npos) return false; req.fields.push_back(std::move(value)); }
    const std::array<uint16_t, 11> field_count{0,2,2,1,1,1,1,1,2,1,1};
    if (expected != body || count != field_count[op]) return false;
    unsigned char extra{};
    ssize_t n;
    do { n = read(STDIN_FILENO, &extra, 1); } while (n < 0 && errno == EINTR);
    return n == 0;
}

int send_response(bool ok, int error, const std::vector<unsigned char>& payload = {}) {
    if (payload.size() > kMaxPayload) return 2;
    std::vector<unsigned char> h; put32(h, kMagic); put16(h, kVersion); put16(h, ok ? 0 : 1); put32(h, static_cast<uint32_t>(error)); put32(h, payload.size());
    return write_full(1, h.data(), h.size()) && write_full(1, payload.data(), payload.size()) ? 0 : 3;
}
int fail(int error, std::string_view message) { return send_response(false, error, {message.begin(), message.end()}); }

struct RootPath { int fd{-1}; std::string relative; };
std::vector<std::string> roots() {
    std::vector<std::string> result{
        "/storage/emulated/0/Android/data/" + std::string(kPackage),
        "/data/data/" + std::string(kPackage),
        "/data/user/0/" + std::string(kPackage),
        "/storage/emulated/0/Android/data/" + std::string(kAppPackage),
    };
#ifdef WUWA_HOST_TEST
    if (const char* test_root = getenv("WUWA_TEST_ROOT")) result.emplace_back(test_root);
#endif
    return result;
}
RootPath map_path(const std::string& absolute) {
    for (const auto& root : roots()) {
        if (absolute != root && absolute.compare(0, root.size() + 1, root + "/") != 0) continue;
        std::string relative = absolute == root ? "" : absolute.substr(root.size() + 1);
        if (!valid_relative(relative)) { errno = EINVAL; return {}; }
        int fd = open(root.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) return {};
        struct stat st{}; if (fstat(fd, &st) || !S_ISDIR(st.st_mode)) { int e = errno == 0 ? ENOTDIR : errno; close(fd); errno = e; return {}; }
        return {fd, std::move(relative)};
    }
    errno = EPERM; return {};
}

int open_component_walk(int root, const std::string& relative, int flags, mode_t mode = 0) {
    if (relative.empty()) return dup(root);
    int current = dup(root); if (current < 0) return -1;
    size_t start = 0;
    while (true) {
        size_t end = relative.find('/', start); bool last = end == std::string::npos; std::string part = relative.substr(start, last ? end : end - start);
        int next = openat(current, part.c_str(), (last ? flags : O_RDONLY | O_DIRECTORY) | O_NOFOLLOW | O_CLOEXEC, mode);
        int e = errno; close(current); if (next < 0) { errno = e; return -1; } current = next; if (last) return current; start = end + 1;
    }
}
int secure_open(int root, const std::string& relative, int flags, mode_t mode = 0) {
    bool force_fallback = false;
#ifdef WUWA_HOST_TEST
    force_fallback = getenv("WUWA_FORCE_OPENAT_FALLBACK") != nullptr;
#endif
#ifdef SYS_openat2
    if (!force_fallback) {
        struct open_how how{}; how.flags = static_cast<uint64_t>(flags | O_CLOEXEC); how.mode = mode; how.resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS;
        int fd = static_cast<int>(syscall(SYS_openat2, root, relative.empty() ? "." : relative.c_str(), &how, sizeof(how)));
        // Only capability errors may select the fallback. Security and lookup errors fail closed.
        if (fd >= 0 || (errno != ENOSYS && errno != EINVAL && errno != E2BIG)) return fd;
    }
#else
    (void)force_fallback;
#endif
    return open_component_walk(root, relative, flags, mode);
}

struct Parent { int fd{-1}; std::string leaf; };
Parent open_parent(const RootPath& p) {
    size_t slash = p.relative.rfind('/'); std::string parent = slash == std::string::npos ? "" : p.relative.substr(0, slash); std::string leaf = slash == std::string::npos ? p.relative : p.relative.substr(slash + 1);
    if (leaf.empty()) { errno = EINVAL; return {}; }
    return {secure_open(p.fd, parent, O_RDONLY | O_DIRECTORY), std::move(leaf)};
}
bool regular_fd(int fd) { struct stat st{}; return fstat(fd, &st) == 0 && S_ISREG(st.st_mode); }
bool existing_path_fd(int fd) { struct stat st{}; return fstat(fd, &st) == 0 && (S_ISREG(st.st_mode) || S_ISDIR(st.st_mode)); }
bool delete_regular_file_if_exists(const RootPath& p) {
    Parent parent = open_parent(p);
    if (parent.fd < 0) return errno == ENOENT;
    struct stat st{};
    if (fstatat(parent.fd, parent.leaf.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
        int saved = errno;
        close(parent.fd);
        errno = saved;
        return saved == ENOENT;
    }
    if (!S_ISREG(st.st_mode)) {
        close(parent.fd);
        errno = EINVAL;
        return false;
    }
    bool deleted = unlinkat(parent.fd, parent.leaf.c_str(), 0) == 0;
    int saved = errno;
    close(parent.fd);
    errno = saved;
    return deleted || saved == ENOENT;
}
bool copy_bytes(int in, int out) { std::array<unsigned char, 64 * 1024> b{}; while (true) { ssize_t n = read(in, b.data(), b.size()); if (n < 0 && errno == EINTR) continue; if (n < 0) return false; if (n == 0) return true; if (!write_full(out, b.data(), n)) return false; } }
std::string random_leaf(std::string_view leaf) { std::array<unsigned char, 8> b{}; int f = open("/dev/urandom", O_RDONLY | O_CLOEXEC); if (f < 0 || !read_full(f,b.data(),b.size())) { if(f>=0)close(f); return {}; } close(f); char x[17]; for(size_t i=0;i<b.size();++i) snprintf(x+i*2,3,"%02x",b[i]); return "." + std::string(leaf) + ".wuwa." + x; }

bool apply_metadata(int fd, int parent, int root_fd = -1) {
    struct stat st{}; if (fstat(parent, &st) != 0) return false;
    uid_t uid = st.st_uid; gid_t gid = st.st_gid;
    if (uid == 0 && root_fd >= 0) { struct stat root_st{}; if (fstat(root_fd, &root_st) == 0 && root_st.st_uid != 0) { uid = root_st.st_uid; gid = root_st.st_gid; (void)fchown(parent, uid, gid); } }
    return fchown(fd, uid, gid) == 0 && fchmod(fd, 0644) == 0;
}
bool apply_dir_metadata(int fd, int parent, int root_fd = -1) {
    struct stat st{}; if (fstat(parent, &st) != 0) return false;
    uid_t uid = st.st_uid; gid_t gid = st.st_gid;
    if (uid == 0 && root_fd >= 0) { struct stat root_st{}; if (fstat(root_fd, &root_st) == 0 && root_st.st_uid != 0) { uid = root_st.st_uid; gid = root_st.st_gid; (void)fchown(parent, uid, gid); } }
    return fchown(fd, uid, gid) == 0 && fchmod(fd, 0755) == 0;
}
bool atomic_write(const RootPath& dst, const unsigned char* data, size_t size) {
    Parent p = open_parent(dst); if (p.fd < 0) return false; std::string tmp = random_leaf(p.leaf); if(tmp.empty()){close(p.fd);return false;}
    int out = openat(p.fd, tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600); bool ok = out >= 0 && write_full(out,data,size) && fsync(out)==0 && apply_metadata(out,p.fd,dst.fd); int saved=errno;
    if(out>=0 && close(out)!=0) ok=false;
    if(ok) ok=renameat(p.fd,tmp.c_str(),p.fd,p.leaf.c_str())==0 && fsync(p.fd)==0;
    if(!ok){saved=errno == 0 ? saved : errno; unlinkat(p.fd,tmp.c_str(),0);}
    close(p.fd); errno=saved; return ok;
}

bool make_dirs(const RootPath& p) {
    int current=dup(p.fd); if(current<0)return false; size_t start=0; if(p.relative.empty()){close(current);return true;}
    while(start<p.relative.size()){
        size_t end=p.relative.find('/',start); if(end==std::string::npos)end=p.relative.size();
        std::string part=p.relative.substr(start,end-start);
        if(mkdirat(current,part.c_str(),0755)&&errno!=EEXIST){close(current);return false;}
        int next=openat(current,part.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(next<0){close(current);return false;}
        if(!apply_dir_metadata(next,current,p.fd)){close(next);close(current);return false;}
        close(current); current=next; start=end+1;
    }
    close(current); return true;
}

uint32_t rol(uint32_t v,int n){return(v<<n)|(v>>(32-n));}
std::vector<unsigned char> sha1_fd(int fd){uint64_t bits=0;std::vector<unsigned char>d;std::array<unsigned char,65536>b{};for(;;){ssize_t n=read(fd,b.data(),b.size());if(n<0&&errno==EINTR)continue;if(n<0)return{};if(n==0)break;bits+=n*8;d.insert(d.end(),b.begin(),b.begin()+n);}d.push_back(0x80);while(d.size()%64!=56)d.push_back(0);for(int i=7;i>=0;--i)d.push_back(bits>>(i*8));uint32_t h0=0x67452301,h1=0xefcdab89,h2=0x98badcfe,h3=0x10325476,h4=0xc3d2e1f0;for(size_t o=0;o<d.size();o+=64){uint32_t w[80];for(int i=0;i<16;++i)w[i]=be32(&d[o+i*4]);for(int i=16;i<80;++i)w[i]=rol(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);uint32_t a=h0,c=h2,e=h4,bb=h1,dd=h3;for(int i=0;i<80;++i){uint32_t f,k;if(i<20){f=(bb&c)|((~bb)&dd);k=0x5a827999;}else if(i<40){f=bb^c^dd;k=0x6ed9eba1;}else if(i<60){f=(bb&c)|(bb&dd)|(c&dd);k=0x8f1bbcdc;}else{f=bb^c^dd;k=0xca62c1d6;}uint32_t t=rol(a,5)+f+e+k+w[i];e=dd;dd=c;c=rol(bb,30);bb=a;a=t;}h0+=a;h1+=bb;h2+=c;h3+=dd;h4+=e;}char out[41];snprintf(out,sizeof(out),"%08x%08x%08x%08x%08x",h0,h1,h2,h3,h4);return{out,out+40};}
// SHA-256 delegated to compact implementation below.
std::vector<unsigned char> sha256_fd(int fd){static const uint32_t k[64]={0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};std::vector<unsigned char>d;std::array<unsigned char,65536>b{};uint64_t bits=0;for(;;){ssize_t n=read(fd,b.data(),b.size());if(n<0&&errno==EINTR)continue;if(n<0)return{};if(n==0)break;bits+=n*8;d.insert(d.end(),b.begin(),b.begin()+n);}d.push_back(0x80);while(d.size()%64!=56)d.push_back(0);for(int i=7;i>=0;--i)d.push_back(bits>>(8*i));uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};for(size_t o=0;o<d.size();o+=64){uint32_t w[64];for(int i=0;i<16;++i)w[i]=be32(&d[o+4*i]);for(int i=16;i<64;++i){uint32_t x=w[i-15],y=w[i-2];w[i]=((x>>7)|(x<<25))^((x>>18)|(x<<14))^(x>>3);w[i]+=w[i-16]+w[i-7]+(((y>>17)|(y<<15))^((y>>19)|(y<<13))^(y>>10));}uint32_t a=h[0],bb=h[1],c=h[2],dd=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];for(int i=0;i<64;++i){uint32_t s1=((e>>6)|(e<<26))^((e>>11)|(e<<21))^((e>>25)|(e<<7)),ch=(e&f)^((~e)&g),t1=hh+s1+ch+k[i]+w[i],s0=((a>>2)|(a<<30))^((a>>13)|(a<<19))^((a>>22)|(a<<10)),maj=(a&bb)^(a&c)^(bb&c),t2=s0+maj;hh=g;g=f;f=e;e=dd+t1;dd=c;c=bb;bb=a;a=t1+t2;}uint32_t v[8]={a,bb,c,dd,e,f,g,hh};for(int i=0;i<8;++i)h[i]+=v[i];}char out[65];snprintf(out,sizeof(out),"%08x%08x%08x%08x%08x%08x%08x%08x",h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7]);return{out,out+64};}

int run(const Request& r) {
    if(r.op==Op::Ping)return send_response(true,0);
    std::vector<RootPath> p;
    const size_t path_count = (r.op == Op::Copy || r.op == Op::Replace) ? 2U : 1U;
    for(size_t i=0;i<path_count;++i){auto x=map_path(r.fields.at(i));if(x.fd<0)return fail(errno,"path rejected");p.push_back({x.fd, x.relative});}
    auto close_all=[&]{for(auto&x:p)if(x.fd>=0)close(x.fd);}; std::vector<unsigned char> payload; bool ok=false;
    if(r.op==Op::Exists){int f=secure_open(p[0].fd,p[0].relative,O_RDONLY|O_NONBLOCK);int saved=errno;if(f>=0){bool supported=existing_path_fd(f);close(f);if(!supported){errno=EINVAL;ok=false;}else{payload={1};ok=true;}}else if(saved==ENOENT||saved==ELOOP||saved==ENXIO){payload={0};close_all();return send_response(true,0,payload);}else{errno=saved;ok=false;}}
    else if(r.op==Op::Mkdirs)ok=make_dirs(p[0]);
    else if(r.op==Op::Delete)ok=delete_regular_file_if_exists(p[0]);
    else if(r.op==Op::Read||r.op==Op::Sha1||r.op==Op::Sha256){int f=secure_open(p[0].fd,p[0].relative,O_RDONLY|O_NONBLOCK);if(f>=0&&regular_fd(f)){if(r.op==Op::Read){std::array<unsigned char,65536>b{};ok=true;while(ok){ssize_t n=read(f,b.data(),b.size());if(n<0&&errno==EINTR)continue;if(n<0){ok=false;break;}if(!n)break;if(payload.size()+n>kMaxPayload){errno=EFBIG;ok=false;break;}payload.insert(payload.end(),b.begin(),b.begin()+n);}}else{payload=r.op==Op::Sha1?sha1_fd(f):sha256_fd(f);ok=!payload.empty();}}if(f>=0)close(f);}
    else if(r.op==Op::List){int d=secure_open(p[0].fd,p[0].relative,O_RDONLY|O_DIRECTORY);if(d>=0){DIR* dir=fdopendir(d);if(dir){std::vector<std::string> names;errno=0;for(dirent*e;(e=readdir(dir));){if(strcmp(e->d_name,".")&&strcmp(e->d_name,"..")){if(names.size()>=100000){errno=EOVERFLOW;break;}names.emplace_back(e->d_name);}}ok=errno==0;if(ok){std::sort(names.begin(),names.end());put32(payload,names.size());for(auto&n:names){if(payload.size()+4+n.size()>kMaxPayload){errno=EOVERFLOW;ok=false;break;}put32(payload,n.size());payload.insert(payload.end(),n.begin(),n.end());}}int saved=errno;closedir(dir);errno=saved;}else close(d);}}
    else if(r.op==Op::WriteAtomic)ok=atomic_write(p[0],reinterpret_cast<const unsigned char*>(r.fields[1].data()),r.fields[1].size());
    else if(r.op==Op::Copy){int in=secure_open(p[0].fd,p[0].relative,O_RDONLY|O_NONBLOCK);if(in>=0&&regular_fd(in)){Parent dst=open_parent(p[1]);if(dst.fd>=0){std::string tmp=random_leaf(dst.leaf);int out=tmp.empty()?-1:openat(dst.fd,tmp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);ok=out>=0&&copy_bytes(in,out)&&fsync(out)==0&&apply_metadata(out,dst.fd,p[1].fd);if(out>=0&&close(out)!=0)ok=false;if(ok)ok=renameat(dst.fd,tmp.c_str(),dst.fd,dst.leaf.c_str())==0&&fsync(dst.fd)==0;if(!ok&&!tmp.empty())unlinkat(dst.fd,tmp.c_str(),0);close(dst.fd);}}if(in>=0)close(in);}
    else if(r.op==Op::Replace){int in=secure_open(p[0].fd,p[0].relative,O_RDONLY|O_NONBLOCK);if(in>=0&&regular_fd(in)){Parent dst=open_parent(p[1]);if(dst.fd>=0){std::string tmp=random_leaf(dst.leaf);int out=tmp.empty()?-1:openat(dst.fd,tmp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);ok=out>=0&&copy_bytes(in,out)&&fsync(out)==0&&apply_metadata(out,dst.fd,p[1].fd);if(out>=0&&close(out)!=0)ok=false;if(ok)ok=renameat(dst.fd,tmp.c_str(),dst.fd,dst.leaf.c_str())==0&&fsync(dst.fd)==0;if(!ok&&!tmp.empty())unlinkat(dst.fd,tmp.c_str(),0);close(dst.fd);}}if(in>=0)close(in);}
    int e=errno;close_all();return ok?send_response(true,0,payload):fail(e,"operation failed");
}
} // namespace
int main(int argc,char**argv){if(argc!=2||strcmp(argv[1],"--stdio"))return 64;Request r;if(!read_request(r))return fail(EPROTO,"invalid request");return run(r);}
