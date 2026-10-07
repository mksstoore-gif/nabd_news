(() => {
  'use strict';
  if (window.__SWEEPX_ARABIC_LOADED__) return;
  window.__SWEEPX_ARABIC_LOADED__ = true;

  const JOB_KEY = 'sweepx_ar_job_v1';
  let archiveItems = [];
  let stopped = false;
  const q = id => document.getElementById(id);
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  function textHas(el, words) {
    const t = (el && (el.innerText || el.textContent) || '').trim().toLowerCase();
    return words.some(w => t.includes(w));
  }

  async function waitFor(getter, timeout = 12000, every = 250) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const v = getter();
      if (v) return v;
      await sleep(every);
    }
    return null;
  }

  function classify(t) {
    const tx = t.full_text || t.text || '';
    if (tx.startsWith('RT @') || t.retweeted_status_id_str) return 'retweet';
    if (t.in_reply_to_status_id_str) return 'reply';
    if (t.quoted_status_id_str) return 'quote';
    return 'original';
  }

  function parseArchive(text) {
    text = text.trim()
      .replace(/^window\.YTD\.tweets\.part\d+\s*=\s*/, '')
      .replace(/;\s*$/, '');
    const raw = JSON.parse(text);
    if (!Array.isArray(raw)) throw new Error('صيغة الملف غير معروفة');
    return raw.map(row => {
      const t = row.tweet || row;
      const id = String(t.id_str || t.id || '');
      return {
        id,
        cat: classify(t),
        text: t.full_text || t.text || '',
        date: t.created_at ? new Date(t.created_at) : null,
        likes: Number(t.favorite_count || 0)
      };
    }).filter(x => x.id);
  }

  function filtered() {
    const allow = {
      original: q('sx-original').checked,
      retweet: q('sx-retweet').checked,
      quote: q('sx-quote').checked,
      reply: q('sx-reply').checked
    };
    const from = q('sx-from').value ? new Date(q('sx-from').value + 'T00:00:00') : null;
    const to = q('sx-to').value ? new Date(q('sx-to').value + 'T23:59:59') : null;
    const keepLikes = Number(q('sx-keep-likes').value || 0);
    const keepWords = q('sx-keep-words').value
      .split(',').map(x => x.trim().toLowerCase()).filter(Boolean);

    return archiveItems.filter(x => {
      if (!allow[x.cat]) return false;
      if (from && x.date && x.date < from) return false;
      if (to && x.date && x.date > to) return false;
      if (keepLikes > 0 && x.likes >= keepLikes) return false;
      const low = x.text.toLowerCase();
      if (keepWords.some(w => low.includes(w))) return false;
      return true;
    });
  }

  function setStatus(message, good = false) {
    let box = q('sx-live');
    if (!box) {
      box = document.createElement('div');
      box.id = 'sx-live';
      box.dir = 'rtl';
      box.style.cssText =
        'position:fixed;left:10px;right:10px;top:10px;z-index:2147483647;' +
        'background:#0f1419;color:#fff;border:1px solid #536471;border-radius:12px;' +
        'padding:10px 12px;font:13px Arial;box-shadow:0 8px 25px #0008';
      document.documentElement.appendChild(box);
    }
    box.style.borderColor = good ? '#00ba7c' : '#536471';
    box.textContent = message;
  }

  function clearJob() {
    localStorage.removeItem(JOB_KEY);
  }

  function saveJob(job) {
    localStorage.setItem(JOB_KEY, JSON.stringify(job));
  }

  function loadJob() {
    try { return JSON.parse(localStorage.getItem(JOB_KEY) || 'null'); }
    catch (_) { return null; }
  }

  function statusUrl(id) {
    return 'https://x.com/i/web/status/' + encodeURIComponent(id);
  }

  async function doDelete() {
    const article = await waitFor(() => document.querySelector('article'), 15000);
    if (!article) return false;

    const caret = article.querySelector('[data-testid="caret"]') ||
      Array.from(article.querySelectorAll('button')).find(b =>
        textHas(b, ['more', 'المزيد', '更多', 'その他'])
      );
    if (!caret) return false;
    caret.click();

    const del = await waitFor(() =>
      Array.from(document.querySelectorAll('[role="menuitem"]')).find(el =>
        textHas(el, ['delete', 'حذف', '删除', '削除'])
      ), 6000);
    if (!del) return false;
    del.click();

    const confirm = await waitFor(() =>
      document.querySelector('[data-testid="confirmationSheetConfirm"]') ||
      Array.from(document.querySelectorAll('button')).find(b =>
        textHas(b, ['delete', 'حذف', '删除', '削除'])
      ), 6000);
    if (!confirm) return false;
    confirm.click();
    await sleep(1300);
    return true;
  }

  async function doUndoRepost() {
    const article = await waitFor(() => document.querySelector('article'), 15000);
    if (!article) return false;

    const btn = article.querySelector('[data-testid="unretweet"]') ||
      article.querySelector('[data-testid="retweet"]');
    if (!btn) return false;
    btn.click();

    const undo = await waitFor(() =>
      Array.from(document.querySelectorAll('[role="menuitem"]')).find(el =>
        textHas(el, ['undo repost', 'undo retweet', 'تراجع عن إعادة النشر', 'إلغاء إعادة التغريد'])
      ), 5000);

    if (undo) {
      undo.click();
      await sleep(1000);
      return true;
    }

    return !!article.querySelector('[data-testid="retweet"]');
  }

  async function continueJob() {
    const job = loadJob();
    if (!job || !job.active || !Array.isArray(job.queue)) return;
    if (job.index >= job.queue.length) {
      setStatus('✅ انتهت عملية SweepX.', true);
      clearJob();
      setTimeout(() => location.href = 'https://x.com/home', 1400);
      return;
    }

    const item = job.queue[job.index];
    const wanted = statusUrl(item.id);
    const currentId = location.pathname.match(/status\/(\d+)/)?.[1];

    setStatus('🧹 العنصر ' + (job.index + 1) + ' من ' + job.queue.length +
      ' — يمكنك إيقاف العملية من زر SweepX.');

    if (currentId !== item.id) {
      location.href = wanted;
      return;
    }

    let ok = false;
    try {
      ok = item.cat === 'retweet' ? await doUndoRepost() : await doDelete();
    } catch (_) {
      ok = false;
    }

    const next = loadJob();
    if (!next || !next.active) return;
    next.index += 1;
    next.success = (next.success || 0) + (ok ? 1 : 0);
    next.failed = (next.failed || 0) + (ok ? 0 : 1);
    saveJob(next);

    setStatus((ok ? '✅ تم تنفيذ العنصر.' : '⚠️ تعذر تنفيذ العنصر وسيتم تجاوزه.') +
      ' النجاح: ' + next.success + ' — المتعذر: ' + next.failed, ok);

    await sleep(2500 + Math.random() * 1800);
    if (next.index >= next.queue.length) {
      setStatus('✅ انتهت العملية. نجاح: ' + next.success + ' — متعذر: ' + next.failed, true);
      clearJob();
      setTimeout(() => location.href = 'https://x.com/home', 1500);
    } else {
      location.href = statusUrl(next.queue[next.index].id);
    }
  }

  function updateCount() {
    if (!q('sx-count')) return;
    const t = filtered();
    q('sx-count').textContent = 'المحدد: ' + t.length + ' من أصل ' + archiveItems.length;
    q('sx-start').disabled = !archiveItems.length;
  }

  function makeUi() {
    if (q('sx-trigger')) return;

    const trigger = document.createElement('button');
    trigger.id = 'sx-trigger';
    trigger.textContent = '🧹 SweepX';
    trigger.style.cssText =
      'position:fixed;right:14px;bottom:20px;z-index:2147483646;border:0;border-radius:999px;' +
      'background:#1d9bf0;color:#fff;font-weight:800;padding:12px 16px;' +
      'box-shadow:0 6px 18px #0008;font-size:14px';
    document.documentElement.appendChild(trigger);

    const modal = document.createElement('div');
    modal.id = 'sx-modal';
    modal.dir = 'rtl';
    modal.style.cssText =
      'display:none;position:fixed;z-index:2147483647;inset:3vh 3vw auto 3vw;' +
      'max-width:620px;max-height:92vh;overflow:auto;margin:auto;background:#0f1419;' +
      'color:white;border:1px solid #273340;border-radius:18px;padding:16px;' +
      'box-shadow:0 20px 60px #000c;font-family:Arial,sans-serif';

    modal.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #273340;padding-bottom:10px">
        <div><b style="font-size:20px">SweepX عربي</b><div style="font-size:12px;color:#8b98a5">تنظيف منشورات حسابك من الجوال</div></div>
        <button id="sx-close" style="background:#273340;color:white;border:0;border-radius:50%;width:38px;height:38px">✕</button>
      </div>

      <div style="margin-top:12px;background:#111923;border:1px solid #273340;border-radius:12px;padding:12px">
        <b>1. استيراد أرشيف X</b>
        <button id="sx-pick" style="width:100%;margin-top:8px;padding:12px;background:#0f1419;color:white;border:1px dashed #536471;border-radius:10px">اختيار ملف tweets.js</button>
        <input id="sx-file" type="file" accept=".js,.json" style="display:none">
        <div id="sx-file-name" style="font-size:12px;color:#8b98a5;margin-top:7px">لا يوجد ملف</div>
      </div>

      <div style="margin-top:10px;background:#111923;border:1px solid #273340;border-radius:12px;padding:12px">
        <b>2. أنواع المحتوى</b>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px">
          <label><input id="sx-original" type="checkbox" checked> الأصلية</label>
          <label><input id="sx-retweet" type="checkbox" checked> إعادة النشر</label>
          <label><input id="sx-quote" type="checkbox" checked> الاقتباسات</label>
          <label><input id="sx-reply" type="checkbox" checked> الردود</label>
        </div>
      </div>

      <div style="margin-top:10px;background:#111923;border:1px solid #273340;border-radius:12px;padding:12px">
        <b>3. الفلاتر</b>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px">
          <label style="font-size:12px">من تاريخ<input id="sx-from" type="date" style="width:100%;box-sizing:border-box;padding:8px;margin-top:3px"></label>
          <label style="font-size:12px">إلى تاريخ<input id="sx-to" type="date" style="width:100%;box-sizing:border-box;padding:8px;margin-top:3px"></label>
        </div>
        <label style="display:block;font-size:12px;margin-top:8px">احتفظ بالمنشورات التي إعجاباتها ≥
          <input id="sx-keep-likes" type="number" min="0" value="0" style="width:100%;box-sizing:border-box;padding:8px;margin-top:3px">
        </label>
        <label style="display:block;font-size:12px;margin-top:8px">كلمات تريد الاحتفاظ بها
          <input id="sx-keep-words" type="text" placeholder="مهم, مثبت" style="width:100%;box-sizing:border-box;padding:8px;margin-top:3px">
        </label>
      </div>

      <label style="display:flex;gap:8px;margin-top:10px;background:#3a2d08;border:1px solid #8a6d12;border-radius:12px;padding:12px">
        <input id="sx-dry" type="checkbox" checked>
        <span><b>معاينة فقط</b><br><span style="font-size:12px">مفعلة افتراضيًا؛ لا تحذف شيئًا.</span></span>
      </label>

      <div id="sx-count" style="font-weight:700;margin:12px 0">المحدد: 0</div>
      <div style="display:flex;gap:8px">
        <button id="sx-start" disabled style="flex:1;padding:12px;background:#1d9bf0;color:white;border:0;border-radius:10px;font-weight:800">تشغيل</button>
        <button id="sx-cancel" style="padding:12px;background:#111923;color:#ff7a85;border:1px solid #f4212e;border-radius:10px;font-weight:800">إيقاف مهمة جارية</button>
      </div>
      <div id="sx-preview" style="margin-top:10px;max-height:180px;overflow:auto;background:#080b0f;border:1px solid #273340;border-radius:10px;padding:8px;font-size:11px"></div>
      <div style="margin-top:8px;font-size:11px;color:#8b98a5">التنفيذ الفعلي يتنقل بين منشورات حسابك ويستخدم أزرار X الظاهرة. لا يتم إرسال الأرشيف إلى خادم خارجي.</div>
    `;
    document.documentElement.appendChild(modal);

    trigger.onclick = () => modal.style.display = 'block';
    q('sx-close').onclick = () => modal.style.display = 'none';
    q('sx-pick').onclick = () => q('sx-file').click();

    q('sx-file').onchange = async e => {
      const f = e.target.files && e.target.files[0];
      if (!f) return;
      try {
        archiveItems = parseArchive(await f.text());
        q('sx-file-name').textContent = f.name + ' — ' + archiveItems.length + ' سجل';
        updateCount();
      } catch (err) {
        alert('تعذر قراءة الملف: ' + err.message);
      }
    };

    ['sx-original','sx-retweet','sx-quote','sx-reply','sx-from','sx-to','sx-keep-likes','sx-keep-words']
      .forEach(id => q(id).addEventListener('input', updateCount));

    q('sx-cancel').onclick = () => {
      clearJob();
      setStatus('⛔ تم إيقاف مهمة SweepX.');
      alert('تم إيقاف المهمة.');
    };

    q('sx-start').onclick = async () => {
      const targets = filtered();
      if (!targets.length) return alert('لا توجد عناصر مطابقة.');

      const dry = q('sx-dry').checked;
      if (dry) {
        q('sx-preview').innerHTML = targets.slice(0, 300).map((x, i) =>
          '<div style="padding:4px 0;border-bottom:1px solid #1f2937">' +
          (i + 1) + '. ' + x.cat + ' — ' + x.id + ' — ' +
          (x.text || '').slice(0, 90).replace(/[<>&]/g, '') + '</div>'
        ).join('') + (targets.length > 300 ? '<div>… وباقي ' + (targets.length - 300) + ' عنصر</div>' : '');
        return;
      }

      if (!confirm('سيتم حذف/إلغاء إعادة نشر ' + targets.length + ' عنصرًا من حسابك. العملية لا يمكن التراجع عنها. متابعة؟')) return;

      saveJob({
        active: true,
        index: 0,
        success: 0,
        failed: 0,
        queue: targets.map(x => ({id:x.id, cat:x.cat}))
      });
      location.href = statusUrl(targets[0].id);
    };
  }

  makeUi();
  setTimeout(continueJob, 900);
})();