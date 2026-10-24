// SPDX-License-Identifier: GPL-3.0-only
// Root-side PIE executable. No code is loaded into the target process.
// Each pipeline accepts only its independently verified semantic instruction set.
// See README-native.txt for the proof, ownership formats and limits.
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <exception>
#ifndef SF17FIX_INSPECT_ONLY
#include <dirent.h>
#endif
#include <elf.h>
#include <fcntl.h>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#ifndef SF17FIX_INSPECT_ONLY
#include <sys/file.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/sysmacros.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#endif
#include <unistd.h>
#include <vector>
#include "policy_broker.hpp"

namespace {
constexpr size_t kMaxElf = 64 * 1024 * 1024;
constexpr uint64_t kStateMagic = 0x325846534e574fULL; // OWNSFX2
constexpr const char* kStateDir = "/data/adb/enable_screenshot17fix";
constexpr const char* kStateFile = "/data/adb/enable_screenshot17fix/native-patch.state";
constexpr const char* kRecordStateFile = "/data/adb/enable_screenshot17fix/native-recording-patch.state";
constexpr uint64_t kRecordStateMagic = 0x52335846534e574fULL; // OWNSFX3R, separate from the 2.0 record.
constexpr uint64_t kRecordStateMagicV4 = 0x52345846534e574fULL; // OWNSFX4R: audited variable site count.
constexpr uint64_t kMappedStateMagic = 0x4d355846534e574fULL; // OWNSFX5M: file-backed executable/DSO identity.
constexpr size_t kMaxRecordingSites = 16;
enum class Pipeline { Screenshot, Recording };
const char* pipelineName(Pipeline p) { return p == Pipeline::Recording ? "recording" : "screenshot"; }
const char* stateFile(Pipeline p) { return p == Pipeline::Recording ? kRecordStateFile : kStateFile; }
constexpr int kStopLimitMs = 2500;
bool gDetachFailed = false;
struct Error : std::runtime_error {
    std::string reason;
    int err;
    Error(std::string why, std::string detail, int e = 0)
        : std::runtime_error(std::move(detail)), reason(std::move(why)), err(e) {}
};
[[noreturn]] void fail(const char* why, const std::string& msg) { throw Error(why, msg, errno); }
struct Fd {
    int value = -1;
    explicit Fd(int v = -1) : value(v) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};
template<class T> T at(const std::vector<uint8_t>& b, size_t offset) {
    if (offset > b.size() || sizeof(T) > b.size() - offset)
        throw Error("invalid_elf", "ELF range exceeds file");
    T t;
    memcpy(&t, b.data() + offset, sizeof(t));
    return t;
}
std::string hex(uint64_t n) { std::ostringstream s; s << "0x" << std::hex << n; return s.str(); }
std::string quote(const std::string& s) {
    std::ostringstream o; o << '"';
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') o << '\\' << c;
        else if (c >= 32 && c < 127) o << c;
        else o << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(c) << std::dec;
    }
    o << '"'; return o.str();
}
std::vector<uint8_t> readFile(const std::string& path, size_t limit = kMaxElf) {
#ifdef SF17FIX_INSPECT_ONLY
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    auto size = f.tellg();
    if (!f || size <= 0 || uint64_t(size) > limit) throw Error("invalid_file", "File size outside supported bounds");
    std::vector<uint8_t> b(static_cast<size_t>(size));
    f.seekg(0); f.read(reinterpret_cast<char*>(b.data()), std::streamsize(b.size()));
    if (!f) throw Error("read_failed", "Short ELF read");
    return b;
#else
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (fd.value < 0) fail("read_denied", "open " + path);
    struct stat st{};
    if (fstat(fd.value, &st) || st.st_size <= 0 || uint64_t(st.st_size) > limit)
        throw Error("invalid_file", "File size outside supported bounds");
    std::vector<uint8_t> b(size_t(st.st_size)); size_t done = 0;
    while (done < b.size()) {
        ssize_t n = read(fd.value, b.data() + done, b.size() - done);
        if (n <= 0) fail("read_failed", "Reading " + path);
        done += size_t(n);
    }
    return b;
#endif
}
uint32_t checksum(const void* ptr, size_t size) {
    const auto* p = static_cast<const uint8_t*>(ptr); uint32_t h = 2166136261u;
    for (size_t i = 0; i < size; ++i) h = (h ^ p[i]) * 16777619u;
    return h;
}
using Digest = std::array<uint8_t, 32>;
Digest strongDigest(const void* input, size_t size) {
    constexpr uint32_t constants[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    std::array<uint32_t, 8> state{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    auto rotate = [](uint32_t w, unsigned n) { return (w >> n) | (w << (32 - n)); };
    auto block = [&](const uint8_t* p) {
        uint32_t w[64];
        for (unsigned i = 0; i < 16; ++i) w[i] = (uint32_t(p[i*4])<<24)|(uint32_t(p[i*4+1])<<16)|(uint32_t(p[i*4+2])<<8)|p[i*4+3];
        for (unsigned i = 16; i < 64; ++i) {
            uint32_t a=rotate(w[i-15],7)^rotate(w[i-15],18)^(w[i-15]>>3);
            uint32_t b=rotate(w[i-2],17)^rotate(w[i-2],19)^(w[i-2]>>10);
            w[i]=w[i-16]+a+w[i-7]+b;
        }
        auto [a,b,c,d,e,f,g,h]=state;
        for (unsigned i=0;i<64;++i) {
            uint32_t s1=rotate(e,6)^rotate(e,11)^rotate(e,25), choose=(e&f)^(~e&g);
            uint32_t t1=h+s1+choose+constants[i]+w[i];
            uint32_t s0=rotate(a,2)^rotate(a,13)^rotate(a,22), majority=(a&b)^(a&c)^(b&c);
            h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+s0+majority;
        }
        state[0]+=a;state[1]+=b;state[2]+=c;state[3]+=d;state[4]+=e;state[5]+=f;state[6]+=g;state[7]+=h;
    };
    const auto* bytes=static_cast<const uint8_t*>(input);size_t done=0;
    while (size-done>=64) {block(bytes+done);done+=64;}
    uint8_t tail[128]{};size_t rest=size-done;if(rest)memcpy(tail,bytes+done,rest);tail[rest]=0x80;
    size_t padded=rest<56?64:128;uint64_t bits=uint64_t(size)*8;
    for(unsigned i=0;i<8;++i)tail[padded-1-i]=uint8_t(bits>>(i*8));
    block(tail);if(padded==128)block(tail+64);
    Digest out{};for(unsigned i=0;i<8;++i)for(unsigned j=0;j<4;++j)out[i*4+j]=uint8_t(state[i]>>(24-j*8));
    return out;
}
// Image copies share the immutable file snapshot. An observation must not copy
// tens of MiB or recalculate the ownership checksum on every policy heartbeat.
class ElfBytes {
    struct Storage {
        const std::vector<uint8_t> bytes;
        const uint32_t hash;
        explicit Storage(std::vector<uint8_t> input)
            : bytes(std::move(input)), hash(checksum(bytes.data(), bytes.size())) {}
    };
    std::shared_ptr<const Storage> storage;
public:
    explicit ElfBytes(std::vector<uint8_t> input) : storage(std::make_shared<const Storage>(std::move(input))) {}
    operator const std::vector<uint8_t>&() const { return storage->bytes; }
    const uint8_t* data() const { return storage->bytes.data(); }
    size_t size() const { return storage->bytes.size(); }
    uint8_t operator[](size_t i) const { return storage->bytes[i]; }
    uint32_t fileChecksum() const { return storage->hash; }
};
struct NativeMetrics {
    std::string discoveryCache = "none";
    std::string accessCache = "none", accessCacheDetail, selectedAccess;
    // Bounded diagnostics only; no file content or per-poll ELF hashing.
    std::vector<std::string> moduleAccess;
    size_t accessDiagnosticBytes = 0;
    bool accessDiagnosticsTruncated = false;
    double discoveryMs = 0, ownershipPrepareMs = 0, stoppedMs = 0, totalMs = 0;
    uint64_t moduleReadBytes = 0, liveReadBytes = 0, stoppedThreads = 0;
};
NativeMetrics gMetrics;
struct Measure {
    double& output;
    std::chrono::steady_clock::time_point begin = std::chrono::steady_clock::now();
    explicit Measure(double& target) : output(target) {}
    ~Measure() { output += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - begin).count(); }
};
struct Site {
    uint64_t vaddr = 0, fileOffset = 0, functionStart = 0, functionEnd = 0;
    uint32_t original = 0, replacement = 0;
};
// Local layouts keep the inspect-only build independent of host ELF headers.
struct DynamicSymbol {
    uint32_t name; uint8_t info, other; uint16_t section; uint64_t value, size;
};
struct RelaEntry { uint64_t offset, info; int64_t addend; };
static_assert(sizeof(DynamicSymbol) == 24 && sizeof(RelaEntry) == 24);
bool ldr64(uint32_t w, int rt, int rn, int offset) {
    return (w & 0xffc00000u) == 0xf9400000u && int(w & 31) == rt &&
        int((w >> 5) & 31) == rn && int((w >> 10) & 4095) * 8 == offset;
}
bool blr(uint32_t w, int r) { return w == (0xd63f0000u | (unsigned(r) << 5)); }
class Image {
public:
    ElfBytes bytes;
    Elf64_Ehdr header{};
    Elf64_Shdr text{}, unwind{};
    std::string buildId;
    std::vector<uint64_t> starts;
    Site site;
    std::vector<Site> sites;
    Pipeline pipeline;
    std::string modulePath;
    uint64_t moduleBias = 0, moduleInode = 0, moduleDevice = 0;
    explicit Image(const std::string& path, Pipeline pipe = Pipeline::Screenshot,
                   std::vector<uint8_t> input = {}, const std::vector<Site>* audited = nullptr)
        : bytes(input.empty() ? readFile(path) : std::move(input)), pipeline(pipe) {
        header = at<Elf64_Ehdr>(bytes, 0);
        if (memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_machine != EM_AARCH64 ||
            header.e_type != ET_DYN || header.e_shentsize != sizeof(Elf64_Shdr) ||
            header.e_phentsize != sizeof(Elf64_Phdr) || !header.e_shnum || header.e_shstrndx >= header.e_shnum)
            throw Error("unsupported_elf", "Needs a little-endian AArch64 PIE with section/unwind metadata");
        auto strings = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(header.e_shstrndx) * sizeof(Elf64_Shdr));
        if (strings.sh_offset > bytes.size() || strings.sh_size > bytes.size() - strings.sh_offset)
            throw Error("invalid_elf", "Invalid section-name table");
        for (unsigned i = 0; i < header.e_shnum; ++i) {
            auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
            if (s.sh_name >= strings.sh_size) throw Error("invalid_elf", "Invalid section name");
            const char* name = reinterpret_cast<const char*>(bytes.data() + strings.sh_offset + s.sh_name);
            if (!memchr(name, 0, strings.sh_size - s.sh_name)) throw Error("invalid_elf", "Unterminated section name");
            if (strcmp(name, ".text") == 0) text = s;
            if (strcmp(name, ".eh_frame_hdr") == 0) unwind = s;
            if (s.sh_type == SHT_NOTE) notes(s);
        }
        if (!text.sh_size || !(text.sh_flags & SHF_EXECINSTR) || text.sh_size > bytes.size() ||
            text.sh_offset > bytes.size() - text.sh_size || !unwind.sh_size || buildId.empty())
            throw Error("unsupported_elf", "Missing executable section, GNU build ID, or unwind boundaries");
        parseUnwind();
        if (audited) {
            if (audited->empty() || audited->size() > kMaxRecordingSites || (pipe == Pipeline::Screenshot && audited->size() != 1))
                throw Error("invalid_compat_cache", "Invalid audited site count");
            for (const auto& s : *audited) {
                auto upper = std::upper_bound(starts.begin(), starts.end(), s.vaddr);
                uint64_t end = upper == starts.end() ? text.sh_addr + text.sh_size : *upper;
                if (upper == starts.begin() || (s.vaddr & 3) || s.functionStart != *std::prev(upper) || s.functionEnd != end ||
                    s.fileOffset != offset(s.vaddr) || instruction(s.vaddr) != s.original ||
                    (!sites.empty() && s.vaddr <= sites.back().vaddr))
                    throw Error("invalid_compat_cache", "Audited site no longer matches ELF/unwind boundaries");
                bool replacement = pipe == Pipeline::Screenshot ?
                    ((s.original & ~0x3e0u) == 0xb2580001u && ((s.original >> 5) & 31) != 31 &&
                     s.replacement == (0xaa0003e1u | (((s.original >> 5) & 31) << 16))) :
                    (s.replacement == (0x2a1f03e0u | (s.original & 31)) && (s.original & 31) != 31);
                if (!replacement) throw Error("invalid_compat_cache", "Audited replacement differs from its original register");
                sites.push_back(s);
            }
            site = sites.front(); return;
        }
        if (pipeline == Pipeline::Recording) {
            sites = matchRecording(); site = sites.front(); return;
        }
        std::vector<Site> found;
        for (uint64_t va = text.sh_addr + 12; va + 12 < text.sh_addr + text.sh_size; va += 4) {
            uint32_t word = instruction(va);
            // ORR X1, Xn, #0x10000000000. The sole added bit is LayerFilter.skipScreenshot.
            if ((word & ~0x3e0u) != 0xb2580001u) continue;
            auto candidate = match(va);
            if (!candidate.vaddr) candidate = matchExportedFactory(va);
            if (!candidate.vaddr) candidate = matchPlainFactory(va);
            if (candidate.vaddr) found.push_back(candidate);
        }
        if (found.empty()) throw Error("signature_not_found", "No validated screenshot-output factory");
        if (found.size() != 1) throw Error("signature_ambiguous", "Multiple validated screenshot-output factories");
        site = found.front();
        sites.push_back(site);
    }
    uint64_t offset(uint64_t va) const {
        if (va < text.sh_addr || va >= text.sh_addr + text.sh_size)
            throw Error("invalid_elf", "Instruction outside executable section");
        return text.sh_offset + va - text.sh_addr;
    }
    uint32_t instruction(uint64_t va) const { return at<uint32_t>(bytes, offset(va)); }
private:
    struct NamedSymbol { std::string name; uint64_t value, size; uint16_t section; };
    std::vector<NamedSymbol> dynamicSymbols() const {
        std::vector<NamedSymbol> out;
        for (unsigned i = 0; i < header.e_shnum; ++i) {
            auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
            if (s.sh_type != 11) continue; // SHT_DYNSYM
            if (s.sh_entsize != sizeof(DynamicSymbol) || s.sh_size % sizeof(DynamicSymbol) ||
                s.sh_link >= header.e_shnum || s.sh_size / sizeof(DynamicSymbol) > 100000)
                throw Error("invalid_elf", "Invalid dynamic symbol table");
            auto str = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(s.sh_link) * sizeof(Elf64_Shdr));
            if (str.sh_offset > bytes.size() || str.sh_size > bytes.size() - str.sh_offset)
                throw Error("invalid_elf", "Dynamic string table exceeds file");
            for (uint64_t p = 0; p < s.sh_size; p += sizeof(DynamicSymbol)) {
                auto sym = at<DynamicSymbol>(bytes, s.sh_offset + p);
                if (sym.name >= str.sh_size) throw Error("invalid_elf", "Invalid dynamic symbol name");
                const char* name = reinterpret_cast<const char*>(bytes.data() + str.sh_offset + sym.name);
                if (!memchr(name, 0, str.sh_size - sym.name)) throw Error("invalid_elf", "Unterminated dynamic symbol");
                out.push_back({name, sym.value, sym.size, sym.section});
            }
        }
        return out;
    }
    NamedSymbol named(const std::vector<NamedSymbol>& symbols, const char* name) const {
        NamedSymbol found{};
        for (const auto& s : symbols) if (s.name == name && s.section && s.value && s.size) {
            if (found.value && (found.value != s.value || found.size != s.size))
                throw Error("signature_ambiguous", "Conflicting exported semantic identities");
            found = s;
        }
        return found;
    }
    struct Relocation { uint32_t type, symbol; int64_t addend; };
    uint64_t allocatedWord(uint64_t address) const {
        if (address & 7) throw Error("invalid_elf", "Unaligned allocated pointer");
        for (unsigned i = 0; i < header.e_shnum; ++i) {
            auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
            if (s.sh_type == SHT_NOBITS || !(s.sh_flags & SHF_ALLOC) || address < s.sh_addr ||
                s.sh_size < 8 || address - s.sh_addr > s.sh_size - 8) continue;
            return at<uint64_t>(bytes, s.sh_offset + address - s.sh_addr);
        }
        throw Error("invalid_elf", "Relocated pointer is outside allocated file data");
    }
    std::map<uint64_t, Relocation> relocations() const {
        std::map<uint64_t, Relocation> out;
        auto save = [&out](uint64_t offset, uint64_t info, int64_t addend) {
            if (!out.emplace(offset, Relocation{uint32_t(info), uint32_t(info >> 32), addend}).second)
                throw Error("invalid_elf", "Duplicate dynamic relocation");
        };
        for (unsigned i = 0; i < header.e_shnum; ++i) {
            auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
            bool relr = s.sh_type == 19 || s.sh_type == 0x6fffff00u; // RELR / Android RELR
            if (s.sh_type != 4 && s.sh_type != 0x60000002u && !relr) continue;
            if (s.sh_offset > bytes.size() || s.sh_size > bytes.size() - s.sh_offset)
                throw Error("invalid_elf", "Relocation section exceeds file");
            if (relr) {
                if (s.sh_entsize != 8 || s.sh_size % 8 || s.sh_size / 8 > 1000000)
                    throw Error("invalid_elf", "Invalid RELR table");
                uint64_t base = 0; bool haveBase = false;
                auto relative = [&](uint64_t address) {
                    if (out.size() >= 1000000) throw Error("invalid_elf", "Relocation expansion exceeds bound");
                    save(address, 1027, int64_t(allocatedWord(address))); // R_AARCH64_RELATIVE
                };
                for (uint64_t p = 0; p < s.sh_size; p += 8) {
                    uint64_t entry = at<uint64_t>(bytes, s.sh_offset + p);
                    if (!(entry & 1)) {
                        if ((entry & 7) || (haveBase && entry < base) || entry > UINT64_MAX - 8)
                            throw Error("invalid_elf", "Invalid RELR address ordering");
                        relative(entry); base = entry + 8; haveBase = true;
                    } else {
                        if (!haveBase || base > UINT64_MAX - 63 * 8)
                            throw Error("invalid_elf", "RELR bitmap lacks a bounded base");
                        for (unsigned bit = 1; bit < 64; ++bit)
                            if (entry & (uint64_t(1) << bit)) relative(base + (bit - 1) * 8);
                        base += 63 * 8;
                    }
                }
                continue;
            }
            if (s.sh_type == 4) {
                if (s.sh_entsize != sizeof(RelaEntry) || s.sh_size % sizeof(RelaEntry))
                    throw Error("invalid_elf", "Invalid RELA table");
                for (uint64_t p = 0; p < s.sh_size; p += sizeof(RelaEntry)) {
                    auto r = at<RelaEntry>(bytes, s.sh_offset + p); save(r.offset, r.info, r.addend);
                }
                continue;
            }
            uint64_t p = s.sh_offset, end = p + s.sh_size;
            if (s.sh_size < 4 || memcmp(bytes.data() + p, "APS2", 4)) throw Error("unsupported_relocation", "Unknown packed relocation format");
            p += 4;
            auto sleb = [&]() -> int64_t {
                uint64_t v = 0; unsigned shift = 0; uint8_t c;
                do {
                    if (p >= end || shift > 63) throw Error("invalid_elf", "Packed relocation integer exceeds bounds");
                    c = bytes[p++];
                    if (shift == 63 && (c & 0x7f) != 0 && (c & 0x7f) != 0x7f)
                        throw Error("invalid_elf", "Packed relocation integer overflow");
                    v |= uint64_t(c & 0x7f) << shift; shift += 7;
                } while (c & 0x80);
                if ((c & 0x40) && shift < 64) v |= (~uint64_t(0)) << shift;
                return int64_t(v);
            };
            auto checkedAdd = [](int64_t a, int64_t b) {
                if ((b > 0 && a > INT64_MAX - b) || (b < 0 && a < INT64_MIN - b))
                    throw Error("invalid_elf", "Packed relocation arithmetic overflow");
                return a + b;
            };
            int64_t count = sleb(), offset = sleb(), addend = 0, done = 0;
            if (count < 0 || count > 1000000 || offset < 0) throw Error("invalid_elf", "Invalid packed relocation count");
            while (done < count) {
                int64_t size = sleb(), flags = sleb(), delta = 0, info = 0;
                if (size <= 0 || size > count - done || flags < 0 || flags > 15 || ((flags & 4) && !(flags & 8)))
                    throw Error("invalid_elf", "Invalid packed relocation group");
                if (flags & 2) delta = sleb();
                if (flags & 1) info = sleb();
                if ((flags & 12) == 12) addend = checkedAdd(addend, sleb());
                else if (!(flags & 8)) addend = 0;
                for (int64_t j = 0; j < size; ++j) {
                    if (!(flags & 2)) delta = sleb();
                    offset = checkedAdd(offset, delta);
                    if (!(flags & 1)) info = sleb();
                    if ((flags & 12) == 8) addend = checkedAdd(addend, sleb());
                    if (offset < 0 || info < 0) throw Error("invalid_elf", "Negative packed relocation address or symbol");
                    save(uint64_t(offset), uint64_t(info), addend); ++done;
                }
            }
            if (p != end) throw Error("invalid_elf", "Trailing packed relocation bytes");
        }
        return out;
    }
    uint64_t pageAddress(uint64_t va, uint32_t word) const {
        uint32_t imm = ((word >> 29) & 3) | (((word >> 5) & 0x7ffff) << 2);
        return (va & ~0xfffull) + signExtend(imm, 21) * 4096;
    }
    struct AddressOrigin {
        uint64_t definition; unsigned reg; int64_t offset;
        auto operator<=>(const AddressOrigin&) const = default;
    };
    // Control-flow predecessors are essential: a physically adjacent epilogue
    // may be skipped by the branch that reaches an inline visibility decision.
    class AddressFlow {
        static constexpr int64_t kMaxAffineBias = int64_t(1) << 56;
        const Image& image;
        uint64_t lo, hi;
        std::vector<std::vector<uint64_t>> predecessors;
        std::array<std::set<int64_t>, 31> possibleBiases;
        std::array<bool, 31> broadBias{};
    public:
        AddressFlow(const Image& im, uint64_t begin, uint64_t end) : image(im), lo(begin), hi(end), predecessors(size_t((end - begin) / 4)) {
            if (hi <= lo || hi - lo > 4 * 1024 * 1024) throw Error("unsupported_dataflow", "Function exceeds CFG audit bound");
            auto edge = [&](uint64_t from, uint64_t to) {
                if (to >= lo && to < hi && !(to & 3)) predecessors[size_t((to - lo) / 4)].push_back(from);
            };
            for (uint64_t a = lo; a < hi; a += 4) {
                uint32_t w = image.instruction(a);
                bool direct = (w & 0xfc000000u) == 0x14000000u;
                bool conditional = (w & 0xff000010u) == 0x54000000u;
                bool compare = (w & 0x7e000000u) == 0x34000000u;
                bool bit = (w & 0x7e000000u) == 0x36000000u;
                bool terminal = (w & 0xfffffc1fu) == 0xd65f0000u || (w & 0xfffffc1fu) == 0xd61f0000u;
                if (direct) edge(a, image.branch26(a));
                else if (!terminal) {
                    edge(a, a + 4);
                    if (conditional || compare) edge(a, image.branch19(a));
                    if (bit) edge(a, image.branch14(a));
                }
            }
            // Cheap over-approximation only selects byte reads for the expensive
            // CFG proof. Retain earlier definitions across all physical blocks,
            // including epilogues; no acceptance is based on this candidate pass.
            std::array<std::set<int64_t>, 31> constants;
            for (auto& values : possibleBiases) values.insert(0);
            for (uint64_t a = lo; a < hi; a += 4) {
                uint32_t w = image.instruction(a); unsigned rd = w & 31;
                if (rd < 31 && (w & 0x7f800000u) == 0x52800000u && ((w >> 21) & 3) <= 1)
                    constants[rd].insert(int64_t((w >> 5) & 65535) << (((w >> 21) & 3) * 16));
            }
            // Solve the finite register-transfer summary with a worklist. This
            // is an over-approximation used only to select strict CFG queries;
            // growing induction sets become broad instead of dropping a use.
            bool moreConstants = true;
            while (moreConstants) {
                moreConstants = false;
                for (uint64_t a = lo; a < hi; a += 4) {
                    uint32_t w = image.instruction(a); unsigned rd = w & 31, rm = (w >> 16) & 31;
                    if (rd == 31 || rm == 31 || (w & 0xffe0ffe0u) != 0xaa0003e0u) continue;
                    auto copy = constants[rm];
                    for (int64_t value : copy) {
                        if (constants[rd].size() >= 128) break;
                        moreConstants |= constants[rd].insert(value).second;
                    }
                }
            }
            struct Transfer { unsigned to; int64_t amount; };
            std::array<std::vector<Transfer>, 31> transfers;
            auto transfer = [&](unsigned from, unsigned to, int64_t amount) {
                if (from < 31 && to < 31) transfers[from].push_back({to, amount});
            };
            for (uint64_t a = lo; a < hi; a += 4) {
                uint32_t w = image.instruction(a); unsigned rd = w & 31, rn = (w >> 5) & 31;
                if ((w & 0xbf800000u) == 0x91000000u) {
                    int64_t amount = int64_t((w >> 10) & 4095) << ((w & 0x400000u) ? 12 : 0);
                    transfer(rn, rd, (w & 0x40000000u) ? -amount : amount);
                } else if ((w & 0xffe0ffe0u) == 0xaa0003e0u) transfer((w >> 16) & 31, rd, 0);
                else if ((w & 0xbf200000u) == 0x8b000000u && ((w >> 22) & 3) == 0 && ((w >> 10) & 63) <= 15) {
                    unsigned rm = (w >> 16) & 31, shift = (w >> 10) & 63;
                    if (rm < 31) for (int64_t value : constants[rm]) if (value >= 0 && value <= 65535)
                        transfer(rn, rd, (w & 0x40000000u) ? -(value << shift) : (value << shift));
                }
                bool singleWriteback = (w & 0x3b200000u) == 0x38000000u && (((w >> 10) & 3) == 1 || ((w >> 10) & 3) == 3);
                bool pair = (w & 0x3a000000u) == 0x28000000u;
                bool pairWriteback = pair && (((w >> 23) & 3) == 1 || ((w >> 23) & 3) == 3);
                if ((singleWriteback || pairWriteback) && rn < 31) {
                    int scale = (w & 0x04000000u) ? (4 << ((w >> 30) & 3)) : ((w & 0x80000000u) ? 8 : 4);
                    transfer(rn, rn, singleWriteback ? signExtend((w >> 12) & 511, 9) : signExtend((w >> 15) & 127, 7) * scale);
                }
            }
            std::deque<unsigned> pending;
            std::array<bool, 31> queued;
            queued.fill(true); for (unsigned r = 0; r < 31; ++r) pending.push_back(r);
            while (!pending.empty()) {
                unsigned from = pending.front(); pending.pop_front(); queued[from] = false;
                for (auto [to, amount] : transfers[from]) {
                    if (broadBias[to]) continue;
                    bool changed = false;
                    if (broadBias[from]) { broadBias[to] = true; changed = true; }
                    else {
                        auto copy = possibleBiases[from];
                        for (int64_t value : copy) {
                            int64_t n = value + amount;
                            if (n < -65535 || n > 65535 || possibleBiases[to].size() >= 128) {
                                broadBias[to] = true; changed = true; break;
                            }
                            changed |= possibleBiases[to].insert(n).second;
                        }
                    }
                    if (changed && !queued[to]) { pending.push_back(to); queued[to] = true; }
                }
            }
        }
        bool mayReadField(unsigned reg, int64_t off, unsigned field) const {
            return reg < 31 && (broadBias[reg] || possibleBiases[reg].count(int64_t(field) - off));
        }
        const std::vector<uint64_t>& incoming(uint64_t use) const {
            return predecessors.at(size_t((use - lo) / 4));
        }
        bool writes(uint32_t instruction, unsigned reg) const { return changes(instruction, reg); }
    private:
        struct Value { std::set<AddressOrigin> roots; bool complete = true; };
        using Key = std::pair<uint64_t, unsigned>;
        mutable std::map<Key, Value> memo;
        bool changes(uint32_t w, unsigned reg) const {
            bool call = (w & 0xfc000000u) == 0x94000000u || (w & 0xfffffc1fu) == 0xd63f0000u;
            if (call) return reg < 19 || reg == 30;
            bool branch = (w & 0x7c000000u) == 0x14000000u || (w & 0x7e000000u) == 0x34000000u ||
                (w & 0x7e000000u) == 0x36000000u || (w & 0xff000010u) == 0x54000000u;
            bool pair = (w & 0x3a000000u) == 0x28000000u;
            bool memory = (w & 0x0a000000u) == 0x08000000u;
            bool load = memory && !(w & 0x04000000u) && (w & 0x00400000u);
            if (load && ((w & 31) == reg || (pair && ((w >> 10) & 31) == reg))) return true;
            bool singleWriteback = (w & 0x3b200000u) == 0x38000000u && (((w >> 10) & 3) == 1 || ((w >> 10) & 3) == 3);
            bool pairWriteback = pair && (((w >> 23) & 3) == 1 || ((w >> 23) & 3) == 3);
            if ((singleWriteback || pairWriteback) && ((w >> 5) & 31) == reg) return true;
            return !memory && !branch && (w & 31) == reg;
        }
        Key canonical(Key key) const {
            std::set<uint64_t> visited;
            while (key.first >= lo && key.first < hi && visited.insert(key.first).second) {
                const auto& pred = predecessors[size_t((key.first - lo) / 4)];
                if (pred.size() != 1 || changes(image.instruction(pred.front()), key.second)) break;
                key.first = pred.front();
            }
            return key;
        }
        // A separate bounded constant query avoids recursively asking the affine
        // graph to solve itself for ADD-register indices. Every predecessor must
        // reach the same literal; any unknown write, nonzero cycle or bound fails.
        std::optional<int64_t> constant(uint64_t use, unsigned reg) const {
            struct Pending { uint64_t pc; unsigned reg; int64_t bias; };
            std::vector<Pending> pending{{use, reg, 0}};
            std::set<std::tuple<uint64_t, unsigned, int64_t>> seen;
            std::optional<int64_t> result;
            while (!pending.empty()) {
                auto item = pending.back(); pending.pop_back();
                if (!seen.emplace(item.pc, item.reg, item.bias).second) continue;
                if (seen.size() > 8192 || item.bias < -65535 || item.bias > 65535) return {};
                auto literal = [&](int64_t n) {
                    n += item.bias;
                    if (n < 0 || n > 65535 || (result && *result != n)) return false;
                    result = n; return true;
                };
                if (item.reg == 31) { if (!literal(0)) return {}; continue; }
                auto key = canonical({item.pc, item.reg});
                const auto& pred = predecessors[size_t((key.first - lo) / 4)];
                if (pred.empty()) return {};
                for (uint64_t p : pred) {
                    uint32_t w = image.instruction(p);
                    if (!changes(w, item.reg)) pending.push_back({p, item.reg, item.bias});
                    else if ((w & 0x7f80001fu) == (0x52800000u | item.reg) && ((w >> 21) & 3) <= 1) {
                        if (!literal(int64_t((w >> 5) & 65535) << (((w >> 21) & 3) * 16))) return {};
                    } else if ((w & 0xffe0ffffu) == (0xaa0003e0u | item.reg))
                        pending.push_back({p, (w >> 16) & 31, item.bias});
                    else if ((w & 0xbf80001fu) == (0x91000000u | item.reg)) {
                        int64_t amount = int64_t((w >> 10) & 4095) << ((w & 0x400000u) ? 12 : 0);
                        pending.push_back({p, (w >> 5) & 31, item.bias + ((w & 0x40000000u) ? -amount : amount)});
                    } else return {};
                }
            }
            return result;
        }
        Value resolve(uint64_t use, unsigned reg, unsigned) const {
            Key answerKey = canonical({use, reg});
            if (auto it = memo.find(answerKey); it != memo.end()) return it->second;
            struct Node { Key key; std::vector<std::pair<size_t, int64_t>> edges; std::set<AddressOrigin> seeds; bool complete = true; };
            std::vector<Node> nodes; std::map<Key, size_t> indices;
            auto node = [&](Key key) -> size_t {
                key = canonical(key);
                auto [it, inserted] = indices.try_emplace(key, nodes.size());
                if (inserted) nodes.push_back({key, {}, {}, true});
                return it->second;
            };
            node(answerKey);
            for (size_t n = 0; n < nodes.size(); ++n) {
                if (nodes.size() > 262144) return {{}, false};
                auto [pc, r] = nodes[n].key;
                if (auto it = memo.find({pc, r}); it != memo.end()) {
                    nodes[n].seeds = it->second.roots; nodes[n].complete = it->second.complete; continue;
                }
                auto seed = [&](uint64_t p) { nodes[n].seeds.insert({p, r, 0}); };
                auto edge = [&](uint64_t p, unsigned source, int64_t delta) {
                    if (source == 31) { seed(p); return; }
                    size_t target = node({p, source}); nodes[n].edges.push_back({target, delta});
                };
                const auto& from = predecessors[size_t((pc - lo) / 4)];
                if (from.empty()) seed(lo);
                for (uint64_t p : from) {
                    uint32_t w = image.instruction(p);
                    if (!changes(w, r)) edge(p, r, 0);
                    else if ((w & 0xbf80001fu) == (0x91000000u | r)) {
                        int64_t amount = int64_t((w >> 10) & 4095) << ((w & 0x400000u) ? 12 : 0);
                        edge(p, (w >> 5) & 31, (w & 0x40000000u) ? -amount : amount);
                    } else if ((w & 0xffe0ffffu) == (0xaa0003e0u | r)) edge(p, (w >> 16) & 31, 0);
                    else if ((w & 0xbf20001fu) == (0x8b000000u | r) && ((w >> 22) & 3) == 0) {
                        unsigned rn = (w >> 5) & 31, rm = (w >> 16) & 31, shift = (w >> 10) & 63;
                        auto c = shift <= 15 ? constant(p, rm) : std::nullopt;
                        auto other = (!(w & 0x40000000u) && !shift) ? constant(p, rn) : std::nullopt;
                        if (c) edge(p, rn, (w & 0x40000000u) ? -(*c << shift) : (*c << shift));
                        else if (other) edge(p, rm, *other);
                        else seed(p);
                    } else {
                        bool pair = (w & 0x3a000000u) == 0x28000000u;
                        bool memory = (w & 0x0a000000u) == 0x08000000u;
                        bool load = memory && !(w & 0x04000000u) && (w & 0x00400000u);
                        bool loadDestination = load && ((w & 31) == r || (pair && ((w >> 10) & 31) == r));
                        bool singleWriteback = (w & 0x3b200000u) == 0x38000000u && (((w >> 10) & 3) == 1 || ((w >> 10) & 3) == 3);
                        bool pairWriteback = pair && (((w >> 23) & 3) == 1 || ((w >> 23) & 3) == 3);
                        if (!loadDestination && (singleWriteback || pairWriteback) && ((w >> 5) & 31) == r) {
                            int scale = (w & 0x04000000u) ? (4 << ((w >> 30) & 3)) : ((w & 0x80000000u) ? 8 : 4);
                            edge(p, r, singleWriteback ? signExtend((w >> 12) & 511, 9) : signExtend((w >> 15) & 127, 7) * scale);
                        } else seed(p);
                    }
                }
            }
            // Iterative SCC decomposition: the amount of work is bounded by the
            // finite dependency graph, not the exponentially many CFG paths.
            for (;;) {
            size_t count = nodes.size(); std::vector<std::vector<size_t>> reverse(count);
            for (size_t n = 0; n < count; ++n) for (auto [dep, delta] : nodes[n].edges) { (void)delta; reverse[dep].push_back(n); }
            std::vector<bool> visited(count); std::vector<size_t> order;
            for (size_t first = 0; first < count; ++first) if (!visited[first]) {
                std::vector<std::pair<size_t, size_t>> stack{{first, 0}}; visited[first] = true;
                while (!stack.empty()) {
                    auto& [n, cursor] = stack.back();
                    if (cursor < nodes[n].edges.size()) {
                        size_t target = nodes[n].edges[cursor++].first;
                        if (!visited[target]) { visited[target] = true; stack.push_back({target, 0}); }
                    } else { order.push_back(n); stack.pop_back(); }
                }
            }
            std::vector<size_t> component(count, count); std::vector<std::vector<size_t>> members;
            for (auto it = order.rbegin(); it != order.rend(); ++it) if (component[*it] == count) {
                size_t group = members.size(); members.push_back({});
                std::vector<size_t> stack{*it}; component[*it] = group;
                while (!stack.empty()) {
                    size_t n = stack.back(); stack.pop_back(); members[group].push_back(n);
                    for (size_t target : reverse[n]) if (component[target] == count) { component[target] = group; stack.push_back(target); }
                }
            }
            std::vector<int64_t> potential(count); std::vector<bool> assigned(count);
            std::vector<Value> values(members.size()); std::set<size_t> induction;
            for (size_t group = 0; group < members.size(); ++group) {
                std::vector<size_t> stack{members[group].front()}; assigned[stack.front()] = true;
                while (!stack.empty()) {
                    size_t n = stack.back(); stack.pop_back(); values[group].complete &= nodes[n].complete;
                    for (auto [dep, delta] : nodes[n].edges) if (component[dep] == group) {
                        int64_t p = potential[n] - delta;
                        if (assigned[dep]) { if (potential[dep] != p) induction.insert(group); }
                        else { potential[dep] = p; assigned[dep] = true; stack.push_back(dep); }
                    }
                }
            }
            if (!induction.empty()) {
                // A varying pointer phi is an opaque value at each program
                // point, just like an unknown arithmetic result. Do not infer
                // cross-iteration aliases or enumerate arbitrary field biases.
                // Downstream explicit affine offsets are still audited; a hot
                // conjunction must independently prove its handle/skip object.
                for (size_t group : induction) for (size_t n : members[group]) {
                    nodes[n].edges.clear(); nodes[n].seeds = {{nodes[n].key.first, nodes[n].key.second, 0}};
                }
                continue;
            }
            auto insert = [&](size_t group, AddressOrigin root, int64_t delta) {
                root.offset += delta;
                if (root.offset < -kMaxAffineBias || root.offset > kMaxAffineBias || values[group].roots.size() >= 128) values[group].complete = false;
                else values[group].roots.insert(root);
            };
            std::vector<std::set<size_t>> dependencies(members.size()), users(members.size());
            std::vector<std::vector<std::pair<size_t, int64_t>>> weighted(members.size());
            for (size_t n = 0; n < count; ++n) {
                size_t group = component[n];
                for (auto root : nodes[n].seeds) insert(group, root, -potential[n]);
                for (auto [dep, delta] : nodes[n].edges) if (component[dep] != group) {
                    size_t other = component[dep]; dependencies[group].insert(other); users[other].insert(group);
                    weighted[group].push_back({other, potential[dep] + delta - potential[n]});
                }
            }
            std::deque<size_t> pending; std::vector<size_t> remaining(members.size());
            for (size_t group = 0; group < members.size(); ++group) { remaining[group] = dependencies[group].size(); if (!remaining[group]) pending.push_back(group); }
            while (!pending.empty()) {
                size_t group = pending.front(); pending.pop_front();
                for (auto [dep, delta] : weighted[group]) {
                    values[group].complete &= values[dep].complete;
                    for (auto root : values[dep].roots) insert(group, root, delta);
                }
                if (values[group].roots.empty()) values[group].complete = false;
                for (size_t user : users[group]) if (!--remaining[user]) pending.push_back(user);
            }
            for (size_t n = 0; n < count; ++n) {
                Value result{{}, values[component[n]].complete};
                for (auto root : values[component[n]].roots) {
                    root.offset += potential[n];
                    if (root.offset < -kMaxAffineBias || root.offset > kMaxAffineBias) result.complete = false;
                    else result.roots.insert(root);
                }
                memo.emplace(nodes[n].key, std::move(result));
            }
            return memo.at(answerKey);
            }
        }
    public:
        std::set<AddressOrigin> origins(uint64_t use, unsigned reg, int64_t displacement, bool& complete, unsigned = 0) const {
            auto value = resolve(use, reg, 0); std::set<AddressOrigin> out;
            complete = value.complete;
            for (auto root : value.roots) { root.offset += displacement; out.insert(root); }
            return out;
        }
    };
    void notes(const Elf64_Shdr& s) {
        uint64_t pos = s.sh_offset, end = pos + s.sh_size;
        if (end > bytes.size()) throw Error("invalid_elf", "Note outside file");
        while (pos + sizeof(Elf64_Nhdr) <= end) {
            auto n = at<Elf64_Nhdr>(bytes, pos); pos += sizeof(n);
            uint64_t nameEnd = pos + ((uint64_t(n.n_namesz) + 3) & ~3ull);
            uint64_t descEnd = nameEnd + ((uint64_t(n.n_descsz) + 3) & ~3ull);
            if (nameEnd > end || descEnd > end) throw Error("invalid_elf", "Invalid note length");
            if (n.n_type == NT_GNU_BUILD_ID && n.n_namesz == 4 && !memcmp(bytes.data() + pos, "GNU", 4) &&
                n.n_descsz >= 8 && n.n_descsz <= 32) {
                std::ostringstream o;
                for (uint64_t i = nameEnd; i < nameEnd + n.n_descsz; ++i)
                    o << std::hex << std::setw(2) << std::setfill('0') << unsigned(bytes[i]);
                buildId = o.str();
            }
            pos = descEnd;
        }
    }
    void parseUnwind() {
        auto p = unwind.sh_offset;
        // Supported DW_EH_PE encodings are checked, never guessed.
        if (unwind.sh_size < 12 || at<uint32_t>(bytes, p) != 0x3b031b01u)
            throw Error("unsupported_unwind", "Unsupported EH frame-header encoding");
        uint32_t count = at<uint32_t>(bytes, p + 8);
        if (!count || count > 100000 || 12ull + 8ull * count > unwind.sh_size)
            throw Error("invalid_elf", "Invalid EH frame-header count");
        for (uint32_t i = 0; i < count; ++i) {
            int32_t relative = at<int32_t>(bytes, p + 12 + uint64_t(i) * 8);
            uint64_t address = unwind.sh_addr + int64_t(relative);
            if (!starts.empty() && address <= starts.back()) throw Error("invalid_elf", "Unsorted unwind table");
            starts.push_back(address);
        }
    }
    bool vcallBetween(uint64_t lo, uint64_t hi, int slot) const {
        for (uint64_t a = lo; a + 4 < hi; a += 4) {
            uint32_t w = instruction(a); int rt = int(w & 31), rn = int((w >> 5) & 31);
            if (ldr64(w, rt, rn, slot) && blr(instruction(a + 4), rt)) return true;
        }
        return false;
    }
    int adjustedStateStore(uint64_t lo, uint64_t hi, int holder, unsigned member) const {
        // Virtual-base adjustment followed by editState(), then a byte field store.
        // This is a compiler-generated access path, not an alternate slot guess.
        for (uint64_t a = lo + 8; a + 40 < hi; a += 4) {
            uint32_t table = instruction(a), displacement = instruction(a + 4), indexed = instruction(a + 8);
            int objectReg = int((table >> 5) & 31), tableReg = int(table & 31), offsetReg = int(displacement & 31);
            int stateTable = int(indexed & 31);
            if (!ldr64(table, tableReg, objectReg, 0) || objectReg == 31 || tableReg == 31 || tableReg == objectReg ||
                (displacement & 0xffe00c00u) != 0xf8400000u || int((displacement >> 5) & 31) != tableReg ||
                ((displacement >> 12) & 511) != 488 || offsetReg == 31 || offsetReg == objectReg ||
                (indexed & 0xffe0fc00u) != 0xf8606800u || int((indexed >> 5) & 31) != objectReg ||
                int((indexed >> 16) & 31) != offsetReg || stateTable == 31 || stateTable == 0 ||
                stateTable == objectReg || stateTable == offsetReg ||
                instruction(a + 12) != (0x8b000000u | (unsigned(offsetReg) << 16) | (unsigned(objectReg) << 5))) continue;
            uint32_t middle = instruction(a - 4);
            bool harmless = int(middle & 31) != objectReg && ((middle & 0xffc00000u) == 0x39400000u ||
                (middle & 0xffe0ffe0u) == 0xaa0003e0u);
            bool object = ldr64(instruction(a - 4), objectReg, holder, 0) ||
                (harmless && ldr64(instruction(a - 8), objectReg, holder, 0));
            uint32_t method = instruction(a + 16); int methodReg = int(method & 31);
            int slot = int((method >> 10) & 4095) * 8;
            if (!object || !slot || methodReg == 0 || !ldr64(method, methodReg, stateTable, slot) || !blr(instruction(a + 20), methodReg)) continue;
            for (uint64_t p = a + 24; p <= a + 40 && p < hi; p += 4) {
                uint32_t w = instruction(p);
                if ((w & 0xffffffe0u) == (0x39000000u | (member << 10))) return slot;
                bool scalar = (w & 31) != 0 && ((w & 0xffc00000u) == 0xf9400000u ||
                    (w & 0xfffffc00u) == 0x530e3800u || (w & 0xffe00000u) == 0x52800000u ||
                    (w & 0xffe0ffe0u) == 0xaa0003e0u);
                if (!scalar) break;
            }
        }
        return -1;
    }
    Site match(uint64_t va) const {
        int source = int((instruction(va) >> 5) & 31);
        uint32_t load = instruction(va - 8);
        if ((load & 0xffe00c00u) != 0xb8400000u || int(load & 31) != source) return {};
        int displacement = int((load >> 12) & 511); if (displacement & 256) displacement -= 512;
        if (displacement != -24 || source == 31) return {};
        uint32_t objectLoad = instruction(va - 12);
        int holder = int((objectLoad >> 5) & 31);
        if (!ldr64(objectLoad, 0, holder, 0) || !ldr64(instruction(va - 4), 8, 0, 0) ||
            !ldr64(instruction(va + 4), 8, 8, 0x58) || !blr(instruction(va + 8), 8)) return {};
        auto it = std::upper_bound(starts.begin(), starts.end(), va);
        if (it == starts.begin() || it == starts.end()) return {};
        uint64_t hi = *it, lo = *(it - 1);
        if (lo < text.sh_addr || hi > text.sh_addr + text.sh_size || hi - lo < 512 || hi - lo > 4096 ||
            va - lo < 128 || hi - va < 256) return {};
        uint32_t first = instruction(lo);
        if (first != 0xd503233fu && first != 0xd503245fu) return {}; // PACIASP / BTI c
        // Source-level semantic sequence: secure/protected state; composition enable;
        // setLayerFilter; render surface; brightness; size; projection; debug name.
        int secureStateSlot = adjustedStateStore(lo, va, holder, 1);
        int protectedStateSlot = adjustedStateStore(lo, va, holder, 2);
        bool stateAccess = vcallBetween(lo, va, 0xc0) ||
            (secureStateSlot > 0 && secureStateSlot == protectedStateSlot);
        if (!vcallBetween(lo, va, 0x38) || !stateAccess ||
            !vcallBetween(lo, va, 0x28) || !vcallBetween(va + 12, hi, 0xb8) ||
            !vcallBetween(va + 12, hi, 0x78) || !vcallBetween(va + 12, hi, 0x50) ||
            !vcallBetween(va + 12, hi, 0x40) || !vcallBetween(va + 12, hi, 0x98)) return {};
        bool usageProtected = false, secureStore = false, protectedStore = false;
        for (uint64_t a = lo; a < va; a += 4) {
            uint32_t w = instruction(a);
            // UBFM Wd,Wn,#14,#14 (UBFX for GRALLOC_USAGE_PROTECTED).
            if ((w & 0xfffffc00u) == 0x530e3800u) usageProtected = true;
            if ((w & 0xffffffe0u) == 0x39000400u) secureStore = true; // STRB Wn,[X0,#1]
            if ((w & 0xffffffe0u) == 0x39000800u) protectedStore = true; // STRB Wn,[X0,#2]
        }
        if (!usageProtected || !secureStore || !protectedStore) return {};
        return {va, offset(va), lo, hi, instruction(va), 0xaa0003e1u | (unsigned(source) << 16)};
    }
    Site matchExportedFactory(uint64_t va) const {
        auto symbols = dynamicSymbols();
        auto factory = named(symbols, "_ZN7android25createScreenCaptureOutputENS_23ScreenCaptureOutputArgsE");
        auto table = named(symbols, "_ZTVN7android19ScreenCaptureOutputE");
        auto setter = named(symbols, "_ZN7android17compositionengine4impl6Output14setLayerFilterENS_11LayerFilterE");
        if (!factory.value || !table.value || !setter.value || va < factory.value || va >= factory.value + factory.size) return {};
        auto [lo, hi] = bounds(va);
        if (factory.value != lo || factory.size != hi - lo || factory.size < 512 || factory.size > 8192 ||
            instruction(lo) != 0xd503233fu || va - lo < 128 || hi - va < 128) return {};
        auto rel = relocations();
        auto target = [&](uint64_t address, const std::string& name) {
            auto r = rel.find(address);
            return r != rel.end() && r->second.type == 257 && r->second.symbol < symbols.size() &&
                symbols[r->second.symbol].name == name && r->second.addend == 0;
        };
        // Establish the actual address point from the constructor's GOT relocation,
        // add-immediate and store. Thus neither a virtual slot nor object subobject
        // offset is accepted merely because it resembles a previous firmware.
        uint64_t point = 0; unsigned holder = 31, objectOffset = 0; int vtableRegister = -1;
        for (uint64_t a = lo; a + 36 < va; a += 4) {
            uint32_t adrp = instruction(a); unsigned reg = adrp & 31;
            if ((adrp & 0x9f000000u) != 0x90000000u || reg == 31) continue;
            for (uint64_t b = a + 4; b <= a + 24; b += 4) {
                uint32_t load = instruction(b);
                if ((load & 0xffc003ffu) != (0xf9400000u | (reg << 5) | reg)) continue;
                uint64_t got = pageAddress(a, adrp) + ((load >> 10) & 4095) * 8;
                auto gotRel = rel.find(got);
                if (gotRel == rel.end() || gotRel->second.type != 1025 || gotRel->second.addend ||
                    gotRel->second.symbol >= symbols.size()) continue;
                const auto& actualTable = symbols[gotRel->second.symbol];
                if (!actualTable.name.starts_with("_ZTV") || actualTable.name.find("ScreenCaptureOutput") == std::string::npos ||
                    !actualTable.section || !actualTable.value || !actualTable.size) continue;
                for (uint64_t c = b + 4; c <= b + 16; c += 4) {
                    uint32_t add = instruction(c); unsigned dst = add & 31;
                    if ((add & 0xffc003e0u) != (0x91000000u | (reg << 5)) || dst == 31) continue;
                    unsigned adjustment = (add >> 10) & 4095;
                    for (uint64_t d = c + 4; d <= c + 24; d += 4) {
                        uint32_t store = instruction(d);
                        if ((store & 0xffc0001fu) != (0xf9000000u | dst)) continue;
                        unsigned base = (store >> 5) & 31, off = ((store >> 10) & 4095) * 8;
                        if (base == 31 || adjustment >= actualTable.size) continue;
                        // This address point must actually implement LayerFilter.
                        bool hasSetter = false;
                        for (uint64_t slot = actualTable.value + adjustment; slot < actualTable.value + actualTable.size; slot += 8)
                            hasSetter |= target(slot, setter.name);
                        if (!hasSetter) continue;
                        if (point && (point != actualTable.value + adjustment || holder != base || objectOffset != off)) return {};
                        point = actualTable.value + adjustment; holder = base; objectOffset = off; vtableRegister = int(dst);
                    }
                }
            }
        }
        if (!point || vtableRegister < 0) return {};
        unsigned source = (instruction(va) >> 5) & 31;
        uint32_t stackLoad = instruction(va - 12), methodLoad = instruction(va - 4);
        unsigned tableReg = methodLoad >> 5 & 31, methodReg = methodLoad & 31;
        // Unsigned 32-bit LayerStack, unchanged output object, packed bit40 only.
        if (source == 31 || (stackLoad & 0xffc0001fu) != (0xb9400000u | source) ||
            !ldr64(instruction(va - 16), int(tableReg), int(holder), int(objectOffset)) ||
            (instruction(va - 8) & 0xffe0ffffu) != 0xaa0003e0u ||
            !ldr64(methodLoad, int(methodReg), int(tableReg), int((methodLoad >> 10 & 4095) * 8)) ||
            !blr(instruction(va + 4), int(methodReg))) return {};
        unsigned object = (instruction(va - 8) >> 16) & 31;
        if (!target(point + ((methodLoad >> 10) & 4095) * 8, setter.name)) return {};
        auto call = [&](const char* name, uint64_t begin, uint64_t end) {
            for (uint64_t a = begin; a + 4 < end; a += 4) {
                uint32_t w = instruction(a); unsigned rt = w & 31, rn = (w >> 5) & 31;
                if (!ldr64(w, int(rt), int(rn), int(((w >> 10) & 4095) * 8)) ||
                    !target(point + ((w >> 10) & 4095) * 8, name)) continue;
                for (uint64_t b = a + 4; b <= a + 12 && b < end; b += 4) {
                    uint32_t next = instruction(b);
                    if (blr(next, int(rt))) return true;
                    // Scheduling may place argument stack stores before BLR.
                    bool scalarStore = (next & 0x3b400000u) == 0x39000000u;
                    bool pairStore = (next & 0x3a400000u) == 0x28000000u;
                    if (!scalarStore && !pairStore) break;
                }
            }
            return false;
        };
        if (!call("_ZN7android17compositionengine4impl6Output21setCompositionEnabledEb", lo, va) ||
            !call("_ZN7android17compositionengine4impl6Output16setRenderSurfaceENSt3__110unique_ptrINS0_13RenderSurfaceENS3_14default_deleteIS5_EEEE", va + 8, hi) ||
            !call("_ZN7android17compositionengine4impl6Output20setDisplayBrightnessEff", va + 8, hi) ||
            !call("_ZN7android17compositionengine4impl6Output14setDisplaySizeERKNS_2ui4SizeE", va + 8, hi) ||
            !call("_ZN7android17compositionengine4impl6Output13setProjectionENS_2ui8RotationERKNS_4RectES7_", va + 8, hi) ||
            !call("_ZN7android17compositionengine4impl6Output7setNameERKNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE", va + 8, hi)) return {};
        // Secure and protected flags still use the same independently verified
        // virtual-base editState path. Preserve both of them and all factory calls.
        unsigned stateSlot = 0, stores = 0;
        for (uint64_t a = lo + 16; a + 24 < va; a += 4) {
            uint32_t disp = instruction(a); unsigned step = 4;
            if ((instruction(a + 4) & 0x3a400000u) == 0x28000000u) step = 8;
            uint32_t indexed = instruction(a + step);
            unsigned tr = (disp >> 5) & 31, dr = disp & 31, sr = indexed & 31;
            bool tableLoad = false;
            for (unsigned distance = 4; distance <= 16; distance += 4) {
                uint32_t prior = instruction(a - distance);
                if (ldr64(prior, int(tr), int(holder), int(objectOffset))) { tableLoad = true; break; }
                // Only a scheduled store or a MOV to a different GPR is harmless.
                if ((prior & 0x3a400000u) == 0x28000000u || (prior & 0x3b400000u) == 0x39000000u ||
                    ((prior & 0xffe0ffe0u) == 0xaa0003e0u && (prior & 31) != tr)) continue;
                break;
            }
            if (!tableLoad || (disp & 0xffe00fe0u) != (0xf8400000u | (tr << 5)) || ((disp >> 12) & 511) != 488 ||
                (indexed & 0xffe0fc00u) != 0xf8606800u || ((indexed >> 5) & 31) != object || ((indexed >> 16) & 31) != dr ||
                instruction(a + step + 4) != (0x8b000000u | (dr << 16) | (object << 5))) continue;
            uint32_t method = instruction(a + step + 8); unsigned slot = ((method >> 10) & 4095) * 8, mr = method & 31;
            if (!slot || !ldr64(method, int(mr), int(sr), int(slot)) || !blr(instruction(a + step + 12), int(mr))) continue;
            for (uint64_t p = a + step + 16; p <= a + step + 28; p += 4) {
                uint32_t store = instruction(p);
                if ((store & 0xffffffe0u) == 0x39000400u) { stores |= 1; if (stateSlot && stateSlot != slot) return {}; stateSlot = slot; }
                if ((store & 0xffffffe0u) == 0x39000800u && p >= a + step + 20 &&
                    (instruction(p - 8) & 0xfffffc00u) == 0x530e3800u) {
                    stores |= 2; if (stateSlot && stateSlot != slot) return {}; stateSlot = slot;
                }
            }
        }
        // The relocated setter itself must transfer packed stack/flags to state;
        // a forged export or unrelated vtable entry does not satisfy this proof.
        bool packedStore = false;
        for (uint64_t a = setter.value; a + 8 < setter.value + setter.size; a += 4) {
            uint32_t shift = instruction(a), word = instruction(a + 4), half = instruction(a + 8);
            unsigned input = (shift >> 5) & 31, temp = shift & 31;
            if ((shift & 0xfffffc00u) == 0xd360fc00u && word == (0xb9000800u | input) &&
                half == (0x79001800u | temp)) packedStore = true;
        }
        if (stores != 3 || !stateSlot || !packedStore) return {};
        return {va, offset(va), lo, hi, instruction(va), 0xaa0003e1u | (source << 16)};
    }
    Site matchPlainFactory(uint64_t va) const {
        auto [lo,hi]=bounds(va);
        if(!lo||hi-lo<512||hi-lo>8192||va<lo+128||hi<va+256)return {};
        unsigned source=(instruction(va)>>5)&31;
        uint32_t stack=instruction(va-12),move=instruction(va-8),method=instruction(va-4);
        unsigned holder=(move>>16)&31,table=(method>>5)&31,target=method&31,slot=((method>>10)&4095)*8;
        if(source==31||holder<19||holder>28||move!=(0xaa0003e0u|(holder<<16))||
           (stack&0xffe00c1fu)!=(0xb8400000u|source)||
           !ldr64(instruction(va-16),int(table),int(holder),0)||
           !ldr64(method,int(target),int(table),int(slot))||!blr(instruction(va+4),int(target)))return {};
        AddressFlow factory(*this,lo,hi);bool complete=false;
        auto stackOrigin=factory.origins(va-12,(stack>>5)&31,signExtend((stack>>12)&511,9),complete);
        if(!complete||stackOrigin!=std::set<AddressOrigin>{{lo,0,16}})return {};
        auto directStateSlot=[&](uint64_t begin,uint64_t end,unsigned object,unsigned member) {
            auto b=bounds(begin);AddressFlow f(*this,b.first,b.second);
            for(uint64_t a=begin;a+28<end;a+=4) {
                uint32_t tableLoad=instruction(a);unsigned tableReg=tableLoad&31;
                if(tableReg==object||tableReg==31||!ldr64(tableLoad,int(tableReg),int(object),0))continue;
                for(uint64_t d=a+4;d<=a+12&&d+20<end;d+=4) {
                    uint32_t displacement=instruction(d);unsigned offsetReg=displacement&31;
                    bool preserved=true;
                    for(uint64_t p=a+4;p<d;p+=4)preserved&=!f.writes(instruction(p),object)&&!f.writes(instruction(p),tableReg);
                    if(!preserved||(displacement&0xffe00fe0u)!=(0xf8400000u|(tableReg<<5))||((displacement>>12)&511)!=488||
                       offsetReg==31||offsetReg==object)continue;
                    uint32_t indexed=instruction(d+4);unsigned state=indexed&31;
                    if(state==0||state==31||state==object||state==offsetReg||
                       (indexed&0xffe0fc00u)!=0xf8606800u||((indexed>>5)&31)!=object||((indexed>>16)&31)!=offsetReg||
                       instruction(d+8)!=(0x8b000000u|(offsetReg<<16)|(object<<5)))continue;
                    uint32_t methodLoad=instruction(d+12);unsigned methodReg=methodLoad&31,methodSlot=((methodLoad>>10)&4095)*8;
                    if(methodReg==0||!methodSlot||!ldr64(methodLoad,int(methodReg),int(state),int(methodSlot))||!blr(instruction(d+16),int(methodReg)))continue;
                    for(uint64_t p=d+20;p<=d+40&&p<end;p+=4) {
                        uint32_t w=instruction(p);
                        if((w&0xffffffe0u)==(0x39000000u|(member<<10)))return int(methodSlot);
                        if(f.writes(w,0)||(w&0x7c000000u)==0x14000000u||(w&0xfffffc1fu)==0xd63f0000u)break;
                    }
                }
            }
            return -1;
        };
        int stateSlot=directStateSlot(lo,va,holder,1);
        if(stateSlot<=0||directStateSlot(lo,va,holder,2)!=stateSlot||
           !vcallBetween(lo,va,0x38)||!vcallBetween(lo,va,0x28)||
           !vcallBetween(va+8,hi,0xc0)||!vcallBetween(va+8,hi,0x78)||
           !vcallBetween(va+8,hi,0x50)||!vcallBetween(va+8,hi,0x40)||!vcallBetween(va+8,hi,0xa0))return {};
        bool usage=false;
        for(uint64_t a=lo;a<va;a+=4)usage|=(instruction(a)&0xfffffc00u)==0x530e3800u;
        if(!usage)return {};
        auto rel=relocations();
        // Establish the returned object through the actual shared_ptr sret ABI,
        // then the initializer called with that very object. The constructor's
        // final vtable store must belong to its X0 object, not a nearby allocation.
        std::set<uint64_t> points;
        for(uint64_t call=lo;call+8<va;call+=4) {
            if((instruction(call)&0xfc000000u)!=0x94000000u||!ldr64(instruction(call+4),int(holder),int(holder),0))continue;
            bool sret=false;
            for(unsigned back=4;back<=16&&call>=lo+back;back+=4) {
                uint32_t prior=instruction(call-back);
                if(prior==(0xaa0003e8u|(holder<<16))) {sret=true;break;}
                if((prior&0x3b400000u)!=0x39000000u)break;
            }
            if(!sret)continue;
            uint64_t helper=branch26(call);auto hb=bounds(helper);
            if(hb.first!=helper||hb.second-helper>16384)continue;
            AddressFlow returned(*this,hb.first,hb.second);
            for(uint64_t store=helper;store+4<hb.second;store+=4) {
                uint32_t pair=instruction(store);
                if((pair&0xffc00000u)!=0xa9000000u||((pair>>15)&127)!=0)continue;
                unsigned base=(pair>>5)&31,object=pair&31;
                auto destination=returned.origins(store,base,0,complete);
                if(!complete||destination!=std::set<AddressOrigin>{{helper,8,0}})continue;
                auto objectValue=returned.origins(store,object,0,complete);
                if(!complete||objectValue.size()!=1)continue;
                for(uint64_t init=helper;init<store;init+=4) {
                    if((instruction(init)&0xfc000000u)!=0x94000000u)continue;
                    auto argument=returned.origins(init,0,0,complete);
                    if(!complete||argument!=objectValue)continue;
                    uint64_t ctor=branch26(init);auto cb=bounds(ctor);
                    if(cb.first!=ctor||cb.second-ctor>16384)continue;
                    AddressFlow constructed(*this,ctor,cb.second);
                    for(uint64_t p=ctor;p+12<cb.second;p+=4) {
                        uint32_t page=instruction(p),add=instruction(p+4);unsigned reg=page&31;
                        if(reg==31||(page&0x9f000000u)!=0x90000000u||
                           (add&0xffc003ffu)!=(0x91000000u|(reg<<5)|reg))continue;
                        uint64_t point=pageAddress(p,page)+((add>>10)&4095);
                        auto entry=rel.find(point+slot);
                        if((point&7)||entry==rel.end()||entry->second.type!=1027||entry->second.symbol)continue;
                        uint64_t setter=uint64_t(entry->second.addend);auto sb=bounds(setter);
                        if(sb.first!=setter||sb.second-setter<64||sb.second-setter>4096)continue;
                        bool objectStore=false;
                        for(uint64_t q=p+8;q<=p+20&&q<cb.second;q+=4) {
                            uint32_t w=instruction(q);
                            if((w&0xffc0001fu)==(0xf9000000u|reg)&&((w>>10)&4095)==0) {
                                auto identity=constructed.origins(q,(w>>5)&31,0,complete);
                                objectStore=complete&&identity==std::set<AddressOrigin>{{ctor,0,0}};
                                break;
                            }
                            // A scheduled ADD to another register is harmless;
                            // any other value definition ends this local proof.
                            if((w&0xffc00000u)!=0x91000000u||(w&31)==reg)break;
                        }
                        if(!objectStore)continue;
                        AddressFlow packed(*this,setter,sb.second);bool setterValid=false;
                        for(uint64_t q=setter;q+40<sb.second;q+=4) {
                            uint32_t shift32=instruction(q),shift40=instruction(q+4);
                            unsigned value=(shift32>>5)&31,flag32=shift32&31,flag40=shift40&31;
                            if((shift32&0xfffffc00u)!=0xd360fc00u||shift40!=(0xd368fc00u|(value<<5)|flag40)||
                               flag32==31||flag40==31||flag32==flag40)continue;
                            auto input=packed.origins(q,value,0,complete);
                            if(!complete||input!=std::set<AddressOrigin>{{setter,1,0}})continue;
                            for(uint64_t q2=q+8;q2+8<sb.second&&q2<=q+48;q2+=4) {
                                uint32_t low=instruction(q2),middle=instruction(q2+4),high=instruction(q2+8);
                                unsigned field=((low>>10)&4095)*4;
                                if((low&0xffc003ffu)!=(0xb9000000u|value)||
                                   middle!=(0x39000000u|((field+4)<<10)|flag32)||
                                   high!=(0x39000000u|((field+5)<<10)|flag40))continue;
                                for(unsigned stateObject=19;stateObject<=28;++stateObject)
                                    if(directStateSlot(setter,sb.second,stateObject,field+4)==stateSlot&&
                                       directStateSlot(setter,sb.second,stateObject,field+5)==stateSlot)setterValid=true;
                            }
                        }
                        if(setterValid)points.insert(point);
                    }
                }
            }
        }
        if(points.size()!=1)return {};
        return {va,offset(va),lo,hi,instruction(va),0xaa0003e1u|(source<<16)};
    }

    std::pair<uint64_t, uint64_t> bounds(uint64_t va) const {
        auto it = std::upper_bound(starts.begin(), starts.end(), va);
        if (it == starts.begin() || it == starts.end()) return {};
        uint64_t lo = *(it - 1), hi = *it;
        if (lo < text.sh_addr || hi > text.sh_addr + text.sh_size) return {};
        return {lo, hi};
    }
    static int64_t signExtend(uint32_t value, unsigned bits) {
        return int64_t(value ^ (uint32_t(1) << (bits - 1))) - (int64_t(1) << (bits - 1));
    }
    uint64_t branch19(uint64_t va) const { return va + signExtend((instruction(va) >> 5) & 0x7ffff, 19) * 4; }
    uint64_t branch14(uint64_t va) const { return va + signExtend((instruction(va) >> 5) & 0x3fff, 14) * 4; }
    uint64_t branch26(uint64_t va) const { return va + signExtend(instruction(va) & 0x3ffffff, 26) * 4; }
    bool ldrByte(uint64_t va, int rt, int rn, unsigned off) const {
        return instruction(va) == (0x39400000u | (off << 10) | (unsigned(rn) << 5) | unsigned(rt));
    }
    bool ldrWord(uint64_t va, int rt, int rn, unsigned off) const {
        return off % 4 == 0 && instruction(va) == (0xb9400000u | ((off / 4) << 10) | (unsigned(rn) << 5) | unsigned(rt));
    }
    bool containsCall(uint64_t lo, uint64_t hi, uint64_t target) const {
        for (uint64_t a = lo; a < hi; a += 4)
            if ((instruction(a) & 0xfc000000u) == 0x94000000u && branch26(a) == target) return true;
        return false;
    }
    bool stringAt(uint64_t address, const char* wanted) const {
        size_t size = strlen(wanted) + 1;
        for (unsigned i = 0; i < header.e_shnum; ++i) {
            auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
            if (s.sh_type == SHT_NOBITS || !(s.sh_flags & SHF_ALLOC) || (s.sh_flags & (SHF_EXECINSTR | SHF_WRITE)) ||
                address < s.sh_addr || size > s.sh_size || address - s.sh_addr > s.sh_size - size) continue;
            uint64_t p = s.sh_offset + address - s.sh_addr;
            return p <= bytes.size() && size <= bytes.size() - p && !memcmp(bytes.data() + p, wanted, size);
        }
        return false;
    }
    bool stringReference(uint64_t va, const char* wanted) const {
        uint32_t page = instruction(va), add = instruction(va + 4);
        // ADRP X1; ADD X1,X1,#imm. These are return strings, not arbitrary data matches.
        if ((page & 0x9f00001fu) != 0x90000001u || (add & 0xffc003ffu) != 0x91000021u) return false;
        uint32_t imm = ((page >> 29) & 3) | (((page >> 5) & 0x7ffff) << 2);
        uint64_t address = (va & ~0xfffull) + signExtend(imm, 21) * 4096 + ((add >> 10) & 4095);
        return stringAt(address, wanted);
    }
    bool containsString(uint64_t lo, uint64_t hi, const char* wanted) const {
        for (uint64_t a = lo; a + 4 < hi; a += 4) if (stringReference(a, wanted)) return true;
        return false;
    }
    bool conditional(uint64_t va, unsigned condition, uint64_t target) const {
        return (instruction(va) & 0xff00001fu) == (0x54000000u | condition) && branch19(va) == target;
    }
    bool testBit(uint64_t va, unsigned reg, bool nonzero, uint64_t target) const {
        return (instruction(va) & 0xfff8001fu) == ((nonzero ? 0x37000000u : 0x36000000u) | reg) && branch14(va) == target;
    }
    bool compareBranch(uint64_t va, unsigned reg, bool nonzero, uint64_t target, bool wide = false) const {
        return (instruction(va) & 0xff00001fu) == ((wide ? 0xb4000000u : 0x34000000u) |
            (nonzero ? 0x01000000u : 0u) | reg) && branch19(va) == target;
    }
    bool directBranch(uint64_t va, uint64_t target) const {
        return (instruction(va) & 0xfc000000u) == 0x14000000u && branch26(va) == target;
    }
    bool callTo(uint64_t va, uint64_t target) const {
        return (instruction(va) & 0xfc000000u) == 0x94000000u && branch26(va) == target;
    }
    bool loadPair(uint64_t va, unsigned first, unsigned second, unsigned base, unsigned displacement) const {
        return displacement % 8 == 0 && displacement <= 504 && instruction(va) ==
            (0xa9400000u | ((displacement / 8) << 15) | (second << 10) | (base << 5) | first);
    }
    bool addImmediate(uint64_t va, unsigned result, unsigned base, unsigned immediate) const {
        return immediate <= 4095 && instruction(va) == (0x91000000u | (immediate << 10) | (base << 5) | result);
    }
    std::vector<Site> matchRecordingDirect() const {
        struct Layout {
            unsigned handle = 0, skip = 0, reach = 0, hidden = 0;
            unsigned vendorHidden = 0, alpha = 0, radius = 0, regions = 0, background = 0, backgroundState = 0;
            uint64_t reasonLo = 0, reasonHi = 0, reasonLoad = 0, drawCall = 0, alphaCall = 0;
        } layout;
        unsigned diagnostics = 0;
        for (uint64_t xref = text.sh_addr + 24; xref + 8 < text.sh_addr + text.sh_size; xref += 4) {
            if (!stringReference(xref, "eLayerSkipScreenshot")) continue;
            auto [lo, hi] = bounds(xref);
            if (!lo || hi - lo < 512 || hi - lo > 8192 || instruction(lo) != 0xd503245fu ||
                !containsString(lo, hi, "layer not reachable from root") ||
                !containsString(lo, hi, "layer only reachable via relative parent") ||
                !containsString(lo, hi, "nothing to draw")) continue;
            uint64_t skipLoad = xref - 24;
            uint32_t skipWord = instruction(skipLoad);
            if ((skipWord & 0xffc003ffu) != 0x39400009u) continue;
            unsigned skip = (skipWord >> 10) & 4095;
            for (uint64_t a = lo + 24; a + 24 < skipLoad; a += 4) {
                uint32_t handleWord = instruction(a);
                if ((handleWord & 0xffc003ffu) != 0x39400009u || instruction(a + 4) != 0x7100053fu ||
                    !conditional(a + 8, 0, skipLoad) || !testBit(skipLoad + 4, 9, false, a + 12)) continue;
                unsigned hidden = (instruction(a - 24) >> 10) & 4095;
                if (hidden < 1 || !ldrByte(a - 24, 9, 0, hidden) || instruction(a - 20) != 0x7100053fu ||
                    (instruction(a - 16) & 0xff00001fu) != 0x54000000u ||
                    !ldrByte(a - 12, 9, 0, hidden + 1) || instruction(a - 8) != 0x7100053fu ||
                    (instruction(a - 4) & 0xff00001fu) != 0x54000000u ||
                    !ldrByte(a + 12, 9, 0, hidden - 1) || instruction(a + 16) != 0x7100053fu ||
                    (instruction(a + 20) & 0xff00001fu) != 0x54000001u) continue;
                unsigned reach = 0, reachMatches = 0;
                for (uint64_t p = lo + 4; p + 20 <= a - 24; p += 4) {
                    uint32_t w = instruction(p);
                    if ((w & 0xffc003ffu) != 0xb9400009u || instruction(p + 4) != 0x7100053fu ||
                        (instruction(p + 8) & 0xff00001fu) != 0x54000000u ||
                        instruction(p + 12) != 0x7100093fu ||
                        (instruction(p + 16) & 0xff00001fu) != 0x54000000u) continue;
                    reach = ((w >> 10) & 4095) * 4; ++reachMatches;
                }
                if (reachMatches != 1 || !reach) continue;
                layout.handle = (handleWord >> 10) & 4095; layout.skip = skip; layout.reach = reach;
                layout.hidden = hidden; layout.reasonLo = lo; layout.reasonHi = hi; layout.reasonLoad = a;
                ++diagnostics;
            }
        }
        if (!diagnostics) throw Error("signature_not_found", "No direct-load mirrored visibility diagnostic");
        if (diagnostics != 1) throw Error("signature_ambiguous", "Multiple direct-load mirrored visibility diagnostics");
        std::vector<uint64_t> loads;
        for (uint64_t a = text.sh_addr; a + 4 < text.sh_addr + text.sh_size; a += 4) {
            uint32_t w = instruction(a);
            if ((w & 0xffc00000u) == 0x39400000u && ((w >> 10) & 4095) == layout.handle) loads.push_back(a);
            // Reject a new, unaudited indexed implementation instead of silently
            // treating this direct-load profile as complete.
            if ((w & 0xffe00000u) == 0x52800000u && ((w >> 5) & 65535) == layout.handle)
                throw Error("signature_not_found", "Unclassified indexed handle-field occurrence");
        }
        std::vector<Site> standalone;
        for (uint64_t a : loads) {
            auto [lo, hi] = bounds(a);
            if (!lo || hi - lo != 192 || a != lo + 20 || instruction(lo) != 0xd503245fu ||
                !ldrWord(lo + 4, 8, 0, layout.reach) || !compareBranch(lo + 8, 8, false, a) ||
                instruction(lo + 12) != 0x2a1f03e0u || instruction(lo + 16) != 0xd65f03c0u ||
                !ldrByte(a, 8, 0, layout.handle) || instruction(lo + 24) != 0x7100051fu ||
                !conditional(lo + 28, 1, lo + 40) || !ldrByte(lo + 32, 8, 0, layout.skip) ||
                !testBit(lo + 36, 8, true, lo + 12) || instruction(lo + 40) != 0xa9be7bfdu ||
                instruction(lo + 44) != 0xf9000bf3u || instruction(lo + 48) != 0x910003fdu ||
                instruction(lo + 52) != 0xaa0003f3u ||
                (instruction(lo + 56) & 0xfc000000u) != 0x94000000u ||
                !testBit(lo + 60, 0, false, lo + 172) || !ldrByte(lo + 64, 9, 19, layout.hidden - 1) ||
                instruction(lo + 68) != 0x2a1f03e0u || !testBit(lo + 72, 9, true, lo + 176) ||
                instruction(lo + 76) != 0xaa1303e8u || !ldrByte(lo + 80, 9, 19, layout.hidden) ||
                !testBit(lo + 84, 9, true, lo + 176) || !ldrByte(lo + 88, 9, 8, layout.hidden + 1) ||
                !testBit(lo + 92, 9, true, lo + 176) ||
                (instruction(lo + 96) & 0xffc003ffu) != 0x39400109u ||
                !testBit(lo + 100, 9, true, lo + 176) ||
                (instruction(lo + 104) & 0xffc003ffu) != 0x91000100u ||
                (instruction(lo + 108) & 0xfc000000u) != 0x94000000u ||
                instruction(lo + 112) != 0x1e202008u || instruction(lo + 116) != 0x52800020u ||
                !conditional(lo + 120, 12, lo + 176) ||
                (instruction(lo + 124) & 0xffc003ffu) != 0xb9400268u ||
                instruction(lo + 128) != 0x7100011fu || !conditional(lo + 132, 12, lo + 176) ||
                (instruction(lo + 136) & 0xffc07fffu) != 0xa9402269u ||
                instruction(lo + 140) != 0xeb09011fu || !conditional(lo + 144, 1, lo + 176) ||
                (instruction(lo + 148) & 0xffc003ffu) != 0xf9400268u ||
                !compareBranch(lo + 152, 8, false, lo + 172, true) ||
                (instruction(lo + 156) & 0xffc003ffu) != 0xb9400268u ||
                instruction(lo + 160) != 0x7100051fu || instruction(lo + 164) != 0x1a9f17e0u ||
                !directBranch(lo + 168, lo + 176) || instruction(lo + 172) != 0x2a1f03e0u ||
                instruction(lo + 176) != 0xf9400bf3u || instruction(lo + 180) != 0xa8c27bfdu ||
                instruction(lo + 184) != 0xd65f03c0u || instruction(lo + 188) != 0) continue;
            uint64_t draw = branch26(lo + 56), alpha = branch26(lo + 108);
            if (!containsCall(layout.reasonLo, layout.reasonHi, draw) ||
                !containsCall(layout.reasonLo, layout.reasonHi, alpha)) continue;
            layout.vendorHidden = (instruction(lo + 96) >> 10) & 4095;
            layout.alpha = (instruction(lo + 104) >> 10) & 4095;
            layout.radius = ((instruction(lo + 124) >> 10) & 4095) * 4;
            layout.regions = ((instruction(lo + 136) >> 15) & 127) * 8;
            layout.background = ((instruction(lo + 148) >> 10) & 4095) * 8;
            layout.backgroundState = ((instruction(lo + 156) >> 10) & 4095) * 4;
            layout.drawCall = draw; layout.alphaCall = alpha;
            standalone.push_back({a, offset(a), lo, hi, instruction(a), 0x2a1f03e8u});
        }
        if (standalone.size() > 1) throw Error("signature_ambiguous", "Multiple complete direct-load visibility functions");
        if (standalone.size() != 1) throw Error("signature_not_found", "Missing complete direct-load visibility function");
        auto spLoad = [&](uint64_t a, unsigned reg) { return ldr64(instruction(a), int(reg), 31, 0); };
        auto sameAdd = [&](uint64_t a) { return addImmediate(a, 0, 8, layout.alpha); };
        auto firstClone = [&](uint64_t a, uint64_t lo, uint64_t hi) {
            if (a < lo + 32 || a + 184 >= hi || hi - lo > 65536) return false;
            uint64_t reject = a + 180, success = a + 164;
            return instruction(a - 32) == 0xa94023f3u && ldrWord(a - 12, 8, 19, layout.reach) &&
                compareBranch(a - 8, 8, true, reject) && spLoad(a - 4, 8) &&
                ldrByte(a, 8, 8, layout.handle) && instruction(a + 4) == 0x7100051fu &&
                conditional(a + 8, 1, a + 24) && spLoad(a + 12, 8) && ldrByte(a + 16, 8, 8, layout.skip) &&
                testBit(a + 20, 8, true, reject) && spLoad(a + 24, 0) && callTo(a + 28, layout.drawCall) &&
                testBit(a + 32, 0, false, reject) && spLoad(a + 36, 8) &&
                ldrByte(a + 40, 8, 8, layout.hidden - 1) && testBit(a + 44, 8, true, reject) &&
                spLoad(a + 48, 8) && ldrByte(a + 52, 8, 8, layout.hidden) && testBit(a + 56, 8, true, reject) &&
                spLoad(a + 60, 8) && ldrByte(a + 64, 8, 8, layout.hidden + 1) && testBit(a + 68, 8, true, reject) &&
                spLoad(a + 72, 8) && ldrByte(a + 76, 8, 8, layout.vendorHidden) && testBit(a + 80, 8, true, reject) &&
                spLoad(a + 84, 8) && sameAdd(a + 88) && callTo(a + 92, layout.alphaCall) &&
                instruction(a + 96) == 0x1e202008u && conditional(a + 100, 12, success) &&
                spLoad(a + 104, 8) && ldrWord(a + 108, 8, 8, layout.radius) && instruction(a + 112) == 0x7100011fu &&
                conditional(a + 116, 12, success) && spLoad(a + 120, 8) &&
                loadPair(a + 124, 8, 9, 8, layout.regions) && instruction(a + 128) == 0xeb08013fu &&
                conditional(a + 132, 1, success) && spLoad(a + 136, 8) &&
                ldr64(instruction(a + 140), 8, 8, int(layout.background)) &&
                compareBranch(a + 144, 8, false, reject, true) && spLoad(a + 148, 8) &&
                ldrWord(a + 152, 8, 8, layout.backgroundState) && instruction(a + 156) == 0x7100051fu &&
                conditional(a + 160, 1, reject) && spLoad(success, 8) && ldrWord(a + 168, 9, 8, layout.reach) &&
                (instruction(a + 172) & 0xff00001fu) == 0x34000009u &&
                branch19(a + 172) > success && branch19(a + 172) < hi && spLoad(reject, 8);
        };
        auto secondClone = [&](uint64_t a, uint64_t lo, uint64_t hi) {
            if (a < lo + 28 || a + 192 >= hi || hi - lo > 65536) return false;
            uint64_t reject = a + 152, result = a + 188, background = a + 160;
            return spLoad(a - 28, 8) && ldrWord(a - 8, 9, 8, layout.reach) &&
                (instruction(a - 4) & 0xff00001fu) == 0x35000009u && branch19(a - 4) >= lo && branch19(a - 4) < a &&
                ldrByte(a, 8, 8, layout.handle) && instruction(a + 4) == 0x7100051fu &&
                conditional(a + 8, 1, a + 24) && spLoad(a + 12, 8) && ldrByte(a + 16, 8, 8, layout.skip) &&
                testBit(a + 20, 8, true, reject) && spLoad(a + 24, 0) && callTo(a + 28, layout.drawCall) &&
                testBit(a + 32, 0, false, reject) && spLoad(a + 36, 8) &&
                ldrByte(a + 40, 9, 8, layout.hidden - 1) && instruction(a + 44) == 0x2a1f03e8u &&
                testBit(a + 48, 9, true, result) && spLoad(a + 52, 9) &&
                ldrByte(a + 56, 9, 9, layout.hidden) && testBit(a + 60, 9, true, result) && spLoad(a + 64, 9) &&
                ldrByte(a + 68, 9, 9, layout.hidden + 1) && testBit(a + 72, 9, true, result) && spLoad(a + 76, 9) &&
                ldrByte(a + 80, 9, 9, layout.vendorHidden) && testBit(a + 84, 9, true, result) &&
                spLoad(a + 88, 8) && sameAdd(a + 92) && callTo(a + 96, layout.alphaCall) &&
                instruction(a + 100) == 0x1e202008u && instruction(a + 104) == 0x52800028u &&
                conditional(a + 108, 12, result) && spLoad(a + 112, 9) && ldrWord(a + 116, 9, 9, layout.radius) &&
                instruction(a + 120) == 0x7100013fu && conditional(a + 124, 12, result) && spLoad(a + 128, 8) &&
                loadPair(a + 132, 8, 9, 8, layout.regions) && instruction(a + 136) == 0xeb08013fu &&
                conditional(a + 140, 0, background) && instruction(a + 144) == 0x52800028u &&
                directBranch(a + 148, result) && instruction(reject) == 0x2a1f03e8u && directBranch(a + 156, result) &&
                spLoad(background, 8) && ldr64(instruction(a + 164), 8, 8, int(layout.background)) &&
                compareBranch(a + 168, 8, false, result, true) && spLoad(a + 172, 8) &&
                ldrWord(a + 176, 8, 8, layout.backgroundState) && instruction(a + 180) == 0x7100051fu &&
                instruction(a + 184) == 0x1a9f17e8u && spLoad(result, 9);
        };
        auto inheritance = [&](uint64_t a, uint64_t lo, uint64_t hi) {
            // Propagation into a child snapshot is not a visibility decision.
            // Classify and preserve it; never patch the source layer's flag.
            if (a + 40 > hi || a < lo) return false;
            uint32_t load = instruction(a); unsigned source = (load >> 5) & 31, value = load & 31;
            uint32_t store = instruction(a + 36); unsigned result = store & 31;
            return (store & 0xffc00000u) == 0x39000000u && ((store >> 10) & 4095) == layout.handle &&
                instruction(a + 4) == (0x52800020u | result) && testBit(a + 8, value, true, a + 36) &&
                (instruction(a + 12) & 0xffc0001fu) == (0xb9400000u | value) &&
                instruction(a + 16) == (0x3100041fu | (value << 5)) && conditional(a + 20, 1, a + 36) &&
                (instruction(a + 24) & 0xffc0001fu) == (0xb9400000u | result) &&
                instruction(a + 28) == (0x3100041fu | (result << 5)) && instruction(a + 32) == (0x1a9f07e0u | result) &&
                source != ((store >> 5) & 31);
        };
        std::vector<Site> result = standalone;
        for (uint64_t a : loads) {
            if (a == layout.reasonLoad || a == standalone[0].vaddr) continue;
            auto [lo, hi] = bounds(a);
            if (lo && (firstClone(a, lo, hi) || secondClone(a, lo, hi)))
                result.push_back({a, offset(a), lo, hi, instruction(a), 0x2a1f03e8u});
            else if (!lo || !inheritance(a, lo, hi))
                throw Error("signature_not_found", "Unclassified direct-load handle-field occurrence at " + hex(a));
        }
        // Audit the other side of every visibility conjunction as well. A missing
        // handle load must not silently reduce the discovered clone count while
        // leaving a live skipScreenshot rejection behind.
        std::set<uint64_t> classifiedSkip;
        classifiedSkip.insert(layout.reasonLoad == 0 ? 0 : [&] {
            for (uint64_t p = layout.reasonLo; p + 4 < layout.reasonHi; p += 4)
                if (ldrByte(p, 9, 0, layout.skip) && testBit(p + 4, 9, false, layout.reasonLoad + 12)) return p;
            return uint64_t(0);
        }());
        for (const auto& s : result) classifiedSkip.insert(s.vaddr + (s.vaddr == standalone[0].vaddr ? 12 : 16));
        if (classifiedSkip.count(0)) throw Error("signature_not_found", "Missing classified diagnostic skip guard");
        for (uint64_t p = text.sh_addr; p + 4 < text.sh_addr + text.sh_size; p += 4) {
            uint32_t w = instruction(p); unsigned reg = w & 31;
            if ((w & 0xffc00000u) != 0x39400000u || ((w >> 10) & 4095) != layout.skip ||
                (instruction(p + 4) & 0xfef8001fu) != (0x36000000u | reg)) continue;
            auto [lo, hi] = bounds(p);
            if (lo && containsCall(lo, hi, layout.drawCall) && containsCall(lo, hi, layout.alphaCall) &&
                !classifiedSkip.count(p)) throw Error("signature_not_found", "Unclassified mirrored skip guard at " + hex(p));
        }
        if (result.empty() || result.size() > kMaxRecordingSites)
            throw Error("signature_ambiguous", "Audited visibility site count exceeds ownership bounds");
        std::sort(result.begin(), result.end(), [](const Site& a, const Site& b) { return a.vaddr < b.vaddr; });
        return result;
    }
    std::vector<Site> matchRecording() const {
        std::vector<Site> result;
        std::string rejected;
        for (unsigned profile = 0; profile < 4; ++profile) {
            try {
                auto found = profile == 0 ? matchRecordingIndexed() : profile == 1 ? matchRecordingDirect() : profile == 2 ? matchRecordingExported() : matchRecordingPlainDirect();
                if (!result.empty()) throw Error("signature_ambiguous", "Multiple independently valid visibility profiles");
                result = std::move(found);
            } catch (const Error& error) {
                if (error.reason != "signature_not_found") throw;
                if (!rejected.empty()) rejected += "; ";
                rejected += std::string(profile == 0 ? "indexed: " : profile == 1 ? "direct: " : profile == 2 ? "exported: " : "plain-direct: ") + error.what();
            }
        }
        if (result.empty()) throw Error("signature_not_found", "No complete audited mirrored-layer visibility implementation (" + rejected + ")");
        return result;
    }
    std::vector<Site> matchRecordingExported() const {
        auto symbols = dynamicSymbols();
        auto visible = named(symbols, "_ZNK7android14surfaceflinger8frontend13LayerSnapshot12getIsVisibleEv");
        auto reason = named(symbols, "_ZNK7android14surfaceflinger8frontend13LayerSnapshot18getIsVisibleReasonEv");
        auto draw = named(symbols, "_ZNK7android14surfaceflinger8frontend13LayerSnapshot9hasEffectEv");
        auto validFunction = [&](const NamedSymbol& s) {
            auto b = bounds(s.value);
            return s.value && b.first == s.value && b.second - b.first == s.size && s.size >= 32 && s.size <= 8192;
        };
        if (!validFunction(visible) || !validFunction(reason) || !validFunction(draw))
            throw Error("signature_not_found", "Missing linked exported visibility functions");
        auto anyString = [&](uint64_t a, const char* wanted) {
            uint32_t page = instruction(a), add = instruction(a + 4); unsigned reg = page & 31;
            return (page & 0x9f000000u) == 0x90000000u && reg != 31 &&
                (add & 0xffc003ffu) == (0x91000000u | (reg << 5) | reg) &&
                stringAt(pageAddress(a, page) + ((add >> 10) & 4095), wanted);
        };
        uint64_t diagnostic = 0; unsigned handle = 0, skip = 0, hidden = 0, reach = 0;
        for (uint64_t a = reason.value + 24; a + 8 < reason.value + reason.size; a += 4) {
            if (!anyString(a, "eLayerSkipScreenshot")) continue;
            uint32_t bias = instruction(a - 24), load = instruction(a - 20), cmp = instruction(a - 16);
            unsigned base = bias & 31, value = load & 31;
            uint64_t continuation = branch19(a - 12);
            if ((bias & 0xffc003e0u) != 0x91000000u || base == 0 || base == 31 ||
                (load & 0xffc003e0u) != (0x39400000u | (base << 5)) ||
                cmp != (0x7100041fu | (value << 5)) || !conditional(a - 12, 1, continuation) ||
                (instruction(a - 8) & 0xffc003ffu) != (0x39400000u | value) ||
                !testBit(a - 4, value, false, continuation) || continuation <= a ||
                continuation >= reason.value + reason.size) continue;
            if (diagnostic) throw Error("signature_ambiguous", "Multiple exported skip diagnostics");
            diagnostic = a - 20; handle = ((bias >> 10) & 4095) + ((load >> 10) & 4095);
            skip = (instruction(a - 8) >> 10) & 4095;
        }
        bool reachReason = false, relativeReason = false, drawReason = false;
        for (uint64_t a = reason.value; a + 4 < reason.value + reason.size; a += 4) {
            reachReason |= anyString(a, "layer not reachable from root");
            relativeReason |= anyString(a, "layer only reachable via relative parent");
            drawReason |= anyString(a, "nothing to draw");
        }
        if (!diagnostic || !handle || handle > 65535 || !skip || !reachReason || !relativeReason || !drawReason)
            throw Error("signature_not_found", "Incomplete exported visibility diagnostic semantics");
        uint64_t lo = visible.value, hi = lo + visible.size;
        uint32_t reachLoad = instruction(lo + 4), index = instruction(lo + 12), load = instruction(lo + 16);
        unsigned reg = index & 31;
        reach = ((reachLoad >> 10) & 4095) * 4;
        if (instruction(lo) != 0xd503245fu || (reachLoad & 0xffc003ffu) != (0xb9400000u | reg) || !reach ||
            !compareBranch(lo + 8, reg, true, lo + 36) ||
            index != (0x52800000u | (handle << 5) | reg) || reg == 0 || reg == 31 ||
            load != (0x38606800u | (reg << 16) | reg) ||
            instruction(lo + 20) != (0x7100041fu | (reg << 5)) || !conditional(lo + 24, 1, lo + 44) ||
            !ldrByte(lo + 28, int(reg), 0, skip) || !testBit(lo + 32, reg, false, lo + 44) ||
            instruction(lo + 36) != 0x2a1f03e0u || instruction(lo + 40) != 0xd65f03c0u ||
            instruction(lo + 44) != 0xd503233fu)
            throw Error("signature_not_found", "Exported visibility does not isolate handle-and-skip rejection");
        // Verify diagnostic/getter field agreement for reachability and all three
        // hidden reasons, plus the real hasSomethingToDraw call through its PLT.
        bool reasonReach = false, hiddenTriple = false, alpha = false, blur = false, drawCall = false;
        uint64_t drawTarget = 0; unsigned alphaOffset = 0;
        for (uint64_t a = reason.value; a + 20 < reason.value + reason.size; a += 4) {
            uint32_t w = instruction(a);
            if ((w & 0xffc003e0u) == 0xb9400000u && ((w >> 10) & 4095) * 4 == reach) reasonReach = true;
        }
        auto rel = relocations();
        for (uint64_t a = lo + 48; a + 20 < hi; a += 4) {
            uint32_t w = instruction(a);
            if ((w & 0xfc000000u) == 0x94000000u) {
                uint64_t target = branch26(a);
                if (target == draw.value) { drawCall = true; drawTarget = target; }
                else {
                    // AArch64 standard PLT: ADRP X16; LDR X17; ADD X16; BR X17.
                    for (unsigned i = 0; i < header.e_shnum; ++i) {
                        auto s = at<Elf64_Shdr>(bytes, header.e_shoff + uint64_t(i) * sizeof(Elf64_Shdr));
                        if (!(s.sh_flags & SHF_EXECINSTR) || target < s.sh_addr || target + 16 > s.sh_addr + s.sh_size) continue;
                        auto base = s.sh_offset + target - s.sh_addr;
                        uint32_t p = at<uint32_t>(bytes, base), l = at<uint32_t>(bytes, base + 4);
                        if ((p & 0x9f00001fu) != 0x90000010u || (l & 0xffc003ffu) != 0xf9400211u ||
                            at<uint32_t>(bytes, base + 12) != 0xd61f0220u) continue;
                        uint64_t got = pageAddress(target, p) + ((l >> 10) & 4095) * 8;
                        auto r = rel.find(got);
                        if (r != rel.end() && r->second.type == 1026 && r->second.symbol < symbols.size() &&
                            symbols[r->second.symbol].name == draw.name && r->second.addend == 0) { drawCall = true; drawTarget = target; }
                    }
                }
            }
            if ((w & 0xffc00000u) == 0x39400000u) {
                unsigned off = (w >> 10) & 4095, rn = (w >> 5) & 31, rt = w & 31;
                if (off < 4093 && testBit(a + 8, rt, true, branch14(a + 8)) &&
                    ldrByte(a + 12, int(rt), int(rn), off + 1) && testBit(a + 16, rt, true, branch14(a + 8)) &&
                    ldrByte(a + 20, int(rt), int(rn), off + 2) && testBit(a + 24, rt, true, branch14(a + 8))) {
                    hidden = off; hiddenTriple = true;
                }
            }
            if ((w & 0xffc00000u) == 0x79400000u) { alpha = true; alphaOffset = ((w >> 10) & 4095) * 2; }
            blur |= w == 0x1e202008u || w == 0x1e202000u; // FCMP S0,#0.0
        }
        bool reasonHidden[3]{};
        for (uint64_t a = reason.value; a + 8 < reason.value + reason.size; a += 4) {
            uint32_t w = instruction(a);
            for (unsigned n = 0; n < 3; ++n) if ((w & 0xffc003e0u) == 0x39400000u &&
                ((w >> 10) & 4095) == hidden + n) reasonHidden[n] = true;
        }
        if (!reasonReach || !hiddenTriple || !reasonHidden[0] || !reasonHidden[1] || !reasonHidden[2] ||
            !alpha || !blur || !drawCall) throw Error("signature_not_found", "Exported visibility body/diagnostic linkage is incomplete: reach=" +
                std::to_string(reasonReach) + " hidden=" + std::to_string(hiddenTriple) + "/" +
                std::to_string(reasonHidden[0]) + std::to_string(reasonHidden[1]) + std::to_string(reasonHidden[2]) +
                " alpha=" + std::to_string(alpha) + " blur=" + std::to_string(blur) + " draw=" + std::to_string(drawCall));
        // Audit all direct/indexed/affine uses using actual CFG predecessors,
        // independently cross-checking every direct skip conjunction. Physical
        // instruction order is not a valid reaching-definition relation.
        std::map<uint64_t, AddressFlow> flows;
        auto origins = [&](uint64_t a, unsigned base, int64_t displacement, bool& complete) {
            auto b = bounds(a);
            if (!b.first) { complete = false; return std::set<AddressOrigin>{}; }
            auto [flow, inserted] = flows.try_emplace(b.first, *this, b.first, b.second); (void)inserted;
            return flow->second.origins(a, base, displacement, complete);
        };
        std::vector<Site> result{{lo + 16, offset(lo + 16), lo, hi, load, 0x2a1f03e0u | reg}};
        std::set<uint64_t> classified{lo + 16, diagnostic};
        unsigned indexedUses = 0, diagnosticUses = 0;
        for (uint64_t a = text.sh_addr; a + 4 < text.sh_addr + text.sh_size; a += 4) {
            uint32_t w = instruction(a);
            if ((w & 0x7fe00000u) == 0x52800000u && ((w >> 5) & 65535) == handle) {
                if (a != lo + 12) throw Error("signature_not_found", "Unclassified exported indexed handle use at " + hex(a));
                ++indexedUses;
            }
            bool unsignedByte = (w & 0xffc00000u) == 0x39400000u;
            bool signedByte = (w & 0xffe00000u) == 0x38400000u; // LDURB / immediate pre/post-index LDRB
            if (!unsignedByte && !signedByte) continue;
            // Post-index loads read the old base, then apply their writeback.
            // LDURB and pre-index loads use the signed displacement for the read.
            int64_t off = unsignedByte ? int64_t((w >> 10) & 4095) :
                ((w >> 10) & 3) == 1 ? 0 : signExtend((w >> 12) & 511, 9);
            unsigned rn = (w >> 5) & 31;
            auto b = bounds(a);
            if (!b.first) continue;
            auto [flow, inserted] = flows.try_emplace(b.first, *this, b.first, b.second); (void)inserted;
            if (!flow->second.mayReadField(rn, off, handle)) continue;
            bool complete = false;
            auto handleOrigins = origins(a, rn, off, complete);
            bool field = std::any_of(handleOrigins.begin(), handleOrigins.end(), [&](const auto& v) { return v.offset == int64_t(handle); });
            if (!field && complete) continue;
            if (!complete || handleOrigins.size() != 1)
                throw Error("signature_not_found", "Ambiguous CFG handle provenance at " + hex(a) + " complete=" +
                    std::to_string(complete) + " roots=" + std::to_string(handleOrigins.size()));
            if (a == diagnostic) { ++diagnosticUses; continue; }
            auto [begin, end] = bounds(a); unsigned value = w & 31;
            // Parent -> child handle propagation is a read of the same field,
            // not a visibility rejection. Prove the boolean OR with the two
            // mirror-ID sentinels and the destination's same derived field.
            if (begin && a + 56 < end) {
                unsigned computed = instruction(a + 4) & 31;
                uint32_t mirrorA = instruction(a + 12), mirrorB = instruction(a + 24), store = instruction(a + 36);
                unsigned requested = (mirrorA >> 5) & 31, mirrorReg = mirrorA & 31;
                bool inheritance = instruction(a + 4) == (0x52800020u | computed) &&
                    testBit(a + 8, value, true, a + 36) &&
                    (mirrorA & 0xffc00000u) == 0xb9400000u &&
                    instruction(a + 16) == (0x3100041fu | (mirrorReg << 5)) && conditional(a + 20, 1, a + 36) &&
                    (mirrorB & 0xffc003ffu) == (0xb9400000u | (requested << 5) | computed) &&
                    ((mirrorA >> 10) & 4095) != ((mirrorB >> 10) & 4095) &&
                    instruction(a + 28) == (0x3100041fu | (computed << 5)) &&
                    instruction(a + 32) == (0x1a9f07e0u | computed) &&
                    (store & 0xffc0001fu) == (0x39000000u | computed);
                if (inheritance) {
                    unsigned destination = (store >> 5) & 31;
                    auto destinationOrigins = origins(a + 36, destination, (store >> 10) & 4095, complete);
                    // updateSnapshot's first explicit argument is LayerSnapshot&
                    // child (X1). Require the store to reach that exact object and
                    // the same derived handle field, through every CFG predecessor.
                    bool destinationValid = complete && destinationOrigins.size() == 1 &&
                        *destinationOrigins.begin() == AddressOrigin{begin, 1, int64_t(handle)};
                    uint32_t parentAlpha = instruction(a + 52); unsigned parent = (parentAlpha >> 5) & 31;
                    auto parentOrigins = origins(a + 52, parent, 0, complete);
                    auto parentExpected = *handleOrigins.begin(); parentExpected.offset = 0;
                    bool parentValid = complete && parentOrigins.size() == 1 && *parentOrigins.begin() == parentExpected &&
                        (parentAlpha & 0xffc00000u) == 0x79400000u && ((parentAlpha >> 10) & 4095) * 2 == alphaOffset;
                    bool namedUpdate = false;
                    for (const auto& symbol : symbols)
                        if (symbol.value == begin && symbol.size == end - begin &&
                            symbol.name == "_ZN7android14surfaceflinger8frontend20LayerSnapshotBuilder14updateSnapshotERNS1_13LayerSnapshotERKNS2_4ArgsERKNS1_19RequestedLayerStateERKS3_RKNS1_14LayerHierarchy13TraversalPathEb") namedUpdate = true;
                    if (destinationValid && parentValid && namedUpdate) { classified.insert(a); continue; }
                    throw Error("signature_not_found", "Incomplete CFG parent-handle inheritance at " + hex(a) + " destination=" +
                        std::to_string(destinationValid) + " parent=" + std::to_string(parentValid) + " named=" + std::to_string(namedUpdate));
                }
            }
            if (!begin || a + 20 >= end || value == 31 ||
                instruction(a + 4) != (0x7100041fu | (value << 5)))
                throw Error("signature_not_found", "Unclassified CFG handle read at " + hex(a));
            uint64_t continuation = branch19(a + 8);
            uint32_t skipLoad = instruction(a + 12); unsigned object = (skipLoad >> 5) & 31;
            if (!conditional(a + 8, 1, continuation) || !ldrByte(a + 12, int(value), int(object), skip) ||
                !testBit(a + 16, value, false, continuation) || continuation < begin || continuation + 40 >= end ||
                instruction(continuation) != (0xaa0003e0u | (object << 16)) || !callTo(continuation + 4, drawTarget))
                throw Error("signature_not_found", "Unclassified CFG handle conjunction at " + hex(a));
            auto objectOrigins = origins(a + 12, object, 0, complete);
            auto expected = *handleOrigins.begin(); expected.offset = 0;
            if (!complete || objectOrigins.size() != 1 || *objectOrigins.begin() != expected)
                throw Error("signature_not_found", "Inline handle/skip do not share one CFG object at " + hex(a));
            // Link the inline continuation to the canonical drawing/hidden/alpha
            // implementation. All vendor fallback/cache/state branches are retained.
            uint64_t hiddenReject = branch14(continuation + 16);
            unsigned temp = instruction(continuation + 12) & 31;
            if (!testBit(continuation + 8, 0, false, branch14(continuation + 8)) ||
                !ldrByte(continuation + 12, int(temp), int(object), hidden) ||
                !testBit(continuation + 16, temp, true, hiddenReject) ||
                !ldrByte(continuation + 20, int(temp), int(object), hidden + 1) ||
                !testBit(continuation + 24, temp, true, hiddenReject) ||
                !ldrByte(continuation + 28, int(temp), int(object), hidden + 2) ||
                !testBit(continuation + 32, temp, true, hiddenReject) ||
                (instruction(continuation + 36) & 0xffc003e0u) != (0x79400000u | (object << 5)) ||
                ((instruction(continuation + 36) >> 10) & 4095) * 2 != alphaOffset)
                throw Error("signature_not_found", "Inline drawing/hidden/alpha linkage is incomplete at " + hex(a));
            bool reachGuard = false;
            for (uint64_t p = begin; p + 12 <= a; p += 4) {
                uint32_t reachWord = instruction(p); unsigned reachReg = reachWord & 31;
                if (!ldrWord(p, int(reachReg), int(object), reach)) continue;
                if (!compareBranch(p + 4, reachReg, false, a) && !compareBranch(p + 8, reachReg, false, a)) continue;
                auto reachOrigins = origins(p, object, 0, complete);
                if (complete && reachOrigins.size() == 1 && *reachOrigins.begin() == expected) reachGuard = true;
            }
            if (!reachGuard) throw Error("signature_not_found", "Inline reachability is not linked to the CFG object at " + hex(a));
            classified.insert(a);
            result.push_back({a, offset(a), begin, end, w, 0x2a1f03e0u | value});
        }
        // Audit the independently recognizable skip conjunction even if a future
        // compiler form defeats address provenance. It must never be ignored.
        for (uint64_t a = text.sh_addr + 12; a + 8 < text.sh_addr + text.sh_size; a += 4) {
            uint32_t w = instruction(a); unsigned value = w & 31;
            if ((w & 0xffc00000u) == 0x39400000u && ((w >> 10) & 4095) == skip &&
                instruction(a - 8) == (0x7100041fu | (value << 5)) &&
                conditional(a - 4, 1, branch19(a - 4)) && testBit(a + 4, value, false, branch19(a - 4)) &&
                !classified.count(a - 12)) throw Error("signature_not_found", "Unclassified independent skip conjunction at " + hex(a));
        }
        // Independently audit the parent -> child boolean propagation shape too.
        // Moving either its source or destination away from the derived field
        // must not silently make that known propagation disappear from the audit.
        for (const auto& symbol : symbols) {
            if (symbol.name != "_ZN7android14surfaceflinger8frontend20LayerSnapshotBuilder14updateSnapshotERNS1_13LayerSnapshotERKNS2_4ArgsERKNS1_19RequestedLayerStateERKS3_RKNS1_14LayerHierarchy13TraversalPathEb") continue;
            auto b = bounds(symbol.value);
            if (b.first != symbol.value || b.second - b.first != symbol.size)
                throw Error("signature_not_found", "Parent propagation has conflicting function bounds");
            for (uint64_t a = b.first; a + 40 < b.second; a += 4) {
                uint32_t source = instruction(a), first = instruction(a + 12), second = instruction(a + 24), store = instruction(a + 36);
                unsigned loaded = source & 31, computed = instruction(a + 4) & 31, temp = first & 31, requested = (first >> 5) & 31;
                if ((source & 0xffc00000u) == 0x39400000u && instruction(a + 4) == (0x52800020u | computed) &&
                    testBit(a + 8, loaded, true, a + 36) && (first & 0xffc00000u) == 0xb9400000u &&
                    instruction(a + 16) == (0x3100041fu | (temp << 5)) && conditional(a + 20, 1, a + 36) &&
                    (second & 0xffc003ffu) == (0xb9400000u | (requested << 5) | computed) &&
                    instruction(a + 28) == (0x3100041fu | (computed << 5)) &&
                    instruction(a + 32) == (0x1a9f07e0u | computed) &&
                    (store & 0xffc0001fu) == (0x39000000u | computed) && !classified.count(a))
                    throw Error("signature_not_found", "Unclassified independent parent propagation at " + hex(a));
            }
        }
        if (indexedUses != 1 || diagnosticUses != 1 || result.size() > kMaxRecordingSites)
            throw Error("signature_ambiguous", "Exported CFG visibility audit has incomplete or excessive coverage");
        std::sort(result.begin(), result.end(), [](const Site& a, const Site& b) { return a.vaddr < b.vaddr; });
        return result;
    }
    std::vector<Site> matchRecordingPlainDirect() const {
        struct Layout { unsigned handle=0,skip=0,reach=0,hidden=0,alpha=0; uint64_t reason=0,reasonEnd=0,reasonLoad=0,draw=0,alphaCall=0; } l;
        unsigned reasons=0;
        for(uint64_t x=text.sh_addr+24;x+8<text.sh_addr+text.sh_size;x+=4) {
            if(!stringReference(x,"eLayerSkipScreenshot"))continue;
            auto [lo,hi]=bounds(x);
            if(!lo||hi-lo<512||hi-lo>8192||!containsString(lo,hi,"layer not reachable from root")||
               !containsString(lo,hi,"layer only reachable via relative parent")||!containsString(lo,hi,"nothing to draw"))continue;
            uint64_t s=x-24;uint32_t sw=instruction(s);
            if((sw&0xffc003ffu)!=0x39400009u)continue;
            for(uint64_t a=lo+24;a+24<s;a+=4) {
                uint32_t w=instruction(a);unsigned hidden=(instruction(a-24)>>10)&4095;
                if((w&0xffc003ffu)!=0x39400009u||instruction(a+4)!=0x7100053fu||!conditional(a+8,0,s)||
                   !testBit(s+4,9,false,a+12)||!hidden||!ldrByte(a-24,9,0,hidden)||
                   instruction(a-20)!=0x7100053fu||!ldrByte(a-12,9,0,hidden+1)||instruction(a-8)!=0x7100053fu||
                   !ldrByte(a+12,9,0,hidden-1)||instruction(a+16)!=0x7100053fu||
                   (instruction(a+20)&0xff00001fu)!=0x54000001u)continue;
                unsigned reach=0,count=0;
                for(uint64_t p=lo;p+20<=a-24;p+=4) {
                    uint32_t r=instruction(p);
                    if((r&0xffc003ffu)==0xb9400009u&&instruction(p+4)==0x7100093fu&&
                       (instruction(p+8)&0xff00001fu)==0x54000000u&&instruction(p+12)==0x7100053fu&&
                       (instruction(p+16)&0xff00001fu)==0x54000000u) {reach=((r>>10)&4095)*4;++count;}
                }
                if(count!=1||!reach)continue;
                l={unsigned((w>>10)&4095),unsigned((sw>>10)&4095),reach,hidden-1,0,lo,hi,a,0,0};++reasons;
            }
        }
        if(!reasons)throw Error("signature_not_found","No plain direct visibility diagnostic");
        if(reasons!=1)throw Error("signature_ambiguous","Multiple plain direct visibility diagnostics");
        std::vector<Site> result;
        for(uint64_t a=text.sh_addr+16;a+172<=text.sh_addr+text.sh_size;a+=4) {
            uint32_t w=instruction(a);unsigned v=w&31;
            if(v==0||v==31||!ldrByte(a,int(v),0,l.handle))continue;
            auto [lo,hi]=bounds(a);if(a!=lo+16||hi!=a+172)continue;
            unsigned holder=instruction(a+32)&31,temp=instruction(a+44)&31,alias=instruction(a+60)&31;
            unsigned alpha=(instruction(a+76)>>10)&4095;
            if(holder<19||holder>28||temp==0||temp==31||alias==0||alias==31||
               !ldrWord(lo,int(v),0,l.reach)||!compareBranch(lo+4,v,false,a)||instruction(lo+8)!=0x2a1f03e0u||instruction(lo+12)!=0xd65f03c0u||
               instruction(a+4)!=(0x7100041fu|(v<<5))||!conditional(a+8,1,a+20)||!ldrByte(a+12,int(v),0,l.skip)||!testBit(a+16,v,true,lo+8)||
               instruction(a+20)!=0xa9be7bfdu||instruction(a+24)!=(0xf9000be0u|holder)||instruction(a+28)!=0x910003fdu||
               instruction(a+32)!=(0xaa0003e0u|holder)||(instruction(a+36)&0xfc000000u)!=0x94000000u||!testBit(a+40,0,false,a+156)||
               !ldrByte(a+44,int(temp),int(holder),l.hidden)||instruction(a+48)!=0x2a1f03e0u||!testBit(a+52,temp,true,a+160)||
               !ldrByte(a+56,int(temp),int(holder),l.hidden+1)||instruction(a+60)!=(0xaa0003e0u|(holder<<16)|alias)||
               !testBit(a+64,temp,true,a+160)||!ldrByte(a+68,int(temp),int(alias),l.hidden+2)||!testBit(a+72,temp,true,a+160)||
               !alpha||!addImmediate(a+76,0,alias,alpha)||(instruction(a+80)&0xfc000000u)!=0x94000000u||
               instruction(a+84)!=0x1e202008u||instruction(a+88)!=0x52800020u||!conditional(a+92,12,a+160)||
               (instruction(a+96)&0xffc003ffu)!=(0xb9400000u|(holder<<5)|v)||instruction(a+100)!=(0x7100001fu|(v<<5))||!conditional(a+104,12,a+160)||
               (instruction(a+108)&0xffc00000u)!=0xa9400000u||((instruction(a+108)>>5)&31)!=holder||
               (instruction(a+108)&31)!=temp||((instruction(a+108)>>10)&31)!=v||instruction(a+112)!=(0xeb00001fu|(temp<<16)|(v<<5))||!conditional(a+116,1,a+160)||
               (instruction(a+120)&0xffc003ffu)!=(0xf9400000u|(holder<<5)|v)||!compareBranch(a+124,v,false,a+140,true)||
               (instruction(a+128)&0xffc003ffu)!=(0xb9400000u|(holder<<5)|v)||instruction(a+132)!=(0x7100041fu|(v<<5))||!conditional(a+136,0,a+160)||
               (instruction(a+140)&0xffc00000u)!=0xa9400000u||((instruction(a+140)>>5)&31)!=holder||
               (instruction(a+140)&31)!=temp||((instruction(a+140)>>10)&31)!=v||instruction(a+144)!=(0xeb00001fu|(temp<<16)|(v<<5))||
               instruction(a+148)!=0x1a9f07e0u||!directBranch(a+152,a+160)||instruction(a+156)!=0x2a1f03e0u||
               instruction(a+160)!=(0xf9400be0u|holder)||instruction(a+164)!=0xa8c27bfdu||instruction(a+168)!=0xd65f03c0u)continue;
            uint64_t draw=branch26(a+36),alphaCall=branch26(a+80);
            if(!containsCall(l.reason,l.reasonEnd,draw)||!containsCall(l.reason,l.reasonEnd,alphaCall))continue;
            if(!result.empty())throw Error("signature_ambiguous","Multiple complete plain direct getters");
            l.draw=draw;l.alphaCall=alphaCall;l.alpha=alpha;
            result.push_back({a,offset(a),lo,hi,w,0x2a1f03e0u|v});
        }
        if(result.empty())throw Error("signature_not_found","Missing complete plain direct getter");
        std::map<uint64_t,AddressFlow> flows;
        auto flow=[&](uint64_t a)->AddressFlow& {auto b=bounds(a);if(!b.first)throw Error("signature_not_found","Missing audited function bounds");return flows.try_emplace(b.first,*this,b.first,b.second).first->second;};
        auto roots=[&](uint64_t a,unsigned reg,int64_t off) {bool c=false;auto r=flow(a).origins(a,reg,off,c);if(!c||r.empty())throw Error("signature_not_found","Incomplete plain CFG provenance at "+hex(a));return r;};
        std::set<uint64_t> reads,classified{l.reasonLoad,result[0].vaddr};
        for(uint64_t a=text.sh_addr;a+4<text.sh_addr+text.sh_size;a+=4) {
            uint32_t w=instruction(a);
            if((w&0x7fe00000u)==0x52800000u&&((w>>5)&65535)==l.handle)
                throw Error("signature_not_found","Unclassified indexed plain handle use at "+hex(a));
            bool u=(w&0xffc00000u)==0x39400000u,s=(w&0xffe00000u)==0x38400000u;
            if(!u&&!s)continue;unsigned rn=(w>>5)&31;
            int64_t off=u?int64_t((w>>10)&4095):((w>>10)&3)==1?0:signExtend((w>>12)&511,9);
            if(!flow(a).mayReadField(rn,off,l.handle))continue;
            bool c=false;auto r=flow(a).origins(a,rn,off,c);
            if(!c)throw Error("signature_not_found","Incomplete plain field audit at "+hex(a));
            if(std::any_of(r.begin(),r.end(),[&](const auto& v){return v.offset==int64_t(l.handle);})) {
                if(r.empty()||!std::all_of(r.begin(),r.end(),[&](const auto& v){return v.offset==int64_t(l.handle);}))throw Error("signature_not_found","Ambiguous plain field audit");
                reads.insert(a);
            }
        }
        struct Typed {uint64_t lo;unsigned reg;std::set<AddressOrigin> origins;};std::vector<Typed> typed;
        std::vector<uint64_t> deferred;
        for(uint64_t a:reads) {
            if(classified.count(a))continue;
            auto [lo,hi]=bounds(a);uint32_t w=instruction(a);unsigned v=w&31,rn=(w>>5)&31;
            if((w&0xffc00000u)!=0x39400000u||((w>>10)&4095)!=l.handle||a+44>=hi)
                throw Error("signature_not_found","Unknown plain handle addressing at "+hex(a));
            uint32_t store=instruction(a+32);unsigned computed=store&31,request=(instruction(a+8)>>5)&31;
            bool inheritance=testBit(a+4,v,true,a+32)&&(instruction(a+8)&0xffc0001fu)==(0xb9400000u|v)&&
                instruction(a+12)==(0x3100041fu|(v<<5))&&conditional(a+16,1,a+32)&&
                (instruction(a+20)&0xffc003ffu)==(0xb9400000u|(request<<5)|computed)&&
                instruction(a+24)==(0x3100041fu|(computed<<5))&&instruction(a+28)==(0x1a9f07e0u|computed)&&
                (store&0xffc00000u)==0x39000000u;
            if(inheritance) {
                auto source=roots(a,rn,l.handle),destination=roots(a+32,(store>>5)&31,(store>>10)&4095);
                bool one=true;const auto& incoming=flow(a).incoming(a);if(incoming.empty())one=false;
                for(uint64_t p:incoming)one&=p>=lo+4&&conditional(p,0,a)&&instruction(p-4)==(0x7100041fu|(computed<<5));
                uint32_t alpha=instruction(a+40);
                if(!one||source!=std::set<AddressOrigin>{{lo,4,int64_t(l.handle)}}||destination!=std::set<AddressOrigin>{{lo,1,int64_t(l.handle)}}||
                   (alpha&0xffc00000u)!=0x79400000u||((alpha>>10)&4095)*2!=l.alpha||
                   roots(a+40,(alpha>>5)&31,0)!=std::set<AddressOrigin>{{lo,4,0}})
                    throw Error("signature_not_found","Incomplete guarded plain parent propagation at "+hex(a));
                classified.insert(a);continue;
            }
            if(v==31||v==rn||instruction(a+4)!=(0x7100041fu|(v<<5)))throw Error("signature_not_found","Unknown plain handle consumer at "+hex(a));
            if(conditional(a+8,0,branch19(a+8))) {deferred.push_back(a);continue;}
            uint64_t cont=a+20,reject=branch14(a+16);
            if(!conditional(a+8,1,cont)||!ldrByte(a+12,int(v),int(rn),l.skip)||!testBit(a+16,v,true,reject)||
               reject<lo||reject>=hi||instruction(cont)!=(0xaa0003e0u|(rn<<16)))throw Error("signature_not_found","Unknown plain inline conjunction at "+hex(a));
            uint64_t call=cont+4;
            if(!callTo(call,l.draw)) {
                uint32_t move=instruction(call);unsigned rd=move&31;
                if((move&0xffe0ffe0u)!=0xaa0003e0u||rd<19||rd==rn)throw Error("signature_not_found","Inline argument scheduling changed");
                call+=4;
            }
            if(!callTo(call,l.draw)||!testBit(call+4,0,false,branch14(call+4)))throw Error("signature_not_found","Inline draw linkage changed");
            bool reachable=false;
            for(uint64_t p=lo;p+8<=a;p+=4) {
                unsigned r=instruction(p)&31;
                if(!ldrWord(p,int(r),int(rn),l.reach)||r==rn)continue;
                uint32_t scheduled=instruction(p+4);unsigned scheduledReg=scheduled&31;
                bool harmlessLoad=(scheduled&0xffc003e0u)==(0xb9400000u|(rn<<5))&&scheduledReg!=r&&scheduledReg!=rn;
                reachable|=compareBranch(p+4,r,false,a)||(p+12<=a&&harmlessLoad&&compareBranch(p+8,r,false,a))||
                    (p+8==a&&(instruction(p+4)&0xff00001fu)==(0x35000000u|r));
            }
            if(!reachable)throw Error("signature_not_found","Missing plain inline reach gate at "+hex(a));
            auto object=roots(a,rn,0);
            typed.push_back({lo,rn,object});classified.insert(a);result.push_back({a,offset(a),lo,hi,w,0x2a1f03e0u|v});
        }
        for(uint64_t a:deferred) {
            auto [lo,hi]=bounds(a);unsigned v=instruction(a)&31,rn=(instruction(a)>>5)&31;uint64_t s=branch19(a+8);
            if(s<lo||s+12>hi||!ldrByte(s,int(v),int(rn),l.skip)||!testBit(s+4,v,false,a+12)||
               (instruction(s+8)&0xfc000000u)!=0x14000000u||branch26(s+8)!=branch19(a-4))throw Error("signature_not_found","Unknown split plain conjunction");
            auto object=roots(a,rn,0);bool known=false;
            for(const auto& t:typed)if(t.lo==lo&&t.reg==rn&&std::includes(t.origins.begin(),t.origins.end(),object.begin(),object.end()))known=true;
            if(!known||roots(s,rn,0)!=object||!ldrWord(a-8,int(v),int(rn),l.reach)||(instruction(a-4)&0xff00001fu)!=(0x35000000u|v))
                throw Error("signature_not_found","Split plain conjunction lacks typed CFG linkage");
            classified.insert(a);result.push_back({a,offset(a),lo,hi,instruction(a),0x2a1f03e0u|v});
        }
        if(classified!=reads)throw Error("signature_not_found","Plain field classification is incomplete");
        // Independent scans prevent removing a load or moving a known source
        // field from hiding a still-present rejection/propagation shape.
        for(uint64_t p=text.sh_addr+4;p+40<text.sh_addr+text.sh_size;p+=4) {
            uint32_t cmp=instruction(p);unsigned v=(cmp>>5)&31;
            unsigned loaded=cmp&31;uint32_t first=instruction(p+8),second=instruction(p+20),store=instruction(p+32);
            unsigned computed=store&31,requested=(first>>5)&31;
            if((cmp&0xffc00000u)==0x39400000u&&testBit(p+4,loaded,true,p+32)&&
               (first&0xffc0001fu)==(0xb9400000u|loaded)&&instruction(p+12)==(0x3100041fu|(loaded<<5))&&conditional(p+16,1,p+32)&&
               (second&0xffc003ffu)==(0xb9400000u|(requested<<5)|computed)&&instruction(p+24)==(0x3100041fu|(computed<<5))&&
               instruction(p+28)==(0x1a9f07e0u|computed)&&(store&0xffc00000u)==0x39000000u&&
               ((store>>10)&4095)==l.handle&&!classified.count(p))
                throw Error("signature_not_found","Unknown independent plain parent propagation at "+hex(p));
            if(cmp!=(0x7100041fu|(v<<5)))continue;
            uint64_t s=conditional(p+4,0,branch19(p+4))?branch19(p+4):p+8;
            if(s<text.sh_addr||s+8>=text.sh_addr+text.sh_size)continue;
            uint32_t load=instruction(s);
            if((load&0xffc0001fu)==(0x39400000u|v)&&((load>>10)&4095)==l.skip&&
               (instruction(s+4)&0xfef8001fu)==(0x36000000u|v)&&!classified.count(p-4))
                throw Error("signature_not_found","Unknown independent plain skip conjunction at "+hex(s));
        }
        if(result.size()>kMaxRecordingSites)throw Error("signature_ambiguous","Plain site count exceeds bound");
        std::sort(result.begin(),result.end(),[](const Site&a,const Site&b){return a.vaddr<b.vaddr;});return result;
    }

    std::vector<Site> matchRecordingIndexed() const {
        // Derive all object-field offsets from the uniquely linked visibility-reason
        // function. No model, version, build ID or object-layout offset is a selector.
        struct Layout { unsigned handle, skip, reach, hidden; uint64_t reasonLo, reasonHi, reasonLoad; } layout{};
        unsigned diagnostics = 0;
        for (uint64_t xref = text.sh_addr + 8; xref + 8 < text.sh_addr + text.sh_size; xref += 4) {
            if (!stringReference(xref, "eLayerSkipScreenshot")) continue;
            auto [lo, hi] = bounds(xref);
            if (!lo || hi - lo < 512 || hi - lo > 4096 || instruction(lo) != 0xd503233fu ||
                !containsString(lo, hi, "layer not reachable from root") ||
                !containsString(lo, hi, "layer only reachable via relative parent") ||
                !containsString(lo, hi, "nothing to draw")) continue;
            if (xref < lo + 64 || (instruction(xref - 8) & 0xffc003ffu) != 0x39400128u ||
                (instruction(xref - 4) & 0xfff8001fu) != 0x36000008u) continue;
            unsigned skip = (instruction(xref - 8) >> 10) & 4095;
            for (uint64_t a = lo + 48; a + 20 < xref; a += 4) {
                uint32_t mov = instruction(a);
                if ((mov & 0xffe0001fu) != 0x52800008u || instruction(a + 4) != 0x38686928u ||
                    instruction(a + 8) != 0x7100051fu || (instruction(a + 12) & 0xff00001fu) != 0x54000000u ||
                    branch19(a + 12) != xref - 8 || branch14(xref - 4) != a + 16) continue;
                unsigned hidden = (instruction(a - 24) >> 10) & 4095;
                if (!hidden || !ldrByte(a - 24, 8, 9, hidden) || !ldrByte(a - 12, 8, 9, hidden + 1) ||
                    !ldrByte(a + 16, 8, 9, hidden - 1) ||
                    (instruction(lo + 24) & 0xffc003ffu) != 0xb940000au ||
                    instruction(lo + 28) != 0xaa0003e9u || instruction(lo + 36) != 0x7100095fu ||
                    instruction(lo + 44) != 0x7100055fu) continue;
                layout = {(mov >> 5) & 65535, skip, ((instruction(lo + 24) >> 10) & 4095) * 4,
                          hidden, lo, hi, a + 4};
                ++diagnostics;
            }
        }
        if (!diagnostics) throw Error("signature_not_found", "No validated mirrored-layer visibility diagnostic");
        if (diagnostics != 1) throw Error("signature_ambiguous", "Multiple mirrored-layer visibility diagnostics");
        std::vector<Site> standalone, inlined;
        unsigned handleLoads = 0;
        uint64_t drawCall = 0, alphaCall = 0;
        const uint32_t handleMov = 0x52800008u | (layout.handle << 5);
        for (uint64_t a = text.sh_addr; a + 16 < text.sh_addr + text.sh_size; a += 4) {
            uint32_t possibleDirect = instruction(a);
            if (layout.handle <= 4095 && (possibleDirect & 0xffc00000u) == 0x39400000u &&
                ((possibleDirect >> 10) & 4095) == layout.handle)
                throw Error("signature_not_found", "Unclassified direct handle-field occurrence in indexed profile");
            // Audit every immediate occurrence for this field, including the one
            // diagnostic use. An unclassified use rejects this compiler profile.
            if ((instruction(a) & 0xffe00000u) == 0x52800000u &&
                ((instruction(a) >> 5) & 65535) == layout.handle) ++handleLoads;
            if (instruction(a) != handleMov || instruction(a + 8) != 0x7100051fu) continue;
            auto [lo, hi] = bounds(a);
            if (!lo || hi - a < 16) continue;
            if (instruction(a + 4) == 0x38686808u && a == lo + 24 && hi - lo == 212 &&
                instruction(lo) == 0xd503245fu && ldrWord(lo + 4, 8, 0, layout.reach) &&
                instruction(lo + 8) == 0x34000088u && instruction(lo + 12) == 0x2a1f03e8u &&
                instruction(lo + 16) == 0x12000100u && instruction(lo + 20) == 0xd65f03c0u &&
                instruction(a + 12) == 0x54000061u && ldrByte(a + 16, 8, 0, layout.skip) &&
                (instruction(a + 20) & 0xfff8001fu) == 0x37000008u && branch14(a + 20) == lo + 12 &&
                instruction(lo + 48) == 0xd503233fu && instruction(lo + 64) == 0xaa0003f3u &&
                (instruction(lo + 68) & 0xfc000000u) == 0x94000000u && instruction(lo + 72) == 0x360002e0u &&
                ldrByte(lo + 76, 10, 19, layout.hidden - 1) && instruction(lo + 84) == 0x370002aau &&
                ldrByte(lo + 88, 10, 19, layout.hidden) && instruction(lo + 96) == 0x3700024au &&
                ldrByte(lo + 100, 10, 9, layout.hidden + 1) && instruction(lo + 104) == 0x3700020au &&
                (instruction(lo + 112) & 0xfc000000u) == 0x94000000u && instruction(lo + 116) == 0x1e202008u &&
                instruction(lo + 164) == 0x2a1f03e8u && instruction(lo + 180) == 0x17ffffd7u) {
                uint64_t draw = branch26(lo + 68), alpha = branch26(lo + 112);
                if (!containsCall(layout.reasonLo, layout.reasonHi, draw) ||
                    !containsCall(layout.reasonLo, layout.reasonHi, alpha)) continue;
                standalone.push_back({a + 4, offset(a + 4), lo, hi, instruction(a + 4), 0x2a1f03e8u});
                drawCall = draw; alphaCall = alpha;
            } else if (instruction(a + 4) == 0x38686a88u && hi - lo >= 2048 && hi - lo <= 8192 &&
                       instruction(lo) == 0xd503233fu && ldrWord(a - 8, 8, 20, layout.reach) &&
                       (instruction(a - 4) & 0xff00001fu) == 0x35000008u &&
                       (instruction(a + 12) & 0xff00001fu) == 0x54000000u) {
                uint64_t cold = branch19(a + 12), reject = branch19(a - 4);
                if (cold < lo || cold + 12 > hi || reject < lo || reject >= hi ||
                    !ldrByte(cold, 8, 20, layout.skip) ||
                    (instruction(cold + 4) & 0xfff8001fu) != 0x36000008u || branch14(cold + 4) != a + 16) continue;
                // One compiler clone has an explicit rejection jump. The other
                // falls directly into the exact same zero-result block as reachability.
                bool rejectJump = (instruction(cold + 8) & 0xfc000000u) == 0x14000000u && branch26(cold + 8) == reject;
                bool rejectFallthrough = cold + 8 == reject && instruction(reject) == 0x2a1f03e8u &&
                    (instruction(reject + 4) & 0xfc000000u) == 0x14000000u;
                if (!rejectJump && !rejectFallthrough) continue;
                inlined.push_back({a + 4, offset(a + 4), lo, hi, instruction(a + 4), 0x2a1f03e8u});
            }
        }
        if (standalone.size() > 1 || standalone.size() + inlined.size() > kMaxRecordingSites)
            throw Error("signature_ambiguous", "Multiple visibility functions or too many audited sites");
        if (standalone.size() != 1 || handleLoads != 1 + standalone.size() + inlined.size())
            throw Error("signature_not_found", "Requires a complete audit of every handle-field occurrence");
        std::map<std::pair<uint64_t, uint64_t>, size_t> groups;
        for (const auto& clone : inlined) ++groups[{clone.functionStart, clone.functionEnd}];
        for (const auto& [range, expected] : groups) {
          auto [lo, hi] = range;
          if (!containsCall(lo, hi, alphaCall) || !drawCall)
              throw Error("signature_not_found", "Missing matching alpha/draw semantics in visibility clones");
          // Every audited clone needs its own complete hidden-policy/alpha chain.
          size_t policyChains = 0;
          for (uint64_t a = lo; a + 28 < hi; a += 4) {
            unsigned rt = instruction(a) & 31;
            if ((rt != 8 && rt != 9) || !ldrByte(a, int(rt), 20, layout.hidden) || !ldrByte(a + 8, int(rt), 20, layout.hidden + 1) ||
                (instruction(a + 4) & 0xfff8001fu) != (0x37000000u | rt) ||
                (instruction(a + 12) & 0xfff8001fu) != (0x37000000u | rt) ||
                branch14(a + 4) != branch14(a + 12) ||
                instruction(a + 16) != instruction(standalone[0].functionStart + 108) + (20u - 9u) * 32u ||
                (instruction(a + 20) & 0xfc000000u) != 0x94000000u || branch26(a + 20) != alphaCall ||
                instruction(a + 24) != 0x1e202008u) continue;
            bool invalidGuard = a >= lo + 12 && ldrByte(a - 12, int(rt), 20, layout.hidden - 1) &&
                instruction(a - 8) == 0x2a1f03e8u &&
                (instruction(a - 4) & 0xfff8001fu) == (0x37000000u | rt) && branch14(a - 4) == branch14(a + 4);
            // The first clone's invalid-transform test is split from the two
            // parent-policy tests by a cold rejection/cleanup block.
            for (uint64_t p = lo; !invalidGuard && p + 8 < a; p += 4) {
                if (ldrByte(p, int(rt), 20, layout.hidden - 1) &&
                    (instruction(p + 4) & 0xfff8001fu) == (0x36000000u | rt) &&
                    branch14(p + 4) == a && p + 8 == branch14(a + 4)) invalidGuard = true;
            }
              if (invalidGuard) ++policyChains;
          }
          if (policyChains != expected) throw Error("signature_not_found", "Missing complete hidden-policy and alpha guards");
        }
        std::vector<Site> out = inlined; out.push_back(standalone.front());
        std::sort(out.begin(), out.end(), [](const Site& a, const Site& b) { return a.vaddr < b.vaddr; });
        return out;
    }
};

