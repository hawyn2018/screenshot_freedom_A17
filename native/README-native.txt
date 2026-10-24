Screenshot Freedom A17 3.5.9 native companion
======================================

Build and artifact
------------------
Run: python tools/native_build.py
Environment: ANDROID_NDK_HOME / ANDROID_NDK_ROOT / NDK_HOME, or an installed
NDK below ANDROID_SDK_ROOT/ndk (Windows default D:\AndroidSDK/ndk).
Verified build: NDK r29, Windows x86_64, Clang, -Wall -Wextra -Werror.
Artifact: native/build/arm64-v8a/libsf17fix.so
This is an AArch64 PIE executable named .so for APK asset transport, not a JNI
library. It uses static libc++, has no libc++_shared.so dependency, and requests
16 KB ELF load alignment. Build metadata and ELF/symbol inspection are emitted
under native/build/. CMakeLists.txt is also provided for NDK CMake builds.

The application/build agent owns packaging assets and native/root-module/.
This native component does not edit the target app, its scope, or its files.

CLI protocol
------------
Screenshot root commands: probe | status | enable | disable
Recording root commands: record-probe | record-status | record-enable | record-disable
Offline commands: inspect ELF_PATH | record-inspect ELF_PATH
Exactly one JSON line is written to stdout. Exit 0 means ok=true; exit 20 means
the operation did not complete successfully. schema=1 fields:
  ok, supported, active, active_known, owned, reason, detail,
  pid, build_id, site_offset, errno.
3.2 also reports module_path, module_bias, module_device and module_inode for a
live selected ELF. The inspect-only form has no live mapping identity.
3.5.9 adds file_access with cache/cache_detail/selected_strategy and bounded
attempts containing strategy, maps/file identities, stage and failure reasons.
Attempts use access_reason/access_detail/access_errno so existing shell parsers
keep reading the top-level result. They are limited to 128 entries and 12 KiB
after JSON escaping; attempts_truncated reports omitted entries.
3.0 adds pipeline="screenshot" or "recording", site_count, and a sites array
(offset/function_start/function_end/original/replacement). Unknown commands return
reason=usage and exit 20; unknown record-* commands retain pipeline="recording".
An active=true result means ALL pipeline replacement instructions are observed;
owned=true separately requires a matching private ownership record.
active_known=false means observation failed; it is not proof of disabled state.
Both pipelines' probe/status never attach, stop, or write to SurfaceFlinger.
Both inspect commands only parse the specified ELF; they need no live process or root.
Each command invokes ONLY its own matcher: unsupported/ambiguous recording code
does not disable the screenshot capability, and conversely.
enable/disable are idempotent after validating the current memory and ownership.

Policy broker (optional observed Java capture sessions)
---------------------------------------------
Daemon: policy-serve APPFILES MODULEDIR
One abstract AF_UNIX stream socket: hawyn.screenshotcompat.policy.v1
The broker is the sole holder of native.lock for its entire lifetime. Standalone
enable/disable commands return busy while it runs; probe/status remain read-only.
MODULEDIR/broker.pid contains PID and process start ticks. While arbitration is
enabled, the root service only supervises/guards the broker; it never competes
for desired-state writes or enables a patch behind the broker.

The private single-value sf17fix-arbitration.conf controls list arbitration.
Missing configuration preserves the enabled upgrade default. When explicitly
zero, the service stops/restores the broker, creates no policy socket or leases,
and uses the original independently validated one-shot screenshot/recording CLI
according to the two SKIP masters. It reports running=false/allow_all_tools=true;
ready=false in that mode is not an unready running broker. Switching modes first
restores the previous owned writes. Disabling/removing the root component stops
the broker and restores both native pipelines. Java FLAG_SECURE compatibility is
separate and does not require a native SKIP patch or a running policy broker.

Only UID 1000 or system-host UIDs from root-private, nofollow
MODULEDIR/policy-system-uids.conf may send session requests. That file contains
one decimal UID per line, at most 1024 lines / 8 KiB. UID 0 may only observe status.
Java clients independently verify that the server's SO_PEERCRED UID is zero.

Requests are ASCII lines terminated by newline (at most 512 bytes):
  READY generation
  BEGIN token SCREENSHOT|PROJECTION ALLOW|BLOCK ownerUid
  END token
  STATUS
  PING
