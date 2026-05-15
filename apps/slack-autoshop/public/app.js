// Pre-submit conflict check. Server is still source of truth on POST.
(function () {
  const form = document.getElementById('book-form');
  if (!form) return;
  const warn = document.getElementById('conflict-warning');
  const start = form.querySelector('input[name="start"]');
  const dur = form.querySelector('select[name="durationMinutes"]');
  if (!start || !dur || !warn) return;

  let timer = null;
  function check() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      if (!start.value) {
        warn.classList.add('hidden');
        return;
      }
      try {
        const url = '/api/conflict-check?start=' +
          encodeURIComponent(start.value) +
          '&durationMinutes=' + encodeURIComponent(dur.value);
        const r = await fetch(url, { credentials: 'same-origin' });
        const j = await r.json();
        if (j.ok) {
          warn.classList.add('hidden');
        } else if (j.conflicts && j.conflicts.length) {
          warn.classList.remove('hidden');
        }
      } catch (_) {
        // Silent — server will catch on submit.
      }
    }, 250);
  }
  start.addEventListener('change', check);
  start.addEventListener('input', check);
  dur.addEventListener('change', check);
})();