struct CompatibilityFile {
    std::string path, stamp;
    Digest digest{};
};
struct CompatibilityRecord {
    Pipeline pipeline = Pipeline::Screenshot;
    Digest program{};
    std::vector<CompatibilityFile> files;
    uint32_t selected = 0;
    std::vector<Site> sites;
};
constexpr size_t kMaxCompatibilityRecord = 8 * 1024 * 1024;
std::vector<uint8_t> encodeCompatibility(const CompatibilityRecord& record) {
    std::vector<uint8_t> out;
    auto number = [&](uint64_t value, unsigned width) { for(unsigned i=0;i<width;++i)out.push_back(uint8_t(value>>(i*8))); };
    auto string = [&](const std::string& value, size_t limit) {
        if(value.empty() || value.size()>limit || value.find('\0')!=std::string::npos)throw Error("invalid_compat_cache","Invalid descriptor string");
        number(value.size(),4);out.insert(out.end(),value.begin(),value.end());
    };
    if(record.files.empty() || record.files.size()>1024 || record.selected>=record.files.size() ||
       record.sites.empty() || record.sites.size()>kMaxRecordingSites)throw Error("invalid_compat_cache","Invalid descriptor counts");
    number(0x3143504d43584653ULL,8);number(1,4);number(record.pipeline==Pipeline::Recording?1:0,4);
    out.insert(out.end(),record.program.begin(),record.program.end());number(record.files.size(),4);number(record.selected,4);
    for(const auto& file:record.files) {
        string(file.path,4096);string(file.stamp,512);out.insert(out.end(),file.digest.begin(),file.digest.end());
    }
    number(record.sites.size(),4);
    for(const auto& s:record.sites) {
        number(s.vaddr,8);number(s.fileOffset,8);number(s.functionStart,8);number(s.functionEnd,8);
        number(s.original,4);number(s.replacement,4);
    }
    if(out.size()>kMaxCompatibilityRecord-32)throw Error("invalid_compat_cache","Descriptor exceeds bound");
    auto hash=strongDigest(out.data(),out.size());out.insert(out.end(),hash.begin(),hash.end());return out;
}
CompatibilityRecord decodeCompatibility(const std::vector<uint8_t>& bytes, Pipeline pipe, const Digest& program) {
    if(bytes.size()<92 || bytes.size()>kMaxCompatibilityRecord)throw Error("invalid_compat_cache","Descriptor size invalid");
    size_t limit=bytes.size()-32,pos=0;auto hash=strongDigest(bytes.data(),limit);
    if(memcmp(hash.data(),bytes.data()+limit,32))throw Error("invalid_compat_cache","Descriptor checksum invalid");
    auto number = [&](unsigned width) {
        if(pos>limit || width>limit-pos)throw Error("invalid_compat_cache","Truncated descriptor");
        uint64_t value=0;for(unsigned i=0;i<width;++i)value|=uint64_t(bytes[pos++])<<(i*8);return value;
    };
    auto digest = [&]() {
        if(pos>limit || 32>limit-pos)throw Error("invalid_compat_cache","Truncated descriptor digest");
        Digest out{};memcpy(out.data(),bytes.data()+pos,32);pos+=32;return out;
    };
    auto string = [&](size_t bound) {
        uint64_t size=number(4);if(!size || size>bound || pos>limit || size>limit-pos)throw Error("invalid_compat_cache","Descriptor string bound invalid");
        std::string out(reinterpret_cast<const char*>(bytes.data()+pos),size_t(size));pos+=size_t(size);
        if(out.find('\0')!=std::string::npos)throw Error("invalid_compat_cache","Descriptor string contains NUL");return out;
    };
    if(number(8)!=0x3143504d43584653ULL || number(4)!=1 || number(4)!=(pipe==Pipeline::Recording?1u:0u))
        throw Error("invalid_compat_cache","Descriptor format/pipeline mismatch");
    CompatibilityRecord out;out.pipeline=pipe;out.program=digest();
    if(out.program!=program)throw Error("invalid_compat_cache","Decoder executable changed");
    uint64_t count=number(4);out.selected=uint32_t(number(4));
    if(!count || count>1024 || out.selected>=count)throw Error("invalid_compat_cache","Descriptor inventory count invalid");
    for(uint64_t i=0;i<count;++i) {
        CompatibilityFile file;file.path=string(4096);file.stamp=string(512);file.digest=digest();
        if(file.path.front()!='/' || file.path.ends_with(" (deleted)") || (!out.files.empty()&&file.path<=out.files.back().path))
            throw Error("invalid_compat_cache","Descriptor inventory is not canonical");
        out.files.push_back(std::move(file));
    }
    count=number(4);
    if(!count || count>kMaxRecordingSites || (pipe==Pipeline::Screenshot&&count!=1))throw Error("invalid_compat_cache","Descriptor site count invalid");
    for(uint64_t i=0;i<count;++i) {
        Site s;s.vaddr=number(8);s.fileOffset=number(8);s.functionStart=number(8);s.functionEnd=number(8);
        s.original=uint32_t(number(4));s.replacement=uint32_t(number(4));out.sites.push_back(s);
    }
    if(pos!=limit)throw Error("invalid_compat_cache","Descriptor contains trailing data");return out;
}

