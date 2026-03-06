# Lessons Learned

## Material Components require MaterialComponents theme
**Date:** 2026-03-05
**Issue:** App crashed when inflating `Chip`/`ChipGroup` because the app used `Theme.AppCompat.DayNight.NoActionBar`.
**Fix:** Changed theme to `Theme.MaterialComponents.DayNight.NoActionBar` in AndroidManifest.xml.
**Rule:** When adding Material Design components (Chip, ChipGroup, MaterialButton, etc.), always verify the app theme extends `Theme.MaterialComponents` or `Theme.Material3`. AppCompat themes lack the required attributes and will cause runtime inflation crashes.
