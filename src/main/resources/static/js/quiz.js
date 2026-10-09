/*
 * Student quiz page.
 *
 * - Navigation is local and instant: students move freely between questions, answered or not.
 * - Every answer change is written to localStorage first, then sent to the server with a client
 *   sequence number. Failed sends stay queued and are retried every 5 seconds (design F2, A4).
 * - The countdown comes from the server and is resynced on every heartbeat (design A2).
 */
(function () {
    'use strict';

    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    const $ = (id) => document.getElementById(id);

    const state = {
        view: null,            // last AttemptView from the server
        questions: [],
        index: 0,
        answers: {},           // questionId -> {selected: number[], marked: bool, seq: number}
        pending: {},           // questionId -> true while not confirmed by the server
        deadline: 0,           // local ms timestamp
        lastSeq: 0,
        storageKey: null,
        finished: false,
        blocked: false,
        sending: false,
    };

    // ------------------------------------------------------------------ server calls

    async function api(method, path, body) {
        const headers = {'Accept': 'application/json'};
        if (csrfHeader) headers[csrfHeader] = csrfToken;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        const res = await fetch(path, {
            method, headers, credentials: 'same-origin',
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (res.status === 401 || res.status === 403) {
            const err = new Error('Your login has expired. Please log in again.');
            err.code = 'LOGGED_OUT';
            throw err;
        }
        let data = null;
        try { data = await res.json(); } catch (e) { /* empty body */ }
        if (!res.ok) {
            const err = new Error((data && data.message) || 'Request failed.');
            err.code = data && data.code;
            err.status = res.status;
            throw err;
        }
        return data;
    }

    // ------------------------------------------------------------------ local storage

    function saveLocal() {
        if (!state.storageKey) return;
        try {
            localStorage.setItem(state.storageKey, JSON.stringify({answers: state.answers, pending: state.pending}));
        } catch (e) { /* storage full or blocked: the server copy still exists */ }
    }

    function loadLocal() {
        try {
            const raw = localStorage.getItem(state.storageKey);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function clearLocal() {
        try { localStorage.removeItem(state.storageKey); } catch (e) { /* ignore */ }
    }

    // ------------------------------------------------------------------ loading

    async function load() {
        try {
            const view = await api('GET', '/api/attempt');
            applyView(view);
        } catch (e) {
            if (e.code === 'NO_ATTEMPT') {
                show('intro');
            } else {
                fail(e);
            }
        }
    }

    async function start() {
        $('start-button').disabled = true;
        try {
            const view = await api('POST', '/api/attempt/start');
            tryFullscreen();
            applyView(view);
        } catch (e) {
            $('start-button').disabled = false;
            banner(e.message);
        }
    }

    function applyView(view) {
        state.view = view;
        $('quiz-title').textContent = view.quizTitle;
        $('student-name').textContent = view.studentName + ' · ' + view.rollNo;
        state.storageKey = 'sitare-quiz:' + view.rollNo + ':' + view.quizTitle;

        if (view.status !== 'IN_PROGRESS') {
            state.finished = true;
            clearLocal();
            showResult(view);
            return;
        }

        state.questions = view.questions;
        state.answers = {};
        Object.entries(view.answers || {}).forEach(([qid, a]) => {
            state.answers[qid] = {selected: a.selected || [], marked: !!a.markedForReview, seq: a.clientSeq};
            state.lastSeq = Math.max(state.lastSeq, a.clientSeq);
        });

        // Answers chosen while offline (or before a crash) that the server never confirmed win if they are newer.
        const local = loadLocal();
        if (local && local.answers) {
            Object.entries(local.answers).forEach(([qid, a]) => {
                const server = state.answers[qid];
                if (!server || a.seq > server.seq) {
                    state.answers[qid] = a;
                    state.pending[qid] = true;
                    state.lastSeq = Math.max(state.lastSeq, a.seq);
                }
            });
        }

        setRemaining(view.remainingSeconds);
        show('quiz');
        $('timer').hidden = false;
        render();
        flush();
    }

    // ------------------------------------------------------------------ rendering

    function show(section) {
        ['intro', 'quiz', 'result'].forEach((id) => { $(id).hidden = id !== section; });
    }

    function render() {
        const q = state.questions[state.index];
        if (!q) return;
        const answer = state.answers[q.id] || {selected: [], marked: false};
        const multi = q.type === 'MULTI';

        $('q-number').textContent = 'Question ' + q.number + ' of ' + state.questions.length;
        $('q-marks').textContent = q.marks + (Number(q.marks) === 1 ? ' mark' : ' marks');
        $('q-type').textContent = multi ? 'Select all correct options' : 'Select one option';
        $('q-stem').textContent = q.stem;

        const list = $('q-options');
        list.replaceChildren();
        q.options.forEach((o, i) => {
            const label = document.createElement('label');
            label.className = 'option';
            const input = document.createElement('input');
            input.type = multi ? 'checkbox' : 'radio';
            input.name = 'q' + q.id;
            input.value = o.id;
            input.checked = answer.selected.includes(o.id);
            input.addEventListener('change', () => onChoose(q, o.id, input.checked));
            const letter = document.createElement('span');
            letter.className = 'letter';
            letter.textContent = String.fromCharCode(65 + i);
            const text = document.createElement('span');
            text.textContent = o.text;
            label.append(input, letter, text);
            if (input.checked) label.classList.add('chosen');
            list.append(label);
        });

        $('mark-button').textContent = answer.marked ? 'Unmark review' : 'Mark for review';
        $('mark-button').classList.toggle('active', answer.marked);
        $('prev-button').disabled = state.index === 0;
        $('next-button').disabled = state.index === state.questions.length - 1;
        renderPalette();
    }

    function renderPalette() {
        const palette = $('palette');
        palette.replaceChildren();
        let answered = 0, marked = 0;
        state.questions.forEach((q, i) => {
            const a = state.answers[q.id];
            const isAnswered = a && a.selected.length > 0;
            if (isAnswered) answered++;
            if (a && a.marked) marked++;
            const b = document.createElement('button');
            b.type = 'button';
            b.textContent = q.number;
            b.className = 'cell';
            if (isAnswered) b.classList.add('answered');
            if (a && a.marked) b.classList.add('marked');
            if (state.pending[q.id]) b.classList.add('saving');
            if (i === state.index) b.classList.add('current');
            b.setAttribute('aria-label', 'Question ' + q.number + (isAnswered ? ', answered' : ', not answered'));
            b.addEventListener('click', () => go(i));
            palette.append(b);
        });
        $('summary').textContent = answered + ' answered · ' + (state.questions.length - answered)
            + ' not answered · ' + marked + ' marked';
        const pendingCount = Object.keys(state.pending).length;
        $('save-status').textContent = pendingCount ? 'Saving ' + pendingCount + '…' : 'All answers saved';
        $('save-status').classList.toggle('pending', pendingCount > 0);
    }

    function go(i) {
        if (i < 0 || i >= state.questions.length) return;
        state.index = i;
        render();
        window.scrollTo(0, 0);
    }

    // ------------------------------------------------------------------ answering

    function nextSeq() {
        state.lastSeq = Math.max(state.lastSeq + 1, Date.now());
        return state.lastSeq;
    }

    function onChoose(q, optionId, checked) {
        if (state.finished || state.blocked) return;
        const a = state.answers[q.id] || {selected: [], marked: false, seq: 0};
        let selected;
        if (q.type === 'MULTI') {
            selected = checked ? [...new Set([...a.selected, optionId])] : a.selected.filter((x) => x !== optionId);
        } else {
            selected = [optionId];
        }
        record(q.id, selected, a.marked);
    }

    function record(qid, selected, marked) {
        state.answers[qid] = {selected, marked, seq: nextSeq()};
        state.pending[qid] = true;
        saveLocal();
        render();
        flush();
    }

    async function flush() {
        if (state.sending || state.finished || state.blocked) return;
        const ids = Object.keys(state.pending);
        if (!ids.length) return;
        state.sending = true;
        try {
            for (const qid of ids) {
                const a = state.answers[qid];
                const sentSeq = a.seq;
                await api('PUT', '/api/attempt/answers/' + qid, {
                    selectedOptionIds: a.selected, markedForReview: a.marked, clientSeq: sentSeq,
                });
                if (state.answers[qid].seq === sentSeq) {
                    delete state.pending[qid];
                }
            }
            saveLocal();
        } catch (e) {
            handleServerError(e);
        } finally {
            state.sending = false;
            renderPalette();
        }
        if (Object.keys(state.pending).length && !state.finished && !state.blocked) {
            setTimeout(flush, 5000);
        }
    }

    function handleServerError(e) {
        if (e.code === 'TIME_UP' || e.code === 'CLOSED') {
            reloadFinished();
        } else if (e.code === 'SESSION_REPLACED' || e.code === 'LOGGED_OUT') {
            state.blocked = true;
            banner(e.message);
            $('quiz').hidden = true;
        } else if (e.code) {
            banner(e.message);
        } else {
            // Network error: keep the answers queued and retry quietly.
            $('save-status').textContent = 'Offline: answers kept on this laptop, retrying…';
        }
    }

    async function submit() {
        const unanswered = state.questions.filter((q) => !(state.answers[q.id] && state.answers[q.id].selected.length)).length;
        const msg = unanswered
            ? 'You have ' + unanswered + ' unanswered question(s). Submit anyway? You cannot change answers after submitting.'
            : 'Submit your quiz? You cannot change answers after submitting.';
        if (!window.confirm(msg)) return;
        $('submit-button').disabled = true;
        await flush();
        if (Object.keys(state.pending).length) {
            banner('Some answers are not saved yet because the connection is down. Wait a moment and try again.');
            $('submit-button').disabled = false;
            return;
        }
        try {
            const view = await api('POST', '/api/attempt/submit');
            applyView(view);
        } catch (e) {
            $('submit-button').disabled = false;
            handleServerError(e);
        }
    }

    // ------------------------------------------------------------------ timer and heartbeat

    function setRemaining(seconds) {
        state.deadline = Date.now() + seconds * 1000;
        tick();
    }

    function tick() {
        if (state.finished) return;
        const left = Math.max(0, Math.round((state.deadline - Date.now()) / 1000));
        const m = Math.floor(left / 60), s = left % 60;
        $('timer').textContent = String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
        $('timer').classList.toggle('low', left <= 120);
        if (left === 0 && state.view && !state.timeUpHandled) {
            state.timeUpHandled = true;
            flush().finally(() => setTimeout(reloadFinished, 6000));
        }
    }

    async function heartbeat() {
        if (state.finished || state.blocked || !state.view) return;
        try {
            const hb = await api('POST', '/api/attempt/heartbeat');
            if (hb.status !== 'IN_PROGRESS') {
                reloadFinished();
            } else {
                setRemaining(hb.remainingSeconds);
            }
        } catch (e) {
            if (e.code) handleServerError(e);
        }
    }

    async function reloadFinished() {
        try {
            applyView(await api('GET', '/api/attempt'));
        } catch (e) {
            setTimeout(reloadFinished, 5000);
        }
    }

    // ------------------------------------------------------------------ result

    function showResult(view) {
        $('timer').hidden = true;
        $('save-status').textContent = '';
        show('result');
        $('result-how').textContent = view.status === 'AUTO_SUBMITTED'
            ? 'Time ran out, so your saved answers were submitted automatically.'
            : 'Your answers have been submitted.';
        $('result-score').textContent = view.score == null ? '—' : view.score + ' / ' + view.maxScore;

        const review = $('review');
        review.replaceChildren();
        if (!view.answersShared) {
            const p = document.createElement('p');
            p.className = 'muted small';
            p.textContent = 'Correct answers will appear here when your teacher shares them.';
            review.append(p);
            return;
        }
        view.questions.forEach((q) => {
            const chosen = (view.answers[q.id] && view.answers[q.id].selected) || [];
            const correct = view.correctOptionIds[q.id] || [];
            const card = document.createElement('div');
            card.className = 'question-card';
            const stem = document.createElement('p');
            stem.className = 'stem';
            stem.textContent = q.number + '. ' + q.stem;
            const ol = document.createElement('ol');
            ol.className = 'options';
            q.options.forEach((o) => {
                const li = document.createElement('li');
                li.textContent = o.text + (chosen.includes(o.id) ? '  (your answer)' : '');
                if (correct.includes(o.id)) li.classList.add('correct');
                else if (chosen.includes(o.id)) li.classList.add('wrong');
                ol.append(li);
            });
            card.append(stem, ol);
            review.append(card);
        });
    }

    // ------------------------------------------------------------------ helpers

    function banner(text) {
        $('banner').textContent = text;
        $('banner').hidden = false;
    }

    function fail(e) {
        banner(e.message || 'Could not load the quiz. Refresh the page to try again.');
        if (e.code === 'SESSION_REPLACED' || e.code === 'LOGGED_OUT') state.blocked = true;
    }

    function tryFullscreen() {
        const el = document.documentElement;
        if (el.requestFullscreen && !document.fullscreenElement) {
            el.requestFullscreen().catch(() => { /* not allowed: fine for now */ });
        }
    }

    // ------------------------------------------------------------------ wiring

    $('start-button').addEventListener('click', start);
    $('prev-button').addEventListener('click', () => go(state.index - 1));
    $('next-button').addEventListener('click', () => go(state.index + 1));
    $('clear-button').addEventListener('click', () => {
        const q = state.questions[state.index];
        const a = state.answers[q.id] || {marked: false};
        record(q.id, [], a.marked);
    });
    $('mark-button').addEventListener('click', () => {
        const q = state.questions[state.index];
        const a = state.answers[q.id] || {selected: [], marked: false};
        record(q.id, a.selected, !a.marked);
    });
    $('submit-button').addEventListener('click', submit);
    document.addEventListener('keydown', (ev) => {
        if ($('quiz').hidden || ev.target.tagName === 'INPUT' && ev.target.type === 'text') return;
        if (ev.key === 'ArrowRight') go(state.index + 1);
        if (ev.key === 'ArrowLeft') go(state.index - 1);
    });
    window.addEventListener('online', flush);
    window.addEventListener('beforeunload', (ev) => {
        if (Object.keys(state.pending).length && !state.finished) {
            ev.preventDefault();
            ev.returnValue = '';
        }
    });

    setInterval(tick, 1000);
    setInterval(heartbeat, 15000);
    load();
})();