template<class Reader> bool validateFunctionBytes(const Image& image, Reader read) {
    std::map<uint64_t, std::vector<uint8_t>> functions;
    size_t active = 0;
    for (const auto& s : image.sites) {
        auto [it, inserted] = functions.try_emplace(s.functionStart);
        if (inserted) it->second = read(s.functionStart, size_t(s.functionEnd - s.functionStart));
        auto& live = it->second;
        if (live.size() != size_t(s.functionEnd - s.functionStart)) throw Error("memory_read_failed", "Short enclosing-function read");
        size_t siteOffset = size_t(s.vaddr - s.functionStart);
        uint32_t word; memcpy(&word, live.data() + siteOffset, 4);
        if (word != s.original && word != s.replacement)
            throw Error("external_change", "Candidate instruction differs from original and owned patch");
        active += word == s.replacement;
        memcpy(live.data() + siteOffset, &s.original, 4);
    }
    for (const auto& [lo, live] : functions) {
        if (memcmp(live.data(), image.bytes.data() + image.offset(lo), live.size()))
            throw Error("external_change", "Enclosing function bytes differ from the original executable");
    }
    if (active && active != image.sites.size())
        throw Error("partial_patch", "Only " + std::to_string(active) + "/" + std::to_string(image.sites.size()) + " pipeline instructions are replaced");
    return active == image.sites.size();
}

