// JNI binding of libghostty-vt for io.github.totocastaldi.herdrdesktop.terminal.GhosttyNative.
// Coarse-grained calls only: write bytes (returning the automatic replies), take a screen
// snapshot, encode a key, a mouse event or a paste, and select text with the mouse. The layout of the snapshot array is documented in SnapshotDecoder.kt.
// Never logs: terminal content must not reach logcat.

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <ghostty/vt.h>

#define JNI_FN(name) Java_io_github_totocastaldi_herdrdesktop_terminal_GhosttyNative_##name

enum { SNAPSHOT_HEADER_INTS = 8, SNAPSHOT_CELL_FIXED_INTS = 4, MAX_GRAPHEME = 32 };
enum { NO_COLOR = -1 };
// Bit of the snapshot cell flags marking a selected cell (bits 16..17 hold the cell width).
enum { SNAPSHOT_SELECTED_BIT = 1 << 18 };
// Selection gesture event types, in the order of SelectionAction in Kotlin.
enum { SELECT_PRESS = 0, SELECT_DRAG = 1, SELECT_RELEASE = 2, SELECT_EVENTS = 3 };
// Repeat clicks (double click: word, triple click: line) within this time and distance.
#define REPEAT_CLICK_NS 500000000ULL
#define REPEAT_CLICK_PX 8.0

typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyKeyEncoder encoder;
  GhosttyKeyEvent event;
  GhosttyMouseEncoder mouse_encoder;
  GhosttyMouseEvent mouse_event;
  GhosttySelectionGesture gesture;
  GhosttySelectionGestureEvent select_events[SELECT_EVENTS];
  uint8_t *replies;
  size_t replies_len;
  size_t replies_cap;
  bool out_of_memory;
  jint *snapshot;      // last snapshot, kept to avoid an allocation per frame
  size_t snapshot_cap;
} Handle;

static void on_write_pty(GhosttyTerminal terminal, void *userdata, const uint8_t *data,
                         size_t len) {
  (void)terminal;
  Handle *h = (Handle *)userdata;
  if (h->out_of_memory || len == 0) return;
  if (h->replies_len + len > h->replies_cap) {
    size_t cap = h->replies_cap ? h->replies_cap : 256;
    while (cap < h->replies_len + len) cap *= 2;
    uint8_t *grown = realloc(h->replies, cap);
    if (!grown) {
      h->out_of_memory = true;
      return;
    }
    h->replies = grown;
    h->replies_cap = cap;
  }
  memcpy(h->replies + h->replies_len, data, len);
  h->replies_len += len;
}

static void destroy(Handle *h) {
  if (!h) return;
  for (int i = 0; i < SELECT_EVENTS; i++) ghostty_selection_gesture_event_free(h->select_events[i]);
  ghostty_selection_gesture_free(h->gesture, h->terminal);
  ghostty_mouse_event_free(h->mouse_event);
  ghostty_mouse_encoder_free(h->mouse_encoder);
  ghostty_key_event_free(h->event);
  ghostty_key_encoder_free(h->encoder);
  ghostty_render_state_row_cells_free(h->cells);
  ghostty_render_state_row_iterator_free(h->rows);
  ghostty_render_state_free(h->render);
  ghostty_terminal_free(h->terminal);
  free(h->replies);
  free(h->snapshot);
  free(h);
}

static jbyteArray to_byte_array(JNIEnv *env, const uint8_t *data, size_t len) {
  jbyteArray array = (*env)->NewByteArray(env, (jsize)len);
  if (array && len > 0) (*env)->SetByteArrayRegion(env, array, 0, (jsize)len, (const jbyte *)data);
  return array;
}

