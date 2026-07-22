# R8 rules for the release build.
#
# The app uses no reflection, no serialization and no dynamic class loading — grepping for
# Class.forName, getMethod and newInstance returns nothing — so almost nothing here needs a keep
# rule. Everything reachable is reachable statically, and AGP already generates keeps for the
# manifest's activities, services, receivers and activity-aliases.

# --- Diagnosability -----------------------------------------------------------------------------
#
# CrashLog exists so an unattended failure can be read later: it writes the last crash to a file and
# Settings shows it with a Copy button, because ADB is rarely attached here. An obfuscated stack
# trace defeats that entirely, and the mapping needed to undo it is written to
# app/build/outputs/mapping/release/, which is gitignored and destroyed by every clean build — so a
# trace copied off a phone weeks later would be permanently unreadable.
#
# Shrinking is what was actually wanted from R8: a smaller APK, and dead code genuinely removed
# rather than merely unreachable, which is what finally strips the debug-gated event trace out of a
# release build. Renaming contributes nothing to that and costs the one diagnostic this app leans on
# hardest.
-dontobfuscate

# Belt and braces. -dontobfuscate already preserves these, but they are what make a trace point at a
# line rather than only a class, and an explicit rule survives someone re-enabling obfuscation.
-keepattributes SourceFile,LineNumberTable

# --- Notes for anyone changing this --------------------------------------------------------------
#
# If obfuscation is ever turned back on, archive app/build/outputs/mapping/release/mapping.txt beside
# every APK that gets installed, or CrashLog's output becomes noise — without it, retrace cannot
# help.
#
# Resource shrinking (isShrinkResources) is deliberately NOT enabled. Widget drawables reach the
# launcher as rasterised bitmaps rather than resource ids, and are referenced through R.drawable
# normally, so they ought to survive — but if that reasoning is ever wrong the failure is a widget
# rendering a blank icon in the launcher's process, where nothing of ours can observe it. That is the
# exact class of silent failure this project keeps having to instrument its way out of, and it is not
# worth a few hundred kilobytes.