Token/generation characters: A-Z, a-z, 0-9, _ and -, length 16..96.
READY is UID-1000-only, on a separate persistent controller connection. Java must
first verify no pre-existing VD or incomplete screenshot session. The Java
inventory requires the entire VirtualDisplayAdapter device map to be empty,
including casting and other non-recording virtual displays. Native also
rejects READY while any tracked session remains. The broker starts unready with
both pipelines restored, and never declares itself ready automatically.
Each BEGIN uses its own persistent connection, kept until END after the actual
screenshot callback or confirmed virtual-display destruction/commit. Other host
UIDs may only use their own ownerUid; UID 1000 may convey a verified caller UID.
Each token is additionally bound to peer UID/PID/start time and its connection.

BLOCK is registered before restoration. Both managed pipelines are restored and
their complete functions verified before an affirmative BLOCK/paused ACK. An
ALLOW session may run with compatibility paused while any BLOCK exists. Multiple
BLOCK sessions require matching ENDs individually. Disconnect without END makes
that session an orphan BLOCK. Controller disconnect makes the broker unready.
Neither disconnection nor elapsed time silently unblocks compatibility. Lost
callbacks therefore conservatively require confirmed lifecycle recovery. An
orphan BLOCK is not cleared by refreshing the app or by waiting. After stopping
all capture and virtual-display outputs, reboot to reinitialize both the Java
bridge and root arbitration. Restarting only the root component can still leave
an incomplete Java screenshot lease blocking the empty-inventory handshake.

Replies are one JSON line, schema=1, pipeline=policy, running=true. They include ok/reason/epoch,
session_id, ready/paused/blockers and identical aliases
policy_ready/policy_paused/blocked_count, sessions, per-pipeline managed/active/
original_verified fields, coverage, original_verified, restored_or_unmanaged.
original_verified strictly means BOTH pipelines actually verified original code.
restored_or_unmanaged permits the independent capability exception: a pipeline
with exact signature/unsupported capability failure and no ownership file is
unmanaged; its native supported=false/active_known=false status stays unknown.
Foreign changes, partial patches, ownership and read errors get no exemption.
BEGIN BLOCK/paused clients must check restored_or_unmanaged; an error or absent
ACK must fail the current capture before its original operation is called.

The broker rereads both original single-value CONF files before each operation
and periodically. Missing configuration keeps original code; empty/malformed
configuration makes the broker unready and restores. App first-launch defaults
are written by the app as explicit 1 values, rather than guessed by this daemon.
Original pipeline JSON files continue to be published under MODULEDIR and APPFILES,
with extra policy fields; policy-status.json / sf17fix-policy-status.json provide
the aggregate state. Publication uses directory FDs, exclusive nofollow temporary
files, descriptor metadata/SELinux labeling, and atomic renameat.

Only an incomplete command has a 2-second read limit. Idle session/controller
connections have no timeout; reply writes are bounded to 1 second. A Java response
timeout must allow the existing bounded two-pipeline restoration (10 seconds is
recommended). SIGTERM stops accepting work, lets the current bounded operation
complete/roll back, restores both pipelines and exits. The guardian must not
SIGKILL a mutation: abrupt death can leave a partial owned group that is explicitly
reported rather than silently repaired by the ordinary disable command. After
an abnormal exit the guardian independently restores/verifies, reports failure
when certainty is absent, and does not enable patches itself. A new broker must
receive a fresh system_server READY before applying desired compatibility again.
An engine detach_failed causes the broker to exit promptly, so kernel tracer
cleanup provides the same final detach guarantee as the original one-shot CLI.

This is conservative aggregate arbitration for fully observed standard Java
ScreenCapture/MediaProjection paths. It is not per-output isolation: exclusion
wins during concurrent allowed and blocked capture sessions. Native tools that
call SurfaceFlinger directly are outside this Java session-observation boundary.

The policy-serve executable reads app-maintained desired state and publishes
observed JSON; while this optional arbitration mode is enabled the root shell
service only supervises that broker and restores on exit. Installation/launch success is separate from READY and actual patch
state. The one-shot CLI does not start a daemon, and neither mode creates an
Android property.

Mapped ELF discovery and cached identity
---------------------------------------
SurfaceFlinger may contain its implementation in the executable or in a mapped
shared ELF. Discovery inventories the process's executable file-backed mappings,
without a path/library-name whitelist or a model/version/build-ID selector. Each
pipeline independently requires one semantic implementation across ALL candidates.
Two independently valid modules, duplicate loads, inaccessible mapped files or an
incomplete anonymous/deleted executable inventory produce explicit diagnostics.
There is no fallback to a guessed library or the first plausible match.