static jint rgb(GhosttyColorRgb c) { return (jint)((c.r << 16) | (c.g << 8) | c.b); }

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jclass cls, jint cols, jint rows,
                                              jlong scrollback_bytes) {
  (void)env;
  (void)cls;
  Handle *h = calloc(1, sizeof(Handle));
  if (!h) return 0;
  bool ok = ghostty_terminal_new(NULL, &h->terminal, (uint16_t)cols, (uint16_t)rows) ==
                GHOSTTY_SUCCESS &&
            ghostty_render_state_new(NULL, &h->render) == GHOSTTY_SUCCESS &&
            ghostty_render_state_row_iterator_new(NULL, &h->rows) == GHOSTTY_SUCCESS &&
            ghostty_render_state_row_cells_new(NULL, &h->cells) == GHOSTTY_SUCCESS &&
            ghostty_key_encoder_new(NULL, &h->encoder) == GHOSTTY_SUCCESS &&
            ghostty_key_event_new(NULL, &h->event) == GHOSTTY_SUCCESS &&
            ghostty_mouse_encoder_new(NULL, &h->mouse_encoder) == GHOSTTY_SUCCESS &&
            ghostty_mouse_event_new(NULL, &h->mouse_event) == GHOSTTY_SUCCESS &&
            ghostty_selection_gesture_new(NULL, &h->gesture) == GHOSTTY_SUCCESS &&
            ghostty_selection_gesture_event_new(NULL, &h->select_events[SELECT_PRESS],
                                                GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS) ==
                GHOSTTY_SUCCESS &&
            ghostty_selection_gesture_event_new(NULL, &h->select_events[SELECT_DRAG],
                                                GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG) ==
                GHOSTTY_SUCCESS &&
            ghostty_selection_gesture_event_new(NULL, &h->select_events[SELECT_RELEASE],
                                                GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE) ==
                GHOSTTY_SUCCESS;
  if (ok) {
    // Motion is reported only when the pointer reaches another cell, as xterm and Ghostty do.
    bool track_last_cell = true;
    uint64_t repeat_ns = REPEAT_CLICK_NS;
    double repeat_px = REPEAT_CLICK_PX;
    GhosttySelectionGestureEvent press = h->select_events[SELECT_PRESS];
    ok = ghostty_mouse_encoder_set(h->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL,
                                   &track_last_cell) == GHOSTTY_SUCCESS &&
         ghostty_selection_gesture_event_set(
             press, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS, &repeat_ns) ==
             GHOSTTY_SUCCESS &&
         ghostty_selection_gesture_event_set(
             press, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE, &repeat_px) ==
             GHOSTTY_SUCCESS;
  }
  if (ok) {
    size_t max_bytes = (size_t)scrollback_bytes;
    ok = ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, h) == GHOSTTY_SUCCESS &&
         ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                              (const void *)on_write_pty) == GHOSTTY_SUCCESS &&
         ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                              &max_bytes) == GHOSTTY_SUCCESS;
  }
  if (!ok) {
    destroy(h);
    return 0;
  }
  return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env;
  (void)cls;
  destroy((Handle *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeWrite)(JNIEnv *env, jclass cls, jlong handle,
                                                  jbyteArray data, jint offset, jint length) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  h->replies_len = 0;
  h->out_of_memory = false;
  jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
  if (!bytes) return NULL;
  ghostty_terminal_vt_write(h->terminal, (const uint8_t *)bytes + offset, (size_t)length);
  (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
  if (h->out_of_memory) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"),
                     "terminal replies");
    return NULL;
  }
  return to_byte_array(env, h->replies, h->replies_len);
}

JNIEXPORT jint JNICALL JNI_FN(nativeResize)(JNIEnv *env, jclass cls, jlong handle, jint cols,
                                             jint rows, jint cell_width, jint cell_height) {
  (void)env;
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  return (jint)ghostty_terminal_resize(h->terminal, (uint16_t)cols, (uint16_t)rows,
                                       (uint32_t)cell_width, (uint32_t)cell_height);
}

// Scrolls the viewport by delta rows (negative: back into the scrollback); delta 0 returns to the
// active area. Returns the libghostty-vt result.
JNIEXPORT jint JNICALL JNI_FN(nativeScroll)(JNIEnv *env, jclass cls, jlong handle, jint delta) {
  (void)env;
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  GhosttyTerminalScrollViewport behavior;
  memset(&behavior, 0, sizeof(behavior));
  if (delta == 0) {
    behavior.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM;
  } else {
    behavior.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA;
    behavior.value.delta = (intptr_t)delta;
  }
  return (jint)ghostty_terminal_scroll_viewport(h->terminal, behavior);
}

// Grows *buf to hold at least need ints. Returns false when out of memory.
static bool reserve(jint **buf, size_t *cap, size_t need) {
  if (need <= *cap) return true;
  size_t cap2 = *cap ? *cap : 1024;
  while (cap2 < need) cap2 *= 2;
  jint *grown = realloc(*buf, cap2 * sizeof(jint));
  if (!grown) return false;
  *buf = grown;
  *cap = cap2;
  return true;
}

