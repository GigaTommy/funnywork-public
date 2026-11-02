/** Keep keyboard-focused table controls and their focus ring inside the scroll viewport. */
export function revealTableFocus(event: FocusEvent): void {
  if (!(event.target instanceof HTMLElement) || !event.target.closest('.el-table')) return;
  const target = event.target;
  target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  const wrap = target.closest('.el-scrollbar__wrap');
  if (!(wrap instanceof HTMLElement)) return;
  const view = wrap.getBoundingClientRect(), control = target.getBoundingClientRect();
  if (control.right > view.right - 6) wrap.scrollLeft += control.right - view.right + 6;
  else if (control.left < view.left + 6) wrap.scrollLeft -= view.left + 6 - control.left;
}
