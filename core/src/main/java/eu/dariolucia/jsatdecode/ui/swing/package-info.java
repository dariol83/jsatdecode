/**
 * Optional bounded Swing observation. A dedicated worker reads immutable
 * snapshots and computes display models; the EDT only displays those models.
 * The CLI loads this package only on explicit UI requests. Headless fallback
 * creates no window, timer or observer. See docs/swing-ui.md.
 */
package eu.dariolucia.jsatdecode.ui.swing;