// Writes the snapshot into h->snapshot. Returns its length in ints, -1 if the render state failed and
// -2 when out of memory.
static long build_snapshot(Handle *h) {
  if (ghostty_render_state_update(h->render, h->terminal) != GHOSTTY_SUCCESS) return -1;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(h->render, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(h->render, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(h->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(h->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor);

  size_t cap = h->snapshot_cap;
  size_t len = SNAPSHOT_HEADER_INTS;
  jint *buf = h->snapshot;
  if (!reserve(&buf, &cap, len + (size_t)cols * rows * (SNAPSHOT_CELL_FIXED_INTS + 1))) goto oom;
  buf[0] = cols;
  buf[1] = rows;
  buf[2] = rgb(colors.foreground);
  buf[3] = rgb(colors.background);
  buf[4] = cursor.visible && cursor.viewport_has_value ? 1 : 0;
  buf[5] = cursor.viewport_has_value ? cursor.viewport_x : -1;
  buf[6] = cursor.viewport_has_value ? cursor.viewport_y : -1;
  buf[7] = (jint)cursor.visual_style;

  ghostty_render_state_get(h->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &h->rows);
  while (ghostty_render_state_row_iterator_next(h->rows)) {
    ghostty_render_state_row_get(h->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &h->cells);
    GhosttyRenderStateRowSelection selection = GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
    bool row_selected = ghostty_render_state_row_get(h->rows, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
                                                     &selection) == GHOSTTY_SUCCESS;
    int column = -1;
    while (ghostty_render_state_row_cells_next(h->cells)) {
      column++;
      uint32_t codepoints[MAX_GRAPHEME];
      GhosttyCodepointBuffer graphemes = {.ptr = codepoints, .cap = MAX_GRAPHEME};
      ghostty_render_state_row_cells_get(h->cells,
                                         GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                                         &graphemes);
      size_t count = graphemes.len < graphemes.cap ? graphemes.len : graphemes.cap;
      if (!reserve(&buf, &cap, len + SNAPSHOT_CELL_FIXED_INTS + count)) goto oom;

      GhosttyColorRgb color;
      jint fg = ghostty_render_state_row_cells_get(
                    h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &color) ==
                        GHOSTTY_SUCCESS
                    ? rgb(color)
                    : NO_COLOR;
      jint bg = ghostty_render_state_row_cells_get(
                    h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &color) ==
                        GHOSTTY_SUCCESS
                    ? rgb(color)
                    : NO_COLOR;
      GhosttyStyle style = {0};
      ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                                         &style);
      GhosttyCell raw = 0;
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      if (ghostty_render_state_row_cells_get(h->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
                                             &raw) == GHOSTTY_SUCCESS) {
        ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      }

      buf[len++] = fg;
      buf[len++] = bg;
      jint flags = (jint)style.flags | ((jint)wide << 16);
      // A selected cell is drawn with its colors swapped, like Ghostty's default.
      if (row_selected && column >= selection.start_x && column <= selection.end_x) {
        flags = (flags ^ GHOSTTY_STYLE_FLAG_INVERSE) | SNAPSHOT_SELECTED_BIT;
      }
      buf[len++] = flags;
      buf[len++] = (jint)count;
      for (size_t i = 0; i < count; i++) buf[len++] = (jint)codepoints[i];
    }
  }

  h->snapshot = buf;
  h->snapshot_cap = cap;
  return (long)len;

oom:
  h->snapshot = buf;
  h->snapshot_cap = cap;
  return -2;
}

static void throw_oom(JNIEnv *env) {
  (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "snapshot");
}

JNIEXPORT jintArray JNICALL JNI_FN(nativeSnapshot)(JNIEnv *env, jclass cls, jlong handle) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  long len = build_snapshot(h);
  if (len == -2) throw_oom(env);
  if (len < 0) return NULL;
  jintArray result = (*env)->NewIntArray(env, (jsize)len);
  if (result) (*env)->SetIntArrayRegion(env, result, 0, (jsize)len, h->snapshot);
  return result;
}

// Copies the snapshot into out when it fits and returns its length; returns minus the length needed when
// out is too small, -1 if the render state failed. Lets the caller reuse one array across frames.
JNIEXPORT jint JNICALL JNI_FN(nativeSnapshotInto)(JNIEnv *env, jclass cls, jlong handle,
                                                   jintArray out) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  long len = build_snapshot(h);
  if (len == -2) {
    throw_oom(env);
    return -1;
  }
  if (len < 0) return -1;
  if ((*env)->GetArrayLength(env, out) < len) return (jint)-len;
  (*env)->SetIntArrayRegion(env, out, 0, (jsize)len, h->snapshot);
  return (jint)len;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncodeKey)(JNIEnv *env, jclass cls, jlong handle,
                                                      jint action, jint key, jint mods,
                                                      jint consumed_mods, jint unshifted,
                                                      jbyteArray utf8) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  ghostty_key_encoder_set_from_terminal(h->encoder, h->terminal);

  uint8_t text[64];
  size_t text_len = 0;
  if (utf8) {
    jsize n = (*env)->GetArrayLength(env, utf8);
    text_len = (size_t)n < sizeof(text) ? (size_t)n : sizeof(text);
    (*env)->GetByteArrayRegion(env, utf8, 0, (jsize)text_len, (jbyte *)text);
  }
  ghostty_key_event_set_action(h->event, (GhosttyKeyAction)action);
  ghostty_key_event_set_key(h->event, (GhosttyKey)key);
  ghostty_key_event_set_mods(h->event, (GhosttyMods)mods);
  ghostty_key_event_set_consumed_mods(h->event, (GhosttyMods)consumed_mods);
  ghostty_key_event_set_unshifted_codepoint(h->event, (uint32_t)unshifted);
  ghostty_key_event_set_utf8(h->event, (GhosttyString){.ptr = text, .len = text_len});

  uint8_t out[128];
  size_t written = 0;
  GhosttyResult result =
      ghostty_key_encoder_encode(h->encoder, h->event, out, sizeof(out), &written);
  ghostty_key_event_set_utf8(h->event, (GhosttyString){0});
  if (result != GHOSTTY_SUCCESS) written = 0;
  return to_byte_array(env, out, written);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeMouseTracking)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env;
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  bool tracking = false;
  ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  return tracking ? JNI_TRUE : JNI_FALSE;
}