The access selector first tries a cached, previously successful strategy, then
the kernel's actual /proc/PID/exe, exact /proc/PID/map_files/LO-HI link, the
process-root path and the absolute path. Magic-link following is confined to a
verified procfs PID directory and these generated links, with matching logical
paths, PID/start and full executable mapping epoch checked around discovery.
An exact mapped-file FD can expose another stat device/inode on stacked mounts;
its independent stat identity is checked before/after each bounded pread. The
original maps identity remains the ownership identity. Ordinary path fallbacks
still require strict maps/stat device+inode equality and final O_NOFOLLOW.

The load bias is derived from executable PT_LOAD file/virtual addresses,
with the actual page size; the entire enclosing function must lie in executable
PT_LOAD and readable/executable mappings at the corresponding file offsets.
The executable's resolved identity and process PID/start time are also checked.

The persistent broker caches discovery per pipeline/PID/start and the complete
executable-module mapping epoch plus file metadata. A warm operation stats the
inventory but reads only the selected ELF, compares its complete bytes with the
cached identity, and revalidates PT_LOAD/mappings. Mapping or file metadata changes
cause fresh discovery. A cold audit is bounded to 1024 executable modules and
512 MiB of files; individual ELF size is limited to 64 MiB. Cached unsupported
results retain the same identity invalidation rules.

Successful access strategies are persisted in the root-private
/data/adb/enable_screenshot17fix/compat-cache/access.cache. Schema, executable
SHA-256, ordered logical paths, selected strategies, complete ELF digests and a
record checksum are validated; no PID, VMA or ASLR address is serialized.
This is a route hint, not a capability grant. Every reopening validates current
process, mapping and file identity, and the ordinary decoder/live-code checks
remain mandatory. Failed or stale routes are tried again through the selector.
The expensive recording CFG proof cache is retained separately. Both pipelines
keep their in-process discovery cache; a warm status operation never rehashes
all ELF files. Candidate ELF FDs are closed promptly; only a proc PID directory
FD is shared by each retained inventory. Cache persistence failures are optional
diagnostics and do not waive any verification.

Caching never bypasses the pre/post memory and stopped-process verification.
Persistent ownership binds only the SELECTED module, so an unrelated later dlopen
does not invalidate an otherwise matching owned patch. The operation itself
aborts if its mapping epoch changes while it is inspecting/writing.

Original executable ELF and matching proof
------------------------------
The first two tested SurfaceFlinger executables are stripped: .dynsym has no internal
createScreenCaptureOutput/setLayerFilter symbols, and there is no .symtab.
The helper therefore parses ELF64 + GNU build ID + sorted .eh_frame_hdr entries.
It requires little-endian AArch64 ET_DYN and a valid executable load segment.
It does not match a model, OS-version number, fixed build ID, PID or address.

The accepted pattern is the screenshot-output factory's source-level sequence:
  create output; set secure/protected state from buffer usage; enable composition;
  combine a 32-bit LayerStack with the boolean skipScreenshot bit at bit 40;
  virtual setLayerFilter; set render surface/brightness/size/projection/name.
The decoder checks the LayerStack load, ORR argument, virtual-call relationship, all
listed independent semantic guards and the enclosing unwind-function boundary.
State access supports both the original direct virtual call and an audited
virtual-base-adjustment chain. In the latter, object/table/offset/method registers
are tracked by role; secure and protected byte stores must use the same derived
state-access slot. Neither that slot nor those temporary registers is a device
constant. Other operations retain their reviewed compiler-profile contracts.
Exactly one accepted candidate is mandatory. Unknown compiler layouts fail with
signature_not_found; multiple candidates fail with signature_ambiguous.
These checks are a supported binary contract, not a claim of universal ABI
compatibility. Future firmware can be supported through a separately reviewed
decoder extension; the helper never falls back to a broad or unverified patch.

On the tested ELF the dynamically found instruction is:
  file vaddr 0x275688: ORR X1, X9, #0x10000000000
  replacement:         MOV X1, X9
This removes only the screenshot output's skip boolean and keeps the LayerStack.
The addresses/build ID here are evidence, not constants used by the program.
It does not clear any application's real layer flags or change physical-display
composition, secure policy, protected-buffer policy, UID or exclusion mask.

Recording mirrored-input proof and compiler clones
-------------------------------------------------
Standard display/virtual-display outputs normally have LayerFilter.skipScreenshot
false, while ScreenCaptureOutput uses true. Clearing the screenshot output bit
therefore does not change the mirrored-input exclusion used by recording.

