// Server pages are ordered by monotonically increasing event IDs.
class EventFeed {
  constructor() { this.cursor = 0; this.items = []; }
  accept(page) {
    if (!Array.isArray(page) || page.length > 200) throw new Error('INVALID_EVENTS');
    let previous = this.cursor;
    for (const event of page) {
      if (!Number.isSafeInteger(event.id) || event.id <= previous) throw new Error('INVALID_EVENTS');
      previous = event.id;
    }
    this.cursor = previous;
    this.items = [...this.items, ...page].slice(-1000);
    return this.items;
  }
}
module.exports = {EventFeed};