// Encodes a mouse event with the terminal's tracking mode and format; button 0 is "no button".
// Positions are in pixels of the pane, with the grid starting at its top left corner.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncodeMouse)(JNIEnv *env, jclass cls, jlong handle,
                                                        jint action, jint button, jint mods,
                                                        jfloat x, jfloat y, jint width,
                                                        jint height, jint cell_width,
                                                        jint cell_height, jboolean any_pressed) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  ghostty_mouse_encoder_set_from_terminal(h->mouse_encoder, h->terminal);
  GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
  size.screen_width = (uint32_t)(width > 0 ? width : 1);
  size.screen_height = (uint32_t)(height > 0 ? height : 1);
  size.cell_width = (uint32_t)cell_width;
  size.cell_height = (uint32_t)cell_height;
  bool pressed = any_pressed == JNI_TRUE;
  size_t written = 0;
  uint8_t out[128];
  if (ghostty_mouse_encoder_set(h->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size) !=
          GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_set(h->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                                &pressed) != GHOSTTY_SUCCESS ||
      ghostty_mouse_event_set_action(h->mouse_event, (GhosttyMouseAction)action) !=
          GHOSTTY_SUCCESS) {
    return to_byte_array(env, out, 0);
  }
  if (button > 0) {
    ghostty_mouse_event_set_button(h->mouse_event, (GhosttyMouseButton)button);
  } else {
    ghostty_mouse_event_clear_button(h->mouse_event);
  }
  ghostty_mouse_event_set_mods(h->mouse_event, (GhosttyMods)mods);
  ghostty_mouse_event_set_position(h->mouse_event, (GhosttyMousePosition){.x = x, .y = y});
  if (ghostty_mouse_encoder_encode(h->mouse_encoder, h->mouse_event, out, sizeof(out), &written) !=
      GHOSTTY_SUCCESS) {
    written = 0;
  }
  return to_byte_array(env, out, written);
}