LayerSnapshotBuilder propagates handleSkipScreenshotFlag=true into mirror
hierarchies. LayerSnapshot::getIsVisible then rejects a snapshot when BOTH that
flag and outputFilter.skipScreenshot are true, before the virtual display's
output filter is consulted. The source-layer outputFilter.skipScreenshot flag
is retained, preserving the separate screenshot-output exclusion policy.

The recording decoder first uniquely links the exact eLayerSkipScreenshot return
string to getIsVisibleReason. Its reachability, parent/relative-parent, invalid
transform and drawing-reason paths identify the semantic context and derive the
handle/skip/reachability/hidden field offsets. The offsets, function addresses,
PID/build ID/model/version are never selectors.

The first two observed binaries contain ONE complete boolean getIsVisible function and
TWO inlined clones. Patching just the separate function misses the hot inline
paths. The matcher does not require this particular clone count: it enumerates
and classifies every occurrence of the derived handle field, with an explicit
1..16 mutation-site resource bound. The decoder
checks the handle load, compare, conjunction branch, original rejection and
continuation targets, reachability, matching draw/alpha helper calls and complete
hidden-policy chains. Indexed-byte and direct-byte compiler profiles are
independently checked. The direct profile also derives and cross-checks the
vendor hidden condition, alpha member, radius/region blur and background-state
fields; these additional visibility restrictions are preserved.
Every handle-field use must be classified as a verified visibility decision,
the unique diagnostic, or a verified inheritance operation that remains intact.
Direct skip-flag guards are audited independently so removal of a handle load
does not silently hide an active exclusion branch. Additional fully classified
inline clones are included, rather than rejected solely for exceeding three.
A second independently complete boolean implementation or incompatible profiles
report signature_ambiguous. Missing, changed or unclassified
semantics report signature_not_found. A changed compiler profile needs a reviewed
decoder extension; the engine has no version/model gate or broad fallback.

Observed ELF evidence (not addresses embedded in the decoder):
  0x1fdf3c: LDRB W8,[X20,X8]   in 0x1fddc0..0x1fe90c
  0x1fe190: LDRB W8,[X20,X8]   in the same enclosing function
  0x1fe928: LDRB W8,[X0,X8]    in 0x1fe90c..0x1fe9e0