struct Ownership {
    uint64_t magic = kStateMagic;
    uint64_t pid = 0, start = 0, site = 0;
    uint32_t original = 0, replacement = 0, fileChecksum = 0;
    char buildId[65]{};
    uint32_t hash = 0;
};
// Do not change Ownership: deployed 2.0 screenshot records are read byte-for-byte.
struct RecordingOwnership {
    uint64_t magic = kRecordStateMagic;
    uint64_t pid = 0, start = 0;
    uint32_t fileChecksum = 0, count = 0;
    struct Entry { uint64_t site = 0; uint32_t original = 0, replacement = 0; } sites[3]{};
    char buildId[65]{};
    uint32_t hash = 0;
};
// The deployed 152-byte three-site format remains readable and is still emitted
// for three sites. Other fully audited site counts use a separately tagged format.
struct RecordingOwnershipV4 {
    uint64_t magic = kRecordStateMagicV4;
    uint64_t pid = 0, start = 0;
    uint32_t fileChecksum = 0, count = 0;
    RecordingOwnership::Entry sites[kMaxRecordingSites]{};
    char buildId[65]{};
    uint32_t hash = 0;
};
struct MappedOwnership {
    uint64_t magic = kMappedStateMagic, pid = 0, start = 0, bias = 0, device = 0, inode = 0;
    uint32_t pipeline = 0, fileChecksum = 0, count = 0, reserved = 0;
    RecordingOwnership::Entry sites[kMaxRecordingSites]{};
    char buildId[65]{}, path[4096]{};
    uint32_t hash = 0;
};
static_assert(sizeof(Ownership) == 120 && offsetof(Ownership, hash) == 112,
              "Preserve the deployed 2.0 ownership format");