// Applies a selection gesture event at the cell column, row of the visible screen and pixel
// position x, y. A press clears the selection (a double or triple click selects a word or a line),
// a drag extends it. Returns 1 when the terminal's selection changed, 0 otherwise.
JNIEXPORT jint JNICALL JNI_FN(nativeSelect)(JNIEnv *env, jclass cls, jlong handle, jint type,
                                             jint column, jint row, jdouble x, jdouble y,
                                             jlong time_ns, jint columns, jint cell_width,
                                             jint screen_height) {
  (void)env;
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  if (type < 0 || type >= SELECT_EVENTS) return 0;
  GhosttySelectionGestureEvent event = h->select_events[type];
  GhosttyPoint point = {.tag = GHOSTTY_POINT_TAG_VIEWPORT,
                        .value = {.coordinate = {.x = (uint16_t)column, .y = (uint32_t)row}}};
  GhosttyGridRef ref = {0};
  bool has_ref = ghostty_terminal_grid_ref(h->terminal, point, &ref) == GHOSTTY_SUCCESS;
  if (!has_ref && type != SELECT_RELEASE) return 0;
  ghostty_selection_gesture_event_set(event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF,
                                      has_ref ? &ref : NULL);
  if (type != SELECT_RELEASE) {
    GhosttySurfacePosition position = {.x = x, .y = y};
    ghostty_selection_gesture_event_set(event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION,
                                        &position);
  }
  if (type == SELECT_PRESS) {
    uint64_t time = (uint64_t)time_ns;
    ghostty_selection_gesture_event_set(event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS, &time);
  }
  if (type == SELECT_DRAG) {
    GhosttySelectionGestureGeometry geometry = {
        .columns = (uint32_t)columns,
        .cell_width = (uint32_t)cell_width,
        .padding_left = 0,
        .screen_height = (uint32_t)(screen_height > 0 ? screen_height : 1)};
    ghostty_selection_gesture_event_set(event, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY,
                                        &geometry);
  }
  GhosttySelection selection = {0};
  GhosttyResult result = ghostty_selection_gesture_event(h->gesture, h->terminal, event, &selection);
  if (type == SELECT_PRESS) {
    ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_SELECTION,
                         result == GHOSTTY_SUCCESS ? &selection : NULL);
    return 1;
  }
  if (type == SELECT_DRAG && result == GHOSTTY_SUCCESS) {
    ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &selection);
    return 1;
  }
  return 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeClearSelection)(JNIEnv *env, jclass cls, jlong handle) {
  (void)env;
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  ghostty_selection_gesture_reset(h->gesture, h->terminal);
  ghostty_terminal_set(h->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
}

// The selected text as UTF-8, as Ghostty copies it (soft wraps joined, trailing blanks trimmed);
// NULL when nothing is selected.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeSelectionText)(JNIEnv *env, jclass cls, jlong handle) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  GhosttySelection selection = {0};
  if (ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &selection) !=
      GHOSTTY_SUCCESS) {
    return NULL;
  }
  GhosttyFormatter formatter = NULL;
  if (ghostty_formatter_terminal_new(NULL, &formatter, h->terminal) != GHOSTTY_SUCCESS) return NULL;
  GhosttyFormatterFormat emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  bool on = true;
  uint8_t *text = NULL;
  size_t len = 0;
  bool ok = ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_EMIT, &emit) == GHOSTTY_SUCCESS &&
            ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_UNWRAP, &on) == GHOSTTY_SUCCESS &&
            ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_TRIM, &on) == GHOSTTY_SUCCESS &&
            ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_SELECTION, &selection) ==
                GHOSTTY_SUCCESS &&
            ghostty_formatter_format_alloc(formatter, NULL, &text, &len) == GHOSTTY_SUCCESS;
  ghostty_formatter_free(formatter);
  jbyteArray result = ok ? to_byte_array(env, text, len) : NULL;
  if (text) ghostty_free(NULL, text, len);
  return result;
}

// Encodes pasted UTF-8 text for the program: unsafe control bytes become spaces, and the text is
// framed for bracketed paste when the program enabled it (mode 2004), otherwise newlines become
// carriage returns.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeEncodePaste)(JNIEnv *env, jclass cls, jlong handle,
                                                        jbyteArray utf8) {
  (void)cls;
  Handle *h = (Handle *)(intptr_t)handle;
  GhosttyTerminalModeConfig mode = {.mode = GHOSTTY_MODE_BRACKETED_PASTE, .value = false};
  ghostty_terminal_get(h->terminal, GHOSTTY_TERMINAL_DATA_MODE, &mode);
  jsize n = (*env)->GetArrayLength(env, utf8);
  char *data = malloc(n > 0 ? (size_t)n : 1);
  if (!data) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "paste");
    return NULL;
  }
  (*env)->GetByteArrayRegion(env, utf8, 0, n, (jbyte *)data);
  size_t needed = 0;
  ghostty_paste_encode(data, (size_t)n, mode.value, NULL, 0, &needed);
  uint8_t *out = malloc(needed > 0 ? needed : 1);
  size_t written = 0;
  if (out && ghostty_paste_encode(data, (size_t)n, mode.value, out, needed, &written) !=
                 GHOSTTY_SUCCESS) {
    written = 0;
  }
  jbyteArray result = out ? to_byte_array(env, out, written) : NULL;
  free(out);
  free(data);
  if (!result) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "paste");
  }
  return result;
}
