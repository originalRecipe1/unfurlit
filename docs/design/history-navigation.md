# Home and History navigation

The interaction follows Android's distinction between in-content paging and
system edge navigation:

- [Pager in Compose](https://developer.android.com/develop/ui/compose/layouts/pager)
  provides drag-following pages, snapping, animated programmatic navigation,
  and page-offset-driven visual effects.
- [Predictive Back progress](https://developer.android.com/develop/ui/compose/system/predictive-back-progress)
  provides gesture progress, completion, and cancellation for custom navigation.
- [Gesture navigation compatibility](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav)
  describes the system's use of screen edges. This implementation does not
  request gesture-exclusion areas or replace the system Back gesture on Home.

History is the page to the right of Home, matching the History toolbar button.
An in-content left swipe opens it; a right swipe returns. Pager position drives a subtle scale, rounded-corner,
and shadow treatment, so the pages move during the drag. The existing History
and Back buttons animate between the same pages.

From History, predictive Back uses a cross-activity-style scale and fade, with
a dimmed return screen underneath, vertical finger tracking and a spring-driven
completion. It responds to either system edge; cancellation restores History.
Button and in-content navigation retain the pager slide. Navigation state
changes after the page settles or Back completes, not halfway through a preview.

Home remains composed to retain typed input and scroll position. The viewer
is removed when fully offscreen to release playback. Opening History from the
viewer returns to that viewer; root paging into History is disabled on the
viewer so image, gallery, and player gestures retain their existing behavior.

History prepares its list at the top once fully hidden; cancelled Back gestures
and updates during a visit retain its position. Rows load through Paging 3 and
local thumbnails load on demand; see [History storage](../development.md#history-pagination-and-storage).

The transition uses the app's current Material colors and Compose animation
primitives. It adds no dependencies and uses the existing AndroidX versions.

Validation includes integration tests for both swipe directions, short and
diagonal swipes, movement before release, retained input, button navigation,
predictive Back completion/cancellation, and preview direction from both edges.
