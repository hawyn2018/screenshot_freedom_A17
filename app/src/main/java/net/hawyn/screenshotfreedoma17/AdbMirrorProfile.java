// SPDX-License-Identifier: AGPL-3.0-only
package net.hawyn.screenshotfreedoma17;

/** Classification at the post-authorization VirtualDisplayAdapter boundary. */
final class AdbMirrorProfile {
    static final int SHELL_UID = 2000;
    static final int ROOT_UID = 0;
    // This key cannot collide with an Android package. Root has no package in the
    // framework's static DisplayManager mirroring API.
    static final String ROOT_TOOL_KEY = "@adb-root-mirror";
    private static final int AUTO_MIRROR = 1 << 4;
    private static final int OWN_CONTENT_ONLY = 1 << 3;

    private AdbMirrorProfile() {}

    static boolean eligible(boolean verifiedAdapter, boolean mediaProjection, int ownerUid,
                            int flags, boolean virtualDevice) {
        // Do not require an initial surface: the SurfaceControl compatibility
        // bridge can create the mirror before attaching its encoder surface.
        return verifiedAdapter && !mediaProjection && !virtualDevice
                && (ownerUid == SHELL_UID || ownerUid == ROOT_UID)
                && (flags & AUTO_MIRROR) != 0 && (flags & OWN_CONTENT_ONLY) == 0;
    }
}