Second observed compiler profile (separate ELF, not a device selector):
  screenshot 0x3d75e8: ORR X1,X9,#0x10000000000 -> MOV X1,X9
  recording  0x326cdc / 0x326dac: LDRB W8,[X8,#derived_handle]
  recording  0x5c34c4: LDRB W8,[X0,#derived_handle]
Its five handle reads comprise these three decisions, one diagnostic and one
parent-to-child inheritance operation. The latter two are intentionally unchanged.
Each replacement is MOV W8,WZR. This substitutes false ONLY for the local
handle flag immediately consumed by CMP W8,#1; the conditional skip test is
bypassed. No application/snapshot field is written. Reachability, drawing,
invalid transform, hidden parent/relative parent, alpha and blur checks remain.
The read-only diagnostic reason function is intentionally untouched, so a dump
reason string alone is not an authoritative observation of patch state.

This policy applies to mirror hierarchies, including recording/casting paths.
It does not independently make a virtual display secure; FLAG_SECURE recording
support is a separate Java/framework setting. Changes affect future visibility
calculations. Stop and reopen a recording to recreate its mirror hierarchy and
secure-output configuration. active=true reports verified code, not a captured
frame or an assertion that protected buffers can be rendered.

Exported-identity matching proof
-------------------------------
Additional exported-identity compiler shape (shared or executable ELF):
  screenshot factory 0x4ec280..0x4ec7b4, site 0x4ec55c, ORR X1,X9,bit40 -> MOV X1,X9
  visibility getter  0x40db90..0x40dcd8, site 0x40dba0, LDRB W8,[X0,X8] -> MOV W8,WZR
  inline visibility  0x42caf8..0x42cfd8, site 0x42cc7c, LDRB W8,[X23,#0x1dc] -> MOV W8,WZR
These are observed evidence addresses, never selectors. Exported standard C++
function/vtable identities are only anchors. ELF RELA and Android APS2 relocations
link the actual constructed ScreenCaptureOutput vtable address point to the
LayerFilter setter. Slots and construction offsets are derived from that chain,
not copied from earlier devices. The setter must store packed stack/flags;
secure/protected stores must retain matching virtual-base state access and usage
bit14 semantics, and composition/render-surface/brightness/size/projection/name
calls must resolve to the expected relocated identities.

The exported visibility diagnostic derives handle/skip/reach/hidden offsets,
including an affine-biased base. The canonical getter must prove the isolated
handle==1 && skip-bit0 rejection, the original false return and continuation,
the hasEffect helper identity through its standard PLT relocation, matching
reach/hidden fields, and unchanged half-alpha/blur body. Every occurrence of the
derived immediate handle constant and direct/biased byte reads is audited.
Actual unwind-bounded control-flow predecessors are used, including branches
that skip a physically earlier return epilogue. Physical backwards instruction
order alone is not a reaching-definition proof. Shifted/chained ADD/SUB, constant
register ADD, MOV aliases, unscaled/pre/post-index byte reads and GPR/SIMD memory
writeback are normalized. A finite register-transfer worklist over-approximates
candidate uses; selected uses require the full CFG proof.

The affine dependency graph is solved by iterative strongly-connected-component
decomposition and bounded root propagation over its dependency DAG. Zero-offset
cycles merge without enumerating paths. A varying pointer-induction component
gets distinct opaque before(program-point,register) values, like unknown
arithmetic results; these do not prove aliases across iterations or program
points. Subsequent explicit field offsets remain tracked. Resource exhaustion,
incomplete provenance and unknown classified-field uses reject the profile.
Every hot conjunction must prove that handle, skip, reach and the linked
drawing/hidden/alpha continuation refer to the same object. The global independent
skip-conjunction audit runs outside the candidate filter and rejects hidden or
changed hot branches. The parent boolean-OR propagation shape is independently
cross-checked as well, so shifting its source field cannot silently remove it.

The observed four uses are two hot decisions, one read-only reason at 0x40de10,
and parent propagation at 0x420b14. The latter reads parent+derived_handle,
ORs two requested mirror-ID sentinel tests, and writes child+derived_handle;
both addresses are proven through every CFG predecessor and the exact exported
updateSnapshot signature (entry parent X4, child X1). Its alpha read must share
the same parent. All propagation and diagnostic instructions remain unchanged.
No inline-count assumption selects this profile. A prior one-hot candidate was
insufficient for virtual-display captures because its physical backwards scan
missed the inline reached across an early-return epilogue; the final two-site
group addresses that omission while preserving the rest of the real function.
These are reviewed instruction/dataflow families; they do not promise recognition
of every future compiler transformation, stripped shared-library layout or ISA.

Additional stripped/plain compiler family
-----------------------------------------
The fourth actual executable has neither the exported internal C++ anchors of
the shared-library family nor the earlier indexed/direct getter prologues. Its
ordinary AArch64 getter and two near/far inline shapes are recognized by the same
uniquely linked diagnostic, derived fields, complete function/control-flow and
independent use audit. Observed evidence, not selectors:
  screenshot factory site 0x6cfaf0
  visibility decisions 0x376ad4 / 0x3c158c / 0x3c17e8 / 0x677158
  unchanged diagnostic 0x3b79d8 and parent propagation 0x3bdc60 -> 0x3bdc80
The split inline conjunction at 0x3c158c reaches its skip-bit test in a distant
block; both the object provenance and original reject/continuation destinations
must match. Scheduled loads and callee-saved register copies are checked by role,
not by searching only physically adjacent instruction sequences. The parent
boolean computed by predecessor CMP/BEQ paths must be proven true; its source is
entry parent X4+derived_handle and its destination entry child X1+derived_handle.
The alpha read must refer to that same parent. These inheritance/diagnostic
instructions remain intact. All four hot instructions form one owned group.

The factory proof follows the returned output object through its actual
shared_ptr/sret holder and initializer to its constructed vtable. The relocated
LayerFilter setter must consume the packed input, split stack/flags with the
expected shifts, and retain matching secure/protected state access. Equivalent
temporary registers and affine argument-address forms are accepted only after
the same role/dataflow proof. It does not claim to identify an allocator merely
from a nearby call. ELF SHT_RELR and Android RELR implicit relocations are now
decoded with entry/alignment/order/bitmap bounds, alongside RELA and APS2.

This native change handles SKIP filtering only. The newly observed vendor also
has independent secure-capture authorization and per-output black-overlay
behavior. Neither a successful matcher/probe nor active SKIP instructions prove
FLAG_SECURE screenshot/recording support; that Java/framework behavior requires
separate functional acceptance. The native engine does not clear source layer
secure flags or change application buffers to address those extra restrictions.

Mutation and rollback
---------------------
One-shot root enable/disable acquire the private mutation lock; policy-serve
holds that same lock for its lifetime and calls the same validated engine. Both
identify the single SurfaceFlinger PID and verify its start time, executable and load bias.
Every entire enclosing function must match the original on-disk executable,
except for that pipeline's allowed replacements. A mix of original and replaced
recording instructions reports partial_patch with active_known=false. It is not
reported as disabled and is not silently repaired. Foreign instruction/function
changes report external_change. Matching replacements without ownership report
foreign_patch; no mutation is attempted.

All current target threads are seized and interrupted, with repeated enumeration
until the set stabilizes. The normal stop budget is 2500 ms. Real signal stops
are detected before writing and the signal is preserved on detach. Fork/exit or
other unexpected events abort. Cleanup uses an additional global 500 ms budget.
PTRACE_O_EXITKILL is never enabled; tracer exit provides the kernel's final
automatic detach rather than killing SurfaceFlinger.

Linux arm64 PTRACE_POKETEXT writes sizeof(long)=8 bytes. The helper first reads
the aligned word, replaces only the selected 4-byte instruction and preserves
the other 4 bytes. Every target thread is stopped during that read-modify-write;
this is not a claim that ptrace provides a hardware atomic 4-byte store. The full
8-byte word is read back before resuming threads. A pipeline operation is one
transaction across all its sites. An error/deadline/readback failure restores and
verifies every attempted word in reverse order while stopped. A rollback failure
retains ownership and reports rollback_failed. Complete functions are checked
again after the write, before detach. Failure is explicit.
The arm64 ptrace/copy_to_user_page path handles instruction-cache synchronization;
there is no unsynchronized /proc/pid/mem write, remote dlopen, trampoline, or PAC/
BTI prologue modification.

Before applying a change, the helper atomically writes a root-owned mode-0600
ownership record to:
  /data/adb/enable_screenshot17fix/native-patch.state
It contains PID, process start time, build ID, original-ELF checksum, target
address and original/replacement instruction. The file has an integrity checksum.
The deployed screenshot Ownership structure, magic and file path remain byte
compatible with 2.0. Recording uses a separate ownership file at:
  /data/adb/enable_screenshot17fix/native-recording-patch.state
It records the same process/ELF identity and ALL ordered sites and words. A
three-site set still writes the original 152-byte OWNSFX3R format. Other audited
counts write the separately tagged 360-byte OWNSFX4R format (1..16 used entries,
unused entries zero). Reading accepts either integrity-checked private format,
normalizes the full ordered list, and requires exact count/site/word/identity
equality. The old structure, magic, checksum location and three-site payload
validation remain unchanged. Stale cleanup applies the same original-code and
previous-identity proof to both formats; it does not clear an unrecognized file.
When the selected implementation is a shared ELF, both pipelines instead write
the separately tagged 4488-byte OWNSFX5M format. It binds pipeline, PID/start,
selected module path/device/inode/load bias, build ID, complete-ELF checksum and
all ordered sites/words (unused entries must be zero). Exact current mapping and
full-function checks remain necessary before restore. A valid old executable
record is still readable but never grants ownership of a shared ELF. The new
format does not change the old 120/152/360-byte structures, magic or checksums;
original executable layouts continue writing the deployed formats. Wrong module,
pipeline, ASLR identity, file or site data never permits restoration.
Both pipelines share native.lock. A recording command never removes screenshot
ownership, and a screenshot command never removes recording ownership. A stale
record from a restarted process is not matching ownership and grants no restore
permission. Successful disable removes only its matching pipeline record.
The root directory and file are checked for ownership/type/permissions and symlink
substitution. This is an integrity check, not a cryptographic authentication scheme.
Closing restores only a matching owned replacement. Foreign or modified factory
bytes produce external_change/foreign_patch and are left untouched. A restarted
SurfaceFlinger is a new identity and is inspected afresh. Only disable on fully
validated current original code may clear a private, format/hash-valid stale
record whose previous PID/start identity is confirmed gone or reused. Same live
identity, active/mixed code, invalid state, unsupported layout or read errors
preserve the record. This cleanup never restores bytes in a different process.
On restoration, ownership is removed. A later cleanup error still re-observes memory so active reflects the
actual instruction, with active_known explicitly describing certainty.

Failure states include root_required, unsupported_elf, unsupported_unwind,
signature_not_found, signature_ambiguous, memory_read_denied, external_change,
foreign_patch, ptrace_denied, unexpected_stop, stop_timeout, threads_unstable,
write_verification_failed, rollback_failed and detach_failed. Errors do not change
SELinux policy or switch the device into global permissive mode.

Verification completed
----------------------
1. NDK build passed all enabled warnings and PIE/dynamic-dependency/load checks.
2. Offline inspect on the real device ELF found one semantic candidate.
3. Root probe/status on the running SF passed complete-function memory comparison.
4. Removing the instruction and changing the protected-buffer semantic guard both
   returned signature_not_found. Adding a second valid candidate returned
   signature_ambiguous. These were offline ELF fixtures, never running SF changes.
5. The parent/root agent, after design review, separately confirmed enable and
   ownership on the same SF PID. Functional screenshot/restore tests belong to the
   parent task's verification record; this subtask did not invoke enable/disable.

Primary implementation sources
------------------------------
AOSP screenshot-only output filter:
https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/services/surfaceflinger/ScreenCaptureOutput.cpp
LayerFilter includes and separate screenshot flag:
https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/services/surfaceflinger/common/include/common/LayerFilter.h
Mirror visibility getIsVisible and diagnostic:
https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/services/surfaceflinger/FrontEnd/LayerSnapshot.cpp
Ancestor skip inheritance:
https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/services/surfaceflinger/FrontEnd/LayerSnapshotBuilder.cpp
Android common kernel ptrace memory writes:
https://android.googlesource.com/kernel/common/+/5a28654cc0e616e129ea23d353d8bed5babb8b31/kernel/ptrace.c
arm64 copy_to_user_page / flush_ptrace_access cache synchronization:
https://android.googlesource.com/kernel/common/+/790c2f9d15b59/arch/arm64/mm/flush.c

3.0 offline validation (no device operation performed by the native subtask):
- Production Image decoder compiled as a Windows inspection-only host executable;
  the same source also passed NDK -Wall -Wextra -Werror PIE compilation.
- Real ELF: exactly one screenshot site and one complete three-site recording set.
- 17 ELF fixtures cover removal of each load, changed skip/reachability/hidden/alpha
  semantic guards, missing diagnostic string, duplicate valid implementation,
  dynamically shifted field offset and both directions of pipeline independence.
- Unicorn executes the exact boolean machine function in 1,536 configurations
  covering reachability, both flags, drawing, all three hidden flags, alpha/blur;
  all untouched conditions retain their original results. Sixteen exact inline
  branch tests cover both differently shaped clones before/after replacement.
- The production complete-function memory validator is exercised against all
  mixed masks, foreign site words and enclosing-function changes (18 cases).
- Seventeen ownership/lifecycle cases check legacy format/hash offsets, wrong
  magic/hash/count/site ordering, and all current-original/old-identity combinations.

3.1 offline validation (no device operation by this native subtask):
- Both real ELF binaries pass screenshot and recording inspection. Original
  3.0 fixtures and exact machine-code regressions continue to pass.
- 48 additional ELF fixtures cover each new visibility site, reach/hidden/vendor
  hidden/draw/alpha/blur guards, inheritance classification, factory virtual-base
  dataflow, missing diagnostics, independent capabilities and ambiguous copies.
  They also prove that an indexed profile with a derived small offset rejects
  an extra unclassified direct use, symmetrically with the direct-profile gate.
- Positive structural variants change the derived handle/hidden offsets, state
  slot, temporary registers and LayerStack source register without any selector.
  A copied, fully audited inline function produces five sites, exercising the
  variable-count contract rather than a fixed-three assumption.
- Exact new ARM64 code passes 6,144 complete boolean executions and 48 inline
  executions. NaN/negative alpha, all four hidden flags, radius/region blur and
  the vendor background-state condition preserve their original outcomes.
- 42 ownership/lifecycle cases retain both old layouts and validate every new
  count from 1..16 plus wrong magic/hash/count, unordered and dirty unused entries.
- Complete-function validation passes 18 old-device, 18 new-device and 46
  five-site mixed/foreign/enclosing-function cases. Broker state tests remain 64.
- Full evidence is verification/v310-native-verification.json. This proves the
  reviewed machine-code shapes, not arbitrary future compiler transformations.

3.2 offline validation and separate parent read-only probe:
- Three actual implementation ELFs pass both independent production matchers.
- All 65 old ELF fixtures are re-read and pass unchanged expected results;
  82 new fixtures check required factory/visibility semantics, moved fields and
  registers, independent capabilities, malformed relocations and unclassified
  shifted/chained/MOV-aliased/unscaled/writeback handle accesses. They include
  CFG edges skipping physical epilogues, reordered address definitions, changed
  inline conditions and mismatched parent/source/destination field offsets.
- 36 mapping/new-state cases cover actual 16 KiB PT_LOAD maps, ASLR, nonzero
  virtual/file-offset difference, duplicate/noexec/bad-offset mappings, epoch
  identity changes and invalid 4488-byte ownership payloads.
- 29 production discovery/cache/identity cases use host-only proc/file adapters:
  two valid ELFs are ambiguous, pipelines may select different modules, warm
  operations reread only the chosen ELF, changed bytes/PID/start/maps/stamps
  invalidate selection, non-executable GPU/data map churn preserves a warm
  selection, and altered module ownership fields never match. A same-process
  host measurement was about 5262 ms cold / 6.4 ms warm; file/proc reads in that
  test are adapters, not a claim about device latency. The parent measured final
  device read-only probe 0.313 s / record-probe 7.781 s including command overhead.
- Complete-function checks pass 18 original, 18 second-executable, 12 new-shared
  and 46 five-site mixed/foreign cases. All three old ownership layouts pass the
  existing 42 checks; the unchanged broker state machine still passes 64 checks.
- Exact new ARM64 executes 22,544 function calls, with 567 changes exclusively
  in the isolated handle==1 && skip-bit0 rejection. Original ColorOS/Vivo exact
  ARM64 is re-run for 7,744 complete/inline executions. Other policy/visibility
  conditions retain their original results; helper-fixture boundaries are stated.
  The real inline function separately passes 35,136 full entry-to-return/fatal
  executions, covering all 312 instructions and the branch across its middle
  epilogue. Its 1097 changed full snapshot/cache states occur only when the
  isolated handle==1 && skip-bit0 exclusion and other visibility conditions allow
  a change; non-target state differences are zero. Total actual ARM64 executions
  across new getter, full inline and both legacy ELFs: 65,424.
- The parent stages the final binary and runs only probe/record-probe, confirming
  the selected shared path/device/inode/bias and complete original live functions.
  No enable/disable or functional capture result is implied by that receipt.
- Full evidence: verification/v320-native-verification.json. The old connected
  devices were not reconnected for a live 3.2 test; their evidence is offline ELF
  and exact ARM64 regression only.

3.3 offline validation and separate parent read-only probe:
- Four actual implementation ELFs pass both independent production matchers.
  All 147 immutable historical mutation fixtures and the three earlier actual
  ELFs reproduce their exact old results, site counts and site tuples: 300
  decoder invocations. Old scripts, fixtures and reports retain their hashes.
- 42 new screenshot-factory fixtures cover the returned-object/vtable/setter
  chain, packed stores, state/usage guards, RELR integrity and positive temporary
  register/argument-address forms. 38 new recording fixtures cover all four hot
  decisions, distant block targets, parent source/destination and computed true
  guards, unknown direct/affine uses, moved derived fields and ambiguity.
- Actual AArch64 execution passes 44,976 complete standalone getter/wrapper
  calls and 53,746 complete dual-hot inline function calls. Their original
  instruction coverage is 150/150 and 507/507 respectively. The fourth hot
  decision separately passes 44,784 complete visibility-state-slice executions,
  including its distant hidden/appearance continuation: 43/43 original slice
  instructions. This is not a whole-function claim for its large outer caller.
  Only the isolated handle==1 && skip-bit0 decision changes; non-target complete
  snapshot/cache state differences are zero. Explicit external helper/typed
  object fixtures are listed in the individual reports.
- Historical exact ARM64 is rerun below the new analysis directory for 65,424
  executions. Total old/new actual ARM64 execution count is 208,930. This does
  not replace device GPU/pixel acceptance or reconnect the previous devices.
- Production mapping/mapped-state checks pass 36 cases; discovery/cache/identity
  checks pass 29 cases. A host-only same-process measurement is approximately
  4097 ms cold / 6.7 ms warm, with proc/file adapters. This is not a device timing
  claim. Full-function memory validation passes 18/18/12/28 cases for the four
  actual ELFs; each run also retains the 42 legacy ownership payload checks.
- The parent staged the frozen target executable and ran read-only probe and
  record-probe. Both selected the executable with one screenshot/four recording
  sites, complete original live functions, inactive/unowned state and unchanged
  SurfaceFlinger/system_server/boot identities. That receipt implies no write,
  activation or successful captured-frame result.
- Evidence is collected below analysis/v33-native-device and indexed by
  verification/v330-native-verification.json. Live functional results and any
  later broker-only rebuild identity are recorded separately by the parent.

Evidence and disassembly: analysis/screenshot-v2-native/ and
analysis/screenrecord-v3-native/verification-v3.json. Host inspection/test files
are offline tooling and are not packaged into the Android root executable.
Live activation, functional recording A/B and restoration are the parent task's
separate device verification; offline evidence is not a live-test claim.
