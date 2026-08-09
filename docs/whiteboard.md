# WHITEBOARD activity type

`WHITEBOARD` is Oyla's second activity type. It follows the normal content-to-history chain:

`Exercise → LessonTemplateItem → immutable SessionExercise snapshot → session runtime → WebSocket → history`.

## Content configuration

A whiteboard exercise has a title and instruction plus `WhiteboardExerciseConfig`: optional IMAGE background, initial child drawing permission, a server-validated palette of 4–6 distinct values from `BLACK`, `BLUE`, `GREEN`, `RED`, `ORANGE`, `PURPLE`, default colour, `THIN`/`MEDIUM`/`THICK` brush, eraser and specialist-clear flags. Background assets use the same centre/system accessibility predicate as all media and are copied into the immutable snapshot URL/reference.

## Realtime protocol

The existing `/ws/sessions/{sessionId}` channel carries `WHITEBOARD_STROKE_STARTED`, `WHITEBOARD_STROKE_POINTS`, `WHITEBOARD_STROKE_COMPLETED`, `WHITEBOARD_CLEARED`, `WHITEBOARD_UNDONE`, and `WHITEBOARD_CHILD_PERMISSION_CHANGED`. Clients batch pointer points at most every 32 ms or 12 points. WebSocket frames remain limited to 32 KiB; a batch may contain at most 96 points and a stroke 2,048 points.

Coordinates are normalised (`0.0..1.0`) and server-validated. Pixel coordinates never cross the network. A completed stroke has a server-issued sequence number; renderer order is that sequence rather than client timestamps. There may be at most one transient stroke per device and 500 completed strokes per board.

## Authority, persistence and reconnect

PostgreSQL `whiteboard_states` stores child permission, board/clear revisions and next sequence. `whiteboard_strokes` stores one completed stroke with JSONB points, role, device, tool, colour, brush, soft-removal state and idempotency key. No point creates a database row. On reconnect, the normal `STATE_SNAPSHOT` event contains the current board state and ordered active strokes.

Clear increments `clear_revision`; previous strokes remain retained for history but are excluded from the current snapshot. Specialist undo removes the globally latest active stroke; child undo removes only that child's latest active stroke. Clear and child permission changes are specialist-only. The server validates every draw event against token, role, current session exercise, running status, device participant, tool/configuration, permission, bounds and payload limits.

## Completion and metrics

`WHITEBOARD` does not have correctness or answer attempts. The specialist explicitly completes it, then can move to the next template item. History reports duration plus total/child/specialist completed stroke counts. Strokes are retained after lesson completion for a later replay feature.

## Compatibility and limits

A lesson template containing WHITEBOARD can be assigned only when both managed tablets report app version 2.0 or later. The first Android implementation uses Compose Canvas with a finite logical drawing area, pen and eraser only; it intentionally excludes shapes, text, zoom/pan, image objects and multi-child collaboration.