static_assert(sizeof(RecordingOwnership) == 152 && offsetof(RecordingOwnership, hash) == 148);
static_assert(sizeof(RecordingOwnershipV4) == 360 && offsetof(RecordingOwnershipV4, hash) == 356);
static_assert(sizeof(MappedOwnership) == 4488 && offsetof(MappedOwnership, hash) == 4484);
void validateOwnershipPayload(const MappedOwnership& s) {
    if (s.magic != kMappedStateMagic || s.hash != checksum(&s, offsetof(MappedOwnership, hash)) ||
        !s.pid || s.pid > uint64_t(INT32_MAX) || !s.start || !s.inode || s.pipeline > 1 || s.reserved ||
        !s.count || s.count > kMaxRecordingSites || (!s.pipeline && s.count != 1) ||
        !memchr(s.buildId, 0, sizeof(s.buildId)) || !s.buildId[0] ||
        !memchr(s.path, 0, sizeof(s.path)) || s.path[0] != '/' || std::string(s.path).ends_with(" (deleted)"))
        throw Error("invalid_state", "Mapped ELF ownership integrity or identity mismatch");
    for (size_t i = 0; i < kMaxRecordingSites; ++i) {
        const auto& e = s.sites[i];
        if (i < s.count) {
            if (!e.site || !e.original || !e.replacement || e.original == e.replacement ||
                (i && e.site <= s.sites[i - 1].site)) throw Error("invalid_state", "Mapped ownership site list mismatch");
        } else if (e.site || e.original || e.replacement) throw Error("invalid_state", "Nonempty unused mapped ownership entry");
    }
}
void validateOwnershipPayload(const Ownership& s) {
    if (s.magic != kStateMagic || s.hash != checksum(&s, offsetof(Ownership, hash)) ||
        !s.pid || s.pid > uint64_t(std::numeric_limits<int32_t>::max()) || !s.start || !s.site)
        throw Error("invalid_state", "Screenshot ownership integrity or format mismatch");
}
void validateOwnershipPayload(const RecordingOwnership& s) {
    if (s.magic != kRecordStateMagic || s.hash != checksum(&s, offsetof(RecordingOwnership, hash)) ||
        !s.pid || s.pid > uint64_t(std::numeric_limits<int32_t>::max()) || !s.start || s.count != 3)
        throw Error("invalid_state", "Recording ownership integrity or format mismatch");
    for (size_t i = 0; i < 3; ++i)
        if (!s.sites[i].site || (i && s.sites[i].site <= s.sites[i - 1].site))
            throw Error("invalid_state", "Recording ownership site list mismatch");
}
void validateOwnershipPayload(const RecordingOwnershipV4& s) {
    if (s.magic != kRecordStateMagicV4 || s.hash != checksum(&s, offsetof(RecordingOwnershipV4, hash)) ||
        !s.pid || s.pid > uint64_t(std::numeric_limits<int32_t>::max()) || !s.start ||
        !s.count || s.count > kMaxRecordingSites)
        throw Error("invalid_state", "Variable recording ownership integrity or format mismatch");
    for (size_t i = 0; i < kMaxRecordingSites; ++i) {
        const auto& e = s.sites[i];
        if (i < s.count) {
            if (!e.site || (i && e.site <= s.sites[i - 1].site))
                throw Error("invalid_state", "Variable recording ownership site list mismatch");
        } else if (e.site || e.original || e.replacement)
            throw Error("invalid_state", "Nonempty unused recording ownership entry");
    }
}
struct Identity { uint64_t pid, start; };
bool staleCleanupAllowed(Identity previous, Identity current, bool fullyOriginal, bool previousIdentityGone) {
    return fullyOriginal && previousIdentityGone &&
        (previous.pid != current.pid || previous.start != current.start);
}

