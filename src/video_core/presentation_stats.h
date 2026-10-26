// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <limits>
#include <iterator>
#include <map>
#include <mutex>
#include <set>
#if !defined(_WIN32)
#include <time.h>
#endif

namespace VideoCore::Presentation {

// Vulkan actualPresentTime uses CLOCK_MONOTONIC on Android/Linux. Do not use a
// std::chrono epoch here (in particular, macOS steady_clock is a different clock).
inline std::uint64_t MonotonicNs() {
#if !defined(_WIN32)
    timespec time{};
    if (clock_gettime(CLOCK_MONOTONIC, &time) == 0) {
        return static_cast<std::uint64_t>(time.tv_sec) * 1'000'000'000 + time.tv_nsec;
    }
#endif
    return 0; // An unavailable/unknown clock domain cannot provide measurement.
}

enum class State { Unsupported, Warmup, Valid, Inactive, Stale, Failed };
struct Snapshot {
    bool valid{};
    double fps{};
    std::uint64_t first{}, last{}, count{}, epoch{};
    State state{State::Unsupported};
};

// Zero means exhausted. Never reuse IDs on the same swapchain: arbitrarily late
// feedback could otherwise alias the first IDs after uint32 wrap.
class PresentIds {
public:
    explicit PresentIds(std::uint32_t last = 0) : value{last} {}
    std::uint32_t Next() {
        if (value == std::numeric_limits<std::uint32_t>::max()) return 0;
        return ++value;
    }
private:
    std::uint32_t value{};
};

// Owns only value data. JNI never retains a renderer, swapchain or session pointer.
// Every method releases this mutex before Vulkan, session locks, or rendering waits.
// Explicit synthetic times make this same class testable without a Vulkan device.
class Statistics {
public:
    std::uint64_t BeginSwapchain(bool available) {
        std::scoped_lock lock{mutex};
        ++generation;
        Reset();
        ids = PresentIds{};
        support = available;
        failed = false;
        return generation;
    }
    void EndSwapchain(std::uint64_t owner) {
        std::scoped_lock lock{mutex};
        if (owner != generation) return;
        Reset();
        support = false;
    }
    void SetRunning(bool value) {
        std::scoped_lock lock{mutex};
        running = value;
        Reset();
    }
    void SetSurface(bool value) {
        std::scoped_lock lock{mutex};
        surface = value;
        Reset();
    }
    std::uint64_t Epoch() const {
        std::scoped_lock lock{mutex};
        return running && surface ? epoch : 0;
    }
    std::uint32_t Issue(std::uint64_t owner, std::uint64_t queued_epoch,
                        std::uint64_t now) {
        std::scoped_lock lock{mutex};
        if (owner != generation || !support) return 0;
        const auto id = ids.Next();
        if (id == 0 || now == 0) {
            Disable();
            return 0;
        }
        // IDs are attached to ALL original/generated presents, including those
        // excluded from measurement because they were queued before a reset.
        if (queued_epoch != epoch || !running || !surface) return id;
        while (!pending.empty() && now > pending.begin()->second &&
               now - pending.begin()->second > 2 * WindowNs) {
            pending.erase(pending.begin());
        }
        if (pending.size() >= MaxPending) {
            Disable(); // Missing feedback cannot silently become a lower fake FPS.
            return id;
        }
        pending.emplace(id, now);
        return id;
    }
    // Called only from the present owner after consuming actual timing history.
    void Record(std::uint64_t owner, std::uint32_t id, std::uint64_t actual,
                std::uint64_t now) {
        std::scoped_lock lock{mutex};
        if (owner != generation || !support || !running || !surface) return;
        const auto found = pending.find(id);
        if (found == pending.end()) return; // duplicate, old epoch, or unknown ID
        const auto submitted = found->second;
        pending.erase(found);
        if (actual == 0 || actual > now || actual < submitted || now == 0) {
            Reset(); // Bad driver data invalidates the entire previous reading.
            return;
        }
        Trim(now);
        if (now - actual > WindowNs) return;
        timestamps.insert(actual); // Different IDs with identical actual times count once.
        if (timestamps.size() > MaxSamples) Disable();
    }
    void Fail(std::uint64_t owner) {
        std::scoped_lock lock{mutex};
        if (owner == generation) Disable();
    }
    Snapshot Read(std::uint64_t now) {
        std::scoped_lock lock{mutex};
        Snapshot result;
        result.epoch = epoch;
        if (!support) {
            result.state = failed ? State::Failed : State::Unsupported;
            return result;
        }
        if (!running || !surface) {
            result.state = State::Inactive;
            return result;
        }
        Trim(now);
        result.state = State::Warmup;
        if (timestamps.empty()) return result;
        // Restrict to the current uninterrupted group after a long stall. Do not
        // destructively remove earlier entries here: later out-of-order batches
        // may supply missing timestamps between them.
        auto first = timestamps.begin();
        auto previous = first;
        for (auto it = std::next(first); it != timestamps.end(); ++it) {
            if (*it - *previous > StaleNs) first = it;
            previous = it;
        }
        result.first = *first;
        result.last = *timestamps.rbegin();
        result.count = static_cast<std::uint64_t>(std::distance(first, timestamps.end()));
        if (now < result.last || now - result.last > StaleNs) {
            result.state = State::Stale;
            return result;
        }
        // A couple of fresh frames after a stall are insufficient evidence.
        if (result.count < 4 || result.last - result.first < MinimumSpanNs) return result;
        result.fps = static_cast<double>(result.count - 1) * 1'000'000'000.0 /
                     static_cast<double>(result.last - result.first);
        result.valid = true;
        result.state = State::Valid;
        return result;
    }
private:
    void Reset() {
        ++epoch;
        timestamps.clear();
        pending.clear();
    }
    void Disable() {
        Reset();
        support = false;
        failed = true;
    }
    void Trim(std::uint64_t now) {
        while (!timestamps.empty() && now > *timestamps.begin() &&
               now - *timestamps.begin() > WindowNs) timestamps.erase(timestamps.begin());
    }
    static constexpr std::uint64_t WindowNs = 1'000'000'000;
    static constexpr std::uint64_t StaleNs = 500'000'000;
    static constexpr std::uint64_t MinimumSpanNs = 250'000'000;
    static constexpr std::size_t MaxPending = 2048;
    static constexpr std::size_t MaxSamples = 1024;
    mutable std::mutex mutex;
    std::uint64_t generation{}, epoch{1};
    bool running{}, surface{true}, support{}, failed{};
    PresentIds ids;
    std::map<std::uint32_t, std::uint64_t> pending;
    std::set<std::uint64_t> timestamps;
};

inline Statistics& GetStatistics() {
    static Statistics stats;
    return stats;
}
} // namespace VideoCore::Presentation
