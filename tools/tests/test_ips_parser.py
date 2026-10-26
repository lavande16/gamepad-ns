#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 GameFold Project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile the production IPS parser against an in-memory VFS and run regression cases."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]

VFS_ADAPTER = r"""
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
using u8 = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;
namespace Common { u16 swap16(u16 v) { return (v >> 8) | (v << 8); } }
namespace FileSys {
struct VectorVfsFile {
    std::vector<u8> bytes;
    std::string name;
    VectorVfsFile(std::vector<u8> data, std::string filename = "memory",
                  std::nullptr_t = nullptr) : bytes(std::move(data)), name(std::move(filename)) {}
    std::vector<u8> ReadAllBytes() const { return bytes; }
    std::vector<u8> ReadBytes(size_t n) const {
        return {bytes.begin(), bytes.begin() + std::min(n, bytes.size())};
    }
    size_t Read(void* destination, size_t n, u64 offset) const {
        if (offset >= bytes.size()) return 0;
        const auto size = std::min(n, bytes.size() - static_cast<size_t>(offset));
        std::memcpy(destination, bytes.data() + offset, size);
        return size;
    }
    template <class T> size_t ReadObject(T* value, u64 offset) const {
        return Read(value, sizeof(T), offset);
    }
    std::optional<u8> ReadByte(u64 offset) const {
        if (offset >= bytes.size()) return {};
        return bytes[offset];
    }
    std::string GetName() const { return name; }
    std::nullptr_t GetContainingDirectory() const { return nullptr; }
};
using VirtualFile = std::shared_ptr<VectorVfsFile>;
"""

CASES = r"""
using FileSys::PatchIPS;
using FileSys::VectorVfsFile;
using FileSys::VirtualFile;

VirtualFile Executable(size_t size = 32) {
    std::vector<u8> data(size, 0xa5);
    std::memcpy(data.data(), "NSO0", 4);
    return std::make_shared<VectorVfsFile>(std::move(data), "main");
}
VirtualFile Patch(std::vector<u8> data) {
    return std::make_shared<VectorVfsFile>(std::move(data), "test.ips");
}
void Check(bool success, const char* label) {
    if (!success) throw std::runtime_error(label);
    std::cout << "PASS " << label << '\n';
}
int main() {
    auto original = Executable();
    auto standard = Patch({'P','A','T','C','H', 0,0,8, 0,4, 1,2,3,4, 'E','O','F'});
    auto result = PatchIPS(original, standard);
    Check(result && result->bytes[8] == 1 && result->bytes[11] == 4,
          "IPS reads patch header and accepts exact 3-byte EOF");
    Check(original->bytes[8] == 0xa5 && result->bytes[0] == 'N',
          "patching preserves input buffer and executable header");

    auto ips32 = Patch({'I','P','S','3','2', 0,0,0,8, 0,2, 0x42,0x43, 'E','E','O','F'});
    result = PatchIPS(original, ips32);
    Check(result && result->bytes[8] == 0x42 && result->bytes[9] == 0x43,
          "IPS32 accepts exact 4-byte EEOF");

    auto rle = Patch({'P','A','T','C','H', 0,0,8, 0,0, 0,3, 0, 'E','O','F'});
    result = PatchIPS(original, rle);
    Check(result && result->bytes[8] == 0 && result->bytes[10] == 0 &&
              result->bytes[11] == 0xa5, "RLE supports zero-valued replacement bytes");

    Check(!PatchIPS(original, Patch({'N','S','O','0',0})), "invalid patch magic is rejected");
    Check(!PatchIPS(original, Patch({'P','A','T'})), "truncated patch header is rejected");
    Check(!PatchIPS(original, Patch({'P','A','T','C','H',0,0,8,0,4,1,2})),
          "truncated record payload is rejected");
    Check(!PatchIPS(original, Patch({'P','A','T','C','H',0,0,8,0,1,1})),
          "missing EOF is rejected");
    Check(!PatchIPS(nullptr, standard) && !PatchIPS(original, nullptr),
          "null inputs are rejected");

    // The 26-byte 60 FPS patch read from the user's NFS 1.0.3 installation.
    auto nfs_patch = Patch({'P','A','T','C','H', 0x3e,0x01,0xac,0,4,0x08,0x10,0x2c,0x1e,
                           0x7e,0xc0,0x2c,0,4,0x21,0,0x80,0x52, 'E','O','F'});
    result = PatchIPS(Executable(0x800000), nfs_patch);
    const std::array<u8,4> expected1{0x08,0x10,0x2c,0x1e};
    const std::array<u8,4> expected2{0x21,0,0x80,0x52};
    Check(result && std::equal(expected1.begin(), expected1.end(), result->bytes.begin()+0x3e01ac)
              && std::equal(expected2.begin(), expected2.end(), result->bytes.begin()+0x7ec02c),
          "both real NFS 60 FPS patch records are applied");
    std::cout << "10 IPS parser regression cases passed\n";
}
"""


def main():
    source = (ROOT / "src/core/file_sys/ips_layer.cpp").read_text()
    start = source.index("enum class IPSFileType")
    end = source.index("\nstruct IPSwitchRecord", start)
    production_parser = source[start:end]
    compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("c++")
    if not compiler:
        raise SystemExit("A C++20 compiler is required")
    with tempfile.TemporaryDirectory(prefix="gamepad-ips-test-") as temporary:
        directory = Path(temporary)
        unit = directory / "ips_parser_test.cpp"
        binary = directory / "ips_parser_test"
        unit.write_text(VFS_ADAPTER + production_parser + "\n}\n" + CASES)
        subprocess.run([compiler, "-std=c++20", "-O0", "-Wall", "-Wextra", str(unit),
                        "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