struct MappedRange {
    uint64_t lo = 0, hi = 0, offset = 0, inode = 0;
    unsigned major = 0, minor = 0;
    std::string permissions, path;
};
std::vector<MappedRange> parseMaps(const std::string& text) {
    std::vector<MappedRange> out; std::istringstream input(text); std::string line;
    while (std::getline(input, line)) {
        unsigned long long lo, hi, off, inode; unsigned major, minor; char permissions[5]{}; int consumed = 0;
        if (sscanf(line.c_str(), "%llx-%llx %4s %llx %x:%x %llu %n", &lo, &hi, permissions, &off,
                   &major, &minor, &inode, &consumed) != 7 || lo >= hi || strlen(permissions) != 4)
            throw Error("maps_mismatch", "Malformed process mapping");
        std::string path = line.substr(size_t(consumed));
        // proc escapes whitespace in path components; retain the exact identity.
        for (size_t p = 0; p + 3 < path.size(); ++p) if (path[p] == '\\' &&
            path[p + 1] >= '0' && path[p + 1] <= '7' && path[p + 2] >= '0' && path[p + 2] <= '7' &&
            path[p + 3] >= '0' && path[p + 3] <= '7') {
            char value = char((path[p + 1] - '0') * 64 + (path[p + 2] - '0') * 8 + path[p + 3] - '0');
            if (!value) throw Error("maps_mismatch", "NUL in mapped path");
            path.replace(p, 4, 1, value);
        }
        out.push_back({lo, hi, off, inode, major, minor, permissions, path});
    }
    if (out.empty()) throw Error("maps_mismatch", "Empty process mappings");
    return out;
}
bool sameModule(const MappedRange& a, const MappedRange& b) {
    return a.path == b.path && a.inode == b.inode && a.major == b.major && a.minor == b.minor;
}
std::string mappingEpoch(const std::vector<MappedRange>& maps) {
    std::ostringstream out;
    std::vector<MappedRange> executable;
    for (const auto& m : maps) if (m.permissions[2] == 'x' && m.path != "[vdso]") executable.push_back(m);
    for (const auto& m : maps) if (std::any_of(executable.begin(), executable.end(), [&](const auto& e) { return sameModule(m, e); }))
        out << m.lo << ':' << m.hi << ':' << m.offset << ':' << m.major << ':' << m.minor << ':' << m.inode << ':'
            << m.permissions << ':' << m.path.size() << ':' << m.path << '\n';
    return out.str();
}
uint64_t mappedBias(const Image& image, const std::vector<MappedRange>& group, uint64_t page) {
    if (group.empty() || !page || (page & (page - 1))) throw Error("maps_mismatch", "Invalid mapped ELF page size");
    std::set<uint64_t> biases;
    for (const auto& m : group) if (m.permissions[2] == 'x') {
        bool accounted = false;
        for (unsigned i = 0; i < image.header.e_phnum; ++i) {
            auto ph = at<Elf64_Phdr>(image.bytes, image.header.e_phoff + uint64_t(i) * sizeof(Elf64_Phdr));
            if (ph.p_type != 1 || !(ph.p_flags & 1) || !ph.p_filesz || ph.p_filesz > image.bytes.size() ||
                ph.p_offset > image.bytes.size() - ph.p_filesz || ph.p_vaddr % page != ph.p_offset % page) continue;
            uint64_t fileStart = ph.p_offset & ~(page - 1), vaStart = ph.p_vaddr & ~(page - 1);
            if (m.offset < fileStart || m.offset - fileStart >= ph.p_filesz + (ph.p_offset - fileStart) ||
                m.lo < vaStart || m.lo - vaStart < m.offset - fileStart) continue;
            uint64_t bias = m.lo - vaStart - (m.offset - fileStart);
            if (bias % page) continue;
            biases.insert(bias); accounted = true;
        }
        if (!accounted) throw Error("maps_mismatch", "Executable mapping does not correspond to an executable PT_LOAD");
    }
    if (biases.size() != 1) throw Error("maps_mismatch", "Mapped ELF has ambiguous load bias or duplicate loads");
    uint64_t bias = *biases.begin();
    for (const auto& s : image.sites) {
        bool executable = false;
        for (unsigned i = 0; i < image.header.e_phnum; ++i) {
            auto ph = at<Elf64_Phdr>(image.bytes, image.header.e_phoff + uint64_t(i) * sizeof(Elf64_Phdr));
            if (ph.p_type == 1 && (ph.p_flags & 1) && s.functionStart >= ph.p_vaddr &&
                s.functionEnd >= s.functionStart && s.functionEnd - ph.p_vaddr <= ph.p_filesz) executable = true;
        }
        if (!executable || s.functionEnd > UINT64_MAX - bias) throw Error("maps_mismatch", "Enclosing function is outside executable PT_LOAD");
        uint64_t cursor = bias + s.functionStart, end = bias + s.functionEnd;
        while (cursor < end) {
            auto map = std::find_if(group.begin(), group.end(), [&](const auto& m) {
                return m.permissions[0] == 'r' && m.permissions[2] == 'x' && m.lo <= cursor && cursor < m.hi;
            });
            if (map == group.end() || map->offset + cursor - map->lo != image.offset(cursor - bias))
                throw Error("maps_mismatch", "Function mapping/file-offset relationship differs from ELF");
            cursor = std::min(end, map->hi);
        }
    }
    return bias;
}

