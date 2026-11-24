// Built instead of core/src/camera_raw.cpp when FILMENGINE_LIBRAW is OFF (a build without the
// CDDL-licensed LibRaw): DNG files still develop through dng.cpp; other camera RAW formats
// (NEF, CR2/CR3, ARW, RAF, ORF, RW2 ...) are refused with a message.
// New file in engine-port; not part of the Android app.
#include "camera_raw.h"

namespace film {

bool parse_camera_raw(const uint8_t *, size_t, RawImage &, std::string &error, int)
{
  error = "camera RAW (non-DNG) support was left out of this build (FILMENGINE_LIBRAW=OFF)";
  return false;
}

} // namespace film
