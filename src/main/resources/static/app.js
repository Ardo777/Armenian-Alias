/* ══════════════════════════════════════════════════════════════
   Հայկական Ալիաս — client
   ══════════════════════════════════════════════════════════════ */

(() => {
    'use strict';

    const $  = (sel, root = document) => root.querySelector(sel);
    const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

    const STORE_KEY = 'alias-session';
    const LEVEL_NAMES = { 1: 'Հեշտ', 2: 'Միջին', 3: 'Բարդ' };

    let socket = null;
    let state = null;
    let me = null;              // { code, playerId }
    let clockOffset = 0;        // serverNow - Date.now()
    let timerHandle = null;
    let selectedPlayerId = null;
    let lastWord = null;
    let lastPhase = null;
    let reconnectDelay = 500;
    let countingDown = false;

    /* ───────────────────────────── helpers ───────────────────────────── */

    function buzz(pattern) {
        if (navigator.vibrate) {
            try { navigator.vibrate(pattern); } catch { /* unsupported */ }
        }
    }

    let toastHandle = null;
    function toast(message, bad = false) {
        const el = $('#toast');
        el.textContent = message;
        el.classList.toggle('is-bad', bad);
        el.classList.add('is-on');
        clearTimeout(toastHandle);
        toastHandle = setTimeout(() => el.classList.remove('is-on'), 2600);
    }

    function show(screen) {
        $$('.screen').forEach(s => s.classList.toggle('is-active', s.dataset.screen === screen));
    }

    /** Stable per-name colour so avatars stay recognisable between renders. */
    function avatarStyle(name) {
        let hash = 0;
        for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)) % 360;
        return `background: linear-gradient(135deg, hsl(${hash} 70% 55%), hsl(${(hash + 48) % 360} 70% 45%))`;
    }

    function initials(name) {
        return (name || '?').trim().charAt(0).toUpperCase();
    }

    let pending = [];

    function send(payload) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(payload));
        } else if (socket && socket.readyState === WebSocket.CONNECTING) {
            // Tapping before the socket finishes opening shouldn't silently do nothing.
            pending.push(payload);
        } else {
            toast('Միացում սերվերին…', true);
        }
    }

    // sessionStorage, not localStorage: two tabs on one phone are two players, and a shared
    // key would make them overwrite each other's identity on the next reconnect.
    function saveSession(code, playerId) {
        me = { code, playerId };
        try { sessionStorage.setItem(STORE_KEY, JSON.stringify(me)); } catch { /* private mode */ }
    }

    function loadSession() {
        try { return JSON.parse(sessionStorage.getItem(STORE_KEY) || 'null'); } catch { return null; }
    }

    function clearSession() {
        me = null;
        try { sessionStorage.removeItem(STORE_KEY); } catch { /* private mode */ }
    }

    /** Tell the player the socket is down, so a dead tap is never a mystery. */
    function showLink(connected) {
        $('#link').classList.toggle('is-on', !connected);
    }

    /**
     * iOS Safari's height:100% is the toolbar-hidden viewport, which pushes the action
     * row underneath the toolbar. innerHeight is the part you can actually touch.
     */
    function syncViewportHeight() {
        document.documentElement.style.setProperty('--app-h', `${window.innerHeight}px`);
    }

    /* ──────────────────────────── connection ──────────────────────────── */

    function connect(onOpen) {
        const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
        socket = new WebSocket(`${protocol}://${location.host}/ws`);

        socket.addEventListener('open', () => {
            reconnectDelay = 500;
            showLink(true);
            if (onOpen) onOpen();
            const queued = pending;
            pending = [];
            queued.forEach(send);
        });

        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.type === 'state') {
                applyState(message.state);
            } else if (message.type === 'joined') {
                saveSession(message.code, message.playerId);
                history.replaceState(null, '', `?r=${message.code}`);
            } else if (message.type === 'error') {
                toast(message.message, true);
                buzz([40, 60, 40]);
            } else if (message.type === 'rejoinFailed') {
                clearSession();
                show('home');
            }
        });

        socket.addEventListener('close', () => {
            showLink(false);
            // Phones suspend sockets whenever the screen locks — always climb back in.
            setTimeout(() => {
                const session = me || loadSession();
                connect(() => {
                    if (session) send({ type: 'rejoin', code: session.code, playerId: session.playerId });
                });
            }, reconnectDelay);
            reconnectDelay = Math.min(reconnectDelay * 1.7, 6000);
        });
    }

    /* ─────────────────────────── state routing ─────────────────────────── */

    function applyState(next) {
        state = next;
        clockOffset = next.serverNow - Date.now();

        const phaseChanged = lastPhase !== next.phase;
        lastPhase = next.phase;

        switch (next.phase) {
            case 'LOBBY':        renderLobby();    show('lobby');    break;
            case 'SETUP':        renderSetup();    show('setup');    break;
            case 'READY':        renderReady();    show('ready');    break;
            case 'PLAYING':      renderPlay();     show('play');     break;
            case 'TURN_SUMMARY': renderSummary();  show('summary');  break;
            case 'FINISHED':     renderFinished(); show('finished'); break;
        }

        if (next.phase === 'PLAYING') {
            startTimerLoop();
        } else {
            stopTimerLoop();
            lastWord = null;
            releaseTapLock();
        }

        if (phaseChanged && next.phase === 'TURN_SUMMARY') {
            buzz([70, 50, 70, 50, 140]);
        }
    }

    /* ──────────────────────────────  lobby  ────────────────────────────── */

    function renderLobby() {
        $('#roomCode').textContent = state.code;
        $('#playerCount').textContent = state.players.length;

        const list = $('#lobbyPlayers');
        list.innerHTML = '';
        state.players.forEach(player => {
            const li = document.createElement('li');
            li.className = 'player-row';
            li.innerHTML = `
                <div class="avatar" style="${avatarStyle(player.name)}">${initials(player.name)}</div>
                <div class="player-name">${escapeHtml(player.name)}${player.id === state.youId ? ' <span class="hint">(դուք)</span>' : ''}</div>
                ${player.isAdmin ? '<span class="badge-admin">ԱԴՄԻՆ</span>' : ''}
                <span class="dot ${player.connected ? '' : 'off'}"></span>`;
            list.appendChild(li);
        });

        $('#btnToSetup').hidden = !state.isAdmin;
        $('#lobbyWait').hidden = state.isAdmin;
    }

    /* ──────────────────────────────  setup  ────────────────────────────── */

    function renderSetup() {
        const admin = state.isAdmin;
        const byId = Object.fromEntries(state.players.map(p => [p.id, p]));

        // Any broadcast re-renders this screen; don't yank the keyboard away mid-rename.
        const focused = document.activeElement;
        const typing = focused?.dataset?.teamId
            ? { teamId: focused.dataset.teamId, value: focused.value, caret: focused.selectionStart }
            : null;

        // unassigned players
        const unassigned = state.players.filter(p => !p.teamId);
        const row = $('#unassigned');
        row.innerHTML = '';
        if (unassigned.length === 0) {
            row.innerHTML = '<li class="chip-empty">Բոլորը բաշխված են ✓</li>';
        }
        unassigned.forEach(player => {
            const li = document.createElement('li');
            li.className = 'chip' + (selectedPlayerId === player.id ? ' is-pick' : '');
            li.innerHTML = `<span class="avatar" style="${avatarStyle(player.name)};width:22px;height:22px;border-radius:7px;font-size:11px">${initials(player.name)}</span>${escapeHtml(player.name)}`;
            li.onclick = () => {
                if (!admin) return toast('Միայն ադմինը կարող է թիմեր կազմել', true);
                selectedPlayerId = selectedPlayerId === player.id ? null : player.id;
                buzz(12);
                renderSetup();
            };
            row.appendChild(li);
        });

        // team cards
        const wrap = $('#teamsWrap');
        wrap.innerHTML = '';
        state.teams.forEach(team => {
            const card = document.createElement('div');
            card.className = 'team-card';
            card.style.setProperty('--from', team.colorFrom);
            card.style.setProperty('--to', team.colorTo);

            const head = document.createElement('div');
            head.className = 'team-head';

            const logo = document.createElement('div');
            logo.className = 'team-logo';
            logo.textContent = team.emoji;
            logo.title = 'Նոր լոգո';
            logo.onclick = () => {
                if (!admin) return;
                buzz(18);
                send({ type: 'rerollLogo', teamId: team.id });
            };

            const nameInput = document.createElement('input');
            nameInput.type = 'text';
            nameInput.className = 'team-name-input';
            nameInput.value = team.name;
            nameInput.maxLength = 24;
            nameInput.dataset.teamId = team.id;
            nameInput.disabled = !admin;
            nameInput.onchange = () => send({ type: 'renameTeam', teamId: team.id, name: nameInput.value });

            head.append(logo, nameInput);

            if (admin && state.teams.length > 2) {
                const del = document.createElement('button');
                del.className = 'team-del';
                del.textContent = '🗑';
                del.onclick = () => send({ type: 'removeTeam', teamId: team.id });
                head.appendChild(del);
            }

            const members = document.createElement('div');
            members.className = 'team-members';
            team.playerIds.forEach(id => {
                const player = byId[id];
                if (!player) return;
                const chip = document.createElement('button');
                chip.className = 'chip';
                chip.innerHTML = `${escapeHtml(player.name)} <span style="opacity:.5">✕</span>`;
                chip.onclick = () => {
                    if (!admin) return;
                    buzz(12);
                    send({ type: 'assign', playerId: id, teamId: null });
                };
                members.appendChild(chip);
            });

            if (admin) {
                const add = document.createElement('button');
                add.className = 'team-add';
                add.textContent = selectedPlayerId ? `+ ${byId[selectedPlayerId]?.name ?? ''}` : '+ Ավելացնել';
                add.onclick = () => {
                    if (!selectedPlayerId) return toast('Նախ ընտրեք խաղացողին վերևից', true);
                    send({ type: 'assign', playerId: selectedPlayerId, teamId: team.id });
                    selectedPlayerId = null;
                    buzz(18);
                };
                members.appendChild(add);
            }

            const note = document.createElement('div');
            note.className = 'hint';
            note.textContent = team.playerIds.length < 2
                ? `Պետք է ևս ${2 - team.playerIds.length} խաղացող`
                : `${team.playerIds.length} խաղացող ✓`;

            card.append(head, members, note);
            wrap.appendChild(card);
        });

        if (typing) {
            const again = wrap.querySelector(`input[data-team-id="${typing.teamId}"]`);
            if (again) {
                again.value = typing.value;
                again.focus();
                again.setSelectionRange(typing.caret, typing.caret);
            }
        }

        // settings controls
        const settings = state.settings;
        applySeg('roundSeconds', settings.roundSeconds);
        applySeg('skipsPerTurn', settings.skipsPerTurn >= 99 ? 99 : settings.skipsPerTurn);
        applySeg('winMode', settings.winMode);
        applySeg('targetScore', settings.targetScore);
        applySeg('totalRounds', settings.totalRounds);
        $$('.seg[data-setting="levels"] button').forEach(btn => {
            btn.classList.toggle('is-on', settings.levels.includes(Number(btn.dataset.value)));
        });
        $('#penaltyToggle').checked = settings.penaltyForWrong;
        $('[data-when="SCORE"]').hidden = settings.winMode !== 'SCORE';
        $('[data-when="ROUNDS"]').hidden = settings.winMode !== 'ROUNDS';

        $$('[data-screen="setup"] input, [data-screen="setup"] .seg button, #btnAddTeam, #btnRandomize')
            .forEach(el => { el.disabled = !admin; });
        $('#btnStart').hidden = !admin;
        $('#setupWait').hidden = admin;
        $('#btnAddTeam').hidden = !admin;
        $('#btnBackLobby').hidden = !admin;
    }

    function applySeg(name, value) {
        $$(`.seg[data-setting="${name}"] button`).forEach(btn => {
            btn.classList.toggle('is-on', btn.dataset.value === String(value));
        });
    }

    /* ──────────────────────────────  ready  ────────────────────────────── */

    function activeTeam() {
        return state.teams.find(t => t.isActive) || state.teams[0];
    }

    function playerName(id) {
        return state.players.find(p => p.id === id)?.name ?? '—';
    }

    function paintTeamColors(el, team) {
        if (!team) return;
        el.style.setProperty('--from', team.colorFrom);
        el.style.setProperty('--to', team.colorTo);
    }

    function renderReady() {
        const team = activeTeam();
        const screen = $('[data-screen="ready"]');
        paintTeamColors(screen, team);
        paintTeamColors($('#readyLogo'), team);

        $('#readyRound').textContent = state.settings.winMode === 'ROUNDS'
            ? `Ռաունդ ${state.roundNumber} / ${state.settings.totalRounds}`
            : `Ռաունդ ${state.roundNumber}`;
        $('#readyLogo').textContent = team?.emoji ?? '🎭';
        $('#readyTeam').textContent = team?.name ?? '';
        $('#readyExplainer').textContent = playerName(state.explainerId);
        $('#readyGuessers').textContent = (team?.playerIds ?? [])
            .filter(id => id !== state.explainerId)
            .map(playerName)
            .join(', ') || '—';

        const mine = state.isExplainer;
        $('#btnStartTurn').hidden = !(mine || state.isAdmin);
        $('#readyWait').textContent = mine
            ? 'Հեռախոսը պահեք ձեր մոտ — բառը միայն ձեզ է երևում'
            : `Սպասում ենք՝ ${playerName(state.explainerId)}…`;

        renderMiniScores($('#readyScores'));
    }

    function renderMiniScores(container) {
        container.innerHTML = '';
        state.teams.forEach(team => {
            const el = document.createElement('div');
            el.className = 'mini-team' + (team.isActive ? ' is-active' : '');
            el.innerHTML = `<span>${team.emoji}</span><span>${escapeHtml(team.name)}</span><b>${team.score}</b>`;
            container.appendChild(el);
        });
    }

    /* ──────────────────────────────  play  ────────────────────────────── */

    function renderPlay() {
        const mine = state.isExplainer;
        $('#playExplainer').classList.toggle('is-on', mine);
        $('#playWatcher').classList.toggle('is-on', !mine);

        const team = activeTeam();
        const guessed = state.entries.filter(e => e.result === 'CORRECT').length;

        if (mine && state.card) {
            if (state.card.word !== lastWord) {
                lastWord = state.card.word;
                releaseTapLock();
                const card = $('#wordCard');
                card.classList.remove('flash-in');
                void card.offsetWidth;        // restart the animation
                card.classList.add('flash-in');
            }
            $('#wordMain').textContent = state.card.word;
            $('#wordLevel').textContent = LEVEL_NAMES[state.card.level] ?? '';

            const taboo = $('#tabooList');
            taboo.innerHTML = '';
            state.card.taboo.forEach(word => {
                const li = document.createElement('li');
                li.textContent = word;
                taboo.appendChild(li);
            });

            $('#turnPoints').textContent = state.turnScore;
            const unlimited = state.settings.skipsPerTurn >= 99;
            $('#skipCount').textContent = unlimited ? '∞' : state.skipsLeft;
            $('#btnSkip').disabled = !unlimited && state.skipsLeft <= 0;
        }

        if (!mine) {
            paintTeamColors($('#watchLogo'), team);
            $('#watchLogo').textContent = team?.emoji ?? '🎭';
            $('#watchExplainer').textContent = playerName(state.explainerId);
            const onMyTeam = state.players.find(p => p.id === state.youId)?.teamId === team?.id;
            $('#watchRole').textContent = onMyTeam
                ? 'Լսեք ուշադիր և գուշակեք! 🔥'
                : 'Հետևեք՝ արմատակից բառ չասի 👀';
            $('#watchPoints').textContent = guessed;
            renderMiniScores($('#watchScores'));
        }
    }

    /* ────────────────────────────── timer ────────────────────────────── */

    function startTimerLoop() {
        stopTimerLoop();
        tick();
        timerHandle = setInterval(tick, 250);
    }

    function stopTimerLoop() {
        if (timerHandle) clearInterval(timerHandle);
        timerHandle = null;
    }

    let lastBeepSecond = -1;
    function tick() {
        if (!state || state.phase !== 'PLAYING') return;
        const remainingMs = Math.max(0, state.turnEndsAt - (Date.now() + clockOffset));
        const seconds = Math.ceil(remainingMs / 1000);
        const total = state.settings.roundSeconds;

        $('#timerText').textContent = seconds;
        $('#watchTimer').textContent = seconds;

        const ring = $('#ringFg');
        const circumference = 2 * Math.PI * 52;
        ring.style.strokeDashoffset = circumference * (1 - remainingMs / (total * 1000));

        const low = seconds <= 10;
        $('.timer-ring').classList.toggle('is-low', low);
        if (low && seconds !== lastBeepSecond && seconds > 0 && state.isExplainer) {
            lastBeepSecond = seconds;
            buzz(18);
        }
        if (seconds > 10) lastBeepSecond = -1;
    }

    /* ───────────────────────────── summary ───────────────────────────── */

    const MARKS = { CORRECT: ['ok', '✓', '+1'], SKIP: ['skip', '↷', '0'], WRONG: ['wrong', '✕', '−1'] };

    function renderSummary() {
        const team = activeTeam();
        const hero = $('#summaryHero');
        paintTeamColors(hero, team);
        $('#summaryLogo').textContent = team?.emoji ?? '🎭';
        $('#summaryTeam').textContent = team?.name ?? '';
        $('#summaryDelta').textContent = (state.turnScore >= 0 ? '+' : '') + state.turnScore;

        const canEdit = state.isExplainer || state.isAdmin;
        $('#summaryHint').textContent = canEdit ? 'Սեղմեք՝ ուղղելու համար' : '';

        const list = $('#resultList');
        list.innerHTML = '';
        if (state.entries.length === 0) {
            list.innerHTML = '<li class="chip-empty">Այս ռաունդում բառ չխաղացվեց</li>';
        }
        state.entries.forEach(entry => {
            const [cls, glyph, points] = MARKS[entry.result] ?? MARKS.SKIP;
            const li = document.createElement('li');
            li.className = `result-row ${cls}`;
            const shownPoints = entry.result === 'WRONG' && !state.settings.penaltyForWrong ? '0' : points;
            li.innerHTML = `
                <div class="mark">${glyph}</div>
                <div class="w">${escapeHtml(entry.word ?? '')}</div>
                <div class="pts">${shownPoints}</div>`;
            if (canEdit) {
                // Cycle correct → wrong → skip so a mis-tap is one or two taps from fixed.
                li.onclick = () => {
                    const order = ['CORRECT', 'WRONG', 'SKIP'];
                    const next = order[(order.indexOf(entry.result) + 1) % order.length];
                    buzz(14);
                    send({ type: 'correct', index: entry.index, result: next });
                };
            }
            list.appendChild(li);
        });

        const canAdvance = state.isExplainer || state.isAdmin;
        $('#btnNext').hidden = !canAdvance;
        $('#summaryWait').textContent = canAdvance ? '' : `Սպասում ենք՝ ${playerName(state.explainerId)}…`;
    }

    /* ───────────────────────────── finished ───────────────────────────── */

    function renderFinished() {
        const ranked = [...state.teams].sort((a, b) => b.score - a.score);
        const top = ranked[0];
        const tie = ranked.length > 1 && ranked[1].score === top.score;

        $('#winTitle').textContent = tie ? 'Ոչ-ոքի! 🤝' : `${top.emoji} ${top.name}`;

        const podium = $('#podium');
        podium.innerHTML = '';
        ranked.forEach((team, index) => {
            const row = document.createElement('div');
            row.className = 'podium-row' + (index === 0 && !tie ? ' first' : '');
            paintTeamColors(row, team);
            row.innerHTML = `
                <div class="podium-rank">${index + 1}</div>
                <div class="podium-logo">${team.emoji}</div>
                <div class="podium-name">${escapeHtml(team.name)}</div>
                <div class="podium-score">${team.score}</div>`;
            podium.appendChild(row);
        });

        $('#btnPlayAgain').hidden = !state.isAdmin;
        $('#btnRestart').hidden = !state.isAdmin;
    }

    /* ─────────────────────────── escape hatch ─────────────────────────── */

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, ch =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
    }

    /* ───────────────────────────── bindings ───────────────────────────── */

    function bindUi() {
        const nameInput = $('#nameInput');
        nameInput.value = localStorage.getItem('alias-name') || '';

        $('#btnCreate').onclick = () => {
            const name = nameInput.value.trim();
            if (!name) return toast('Գրեք ձեր անունը', true);
            localStorage.setItem('alias-name', name);
            buzz(20);
            send({ type: 'create', name });
        };

        $('#btnJoin').onclick = () => {
            const name = nameInput.value.trim();
            const code = $('#codeInput').value.trim().toUpperCase();
            if (!name) return toast('Գրեք ձեր անունը', true);
            if (code.length !== 4) return toast('Կոդը 4 նիշ է', true);
            localStorage.setItem('alias-name', name);
            buzz(20);
            send({ type: 'join', code, name });
        };

        $('#codeInput').addEventListener('input', event => {
            event.target.value = event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
        });

        $('#btnShare').onclick = async () => {
            const url = `${location.origin}/?r=${state.code}`;
            const payload = { title: 'Հայկական Ալիաս', text: `Միացիր խաղին: կոդ ${state.code}`, url };
            try {
                if (navigator.share) await navigator.share(payload);
                else { await navigator.clipboard.writeText(url); toast('Հղումը պատճենվեց ✓'); }
            } catch { /* user dismissed the share sheet */ }
        };

        $('#btnLeave').onclick = () => {
            clearSession();
            history.replaceState(null, '', '/');
            location.reload();
        };

        $('#btnToSetup').onclick    = () => send({ type: 'toSetup' });
        $('#btnBackLobby').onclick  = () => send({ type: 'toLobby' });
        $('#btnAddTeam').onclick    = () => send({ type: 'addTeam' });
        $('#btnRandomize').onclick  = () => { buzz(25); selectedPlayerId = null; send({ type: 'randomize' }); };
        $('#btnStart').onclick      = () => { buzz([30, 40, 60]); send({ type: 'start' }); };
        $('#btnNext').onclick       = () => send({ type: 'next' });
        $('#btnPlayAgain').onclick  = () => send({ type: 'playAgain' });
        $('#btnRestart').onclick    = () => send({ type: 'restart' });

        $('#btnStartTurn').onclick = () => {
            if (countingDown) return;
            runCountdown(() => send({ type: 'startTurn' }));
        };

        $('#btnRight').onclick = () => answer('CORRECT', [40], 'hit-right');
        $('#btnWrong').onclick = () => answer('WRONG', [90], 'hit-wrong');
        $('#btnSkip').onclick  = () => answer('SKIP', [18], null);

        // tabs
        $$('.tab').forEach(tab => {
            tab.onclick = () => {
                $$('.tab').forEach(t => t.classList.toggle('is-active', t === tab));
                $$('.tab-panel').forEach(p => p.classList.toggle('is-active', p.dataset.panel === tab.dataset.tab));
            };
        });

        // settings segments
        $$('.seg').forEach(seg => {
            const name = seg.dataset.setting;
            seg.querySelectorAll('button').forEach(btn => {
                btn.onclick = () => {
                    if (!state?.isAdmin) return;
                    buzz(12);
                    if (name === 'levels') {
                        const value = Number(btn.dataset.value);
                        const levels = state.settings.levels.includes(value)
                            ? state.settings.levels.filter(l => l !== value)
                            : [...state.settings.levels, value];
                        if (levels.length === 0) return toast('Ընտրեք գոնե մեկ բարդություն', true);
                        send({ type: 'settings', settings: { levels } });
                    } else {
                        const raw = btn.dataset.value;
                        const value = name === 'winMode' ? raw : Number(raw);
                        send({ type: 'settings', settings: { [name]: value } });
                    }
                };
            });
        });

        $('#penaltyToggle').onchange = event => {
            if (!state?.isAdmin) return;
            send({ type: 'settings', settings: { penaltyForWrong: event.target.checked } });
        };
    }

    /**
     * Two quick taps before the next word arrives would score a word the player never saw,
     * so hold further taps until the card actually changes (released early by renderPlay).
     */
    let awaitingCard = false;
    let awaitTimer = null;

    function releaseTapLock() {
        awaitingCard = false;
        clearTimeout(awaitTimer);
    }

    function answer(result, pattern, flash) {
        if (!state || state.phase !== 'PLAYING' || !state.isExplainer) return;
        if (awaitingCard) return;
        if (result === 'SKIP' && state.settings.skipsPerTurn < 99 && state.skipsLeft <= 0) {
            toast('Բաց թողնելու հնարավորությունները սպառվել են', true);
            return;
        }
        buzz(pattern);
        if (flash) {
            const screen = $('.screen-play');
            screen.classList.remove('hit-right', 'hit-wrong');
            void screen.offsetWidth;
            screen.classList.add(flash);
        }
        awaitingCard = true;
        clearTimeout(awaitTimer);
        // Safety valve: never leave the buttons stuck if a reply goes missing.
        awaitTimer = setTimeout(() => { awaitingCard = false; }, 1200);
        send({ type: 'answer', result });
    }

    function runCountdown(done) {
        countingDown = true;
        const overlay = $('#countdown');
        const label = overlay.firstElementChild;
        let value = 3;
        overlay.classList.add('is-on');

        const step = () => {
            if (value === 0) {
                clearInterval(handle);
                overlay.classList.remove('is-on');
                countingDown = false;
                buzz([60, 40, 60]);
                done();
                return;
            }
            label.textContent = value;
            label.style.animation = 'none';
            void label.offsetWidth;
            label.style.animation = '';
            buzz(30);
            value -= 1;
        };
        step();
        const handle = setInterval(step, 800);
    }

    /* ────────────────────────────── bootstrap ────────────────────────────── */

    function boot() {
        syncViewportHeight();
        window.addEventListener('resize', syncViewportHeight);
        window.addEventListener('orientationchange', () => setTimeout(syncViewportHeight, 120));
        bindUi();

        const prefill = new URLSearchParams(location.search).get('r');
        if (prefill) $('#codeInput').value = prefill.toUpperCase();

        const session = loadSession();
        show('home');

        connect(() => {
            if (session && (!prefill || prefill.toUpperCase() === session.code)) {
                send({ type: 'rejoin', code: session.code, playerId: session.playerId });
            }
        });

        // Phones aggressively pause background tabs; nudge the socket when we come back.
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden && socket?.readyState === WebSocket.OPEN) {
                send({ type: 'ping' });
            }
        });
    }

    boot();
})();
