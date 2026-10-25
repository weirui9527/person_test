#ifndef EINKMODE_H
#define EINKMODE_H

#include <QtGlobal>
#include <QByteArray>
#include <QSettings>
#include <QString>

// ============================================================================
// E-Ink display mode
// ============================================================================
// This fork of SpeedyNote is adapted for a 10.3" 1404x1872 e-paper (E-Ink)
// tablet running Android 7. E-paper refreshes slowly and retains a "ghost" of
// the previous frame, so the continuous, high-frame-rate effects that look
// smooth on LCD/OLED are actively harmful here:
//
//   - kinetic / fling scrolling (dozens of frames in a single gesture)
//   - QPropertyAnimation transitions (fades, slides, snaps, rotations)
//
// When E-Ink mode is enabled, callers replace those animations with instant
// state changes (duration 0) and skip fling altogether, trading motion polish
// for a clean, ghost-free image that is far easier to read on e-paper.
//
// Activation order (first match wins):
//   1. SPEEDYNOTE_EINK env var: "1"/"true"/"on"/"yes" -> on, "0"/"false" -> off
//   2. QSettings key display/einkMode (bool)
//   3. Compile-time default: ON when SPEEDYNOTE_EINK_DEFAULT is defined (the
//      Android build defines it in CMakeLists.txt), otherwise OFF. Desktop
//      builds are therefore unaffected unless explicitly enabled.
//
// The value is resolved once and cached for the whole session.
//
// Header-only (inline) on purpose: no extra source file and no CMakeLists
// entry are needed, and a function-local static in an inline function has a
// single shared instance across all translation units.
// ============================================================================

namespace EInkMode {

inline bool enabled()
{
    static const bool s_enabled = []() -> bool {
        const QByteArray env = qgetenv("SPEEDYNOTE_EINK");
        if (!env.isEmpty()) {
            const QByteArray v = env.toLower();
            return (v == "1" || v == "true" || v == "on" || v == "yes");
        }

        bool fallback = false;
#ifdef SPEEDYNOTE_EINK_DEFAULT
        fallback = true;
#endif
        // Explicit org/app, so this works even before QCoreApplication exists
        // (in practice every caller runs well after QApplication is created).
        const QSettings settings(QStringLiteral("SpeedyNote"), QStringLiteral("App"));
        return settings.value(QStringLiteral("display/einkMode"), fallback).toBool();
    }();
    return s_enabled;
}

// Returns 0 ms when E-Ink mode is on so the animation completes instantly,
// otherwise the caller's nominal duration. Wrap every setDuration(...) with it.
inline int anim(int nominalMs)
{
    return enabled() ? 0 : nominalMs;
}

} // namespace EInkMode

#endif // EINKMODE_H
