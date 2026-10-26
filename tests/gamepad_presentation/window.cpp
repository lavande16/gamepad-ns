// SPDX-License-Identifier: GPL-3.0-or-later
#include "video_core/presentation_stats.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <thread>
#include <atomic>
using VideoCore::Presentation::Statistics;
using VideoCore::Presentation::Snapshot;
using U64 = std::uint64_t;
constexpr U64 second = 1'000'000'000;
int checks = 0;
void check(bool ok) { ++checks; if (!ok) std::cerr << "FAIL assertion " << checks << "\n"; assert(ok); }
void rate(const Snapshot& s, double expected) {
    check(s.valid);
    check(std::abs(s.fps - expected) < .001);
}
struct Fixture {
    Statistics stats;
    U64 generation;
    Fixture() : generation(stats.BeginSwapchain(true)) { stats.SetRunning(true); }
    void add(U64 time) {
        auto id = stats.Issue(generation, stats.Epoch(), time - 1);
        stats.Record(generation, id, time, time);
    }
    void fill(U64 start = second, int hz = 60, int intervals = 60) {
        for (int i=0; i<=intervals; ++i) add(start + second * i / hz);
    }
};
int main() {
    // Missing actual feedback must never become queue-count or multiplied FPS.
    Fixture a;
    check(!a.stats.Read(second).valid);
    for (int i=0; i<120; ++i) a.stats.Issue(a.generation, a.stats.Epoch(), second);
    check(!a.stats.Read(second).valid);
    a.fill();
    rate(a.stats.Read(2*second), 60);
    check(a.stats.Read(2*second).count == 61);
    Fixture b; b.fill(second,120,120); rate(b.stats.Read(2*second),120);
    // Dropped presents have IDs but no timestamps: count only displayed output.
    Fixture dropped;
    for(int i=0;i<=120;++i) {
        auto t=second+second*i/120;
        auto id=dropped.stats.Issue(dropped.generation,dropped.stats.Epoch(),t-1);
        if(i%2==0) dropped.stats.Record(dropped.generation,id,t,t);
    }
    rate(dropped.stats.Read(2*second),60);
    // Duplicate ID and timestamp feedback must not increase rate.
    auto id=a.stats.Issue(a.generation,a.stats.Epoch(),2*second-1);
    a.stats.Record(a.generation,id,2*second,2*second);
    a.stats.Record(a.generation,id,2*second,2*second);
    rate(a.stats.Read(2*second),60);
    // Out-of-order records and batches are retained and sorted by actual time.
    Fixture reverse;
    std::uint32_t ids[61];
    for(int i=0;i<=60;++i) ids[i]=reverse.stats.Issue(reverse.generation,reverse.stats.Epoch(),second-1);
    for(int i=60;i>=0;--i) reverse.stats.Record(reverse.generation,ids[i],second+second*i/60,2*second);
    rate(reverse.stats.Read(2*second),60);
    check(reverse.stats.Read(2*second).first == second);
    // Rolling windows trim against now, not the last feedback timestamp.
    a.fill(2*second,60,60);
    auto trimmed=a.stats.Read(3*second);
    rate(trimmed,60); check(trimmed.first==2*second); check(trimmed.count==61);
    check(!a.stats.Read(4*second).valid);
    // Stalls need enough NEW samples before returning a rate.
    a.add(5*second); a.add(5*second+second/60);
    check(!a.stats.Read(5*second+second/60).valid);
    a.fill(5*second,60,30); rate(a.stats.Read(5*second+second/2),60);
    // Invalid driver data clears a formerly valid result; never overflows.
    for(auto bad : {U64{0}, std::numeric_limits<U64>::max(),3*second}) {
        Fixture invalid; invalid.fill();
        auto bad_id=invalid.stats.Issue(invalid.generation,invalid.stats.Epoch(),2*second-1);
        invalid.stats.Record(invalid.generation,bad_id,bad,2*second);
        check(!invalid.stats.Read(2*second).valid);
    }
    Fixture before_submit; before_submit.fill();
    auto early=before_submit.stats.Issue(before_submit.generation,before_submit.stats.Epoch(),2*second);
    before_submit.stats.Record(before_submit.generation,early,second,2*second);
    check(!before_submit.stats.Read(2*second).valid);
    // Pause/resume rejects both outstanding feedback and queued old frames.
    Fixture paused; paused.fill();
    auto old_epoch=paused.stats.Epoch();
    auto pending=paused.stats.Issue(paused.generation,old_epoch,2*second);
    paused.stats.SetRunning(false); check(!paused.stats.Read(2*second).valid);
    paused.stats.SetRunning(true);
    paused.stats.Record(paused.generation,pending,2*second+1,2*second+1);
    auto queued=paused.stats.Issue(paused.generation,old_epoch,2*second);
    paused.stats.Record(paused.generation,queued,2*second+1,2*second+1);
    check(!paused.stats.Read(2*second+1).valid);
    paused.fill(3*second); rate(paused.stats.Read(4*second),60);
    paused.stats.SetSurface(false); check(!paused.stats.Read(4*second).valid);
    paused.stats.SetSurface(true); check(!paused.stats.Read(4*second).valid);
    // Old swapchain IDs/epochs cannot leak into a new swapchain generation.
    Fixture recreated; recreated.fill();
    auto old_gen=recreated.generation; auto old=recreated.stats.Epoch();
    recreated.generation=recreated.stats.BeginSwapchain(true);
    recreated.stats.Record(old_gen,1,2*second,2*second);
    auto stale=recreated.stats.Issue(recreated.generation,old,2*second);
    recreated.stats.Record(recreated.generation,stale,2*second+1,2*second+1);
    check(!recreated.stats.Read(2*second+1).valid);
    recreated.fill(3*second); rate(recreated.stats.Read(4*second),60);
    recreated.stats.EndSwapchain(old_gen); rate(recreated.stats.Read(4*second),60);
    recreated.stats.EndSwapchain(recreated.generation); check(!recreated.stats.Read(4*second).valid);
    Fixture failed; failed.fill(); failed.stats.Fail(failed.generation);
    check(!failed.stats.Read(2*second).valid);
    failed.fill(3*second); check(!failed.stats.Read(4*second).valid);
    failed.generation=failed.stats.BeginSwapchain(false);
    failed.fill(5*second); check(!failed.stats.Read(6*second).valid);
    // IDs never wrap/reuse within a live swapchain; wrapping would alias delayed history.
    VideoCore::Presentation::PresentIds wrap(std::numeric_limits<std::uint32_t>::max()-1);
    check(wrap.Next()==std::numeric_limits<std::uint32_t>::max());
    check(wrap.Next()==0); check(wrap.Next()==0);
    // Delayed history and bounded-capacity eviction cannot revive a stale result.
    Fixture delayed; delayed.fill();
    auto delayed_id=delayed.stats.Issue(delayed.generation,delayed.stats.Epoch(),2*second);
    delayed.stats.Record(delayed.generation,delayed_id,2*second+1,4*second);
    check(!delayed.stats.Read(4*second).valid);
    for(int i=0;i<5000;++i) delayed.stats.Issue(delayed.generation,delayed.stats.Epoch(),5*second);
    delayed.stats.Record(delayed.generation,delayed_id,5*second+1,5*second+1);
    check(!delayed.stats.Read(5*second+1).valid);
    // Recent sparse frames after a sub-second stall must not revive the older group.
    Fixture gap;
    gap.fill(second,60,18);
    gap.add(1'950'000'000); gap.add(2*second);
    check(!gap.stats.Read(2*second).valid);
    // Feedback after a new generation works, even after the previous query failed.
    failed.generation=failed.stats.BeginSwapchain(true);
    failed.fill(7*second); rate(failed.stats.Read(8*second),60);
    // Slow actual cadence is measured without multiplying it by generated submissions.
    Fixture slow; slow.fill(second,10,10); rate(slow.stats.Read(2*second),10);
    // Concurrent JNI reads and lifecycle resets touch only this independently owned data.
    Fixture concurrent;
    std::atomic<bool> done=false;
    std::atomic<bool> broken=false;
    std::thread reader([&] {
        while (!done.load()) {
            const auto sample=concurrent.stats.Read(2*second);
            if (sample.valid && (!std::isfinite(sample.fps) || sample.count<4 ||
                sample.last<=sample.first)) broken=true;
        }
    });
    for(int run=0;run<100;++run) {
        concurrent.stats.SetRunning(false);
        concurrent.stats.SetRunning(true);
        concurrent.fill();
    }
    done=true; reader.join(); check(!broken.load());
    std::cout << "PASS " << checks << " presentation assertions\n";
}