#ifndef SF17FIX_INSPECT_ONLY
uint64_t processStart(pid_t pid) {
    std::ifstream f("/proc/" + std::to_string(pid) + "/stat"); std::string s;
    std::getline(f, s); auto p = s.rfind(')');
    if (p == std::string::npos) throw Error("process_gone", "Missing process start time");
    std::istringstream fields(s.substr(p + 2)); std::string word;
    for (int field = 3; field <= 22; ++field) { if (!(fields >> word)) throw Error("process_gone", "Short process stat"); }
    return std::stoull(word);
}
pid_t findSf() {
    DIR* d = opendir("/proc"); if (!d) fail("proc_denied", "Cannot enumerate /proc");
    std::vector<pid_t> found;
    while (auto* ent = readdir(d)) {
        char* end = nullptr; long id = strtol(ent->d_name, &end, 10);
        if (!id || !end || *end || id > std::numeric_limits<pid_t>::max()) continue;
        std::ifstream f("/proc/" + std::to_string(id) + "/comm"); std::string name; std::getline(f, name);
        if (name == "surfaceflinger") found.push_back(pid_t(id));
    }
    closedir(d);
    if (found.size() != 1) throw Error(found.empty() ? "sf_not_found" : "sf_ambiguous", "Expected exactly one SurfaceFlinger process");
    return found[0];
}
std::string readMaps(pid_t pid) {
    std::ifstream file("/proc/" + std::to_string(pid) + "/maps");
    if (!file) fail("proc_denied", "Cannot read process mappings");
    std::string text((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    if (file.bad() || text.empty() || text.size() > 4 * 1024 * 1024) throw Error("maps_mismatch", "Invalid process mapping inventory");
    return text;
}
std::string processExe(pid_t pid) {
    std::array<char, 4096> path{};
    ssize_t n = readlink(("/proc/" + std::to_string(pid) + "/exe").c_str(), path.data(), path.size() - 1);
    if (n <= 0 || n == ssize_t(path.size() - 1)) fail("proc_denied", "Cannot resolve process executable identity");
    std::string result(path.data(), size_t(n));
    if (result.front() != '/' || result.ends_with(" (deleted)")) throw Error("unsupported_module_maps", "Executable has no stable file identity");
    return result;
}
enum class AccessRoute : uint32_t { ProcExe = 1, MapFile = 2, ProcRoot = 3, Absolute = 4 };
const char* accessRouteName(AccessRoute route) {
    switch (route) {
        case AccessRoute::ProcExe: return "proc_exe";
        case AccessRoute::MapFile: return "exact_map_file";
        case AccessRoute::ProcRoot: return "proc_root_strict";
        case AccessRoute::Absolute: return "absolute_strict";
    }
    return "invalid";
}
bool validAccessRoute(uint32_t route) { return route >= 1 && route <= 4; }
struct AccessHint { AccessRoute route = AccessRoute::ProcRoot; Digest digest{}; };
using AccessHints = std::map<std::string, AccessHint>;
struct ProcessDirectory {
    Fd fd;
    pid_t pid;
    uint64_t start;
    std::string epoch;
    explicit ProcessDirectory(pid_t id, const std::string& expectedEpoch) : pid(id), start(processStart(id)), epoch(expectedEpoch) {
        // Only these verified procfs directories may supply magic-link proof.
        // Never follow a caller-controlled symlink and call it a mapped handle.
        Fd proc(open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        struct stat st{}; struct statfs fs{};
        if (proc.value < 0 || fstatfs(proc.value, &fs) || uint64_t(fs.f_type) != 0x9fa0 ||
            fstat(proc.value, &st) || !S_ISDIR(st.st_mode) || st.st_uid != 0)
            throw Error("untrusted_procfs", "Mapped-file proof requires root-owned procfs");
        fd.value = openat(proc.value, std::to_string(pid).c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (fd.value < 0 || fstatfs(fd.value, &fs) || uint64_t(fs.f_type) != 0x9fa0 ||
            fstat(fd.value, &st) || !S_ISDIR(st.st_mode))
            throw Error("untrusted_procfs", "Cannot bind the SurfaceFlinger procfs directory");
        if (processStart(pid) != start) throw Error("process_changed", "PID changed while opening procfs directory");
    }
};
struct ModuleReadIdentity { std::optional<Digest> digest; };
struct ModuleFile {
    MappedRange identity;
    std::vector<MappedRange> maps;
    std::string openedPath, stamp;
    AccessRoute route = AccessRoute::ProcRoot;
    bool cachedRoute = false;
    std::optional<Digest> cachedDigest;
    std::shared_ptr<ProcessDirectory> process;
    std::shared_ptr<ModuleReadIdentity> readIdentity = std::make_shared<ModuleReadIdentity>();
};
std::string fileStamp(const struct stat& s) {
    std::ostringstream o;
    o << uint64_t(s.st_dev) << ':' << s.st_ino << ':' << s.st_size << ':' << s.st_mtim.tv_sec << ':' << s.st_mtim.tv_nsec
      << ':' << s.st_ctim.tv_sec << ':' << s.st_ctim.tv_nsec
      << ':' << s.st_mode << ':' << s.st_uid << ':' << s.st_gid;
    return o.str();
}
void validateModuleStat(const ModuleFile& module, const struct stat& s) {
    if (!S_ISREG(s.st_mode) || s.st_size <= 0 || uint64_t(s.st_size) > kMaxElf)
        throw Error("unsupported_module_elf", "Mapped ELF is not a bounded regular file: " + module.identity.path);
    // maps uses the kernel's file-user-inode identity. Stacked filesystems may
    // expose another getattr identity even on the exact mapped-file FD. Only a
    // trusted procfs magic link binds that FD to the mapping independently.
    bool mappedHandle = module.route == AccessRoute::ProcExe || module.route == AccessRoute::MapFile;
    if (!mappedHandle && (uint64_t(s.st_ino) != module.identity.inode ||
        unsigned(major(s.st_dev)) != module.identity.major || unsigned(minor(s.st_dev)) != module.identity.minor))
        throw Error("module_identity_changed", "Mapped path device/inode differs from opened ELF: " + module.identity.path);
}
std::string procLink(const ModuleFile& module, const std::string& name) {
    std::array<char, 4096> text{};
    ssize_t n = readlinkat(module.process->fd.value, name.c_str(), text.data(), text.size());
    if (n <= 0 || size_t(n) == text.size()) fail("module_read_denied", "Cannot resolve mapped procfs link: " + name);
    return std::string(text.data(), size_t(n));
}
std::string mapFileName(const ModuleFile& module) {
    std::ostringstream name;
    name << "map_files/" << std::hex << module.identity.lo << '-' << module.identity.hi;
    return name.str();
}
int openModule(const ModuleFile& module) {
    if (!module.process || module.process->fd.value < 0)
        throw Error("untrusted_procfs", "Missing verified process directory");
    int descriptor = -1;
    if (module.route == AccessRoute::ProcExe || module.route == AccessRoute::MapFile) {
        std::string link = module.route == AccessRoute::ProcExe ? "exe" : mapFileName(module);
        if (procLink(module, link) != module.identity.path)
            throw Error("module_identity_changed", "Kernel mapped-file link no longer names the selected logical ELF");
        // Deliberately follow ONLY this generated, procfs-verified magic link.
        // O_NOFOLLOW is required for ordinary paths, but would reject proc links.
        descriptor = openat(module.process->fd.value, link.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    } else if (module.route == AccessRoute::ProcRoot) {
        descriptor = openat(module.process->fd.value, ("root" + module.identity.path).c_str(),
                            O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    } else if (module.route == AccessRoute::Absolute) {
        descriptor = open(module.identity.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    } else throw Error("invalid_compat_cache", "Unknown mapped-file access strategy");
    if (descriptor < 0) fail("module_read_denied", "Cannot open mapped ELF using " + std::string(accessRouteName(module.route)));
    return descriptor;
}
std::string currentStamp(const ModuleFile& module) {
    Fd fd(openModule(module)); struct stat st{};
    if (fstat(fd.value, &st)) fail("module_read_denied", "Cannot inspect mapped ELF: " + module.identity.path);
    validateModuleStat(module, st); return fileStamp(st);
}
std::vector<uint8_t> readModule(const ModuleFile& module) {
    Fd fd(openModule(module));
    struct stat before{}, after{};
    if (fstat(fd.value, &before)) fail("module_read_denied", "Cannot inspect opened mapped ELF");
    validateModuleStat(module, before);
    if (fileStamp(before) != module.stamp) throw Error("module_identity_changed", "Mapped ELF changed during discovery");
    if (before.st_size <= 0 || uint64_t(before.st_size) > kMaxElf) throw Error("unsupported_module_elf", "Mapped ELF exceeds supported file-size bounds");
    std::vector<uint8_t> bytes(size_t(before.st_size)); size_t done = 0;
    while (done < bytes.size()) {
        ssize_t count = pread(fd.value, bytes.data() + done, bytes.size() - done, off_t(done));
        if (count <= 0) fail("module_read_failed", "Short mapped ELF read");
        done += size_t(count);
    }
    if (fstat(fd.value, &after) || fileStamp(after) != module.stamp)
        throw Error("module_identity_changed", "Mapped ELF changed while reading");
    validateModuleStat(module, after);
    gMetrics.moduleReadBytes += bytes.size();
    module.readIdentity->digest = strongDigest(bytes.data(), bytes.size());
    if (module.cachedDigest && *module.cachedDigest != *module.readIdentity->digest) {
        // A route hint is not an ELF proof. A system update may leave the same
        // access route usable, but the old semantic descriptor must miss and
        // the complete decoder/live-code/ownership checks still run below.
        gMetrics.accessCache = "stale_elf";
        gMetrics.accessCacheDetail = "ELF changed; revalidating semantic proof: " + module.identity.path;
    }
    return bytes;
}
AccessHints& accessHints();
void saveAccessHints(const std::vector<ModuleFile>& inventory);
std::string accessIdentity(const MappedRange& identity) {
    std::ostringstream out; out << std::hex << identity.major << ':' << identity.minor << std::dec << ':' << identity.inode; return out.str();
}
std::string accessIdentity(const struct stat& s) {
    std::ostringstream out; out << std::hex << major(s.st_dev) << ':' << minor(s.st_dev) << std::dec << ':' << s.st_ino; return out.str();
}
void accessDiagnostic(const ModuleFile& module, const std::string& stage, const struct stat* observed, const Error* error = nullptr) {
    if (gMetrics.accessDiagnosticsTruncated || gMetrics.moduleAccess.size() >= 128) {
        gMetrics.accessDiagnosticsTruncated = true; return;
    }
    std::ostringstream out;
    out << "{\"path\":" << quote(module.identity.path) << ",\"strategy\":" << quote(accessRouteName(module.route))
        << ",\"cached_strategy\":" << (module.cachedRoute ? "true" : "false")
        << ",\"stage\":" << quote(stage) << ",\"maps_identity\":" << quote(accessIdentity(module.identity))
        << ",\"file_identity\":" << quote(observed ? accessIdentity(*observed) : "")
        << ",\"dual_identity\":" << ((observed && accessIdentity(*observed) != accessIdentity(module.identity)) ? "true" : "false")
        // Legacy companion scripts select the top-level reason with sed/grep.
        // Keep diagnostic names distinct so a failed attempt cannot impersonate
        // the authoritative pipeline result in those deployed parsers.
        << ",\"access_reason\":" << quote(error ? error->reason : "verified")
        << ",\"access_detail\":" << quote(error ? error->what() : "") << ",\"access_errno\":" << (error ? error->err : 0) << '}';
    auto encoded = out.str();
    // Status consumers also run without diagnostics enabled and cap reads at
    // 64 KiB. Budget the *encoded* bytes, including escaping and separators.
    constexpr size_t budget = 12 * 1024;
    size_t added = encoded.size() + (gMetrics.moduleAccess.empty() ? 0 : 1);
    if (added > budget - gMetrics.accessDiagnosticBytes) {
        gMetrics.accessDiagnosticsTruncated = true; return;
    }
    gMetrics.accessDiagnosticBytes += added;
    gMetrics.moduleAccess.push_back(std::move(encoded));
}
std::vector<ModuleFile> moduleInventory(pid_t pid, const std::vector<MappedRange>& maps) {
    std::vector<ModuleFile> out;
    auto epoch = mappingEpoch(maps);
    auto process = std::make_shared<ProcessDirectory>(pid, epoch);
    auto& hints = accessHints();
    for (const auto& m : maps) if (m.permissions[2] == 'x' && m.path != "[vdso]") {
        if (m.path.empty() || m.path.front() != '/' || m.path.ends_with(" (deleted)") || !m.inode)
            throw Error("unsupported_module_maps", "Executable anonymous/deleted mapping prevents a complete ELF audit");
        if (std::any_of(out.begin(), out.end(), [&](const auto& x) { return sameModule(x.identity, m); })) continue;
        if (out.size() == 1024) throw Error("unsupported_module_maps", "Executable module inventory exceeds audit bound");
        ModuleFile module; module.identity = m; module.process = process;
        for (const auto& r : maps) if (sameModule(m, r)) module.maps.push_back(r);
        std::vector<AccessRoute> routes;
        auto hint = hints.find(m.path);
        if (hint != hints.end()) { routes.push_back(hint->second.route); module.cachedDigest = hint->second.digest; }
        for (auto route : {AccessRoute::ProcExe, AccessRoute::MapFile, AccessRoute::ProcRoot, AccessRoute::Absolute})
            if (std::find(routes.begin(), routes.end(), route) == routes.end()) routes.push_back(route);
        bool accepted = false; std::string failure;
        for (auto route : routes) {
            module.route = route; module.cachedRoute = hint != hints.end() && route == hint->second.route;
            // The executable link is a candidate only for the kernel's actual
            // main executable; DSO mappings use exact map_files handles.
            struct stat st{}; bool observed = false;
            try {
                if (route == AccessRoute::ProcExe && procLink(module, "exe") != m.path) continue;
                Fd fd(openModule(module));
                if (fstat(fd.value, &st)) fail("module_read_denied", "Cannot inspect access-strategy FD");
                observed = true; validateModuleStat(module, st);
                module.stamp = fileStamp(st);
                module.openedPath = route == AccessRoute::ProcExe ? "/proc/" + std::to_string(pid) + "/exe" :
                    route == AccessRoute::MapFile ? "/proc/" + std::to_string(pid) + "/" + mapFileName(module) :
                    route == AccessRoute::ProcRoot ? "/proc/" + std::to_string(pid) + "/root" + m.path : m.path;
                accessDiagnostic(module, "selected", &st); accepted = true;
                if (hint != hints.end() && !module.cachedRoute) gMetrics.accessCache = "fallback";
                break;
            } catch (const Error& e) {
                accessDiagnostic(module, "rejected", observed ? &st : nullptr, &e);
                if (failure.size() < 4096) failure += std::string(accessRouteName(route)) + ": " + e.reason + " (" + std::to_string(e.err) + "); ";
            }
        }
        if (!accepted) throw Error("module_identity_changed", "No verified mapped ELF access strategy for " + m.path + ": " + failure);
        out.push_back(std::move(module));
    }
    if (out.empty()) throw Error("maps_mismatch", "No file-backed executable ELF candidates");
    if (processStart(pid) != process->start || mappingEpoch(parseMaps(readMaps(pid))) != epoch)
        throw Error("process_changed", "Process/mapping epoch changed while selecting mapped-file access strategies");
    return out;
}
bool privateMetadata(const struct stat& s, bool directory) {
    return s.st_uid==0 && (s.st_mode&07777)==(directory?0700:0600) &&
        (directory?S_ISDIR(s.st_mode):(S_ISREG(s.st_mode)&&s.st_nlink==1));
}
int compatibilityDirectory() {
    Fd parent(open(kStateDir,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW));struct stat st{};
    if(parent.value<0 || fstat(parent.value,&st) || !privateMetadata(st,true))throw Error("invalid_compat_cache","Cache parent is not root-private");
    if(mkdirat(parent.value,"compat-cache",0700) && errno!=EEXIST)fail("compat_cache_io","Cannot prepare compatibility cache directory");
    Fd directory(openat(parent.value,"compat-cache",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW));
    if(directory.value<0 || fstat(directory.value,&st) || !privateMetadata(st,true))throw Error("invalid_compat_cache","Cache directory is not root-private");
    int result=dup(directory.value);if(result<0)fail("compat_cache_io","Cannot retain cache directory");return result;
}
const Digest& programDigest() {
    static const Digest digest=[] {
        // /proc/self/exe references this running inode even across an atomic
        // component replacement. Hashing it automatically invalidates proof
        // descriptors whenever any decoder/broker/build byte changes.
        Fd fd(open("/proc/self/exe",O_RDONLY|O_CLOEXEC));struct stat before{},after{};
        if(fd.value<0 || fstat(fd.value,&before) || !S_ISREG(before.st_mode) || before.st_size<=0 || uint64_t(before.st_size)>kMaxElf)
            throw Error("compat_cache_io","Cannot identify running decoder executable");
        std::vector<uint8_t> bytes(size_t(before.st_size));size_t done=0;
        while(done<bytes.size()) {ssize_t n=read(fd.value,bytes.data()+done,bytes.size()-done);if(n<=0)fail("compat_cache_io","Cannot hash decoder executable");done+=size_t(n);}
        if(fstat(fd.value,&after) || fileStamp(before)!=fileStamp(after))throw Error("compat_cache_io","Decoder executable changed while hashing");
        return strongDigest(bytes.data(),bytes.size());
    }();return digest;
}
// This descriptor remembers *how* a verified mapped file can be read. It never
// grants patch capability, replaces the decoder proof, stores a live address,
// or permits an ordinary pathname to bypass maps device/inode comparison.
constexpr uint64_t kAccessCacheMagic = 0x3153434341584653ULL; // SFXACCS1
std::vector<uint8_t> encodeAccessHints(const AccessHints& hints, const Digest& program) {
    if (hints.empty() || hints.size() > 1024) throw Error("invalid_access_cache", "Access descriptor inventory count invalid");
    std::vector<uint8_t> out;
    auto number = [&](uint64_t n, unsigned width) { for (unsigned i = 0; i < width; ++i) out.push_back(uint8_t(n >> (8 * i))); };
    number(kAccessCacheMagic, 8); number(1, 4); out.insert(out.end(), program.begin(), program.end()); number(hints.size(), 4);
    for (const auto& [path, hint] : hints) {
        if (path.empty() || path.front() != '/' || path.size() > 4096 || path.find('\0') != std::string::npos ||
            path.ends_with(" (deleted)") || !validAccessRoute(uint32_t(hint.route)))
            throw Error("invalid_access_cache", "Access descriptor entry invalid");
        number(path.size(), 4); out.insert(out.end(), path.begin(), path.end()); number(uint32_t(hint.route), 4);
        out.insert(out.end(), hint.digest.begin(), hint.digest.end());
    }
    if (out.size() > kMaxCompatibilityRecord - 32) throw Error("invalid_access_cache", "Access descriptor too large");
    auto digest = strongDigest(out.data(), out.size()); out.insert(out.end(), digest.begin(), digest.end()); return out;
}
AccessHints decodeAccessHints(const std::vector<uint8_t>& bytes, const Digest& program) {
    if (bytes.size() < 88 || bytes.size() > kMaxCompatibilityRecord) throw Error("invalid_access_cache", "Access descriptor size invalid");
    size_t pos = 0, end = bytes.size() - 32; auto checksum = strongDigest(bytes.data(), end);
    if (memcmp(checksum.data(), bytes.data() + end, 32)) throw Error("invalid_access_cache", "Access descriptor checksum invalid");
    auto number = [&](unsigned width) {
        if (pos > end || width > end - pos) throw Error("invalid_access_cache", "Truncated access descriptor");
        uint64_t n = 0; for (unsigned i = 0; i < width; ++i) n |= uint64_t(bytes[pos++]) << (8 * i); return n;
    };
    if (number(8) != kAccessCacheMagic || number(4) != 1) throw Error("invalid_access_cache", "Access descriptor schema invalid");
    if (pos > end || 32 > end - pos || memcmp(bytes.data() + pos, program.data(), 32))
        throw Error("invalid_access_cache", "Access descriptor decoder changed");
    pos += 32; uint64_t count = number(4);
    if (!count || count > 1024) throw Error("invalid_access_cache", "Access descriptor inventory count invalid");
    AccessHints out; std::string previous;
    for (uint64_t i = 0; i < count; ++i) {
        uint64_t length = number(4);
        if (!length || length > 4096 || pos > end || length > end - pos) throw Error("invalid_access_cache", "Access descriptor path bound invalid");
        std::string path(reinterpret_cast<const char*>(bytes.data() + pos), size_t(length)); pos += size_t(length);
        uint32_t route = uint32_t(number(4));
        if (path.front() != '/' || path.find('\0') != std::string::npos || path.ends_with(" (deleted)") ||
            (!previous.empty() && path <= previous) || !validAccessRoute(route) || pos > end || 32 > end - pos)
            throw Error("invalid_access_cache", "Access descriptor entry invalid");
        AccessHint hint; hint.route = AccessRoute(route); memcpy(hint.digest.data(), bytes.data() + pos, 32); pos += 32;
        out.emplace(path, hint); previous = std::move(path);
    }
    if (pos != end) throw Error("invalid_access_cache", "Access descriptor trailing data");
    return out;
}
AccessHints& accessHints() {
    static AccessHints hints;
    static bool loaded = false;
    static std::string state = "miss", detail;
    if (!loaded) {
        loaded = true;
        try {
            Fd directory(compatibilityDirectory());
            Fd fd(openat(directory.value, "access.cache", O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW));
            struct stat before{}, after{};
            if (fd.value < 0 || fstat(fd.value, &before) || !privateMetadata(before, false) || before.st_size <= 0 ||
                uint64_t(before.st_size) > kMaxCompatibilityRecord)
                throw Error("invalid_access_cache", "Access descriptor absent or not root-private");
            std::vector<uint8_t> bytes(size_t(before.st_size)); size_t done = 0;
            while (done < bytes.size()) {
                ssize_t n = pread(fd.value, bytes.data() + done, bytes.size() - done, off_t(done));
                if (n <= 0) fail("access_cache_io", "Short access descriptor read"); done += size_t(n);
            }
            if (fstat(fd.value, &after) || !privateMetadata(after, false) || fileStamp(before) != fileStamp(after))
                throw Error("invalid_access_cache", "Access descriptor changed while reading");
            hints = decodeAccessHints(bytes, programDigest()); state = "persistent";
        } catch (const Error& e) { detail = e.reason + ": " + e.what(); }
          catch (const std::exception& e) { detail = e.what(); }
    }
    gMetrics.accessCache = hints.empty() ? state : (state == "persistent" ? "persistent" : "memory");
    gMetrics.accessCacheDetail = detail;
    return hints;
}
void saveAccessHints(const std::vector<ModuleFile>& inventory) {
    try {
        AccessHints next;
        for (const auto& module : inventory) {
            if (!module.readIdentity->digest) throw Error("invalid_access_cache", "Unverified ELF cannot enter access descriptor");
            next.emplace(module.identity.path, AccessHint{module.route, *module.readIdentity->digest});
        }
        auto& previous = accessHints();
        bool same = next.size() == previous.size();
        for (const auto& [path, hint] : next) {
            auto old = previous.find(path);
            if (old == previous.end() || old->second.route != hint.route || old->second.digest != hint.digest) same = false;
        }
        if (same) return;
        auto bytes = encodeAccessHints(next, programDigest());
        Fd directory(compatibilityDirectory());
        std::string temporary = "access.cache.tmp." + std::to_string(getpid()) + "." +
            std::to_string(std::chrono::steady_clock::now().time_since_epoch().count());
        Fd fd(openat(directory.value, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600));
        if (fd.value < 0) fail("access_cache_io", "Cannot create private access descriptor");
        try {
            struct stat st{}; if (fstat(fd.value, &st) || !privateMetadata(st, false)) throw Error("invalid_access_cache", "Access descriptor metadata invalid");
            size_t done = 0;
            while (done < bytes.size()) { ssize_t n = write(fd.value, bytes.data() + done, bytes.size() - done); if (n <= 0) fail("access_cache_io", "Short access descriptor write"); done += size_t(n); }
            if (fsync(fd.value) || renameat(directory.value, temporary.c_str(), directory.value, "access.cache") || fsync(directory.value))
                fail("access_cache_io", "Cannot commit access descriptor");
        } catch (...) { unlinkat(directory.value, temporary.c_str(), 0); throw; }
        previous = std::move(next);
        gMetrics.accessCache = "saved"; gMetrics.accessCacheDetail.clear();
    } catch (const Error& e) { gMetrics.accessCacheDetail = e.reason + ": " + e.what(); }
      catch (const std::exception& e) { gMetrics.accessCacheDetail = e.what(); }
}
const char* compatibilityName(Pipeline pipe) {return pipe==Pipeline::Recording?"recording.cache":"screenshot.cache";}
CompatibilityRecord readCompatibility(Pipeline pipe) {
    Fd directory(compatibilityDirectory());
    Fd fd(openat(directory.value,compatibilityName(pipe),O_RDONLY|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW));struct stat before{},after{};
    if(fd.value<0 || fstat(fd.value,&before) || !privateMetadata(before,false) || before.st_size<=0 || uint64_t(before.st_size)>kMaxCompatibilityRecord)
        throw Error("invalid_compat_cache","Cache file is absent or not root-private");
    std::vector<uint8_t> bytes(size_t(before.st_size));size_t done=0;
    while(done<bytes.size()) {ssize_t n=read(fd.value,bytes.data()+done,bytes.size()-done);if(n<=0)fail("compat_cache_io","Short compatibility descriptor read");done+=size_t(n);}
    if(fstat(fd.value,&after) || !privateMetadata(after,false) || fileStamp(before)!=fileStamp(after))
        throw Error("invalid_compat_cache","Cache file changed while reading");
    return decodeCompatibility(bytes,pipe,programDigest());
}
void writeCompatibility(const CompatibilityRecord& record) {
    auto bytes=encodeCompatibility(record);Fd directory(compatibilityDirectory());
    std::string temporary=std::string(compatibilityName(record.pipeline))+".tmp."+std::to_string(getpid())+"."+
        std::to_string(std::chrono::steady_clock::now().time_since_epoch().count());
    Fd fd(openat(directory.value,temporary.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600));
    if(fd.value<0)fail("compat_cache_io","Cannot create private compatibility descriptor");
    try {
        struct stat st{};if(fstat(fd.value,&st) || !privateMetadata(st,false))throw Error("invalid_compat_cache","Prepared descriptor metadata invalid");
        size_t done=0;while(done<bytes.size()) {ssize_t n=write(fd.value,bytes.data()+done,bytes.size()-done);if(n<=0)fail("compat_cache_io","Short compatibility descriptor write");done+=size_t(n);}
        if(fsync(fd.value) || renameat(directory.value,temporary.c_str(),directory.value,compatibilityName(record.pipeline)) || fsync(directory.value))
            fail("compat_cache_io","Cannot commit compatibility descriptor");
    } catch (...) {unlinkat(directory.value,temporary.c_str(),0);throw;}
}
std::vector<ModuleFile> canonicalInventory(std::vector<ModuleFile> inventory) {
    std::sort(inventory.begin(),inventory.end(),[](const auto& a,const auto& b){return a.identity.path<b.identity.path;});return inventory;
}
std::optional<Image> persistentImage(const std::vector<ModuleFile>& inventory,Pipeline pipe) {
    try {
        auto record=readCompatibility(pipe);auto canonical=canonicalInventory(inventory);
        if(record.files.size()!=canonical.size())return std::nullopt;
        // Old mount metadata is diagnostic, not semantic identity: an unchanged
        // ELF may acquire another dev/inode/ctime after reboot or remount. Every
        // current read still binds its current maps/fstat tuple before and after,
        // and *all* current file contents must match the certified SHA-256 set.
        for(size_t i=0;i<canonical.size();++i)if(record.files[i].path!=canonical[i].identity.path)return std::nullopt;
        std::vector<uint8_t> selected;uint64_t total=0;
        for(size_t i=0;i<canonical.size();++i) {
            auto bytes=readModule(canonical[i]);total+=bytes.size();
            if(total>512*1024*1024 || *canonical[i].readIdentity->digest!=record.files[i].digest)return std::nullopt;
            if(i==record.selected)selected=std::move(bytes);
        }
        const auto& module=canonical[record.selected];
        Image image(module.openedPath,pipe,std::move(selected),&record.sites);
        image.modulePath=module.identity.path;image.moduleInode=module.identity.inode;
        image.moduleDevice=uint64_t(makedev(module.identity.major,module.identity.minor));
        image.moduleBias=mappedBias(image,module.maps,uint64_t(sysconf(_SC_PAGESIZE)));
        image.starts.clear();image.starts.shrink_to_fit();return image;
    } catch(const std::exception&) {
        // A cache never grants capability or weakens an error. Any unavailable,
        // stale, malformed or mismatched proof falls back to a complete decoder.
        return std::nullopt;
    }
}
bool hasSemanticAnchor(const std::vector<uint8_t>& bytes, Pipeline pipe) {
    if (bytes.size() < sizeof(Elf64_Ehdr) || memcmp(bytes.data(), ELFMAG, SELFMAG)) return false;
    if (pipe == Pipeline::Recording) {
        constexpr char name[] = "eLayerSkipScreenshot";
        return std::search(bytes.begin(), bytes.end(), name, name + sizeof(name)) != bytes.end();
    }
    for (size_t p = 0; p + 4 <= bytes.size(); p += 4) {
        uint32_t w; memcpy(&w, bytes.data() + p, 4);
        if ((w & ~0x3e0u) == 0xb2580001u) return true;
    }
    return false;
}
struct DiscoveryCache {
    pid_t pid = 0; uint64_t start = 0;
    std::string epoch, selectedPath, failureReason, failureDetail;
    std::vector<ModuleFile> inventory;
    std::optional<Image> image;
};
Image discoverImage(pid_t pid, uint64_t start, Pipeline pipe, ModuleFile* selected = nullptr, std::string* selectedEpoch = nullptr) {
    Measure measure(gMetrics.discoveryMs);
    static std::map<Pipeline, DiscoveryCache> caches;
    auto maps = parseMaps(readMaps(pid)); auto epoch = mappingEpoch(maps);
    auto inventory = moduleInventory(pid, maps); // stat identities only, no ELF reads on a warm hit
    auto& cache = caches[pipe];
    bool warm = cache.pid == pid && cache.start == start && cache.epoch == epoch && cache.inventory.size() == inventory.size();
    for (size_t i = 0; warm && i < inventory.size(); ++i)
        warm = sameModule(cache.inventory[i].identity, inventory[i].identity) && cache.inventory[i].stamp == inventory[i].stamp &&
            cache.inventory[i].route == inventory[i].route;
    if (warm) {
        gMetrics.discoveryCache = "memory";
        if (!cache.failureReason.empty()) throw Error(cache.failureReason, cache.failureDetail);
        auto module = std::find_if(inventory.begin(), inventory.end(), [&](const auto& m) { return m.identity.path == cache.selectedPath; });
        if (!cache.image || module == inventory.end()) throw Error("internal_error", "Incomplete mapped ELF cache");
        Image result = *cache.image;
        if (mappedBias(result, module->maps, uint64_t(sysconf(_SC_PAGESIZE))) != result.moduleBias || processStart(pid) != start)
            throw Error("process_changed", "Cached module mapping identity changed");
        if (selected) *selected = *module;
        if (selectedEpoch) *selectedEpoch = epoch;
        return result;
    }
    gMetrics.discoveryCache = "scan";
    cache = {}; cache.pid = pid; cache.start = start; cache.epoch = epoch; cache.inventory = inventory;
#ifndef SF17FIX_INSPECT_ONLY
    // Screenshot's small factory scan is cheaper than hashing every candidate.
    // Persist only the expensive recording CFG proof; both pipelines still use
    // the same immutable warm cache and mandatory live/write validation.
    if (pipe == Pipeline::Recording) if (auto persisted = persistentImage(inventory, pipe)) {
        if (processStart(pid) != start || mappingEpoch(parseMaps(readMaps(pid))) != epoch)
            throw Error("process_changed", "Mapping identity changed during cached discovery");
        bool unchanged = true;
        for (const auto& module : inventory) unchanged = unchanged && currentStamp(module) == module.stamp;
        if (unchanged) {
            gMetrics.discoveryCache = "persistent";
            saveAccessHints(inventory);
            cache.selectedPath = persisted->modulePath; cache.image = std::move(*persisted);
            if (selected) *selected = *std::find_if(inventory.begin(), inventory.end(), [&](const auto& m) { return m.identity.path == cache.selectedPath; });
            if (selectedEpoch) *selectedEpoch = epoch;
            return *cache.image;
        }
    }
    std::vector<CompatibilityFile> verifiedFiles;
#endif
    std::optional<Image> found;
    std::string diagnostic; uint64_t total = 0;
    try {
        for (const auto& module : inventory) {
            auto bytes = readModule(module); total += bytes.size();
            if (total > 512 * 1024 * 1024) throw Error("unsupported_module_maps", "Mapped ELF discovery exceeds total read bound");
#ifndef SF17FIX_INSPECT_ONLY
            if (pipe == Pipeline::Recording)
                verifiedFiles.push_back({module.identity.path, module.stamp, *module.readIdentity->digest});
#endif
            if (!hasSemanticAnchor(bytes, pipe)) continue;
            try {
                Image candidate(module.openedPath, pipe, std::move(bytes));
                candidate.modulePath = module.identity.path;
                candidate.moduleBias = mappedBias(candidate, module.maps, uint64_t(sysconf(_SC_PAGESIZE)));
                candidate.moduleInode = module.identity.inode;
                candidate.moduleDevice = uint64_t(makedev(module.identity.major, module.identity.minor));
                // Function starts are matcher work space, not runtime proof.
                // Retain only the audited sites and immutable ELF snapshot.
                candidate.starts.clear(); candidate.starts.shrink_to_fit();
                if (found) throw Error("signature_ambiguous", "Multiple mapped ELFs independently satisfy this pipeline: " + found->modulePath + " and " + candidate.modulePath);
                found = std::move(candidate);
            } catch (const Error& e) {
                if (e.reason != "signature_not_found" && !e.reason.starts_with("unsupported_")) throw;
                if (diagnostic.size() < 4096) diagnostic += module.identity.path + ": " + e.what() + "; ";
            }
        }
        if (processStart(pid) != start || mappingEpoch(parseMaps(readMaps(pid))) != epoch)
            throw Error("process_changed", "Process/mapping epoch changed during ELF discovery");
        if (!found) throw Error("signature_not_found", "No unique semantic implementation in mapped executable ELFs. " + diagnostic);
#ifndef SF17FIX_INSPECT_ONLY
        for (const auto& module : inventory) if (currentStamp(module) != module.stamp)
            throw Error("module_identity_changed", "Candidate identity changed during full ELF audit");
        if (pipe == Pipeline::Recording) try {
            std::sort(verifiedFiles.begin(), verifiedFiles.end(), [](const auto& a, const auto& b) { return a.path < b.path; });
            CompatibilityRecord proof; proof.pipeline = pipe; proof.program = programDigest(); proof.files = std::move(verifiedFiles); proof.sites = found->sites;
            auto subject = std::find_if(proof.files.begin(), proof.files.end(), [&](const auto& f) { return f.path == found->modulePath; });
            proof.selected = uint32_t(subject - proof.files.begin()); writeCompatibility(proof);
        } catch (const std::exception&) { /* Cache persistence is optional; validated capability is not. */ }
#endif
        cache.image = *found; cache.selectedPath = found->modulePath;
        saveAccessHints(inventory);
        if (selected) *selected = *std::find_if(inventory.begin(), inventory.end(), [&](const auto& m) { return m.identity.path == found->modulePath; });
        if (selectedEpoch) *selectedEpoch = epoch;
        return *found;
    } catch (const Error& e) {
        // A negative result is reusable only for the same PID/start, full mapping
        // epoch and per-file metadata. It never turns an unknown state into original.
        cache.failureReason = e.reason; cache.failureDetail = e.what(); throw;
    }
}
struct Process {
    pid_t pid;
    uint64_t start, bias = 0;
    std::string exe;
    std::string epoch, moduleStamp;
    ModuleFile module;
    Image image;
    explicit Process(pid_t p, Pipeline pipe) : pid(p), start(processStart(p)), image(discoverImage(p, start, pipe, &module, &epoch)) {
        exe = processExe(pid); bias = image.moduleBias;
        moduleStamp = module.stamp;
        gMetrics.selectedAccess = accessRouteName(module.route);
        if (module.identity.inode != image.moduleInode ||
            uint64_t(makedev(module.identity.major, module.identity.minor)) != image.moduleDevice)
            throw Error("module_identity_changed", "Selected file identity changed after discovery");
        validateMapping();
    }
    void validateFile() const {
        validateMapping();
        // Status uses fstat-equivalent identity plus complete live functions.
        // A mutation additionally rereads the complete source snapshot before
        // any thread is interrupted, then rechecks its identity while stopped.
        auto current = readModule(module);
        if (current.size() != image.bytes.size() || memcmp(current.data(), image.bytes.data(), current.size()))
            throw Error("module_identity_changed", "Selected ELF contents differ from cached identity");
        validateMapping();
    }
    void validateMapping() const {
        if (processStart(pid) != start || processExe(pid) != exe || currentStamp(module) != moduleStamp)
            throw Error("process_changed", "Process or selected ELF identity changed");
        auto maps = parseMaps(readMaps(pid));
        if (mappingEpoch(maps) != epoch) throw Error("maps_mismatch", "Executable mapping epoch changed");
        std::vector<MappedRange> group;
        for (const auto& m : maps) if (sameModule(m, module.identity)) group.push_back(m);
        if (mappedBias(image, group, uint64_t(sysconf(_SC_PAGESIZE))) != bias)
            throw Error("maps_mismatch", "Selected ELF load bias changed");
    }
    std::vector<uint8_t> memory(uint64_t address, size_t length) const {
        Fd fd(open(("/proc/" + std::to_string(pid) + "/mem").c_str(), O_RDONLY | O_CLOEXEC));
        if (fd.value < 0) fail("memory_read_denied", "Reading SurfaceFlinger memory requires root policy access");
        std::vector<uint8_t> out(length);
        ssize_t count = pread(fd.value, out.data(), length, off_t(address));
        if (count != ssize_t(length)) fail("memory_read_failed", "Short read of SurfaceFlinger executable memory");
        gMetrics.liveReadBytes += length;
        return out;
    }
    bool validateMemory() const {
        validateMapping();
        bool active = validateFunctionBytes(image, [this](uint64_t lo, size_t length) { return memory(bias + lo, length); });
        validateMapping();
        return active;
    }
};

Ownership record(const Process& p) {
    Ownership s{}; s.pid = uint64_t(p.pid); s.start = p.start; s.site = p.image.site.vaddr;
    s.original = p.image.site.original; s.replacement = p.image.site.replacement;
    s.fileChecksum = p.image.bytes.fileChecksum();
    strncpy(s.buildId, p.image.buildId.c_str(), sizeof(s.buildId) - 1);
    s.hash = checksum(&s, offsetof(Ownership, hash)); return s;
}
template<class T> T readOwnership(const char* path) {
    struct stat st{};
    if (lstat(path, &st)) fail("state_read_failed", "Cannot inspect ownership state");
    if (!S_ISREG(st.st_mode) || st.st_uid != 0 || (st.st_mode & 077) || st.st_size != sizeof(T))
        throw Error("invalid_state", "Ownership state must be a root-owned private regular file");
    Fd f(open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (f.value < 0) fail("state_read_failed", "Cannot open ownership state");
    T s{};
    if (read(f.value, &s, sizeof(s)) != sizeof(s)) fail("state_read_failed", "Short ownership state read");
    validateOwnershipPayload(s);
    return s;
}
RecordingOwnership recordingRecord(const Process& p) {
    if (p.image.sites.size() != 3) throw Error("internal_error", "Invalid recording ownership site count");
    RecordingOwnership s{}; s.pid = uint64_t(p.pid); s.start = p.start; s.count = 3;
    s.fileChecksum = p.image.bytes.fileChecksum();
    strncpy(s.buildId, p.image.buildId.c_str(), sizeof(s.buildId) - 1);
    for (size_t i = 0; i < 3; ++i) {
        s.sites[i] = {p.image.sites[i].vaddr, p.image.sites[i].original, p.image.sites[i].replacement};
    }
    s.hash = checksum(&s, offsetof(RecordingOwnership, hash)); return s;
}
RecordingOwnershipV4 variableRecordingRecord(const Process& p) {
    if (p.image.sites.empty() || p.image.sites.size() > kMaxRecordingSites)
        throw Error("internal_error", "Invalid audited recording ownership site count");
    RecordingOwnershipV4 s{}; s.pid = uint64_t(p.pid); s.start = p.start; s.count = uint32_t(p.image.sites.size());
    s.fileChecksum = p.image.bytes.fileChecksum();
    strncpy(s.buildId, p.image.buildId.c_str(), sizeof(s.buildId) - 1);
    for (size_t i = 0; i < s.count; ++i)
        s.sites[i] = {p.image.sites[i].vaddr, p.image.sites[i].original, p.image.sites[i].replacement};
    s.hash = checksum(&s, offsetof(RecordingOwnershipV4, hash)); return s;
}
RecordingOwnershipV4 readRecordingState(const char* path) {
    struct stat st{};
    if (lstat(path, &st)) fail("state_read_failed", "Cannot inspect recording ownership format");
    if (st.st_size == sizeof(RecordingOwnershipV4)) return readOwnership<RecordingOwnershipV4>(path);
    if (st.st_size != sizeof(RecordingOwnership)) throw Error("invalid_state", "Unknown recording ownership size");
    auto previous = readOwnership<RecordingOwnership>(path);
    RecordingOwnershipV4 s{}; s.pid = previous.pid; s.start = previous.start;
    s.fileChecksum = previous.fileChecksum; s.count = previous.count;
    std::copy(std::begin(previous.sites), std::end(previous.sites), std::begin(s.sites));
    memcpy(s.buildId, previous.buildId, sizeof(s.buildId));
    s.hash = checksum(&s, offsetof(RecordingOwnershipV4, hash)); return s;
}
MappedOwnership mappedRecord(const Process& p) {
    MappedOwnership s{}; s.pid = uint64_t(p.pid); s.start = p.start; s.bias = p.bias;
    s.device = p.image.moduleDevice; s.inode = p.image.moduleInode;
    s.pipeline = p.image.pipeline == Pipeline::Recording ? 1 : 0;
    s.fileChecksum = p.image.bytes.fileChecksum(); s.count = uint32_t(p.image.sites.size());
    if (p.image.modulePath.size() >= sizeof(s.path) || p.image.buildId.size() >= sizeof(s.buildId))
        throw Error("unsupported_module_maps", "Mapped identity exceeds ownership format bounds");
    memcpy(s.path, p.image.modulePath.c_str(), p.image.modulePath.size() + 1);
    memcpy(s.buildId, p.image.buildId.c_str(), p.image.buildId.size() + 1);
    for (size_t i = 0; i < s.count; ++i) s.sites[i] = {p.image.sites[i].vaddr, p.image.sites[i].original, p.image.sites[i].replacement};
    s.hash = checksum(&s, offsetof(MappedOwnership, hash)); return s;
}
bool matchingMappedRecord(const MappedOwnership& s, const MappedOwnership& expected) {
    if (s.pid != expected.pid || s.start != expected.start || s.bias != expected.bias || s.device != expected.device ||
        s.inode != expected.inode || s.pipeline != expected.pipeline || s.fileChecksum != expected.fileChecksum ||
        s.count != expected.count || memcmp(s.buildId, expected.buildId, sizeof(s.buildId)) ||
        memcmp(s.path, expected.path, sizeof(s.path))) return false;
    for (size_t i = 0; i < s.count; ++i) if (s.sites[i].site != expected.sites[i].site ||
        s.sites[i].original != expected.sites[i].original || s.sites[i].replacement != expected.sites[i].replacement) return false;
    return true;
}
bool owned(const Process& p) {
    const char* path = stateFile(p.image.pipeline);
    struct stat st{};
    if (lstat(path, &st)) { if (errno == ENOENT) return false; fail("state_read_failed", "Cannot inspect ownership state"); }
    if (st.st_size == sizeof(MappedOwnership)) {
        auto s = readOwnership<MappedOwnership>(path);
        if (s.pipeline != (p.image.pipeline == Pipeline::Recording ? 1u : 0u))
            throw Error("invalid_state", "Ownership belongs to another pipeline");
        return matchingMappedRecord(s, mappedRecord(p));
    }
    if (p.image.pipeline == Pipeline::Recording) {
        auto s = readRecordingState(path), expected = variableRecordingRecord(p);
        if (p.image.modulePath != p.exe) return false;
        if (s.pid != expected.pid || s.start != expected.start || s.count != expected.count || s.fileChecksum != expected.fileChecksum ||
            memcmp(s.buildId, expected.buildId, sizeof(s.buildId))) return false;
        for (size_t i = 0; i < s.count; ++i) {
            if (s.sites[i].site != expected.sites[i].site || s.sites[i].original != expected.sites[i].original ||
                s.sites[i].replacement != expected.sites[i].replacement) return false;
        }
        return true;
    }
    auto s = readOwnership<Ownership>(path);
    if (p.image.modulePath != p.exe) return false;
    if (s.magic != kStateMagic) throw Error("invalid_state", "Screenshot ownership format mismatch");
    auto expected = record(p);
    return s.pid == expected.pid && s.start == expected.start && s.site == expected.site &&
        s.original == expected.original && s.replacement == expected.replacement &&
        s.fileChecksum == expected.fileChecksum && !memcmp(s.buildId, expected.buildId, sizeof(s.buildId));
}
template<class T> void writeOwnership(const char* path, const T& s) {
    std::string temporary = std::string(path) + ".tmp." + std::to_string(getpid());
    Fd f(open(temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600));
    if (f.value < 0) fail("state_write_failed", "Cannot prepare ownership state");
    if (write(f.value, &s, sizeof(s)) != sizeof(s) || fsync(f.value) || rename(temporary.c_str(), path)) {
        int e = errno; unlink(temporary.c_str()); errno = e; fail("state_write_failed", "Cannot commit ownership state");
    }
}
void saveOwnership(const Process& p) {
    if (p.image.modulePath != p.exe) writeOwnership(stateFile(p.image.pipeline), mappedRecord(p));
    else if (p.image.pipeline == Pipeline::Recording && p.image.sites.size() == 3)
        writeOwnership(stateFile(p.image.pipeline), recordingRecord(p));
    else if (p.image.pipeline == Pipeline::Recording)
        writeOwnership(stateFile(p.image.pipeline), variableRecordingRecord(p));
    else writeOwnership(stateFile(p.image.pipeline), record(p));
}
struct OwnershipIdentity { uint64_t device, inode; std::string stamp; };
OwnershipIdentity ownershipIdentity(const char* path) {
    struct stat st{};if(lstat(path,&st))fail("state_read_failed","Cannot inspect prepared ownership intent");
    return {uint64_t(st.st_dev),uint64_t(st.st_ino),fileStamp(st)};
}
void discardFreshIntent(const Process& p,const OwnershipIdentity& prepared) {
    const char* path=stateFile(p.image.pipeline);struct stat st{};
    if(lstat(path,&st)) {if(errno==ENOENT)return;fail("state_cleanup_failed","Cannot inspect zero-write ownership intent");}
    if(uint64_t(st.st_dev)==prepared.device && uint64_t(st.st_ino)==prepared.inode && fileStamp(st)==prepared.stamp && owned(p)) {
        if(unlink(path) && errno!=ENOENT)fail("state_cleanup_failed","Cannot remove zero-write ownership intent");
    }
}
bool previousIdentityGone(Identity identity) {
    pid_t pid = pid_t(identity.pid);
    errno = 0;
    if (kill(pid, 0) < 0) {
        if (errno == ESRCH) return true;
        fail("process_identity_unknown", "Cannot confirm previous ownership process identity");
    }
    try { return processStart(pid) != identity.start; }
    catch (...) {
        // A process may exit between the existence check and /proc read. Only
        // a second explicit ESRCH confirms disappearance; read errors preserve state.
        if (kill(pid, 0) < 0 && errno == ESRCH) return true;
        throw;
    }
}
bool clearStaleOwnership(const Process& p) {
    const char* path = stateFile(p.image.pipeline);
    struct stat st{};
    if (lstat(path, &st)) { if (errno == ENOENT) return false; fail("state_read_failed", "Cannot inspect stale ownership"); }
    Identity previous{};
    if (st.st_size == sizeof(MappedOwnership)) {
        auto s = readOwnership<MappedOwnership>(path);
        if (s.pipeline != (p.image.pipeline == Pipeline::Recording ? 1u : 0u))
            throw Error("invalid_state", "Stale ownership belongs to another pipeline");
        previous = {s.pid, s.start};
    } else if (p.image.pipeline == Pipeline::Recording) {
        auto s = readRecordingState(path); previous = {s.pid, s.start};
    } else {
        auto s = readOwnership<Ownership>(path); previous = {s.pid, s.start};
    }
    Identity current{uint64_t(p.pid), p.start};
    if (previous.pid == current.pid && previous.start == current.start) return false;
    if (!previousIdentityGone(previous)) return false;
    // A valid obsolete record grants permission to remove only that record,
    // never to restore bytes in a different process. Recheck all current code.
    bool original = !p.validateMemory();
    if (!staleCleanupAllowed(previous, current, original, true)) return false;
    if (unlink(path) && errno != ENOENT) fail("state_cleanup_failed", "Cannot remove confirmed stale ownership");
    return true;
}
std::vector<pid_t> threads(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/task";
    DIR* d = opendir(path.c_str()); if (!d) fail("threads_denied", "Cannot enumerate target threads");
    std::vector<pid_t> out;
    while (auto* ent = readdir(d)) {
        char* end = nullptr; long n = strtol(ent->d_name, &end, 10);
        if (n > 0 && end && !*end && n <= std::numeric_limits<pid_t>::max()) out.push_back(pid_t(n));
    }
    closedir(d); std::sort(out.begin(), out.end()); return out;
}
struct Stopped {
    std::vector<pid_t> attached;
    std::set<pid_t> stopped;
    std::map<pid_t, int> pendingSignals;
    std::chrono::steady_clock::time_point began = std::chrono::steady_clock::now();
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kStopLimitMs);
    ~Stopped() { detach(); }
    void detach() noexcept {
        bool measured = !attached.empty();
        auto cleanupDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
        for (auto it = attached.rbegin(); it != attached.rend(); ++it) {
            // Interrupted threads normally are stopped. On an error we still collect
            // their pending interrupt before detach, rather than leave a tracee paused.
            if (!stopped.count(*it)) {
                ptrace(PTRACE_INTERRUPT, *it, nullptr, nullptr);
                for (int n = 0; n < 100; ++n) {
                    int status = 0; pid_t got = waitpid(*it, &status, __WALL | WNOHANG);
                    if (got == *it && WIFSTOPPED(status)) { stopped.insert(*it); break; }
                    if (got < 0 && errno == ECHILD) break;
                    if (std::chrono::steady_clock::now() >= cleanupDeadline) break;
                    usleep(1000);
                }
            }
            auto signal = pendingSignals.find(*it);
            void* deliver = reinterpret_cast<void*>(intptr_t(signal == pendingSignals.end() ? 0 : signal->second));
            if (ptrace(PTRACE_DETACH, *it, nullptr, deliver) < 0 && errno != ESRCH) {
                // A final interrupt/wait retry avoids silent leakage of a seized thread.
                ptrace(PTRACE_INTERRUPT, *it, nullptr, nullptr);
                int status = 0;
                for (int n = 0; n < 100; ++n) {
                    pid_t got = waitpid(*it, &status, __WALL | WNOHANG);
                    if (got == *it && WIFSTOPPED(status)) {
                        if (ptrace(PTRACE_DETACH, *it, nullptr, deliver) < 0 && errno != ESRCH) gDetachFailed = true;
                        break;
                    }
                    if (got < 0) break;
                    if (std::chrono::steady_clock::now() >= cleanupDeadline) { gDetachFailed = true; break; }
                    usleep(1000);
                }
            }
        }
        attached.clear(); stopped.clear(); pendingSignals.clear();
        if (measured) gMetrics.stoppedMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - began).count();
    }
    void checkTime() const {
        if (std::chrono::steady_clock::now() >= deadline) throw Error("stop_timeout", "Target thread-stop deadline exceeded");
    }
    void all(pid_t pid) {
        for (int pass = 0; pass < 5; ++pass) {
            std::vector<pid_t> added;
            for (pid_t tid : threads(pid)) {
                checkTime();
                if (std::find(attached.begin(), attached.end(), tid) != attached.end()) continue;
                if (ptrace(PTRACE_SEIZE, tid, nullptr, nullptr) < 0) {
                    if (errno == ESRCH) continue;
                    fail("ptrace_denied", "SEIZE denied; no instruction was written");
                }
                attached.push_back(tid); added.push_back(tid);
                gMetrics.stoppedThreads = std::max(gMetrics.stoppedThreads, uint64_t(attached.size()));
                if (ptrace(PTRACE_INTERRUPT, tid, nullptr, nullptr) < 0) fail("ptrace_interrupt_failed", "Cannot interrupt seized thread");
            }
            if (added.empty()) return;
            // Interrupt the complete batch before waiting. Waiting for each tid
            // separately serialized the scheduler's stop latency across every
            // SurfaceFlinger thread and could add one millisecond per thread.
            for (;;) {
                bool pending = false;
                for (pid_t tid : added) {
                    if (stopped.count(tid)) continue;
                    checkTime(); int status = 0;
                    pid_t got = waitpid(tid, &status, __WALL | WNOHANG);
                    if (got == tid) {
                        if (!WIFSTOPPED(status)) throw Error("process_changed", "Target thread exited while stopping");
                        // Do not swallow a real signal-delivery stop. PTRACE_INTERRUPT
                        // must produce PTRACE_EVENT_STOP, otherwise abort before mutation.
                        stopped.insert(tid);
                        if ((unsigned(status) >> 16) != PTRACE_EVENT_STOP || WSTOPSIG(status) != SIGTRAP) {
                            pendingSignals[tid] = WSTOPSIG(status);
                            throw Error("unexpected_stop", "Unexpected signal stop; no instruction was written");
                        }
                        continue;
                    }
                    if (got < 0) fail("ptrace_wait_failed", "Cannot wait for interrupted thread");
                    pending = true;
                }
                if (!pending) break;
                usleep(1000);
            }
        }
        throw Error("threads_unstable", "Thread set did not stabilize before mutation");
    }
};
void mutation(Process& p, bool enable, bool isOwned) {
    if (!enable && !isOwned) throw Error("not_owned", "No matching ownership record; nothing will be restored");
    p.validateFile();
    // Durable intent precedes every instruction write, but disk sync must not
    // take place while SurfaceFlinger is paused. A confirmed zero-write failure
    // removes only the new intent after detach; existing ownership is retained.
    std::optional<OwnershipIdentity> prepared;
    if (enable) { Measure measure(gMetrics.ownershipPrepareMs); saveOwnership(p); }
    if (enable && !isOwned) prepared=ownershipIdentity(stateFile(p.image.pipeline));
    size_t attempted = 0;
    Stopped stopped;
    try {
    stopped.all(p.pid); stopped.checkTime();
    bool active = p.validateMemory();
    if (active == enable) throw Error("external_change", "Pipeline state changed before the write");
    struct Word { uint64_t address; long before, after; };
    std::vector<Word> words;
    for (const auto& s : p.image.sites) {
        uint64_t address = p.bias + s.vaddr, aligned = address & ~uint64_t(sizeof(long) - 1);
        if (std::any_of(words.begin(), words.end(), [aligned](const Word& w) { return w.address == aligned; }))
            throw Error("internal_error", "Overlapping aligned patch words");
        errno = 0; long before = ptrace(PTRACE_PEEKTEXT, p.pid, reinterpret_cast<void*>(aligned), nullptr);
        if (before == -1 && errno) fail("ptrace_read_failed", "Cannot read aligned instruction word");
        uint32_t current; memcpy(&current, reinterpret_cast<uint8_t*>(&before) + (address - aligned), 4);
        if (current != (enable ? s.original : s.replacement)) throw Error("external_change", "Instruction changed while stopped");
        uint32_t desired = enable ? s.replacement : s.original;
        long after = before; memcpy(reinterpret_cast<uint8_t*>(&after) + (address - aligned), &desired, 4);
        words.push_back({aligned, before, after});
    }
    const char* path = stateFile(p.image.pipeline);
    try {
        // All pipeline instructions form one transaction while every target thread
        // remains stopped. POKETEXT also synchronizes the AArch64 instruction cache.
        for (const auto& w : words) {
            stopped.checkTime(); ++attempted;
            if (ptrace(PTRACE_POKETEXT, p.pid, reinterpret_cast<void*>(w.address), reinterpret_cast<void*>(w.after)) < 0)
                fail("ptrace_write_failed", "POKETEXT failed; rolling back the complete pipeline transaction");
            errno = 0; long got = ptrace(PTRACE_PEEKTEXT, p.pid, reinterpret_cast<void*>(w.address), nullptr);
            if ((got == -1 && errno) || got != w.after)
                throw Error("write_verification_failed", "Aligned instruction readback failed; rolling back the pipeline transaction");
        }
        // Whole-function verification includes all allowed replacements and every
        // untouched instruction before the stopped process can resume.
        if (p.validateMemory() != enable) throw Error("write_verification_failed", "Pipeline verification disagrees with requested state");
    } catch (...) {
        bool restored = true;
        for (size_t i = attempted; i > 0; --i) {
            const auto& w = words[i - 1];
            int rc = ptrace(PTRACE_POKETEXT, p.pid, reinterpret_cast<void*>(w.address), reinterpret_cast<void*>(w.before));
            errno = 0; long got = ptrace(PTRACE_PEEKTEXT, p.pid, reinterpret_cast<void*>(w.address), nullptr);
            if (rc < 0 || (got == -1 && errno) || got != w.before) restored = false;
        }
        if (!restored) throw Error("rollback_failed", "Pipeline rollback failed; ownership is retained for inspection");
        stopped.detach();
        if (gDetachFailed) throw Error("detach_failed", "Rollback verified but at least one thread detach did not confirm");
        if (enable) unlink(path);
        throw;
    }
    stopped.detach();
    if (gDetachFailed) throw Error("detach_failed", "Pipeline verified but at least one thread detach did not confirm");
    if (!enable && unlink(path) && errno != ENOENT) fail("state_cleanup_failed", "Pipeline restored but ownership cleanup failed");
    } catch (...) {
        auto error=std::current_exception();stopped.detach();
        if(gDetachFailed)throw Error("detach_failed","Operation failed and at least one thread detach did not confirm");
        if(prepared && !attempted)discardFreshIntent(p,*prepared);
        std::rethrow_exception(error);
    }
}
#endif // SF17FIX_INSPECT_ONLY
struct Result {
    bool ok = false, supported = false, active = false, activeKnown = false, owned = false;
    std::string reason, detail, buildId, siteOffset, modulePath;
    uint64_t moduleBias = 0, moduleDevice = 0, moduleInode = 0;
    Pipeline pipeline = Pipeline::Screenshot;
    std::vector<Site> sites;
    NativeMetrics metrics;
    pid_t pid = 0; int error = 0;
    std::string json() const {
        std::ostringstream output;
        output << "{\"schema\":1,\"ok\":" << (ok ? "true" : "false")
            << ",\"pipeline\":" << quote(pipelineName(pipeline))
            << ",\"supported\":" << (supported ? "true" : "false")
            << ",\"active\":" << (active ? "true" : "false")
            << ",\"active_known\":" << (activeKnown ? "true" : "false")
            << ",\"owned\":" << (owned ? "true" : "false")
            << ",\"reason\":" << quote(reason) << ",\"detail\":" << quote(detail)
            << ",\"pid\":" << pid << ",\"build_id\":" << quote(buildId)
            << ",\"module_path\":" << quote(modulePath) << ",\"module_bias\":" << quote(hex(moduleBias))
            << ",\"module_device\":" << moduleDevice << ",\"module_inode\":" << moduleInode
            << ",\"site_offset\":" << quote(siteOffset) << ",\"site_count\":" << sites.size() << ",\"sites\":[";
        for (size_t i = 0; i < sites.size(); ++i) {
            if (i) output << ',';
            output << "{\"offset\":" << quote(hex(sites[i].vaddr)) << ",\"function_start\":" << quote(hex(sites[i].functionStart))
                << ",\"function_end\":" << quote(hex(sites[i].functionEnd))
                << ",\"original\":" << quote(hex(sites[i].original)) << ",\"replacement\":" << quote(hex(sites[i].replacement)) << '}';
        }
        output << "],\"errno\":" << error
            << ",\"file_access\":{\"cache\":" << quote(metrics.accessCache)
            << ",\"cache_detail\":" << quote(metrics.accessCacheDetail)
            << ",\"selected_strategy\":" << quote(metrics.selectedAccess)
            << ",\"attempts_truncated\":" << (metrics.accessDiagnosticsTruncated ? "true" : "false") << ",\"attempts\":[";
        for (size_t i = 0; i < metrics.moduleAccess.size(); ++i) { if (i) output << ','; output << metrics.moduleAccess[i]; }
        output << "]}"
            << ",\"performance\":{\"discovery_cache\":" << quote(metrics.discoveryCache)
            << ",\"discovery_ms\":" << metrics.discoveryMs << ",\"module_read_bytes\":" << metrics.moduleReadBytes
            << ",\"live_read_bytes\":" << metrics.liveReadBytes << ",\"ownership_prepare_ms\":" << metrics.ownershipPrepareMs
            << ",\"stopped_ms\":" << metrics.stoppedMs << ",\"stopped_threads\":" << metrics.stoppedThreads
            << ",\"total_ms\":" << metrics.totalMs << "}}";
        return output.str();
    }
    void print() const { std::cout << json() << "\n"; }
};
#ifndef SF17FIX_INSPECT_ONLY
Result operate(Pipeline pipeline, const std::string& command) {
    gMetrics = {};
    auto operationBegin = std::chrono::steady_clock::now();
    Result result; result.pipeline = pipeline;
    try {
        Process p(findSf(), result.pipeline); result.pid = p.pid; result.buildId = p.image.buildId;
        result.modulePath = p.image.modulePath; result.moduleBias = p.bias;
        result.moduleDevice = p.image.moduleDevice; result.moduleInode = p.image.moduleInode;
        result.sites = p.image.sites;
        result.siteOffset = hex(p.image.site.vaddr); result.supported = true;
        result.active = p.validateMemory();
        result.activeKnown = true;
        result.owned = owned(p);
        if (result.active && !result.owned) throw Error("foreign_patch", "Matching replacement exists without this component's ownership record");
        if (command == "enable" && !result.active) {
            struct stat previous{};
            if (!result.owned && (lstat(stateFile(pipeline), &previous) == 0 || errno != ENOENT))
                throw Error("ownership_conflict", "Existing nonmatching ownership is retained");
            mutation(p, true, result.owned); result.active = result.owned = true; result.reason = "enabled";
        } else if (command == "disable" && result.active) {
            mutation(p, false, result.owned); result.active = result.owned = false; result.reason = "disabled";
        } else if (command == "disable") {
            if (result.owned && unlink(stateFile(result.pipeline)) && errno != ENOENT)
                fail("state_cleanup_failed", "Pipeline already restored but ownership cleanup failed");
            if (!result.owned && clearStaleOwnership(p)) result.detail = "Removed valid ownership of a confirmed obsolete process identity";
            result.owned = false; result.reason = "already_disabled";
        } else result.reason = result.active ? "active" : "ready";
        result.ok = true;
    } catch (const Error& e) {
        result.ok = false; result.reason = e.reason; result.detail = e.what(); result.error = e.err;
    } catch (const std::exception& e) {
        result.ok = false; result.reason = "internal_error"; result.detail = e.what();
    }
#ifndef SF17FIX_INSPECT_ONLY
    if (!result.ok && result.pid > 0) {
        // Report observed state, including an already-restored instruction when
        // ownership-file cleanup or thread-detach reporting failed afterwards.
        result.activeKnown = false;
        try {
            Process current(result.pid, result.pipeline);
            try { result.owned = owned(current); } catch (...) { /* Observe code independently of ownership errors. */ }
            result.active = current.validateMemory();
            result.activeKnown = true;
        } catch (...) { /* Preserve the original failure and explicit unknown flag. */ }
    }
#endif
    if (gDetachFailed) {
        result.ok = false; result.reason = "detach_failed";
        result.detail = "A ptrace cleanup error occurred; inspect SurfaceFlinger thread state";
    }
    gMetrics.totalMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - operationBegin).count();
    result.metrics = gMetrics;
    return result;
}
sfpolicy::EngineState brokerOperation(bool recording, bool enabled) {
    Pipeline pipeline = recording ? Pipeline::Recording : Pipeline::Screenshot;
    struct stat state{};
    auto result = operate(pipeline, enabled ? "status" : "disable");
    bool present = lstat(stateFile(pipeline), &state) == 0 || errno != ENOENT;
    if (enabled && result.ok && result.activeKnown && !result.active) {
        if (present && !result.owned) {
            result.ok = false; result.reason = "ownership_conflict";
            result.detail = "Existing nonmatching ownership is preserved; broker will not replace it";
        } else result = operate(pipeline, "enable");
        present = lstat(stateFile(pipeline), &state) == 0 || errno != ENOENT;
    }
    bool capability = !result.supported && (result.reason == "signature_not_found" || result.reason == "signature_ambiguous" ||
        result.reason.starts_with("unsupported_"));
    return {result.ok, result.supported, result.active, result.activeKnown, result.owned,
            present, capability && !present, result.reason, result.json()};
}
#endif
} // namespace
int main(int argc, char** argv) {
    Result result;
    try {
        std::string command = argc > 1 ? argv[1] : "status";
#ifndef SF17FIX_INSPECT_ONLY
        if (command == "policy-serve") {
            if (argc != 4) throw Error("usage", "policy-serve requires APPFILES MODULEDIR");
            return sfpolicy::serve(argv[2], argv[3], brokerOperation);
        }
        if (command == "direct-serve") {
            if (argc != 4) throw Error("usage", "direct-serve requires APPFILES MODULEDIR");
            return sfpolicy::serveDirect(argv[2], argv[3], brokerOperation);
        }
#endif
        if (command.starts_with("record-")) { result.pipeline = Pipeline::Recording; command.erase(0, 7); }
        if (command == "inspect") {
            if (argc != 3) throw Error("usage", "inspect requires an ELF path");
            Image i(argv[2], result.pipeline); result.supported = result.ok = true; result.reason = "signature_validated";
            result.sites = i.sites; result.buildId = i.buildId; result.siteOffset = hex(i.site.vaddr); result.print(); return 0;
        }
        if (command != "probe" && command != "status" && command != "enable" && command != "disable")
            throw Error("usage", "Expected [record-]probe, status, enable, disable, or inspect ELF");
#ifdef SF17FIX_INSPECT_ONLY
        throw Error("usage", "This host build supports only inspect and record-inspect");
#else
        if (geteuid() != 0) throw Error("root_required", "This executable must run in the root companion");
        bool mutate = command == "enable" || command == "disable";
        if (mutate) {
            if (mkdir(kStateDir, 0700) && errno != EEXIST) fail("state_write_failed", "Cannot create root companion state directory");
            struct stat st{};
            if (lstat(kStateDir, &st) || !S_ISDIR(st.st_mode) || st.st_uid != 0 || (st.st_mode & 022))
                throw Error("invalid_state", "Root companion state directory is not private");
        }
        Fd lockFd;
        if (mutate) {
            lockFd.value = open((std::string(kStateDir) + "/native.lock").c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
            if (lockFd.value < 0 || flock(lockFd.value, LOCK_EX | LOCK_NB)) fail("busy", "Another mutation is in progress");
        }
        result = operate(result.pipeline, command);
#endif
    } catch (const Error& e) {
        result.ok = false; result.reason = e.reason; result.detail = e.what(); result.error = e.err;
    } catch (const std::exception& e) {
        result.ok = false; result.reason = "internal_error"; result.detail = e.what();
    }
    if (gDetachFailed) {
        result.ok = false; result.reason = "detach_failed";
        result.detail = "A ptrace cleanup error occurred; inspect SurfaceFlinger thread state";
    }
    result.print(); return result.ok ? 0 : 20;
}
